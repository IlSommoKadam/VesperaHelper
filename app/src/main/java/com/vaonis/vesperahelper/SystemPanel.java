package com.vaonis.vesperahelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Tab Sistema: elenco delle attività automatiche, con check e un unico Salva. */
final class SystemPanel {
    private final Activity activity;
    private final float density;
    private final SystemSettingsStore settings;
    private final PhotoSyncStore syncStore;
    private final UsbHdStore hdStore;
    private final WeatherProtectionStore weatherStore;
    private final FixedScrollView scroll;
    private final Row photoSync;
    private final Row storageSync;
    private final Row resumeSync;
    private final Row sunCheck;
    private final Row sunSync;
    private final Row sunTelescope;
    private final Row sunHd;
    private final Row sunPiShutdown;
    private final Row hdMount;
    private final Row clockNtp;
    private final Row bootStart;
    private final Row wifiConnect;
    private final Row singularityStart;
    private final Row watchdog;
    private final Row ftpLocal;
    private final Row keepAlive;
    private CheckBox weatherEnable;
    private EditText weatherLat;
    private EditText weatherLon;
    private EditText weatherInterval;
    private EditText weatherLookAhead;
    private EditText weatherThreshold;
    private CheckBox weatherSimulation;
    private TextView weatherInfo;
    private final TextView saveResult;
    private final TextView logBody;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean visible;
    private boolean logReceiverRegistered;
    private static final long LOG_REFRESH_MS = 2_000L;

    SystemPanel(Activity activity, float density, int padding) {
        this.activity = activity;
        this.density = density;
        this.settings = SystemSettingsStore.from(activity);
        this.syncStore = PhotoSyncStore.from(activity);
        this.hdStore = UsbHdStore.from(activity);
        this.weatherStore = WeatherProtectionStore.from(activity);

        scroll = new FixedScrollView(activity);
        scroll.setVisibility(View.GONE);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, (int) (80 * density));
        layout.setLayoutParams(new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        layout.addView(title(activity.getString(R.string.system_tab_title)));
        layout.addView(body(activity.getString(R.string.system_tab_intro)));
        logBody = addLogBox(layout);

        SystemSettingsStore.Snapshot snap = settings.snapshot();

        LinearLayout photoGroup = addGroup(layout, R.string.system_group_photo_sync, 0);
        photoSync = addRow(photoGroup, activity.getString(R.string.system_photo_sync_title), snap.photoSync);
        storageSync = addRow(photoGroup, activity.getString(R.string.system_storage_sync_title), snap.storageSync);
        resumeSync = addRow(photoGroup, activity.getString(R.string.system_resume_sync_title), snap.resumeSync);

        LinearLayout hdGroup = addGroup(layout, R.string.system_group_hd, 0);
        hdMount = addRow(hdGroup, activity.getString(R.string.system_hd_mount_title), snap.hdMount);
        ftpLocal = addRow(hdGroup, activity.getString(R.string.system_ftp_title), snap.ftpLocal);

        LinearLayout sunGroup = addGroup(layout, R.string.system_group_sun_shutdown,
                R.string.system_group_sun_intro);
        sunCheck = addRow(sunGroup, activity.getString(R.string.system_sun_check_title), snap.sunCheck);
        sunSync = addRow(sunGroup, activity.getString(R.string.system_sun_sync_title), snap.sunSync);
        sunTelescope = addRow(sunGroup, activity.getString(R.string.system_sun_telescope_title),
                snap.sunTelescopeShutdown);
        sunHd = addRow(sunGroup, activity.getString(R.string.system_sun_hd_title), snap.sunHdShutdown);
        sunPiShutdown = addRow(sunGroup, activity.getString(R.string.system_sun_pi_title),
                snap.sunPiShutdown);

        addWeatherGroup(layout);

        LinearLayout connGroup = addGroup(layout, R.string.system_group_connection, 0);
        wifiConnect = addRow(connGroup, activity.getString(R.string.system_wifi_title), snap.wifiConnect);
        singularityStart = addRow(connGroup,
                activity.getString(R.string.system_singularity_title), snap.singularityStart);
        watchdog = addRow(connGroup, activity.getString(R.string.system_watchdog_title), snap.watchdog);

        LinearLayout appGroup = addGroup(layout, R.string.system_group_app, 0);
        bootStart = addRow(appGroup, activity.getString(R.string.system_boot_title), snap.bootStart);
        keepAlive = addRow(appGroup, activity.getString(R.string.system_keepalive_title), snap.keepAlive);

        LinearLayout clockGroup = addGroup(layout, R.string.system_group_clock, 0);
        clockNtp = addRow(clockGroup, activity.getString(R.string.system_clock_title), snap.clockNtp);

        Button save = new Button(activity);
        save.setAllCaps(true);
        save.setText(R.string.system_save);
        UiStyle.applyRaised(save, UiStyle.SLATE, true);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = (int) (8 * density);
        saveLp.bottomMargin = (int) (6 * density);
        save.setLayoutParams(saveLp);
        save.setOnClickListener(v -> saveAll());
        layout.addView(save);

        saveResult = body("");
        saveResult.setTextColor(UiStyle.GREEN);
        layout.addView(saveResult);

        scroll.addView(layout);
        refreshInfo();
        refreshLog();
    }

