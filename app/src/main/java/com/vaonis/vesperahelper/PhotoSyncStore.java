package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.File;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Last photo-sync run and night automatic interval (no periodic daytime slots). */
final class PhotoSyncStore {
    static final int DEFAULT_DAY_START = 10;
    static final int DEFAULT_DAY_END = 19;

    static final float DEFAULT_DAY_INTERVAL_HOURS = 3f;
    static final float DEFAULT_NIGHT_INTERVAL_HOURS = 1f;
    static final float MIN_INTERVAL_HOURS = 0.25f;
    static final float MAX_INTERVAL_HOURS = 12f;
    /** Delay after civil sunrise before the once-a-day morning shutdown. */
    static final long SUN_TOO_HIGH_AFTER_SUNRISE_MS = 30 * 60_000L;

    private static final String PREFS = "vespera_photo_sync";
    private static final String KEY_START = "day_start_hour";
    private static final String KEY_END = "day_end_hour";
    private static final String KEY_DAY_INTERVAL_HOURS = "day_interval_hours";
    private static final String KEY_NIGHT_INTERVAL_HOURS = "night_interval_hours";
    private static final String KEY_LAST_AT = "last_at";
    private static final String KEY_LAST_ATTEMPT = "last_attempt_at";
    private static final String KEY_LAST_COPIED = "last_copied";
    private static final String KEY_LAST_SKIPPED = "last_skipped";
    private static final String KEY_LAST_DELETED = "last_deleted";
    private static final String KEY_LAST_BYTES = "last_bytes";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_LAST_OK = "last_ok";
    private static final String KEY_PHOTOS_PATH = "photos_path";
    private static final String KEY_IN_PROGRESS = "in_progress";
    private static final String KEY_PAUSED = "paused";
    private static final String KEY_PAUSE_UNTIL_SCHEDULE = "pause_until_schedule";
    private static final String KEY_HAS_SITE = "has_site";
    private static final String KEY_SITE_LAT = "site_lat";
    private static final String KEY_SITE_LON = "site_lon";
    private static final String KEY_SITE_LABEL = "site_label";
    private static final String KEY_SITE_SOURCE = "site_source";
    private static final String KEY_SITE_TZ = "site_tz";
    private static final String KEY_SITE_COUNTRY = "site_country";
    private static final String KEY_AUTO_HOURS = "auto_hours";
    private static final String KEY_SUN_DAY = "sun_day";
    private static final String KEY_LAST_NTP = "last_ntp_at";
    private static final String KEY_LAST_NTP_ELAPSED = "last_ntp_elapsed";
    private static final String KEY_NTP_OK = "last_ntp_ok";
    static final String SITE_CITY = "city";
    static final String SITE_VESPERA = "vespera";
    static final String SITE_MANUAL = "manual";
    static final String MARKER_NAME = "sync.inprogress";

    private final SharedPreferences prefs;

