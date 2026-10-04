package com.vaonis.vesperahelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps Vespera Wi-Fi up on Pi+Ethernet and prefers a <b>system-wide</b> association.
 *
 * Singularity's "Open Wi-Fi settings" screen requires a system-wide Wi-Fi NetworkAgent.
 * Promotion uses an optional root daemon ({@code tools/vespera-netd.sh}) because the
 * app cannot exec {@code su} under SELinux. The daemon also installs an {@code ip rule}
 * so {@code 10.0.0.0/24} goes via {@code wlan0} without a VPN.
 */
public final class VesperaConnectionService extends Service {
    public static final String ACTION_CONNECT = "com.vaonis.vesperahelper.CONNECT";
    public static final String ACTION_DISCONNECT = "com.vaonis.vesperahelper.DISCONNECT";
    public static final String ACTION_REFRESH = "com.vaonis.vesperahelper.REFRESH";
    public static final String ACTION_STATUS = "com.vaonis.vesperahelper.STATUS";
    public static final String EXTRA_STATUS = "status";

    public static final String STATUS_DISCONNECTED = "DISCONNECTED";
    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_LOST = "LOST";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    public static final String STATUS_NEED_CONFIG = "NEED_CONFIG";
    public static final String STATUS_REQUESTING = "REQUESTING";
    public static final String STATUS_ERROR = "ERROR";

    private static final String TAG = "VesperaConn";
    private static final int NOTIFICATION_ID = 42;
    private static final String NET_REQ = "net.req";
    private static final String SINGULARITY_REQ = "singularity.req";
    private static final String DISK_REQ = "disk.req";
    private static final String PROBE_REQ = "probe.req";
    private static final String ETH_REQ = "eth.req";
    private static final String SNIFF_REQ = "sniff.req";

    private static final long SINGULARITY_START_DELAY_MS = 3_000;

    private static volatile Network activeNetwork;
    private static volatile String lastStatus = STATUS_DISCONNECTED;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback holdCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable startSingularityAfterConnect = this::startSingularityAfterConnect;
    private String targetSsid;
    private String targetBssid;

    public static Network getActiveNetwork() { return activeNetwork; }
    public static String getLastStatus() { return lastStatus; }

    @Override public void onCreate() {
        super.onCreate();
        connectivity = getSystemService(ConnectivityManager.class);
        Context localized = AppLocale.wrap(this);
        NotificationChannel channel = new NotificationChannel(
                "vespera_connection",
                localized.getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private boolean stopRequested;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            stopRequested = true;
            stopConnection();
            stopSelf();
            return START_NOT_STICKY;
        }
        stopRequested = false;
        if (ACTION_REFRESH.equals(action)) {
            findConnectedVespera();
            return START_NOT_STICKY;
        }
        Context localized = AppLocale.wrap(this);
        startForeground(NOTIFICATION_ID,
                notification(localized.getString(R.string.conn_notification_connecting)));
        if (holdCallback == null) connect();
        return START_STICKY;
    }

    private Notification notification(String text) {
        Context localized = AppLocale.wrap(this);
        return new Notification.Builder(this, "vespera_connection")
                .setSmallIcon(R.drawable.ic_vespera_notification)
                .setContentTitle(localized.getString(R.string.conn_notification_title))
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void connect() {
        VesperaDeviceStore device = VesperaDeviceStore.from(this);
        if (!device.isConfigured()) {
            update(STATUS_NEED_CONFIG);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        targetSsid = device.getSsid();
        targetBssid = device.getBssid();
        update(STATUS_REQUESTING + "|" + device.getModel() + "|" + targetSsid);
        try {
            // Keeps ClientMode from bailing on Ethernet-primary devices.
            holdGenericWifiRequest();
            worker.execute(this::requestDaemonPromote);
            mainHandler.postDelayed(this::findConnectedVespera, 2_000);
            mainHandler.postDelayed(() -> worker.execute(this::requestDaemonPromote), 4_000);
            mainHandler.postDelayed(this::findConnectedVespera, 5_000);
            mainHandler.postDelayed(() -> worker.execute(this::requestDaemonPromote), 8_000);
            mainHandler.postDelayed(this::findConnectedVespera, 9_000);
            mainHandler.postDelayed(this::markUnavailableIfDisconnected, 15_000);
        } catch (RuntimeException failure) {
            update(STATUS_ERROR + "|" + failure.getClass().getSimpleName());
            stopSelf();
        }
    }

    private void holdGenericWifiRequest() {
        if (holdCallback != null) return;
        holdCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (isTargetVesperaWifi(network)) adopt(network);
            }
            @Override public void onLost(Network network) {
                if (network.equals(activeNetwork)) {
                    activeNetwork = null;
                    update(STATUS_LOST);
                }
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (isTargetVesperaWifi(network)) adopt(network);
            }
        };
        // Match any Wi‑Fi (with or without INTERNET). Excluding INTERNET made
        // Android refuse bindSocket (EPERM) when the AP was still marked as
        // having internet, because the Network came from getAllNetworks()
        // rather than a matching requestNetwork callback.
        NetworkRequest hold = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        connectivity.requestNetwork(hold, holdCallback);
    }

