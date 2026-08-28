package com.vaonis.vesperahelper;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatic Weather Protection engine.
 *
 * <p>Each run queries Open-Meteo for the configured coordinates and, if rain is
 * forecast within the look-ahead window, runs the protection sequence:
 * stop session → close (park/retract arm) → wait for the real ARM_RETRACTED /
 * CLOSED status → verify closed → power off. The telescope is powered off
 * <em>only</em> after the closed state is reliably confirmed; it never relies on
 * a fixed delay before shutdown.
 *
 * <p>Runs on a background thread supplied by {@link PhotoSyncService}. A latch
 * ({@link WeatherProtectionStore#protectionActive()}) prevents a second sequence
 * from starting once one has begun/completed until the feature is re-saved.
 */
final class WeatherProtectionEngine {
    private static final String TAG = "WeatherProtect";
    /** Max time to wait for the arm-retracted / CLOSED confirmation. */
    static final long CLOSE_WAIT_TIMEOUT_MS = 120_000L;
    /** Poll interval while waiting for the closed state. */
    static final long CLOSE_WAIT_POLL_MS = 5_000L;

    private static final AtomicBoolean running = new AtomicBoolean(false);

    private WeatherProtectionEngine() {}

    /** Runs one weather check + protection cycle. Safe to call off the main thread. */
    static void runCheck(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        WeatherProtectionStore store = WeatherProtectionStore.from(app);
        if (!store.enabled()) return;
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "check skipped: a cycle is already running");
            return;
        }
        try {
            performCheck(app, store);
        } finally {
            running.set(false);
        }
    }

    private static void performCheck(Context app, WeatherProtectionStore store) {
        long now = System.currentTimeMillis();
        store.setState(WeatherProtectionState.CHECKING_WEATHER);

        double lat = store.latitude();
        double lon = store.longitude();
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            // Fall back to the configured observing site coordinates if present.
            PhotoSyncStore site = PhotoSyncStore.from(app);
            if (site.hasSite()) {
                lat = site.siteLat();
                lon = site.siteLon();
            }
        }
        int lookAhead = store.lookAheadMin();
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            store.recordCheck(now, WeatherProtectionStore.DECISION_NO_COORDS, -1f, -1);
            store.setState(WeatherProtectionState.IDLE);
            Log.w(TAG, "weather check skipped: no coordinates configured");
            SystemActivityLog.record(app, SystemActivityLog.KIND_WEATHER_CHECK, "no_coords");
            return;
        }

        OpenMeteoClient.Forecast forecast = OpenMeteoClient.fetch(lat, lon, lookAhead);
        if (!forecast.success) {
            store.incrementApiFailures();
            store.recordCheck(now, WeatherProtectionStore.DECISION_API_ERROR, -1f, -1);
            store.setState(WeatherProtectionState.IDLE);
            Log.w(TAG, "Open-Meteo error: " + forecast.error
                    + " (consecutive failures=" + store.consecutiveApiFailures()
                    + ") — no action, retry next interval");
            SystemActivityLog.record(app, SystemActivityLog.KIND_WEATHER_CHECK,
                    "api_error " + forecast.error);
            return;
        }
        store.resetApiFailures();

        float precip = (float) forecast.precipitationMm;
        int prob = forecast.probabilityPercent;
        boolean rain = forecast.precipitationMm > store.thresholdMm();
        String decision = rain ? WeatherProtectionStore.DECISION_RAIN
                : WeatherProtectionStore.DECISION_NO_ACTION;
        store.recordCheck(now, decision, precip, prob);

        Log.i(TAG, String.format(Locale.US,
                "Weather check lat=%.5f lon=%.5f look-ahead=%dm precip=%.2fmm (%s) thr=%.2fmm → %s",
                lat, lon, lookAhead, forecast.precipitationMm, forecast.granularity,
                store.thresholdMm(), rain ? "RAIN → PROTECT" : "NO ACTION"));
        SystemActivityLog.record(app, SystemActivityLog.KIND_WEATHER_CHECK,
                (rain ? "rain " : "no_action ")
                        + String.format(Locale.US, "%.2fmm", forecast.precipitationMm));

        if (!rain) {
            store.setState(WeatherProtectionState.IDLE);
            return;
        }
        if (store.protectionActive()) {
            Log.i(TAG, "rain detected but protection already active — no new sequence");
            return;
        }
        runProtection(app, store, now);
    }

    private static void runProtection(Context app, WeatherProtectionStore store, long now) {
        store.setState(WeatherProtectionState.RAIN_DETECTED);
        Log.i(TAG, "Automatic Weather Protection triggered — Reason: Rain forecast detected");
        SystemActivityLog.record(app, SystemActivityLog.KIND_WEATHER_PROTECT, "triggered");

        if (store.simulation()) {
            simulateProtection(app, store, now);
            return;
        }

        Network network = resolveNetwork(app);
        int port = resolveApiPort(app, network);
        if (network == null) {
            // Cannot reach the telescope (probably already off). Do not latch — retry.
            store.setState(WeatherProtectionState.ERROR);
            store.recordResult(now, WeatherProtectionStore.RESULT_OFFLINE);
            Log.w(TAG, "protection: Vespera network unavailable — cannot close (retry next interval)");
            SystemActivityLog.record(app, SystemActivityLog.KIND_WEATHER_PROTECT, "offline");
            return;
        }

        // Latch immediately so subsequent checks do not restart the sequence.
        store.setProtectionActive(true);
        boolean poweredOff = false;
        try {
            String host = PhotoSyncEngine.HOST;

            // 1) Stop any active session/observation.
            store.setState(WeatherProtectionState.STOPPING_SESSION);
            VesperaStatusSnapshot snap = VesperaStatusClient.fetch(host, port, network);
            if (snap != null && (snap.isObserving() || snap.isTrackingAcquisition())) {
                VesperaCommandClient.Result stop = VesperaCommandClient.send(
                        host, port, network, VesperaCommandClient.Command.STOP);
                Log.i(TAG, "stop session command sent " + describe(stop));
            } else {
                Log.i(TAG, "stop session skipped (no active observation)");
            }

            // 2) Close the Vespera — PARK retracts the arm to the closed position.
            store.setState(WeatherProtectionState.CLOSING_VESPERA);
            VesperaCommandClient.Result park = VesperaCommandClient.send(
                    host, port, network, VesperaCommandClient.Command.PARK);
            Log.i(TAG, "close command sent " + describe(park));

            // 3) Wait for the real arm-retracted / CLOSED status (not a fixed delay).
            store.setState(WeatherProtectionState.WAITING_FOR_ARM_EVENT);
            Log.i(TAG, "waiting for arm retraction / CLOSED (timeout "
                    + (CLOSE_WAIT_TIMEOUT_MS / 1000L) + "s)");
            boolean idle = VesperaCommandClient.waitUntilIdleAfterPark(
                    host, port, network, "weather-close",
                    CLOSE_WAIT_TIMEOUT_MS, CLOSE_WAIT_POLL_MS);

            // 4) Verify the closed state. On timeout, re-read the real status as backup.
            store.setState(WeatherProtectionState.VERIFYING_CLOSED_STATE);
            boolean closed = idle;
            if (!closed) {
                VesperaStatusSnapshot after = VesperaStatusClient.fetch(host, port, network);
                closed = after != null && !after.isBusyForShutdown();
            }
            if (!closed) {
                store.setState(WeatherProtectionState.ERROR);
                store.recordResult(now, WeatherProtectionStore.RESULT_ERROR);
                store.setProtectionActive(false); // allow a retry on the next interval
                Log.e(TAG, "CLOSED state not confirmed within timeout — NOT powering off");
                SystemActivityLog.record(app, SystemActivityLog.KIND_WEATHER_PROTECT,
                        "error close_unconfirmed");
                return;
            }
            Log.i(TAG, "ARM_RETRACTED / CLOSED state confirmed");

            // 5) Power off — only after reliable closed confirmation.
            store.setState(WeatherProtectionState.POWERING_OFF);
            VesperaCommandClient.Result shutdown = VesperaCommandClient.send(
                    host, port, network, VesperaCommandClient.Command.SHUTDOWN);
            poweredOff = shutdown != null && shutdown.success;
            Log.i(TAG, "power off command sent " + describe(shutdown));
            if (poweredOff) {
                TelescopeStatusHub.ensure().ingestShutdown();
                store.setState(WeatherProtectionState.PROTECTION_COMPLETED);
                store.recordResult(now, WeatherProtectionStore.RESULT_COMPLETED);
                SystemActivityLog.record(app, SystemActivityLog.KIND_WEATHER_PROTECT,
                        SystemActivityLog.DETAIL_SHUTDOWN_OK);
                Log.i(TAG, "Automatic Weather Protection completed: telescope closed and powered off");
            } else {
                store.setState(WeatherProtectionState.ERROR);
                store.recordResult(now, WeatherProtectionStore.RESULT_ERROR);
                store.setProtectionActive(false); // allow a retry on the next interval
                SystemActivityLog.record(app, SystemActivityLog.KIND_WEATHER_PROTECT,
                        "error power_off_failed");
            }
        } catch (Exception failure) {
            store.setState(WeatherProtectionState.ERROR);
            store.recordResult(now, WeatherProtectionStore.RESULT_ERROR);
            if (!poweredOff) store.setProtectionActive(false);
            Log.e(TAG, "protection sequence failed", failure);
            SystemActivityLog.record(app, SystemActivityLog.KIND_WEATHER_PROTECT,
                    "error " + failure.getClass().getSimpleName());
        }
    }

    /** Dry run: everything real except commands to the Vespera. */
    private static void simulateProtection(Context app, WeatherProtectionStore store, long now) {
        Log.i(TAG, "[SIMULATION] Rain forecast detected");
        store.setState(WeatherProtectionState.STOPPING_SESSION);
        Log.i(TAG, "[SIMULATION] Would stop session");
        store.setState(WeatherProtectionState.CLOSING_VESPERA);
        Log.i(TAG, "[SIMULATION] Would close Vespera");
        store.setState(WeatherProtectionState.WAITING_FOR_ARM_EVENT);
        Log.i(TAG, "[SIMULATION] Would wait for ARM_RETRACTED / CLOSED event");
        store.setState(WeatherProtectionState.VERIFYING_CLOSED_STATE);
        Log.i(TAG, "[SIMULATION] Would power OFF");
        store.setState(WeatherProtectionState.PROTECTION_COMPLETED);
        store.recordResult(now, WeatherProtectionStore.RESULT_SIMULATED);
        // Not latched in simulation: keep evaluating every interval during testing.
        SystemActivityLog.record(app, SystemActivityLog.KIND_WEATHER_PROTECT, "simulated");
    }

    private static Network resolveNetwork(Context app) {
        Network net = VesperaConnectionService.getActiveNetwork();
        if (net != null) return net;
        try {
            VesperaConnectionService.refreshConnectedNetwork(app);
        } catch (Exception ignored) {
        }
        net = VesperaConnectionService.getActiveNetwork();
        if (net != null) return net;
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        for (Network network : cm.getAllNetworks()) {
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null) continue;
            for (LinkAddress addr : lp.getLinkAddresses()) {
                String host = addr.getAddress().getHostAddress();
                if (host != null && host.startsWith("10.0.0.")) return network;
            }
        }
        return null;
    }

    private static int resolveApiPort(Context app, Network network) {
        VesperaPortScan scan = VesperaPortScanner.lastScan();
        if (scan != null && scan.apiRestPort > 0) return scan.apiRestPort;
        return InstrumentWatchdog.probeApiPort(app, network);
    }

    private static String describe(VesperaCommandClient.Result result) {
        if (result == null) return "null";
        return "http=" + result.httpCode + " " + result.message;
    }
}
