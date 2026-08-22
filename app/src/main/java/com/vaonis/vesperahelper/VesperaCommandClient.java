package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import org.json.JSONObject;

/**
 * Sends Vespera REST commands with Ed25519 challenge-response auth.
 */
final class VesperaCommandClient {
    private static final String TAG = "VesperaCmd";
    private static final int TIMEOUT_MS = 8_000;
    /** Auto-init (arm + astrometry + focus) often exceeds 3 minutes. */
    private static final long INIT_WAIT_MS = 10 * 60_000L;
    private static final long INIT_POLL_MS = 4_000L;
    /** After PARK, poll status every minute until idle or this timeout. */
    static final long PARK_IDLE_WAIT_MS = 10 * 60_000L;
    static final long PARK_IDLE_POLL_MS = 60_000L;

    enum Command {
        PARK("/v1/general/park"),
        STOP("/v1/general/stopObservation"),
        RESUME("/v1/general/startObservation"),
        INIT("/v1/general/startAutoInit"),
        SHUTDOWN("/v1/board/requestShutdown");

        final String path;

        Command(String path) {
            this.path = path;
        }
    }

    static final class Result {
        final boolean success;
        final int httpCode;
        final String message;

        Result(boolean success, int httpCode, String message) {
            this.success = success;
            this.httpCode = httpCode;
            this.message = message == null ? "" : message;
        }
    }

    private VesperaCommandClient() {}

    static Result send(String host, int apiPort, Network network, Command command) {
        return send(host, apiPort, network, command, null);
    }

    static Result send(String host, int apiPort, Network network, Command command,
            VesperaLocationClient.Site initSite) {
        if (host == null || host.isEmpty()) host = "10.0.0.1";
        int port = apiPort > 0 ? apiPort : 8082;
        if (port == 8083) port = 8082;
        VesperaStatusClient.Result status = VesperaStatusClient.fetchResult(host, port, network);
        VesperaStatusSnapshot snap = status.snapshot;
        if (snap == null) {
            String detail = status.error.isEmpty() ? "status_unavailable" : status.error;
            return new Result(false, -1, "status_unavailable: " + detail);
        }
        if (!snap.canSignCommands()) {
            return new Result(false, -1, snap.authMissingCode());
        }
        if (command == Command.SHUTDOWN && snap.isShuttingDown()) {
            return new Result(true, 200, "already_shutting_down");
        }
        if (command == Command.SHUTDOWN) {
            status = VesperaStatusClient.fetchResult(host, port, network);
            snap = status.snapshot;
            if (snap == null) {
                String detail = status.error.isEmpty() ? "status_unavailable" : status.error;
                return new Result(false, -1, "status_unavailable: " + detail);
            }
            if (!snap.canSignCommands()) {
                return new Result(false, -1, snap.authMissingCode());
            }
            if (snap.isShuttingDown()) {
                return new Result(true, 200, "already_shutting_down");
            }
        }

        // Riprendi senza init già fatto: avvia auto-init, aspetta che finisca,
        // poi POST resume. Lo status può restare initialized=false: non è
        // il segnale per spedire startObservation.
        if (command == Command.RESUME && !readyToResumeAfterInit(snap)) {
            Result init = ensureInitialized(host, port, network, snap, initSite);
            if (!init.success) return init;
            status = VesperaStatusClient.fetchResult(host, port, network);
            snap = status.snapshot;
            if (snap == null || !snap.canSignCommands()) {
                return new Result(false, -1, "status_unavailable: after_init");
            }
            if (snap.isAutoInitRunning()) {
                return new Result(false, -1, "init_not_ready");
            }
        }

        String body = "{}";
        boolean haveTarget = false;
        boolean fromStoredCapture = false;
        if (command == Command.RESUME) {
            // Multi-night / PerseverENS: Singularity uses captureStore API + storeId.
            String stored = VesperaLastTarget.storedCaptureBody();
            if (!stored.isEmpty()) {
                body = stored;
                fromStoredCapture = true;
                haveTarget = true;
            } else {
                body = VesperaLastTarget.startObservationBody(snap);
                haveTarget = VesperaLastTarget.hasTarget() && !body.isEmpty();
                if (!haveTarget) body = "{\"resume\":true}";
            }
        } else if (command == Command.INIT) {
            body = autoInitBody(initSite);
            if (body.isEmpty()) {
                return new Result(false, -1, "no_site");
            }
        }
        String authorization = VesperaApiAuth.authorizationHeader(snap);
        if (authorization.isEmpty()) {
            return new Result(false, -1, "auth_sign_failed");
        }
        if (command == Command.RESUME) {
            Log.i(TAG, "POST resume stored=" + fromStoredCapture
                    + " initialized=" + snap.initialized
                    + " store=" + VesperaLastTarget.storeId());
        }
        String[] paths;
        if (command == Command.SHUTDOWN) {
            paths = new String[] {
                    command.path,
                    "/v1/general/shutdown",
                    "/v1/general/powerOff",
                    "/v1/device/shutdown",
                    "/v1/board/shutdown",
                    "/v1/general/requestShutdown"
            };
        } else if (command == Command.RESUME && fromStoredCapture) {
            paths = new String[] {
                    "/v1/captureStore/startObservationFromStoredCapture",
                    command.path,
                    "/v1/general/resumeObservation"
            };
        } else if (command == Command.RESUME) {
            paths = new String[] { command.path, "/v1/general/resumeObservation" };
        } else {
            paths = new String[] { command.path };
        }
        Result last = new Result(false, -1, "HTTP");
        try {
            for (String path : paths) {
                boolean shutdown = command == Command.SHUTDOWN;
                String postBody = body;
                if (command == Command.RESUME && fromStoredCapture
                        && !path.contains("FromStoredCapture")) {
                    // Fallback paths need the full startObservation payload.
                    String full = VesperaLastTarget.startObservationBody(snap);
                    if (!full.isEmpty()) postBody = full;
                }
                VesperaHttp.Response response = VesperaHttp.post(
                        network, host, port, path, postBody, authorization, TIMEOUT_MS, shutdown);
                if (shutdown && response.code == 0) {
                    return new Result(true, 0, "shutdown_started");
                }
                boolean ok = response.code >= 200 && response.code < 300
                        && !isFirmwareFailure(response.body);
                if (!ok && response.code == 401) {
                    return new Result(false, response.code, "auth_required");
                }
                String msg = response.body.isEmpty()
                        ? ("HTTP " + response.code) : truncate(response.body, 400);
                last = new Result(ok, response.code, msg);
                if (ok) {
                    if (shutdown) return new Result(true, response.code, "shutdown_started");
                    return last;
                }
                boolean tryNext = response.code == 404 || response.code == 405
                        || (command == Command.RESUME && isIncorrectParams(response.body))
                        || (shutdown && response.code != 401 && !ok);
                if (!tryNext) return last;
            }
            if (command == Command.RESUME && !haveTarget) {
                return new Result(false, last.httpCode,
                        last.message.isEmpty() ? "no_target" : last.message);
            }
            return last;
        } catch (Exception failure) {
            Log.w(TAG, command.path + ": " + failure.getMessage());
            if (command == Command.SHUTDOWN && VesperaHttp.isHangup(failure)) {
                return new Result(true, 0, "shutdown_started");
            }
            return new Result(false, -1, failure.getMessage());
        }
    }

