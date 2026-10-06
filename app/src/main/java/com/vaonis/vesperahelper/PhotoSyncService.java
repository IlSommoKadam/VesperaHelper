package com.vaonis.vesperahelper;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Mounts the USB HD, syncs Vespera /USER photos at night and on reconnect, and serves them over FTP. */
public final class PhotoSyncService extends Service {
    public static final String ACTION_STATUS = "com.vaonis.vesperahelper.PHOTO_STATUS";
    public static final String ACTION_PROGRESS = "com.vaonis.vesperahelper.PHOTO_PROGRESS";
    public static final String ACTION_REFRESH_DISKS = "com.vaonis.vesperahelper.PHOTO_REFRESH";
    public static final String ACTION_LIST_DISKS = ACTION_REFRESH_DISKS;
    public static final String ACTION_MOUNT = "com.vaonis.vesperahelper.PHOTO_MOUNT";
    public static final String ACTION_UNMOUNT = "com.vaonis.vesperahelper.PHOTO_UNMOUNT";
    public static final String ACTION_EJECT = "com.vaonis.vesperahelper.PHOTO_EJECT";
    public static final String ACTION_SYNC_NOW = "com.vaonis.vesperahelper.PHOTO_SYNC_NOW";
    public static final String ACTION_SYNC_STORAGE = "com.vaonis.vesperahelper.PHOTO_SYNC_STORAGE";
    public static final String ACTION_FORGET = "com.vaonis.vesperahelper.PHOTO_FORGET";
    public static final String ACTION_BOOTSTRAP = "com.vaonis.vesperahelper.PHOTO_BOOTSTRAP";
    /** UI opened (MainActivity): power HD off if telescope is silent. */
    public static final String ACTION_APP_OPEN = "com.vaonis.vesperahelper.PHOTO_APP_OPEN";
    public static final String ACTION_RESUME = "com.vaonis.vesperahelper.PHOTO_RESUME";
    public static final String ACTION_PAUSE = "com.vaonis.vesperahelper.PHOTO_PAUSE";
    public static final String ACTION_PAUSE_UNTIL_SCHEDULE =
            "com.vaonis.vesperahelper.PHOTO_PAUSE_UNTIL_SCHEDULE";
    public static final String ACTION_HIDE_WINDOW = "com.vaonis.vesperahelper.PHOTO_HIDE_WINDOW";
    public static final String ACTION_SHOW_WINDOW = "com.vaonis.vesperahelper.PHOTO_SHOW_WINDOW";
    public static final String ACTION_SYNC_CLOCK = "com.vaonis.vesperahelper.PHOTO_SYNC_CLOCK";
    public static final String ACTION_APPLY_SETTINGS = "com.vaonis.vesperahelper.PHOTO_APPLY_SETTINGS";
    public static final String ACTION_AUTO_SYNC = "com.vaonis.vesperahelper.PHOTO_AUTO_SYNC";
    public static final String ACTION_SYNC_THEN_SHUTDOWN =
            "com.vaonis.vesperahelper.PHOTO_SYNC_THEN_SHUTDOWN";
    public static final String EXTRA_SPEC = "spec";
    public static final String EXTRA_DISK_ID = "disk_id";
    public static final String EXTRA_DISKS = "disks";
    public static final String EXTRA_HD = "hd";
    public static final String EXTRA_FTP = "ftp";
    public static final String EXTRA_SYNC = "sync";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_FTP_HINT = "ftp_hint";
    public static final String EXTRA_FILE_COUNT = "file_count";
    public static final String EXTRA_LAST_SYNC = "last_sync";
    public static final String EXTRA_MOUNTED = "mounted";
    public static final String EXTRA_EJECTED = "ejected";
    public static final String EXTRA_FTP_RUNNING = "ftp_running";
    public static final String EXTRA_SELECTED = "selected";
    public static final String EXTRA_SYNCING = "syncing";
    public static final String EXTRA_PAUSED = "paused";
    public static final String EXTRA_STORAGE_PERCENT = "storage_percent";
    public static final String EXTRA_STORAGE_LABEL = "storage_label";

