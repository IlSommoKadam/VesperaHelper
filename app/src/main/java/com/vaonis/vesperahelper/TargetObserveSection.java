package com.vaonis.vesperahelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Catalog search, field preview, and multi-night start/resume. */
final class TargetObserveSection {
    interface Bridge {
        boolean connected();
        String host();
        int apiPort();
        Network network();
        VesperaStatusSnapshot status();
        VesperaLocationClient.Site site();
        boolean beginCommand();
        void endCommand();
    }

    private final Activity activity;
    private final float density;
    private final Bridge bridge;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinearLayout root;
    private final EditText query;
    private final Button search;
    private final ImageView preview;
    private final TextView details;
    private final TextView sessionLine;
    private final Button continueNight;
    private final Button startNight;
    private final TextView result;

    private SkyCatalog.Target target;
    private SkyCatalog.Session session;
    private Bitmap previewBitmap;
    private Dialog fullPreview;
    private boolean busy;

    TargetObserveSection(Activity activity, float density, Bridge bridge) {
        this.activity = activity;
        this.density = density;
        this.bridge = bridge;

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.telescope_section_observe));
        title.setTextSize(14);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(UiStyle.SLATE);
        UiStyle.spaceBelow(title, density * 0.5f);
        root.addView(title);

        TextView hint = text(activity.getString(R.string.telescope_observe_hint));
        hint.setTextSize(13);
        root.addView(hint);

        query = new EditText(activity);
        query.setSingleLine(true);
        query.setHint(activity.getString(R.string.telescope_observe_query_hint));
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search();
                return true;
            }
            return false;
        });
        UiStyle.spaceBelow(query, density);
        root.addView(query);

        search = button(activity.getString(R.string.telescope_observe_search), UiStyle.STEEL_BLUE);
        search.setOnClickListener(v -> search());
        root.addView(search);

        preview = new ImageView(activity);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFF101418);
        LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        imageLp.gravity = Gravity.CENTER_HORIZONTAL;
        imageLp.bottomMargin = (int) (8 * density);
        preview.setLayoutParams(imageLp);
        preview.setOnClickListener(v -> openFullPreview());
        preview.setVisibility(ImageView.GONE);
        root.addView(preview);

        details = text("");
        details.setVisibility(TextView.GONE);
        root.addView(details);

        sessionLine = text("");
        sessionLine.setTextSize(13);
        sessionLine.setVisibility(TextView.GONE);
        root.addView(sessionLine);

        continueNight = button(activity.getString(R.string.telescope_observe_continue), UiStyle.GREEN);
        continueNight.setVisibility(Button.GONE);
        continueNight.setOnClickListener(v -> confirmSend(true));
        root.addView(continueNight);

        startNight = button(activity.getString(R.string.telescope_observe_start), UiStyle.STEEL_BLUE);
        startNight.setVisibility(Button.GONE);
        startNight.setOnClickListener(v -> confirmSend(false));
        root.addView(startNight);

        result = text("");
        result.setTextColor(UiStyle.SLATE);
        result.setVisibility(TextView.GONE);
        root.addView(result);
    }

    LinearLayout view() {
        return root;
    }

    void onStatus(VesperaStatusSnapshot snap) {
        if (target == null) return;
        session = SkyCatalog.findSession(
                snap == null ? "" : snap.rawJson, target.name, target.query);
        showSession();
    }

    private void search() {
        String name = query.getText() == null ? "" : query.getText().toString().trim();
        if (name.isEmpty() || busy) return;
        busy = true;
        search.setEnabled(false);
        result.setVisibility(TextView.VISIBLE);
        result.setText(activity.getString(R.string.telescope_observe_searching));
        worker.execute(() -> {
            try {
                SkyCatalog.Target found = SkyCatalog.lookup(activity, name);
                SkyCatalog.Session stored = SkyCatalog.findSession(
                        rawStatus(), found.name, found.query);
                main.post(() -> showTarget(found, stored));
            } catch (Exception failure) {
                String code = failure.getMessage() == null ? "" : failure.getMessage();
                main.post(() -> {
                    busy = false;
                    search.setEnabled(true);
                    result.setText("not_found".equals(code) || "empty".equals(code)
                            ? activity.getString(R.string.telescope_observe_not_found)
                            : activity.getString(R.string.telescope_observe_net));
                });
            }
        });
    }

    private void showTarget(SkyCatalog.Target found, SkyCatalog.Session stored) {
        busy = false;
        search.setEnabled(true);
        target = found;
        session = stored;
        Bitmap bitmap = null;
        if (found.previewJpeg != null && found.previewJpeg.length > 0) {
            bitmap = BitmapFactory.decodeByteArray(
                    found.previewJpeg, 0, found.previewJpeg.length);
        }
        bindPreview(bitmap);
        details.setText(pointingText(found));
        details.setVisibility(TextView.VISIBLE);
        showSession();
        result.setText(found.previewJpeg == null
                ? activity.getString(R.string.telescope_observe_preview_fail)
                : "");
        if (result.getText().length() == 0) result.setVisibility(TextView.GONE);
    }

    private void bindPreview(Bitmap bitmap) {
        if (fullPreview != null && fullPreview.isShowing()) fullPreview.dismiss();
        Bitmap previous = previewBitmap;
        previewBitmap = bitmap;
        if (bitmap == null) {
            preview.setImageDrawable(null);
            preview.setVisibility(ImageView.GONE);
        } else {
            int maxW = previewWidth();
            int maxH = Math.round(340 * density);
            float aspect = bitmap.getWidth() / (float) Math.max(1, bitmap.getHeight());
            int width = maxW;
            int height = Math.round(width / aspect);
            if (height > maxH) {
                height = maxH;
                width = Math.round(height * aspect);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = (int) (8 * density);
            preview.setLayoutParams(lp);
            preview.setImageBitmap(bitmap);
            preview.setVisibility(ImageView.VISIBLE);
        }
        if (previous != null && previous != bitmap) previous.recycle();
    }

    private int previewWidth() {
        int width = root.getWidth();
        if (width <= 0) width = activity.getResources().getDisplayMetrics().widthPixels;
        int pad = Math.round(24 * density);
        return Math.max(Math.round(160 * density), width - pad);
    }

    private void openFullPreview() {
        if (previewBitmap == null) return;
        if (fullPreview != null && fullPreview.isShowing()) {
            fullPreview.dismiss();
            return;
        }
        Dialog dialog = new Dialog(activity,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout frame = new FrameLayout(activity);
        frame.setBackgroundColor(0xFF000000);
        ImageView full = new ImageView(activity);
        full.setScaleType(ImageView.ScaleType.FIT_CENTER);
        full.setImageBitmap(previewBitmap);
        frame.addView(full, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        View.OnClickListener close = v -> dialog.dismiss();
        frame.setOnClickListener(close);
        full.setOnClickListener(close);
        dialog.setContentView(frame);
        dialog.setOnDismissListener(d -> {
            if (fullPreview == dialog) fullPreview = null;
        });
        fullPreview = dialog;
        dialog.show();
    }

    private void showSession() {
        if (target == null) return;
        if (session != null) {
            sessionLine.setText(activity.getString(
                    R.string.telescope_observe_store, session.stacks, session.objectName));
            continueNight.setVisibility(Button.VISIBLE);
            startNight.setText(activity.getString(R.string.telescope_observe_fresh));
        } else {
            sessionLine.setText(activity.getString(R.string.telescope_observe_no_store));
            continueNight.setVisibility(Button.GONE);
            startNight.setText(activity.getString(R.string.telescope_observe_start));
        }
        sessionLine.setVisibility(TextView.VISIBLE);
        startNight.setVisibility(Button.VISIBLE);
    }

    private String pointingText(SkyCatalog.Target found) {
        String type = SkyCatalog.typeLabel(found.typeCode);
        String coords = activity.getString(R.string.telescope_observe_pointing,
                found.name,
                type.isEmpty() ? found.typeCode : type + " (" + found.typeCode + ")",
                SkyCatalog.raText(found.raDeg),
                SkyCatalog.decText(found.decDeg),
                found.raDeg,
                found.decDeg);
        VesperaLocationClient.Site site = PhotoSyncStore.from(activity).hasSite()
                ? new VesperaLocationClient.Site(
                PhotoSyncStore.from(activity).siteLat(),
                PhotoSyncStore.from(activity).siteLon())
                : null;
        if (site == null) {
            return coords + "\n" + activity.getString(R.string.telescope_observe_no_site_alt);
        }
        double[] altAz = SkyCatalog.altAz(
                site.lat, site.lon, found.raDeg, found.decDeg, System.currentTimeMillis());
        String where = altAz[0] < 0
                ? activity.getString(R.string.telescope_observe_below)
                : activity.getString(R.string.telescope_observe_alt, altAz[0], altAz[1]);
        return coords + "\n" + where;
    }

    private void confirmSend(boolean resume) {
        if (target == null || busy) return;
        if (resume && session == null) return;
        if (!bridge.connected()) {
            result.setVisibility(TextView.VISIBLE);
            result.setText(activity.getString(R.string.telescope_observe_need_wifi));
            return;
        }
        String message = resume
                ? activity.getString(R.string.telescope_observe_confirm_resume,
                target.name, session.stacks)
                : activity.getString(R.string.telescope_observe_confirm_new,
                target.name, SkyCatalog.raText(target.raDeg), SkyCatalog.decText(target.decDeg));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(resume
                        ? R.string.telescope_observe_continue
                        : R.string.telescope_observe_start)
                .setMessage(message)
                .setPositiveButton(R.string.telescope_confirm_ok, (d, w) -> send(resume))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        UiStyle.styleAlertButtons(dialog);
    }

    private void send(boolean resume) {
        if (!bridge.beginCommand()) {
            result.setVisibility(TextView.VISIBLE);
            result.setText(activity.getString(R.string.telescope_observe_busy));
            return;
        }
        busy = true;
        search.setEnabled(false);
        continueNight.setEnabled(false);
        startNight.setEnabled(false);
        result.setVisibility(TextView.VISIBLE);
        result.setText(activity.getString(R.string.telescope_observe_sending));
        String body = resume
                ? SkyCatalog.resumeBody(session)
                : SkyCatalog.startBody(target, bridge.status());
        String fetchHost = bridge.host();
        int fetchPort = bridge.apiPort();
        Network network = bridge.network();
        VesperaLocationClient.Site site = bridge.site();
        boolean stored = resume;
        worker.execute(() -> {
            VesperaCommandClient.Result sent = VesperaCommandClient.observe(
                    fetchHost, fetchPort, network, site, body, stored);
            main.post(() -> {
                busy = false;
                search.setEnabled(true);
                continueNight.setEnabled(true);
                startNight.setEnabled(true);
                bridge.endCommand();
                result.setText(sent.success
                        ? activity.getString(resume
                        ? R.string.telescope_observe_ok_resume
                        : R.string.telescope_observe_ok_new, target.name)
                        : failureText(sent.message));
            });
        });
    }

    private String failureText(String message) {
        if (message == null) message = "";
        if ("auth_required".equals(message) || "auth_sign_failed".equals(message)) {
            return activity.getString(R.string.telescope_command_auth);
        }
        if ("no_site".equals(message)) {
            return activity.getString(R.string.telescope_command_no_site);
        }
        if (message.startsWith("auth_missing")) {
            return activity.getString(R.string.telescope_command_auth_missing);
        }
        if ("init_timeout".equals(message) || "init_not_ready".equals(message)
                || message.startsWith("init_failed")) {
            return activity.getString(R.string.telescope_command_init_before_resume_fail, message);
        }
        return activity.getString(R.string.telescope_observe_fail, message);
    }

    private String rawStatus() {
        VesperaStatusSnapshot snap = bridge.status();
        return snap == null ? "" : snap.rawJson;
    }

    private TextView text(String value) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(14);
        view.setTextColor(0xFF37474F);
        view.setFocusable(false);
        UiStyle.applyRecessed(view, 0xFFECEFF1);
        UiStyle.spaceBelow(view, density);
        return view;
    }

    private Button button(String label, int color) {
        Button button = new Button(activity);
        button.setAllCaps(true);
        button.setText(label);
        UiStyle.applyRaised(button, color, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * density);
        button.setLayoutParams(lp);
        return button;
    }
}
