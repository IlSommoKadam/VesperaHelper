package com.vaonis.vesperahelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Builds and refreshes the Foto tab (USB HD + night/reconnect FTP sync). */
final class PhotoPanel {
    private static final int COLOR_OFFLINE = UiStyle.STEEL;
    private static final int COLOR_DETECTED = UiStyle.AMBER;
    private static final int COLOR_CONNECTED = UiStyle.GREEN;
    private static final int COLOR_CONNECTING = UiStyle.SLATE;
    private static final int COLOR_DANGER = UiStyle.ROSE;

    private final Activity activity;
    private final float density;
    private final UsbDiskStore diskStore;
    private final UsbHdStore hdStore;
    private final PhotoSyncStore syncStore;
    private final FixedScrollView scroll;
    private final TextView hdStatus;
    private final TextView hdSpaceView;
    private final TextView savedLabel;
    private final LinearLayout diskList;
    private final Button refreshDisks;
    private final Button mount;
    private final Button unmount;
    private final Button eject;
    private final TextView ejectSafe;
    private final TextView syncWindow;
    private final EditText nightIntervalInput;
    private final EditText dayStartHourInput;
    private final EditText dayEndHourInput;
    private final EditText cityInput;
    private final LinearLayout cityResults;
    private final Button citySearch;
    private final Button vesperaLocation;
    private final TextView locationStatus;
    private final Button applyInterval;
    private final TextView syncStatus;
    private final Button syncNow;
    private final Button pauseSync;
    private final Button continueSync;
    private final Button overlayPermission;
    private final TextView overlayHint;
    private final TextView ftpStatus;
    private final TextView ftpHint;
    private final ExecutorService geoWorker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private String selectedId = "";
    private boolean mounted;
    private boolean ejected;
    private int listedCount;
    private String lastMessage = "";
    private boolean visible;
    private AlertDialog diskDialog;
    private boolean diskWarningIgnoredThisVisit;
    private boolean diskWarningAccepted;
    private DaemonDisk.Space hdSpace = DaemonDisk.Space.unknown();
    private final TelescopeStatusHub.Listener statusListener = this::onTelescopeStatusEvent;

