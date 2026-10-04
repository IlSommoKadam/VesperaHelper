package com.vaonis.vesperahelper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Last observation target from live/REST status, used to POST startObservation.
 */
final class VesperaLastTarget {
    private static volatile String bodyJson = "";
    private static volatile String label = "";
    /** Multi-night / stored capture id for startObservationFromStoredCapture. */
    private static volatile String storeId = "";

    private VesperaLastTarget() {}

    static void rememberFromStatus(JSONObject payload) {
        JSONObject operation = findOperation(payload);
        remember(targetOf(operation), operation, payload);
        rememberStoreId(payload, operation);
    }

    /** Same target shown in the Telescopio status rows. */
    static void remember(JSONObject target, JSONObject operation) {
        remember(target, operation, null);
    }

    static void remember(JSONObject target, JSONObject operation, JSONObject payload) {
        JSONObject body = buildBody(target, operation);
        if (body == null || !hasCoordinates(body)) return;
        keepPreviousCameraParams(body);
        fillFromCaptureStore(body, payload);
        bodyJson = body.toString();
        label = firstNonEmpty(
                text(body, "objectName"),
                text(body, "name"),
                text(body, "catalogName"),
                formatCoordinates(body));
    }

    static boolean hasTarget() {
        return bodyJson != null && !bodyJson.isEmpty();
    }

    static boolean hasStoreId() {
        return storeId != null && !storeId.isEmpty();
    }

    static String storeId() {
        return hasStoreId() ? storeId : "";
    }

