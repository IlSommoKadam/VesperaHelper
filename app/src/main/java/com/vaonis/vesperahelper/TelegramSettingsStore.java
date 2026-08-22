package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.SharedPreferences;

/** Bot token, chat id and per-event flags for Telegram. */
final class TelegramSettingsStore {
    private static final String PREFS = "vespera_telegram";
    private static final String KEY_TOKEN = "bot_token";
    private static final String KEY_CHAT = "chat_id";
    private static final String KEY_LAST_OK = "last_ok_at";
    private static final String KEY_LAST_ERROR = "last_error";

    static final class Snapshot {
        String token = "";
        String chatId = "";
        boolean initialized = true;
        boolean shutdown = true;
        boolean batteryOffMains = true;
        boolean hdHigh = true;
        boolean connected = true;
        boolean obsStopped = true;
        boolean obsStarted = true;
        boolean error = true;
        boolean lost = true;
        boolean obsFinished = true;
        boolean sunTooHigh = true;
        boolean batteryLow = true;
        boolean storageInternalHigh = false;
    }

    private final SharedPreferences prefs;

    private TelegramSettingsStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static TelegramSettingsStore from(Context context) {
        return new TelegramSettingsStore(
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    boolean configured() {
        return !token().isEmpty() && !chatId().isEmpty();
    }

    String token() { return prefs.getString(KEY_TOKEN, ""); }
    String chatId() { return prefs.getString(KEY_CHAT, ""); }
    long lastOkAt() { return prefs.getLong(KEY_LAST_OK, 0); }
    String lastError() { return prefs.getString(KEY_LAST_ERROR, ""); }

    Snapshot snapshot() {
        Snapshot snap = new Snapshot();
        snap.token = token();
        snap.chatId = chatId();
        snap.initialized = prefs.getBoolean("ev_initialized", true);
        snap.shutdown = prefs.getBoolean("ev_shutdown", true);
        snap.batteryOffMains = prefs.getBoolean("ev_battery", true);
        snap.hdHigh = prefs.getBoolean("ev_hd", true);
        snap.connected = prefs.getBoolean("ev_connected", true);
        snap.obsStopped = prefs.getBoolean("ev_obs_stopped", true);
        snap.obsStarted = prefs.getBoolean("ev_obs_started", true);
        snap.error = prefs.getBoolean("ev_error", true);
        snap.lost = prefs.getBoolean("ev_lost", true);
        snap.obsFinished = prefs.getBoolean("ev_obs_finished", true);
        snap.sunTooHigh = prefs.getBoolean("ev_sun", true);
        snap.batteryLow = prefs.getBoolean("ev_battery_low", true);
        snap.storageInternalHigh = prefs.getBoolean("ev_storage80", false);
        return snap;
    }

    void save(Snapshot snap) {
        if (snap == null) return;
        prefs.edit()
                .putString(KEY_TOKEN, snap.token == null ? "" : snap.token.trim())
                .putString(KEY_CHAT, snap.chatId == null ? "" : snap.chatId.trim())
                .putBoolean("ev_initialized", snap.initialized)
                .putBoolean("ev_shutdown", snap.shutdown)
                .putBoolean("ev_battery", snap.batteryOffMains)
                .putBoolean("ev_hd", snap.hdHigh)
                .putBoolean("ev_connected", snap.connected)
                .putBoolean("ev_obs_stopped", snap.obsStopped)
                .putBoolean("ev_obs_started", snap.obsStarted)
                .putBoolean("ev_error", snap.error)
                .putBoolean("ev_lost", snap.lost)
                .putBoolean("ev_obs_finished", snap.obsFinished)
                .putBoolean("ev_sun", snap.sunTooHigh)
                .putBoolean("ev_battery_low", snap.batteryLow)
                .putBoolean("ev_storage80", snap.storageInternalHigh)
                .commit();
    }

    void recordOk() {
        prefs.edit().putLong(KEY_LAST_OK, System.currentTimeMillis())
                .putString(KEY_LAST_ERROR, "")
                .commit();
    }

    void recordError(String error) {
        prefs.edit().putString(KEY_LAST_ERROR, error == null ? "" : error)
                .commit();
    }

    boolean enabled(TelescopeStatusEvent.Kind kind) {
        Snapshot snap = snapshot();
        switch (kind) {
            case INITIALIZED: return snap.initialized;
            case SHUTTING_DOWN: return snap.shutdown;
            case POWER_OFF_MAINS: return snap.batteryOffMains;
            case HD_HIGH: return snap.hdHigh;
            case CONNECTED: return snap.connected;
            case OBS_STOPPED: return snap.obsStopped;
            case OBS_STARTED:
            case OBS_RESUMED: return snap.obsStarted;
            case ERROR: return snap.error;
            case LOST: return snap.lost;
            case OBS_FINISHED: return snap.obsFinished;
            case SUN_TOO_HIGH: return snap.sunTooHigh;
            case BATTERY_LOW: return snap.batteryLow;
            case STORAGE_INTERNAL_HIGH: return snap.storageInternalHigh;
            default: return false;
        }
    }
}