    private PhotoSyncStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static PhotoSyncStore from(Context context) {
        return new PhotoSyncStore(
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    int dayStartHour() { return prefs.getInt(KEY_START, DEFAULT_DAY_START); }
    int dayEndHour() { return prefs.getInt(KEY_END, DEFAULT_DAY_END); }

    float dayIntervalHours() {
        float hours = prefs.getFloat(KEY_DAY_INTERVAL_HOURS, DEFAULT_DAY_INTERVAL_HOURS);
        return clampIntervalHours(hours);
    }

    float nightIntervalHours() {
        float hours = prefs.getFloat(KEY_NIGHT_INTERVAL_HOURS, DEFAULT_NIGHT_INTERVAL_HOURS);
        return clampIntervalHours(hours);
    }

    void setDayIntervalHours(float hours) {
        prefs.edit().putFloat(KEY_DAY_INTERVAL_HOURS, clampIntervalHours(hours)).commit();
    }

    void setNightIntervalHours(float hours) {
        prefs.edit().putFloat(KEY_NIGHT_INTERVAL_HOURS, clampIntervalHours(hours)).commit();
    }

    void setDayStartHour(int hour) {
        prefs.edit().putInt(KEY_START, clampHour(hour)).commit();
    }

    void setDayEndHour(int hour) {
        prefs.edit().putInt(KEY_END, clampHour(hour)).commit();
    }

    boolean hasSite() { return prefs.getBoolean(KEY_HAS_SITE, false); }
    float siteLat() { return prefs.getFloat(KEY_SITE_LAT, 0f); }
    float siteLon() { return prefs.getFloat(KEY_SITE_LON, 0f); }
    String siteLabel() { return prefs.getString(KEY_SITE_LABEL, ""); }
    String siteSource() { return prefs.getString(KEY_SITE_SOURCE, ""); }
    String siteTimeZone() { return prefs.getString(KEY_SITE_TZ, ""); }
    String siteCountry() { return prefs.getString(KEY_SITE_COUNTRY, ""); }
    boolean autoHours() { return prefs.getBoolean(KEY_AUTO_HOURS, false) && hasSite(); }

    TimeZone zone() {
        return TimeZones.zone(siteTimeZone());
    }

    void setSiteTimeZone(String timeZoneId) {
        prefs.edit().putString(KEY_SITE_TZ, timeZoneId == null ? "" : timeZoneId).commit();
    }

    void setAutoHours(boolean auto) {
        prefs.edit().putBoolean(KEY_AUTO_HOURS, auto).commit();
    }

    void setSite(double lat, double lon, String label, String source) {
        setSite(lat, lon, label, source, "");
    }

    void setSite(double lat, double lon, String label, String source, String countryCode) {
        prefs.edit()
                .putBoolean(KEY_HAS_SITE, true)
                .putFloat(KEY_SITE_LAT, (float) lat)
                .putFloat(KEY_SITE_LON, (float) lon)
                .putString(KEY_SITE_LABEL, label == null ? "" : label)
                .putString(KEY_SITE_SOURCE, source == null ? "" : source)
                .putString(KEY_SITE_COUNTRY, countryCode == null ? "" : countryCode)
                .putString(KEY_SITE_TZ, "")
                .putBoolean(KEY_AUTO_HOURS, true)
                .putInt(KEY_SUN_DAY, -1)
                .putLong(KEY_LAST_NTP, 0)
                .putLong(KEY_LAST_NTP_ELAPSED, 0)
                .commit();
    }

    boolean clockSyncDue(long nowMs, long intervalMs) {
        long last = prefs.getLong(KEY_LAST_NTP, 0);
        if (last <= 0) return true;
        long delta = nowMs - last;
        if (delta >= intervalMs) return true;
        if (delta < 0) return true;
        Calendar now = Calendar.getInstance(zone());
        now.setTimeInMillis(nowMs);
        Calendar then = Calendar.getInstance(zone());
        then.setTimeInMillis(last);
        return now.get(Calendar.DAY_OF_YEAR) != then.get(Calendar.DAY_OF_YEAR)
                || now.get(Calendar.YEAR) != then.get(Calendar.YEAR);
    }

    /**
     * True when the wall clock is behind stored NTP/sync stamps (typical after a
     * Pi reboot with no RTC: clock falls back to the Android build date).
     */
    boolean clockLooksWrong(long nowMs) {
        long skew = 120_000L;
        if (lastNtpAt() > nowMs + skew) return true;
        long last = Math.max(lastAt(), lastAttemptAt());
        return last > nowMs + skew;
    }

    /**
     * True if an NTP attempt already ran recently. Uses {@link SystemClock#elapsedRealtime()}
     * so a wall-clock jump during set-clock cannot retrigger the sync.
     */
    boolean clockSyncedRecently(long minGapMs) {
        long lastElapsed = prefs.getLong(KEY_LAST_NTP_ELAPSED, 0);
        if (lastElapsed <= 0) return false;
        long nowElapsed = SystemClock.elapsedRealtime();
        if (nowElapsed < lastElapsed) return false;
        return nowElapsed - lastElapsed < minGapMs;
    }

    boolean lastNtpOk() { return prefs.getBoolean(KEY_NTP_OK, false); }

    long lastNtpAt() { return prefs.getLong(KEY_LAST_NTP, 0); }

    int dayKey(long nowMs) {
        Calendar calendar = Calendar.getInstance(zone());
        calendar.setTimeInMillis(nowMs);
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR);
    }

    /** Civil sunrise for the local calendar day of {@code nowMs}, or -1 if unknown/polar. */
    long sunriseMs(long nowMs) {
        if (!hasSite()) return -1;
        SunTimes.Result sun = SunTimes.compute(siteLat(), siteLon(), nowMs, zone());
        if (sun == null || sun.polar || sun.sunriseMs <= 0) return -1;
        return sun.sunriseMs;
    }

    /** Instant of today's once-a-day sun-too-high check (sunrise + 30 min), or -1. */
    long sunTooHighCheckAt(long nowMs) {
        long rise = sunriseMs(nowMs);
        if (rise <= 0) return -1;
        return rise + SUN_TOO_HIGH_AFTER_SUNRISE_MS;
    }

    /**
     * Next check time: today's sunrise+30 if that day was not already checked,
     * otherwise tomorrow's sunrise+30. {@code retryToday} keeps today's window
     * open after a failed or interrupted attempt. Returns -1 without a site.
     */
    long nextSunTooHighCheckAt(int lastCheckedDay, long nowMs) {
        return nextSunTooHighCheckAt(lastCheckedDay, false, nowMs);
    }

    long nextSunTooHighCheckAt(int lastCheckedDay, boolean retryToday, long nowMs) {
        if (!hasSite()) return -1;
        int today = dayKey(nowMs);
        if (lastCheckedDay == today && !retryToday) {
            Calendar calendar = Calendar.getInstance(zone());
            calendar.setTimeInMillis(nowMs);
            calendar.add(Calendar.DAY_OF_YEAR, 1);
            return sunTooHighCheckAt(calendar.getTimeInMillis());
        }
        long window = sunTooHighCheckAt(nowMs);
        if (window <= 0) return -1;
        return nowMs < window ? window : nowMs;
    }

    void recordClockSync(long nowMs, boolean ok) {
        prefs.edit()
                .putLong(KEY_LAST_NTP, nowMs)
                .putLong(KEY_LAST_NTP_ELAPSED, SystemClock.elapsedRealtime())
                .putBoolean(KEY_NTP_OK, ok)
                .commit();
    }

    /** Recompute day/night hours from today's sunrise/sunset in the site timezone. */
    boolean applySunHours(long nowMs) {
        return applySunHours(nowMs, false);
    }

    boolean applySunHours(long nowMs, boolean force) {
        if (!autoHours()) return false;
        TimeZone tz = zone();
        Calendar calendar = Calendar.getInstance(tz);
        calendar.setTimeInMillis(nowMs);
        int dayKey = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR);
        if (!force && dayKey == prefs.getInt(KEY_SUN_DAY, -1)) return false;
        SunTimes.Result sun = SunTimes.compute(siteLat(), siteLon(), nowMs, tz);
        if (sun.polar) return false;
        int start = SunTimes.roundHour(sun.sunriseMs, tz);
        int end = SunTimes.roundHour(sun.sunsetMs, tz);
        if (start == end) end = clampHour(end + 1);
        prefs.edit()
                .putInt(KEY_START, clampHour(start))
                .putInt(KEY_END, clampHour(end))
                .putInt(KEY_SUN_DAY, dayKey)
                .commit();
        return true;
    }

