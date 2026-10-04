package com.vaonis.vesperahelper;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single differ for telescope status. UI, automations and Telegram subscribe here
 * instead of each polling {@code /app/status} on their own.
 */
final class TelescopeStatusHub {
    interface Listener {
        void onTelescopeEvent(TelescopeStatusEvent event);
    }

    private static final String TAG = "VesperaStatusHub";
    private static final int HD_HIGH_PERCENT = PhotoSyncService.HD_WARNING_PERCENT;
    private static final int HD_RESET_PERCENT = 75;
    private static final int STORAGE_HIGH_PERCENT = PhotoSyncService.STORAGE_SYNC_PERCENT;
    private static final int STORAGE_INTERNAL_HIGH_PERCENT = 80;
    private static final int STORAGE_INTERNAL_RESET_PERCENT = 75;
    private static final int BATTERY_LOW_PERCENT = 20;
    private static final int BATTERY_LOW_RESET_PERCENT = 25;

    private static final Object INSTANCE_LOCK = new Object();
    private static TelescopeStatusHub instance;

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object stateLock = new Object();

    private VesperaStatusSnapshot lastSnap;
    private long lastSnapAt;
    private boolean baselineReady;
    private String lastObs = "";
    private boolean lastInitialized;
    private boolean lastInitOk;
    private boolean lastOnMains;
    private boolean lastOffMains;
    private boolean lastShuttingDown;
    private boolean lastSunTooHigh;
    private boolean lastTracking;
    /** AUTO_INIT was running on the previous snapshot. */
    private boolean initRunningSeen;
    /** Already sent INITIALIZED for the current init cycle. */
    private boolean initNotified;
    /** Last AUTO_INIT id that already finished after focus. */
    private String lastFinishedInitId = "";
    private String lastError = "";
    private String lastInitFailure = "";
    private int lastBattery = -1;
    private boolean batteryLowLatched;
    private int lastHdPercent = -1;
    private boolean hdHighLatched;
    private int lastStoragePercent = -1;
    private boolean storageHighLatched;
    private boolean storageInternalHighLatched;
    private Boolean connected;

    private TelescopeStatusHub() {}

    static TelescopeStatusHub ensure() {
        synchronized (INSTANCE_LOCK) {
            if (instance == null) instance = new TelescopeStatusHub();
            return instance;
        }
    }

    static TelescopeStatusHub get() {
        synchronized (INSTANCE_LOCK) {
            return instance;
        }
    }