    View view() {
        return scroll;
    }

    void onVisible() {
        visible = true;
        loadChecks();
        refreshInfo();
        refreshLog();
        saveResult.setText("");
        if (!logReceiverRegistered) {
            IntentFilter filter = new IntentFilter(SystemActivityLog.ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(logReceiver, filter);
            }
            logReceiverRegistered = true;
        }
        mainHandler.removeCallbacks(logTick);
        mainHandler.postDelayed(logTick, LOG_REFRESH_MS);
    }

    void onHidden() {
        visible = false;
        mainHandler.removeCallbacks(logTick);
        if (logReceiverRegistered) {
            try {
                activity.unregisterReceiver(logReceiver);
            } catch (Exception ignored) {
            }
            logReceiverRegistered = false;
        }
    }

    boolean isTabActive() {
        return visible;
    }

    private void loadChecks() {
        SystemSettingsStore.Snapshot snap = settings.snapshot();
        photoSync.check.setChecked(snap.photoSync);
        storageSync.check.setChecked(snap.storageSync);
        resumeSync.check.setChecked(snap.resumeSync);
        sunCheck.check.setChecked(snap.sunCheck);
        sunSync.check.setChecked(snap.sunSync);
        sunTelescope.check.setChecked(snap.sunTelescopeShutdown);
        sunHd.check.setChecked(snap.sunHdShutdown);
        sunPiShutdown.check.setChecked(snap.sunPiShutdown);
        hdMount.check.setChecked(snap.hdMount);
        clockNtp.check.setChecked(snap.clockNtp);
        bootStart.check.setChecked(snap.bootStart);
        wifiConnect.check.setChecked(snap.wifiConnect);
        singularityStart.check.setChecked(snap.singularityStart);
        watchdog.check.setChecked(snap.watchdog);
        ftpLocal.check.setChecked(snap.ftpLocal);
        keepAlive.check.setChecked(snap.keepAlive);
        loadWeather();
    }

    private void loadWeather() {
        WeatherProtectionStore.Config cfg = weatherStore.config();
        weatherEnable.setChecked(cfg.enabled);
        weatherLat.setText(displayLatitude(cfg));
        weatherLon.setText(displayLongitude(cfg));
        weatherInterval.setText(String.valueOf(cfg.checkIntervalMin));
        weatherLookAhead.setText(String.valueOf(cfg.lookAheadMin));
        weatherThreshold.setText(formatThreshold(cfg.thresholdMm));
        weatherSimulation.setChecked(cfg.simulation);
    }

