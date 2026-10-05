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

/** Tab Notifiche: bot Telegram, chat ID, interruttore unico, Salva e prova. */
final class TelegramPanel {
    private final Activity activity;
    private final float density;
    private final TelegramSettingsStore settings;
    private final FixedScrollView scroll;
    private final EditText tokenInput;
    private final EditText chatInput;
    private final CheckBox enableCheck;
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
        enableCheck = new CheckBox(activity);
        enableCheck.setText(R.string.telegram_enable_all);
        enableCheck.setChecked(snap.anyEnabled());
        enableCheck.setFocusable(false);
        enableCheck.setFocusableInTouchMode(false);
        enableCheck.setTextSize(15);
        enableCheck.setTypeface(enableCheck.getTypeface(), Typeface.BOLD);
        enableCheck.setTextColor(0xFF1A237E);
        enableCheck.setPadding(0, 0, 0, (int) (2 * density));
        events.addView(enableCheck);
        TextView hint = body(activity.getString(R.string.telegram_enable_hint));
        hint.setTextSize(12);
        hint.setTextColor(0xFF607D8B);
        events.addView(hint);

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
        enableCheck.setChecked(snap.anyEnabled());
    }

    private TelegramSettingsStore.Snapshot collect() {
        TelegramSettingsStore.Snapshot snap = new TelegramSettingsStore.Snapshot();
        snap.token = tokenInput.getText().toString().trim();
        snap.chatId = chatInput.getText().toString().trim();
        snap.setAllEvents(enableCheck.isChecked());
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
}