    private static final String TAG = "VesperaPhotos";
    private static final int NOTIFICATION_ID = 43;
    private static final int AUTO_SYNC_ALARM_REQ = 44;
    /** HD/FTP housekeeping only — auto-sync is scheduled separately. */
    private static final long TICK_MS = 120_000;
    private static final long STATUS_WATCH_MS = 20_000L;
    /** Occupied percent of Vespera internal storage (FTP /USER) that starts a photo sync. */
    static final int STORAGE_SYNC_PERCENT = 50;
    /** Occupied percent of the mounted USB HD that shows the disk warning. */
    static final int HD_WARNING_PERCENT = 80;
    private static final long STORAGE_SYNC_COOLDOWN_MS = 10 * 60_000L;
    private static final long AUTO_RETRY_MS = 5 * 60_000L;
    private static final long MIN_AUTO_DELAY_MS = 5_000L;
    private static final long SUN_TOO_HIGH_RETRY_MS = 10 * 60_000L;
    private static final long OBS_SUN_SHUTDOWN_COOLDOWN_MS = 2 * 60_000L;
    private static final long PI_SHUTDOWN_DELAY_MS = 3_000L;
    /** Gap between automatic observation resumes after an astrometry stop. */
    private static final long AUTO_RESUME_COOLDOWN_MS = 4 * 60_000L;
    private static final int AUTO_RESUME_MAX = 20;
    private static final int[] STORAGE_CHECK_MINUTES = {10, 11};

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final Object syncLock = new Object();
    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
    private final AtomicBoolean sunShutdownRunning = new AtomicBoolean(false);
    private final AtomicBoolean autoResumeRunning = new AtomicBoolean(false);
    private long lastAutoResumeAt;
    private int autoResumeAttempts;
    private String autoResumeSession = "";
    /** Morning photo sync only — must not block observation-sun shutdown. */
    private final AtomicBoolean morningSyncRunning = new AtomicBoolean(false);
    private final SimpleFtpServer ftpServer = new SimpleFtpServer();
    private final FtpProxyServer telescopeFtp = new FtpProxyServer();
    private int vesperaFtpPort = -1;
    private SyncProgressHud hud;
    private UsbHdStore hdStore;
    private PhotoSyncStore syncStore;
    private boolean mounted;
    private boolean ejected;
    private boolean emptyAfterEject;
    private String mountLabel = "";
    private String selectedSpec = "";
    private String[] disksEncoded = new String[0];
    private String hdStatus = "";
    private String ftpStatus = "";
    private String syncStatus = "";
    private String lastSync = "";
    private String message = "";
    private int fileCount;
    private volatile boolean syncing;
    /** True after Copia ora / Continua until maybeAutoSync takes the lock. */
    private volatile boolean pendingForceSync;
    private boolean userUnmounted;
    /** USB VBUS cut (manual «Spegni HD» or morning option). Stays off until Attiva HD. */
    private volatile boolean autoPoweredOff;
    /** Serializes mount/wake against USB power-off (morning thread vs. the 2 min tick). */
    private final Object hdLock = new Object();
    /** User pressed Attiva/Monta — do not auto power-off until app-open / shutdown policy. */
    private boolean manualHdWake;
    /** One-shot launch power-off; further ensure()/BOOTSTRAP must not re-eject after Attiva HD. */
    private boolean hdLaunchPowerOffDone;
    private long lastNotifyAt;
    private volatile SyncProgress lastProgress;
    /** Istanza viva, per lo stato sync esposto da {@link RemoteBridge}. */
    private static volatile PhotoSyncService current;
    private String lastBroadcastPhase = "";
    private volatile long extraAutoDelayMs;
    private long lastStorageSyncAt;
    private long lastObsSunAttemptAt;
    /** Last CONNECTED/other status seen, so photo sync runs only on a rising edge. */
    private volatile boolean vesperaWasConnected;
    private boolean connectionReceiverRegistered;
    private boolean clockReceiverRegistered;
    private TelescopeStatusHub statusHub;
    private TelegramNotifier telegramNotifier;
    private final Object statusPollLock = new Object();
    private volatile boolean statusWatchStop;
    private Thread statusWatchThread;
    private final TelescopeStatusHub.Listener statusListener = this::onTelescopeStatusEvent;
    private final BroadcastReceiver connectionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(VesperaConnectionService.EXTRA_STATUS);
            if (VesperaConnectionService.STATUS_CONNECTED.equals(status)) {
                extraAutoDelayMs = 0;
                boolean becameConnected = !vesperaWasConnected;
                vesperaWasConnected = true;
                TelescopeStatusHub.ensure().ingestConnection(true);
                wakeStatusWatch();
                worker.execute(() -> {
                    // Remount only when the instrument answers — SSID alone is not enough.
                    Network net = resolveVesperaNetwork();
                    boolean instrumentUp = canReachVesperaFtp(net)
                            || InstrumentWatchdog.probeApiPort(PhotoSyncService.this, net, true) > 0;
                    // Do not clear a USB power-off here: waking VBUS right after a cut
                    // often leaves the enclosure unusable until the cable is replugged.
                    if (instrumentUp && !userUnmounted && !autoPoweredOff) {
                        ensureMountedForSync();
                    }
                    refreshTelescopeFtpLocked();
                    publish();
                });
                if (becameConnected) {
                    syncExecutor.execute(PhotoSyncService.this::syncOnVesperaOnline);
                }
            } else if (isRequestingStatus(status)) {
                // Associating with the AP — do not treat this as "Vespera offline".
            } else if (isWifiGoneStatus(status)) {
                vesperaWasConnected = false;
                TelescopeStatusHub.ensure().ingestConnection(false);
                worker.execute(() -> {
                    telescopeFtp.stop();
                    // Keep HD USB powered when Vespera Wi‑Fi drops — auto VBUS cut
                    // often requires physically unplugging before remount works.
                    refreshFtpStatus();
                    publish();
                });
            }
        }
    };
    /** Reschedule RTC alarm after manual/NTP clock or timezone changes. */
    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_TIME_CHANGED.equals(action)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                scheduleNextAutoSync();
            }
        }
    };
    private final Runnable autoSyncAlarm = () -> syncExecutor.execute(
            () -> maybeAutoSync(false, SystemActivityLog.KIND_PHOTO_SYNC));
    private final Runnable hourlyStorageCheck = () -> worker.execute(this::maybeCheckInternalStorage);
    private final Runnable sunTooHighAlarm = () -> {
        Thread t = new Thread(this::maybeCheckSunTooHigh, "sun-too-high");
        t.start();
    };
    /** Periodic Automatic Weather Protection check (System tab). Reschedules itself. */
    private final Runnable weatherAlarm = new Runnable() {
        @Override public void run() {
            new Thread(() -> WeatherProtectionEngine.runCheck(PhotoSyncService.this),
                    "weather-protection").start();
            WeatherProtectionStore store = WeatherProtectionStore.from(PhotoSyncService.this);
            if (store.enabled()) {
                mainHandler.postDelayed(this, weatherCheckIntervalMs(store));
            }
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
        worker.execute(() -> {
            refreshClockAndSun(false);
            refreshMountStatus();
            maybeAutoMount(); // no-op while the HD is powered off
            refreshTelescopeFtpLocked();
            publish();
            if (syncStore != null
                    && SystemSettingsStore.from(PhotoSyncService.this).photoSync()
                    && !syncStore.paused()
                    && syncStore.isAutoDue(System.currentTimeMillis())) {
                syncExecutor.execute(() -> maybeAutoSync(false, SystemActivityLog.KIND_PHOTO_SYNC));
            }
            pollTelescopeStatusLocked();
            if (telegramNotifier != null) telegramNotifier.flushQueue();
        });
        mainHandler.postDelayed(this, TICK_MS);
        }
    };

    /** Ultimo avanzamento sync (null se il servizio non gira o non ha mai sincronizzato). */
    static SyncProgress currentProgress() {
        PhotoSyncService s = current;
        return s == null ? null : s.lastProgress;
    }

    static boolean isSyncRunning() {
        PhotoSyncService s = current;
        return s != null && s.isCopyUiActive();
    }

    static String currentLastSync() {
        PhotoSyncService s = current;
        return s == null ? "" : s.lastSync;
    }

    public static void ensure(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_BOOTSTRAP));
    }

    /** Call from MainActivity.onCreate: always re-evaluate HD power on UI open. */
    public static void onAppOpened(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_APP_OPEN));
    }

    public static void start(Context context) {
        ensure(context);
    }

    public static List<UsbDisk> parseDisks(String blob) {
        return UsbDisk.parseList(blob);
    }

    public static void refreshDisks(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_REFRESH_DISKS));
    }

    public static void mount(Context context, String spec) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_MOUNT).putExtra(EXTRA_SPEC, spec));
    }

    public static void unmount(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_UNMOUNT));
    }

    public static void eject(Context context, String spec) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_EJECT).putExtra(EXTRA_SPEC, spec));
    }

    public static void syncNow(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_SYNC_NOW));
    }

    public static void syncIfStorageHigh(Context context, int usedPercent) {
        if (context == null || usedPercent < STORAGE_SYNC_PERCENT) return;
        if (!SystemSettingsStore.from(context).storageSync()) return;
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_SYNC_STORAGE));
    }

    public static void resumeSync(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_RESUME));
    }

    public static void pauseSync(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_PAUSE));
    }

    /** Stop the current transfer; the next scheduled slot resumes it. */
    public static void pauseUntilSchedule(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_PAUSE_UNTIL_SCHEDULE));
    }

    public static void hideWindow(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_HIDE_WINDOW));
    }

    public static void showWindow(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_SHOW_WINDOW));
    }

    public static void forget(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_FORGET));
    }

    public static void applySettings(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_APPLY_SETTINGS));
    }

    /** Copy USER photos, then send the telescope shutdown command. */
    public static void syncThenShutdown(Context context) {
        context.startForegroundService(new Intent(context, PhotoSyncService.class)
                .setAction(ACTION_SYNC_THEN_SHUTDOWN));
    }

    @Override public void onCreate() {
        super.onCreate();
        hdStore = UsbHdStore.from(this);
        // A previous USB cut cannot be undone from the Pi. Do not keep the disk locked off.
        if (hdStore.isPoweredOff()) hdStore.setPoweredOff(false);
        autoPoweredOff = false;
        syncStore = PhotoSyncStore.from(this);
        selectedSpec = hdStore.getSpec();
        if (selectedSpec.isEmpty()) selectedSpec = UsbDiskStore.from(this).getId();
        Context localized = AppLocale.wrap(this);
        NotificationChannel channel = new NotificationChannel(
                "vespera_photos",
                localized.getString(R.string.photos_notification_title),
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        hdStatus = localized.getString(R.string.photos_hd_unmounted);
        ftpStatus = localized.getString(R.string.photos_ftp_off);
        syncStatus = localized.getString(R.string.photos_sync_idle);
        hud = new SyncProgressHud(this);
        current = this;
        startAsForeground();
        statusHub = TelescopeStatusHub.ensure();
        telegramNotifier = new TelegramNotifier(this);
        statusHub.addListener(statusListener);
        statusHub.addListener(telegramNotifier);
        if (VesperaConnectionService.STATUS_CONNECTED.equals(VesperaConnectionService.getLastStatus())) {
            vesperaWasConnected = true;
            statusHub.ingestConnection(true);
        }
        if (!connectionReceiverRegistered) {
            IntentFilter filter = new IntentFilter(VesperaConnectionService.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(connectionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(connectionReceiver, filter);
            }
            connectionReceiverRegistered = true;
        }
        if (!clockReceiverRegistered) {
            IntentFilter clockFilter = new IntentFilter();
            clockFilter.addAction(Intent.ACTION_TIME_CHANGED);
            clockFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(clockReceiver, clockFilter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(clockReceiver, clockFilter);
            }
            clockReceiverRegistered = true;
        }
        worker.execute(() -> {
            refreshClockAndSun(false);
            refreshMountStatus();
            bootstrapHdForConnectionLocked();
            if (mounted) startFtpLocked();
            refreshTelescopeFtpLocked();
            listDisksLocked();
            publish();
            syncExecutor.execute(PhotoSyncService.this::resumeSuspendedIfNeeded);
        });
        mainHandler.postDelayed(tick, TICK_MS);
        startStatusWatch();
        scheduleNextAutoSync();
        scheduleHourlyStorageCheck();
        scheduleSunTooHighCheck();
        scheduleWeatherCheck();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        String action = intent == null ? null : intent.getAction();
        if (ACTION_BOOTSTRAP.equals(action) || action == null) {
            worker.execute(() -> {
                refreshClockAndSun(false);
                refreshMountStatus();
                bootstrapHdForConnectionLocked();
                if (mounted) startFtpLocked();
                refreshTelescopeFtpLocked();
                listDisksLocked();
                publish();
                syncExecutor.execute(PhotoSyncService.this::resumeSuspendedIfNeeded);
            });
            scheduleNextAutoSync();
            scheduleSunTooHighCheck();
            scheduleWeatherCheck();
        } else if (ACTION_APP_OPEN.equals(action)) {
            worker.execute(() -> {
                refreshClockAndSun(false);
                // Re-evaluate HD policy without an immediate eject: opening the UI
                // used to spin the disk down while Vespera was still associating.
                appOpenHdPolicyLocked();
                if (mounted) startFtpLocked();
                else stopFtpLocked();
                refreshTelescopeFtpLocked();
                listDisksLocked();
                publish();
            });
            scheduleNextAutoSync();
            scheduleSunTooHighCheck();
        } else if (ACTION_REFRESH_DISKS.equals(action) || ACTION_LIST_DISKS.equals(action)) {
            worker.execute(() -> {
                refreshClockAndSun(false);
                // Do NOT wake USB here: listing after power-off would turn the HD back on.
                // Wake only happens on Monta / Attiva HD (mount-disk).
                listDisksLocked();
                refreshMountStatus();
                publish();
                scheduleNextAutoSync();
            });
        } else if (ACTION_MOUNT.equals(action)) {
            String spec = intent.getStringExtra(EXTRA_SPEC);
            if (spec == null || spec.isEmpty()) spec = intent.getStringExtra(EXTRA_DISK_ID);
            final String mountSpec = spec;
            worker.execute(() -> mountLocked(mountSpec));
        } else if (ACTION_UNMOUNT.equals(action)) {
            worker.execute(this::unmountLocked);
        } else if (ACTION_EJECT.equals(action)) {
            // USB power-off is disabled: the Pi cannot turn the enclosure back on.
            Log.i(TAG, "HD power-off ignored (checkbox only)");
        } else if (ACTION_SYNC_NOW.equals(action)) {
            pauseRequested.set(false);
            if (syncStore != null) {
                syncStore.setPaused(false);
                syncStore.setPauseUntilSchedule(false);
            }
            beginForcedSyncUi();
            syncExecutor.execute(() -> maybeAutoSync(true, null));
        } else if (ACTION_SYNC_STORAGE.equals(action)) {
            syncExecutor.execute(this::maybeSyncForFullStorage);
        } else if (ACTION_RESUME.equals(action)) {
            pauseRequested.set(false);
            if (syncStore != null) {
                syncStore.setPaused(false);
                syncStore.setPauseUntilSchedule(false);
            }
            beginForcedSyncUi();
            syncExecutor.execute(() -> maybeAutoSync(true, null));
        } else if (ACTION_PAUSE.equals(action)) {
            pauseRequested.set(true);
            if (syncStore != null) {
                syncStore.markInterrupted(this);
                syncStore.setPaused(true);
            }
            if (hud != null) hud.hideByUser();
            mainHandler.removeCallbacks(autoSyncAlarm);
            cancelRtcAlarm();
            publish();
        } else if (ACTION_PAUSE_UNTIL_SCHEDULE.equals(action)) {
            pauseRequested.set(true);
            if (syncStore != null) {
                syncStore.markInterrupted(this);
                syncStore.setPauseUntilSchedule(true);
            }
            publish();
        } else if (ACTION_HIDE_WINDOW.equals(action)) {
            if (hud != null) hud.hideByUser();
        } else if (ACTION_SHOW_WINDOW.equals(action)) {
            if (hud != null) hud.show();
        } else if (ACTION_FORGET.equals(action)) {
            worker.execute(this::forgetLocked);
        } else if (ACTION_SYNC_CLOCK.equals(action)) {
            worker.execute(() -> {
                refreshClockAndSun(true);
                publish();
                scheduleNextAutoSync();
                scheduleSunTooHighCheck();
            });
        } else if (ACTION_APPLY_SETTINGS.equals(action)) {
            worker.execute(this::applySystemSettingsLocked);
            mainHandler.post(this::rescheduleFromSettings);
        } else if (ACTION_AUTO_SYNC.equals(action)) {
            syncExecutor.execute(() -> maybeAutoSync(false, SystemActivityLog.KIND_PHOTO_SYNC));
        } else if (ACTION_SYNC_THEN_SHUTDOWN.equals(action)) {
            pauseRequested.set(false);
            if (syncStore != null) {
                syncStore.setPaused(false);
                syncStore.setPauseUntilSchedule(false);
            }
            beginForcedSyncUi();
            syncExecutor.execute(() -> lastSyncThenShutdown(null, null, -1));
        }
        return START_STICKY;
    }

    private void startAsForeground() {
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification notification() {
        Context localized = AppLocale.wrap(this);
        String hd = hdStatus == null || hdStatus.isEmpty()
                ? localized.getString(R.string.photos_hd_unmounted) : hdStatus;
        String ftp = ftpServer.isRunning()
                ? localized.getString(R.string.photos_ftp_on, SimpleFtpServer.PORT)
                : localized.getString(R.string.photos_ftp_off);
        String sync = syncStatus == null || syncStatus.isEmpty()
                ? localized.getString(R.string.photos_sync_idle) : syncStatus;
        Intent popup = new Intent(this, SyncProgressActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent content = PendingIntent.getActivity(this, 0, popup,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, "vespera_photos")
                .setSmallIcon(R.drawable.ic_vespera_notification)
                .setContentTitle(syncing
                        ? localized.getString(R.string.photo_sync_popup_title)
                        : localized.getString(R.string.photos_notification_title))
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (syncing && lastProgress != null) {
            builder.setContentText(lastProgress.compactStatus())
                    .setProgress(1000, lastProgress.permille(), false)
                    .setStyle(new Notification.BigTextStyle().bigText(
                            SyncProgress.phaseLabel(localized, lastProgress.phase)
                                    + "\n" + lastProgress.compactStatus()
                                    + "\n" + localized.getString(R.string.photo_sync_eta,
                                    SyncProgress.formatEta(lastProgress.etaMs))));
        } else {
            builder.setContentText(localized.getString(R.string.photos_notification_text, hd, ftp, sync))
                    .setProgress(0, 0, false);
        }
        return builder.build();
    }

    private void listDisksLocked() {
        Context localized = AppLocale.wrap(this);
        message = localized.getString(R.string.photos_scanning);
        publish();
        String raw = DaemonDisk.listRaw(this);
        List<UsbDisk> disks = UsbDisk.parseList(raw);
        List<String> encoded = new ArrayList<>();
        if (disks != null) {
            for (UsbDisk disk : disks) encoded.add(disk.encode());
        }
        disksEncoded = encoded.toArray(new String[0]);
        if (ejected && !mounted) {
            if (disks == null || disks.isEmpty()) {
                emptyAfterEject = true;
            } else if (emptyAfterEject) {
                ejected = false;
                emptyAfterEject = false;
            }
        }
        if (ejected && !mounted) {
            message = localized.getString(R.string.photos_eject_ok);
        } else if (raw == null) {
            message = localized.getString(R.string.photos_daemon_timeout);
        } else if (disks == null || disks.isEmpty()) {
            message = localized.getString(R.string.photos_none);
        } else {
            message = localized.getString(R.string.photos_found, disks.size());
            if (selectedSpec.isEmpty() && hdStore.isConfigured()) {
                selectedSpec = hdStore.getSpec();
            }
        }
        refreshMountStatus();
        if (!userUnmounted && !autoPoweredOff) {
            bindListedDiskIfNeeded();
            if (!mounted) maybeAutoMount();
        }
    }

    /** Wi‑Fi to Vespera plus API or FTP answering (not SSID alone). */
    private boolean isVesperaInstrumentUp() {
        if (!isVesperaConnected()) return false;
        Network net = resolveVesperaNetwork();
        return canReachVesperaFtp(net)
                || InstrumentWatchdog.probeApiPort(this, net, true) > 0;
    }

    private static boolean isRequestingStatus(String status) {
        return status != null
                && status.startsWith(VesperaConnectionService.STATUS_REQUESTING);
    }

    private static boolean isWifiGoneStatus(String status) {
        return VesperaConnectionService.STATUS_DISCONNECTED.equals(status)
                || VesperaConnectionService.STATUS_LOST.equals(status);
    }

    /** Wi‑Fi to the Vespera AP is up or being acquired — keep the HD spinning. */
    private boolean isVesperaWifiPresent() {
        if (isVesperaConnected()) return true;
        if (resolveVesperaNetwork() != null) return true;
        String status = VesperaConnectionService.getLastStatus();
        return isRequestingStatus(status)
                || VesperaConnectionService.STATUS_CONNECTED.equals(status);
    }

    private void mountLocked(String spec) {
        synchronized (hdLock) {
        Context localized = AppLocale.wrap(this);
        String use = spec == null || spec.trim().isEmpty() ? selectedSpec : spec.trim();
        if (use.isEmpty()) use = hdStore.getSpec();
        if (use.isEmpty()) {
            message = localized.getString(R.string.photos_select_first);
            publish();
            return;
        }
        selectedSpec = use;
        userUnmounted = false;
        autoPoweredOff = false;
        hdStore.setPoweredOff(false);
        manualHdWake = true;
        ejected = false;
        emptyAfterEject = false;
        hdLaunchPowerOffDone = true; // manual Attiva must survive later ensure()/BOOTSTRAP
        message = localized.getString(R.string.photo_hd_activating,
                hdStore.displayName().isEmpty() ? use : hdStore.displayName());
        publish();
        DaemonDisk.MountStatus status = DaemonDisk.mount(this, use);
        applyMountStatus(status);
        if (status.timeout) {
            message = localized.getString(R.string.photos_daemon_timeout);
        } else if (status.mounted) {
            hdStore.rememberMount(status.uuid, status.label, status.device);
            UsbDisk listed = findDisk(use);
            if (listed != null) {
                hdStore.saveMounted(listed);
                UsbDiskStore.from(this).save(listed);
            } else {
                UsbDiskStore.from(this).saveId(use, status.device, status.label, status.uuid);
            }
            message = localized.getString(R.string.photos_mount_ok, hdStore.displayName());
            startFtpLocked();
            extraAutoDelayMs = 0;
            scheduleNextAutoSync();
        } else if (status.raw != null && status.raw.startsWith("mount-unsupported-ntfs")) {
            message = localized.getString(R.string.photos_mount_ntfs);
            stopFtpLocked();
        } else {
            message = localized.getString(R.string.photos_mount_fail, status.raw);
            stopFtpLocked();
        }
        listDisksLocked();
        publish();
        }
    }

    private void unmountLocked() {
        synchronized (hdLock) {
        Context localized = AppLocale.wrap(this);
        stopFtpLocked();
        userUnmounted = true;
        autoPoweredOff = false;
        hdStore.setPoweredOff(false);
        DaemonDisk.MountStatus status = DaemonDisk.unmount(this, selectedSpec);
        applyMountStatus(status);
        if (status.timeout) {
            message = localized.getString(R.string.photos_daemon_timeout);
        } else if (!status.mounted) {
            message = localized.getString(R.string.photos_unmount_ok);
        } else {
            userUnmounted = false;
            message = localized.getString(R.string.photos_unmount_fail, status.raw);
        }
        listDisksLocked();
        publish();
        }
    }

    private void ejectLocked(String spec) {
        Log.i(TAG, "HD eject ignored (checkbox only) spec=" + spec);
    }

    private void forgetLocked() {
        Context localized = AppLocale.wrap(this);
        autoPoweredOff = false;
        ejected = false;
        hdStore.clear();
        selectedSpec = "";
        message = localized.getString(R.string.photos_forget_ok);
        publish();
    }

    /**
     * At first service start: remount the saved HD if auto-mount is on.
     * Does not cut USB power (that is morning-check / manual only). Later
     * {@code ensure()}/BOOTSTRAP calls only refresh after the first pass.
     */
    private void bootstrapHdForConnectionLocked() {
        if (!SystemSettingsStore.from(this).hdMount()) {
            refreshMountStatus();
            return;
        }
        if (hdLaunchPowerOffDone) {
            refreshMountStatus();
            return;
        }
        hdLaunchPowerOffDone = true;
        Log.i(TAG, "bootstrap: remount HD if present");
        applyHdOfflineOrOnlineLocked();
    }

    /** MainActivity opened: re-evaluate HD mount without cutting USB power. */
    private void appOpenHdPolicyLocked() {
        if (!SystemSettingsStore.from(this).hdMount()) {
            refreshMountStatus();
            return;
        }
        Log.i(TAG, "app-open: remount HD if present");
        applyHdOfflineOrOnlineLocked();
        hdLaunchPowerOffDone = true;
    }

    private void applyHdOfflineOrOnlineLocked() {
        synchronized (hdLock) {
            if (userUnmounted || autoPoweredOff) {
                refreshMountStatus();
                return;
            }
            // Mount whenever the disk is present — do not wait for Vespera online.
            // USB power-off is only morning-check (optional, off by default) or manual «Spegni HD».
            ejected = false;
            Log.i(TAG, "HD policy: remount saved disk (Vespera wifi="
                    + isVesperaWifiPresent() + ")");
            bootstrapMountLocked();
        }
    }

    private void bootstrapMountLocked() {
        refreshMountStatus();
        if (DaemonDisk.isPhotosBoundLive(DaemonDisk.photosDir(this))) return;
        Context localized = AppLocale.wrap(this);
        message = localized.getString(R.string.photo_hd_auto_mount,
                hdStore.displayName().isEmpty() ? "HD" : hdStore.displayName());
        publish();
        maybeAutoMount();
        if (!mounted) {
            listDisksLocked();
            maybeAutoMount();
        }
    }

    /** USB power-off is disabled: the Pi cannot turn the enclosure back on. */
    private void powerOffHdLocked(int messageRes) {
        Log.i(TAG, "HD power-off ignored (checkbox only) msg=" + messageRes);
    }

    private void maybeAutoMount() {
        synchronized (hdLock) {
        if (!SystemSettingsStore.from(this).hdMount()) return;
        if (userUnmounted || autoPoweredOff) return;
        // Mount even with Vespera offline. After a USB power-off, stay off
        // until Attiva HD — an automatic wake often needs a cable replug.
        if (DaemonDisk.isPhotosBoundLive(DaemonDisk.photosDir(this))) {
            if (!mounted) refreshMountStatus();
            return;
        }
        String spec = hdStore.getSpec();
        if (spec.isEmpty()) spec = UsbDiskStore.from(this).getId();
        if (spec.isEmpty() && disksEncoded != null && disksEncoded.length == 1) {
            UsbDisk only = UsbDisk.parse(disksEncoded[0]);
            if (only != null) spec = only.id();
        }
        if (spec.isEmpty()) return;
        selectedSpec = spec;
        DaemonDisk.MountStatus status = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (autoPoweredOff) return;
            status = DaemonDisk.mount(this, spec);
            if (status != null && status.mounted) break;
            if (status != null && !status.timeout
                    && status.raw != null && status.raw.startsWith("mount-unsupported")) {
                break;
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (status == null) return;
        boolean wasMounted = mounted;
        applyMountStatus(status);
        if (status.mounted) {
            ejected = false;
            emptyAfterEject = false;
            hdStore.rememberMount(status.uuid, status.label, status.device);
            UsbDisk listed = findDisk(spec);
            if (listed != null) {
                hdStore.saveMounted(listed);
                UsbDiskStore.from(this).save(listed);
            } else {
                UsbDiskStore.from(this).saveId(spec, status.device, status.label, status.uuid);
            }
            startFtpLocked();
            Context localized = AppLocale.wrap(this);
            message = localized.getString(R.string.photos_mount_ok, hdStore.displayName());
            extraAutoDelayMs = 0;
            scheduleNextAutoSync();
            if (!wasMounted) {
                SystemActivityLog.record(this, SystemActivityLog.KIND_HD_MOUNT,
                        SystemActivityLog.DETAIL_OK);
            }
        } else if (status.timeout) {
            Context localized = AppLocale.wrap(this);
            message = localized.getString(R.string.photos_daemon_timeout);
        }
        }
    }

    private void refreshMountStatus() {
        synchronized (hdLock) {
        // While intentionally powered off, never ensure-bind (that would remount/wake the HD).
        if (autoPoweredOff) {
            // Do not eject again: that cuts USB and the Pi cannot power the disk back on.
            DaemonDisk.MountStatus status = DaemonDisk.status(this);
            if (status == null || status.mounted) {
                status = DaemonDisk.MountStatus.unmounted("powered-off");
            }
            applyMountStatus(status);
            mounted = false;
            ejected = true;
            stopFtpLocked();
            return;
        }
        DaemonDisk.MountStatus status = DaemonDisk.status(this);
        if (status != null && status.mounted && !DaemonDisk.isPhotosBoundLive(DaemonDisk.photosDir(this))) {
            status = DaemonDisk.ensureBind(this);
        }
        applyMountStatus(status);
        if (mounted) startFtpLocked();
        else stopFtpLocked();
        }
    }

    private void applyMountStatus(DaemonDisk.MountStatus status) {
        Context localized = AppLocale.wrap(this);
        mounted = status != null && status.mounted && !status.timeout;
        if (mounted) {
            ejected = false;
            emptyAfterEject = false;
            mountLabel = !"-".equals(status.label) ? status.label
                    : (!"-".equals(status.uuid) ? status.uuid : status.device);
            hdStatus = localized.getString(R.string.photos_hd_mounted, mountLabel);
            fileCount = PhotoSyncEngine.countLocalPhotos(DaemonDisk.photosDir(this));
        } else if (hdStore.isAutoMount()) {
            hdStatus = localized.getString(R.string.photos_hd_waiting);
            fileCount = 0;
        } else {
            hdStatus = localized.getString(R.string.photos_hd_unmounted);
            fileCount = 0;
        }
        refreshSyncHint();
        refreshFtpStatus();
    }

    private void refreshClockAndSun(boolean forceNtp) {
        if (syncStore == null || !syncStore.hasSite()) return;
        if (!SystemSettingsStore.from(this).clockNtp()) return;
        SiteClock.Result result = SiteClock.sync(this, syncStore, forceNtp);
        if (result.ntpAttempted) {
            String detail = result.ntpOk
                    ? SystemActivityLog.DETAIL_OK : SystemActivityLog.DETAIL_FAIL;
            // One failed NTP line until it succeeds — hourly retries must not fill the log.
            if (forceNtp || result.ntpOk
                    || !SystemActivityLog.lastKindIs(this, SystemActivityLog.KIND_CLOCK_NTP, detail)) {
                SystemActivityLog.record(this, SystemActivityLog.KIND_CLOCK_NTP, detail);
            }
        }
        if (result.hoursChanged || result.ntpOk) {
            Log.i(TAG, "clock tz=" + result.timeZoneId
                    + " ntp=" + result.ntpOk
                    + " hours=" + syncStore.dayStartHour() + "-" + syncStore.dayEndHour());
            mainHandler.post(this::scheduleNextAutoSync);
            mainHandler.post(this::scheduleSunTooHighCheck);
        }
    }

    private void refreshSyncHint() {
        Context localized = AppLocale.wrap(this);
        if (syncing) {
            syncStatus = localized.getString(R.string.photos_sync_running);
            return;
        }
        if (!SystemSettingsStore.from(this).photoSync()) {
            syncStatus = localized.getString(R.string.system_activity_off);
            return;
        }
        if (syncStore != null && syncStore.paused()) {
            syncStatus = localized.getString(R.string.photo_sync_paused);
            return;
        }
        if (syncStore != null && syncStore.pauseUntilSchedule()) {
            long nextAt = syncStore.nextAutoAt(System.currentTimeMillis());
            syncStatus = localized.getString(R.string.photo_sync_paused_until_next,
                    formatClock(nextAt));
            return;
        }
        if (!mounted) {
            syncStatus = localized.getString(R.string.photos_sync_need_hd);
            return;
        }
        if (!isVesperaConnected()) {
            syncStatus = localized.getString(R.string.photos_sync_need_vespera);
            return;
        }
        long now = System.currentTimeMillis();
        long nextAt = syncStore.nextAutoAt(now);
        String nextNightEnd = formatClock(syncStore.nextNightEndAt(now));
        int label = (syncStore.lastAt() > 0 || syncStore.lastAttemptAt() > 0) && nextAt <= now
                ? R.string.photos_sync_overdue
                : R.string.photos_sync_next;
        syncStatus = localized.getString(label,
                formatClock(nextAt),
                PhotoSyncStore.formatIntervalHours(syncStore.nightIntervalHours()),
                nextNightEnd);
    }

    private String formatClock(long timeMs) {
        Calendar calendar = Calendar.getInstance(
                syncStore != null ? syncStore.zone() : java.util.TimeZone.getDefault());
        calendar.setTimeInMillis(timeMs);
        return String.format(java.util.Locale.US, "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
    }

    private void scheduleNextAutoSync() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::scheduleNextAutoSync);
            return;
        }
        if (!SystemSettingsStore.from(this).photoSync()
                || syncStore == null || syncStore.paused()) {
            mainHandler.removeCallbacks(autoSyncAlarm);
            cancelRtcAlarm();
            return;
        }
        synchronized (syncLock) {
            if (syncing) return;
        }
        mainHandler.removeCallbacks(autoSyncAlarm);
        long now = System.currentTimeMillis();
        long extra = extraAutoDelayMs;
        extraAutoDelayMs = 0;
        long slotAt = syncStore.nextAutoAt(now);
        long fireAt = Math.max(slotAt, now + extra);
        long delay = fireAt - now;
        if (delay < MIN_AUTO_DELAY_MS) {
            if (fireAt <= now) {
                delay = MIN_AUTO_DELAY_MS;
                fireAt = now + delay;
            } else {
                delay = Math.max(delay, 0L);
            }
        }
        mainHandler.postDelayed(autoSyncAlarm, delay);
        setRtcAlarm(fireAt);
        Log.i(TAG, "next auto-sync in " + (delay / 1000L) + "s at "
                + formatClock(fireAt)
                + (syncStore.clockLooksWrong(now) ? " (clock behind stored stamps)" : ""));
    }

    /**
     * Wall-clock wakeup for the next :00 slot. Handler.postDelayed uses uptime,
     * which freezes in sleep and then leaves the UI stuck on a past hour.
     */
    private void setRtcAlarm(long atMs) {
        AlarmManager alarm = getSystemService(AlarmManager.class);
        if (alarm == null) return;
        PendingIntent pending = autoSyncPendingIntent();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending);
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending);
            }
        } catch (SecurityException ignored) {
            try {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending);
            } catch (Exception failure) {
                Log.w(TAG, "rtc auto-sync alarm", failure);
            }
        } catch (Exception failure) {
            Log.w(TAG, "rtc auto-sync alarm", failure);
        }
    }

    private void cancelRtcAlarm() {
        AlarmManager alarm = getSystemService(AlarmManager.class);
        if (alarm == null) return;
        try {
            alarm.cancel(autoSyncPendingIntent());
        } catch (Exception ignored) {
        }
    }

    private PendingIntent autoSyncPendingIntent() {
        Intent intent = new Intent(this, PhotoSyncService.class).setAction(ACTION_AUTO_SYNC);
        return PendingIntent.getForegroundService(this, AUTO_SYNC_ALARM_REQ, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void scheduleHourlyStorageCheck() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::scheduleHourlyStorageCheck);
            return;
        }
        mainHandler.removeCallbacks(hourlyStorageCheck);
        if (!SystemSettingsStore.from(this).storageSync()) return;
        long delay = delayUntilNextStorageSlot();
        mainHandler.postDelayed(hourlyStorageCheck, delay);
        Log.i(TAG, "next internal-storage check in " + (delay / 1000L) + "s");
    }

    private void scheduleSunTooHighCheck() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::scheduleSunTooHighCheck);
            return;
        }
        mainHandler.removeCallbacks(sunTooHighAlarm);
        if (!SystemSettingsStore.from(this).sunCheck()) return;
        long delay = delayUntilSunTooHighCheck();
        if (delay < 0) delay = 60 * 60_000L;
        if (delay < MIN_AUTO_DELAY_MS) delay = MIN_AUTO_DELAY_MS;
        mainHandler.postDelayed(sunTooHighAlarm, delay);
        Log.i(TAG, "next sun-too-high check in " + (delay / 1000L) + "s");
    }

    private long delayUntilSunTooHighCheck() {
        if (syncStore == null || !syncStore.hasSite()) return -1;
        long now = System.currentTimeMillis();
        SystemSettingsStore settings = SystemSettingsStore.from(this);
        int today = syncStore.dayKey(now);
        boolean retryToday = settings.sunTooHighDay() == today
                && SystemSettingsStore.sunTooHighNeedsRetry(settings.sunTooHighResult());
        long close = syncStore.sunTooHighWindowCloseAt(now);
        // Past the morning window: wake soon so maybeCheck can close the day
        // (no afternoon/evening shutdown on reconnect).
        if (retryToday && close > 0 && now >= close) return MIN_AUTO_DELAY_MS;
        if (retryToday && (close <= 0 || now < close)) return MIN_AUTO_DELAY_MS;
        long at = syncStore.nextSunTooHighCheckAt(settings.sunTooHighDay(), now);
        if (at <= 0) return -1;
        long delay = at - now;
        return delay < MIN_AUTO_DELAY_MS ? MIN_AUTO_DELAY_MS : delay;
    }

    private void maybeCheckSunTooHigh() {
        SystemSettingsStore settings = SystemSettingsStore.from(this);
        if (!settings.sunCheck() || syncStore == null || !syncStore.hasSite()) {
            scheduleSunTooHighCheck();
            return;
        }
        long now = System.currentTimeMillis();
        int today = syncStore.dayKey(now);
        long window = syncStore.sunTooHighCheckAt(now);
        if (window <= 0 || now < window) {
            scheduleSunTooHighCheck();
            return;
        }
        if (settings.sunTooHighDay() == today
                && !SystemSettingsStore.sunTooHighNeedsRetry(settings.sunTooHighResult())) {
            scheduleSunTooHighCheck();
            return;
        }
        long close = syncStore.sunTooHighWindowCloseAt(now);
        if (close > 0 && now >= close) {
            Log.i(TAG, "morning sync: window closed — skip until tomorrow");
            settings.recordSunTooHigh(today, SystemSettingsStore.SUN_RESULT_WINDOW_CLOSED);
            if (!SystemActivityLog.lastKindIs(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                    SystemActivityLog.DETAIL_WINDOW_CLOSED)) {
                SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                        SystemActivityLog.DETAIL_WINDOW_CLOSED);
            }
            scheduleSunTooHighCheck();
            return;
        }
        // Morning window: photo sync, then telescope shutdown if that option is on.
        // A session that started after sunrise (solar) is left running.
        if (!syncStore.clockTrustedForMorningShutdown()) {
            Log.i(TAG, "morning sync skip: clock not NTP-synced — retry");
            if (!SystemActivityLog.lastKindIs(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                    SystemActivityLog.DETAIL_NO_NTP)) {
                SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                        SystemActivityLog.DETAIL_NO_NTP);
            }
            retrySunTooHighSoon();
            return;
        }
        if (!settings.sunSync()) {
            Log.i(TAG, "morning sync: photo sync disabled — mark day done");
            settings.recordSunTooHigh(today, SystemSettingsStore.SUN_RESULT_SYNC_OK);
            SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                    SystemActivityLog.DETAIL_OK);
            maybeKickPendingObservationSunShutdown(settings);
            if (finishMorningTelescopeShutdown(today, settings)) {
                retrySunTooHighSoon();
            } else {
                scheduleSunTooHighCheck();
            }
            return;
        }
        if (morningSyncRunning.get() || sunShutdownRunning.get()) {
            retrySunTooHighSoon();
            return;
        }
        if (!isVesperaConnected()) {
            Log.i(TAG, "morning sync skip: Vespera offline — retry");
            retrySunTooHighSoon();
            return;
        }
        Network network = resolveVesperaNetwork();
        if (network == null) {
            Log.i(TAG, "morning sync skip: no Vespera network — retry");
            retrySunTooHighSoon();
            return;
        }
        // Prefer observation-sun shutdown over morning sync when both apply.
        if (maybeKickPendingObservationSunShutdown(settings)) {
            Log.i(TAG, "morning sync deferred — pending observation sun shutdown");
            retrySunTooHighSoon();
            return;
        }
        if (!morningSyncRunning.compareAndSet(false, true)) {
            retrySunTooHighSoon();
            return;
        }
        try {
            runMorningPhotoSync(today, network);
        } finally {
            morningSyncRunning.set(false);
        }
    }

    /** @return true if an observation-sun shutdown was queued. */
    private boolean maybeKickPendingObservationSunShutdown(SystemSettingsStore settings) {
        if (settings == null || !settings.sunTelescopeShutdown()) return false;
        TelescopeStatusHub hub = TelescopeStatusHub.ensure();
        VesperaStatusSnapshot snap = hub.lastSnapshot();
        if (snap != null && snap.isEndedObservationSunTooHigh()) {
            String sid = snap.endedSessionSunTooHighId();
            if (!sid.isEmpty() && !sid.equals(settings.handledSunSessionId())) {
                settings.recordPendingSunSession(sid);
                syncExecutor.execute(this::maybeObservationSunShutdown);
                return true;
            }
        }
        String pending = settings.pendingSunSessionId();
        if (!pending.isEmpty() && !pending.equals(settings.handledSunSessionId())) {
            syncExecutor.execute(this::maybeObservationSunShutdown);
            return true;
        }
        return false;
    }

    /** Sunrise+30 photo sync only — never powers off telescope or Pi. */
    private void runMorningPhotoSync(int dayKey, Network network) {
        SystemSettingsStore settings = SystemSettingsStore.from(this);
        int attempt = settings.sunTooHighAttempt();
        settings.recordSunTooHigh(dayKey, SystemSettingsStore.SUN_RESULT_TRIGGERED, attempt + 1);
        pauseRequested.set(false);
        if (syncStore != null) {
            syncStore.setPaused(false);
            syncStore.setPauseUntilSchedule(false);
        }
        if (!waitUntilSyncIdle(6 * 60 * 60_000L)) {
            Log.w(TAG, "morning sync: in-flight sync still busy after timeout");
        }
        boolean alreadyDone = attempt > 0
                && syncStore != null && syncStore.lastOk()
                && !hasSuspendedWork();
        if (!alreadyDone) {
            Log.i(TAG, "morning photo sync");
            maybeAutoSync(true, SystemActivityLog.KIND_PHOTO_SYNC);
            waitUntilSyncIdle(6 * 60 * 60_000L);
        } else {
            Log.i(TAG, "morning sync skipped (already completed)");
        }
        int remaining = countRemotePhotosForShutdown(network);
        if (remaining > 0) {
            Log.w(TAG, "morning sync: Vespera USER still has " + remaining + " photo(s)");
            settings.recordSunTooHigh(dayKey, SystemSettingsStore.SUN_RESULT_SHUTDOWN_FAIL,
                    attempt + 1);
            SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                    "step" + (attempt + 1) + " remote-user " + remaining + " left");
            retrySunTooHighSoon();
            return;
        }
        if (remaining < 0 && isVesperaConnected()) {
            boolean localLeftover = hasSuspendedWork();
            boolean lastSyncOk = syncStore != null && syncStore.lastOk();
            if (localLeftover || !lastSyncOk) {
                Log.w(TAG, "morning sync: cannot verify USER empty");
                settings.recordSunTooHigh(dayKey, SystemSettingsStore.SUN_RESULT_SHUTDOWN_FAIL,
                        attempt + 1);
                SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                        "step" + (attempt + 1) + " remote-user unverified");
                retrySunTooHighSoon();
                return;
            }
        } else if (hasSuspendedWork() && syncStore != null) {
            syncStore.clearInProgress(this);
        }
        SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                SystemActivityLog.DETAIL_OK);
        Log.i(TAG, "morning sync ok");
        if (finishMorningTelescopeShutdown(dayKey, settings)) {
            retrySunTooHighSoon();
        } else {
            if (!settings.sunTelescopeShutdown()) {
                settings.recordSunTooHigh(dayKey, SystemSettingsStore.SUN_RESULT_SYNC_OK, 0);
            }
            scheduleSunTooHighCheck();
        }
    }

    /**
     * After the morning photo sync: park and power off if the telescope is
     * still on. Skips a live session that started at or after today's sunrise.
     *
     * @return true when shutdown failed and the morning window should retry
     */
    private boolean finishMorningTelescopeShutdown(int dayKey, SystemSettingsStore settings) {
        if (settings == null || !settings.sunTelescopeShutdown()) return false;
        if (syncStore == null || !syncStore.clockTrustedForMorningShutdown()) return false;
        if (!isVesperaConnected()) {
            Log.i(TAG, "morning shutdown deferred — Vespera offline");
            settings.recordSunTooHigh(dayKey, SystemSettingsStore.SUN_RESULT_SHUTDOWN_FAIL);
            return true;
        }
        Network network = resolveVesperaNetwork();
        if (network == null) {
            settings.recordSunTooHigh(dayKey, SystemSettingsStore.SUN_RESULT_SHUTDOWN_FAIL);
            return true;
        }
        int apiPort = currentApiPort();
        VesperaStatusSnapshot snap = freshSnapshot(network, apiPort);
        long rise = syncStore.sunriseMs(System.currentTimeMillis());
        if (snap != null && isLiveUserSession(snap)) {
            long start = snap.liveSessionStartMs();
            if (start <= 0L || (rise > 0L && start >= rise)) {
                Log.i(TAG, "morning shutdown skipped — session started after sunrise");
                settings.recordSunTooHigh(dayKey, SystemSettingsStore.SUN_RESULT_SYNC_OK, 0);
                SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                        "shutdown skip daytime-session");
                return false;
            }
        }
        Log.i(TAG, "morning window — park and shutdown telescope");
        boolean ok = lastSyncThenShutdown("morning:" + dayKey, network, apiPort);
        settings.recordSunTooHigh(dayKey, ok
                ? SystemSettingsStore.SUN_RESULT_SHUTDOWN_OK
                : SystemSettingsStore.SUN_RESULT_SHUTDOWN_FAIL);
        return !ok;
    }

    private int currentApiPort() {
        VesperaPortScan scan = VesperaPortScanner.lastScan();
        if (scan != null && scan.apiRestPort > 0) return scan.apiRestPort;
        return -1;
    }

    private VesperaStatusSnapshot freshSnapshot(Network network, int apiPort) {
        TelescopeStatusHub hub = TelescopeStatusHub.ensure();
        VesperaStatusSnapshot snap = hub.lastSnapshot();
        if (snap != null && hub.snapshotAgeMs() <= 180_000L) return snap;
        snap = VesperaStatusClient.fetch(PhotoSyncEngine.HOST, apiPort, network);
        if (snap != null) hub.ingestSnapshot(snap);
        return snap;
    }

    /**
     * Restart a session the firmware stopped for astrometry/guiding, while the
     * target is still above {@link SkyPosition#MIN_OBSERVE_ALT_DEG} and civil
     * sunrise has not arrived. Does not resume a parked arm or a sun error.
     */
    private void maybeAutoResumeObservation() {
        if (sunShutdownRunning.get() || morningSyncRunning.get()) return;
        if (!autoResumeRunning.compareAndSet(false, true)) return;
        try {
            if (!isVesperaConnected() || syncStore == null || !syncStore.hasSite()) return;
            if (!syncStore.clockTrustedForMorningShutdown()) return;
            if (WeatherProtectionStore.from(this).protectionActive()) return;
            long now = System.currentTimeMillis();
            long rise = syncStore.sunriseMs(now);
            if (rise > 0L && now >= rise) return;
            VesperaStatusSnapshot snap = TelescopeStatusHub.ensure().lastSnapshot();
            if (snap == null || snap.isShuttingDown() || snap.isArmClosed()
                    || snap.isAutoInitRunning() || isLiveUserSession(snap)
                    || snap.isSunTooHigh()) {
                return;
            }
            String session = snap.astrometryResumeKey();
            if (session.isEmpty()) return;
            double[] eq = snap.stoppedTargetRaDec();
            if (eq == null) eq = VesperaLastTarget.raDec();
            if (eq == null) {
                Log.i(TAG, "auto-resume skip: no target coordinates");
                return;
            }
            double alt = SkyPosition.altitudeDeg(
                    syncStore.siteLat(), syncStore.siteLon(), eq[0], eq[1], now);
            if (Double.isNaN(alt) || alt < SkyPosition.MIN_OBSERVE_ALT_DEG) {
                Log.i(TAG, "auto-resume skip: target altitude " + alt);
                if (!SystemActivityLog.lastKindIs(this, SystemActivityLog.KIND_OBS_RESUME,
                        "target-low")) {
                    SystemActivityLog.record(this, SystemActivityLog.KIND_OBS_RESUME, "target-low");
                }
                return;
            }
            if (!session.equals(autoResumeSession)) {
                autoResumeSession = session;
                autoResumeAttempts = 0;
            }
            if (autoResumeAttempts >= AUTO_RESUME_MAX) return;
            if (lastAutoResumeAt > 0 && now - lastAutoResumeAt < AUTO_RESUME_COOLDOWN_MS) return;
            Network network = resolveVesperaNetwork();
            if (network == null) return;
            autoResumeAttempts++;
            lastAutoResumeAt = now;
            String label = VesperaLastTarget.label();
            Log.i(TAG, "auto-resume " + label + " alt=" + Math.round(alt)
                    + " attempt=" + autoResumeAttempts + " session=" + session);
            VesperaCommandClient.Result result = VesperaCommandClient.send(
                    PhotoSyncEngine.HOST, currentApiPort(), network,
                    VesperaCommandClient.Command.RESUME,
                    new VesperaLocationClient.Site(syncStore.siteLat(), syncStore.siteLon()));
            boolean ok = result != null && result.success;
            SystemActivityLog.record(this, SystemActivityLog.KIND_OBS_RESUME,
                    ok ? SystemActivityLog.DETAIL_OK
                            : (SystemActivityLog.DETAIL_FAIL + " " + describeResult(result)));
            Log.i(TAG, "auto-resume " + (ok ? "ok" : "fail") + " " + describeResult(result));
        } finally {
            autoResumeRunning.set(false);
        }
    }

    /**
     * If an observation/plan recently ended with GENERAL.SUN_TOO_HIGH, sync
     * remaining USER photos then park+shutdown (and optional Pi). Never runs
     * on idle init sun errors. The morning window has its own shutdown.
     */
    private void maybeObservationSunShutdown() {
        SystemSettingsStore settings = SystemSettingsStore.from(this);
        if (!settings.sunTelescopeShutdown()) return;
        if (sunShutdownRunning.get()) return;
        long now = System.currentTimeMillis();
        if (lastObsSunAttemptAt > 0 && now - lastObsSunAttemptAt < OBS_SUN_SHUTDOWN_COOLDOWN_MS) {
            return;
        }
        if (!isVesperaConnected()) return;
        Network network = resolveVesperaNetwork();
        if (network == null) return;
        int apiPort = -1;
        VesperaPortScan scan = VesperaPortScanner.lastScan();
        if (scan != null && scan.apiRestPort > 0) apiPort = scan.apiRestPort;
        TelescopeStatusHub hub = TelescopeStatusHub.ensure();
        VesperaStatusSnapshot snap = hub.lastSnapshot();
        if (snap == null || hub.snapshotAgeMs() > 180_000L) {
            snap = VesperaStatusClient.fetch(PhotoSyncEngine.HOST, apiPort, network);
            if (snap != null) hub.ingestSnapshot(snap);
        }
        if (snap == null) return;
        String sessionId = snap.endedSessionSunTooHighId();
        if (!sessionId.isEmpty()) {
            settings.recordPendingSunSession(sessionId);
        } else {
            sessionId = settings.pendingSunSessionId();
        }
        if (sessionId.isEmpty()) return;
        if (sessionId.equals(settings.handledSunSessionId())) return;
        // Do not interrupt a new live observation (e.g. intentional solar session).
        if (isLiveUserSession(snap)) {
            Log.i(TAG, "observation sun-too-high deferred — live session active");
            return;
        }
        if (!sunShutdownRunning.compareAndSet(false, true)) return;
        lastObsSunAttemptAt = now;
        try {
            Log.i(TAG, "observation sun-too-high — sync then shutdown session=" + sessionId);
            lastSyncThenShutdown(sessionId, network, apiPort);
        } finally {
            sunShutdownRunning.set(false);
        }
    }

    private static boolean isLiveUserSession(VesperaStatusSnapshot snap) {
        return snap != null && (snap.isObserving() || snap.isTrackingAcquisition()
                || snap.hasLiveObservationOrPlan());
    }

    private void retrySunTooHighSoon() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::retrySunTooHighSoon);
            return;
        }
        long delay = SUN_TOO_HIGH_RETRY_MS;
        if (syncStore != null && syncStore.hasSite()) {
            long now = System.currentTimeMillis();
            long close = syncStore.sunTooHighWindowCloseAt(now);
            if (close > 0 && now < close) {
                delay = Math.min(delay, Math.max(MIN_AUTO_DELAY_MS, close - now));
            } else if (close > 0) {
                delay = MIN_AUTO_DELAY_MS;
            }
        }
        mainHandler.removeCallbacks(sunTooHighAlarm);
        mainHandler.postDelayed(sunTooHighAlarm, delay);
        Log.i(TAG, "morning sync retry in " + (delay / 1000L) + "s");
    }

    /**
     * Copy remaining USER photos, then power off the telescope. Used by the
     * observation-sun path, the morning window, and the manual shutdown button.
     * {@code sessionId} non-null = automatic flow (records handled session +
     * optional Pi). A {@code morning:} id also stops a night session still
     * running. {@code null} = manual. After sync, sends PARK (unless the arm
     * is already closed) then SHUTDOWN.
     *
     * @return true when the telescope accepted shutdown
     */
    private boolean lastSyncThenShutdown(String sessionId, Network network, int apiPort) {
        boolean obsFlow = sessionId != null && !sessionId.isEmpty();
        boolean morningClock = sessionId != null && sessionId.startsWith("morning:");
        SystemSettingsStore settings = SystemSettingsStore.from(this);
        pauseRequested.set(false);
        if (syncStore != null) {
            syncStore.setPaused(false);
            syncStore.setPauseUntilSchedule(false);
        }
        // Always sync before power-off so USER is empty (manual + observation).
        if (!waitUntilSyncIdle(6 * 60 * 60_000L)) {
            Log.w(TAG, "pre-shutdown: in-flight sync still busy after timeout");
        }
        Log.i(TAG, "photo sync before telescope shutdown");
        maybeAutoSync(true, SystemActivityLog.KIND_PHOTO_SYNC);
        waitUntilSyncIdle(6 * 60 * 60_000L);
        Network probeNet = network != null ? network : resolveVesperaNetwork();
        int remaining = countRemotePhotosForShutdown(probeNet);
        if (remaining > 0) {
            Log.w(TAG, "Vespera USER still has " + remaining
                    + " photo(s) — defer telescope shutdown");
            deferShutdownForIncompleteSync(obsFlow,
                    "remote-user " + remaining + " left");
            return false;
        }
        if (remaining < 0 && isVesperaConnected()) {
            boolean localLeftover = hasSuspendedWork();
            boolean lastSyncOk = syncStore != null && syncStore.lastOk();
            if (localLeftover || !lastSyncOk) {
                Log.w(TAG, "cannot verify Vespera USER empty — defer telescope shutdown"
                        + " (localLeftover=" + localLeftover
                        + " lastOk=" + lastSyncOk + ")");
                deferShutdownForIncompleteSync(obsFlow, "remote-user unverified");
                return false;
            }
            Log.w(TAG, "cannot verify Vespera USER empty — proceed after last OK sync");
        } else if (hasSuspendedWork()) {
            Log.w(TAG, "Vespera USER empty — clearing stale sync-in-progress before shutdown");
            if (syncStore != null) syncStore.clearInProgress(this);
        } else {
            Log.i(TAG, "Vespera USER empty — proceed with telescope shutdown");
        }
        Network net = network;
        if (net == null) net = resolveVesperaNetwork();
        int port = apiPort;
        if (port <= 0) {
            VesperaPortScan scan = VesperaPortScanner.lastScan();
            if (scan != null && scan.apiRestPort > 0) port = scan.apiRestPort;
        }
        // Re-check before PARK: user may have started a solar session during sync.
        // Fail closed if status cannot be refreshed — never PARK on a stale snapshot.
        VesperaStatusSnapshot live = null;
        if (obsFlow) {
            live = VesperaStatusClient.fetch(PhotoSyncEngine.HOST, port, net);
            if (live == null) {
                Log.w(TAG, "shutdown abort before park — status refresh failed");
                SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                        "obs abort status-refresh");
                return false;
            }
            TelescopeStatusHub.ensure().ingestSnapshot(live);
            if (isLiveUserSession(live) && !morningClock) {
                Log.i(TAG, "observation sun-too-high abort before park — live session");
                SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                        "obs abort live-session");
                return false;
            }
            if (morningClock && isLiveUserSession(live)) {
                Log.i(TAG, "morning shutdown — stopping night session before park");
                if (!stopLiveObservation(net, port)) {
                    SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                            "obs stop-before-park fail");
                    return false;
                }
                live = VesperaStatusClient.fetch(PhotoSyncEngine.HOST, port, net);
                if (live != null) TelescopeStatusHub.ensure().ingestSnapshot(live);
            }
        }
        boolean telescopeOk = false;
        final String stepLabel = "park+shutdown";
        VesperaCommandClient.Result result;
        try {
            boolean alreadyParked = live != null && live.isArmClosed();
            if (alreadyParked) {
                Log.i(TAG, "arm already closed — shutdown without another park");
                result = new VesperaCommandClient.Result(true, 200, "already_parked");
            } else {
                result = VesperaCommandClient.send(
                        PhotoSyncEngine.HOST, port, net,
                        VesperaCommandClient.Command.PARK);
                Log.i(TAG, "park before shutdown " + describeResult(result));
            }
            if (!alreadyParked && !waitUntilTelescopeIdleAfterPark(net, port, "after park")) {
                result = new VesperaCommandClient.Result(false, -1, "busy_after_park");
                if (obsFlow) {
                    SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                            "obs wait-after-park fail busy_after_park");
                }
                Log.i(TAG, "shutdown skipped: telescope still busy after park");
            } else {
                result = VesperaCommandClient.send(
                        PhotoSyncEngine.HOST, port, net,
                        VesperaCommandClient.Command.SHUTDOWN);
                telescopeOk = result != null && result.success;
                if (obsFlow) {
                    SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                            telescopeOk
                                    ? SystemActivityLog.DETAIL_SHUTDOWN_OK
                                    : ("obs " + stepLabel + " fail " + describeResult(result)));
                    if (telescopeOk) settings.recordHandledSunSession(sessionId);
                }
                Log.i(TAG, "shutdown after photo sync " + (telescopeOk ? "ok" : "fail")
                        + " " + describeResult(result)
                        + (obsFlow ? " session=" + sessionId : " manual"));
                if (telescopeOk) TelescopeStatusHub.ensure().ingestShutdown();
            }
            Context localized = AppLocale.wrap(this);
            message = localized.getString(telescopeOk
                    ? R.string.telescope_command_shutdown_ok
                    : R.string.system_sun_result_shutdown_fail);
            publish();
            // Pi only after a successful telescope shutdown on the automatic path.
            if (obsFlow && telescopeOk && settings.sunPiShutdown()) {
                schedulePiShutdownAfterSunTooHigh();
            }
        } finally {
            // Observation path retries while the ended sun session stays recent;
            // maybeObservationSunShutdown is polled from the status tick.
        }
        return telescopeOk;
    }

    /** Stop a live observation and wait until it is no longer running. */
    private boolean stopLiveObservation(Network net, int port) {
        VesperaCommandClient.Result stop = VesperaCommandClient.send(
                PhotoSyncEngine.HOST, port, net, VesperaCommandClient.Command.STOP);
        Log.i(TAG, "stop before morning shutdown " + describeResult(stop));
        long deadline = System.currentTimeMillis() + 90_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(4_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            VesperaStatusSnapshot snap = VesperaStatusClient.fetch(PhotoSyncEngine.HOST, port, net);
            if (snap == null) continue;
            TelescopeStatusHub.ensure().ingestSnapshot(snap);
            if (!isLiveUserSession(snap)) return true;
        }
        return false;
    }

    private static String describeResult(VesperaCommandClient.Result result) {
        if (result == null) return "null";
        return "http=" + result.httpCode + " " + result.message;
    }

    /** Poll every minute after PARK until the telescope is idle. */
    private boolean waitUntilTelescopeIdleAfterPark(Network net, int port, String reason) {
        return VesperaCommandClient.waitUntilIdleAfterPark(
                PhotoSyncEngine.HOST, port, net, reason);
    }

    /**
     * Block until no photo sync is active, or {@code timeoutMs} elapses.
     * Used before telescope shutdown so we never power off mid-copy.
     */
    private boolean waitUntilSyncIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (true) {
            synchronized (syncLock) {
                boolean busy = syncing && lastProgress != null && lastProgress.active;
                if (!busy) return true;
            }
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void deferShutdownForIncompleteSync(boolean obsFlow, String detail) {
        if (obsFlow) {
            SystemActivityLog.record(this, SystemActivityLog.KIND_SUN_TOO_HIGH,
                    "obs " + detail);
        }
        Context localized = AppLocale.wrap(this);
        message = localized.getString(R.string.telescope_shutdown_syncing);
        publish();
    }

    /** Retry FTP listing a few times — a single busy socket must not block morning power-off. */
    private static int countRemotePhotosForShutdown(Network network) {
        int last = -1;
        for (int i = 0; i < 3; i++) {
            last = PhotoSyncEngine.countRemotePhotos(network);
            if (last >= 0) return last;
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    /** After sun-too-high HD eject, optionally power off the Pi via vespera-netd. */
    private void schedulePiShutdownAfterSunTooHigh() {
        Log.i(TAG, "sun-too-high — Pi shutdown scheduled in "
                + (PI_SHUTDOWN_DELAY_MS / 1000L) + "s");
        mainHandler.postDelayed(() -> {
            boolean sent = DaemonDisk.shutdownPi(this);
            if (sent) {
                Log.i(TAG, "Pi shutdown requested");
                SystemActivityLog.record(this, SystemActivityLog.KIND_PI_SHUTDOWN,
                        SystemActivityLog.DETAIL_OK);
            } else {
                Log.w(TAG, "Pi shutdown failed");
                SystemActivityLog.record(this, SystemActivityLog.KIND_PI_SHUTDOWN,
                        SystemActivityLog.DETAIL_FAIL);
            }
        }, PI_SHUTDOWN_DELAY_MS);
    }

    private long delayUntilNextStorageSlot() {
        TimeZone zone = syncStore != null ? syncStore.zone() : TimeZone.getDefault();
        long nowMs = System.currentTimeMillis();
        Calendar hour = Calendar.getInstance(zone);
        hour.setTimeInMillis(nowMs);
        hour.set(Calendar.SECOND, 0);
        hour.set(Calendar.MILLISECOND, 0);
        hour.set(Calendar.MINUTE, 0);
        for (int hourOffset = 0; hourOffset <= 1; hourOffset++) {
            Calendar base = (Calendar) hour.clone();
            if (hourOffset > 0) base.add(Calendar.HOUR_OF_DAY, hourOffset);
            for (int slot : STORAGE_CHECK_MINUTES) {
                Calendar cand = (Calendar) base.clone();
                cand.set(Calendar.MINUTE, slot);
                long at = cand.getTimeInMillis();
                if (at > nowMs) {
                    long delay = at - nowMs;
                    return delay < MIN_AUTO_DELAY_MS ? MIN_AUTO_DELAY_MS : delay;
                }
            }
        }
        return 60 * 60_000L;
    }

    private void maybeCheckInternalStorage() {
        scheduleHourlyStorageCheck();
        if (!SystemSettingsStore.from(this).storageSync()) return;
        if (!isVesperaConnected()) {
            Log.i(TAG, "internal storage skip: Vespera offline");
            return;
        }
        synchronized (syncLock) {
            if (syncing) {
                Log.i(TAG, "internal storage skip: photo sync in progress");
                return;
            }
        }
        Network network = resolveVesperaNetwork();
        if (network == null) return;
        int apiPort = -1;
        VesperaPortScan scan = VesperaPortScanner.lastScan();
        if (scan != null && scan.apiRestPort > 0) apiPort = scan.apiRestPort;
        VesperaStatusSnapshot snap = VesperaStatusClient.fetch(PhotoSyncEngine.HOST, apiPort, network);
        if (snap != null) TelescopeStatusHub.ensure().ingestSnapshot(snap);
        if (snap == null || !snap.isTrackingAcquisition()) {
            Log.i(TAG, "internal storage skip: not tracking/acquisition");
            return;
        }
        String model = snap.model;
        VesperaInternalStorage.Usage usage = VesperaInternalStorage.probe(
                network, PhotoSyncEngine.HOST, apiPort, model);
        if (usage == null) {
            Log.w(TAG, "internal storage probe failed");
            return;
        }
        Log.i(TAG, "internal storage " + usage.label);
        TelescopeStatusHub.ensure().ingestInternalStorage(usage.usedPercent);
        publish();
    }

    /** Re-read Vespera /USER occupancy after an FTP copy/delete so Telescopio status stays current. */
    private void refreshInternalStorageAfterSync(PhotoSyncEngine.Result result) {
        if (result == null) return;
        if ("hd-unmounted".equals(result.error) || "local-user".equals(result.error)
                || "missing-user".equals(result.error)) {
            return;
        }
        if (!isVesperaConnected()) return;
        Network network = resolveVesperaNetwork();
        if (network == null) return;
        int apiPort = -1;
        VesperaPortScan scan = VesperaPortScanner.lastScan();
        if (scan != null && scan.apiRestPort > 0) apiPort = scan.apiRestPort;
        String model = VesperaDeviceStore.from(this).getModel();
        VesperaInternalStorage.Usage usage = VesperaInternalStorage.probe(
                network, PhotoSyncEngine.HOST, apiPort, model);
        if (usage == null) {
            Log.w(TAG, "storage after FTP sync: probe failed");
            return;
        }
        Log.i(TAG, "storage after FTP sync " + usage.label);
        TelescopeStatusHub.ensure().ingestInternalStorage(usage.usedPercent);
    }

    private void pollTelescopeStatusLocked() {
        synchronized (statusPollLock) {
            if (!isVesperaConnected()) return;
            Network network = resolveVesperaNetwork();
            if (network == null) return;
            int apiPort = -1;
            VesperaPortScan scan = VesperaPortScanner.lastScan();
            if (scan != null && scan.apiRestPort > 0) apiPort = scan.apiRestPort;
            VesperaStatusSnapshot snap = VesperaStatusClient.fetch(PhotoSyncEngine.HOST, apiPort, network);
            if (snap != null) {
                TelescopeStatusHub.ensure().ingestSnapshot(snap);
                SystemSettingsStore settings = SystemSettingsStore.from(this);
                if (snap.isEndedObservationSunTooHigh()) {
                    settings.recordPendingSunSession(snap.endedSessionSunTooHighId());
                }
                if (snap.isEndedObservationSunTooHigh()
                        || (!settings.pendingSunSessionId().isEmpty()
                        && !settings.pendingSunSessionId().equals(settings.handledSunSessionId()))) {
                    syncExecutor.execute(this::maybeObservationSunShutdown);
                }
                syncExecutor.execute(this::maybeAutoResumeObservation);
            }
            DaemonDisk.Space space = DaemonDisk.photosSpace(this);
            if (space != null && space.known) {
                TelescopeStatusHub.ensure().ingestHd(space.usedPercent);
            }
        }
    }

    /** Status for Telegram must not wait on HD mount or the 2-minute tick. */
    private void startStatusWatch() {
        if (statusWatchThread != null && statusWatchThread.isAlive()) return;
        statusWatchStop = false;
        statusWatchThread = new Thread(this::statusWatchLoop, "telescope-status");
        statusWatchThread.start();
    }

    private void stopStatusWatch() {
        statusWatchStop = true;
        if (statusWatchThread != null) statusWatchThread.interrupt();
    }

    private void wakeStatusWatch() {
        if (statusWatchThread != null) statusWatchThread.interrupt();
    }

    private void statusWatchLoop() {
        while (!statusWatchStop) {
            try {
                if (isVesperaConnected()) pollTelescopeStatusLocked();
            } catch (RuntimeException failure) {
                Log.w(TAG, "status watch " + failure.getMessage());
            }
            try {
                Thread.sleep(STATUS_WATCH_MS);
            } catch (InterruptedException interrupted) {
                if (statusWatchStop) return;
            }
        }
    }

    private void onTelescopeStatusEvent(TelescopeStatusEvent event) {
        if (event == null) return;
        if (event.kind == TelescopeStatusEvent.Kind.STORAGE_HIGH) {
            if (!SystemSettingsStore.from(this).storageSync()) return;
            syncExecutor.execute(this::maybeSyncForFullStorage);
        } else if (event.kind == TelescopeStatusEvent.Kind.SUN_TOO_HIGH) {
            syncExecutor.execute(this::maybeObservationSunShutdown);
        }
    }

    private void maybeSyncForFullStorage() {
        if (!SystemSettingsStore.from(this).storageSync()) return;
        synchronized (syncLock) {
            if (syncing) return;
        }
        if (syncStore != null && (syncStore.paused() || syncStore.pauseUntilSchedule())) return;
        long now = System.currentTimeMillis();
        if (lastStorageSyncAt > 0 && now - lastStorageSyncAt < STORAGE_SYNC_COOLDOWN_MS) {
            return;
        }
        lastStorageSyncAt = now;
        Log.i(TAG, "storage ≥" + STORAGE_SYNC_PERCENT + "% — starting photo sync");
        pauseRequested.set(false);
        maybeAutoSync(true, SystemActivityLog.KIND_STORAGE_SYNC);
    }

    private void beginForcedSyncUi() {
        pendingForceSync = true;
        Context localized = AppLocale.wrap(this);
        SyncProgress progress = new SyncProgress();
        progress.active = true;
        progress.phase = SyncProgress.PHASE_LIST;
        progress.detail = localized.getString(R.string.photo_sync_starting);
        lastProgress = progress;
        syncStatus = progress.detail;
        message = progress.detail;
        if (hud != null) {
            hud.show();
            hud.bind(progress);
        }
        publish();
    }

    private void maybeAutoSync(boolean force) {
        maybeAutoSync(force, null);
    }

    private void maybeAutoSync(boolean force, String autoKind) {
        Context localized = AppLocale.wrap(this);
        SystemSettingsStore settings = SystemSettingsStore.from(this);
        boolean resume = shouldResume() || (force && hasSuspendedWork());
        if (resume && !settings.resumeSync() && !force) {
            resume = false;
        }
        if (!force && !resume && !settings.photoSync()) {
            refreshSyncHint();
            publish();
            return;
        }
        synchronized (syncLock) {
            if (syncing) {
                boolean reallyActive = lastProgress != null && lastProgress.active;
                if (reallyActive) {
                    if ((force || resume) && hud != null) hud.show();
                    if (force) pendingForceSync = false;
                    return;
                }
                Log.w(TAG, "stale syncing flag — restarting copy");
                syncing = false;
            }
        }
        try {
            if (syncStore != null && syncStore.pauseUntilSchedule() && !force) {
                long now = System.currentTimeMillis();
                if (!syncStore.isAutoDue(now)) {
                    refreshSyncHint();
                    publish();
                    return;
                }
                syncStore.setPauseUntilSchedule(false);
                resume = shouldResume() || hasSuspendedWork();
            }
            if (syncStore != null && syncStore.paused() && !force) {
                refreshSyncHint();
                return;
            }
            if (!force && !resume && syncStore != null
                    && syncStore.isDaytime(System.currentTimeMillis())) {
                Log.i(TAG, "auto-sync skip: daytime (no periodic day slots)");
                refreshSyncHint();
                publish();
                return;
            }
            if (!force && !resume && !syncStore.isAutoDue(System.currentTimeMillis())) {
                Log.i(TAG, "auto-sync skip: not due, next "
                        + formatClock(syncStore.nextAutoAt(System.currentTimeMillis())));
                refreshSyncHint();
                publish();
                return;
            }
            Log.i(TAG, force ? "sync forced" : "auto-sync due");
            ensureMountedForSync();
            File root = DaemonDisk.photosDir(this);
            if (!DaemonDisk.isPhotosBoundLive(root)) {
                DaemonDisk.MountStatus rebound = DaemonDisk.ensureBind(this);
                applyMountStatus(rebound);
                root = DaemonDisk.photosDir(this);
            }
            if (!mounted || root == null || !root.isDirectory()) {
                message = localized.getString(R.string.photos_sync_need_hd);
                if (force || resume) {
                    showSyncGate(SyncProgress.PHASE_ERROR, message);
                } else {
                    extraAutoDelayMs = AUTO_RETRY_MS;
                }
                refreshSyncHint();
                publish();
                return;
            }
            if (!DaemonDisk.isPhotosBoundLive(root) && !root.canWrite()) {
                message = localized.getString(R.string.photos_sync_local_user);
                if (force || resume) {
                    showSyncGate(SyncProgress.PHASE_ERROR, message);
                } else {
                    extraAutoDelayMs = AUTO_RETRY_MS;
                }
                refreshSyncHint();
                publish();
                return;
            }
            Network network = resolveVesperaNetwork();
            if (network == null && !isVesperaConnected() && !canReachVesperaFtp(null)) {
                message = localized.getString(R.string.photos_sync_need_vespera);
                if (force && !resume) {
                    showSyncGate(SyncProgress.PHASE_ERROR, message);
                } else if (resume) {
                    syncStatus = localized.getString(R.string.photo_sync_resume_wait_vespera);
                    message = syncStatus;
                    refreshSyncHint();
                    extraAutoDelayMs = AUTO_RETRY_MS;
                } else {
                    extraAutoDelayMs = AUTO_RETRY_MS;
                }
                publish();
                return;
            }
            synchronized (syncLock) {
                if (syncing) return;
                syncing = true;
            }
            try {
                if (!force && !resume) syncStore.recordAttempt();
                syncStore.markInProgress(this);
                pauseRequested.set(false);
                lastProgress = new SyncProgress();
                lastProgress.active = true;
                lastProgress.phase = SyncProgress.PHASE_LIST;
                lastProgress.detail = resume
                        ? localized.getString(R.string.photo_sync_resume)
                        : localized.getString(R.string.photo_sync_listing);
                refreshSyncHint();
                if (hud != null) {
                    hud.show();
                    hud.bind(lastProgress);
                }
                publish();
                PhotoSyncEngine.Result result = PhotoSyncEngine.sync(network, root, this::onEngineProgress,
                        pauseRequested::get);
                boolean paused = "paused".equals(result.error);
                boolean complete = result.error == null && result.failed == 0;
                boolean startedWork = result.downloaded > 0 || result.skipped > 0
                        || result.deleted > 0 || PhotoSyncEngine.hasIncomplete(root);
                if (paused) {
                    syncStore.markInterrupted(this);
                    if (syncStore.pauseUntilSchedule()) {
                        syncStore.setPaused(false);
                        syncStore.recordAttempt();
                    } else {
                        syncStore.setPaused(true);
                    }
                } else if (complete) {
                    syncStore.clearInProgress(this);
                } else if (!startedWork) {
                    syncStore.clearInProgress(this);
                } else {
                    syncStore.markInterrupted(this);
                }
                if (paused) {
                    if (syncStore.pauseUntilSchedule()) {
                        message = localized.getString(R.string.photo_sync_paused_until_next,
                                formatClock(syncStore.nextAutoAt(System.currentTimeMillis())));
                    } else {
                        message = localized.getString(R.string.photo_sync_paused);
                    }
                } else if (result.error != null) {
                    syncStore.recordFailure(result.error);
                    message = lastErrorLabel(localized, result.error);
                } else if (!complete) {
                    // Partial copy: do not mark lastOk — morning shutdown must retry sync.
                    syncStore.recordFailure("failed=" + result.failed);
                    message = formatSyncSummary(localized, result, new File(root, "USER"));
                } else {
                    syncStore.recordSuccess(result.downloaded, result.skipped, result.deleted, result.bytes);
                    syncStore.setPhotosPath(new File(root, "USER").getAbsolutePath());
                    message = formatSyncSummary(localized, result, new File(root, "USER"));
                }
                if (autoKind != null) {
                    String detail = paused ? SystemActivityLog.DETAIL_PAUSED
                            : (complete && result.error == null
                            ? SystemActivityLog.DETAIL_OK
                            : SystemActivityLog.DETAIL_FAIL);
                    SystemActivityLog.record(this, autoKind, detail);
                }
                restoreLastSync();
                if (message == null || message.isEmpty()) message = lastSync;
                if (lastProgress != null) {
                    lastProgress.active = false;
                    lastProgress.phase = paused ? SyncProgress.PHASE_PAUSED
                            : (result.error != null ? SyncProgress.PHASE_ERROR : SyncProgress.PHASE_DONE);
                    lastProgress.detail = message;
                    lastProgress.etaMs = 0;
                    if (hud != null) hud.bind(lastProgress);
                }
                fileCount = PhotoSyncEngine.countLocalPhotos(root);
                if (hud != null && paused) hud.hideByUser();
                refreshInternalStorageAfterSync(result);
            } finally {
                synchronized (syncLock) {
                    syncing = false;
                }
                refreshSyncHint();
                publish();
                sendBroadcast(progressIntent());
            }
        } finally {
            if (!syncing) pendingForceSync = false;
            if (syncStore != null && !syncStore.paused()) {
                synchronized (syncLock) {
                    if (!syncing) scheduleNextAutoSync();
                }
            }
        }
    }

    private void ensureMountedForSync() {
        synchronized (hdLock) {
        if (userUnmounted || autoPoweredOff) {
            refreshMountStatus();
            return;
        }
        refreshMountStatus();
        if (mounted) return;
        if (disksEncoded == null || disksEncoded.length == 0) {
            listDisksLocked();
        } else {
            bindListedDiskIfNeeded();
        }
        if (!mounted) maybeAutoMount();
        }
    }

    private void bindListedDiskIfNeeded() {
        synchronized (hdLock) {
        if (mounted || userUnmounted || autoPoweredOff) return;
        UsbDisk chosen = null;
        for (String encoded : disksEncoded) {
            UsbDisk disk = UsbDisk.parse(encoded);
            if (disk == null || !disk.mounted) continue;
            if (chosen == null) chosen = disk;
            if (!selectedSpec.isEmpty() && disk.matches(selectedSpec)) {
                chosen = disk;
                break;
            }
            if (hdStore.isConfigured() && disk.matches(hdStore.getSpec())) {
                chosen = disk;
                break;
            }
        }
        if (chosen == null) return;
        selectedSpec = chosen.id();
        DaemonDisk.MountStatus status = DaemonDisk.mount(this, chosen.id());
        applyMountStatus(status);
        if (status != null && status.mounted) {
            hdStore.rememberMount(status.uuid, status.label, status.device);
            hdStore.saveMounted(chosen);
            UsbDiskStore.from(this).save(chosen);
            startFtpLocked();
            Context localized = AppLocale.wrap(this);
            message = localized.getString(R.string.photos_mount_ok, hdStore.displayName());
            extraAutoDelayMs = 0;
            scheduleNextAutoSync();
        }
        }
    }

    private boolean shouldResume() {
        File root = DaemonDisk.photosDir(this);
        return (syncStore != null && syncStore.shouldResume(this))
                || PhotoSyncEngine.hasIncomplete(root);
    }

    private boolean hasSuspendedWork() {
        File root = DaemonDisk.photosDir(this);
        return (syncStore != null && syncStore.hasSuspendedWork(this))
                || PhotoSyncEngine.hasIncomplete(root);
    }

    /**
     * When Vespera goes from offline to online: resume an interrupted copy, or
     * start a fresh USER sync during daytime (replaces the old day interval).
     * At night the periodic slots already copy; reconnects must not force extras.
     */
    private void syncOnVesperaOnline() {
        SystemSettingsStore settings = SystemSettingsStore.from(this);
        if (syncStore != null && syncStore.paused()) {
            Log.i(TAG, "Vespera online — photo sync skipped (paused)");
            return;
        }
        if (hasSuspendedWork() && settings.resumeSync()) {
            resumeSuspendedIfNeeded();
            return;
        }
        if (!settings.photoSync()) return;
        long now = System.currentTimeMillis();
        if (syncStore != null && !syncStore.isDaytime(now)) {
            Log.i(TAG, "Vespera online — night, wait for scheduled slot");
            maybeAutoSync(false, SystemActivityLog.KIND_PHOTO_SYNC);
            return;
        }
        if (syncStore != null && syncStore.syncedWithinInterval(now)) {
            Log.i(TAG, "Vespera online — photo sync skipped (recent copy)");
            return;
        }
        Log.i(TAG, "Vespera online — starting photo sync");
        maybeAutoSync(true, SystemActivityLog.KIND_PHOTO_SYNC);
    }

    /**
     * On app/service restart (or when Vespera comes back), continue any sync that
     * was left paused, interrupted, or with leftover {@code .part} files.
     */
    private void resumeSuspendedIfNeeded() {
        SystemSettingsStore settings = SystemSettingsStore.from(this);
        if (syncStore != null && syncStore.pauseUntilSchedule()) {
            if (settings.photoSync()) maybeAutoSync(false, SystemActivityLog.KIND_PHOTO_SYNC);
            return;
        }
        if (!settings.resumeSync() || !hasSuspendedWork()) {
            if (settings.photoSync()) maybeAutoSync(false, SystemActivityLog.KIND_PHOTO_SYNC);
            return;
        }
        Log.i(TAG, "suspended sync detected — auto resume");
        pauseRequested.set(false);
        if (syncStore != null) {
            syncStore.clearPauseForAutoResume();
            syncStore.markInProgress(this);
        }
        Context localized = AppLocale.wrap(this);
        syncStatus = localized.getString(R.string.photo_sync_resume);
        publish();
        maybeAutoSync(true, SystemActivityLog.KIND_RESUME_SYNC);
    }

    private String formatSyncSummary(Context localized, PhotoSyncEngine.Result result, File localUser) {
        StringBuilder text = new StringBuilder();
        boolean ok = result.failed == 0;
        text.append(ok
                ? localized.getString(R.string.photo_sync_summary_ok)
                : localized.getString(R.string.photo_sync_summary_partial, result.failed));
        text.append('\n');
        text.append(localized.getString(R.string.photo_sync_summary_path,
                result.remoteRoot, localUser == null ? "USER" : localUser.getAbsolutePath()));
        text.append('\n');
        text.append(localized.getString(R.string.photos_sync_done,
                result.downloaded, result.skipped, result.deleted, result.failed,
                PhotoSyncEngine.formatBytes(result.bytes)));
        if (result.folders != null && !result.folders.isEmpty()) {
            text.append('\n').append(localized.getString(R.string.photo_sync_summary_folders));
            for (String folder : result.folders) {
                text.append('\n').append("  ").append(folder);
            }
        }
        if (result.files != null && !result.files.isEmpty()) {
            text.append('\n').append(localized.getString(R.string.photo_sync_summary_files,
                    result.files.size()));
        } else {
            text.append('\n').append(localized.getString(R.string.photo_sync_summary_empty));
        }
        return text.toString();
    }

    private void showSyncGate(String phase, String text) {
        SyncProgress progress = new SyncProgress();
        progress.active = false;
        progress.phase = phase;
        progress.detail = text == null ? "" : text;
        lastProgress = progress;
        if (hud != null) {
            hud.show();
            hud.bind(progress);
            hud.hideLater(8_000);
        }
        sendBroadcast(progressIntent());
    }

    private Intent progressIntent() {
        return new Intent(ACTION_PROGRESS).setPackage(getPackageName())
                .putExtra(EXTRA_SYNCING, isCopyUiActive());
    }

    private boolean isCopyUiActive() {
        if (syncing) return true;
        return pendingForceSync && lastProgress != null && lastProgress.active;
    }

    private void onEngineProgress(SyncProgress progress) {
        lastProgress = progress;
        Context localized = AppLocale.wrap(this);
        if (progress.fileTotal > 0 && progress.fileName != null && !progress.fileName.isEmpty()) {
            syncStatus = localized.getString(R.string.photos_sync_file,
                    progress.fileIndex, progress.fileTotal, progress.fileName);
        } else {
            syncStatus = localized.getString(R.string.photos_sync_running);
        }
        if (hud != null) hud.bind(progress);
        long now = SystemClock.elapsedRealtime();
        boolean phaseChanged = progress.phase != null && lastProgress != null
                && !progress.phase.equals(lastBroadcastPhase);
        if (phaseChanged || now - lastNotifyAt > 250) {
            lastBroadcastPhase = progress.phase == null ? "" : progress.phase;
            sendBroadcast(progressIntent());
            lastNotifyAt = now;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
    }

    private Network resolveVesperaNetwork() {
        Network current = VesperaConnectionService.getActiveNetwork();
        if (current != null) return current;
        try {
            VesperaConnectionService.refreshConnectedNetwork(this);
        } catch (Exception ignored) {
        }
        current = VesperaConnectionService.getActiveNetwork();
        if (current != null) return current;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        for (Network network : cm.getAllNetworks()) {
            LinkProperties lp = cm.getLinkProperties(network);
            if (lp == null) continue;
            for (LinkAddress addr : lp.getLinkAddresses()) {
                String host = addr.getAddress().getHostAddress();
                if (host != null && host.startsWith("10.0.0.")) return network;
            }
        }
        return null;
    }

    private boolean canReachVesperaFtp(Network network) {
        return FtpProbe.findVesperaControl(network, PhotoSyncEngine.HOST) > 0;
    }

    private void startFtpLocked() {
        if (!SystemSettingsStore.from(this).ftpLocal()) {
            stopFtpLocked();
            return;
        }
        File root = DaemonDisk.photosDir(this);
        if (!mounted || root == null || !root.isDirectory()) return;
        if (ftpServer.isRunning()) return;
        try {
            int avoid = telescopeFtp.isRunning() ? telescopeFtp.getPort() : FtpProbe.TELESCOPE_PREFERRED;
            ftpServer.start(root, avoid);
            if (ftpServer.isRunning()) {
                SystemActivityLog.record(this, SystemActivityLog.KIND_FTP, SystemActivityLog.DETAIL_OK);
            }
        } catch (Exception failure) {
            Log.w(TAG, "ftp start", failure);
        }
        refreshFtpStatus();
    }

    private void applySystemSettingsLocked() {
        refreshClockAndSun(false);
        refreshMountStatus();
        if (!mounted) maybeAutoMount();
        if (SystemSettingsStore.from(this).ftpLocal() && mounted) startFtpLocked();
        else stopFtpLocked();
        refreshTelescopeFtpLocked();
        refreshSyncHint();
        publish();
    }

    private void rescheduleFromSettings() {
        mainHandler.removeCallbacks(autoSyncAlarm);
        mainHandler.removeCallbacks(hourlyStorageCheck);
        scheduleNextAutoSync();
        scheduleHourlyStorageCheck();
        scheduleSunTooHighCheck();
        scheduleWeatherCheck();
    }

    private long weatherCheckIntervalMs(WeatherProtectionStore store) {
        return Math.max(WeatherProtectionStore.MIN_CHECK_INTERVAL_MIN,
                store.checkIntervalMin()) * 60_000L;
    }

    /**
     * Schedules the Automatic Weather Protection check. Runs a first check
     * shortly after enabling/boot, then every configured interval (default 5 min).
     */
    private void scheduleWeatherCheck() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::scheduleWeatherCheck);
            return;
        }
        mainHandler.removeCallbacks(weatherAlarm);
        WeatherProtectionStore store = WeatherProtectionStore.from(this);
        if (!store.enabled()) return;
        mainHandler.postDelayed(weatherAlarm, MIN_AUTO_DELAY_MS);
        Log.i(TAG, "weather protection check scheduled (every "
                + store.checkIntervalMin() + " min)");
    }

    private void stopFtpLocked() {
        ftpServer.stop();
        refreshFtpStatus();
    }

    private void refreshTelescopeFtpLocked() {
        Network network = resolveVesperaNetwork();
        if (network == null && !isVesperaConnected()) {
            vesperaFtpPort = -1;
            telescopeFtp.stop();
            refreshFtpStatus();
            return;
        }
        if (telescopeFtp.isRunning() && vesperaFtpPort > 0) {
            refreshFtpStatus();
            return;
        }
        vesperaFtpPort = FtpProbe.findVesperaControl(network, PhotoSyncEngine.HOST);
        if (vesperaFtpPort <= 0) {
            telescopeFtp.stop();
            refreshFtpStatus();
            return;
        }
        try {
            int avoid = ftpServer.isRunning() ? ftpServer.getPort() : FtpProbe.HD_PREFERRED;
            telescopeFtp.start(network, PhotoSyncEngine.HOST, vesperaFtpPort, avoid);
        } catch (Exception failure) {
            Log.w(TAG, "telescope ftp", failure);
            telescopeFtp.stop();
        }
        refreshFtpStatus();
    }

    private void refreshFtpStatus() {
        Context localized = AppLocale.wrap(this);
        String vespera;
        if (telescopeFtp.isRunning()) {
            vespera = localized.getString(R.string.photo_ftp_vespera_on,
                    telescopeFtp.getPort(), telescopeFtp.getUpstreamHost(),
                    telescopeFtp.getUpstreamPort());
        } else if (isVesperaConnected() || resolveVesperaNetwork() != null) {
            vespera = localized.getString(R.string.photo_ftp_vespera_off, PhotoSyncEngine.HOST);
        } else {
            vespera = localized.getString(R.string.photo_ftp_vespera_wait);
        }
        String disk = ftpServer.isRunning()
                ? localized.getString(R.string.photo_ftp_hd_on, ftpServer.getPort())
                : localized.getString(R.string.photo_ftp_hd_off);
        ftpStatus = vespera + "\n" + disk;
    }

    private UsbDisk findDisk(String spec) {
        for (String encoded : disksEncoded) {
            UsbDisk disk = UsbDisk.parse(encoded);
            if (disk != null && disk.matches(spec)) return disk;
        }
        return null;
    }

    private boolean isVesperaConnected() {
        return VesperaConnectionService.STATUS_CONNECTED.equals(VesperaConnectionService.getLastStatus())
                && VesperaConnectionService.getActiveNetwork() != null;
    }

    private String ftpHint() {
        Context localized = AppLocale.wrap(this);
        int scopePort = telescopeFtp.isRunning() ? telescopeFtp.getPort() : FtpProbe.TELESCOPE_PREFERRED;
        int diskPort = ftpServer.isRunning() ? ftpServer.getPort() : FtpProbe.HD_PREFERRED;
        String scopeUrls = joinUrls(ftpUrls(scopePort));
        String diskUrls = joinUrls(ftpUrls(diskPort));
        return localized.getString(R.string.photo_ftp_hint_two,
                scopeUrls, diskUrls, localized.getString(R.string.photos_ftp_anonymous))
                + "\n\n" + localized.getString(R.string.photo_user_folder,
                VesperaPortInventory.userFolderUrl());
    }

    private List<String> ftpUrls(int port) {
        List<String> urls = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs != null) {
                List<NetworkInterface> list = Collections.list(ifs);
                Collections.sort(list, (a, b) -> Integer.compare(rankIface(a.getName()), rankIface(b.getName())));
                for (NetworkInterface nif : list) {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                        if (addr instanceof Inet4Address) {
                            urls.add("ftp://" + addr.getHostAddress() + ":" + port
                                    + "/  (" + nif.getName() + ")");
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (urls.isEmpty()) {
            urls.add("ftp://<tailscale-ip>:" + port + "/");
        }
        return urls;
    }

    private static String joinUrls(List<String> urls) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) body.append('\n');
            body.append(urls.get(i));
        }
        return body.toString();
    }

    private static int rankIface(String name) {
        if (name == null) return 50;
        String n = name.toLowerCase();
        if (n.contains("tailscale") || n.equals("tun0")) return 0;
        if (n.startsWith("eth")) return 1;
        if (n.startsWith("wlan")) return 10;
        return 20;
    }

    private void restoreLastSync() {
        if (syncStore == null) {
            lastSync = "";
            return;
        }
        Context localized = AppLocale.wrap(this);
        long at = syncStore.lastAt();
        if (at <= 0) {
            lastSync = "";
            return;
        }
        String when = formatLastSyncWhen(at);
        if (syncStore.lastOk()) {
            lastSync = localized.getString(R.string.photos_last_sync_ok,
                    when, syncStore.lastCopied(), syncStore.lastSkipped(),
                    syncStore.lastDeleted());
        } else {
            lastSync = localized.getString(R.string.photos_last_sync_fail,
                    when, lastErrorLabel(localized, syncStore.lastError()));
        }
    }

    private String formatLastSyncWhen(long timeMs) {
        Calendar calendar = Calendar.getInstance(
                syncStore != null ? syncStore.zone() : java.util.TimeZone.getDefault());
        calendar.setTimeInMillis(timeMs);
        Calendar now = Calendar.getInstance(calendar.getTimeZone());
        String time = String.format(java.util.Locale.US, "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
        if (calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && calendar.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
            return time;
        }
        return String.format(java.util.Locale.US, "%02d/%02d %s",
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.MONTH) + 1,
                time);
    }

    private String lastErrorLabel(Context localized, String error) {
        if ("missing-user".equals(error)) {
            return localized.getString(R.string.photos_sync_no_user);
        }
        if ("hd-unmounted".equals(error)) {
            return localized.getString(R.string.photos_sync_need_hd);
        }
        if ("local-user".equals(error)) {
            return localized.getString(R.string.photos_sync_local_user);
        }
        if (error == null || error.isEmpty()) return "—";
        return error;
    }

    private void publish() {
        restoreLastSync();
        Context localized = AppLocale.wrap(this);
        String last = lastSync == null || lastSync.isEmpty()
                ? localized.getString(R.string.photos_never_sync) : lastSync;
        String disksBlob = "";
        if (disksEncoded != null && disksEncoded.length > 0) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < disksEncoded.length; i++) {
                if (i > 0) joined.append('\n');
                joined.append(disksEncoded[i]);
            }
            disksBlob = joined.toString();
        }
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_DISKS, disksBlob)
                .putExtra(EXTRA_HD, hdStatus)
                .putExtra(EXTRA_FTP, ftpStatus)
                .putExtra(EXTRA_SYNC, syncStatus)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_FTP_HINT, ftpHint())
                .putExtra(EXTRA_FILE_COUNT, fileCount)
                .putExtra(EXTRA_LAST_SYNC, last)
                .putExtra(EXTRA_MOUNTED, mounted)
                .putExtra(EXTRA_EJECTED, ejected)
                .putExtra(EXTRA_FTP_RUNNING, ftpServer.isRunning())
                .putExtra(EXTRA_SELECTED, selectedSpec)
                .putExtra(EXTRA_SYNCING, isCopyUiActive())
                .putExtra(EXTRA_PAUSED, syncStore != null && syncStore.paused());
        VesperaInternalStorage.Usage usage = VesperaInternalStorage.lastKnown();
        if (usage != null) {
            intent.putExtra(EXTRA_STORAGE_PERCENT, usage.usedPercent)
                    .putExtra(EXTRA_STORAGE_LABEL, usage.label);
        }
        sendBroadcast(intent);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (syncStore != null) syncStore.markInterrupted(this);
        if (SystemSettingsStore.from(this).keepAlive()) {
            SystemActivityLog.record(this, SystemActivityLog.KIND_KEEP_ALIVE,
                    SystemActivityLog.DETAIL_OK);
            restartSelf(SystemSettingsStore.from(this).resumeSync() && shouldResume());
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        if (current == this) current = null;
        boolean resume = syncing || shouldResume();
        if (resume && syncStore != null) syncStore.markInterrupted(this);
        mainHandler.removeCallbacks(tick);
        mainHandler.removeCallbacks(autoSyncAlarm);
        mainHandler.removeCallbacks(hourlyStorageCheck);
        mainHandler.removeCallbacks(sunTooHighAlarm);
        mainHandler.removeCallbacks(weatherAlarm);
        stopStatusWatch();
        if (connectionReceiverRegistered) {
            try { unregisterReceiver(connectionReceiver); } catch (Exception ignored) {}
            connectionReceiverRegistered = false;
        }
        if (clockReceiverRegistered) {
            try { unregisterReceiver(clockReceiver); } catch (Exception ignored) {}
            clockReceiverRegistered = false;
        }
        ftpServer.stop();
        telescopeFtp.stop();
        if (statusHub != null) {
            statusHub.removeListener(statusListener);
            if (telegramNotifier != null) statusHub.removeListener(telegramNotifier);
        }
        if (hud != null) hud.hide();
        worker.shutdownNow();
        syncExecutor.shutdownNow();
        if (SystemSettingsStore.from(this).keepAlive()) {
            restartSelf(resume && SystemSettingsStore.from(this).resumeSync());
        }
        super.onDestroy();
    }

    private void restartSelf(boolean resume) {
        try {
            startForegroundService(new Intent(getApplicationContext(), PhotoSyncService.class)
                    .setAction(resume ? ACTION_RESUME : ACTION_BOOTSTRAP));
        } catch (Exception ignored) {
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