    // Backward compat: old UI used a single "intervalHours()". Keep it as day interval.
    float intervalHours() { return dayIntervalHours(); }

    void setIntervalHours(float hours) { setDayIntervalHours(hours); }

    static float clampIntervalHours(float hours) {
        if (hours < MIN_INTERVAL_HOURS) return MIN_INTERVAL_HOURS;
        if (hours > MAX_INTERVAL_HOURS) return MAX_INTERVAL_HOURS;
        return hours;
    }

    private static int clampHour(int hour) {
        if (hour < 0) return 0;
        if (hour > 23) return 23;
        return hour;
    }

    static float parseIntervalHours(String raw) {
        if (raw == null) return DEFAULT_NIGHT_INTERVAL_HOURS;
        String text = raw.trim().toLowerCase(Locale.US).replace(',', '.');
        if (text.endsWith("h")) text = text.substring(0, text.length() - 1).trim();
        if (text.isEmpty()) return DEFAULT_NIGHT_INTERVAL_HOURS;
        try {
            return clampIntervalHours(Float.parseFloat(text));
        } catch (NumberFormatException ignored) {
            return DEFAULT_NIGHT_INTERVAL_HOURS;
        }
    }

    static String formatIntervalHours(float hours) {
        float clamped = clampIntervalHours(hours);
        if (Math.abs(clamped - Math.round(clamped)) < 0.001f) {
            return String.valueOf(Math.round(clamped));
        }
        return String.format(Locale.US, "%.2f", clamped).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    long nightIntervalMs() {
        return Math.round(nightIntervalHours() * 3_600_000L);
    }

    long lastAttemptAt() { return prefs.getLong(KEY_LAST_ATTEMPT, 0); }

    void recordAttempt() {
        prefs.edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).commit();
    }

    boolean isDaytime(long nowMs) {
        Calendar calendar = Calendar.getInstance(zone());
        calendar.setTimeInMillis(nowMs);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        return hour >= dayStartHour() && hour < dayEndHour();
    }

