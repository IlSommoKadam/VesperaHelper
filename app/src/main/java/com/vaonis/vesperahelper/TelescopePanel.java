package com.vaonis.vesperahelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.database.Cursor;
import android.net.Network;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Tab Telescopio: stato live Socket.IO 8083 + comandi REST 8082. */
final class TelescopePanel {
    private static final String TAG = "VesperaPower";
    private static final String PREFS = "vespera_telescope";
    private static final String KEY_LAST_OFF_MAINS = "last_consulted_off_mains";
    private static final String KEY_LAST_POWER_KNOWN = "last_consulted_power_known";
    private static final long AUTO_REFRESH_MS = 30_000;
    private static final long PHOTO_USAGE_RETRY_MS = 60_000;

    private final Activity activity;
    private final float density;
    private final FixedScrollView scroll;
    private final LinearLayout statusBox;
    private final TextView commandResult;
    private final TextView sniffStatus;
    private TargetObserveSection observeSection;
    private final TextView sniffLog;
    private final Button sniffToggle;
    private final TextView lastUpdate;
    private final SingularityCommandSniffer.Ui sniffUi = new SingularityCommandSniffer.Ui() {
        @Override
        public void onState(boolean running, String code) {
            mainHandler.post(() -> applySniffState(running, code));
        }

        @Override
        public void onLog(String text) {
            mainHandler.post(() -> sniffLog.setText(
                    text == null || text.isEmpty()
                            ? activity.getString(R.string.telescope_sniff_idle)
                            : text));
        }
    };
    private final TextView portInventory;
    private final Button refreshStatus;
    private final Button cmdPark;
    private final Button cmdStop;
    private final Button cmdResume;
    private final Button cmdInit;
    private final Button cmdShutdown;
    private final Button cmdUploadSwu;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService storageWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService updateWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService liveWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetchInFlight = new AtomicBoolean(false);
    private final AtomicBoolean commandInFlight = new AtomicBoolean(false);
    private final AtomicBoolean liveRunning = new AtomicBoolean(false);
    private final AtomicBoolean photoUsageInFlight = new AtomicBoolean(false);
    private final Runnable autoRefresh = this::refreshStatusIfVisible;
    private VesperaSocketIo liveClient;
    private volatile boolean liveOk;
    private boolean visible;
    private String host = "10.0.0.1";
    private int apiPort = -1;
    private AlertDialog powerDialog;
    private boolean powerWarningIgnoredThisVisit;
    private boolean powerWarningAccepted;
    private String lastBatteryStatus = "";
    private int lastBatteryPercent = -1;
    private VesperaStatusSnapshot lastSnap;
    private VesperaInternalStorage.Usage photoUsage;
    private long lastPhotoUsageAt;
    private boolean photoUsageForceOnOpen;
    private boolean photoUsageChecking;
    private boolean photoStatusReceiverRegistered;
    private boolean sawPhotoSyncing;
    /** Cursor used by {@link #addStatusRow} to update statusBox children in place. */
    private int statusRowCursor;
    private final TelescopeStatusHub.Listener statusListener = this::onTelescopeStatusEvent;
    private final BroadcastReceiver photoStatusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            applyPhotoSyncStatus(intent);
        }
    };

    TelescopePanel(Activity activity, float density, int padding) {
        this.activity = activity;
        this.density = density;

        scroll = new FixedScrollView(activity);
        scroll.setVisibility(View.GONE);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, (int) (80 * density));
        layout.setLayoutParams(new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        layout.addView(title(activity.getString(R.string.telescope_tab_title)));
        layout.addView(section(activity.getString(R.string.telescope_section_status)));
        statusBox = new LinearLayout(activity);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        int boxPad = Math.max(8, Math.round(10 * density));
        statusBox.setPadding(boxPad, boxPad, boxPad, boxPad);
        statusBox.setBackground(UiStyle.recessedStatus(0xFFECEFF1, density));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.bottomMargin = (int) (8 * density);
        statusBox.setLayoutParams(boxLp);
        addStatusRow(activity.getString(R.string.status_tab_field_state), "—");
        lastUpdate = body("");
        lastUpdate.setTextSize(12);
        refreshStatus = action(activity.getString(R.string.telescope_btn_refresh_status),
                UiStyle.SLATE);
        refreshStatus.setOnClickListener(v -> confirmRefresh());

        layout.addView(statusBox);
        layout.addView(lastUpdate);
        layout.addView(refreshStatus);

        layout.addView(section(activity.getString(R.string.telescope_section_commands)));
        TextView cmdHint = body(activity.getString(R.string.telescope_commands_hint));
        cmdHint.setTextSize(13);
        layout.addView(cmdHint);
        cmdPark = action(activity.getString(R.string.telescope_btn_park), UiStyle.TERRACOTTA);
        cmdPark.setOnClickListener(v -> confirmCommand(VesperaCommandClient.Command.PARK));
        cmdStop = action(activity.getString(R.string.telescope_btn_stop), UiStyle.INK);
        cmdStop.setOnClickListener(v -> confirmCommand(VesperaCommandClient.Command.STOP));
        cmdResume = action(activity.getString(R.string.telescope_btn_resume), UiStyle.STEEL_BLUE);
        cmdResume.setOnClickListener(v -> confirmCommand(VesperaCommandClient.Command.RESUME));
        cmdInit = action(activity.getString(R.string.telescope_btn_init), UiStyle.GREEN);
        cmdInit.setOnClickListener(v -> confirmCommand(VesperaCommandClient.Command.INIT));
        cmdShutdown = action(activity.getString(R.string.telescope_btn_shutdown), UiStyle.ROSE);
        cmdShutdown.setOnClickListener(v -> confirmShutdown());
        cmdUploadSwu = action(activity.getString(R.string.telescope_btn_upload_swu_expert), UiStyle.INK);
        cmdUploadSwu.setOnClickListener(v -> promptUploadSwu());
        LinearLayout.LayoutParams shutdownLp =
                (LinearLayout.LayoutParams) cmdShutdown.getLayoutParams();
        shutdownLp.topMargin = (int) (8 * density);
        cmdShutdown.setLayoutParams(shutdownLp);
        commandResult = body("");
        commandResult.setTextColor(UiStyle.SLATE);
        layout.addView(cmdPark);
        layout.addView(cmdStop);
        layout.addView(cmdResume);
        layout.addView(cmdInit);
        layout.addView(cmdShutdown);
        layout.addView(cmdUploadSwu);
        layout.addView(commandResult);

        observeSection = new TargetObserveSection(activity, density, new TargetObserveSection.Bridge() {
            @Override public boolean connected() { return isConnected(); }
            @Override public String host() { return host; }
            @Override public int apiPort() { return resolveApiPort(); }
            @Override public Network network() { return VesperaConnectionService.getActiveNetwork(); }
            @Override public VesperaStatusSnapshot status() { return lastSnap; }
            @Override public VesperaLocationClient.Site site() {
                return resolveInitSite(VesperaConnectionService.getActiveNetwork(), lastSnap);
            }
            @Override public boolean beginCommand() { return beginObserveCommand(); }
            @Override public void endCommand() { endObserveCommand(); }
        });
        layout.addView(observeSection.view());

        layout.addView(section(activity.getString(R.string.telescope_section_sniff)));
        TextView sniffHint = body(activity.getString(R.string.telescope_sniff_hint));
        sniffHint.setTextSize(13);
        layout.addView(sniffHint);
        sniffStatus = body("");
        sniffStatus.setTextSize(13);
        sniffToggle = action(activity.getString(R.string.telescope_sniff_start), UiStyle.STEEL_BLUE);
        sniffToggle.setOnClickListener(v -> toggleSniff());
        Button sniffClear = action(activity.getString(R.string.telescope_sniff_clear), UiStyle.SLATE);
        sniffClear.setOnClickListener(v -> SingularityCommandSniffer.clear());
        sniffLog = body("");
        sniffLog.setTextSize(12);
        sniffLog.setTypeface(Typeface.MONOSPACE);
        sniffLog.setTextIsSelectable(true);
        layout.addView(sniffStatus);
        layout.addView(sniffToggle);
        layout.addView(sniffClear);
        layout.addView(sniffLog);
        SingularityCommandSniffer.setUi(sniffUi);
        refreshSniffStatus();

        layout.addView(section(activity.getString(R.string.telescope_section_ports)));
        portInventory = body(activity.getString(R.string.port_inventory_idle));
        portInventory.setTextSize(13);
        layout.addView(portInventory);
        scroll.addView(layout);
        updateObservationButtons(false);
        TelescopeStatusHub.ensure().addListener(statusListener);
    }

    View view() {
        return scroll;
    }

    void setHost(String host) {
        if (host != null && !host.trim().isEmpty()) {
            this.host = host.trim();
        }
    }

    void setApiPort(int port) {
        this.apiPort = port;
    }

    void onVisible() {
        visible = true;
        powerWarningIgnoredThisVisit = false;
        photoUsageForceOnOpen = true;
        SingularityCommandSniffer.setUi(sniffUi);
        refreshSniffStatus();
        refreshPortInventory();
        if (lastSnap != null) maybeShowPowerWarning(lastSnap);
        refreshStatusNow(true);
        startLive();
        scheduleAutoRefresh();
    }

    void onHidden() {
        visible = false;
        stopLive();
        mainHandler.removeCallbacks(autoRefresh);
    }

    void onAppPause() {
        unregisterPhotoStatus();
        dismissPowerWarning(false);
    }

    void onActivityResume() {
        registerPhotoStatus();
    }

    /** Fetch status and show the mains popup even if the Telescopio tab is not open. */
    void probePowerWarning() {
        refreshStatusNow(true);
    }

    boolean isTabActive() {
        return visible;
    }

    void onConnectionChanged() {
        refreshPortInventory();
        stopLive();
        refreshStatusNow(true);
        if (visible) {
            startLive();
        }
    }

    void showPortScanRunning() {
        portInventory.setText(activity.getString(R.string.port_scan_running));
    }

    void updatePortInventory(VesperaPortScan scan) {
        portInventory.setText(VesperaPortInventory.formatFull(activity, scan));
    }

    void clearPortInventory() {
        portInventory.setText(activity.getString(R.string.port_inventory_idle));
    }

    private void refreshPortInventory() {
        VesperaPortScan scan = VesperaPortScanner.lastScan();
        if (scan != null) {
            updatePortInventory(scan);
        } else {
            clearPortInventory();
        }
    }

    void shutdown() {
        visible = false;
        TelescopeStatusHub hub = TelescopeStatusHub.get();
        if (hub != null) hub.removeListener(statusListener);
        unregisterPhotoStatus();
        stopLive();
        mainHandler.removeCallbacks(autoRefresh);
        worker.shutdownNow();
        storageWorker.shutdownNow();
        updateWorker.shutdownNow();
        liveWorker.shutdownNow();
        dismissPowerWarning(false);
    }

    private void scheduleAutoRefresh() {
        mainHandler.removeCallbacks(autoRefresh);
        if (!visible) return;
        mainHandler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    private void refreshStatusIfVisible() {
        if (!visible) return;
        if (!liveOk) refreshStatusNow(false);
        scheduleAutoRefresh();
    }

    private void startLive() {
        if (!visible || !isConnected()) {
            stopLive();
            return;
        }
        if (!liveRunning.compareAndSet(false, true)) return;
        liveOk = false;
        final String fetchHost = host;
        final Network network = VesperaConnectionService.getActiveNetwork();
        liveClient = new VesperaSocketIo(new VesperaSocketIo.Listener() {
            @Override
            public void onStatus(VesperaStatusSnapshot snapshot) {
                mainHandler.post(() -> applyLiveSnapshot(snapshot));
            }

            @Override
            public void onInfo(String message) {
                Log.d("VesperaSIO", message);
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    liveOk = false;
                    if (visible && !fetchInFlight.get()) refreshStatusNow(false);
                });
            }
        });
        final VesperaSocketIo client = liveClient;
        liveWorker.execute(() -> {
            try {
                client.run(fetchHost, network);
            } finally {
                if (liveClient == client) {
                    liveRunning.set(false);
                    liveOk = false;
                }
            }
        });
    }

    private void stopLive() {
        liveOk = false;
        VesperaSocketIo client = liveClient;
        liveClient = null;
        if (client != null) client.stop();
        liveRunning.set(false);
    }

    private void applyLiveSnapshot(VesperaStatusSnapshot snapshot) {
        if (snapshot == null) return;
        rememberBattery(snapshot);
        TelescopeStatusHub.ensure().ingestSnapshot(snapshot);
        if (!visible) return;
        liveOk = true;
        setCommandsEnabled(true);
        String when = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault())
                .format(new Date());
        setStatusSnapshot(snapshot,
                activity.getString(R.string.status_tab_last_update, when)
                        + " · " + activity.getString(R.string.status_tab_live));
        scroll.runKeepingScroll(() -> updateObservationButtons(snapshot.isObserving()));
    }

    private void refreshStatusNow(boolean userInitiated) {
        if (!visible && !userInitiated) return;
        if (!isConnected()) {
            stopLive();
            if (!visible) return;
            setStatusMessage(activity.getString(R.string.status_tab_need_wifi), "");
            setCommandsEnabled(false);
            return;
        }
        if (visible) setCommandsEnabled(true);
        if (!fetchInFlight.compareAndSet(false, true)) {
            if (lastSnap != null) maybeShowPowerWarning(lastSnap);
            return;
        }
        if (visible && userInitiated) {
            refreshStatus.setEnabled(false);
            setStatusMessage(activity.getString(R.string.status_tab_fetching),
                    lastUpdate.getText().toString());
        }
        final String fetchHost = host;
        final int fetchPort = resolveApiPort();
        final Network network = VesperaConnectionService.getActiveNetwork();
        worker.execute(() -> {
            VesperaStatusClient.Result result = VesperaStatusClient.fetchResult(
                    fetchHost, fetchPort, network);
            mainHandler.post(() -> {
                fetchInFlight.set(false);
                if (visible) refreshStatus.setEnabled(isConnected());
                if (result.snapshot != null) {
                    rememberBattery(result.snapshot);
                    TelescopeStatusHub.ensure().ingestSnapshot(result.snapshot);
                }
                if (!visible) {
                    if (result.snapshot != null) lastSnap = result.snapshot;
                    return;
                }
                String when = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault())
                        .format(new Date());
                if (result.snapshot == null) {
                    setStatusMessage(formatUnavailable(result.error),
                            activity.getString(R.string.status_tab_last_update, when));
                } else {
                    setStatusSnapshot(result.snapshot,
                            activity.getString(R.string.status_tab_last_update, when));
                    scroll.runKeepingScroll(
                            () -> updateObservationButtons(result.snapshot.isObserving()));
                }
            });
        });
    }

    /** Updates status without scrolling the tab back to the top. */
    private void setStatusSnapshot(VesperaStatusSnapshot snap, String updated) {
        lastSnap = snap;
        if (observeSection != null) observeSection.onStatus(snap);
        maybeProbePhotoUsage(snap);
        refillStatusKeepingScroll();
        if (updated != null) lastUpdate.setText(updated);
    }

    private void refillStatusKeepingScroll() {
        if (!visible || lastSnap == null) return;
        scroll.runKeepingScroll(() -> {
            statusRowCursor = 0;
            fillStatusRows(lastSnap);
            while (statusBox.getChildCount() > statusRowCursor) {
                statusBox.removeViewAt(statusBox.getChildCount() - 1);
            }
        });
    }

    private void setStatusMessage(String status, String updated) {
        scroll.runKeepingScroll(() -> {
            statusBox.removeAllViews();
            statusRowCursor = 0;
            addStatusMessage(status == null ? "—" : status);
            if (updated != null) lastUpdate.setText(updated);
        });
    }

    private void fillStatusRows(VesperaStatusSnapshot snap) {
        addStatusRow(activity.getString(R.string.status_tab_field_state),
                snap.state.isEmpty() ? "—" : snap.state);
        addStatusRow(activity.getString(R.string.status_tab_field_initialized),
                snap.initialized
                        ? activity.getString(R.string.status_tab_yes)
                        : activity.getString(R.string.status_tab_no));
        addStatusRow(activity.getString(R.string.status_tab_field_observation),
                observationLabel(snap));
        addStatusRow(activity.getString(R.string.status_tab_field_operation), snap.operationType);
        addStatusRow(activity.getString(R.string.status_tab_field_step), snap.step);
        addStatusRow(activity.getString(R.string.status_tab_field_tracking),
                trackingLabel(snap.tracking));
        addStatusRow(activity.getString(R.string.status_tab_field_arm), armLabel(snap));
        addStatusRow(activity.getString(R.string.status_tab_field_motors), snap.motors);
        addStatusRow(activity.getString(R.string.status_tab_field_focus), snap.focus);
        if (!snap.targetName.isEmpty()) {
            addStatusRow(activity.getString(R.string.status_tab_field_target), snap.targetName);
        } else if (VesperaLastTarget.hasTarget() && !VesperaLastTarget.label().isEmpty()) {
            addStatusRow(activity.getString(R.string.status_tab_field_last_target),
                    VesperaLastTarget.label());
        }
        addStatusRow(activity.getString(R.string.status_tab_field_coordinates), snap.coordinates);
        addStatusRow(activity.getString(R.string.status_tab_field_location), snap.location);
        if (snap.stackingCount > 0) {
            addStatusRow(activity.getString(R.string.status_tab_field_stacking),
                    String.valueOf(snap.stackingCount));
        }
        if (snap.exposureMicroSec > 0) {
            double sec = snap.exposureMicroSec / 1_000_000.0;
            addStatusRow(activity.getString(R.string.status_tab_field_exposure),
                    String.format(Locale.US, "%.1f s", sec));
        }
        if (snap.gain > 0) {
            addStatusRow(activity.getString(R.string.status_tab_field_gain),
                    String.valueOf(snap.gain));
        }
        addStatusRow(activity.getString(R.string.status_tab_field_filter), snap.filter);
        addStatusRow(activity.getString(R.string.status_tab_field_temperature), snap.temperature);
        addStatusRow(activity.getString(R.string.status_tab_field_firmware), snap.firmware);
        addStatusRow(activity.getString(R.string.status_tab_field_error), snap.error);
        addStatusRow(activity.getString(R.string.status_tab_field_storage), storageLabel(snap));
        if (snap.batteryPercent >= 0) {
            String battery = snap.batteryPercent + "%";
            if (!snap.batteryStatus.isEmpty()) {
                battery += " (" + snap.batteryStatus + ")";
            }
            addStatusRow(activity.getString(R.string.status_tab_field_battery), battery);
        }
        if (!snap.hasInstrumentFields()) {
            String compact = snap.rawJson;
            if (compact.length() > 600) compact = compact.substring(0, 600) + "…";
            addStatusRow(activity.getString(R.string.status_tab_field_raw), compact);
        }
    }

    private String storageLabel(VesperaStatusSnapshot snap) {
        VesperaInternalStorage.Usage usage = VesperaInternalStorage.lastKnown();
        if (usage != null) photoUsage = usage;
        int percent = -1;
        String label = "";
        if (photoUsage != null) {
            percent = photoUsage.usedPercent;
            label = photoUsage.label;
        } else if (snap.storageUsedPercent >= 0 || !snap.storage.isEmpty()) {
            percent = snap.storageUsedPercent;
            label = snap.storage;
        }
        if (percent >= PhotoSyncService.STORAGE_SYNC_PERCENT) {
            String base = label.isEmpty() ? (percent + "%") : label;
            return activity.getString(R.string.status_tab_storage_full, base);
        }
        if (!label.isEmpty()) return label;
        if (percent >= 0) return percent + "%";
        if (photoUsageChecking) {
            return activity.getString(R.string.status_tab_storage_checking);
        }
        String err = VesperaInternalStorage.lastError();
        if (!err.isEmpty()) {
            return activity.getString(R.string.status_tab_storage_ftp_fail, err);
        }
        return "—";
    }

    private void registerPhotoStatus() {
        if (photoStatusReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(PhotoSyncService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(photoStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(photoStatusReceiver, filter);
        }
        photoStatusReceiverRegistered = true;
    }

    private void unregisterPhotoStatus() {
        if (!photoStatusReceiverRegistered) return;
        try {
            activity.unregisterReceiver(photoStatusReceiver);
        } catch (Exception ignored) {
        }
        photoStatusReceiverRegistered = false;
    }

    private void applyPhotoSyncStatus(Intent intent) {
        boolean syncing = intent.getBooleanExtra(PhotoSyncService.EXTRA_SYNCING, false);
        int percent = intent.getIntExtra(PhotoSyncService.EXTRA_STORAGE_PERCENT, -1);
        String label = intent.getStringExtra(PhotoSyncService.EXTRA_STORAGE_LABEL);
        boolean fromBroadcast = percent >= 0 && label != null && !label.isEmpty();
        if (fromBroadcast) {
            if (photoUsage == null || photoUsage.usedPercent != percent
                    || !label.equals(photoUsage.label)) {
                photoUsage = new VesperaInternalStorage.Usage(percent, label);
                photoUsageChecking = false;
                if (visible && lastSnap != null) refillStatusKeepingScroll();
            }
        } else if (sawPhotoSyncing && !syncing && lastSnap != null) {
            photoUsageForceOnOpen = true;
            lastPhotoUsageAt = 0;
            maybeProbePhotoUsage(lastSnap);
        }
        sawPhotoSyncing = syncing;
    }

    private void maybeProbePhotoUsage(VesperaStatusSnapshot snap) {
        if (!visible || snap == null || !isConnected()) return;
        boolean force = photoUsageForceOnOpen;
        photoUsageForceOnOpen = false;
        if (!force && snap.storageUsedPercent >= 0 && !snap.storage.isEmpty()) {
            photoUsageChecking = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (!force) {
            if (photoUsage != null || VesperaInternalStorage.lastKnown() != null) return;
            if (lastPhotoUsageAt > 0 && now - lastPhotoUsageAt < PHOTO_USAGE_RETRY_MS) return;
        }
        if (!photoUsageInFlight.compareAndSet(false, true)) {
            photoUsageChecking = true;
            return;
        }
        lastPhotoUsageAt = now;
        photoUsageChecking = true;
        final String fetchHost = host;
        final int fetchPort = resolveApiPort();
        final String model = snap.model;
        final Network network = VesperaConnectionService.getActiveNetwork();
        storageWorker.execute(() -> {
            VesperaInternalStorage.Usage usage = VesperaInternalStorage.probe(
                    network, fetchHost, fetchPort, model);
            if (usage != null) {
                TelescopeStatusHub.ensure().ingestInternalStorage(usage.usedPercent);
            }
            mainHandler.post(() -> {
                photoUsageInFlight.set(false);
                photoUsageChecking = false;
                if (usage != null) photoUsage = usage;
                if (!visible || lastSnap == null) return;
                refillStatusKeepingScroll();
            });
        });
    }

    private void onTelescopeStatusEvent(TelescopeStatusEvent event) {
        if (event == null) return;
        if (event.kind == TelescopeStatusEvent.Kind.POWER_OFF_MAINS
                || event.kind == TelescopeStatusEvent.Kind.POWER_ON_MAINS) {
            VesperaStatusSnapshot snap = event.snapshot != null ? event.snapshot : lastSnap;
            mainHandler.post(() -> {
                if (snap != null) maybeShowPowerWarning(snap);
            });
        }
    }

    private String observationLabel(VesperaStatusSnapshot snap) {
        if ("RUNNING".equals(snap.observationStatus) || snap.isTrackingAcquisition()) {
            return activity.getString(R.string.status_tab_observation_running);
        }
        if ("FINISHED".equals(snap.observationStatus)) {
            return activity.getString(R.string.status_tab_observation_finished);
        }
        if ("STOPPED".equals(snap.observationStatus) || snap.canResumeObservation()) {
            return activity.getString(R.string.status_tab_observation_stopped);
        }
        return "";
    }

    private String trackingLabel(String tracking) {
        if ("ON".equals(tracking)) return activity.getString(R.string.status_tab_tracking_on);
        if ("STARTING".equals(tracking)) {
            return activity.getString(R.string.status_tab_tracking_starting);
        }
        return activity.getString(R.string.status_tab_tracking_off);
    }

    private String armLabel(VesperaStatusSnapshot snap) {
        String code = snap.armState();
        if ("CLOSED".equals(code)) {
            return activity.getString(R.string.status_tab_arm_closed);
        }
        if ("OPEN".equals(code)) {
            return activity.getString(R.string.status_tab_arm_open);
        }
        if ("MOVING".equals(code)) {
            return activity.getString(R.string.status_tab_arm_moving);
        }
        return "—";
    }

    private void addStatusRow(String label, String value) {
        if (value == null || value.isEmpty()) return;
        // Reuse an existing row when possible so live status updates do not
        // collapse the ScrollView height (which jumps scrollY back to top).
        if (statusRowCursor < statusBox.getChildCount()) {
            View child = statusBox.getChildAt(statusRowCursor);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                if (row.getChildCount() >= 3
                        && row.getChildAt(0) instanceof TextView
                        && row.getChildAt(2) instanceof TextView) {
                    ((TextView) row.getChildAt(0)).setText(label);
                    ((TextView) row.getChildAt(2)).setText(value);
                    statusRowCursor++;
                    return;
                }
            }
            while (statusBox.getChildCount() > statusRowCursor) {
                statusBox.removeViewAt(statusBox.getChildCount() - 1);
            }
        }
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.max(8, Math.round(8 * density));
        int padV = Math.max(6, Math.round(6 * density));
        row.setPadding(padH, padV, padH, padV);
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0xFFF7FAFC);
        chip.setCornerRadius(Math.round(6 * density));
        row.setBackground(chip);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = Math.max(4, Math.round(4 * density));
        row.setLayoutParams(rowLp);

        int labelWidth = Math.round(152 * density);
        TextView name = statusCell(label, 0xFF546E7A, false);
        name.setLayoutParams(new LinearLayout.LayoutParams(labelWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        name.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView colon = statusCell(" : ", 0xFF546E7A, false);
        colon.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView state = statusCell(value, 0xFF1A237E, true);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        state.setLayoutParams(stateLp);
        state.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.addView(name);
        row.addView(colon);
        row.addView(state);
        statusBox.addView(row);
        statusRowCursor++;
    }

    private void addStatusMessage(String message) {
        TextView view = statusCell(message, 0xFF37474F, false);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        statusBox.addView(view);
    }

    private TextView statusCell(String text, int color, boolean emphasize) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(13);
        view.setTypeface(Typeface.SANS_SERIF, emphasize ? Typeface.BOLD : Typeface.NORMAL);
        view.setTextColor(color);
        view.setFocusable(false);
        return view;
    }

    private void runCommand(VesperaCommandClient.Command command) {
        if (!isConnected()) {
            commandResult.setText(activity.getString(R.string.status_tab_need_wifi));
            return;
        }
        if (!commandInFlight.compareAndSet(false, true)) return;
        setCommandsEnabled(false);
        commandResult.setText(activity.getString(R.string.telescope_command_running));
        final String fetchHost = host;
        final int fetchPort = resolveApiPort();
        final Network network = VesperaConnectionService.getActiveNetwork();
        final VesperaStatusSnapshot snap = lastSnap;
        worker.execute(() -> {
            VesperaLocationClient.Site initSite = null;
            boolean needsSite = command == VesperaCommandClient.Command.INIT
                    || (command == VesperaCommandClient.Command.RESUME
                    && (snap == null || !VesperaCommandClient.readyToResumeAfterInit(snap)));
            if (needsSite) {
                initSite = resolveInitSite(network, snap);
                if (initSite == null) {
                    mainHandler.post(() -> {
                        scroll.pin();
                        commandInFlight.set(false);
                        applyCommandEnablement(true);
                        commandResult.setText(activity.getString(R.string.telescope_command_no_site));
                    });
                    return;
                }
            }
            if (command == VesperaCommandClient.Command.RESUME
                    && (snap == null || !VesperaCommandClient.readyToResumeAfterInit(snap))) {
                mainHandler.post(() -> commandResult.setText(
                        activity.getString(R.string.telescope_command_resume_initing)));
            }
            VesperaCommandClient.Result result = VesperaCommandClient.send(
                    fetchHost, fetchPort, network, command, initSite);
            mainHandler.post(() -> {
                scroll.pin();
                commandInFlight.set(false);
                applyCommandEnablement(true);
                if (result.success) {
                    if (command == VesperaCommandClient.Command.SHUTDOWN) {
                        commandResult.setText(activity.getString(
                                R.string.telescope_command_shutdown_ok));
                        TelescopeStatusHub.ensure().ingestShutdown();
                    } else {
                        commandResult.setText(activity.getString(
                                R.string.telescope_command_ok, label(command), result.message));
                    }
                    if (command == VesperaCommandClient.Command.STOP) {
                        updateObservationButtons(false);
                    } else if (command == VesperaCommandClient.Command.RESUME) {
                        updateObservationButtons(true);
                    }
                    if (visible && command != VesperaCommandClient.Command.SHUTDOWN) {
                        refreshStatusNow(false);
                    }
                } else if ("auth_required".equals(result.message)
                        || "auth_sign_failed".equals(result.message)) {
                    commandResult.setText(activity.getString(R.string.telescope_command_auth));
                } else if (result.message.startsWith("status_unavailable")) {
                    commandResult.setText(formatUnavailable(result.message));
                } else if ("auth_missing_challenge".equals(result.message)) {
                    commandResult.setText(activity.getString(
                            R.string.telescope_command_auth_missing_challenge));
                } else if ("auth_missing_id".equals(result.message)
                        || "auth_missing".equals(result.message)) {
                    commandResult.setText(activity.getString(R.string.telescope_command_auth_missing));
                } else if ("no_target".equals(result.message)) {
                    commandResult.setText(activity.getString(R.string.telescope_command_no_target));
                } else if ("no_site".equals(result.message)) {
                    commandResult.setText(activity.getString(R.string.telescope_command_no_site));
                } else if ("init_timeout".equals(result.message)
                        || "init_not_ready".equals(result.message)
                        || result.message.startsWith("init_failed")) {
                    commandResult.setText(activity.getString(
                            R.string.telescope_command_init_before_resume_fail, result.message));
                } else {
                    commandResult.setText(activity.getString(
                            R.string.telescope_command_fail, label(command), result.message));
                }
            });
        });
    }

    private String label(VesperaCommandClient.Command command) {
        switch (command) {
            case PARK: return activity.getString(R.string.telescope_btn_park);
            case STOP: return activity.getString(R.string.telescope_btn_stop);
            case RESUME: return activity.getString(R.string.telescope_btn_resume);
            case INIT: return activity.getString(R.string.telescope_btn_init);
            case SHUTDOWN: return activity.getString(R.string.telescope_btn_shutdown);
            default: return command.path;
        }
    }

    private int resolveApiPort() {
        if (apiPort > 0) return apiPort;
        VesperaPortScan scan = VesperaPortScanner.lastScan();
        if (scan != null && scan.apiRestPort > 0) return scan.apiRestPort;
        return InstrumentWatchdog.lastApiPort();
    }

    private void setCommandsEnabled(boolean enabled) {
        applyCommandEnablement(enabled);
    }

    private boolean beginObserveCommand() {
        if (!commandInFlight.compareAndSet(false, true)) return false;
        applyCommandEnablement(false);
        return true;
    }

    private void endObserveCommand() {
        commandInFlight.set(false);
        applyCommandEnablement(true);
    }

    /** Enable/disable command buttons; Init stays off if already init or observing. */
    private void applyCommandEnablement(boolean enabled) {
        boolean busy = commandInFlight.get();
        boolean on = enabled && !busy;
        cmdPark.setEnabled(on);
        cmdStop.setEnabled(on);
        cmdResume.setEnabled(on);
        cmdShutdown.setEnabled(on);
        cmdUploadSwu.setEnabled(on);
        cmdInit.setEnabled(on && canClickInit());
    }

    /** Init only when not initialized and not tracking. */
    private boolean canClickInit() {
        VesperaStatusSnapshot snap = lastSnap;
        if (snap == null) return true;
        if (snap.initialized) return false;
        if ("ON".equals(snap.tracking) || "STARTING".equals(snap.tracking)) return false;
        if (snap.isObserving() || snap.isTrackingAcquisition()) return false;
        return true;
    }

    private void updateObservationButtons(boolean observing) {
        cmdStop.setVisibility(observing ? View.VISIBLE : View.GONE);
        cmdResume.setVisibility(observing ? View.GONE : View.VISIBLE);
        cmdInit.setEnabled(!commandInFlight.get() && canClickInit());
    }

    private void confirmRefresh() {
        confirmAction(R.string.telescope_btn_refresh_status,
                R.string.telescope_confirm_refresh,
                () -> refreshStatusNow(true));
    }

    private void confirmCommand(VesperaCommandClient.Command command) {
        if (!isConnected()) {
            commandResult.setText(activity.getString(R.string.status_tab_need_wifi));
            return;
        }
        if (command == VesperaCommandClient.Command.INIT && !canClickInit()) {
            commandResult.setText(activity.getString(R.string.telescope_command_init_unavailable));
            return;
        }
        String message;
        if (command == VesperaCommandClient.Command.INIT) {
            PhotoSyncStore store = PhotoSyncStore.from(activity);
            if (store.hasSite()) {
                String label = store.siteLabel();
                if (label == null || label.trim().isEmpty()) {
                    label = String.format(Locale.US, "%.4f, %.4f",
                            store.siteLat(), store.siteLon());
                }
                String coords = String.format(Locale.US, "%.4f, %.4f",
                        store.siteLat(), store.siteLon());
                message = activity.getString(R.string.telescope_confirm_init_site, label, coords);
            } else {
                message = activity.getString(R.string.telescope_confirm_init);
            }
        } else if (command == VesperaCommandClient.Command.RESUME
                && (lastSnap == null || !VesperaCommandClient.readyToResumeAfterInit(lastSnap))) {
            message = activity.getString(R.string.telescope_confirm_resume_with_init);
        } else {
            message = activity.getString(confirmMessage(command));
        }
        confirmAction(label(command), message, () -> runCommand(command));
    }

    private void confirmShutdown() {
        if (!isConnected()) {
            commandResult.setText(activity.getString(R.string.status_tab_need_wifi));
            return;
        }
        confirmAction(activity.getString(R.string.telescope_shutdown_title),
                activity.getString(R.string.telescope_shutdown_message),
                () -> {
                    commandResult.setText(activity.getString(R.string.telescope_shutdown_syncing));
                    PhotoSyncService.syncThenShutdown(activity);
                });
    }

    private void promptUploadSwu() {
        if (!isConnected()) {
            commandResult.setText(activity.getString(R.string.status_tab_need_wifi));
            return;
        }
        commandResult.setText(activity.getString(R.string.telescope_swu_pick_waiting));
        Intent openDoc = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        openDoc.addCategory(Intent.CATEGORY_OPENABLE);
        openDoc.setType("*/*");
        openDoc.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(
                    Intent.createChooser(openDoc,
                            activity.getString(R.string.telescope_swu_pick_title)),
                    MainActivity.REQUEST_PICK_SWU);
        } catch (ActivityNotFoundException ignored) {
            try {
                Intent get = new Intent(Intent.ACTION_GET_CONTENT);
                get.addCategory(Intent.CATEGORY_OPENABLE);
                get.setType("*/*");
                get.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivityForResult(
                        Intent.createChooser(get,
                                activity.getString(R.string.telescope_swu_pick_title)),
                        MainActivity.REQUEST_PICK_SWU);
            } catch (ActivityNotFoundException missing) {
                commandResult.setText(activity.getString(R.string.telescope_swu_pick_no_app));
            }
        }
    }

    void onSwuPicked(Uri uri) {
        if (uri == null) {
            commandResult.setText(activity.getString(R.string.telescope_swu_pick_cancelled));
            return;
        }
        if (!commandInFlight.compareAndSet(false, true)) return;
        setCommandsEnabled(false);
        commandResult.setText(activity.getString(R.string.telescope_swu_checking));
        updateWorker.execute(() -> {
            File local = null;
            String fail = null;
            VesperaSwuValidator.Result check = null;
            try {
                local = copyPickedSwu(uri);
                check = VesperaSwuValidator.inspect(local);
                if (check == null || !check.ok()) {
                    fail = checkMessage(check);
                    deleteTempSwu(local);
                    local = null;
                }
            } catch (Exception e) {
                fail = e.getMessage() == null ? e.toString() : e.getMessage();
                deleteTempSwu(local);
                local = null;
            }
            final String error = fail;
            final File file = local;
            final VesperaSwuValidator.Result okCheck = check;
            mainHandler.post(() -> {
                commandInFlight.set(false);
                setCommandsEnabled(true);
                if (error != null || file == null || okCheck == null || !okCheck.ok()) {
                    commandResult.setText(error != null ? error
                            : activity.getString(R.string.telescope_swu_copy_fail));
                    return;
                }
                commandResult.setText(activity.getString(
                        R.string.telescope_swu_checksum_ok,
                        okCheck.sha256, okCheck.filesChecked));
                confirmAction(
                        activity.getString(R.string.telescope_swu_confirm_title),
                        activity.getString(R.string.telescope_swu_confirm_message,
                                file.getName(),
                                formatSize(okCheck.size),
                                okCheck.sha256,
                                okCheck.filesChecked),
                        () -> runSwuUpload(file));
            });
        });
    }

    private File copyPickedSwu(Uri uri) throws IOException {
        String name = queryDisplayName(uri);
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name != null) {
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
            if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
        }
        if (!VesperaSwuValidator.isValidSwuName(name)) {
            throw new IOException(activity.getString(R.string.telescope_swu_invalid_name));
        }
        File dir = new File(activity.getCacheDir(), "swu-upload");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException(activity.getString(R.string.telescope_swu_pick_missing_dir));
        }
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (f != null) f.delete();
            }
        }
        File dest = new File(dir, name);
        try (InputStream in = activity.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                throw new IOException(activity.getString(R.string.telescope_swu_copy_fail));
            }
            byte[] buf = new byte[65_536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return dest;
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri,
                    new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = cursor.getString(idx);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private String checkMessage(VesperaSwuValidator.Result r) {
        if (r == null) return activity.getString(R.string.telescope_swu_copy_fail);
        switch (r.kind) {
            case BAD_NAME:
                return activity.getString(R.string.telescope_swu_invalid_name);
            case EMPTY:
                return activity.getString(R.string.telescope_swu_empty);
            case BAD_MAGIC:
                return activity.getString(R.string.telescope_swu_invalid_magic);
            case BAD_CPIO:
                return activity.getString(R.string.telescope_swu_invalid_cpio);
            case MISSING_DESC:
                return activity.getString(R.string.telescope_swu_missing_desc);
            case HASH_MISMATCH:
                return activity.getString(R.string.telescope_swu_checksum_fail, r.detail);
            case IO:
            default:
                return activity.getString(R.string.telescope_swu_copy_fail);
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void deleteTempSwu(File file) {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && "swu-upload".equals(parent.getName())) {
            file.delete();
        }
    }

    private void runSwuUpload(File swuFile) {
        if (swuFile == null) return;
        if (!commandInFlight.compareAndSet(false, true)) return;
        setCommandsEnabled(false);

        commandResult.setText(activity.getString(R.string.telescope_swu_running));
        final VesperaStatusSnapshot snap = lastSnap;
        final File file = swuFile;

        updateWorker.execute(() -> {
            boolean ok = false;
            String message;
            try {
                if (!isConnected()) {
                    message = activity.getString(R.string.status_tab_need_wifi);
                } else if (snap == null) {
                    message = activity.getString(R.string.telescope_swu_no_status);
                } else if (!snap.canSignCommands()) {
                    message = activity.getString(R.string.telescope_command_auth_missing);
                } else if (snap.isObserving() || snap.isTrackingAcquisition()) {
                    message = activity.getString(R.string.telescope_swu_block_observing);
                } else if (!snap.isOnMainsPower() && snap.batteryPercent >= 0
                        && snap.batteryPercent < 50) {
                    message = activity.getString(R.string.telescope_swu_block_battery);
                } else if (!validateSdpMagic(file)) {
                    message = activity.getString(R.string.telescope_swu_invalid_magic);
                } else if (!file.getName().toLowerCase(Locale.US).startsWith("vespera-")
                        || !file.getName().toLowerCase(Locale.US).endsWith(".swu")) {
                    message = activity.getString(R.string.telescope_swu_invalid_name);
                } else {
                    VesperaFirmwareUpdateClient.Result r =
                            VesperaFirmwareUpdateClient.uploadSwu(
                                    VesperaConnectionService.getActiveNetwork(),
                                    host,
                                    resolveApiPort(),
                                    snap,
                                    file,
                                    createSwuProgressCallback());
                    ok = r.success;
                    message = ok ? activity.getString(R.string.telescope_swu_ok)
                            : activity.getString(R.string.telescope_swu_fail,
                            r.httpCode, r.message == null ? "" : r.message);
                }
            } catch (Exception e) {
                message = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String msg = message;
            final boolean okFinal = ok;
            mainHandler.post(() -> {
                scroll.pin();
                commandInFlight.set(false);
                setCommandsEnabled(true);
                commandResult.setText(msg);
                deleteTempSwu(file);
                if (okFinal && visible) {
                    // Firmware updates usually reboot the instrument; status refresh
                    // will recover when the connection state changes.
                    refreshStatusNow(false);
                }
            });
        });
    }

    private boolean validateSdpMagic(File file) {
        if (file == null || file.length() <= 0) return false;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] head = new byte[6];
            int n = in.read(head);
            if (n < 6) return false;
            String prefix = new String(head, StandardCharsets.US_ASCII);
            return "070701".equals(prefix) || "070702".equals(prefix);
        } catch (IOException e) {
            return false;
        }
    }

    private VesperaFirmwareUpdateClient.Progress createSwuProgressCallback() {
        final AtomicLong lastUiAt = new AtomicLong(0);
        return (sent, total) -> {
            long now = System.currentTimeMillis();
            long prev = lastUiAt.get();
            if (now - prev < 700) return;
            if (!lastUiAt.compareAndSet(prev, now)) return;
            mainHandler.post(() -> {
                if (!visible) return;
                int permille = total > 0
                        ? (int) Math.max(0, Math.min(1000, (sent * 1000) / total))
                        : 0;
                commandResult.setText(activity.getString(
                        R.string.telescope_swu_progress,
                        permille / 10, sent, total));
            });
        };
    }

    private int confirmMessage(VesperaCommandClient.Command command) {
        switch (command) {
            case PARK: return R.string.telescope_confirm_park;
            case STOP: return R.string.telescope_confirm_stop;
            case RESUME: return R.string.telescope_confirm_resume;
            case INIT: return R.string.telescope_confirm_init;
            case SHUTDOWN: return R.string.telescope_shutdown_message;
            default: return R.string.telescope_confirm_generic;
        }
    }

    private void confirmAction(int titleRes, int messageRes, Runnable onConfirm) {
        confirmAction(activity.getString(titleRes), activity.getString(messageRes), onConfirm);
    }

    private void confirmAction(String title, String message, Runnable onConfirm) {
        final int x = scroll.getScrollX();
        final int y = scroll.getScrollY();
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.telescope_confirm_ok, (d, w) -> {
                    scroll.restoreTo(x, y);
                    onConfirm.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> scroll.restoreTo(x, y));
        dialog.show();
        UiStyle.styleAlertButtons(dialog);
    }

    private VesperaLocationClient.Site resolveInitSite(Network network,
            VesperaStatusSnapshot snap) {
        PhotoSyncStore store = PhotoSyncStore.from(activity);
        if (store.hasSite()) {
            return new VesperaLocationClient.Site(store.siteLat(), store.siteLon());
        }
        VesperaLocationClient.Site fromApi = VesperaLocationClient.fetch(network);
        if (fromApi != null) return fromApi;
        return parseSnapLocation(snap);
    }

    private static VesperaLocationClient.Site parseSnapLocation(VesperaStatusSnapshot snap) {
        if (snap == null) return null;
        String loc = snap.location;
        if (loc == null || loc.isEmpty()) return null;
        int comma = loc.lastIndexOf(',');
        if (comma <= 0 || comma >= loc.length() - 1) return null;
        String lonPart = loc.substring(comma + 1).trim();
        String before = loc.substring(0, comma).trim();
        int sep = Math.max(before.lastIndexOf(' '), before.lastIndexOf('·'));
        String latPart = sep >= 0 ? before.substring(sep + 1).trim() : before;
        try {
            double lat = Double.parseDouble(latPart);
            double lon = Double.parseDouble(lonPart);
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
            if (Math.abs(lat) < 0.01 && Math.abs(lon) < 0.01) return null;
            return new VesperaLocationClient.Site(lat, lon);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatUnavailable(String detail) {
        String base = activity.getString(R.string.status_tab_api_unavailable);
        if (detail == null || detail.isEmpty()) return base;
        return base + "\n" + detail;
    }

    private void maybeShowPowerWarning(VesperaStatusSnapshot snap) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String status = effectiveBatteryStatus(snap);
        boolean onMains = VesperaStatusSnapshot.isOnMainsPower(status);
        boolean offMains = VesperaStatusSnapshot.isOffMainsPower(status);
        Log.i(TAG, "power status=" + status
                + " pct=" + lastBatteryPercent
                + " onMains=" + onMains
                + " offMains=" + offMains
                + " accepted=" + powerWarningAccepted
                + " ignored=" + powerWarningIgnoredThisVisit);
        if (onMains) {
            powerWarningAccepted = false;
            rememberConsultedPower(false);
            dismissPowerWarning(true);
            return;
        }
        if (!offMains) return;
        boolean changedFromMains = wasLastConsultedOnMains();
        rememberConsultedPower(true);
        if (powerWarningIgnoredThisVisit) return;
        if (powerWarningAccepted && !changedFromMains) return;
        if (changedFromMains) powerWarningAccepted = false;
        if (powerDialog != null && powerDialog.isShowing()) return;
        final int x = scroll.getScrollX();
        final int y = scroll.getScrollY();
        View hostView = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        Runnable show = () -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (powerDialog != null && powerDialog.isShowing()) return;
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.telescope_power_title)
                    .setMessage(R.string.telescope_power_message)
                    .setPositiveButton(R.string.telescope_power_accept, (d, w) -> {
                        powerWarningAccepted = true;
                        powerWarningIgnoredThisVisit = false;
                    })
                    .setNegativeButton(R.string.telescope_power_ignore, (d, w) -> {
                        powerWarningAccepted = false;
                        powerWarningIgnoredThisVisit = true;
                    })
                    .setOnCancelListener(d -> {
                        powerWarningAccepted = false;
                        powerWarningIgnoredThisVisit = true;
                    })
                    .create();
            powerDialog = dialog;
            dialog.setOnDismissListener(d -> {
                if (visible) scroll.restoreTo(x, y);
            });
            dialog.show();
            UiStyle.styleAlertButtons(dialog);
        };
        if (hostView != null) hostView.post(show);
        else mainHandler.post(show);
    }

    private void rememberBattery(VesperaStatusSnapshot snap) {
        if (snap == null) return;
        if (!snap.batteryStatus.isEmpty()) lastBatteryStatus = snap.batteryStatus;
        if (snap.batteryPercent >= 0) lastBatteryPercent = snap.batteryPercent;
    }

    private String effectiveBatteryStatus(VesperaStatusSnapshot snap) {
        if (snap != null && !snap.batteryStatus.isEmpty()) return snap.batteryStatus;
        return lastBatteryStatus;
    }

    private SharedPreferences powerPrefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean wasLastConsultedOnMains() {
        SharedPreferences prefs = powerPrefs();
        if (!prefs.getBoolean(KEY_LAST_POWER_KNOWN, false)) return true;
        return !prefs.getBoolean(KEY_LAST_OFF_MAINS, false);
    }

    private void rememberConsultedPower(boolean offMains) {
        powerPrefs().edit()
                .putBoolean(KEY_LAST_POWER_KNOWN, true)
                .putBoolean(KEY_LAST_OFF_MAINS, offMains)
                .apply();
    }

    private void dismissPowerWarning(boolean resetIgnore) {
        AlertDialog dialog = powerDialog;
        powerDialog = null;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        if (resetIgnore) powerWarningIgnoredThisVisit = false;
    }

    private boolean isConnected() {
        return VesperaConnectionService.STATUS_CONNECTED.equals(
                VesperaConnectionService.getLastStatus());
    }

    private TextView title(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(17);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setTextColor(0xFF1A237E);
        UiStyle.spaceBelow(view, density);
        return view;
    }

    private TextView section(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(14);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setTextColor(UiStyle.SLATE);
        UiStyle.spaceBelow(view, density * 0.5f);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(0xFF37474F);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        UiStyle.applyRecessed(view, 0xFFECEFF1);
        UiStyle.spaceBelow(view, density);
        return view;
    }

    private Button action(String text, int color) {
        Button button = new Button(activity);
        button.setAllCaps(true);
        button.setText(text);
        UiStyle.applyRaised(button, color, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * density);
        button.setLayoutParams(lp);
        return button;
    }

    private void toggleSniff() {
        sniffToggle.setEnabled(false);
        if (SingularityCommandSniffer.isRunning()) {
            SingularityCommandSniffer.stop(activity);
        } else {
            SingularityCommandSniffer.start(
                    activity, host, VesperaConnectionService.getActiveNetwork());
        }
    }

    private void refreshSniffStatus() {
        applySniffState(SingularityCommandSniffer.isRunning(),
                SingularityCommandSniffer.isRunning() ? "on" : "idle");
    }

    private void applySniffState(boolean running, String code) {
        sniffToggle.setEnabled(true);
        sniffToggle.setText(activity.getString(running
                ? R.string.telescope_sniff_stop
                : R.string.telescope_sniff_start));
        String installed = singularityInstalled()
                ? activity.getString(R.string.telescope_sniff_installed)
                : activity.getString(R.string.telescope_sniff_missing);
        sniffStatus.setText(installed + "\n" + sniffMessage(code));
    }

    private boolean singularityInstalled() {
        try {
            activity.getPackageManager().getPackageInfo(SingularityCommandSniffer.PKG, 0);
            return true;
        } catch (Exception missing) {
            return false;
        }
    }

    private String sniffMessage(String code) {
        if (code == null) code = "idle";
        switch (code) {
            case "starting": return activity.getString(R.string.telescope_sniff_starting);
            case "on": return activity.getString(R.string.telescope_sniff_on);
            case "off": return activity.getString(R.string.telescope_sniff_off);
            case "daemon": return activity.getString(R.string.telescope_sniff_err_daemon);
            case "iptables": return activity.getString(R.string.telescope_sniff_err_iptables);
            case "no-uid": return activity.getString(R.string.telescope_sniff_err_uid);
            case "bind": return activity.getString(R.string.telescope_sniff_err_bind);
            case "timeout": return activity.getString(R.string.telescope_sniff_err_timeout);
            case "proxy-down": return activity.getString(R.string.telescope_sniff_err_proxy);
            default: return activity.getString(R.string.telescope_sniff_idle);
        }
    }
}
