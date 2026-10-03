package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fetches Vespera REST status (StellinAPI). Challenge is used to sign commands. */
final class VesperaStatusClient {
    private static final String TAG = "VesperaStatus";
    private static final int TIMEOUT_MS = 4_000;
    private static final int TCP_TIMEOUT_MS = 1_500;
    private static final String[] PATHS = {"/v1/app/status", "/v2/app/status"};

    static final class Result {
        final VesperaStatusSnapshot snapshot;
        final String error;

        Result(VesperaStatusSnapshot snapshot, String error) {
            this.snapshot = snapshot;
            this.error = error == null ? "" : error;
        }
    }

    private VesperaStatusClient() {}

    static VesperaStatusSnapshot fetch(String host, int preferredPort, Network network) {
        return fetchResult(host, preferredPort, network).snapshot;
    }

    static Result fetchResult(String host, int preferredPort, Network network) {
        if (host == null || host.isEmpty()) host = "10.0.0.1";
        String lastError = "";
        VesperaStatusSnapshot best = null;
        for (int port : restPorts(preferredPort)) {
            String target = host + ":" + port;
            if (!VesperaHttp.portOpen(network, host, port, TCP_TIMEOUT_MS)) {
                lastError = target + " TCP closed";
                Log.d(TAG, lastError);
                continue;
            }
            for (String path : PATHS) {
                String endpoint = target + path;
                try {
                    VesperaHttp.Response response = VesperaHttp.get(
                            network, host, port, path, TIMEOUT_MS);
                    if (response.code < 200 || response.code >= 300) {
                        lastError = endpoint + " HTTP " + response.code;
                        Log.d(TAG, lastError);
                        continue;
                    }
                    String json = extractJson(response.body);
                    if (json.isEmpty()) {
                        lastError = endpoint + " empty body";
                        continue;
                    }
                    VesperaStatusSnapshot snap = parse(endpoint, json);
                    if (snap.canSignCommands()) {
                        return new Result(snap, "");
                    }
                    if (best == null && (snap.hasInstrumentFields() || json.length() > 4)) {
                        best = snap;
                    }
                    lastError = endpoint + " missing challenge/id";
                } catch (Exception failure) {
                    lastError = endpoint + " " + failure.getClass().getSimpleName()
                            + ": " + failure.getMessage();
                    Log.d(TAG, lastError);
                }
            }
        }
        if (best != null) return new Result(best, "");
        return new Result(null, lastError);
    }

    private static int[] restPorts(int preferredPort) {
        if (preferredPort == 8082) return new int[] {8082, 8083, 8080};
        if (preferredPort == 8083) return new int[] {8082, 8083, 8080};
        if (preferredPort == 8080) return new int[] {8080, 8082, 8083};
        if (preferredPort > 0) return new int[] {preferredPort, 8082, 8083, 8080};
        return new int[] {8082, 8083, 8080};
    }

    private static String extractJson(String body) {
        if (body == null) return "";
        String trim = body.trim();
        if (trim.isEmpty()) return "";
        if (trim.charAt(0) == '\uFEFF') trim = trim.substring(1).trim();
        int obj = trim.indexOf('{');
        int arr = trim.indexOf('[');
        if (obj < 0 && arr < 0) return "";
        int start;
        if (obj < 0) start = arr;
        else if (arr < 0) start = obj;
        else start = Math.min(obj, arr);
        return trim.substring(start);
    }