    /**
     * Starts auto-init if needed and waits until AUTO_INIT has stopped (or
     * motors are calibrated). Does not wait for {@code initialized=true}:
     * that flag often stays false until after the resume POST.
     */
    private static Result ensureInitialized(String host, int port, Network network,
            VesperaStatusSnapshot snap, VesperaLocationClient.Site initSite) {
        if (readyToResumeAfterInit(snap)) {
            return new Result(true, 200, "already_initialized");
        }
        String body = autoInitBody(initSite);
        if (body.isEmpty()) {
            return new Result(false, -1, "no_site");
        }
        boolean hadFinishedInit = snap != null && snap.isAutoInitFinishedOk();
        boolean calibratedAtStart = snap != null && snap.azAltCalibrated();
        boolean sawRunning = snap != null && snap.isAutoInitRunning();
        boolean posted = false;
        if (snap == null || !snap.isAutoInitRunning()) {
            String authorization = VesperaApiAuth.authorizationHeader(snap);
            if (authorization.isEmpty()) {
                return new Result(false, -1, "auth_sign_failed");
            }
            try {
                VesperaHttp.Response response = VesperaHttp.post(
                        network, host, port, Command.INIT.path, body, authorization, TIMEOUT_MS, false);
                boolean ok = response.code >= 200 && response.code < 300
                        && !isFirmwareFailure(response.body);
                if (!ok && response.code != 0) {
                    // code 0 can happen with odd proxies; still poll status
                    if (response.code == 401) {
                        return new Result(false, 401, "auth_required");
                    }
                    if (response.code > 0 && !isAutoInitAccepted(response)) {
                        String msg = response.body.isEmpty()
                                ? ("HTTP " + response.code) : truncate(response.body, 400);
                        return new Result(false, response.code, msg);
                    }
                }
                posted = true;
                Log.i(TAG, "auto-init started before resume");
            } catch (Exception failure) {
                Log.w(TAG, "auto-init: " + failure.getMessage());
                // Continue polling — init may still have been accepted.
                posted = true;
            }
        }
        long deadline = System.currentTimeMillis() + INIT_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(INIT_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Result(false, -1, "init_interrupted");
            }
            VesperaStatusClient.Result status = VesperaStatusClient.fetchResult(host, port, network);
            VesperaStatusSnapshot now = status.snapshot;
            if (now == null) continue;
            if (now.isAutoInitRunning()) sawRunning = true;
            String fail = now.autoInitFailure(sawRunning);
            if (!fail.isEmpty()) {
                return new Result(false, -1, "init_failed: " + fail);
            }
            if (autoInitReadyForResume(now, sawRunning, hadFinishedInit, calibratedAtStart,
                    posted)) {
                Log.i(TAG, "auto-init complete, sending resume"
                        + " initialized=" + now.initialized
                        + " finished=" + now.isAutoInitFinishedOk()
                        + " calibrated=" + now.azAltCalibrated());
                return new Result(true, 200, "init_ok");
            }
        }
        return new Result(false, -1, "init_timeout");
    }

    /**
     * Ready to POST resume: AUTO_INIT is not running. {@code initialized} is
     * optional — firmware often does not flip it before startObservation.
     */
    static boolean readyToResumeAfterInit(VesperaStatusSnapshot snap) {
        if (snap == null) return false;
        if (snap.isAutoInitRunning()) return false;
        return snap.initialized || snap.isAutoInitFinishedOk() || snap.azAltCalibrated();
    }

    private static boolean autoInitReadyForResume(VesperaStatusSnapshot now,
            boolean sawRunning, boolean hadFinishedInit, boolean calibratedAtStart,
            boolean posted) {
        if (now == null || now.isAutoInitRunning()) return false;
        if (now.initialized) return true;
        if (sawRunning && now.isAutoInitFinishedOk()) return true;
        if (!hadFinishedInit && now.isAutoInitFinishedOk()) return true;
        if ((posted || sawRunning) && !calibratedAtStart && now.azAltCalibrated()) return true;
        return false;
    }

    private static boolean isAutoInitAccepted(VesperaHttp.Response response) {
        if (response == null) return false;
        if (response.code >= 200 && response.code < 300) return true;
        // Some firmwares return empty body on accept.
        return response.code == 0;
    }

    /**
     * Firmware {@code startAutoInit} requires {@code time} (Date.now ms),
     * {@code latitude} and {@code longitude}. Empty {@code {}} is rejected with
     * CHECKPARAMS.INCORRECT_PARAMS.
     */
    private static String autoInitBody(VesperaLocationClient.Site site) {
        if (site == null) return "";
        try {
            JSONObject body = new JSONObject();
            body.put("time", System.currentTimeMillis());
            body.put("latitude", site.lat);
            body.put("longitude", site.lon);
            return body.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isIncorrectParams(String responseBody) {
        if (responseBody == null) return false;
        String text = responseBody.toUpperCase(java.util.Locale.US);
        return text.contains("INCORRECT_PARAMS") || text.contains("CHECKPARAMS");
    }

    private static boolean isFirmwareFailure(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) return false;
        String trim = responseBody.trim();
        if (trim.charAt(0) != '{') return false;
        try {
            JSONObject json = new JSONObject(trim);
            if (json.has("success") && !json.optBoolean("success", true)) return true;
            return false;
        } catch (Exception ignored) {
            return isIncorrectParams(responseBody);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    /**
     * After PARK, wait one minute then poll status every minute until no
     * park/stop/slew/observation is active. Returns false if still busy after
     * {@link #PARK_IDLE_WAIT_MS}.
     */
    static boolean waitUntilIdleAfterPark(String host, int port, Network network,
            String reason) {
        long deadline = System.currentTimeMillis() + PARK_IDLE_WAIT_MS;
        String last = "";
        while (true) {
            try {
                Thread.sleep(PARK_IDLE_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            VesperaStatusSnapshot snap = VesperaStatusClient.fetch(
                    host == null || host.isEmpty() ? "10.0.0.1" : host, port, network);
            if (snap != null) {
                if (snap.isShuttingDown()) {
                    Log.i(TAG, "idle (" + reason + "): already shutting down");
                    return true;
                }
                if (!snap.isBusyForShutdown()) {
                    if (!last.isEmpty()) {
                        Log.i(TAG, "idle (" + reason + ") after " + last);
                    } else {
                        Log.i(TAG, "idle (" + reason + ")");
                    }
                    return true;
                }
                last = snap.busyLabel();
                Log.i(TAG, "waiting (" + reason + ") op=" + last);
            } else {
                Log.i(TAG, "waiting (" + reason + "): status unavailable");
            }
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "still busy (" + reason + ") op=" + last);
                return false;
            }
        }
    }
}
