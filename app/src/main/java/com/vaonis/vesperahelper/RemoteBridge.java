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
    /** Rilettura FTP della memoria interna per i client (10 min; 2 min se mai letta). */
    private static final long STORAGE_REFRESH_MS = 10 * 60_000L;
    private static final long STORAGE_RETRY_MS = 2 * 60_000L;
    private static final java.util.concurrent.ExecutorService STORAGE_WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final AtomicBoolean STORAGE_BUSY = new AtomicBoolean(false);
    private long lastStorageProbeAt;

    private static final java.util.concurrent.ExecutorService SING_WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final AtomicBoolean SING_BUSY = new AtomicBoolean(false);

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
        NightPlanRunner.get(context);
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
                return cmdTelescope(action, p);
            case "plan":
                return cmdPlan(action, p);
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
            InstrumentWatchdog.recordBackgroundCheck(r);
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

    /** Piano notturno: {@code cmd|plan|load|{json}}, {@code cmd|plan|cancel}. */
    private String cmdPlan(String action, String[] p) {
        NightPlanRunner runner = NightPlanRunner.get(app);
        try {
            if ("load".equals(action)) {
                if (p.length < 4) return "ERR|plan|missing_json";
                return runner.load(joinFrom(p, 3));
            }
            if ("cancel".equals(action)) return runner.cancel();
        } catch (Exception e) {
            return "ERR|plan|" + safe(e.getMessage());
        }
        return "ERR|plan|unknown|" + action;
    }

    /** Osservazione da catalogo inviata dai client (multi-notte nuova o ripresa). */
    private String cmdObserve(boolean resume, String[] p) {
        if (p.length < 4) return "ERR|telescope|missing_arg";
        String body;
        if (resume) {
            body = SkyCatalog.resumeBody(new SkyCatalog.Session(p[3].trim(), "", 0));
        } else {
            body = joinFrom(p, 3);
        }
        Network network = VesperaConnectionService.getActiveNetwork();
        int port = InstrumentWatchdog.lastApiPort();
        if (port <= 0) port = 8082;
        VesperaLocationClient.Site site = resolveSite(network, null);
        VesperaCommandClient.Result result = VesperaCommandClient.observe(
                "10.0.0.1", port, network, site, body, resume);
        String action = resume ? "observeResume" : "observe";
        return (result.success ? "OK" : "ERR")
                + "|telescope|" + action + "|" + result.httpCode + "|" + safe(result.message);
    }

    /**
     * Posizione per init/resume/observe: prima quella inviata dal client
     * ({@code cmd|telescope|init|{"lat":..,"lon":..}}), poi quella di Sistema
     * dell'Helper, poi l'API del Vespera e infine l'ultimo status letto.
     */
    private VesperaLocationClient.Site resolveSite(Network network, String clientJson) {
        if (clientJson != null && !clientJson.trim().isEmpty()) {
            try {
                JSONObject o = new JSONObject(clientJson.trim());
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);
                if (!Double.isNaN(lat) && !Double.isNaN(lon)
                        && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
                        && (Math.abs(lat) >= 0.01 || Math.abs(lon) >= 0.01)) {
                    return new VesperaLocationClient.Site(lat, lon);
                }
            } catch (Exception ignored) {
            }
        }
        PhotoSyncStore store = PhotoSyncStore.from(app);
        if (store.hasSite()) {
            double lat = store.siteLat();
            double lon = store.siteLon();
            if (Math.abs(lat) >= 0.01 || Math.abs(lon) >= 0.01) {
                return new VesperaLocationClient.Site(lat, lon);
            }
        }
        VesperaLocationClient.Site site = VesperaLocationClient.fetch(network);
        if (site != null) return site;
        TelescopeStatusHub hub = TelescopeStatusHub.get();
        return VesperaLocationClient.fromSnapshot(hub == null ? null : hub.lastSnapshot());
    }

    private String cmdTelescope(String action, String[] p) {
        if ("observe".equals(action)) return cmdObserve(false, p);
        if ("observeresume".equals(action)) return cmdObserve(true, p);
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
            site = resolveSite(network, p.length >= 4 ? joinFrom(p, 3) : null);
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
        if (o.has("enabled")) {
            snap.setAllEvents(o.optBoolean("enabled"));
        }
        if (o.has("initialized")) snap.initialized = o.optBoolean("initialized");
        if (o.has("shutdown")) snap.shutdown = o.optBoolean("shutdown");
        if (o.has("batteryOffMains")) snap.batteryOffMains = o.optBoolean("batteryOffMains");
        if (o.has("hdHigh")) snap.hdHigh = o.optBoolean("hdHigh");
        if (o.has("connected")) snap.connected = o.optBoolean("connected");
        if (o.has("obsStopped")) snap.obsStopped = o.optBoolean("obsStopped");
        if (o.has("obsStarted")) snap.obsStarted = o.optBoolean("obsStarted");
        if (o.has("error")) snap.error = o.optBoolean("error");
        if (o.has("lost")) snap.lost = o.optBoolean("lost");
        if (o.has("obsFinished")) snap.obsFinished = o.optBoolean("obsFinished");
        if (o.has("sunTooHigh")) snap.sunTooHigh = o.optBoolean("sunTooHigh");
        if (o.has("batteryLow")) snap.batteryLow = o.optBoolean("batteryLow");
        if (o.has("storageInternalHigh")) snap.storageInternalHigh = o.optBoolean("storageInternalHigh");
        if (o.has("rainForecast")) snap.rainForecast = o.optBoolean("rainForecast");
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
            root.put("sync", syncJson());
            root.put("telescope", telescopeJson());
            root.put("system", systemJson());
            root.put("telegram", telegramJson(true));
            root.put("plan", NightPlanRunner.get(app).stateJson());
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
        // Segnale dello strumento salvato dall'ultima scansione (come la barra della UI Helper).
        ScanResult seen = null;
        try {
            WifiManager wifi = (WifiManager) app.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && store.isConfigured()) {
                //noinspection deprecation
                List<ScanResult> results = wifi.getScanResults();
                if (results != null) {
                    for (ScanResult r : results) {
                        if (store.matchesScan(r)) {
                            seen = r;
                            break;
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {
        }
        o.put("online", seen != null);
        if (seen != null) {
            o.put("level", seen.level);
            o.put("bars", WifiManager.calculateSignalLevel(seen.level, 5) + 1);
            o.put("scanFreq", seen.frequency);
        }
        return o;
    }

    /**
     * The activity watchdog only runs while VesperaHelper is in the foreground; on the Pi
     * Singularity usually is, so the bridge refreshes the check itself for remote clients.
     */
    private void refreshSingularityIfStale() {
        if (!VesperaConnectionService.STATUS_CONNECTED.equals(VesperaConnectionService.getLastStatus())) {
            return;
        }
        if (InstrumentWatchdog.snapshotAgeMs() < InstrumentWatchdog.INTERVAL_MS) return;
        if (!SING_BUSY.compareAndSet(false, true)) return;
        SING_WORKER.execute(() -> {
            try {
                InstrumentWatchdog.recordBackgroundCheck(SingularityDetector.check(app));
            } catch (Exception e) {
                Log.w(TAG, "singularity background check failed", e);
            } finally {
                SING_BUSY.set(false);
            }
        });
    }

    private JSONObject singularityJson() throws Exception {
        JSONObject o = new JSONObject();
        boolean wifiConnected = VesperaConnectionService.STATUS_CONNECTED
                .equals(VesperaConnectionService.getLastStatus());
        if (wifiConnected) refreshSingularityIfStale();
        InstrumentWatchdog.Snapshot snap = InstrumentWatchdog.lastSnapshot();
        if (wifiConnected && (snap == null || InstrumentWatchdog.STATUS_IDLE.equals(snap.status))) {
            o.put("detected", false);
            o.put("status", InstrumentWatchdog.STATUS_CHECKING);
            o.put("port", -1);
            o.put("message", "");
        } else if (snap == null || !wifiConnected) {
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

    /** Sync foto USER → HD: avanzamento corrente e coda file (Helper ≥ 0.8.37). */
    private JSONObject syncJson() throws Exception {
        JSONObject o = new JSONObject();
        PhotoSyncStore store = PhotoSyncStore.from(app);
        o.put("running", PhotoSyncService.isSyncRunning());
        o.put("paused", store.paused());
        o.put("pauseUntilSchedule", store.pauseUntilSchedule());
        long now = System.currentTimeMillis();
        boolean auto = SystemSettingsStore.from(app).photoSync() && !store.paused();
        o.put("autoSync", auto);
        o.put("nextAutoAt", auto ? store.nextAutoAt(now) : 0);
        o.put("lastAt", store.lastAt());
        o.put("lastOk", store.lastOk());
        o.put("lastError", nullToEmpty(store.lastError()));
        o.put("lastSync", nullToEmpty(PhotoSyncService.currentLastSync()));
        SyncProgress p = PhotoSyncService.currentProgress();
        if (p != null) {
            o.put("phase", nullToEmpty(p.phase));
            o.put("detail", nullToEmpty(p.detail));
            o.put("fileName", nullToEmpty(p.fileName));
            o.put("fileIndex", p.fileIndex);
            o.put("fileTotal", p.fileTotal);
            o.put("fileBytes", p.fileBytes);
            o.put("fileSize", p.fileSize);
            o.put("doneBytes", p.doneBytes);
            o.put("totalBytes", p.totalBytes);
            o.put("speedBps", p.speedBps);
            o.put("etaMs", p.etaMs);
            o.put("permille", p.permille());
            o.put("copied", p.copied);
            o.put("skipped", p.skipped);
            o.put("deleted", p.deleted);
            o.put("failed", p.failed);
        }
        SyncQueue.putJson(o);
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

    /** Posizione impostata in Sistema: i client la leggono come lat/lon del telescopio. */
    private void putHelperSite(JSONObject o) throws Exception {
        PhotoSyncStore store = PhotoSyncStore.from(app);
        if (!store.hasSite()) return;
        double lat = store.siteLat();
        double lon = store.siteLon();
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return;
        if (Math.abs(lat) < 0.01 && Math.abs(lon) < 0.01) return;
        o.put("latitude", lat);
        o.put("longitude", lon);
        o.put("siteLabel", store.siteLabel());
        o.put("siteSource", store.siteSource());
    }

    private JSONObject telescopeJson() throws Exception {
        JSONObject o = new JSONObject();
        Network network = VesperaConnectionService.getActiveNetwork();
        int port = InstrumentWatchdog.lastApiPort();
        if (port <= 0) port = 8082;
        o.put("apiPort", port);
        o.put("host", "10.0.0.1");
        putHelperSite(o);
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
        // Foto interne: il valore FTP (/USER) è quello mostrato dal pannello Helper.
        refreshStorageIfStale(network, port, snap.model);
        VesperaInternalStorage.Usage usage = VesperaInternalStorage.lastKnown();
        o.put("storage", usage != null ? usage.label : snap.storage);
        o.put("storageUsedPercent", usage != null ? usage.usedPercent : snap.storageUsedPercent);
        o.put("location", snap.location);
        o.put("instrumentError", snap.error);
        o.put("arm", snap.armState());
        o.put("lastTarget", VesperaLastTarget.hasTarget() ? VesperaLastTarget.label() : "");
        o.put("details", statusDetails(snap, usage));
        o.put("canSignCommands", snap.canSignCommands());
        o.put("isObserving", snap.isObserving());
        o.put("isShuttingDown", snap.isShuttingDown());
        o.put("isSunTooHigh", snap.isSunTooHigh());
        o.put("canResumeObservation", snap.canResumeObservation());
        o.put("isAutoInitRunning", snap.isAutoInitRunning());
        JSONObject store = SkyCatalog.captureStoreOf(snap.rawJson);
        if (store != null) o.put("captureStore", store);
        return o;
    }

    /**
     * Senza il pannello Helper aperto la memoria interna veniva letta solo durante il tracking:
     * la rilegge in background, così i client hanno sempre la % (pronta al giro di stato dopo).
     */
    private void refreshStorageIfStale(Network network, int apiPort, String model) {
        long now = SystemClock.elapsedRealtime();
        long at = VesperaInternalStorage.lastKnownAt();
        long maxAge = at > 0 ? STORAGE_REFRESH_MS : STORAGE_RETRY_MS;
        if (at > 0 && now - at < maxAge) return;
        if (now - lastStorageProbeAt < STORAGE_RETRY_MS && lastStorageProbeAt > 0) return;
        if (!STORAGE_BUSY.compareAndSet(false, true)) return;
        lastStorageProbeAt = now;
        STORAGE_WORKER.execute(() -> {
            try {
                VesperaInternalStorage.Usage usage =
                        VesperaInternalStorage.probe(network, "10.0.0.1", apiPort, model);
                if (usage != null) TelescopeStatusHub.ensure().ingestInternalStorage(usage.usedPercent);
            } catch (Exception e) {
                Log.w(TAG, "storage probe failed", e);
            } finally {
                STORAGE_BUSY.set(false);
            }
        });
    }

    /** Stesse righe del pannello Telescopio › Stato dell'Helper, pronte per i client. */
    private JSONArray statusDetails(VesperaStatusSnapshot snap, VesperaInternalStorage.Usage usage)
            throws Exception {
        JSONArray rows = new JSONArray();
        addDetail(rows, R.string.status_tab_field_model, snap.model);
        addDetail(rows, R.string.status_tab_field_state, snap.state);
        addDetail(rows, R.string.status_tab_field_initialized,
                app.getString(snap.initialized ? R.string.status_tab_yes : R.string.status_tab_no));
        String obs = "";
        if ("RUNNING".equals(snap.observationStatus) || snap.isTrackingAcquisition()) {
            obs = app.getString(R.string.status_tab_observation_running);
        } else if ("FINISHED".equals(snap.observationStatus)) {
            obs = app.getString(R.string.status_tab_observation_finished);
        } else if ("STOPPED".equals(snap.observationStatus) || snap.canResumeObservation()) {
            obs = app.getString(R.string.status_tab_observation_stopped);
        }
        addDetail(rows, R.string.status_tab_field_observation, obs);
        addDetail(rows, R.string.status_tab_field_operation, snap.operationType);
        addDetail(rows, R.string.status_tab_field_step, snap.step);
        int tracking = "ON".equals(snap.tracking) ? R.string.status_tab_tracking_on
                : "STARTING".equals(snap.tracking) ? R.string.status_tab_tracking_starting
                : R.string.status_tab_tracking_off;
        addDetail(rows, R.string.status_tab_field_tracking, app.getString(tracking));
        String arm = snap.armState();
        if ("CLOSED".equals(arm)) arm = app.getString(R.string.status_tab_arm_closed);
        else if ("OPEN".equals(arm)) arm = app.getString(R.string.status_tab_arm_open);
        else if ("MOVING".equals(arm)) arm = app.getString(R.string.status_tab_arm_moving);
        else arm = "";
        addDetail(rows, R.string.status_tab_field_arm, arm);
        addDetail(rows, R.string.status_tab_field_motors, snap.motors);
        addDetail(rows, R.string.status_tab_field_focus, snap.focus);
        if (!snap.targetName.isEmpty()) {
            addDetail(rows, R.string.status_tab_field_target, snap.targetName);
        } else if (VesperaLastTarget.hasTarget()) {
            addDetail(rows, R.string.status_tab_field_last_target, VesperaLastTarget.label());
        }
        addDetail(rows, R.string.status_tab_field_coordinates, snap.coordinates);
        addDetail(rows, R.string.status_tab_field_location, snap.location);
        if (snap.stackingCount > 0) {
            addDetail(rows, R.string.status_tab_field_stacking, String.valueOf(snap.stackingCount));
        }
        if (snap.exposureMicroSec > 0) {
            addDetail(rows, R.string.status_tab_field_exposure, String.format(
                    java.util.Locale.US, "%.1f s", snap.exposureMicroSec / 1_000_000.0));
        }
        if (snap.gain > 0) addDetail(rows, R.string.status_tab_field_gain, String.valueOf(snap.gain));
        addDetail(rows, R.string.status_tab_field_filter, snap.filter);
        addDetail(rows, R.string.status_tab_field_temperature, snap.temperature);
        addDetail(rows, R.string.status_tab_field_firmware, snap.firmware);
        addDetail(rows, R.string.status_tab_field_error, snap.error);
        int percent = usage != null ? usage.usedPercent : snap.storageUsedPercent;
        String storage = usage != null ? usage.label : snap.storage;
        if (storage.isEmpty() && percent >= 0) storage = percent + "%";
        if (!storage.isEmpty() && percent >= PhotoSyncService.STORAGE_SYNC_PERCENT) {
            storage = app.getString(R.string.status_tab_storage_full, storage);
        } else if (storage.isEmpty() && !VesperaInternalStorage.lastError().isEmpty()) {
            storage = app.getString(R.string.status_tab_storage_ftp_fail,
                    VesperaInternalStorage.lastError());
        } else if (storage.isEmpty()) {
            storage = app.getString(R.string.status_tab_storage_checking);
        }
        addDetail(rows, R.string.status_tab_field_storage, storage);
        if (snap.batteryPercent >= 0) {
            String battery = snap.batteryPercent + "%";
            if (!snap.batteryStatus.isEmpty()) battery += " (" + snap.batteryStatus + ")";
            addDetail(rows, R.string.status_tab_field_battery, battery);
        }
        addDetail(rows, R.string.status_tab_field_id, snap.telescopeId);
        return rows;
    }

    private void addDetail(JSONArray rows, int labelRes, String value) throws Exception {
        if (value == null || value.trim().isEmpty()) return;
        JSONObject row = new JSONObject();
        row.put("label", app.getString(labelRes));
        row.put("value", value.trim());
        rows.put(row);
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
        o.put("enabled", s.anyEnabled());
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
        o.put("rainForecast", s.rainForecast);
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