    static VesperaStatusSnapshot parse(String endpoint, String json) throws Exception {
        JSONObject root = asObject(json);
        JSONObject body = payload(root);
        String telescopeId = firstNonEmpty(
                findText(body, "telescopeId"),
                findText(body, "telescope_id"),
                findText(root, "telescopeId"),
                findText(root, "telescope_id"));
        String model = firstNonEmpty(directText(body, "model"), directText(root, "model"));
        String state = firstNonEmpty(directText(body, "state"), directText(root, "state"));
        String challenge = firstNonEmpty(
                directText(body, "challenge"),
                directText(root, "challenge"));
        int bootCount = findInt(body, 0, "bootCount", "boot_count", "nbBoot", "bootNumber");
        if (bootCount == 0) {
            bootCount = findInt(root, 0, "bootCount", "boot_count", "nbBoot", "bootNumber");
        }
        boolean initialized = findBoolean(body, "initialized") || findBoolean(root, "initialized");
        JSONObject operation = currentOperationOf(body, root);
        JSONObject lastObservation = lastObservationOf(body, root, operation);
        VesperaLastTarget.rememberFromStatus(body);
        VesperaLastTarget.rememberFromStatus(root);
        String operationType = lastObservation != null ? text(lastObservation, "type") : "";
        String observationStatus = observationStatusOf(body, root, operation, lastObservation);
        String targetName = "";
        int stacking = -1;
        long exposureUs = 0;
        int gain = -1;
        JSONObject details = operation != null ? operation : lastObservation;
        JSONObject targetObj = details == null ? null : details.optJSONObject("target");
        if (targetObj == null && details != null) targetObj = details.optJSONObject("object");
        if (targetObj == null && lastObservation != null) {
            targetObj = lastObservation.optJSONObject("target");
        }
        if (VesperaLastTarget.formatCoordinates(targetObj).isEmpty() && lastObservation != null) {
            JSONObject fromLast = lastObservation.optJSONObject("target");
            if (fromLast == null) fromLast = lastObservation.optJSONObject("object");
            if (!VesperaLastTarget.formatCoordinates(fromLast).isEmpty()) {
                targetObj = fromLast;
            }
        }
        if (details != null) {
            if (targetObj != null) {
                targetName = firstNonEmpty(
                        text(targetObj, "objectName"),
                        text(targetObj, "name"),
                        text(targetObj, "catalogName"));
            }
            JSONObject capture = details.optJSONObject("capture");
            if (capture == null && lastObservation != null) {
                capture = lastObservation.optJSONObject("capture");
            }
            if (capture != null) {
                stacking = capture.optInt("stackingCount", -1);
                exposureUs = capture.optLong("exposureMicroSec", 0);
                gain = capture.optInt("gain", -1);
                JSONObject cam = capture.optJSONObject("cameraParams");
                if (cam != null) {
                    if (exposureUs <= 0) exposureUs = cam.optLong("exposureMicroSec", 0);
                    if (gain <= 0) gain = cam.optInt("gain", -1);
                }
            }
        }
        if (gain <= 0 || exposureUs <= 0) {
            JSONObject stored = lastStoredCapture(body);
            if (stored == null) stored = lastStoredCapture(root);
            if (stored != null) {
                if (exposureUs <= 0) exposureUs = stored.optLong("exposureMicroSec", 0);
                if (gain <= 0) gain = stored.optInt("gain", -1);
            }
        }
        VesperaLastTarget.remember(targetObj,
                lastObservation != null ? lastObservation : operation, body);
        String currentOpType = operation == null ? "" : text(operation, "type");
        if (!currentOpType.isEmpty()) operationType = currentOpType;
        int batteryPercent = -1;
        String batteryStatus = "";
        JSONObject battery = findObject(body, "internalBattery");
        if (battery == null) battery = findObject(body, "battery");
        if (battery == null) battery = findObject(root, "internalBattery");
        if (battery == null) battery = findObject(root, "battery");
        if (battery != null) {
            batteryPercent = battery.optInt("chargeLevel", battery.optInt("level", -1));
            batteryStatus = firstNonEmpty(
                    text(battery, "chargeStatus"), text(battery, "status"));
            if (battery.has("plugged") || battery.has("isPlugged")) {
                boolean plugged = battery.optBoolean("plugged", battery.optBoolean("isPlugged", false));
                if (batteryStatus.isEmpty() || (!plugged && !VesperaStatusSnapshot.isOffMainsPower(batteryStatus))) {
                    batteryStatus = plugged ? "CONNECTED" : "DISCONNECTED";
                }
            }
        }
        String tracking = parseTracking(body, root, operation, observationStatus);
        StorageInfo storage = parseStorageInfo(body, root);
        Log.i(TAG, "parsed " + endpoint
                + " id=" + telescopeId
                + " challenge=" + (challenge.isEmpty() ? "missing" : (challenge.length() + "c"))
                + " boot=" + bootCount
                + " op=" + operationType
                + " obs=" + observationStatus
                + " track=" + tracking
                + " storage=" + (storage.usedPercent < 0 ? "?" : (storage.usedPercent + "%")));
        return new VesperaStatusSnapshot(endpoint, telescopeId, model, state, initialized,
                operationType, observationStatus, targetName, Math.max(0, stacking), exposureUs,
                Math.max(0, gain), batteryPercent, batteryStatus, challenge, bootCount,
                tracking, parseMotors(body, root),
                parseStep(operation), parseCoordinates(targetObj),
                parseFirmware(body, root), parseFilter(body, root, details),
                parseTemperature(body, root), parseError(body, root, operation, details),
                storage.label, storage.usedPercent, parseLocation(body, root),
                parseFocus(body, root, operation), json);
    }

    private static String directText(JSONObject obj, String key) {
        return text(obj, key);
    }

    private static JSONObject directObject(JSONObject obj, String key) {
        return asJsonObject(obj == null ? null : obj.opt(key));
    }

    private static JSONObject currentOperationOf(JSONObject body, JSONObject root) {
        JSONObject current = directObject(body, "currentOperation");
        if (current == null) current = directObject(root, "currentOperation");
        if (current == null) current = directObject(body, "operation");
        if (current == null) current = directObject(root, "operation");
        if (current != null) return current;
        JSONArray others = body == null ? null : body.optJSONArray("otherCurrentOperations");
        if (others == null && root != null) others = root.optJSONArray("otherCurrentOperations");
        if (others != null) {
            for (int i = 0; i < others.length(); i++) {
                JSONObject item = others.optJSONObject(i);
                if (item != null) return item;
            }
        }
        return null;
    }

    private static JSONObject lastObservationOf(JSONObject body, JSONObject root,
            JSONObject current) {
        if (looksLikeObservation(current)) return current;
        JSONObject fromPrev = lastPreviousObservation(body);
        if (fromPrev != null) return fromPrev;
        return lastPreviousObservation(root);
    }

