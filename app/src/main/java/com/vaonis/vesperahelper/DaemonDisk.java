package com.vaonis.vesperahelper;

import android.content.Context;
import android.os.StatFs;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Talks to {@code vespera-netd.sh} for USB disk list / mount / unmount. */
public final class DaemonDisk {
    private static final String TAG = "VesperaDisk";
    static final String DISKS_ACK = "disks.ack";
    static final String MOUNT_ACK = "mount.ack";
    private static final long LIST_TIMEOUT_MS = 10_000;
    private static final long MOUNT_TIMEOUT_MS = 15_000;

    public static final class MountStatus {
        public final boolean mounted;
        public final String uuid;
        public final String label;
        public final String device;
        public final String path;
        public final String raw;
        public final boolean timeout;

        MountStatus(boolean mounted, String uuid, String label, String device, String path,
                    String raw, boolean timeout) {
            this.mounted = mounted;
            this.uuid = uuid == null ? "-" : uuid;
            this.label = label == null ? "-" : label;
            this.device = device == null ? "-" : device;
            this.path = path == null ? "" : path;
            this.raw = raw == null ? "" : raw;
            this.timeout = timeout;
        }

        static MountStatus unmounted(String raw) {
            return new MountStatus(false, "-", "-", "-", "", raw, false);
        }

        static MountStatus timeout() {
            return new MountStatus(false, "-", "-", "-", "", "", true);
        }
    }

    public static final class Space {
        public final boolean known;
        public final int usedPercent;
        public final long usedBytes;
        public final long totalBytes;

        Space(boolean known, int usedPercent, long usedBytes, long totalBytes) {
            this.known = known;
            this.usedPercent = usedPercent;
            this.usedBytes = usedBytes;
            this.totalBytes = totalBytes;
        }

        static Space unknown() {
            return new Space(false, -1, 0, 0);
        }

        public String label() {
            if (!known || usedPercent < 0 || totalBytes <= 0) return "";
            return usedPercent + "%  ·  " + formatBytes(usedBytes) + " / " + formatBytes(totalBytes);
        }
    }

    private DaemonDisk() {}

    /** Occupied space on the mounted USB HD bind, if present. */
    public static Space photosSpace(Context context) {
        File dir = photosDir(context);
        if (!isPhotosBoundLive(dir)) return Space.unknown();
        try {
            StatFs fs = new StatFs(dir.getAbsolutePath());
            long total = fs.getTotalBytes();
            long free = fs.getAvailableBytes();
            if (total <= 0) return Space.unknown();
            long used = Math.max(0, total - Math.max(0, free));
            int percent = (int) Math.round(100.0 * used / (double) total);
            if (percent > 100) percent = 100;
            return new Space(true, percent, used, total);
        } catch (IllegalArgumentException ignored) {
            return Space.unknown();
        }
    }

