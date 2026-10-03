package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists the USB HD chosen for photo sync and auto-mount after the first success. */
public final class UsbHdStore {
    private static final String PREFS = "vespera_usb_hd";
    private static final String KEY_NAME = "name";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_LABEL = "label";
    private static final String KEY_FSTYPE = "fstype";
    private static final String KEY_SIZE = "size";
    private static final String KEY_AUTO_MOUNT = "auto_mount";
    /** USB VBUS cut (manual or morning). Stays off until Attiva HD. */
    private static final String KEY_POWERED_OFF = "powered_off";

    private final SharedPreferences prefs;

    private UsbHdStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public static UsbHdStore from(Context context) {
        return new UsbHdStore(
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    public boolean isConfigured() {
        return !getSpec().isEmpty();
    }

    public boolean isAutoMount() {
        return prefs.getBoolean(KEY_AUTO_MOUNT, false) && isConfigured();
    }

    /** True after a real USB power-off. Survives a service restart so boot does not wake the disk. */
    public boolean isPoweredOff() {
        return prefs.getBoolean(KEY_POWERED_OFF, false);
    }

    public void setPoweredOff(boolean poweredOff) {
        prefs.edit().putBoolean(KEY_POWERED_OFF, poweredOff).apply();
    }

    public String getName() { return prefs.getString(KEY_NAME, ""); }
    public String getUuid() { return prefs.getString(KEY_UUID, ""); }
    public String getLabel() { return prefs.getString(KEY_LABEL, ""); }
    public String getFstype() { return prefs.getString(KEY_FSTYPE, ""); }
    public String getSize() { return prefs.getString(KEY_SIZE, ""); }

    public String getSpec() {
        String uuid = getUuid();
        if (uuid != null && uuid.length() >= 4 && !"-".equals(uuid)) return uuid;
        String name = getName();
        return name == null ? "" : name;
    }

    public String displayName() {
        String label = getLabel();
        if (label != null && !label.isEmpty() && !"-".equals(label)) return label;
        String name = getName();
        return name == null || name.isEmpty() ? "USB" : name;
    }

    public void save(UsbDisk disk) {
        if (disk == null) return;
        prefs.edit()
                .putString(KEY_NAME, disk.name)
                .putString(KEY_UUID, disk.uuid)
                .putString(KEY_LABEL, disk.label)
                .putString(KEY_FSTYPE, disk.fstype)
                .putString(KEY_SIZE, disk.size)
                .apply();
    }

    public void saveMounted(UsbDisk disk) {
        save(disk);
        prefs.edit().putBoolean(KEY_AUTO_MOUNT, true).apply();
    }

    public void rememberMount(String uuid, String label, String dev) {
        SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_AUTO_MOUNT, true);
        if (uuid != null && !uuid.isEmpty() && !"-".equals(uuid)) {
            editor.putString(KEY_UUID, uuid);
        }
        if (label != null && !label.isEmpty() && !"-".equals(label)) {
            editor.putString(KEY_LABEL, label);
        }
        if (dev != null && !dev.isEmpty()) {
            String name = dev;
            int slash = Math.max(dev.lastIndexOf('/'), dev.lastIndexOf('\\'));
            if (slash >= 0 && slash + 1 < dev.length()) name = dev.substring(slash + 1);
            editor.putString(KEY_NAME, name);
        }
        editor.apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
