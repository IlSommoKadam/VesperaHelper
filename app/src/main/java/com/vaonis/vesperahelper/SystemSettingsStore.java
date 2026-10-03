package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.SharedPreferences;

/** Enable/disable flags for background automatic activities. Defaults keep current behaviour. */
final class SystemSettingsStore {
    private static final String PREFS = "vespera_system";
    private static final String KEY_PHOTO_SYNC = "photo_sync";
    private static final String KEY_STORAGE_SYNC = "storage_sync";
    private static final String KEY_RESUME_SYNC = "resume_sync";
    private static final String KEY_HD_MOUNT = "hd_mount";
    private static final String KEY_CLOCK_NTP = "clock_ntp";
    private static final String KEY_BOOT_START = "boot_start";
    private static final String KEY_WIFI_CONNECT = "wifi_connect";
    private static final String KEY_SINGULARITY_START = "singularity_start";
    private static final String KEY_WATCHDOG = "watchdog";
    private static final String KEY_FTP_LOCAL = "ftp_local";
    private static final String KEY_KEEP_ALIVE = "keep_alive";
    /** Daily morning shutdown (~30 min after sunrise). */
    private static final String KEY_SUN_CHECK = "sun_too_high";
    private static final String KEY_SUN_SYNC = "sun_sync";
    private static final String KEY_SUN_TELESCOPE = "sun_telescope_shutdown";
    private static final String KEY_SUN_HD = "sun_hd_shutdown";
    /** Set on the next settings save so a pre-checked inherited value is not treated as opt-in. */
    private static final String KEY_SUN_HD_CHOICE = "sun_hd_shutdown_choice";
    private static final String KEY_SUN_PI_SHUTDOWN = "sun_pi_shutdown";
    private static final String KEY_SUN_TOO_HIGH_DAY = "sun_too_high_day";
    private static final String KEY_SUN_TOO_HIGH_AT = "sun_too_high_at";
    private static final String KEY_SUN_TOO_HIGH_RESULT = "sun_too_high_result";
    private static final String KEY_SUN_TOO_HIGH_ATTEMPT = "sun_too_high_attempt";

    static final String SUN_RESULT_NOT_STATUS = "not_status";
    static final String SUN_RESULT_TRIGGERED = "triggered";
    static final String SUN_RESULT_SHUTDOWN_OK = "shutdown_ok";
    static final String SUN_RESULT_SHUTDOWN_FAIL = "shutdown_fail";

    /** Incomplete today's attempt: retry instead of waiting until tomorrow. */
    static boolean sunTooHighNeedsRetry(String result) {
        return SUN_RESULT_SHUTDOWN_FAIL.equals(result)
                || SUN_RESULT_TRIGGERED.equals(result)
                || SUN_RESULT_NOT_STATUS.equals(result);
    }

    static final class Snapshot {
        boolean photoSync;
        boolean storageSync;
        boolean resumeSync;
        boolean hdMount;
        boolean clockNtp;
        boolean bootStart;
        boolean wifiConnect;
        boolean singularityStart;
        boolean watchdog;
        boolean ftpLocal;
        boolean keepAlive;
        boolean sunCheck;
        boolean sunSync;
        boolean sunTelescopeShutdown;
        boolean sunHdShutdown;
        boolean sunPiShutdown;
    }

    private final SharedPreferences prefs;

