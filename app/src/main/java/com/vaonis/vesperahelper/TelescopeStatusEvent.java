package com.vaonis.vesperahelper;

/** One status transition from {@link TelescopeStatusHub}. */
final class TelescopeStatusEvent {
    enum Kind {
        CONNECTED,
        LOST,
        INITIALIZED,
        SHUTTING_DOWN,
        POWER_OFF_MAINS,
        POWER_ON_MAINS,
        BATTERY_LOW,
        HD_HIGH,
        STORAGE_HIGH,
        STORAGE_INTERNAL_HIGH,
        OBS_STARTED,
        OBS_RESUMED,
        OBS_STOPPED,
        OBS_FINISHED,
        ERROR,
        SUN_TOO_HIGH
    }

    final Kind kind;
    final VesperaStatusSnapshot snapshot;
    final String target;
    final int percent;
    final String error;

    TelescopeStatusEvent(Kind kind, VesperaStatusSnapshot snapshot, String target,
            int percent, String error) {
        this.kind = kind;
        this.snapshot = snapshot;
        this.target = target == null ? "" : target;
        this.percent = percent;
        this.error = error == null ? "" : error;
    }

    static TelescopeStatusEvent of(Kind kind) {
        return new TelescopeStatusEvent(kind, null, "", -1, "");
    }

    static TelescopeStatusEvent of(Kind kind, VesperaStatusSnapshot snap) {
        return new TelescopeStatusEvent(kind, snap, targetOf(snap),
                snap == null ? -1 : snap.storageUsedPercent,
                snap == null ? "" : snap.error);
    }

    static String targetOf(VesperaStatusSnapshot snap) {
        if (snap != null && !snap.targetName.isEmpty()) return snap.targetName;
        String last = VesperaLastTarget.label();
        return last == null ? "" : last;
    }
}