    private static String formatBytes(long bytes) {
        double tb = bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0);
        if (tb >= 1) return String.format(java.util.Locale.US, "%.1f TB", tb);
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1) return String.format(java.util.Locale.US, "%.1f GB", gb);
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(java.util.Locale.US, "%.0f MB", mb);
    }

    public static File photosDir(Context context) {
        File emulatedParent = context.getExternalFilesDir(null);
        File emulated = emulatedParent == null ? null : new File(emulatedParent, "vespera-photos");
        File media = new File("/data/media/0/Android/data/com.vaonis.vesperahelper/files/vespera-photos");
        File sdcard = new File("/sdcard/Android/data/com.vaonis.vesperahelper/files/vespera-photos");
        File[] candidates = new File[] { emulated, media, sdcard };
        File fallback = emulated != null ? emulated : media;
        for (File candidate : candidates) {
            if (looksLikeMountedPhotos(candidate)) return candidate;
        }
        for (File candidate : candidates) {
            if (candidate != null && candidate.isDirectory() && candidate.canWrite()) return candidate;
        }
        return fallback;
    }

    /** True when the app-visible photos folder is a live bind of the USB HD. */
    public static boolean isPhotosBoundLive(File dir) {
        return looksLikeMountedPhotos(dir);
    }

    private static boolean looksLikeMountedPhotos(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        // The mount table is authoritative: a bind of the bare /mnt/vespera-hd
        // dir is tmpfs (RAM) and differs in st_dev too, so it fooled the
        // checks below — photos went to RAM and StatFs showed it ~full.
        Boolean block = photosMountIsBlock();
        if (block != null) return block;
        File parent = dir.getParentFile();
        if (parent != null) {
            try {
                StructStat dirStat = Os.stat(dir.getAbsolutePath());
                StructStat parentStat = Os.stat(parent.getAbsolutePath());
                if (dirStat.st_dev != parentStat.st_dev) return true;
            } catch (ErrnoException ignored) {
            }
        }
        File user = new File(dir, "USER");
        if (!user.isDirectory()) user = new File(dir, "user");
        if (user.isDirectory()) {
            File[] kids = user.listFiles();
            if (kids != null && kids.length > 0) return true;
        }
        return new File(dir, "$RECYCLE.BIN").isDirectory();
    }

    /**
     * Whether the topmost mount on the photos folder (any alias: /data/media,
     * pass_through, /storage) is a block device. {@code null} when the app's
     * mount table has no entry for it.
     */
    static Boolean photosMountIsBlock() {
        String suffix = "/" + BuildConfig.APPLICATION_ID + "/files/vespera-photos";
        // mountpoint -> source of the last (topmost) entry
        Map<String, String> top = new HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/self/mounts"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.split(" ");
                if (f.length < 3 || !f[1].endsWith(suffix)) continue;
                top.put(f[1], "tmpfs".equals(f[2]) ? "tmpfs" : f[0]);
            }
        } catch (IOException ignored) {
            return null;
        }
        if (top.isEmpty()) return null;
        for (String source : top.values()) {
            if (source.startsWith("/dev/block/") || source.startsWith("/dev/sd")) return true;
        }
        return false;
    }

    public static MountStatus ensureBind(Context context) {
        String ack = request(context, "ensure-bind", MOUNT_ACK, LIST_TIMEOUT_MS);
        return parseMount(ack);
    }

    public static List<UsbDisk> listDisks(Context context) {
        String ack = request(context, "list-disks", DISKS_ACK, LIST_TIMEOUT_MS);
        return UsbDisk.parseList(ack);
    }

    public static String listRaw(Context context) {
        return request(context, "list-disks", DISKS_ACK, LIST_TIMEOUT_MS);
    }

    public static MountStatus mount(Context context, String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return MountStatus.unmounted("mount-bad-args");
        }
        String ack = request(context, "mount-disk|" + spec.trim(), MOUNT_ACK, MOUNT_TIMEOUT_MS);
        return parseMount(ack);
    }

    public static MountStatus unmount(Context context) {
        return unmount(context, null);
    }

    public static MountStatus unmount(Context context, String spec) {
        String cmd = (spec == null || spec.trim().isEmpty())
                ? "umount-disk" : "umount-disk|" + spec.trim();
        String ack = request(context, cmd, MOUNT_ACK, MOUNT_TIMEOUT_MS);
        return parseMount(ack);
    }

    public static MountStatus eject(Context context, String spec) {
        String cmd = (spec == null || spec.trim().isEmpty())
                ? "eject-disk" : "eject-disk|" + spec.trim();
        String ack = request(context, cmd, MOUNT_ACK, MOUNT_TIMEOUT_MS);
        return parseMount(ack);
    }

    /** Re-probe USB after SCSI eject so list/mount can see the disk again. */
    public static String wake(Context context) {
        return request(context, "wake-disk", DISKS_ACK, MOUNT_TIMEOUT_MS);
    }

    public static MountStatus status(Context context) {
        String ack = request(context, "disk-status", MOUNT_ACK, LIST_TIMEOUT_MS);
        return parseMount(ack);
    }

    /** Ask vespera-netd to power off the Pi (after HD eject). */
    public static boolean shutdownPi(Context context) {
        String ack = request(context, "shutdown-pi", MOUNT_ACK, 8_000);
        if (ack == null) return false;
        return firstLine(ack).startsWith("shutdown-pi-ok");
    }

    public static MountStatus parseMount(String ack) {
        if (ack == null) return MountStatus.timeout();
        String line = firstLine(ack);
        if (line.startsWith("mounted|")) {
            String[] p = line.split("\\|", -1);
            return new MountStatus(true,
                    p.length > 1 ? p[1] : "-",
                    p.length > 2 ? p[2] : "-",
                    p.length > 3 ? p[3] : "-",
                    p.length > 4 ? p[4] : "",
                    line, false);
        }
        if (line.startsWith("ejected|")) {
            String[] p = line.split("\\|", -1);
            return new MountStatus(false, "-", "-", p.length > 1 ? p[1] : "-", "", line, false);
        }
        if (line.startsWith("mount-unsupported-ntfs")) {
            return new MountStatus(false, "-", "-", "-", "", line, false);
        }
        if (line.isEmpty()) return MountStatus.timeout();
        return new MountStatus(false, "-", "-", "-", "", line, false);
    }

    private static String request(Context context, String command, String ackName, long timeoutMs) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        File ack = new File(dir, ackName);
        if (ack.exists() && !ack.delete()) {
            Log.w(TAG, "could not clear " + ackName);
        }
        boolean written = VesperaConnectionService.writeDiskRequest(context, command);
        if (!written) {
            Log.w(TAG, "failed to write " + command);
            return null;
        }
        long start = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (ack.exists() && ack.length() > 0) {
                try {
                    Thread.sleep(80);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return readQuietly(ack);
                }
                return readQuietly(ack);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Log.w(TAG, "timeout waiting for " + ackName + " after " + command);
        return null;
    }

    private static String readQuietly(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 32_768)];
            int n = in.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String firstLine(String ack) {
        int nl = ack.indexOf('\n');
        String line = nl < 0 ? ack : ack.substring(0, nl);
        return line.replace("\r", "").trim();
    }
}
