package com.vaonis.vesperahelper;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tab Notifiche: bot Telegram, chat ID, checkbox eventi, Salva e prova. */
final class TelegramPanel {
    private final Activity activity;
    private final float density;
    private final TelegramSettingsStore settings;
    private final FixedScrollView scroll;
    private final EditText tokenInput;
    private final EditText chatInput;
    private final Row initialized;
    private final Row shutdown;
    private final Row batteryOffMains;
    private final Row hdHigh;
    private final Row connected;
    private final Row obsStopped;
    private final Row obsStarted;
    private final Row error;
    private final Row lost;
    private final Row obsFinished;
    private final Row sunTooHigh;
    private final Row batteryLow;
    private final Row storageInternalHigh;
    private final Row rainForecast;
    private final TextView saveResult;
    private final TextView lastStatus;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean visible;

    TelegramPanel(Activity activity, float density, int padding) {
        this.activity = activity;
        this.density = density;
        this.settings = TelegramSettingsStore.from(activity);

        scroll = new FixedScrollView(activity);
        scroll.setVisibility(View.GONE);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, (int) (80 * density));
        layout.setLayoutParams(new ScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        layout.addView(title(activity.getString(R.string.telegram_tab_title)));
        layout.addView(body(activity.getString(R.string.telegram_tab_intro)));

        layout.addView(section(activity.getString(R.string.telegram_section_bot)));
        tokenInput = field(activity.getString(R.string.telegram_token_hint), true);
        chatInput = field(activity.getString(R.string.telegram_chat_hint), false);
        layout.addView(label(activity.getString(R.string.telegram_token_label)));
        layout.addView(tokenInput);
        layout.addView(label(activity.getString(R.string.telegram_chat_label)));
        layout.addView(chatInput);

        TelegramSettingsStore.Snapshot snap = settings.snapshot();
        tokenInput.setText(snap.token);
        chatInput.setText(snap.chatId);

        LinearLayout events = addGroup(layout, R.string.telegram_section_events, 0);
        initialized = addRow(events, activity.getString(R.string.telegram_ev_initialized),
                snap.initialized);
        shutdown = addRow(events, activity.getString(R.string.telegram_ev_shutdown), snap.shutdown);
        batteryOffMains = addRow(events, activity.getString(R.string.telegram_ev_battery),
                snap.batteryOffMains);
        hdHigh = addRow(events, activity.getString(R.string.telegram_ev_hd), snap.hdHigh);
        connected = addRow(events, activity.getString(R.string.telegram_ev_connected), snap.connected);
        obsStopped = addRow(events, activity.getString(R.string.telegram_ev_obs_stopped),
                snap.obsStopped);
        obsStarted = addRow(events, activity.getString(R.string.telegram_ev_obs_started),
                snap.obsStarted);
        error = addRow(events, activity.getString(R.string.telegram_ev_error), snap.error);

        LinearLayout extra = addGroup(layout, R.string.telegram_section_extra, 0);
        lost = addRow(extra, activity.getString(R.string.telegram_ev_lost), snap.lost);
        obsFinished = addRow(extra, activity.getString(R.string.telegram_ev_obs_finished),
                snap.obsFinished);
        sunTooHigh = addRow(extra, activity.getString(R.string.telegram_ev_sun), snap.sunTooHigh);
        batteryLow = addRow(extra, activity.getString(R.string.telegram_ev_battery_low),
                snap.batteryLow);
        storageInternalHigh = addRow(extra, activity.getString(R.string.telegram_ev_storage),
                snap.storageInternalHigh);
        rainForecast = addRow(extra, activity.getString(R.string.telegram_ev_rain),
                snap.rainForecast);

        Button save = new Button(activity);
        save.setAllCaps(true);
        save.setText(R.string.telegram_save);
        UiStyle.applyRaised(save, UiStyle.SLATE, true);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = (int) (8 * density);
        save.setLayoutParams(saveLp);
        save.setOnClickListener(v -> saveAll());
        layout.addView(save);

        Button test = new Button(activity);
        test.setAllCaps(true);
        test.setText(R.string.telegram_test);
        UiStyle.applyRaised(test, UiStyle.GREEN, true);
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        testLp.topMargin = (int) (6 * density);
        testLp.bottomMargin = (int) (6 * density);
        test.setLayoutParams(testLp);
        test.setOnClickListener(v -> sendTest());
        layout.addView(test);

        saveResult = body("");
        saveResult.setTextColor(UiStyle.GREEN);
        layout.addView(saveResult);

        lastStatus = body("");
        lastStatus.setTextSize(13);
        lastStatus.setTextColor(0xFF455A64);
        layout.addView(lastStatus);

        scroll.addView(layout);
        refreshStatus();
    }

    View view() {
        return scroll;
    }

    void onVisible() {
        visible = true;
        loadChecks();
        saveResult.setText("");
        refreshStatus();
    }

    void onHidden() {
        visible = false;
    }

    private void loadChecks() {
        TelegramSettingsStore.Snapshot snap = settings.snapshot();
        if (tokenInput.getText().toString().isEmpty()) tokenInput.setText(snap.token);
        if (chatInput.getText().toString().isEmpty()) chatInput.setText(snap.chatId);
        initialized.check.setChecked(snap.initialized);
        shutdown.check.setChecked(snap.shutdown);
        batteryOffMains.check.setChecked(snap.batteryOffMains);
        hdHigh.check.setChecked(snap.hdHigh);
        connected.check.setChecked(snap.connected);
        obsStopped.check.setChecked(snap.obsStopped);
        obsStarted.check.setChecked(snap.obsStarted);
        error.check.setChecked(snap.error);
        lost.check.setChecked(snap.lost);
        obsFinished.check.setChecked(snap.obsFinished);
        sunTooHigh.check.setChecked(snap.sunTooHigh);
        batteryLow.check.setChecked(snap.batteryLow);
        storageInternalHigh.check.setChecked(snap.storageInternalHigh);
        rainForecast.check.setChecked(snap.rainForecast);
    }

