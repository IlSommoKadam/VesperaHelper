package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remote control channel for Windows / Android clients over ADB.
 * Clients write one line to {@code remote.req}; this bridge answers in
 * {@code remote.ack} and keeps {@code remote.state.json} updated.
 */
public final class RemoteBridge {
    private static final String TAG = "VesperaRemote";
    private static final String REQ = "remote.req";
    private static final String ACK = "remote.ack";
    private static final String STATE = "remote.state.json";
    private static final long POLL_MS = 400L;
    private static final long STATE_MS = 2_500L;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private final Context app;
    private final HandlerThread thread;
    private final Handler handler;
    private long lastStateAt;
    private String lastReqFingerprint = "";

    private RemoteBridge(Context context) {
        this.app = context.getApplicationContext();
        this.thread = new HandlerThread("vespera-remote-bridge");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public static void ensure(Context context) {
        if (context == null) return;
        if (!STARTED.compareAndSet(false, true)) return;
        RemoteBridge bridge = new RemoteBridge(context);
        bridge.handler.post(bridge.pollLoop);
        Log.i(TAG, "RemoteBridge started");
    }

    private final Runnable pollLoop = new Runnable() {
        @Override public void run() {
            try {
                drainRequest();
                long now = SystemClock.elapsedRealtime();
                if (now - lastStateAt >= STATE_MS) {
                    writeState();
                    lastStateAt = now;
                }
            } catch (Exception e) {
                Log.w(TAG, "poll error", e);
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    private File filesDir() {
        File dir = app.getExternalFilesDir(null);
        if (dir == null) dir = app.getFilesDir();
        return dir;
    }

    private void drainRequest() {
        File req = new File(filesDir(), REQ);
        if (!req.isFile() || req.length() <= 0) return;
        String body = readFile(req);
        if (body == null || body.trim().isEmpty()) return;
        String fingerprint = req.lastModified() + ":" + req.length() + ":" + body.hashCode();
        if (fingerprint.equals(lastReqFingerprint)) return;
        lastReqFingerprint = fingerprint;
        String line = firstLine(body);
        if (line.isEmpty()) return;
        // Consume request before long work so clients can write the next one.
        //noinspection ResultOfMethodCallIgnored
        req.delete();
        String ack;
        try {
            ack = handle(line);
        } catch (Exception e) {
            Log.w(TAG, "command failed: " + line, e);
            ack = "ERR|" + safe(e.getMessage());
        }
        writeFile(new File(filesDir(), ACK), ack == null ? "ERR|empty" : ack);
        writeState();
        lastStateAt = SystemClock.elapsedRealtime();
    }

    private String handle(String line) throws Exception {
        String[] p = line.split("\\|", -1);
        String head = p[0].trim().toLowerCase(java.util.Locale.US);
        if ("ping".equals(head)) {
            return "OK|pong|" + System.currentTimeMillis();
        }
        if ("get".equals(head) && p.length >= 2 && "state".equalsIgnoreCase(p[1].trim())) {
            writeState();
            return "OK|state";
        }
        if ("get".equals(head) && p.length >= 2 && "system".equalsIgnoreCase(p[1].trim())) {
            return "OK|system|" + systemJson().toString();
        }
        if ("get".equals(head) && p.length >= 2 && "telegram".equalsIgnoreCase(p[1].trim())) {
            return "OK|telegram|" + telegramJson(false).toString();
        }
        if ("set".equals(head) && p.length >= 2) {
            return handleSet(p);
        }
        if ("cmd".equals(head) && p.length >= 2) {
            return handleCmd(p);
        }
        return "ERR|unknown|" + line;
    }

    private String handleSet(String[] p) throws Exception {
        String kind = p[1].trim().toLowerCase(java.util.Locale.US);
        if ("system".equals(kind)) {
            if (p.length < 3) return "ERR|system|missing_json";
            String json = joinFrom(p, 2);
            applySystemJson(new JSONObject(json));
            PhotoSyncService.applySettings(app);
            return "OK|system";
        }
        if ("device".equals(kind)) {
            if (p.length < 4) return "ERR|device|ssid_bssid_required";
            String ssid = p[2];
            String bssid = p[3];
            int freq = 0;
            if (p.length >= 5) {
                try { freq = Integer.parseInt(p[4].trim()); } catch (NumberFormatException ignored) {}
            }
            VesperaDeviceStore.from(app).save(ssid, bssid, freq);
            return "OK|device|" + ssid + "|" + bssid;
        }
        if ("telegram".equals(kind)) {
            if (p.length < 3) return "ERR|telegram|missing_json";
            applyTelegramJson(new JSONObject(joinFrom(p, 2)));
            return "OK|telegram";
        }
        return "ERR|set|unknown|" + kind;
    }

    private String handleCmd(String[] p) {
        String domain = p[1].trim().toLowerCase(java.util.Locale.US);
        String action = p.length >= 3 ? p[2].trim().toLowerCase(java.util.Locale.US) : "";
        switch (domain) {
            case "wifi":
                return cmdWifi(action);
            case "singularity":
                return cmdSingularity(action);
            case "hd":
                return cmdHd(action, p);
            case "sync":
                return cmdSync(action);
            case "telescope":
                return cmdTelescope(action);
            default:
                return "ERR|cmd|unknown|" + domain;
        }
    }

    private String cmdWifi(String action) {
        if ("connect".equals(action)) {
            if (!VesperaDeviceStore.from(app).isConfigured()) {
                return "ERR|wifi|NEED_CONFIG";
            }
            Intent intent = new Intent(app, VesperaConnectionService.class)
                    .setAction(VesperaConnectionService.ACTION_CONNECT);
            app.startForegroundService(intent);
            return "OK|wifi|connect";
        }
        if ("disconnect".equals(action)) {
            Intent intent = new Intent(app, VesperaConnectionService.class)
                    .setAction(VesperaConnectionService.ACTION_DISCONNECT);
            app.startService(intent);
            InstrumentWatchdog.clearSnapshot();
            return "OK|wifi|disconnect";
        }
        if ("scan".equals(action)) {
            return wifiScan();
        }
        if ("refresh".equals(action)) {
            VesperaConnectionService.refreshConnectedNetwork(app);
            return "OK|wifi|refresh|" + VesperaConnectionService.getLastStatus();
        }
        return "ERR|wifi|unknown|" + action;
    }

    private String wifiScan() {
        WifiManager wifi = (WifiManager) app.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return "ERR|wifi|scan|no_wifi";
        try {
            //noinspection deprecation
            wifi.startScan();
        } catch (SecurityException e) {
            return "ERR|wifi|scan|" + safe(e.getMessage());
        }
        SystemClock.sleep(1200);
        List<ScanResult> results;
        try {
            //noinspection deprecation
            results = wifi.getScanResults();
        } catch (SecurityException e) {
            return "ERR|wifi|scan|" + safe(e.getMessage());
        }
        JSONArray arr = new JSONArray();
        if (results != null) {
            VesperaDeviceStore store = VesperaDeviceStore.from(app);
            for (ScanResult r : results) {
                if (r == null || !VesperaDeviceStore.isVesperaSsid(r.SSID)) continue;
                JSONObject o = new JSONObject();
                try {
                    o.put("ssid", r.SSID == null ? "" : r.SSID);
                    o.put("bssid", r.BSSID == null ? "" : r.BSSID);
                    o.put("level", r.level);
                    o.put("freq", r.frequency);
                    o.put("saved", store.matchesScan(r));
                    arr.put(o);
                } catch (Exception ignored) {
                }
            }
        }
        return "OK|wifi|scan|" + arr;
    }

    private String cmdSingularity(String action) {
        if ("restart".equals(action)) {
            VesperaConnectionService.requestSingularityRestart(app);
            return "OK|singularity|restart";
        }
        if ("start".equals(action)) {
            VesperaConnectionService.requestSingularityStart(app);
            return "OK|singularity|start";
        }
        if ("check".equals(action)) {
            SingularityDetector.Result r = SingularityDetector.check(app);
            return "OK|singularity|check|" + r.status.name() + "|" + safe(r.detail);
        }
        return "ERR|singularity|unknown|" + action;
    }

    private String cmdHd(String action, String[] p) {
        String spec = p.length >= 4 ? p[3].trim() : "";
        if ("list".equals(action)) {
            PhotoSyncService.refreshDisks(app);
            List<UsbDisk> disks = DaemonDisk.listDisks(app);
            JSONArray arr = new JSONArray();
            for (UsbDisk d : disks) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", d.name);
                    o.put("uuid", d.uuid);
                    o.put("label", d.label);
                    o.put("size", d.size);
                    o.put("fstype", d.fstype);
                    o.put("mounted", d.mounted);
                    o.put("mountPoint", d.mountPoint);
                    o.put("spec", d.spec());
                    arr.put(o);
                } catch (Exception ignored) {
                }
            }
            return "OK|hd|list|" + arr;
        }
        if ("mount".equals(action)) {
            PhotoSyncService.mount(app, spec);
            return "OK|hd|mount|" + spec;
        }
        if ("unmount".equals(action)) {
            PhotoSyncService.unmount(app);
            return "OK|hd|unmount";
        }
        if ("eject".equals(action)) {
            PhotoSyncService.eject(app, spec);
            return "OK|hd|eject|" + spec;
        }
        if ("wake".equals(action)) {
            String ack = DaemonDisk.wake(app);
            return "OK|hd|wake|" + firstLine(ack == null ? "" : ack);
        }
        if ("status".equals(action)) {
            DaemonDisk.MountStatus st = DaemonDisk.status(app);
            DaemonDisk.Space sp = DaemonDisk.photosSpace(app);
            return "OK|hd|status|" + (st.mounted ? "mounted" : "unmounted")
                    + "|" + st.path + "|" + (sp.known ? sp.usedPercent : -1);
        }
        return "ERR|hd|unknown|" + action;
    }

    private String cmdSync(String action) {
        if ("now".equals(action)) {
            PhotoSyncService.syncNow(app);
            return "OK|sync|now";
        }
        if ("resume".equals(action)) {
            PhotoSyncService.resumeSync(app);
            return "OK|sync|resume";
        }
        if ("pause".equals(action)) {
            PhotoSyncService.pauseSync(app);
            return "OK|sync|pause";
        }
        return "ERR|sync|unknown|" + action;
    }

    private String cmdTelescope(String action) {
        VesperaCommandClient.Command command;
        switch (action) {
            case "park":
                command = VesperaCommandClient.Command.PARK;
                break;
            case "stop":
                command = VesperaCommandClient.Command.STOP;
                break;
            case "resume":
                command = VesperaCommandClient.Command.RESUME;
                break;
            case "init":
                command = VesperaCommandClient.Command.INIT;
                break;
            case "shutdown":
                command = VesperaCommandClient.Command.SHUTDOWN;
                break;
            default:
                return "ERR|telescope|unknown|" + action;
        }
        Network network = VesperaConnectionService.getActiveNetwork();
        int port = InstrumentWatchdog.lastApiPort();
        if (port <= 0) port = 8082;
        VesperaLocationClient.Site site = null;
        if (command == VesperaCommandClient.Command.INIT
                || command == VesperaCommandClient.Command.RESUME) {
            site = VesperaLocationClient.fetch(network);
        }
        VesperaCommandClient.Result result =
                VesperaCommandClient.send("10.0.0.1", port, network, command, site);
        return (result.success ? "OK" : "ERR")
                + "|telescope|" + action + "|" + result.httpCode + "|" + safe(result.message);
    }

    private void applySystemJson(JSONObject o) {
        SystemSettingsStore store = SystemSettingsStore.from(app);
        SystemSettingsStore.Snapshot snap = store.snapshot();
        snap.photoSync = o.optBoolean("photoSync", snap.photoSync);
        snap.storageSync = o.optBoolean("storageSync", snap.storageSync);
        snap.resumeSync = o.optBoolean("resumeSync", snap.resumeSync);
        snap.hdMount = o.optBoolean("hdMount", snap.hdMount);
        snap.clockNtp = o.optBoolean("clockNtp", snap.clockNtp);
        snap.bootStart = o.optBoolean("bootStart", snap.bootStart);
        snap.wifiConnect = o.optBoolean("wifiConnect", snap.wifiConnect);
        snap.singularityStart = o.optBoolean("singularityStart", snap.singularityStart);
        snap.watchdog = o.optBoolean("watchdog", snap.watchdog);
        snap.ftpLocal = o.optBoolean("ftpLocal", snap.ftpLocal);
        snap.keepAlive = o.optBoolean("keepAlive", snap.keepAlive);
        snap.sunCheck = o.optBoolean("sunCheck", snap.sunCheck);
        snap.sunSync = o.optBoolean("sunSync", snap.sunSync);
        snap.sunTelescopeShutdown = o.optBoolean("sunTelescopeShutdown", snap.sunTelescopeShutdown);
        snap.sunHdShutdown = o.optBoolean("sunHdShutdown", snap.sunHdShutdown);
        snap.sunPiShutdown = o.optBoolean("sunPiShutdown", snap.sunPiShutdown);
        store.save(snap);
    }

    private void applyTelegramJson(JSONObject o) {
        TelegramSettingsStore store = TelegramSettingsStore.from(app);
        TelegramSettingsStore.Snapshot snap = store.snapshot();
        if (o.has("token")) snap.token = o.optString("token", snap.token);
        if (o.has("chatId")) snap.chatId = o.optString("chatId", snap.chatId);
        snap.initialized = o.optBoolean("initialized", snap.initialized);
        snap.shutdown = o.optBoolean("shutdown", snap.shutdown);
        snap.batteryOffMains = o.optBoolean("batteryOffMains", snap.batteryOffMains);
        snap.hdHigh = o.optBoolean("hdHigh", snap.hdHigh);
        snap.connected = o.optBoolean("connected", snap.connected);
        snap.obsStopped = o.optBoolean("obsStopped", snap.obsStopped);
        snap.obsStarted = o.optBoolean("obsStarted", snap.obsStarted);
        snap.error = o.optBoolean("error", snap.error);
        snap.lost = o.optBoolean("lost", snap.lost);
        snap.obsFinished = o.optBoolean("obsFinished", snap.obsFinished);
        snap.sunTooHigh = o.optBoolean("sunTooHigh", snap.sunTooHigh);
        snap.batteryLow = o.optBoolean("batteryLow", snap.batteryLow);
        snap.storageInternalHigh = o.optBoolean("storageInternalHigh", snap.storageInternalHigh);
        store.save(snap);
    }

    private void writeState() {
        try {
            JSONObject root = new JSONObject();
            root.put("updatedAt", System.currentTimeMillis());
            root.put("appVersion", BuildConfig.VERSION_NAME);
            root.put("versionCode", BuildConfig.VERSION_CODE);
            root.put("wifi", wifiJson());
            root.put("singularity", singularityJson());
            root.put("hd", hdJson());
            root.put("telescope", telescopeJson());
            root.put("system", systemJson());
            root.put("telegram", telegramJson(true));
            writeFile(new File(filesDir(), STATE), root.toString(2));
        } catch (Exception e) {
            Log.w(TAG, "writeState failed", e);
        }
    }

    private JSONObject wifiJson() throws Exception {
        JSONObject o = new JSONObject();
        VesperaDeviceStore store = VesperaDeviceStore.from(app);
        o.put("status", nullToEmpty(VesperaConnectionService.getLastStatus()));
        o.put("ssid", store.getSsid());
        o.put("bssid", store.getBssid());
        o.put("model", store.getModel());
        o.put("freq", store.getFrequencyMhz());
        o.put("configured", store.isConfigured());
        o.put("hasNetwork", VesperaConnectionService.getActiveNetwork() != null);
        return o;
    }

    private JSONObject singularityJson() throws Exception {
        JSONObject o = new JSONObject();
        InstrumentWatchdog.Snapshot snap = InstrumentWatchdog.lastSnapshot();
        if (snap == null) {
            o.put("detected", false);
            o.put("status", "IDLE");
            o.put("port", -1);
            o.put("message", "");
        } else {
            o.put("detected", snap.detected);
            o.put("status", snap.status);
            o.put("port", snap.port);
            o.put("message", snap.message);
        }
        o.put("apiPort", InstrumentWatchdog.lastApiPort());
        return o;
    }

    private JSONObject hdJson() throws Exception {
        JSONObject o = new JSONObject();
        DaemonDisk.MountStatus st = DaemonDisk.status(app);
        DaemonDisk.Space sp = DaemonDisk.photosSpace(app);
        o.put("mounted", st.mounted);
        o.put("path", st.path);
        o.put("uuid", st.uuid);
        o.put("label", st.label);
        o.put("device", st.device);
        o.put("raw", st.raw);
        o.put("spaceKnown", sp.known);
        o.put("spacePercent", sp.known ? sp.usedPercent : -1);
        o.put("spaceLabel", sp.label());
        JSONArray disks = new JSONArray();
        for (UsbDisk d : DaemonDisk.listDisks(app)) {
            JSONObject disk = new JSONObject();
            disk.put("name", d.name);
            disk.put("uuid", d.uuid);
            disk.put("label", d.label);
            disk.put("size", d.size);
            disk.put("fstype", d.fstype);
            disk.put("mounted", d.mounted);
            disk.put("spec", d.spec());
            disks.put(disk);
        }
        o.put("disks", disks);
        return o;
    }

    private JSONObject telescopeJson() throws Exception {
        JSONObject o = new JSONObject();
        Network network = VesperaConnectionService.getActiveNetwork();
        int port = InstrumentWatchdog.lastApiPort();
        if (port <= 0) port = 8082;
        o.put("apiPort", port);
        o.put("host", "10.0.0.1");
        if (network == null) {
            o.put("reachable", false);
            o.put("error", "no_network");
            return o;
        }
        VesperaStatusClient.Result result =
                VesperaStatusClient.fetchResult("10.0.0.1", port, network);
        VesperaStatusSnapshot snap = result.snapshot;
        if (snap == null) {
            o.put("reachable", false);
            o.put("error", result.error);
            return o;
        }
        o.put("reachable", true);
        o.put("error", "");
        o.put("endpoint", snap.endpoint);
        o.put("telescopeId", snap.telescopeId);
        o.put("model", snap.model);
        o.put("state", snap.state);
        o.put("initialized", snap.initialized);
        o.put("operationType", snap.operationType);
        o.put("observationStatus", snap.observationStatus);
        o.put("targetName", snap.targetName);
        o.put("stackingCount", snap.stackingCount);
        o.put("exposureMicroSec", snap.exposureMicroSec);
        o.put("gain", snap.gain);
        o.put("batteryPercent", snap.batteryPercent);
        o.put("batteryStatus", snap.batteryStatus);
        o.put("tracking", snap.tracking);
        o.put("motors", snap.motors);
        o.put("step", snap.step);
        o.put("coordinates", snap.coordinates);
        o.put("firmware", snap.firmware);
        o.put("filter", snap.filter);
        o.put("temperature", snap.temperature);
        o.put("focus", snap.focus);
        o.put("storage", snap.storage);
        o.put("storageUsedPercent", snap.storageUsedPercent);
        o.put("location", snap.location);
        o.put("canSignCommands", snap.canSignCommands());
        o.put("isObserving", snap.isObserving());
        o.put("isShuttingDown", snap.isShuttingDown());
        o.put("isSunTooHigh", snap.isSunTooHigh());
        o.put("canResumeObservation", snap.canResumeObservation());
        o.put("isAutoInitRunning", snap.isAutoInitRunning());
        return o;
    }

    private JSONObject systemJson() throws Exception {
        SystemSettingsStore store = SystemSettingsStore.from(app);
        SystemSettingsStore.Snapshot s = store.snapshot();
        JSONObject o = new JSONObject();
        o.put("photoSync", s.photoSync);
        o.put("storageSync", s.storageSync);
        o.put("resumeSync", s.resumeSync);
        o.put("hdMount", s.hdMount);
        o.put("clockNtp", s.clockNtp);
        o.put("bootStart", s.bootStart);
        o.put("wifiConnect", s.wifiConnect);
        o.put("singularityStart", s.singularityStart);
        o.put("watchdog", s.watchdog);
        o.put("ftpLocal", s.ftpLocal);
        o.put("keepAlive", s.keepAlive);
        o.put("sunCheck", s.sunCheck);
        o.put("sunSync", s.sunSync);
        o.put("sunTelescopeShutdown", s.sunTelescopeShutdown);
        o.put("sunHdShutdown", s.sunHdShutdown);
        o.put("sunPiShutdown", s.sunPiShutdown);
        o.put("sunTooHighResult", store.sunTooHighResult());
        o.put("sunTooHighAt", store.sunTooHighAt());
        return o;
    }

    private JSONObject telegramJson(boolean maskToken) throws Exception {
        TelegramSettingsStore store = TelegramSettingsStore.from(app);
        TelegramSettingsStore.Snapshot s = store.snapshot();
        JSONObject o = new JSONObject();
        o.put("configured", store.configured());
        o.put("token", maskToken ? (s.token.isEmpty() ? "" : "***") : s.token);
        o.put("chatId", s.chatId);
        o.put("initialized", s.initialized);
        o.put("shutdown", s.shutdown);
        o.put("batteryOffMains", s.batteryOffMains);
        o.put("hdHigh", s.hdHigh);
        o.put("connected", s.connected);
        o.put("obsStopped", s.obsStopped);
        o.put("obsStarted", s.obsStarted);
        o.put("error", s.error);
        o.put("lost", s.lost);
        o.put("obsFinished", s.obsFinished);
        o.put("sunTooHigh", s.sunTooHigh);
        o.put("batteryLow", s.batteryLow);
        o.put("storageInternalHigh", s.storageInternalHigh);
        o.put("lastOkAt", store.lastOkAt());
        o.put("lastError", store.lastError());
        return o;
    }

    private static String joinFrom(String[] p, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < p.length; i++) {
            if (i > start) sb.append('|');
            sb.append(p[i]);
        }
        return sb.toString();
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        String line = nl < 0 ? text : text.substring(0, nl);
        return line.replace("\r", "").trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }

    private static String readFile(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 256_000)];
            int n = in.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeFile(File file, String text) {
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(file);
            }
        } catch (IOException e) {
            Log.w(TAG, "write failed " + file.getName(), e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