    long nextNightEndAt(long nowMs) {
        Calendar calendar = Calendar.getInstance(zone());
        calendar.setTimeInMillis(nowMs);
        int start = dayStartHour();
        calendar.set(Calendar.HOUR_OF_DAY, start);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (nowMs >= calendar.getTimeInMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar.getTimeInMillis();
    }

    /**
     * First scheduled slot strictly after {@code refMs}. Night slots start at
     * {@link #dayEndHour()} every {@link #nightIntervalHours()} and stop before
     * {@link #dayStartHour()}. Daytime has no periodic slots (Vespera is off
     * after the sun-too-high shutdown); the next slot is night start.
     */
    private long nextSlotAfter(long refMs) {
        Calendar calendar = Calendar.getInstance(zone());
        calendar.setTimeInMillis(refMs);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.HOUR_OF_DAY, dayStartHour());
        long dayStartMs = calendar.getTimeInMillis();
        calendar.set(Calendar.HOUR_OF_DAY, dayEndHour());
        long dayEndMs = calendar.getTimeInMillis();
        if (dayEndMs <= dayStartMs) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
            dayEndMs = calendar.getTimeInMillis();
        }

        if (refMs < dayStartMs) {
            calendar.setTimeInMillis(dayStartMs);
            calendar.add(Calendar.DAY_OF_YEAR, -1);
            calendar.set(Calendar.HOUR_OF_DAY, dayEndHour());
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long nightStartMs = calendar.getTimeInMillis();
            long slot = nextIntervalSlot(nightStartMs, nightIntervalMs(), refMs, dayStartMs);
            if (slot >= dayStartMs) return dayEndMs;
            return slot;
        }
        if (refMs < dayEndMs) {
            return dayEndMs;
        }
        calendar.setTimeInMillis(dayStartMs);
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        long nextDayStartMs = calendar.getTimeInMillis();
        long slot = nextIntervalSlot(dayEndMs, nightIntervalMs(), refMs, nextDayStartMs);
        if (slot >= nextDayStartMs) {
            calendar.setTimeInMillis(nextDayStartMs);
            calendar.set(Calendar.HOUR_OF_DAY, dayEndHour());
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            return calendar.getTimeInMillis();
        }
        return slot;
    }

    /** Next interval boundary strictly after {@code refMs}, or {@code periodEndMs}. */
    private static long nextIntervalSlot(long periodStartMs, long intervalMs, long refMs,
                                         long periodEndMs) {
        if (intervalMs <= 0) return periodEndMs;
        if (refMs < periodStartMs) return periodStartMs;
        long elapsed = refMs - periodStartMs;
        long slot = periodStartMs + (elapsed / intervalMs + 1) * intervalMs;
        if (slot >= periodEndMs) return periodEndMs;
        return slot;
    }

    long nextAutoAt(long nowMs) {
        if (isDaytime(nowMs)) return nextNightStartAt(nowMs);
        long last = Math.max(lastAt(), lastAttemptAt());
        if (last <= 0) return nowMs;
        if (last > nowMs + 120_000L) return nowMs;
        return nextSlotAfter(last);
    }

    /** Tonight's {@link #dayEndHour()} (night start), or tomorrow's if already past it. */
    long nextNightStartAt(long nowMs) {
        Calendar calendar = Calendar.getInstance(zone());
        calendar.setTimeInMillis(nowMs);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.HOUR_OF_DAY, dayEndHour());
        if (nowMs < calendar.getTimeInMillis()) return calendar.getTimeInMillis();
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        return calendar.getTimeInMillis();
    }

    boolean isAutoDue(long nowMs) {
        if (paused()) return false;
        if (isDaytime(nowMs)) return false;
        return nowMs >= nextAutoAt(nowMs);
    }

    long lastAt() { return prefs.getLong(KEY_LAST_AT, 0); }
    int lastCopied() { return prefs.getInt(KEY_LAST_COPIED, 0); }
    int lastSkipped() { return prefs.getInt(KEY_LAST_SKIPPED, 0); }
    int lastDeleted() { return prefs.getInt(KEY_LAST_DELETED, 0); }
    long lastBytes() { return prefs.getLong(KEY_LAST_BYTES, 0); }
    String lastError() { return prefs.getString(KEY_LAST_ERROR, ""); }
    boolean lastOk() { return prefs.getBoolean(KEY_LAST_OK, false); }
    String photosPath() { return prefs.getString(KEY_PHOTOS_PATH, ""); }
    boolean inProgress() { return prefs.getBoolean(KEY_IN_PROGRESS, false); }
    boolean paused() { return prefs.getBoolean(KEY_PAUSED, false); }
    boolean pauseUntilSchedule() { return prefs.getBoolean(KEY_PAUSE_UNTIL_SCHEDULE, false); }

    boolean shouldResume(Context context) {
        if (paused()) return false;
        if (pauseUntilSchedule()) return false;
        return hasSuspendedWork(context);
    }

    /**
     * Fast UI-safe check: prefs / pause / marker only. Never walks the photo
     * tree (that can ANR on a large USB HD when called from the main thread).
     */
    boolean hasSuspendedWorkQuick(Context context) {
        if (inProgress() || paused()) return true;
        File marker = markerFile(context);
        return marker != null && marker.isFile();
    }

    /**
     * True if a sync was left unfinished (crash, kill, pause, or leftover
     * {@code .part} files). Used on app/service restart to auto-continue.
     * May scan the HD — call only from a background thread.
     */
    boolean hasSuspendedWork(Context context) {
        if (hasSuspendedWorkQuick(context)) return true;
        File root = DaemonDisk.photosDir(context);
        return PhotoSyncEngine.hasIncomplete(root);
    }

    /** Clear the user-pause flag so a restart can auto-resume suspended work. */
    void clearPauseForAutoResume() {
        if (!paused()) return;
        prefs.edit().putBoolean(KEY_PAUSED, false).commit();
    }

    void setPaused(boolean paused) {
        SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_PAUSED, paused);
        if (paused) editor.putBoolean(KEY_PAUSE_UNTIL_SCHEDULE, false);
        editor.commit();
    }

    void setPauseUntilSchedule(boolean defer) {
        SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_PAUSE_UNTIL_SCHEDULE, defer);
        if (defer) editor.putBoolean(KEY_PAUSED, false);
        editor.commit();
    }

    void markInProgress(Context context) {
        prefs.edit().putBoolean(KEY_IN_PROGRESS, true)
                .putBoolean(KEY_PAUSED, false)
                .putBoolean(KEY_PAUSE_UNTIL_SCHEDULE, false)
                .commit();
        writeMarker(context, true);
    }

    void markInterrupted(Context context) {
        if (!inProgress()) return;
        writeMarker(context, true);
        prefs.edit().putBoolean(KEY_IN_PROGRESS, true).commit();
    }

    void clearInProgress(Context context) {
        prefs.edit().putBoolean(KEY_IN_PROGRESS, false)
                .putBoolean(KEY_PAUSED, false)
                .putBoolean(KEY_PAUSE_UNTIL_SCHEDULE, false)
                .commit();
        writeMarker(context, false);
    }

    static File markerFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        if (dir == null) return null;
        return new File(dir, MARKER_NAME);
    }

    private static void writeMarker(Context context, boolean present) {
        File marker = markerFile(context);
        if (marker == null) return;
        try {
            if (present) {
                java.io.FileOutputStream out = new java.io.FileOutputStream(marker, false);
                try {
                    String line = Long.toString(System.currentTimeMillis()) + "\n";
                    out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
                //noinspection ResultOfMethodCallIgnored
                marker.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                marker.setWritable(true, false);
            } else if (marker.exists()) {
                //noinspection ResultOfMethodCallIgnored
                marker.delete();
            }
        } catch (Exception ignored) {
        }
    }

    void setPhotosPath(String path) {
        prefs.edit().putString(KEY_PHOTOS_PATH, path == null ? "" : path).apply();
    }

    void recordSuccess(int copied, int skipped, int deleted, long bytes) {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putLong(KEY_LAST_AT, now)
                .putLong(KEY_LAST_ATTEMPT, now)
                .putInt(KEY_LAST_COPIED, copied)
                .putInt(KEY_LAST_SKIPPED, skipped)
                .putInt(KEY_LAST_DELETED, deleted)
                .putLong(KEY_LAST_BYTES, bytes)
                .putString(KEY_LAST_ERROR, "")
                .putBoolean(KEY_LAST_OK, true)
                .commit();
    }

    void recordFailure(String error) {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putLong(KEY_LAST_AT, now)
                .putLong(KEY_LAST_ATTEMPT, now)
                .putString(KEY_LAST_ERROR, error == null ? "" : error)
                .putBoolean(KEY_LAST_OK, false)
                .commit();
    }

    /** True if a sync finished (or was attempted) within the night interval. */
    boolean syncedWithinInterval(long nowMs) {
        long last = Math.max(lastAt(), lastAttemptAt());
        return last > 0 && nowMs - last < nightIntervalMs();
    }
}