    void addListener(Listener listener) {
        if (listener == null) return;
        listeners.remove(listener);
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    VesperaStatusSnapshot lastSnapshot() {
        synchronized (stateLock) {
            return lastSnap;
        }
    }

    long snapshotAgeMs() {
        synchronized (stateLock) {
            if (lastSnapAt <= 0) return Long.MAX_VALUE;
            return Math.max(0L, System.currentTimeMillis() - lastSnapAt);
        }
    }

    void ingestConnection(boolean nowConnected) {
        List<TelescopeStatusEvent> events = new ArrayList<>(2);
        synchronized (stateLock) {
            if (connected != null && connected == nowConnected) return;
            boolean was = connected != null && connected;
            connected = nowConnected;
            if (nowConnected && !was) {
                resetInstrumentBaselineLocked();
                events.add(TelescopeStatusEvent.of(TelescopeStatusEvent.Kind.CONNECTED));
            } else if (!nowConnected && was) {
                resetInstrumentBaselineLocked();
                events.add(TelescopeStatusEvent.of(TelescopeStatusEvent.Kind.LOST));
            }
        }
        dispatch(events);
    }

    void ingestShutdown() {
        List<TelescopeStatusEvent> events = new ArrayList<>(1);
        synchronized (stateLock) {
            if (lastShuttingDown) return;
            lastShuttingDown = true;
            events.add(TelescopeStatusEvent.of(TelescopeStatusEvent.Kind.SHUTTING_DOWN, lastSnap));
        }
        dispatch(events);
    }

    void ingestHd(int usedPercent) {
        List<TelescopeStatusEvent> events = new ArrayList<>(1);
        synchronized (stateLock) {
            diffHdLocked(usedPercent, events);
        }
        dispatch(events);
    }

    void ingestInternalStorage(int usedPercent) {
        List<TelescopeStatusEvent> events = new ArrayList<>(2);
        synchronized (stateLock) {
            diffStorageLocked(usedPercent, lastSnap != null && lastSnap.isTrackingAcquisition(),
                    events);
        }
        dispatch(events);
    }

    void ingestSnapshot(VesperaStatusSnapshot snap) {
        if (snap == null) return;
        List<TelescopeStatusEvent> events = new ArrayList<>();
        synchronized (stateLock) {
            if (!baselineReady) {
                captureBaselineLocked(snap);
                return;
            }
            diffSnapshotLocked(snap, events);
            lastSnap = snap;
            lastSnapAt = System.currentTimeMillis();
        }
        dispatch(events);
    }

    private void resetInstrumentBaselineLocked() {
        baselineReady = false;
        lastSnap = null;
        lastSnapAt = 0;
        lastObs = "";
        lastInitialized = false;
        lastInitOk = false;
        lastOnMains = false;
        lastOffMains = false;
        lastShuttingDown = false;
        lastSunTooHigh = false;
        lastTracking = false;
        initRunningSeen = false;
        initNotified = false;
        lastFinishedInitId = "";
        lastError = "";
        lastInitFailure = "";
        lastBattery = -1;
        batteryLowLatched = false;
        lastStoragePercent = -1;
        storageHighLatched = false;
        storageInternalHighLatched = false;
    }

    private void captureBaselineLocked(VesperaStatusSnapshot snap) {
        lastSnap = snap;
        lastSnapAt = System.currentTimeMillis();
        lastObs = snap.observationStatus == null ? "" : snap.observationStatus;
        // A finished AUTO_INIT left in previousOperations must not count as
        // "already initialized": the arm is often parked and uncalibrated.
        lastInitialized = snap.initialized || snap.azAltCalibrated();
        initRunningSeen = snap.isAutoInitRunning();
        initNotified = false;
        lastFinishedInitId = snap.finishedAutoInitAfterFocusId();
        lastInitOk = snap.isAutoInitFinishedOk();
        lastOnMains = snap.isOnMainsPower();
        lastOffMains = snap.isOffMainsPower();
        lastShuttingDown = snap.isShuttingDown();
        lastSunTooHigh = snap.isEndedObservationSunTooHigh();
        lastTracking = snap.isTrackingAcquisition();
        lastError = snap.error == null ? "" : snap.error.trim();
        lastInitFailure = snap.autoInitFailure(true);
        lastBattery = snap.batteryPercent;
        if (snap.storageUsedPercent >= 0) lastStoragePercent = snap.storageUsedPercent;
        batteryLowLatched = snap.batteryPercent >= 0 && snap.batteryPercent < BATTERY_LOW_PERCENT;
        storageHighLatched = false;
        storageInternalHighLatched = false;
        baselineReady = true;
        Log.i(TAG, "baseline obs=" + lastObs + " init=" + lastInitialized
                + " finishedInit=" + lastFinishedInitId
                + " storage=" + lastStoragePercent);
    }

    private void diffSnapshotLocked(VesperaStatusSnapshot snap, List<TelescopeStatusEvent> events) {
        boolean ready = snap.initialized || snap.azAltCalibrated();
        boolean initRunning = snap.isAutoInitRunning();
        String finishedInitId = snap.finishedAutoInitAfterFocusId();
        if (initRunning && !initRunningSeen) initNotified = false;
        // Motor calibration happens at SEEK_STOP, near the start. Notify only
        // when AUTO_INIT has stopped and autofocus is already in the result.
        boolean initJustFinished = !initRunning
                && !finishedInitId.isEmpty()
                && !finishedInitId.equals(lastFinishedInitId);
        if (!initNotified && initJustFinished) {
            events.add(TelescopeStatusEvent.of(TelescopeStatusEvent.Kind.INITIALIZED, snap));
            initNotified = true;
        }
        if (!initRunning && finishedInitId.isEmpty() && !initJustFinished) initNotified = false;
        if (!finishedInitId.isEmpty()) lastFinishedInitId = finishedInitId;
        lastInitialized = ready;
        initRunningSeen = initRunning;
        lastInitOk = snap.isAutoInitFinishedOk();

        boolean shutting = snap.isShuttingDown();
        if (shutting && !lastShuttingDown) {
            events.add(TelescopeStatusEvent.of(TelescopeStatusEvent.Kind.SHUTTING_DOWN, snap));
        }
        lastShuttingDown = shutting;

        boolean onMains = snap.isOnMainsPower();
        boolean offMains = snap.isOffMainsPower();
        if (offMains && !lastOffMains) {
            events.add(TelescopeStatusEvent.of(TelescopeStatusEvent.Kind.POWER_OFF_MAINS, snap));
        } else if (onMains && !lastOnMains && lastOffMains) {
            events.add(TelescopeStatusEvent.of(TelescopeStatusEvent.Kind.POWER_ON_MAINS, snap));
        }
        lastOnMains = onMains;
        lastOffMains = offMains;

        int battery = snap.batteryPercent;
        if (battery >= 0) {
            if (!batteryLowLatched && battery < BATTERY_LOW_PERCENT) {
                batteryLowLatched = true;
                events.add(new TelescopeStatusEvent(TelescopeStatusEvent.Kind.BATTERY_LOW, snap,
                        TelescopeStatusEvent.targetOf(snap), battery, snap.error));
            } else if (batteryLowLatched && battery >= BATTERY_LOW_RESET_PERCENT) {
                batteryLowLatched = false;
            }
            lastBattery = battery;
        }

        String obs = snap.observationStatus == null ? "" : snap.observationStatus;
        if (!obs.equals(lastObs)) {
            if ("RUNNING".equals(obs)) {
                TelescopeStatusEvent.Kind kind = "STOPPED".equals(lastObs)
                        ? TelescopeStatusEvent.Kind.OBS_RESUMED
                        : TelescopeStatusEvent.Kind.OBS_STARTED;
                events.add(TelescopeStatusEvent.of(kind, snap));
            } else if ("RUNNING".equals(lastObs) && "STOPPED".equals(obs)) {
                events.add(TelescopeStatusEvent.of(TelescopeStatusEvent.Kind.OBS_STOPPED, snap));
            } else if ("RUNNING".equals(lastObs) && "FINISHED".equals(obs)) {
                events.add(TelescopeStatusEvent.of(TelescopeStatusEvent.Kind.OBS_FINISHED, snap));
            }
            lastObs = obs;
        }

        boolean sun = snap.isEndedObservationSunTooHigh();
        if (sun && !lastSunTooHigh) {
            events.add(TelescopeStatusEvent.of(TelescopeStatusEvent.Kind.SUN_TOO_HIGH, snap));
        }
        lastSunTooHigh = sun;

        String error = snap.error == null ? "" : snap.error.trim();
        String initFail = snap.autoInitFailure(true);
        if (!sun) {
            if (!error.isEmpty() && !error.equals(lastError)) {
                events.add(new TelescopeStatusEvent(TelescopeStatusEvent.Kind.ERROR, snap,
                        TelescopeStatusEvent.targetOf(snap), -1, error));
            } else if (!initFail.isEmpty() && !initFail.equals(lastInitFailure)) {
                events.add(new TelescopeStatusEvent(TelescopeStatusEvent.Kind.ERROR, snap,
                        TelescopeStatusEvent.targetOf(snap), -1, initFail));
            }
        }
        lastError = error;
        lastInitFailure = initFail;

        boolean tracking = snap.isTrackingAcquisition();
        if (snap.storageUsedPercent >= 0) {
            diffStorageLocked(snap.storageUsedPercent, tracking, events);
        }
        lastTracking = tracking;
    }

    private void diffHdLocked(int usedPercent, List<TelescopeStatusEvent> events) {
        if (usedPercent < 0) return;
        if (!hdHighLatched && usedPercent >= HD_HIGH_PERCENT) {
            hdHighLatched = true;
            events.add(new TelescopeStatusEvent(TelescopeStatusEvent.Kind.HD_HIGH, lastSnap,
                    "", usedPercent, ""));
        } else if (hdHighLatched && usedPercent < HD_RESET_PERCENT) {
            hdHighLatched = false;
        }
        lastHdPercent = usedPercent;
    }

    private void diffStorageLocked(int usedPercent, boolean tracking,
            List<TelescopeStatusEvent> events) {
        if (usedPercent < 0) return;
        boolean wasTracking = lastTracking;
        if (tracking && usedPercent >= STORAGE_HIGH_PERCENT
                && (!storageHighLatched || !wasTracking)) {
            storageHighLatched = true;
            events.add(new TelescopeStatusEvent(TelescopeStatusEvent.Kind.STORAGE_HIGH, lastSnap,
                    "", usedPercent, ""));
        } else if (usedPercent < STORAGE_HIGH_PERCENT) {
            storageHighLatched = false;
        }
        if (!storageInternalHighLatched && usedPercent >= STORAGE_INTERNAL_HIGH_PERCENT) {
            storageInternalHighLatched = true;
            events.add(new TelescopeStatusEvent(TelescopeStatusEvent.Kind.STORAGE_INTERNAL_HIGH,
                    lastSnap, "", usedPercent, ""));
        } else if (storageInternalHighLatched && usedPercent < STORAGE_INTERNAL_RESET_PERCENT) {
            storageInternalHighLatched = false;
        }
        lastStoragePercent = usedPercent;
        lastTracking = tracking;
    }

    private void dispatch(List<TelescopeStatusEvent> events) {
        if (events == null || events.isEmpty()) return;
        for (TelescopeStatusEvent event : events) {
            Log.i(TAG, "event " + event.kind
                    + (event.target.isEmpty() ? "" : (" target=" + event.target))
                    + (event.percent >= 0 ? (" pct=" + event.percent) : "")
                    + (event.error.isEmpty() ? "" : (" err=" + event.error)));
            for (Listener listener : listeners) {
                try {
                    listener.onTelescopeEvent(event);
                } catch (RuntimeException failure) {
                    Log.w(TAG, "listener " + failure.getMessage());
                }
            }
        }
    }
}