    private SystemSettingsStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static SystemSettingsStore from(Context context) {
        return new SystemSettingsStore(
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    Snapshot snapshot() {
        Snapshot snap = new Snapshot();
        snap.photoSync = photoSync();
        snap.storageSync = storageSync();
        snap.resumeSync = resumeSync();
        snap.hdMount = hdMount();
        snap.clockNtp = clockNtp();
        snap.bootStart = bootStart();
        snap.wifiConnect = wifiConnect();
        snap.singularityStart = singularityStart();
        snap.watchdog = watchdog();
        snap.ftpLocal = ftpLocal();
        snap.keepAlive = keepAlive();
        snap.sunCheck = sunCheck();
        snap.sunSync = sunSync();
        snap.sunTelescopeShutdown = sunTelescopeShutdown();
        snap.sunHdShutdown = sunHdShutdown();
        snap.sunPiShutdown = sunPiShutdown();
        return snap;
    }

    void save(Snapshot snap) {
        if (snap == null) return;
        prefs.edit()
                .putBoolean(KEY_PHOTO_SYNC, snap.photoSync)
                .putBoolean(KEY_STORAGE_SYNC, snap.storageSync)
                .putBoolean(KEY_RESUME_SYNC, snap.resumeSync)
                .putBoolean(KEY_HD_MOUNT, snap.hdMount)
                .putBoolean(KEY_CLOCK_NTP, snap.clockNtp)
                .putBoolean(KEY_BOOT_START, snap.bootStart)
                .putBoolean(KEY_WIFI_CONNECT, snap.wifiConnect)
                .putBoolean(KEY_SINGULARITY_START, snap.singularityStart)
                .putBoolean(KEY_WATCHDOG, snap.watchdog)
                .putBoolean(KEY_FTP_LOCAL, snap.ftpLocal)
                .putBoolean(KEY_KEEP_ALIVE, snap.keepAlive)
                .putBoolean(KEY_SUN_CHECK, snap.sunCheck)
                .putBoolean(KEY_SUN_SYNC, snap.sunSync)
                .putBoolean(KEY_SUN_TELESCOPE, snap.sunTelescopeShutdown)
                .putBoolean(KEY_SUN_HD, snap.sunHdShutdown)
                .putBoolean(KEY_SUN_HD_CHOICE, true)
                .putBoolean(KEY_SUN_PI_SHUTDOWN, snap.sunPiShutdown)
                .commit();
    }

    boolean photoSync() { return prefs.getBoolean(KEY_PHOTO_SYNC, true); }
    boolean storageSync() { return prefs.getBoolean(KEY_STORAGE_SYNC, true); }
    boolean resumeSync() { return prefs.getBoolean(KEY_RESUME_SYNC, true); }
    boolean hdMount() { return prefs.getBoolean(KEY_HD_MOUNT, true); }
    boolean clockNtp() { return prefs.getBoolean(KEY_CLOCK_NTP, true); }
    boolean bootStart() { return prefs.getBoolean(KEY_BOOT_START, true); }
    boolean wifiConnect() { return prefs.getBoolean(KEY_WIFI_CONNECT, true); }
    boolean singularityStart() { return prefs.getBoolean(KEY_SINGULARITY_START, true); }
    boolean watchdog() { return prefs.getBoolean(KEY_WATCHDOG, true); }
    boolean ftpLocal() { return prefs.getBoolean(KEY_FTP_LOCAL, true); }
    boolean keepAlive() { return prefs.getBoolean(KEY_KEEP_ALIVE, true); }

    boolean sunCheck() { return prefs.getBoolean(KEY_SUN_CHECK, true); }

    /** Pre-0.6.86 installs bundled sync+telescope under {@link #KEY_SUN_CHECK}. */
    boolean sunSync() {
        if (!prefs.contains(KEY_SUN_SYNC)) return sunCheck();
        return prefs.getBoolean(KEY_SUN_SYNC, true);
    }

    boolean sunTelescopeShutdown() {
        if (!prefs.contains(KEY_SUN_TELESCOPE)) return sunCheck();
        return prefs.getBoolean(KEY_SUN_TELESCOPE, true);
    }

    /**
     * Off unless the user saved the checkbox after this opt-in.
     * Older builds inherited {@link #sunCheck()}, so a stored {@code true} was often never chosen.
     */
    boolean sunHdShutdown() {
        if (!prefs.getBoolean(KEY_SUN_HD_CHOICE, false)) return false;
        return prefs.getBoolean(KEY_SUN_HD, false);
    }

    boolean sunPiShutdown() { return prefs.getBoolean(KEY_SUN_PI_SHUTDOWN, false); }

    /** @deprecated use {@link #sunCheck()} */
    boolean sunTooHigh() { return sunCheck(); }

    int sunTooHighDay() { return prefs.getInt(KEY_SUN_TOO_HIGH_DAY, -1); }
    long sunTooHighAt() { return prefs.getLong(KEY_SUN_TOO_HIGH_AT, 0); }
    String sunTooHighResult() { return prefs.getString(KEY_SUN_TOO_HIGH_RESULT, ""); }
    int sunTooHighAttempt() { return prefs.getInt(KEY_SUN_TOO_HIGH_ATTEMPT, 0); }

    void recordSunTooHigh(int dayKey, String result) {
        recordSunTooHigh(dayKey, result, 0);
    }

    void recordSunTooHigh(int dayKey, String result, int attempt) {
        boolean done = SUN_RESULT_SHUTDOWN_OK.equals(result);
        prefs.edit()
                .putInt(KEY_SUN_TOO_HIGH_DAY, dayKey)
                .putLong(KEY_SUN_TOO_HIGH_AT, System.currentTimeMillis())
                .putString(KEY_SUN_TOO_HIGH_RESULT, result == null ? "" : result)
                .putInt(KEY_SUN_TOO_HIGH_ATTEMPT, done ? 0 : Math.max(0, attempt))
                .commit();
    }
}
