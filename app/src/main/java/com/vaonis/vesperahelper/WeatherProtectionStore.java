package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration and runtime state for the Automatic Weather Protection feature
 * (System → Automatic Weather Protection).
 *
 * <p>Config values are user-editable from the System tab. Defaults deliberately
 * match the initial requirement (check every 5 min, look ahead 5 min, threshold
 * 0 mm) but are stored as plain values so they can be changed later without code
 * changes.
 */
final class WeatherProtectionStore {
    private static final String PREFS = "vespera_weather";

    // Config keys.
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAT = "latitude";
    private static final String KEY_LON = "longitude";
    private static final String KEY_CHECK_INTERVAL_MIN = "check_interval_min";
    private static final String KEY_LOOKAHEAD_MIN = "lookahead_min";
    private static final String KEY_THRESHOLD_MM = "precip_threshold_mm";
    private static final String KEY_SIMULATION = "simulation";

    // Runtime state keys.
    private static final String KEY_STATE = "state";
    private static final String KEY_LAST_CHECK_AT = "last_check_at";
    private static final String KEY_LAST_DECISION = "last_decision";
    private static final String KEY_LAST_PRECIP_MM = "last_precip_mm";
    private static final String KEY_LAST_PROB = "last_prob";
    private static final String KEY_API_FAILURES = "api_failures";
    private static final String KEY_PROTECTION_ACTIVE = "protection_active";
    private static final String KEY_LAST_TRIGGER_AT = "last_trigger_at";
    private static final String KEY_LAST_RESULT = "last_result";

    /** Weather provider — currently Open-Meteo only. */
    static final String SOURCE_OPEN_METEO = "Open-Meteo";

    // Defaults — easy to tune later.
    static final int DEFAULT_CHECK_INTERVAL_MIN = 5;
    static final int DEFAULT_LOOKAHEAD_MIN = 5;
    static final float DEFAULT_THRESHOLD_MM = 0f;
    static final int MIN_CHECK_INTERVAL_MIN = 1;
    static final int MIN_LOOKAHEAD_MIN = 1;

    // Decision codes.
    static final String DECISION_NO_ACTION = "no_action";
    static final String DECISION_RAIN = "rain";
    static final String DECISION_API_ERROR = "api_error";
    static final String DECISION_NO_COORDS = "no_coords";

    // Result codes for the last protection run.
    static final String RESULT_NONE = "";
    static final String RESULT_COMPLETED = "completed";
    static final String RESULT_SIMULATED = "simulated";
    static final String RESULT_ERROR = "error";
    static final String RESULT_OFFLINE = "offline";

    /** Immutable view of the editable config. */
    static final class Config {
        boolean enabled;
        String latitude = "";
        String longitude = "";
        int checkIntervalMin = DEFAULT_CHECK_INTERVAL_MIN;
        int lookAheadMin = DEFAULT_LOOKAHEAD_MIN;
        float thresholdMm = DEFAULT_THRESHOLD_MM;
        boolean simulation;
    }

    private final SharedPreferences prefs;