    private void saveAll() {
        scroll.pin();
        SystemSettingsStore.Snapshot snap = new SystemSettingsStore.Snapshot();
        snap.photoSync = photoSync.check.isChecked();
        snap.storageSync = storageSync.check.isChecked();
        snap.resumeSync = resumeSync.check.isChecked();
        snap.sunCheck = sunCheck.check.isChecked();
        snap.sunSync = sunSync.check.isChecked();
        snap.sunTelescopeShutdown = sunTelescope.check.isChecked();
        snap.sunHdShutdown = sunHd.check.isChecked();
        snap.sunPiShutdown = sunPiShutdown.check.isChecked();
        snap.hdMount = hdMount.check.isChecked();
        snap.clockNtp = clockNtp.check.isChecked();
        snap.bootStart = bootStart.check.isChecked();
        snap.wifiConnect = wifiConnect.check.isChecked();
        snap.singularityStart = singularityStart.check.isChecked();
        snap.watchdog = watchdog.check.isChecked();
        snap.ftpLocal = ftpLocal.check.isChecked();
        snap.keepAlive = keepAlive.check.isChecked();
        settings.save(snap);
        saveWeather();
        PhotoSyncService.applySettings(activity);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).onSystemSettingsSaved();
        }
        refreshInfo();
        refreshLog();
        saveResult.setText(R.string.system_saved);
    }

    private void refreshInfo() {
        SystemSettingsStore.Snapshot snap = settings.snapshot();
        photoSync.info.setText(photoSyncInfo(snap.photoSync));
        storageSync.info.setText(storageSyncInfo(snap.storageSync));
        resumeSync.info.setText(resumeSyncInfo(snap.resumeSync));
        sunCheck.info.setText(sunCheckInfo(snap.sunCheck));
        sunSync.info.setText(sunActionInfo(snap.sunSync, snap.sunCheck,
                R.string.system_sun_sync_info));
        sunTelescope.info.setText(sunActionInfo(snap.sunTelescopeShutdown, snap.sunCheck,
                R.string.system_sun_telescope_info));
        sunHd.info.setText(sunActionInfo(snap.sunHdShutdown, snap.sunCheck,
                R.string.system_sun_hd_info));
        sunPiShutdown.info.setText(sunActionInfo(snap.sunPiShutdown, snap.sunCheck,
                R.string.system_sun_pi_info));
        hdMount.info.setText(hdMountInfo(snap.hdMount));
        clockNtp.info.setText(clockInfo(snap.clockNtp));
        bootStart.info.setText(prefixed(snap.bootStart,
                activity.getString(R.string.system_boot_info)));
        wifiConnect.info.setText(wifiInfo(snap.wifiConnect));
        singularityStart.info.setText(prefixed(snap.singularityStart,
                activity.getString(R.string.system_singularity_info)));
        watchdog.info.setText(watchdogInfo(snap.watchdog));
        ftpLocal.info.setText(ftpInfo(snap.ftpLocal));
        keepAlive.info.setText(prefixed(snap.keepAlive,
                activity.getString(R.string.system_keepalive_info)));
        if (weatherInfo != null) weatherInfo.setText(weatherInfoText());
    }

    private void saveWeather() {
        WeatherProtectionStore.Config cfg = new WeatherProtectionStore.Config();
        cfg.enabled = weatherEnable.isChecked();
        cfg.latitude = weatherLat.getText().toString().trim();
        cfg.longitude = weatherLon.getText().toString().trim();
        cfg.checkIntervalMin = parseIntOr(weatherInterval.getText().toString(),
                WeatherProtectionStore.DEFAULT_CHECK_INTERVAL_MIN);
        cfg.lookAheadMin = parseIntOr(weatherLookAhead.getText().toString(),
                WeatherProtectionStore.DEFAULT_LOOKAHEAD_MIN);
        cfg.thresholdMm = parseFloatOr(weatherThreshold.getText().toString(),
                WeatherProtectionStore.DEFAULT_THRESHOLD_MM);
        cfg.simulation = weatherSimulation.isChecked();
        weatherStore.saveConfig(cfg);
    }

    private void refreshLog() {
        List<SystemActivityLog.Entry> entries = SystemActivityLog.latest(activity);
        if (entries.isEmpty()) {
            logBody.setText(R.string.system_log_empty);
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) text.append('\n');
            SystemActivityLog.Entry entry = entries.get(i);
            text.append(activity.getString(R.string.system_log_line,
                    formatDateTime(entry.at),
                    logKindLabel(entry.kind),
                    logDetailLabel(entry.kind, entry.detail)));
        }
        String next = text.toString();
        if (!next.contentEquals(logBody.getText())) logBody.setText(next);
    }

    private String logKindLabel(String kind) {
        if (SystemActivityLog.KIND_PHOTO_SYNC.equals(kind)) {
            return activity.getString(R.string.system_photo_sync_title);
        }
        if (SystemActivityLog.KIND_STORAGE_SYNC.equals(kind)) {
            return activity.getString(R.string.system_storage_sync_title);
        }
        if (SystemActivityLog.KIND_RESUME_SYNC.equals(kind)) {
            return activity.getString(R.string.system_resume_sync_title);
        }
        if (SystemActivityLog.KIND_SUN_TOO_HIGH.equals(kind)) {
            return activity.getString(R.string.system_sun_check_title);
        }
        if (SystemActivityLog.KIND_PI_SHUTDOWN.equals(kind)) {
            return activity.getString(R.string.system_sun_pi_title);
        }
        if (SystemActivityLog.KIND_HD_MOUNT.equals(kind)) {
            return activity.getString(R.string.system_hd_mount_title);
        }
        if (SystemActivityLog.KIND_HD_POWER_OFF.equals(kind)) {
            return activity.getString(R.string.system_hd_power_off_title);
        }
        if (SystemActivityLog.KIND_CLOCK_NTP.equals(kind)) {
            return activity.getString(R.string.system_clock_title);
        }
        if (SystemActivityLog.KIND_BOOT.equals(kind)) {
            return activity.getString(R.string.system_boot_title);
        }
        if (SystemActivityLog.KIND_WIFI.equals(kind)) {
            return activity.getString(R.string.system_wifi_title);
        }
        if (SystemActivityLog.KIND_SINGULARITY.equals(kind)) {
            return activity.getString(R.string.system_singularity_title);
        }
        if (SystemActivityLog.KIND_FTP.equals(kind)) {
            return activity.getString(R.string.system_ftp_title);
        }
        if (SystemActivityLog.KIND_KEEP_ALIVE.equals(kind)) {
            return activity.getString(R.string.system_keepalive_title);
        }
        if (SystemActivityLog.KIND_WEATHER_CHECK.equals(kind)) {
            return activity.getString(R.string.system_weather_log_check);
        }
        if (SystemActivityLog.KIND_WEATHER_PROTECT.equals(kind)) {
            return activity.getString(R.string.system_weather_log_protect);
        }
        return kind == null ? "—" : kind;
    }

    private String logDetailLabel(String kind, String detail) {
        if (SystemActivityLog.DETAIL_OK.equals(detail)) {
            return activity.getString(R.string.system_log_ok);
        }
        if (SystemActivityLog.DETAIL_FAIL.equals(detail)) {
            return activity.getString(R.string.system_log_fail);
        }
        if (SystemActivityLog.DETAIL_PAUSED.equals(detail)) {
            return activity.getString(R.string.system_log_paused);
        }
        if (SystemActivityLog.KIND_SUN_TOO_HIGH.equals(kind)) {
            return sunTooHighResultLabel(detail);
        }
        return detail == null || detail.isEmpty() ? "—" : detail;
    }

    private String photoSyncInfo(boolean enabled) {
        String schedule = activity.getString(R.string.system_photo_sync_info,
                PhotoSyncStore.formatIntervalHours(syncStore.nightIntervalHours()),
                syncStore.dayStartHour(),
                syncStore.dayEndHour(),
                formatClock(syncStore.nextAutoAt(System.currentTimeMillis())));
        String last;
        if (syncStore.lastAt() <= 0) {
            last = activity.getString(R.string.system_photo_sync_never);
        } else {
            String when = formatDateTime(syncStore.lastAt());
            String detail = syncStore.lastOk()
                    ? activity.getString(R.string.photos_sync_done,
                    syncStore.lastCopied(), syncStore.lastSkipped(),
                    syncStore.lastDeleted(), 0,
                    PhotoSyncEngine.formatBytes(syncStore.lastBytes()))
                    : syncStore.lastError();
            if (detail == null || detail.isEmpty()) detail = "—";
            last = activity.getString(R.string.system_photo_sync_last, when, detail);
        }
        if (syncStore.paused()) {
            last = activity.getString(R.string.system_photo_sync_paused) + "\n" + last;
        }
        return prefixed(enabled, schedule + "\n" + last);
    }

    private String storageSyncInfo(boolean enabled) {
        return prefixed(enabled, activity.getString(R.string.system_storage_sync_info,
                PhotoSyncService.STORAGE_SYNC_PERCENT));
    }

    private String resumeSyncInfo(boolean enabled) {
        String body = activity.getString(R.string.system_resume_sync_info);
        // Quick prefs/marker only: a full .part tree walk on a large USB HD
        // ANRs the UI when opening this tab (and every 2s refresh).
        String state = syncStore.hasSuspendedWorkQuick(activity)
                ? activity.getString(R.string.system_resume_sync_pending)
                : activity.getString(R.string.system_resume_sync_idle);
        return prefixed(enabled, body + "\n" + state);
    }

    private String sunCheckInfo(boolean enabled) {
        String body = activity.getString(R.string.system_sun_check_info);
        if (!syncStore.hasSite()) {
            return prefixed(enabled, body + "\n" + activity.getString(R.string.system_sun_unset));
        }
        long now = System.currentTimeMillis();
        boolean retryToday = SystemSettingsStore.sunTooHighNeedsRetry(settings.sunTooHighResult());
        long nextAt = syncStore.nextSunTooHighCheckAt(settings.sunTooHighDay(), retryToday, now);
        String next;
        if (nextAt <= 0) {
            next = activity.getString(R.string.system_sun_unset);
        } else if (nextAt <= now + 5_000L) {
            next = activity.getString(R.string.system_sun_due);
        } else {
            next = activity.getString(R.string.system_sun_next, formatClock(nextAt));
        }
        String last;
        if (settings.sunTooHighAt() <= 0) {
            last = activity.getString(R.string.system_sun_never);
        } else {
            last = activity.getString(R.string.system_sun_last,
                    formatDateTime(settings.sunTooHighAt()),
                    sunTooHighResultLabel(settings.sunTooHighResult()));
        }
        return prefixed(enabled, body + "\n" + next + "\n" + last);
    }

    private String sunActionInfo(boolean enabled, boolean checkEnabled, int infoRes) {
        String body = activity.getString(infoRes);
        if (!checkEnabled) {
            body += "\n" + activity.getString(R.string.system_sun_requires_check);
        }
        return prefixed(enabled, body);
    }

    private String sunTooHighResultLabel(String code) {
        if (SystemSettingsStore.SUN_RESULT_SHUTDOWN_OK.equals(code)) {
            return activity.getString(R.string.system_sun_result_shutdown_ok);
        }
        if (code != null && code.startsWith(SystemSettingsStore.SUN_RESULT_SHUTDOWN_FAIL)) {
            String label = activity.getString(R.string.system_sun_result_shutdown_fail);
            String extra = code.substring(SystemSettingsStore.SUN_RESULT_SHUTDOWN_FAIL.length()).trim();
            return extra.isEmpty() ? label : label + " — " + extra;
        }
        if (SystemSettingsStore.SUN_RESULT_TRIGGERED.equals(code)) {
            return activity.getString(R.string.system_sun_result_triggered);
        }
        if (SystemSettingsStore.SUN_RESULT_NOT_STATUS.equals(code)) {
            return activity.getString(R.string.system_sun_result_not_status);
        }
        if (code != null && code.startsWith("step")) {
            int sp = code.indexOf(' ');
            String num = sp < 0 ? code.substring(4) : code.substring(4, sp);
            String rest = sp < 0 ? "" : code.substring(sp + 1).trim();
            int n = 1;
            try {
                n = Integer.parseInt(num.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
            }
            if (n < 1) n = 1;
            return activity.getString(R.string.system_sun_result_step, n, rest);
        }
        return code == null || code.isEmpty() ? "—" : code;
    }

    private String hdMountInfo(boolean enabled) {
        String body = activity.getString(R.string.system_hd_mount_info);
        String extra;
        if (!hdStore.isConfigured()) {
            extra = activity.getString(R.string.system_hd_mount_none);
        } else {
            extra = activity.getString(R.string.system_hd_mount_saved, hdStore.displayName());
        }
        return prefixed(enabled, body + "\n" + extra);
    }

    private String clockInfo(boolean enabled) {
        String body = activity.getString(R.string.system_clock_info);
        if (!syncStore.hasSite()) {
            return prefixed(enabled, body + "\n" + activity.getString(R.string.system_clock_unset));
        }
        String tz = syncStore.siteTimeZone();
        if (tz == null || tz.isEmpty()) tz = TimeZone.getDefault().getID();
        long lastNtp = syncStore.lastNtpAt();
        String last = lastNtp > 0
                ? formatDateTime(lastNtp)
                : activity.getString(R.string.system_clock_never);
        String ok = activity.getString(syncStore.lastNtpOk()
                ? R.string.system_clock_ok_short
                : R.string.system_clock_fail_short);
        return prefixed(enabled, body + "\n"
                + activity.getString(R.string.system_clock_last, tz, last, ok));
    }

    private String wifiInfo(boolean enabled) {
        String body = activity.getString(R.string.system_wifi_info);
        VesperaDeviceStore device = VesperaDeviceStore.from(activity);
        if (!device.isConfigured()) {
            return prefixed(enabled, body + "\n" + activity.getString(R.string.system_wifi_none));
        }
        String status = StatusTexts.connection(activity, VesperaConnectionService.getLastStatus());
        return prefixed(enabled, body + "\n"
                + activity.getString(R.string.system_wifi_device, device.getSsid(), status));
    }

    private String watchdogInfo(boolean enabled) {
        String body = activity.getString(R.string.system_watchdog_info);
        InstrumentWatchdog.Snapshot snap = InstrumentWatchdog.lastSnapshot();
        String status = snap == null
                ? activity.getString(R.string.system_watchdog_idle)
                : (snap.message == null || snap.message.isEmpty()
                ? StatusTexts.singularity(activity, snap.status)
                : snap.message);
        return prefixed(enabled, body + "\n"
                + activity.getString(R.string.system_watchdog_status, status));
    }

    private String ftpInfo(boolean enabled) {
        return prefixed(enabled, activity.getString(R.string.system_ftp_info));
    }

    private String prefixed(boolean enabled, String info) {
        if (enabled) return info;
        return activity.getString(R.string.system_activity_off) + "\n" + info;
    }

    private String formatClock(long timeMs) {
        Calendar calendar = Calendar.getInstance(syncStore.zone());
        calendar.setTimeInMillis(timeMs);
        return String.format(Locale.US, "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
    }

    private String formatDateTime(long timeMs) {
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        format.setTimeZone(syncStore.zone());
        return format.format(new Date(timeMs));
    }

    private void addWeatherGroup(LinearLayout layout) {
        LinearLayout box = addGroup(layout, R.string.system_group_weather,
                R.string.system_group_weather_intro);
        WeatherProtectionStore.Config cfg = weatherStore.config();

        weatherEnable = new CheckBox(activity);
        weatherEnable.setText(R.string.system_weather_enable);
        weatherEnable.setChecked(cfg.enabled);
        weatherEnable.setFocusable(false);
        weatherEnable.setFocusableInTouchMode(false);
        weatherEnable.setTextSize(15);
        weatherEnable.setTypeface(weatherEnable.getTypeface(), Typeface.BOLD);
        weatherEnable.setTextColor(0xFF1A237E);
        box.addView(weatherEnable);

        weatherLat = addWeatherField(box, R.string.system_weather_latitude,
                displayLatitude(cfg),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        weatherLon = addWeatherField(box, R.string.system_weather_longitude,
                displayLongitude(cfg),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);

        addWeatherLabel(box, activity.getString(R.string.system_weather_source,
                WeatherProtectionStore.SOURCE_OPEN_METEO));

        weatherInterval = addWeatherField(box, R.string.system_weather_interval,
                String.valueOf(cfg.checkIntervalMin), InputType.TYPE_CLASS_NUMBER);
        weatherLookAhead = addWeatherField(box, R.string.system_weather_lookahead,
                String.valueOf(cfg.lookAheadMin), InputType.TYPE_CLASS_NUMBER);
        weatherThreshold = addWeatherField(box, R.string.system_weather_threshold,
                formatThreshold(cfg.thresholdMm),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        weatherSimulation = new CheckBox(activity);
        weatherSimulation.setText(R.string.system_weather_simulation);
        weatherSimulation.setChecked(cfg.simulation);
        weatherSimulation.setFocusable(false);
        weatherSimulation.setFocusableInTouchMode(false);
        weatherSimulation.setTextColor(0xFF1A237E);
        LinearLayout.LayoutParams simLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        simLp.topMargin = (int) (10 * density);
        weatherSimulation.setLayoutParams(simLp);
        box.addView(weatherSimulation);

        weatherInfo = new TextView(activity);
        weatherInfo.setTextSize(13);
        weatherInfo.setTextColor(0xFF455A64);
        weatherInfo.setPadding(0, (int) (8 * density), 0, 0);
        weatherInfo.setText(weatherInfoText());
        box.addView(weatherInfo);
    }

    private EditText addWeatherField(LinearLayout group, int labelRes, String value, int inputType) {
        TextView label = new TextView(activity);
        label.setText(labelRes);
        label.setTextSize(13);
        label.setTextColor(0xFF455A64);
        label.setPadding(0, (int) (8 * density), 0, 0);
        group.addView(label);

        EditText edit = new EditText(activity);
        edit.setText(value == null ? "" : value);
        edit.setInputType(inputType);
        edit.setSingleLine(true);
        edit.setTextSize(15);
        edit.setTextColor(0xFF1A237E);
        group.addView(edit);
        return edit;
    }

    private void addWeatherLabel(LinearLayout group, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(0xFF455A64);
        view.setPadding(0, (int) (8 * density), 0, 0);
        group.addView(view);
    }

    private String displayLatitude(WeatherProtectionStore.Config cfg) {
        if (cfg.latitude != null && !cfg.latitude.isEmpty()) return cfg.latitude;
        if ((cfg.longitude == null || cfg.longitude.isEmpty()) && syncStore.hasSite()) {
            return WeatherProtectionStore.formatCoord(syncStore.siteLat());
        }
        return "";
    }

    private String displayLongitude(WeatherProtectionStore.Config cfg) {
        if (cfg.longitude != null && !cfg.longitude.isEmpty()) return cfg.longitude;
        if ((cfg.latitude == null || cfg.latitude.isEmpty()) && syncStore.hasSite()) {
            return WeatherProtectionStore.formatCoord(syncStore.siteLon());
        }
        return "";
    }

    private String weatherInfoText() {
        WeatherProtectionStore.Config cfg = weatherStore.config();
        String base = activity.getString(R.string.system_weather_info);
        if (!cfg.enabled) {
            return activity.getString(R.string.system_activity_off) + "\n" + base;
        }
        StringBuilder sb = new StringBuilder(base);
        if (!weatherStore.hasCoordinates() && !syncStore.hasSite()) {
            sb.append('\n').append(activity.getString(R.string.system_weather_no_coords));
        }
        long last = weatherStore.lastCheckAt();
        if (last > 0) {
            sb.append('\n').append(activity.getString(R.string.system_weather_last_check,
                    formatDateTime(last),
                    weatherDecisionLabel(weatherStore.lastDecision(),
                            weatherStore.lastPrecipMm())));
        } else {
            sb.append('\n').append(activity.getString(R.string.system_weather_never));
        }
        sb.append('\n').append(activity.getString(R.string.system_weather_state,
                weatherStore.state()));
        int fails = weatherStore.consecutiveApiFailures();
        if (fails > 0) {
            sb.append('\n').append(activity.getString(R.string.system_weather_api_fail, fails));
        }
        if (cfg.simulation) {
            sb.append('\n').append(activity.getString(R.string.system_weather_sim_on));
        }
        return sb.toString();
    }

    private String weatherDecisionLabel(String decision, float precipMm) {
        String mm = precipMm < 0 ? "—" : String.format(Locale.US, "%.2f", precipMm);
        if (WeatherProtectionStore.DECISION_RAIN.equals(decision)) {
            return activity.getString(R.string.system_weather_decision_rain, mm);
        }
        if (WeatherProtectionStore.DECISION_NO_ACTION.equals(decision)) {
            return activity.getString(R.string.system_weather_decision_no_action, mm);
        }
        if (WeatherProtectionStore.DECISION_API_ERROR.equals(decision)) {
            return activity.getString(R.string.system_weather_decision_api_error);
        }
        if (WeatherProtectionStore.DECISION_NO_COORDS.equals(decision)) {
            return activity.getString(R.string.system_weather_decision_no_coords);
        }
        return decision == null || decision.isEmpty() ? "—" : decision;
    }

    private static int parseIntOr(String text, int fallback) {
        if (text == null) return fallback;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static float parseFloatOr(String text, float fallback) {
        if (text == null) return fallback;
        try {
            return Float.parseFloat(text.trim().replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String formatThreshold(float mm) {
        if (mm == Math.rint(mm)) return String.valueOf((int) mm);
        return String.format(Locale.US, "%.2f", mm);
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
        if (introRes != 0) {
            TextView intro = new TextView(activity);
            intro.setText(introRes);
            intro.setTextSize(13);
            intro.setTextColor(0xFF546E7A);
            intro.setPadding(0, 0, 0, (int) (8 * density));
            box.addView(intro);
        }
        layout.addView(box);
        return box;
    }

    private Row addRow(LinearLayout group, String title, boolean checked) {
        if (countCheckRows(group) > 0) {
            group.addView(rowDivider());
        }
        CheckBox check = new CheckBox(activity);
        check.setText(title);
        check.setChecked(checked);
        check.setFocusable(false);
        check.setFocusableInTouchMode(false);
        check.setTextSize(15);
        check.setTypeface(check.getTypeface(), Typeface.BOLD);
        check.setTextColor(0xFF1A237E);
        check.setPadding(0, 0, 0, (int) (2 * density));

        TextView info = new TextView(activity);
        info.setTextSize(13);
        info.setTextColor(0xFF455A64);
        info.setPadding((int) (8 * density), 0, 0, 0);

        group.addView(check);
        group.addView(info);
        return new Row(check, info);
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

    private TextView addLogBox(LinearLayout layout) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (10 * density);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundColor(0xFFDCEBFA);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * density);
        lp.bottomMargin = (int) (4 * density);
        card.setLayoutParams(lp);

        TextView heading = new TextView(activity);
        heading.setText(R.string.system_log_title);
        heading.setTypeface(heading.getTypeface(), Typeface.BOLD);
        heading.setTextColor(0xFF1A237E);
        heading.setTextSize(15);
        heading.setPadding(0, 0, 0, (int) (6 * density));

        TextView body = new TextView(activity);
        body.setTextSize(13);
        body.setTextColor(0xFF263238);
        body.setLineSpacing(0, 1.15f);

        card.addView(heading);
        card.addView(body);
        layout.addView(card);
        return body;
    }

    private final Runnable logTick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            refreshInfo();
            refreshLog();
            mainHandler.postDelayed(this, LOG_REFRESH_MS);
        }
    };

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!visible) return;
            activity.runOnUiThread(() -> {
                if (!visible) return;
                refreshInfo();
                refreshLog();
            });
        }
    };

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

    private TextView body(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setPadding(0, (int) (2 * density), 0, (int) (6 * density));
        return view;
    }

    private static final class Row {
        final CheckBox check;
        final TextView info;

        Row(CheckBox check, TextView info) {
            this.check = check;
            this.info = info;
        }
    }
}
