package com.vaonis.vesperahelper;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Compact app-wide footer: one thin row with day arrows and 3 h overnight slots
 * through dawn.
 */
final class NightForecastFooter {
    private static final long REFRESH_MS = 30 * 60 * 1000L;

    private final Context app;
    private final float density;
    private final LinearLayout root;
    private final Button prevDay;
    private final Button nextDay;
    private final TextView dayLabel;
    private final LinearLayout slotsRow;
    private final TextView flagView;
    private final ImageView pinView;
    private final TextView placeLabelView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable refreshTick = this::refresh;

    private List<OpenMeteoClient.NightWindow> nights = new ArrayList<>();
    private int nightIndex;
    private String lastPlace = "";
    private TimeZone lastZone = TimeZone.getDefault();
    private boolean started;

    NightForecastFooter(Context context, float density) {
        this.app = context.getApplicationContext();
        this.density = density;

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFE8EEF4);
        int padH = (int) (8 * density);
        int padV = (int) (6 * density);
        root.setPadding(padH, padV, padH, padV);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        prevDay = navButton(context, "‹");
        prevDay.setOnClickListener(v -> shiftDay(-1));
        row.addView(prevDay);

        dayLabel = new TextView(context);
        dayLabel.setTextSize(16);
        dayLabel.setTypeface(dayLabel.getTypeface(), Typeface.BOLD);
        dayLabel.setTextColor(0xFF37474F);
        dayLabel.setMaxLines(1);
        dayLabel.setPadding((int) (4 * density), 0, (int) (6 * density), 0);
        row.addView(dayLabel);

        nextDay = navButton(context, "›");
        nextDay.setOnClickListener(v -> shiftDay(1));
        row.addView(nextDay);

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        scrollLp.setMarginStart((int) (6 * density));
        scroll.setLayoutParams(scrollLp);

        slotsRow = new LinearLayout(context);
        slotsRow.setOrientation(LinearLayout.HORIZONTAL);
        slotsRow.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(slotsRow);
        row.addView(scroll);

        LinearLayout placeBox = new LinearLayout(context);
        placeBox.setOrientation(LinearLayout.HORIZONTAL);
        placeBox.setGravity(Gravity.CENTER_VERTICAL);
        placeBox.setPadding((int) (6 * density), 0, 0, 0);

        flagView = new TextView(context);
        flagView.setTextSize(18);
        flagView.setIncludeFontPadding(false);
        flagView.setPadding(0, 0, (int) (4 * density), 0);
        placeBox.addView(flagView);

