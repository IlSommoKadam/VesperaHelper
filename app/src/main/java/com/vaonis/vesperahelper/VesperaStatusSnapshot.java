package com.vaonis.vesperahelper;

/** Parsed fields from GET /v1/app/status or /v2/app/status. */
final class VesperaStatusSnapshot {
    final String endpoint;
    final String telescopeId;
    final String model;
    final String state;
    final boolean initialized;
    final String operationType;
    final String observationStatus;
    final String targetName;
    final int stackingCount;
    final long exposureMicroSec;
    final int gain;
    final int batteryPercent;
    final String batteryStatus;
    final String challenge;
    final int bootCount;
    final String tracking;
    final String motors;
    final String step;
    final String coordinates;
    final String firmware;
    final String filter;
    final String temperature;
    final String error;
    final String storage;
    /** Occupied internal storage percent, or -1 if unknown. */
    final int storageUsedPercent;
    final String location;
    final String focus;
    final String rawJson;

    VesperaStatusSnapshot(String endpoint, String telescopeId, String model, String state,
            boolean initialized, String operationType, String observationStatus, String targetName,
            int stackingCount,
            long exposureMicroSec, int gain, int batteryPercent, String batteryStatus,
            String challenge, int bootCount, String tracking, String motors,
            String step, String coordinates, String firmware, String filter, String temperature,
            String error, String storage, int storageUsedPercent, String location, String focus,
            String rawJson) {
        this.endpoint = endpoint == null ? "" : endpoint;
        this.telescopeId = telescopeId == null ? "" : telescopeId;
        this.model = model == null ? "" : model;
        this.state = state == null ? "" : state;
        this.initialized = initialized;
        this.operationType = operationType == null ? "" : operationType;
        this.observationStatus = observationStatus == null ? "" : observationStatus;
        this.targetName = targetName == null ? "" : targetName;
        this.stackingCount = stackingCount;
        this.exposureMicroSec = exposureMicroSec;
        this.gain = gain;
        this.batteryPercent = batteryPercent;
        this.batteryStatus = batteryStatus == null ? "" : batteryStatus;
        this.challenge = challenge == null ? "" : challenge;
        this.bootCount = bootCount;
        this.tracking = tracking == null ? "OFF" : tracking;
        this.motors = motors == null ? "" : motors;
        this.step = step == null ? "" : step;
        this.coordinates = coordinates == null ? "" : coordinates;
        this.firmware = firmware == null ? "" : firmware;
        this.filter = filter == null ? "" : filter;
        this.temperature = temperature == null ? "" : temperature;
        this.error = error == null ? "" : error;
        this.storage = storage == null ? "" : storage;
        this.storageUsedPercent = storageUsedPercent;
        this.location = location == null ? "" : location;
        this.focus = focus == null ? "" : focus;
        this.rawJson = rawJson == null ? "" : rawJson;
    }

    boolean hasInstrumentFields() {
        return !telescopeId.isEmpty() || !model.isEmpty() || !state.isEmpty()
                || !operationType.isEmpty() || !targetName.isEmpty() || !challenge.isEmpty()
                || !observationStatus.isEmpty() || !step.isEmpty() || !firmware.isEmpty();
    }

    boolean canSignCommands() {
        return !challenge.isEmpty() && !telescopeId.isEmpty();
    }

    String authMissingCode() {
        if (challenge.isEmpty() && telescopeId.isEmpty()) return "auth_missing";
        if (challenge.isEmpty()) return "auth_missing_challenge";
        return "auth_missing_id";
    }

    /** True when status says the charger / mains is connected. */
    boolean isOnMainsPower() {
        return isOnMainsPower(batteryStatus);
    }

    /** True only when status clearly says the telescope is on battery. */
    boolean isOffMainsPower() {
        return isOffMainsPower(batteryStatus);
    }

    static boolean isOnMainsPower(String batteryStatus) {
        String status = normalizeBatteryStatus(batteryStatus);
        if (status.isEmpty() || isOffMainsPower(status)) return false;
        return "CONNECTED".equals(status) || "CHARGING".equals(status)
                || "AC".equals(status) || "FULL".equals(status) || "PLUGGED".equals(status)
                || status.contains("CHARGING")
                || (status.contains("CONNECTED") && !status.contains("DISCONNECTED"));
    }

