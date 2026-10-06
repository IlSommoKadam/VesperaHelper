package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls whether Singularity sees the Vespera instrument every {@value #INTERVAL_MS}
 * ms while VesperaHelper is in the foreground. Checks only: no automatic start/restart.
 * Manual restart is triggered from the UI button.
 */
final class InstrumentWatchdog {
    private static final String TAG = "InstrumentWatchdog";
    static final String ACTION_STATUS = "com.vaonis.vesperahelper.INSTRUMENT_STATUS";
    static final String EXTRA_DETECTED = "detected";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_MANUAL = "manual";
    static final String EXTRA_STATUS = "status";
    /** UI: VesperaHelper non connesso al Vespera — Singularity non verificabile. */
    static final String STATUS_IDLE = "IDLE";
    /** UI: controllo in corso. */
    static final String STATUS_CHECKING = "CHECKING";
    /** UI: recovery (route / riavvio) in corso. */
    static final String STATUS_RECOVERING = "RECOVERING";
    /** UI: avvio Singularity (non era in esecuzione). */
    static final String STATUS_STARTING = "STARTING";

    static final long INTERVAL_MS = 30_000;
    static final String PROBE_ACK = "probe.ack";
    private static final int[] API_PORTS = {8083, 8082};
    private static final int PROBE_TIMEOUT_MS = 2_500;
    private static final long DAEMON_PROBE_TIMEOUT_MS = 12_000;
    private static final long RECHECK_AFTER_RESTART_MS = 12_000;
    private static final Pattern PORT_PATTERN = Pattern.compile("(?:port=|api-port-|:)(808[23])\\b");
    private static volatile int lastApiPort = -1;

    private final Context appContext;
    private final Context ui;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean checkInFlight = new AtomicBoolean(false);
    private final AtomicBoolean restartInFlight = new AtomicBoolean(false);
    private static volatile Snapshot lastSnapshot;
    private static volatile long lastSnapshotAt;

    static final class Snapshot {
        final boolean detected;
        final int port;
        final String message;
        final String status;

        Snapshot(boolean detected, int port, String message, String status) {
            this.detected = detected;
            this.port = port;
            this.message = message == null ? "" : message;
            this.status = status == null ? STATUS_IDLE : status;
        }
    }

    static Snapshot lastSnapshot() {
        return lastSnapshot;
    }

    static boolean lastDetectedConnected() {
        Snapshot snap = lastSnapshot;
        return snap != null
                && SingularityDetector.Status.CONNECTED.name().equals(snap.status);
    }

    /** True after a check has started or finished in this process (skip UI reload). */
    static boolean hasCachedStatus() {
        return lastSnapshot != null;
    }

    static void clearSnapshot() {
        lastSnapshot = null;
        lastSnapshotAt = 0;
        lastApiPort = -1;
    }

    /** Age of the last check result in ms (Long.MAX_VALUE if never checked). */
    static long snapshotAgeMs() {
        long at = lastSnapshotAt;
        return at == 0 ? Long.MAX_VALUE : android.os.SystemClock.elapsedRealtime() - at;
    }

    /**
     * Records a check run outside the activity (RemoteBridge), so remote clients
     * see a real status while Singularity — not VesperaHelper — is in the foreground.
     */
    static void recordBackgroundCheck(SingularityDetector.Result result) {
        if (result == null) return;
        rememberPort(parsePort(result.detail));
        lastSnapshot = new Snapshot(result.isConnected(), lastApiPort, result.detail,
                result.status.name());
        lastSnapshotAt = android.os.SystemClock.elapsedRealtime();
    }

    static void rememberPort(int port) {
        if (port > 0) lastApiPort = port;
    }

    static int lastApiPort() {
        return lastApiPort;
    }

    private final Runnable periodicCheck = new Runnable() {
        @Override public void run() {
            if (!running.get()) return;
            runCheck(false);
            if (running.get()) {
                mainHandler.postDelayed(this, INTERVAL_MS);
            }
        }
    };

    InstrumentWatchdog(Context context) {
        this.appContext = context.getApplicationContext();
        this.ui = AppLocale.wrap(this.appContext);
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        mainHandler.removeCallbacks(periodicCheck);
        if (lastDetectedConnected()) {
            mainHandler.postDelayed(periodicCheck, INTERVAL_MS);
        } else {
            mainHandler.post(periodicCheck);
        }
    }

    void stop() {
        running.set(false);
        mainHandler.removeCallbacks(periodicCheck);
        checkInFlight.set(false);
        restartInFlight.set(false);
    }

    void shutdown() {
        stop();
        worker.shutdownNow();
    }

    void requestManualCheck() {
        if (!STATUS_CONNECTED()) {
            emit(false, -1, uiString(R.string.watchdog_not_connected), true,
                    STATUS_IDLE);
            return;
        }
        emit(false, -1, uiString(R.string.watchdog_checking), true, STATUS_CHECKING);
        runCheck(true);
    }

    void requestManualRestart() {
        if (!STATUS_CONNECTED()) {
            emit(false, -1, uiString(R.string.watchdog_not_connected), true,
                    STATUS_IDLE);
            return;
        }
        if (!restartInFlight.compareAndSet(false, true)) return;
        emit(false, -1, uiString(R.string.watchdog_manual_restart), true, STATUS_RECOVERING);
        worker.execute(() -> {
            try {
                restartSingularityNow();
            } finally {
                restartInFlight.set(false);
            }
        });
    }

    private void runCheck(boolean manual) {
        if (!running.get() && !manual) return;
        if (!checkInFlight.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                performCheck(manual);
            } finally {
                checkInFlight.set(false);
            }
        });
    }

    private void performCheck(boolean manual) {
        if (!STATUS_CONNECTED()) {
            emit(false, -1, uiString(R.string.watchdog_not_connected), manual,
                    STATUS_IDLE);
            return;
        }

        SingularityDetector.Result result = SingularityDetector.check(appContext);
        rememberPort(parsePort(result.detail));
        int port = lastApiPort;
        if (result.isConnected()) {
            emitIfChanged(true, port, uiString(R.string.watchdog_singularity_detected), manual,
                    SingularityDetector.Status.CONNECTED.name());
            return;
        }
        emit(false, port, messageFor(result), manual, result.status.name());
    }

    private String messageFor(SingularityDetector.Result result) {
        switch (result.status) {
            case NOT_RUNNING:
                return uiString(R.string.watchdog_singularity_not_running);
            case API_DOWN:
                return uiString(R.string.watchdog_singularity_api_down);
            case NO_WIFI:
                return uiString(R.string.watchdog_singularity_no_wifi);
            case DAEMON_MISSING:
                return uiString(R.string.watchdog_singularity_no_daemon);
            case DISCONNECTED:
            default:
                return uiString(R.string.watchdog_singularity_not_detected);
        }
    }

    private void restartSingularityNow() {
        if (!STATUS_CONNECTED()) {
            emit(false, -1, uiString(R.string.watchdog_not_connected), true, STATUS_IDLE);
            return;
        }
        VesperaConnectionService.requestSingularityRestart(appContext);
        sleepQuietly(RECHECK_AFTER_RESTART_MS);

        SingularityDetector.Result restartResult = SingularityDetector.check(appContext);
        rememberPort(parsePort(restartResult.detail));
        int port = lastApiPort;
        if (restartResult.isConnected()) {
            emit(true, port, uiString(R.string.watchdog_recovered_restart), true,
                    SingularityDetector.Status.CONNECTED.name());
        } else if (restartResult.status == SingularityDetector.Status.NOT_RUNNING) {
            emit(false, port, uiString(R.string.watchdog_start_failed), true,
                    restartResult.status.name());
        } else {
            emit(false, port, uiString(R.string.watchdog_restart_failed), true,
                    restartResult.status.name());
        }
    }

    /**
     * Discovers the Vespera API port. Prefers the rooted daemon (binds to wlan0),
     * then falls back to a Network-bound TCP connect.
     */
    static int probeApiPort(Context context, Network network) {
        return probeApiPort(context, network, true);
    }

    static int probeApiPort(Context context, Network network, boolean tryDaemon) {
        if (tryDaemon) {
            int daemonPort = probeApiPortViaDaemon(context);
            if (daemonPort > 0) {
                rememberPort(daemonPort);
                return daemonPort;
            }
        }
        int socketPort = probeApiPortViaSocket(network);
        if (socketPort > 0) rememberPort(socketPort);
        return socketPort;
    }

    private static int probeApiPortViaDaemon(Context context) {
        if (context == null) return -1;
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        if (dir == null) return -1;
        File ack = new File(dir, PROBE_ACK);
        if (ack.exists() && !ack.delete()) {
            Log.w(TAG, "could not clear " + PROBE_ACK);
        }
        if (!VesperaConnectionService.writeProbeRequest(context, "probe-api")) {
            Log.w(TAG, "probe-api request write failed");
            return -1;
        }
        long deadline = System.currentTimeMillis() + DAEMON_PROBE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (ack.exists() && ack.length() > 0) {
                String line = readFirstLine(ack);
                int port = parseDaemonProbe(line);
                Log.i(TAG, "daemon probe ack=" + line + " port=" + port);
                return port;
            }
            sleepQuietly(200);
        }
        Log.w(TAG, "daemon probe timeout");
        return -1;
    }

    private static int parseDaemonProbe(String line) {
        if (line == null || line.isEmpty()) return -1;
        String lower = line.toLowerCase(Locale.US);
        if (lower.startsWith("api-port-none")) return -1;
        return parsePort(line);
    }

    static int parsePort(String text) {
        if (text == null || text.isEmpty()) return -1;
        Matcher match = PORT_PATTERN.matcher(text);
        if (match.find()) {
            try {
                return Integer.parseInt(match.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static String readFirstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception failure) {
            Log.w(TAG, "read probe ack failed", failure);
            return "";
        }
    }

    private static int probeApiPortViaSocket(Network network) {
        if (network == null) return -1;
        for (int candidate : API_PORTS) {
            if (tryConnect(network, candidate)) return candidate;
        }
        return -1;
    }

    private static boolean tryConnect(Network network, int port) {
        InetSocketAddress target = new InetSocketAddress("10.0.0.1", port);
        try (Socket socket = VesperaSockets.create(network)) {
            socket.connect(target, PROBE_TIMEOUT_MS);
            Log.i(TAG, "tcp probe ok port=" + port);
            return true;
        } catch (IOException failure) {
            Log.w(TAG, "tcp probe fail port=" + port
                    + " " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return false;
        }
    }

    private void emitIfChanged(boolean detected, int port, String message, boolean manual,
                               String status) {
        Snapshot previous = lastSnapshot;
        if (!manual && previous != null
                && previous.detected == detected
                && previous.port == port
                && previous.status.equals(status == null ? STATUS_IDLE : status)) {
            lastSnapshotAt = android.os.SystemClock.elapsedRealtime();
            return;
        }
        emit(detected, port, message, manual, status);
    }

    private void emit(boolean detected, int port, String message, boolean manual, String status) {
        String localizedMessage = message;
        if (manual) {
            localizedMessage = uiString(R.string.watchdog_manual_prefix, message);
        }
        if (port <= 0) port = lastApiPort;
        lastSnapshot = new Snapshot(detected, port, localizedMessage,
                status == null ? STATUS_IDLE : status);
        lastSnapshotAt = android.os.SystemClock.elapsedRealtime();
        Intent intent = new Intent(ACTION_STATUS)
                .setPackage(appContext.getPackageName())
                .putExtra(EXTRA_DETECTED, detected)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_MESSAGE, localizedMessage)
                .putExtra(EXTRA_MANUAL, manual)
                .putExtra(EXTRA_STATUS, status == null ? STATUS_IDLE : status);
        appContext.sendBroadcast(intent);
        Log.i(TAG, (detected ? "singularity-detected" : "singularity-missing")
                + " status=" + status + " manual=" + manual + " msg=" + localizedMessage);
    }

    private String uiString(int resId) {
        return ui.getString(resId);
    }

    private String uiString(int resId, Object... args) {
        return ui.getString(resId, args);
    }

    private static boolean STATUS_CONNECTED() {
        return VesperaConnectionService.STATUS_CONNECTED.equals(VesperaConnectionService.getLastStatus());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