    /** Ask rooted {@code vespera-netd.sh} to connect-network + install wlan route. */
    private void requestDaemonPromote() {
        boolean written = writeNetRequest("promote|" + targetSsid + "|" + targetBssid);
        Log.i(TAG, "daemon promote request written=" + written);
    }

    /** Re-associate system Wi‑Fi to the saved Vespera and refresh the wlan route. */
    public static void requestDaemonPromote(Context context) {
        VesperaDeviceStore device = VesperaDeviceStore.from(context);
        if (!device.isConfigured()) {
            Log.w(TAG, "daemon promote skipped: no saved device");
            return;
        }
        writeNetRequestStatic(context,
                "promote|" + device.getSsid() + "|" + device.getBssid());
    }

    /** Ask daemon to refresh 10.0.0.0/24 → wlan0 rule (no VPN). */
    public static void requestDaemonRoute(Context context) {
        writeNetRequestStatic(context, "route");
    }

    /** Ask rooted daemon to force-stop Singularity (requires {@code vespera-netd.sh}). */
    public static void requestSingularityRestart(Context context) {
        writeSingularityRequest(context, "restart-singularity");
    }

    /** Ask daemon to launch Singularity if it is not already running. */
    public static void requestSingularityStart(Context context) {
        writeSingularityRequest(context, "start-singularity");
    }

    /** Ask daemon to probe whether Singularity sees the Vespera instrument. */
    public static boolean writeNetRequest(Context context, String line) {
        return writeNetRequestStatic(context, line);
    }

    /** Dedicated file so set-clock / route cannot overwrite Ethernet IP commands. */
    public static boolean writeEthRequest(Context context, String line) {
        return writeRequestFile(context, ETH_REQ, line);
    }

    /** Dedicated request file so route/promote cannot overwrite a pending Singularity check. */
    public static boolean writeSingularityRequest(Context context, String line) {
        return writeRequestFile(context, SINGULARITY_REQ, line);
    }

    /** Dedicated request file so route/promote/check-singularity cannot overwrite disk ops. */
    public static boolean writeDiskRequest(Context context, String line) {
        return writeRequestFile(context, DISK_REQ, line);
    }

    /** Dedicated request so route/promote cannot steal API port discovery. */
    public static boolean writeProbeRequest(Context context, String line) {
        return writeRequestFile(context, PROBE_REQ, line);
    }

    /** Ask the daemon to redirect Singularity's telescope API through the local sniffer. */
    public static boolean writeSniffRequest(Context context, String line) {
        return writeRequestFile(context, SNIFF_REQ, line);
    }

    /** Re-scan active networks and adopt the saved Vespera if present. */
    public static void refreshConnectedNetwork(Context context) {
        Intent intent = new Intent(context, VesperaConnectionService.class)
                .setAction(ACTION_REFRESH);
        context.startService(intent);
    }

    private boolean writeNetRequest(String line) {
        return writeNetRequestStatic(this, line);
    }

    private static boolean writeNetRequestStatic(Context context, String line) {
        return writeRequestFile(context, NET_REQ, line);
    }