    private WeatherProtectionStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static WeatherProtectionStore from(Context context) {
        return new WeatherProtectionStore(
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    Config config() {
        Config c = new Config();
        c.enabled = prefs.getBoolean(KEY_ENABLED, false);
        c.latitude = prefs.getString(KEY_LAT, "");
        c.longitude = prefs.getString(KEY_LON, "");
        c.checkIntervalMin = prefs.getInt(KEY_CHECK_INTERVAL_MIN, DEFAULT_CHECK_INTERVAL_MIN);
        c.lookAheadMin = prefs.getInt(KEY_LOOKAHEAD_MIN, DEFAULT_LOOKAHEAD_MIN);
        c.thresholdMm = prefs.getFloat(KEY_THRESHOLD_MM, DEFAULT_THRESHOLD_MM);
        c.simulation = prefs.getBoolean(KEY_SIMULATION, false);
        return c;
    }

    /** Persists config and, when enabled, clears any stale protection latch. */
    void saveConfig(Config c) {
        if (c == null) return;
        int interval = Math.max(MIN_CHECK_INTERVAL_MIN, c.checkIntervalMin);
        int lookAhead = Math.max(MIN_LOOKAHEAD_MIN, c.lookAheadMin);
        float threshold = c.thresholdMm < 0 ? 0f : c.thresholdMm;
        prefs.edit()
                .putBoolean(KEY_ENABLED, c.enabled)
                .putString(KEY_LAT, c.latitude == null ? "" : c.latitude.trim())
                .putString(KEY_LON, c.longitude == null ? "" : c.longitude.trim())
                .putInt(KEY_CHECK_INTERVAL_MIN, interval)
                .putInt(KEY_LOOKAHEAD_MIN, lookAhead)
                .putFloat(KEY_THRESHOLD_MM, threshold)
                .putBoolean(KEY_SIMULATION, c.simulation)
                .commit();
        // Re-arm on save: a fresh configuration should be able to protect again.
        clearProtectionLatch();
    }

    /** Updates only the site coordinates used for Open-Meteo (no latch reset). */
    void setCoordinates(double lat, double lon) {
        prefs.edit()
                .putString(KEY_LAT, formatCoord(lat))
                .putString(KEY_LON, formatCoord(lon))
                .commit();
    }

    boolean enabled() { return prefs.getBoolean(KEY_ENABLED, false); }

    boolean simulation() { return prefs.getBoolean(KEY_SIMULATION, false); }

    int checkIntervalMin() {
        return Math.max(MIN_CHECK_INTERVAL_MIN,
                prefs.getInt(KEY_CHECK_INTERVAL_MIN, DEFAULT_CHECK_INTERVAL_MIN));
    }

    int lookAheadMin() {
        return Math.max(MIN_LOOKAHEAD_MIN,
                prefs.getInt(KEY_LOOKAHEAD_MIN, DEFAULT_LOOKAHEAD_MIN));
    }

    float thresholdMm() { return prefs.getFloat(KEY_THRESHOLD_MM, DEFAULT_THRESHOLD_MM); }

    String latitudeRaw() { return prefs.getString(KEY_LAT, ""); }

    String longitudeRaw() { return prefs.getString(KEY_LON, ""); }

    /** Configured latitude, or NaN when unset/invalid. */
    double latitude() { return parseCoord(latitudeRaw()); }

    /** Configured longitude, or NaN when unset/invalid. */
    double longitude() { return parseCoord(longitudeRaw()); }

    boolean hasCoordinates() {
        double lat = latitude();
        double lon = longitude();
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    // ---- Runtime state ----

    /** True while a protection sequence is in progress or already completed. */
    boolean protectionActive() { return prefs.getBoolean(KEY_PROTECTION_ACTIVE, false); }

    void setProtectionActive(boolean active) {
        prefs.edit().putBoolean(KEY_PROTECTION_ACTIVE, active).commit();
    }

    void clearProtectionLatch() {
        prefs.edit()
                .putBoolean(KEY_PROTECTION_ACTIVE, false)
                .putString(KEY_STATE, WeatherProtectionState.IDLE.name())
                .commit();
    }

    String state() { return prefs.getString(KEY_STATE, WeatherProtectionState.IDLE.name()); }

    void setState(WeatherProtectionState state) {
        if (state == null) return;
        prefs.edit().putString(KEY_STATE, state.name()).commit();
    }

    long lastCheckAt() { return prefs.getLong(KEY_LAST_CHECK_AT, 0L); }

    String lastDecision() { return prefs.getString(KEY_LAST_DECISION, ""); }

    float lastPrecipMm() { return prefs.getFloat(KEY_LAST_PRECIP_MM, -1f); }

    int lastProbability() { return prefs.getInt(KEY_LAST_PROB, -1); }

    void recordCheck(long at, String decision, float precipMm, int probability) {
        prefs.edit()
                .putLong(KEY_LAST_CHECK_AT, at)
                .putString(KEY_LAST_DECISION, decision == null ? "" : decision)
                .putFloat(KEY_LAST_PRECIP_MM, precipMm)
                .putInt(KEY_LAST_PROB, probability)
                .commit();
    }

    int consecutiveApiFailures() { return prefs.getInt(KEY_API_FAILURES, 0); }

    void incrementApiFailures() {
        prefs.edit().putInt(KEY_API_FAILURES, consecutiveApiFailures() + 1).commit();
    }

    void resetApiFailures() {
        if (consecutiveApiFailures() != 0) {
            prefs.edit().putInt(KEY_API_FAILURES, 0).commit();
        }
    }

    long lastTriggerAt() { return prefs.getLong(KEY_LAST_TRIGGER_AT, 0L); }

    String lastResult() { return prefs.getString(KEY_LAST_RESULT, RESULT_NONE); }

    void recordResult(long at, String result) {
        prefs.edit()
                .putLong(KEY_LAST_TRIGGER_AT, at)
                .putString(KEY_LAST_RESULT, result == null ? RESULT_NONE : result)
                .commit();
    }

    static double parseCoord(String raw) {
        if (raw == null) return Double.NaN;
        String trimmed = raw.trim().replace(',', '.');
        if (trimmed.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    static String formatCoord(double value) {
        if (Double.isNaN(value)) return "";
        return String.format(Locale.US, "%.5f", value);
    }

    static String formatLatLon(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return "";
        return formatCoord(lat) + ", " + formatCoord(lon);
    }

    /**
     * Parses a single "lat, lon" paste (Google Maps, comma/space/semicolon).
     * Returns null if the pair is missing or out of range.
     */
    static double[] parseLatLon(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;
        Matcher match = LAT_LON.matcher(text);
        if (!match.find()) return null;
        double lat = parseCoord(match.group(1));
        double lon = parseCoord(match.group(2));
        if (Double.isNaN(lat) || Double.isNaN(lon)
                || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return null;
        }
        return new double[] { lat, lon };
    }

    private static final Pattern LAT_LON = Pattern.compile(
            "([+-]?\\d+(?:[.,]\\d+)?)\\s*[,;\\s]\\s*([+-]?\\d+(?:[.,]\\d+)?)");
}