        pinView = new ImageView(context);
        pinView.setImageResource(R.drawable.ic_place_pin);
        LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                (int) (16 * density), (int) (16 * density));
        pinLp.setMarginEnd((int) (4 * density));
        pinView.setLayoutParams(pinLp);
        placeBox.addView(pinView);

        placeLabelView = new TextView(context);
        placeLabelView.setTextSize(16);
        placeLabelView.setTypeface(placeLabelView.getTypeface(), Typeface.BOLD);
        placeLabelView.setTextColor(0xFF37474F);
        placeLabelView.setMaxLines(1);
        placeBox.addView(placeLabelView);

        row.addView(placeBox);

        root.addView(row);
        // Errors/loading reuse dayLabel so the footer stays a single thin row.
    }

    private Button navButton(Context context, String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setTextSize(30);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding((int) (8 * density), (int) (0 * density), (int) (8 * density), (int) (0 * density));
        b.setBackgroundColor(0x00000000);
        b.setTextColor(0xFF1565C0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    View view() {
        return root;
    }

    void start() {
        if (started) return;
        started = true;
        refresh();
    }

    void stop() {
        started = false;
        mainHandler.removeCallbacks(refreshTick);
    }

    void refresh() {
        mainHandler.removeCallbacks(refreshTick);
        if (!started) return;
        worker.execute(this::loadAndBind);
        mainHandler.postDelayed(refreshTick, REFRESH_MS);
    }

    private void shiftDay(int delta) {
        if (nights.isEmpty()) return;
        int next = nightIndex + delta;
        if (next < 0 || next >= nights.size()) return;
        nightIndex = next;
        Context localized = AppLocale.wrap(app);
        bindCurrentNight(localized, lastZone);
    }

    private void loadAndBind() {
        PhotoSyncStore site = PhotoSyncStore.from(app);
        Context localized = AppLocale.wrap(app);
        if (!site.hasSite()) {
            mainHandler.post(() -> {
                lastPlace = "";
                nights = new ArrayList<>();
                nightIndex = 0;
                dayLabel.setText(localized.getString(R.string.forecast_footer_no_site));
                bindPlace("", "");
                slotsRow.removeAllViews();
                updateNavEnabled();
            });
            return;
        }
        String place = sitePlaceLabel(localized, site);
        final String country = site.siteCountry();
        mainHandler.post(() -> {
            lastPlace = place;
            bindPlace(place, country);
            if (nights.isEmpty()) {
                dayLabel.setText(localized.getString(R.string.forecast_footer_loading));
            }
        });
        // Do not stop on ConnectivityManager: on the Pi, eth0/Tailscale can be the
        // default route while Android only lists the Vespera Wi‑Fi (no INTERNET
        // capability). HostDns tries that socket first, same path as Telegram.
        TimeZone tz = site.zone();
        OpenMeteoClient.NightResult result = OpenMeteoClient.fetchNightSlots(
                app, site.siteLat(), site.siteLon(), tz, site.dayEndHour(), site.dayStartHour());
        mainHandler.post(() -> applyResult(result, localized, tz, place, country));
    }

    private void applyResult(OpenMeteoClient.NightResult result, Context localized,
            TimeZone tz, String place, String country) {
        lastPlace = place == null ? "" : place;
        lastZone = tz == null ? TimeZone.getDefault() : tz;
        bindPlace(lastPlace, country);
        if (result == null || !result.error.isEmpty()
                || result.nights == null || result.nights.isEmpty()) {
            String err = result == null ? "" : result.error;
            if (!nights.isEmpty()) {
                bindCurrentNight(localized, lastZone);
                return;
            }
            nights = new ArrayList<>();
            nightIndex = 0;
            slotsRow.removeAllViews();
            dayLabel.setText(friendlyError(localized, lastPlace, err));
            updateNavEnabled();
            return;
        }
        nights = new ArrayList<>(result.nights);
        if (nightIndex >= nights.size()) nightIndex = 0;
        bindCurrentNight(localized, lastZone);
    }

    private void bindPlace(String place, String country) {
        String text = place == null ? "" : place;
        placeLabelView.setText(text);
        String flag = CountryFlags.emoji(country);
        if (flag.isEmpty()) {
            flagView.setVisibility(View.GONE);
            flagView.setText("");
        } else {
            flagView.setVisibility(View.VISIBLE);
            flagView.setText(flag);
        }
        pinView.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void bindCurrentNight(Context localized, TimeZone tz) {
        slotsRow.removeAllViews();
        if (nights.isEmpty() || nightIndex < 0 || nightIndex >= nights.size()) {
            dayLabel.setText("");
            updateNavEnabled();
            return;
        }
        OpenMeteoClient.NightWindow night = nights.get(nightIndex);
        dayLabel.setText(dayCaption(localized, tz, night.nightStartMs));
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        fmt.setTimeZone(tz);
        int gap = (int) (8 * density);
        List<OpenMeteoClient.HourSlot> slots = night.slots;
        for (int i = 0; i < slots.size(); i++) {
            OpenMeteoClient.HourSlot slot = slots.get(i);
            LinearLayout cell = new LinearLayout(root.getContext());
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding((int) (5 * density), 0, (int) (5 * density), 0);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) cellLp.setMarginStart(gap);
            cell.setLayoutParams(cellLp);

            ImageView icon = new ImageView(root.getContext());
            icon.setImageResource(WeatherIcons.drawableFor(slot.weatherCode));
            icon.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (28 * density), (int) (28 * density)));
            cell.addView(icon);

            LinearLayout texts = new LinearLayout(root.getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setGravity(Gravity.CENTER_VERTICAL);
            texts.setPadding((int) (4 * density), 0, 0, 0);

            TextView time = new TextView(root.getContext());
            time.setText(fmt.format(new Date(slot.epochMs)));
            time.setTextSize(16);
            time.setTypeface(time.getTypeface(), Typeface.BOLD);
            time.setTextColor(0xFF1A237E);
            texts.addView(time);

            String meta = slotMeta(localized, slot);
            if (!meta.isEmpty()) {
                TextView detail = new TextView(root.getContext());
                detail.setText(meta);
                detail.setTextSize(14);
                detail.setTypeface(detail.getTypeface(), Typeface.BOLD);
                detail.setMaxLines(1);
                detail.setIncludeFontPadding(false);
                detail.setTextColor(slot.precipitationMm >= 0.05d ? 0xFF1565C0 : 0xFF37474F);
                texts.addView(detail);
            }
            cell.addView(texts);

            slotsRow.addView(cell);
        }
        updateNavEnabled();
    }

    private void updateNavEnabled() {
        prevDay.setEnabled(nightIndex > 0);
        nextDay.setEnabled(nightIndex + 1 < nights.size());
        prevDay.setTextColor(prevDay.isEnabled() ? 0xFF1565C0 : 0xFFB0BEC5);
        nextDay.setTextColor(nextDay.isEnabled() ? 0xFF1565C0 : 0xFFB0BEC5);
    }

    /** Short date + relative day for the evening when the night starts. */
    private static String dayCaption(Context localized, TimeZone zone, long nightStartMs) {
        Calendar nightDay = Calendar.getInstance(zone);
        nightDay.setTimeInMillis(nightStartMs);
        clearTime(nightDay);

        Calendar today = Calendar.getInstance(zone);
        clearTime(today);

        long diffMs = nightDay.getTimeInMillis() - today.getTimeInMillis();
        int days = (int) Math.round(diffMs / (24d * 60d * 60d * 1000d));
        if (days < 0) days = 0;

        String relative;
        if (days == 0) relative = localized.getString(R.string.forecast_day_today);
        else if (days == 1) relative = localized.getString(R.string.forecast_day_tomorrow);
        else if (days == 2) relative = localized.getString(R.string.forecast_day_after_tomorrow);
        else relative = localized.getString(R.string.forecast_day_in_days, days);

        SimpleDateFormat dateFmt = new SimpleDateFormat("d MMM", Locale.getDefault());
        dateFmt.setTimeZone(zone);
        return localized.getString(R.string.forecast_day_caption,
                dateFmt.format(new Date(nightStartMs)), relative);
    }

    private static void clearTime(Calendar cal) {
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
    }

    private static String slotMeta(Context localized, OpenMeteoClient.HourSlot slot) {
        String temp = formatTemp(slot.temperatureC);
        String mm = formatPrecipMm(slot.precipitationMm);
        String tempLabel = temp.isEmpty()
                ? ""
                : localized.getString(R.string.forecast_footer_temp, temp);
        String mmLabel = mm.isEmpty()
                ? ""
                : localized.getString(R.string.forecast_footer_mm, mm);
        if (!tempLabel.isEmpty() && !mmLabel.isEmpty()) {
            return tempLabel + " · " + mmLabel;
        }
        if (!tempLabel.isEmpty()) return tempLabel;
        return mmLabel;
    }

    private static String formatTemp(double celsius) {
        if (Double.isNaN(celsius)) return "";
        return String.valueOf(Math.round(celsius));
    }

    /** Shown only when rain/snow is actually forecast for the 3 h step. */
    private static String formatPrecipMm(double mm) {
        if (mm < 0.05d) return "";
        if (mm < 9.95d) {
            return String.format(Locale.getDefault(), "%.1f", mm);
        }
        return String.format(Locale.getDefault(), "%.0f", mm);
    }

    private static String sitePlaceLabel(Context localized, PhotoSyncStore site) {
        String label = site.siteLabel();
        if (label != null) {
            label = label.trim();
            if (!label.isEmpty()) return label;
        }
        return localized.getString(R.string.forecast_footer_coords,
                (double) site.siteLat(), (double) site.siteLon());
    }

    private static String friendlyError(Context localized, String place, String err) {
        if (err == null || err.isEmpty()) {
            return localized.getString(R.string.forecast_footer_empty, place);
        }
        String lower = err.toLowerCase(Locale.US);
        if (lower.contains("ehostunreach")
                || lower.contains("no route to host")
                || lower.contains("network is unreachable")
                || lower.contains("enoneto")) {
            return localized.getString(R.string.forecast_footer_no_route, place);
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return localized.getString(R.string.forecast_footer_timeout, place);
        }
        return localized.getString(R.string.forecast_footer_error, place);
    }
}