    private static JSONObject lastPreviousObservation(JSONObject payload) {
        if (payload == null) return null;
        Object raw = payload.opt("previousOperations");
        JSONObject preferred = null;
        JSONObject fallback = null;
        if (raw instanceof JSONObject) {
            JSONObject map = (JSONObject) raw;
            JSONObject named = map.optJSONObject("observation");
            if (looksLikeObservation(named)) {
                if (isResumable(named)) return named;
                preferred = named;
            }
            JSONArray names = map.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    JSONObject item = map.optJSONObject(names.optString(i));
                    if (!looksLikeObservation(item) || item == named) continue;
                    if (isResumable(item)) return item;
                    fallback = item;
                }
            }
            return preferred != null ? preferred : fallback;
        }
        if (raw instanceof JSONArray) {
            JSONArray previous = (JSONArray) raw;
            for (int i = previous.length() - 1; i >= 0; i--) {
                JSONObject item = previous.optJSONObject(i);
                if (!looksLikeObservation(item)) continue;
                if (isResumable(item)) return item;
                fallback = item;
            }
        }
        return fallback;
    }

    private static JSONObject lastStoredCapture(JSONObject payload) {
        if (payload == null) return null;
        JSONObject store = payload.optJSONObject("captureStore");
        if (store == null) {
            JSONObject nested = payload.optJSONObject("result");
            if (nested == null) nested = payload.optJSONObject("data");
            store = nested == null ? null : nested.optJSONObject("captureStore");
        }
        if (store == null) return null;
        JSONArray captures = store.optJSONArray("storedCaptures");
        if (captures == null || captures.length() == 0) return null;
        return captures.optJSONObject(captures.length() - 1);
    }

    private static boolean isResumable(JSONObject op) {
        String store = storeState(op);
        return store.contains("TO_BE_RESUMABLE")
                || (store.contains("RESUMABLE") && !store.contains("NON_"));
    }

    private static String storeState(JSONObject op) {
        if (op == null) return "";
        JSONObject store = op.optJSONObject("store");
        String direct = text(store, "state");
        if (!direct.isEmpty()) return direct.toUpperCase(java.util.Locale.US);
        return text(op, "storeState").toUpperCase(java.util.Locale.US);
    }

    private static boolean looksLikeObservation(JSONObject op) {
        if (op == null) return false;
        String type = text(op, "type").toUpperCase(java.util.Locale.US);
        if (type.contains("TRACK") || type.contains("GOTO") || type.contains("SLEW")
                || type.contains("PARK") || type.contains("INIT")) {
            return false;
        }
        if (type.contains("OBSERV") || type.contains("MOSAIC") || type.contains("COVAL")
                || type.contains("CAPTURE") || type.contains("IMAG")) {
            return true;
        }
        return op.optJSONObject("target") != null && op.optJSONObject("capture") != null;
    }

    private static String observationStatusOf(JSONObject body, JSONObject root,
            JSONObject current, JSONObject last) {
        if (isActiveObservation(current) || captureOpen(current)) return "RUNNING";
        boolean currentIsObservation = looksLikeObservation(current);
        if (!currentIsObservation
                && (isActiveObservation(last) || captureOpen(last))) {
            return "RUNNING";
        }
        if (motorsAreTracking(body, root) && liveWork(current, last)) return "RUNNING";
        String stopped = stoppedLabel(current);
        // A finished previous night must not cover the operation in progress.
        if (stopped.isEmpty() && !currentIsObservation) stopped = stoppedLabel(last);
        if (!stopped.isEmpty()) return stopped;
        if (looksLikeObservation(last) && current == null) return "STOPPED";
        return "";
    }

    /** Capture still taking frames, even if the parent operation looks closed. */
    private static boolean captureOpen(JSONObject op) {
        if (!looksLikeObservation(op)) return false;
        JSONObject capture = op.optJSONObject("capture");
        if (capture == null || capture.optBoolean("stopped", false)) return false;
        return capture.has("startTime") || capture.optInt("acquisitionCount", 0) > 0
                || capture.optInt("stackingCount", 0) > 0
                || !op.optBoolean("stopped", false);
    }

    private static boolean liveWork(JSONObject current, JSONObject last) {
        if (captureOpen(current) || captureOpen(last)) return true;
        if (isActiveObservation(current) || isActiveObservation(last)) return true;
        if (current == null || current.optBoolean("stopped", false)) return false;
        String type = text(current, "type").toUpperCase(java.util.Locale.US);
        return type.contains("OBSERV") || type.contains("CAPTURE") || type.contains("MOSAIC")
                || type.contains("FOCUS") || type.contains("POINT") || type.contains("TRACK")
                || type.contains("GOTO") || type.contains("SLEW");
    }

    private static boolean motorsAreTracking(JSONObject body, JSONObject root) {
        return "ON".equals(trackingFromMotors(body, root));
    }

    private static boolean isActiveObservation(JSONObject op) {
        if (!looksLikeObservation(op)) return false;
        // Firmware keeps store.state at NON_RESUMABLE for the whole standard
        // session, and often writes endTime while pointing or stacking.
        // The session is live until the operation itself is stopped.
        if (op.optBoolean("stopped", false)) return false;
        String status = operationPhase(op).toUpperCase(java.util.Locale.US);
        return status.isEmpty() || !isStoppedPhase(status);
    }

    private static boolean hasEnded(JSONObject op) {
        if (!looksLikeObservation(op)) return false;
        if (isActiveObservation(op)) return false;
        if (op.optBoolean("stopped", false)) return true;
        return isStoppedPhase(operationPhase(op));
    }

    private static String stoppedLabel(JSONObject op) {
        if (!looksLikeObservation(op) || !hasEnded(op)) return "";
        if (isResumable(op) || hasOperationError(op)) return "STOPPED";
        String store = storeState(op);
        String phase = operationPhase(op).toUpperCase(java.util.Locale.US);
        if (store.contains("FINISH") || phase.contains("FINISH")) return "FINISHED";
        // Closed standard session, no error: the night actually completed.
        if (op.optBoolean("stopped", false) && store.contains("NON_RESUMABLE")) {
            return "FINISHED";
        }
        return "STOPPED";
    }

    private static boolean hasOperationError(JSONObject op) {
        if (op == null) return false;
        if (errorName(op).length() > 0) return true;
        JSONObject capture = op.optJSONObject("capture");
        return capture != null && errorName(capture).length() > 0;
    }

    private static String errorName(JSONObject op) {
        if (op == null) return "";
        JSONObject error = op.optJSONObject("error");
        if (error != null) {
            String name = text(error, "name");
            if (!name.isEmpty()) return name;
        }
        return text(op, "error");
    }

    private static String operationPhase(JSONObject op) {
        return firstNonEmpty(
                text(op, "status"),
                text(op, "state"),
                text(op, "phase"),
                text(op, "progressStatus"));
    }

    private static boolean isStoppedPhase(String phase) {
        String u = phase == null ? "" : phase.toUpperCase(java.util.Locale.US);
        if (u.isEmpty()) return false;
        if (u.contains("STOP") || u.contains("FINISH") || u.contains("ABORT")
                || u.contains("CANCEL") || u.contains("IDLE")) {
            return true;
        }
        // "PENDING" and "EXTENDED" contain the letters END but are not terminal.
        if (u.contains("PENDING") || u.contains("EXTENDED")) return false;
        return "END".equals(u) || u.contains("ENDED") || u.contains("_END")
                || u.contains("END_") || u.contains(" END");
    }

    private static String parseTracking(JSONObject body, JSONObject root, JSONObject operation,
            String observationStatus) {
        String fromMotors = trackingFromMotors(body, root);
        if (!fromMotors.isEmpty()) return fromMotors;
        String fromStep = trackingFromSteps(operation);
        if (!fromStep.isEmpty()) return fromStep;
        String flagged = trackingFlag(body, root, operation);
        if (!flagged.isEmpty()) return flagged;
        String type = operation == null ? "" : text(operation, "type").toUpperCase(java.util.Locale.US);
        if (type.contains("TRACK")) return "ON";
        String state = firstNonEmpty(directText(body, "state"), directText(root, "state"))
                .toUpperCase(java.util.Locale.US);
        if (state.contains("TRACK")) return "ON";
        if ("STOPPED".equals(observationStatus) || "FINISHED".equals(observationStatus)) {
            return "OFF";
        }
        return "OFF";
    }

    private static String trackingFromMotors(JSONObject body, JSONObject root) {
        JSONObject motors = findObject(body, "motors");
        if (motors == null) motors = findObject(root, "motors");
        if (motors == null) return "";
        boolean starting = false;
        String[] axes = {"AZ", "az", "azimuth", "ALT", "alt", "altitude"};
        for (String key : axes) {
            JSONObject axis = motors.optJSONObject(key);
            if (axis == null) continue;
            String state = text(axis, "state").toUpperCase(java.util.Locale.US);
            if (state.contains("TRACK")) return "ON";
            if (state.contains("START") || state.contains("MOVE") || state.contains("SLEW")
                    || state.contains("GO")) {
                starting = true;
            }
        }
        return starting ? "STARTING" : "";
    }

    private static String trackingFlag(JSONObject body, JSONObject root, JSONObject operation) {
        if (explicitTrue(body, "tracking") || explicitTrue(root, "tracking")
                || explicitTrue(operation, "tracking")) {
            return "ON";
        }
        String named = firstNonEmpty(
                trackingWord(body, "tracking", "trackingState", "trackingStatus"),
                trackingWord(root, "tracking", "trackingState", "trackingStatus"),
                trackingWord(operation, "tracking", "trackingState", "trackingStatus"));
        if (!named.isEmpty()) return named;
        if (explicitFalse(body, "tracking") || explicitFalse(root, "tracking")
                || explicitFalse(operation, "tracking")) {
            return "OFF";
        }
        return "";
    }

    private static String trackingWord(JSONObject obj, String... keys) {
        if (obj == null) return "";
        for (String key : keys) {
            String value = text(obj, key).toUpperCase(java.util.Locale.US);
            if (value.isEmpty()) continue;
            if ("TRUE".equals(value) || "ON".equals(value) || value.contains("TRACK")) return "ON";
            if ("FALSE".equals(value) || "OFF".equals(value) || "IDLE".equals(value)
                    || "STOP".equals(value)) {
                return "OFF";
            }
        }
        return "";
    }

    private static String trackingFromSteps(JSONObject operation) {
        return trackingFromSteps(operation == null ? null : operation.optJSONArray("steps"));
    }

    private static String trackingFromSteps(JSONArray steps) {
        if (steps == null) return "";
        String found = "";
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            String nested = trackingFromSteps(step.optJSONArray("steps"));
            if (!nested.isEmpty()) found = nested;
            String type = text(step, "type").toUpperCase(java.util.Locale.US);
            if (!type.contains("TRACK")) continue;
            String status = firstNonEmpty(text(step, "status"), text(step, "state"))
                    .toUpperCase(java.util.Locale.US);
            if (status.contains("FAIL") || status.contains("STOP") || status.contains("IDLE")) {
                continue;
            }
            double progress = step.optDouble("progress", -1);
            found = (progress >= 0 && progress < 1.0) || status.contains("START")
                    ? "STARTING" : "ON";
        }
        return found;
    }

    private static boolean explicitTrue(JSONObject obj, String key) {
        return obj != null && obj.has(key) && !obj.isNull(key) && obj.optBoolean(key, false);
    }

    private static boolean explicitFalse(JSONObject obj, String key) {
        return obj != null && obj.has(key) && !obj.isNull(key) && !obj.optBoolean(key, true);
    }

    private static String parseMotors(JSONObject body, JSONObject root) {
        JSONObject motors = findObject(body, "motors");
        if (motors == null) motors = findObject(root, "motors");
        if (motors == null) return "";
        String az = motorPos(motors, "AZ", "az", "azimuth");
        String alt = motorPos(motors, "ALT", "alt", "altitude");
        if (az.isEmpty() && alt.isEmpty()) return "";
        if (az.isEmpty()) return "ALT " + alt;
        if (alt.isEmpty()) return "AZ " + az;
        return "AZ " + az + "  ·  ALT " + alt;
    }

    private static String parseStep(JSONObject operation) {
        if (operation == null) return "";
        String fromSteps = stepFromArray(operation.optJSONArray("steps"));
        if (!fromSteps.isEmpty()) return fromSteps;
        String named = firstNonEmpty(
                text(operation, "currentStep"),
                text(operation, "step"),
                text(operation, "phase"),
                text(operation, "progressStatus"));
        if (named.isEmpty()) return "";
        return withPercent(named, operation.optDouble("progress", -1));
    }

    private static String stepFromArray(JSONArray steps) {
        if (steps == null) return "";
        String current = "";
        String lastDone = "";
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            String type = firstNonEmpty(text(step, "type"), text(step, "name"), text(step, "id"));
            if (type.isEmpty()) continue;
            String status = firstNonEmpty(text(step, "status"), text(step, "state"))
                    .toUpperCase(java.util.Locale.US);
            double progress = step.optDouble("progress", -1);
            if (status.contains("FAIL") || status.contains("ERROR") || status.contains("ABORT")) {
                current = type + " failed";
                continue;
            }
            if ((progress >= 0 && progress < 1.0)
                    || status.contains("RUN") || status.contains("PROGRESS")
                    || status.contains("START") || status.contains("ING")) {
                current = withPercent(type, progress);
            } else {
                lastDone = withPercent(type, progress);
            }
        }
        return current.isEmpty() ? lastDone : current;
    }

    private static String withPercent(String label, double progress) {
        if (progress < 0) return label;
        int percent = progress <= 1.0
                ? (int) Math.round(progress * 100.0)
                : (int) Math.round(progress);
        if (percent < 0) return label;
        if (percent > 100) percent = 100;
        if (percent == 100 && progress <= 1.0) return label;
        return label + " " + percent + "%";
    }

    private static String parseCoordinates(JSONObject target) {
        return VesperaLastTarget.formatCoordinates(target);
    }

    private static String parseFirmware(JSONObject body, JSONObject root) {
        String found = firmwareOf(body);
        if (found.isEmpty()) found = firmwareOf(root);
        return found;
    }

    private static String firmwareOf(JSONObject obj) {
        if (obj == null) return "";
        String direct = firstNonEmpty(
                text(obj, "firmwareVersion"),
                text(obj, "softwareVersion"),
                text(obj, "fwVersion"),
                text(obj, "version"));
        if (!direct.isEmpty()) return direct;
        JSONObject nested = firstObject(obj, "software", "firmware", "device", "info", "system");
        if (nested != null) {
            String fromNested = firstNonEmpty(
                    text(nested, "firmwareVersion"),
                    text(nested, "softwareVersion"),
                    text(nested, "fwVersion"),
                    text(nested, "version"));
            if (!fromNested.isEmpty()) return fromNested;
        }
        return firstNonEmpty(text(obj, "apiVersion"),
                nested == null ? "" : text(nested, "apiVersion"));
    }

    private static String parseFilter(JSONObject body, JSONObject root, JSONObject details) {
        String found = filterOf(details);
        if (found.isEmpty()) found = filterOf(body);
        if (found.isEmpty()) found = filterOf(root);
        return found;
    }

    private static String filterOf(JSONObject obj) {
        if (obj == null) return "";
        String direct = firstNonEmpty(
                text(obj, "filterName"),
                text(obj, "opticalFilter"),
                text(obj, "filterType"),
                text(obj, "filter"));
        if (!direct.isEmpty()) return direct;
        JSONObject filter = obj.optJSONObject("filter");
        if (filter != null) {
            return firstNonEmpty(
                    text(filter, "name"),
                    text(filter, "type"),
                    text(filter, "id"),
                    text(filter, "slot"));
        }
        JSONObject target = obj.optJSONObject("target");
        return target == null ? "" : filterOf(target);
    }

    private static String parseTemperature(JSONObject body, JSONObject root) {
        String found = temperatureOf(body);
        if (found.isEmpty()) found = temperatureOf(root);
        return found;
    }

    private static String temperatureOf(JSONObject obj) {
        if (obj == null) return "";
        String[] keys = {
                "temperature", "temp", "ambientTemperature", "sensorTemperature",
                "cpuTemperature", "boardTemperature"
        };
        for (String key : keys) {
            String formatted = formatTemperature(obj.opt(key));
            if (!formatted.isEmpty()) return formatted;
        }
        JSONObject nested = firstObject(obj, "sensors", "environment", "device", "thermal");
        return nested == null || nested == obj ? "" : temperatureOf(nested);
    }

    private static String formatTemperature(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof Number) {
            return String.format(java.util.Locale.US, "%.1f °C", ((Number) value).doubleValue());
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            Object nested = obj.has("celsius") ? obj.opt("celsius")
                    : obj.has("c") ? obj.opt("c")
                    : obj.has("value") ? obj.opt("value")
                    : obj.has("temp") ? obj.opt("temp")
                    : obj.has("temperature") ? obj.opt("temperature")
                    : null;
            return formatTemperature(nested);
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? "" : text;
    }

    private static String parseError(JSONObject body, JSONObject root, JSONObject operation,
            JSONObject details) {
        String found = errorOf(operation);
        if (found.isEmpty()) found = errorOf(details);
        if (found.isEmpty()) found = errorOf(body);
        if (found.isEmpty()) found = errorOf(root);
        if (found.isEmpty()) found = failedStep(operation);
        return found;
    }

    private static String errorOf(JSONObject obj) {
        if (obj == null) return "";
        String direct = firstNonEmpty(
                text(obj, "errorMessage"),
                text(obj, "lastError"),
                text(obj, "errorType"),
                text(obj, "failure"),
                text(obj, "error"));
        if (!direct.isEmpty()) return direct;
        JSONObject nested = firstObject(obj, "error", "lastError", "failure");
        if (nested == null) return "";
        return firstNonEmpty(
                text(nested, "message"),
                text(nested, "type"),
                text(nested, "code"),
                text(nested, "name"));
    }

    private static String failedStep(JSONObject operation) {
        if (operation == null) return "";
        JSONArray steps = operation.optJSONArray("steps");
        if (steps == null) return "";
        for (int i = steps.length() - 1; i >= 0; i--) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            String status = firstNonEmpty(text(step, "status"), text(step, "state"))
                    .toUpperCase(java.util.Locale.US);
            if (status.contains("FAIL") || status.contains("ERROR") || status.contains("ABORT")) {
                String type = firstNonEmpty(text(step, "type"), text(step, "name"));
                String message = firstNonEmpty(text(step, "error"), text(step, "message"));
                if (type.isEmpty()) return message.isEmpty() ? status : message;
                return message.isEmpty() ? type + " failed" : type + ": " + message;
            }
        }
        return "";
    }

    private static final class StorageInfo {
        final String label;
        final int usedPercent;

        StorageInfo(String label, int usedPercent) {
            this.label = label == null ? "" : label;
            this.usedPercent = usedPercent;
        }
    }

    private static StorageInfo parseStorageInfo(JSONObject body, JSONObject root) {
        String[] nestedKeys = {
                "photos", "user", "userStorage", "pictures", "media",
                "internal", "internalMemory", "internalStorage", "emmc", "flash",
                "storage", "sdCard", "diskSpace", "availableStorage", "disk", "memory"
        };
        StorageInfo best = StorageInfoEmpty();
        JSONObject[] roots = { body, root };
        for (JSONObject src : roots) {
            if (src == null) continue;
            for (String key : nestedKeys) {
                StorageInfo candidate = storageFrom(findObject(src, key));
                best = preferStorage(best, candidate);
            }
            best = preferStorage(best, topLevelSpace(src));
        }
        return best;
    }

    private static StorageInfo StorageInfoEmpty() {
        return new StorageInfo("", -1);
    }

    private static StorageInfo preferStorage(StorageInfo current, StorageInfo candidate) {
        if (candidate == null || (candidate.label.isEmpty() && candidate.usedPercent < 0)) {
            return current == null ? StorageInfoEmpty() : current;
        }
        if (current == null || (current.label.isEmpty() && current.usedPercent < 0)) {
            return candidate;
        }
        if (current.usedPercent < 0 && candidate.usedPercent >= 0) return candidate;
        return current;
    }

    private static StorageInfo topLevelSpace(JSONObject obj) {
        if (obj == null) return StorageInfoEmpty();
        long used = rawLong(obj, "usedSpace", "usedBytes", "usedMemory");
        long free = rawLong(obj, "freeSpace", "availableSpace", "freeBytes");
        long total = rawLong(obj, "totalSpace", "totalBytes", "capacity");
        int percent = parsePercent(obj);
        used = toBytes(used);
        free = toBytes(free);
        total = toBytes(total);
        if (used < 0 && free >= 0 && total > free) used = total - free;
        if (total < 0 && used >= 0 && free >= 0) total = used + free;
        if (percent < 0 && used >= 0 && total > 0) {
            percent = (int) Math.round(100.0 * used / (double) total);
        }
        if (percent > 100) percent = 100;
        String label = formatStorageLabel(percent, used, free, total);
        if (label.isEmpty() && percent >= 0) label = percent + "%";
        return new StorageInfo(label, percent);
    }

    private static StorageInfo storageFrom(JSONObject obj) {
        if (obj == null) return StorageInfoEmpty();
        JSONObject nested = firstObject(obj, "photos", "user", "userStorage", "pictures", "media",
                "internal", "internalMemory", "internalStorage", "emmc", "flash");
        if (nested != null && nested != obj) {
            StorageInfo nestedInfo = storageFromObject(nested);
            if (nestedInfo.usedPercent >= 0 || !nestedInfo.label.isEmpty()) return nestedInfo;
        }
        return storageFromObject(obj);
    }

    private static StorageInfo storageFromObject(JSONObject obj) {
        if (obj == null) return StorageInfoEmpty();
        long used = rawLong(obj, "used", "usedSpace", "usedBytes", "usedMemory",
                "occupied", "usage", "usedSize");
        long free = rawLong(obj, "free", "freeSpace", "available", "availableSpace",
                "freeBytes", "remaining", "availableSize");
        long total = rawLong(obj, "total", "totalSpace", "capacity", "totalBytes",
                "size", "totalSize", "totalMemory");
        int percent = parsePercent(obj);
        used = toBytes(used);
        free = toBytes(free);
        total = toBytes(total);
        if (used < 0 && free >= 0 && total > free) used = total - free;
        if (total < 0 && used >= 0 && free >= 0) total = used + free;
        if (percent < 0 && used >= 0 && total > 0) {
            percent = (int) Math.round(100.0 * used / (double) total);
        }
        if (percent > 100) percent = 100;
        String label = formatStorageLabel(percent, used, free, total);
        if (label.isEmpty() && percent >= 0) label = percent + "%";
        return new StorageInfo(label, percent);
    }

    private static int parsePercent(JSONObject obj) {
        double value = rawDouble(obj, "usedPercent", "percentUsed", "usagePercent",
                "usedPercentage", "occupancy", "percent");
        if (Double.isNaN(value) || value < 0) return -1;
        if (value <= 1.0) value *= 100.0;
        if (value > 100) return -1;
        return (int) Math.round(value);
    }

    private static String formatStorageLabel(int percent, long used, long free, long total) {
        if (percent >= 0 && used >= 0 && total > 0) {
            return percent + "%  ·  " + formatBytes(used) + " / " + formatBytes(total);
        }
        if (percent >= 0 && free >= 0 && total > 0) {
            return percent + "%  ·  " + formatBytes(free) + " / " + formatBytes(total);
        }
        if (percent >= 0) return percent + "%";
        if (used >= 0 && total > 0) return formatBytes(used) + " / " + formatBytes(total);
        if (free >= 0 && total > 0) return formatBytes(free) + " / " + formatBytes(total);
        if (free >= 0) return formatBytes(free);
        if (total > 0) return formatBytes(total);
        return "";
    }

    private static long toBytes(long value) {
        if (value < 0) return -1;
        if (value == 0) return 0;
        if (value <= 64) return value * 1024L * 1024L * 1024L;
        if (value <= 512_000L) return value * 1024L * 1024L;
        return value;
    }

    private static long rawLong(JSONObject obj, String... keys) {
        double value = rawDouble(obj, keys);
        if (Double.isNaN(value)) return -1;
        return Math.round(value);
    }

    private static double rawDouble(JSONObject obj, String... keys) {
        if (obj == null) return Double.NaN;
        for (String key : keys) {
            if (!obj.has(key) || obj.isNull(key)) continue;
            Object value = obj.opt(key);
            if (value instanceof Number) return ((Number) value).doubleValue();
            if (value instanceof String) {
                String text = ((String) value).trim().replace("%", "").replace(",", ".");
                if (text.isEmpty()) continue;
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Double.NaN;
    }

    private static String parseLocation(JSONObject body, JSONObject root) {
        String found = locationOf(body);
        if (found.isEmpty()) found = locationOf(root);
        return found;
    }

    private static String locationOf(JSONObject obj) {
        if (obj == null) return "";
        String pair = latLonOf(obj);
        if (!pair.isEmpty()) return pair;
        String named = firstNonEmpty(text(obj, "site"), text(obj, "locationName"));
        if (!named.isEmpty()) return named;
        JSONObject nested = firstObject(obj, "location", "gps", "site", "geo");
        if (nested == null || nested == obj) return "";
        pair = latLonOf(nested);
        if (!pair.isEmpty()) {
            String site = firstNonEmpty(text(nested, "name"), text(nested, "site"));
            return site.isEmpty() ? pair : site + "  ·  " + pair;
        }
        return firstNonEmpty(text(nested, "name"), text(nested, "site"));
    }

    private static String latLonOf(JSONObject obj) {
        if (obj == null) return "";
        if (!(obj.has("latitude") || obj.has("lat"))
                || !(obj.has("longitude") || obj.has("lon") || obj.has("lng"))) {
            return "";
        }
        try {
            double lat = obj.has("latitude") ? obj.getDouble("latitude") : obj.getDouble("lat");
            double lon = obj.has("longitude") ? obj.getDouble("longitude")
                    : obj.has("lon") ? obj.getDouble("lon") : obj.getDouble("lng");
            return String.format(java.util.Locale.US, "%.4f, %.4f", lat, lon);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String parseFocus(JSONObject body, JSONObject root, JSONObject operation) {
        String found = focusOf(operation);
        if (found.isEmpty()) found = focusOf(body);
        if (found.isEmpty()) found = focusOf(root);
        return found;
    }

    private static String focusOf(JSONObject obj) {
        if (obj == null) return "";
        String formatted = formatFocus(obj.opt("focusPosition"));
        if (formatted.isEmpty()) formatted = formatFocus(obj.opt("focus"));
        if (!formatted.isEmpty()) return formatted;
        JSONObject nested = firstObject(obj, "focus", "focuser", "autofocus");
        if (nested == null) return "";
        formatted = formatFocus(nested.opt("position"));
        if (formatted.isEmpty()) formatted = formatFocus(nested.opt("value"));
        if (formatted.isEmpty()) formatted = formatFocus(nested.opt("step"));
        return formatted;
    }

    private static String formatFocus(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof Number) {
            double n = ((Number) value).doubleValue();
            if (n == Math.rint(n)) return String.valueOf((long) n);
            return String.format(java.util.Locale.US, "%.1f", n);
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            String nested = formatFocus(obj.opt("position"));
            if (nested.isEmpty()) nested = formatFocus(obj.opt("value"));
            if (nested.isEmpty()) nested = formatFocus(obj.opt("step"));
            return nested;
        }
        return "";
    }

    private static String formatBytes(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1) return String.format(java.util.Locale.US, "%.1f GB", gb);
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(java.util.Locale.US, "%.0f MB", mb);
    }

    private static String motorPos(JSONObject motors, String... keys) {
        for (String key : keys) {
            JSONObject axis = motors.optJSONObject(key);
            if (axis != null && axis.has("position") && !axis.isNull("position")) {
                return String.format(java.util.Locale.US, "%.1f°", axis.optDouble("position"));
            }
            if (motors.has(key) && !motors.isNull(key) && !(motors.opt(key) instanceof JSONObject)) {
                try {
                    return String.format(java.util.Locale.US, "%.1f°", motors.getDouble(key));
                } catch (Exception ignored) {
                }
            }
        }
        return "";
    }

    private static String findText(JSONObject root, String key) {
        String direct = text(root, key);
        if (!direct.isEmpty()) return direct;
        JSONObject nested = unwrap(root);
        if (nested != null && nested != root) {
            String fromNested = text(nested, key);
            if (!fromNested.isEmpty()) return fromNested;
        }
        return findTextDeep(root, key, 0);
    }

    private static int findInt(JSONObject root, int fallback, String... keys) {
        for (String key : keys) {
            if (root.has(key) && !root.isNull(key)) {
                return root.optInt(key, fallback);
            }
        }
        JSONObject nested = unwrap(root);
        if (nested != null && nested != root) {
            for (String key : keys) {
                if (nested.has(key) && !nested.isNull(key)) {
                    return nested.optInt(key, fallback);
                }
            }
        }
        return findIntDeep(root, fallback, keys, 0);
    }

    private static boolean findBoolean(JSONObject root, String key) {
        if (root.has(key) && !root.isNull(key)) return root.optBoolean(key, false);
        JSONObject nested = unwrap(root);
        if (nested != null && nested != root && nested.has(key) && !nested.isNull(key)) {
            return nested.optBoolean(key, false);
        }
        return findBooleanDeep(root, key, 0);
    }

    private static JSONObject findObject(JSONObject root, String key) {
        JSONObject direct = asJsonObject(root == null ? null : root.opt(key));
        if (direct != null) return direct;
        JSONObject nested = unwrap(root);
        if (nested != null && nested != root) {
            JSONObject fromNested = asJsonObject(nested.opt(key));
            if (fromNested != null) return fromNested;
        }
        return findObjectDeep(root, key, 0);
    }

    private static JSONObject asObject(String json) throws Exception {
        String trim = json == null ? "" : json.trim();
        if (trim.startsWith("[")) {
            JSONArray array = new JSONArray(trim);
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof JSONObject) return (JSONObject) item;
            }
            throw new IllegalStateException("JSON array");
        }
        return new JSONObject(trim);
    }

    /** Vaonis wraps instrument fields in {@code result} ({@code success}+{@code result}). */
    private static JSONObject payload(JSONObject root) {
        JSONObject current = root;
        for (int i = 0; i < 3 && current != null; i++) {
            JSONObject next = firstObject(current, "result", "data", "status", "payload");
            if (next == null) break;
            current = next;
        }
        return current == null ? root : current;
    }

    private static JSONObject unwrap(JSONObject root) {
        return payload(root);
    }

    private static JSONObject firstObject(JSONObject obj, String... keys) {
        if (obj == null) return null;
        for (String key : keys) {
            JSONObject nested = asJsonObject(obj.opt(key));
            if (nested != null) return nested;
        }
        return null;
    }

    private static JSONObject asJsonObject(Object value) {
        if (value instanceof JSONObject) return (JSONObject) value;
        if (value instanceof String) {
            String trim = ((String) value).trim();
            if (trim.startsWith("{")) {
                try {
                    return new JSONObject(trim);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String findTextDeep(JSONObject obj, String key, int depth) {
        if (obj == null || depth > 5) return "";
        String direct = text(obj, key);
        if (!direct.isEmpty()) return direct;
        JSONArray names = obj.names();
        if (names == null) return "";
        for (int i = 0; i < names.length(); i++) {
            Object child = obj.opt(names.optString(i));
            if (child instanceof JSONObject) {
                String found = findTextDeep((JSONObject) child, key, depth + 1);
                if (!found.isEmpty()) return found;
            } else if (child instanceof JSONArray) {
                String found = findTextInArray((JSONArray) child, key, depth + 1);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static String findTextInArray(JSONArray array, String key, int depth) {
        if (array == null || depth > 5) return "";
        for (int i = 0; i < array.length(); i++) {
            Object child = array.opt(i);
            if (child instanceof JSONObject) {
                String found = findTextDeep((JSONObject) child, key, depth);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static int findIntDeep(JSONObject obj, int fallback, String[] keys, int depth) {
        if (obj == null || depth > 5) return fallback;
        for (String key : keys) {
            if (obj.has(key) && !obj.isNull(key)) return obj.optInt(key, fallback);
        }
        JSONArray names = obj.names();
        if (names == null) return fallback;
        for (int i = 0; i < names.length(); i++) {
            Object child = obj.opt(names.optString(i));
            if (child instanceof JSONObject) {
                int found = findIntDeep((JSONObject) child, Integer.MIN_VALUE, keys, depth + 1);
                if (found != Integer.MIN_VALUE) return found;
            }
        }
        return fallback;
    }

    private static boolean findBooleanDeep(JSONObject obj, String key, int depth) {
        if (obj == null || depth > 5) return false;
        if (obj.has(key) && !obj.isNull(key)) return obj.optBoolean(key, false);
        JSONArray names = obj.names();
        if (names == null) return false;
        for (int i = 0; i < names.length(); i++) {
            Object child = obj.opt(names.optString(i));
            if (child instanceof JSONObject && findBooleanDeep((JSONObject) child, key, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject findObjectDeep(JSONObject obj, String key, int depth) {
        if (obj == null || depth > 5) return null;
        JSONObject direct = asJsonObject(obj.opt(key));
        if (direct != null) return direct;
        JSONArray names = obj.names();
        if (names == null) return null;
        for (int i = 0; i < names.length(); i++) {
            Object child = obj.opt(names.optString(i));
            if (child instanceof JSONObject) {
                JSONObject found = findObjectDeep((JSONObject) child, key, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String text(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return "";
        Object value = obj.opt(key);
        if (value instanceof String) return ((String) value).trim();
        if (value instanceof JSONObject || value instanceof JSONArray) return "";
        return String.valueOf(value);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }
}
