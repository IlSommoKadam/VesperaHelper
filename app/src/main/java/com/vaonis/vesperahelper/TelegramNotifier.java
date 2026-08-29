package com.vaonis.vesperahelper;

import android.content.Context;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends enabled telescope events to Telegram. */
final class TelegramNotifier implements TelescopeStatusHub.Listener {
    private static final String TAG = "VesperaTelegram";
    private static final int QUEUE_MAX = 20;

    private final Context app;
    private final TelegramSettingsStore settings;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> pending = new ArrayDeque<>();

    TelegramNotifier(Context context) {
        this.app = context.getApplicationContext();
        this.settings = TelegramSettingsStore.from(app);
    }

    @Override public void onTelescopeEvent(TelescopeStatusEvent event) {
        if (event == null) return;
        if (!settings.configured()) return;
        if (!settings.enabled(event.kind)) return;
        String text = format(event);
        if (text.isEmpty()) return;
        worker.execute(() -> sendOrQueue(text));
    }

    void sendTest(Runnable onDone) {
        worker.execute(() -> {
            Context localized = AppLocale.wrap(app);
            TelegramBotClient.Result me = TelegramBotClient.getMe(app, settings.token());
            if (!me.ok) {
                settings.recordError(me.error);
                if (onDone != null) onDone.run();
                return;
            }
            TelegramBotClient.Result sent = TelegramBotClient.sendMessage(
                    app, settings.token(), settings.chatId(),
                    localized.getString(R.string.telegram_test_message));
            if (sent.ok) settings.recordOk();
            else settings.recordError(sent.error);
            if (onDone != null) onDone.run();
        });
    }

    void flushQueue() {
        worker.execute(this::drainQueue);
    }

    private void sendOrQueue(String text) {
        if (!settings.configured()) return;
        if (!InternetNetwork.available(app)) {
            queue(text);
            return;
        }
        TelegramBotClient.Result result = TelegramBotClient.sendMessage(
                app, settings.token(), settings.chatId(), text);
        if (result.ok) {
            settings.recordOk();
            drainQueue();
            return;
        }
        settings.recordError(result.error);
        Log.w(TAG, "send failed " + result.error);
        queue(text);
    }

    private void queue(String text) {
        if (pending.size() >= QUEUE_MAX) pending.pollFirst();
        pending.addLast(text);
    }

    private void drainQueue() {
        if (!settings.configured()) return;
        if (!InternetNetwork.available(app)) return;
        while (!pending.isEmpty()) {
            String text = pending.peekFirst();
            TelegramBotClient.Result result = TelegramBotClient.sendMessage(
                    app, settings.token(), settings.chatId(), text);
            if (!result.ok) {
                settings.recordError(result.error);
                return;
            }
            pending.pollFirst();
            settings.recordOk();
        }
    }

    private String format(TelescopeStatusEvent event) {
        Context localized = AppLocale.wrap(app);
        String target = event.target.isEmpty()
                ? localized.getString(R.string.telegram_target_unknown)
                : event.target;
        switch (event.kind) {
            case CONNECTED:
                return localized.getString(R.string.telegram_msg_connected);
            case LOST:
                return localized.getString(R.string.telegram_msg_lost);
            case INITIALIZED:
                return localized.getString(R.string.telegram_msg_initialized);
            case SHUTTING_DOWN:
                return localized.getString(R.string.telegram_msg_shutdown);
            case POWER_OFF_MAINS:
                return localized.getString(R.string.telegram_msg_battery);
            case BATTERY_LOW:
                return localized.getString(R.string.telegram_msg_battery_low, event.percent);
            case HD_HIGH:
                return localized.getString(R.string.telegram_msg_hd, event.percent);
            case STORAGE_INTERNAL_HIGH:
                return localized.getString(R.string.telegram_msg_storage, event.percent);
            case OBS_STARTED:
                return localized.getString(R.string.telegram_msg_obs_started, target);
            case OBS_RESUMED:
                return localized.getString(R.string.telegram_msg_obs_resumed, target);
            case OBS_STOPPED:
                return localized.getString(R.string.telegram_msg_obs_stopped, target);
            case OBS_FINISHED:
                return localized.getString(R.string.telegram_msg_obs_finished, target);
            case ERROR:
                String err = event.error.isEmpty() ? "—" : event.error;
                return localized.getString(R.string.telegram_msg_error, err);
            case SUN_TOO_HIGH:
                return localized.getString(R.string.telegram_msg_sun);
            default:
                return "";
        }
    }
}