    PhotoPanel(Activity activity, float density, int padding) {
        this.activity = activity;
        this.density = density;
        this.diskStore = UsbDiskStore.from(activity);
        this.hdStore = UsbHdStore.from(activity);
        this.syncStore = PhotoSyncStore.from(activity);
        this.selectedId = diskStore.getId();

        scroll = new FixedScrollView(activity);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, (int) (80 * density));
        layout.setLayoutParams(new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = title(activity.getString(R.string.photo_section_hd));
        hdStatus = body(activity.getString(R.string.photo_hd_idle));
        hdSpaceView = body(activity.getString(R.string.photo_hd_occupied, "—"));
        hdSpaceView.setTextSize(15);
        hdSpaceView.setTypeface(hdSpaceView.getTypeface(), android.graphics.Typeface.BOLD);
        savedLabel = body(savedText());

        refreshDisks = action(activity.getString(R.string.photo_btn_refresh_disks), COLOR_CONNECTING);
        refreshDisks.setOnClickListener(v -> activity.startForegroundService(
                new Intent(activity, PhotoSyncService.class)
                        .setAction(PhotoSyncService.ACTION_LIST_DISKS)));
        mount = action(activity.getString(R.string.photo_btn_mount), COLOR_CONNECTED);
        mount.setOnClickListener(v -> {
            String spec = selectedId;
            if (spec == null || spec.isEmpty()) spec = hdStore.getSpec();
            if (spec == null || spec.isEmpty()) spec = diskStore.getId();
            if (spec == null || spec.isEmpty()) {
                hdStatus.setText(R.string.photo_hd_select_first);
                return;
            }
            selectedId = spec;
            activity.startForegroundService(new Intent(activity, PhotoSyncService.class)
                    .setAction(PhotoSyncService.ACTION_MOUNT)
                    .putExtra(PhotoSyncService.EXTRA_DISK_ID, spec));
        });
        unmount = action(activity.getString(R.string.photo_btn_unmount), COLOR_DANGER);
        unmount.setOnClickListener(v -> activity.startForegroundService(
                new Intent(activity, PhotoSyncService.class)
                        .setAction(PhotoSyncService.ACTION_UNMOUNT)));
        unmount.setVisibility(View.GONE);
        eject = action(activity.getString(R.string.photo_btn_eject), UiStyle.TERRACOTTA);
        eject.setOnClickListener(v -> {
            String spec = selectedId;
            if (spec == null || spec.isEmpty()) spec = hdStore.getSpec();
            if (spec == null || spec.isEmpty()) spec = diskStore.getId();
            activity.startForegroundService(new Intent(activity, PhotoSyncService.class)
                    .setAction(PhotoSyncService.ACTION_EJECT)
                    .putExtra(PhotoSyncService.EXTRA_DISK_ID, spec));
        });
        ejectSafe = body(activity.getString(R.string.photo_hd_safe_to_remove));
        ejectSafe.setTypeface(ejectSafe.getTypeface(), android.graphics.Typeface.BOLD);
        ejectSafe.setTextColor(COLOR_CONNECTED);
        ejectSafe.setTextSize(15);
        ejectSafe.setVisibility(View.GONE);

        diskList = new LinearLayout(activity);
        diskList.setOrientation(LinearLayout.VERTICAL);

        TextView syncTitle = title(activity.getString(R.string.photo_section_sync));
        syncTitle.setPadding(0, 0, 0, (int) (4 * density));
        syncWindow = body(windowText());
        applyInterval = action(activity.getString(R.string.photo_sync_interval_apply), COLOR_CONNECTING);
        applyInterval.setOnClickListener(v -> applyInterval());

        LinearLayout nightIntervalRow = new LinearLayout(activity);
        nightIntervalRow.setOrientation(LinearLayout.HORIZONTAL);
        nightIntervalRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView nightIntervalLabel = body(activity.getString(R.string.photo_sync_night_interval_label));
        nightIntervalLabel.setPadding(0, 0, (int) (8 * density), 0);
        nightIntervalInput = new EditText(activity);
        nightIntervalInput.setHint(R.string.photo_sync_interval_hint);
        nightIntervalInput.setText(PhotoSyncStore.formatIntervalHours(syncStore.nightIntervalHours()));
        nightIntervalInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        nightIntervalInput.setSingleLine(true);
        nightIntervalInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nightIntervalInput.setEms(4);
        LinearLayout.LayoutParams intervalLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nightIntervalInput.setLayoutParams(intervalLp);
        TextView intervalUnit2 = body(activity.getString(R.string.photo_sync_interval_unit));
        intervalUnit2.setPadding((int) (8 * density), 0, (int) (8 * density), 0);
        nightIntervalInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyInterval();
                return true;
            }
            return false;
        });
        nightIntervalRow.addView(nightIntervalLabel);
        nightIntervalRow.addView(nightIntervalInput);
        nightIntervalRow.addView(intervalUnit2);

        LinearLayout dayStartRow = new LinearLayout(activity);
        dayStartRow.setOrientation(LinearLayout.HORIZONTAL);
        dayStartRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView dayStartLabel = body(activity.getString(R.string.photo_sync_day_start_label));
        dayStartLabel.setPadding(0, 0, (int) (8 * density), 0);
        dayStartHourInput = new EditText(activity);
        dayStartHourInput.setHint("10");
        dayStartHourInput.setText(String.valueOf(syncStore.dayStartHour()));
        dayStartHourInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        dayStartHourInput.setSingleLine(true);
        dayStartHourInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        dayStartHourInput.setEms(3);
        dayStartHourInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView hourUnit = body(activity.getString(R.string.photo_sync_hour_unit));
        hourUnit.setPadding((int) (8 * density), 0, (int) (8 * density), 0);
        dayStartHourInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyInterval();
                return true;
            }
            return false;
        });
        dayStartRow.addView(dayStartLabel);
        dayStartRow.addView(dayStartHourInput);
        dayStartRow.addView(hourUnit);

        LinearLayout dayEndRow = new LinearLayout(activity);
        dayEndRow.setOrientation(LinearLayout.HORIZONTAL);
        dayEndRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView dayEndLabel = body(activity.getString(R.string.photo_sync_day_end_label));
        dayEndLabel.setPadding(0, 0, (int) (8 * density), 0);
        dayEndHourInput = new EditText(activity);
        dayEndHourInput.setHint("19");
        dayEndHourInput.setText(String.valueOf(syncStore.dayEndHour()));
        dayEndHourInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        dayEndHourInput.setSingleLine(true);
        dayEndHourInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        dayEndHourInput.setEms(3);
        dayEndHourInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView hourUnit2 = body(activity.getString(R.string.photo_sync_hour_unit));
        hourUnit2.setPadding((int) (8 * density), 0, (int) (8 * density), 0);
        dayEndHourInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyInterval();
                return true;
            }
            return false;
        });
        dayEndRow.addView(dayEndLabel);
        dayEndRow.addView(dayEndHourInput);
        dayEndRow.addView(hourUnit2);

        TextView cityLabel = body(activity.getString(R.string.photo_sync_city_label));
        LinearLayout cityRow = new LinearLayout(activity);
        cityRow.setOrientation(LinearLayout.HORIZONTAL);
        cityRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        cityInput = new EditText(activity);
        cityInput.setHint(R.string.photo_sync_city_hint);
        cityInput.setSingleLine(true);
        cityInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        cityInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (syncStore.hasSite() && PhotoSyncStore.SITE_CITY.equals(syncStore.siteSource())) {
            cityInput.setText(syncStore.siteLabel());
        }
        cityInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchCity();
                return true;
            }
            return false;
        });
        citySearch = new Button(activity);
        citySearch.setAllCaps(false);
        citySearch.setText(R.string.photo_sync_city_search);
        UiStyle.applyRaised(citySearch, COLOR_CONNECTING, true);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        searchLp.setMarginStart((int) (8 * density));
        citySearch.setLayoutParams(searchLp);
        citySearch.setOnClickListener(v -> searchCity());
        cityRow.addView(cityInput);
        cityRow.addView(citySearch);
        cityResults = new LinearLayout(activity);
        cityResults.setOrientation(LinearLayout.VERTICAL);
        vesperaLocation = action(activity.getString(R.string.photo_sync_vespera_location), COLOR_CONNECTING);
        vesperaLocation.setOnClickListener(v -> fetchVesperaSite());
        locationStatus = body(locationText());
        locationStatus.setTextSize(13);

        LinearLayout syncBox = new LinearLayout(activity);
        syncBox.setOrientation(LinearLayout.VERTICAL);
        int boxPad = (int) (12 * density);
        syncBox.setPadding(boxPad, boxPad, boxPad, (int) (6 * density));
        syncBox.setBackground(UiStyle.sectionPanel(density));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = (int) (6 * density);
        boxLp.bottomMargin = (int) (10 * density);
        syncBox.setLayoutParams(boxLp);
        syncBox.addView(syncTitle);
        syncBox.addView(syncWindow);
        syncBox.addView(nightIntervalRow);
        syncBox.addView(dayStartRow);
        syncBox.addView(dayEndRow);
        syncBox.addView(cityLabel);
        syncBox.addView(cityRow);
        syncBox.addView(cityResults);
        syncBox.addView(vesperaLocation);
        syncBox.addView(locationStatus);
        syncBox.addView(applyInterval);

        syncStatus = body(activity.getString(R.string.photo_sync_idle));
        syncNow = action(activity.getString(R.string.photo_btn_sync_now), COLOR_CONNECTED);
        syncNow.setOnClickListener(v -> startCopyNow());
        pauseSync = action(activity.getString(R.string.photo_btn_pause), COLOR_DANGER);
        pauseSync.setOnClickListener(v -> PhotoSyncService.pauseUntilSchedule(activity));
        continueSync = action(activity.getString(R.string.photo_btn_continue), COLOR_DETECTED);
        continueSync.setOnClickListener(v -> resumeCopyNow());
        continueSync.setVisibility(View.GONE);
        overlayPermission = action(activity.getString(R.string.photo_sync_overlay_btn), COLOR_CONNECTING);
        overlayPermission.setOnClickListener(v -> requestOverlayPermission());
        overlayHint = body(activity.getString(R.string.photo_sync_overlay_hint));
        overlayHint.setTextSize(13);

        TextView ftpTitle = title(activity.getString(R.string.photo_section_ftp));
        ftpStatus = body(activity.getString(R.string.photo_ftp_off));
        ftpHint = body(activity.getString(R.string.photo_ftp_hint));
        ftpHint.setTextSize(13);

        layout.addView(title);
        layout.addView(hdStatus);
        layout.addView(hdSpaceView);
        layout.addView(savedLabel);
        layout.addView(refreshDisks);
        layout.addView(mount);
        layout.addView(unmount);
        layout.addView(eject);
        layout.addView(ejectSafe);
        layout.addView(diskList);
        layout.addView(syncBox);
        layout.addView(syncStatus);
        layout.addView(syncNow);
        layout.addView(pauseSync);
        layout.addView(continueSync);
        layout.addView(overlayPermission);
        layout.addView(overlayHint);
        layout.addView(ftpTitle);
        layout.addView(ftpStatus);
        layout.addView(ftpHint);
        scroll.addView(layout);
        scroll.setVisibility(View.GONE);
        refreshMountButtons();
        refreshOverlayRow();
        refreshUserFolderHint();
        refreshCopyButtons(false);
        TelescopeStatusHub.ensure().addListener(statusListener);
    }

    void refreshUserFolderHint() {
        ftpHint.setText(activity.getString(R.string.photo_ftp_hint) + "\n\n"
                + activity.getString(R.string.photo_user_folder,
                VesperaPortInventory.userFolderUrl()));
    }

    View view() {
        return scroll;
    }

    void onResume() {
        boolean opening = !visible;
        visible = true;
        if (opening) diskWarningIgnoredThisVisit = false;
        if (receiverRegistered) {
            refreshHdSpace();
            maybeShowDiskWarning();
            refreshCopyButtons(isCopyRunning());
            return;
        }
        IntentFilter filter = new IntentFilter(PhotoSyncService.ACTION_STATUS);
        filter.addAction(PhotoSyncService.ACTION_PROGRESS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
        activity.startForegroundService(new Intent(activity, PhotoSyncService.class)
                .setAction(PhotoSyncService.ACTION_LIST_DISKS));
        refreshOverlayRow();
        refreshUserFolderHint();
        refreshHdSpace();
        maybeShowDiskWarning();
        refreshCopyButtons(isCopyRunning());
    }

    void onPause() {
        visible = false;
        dismissDiskWarning(false);
        if (!receiverRegistered) return;
        activity.unregisterReceiver(statusReceiver);
        receiverRegistered = false;
    }

    void shutdown() {
        TelescopeStatusHub hub = TelescopeStatusHub.get();
        if (hub != null) hub.removeListener(statusListener);
        onPause();
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            scroll.pin();
            if (PhotoSyncService.ACTION_PROGRESS.equals(intent.getAction())) {
                boolean syncing = intent.hasExtra(PhotoSyncService.EXTRA_SYNCING)
                        ? intent.getBooleanExtra(PhotoSyncService.EXTRA_SYNCING, false)
                        : isCopyRunning();
                bindCopyProgressText();
                refreshCopyButtons(syncing);
                return;
            }
            String hd = intent.getStringExtra(PhotoSyncService.EXTRA_HD);
            String sync = intent.getStringExtra(PhotoSyncService.EXTRA_SYNC);
            String ftp = intent.getStringExtra(PhotoSyncService.EXTRA_FTP);
            String disks = intent.getStringExtra(PhotoSyncService.EXTRA_DISKS);
            String selected = intent.getStringExtra(PhotoSyncService.EXTRA_SELECTED);
            String msg = intent.getStringExtra(PhotoSyncService.EXTRA_MESSAGE);
            mounted = intent.getBooleanExtra(PhotoSyncService.EXTRA_MOUNTED, false);
            ejected = intent.getBooleanExtra(PhotoSyncService.EXTRA_EJECTED, false);
            if (msg != null) lastMessage = msg;
            if (hd != null) hdStatus.setText(hd);
            if (!mounted && lastMessage != null && !lastMessage.isEmpty()) {
                hdStatus.setText(lastMessage);
            }
            if (sync != null) syncStatus.setText(sync);
            String last = intent.getStringExtra(PhotoSyncService.EXTRA_LAST_SYNC);
            if (last != null && !last.isEmpty()
                    && !intent.getBooleanExtra(PhotoSyncService.EXTRA_SYNCING, false)) {
                syncStatus.setText(sync == null || sync.isEmpty() ? last : (sync + "\n" + last));
            }
            if (ftp != null) ftpStatus.setText(ftp);
            String hint = intent.getStringExtra(PhotoSyncService.EXTRA_FTP_HINT);
            if (hint != null && !hint.isEmpty()) {
                ftpHint.setText(hint + "\n\n" + activity.getString(R.string.photo_user_folder,
                        VesperaPortInventory.userFolderUrl()));
            }
            if (selected != null && !selected.isEmpty()) selectedId = selected;
            if (disks != null) bindDisks(PhotoSyncService.parseDisks(disks));
            savedLabel.setText(savedText());
            syncWindow.setText(windowText());
            refreshHourFields();
            locationStatus.setText(locationText());
            refreshMountButtons();
            refreshCopyButtons(intent.getBooleanExtra(PhotoSyncService.EXTRA_SYNCING, false));
            refreshHdSpace();
            maybeShowDiskWarning();
        }
    };

    private static boolean isCopyRunning() {
        SyncProgress progress = SyncProgressHud.latest;
        return progress != null && progress.active;
    }

    private void bindCopyProgressText() {
        SyncProgress progress = SyncProgressHud.latest;
        if (progress == null || !progress.active) return;
        if (progress.fileTotal > 0 && progress.fileName != null && !progress.fileName.isEmpty()) {
            syncStatus.setText(activity.getString(R.string.photos_sync_file,
                    progress.fileIndex, progress.fileTotal, progress.fileName));
        }
    }

    private void startCopyNow() {
        refreshCopyButtons(true);
        syncStatus.setText(R.string.photo_sync_starting);
        SyncProgress starting = new SyncProgress();
        starting.active = true;
        starting.phase = SyncProgress.PHASE_LIST;
        starting.detail = activity.getString(R.string.photo_sync_starting);
        SyncProgressHud.latest = starting;
        openCopyWindow();
        PhotoSyncService.syncNow(activity);
    }

    private void resumeCopyNow() {
        refreshCopyButtons(true);
        syncStatus.setText(R.string.photo_sync_resume);
        SyncProgress starting = new SyncProgress();
        starting.active = true;
        starting.phase = SyncProgress.PHASE_LIST;
        starting.detail = activity.getString(R.string.photo_sync_resume);
        SyncProgressHud.latest = starting;
        openCopyWindow();
        PhotoSyncService.resumeSync(activity);
    }

    private void openCopyWindow() {
        try {
            activity.startActivity(new Intent(activity, SyncProgressActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) {
        }
    }

    /** Copia foto ora / Continua inert while transferring; Metti in pausa only then. */
    private void refreshCopyButtons(boolean syncing) {
        boolean unfinished = syncStore.paused() || syncStore.pauseUntilSchedule()
                || syncStore.inProgress();
        boolean enableNow = !syncing;
        boolean enablePause = syncing;
        boolean enableContinue = unfinished && !syncing;
        UiStyle.applyRaised(syncNow, enableNow ? COLOR_CONNECTED : COLOR_OFFLINE, enableNow);
        UiStyle.applyRaised(pauseSync, enablePause ? COLOR_DANGER : COLOR_OFFLINE, enablePause);
        continueSync.setVisibility((unfinished || syncing) ? View.VISIBLE : View.GONE);
        UiStyle.applyRaised(continueSync, enableContinue ? COLOR_DETECTED : COLOR_OFFLINE, enableContinue);
    }

    private void bindDisks(List<UsbDisk> disks) {
        listedCount = disks.size();
        scroll.runKeepingScroll(() -> bindDisksLocked(disks));
    }

    private void bindDisksLocked(List<UsbDisk> disks) {
        diskList.removeAllViews();
        if (disks.isEmpty()) {
            TextView empty = body(lastMessage != null && !lastMessage.isEmpty()
                    ? lastMessage : activity.getString(R.string.photo_hd_none));
            diskList.addView(empty);
            return;
        }
        for (UsbDisk disk : disks) {
            Button row = new Button(activity);
            row.setAllCaps(false);
            boolean selected = disk.id().equals(selectedId);
            boolean isMounted = disk.mounted || (mounted && selected);
            String marker = isMounted ? "✓ " : (selected ? "● " : "");
            row.setText(marker + disk.displayTitle()
                    + "\n" + disk.name
                    + (disk.uuid.isEmpty() ? "" : (" · UUID " + disk.uuid))
                    + "\n" + disk.size + " · " + disk.fsType
                    + (isMounted ? (" · " + activity.getString(R.string.photo_hd_row_mounted)) : ""));
            int color = isMounted ? COLOR_CONNECTED : (selected ? COLOR_DETECTED : COLOR_OFFLINE);
            if (isMounted) {
                UiStyle.applyRecessed(row, color);
            } else {
                UiStyle.applyRaised(row, color, true);
            }
            row.setOnClickListener(v -> {
                selectedId = disk.id();
                bindDisks(disks);
                refreshMountButtons();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (6 * density);
            row.setLayoutParams(lp);
            diskList.addView(row);
        }
    }

    private void refreshMountButtons() {
        boolean hasSelection = selectedId != null && !selectedId.isEmpty();
        boolean hasSaved = hdStore.isConfigured() || !diskStore.getId().isEmpty();
        boolean hasListed = listedCount > 0;
        // Smonta HD is hidden: Spegni HD already umounts then powers USB off.
        unmount.setVisibility(View.GONE);
        if (mounted) {
            mount.setVisibility(View.GONE);
        } else {
            mount.setVisibility(View.VISIBLE);
            // After power-off/eject the list is empty: still allow Attiva/Monta on the saved HD.
            boolean canMount = (hasListed && hasSelection) || hasSaved;
            mount.setText(ejected
                    ? activity.getString(R.string.photo_btn_activate)
                    : activity.getString(R.string.photo_btn_mount));
            mount.setEnabled(canMount);
            UiStyle.applyRaised(mount, canMount ? COLOR_CONNECTED : COLOR_OFFLINE, canMount);
        }
        // When the HD is powered off, hide Spegni and show the hint + Attiva.
        boolean showSafe = ejected;
        eject.setVisibility(showSafe ? View.GONE : View.VISIBLE);
        ejectSafe.setVisibility(showSafe ? View.VISIBLE : View.GONE);
        if (showSafe) {
            ejectSafe.setText(activity.getString(R.string.photo_hd_powered_off_hint));
        }
        if (!showSafe) {
            boolean canEject = mounted || hasListed || hasSaved;
            eject.setEnabled(canEject);
            UiStyle.applyRaised(eject, canEject ? UiStyle.TERRACOTTA : COLOR_OFFLINE, canEject);
        }
    }

    private void refreshOverlayRow() {
        boolean allowed = Settings.canDrawOverlays(activity);
        overlayPermission.setVisibility(allowed ? View.GONE : View.VISIBLE);
        overlayHint.setText(allowed
                ? activity.getString(R.string.photo_sync_overlay_ok)
                : activity.getString(R.string.photo_sync_overlay_hint));
    }

    private void requestOverlayPermission() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception ignored) {
        }
    }

    private void applyInterval() {
        scroll.pin();
        float nightHours = PhotoSyncStore.parseIntervalHours(nightIntervalInput.getText().toString());
        int dayStart = parseHour(dayStartHourInput.getText().toString(), syncStore.dayStartHour());
        int dayEnd = parseHour(dayEndHourInput.getText().toString(), syncStore.dayEndHour());
        boolean hoursEdited = dayStart != syncStore.dayStartHour() || dayEnd != syncStore.dayEndHour();

        syncStore.setNightIntervalHours(nightHours);
        if (hoursEdited) {
            syncStore.setAutoHours(false);
            syncStore.setDayStartHour(dayStart);
            syncStore.setDayEndHour(dayEnd);
        }

        nightIntervalInput.setText(PhotoSyncStore.formatIntervalHours(nightHours));
        refreshHourFields();
        locationStatus.setText(locationText());

        syncWindow.setText(windowText());
        activity.startForegroundService(new Intent(activity, PhotoSyncService.class)
                .setAction(PhotoSyncService.ACTION_LIST_DISKS));
    }

    private void searchCity() {
        hideKeyboard(cityInput);
        final String query = cityInput.getText() == null ? "" : cityInput.getText().toString().trim();
        scroll.runKeepingScroll(() -> cityResults.removeAllViews());
        if (query.length() < 2) {
            locationStatus.setText(R.string.photo_sync_city_none);
            return;
        }
        locationStatus.setText(R.string.photo_sync_city_searching);
        citySearch.setEnabled(false);
        final String language = AppLocale.getLanguage(activity);
        geoWorker.execute(() -> {
            try {
                final List<CityGeocoder.Hit> hits = CityGeocoder.search(query, language);
                mainHandler.post(() -> {
                    citySearch.setEnabled(true);
                    bindCityHits(hits);
                });
            } catch (Exception failure) {
                mainHandler.post(() -> {
                    citySearch.setEnabled(true);
                    scroll.runKeepingScroll(() -> {
                        cityResults.removeAllViews();
                        locationStatus.setText(R.string.photo_sync_city_error);
                    });
                });
            }
        });
    }

    private void bindCityHits(List<CityGeocoder.Hit> hits) {
        scroll.runKeepingScroll(() -> {
            cityResults.removeAllViews();
            if (hits == null || hits.isEmpty()) {
                locationStatus.setText(R.string.photo_sync_city_none);
                return;
            }
            locationStatus.setText(locationText());
            for (CityGeocoder.Hit hit : hits) {
                Button row = new Button(activity);
                row.setAllCaps(false);
                row.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
                row.setText(hit.label);
                UiStyle.applyRaised(row, UiStyle.SLATE, true);
                row.setOnClickListener(v -> applySite(hit.lat, hit.lon, hit.label,
                        PhotoSyncStore.SITE_CITY, hit.countryCode));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = (int) (4 * density);
                row.setLayoutParams(lp);
                cityResults.addView(row);
            }
        });
    }

    private void fetchVesperaSite() {
        if (!VesperaConnectionService.STATUS_CONNECTED.equals(
                VesperaConnectionService.getLastStatus())) {
            locationStatus.setText(R.string.photo_sync_vespera_need);
            return;
        }
        locationStatus.setText(R.string.photo_sync_vespera_reading);
        vesperaLocation.setEnabled(false);
        final Network network = VesperaConnectionService.getActiveNetwork();
        geoWorker.execute(() -> {
            final VesperaLocationClient.Site site = VesperaLocationClient.fetch(network);
            mainHandler.post(() -> {
                vesperaLocation.setEnabled(true);
                if (site == null) {
                    locationStatus.setText(R.string.photo_sync_vespera_fail);
                    return;
                }
                String label = activity.getString(R.string.photo_sync_vespera_ok, site.lat, site.lon);
                applySite(site.lat, site.lon, label, PhotoSyncStore.SITE_VESPERA, "");
            });
        });
    }

    private void applySite(double lat, double lon, String label, String source, String countryCode) {
        locationStatus.setText(R.string.photo_sync_clock_syncing);
        geoWorker.execute(() -> {
            syncStore.setSite(lat, lon, label, source, countryCode);
            mainHandler.post(() -> {
                scroll.pin();
                cityResults.removeAllViews();
                if (PhotoSyncStore.SITE_CITY.equals(source)) {
                    cityInput.setText(label);
                }
                activity.startForegroundService(new Intent(activity, PhotoSyncService.class)
                        .setAction(PhotoSyncService.ACTION_SYNC_CLOCK));
            });
        });
    }

    private void refreshHourFields() {
        if (!dayStartHourInput.hasFocus()) {
            dayStartHourInput.setText(String.valueOf(syncStore.dayStartHour()));
        }
        if (!dayEndHourInput.hasFocus()) {
            dayEndHourInput.setText(String.valueOf(syncStore.dayEndHour()));
        }
    }

    private String locationText() {
        if (syncStore.autoHours()) {
            String label = syncStore.siteLabel();
            if (label == null || label.isEmpty()) {
                label = String.format(Locale.US, "%.2f, %.2f",
                        syncStore.siteLat(), syncStore.siteLon());
            }
            String auto = activity.getString(R.string.photo_sync_location_auto,
                    label, syncStore.dayEndHour(), syncStore.dayStartHour());
            String tz = syncStore.siteTimeZone();
            if (tz != null && !tz.isEmpty()) {
                auto += "\n" + activity.getString(R.string.photo_sync_clock_tz, tz);
            }
            auto += "\n" + activity.getString(syncStore.lastNtpOk()
                    ? R.string.photo_sync_clock_ok : R.string.photo_sync_clock_pending);
            return auto;
        }
        if (syncStore.hasSite()) {
            return activity.getString(R.string.photo_sync_location_manual);
        }
        return activity.getString(R.string.photo_sync_location_unset);
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
        if (imm != null && view != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private String windowText() {
        return activity.getString(R.string.photo_sync_interval_help,
                PhotoSyncStore.formatIntervalHours(syncStore.nightIntervalHours()),
                syncStore.dayStartHour(),
                syncStore.dayEndHour());
    }

    private static int parseHour(String raw, int fallback) {
        try {
            String t = raw == null ? "" : raw.trim();
            if (t.isEmpty()) return fallback;
            // Accept "10", "11", "12" (and also "7") as integers.
            return Integer.parseInt(t);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String savedText() {
        if (!diskStore.hasSaved()) return activity.getString(R.string.photo_hd_not_saved);
        String label = diskStore.getLabel();
        if (label == null || label.isEmpty() || "-".equals(label)) label = diskStore.getId();
        return activity.getString(R.string.photo_hd_saved, label, diskStore.getId());
    }

    private void refreshHdSpace() {
        hdSpace = DaemonDisk.photosSpace(activity);
        if (hdSpace.known) {
            TelescopeStatusHub.ensure().ingestHd(hdSpace.usedPercent);
        }
        if (hdSpace.known && hdSpace.usedPercent < PhotoSyncService.HD_WARNING_PERCENT) {
            diskWarningAccepted = false;
            dismissDiskWarning(true);
        }
        String occupied = (hdSpace != null && hdSpace.known && !hdSpace.label().isEmpty())
                ? hdSpace.label()
                : "—";
        hdSpaceView.setText(activity.getString(R.string.photo_hd_occupied, occupied));
    }

    private void maybeShowDiskWarning() {
        if (!visible || activity.isFinishing()) return;
        if (hdSpace == null || !hdSpace.known
                || hdSpace.usedPercent < PhotoSyncService.HD_WARNING_PERCENT) {
            return;
        }
        if (diskWarningAccepted || diskWarningIgnoredThisVisit) return;
        if (diskDialog != null && diskDialog.isShowing()) return;
        String occupied = hdSpace.label().isEmpty() ? (hdSpace.usedPercent + "%") : hdSpace.label();
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.telescope_disk_title)
                .setMessage(activity.getString(R.string.telescope_disk_message, occupied))
                .setPositiveButton(R.string.telescope_power_accept, (d, w) -> {
                    diskWarningAccepted = true;
                    diskWarningIgnoredThisVisit = false;
                })
                .setNegativeButton(R.string.telescope_power_ignore, (d, w) -> {
                    diskWarningAccepted = false;
                    diskWarningIgnoredThisVisit = true;
                })
                .setOnCancelListener(d -> {
                    diskWarningAccepted = false;
                    diskWarningIgnoredThisVisit = true;
                })
                .create();
        diskDialog = dialog;
        final int x = scroll.getScrollX();
        final int y = scroll.getScrollY();
        dialog.setOnDismissListener(d -> scroll.restoreTo(x, y));
        dialog.show();
        UiStyle.styleAlertButtons(dialog);
    }

    private void onTelescopeStatusEvent(TelescopeStatusEvent event) {
        if (event == null || event.kind != TelescopeStatusEvent.Kind.HD_HIGH) return;
        mainHandler.post(() -> {
            refreshHdSpace();
            maybeShowDiskWarning();
        });
    }

    private void dismissDiskWarning(boolean resetIgnore) {
        AlertDialog dialog = diskDialog;
        diskDialog = null;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        if (resetIgnore) diskWarningIgnoredThisVisit = false;
    }

    private TextView title(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
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

    private Button action(String text, int color) {
        Button button = new Button(activity);
        button.setAllCaps(true);
        button.setText(text);
        UiStyle.applyRaised(button, color, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (6 * density);
        button.setLayoutParams(lp);
        return button;
    }
}