    private TelegramSettingsStore.Snapshot collect() {
        TelegramSettingsStore.Snapshot snap = new TelegramSettingsStore.Snapshot();
        snap.token = tokenInput.getText().toString().trim();
        snap.chatId = chatInput.getText().toString().trim();
        snap.initialized = initialized.check.isChecked();
        snap.shutdown = shutdown.check.isChecked();
        snap.batteryOffMains = batteryOffMains.check.isChecked();
        snap.hdHigh = hdHigh.check.isChecked();
        snap.connected = connected.check.isChecked();
        snap.obsStopped = obsStopped.check.isChecked();
        snap.obsStarted = obsStarted.check.isChecked();
        snap.error = error.check.isChecked();
        snap.lost = lost.check.isChecked();
        snap.obsFinished = obsFinished.check.isChecked();
        snap.sunTooHigh = sunTooHigh.check.isChecked();
        snap.batteryLow = batteryLow.check.isChecked();
        snap.storageInternalHigh = storageInternalHigh.check.isChecked();
        snap.rainForecast = rainForecast.check.isChecked();
        return snap;
    }

    private void saveAll() {
        scroll.pin();
        settings.save(collect());
        saveResult.setTextColor(UiStyle.GREEN);
        saveResult.setText(R.string.telegram_saved);
        refreshStatus();
    }

    private void sendTest() {
        scroll.pin();
        settings.save(collect());
        saveResult.setTextColor(UiStyle.SLATE);
        saveResult.setText(R.string.telegram_test_sending);
        worker.execute(() -> {
            TelegramBotClient.Result me = TelegramBotClient.getMe(activity, settings.token());
            if (!me.ok) {
                settings.recordError(me.error);
                mainHandler.post(() -> showTestResult(false, me.error));
                return;
            }
            TelegramBotClient.Result sent = TelegramBotClient.sendMessage(
                    activity, settings.token(), settings.chatId(),
                    activity.getString(R.string.telegram_test_message));
            if (sent.ok) settings.recordOk();
            else settings.recordError(sent.error);
            mainHandler.post(() -> showTestResult(sent.ok, sent.error));
        });
    }

    private void showTestResult(boolean ok, String error) {
        if (!visible) return;
        saveResult.setTextColor(ok ? UiStyle.GREEN : UiStyle.ROSE);
        saveResult.setText(ok
                ? activity.getString(R.string.telegram_test_ok)
                : activity.getString(R.string.telegram_test_fail, error));
        refreshStatus();
    }

    private void refreshStatus() {
        if (!settings.configured()) {
            lastStatus.setText(R.string.telegram_status_unconfigured);
            return;
        }
        String error = settings.lastError();
        long okAt = settings.lastOkAt();
        if (!error.isEmpty()) {
            lastStatus.setText(activity.getString(R.string.telegram_status_error, error));
            return;
        }
        if (okAt > 0) {
            String when = DateFormat.getTimeFormat(activity).format(new Date(okAt));
            lastStatus.setText(activity.getString(R.string.telegram_status_ok, when));
            return;
        }
        lastStatus.setText(R.string.telegram_status_ready);
    }

    private EditText field(String hint, boolean password) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        if (password) {
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (6 * density);
        input.setLayoutParams(lp);
        return input;
    }

    private LinearLayout addGroup(LinearLayout layout, int sectionTitleRes, int introRes) {
        layout.addView(section(activity.getString(sectionTitleRes)));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.max(8, Math.round(10 * density));
        box.setPadding(pad, pad, pad, pad);
        Drawable bg = UiStyle.recessedStatus(0xFFECEFF1, density);
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (2 * density);
        lp.bottomMargin = (int) (10 * density);
        box.setLayoutParams(lp);
        layout.addView(box);
        return box;
    }

    private Row addRow(LinearLayout group, String title, boolean checked) {
        if (countCheckRows(group) > 0) group.addView(rowDivider());
        CheckBox check = new CheckBox(activity);
        check.setText(title);
        check.setChecked(checked);
        check.setFocusable(false);
        check.setFocusableInTouchMode(false);
        check.setTextSize(15);
        check.setTypeface(check.getTypeface(), Typeface.BOLD);
        check.setTextColor(0xFF1A237E);
        check.setPadding(0, 0, 0, (int) (2 * density));
        group.addView(check);
        return new Row(check);
    }

    private int countCheckRows(LinearLayout group) {
        int n = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof CheckBox) n++;
        }
        return n;
    }

    private View rowDivider() {
        View divider = new View(activity);
        divider.setBackgroundColor(0xFFB0BEC5);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(1 * density)));
        lp.topMargin = (int) (6 * density);
        lp.bottomMargin = (int) (6 * density);
        divider.setLayoutParams(lp);
        return divider;
    }

    private TextView section(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setPadding(0, (int) (12 * density), 0, (int) (4 * density));
        view.setTextColor(UiStyle.SLATE);
        view.setTextSize(14);
        return view;
    }

    private TextView title(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTypeface(view.getTypeface(), Typeface.BOLD);
        view.setPadding(0, (int) (10 * density), 0, (int) (4 * density));
        view.setTextColor(0xFF1A237E);
        view.setTextSize(16);
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(0xFF455A64);
        view.setPadding(0, (int) (4 * density), 0, 0);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setPadding(0, (int) (2 * density), 0, (int) (6 * density));
        return view;
    }

    private static final class Row {
        final CheckBox check;

        Row(CheckBox check) {
            this.check = check;
        }
    }
}