    static boolean isOffMainsPower(String batteryStatus) {
        String status = normalizeBatteryStatus(batteryStatus);
        if (status.isEmpty()) return false;
        return status.contains("DISCONNECTED") || status.contains("UNPLUG")
                || "BATTERY".equals(status) || status.contains("DISCHARG")
                || status.contains("NOT_CONNECTED") || status.contains("NOT CONNECTED")
                || status.contains("NOT_CHARG") || status.contains("NOT CHARG");
    }

    private static String normalizeBatteryStatus(String batteryStatus) {
        return batteryStatus == null ? "" : batteryStatus.trim().toUpperCase(java.util.Locale.US);
    }

    boolean isObserving() {
        return "RUNNING".equals(observationStatus);
    }

    /** Tracking + imaging — photos are being written to internal storage. */
    boolean isTrackingAcquisition() {
        if (isObserving()) return true;
        if ("ON".equals(tracking) || "STARTING".equals(tracking)) return true;
        String blob = (operationType + " " + step + " " + state).toUpperCase(java.util.Locale.US);
        return blob.contains("ACQUI") || blob.contains("STACK")
                || blob.contains("IMAGE") || blob.contains("EXPOS");
    }

    boolean canResumeObservation() {
        if (isObserving()) return false;
        if ("STOPPED".equals(observationStatus)) return true;
        if (VesperaLastTarget.hasStoreId()) return true;
        return VesperaLastTarget.hasTarget();
    }

    /** True when the instrument reports Vaonis GENERAL_SUN_TOO_HIGH. */
    boolean isSunTooHigh() {
        String blob = (error + " " + state + " " + operationType + " " + step + " " + rawJson)
                .toUpperCase(java.util.Locale.US);
        return blob.contains("GENERAL_SUN_TOO_HIGH") || blob.contains("SUN_TOO_HIGH");
    }

    boolean isShuttingDown() {
        String blob = rawJson == null ? "" : rawJson;
        return blob.contains("\"shuttingDown\":true") || blob.contains("\"shutting_down\":true");
    }

    /**
     * Park / stop / slew / observation still running. Shutdown is refused until
     * these finish (firmware sunCheck park takes about a minute).
     */
    boolean isBusyForShutdown() {
        if (isShuttingDown()) return false;
        if (isObserving() || isTrackingAcquisition()) return true;
        String label = busyLabel();
        return label != null && !label.isEmpty();
    }

    /** True while {@code currentOperation} is a running AUTO_INIT. */
    boolean isAutoInitRunning() {
        org.json.JSONObject op = currentAutoInitOp();
        if (op == null) return false;
        if (op.optBoolean("stopped", false)) return false;
        return !op.has("endTime") || op.isNull("endTime");
    }

    /**
     * AUTO_INIT has moved to {@code previousOperations} and stopped without error.
     * Does not require {@code initialized}: firmware can leave that flag false
     * until after {@code startObservation}.
     */
    boolean isAutoInitFinishedOk() {
        if (isAutoInitRunning()) return false;
        org.json.JSONObject prev = previousAutoInit();
        if (prev == null) return false;
        if (!prev.optBoolean("stopped", false)
                && !(prev.has("endTime") && !prev.isNull("endTime"))) {
            return false;
        }
        return autoInitErrorName(prev).isEmpty();
    }

    /** AZ+ALT reported calibrated — typical after a successful auto-init. */
    boolean azAltCalibrated() {
        org.json.JSONObject body = statusBody();
        org.json.JSONObject motors = body == null ? null : body.optJSONObject("motors");
        if (motors == null) return false;
        return motorCalibrated(motors, "AZ") && motorCalibrated(motors, "ALT");
    }

    String autoInitFailure(boolean includePrevious) {
        String fromCurrent = autoInitErrorName(currentAutoInitOp());
        if (!fromCurrent.isEmpty()) return fromCurrent;
        if (!includePrevious || isAutoInitRunning()) return "";
        return autoInitErrorName(previousAutoInit());
    }

    private org.json.JSONObject currentAutoInitOp() {
        org.json.JSONObject body = statusBody();
        if (body == null) return null;
        org.json.JSONObject current = body.optJSONObject("currentOperation");
        if (isAutoInitOp(current)) return current;
        org.json.JSONArray others = body.optJSONArray("otherCurrentOperations");
        if (others != null) {
            for (int i = 0; i < others.length(); i++) {
                org.json.JSONObject item = others.optJSONObject(i);
                if (isAutoInitOp(item)) return item;
            }
        }
        return null;
    }