    /** Body for Singularity multi-night resume API. */
    static String storedCaptureBody() {
        if (!hasStoreId()) return "";
        try {
            JSONObject body = new JSONObject();
            body.put("storeId", storeId);
            return body.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    static String startObservationBody() {
        return hasTarget() ? bodyJson : "";
    }

    /**
     * Firmware {@code startObservation} requires top-level {@code gain} and
     * {@code exposureMicroSec}. Status may store them on {@code capture},
     * {@code cameraParams}, or {@code captureStore} rather than all three.
     */
    static String startObservationBody(VesperaStatusSnapshot snap) {
        String raw = startObservationBody();
        if (raw.isEmpty()) return "";
        try {
            JSONObject body = new JSONObject(raw);
            body.put("resume", true);
            if (!hasNumber(body, "gain") && snap != null && snap.gain > 0) {
                body.put("gain", snap.gain);
            }
            if (!hasNumber(body, "exposureMicroSec") && snap != null
                    && snap.exposureMicroSec > 0) {
                body.put("exposureMicroSec", snap.exposureMicroSec);
            }
            if ((!hasNumber(body, "gain") || !hasNumber(body, "exposureMicroSec"))
                    && snap != null) {
                JSONObject payload = parseJson(snap.rawJson);
                copyCameraParams(body, payload);
                fillFromCaptureStore(body, payload);
            }
            if (!hasNumber(body, "gain")) body.put("gain", 150);
            if (!hasNumber(body, "exposureMicroSec")) {
                body.put("exposureMicroSec", 10_000_000L);
            }
            // Advanced/mosaic startObservation also requires stacking params.
            if (!body.has("histogramEnabled")) body.put("histogramEnabled", true);
            if (!body.has("histogramLow")) body.put("histogramLow", -0.75);
            if (!body.has("histogramMedium")) body.put("histogramMedium", 5);
            if (!body.has("histogramHigh")) body.put("histogramHigh", 0);
            if (!body.has("backgroundEnabled")) body.put("backgroundEnabled", true);
            if (!body.has("backgroundPolyorder")) body.put("backgroundPolyorder", 2);
            String sid = text(body, "storeId");
            if (!sid.isEmpty()) storeId = sid;
            return body.toString();
        } catch (Exception ignored) {
            return raw;
        }
    }

    static String label() {
        return label == null ? "" : label;
    }

    /** Last remembered RA/Dec in degrees, or null. RA may be negative. */
    static double[] raDec() {
        if (!hasTarget()) return null;
        try {
            return raDecOf(new JSONObject(bodyJson));
        } catch (Exception ignored) {
            return null;
        }
    }

    static String formatCoordinates(JSONObject target) {
        double[] pair = raDecOf(target);
        if (pair == null) return "";
        return String.format(java.util.Locale.US, "RA %.3f  ·  Dec %.3f", pair[0], pair[1]);
    }

    private static JSONObject buildBody(JSONObject target, JSONObject operation) {
        JSONObject src = target != null ? target : operation;
        if (src == null) return null;
        JSONObject body = new JSONObject();
        copyNames(body, src);
        if (target != null && operation != null && target != operation) {
            copyNames(body, operation);
        }
        double[] pair = raDecOf(src);
        if (pair == null && operation != null && operation != src) {
            pair = raDecOf(operation);
        }
        if (pair == null) return null;
        try {
            body.put("ra", pair[0]);
            body.put("de", pair[1]);
            body.put("dec", pair[1]);
            body.put("resume", true);
        } catch (Exception ignored) {
            return null;
        }
        putIfPresent(body, src, "type");
        putIfPresent(body, src, "rot");
        putIfPresent(body, src, "objectType");
        if (operation != null) {
            putIfPresent(body, operation, "id");
            putIfPresent(body, operation, "uuid");
            putIfPresent(body, operation, "observationId");
            putIfPresent(body, operation, "taskId");
            putIfPresent(body, operation, "observationType");
            JSONObject store = operation.optJSONObject("store");
            if (store != null) putIfPresent(body, store, "storeId");
            JSONObject mosaic = operation.optJSONObject("mosaic");
            if (mosaic != null) {
                try {
                    body.put("mosaic", mosaic);
                } catch (Exception ignored) {
                }
            }
            copyCameraParams(body, operation);
        }
        copyCameraParams(body, src, target);
        String sid = text(body, "storeId");
        if (!sid.isEmpty()) storeId = sid;
        return body;
    }

    /**
     * Keep multi-night {@code storeId} even after auto-init clears
     * {@code previousOperations.observation} (Singularity resumes via captureStore).
     */
    private static void rememberStoreId(JSONObject payload, JSONObject operation) {
        String fromOp = storeIdOf(operation);
        if (!fromOp.isEmpty() && isResumableStore(operation)) {
            storeId = fromOp;
            return;
        }
        if (!fromOp.isEmpty() && storeId.isEmpty()) storeId = fromOp;
        if (payload == null) return;
        JSONObject chosen = preferredStoredCapture(payload);
        if (chosen == null) return;
        String fromStore = text(chosen, "storeId");
        if (!fromStore.isEmpty()) {
            // Prefer an existing remembered id if still present; else latest capture.
            if (storeId.isEmpty() || storeId.equals(fromStore)
                    || findStoredCapture(payload, storeId) == null) {
                storeId = fromStore;
            }
            if (!hasTarget()) {
                remember(chosen.optJSONObject("target"), chosen, payload);
            }
        }
    }

    private static String storeIdOf(JSONObject operation) {
        if (operation == null) return "";
        JSONObject store = operation.optJSONObject("store");
        String direct = text(store, "storeId");
        if (!direct.isEmpty()) return direct;
        return text(operation, "storeId");
    }

    private static boolean isResumableStore(JSONObject operation) {
        if (operation == null) return false;
        JSONObject store = operation.optJSONObject("store");
        String state = text(store, "state").toUpperCase(java.util.Locale.US);
        if (state.isEmpty()) state = text(operation, "storeState").toUpperCase(java.util.Locale.US);
        return state.contains("TO_BE_RESUMABLE")
                || "RESUME".equals(state)
                || (state.contains("RESUMABLE") && !state.contains("NON_"));
    }

    private static JSONObject preferredStoredCapture(JSONObject payload) {
        if (!storeId.isEmpty()) {
            JSONObject match = findStoredCapture(payload, storeId);
            if (match != null) return match;
        }
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

    private static JSONObject findStoredCapture(JSONObject payload, String id) {
        if (payload == null || id == null || id.isEmpty()) return null;
        JSONObject store = payload.optJSONObject("captureStore");
        if (store == null) {
            JSONObject nested = payload.optJSONObject("result");
            if (nested == null) nested = payload.optJSONObject("data");
            store = nested == null ? null : nested.optJSONObject("captureStore");
        }
        if (store == null) return null;
        JSONArray captures = store.optJSONArray("storedCaptures");
        if (captures == null) return null;
        for (int i = captures.length() - 1; i >= 0; i--) {
            JSONObject item = captures.optJSONObject(i);
            if (item != null && id.equals(text(item, "storeId"))) return item;
        }
        return null;
    }

    private static void copyCameraParams(JSONObject body, JSONObject... sources) {
        if (body == null || sources == null) return;
        for (JSONObject src : sources) {
            if (src == null) continue;
            putCameraNumber(body, src, "gain");
            putCameraNumber(body, src, "exposureMicroSec");
            JSONObject capture = src.optJSONObject("capture");
            if (capture != null) {
                putCameraNumber(body, capture, "gain");
                putCameraNumber(body, capture, "exposureMicroSec");
                JSONObject nested = capture.optJSONObject("cameraParams");
                putCameraNumber(body, nested, "gain");
                putCameraNumber(body, nested, "exposureMicroSec");
            }
            JSONObject cam = src.optJSONObject("cameraParams");
            putCameraNumber(body, cam, "gain");
            putCameraNumber(body, cam, "exposureMicroSec");
        }
    }

    private static void putCameraNumber(JSONObject dest, JSONObject src, String key) {
        if (dest == null || src == null || hasNumber(dest, key)) return;
        if (!src.has(key) || src.isNull(key)) return;
        Double value = asDouble(src.opt(key));
        if (value == null || value <= 0) return;
        try {
            if ("gain".equals(key)) dest.put(key, value.intValue());
            else dest.put(key, value.longValue());
        } catch (Exception ignored) {
        }
    }

    private static void keepPreviousCameraParams(JSONObject body) {
        if (hasNumber(body, "gain") && hasNumber(body, "exposureMicroSec")) return;
        copyCameraParams(body, parseJson(bodyJson));
    }

    private static void fillFromCaptureStore(JSONObject body, JSONObject payload) {
        if (body == null || payload == null) return;
        JSONObject store = payload.optJSONObject("captureStore");
        if (store == null) {
            JSONObject nested = payload.optJSONObject("result");
            if (nested == null) nested = payload.optJSONObject("data");
            store = nested == null ? null : nested.optJSONObject("captureStore");
        }
        if (store == null) return;
        JSONArray captures = store.optJSONArray("storedCaptures");
        if (captures == null || captures.length() == 0) return;
        String storeId = text(body, "storeId");
        JSONObject chosen = captures.optJSONObject(captures.length() - 1);
        if (!storeId.isEmpty()) {
            for (int i = captures.length() - 1; i >= 0; i--) {
                JSONObject item = captures.optJSONObject(i);
                if (item != null && storeId.equals(text(item, "storeId"))) {
                    chosen = item;
                    break;
                }
            }
        }
        if (chosen == null) return;
        copyCameraParams(body, chosen);
        if (!hasText(body, "storeId")) putIfPresent(body, chosen, "storeId");
    }

    private static boolean hasNumber(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return false;
        return asDouble(obj.opt(key)) != null;
    }

    private static boolean hasText(JSONObject obj, String key) {
        return !text(obj, key).isEmpty();
    }

    private static JSONObject parseJson(String raw) {
        if (raw == null) return null;
        String trim = raw.trim();
        if (trim.isEmpty() || trim.charAt(0) != '{') return null;
        try {
            return new JSONObject(trim);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void copyNames(JSONObject dest, JSONObject src) {
        if (src == null) return;
        putIfPresent(dest, src, "objectName");
        putIfPresent(dest, src, "name");
        putIfPresent(dest, src, "catalogName");
        putIfPresent(dest, src, "objectId");
    }

    private static JSONObject findOperation(JSONObject payload) {
        if (payload == null) return null;
        String[] keys = {
                "currentOperation", "operation", "lastOperation",
                "observation", "lastObservation"
        };
        for (String key : keys) {
            JSONObject operation = payload.optJSONObject(key);
            if (targetOf(operation) != null) return operation;
        }
        JSONObject nested = payload.optJSONObject("result");
        if (nested == null) nested = payload.optJSONObject("data");
        if (nested != null && nested != payload) {
            JSONObject fromNested = findOperation(nested);
            if (fromNested != null) return fromNested;
        }
        JSONArray previous = payload.optJSONArray("previousOperations");
        if (previous != null) {
            for (int i = previous.length() - 1; i >= 0; i--) {
                JSONObject item = previous.optJSONObject(i);
                if (targetOf(item) != null) return item;
            }
        }
        JSONObject previousMap = payload.optJSONObject("previousOperations");
        if (previousMap != null) {
            JSONObject named = previousMap.optJSONObject("observation");
            if (targetOf(named) != null) return named;
            JSONArray names = previousMap.names();
            if (names != null) {
                for (int i = names.length() - 1; i >= 0; i--) {
                    JSONObject item = previousMap.optJSONObject(names.optString(i));
                    if (targetOf(item) != null) return item;
                }
            }
        }
        return payload.optJSONObject("operation");
    }

    private static JSONObject targetOf(JSONObject operation) {
        if (operation == null) return null;
        JSONObject target = operation.optJSONObject("target");
        if (target == null) target = operation.optJSONObject("object");
        if (raDecOf(target) != null) return target;
        if (raDecOf(operation) != null) return operation;
        return null;
    }

    private static boolean hasCoordinates(JSONObject obj) {
        return raDecOf(obj) != null;
    }

    private static double[] raDecOf(JSONObject obj) {
        if (obj == null) return null;
        Double ra = number(obj, "ra", "RA", "rightAscension", "raHours");
        Double dec = number(obj, "de", "dec", "DEC", "Dec", "declination", "decDeg");
        if (ra != null && dec != null) return new double[] { ra, dec };
        String[] nestedKeys = { "coordinates", "equatorial", "eq", "position", "pointing" };
        for (String key : nestedKeys) {
            double[] nested = raDecOf(obj.optJSONObject(key));
            if (nested != null) return nested;
        }
        return null;
    }

    private static Double number(JSONObject obj, String... keys) {
        if (obj == null) return null;
        for (String key : keys) {
            if (!obj.has(key) || obj.isNull(key)) continue;
            Double value = asDouble(obj.opt(key));
            if (value != null) return value;
        }
        JSONArray names = obj.names();
        if (names == null) return null;
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i);
            for (String key : keys) {
                if (key.equalsIgnoreCase(name)) {
                    Double value = asDouble(obj.opt(name));
                    if (value != null) return value;
                }
            }
        }
        return null;
    }

    private static Double asDouble(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            String text = ((String) value).trim().replace(',', '.');
            if (text.isEmpty()) return null;
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            Double nested = asDouble(obj.opt("value"));
            if (nested != null) return nested;
            nested = asDouble(obj.opt("hours"));
            if (nested != null) return nested;
            return asDouble(obj.opt("deg"));
        }
        return null;
    }

    private static void putIfPresent(JSONObject dest, JSONObject src, String key) {
        if (src == null || !src.has(key) || src.isNull(key)) return;
        try {
            dest.put(key, src.get(key));
        } catch (Exception ignored) {
        }
    }

    private static String text(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return "";
        return String.valueOf(obj.opt(key)).trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }
}