    private static boolean writeRequestFile(Context context, String fileName, String line) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            if (!dir.exists() && !dir.mkdirs()) return false;
            File req = new File(dir, fileName);
            // Root/adb may leave an unwritable inode; replace it.
            if (req.exists() && !req.canWrite()) {
                //noinspection ResultOfMethodCallIgnored
                req.delete();
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(req, false), StandardCharsets.UTF_8)) {
                writer.write(line);
                writer.write('\n');
            }
            // World-readable so the root daemon can consume it.
            //noinspection ResultOfMethodCallIgnored
            req.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            req.setWritable(true, false);
            Log.i(TAG, "wrote " + req.getAbsolutePath() + " => " + line);
            return true;
        } catch (Exception failure) {
            Log.w(TAG, "writeRequestFile failed (" + fileName + ")", failure);
            return false;
        }
    }

    private void markUnavailableIfDisconnected() {
        if (!STATUS_CONNECTED.equals(lastStatus)) {
            activeNetwork = null;
            update(STATUS_UNAVAILABLE);
        }
    }

    private void adopt(Network network) {
        // onCapabilitiesChanged fires on RSSI / validation ticks; do not
        // re-broadcast CONNECTED or rewrite the route every time.
        if (network.equals(activeNetwork) && STATUS_CONNECTED.equals(lastStatus)) {
            return;
        }
        activeNetwork = network;
        requestDaemonRoute(this);
        update(STATUS_CONNECTED);
    }

    private boolean isTargetVesperaWifi(Network network) {
        if (network == null || connectivity == null) return false;
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false;
        WifiInfo info = extractWifiInfo(caps);
        if (info != null) {
            String ssid = sanitizeSsid(info.getSSID());
            String bssid = info.getBSSID();
            if (targetSsid != null && targetSsid.equalsIgnoreCase(ssid)) return true;
            if (targetBssid != null && targetBssid.equalsIgnoreCase(bssid)) return true;
        }
        android.net.LinkProperties lp = connectivity.getLinkProperties(network);
        if (lp != null) {
            for (android.net.LinkAddress addr : lp.getLinkAddresses()) {
                String host = addr.getAddress().getHostAddress();
                if (host != null && host.startsWith("10.0.0.")) return true;
            }
        }
        return false;
    }

    private void findConnectedVespera() {
        for (Network network : connectivity.getAllNetworks()) {
            if (isTargetVesperaWifi(network)) {
                adopt(network);
                return;
            }
        }
    }

    private static WifiInfo extractWifiInfo(NetworkCapabilities caps) {
        if (caps == null) return null;
        android.net.TransportInfo transport = caps.getTransportInfo();
        if (transport instanceof WifiInfo) return (WifiInfo) transport;
        return null;
    }

    private static String sanitizeSsid(String ssid) {
        if (ssid == null) return "";
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private void startSingularityAfterConnect() {
        if (!STATUS_CONNECTED.equals(lastStatus)) return;
        Log.i(TAG, "starting Singularity after Vespera connect (background)");
        SystemActivityLog.record(this, SystemActivityLog.KIND_SINGULARITY, SystemActivityLog.DETAIL_OK);
        // Daemon starts Singularity without taking the screen; UI only if the user opens it.
        requestSingularityStart(this);
    }

    private void stopConnection() {
        mainHandler.removeCallbacks(startSingularityAfterConnect);
        mainHandler.removeCallbacksAndMessages(null);
        if (holdCallback != null) {
            try { connectivity.unregisterNetworkCallback(holdCallback); } catch (RuntimeException ignored) {}
            holdCallback = null;
        }
        activeNetwork = null;
        update(STATUS_DISCONNECTED);
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void update(String status) {
        if (status == null) return;
        boolean becameConnected = STATUS_CONNECTED.equals(status)
                && !STATUS_CONNECTED.equals(lastStatus);
        if (status.equals(lastStatus)) return;
        lastStatus = status;
        Context localized = AppLocale.wrap(this);
        String text = StatusTexts.connection(localized, status);
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, status));
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
        if (becameConnected) {
            if (SystemSettingsStore.from(this).singularityStart()) {
                mainHandler.removeCallbacks(startSingularityAfterConnect);
                mainHandler.postDelayed(startSingularityAfterConnect, SINGULARITY_START_DELAY_MS);
            }
        } else if (!STATUS_CONNECTED.equals(status)
                && !status.startsWith(STATUS_REQUESTING)) {
            mainHandler.removeCallbacks(startSingularityAfterConnect);
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!stopRequested && SystemSettingsStore.from(this).keepAlive()
                && VesperaDeviceStore.from(this).isConfigured()) {
            try {
                startForegroundService(new Intent(this, VesperaConnectionService.class)
                        .setAction(ACTION_CONNECT));
            } catch (Exception ignored) {
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        stopConnection();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