    private org.json.JSONObject previousAutoInit() {
        org.json.JSONObject body = statusBody();
        if (body == null) return null;
        org.json.JSONObject previous = body.optJSONObject("previousOperations");
        if (previous == null) return null;
        org.json.JSONObject named = previous.optJSONObject("autoInit");
        if (isAutoInitOp(named)) return named;
        org.json.JSONArray names = previous.names();
        if (names == null) return null;
        for (int i = 0; i < names.length(); i++) {
            org.json.JSONObject item = previous.optJSONObject(names.optString(i));
            if (isAutoInitOp(item)) return item;
        }
        return null;
    }

    private static boolean isAutoInitOp(org.json.JSONObject op) {
        if (op == null) return false;
        String type = op.optString("type", "").toUpperCase(java.util.Locale.US);
        return type.contains("AUTO_INIT");
    }

    private static String autoInitErrorName(org.json.JSONObject op) {
        if (op == null) return "";
        if (!op.has("error") || op.isNull("error")) return "";
        Object raw = op.opt("error");
        if (raw instanceof org.json.JSONObject) {
            org.json.JSONObject err = (org.json.JSONObject) raw;
            String name = err.optString("name", "").trim();
            if (!name.isEmpty()) return name;
            String message = err.optString("message", "").trim();
            return message.isEmpty() ? "AUTO_INIT_ERROR" : message;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) return "";
        return text;
    }

    private static boolean motorCalibrated(org.json.JSONObject motors, String axis) {
        org.json.JSONObject motor = motors.optJSONObject(axis);
        return motor != null && motor.optBoolean("calibrated", false);
    }

    /** Short label of the blocking operation, or empty if idle. */
    String busyLabel() {
        if (isShuttingDown()) return "";
        org.json.JSONObject body = statusBody();
        if (body == null) {
            String blob = (operationType + " " + step + " " + motors).toUpperCase(
                    java.util.Locale.US);
            if (blob.contains("PARK") || blob.contains("MOVING")) return blob.trim();
            return "";
        }
        String fromOp = activeOpLabel(body.optJSONObject("currentOperation"));
        if (!fromOp.isEmpty()) return fromOp;
        org.json.JSONArray others = body.optJSONArray("otherCurrentOperations");
        if (others != null) {
            for (int i = 0; i < others.length(); i++) {
                fromOp = activeOpLabel(others.optJSONObject(i));
                if (!fromOp.isEmpty()) return fromOp;
            }
        }
        org.json.JSONObject previous = body.optJSONObject("previousOperations");
        if (previous != null) {
            org.json.JSONArray names = previous.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    fromOp = activeOpLabel(previous.optJSONObject(names.optString(i)));
                    if (!fromOp.isEmpty()) return fromOp;
                }
            }
        }
        if (motorsMoving(body.optJSONObject("motors"))) return "motors";
        return "";
    }

    private org.json.JSONObject statusBody() {
        if (rawJson == null || rawJson.isEmpty()) return null;
        try {
            org.json.JSONObject root = new org.json.JSONObject(rawJson);
            org.json.JSONObject result = root.optJSONObject("result");
            return result != null ? result : root;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String activeOpLabel(org.json.JSONObject op) {
        if (op == null) return "";
        if (op.optBoolean("stopped", false)) return "";
        if (op.has("endTime") && !op.isNull("endTime")) return "";
        String type = op.optString("type", "").trim();
        if (type.isEmpty()) return "";
        return type;
    }

    private static boolean motorsMoving(org.json.JSONObject motors) {
        if (motors == null) return false;
        org.json.JSONArray names = motors.names();
        if (names == null) return false;
        for (int i = 0; i < names.length(); i++) {
            org.json.JSONObject axis = motors.optJSONObject(names.optString(i));
            if (axis == null) continue;
            String state = axis.optString("state", "").toUpperCase(java.util.Locale.US);
            if (state.contains("MOVING") || state.contains("RUNNING")
                    || state.contains("BUSY")) {
                return true;
            }
        }
        return false;
    }
}
