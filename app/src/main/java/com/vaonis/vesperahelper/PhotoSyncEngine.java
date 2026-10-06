package com.vaonis.vesperahelper;

import android.net.Network;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Pull of Vespera {@code /USER} photos onto the mounted USB HD.
 * Each file is copied, flushed to the USB device, size-verified, then deleted
 * on the instrument — not in a batch at the end. Progress includes bytes
 * transferred and an ETA based on measured throughput.
 */
public final class PhotoSyncEngine {
    private static final String TAG = "VesperaSync";
    static final String REMOTE_ROOT = "/USER";
    static final String HOST = "10.0.0.1";
    static final int PORT = 21;

    public interface Listener {
        void onProgress(SyncProgress progress);
    }

    public static final class FileLine {
        public final String folder;
        public final String name;
        public final long bytes;
        public final String kind;

        FileLine(String folder, String name, long bytes, String kind) {
            this.folder = folder == null || folder.isEmpty() ? "USER" : folder;
            this.name = name == null ? "" : name;
            this.bytes = Math.max(0, bytes);
            this.kind = kind == null ? "copied" : kind;
        }
    }

    public static final class Result {
        public final int downloaded;
        public final int skipped;
        public final int deleted;
        public final int failed;
        public final long bytes;
        public final String error;
        public final List<String> folders;
        public final List<FileLine> files;
        public final String remoteRoot;

        Result(int downloaded, int skipped, int deleted, int failed, long bytes, String error) {
            this(downloaded, skipped, deleted, failed, bytes, error,
                    Collections.emptyList(), Collections.emptyList(), REMOTE_ROOT);
        }

        Result(int downloaded, int skipped, int deleted, int failed, long bytes, String error,
               List<String> folders, List<FileLine> files, String remoteRoot) {
            this.downloaded = downloaded;
            this.skipped = skipped;
            this.deleted = deleted;
            this.failed = failed;
            this.bytes = bytes;
            this.error = error;
            this.folders = folders == null ? Collections.emptyList() : folders;
            this.files = files == null ? Collections.emptyList() : files;
            this.remoteRoot = remoteRoot == null ? REMOTE_ROOT : remoteRoot;
        }

        public static Result error(String message) {
            return new Result(0, 0, 0, 0, 0, message);
        }
    }

    private PhotoSyncEngine() {}

    public static Result sync(Network network, File localRoot, Listener listener) {
        return sync(network, localRoot, listener, null);
    }

    public static Result sync(Network network, File localRoot, Listener listener, BooleanSupplier pause) {
        if (localRoot == null || !localRoot.isDirectory()) {
            return Result.error("hd-unmounted");
        }
        if (Boolean.FALSE.equals(DaemonDisk.photosMountIsBlock())) {
            return Result.error("hd-ram");
        }
        // Started on the USB HD: if it drops mid-sync, stop before any more
        // telescope deletes (the folder may fall back to RAM or internal).
        final boolean startedOnHd = DaemonDisk.isPhotosBoundLive(localRoot);
        File userDir = resolveOrCreateLocalUser(localRoot);
        if (userDir == null) {
            return Result.error("local-user");
        }
        purgePartFiles(userDir);
        SyncProgress progress = new SyncProgress();
        progress.active = true;
        progress.phase = SyncProgress.PHASE_LIST;
        publish(listener, progress);
        SpeedTracker speed = new SpeedTracker();
        try (CommonsFtpClient ftp = new CommonsFtpClient(network, HOST)) {
            int port = FtpProbe.findVesperaControl(network, HOST);
            if (port <= 0) {
                return Result.error("FTP Vespera: nessuna porta in ascolto");
            }
            ftp.connect(port);
            String remoteRoot = ftp.resolveUserDir();
            Log.i(TAG, "listing " + remoteRoot + " on :" + port);
            List<CommonsFtpClient.Entry> remote = ftp.listRecursive(remoteRoot, (dir, n) -> {
                if (isPaused(pause)) throw new IOException("paused");
                progress.fileName = dir;
                progress.fileIndex = n;
                progress.fileTotal = Math.max(n, 1);
                progress.detail = dir;
                publish(listener, progress);
            });
            List<CommonsFtpClient.Entry> photos = new ArrayList<>();
            long totalBytes = 0;
            for (CommonsFtpClient.Entry entry : remote) {
                if (!entry.directory && isPhoto(entry.name)) {
                    photos.add(entry);
                    long size = entry.size > 0 ? entry.size : 0;
                    totalBytes += size;
                }
            }
            progress.fileTotal = photos.size();
            SyncQueue.begin(photos);
            progress.totalBytes = totalBytes;
            progress.detail = photos.isEmpty() ? "0" : String.valueOf(photos.size());
            publish(listener, progress);

            int downloaded = 0;
            int skipped = 0;
            int deleted = 0;
            int failed = 0;
            long bytes = 0;
            Set<String> dirs = new HashSet<>();
            java.util.LinkedHashMap<String, FileLine> tracked = new java.util.LinkedHashMap<>();

            int index = 0;
            for (CommonsFtpClient.Entry entry : photos) {
                if (isPaused(pause)) return pauseResult(progress, listener);
                if (startedOnHd && !DaemonDisk.isPhotosBoundLive(localRoot)) {
                    Log.w(TAG, "HD lost mid-sync — stop, telescope copies kept");
                    return Result.error("hd-lost");
                }
                index++;
                String relative = relativeUserPath(entry.path);
                File local = new File(userDir, relative);
                dirs.add(parentFtpPath(entry.path));
                long remoteSize = entry.size > 0 ? entry.size : ftp.sizeOf(entry.path);
                progress.fileIndex = index;
                progress.fileName = entry.name;
                progress.fileSize = Math.max(0, remoteSize);
                progress.fileBytes = 0;
                progress.copied = downloaded;
                progress.skipped = skipped;
                progress.deleted = deleted;
                progress.failed = failed;
                progress.phase = SyncProgress.PHASE_DOWNLOAD;
                SyncQueue.mark(index - 1, SyncQueue.ACTIVE);
                publish(listener, progress);

                boolean copiedNow = false;
                if (local.exists() && local.isFile() && remoteSize > 0 && local.length() == remoteSize) {
                    if (needsOriginalTime(local, entry)) keepOriginalTime(ftp, entry, local);
                    skipped++;
                    progress.skipped = skipped;
                    progress.doneBytes += remoteSize;
                    refreshEta(progress, speed);
                    publish(listener, progress);
                } else {
                    if (remoteSize > 0) {
                        File parent = local.getParentFile();
                        long free = parent != null ? parent.getFreeSpace() : localRoot.getFreeSpace();
                        if (free > 0 && free < remoteSize + 1_048_576L) {
                            failed++;
                            progress.failed = failed;
                            track(tracked, index, entry, remoteSize, "failed");
                            Log.w(TAG, "not enough space for " + entry.path);
                            continue;
                        }
                    }
                    try {
                        speed.resetFile();
                        final long already = progress.doneBytes;
                        ftp.retrieve(entry.path, local, transferred -> {
                            if (isPaused(pause)) throw new IOException("paused");
                            progress.fileBytes = transferred;
                            progress.doneBytes = already + transferred;
                            speed.sample(transferred);
                            refreshEta(progress, speed);
                            publish(listener, progress);
                        });
                        long expect = remoteSize > 0 ? remoteSize : local.length();
                        if (!local.isFile() || local.length() != expect) {
                            failed++;
                            progress.failed = failed;
                            track(tracked, index, entry, expect, "failed");
                            Log.w(TAG, "size mismatch " + entry.path + " local="
                                    + (local.exists() ? local.length() : -1) + " remote=" + expect);
                            if (local.exists()) {
                                //noinspection ResultOfMethodCallIgnored
                                local.delete();
                            }
                            continue;
                        }
                        keepOriginalTime(ftp, entry, local);
                        copiedNow = true;
                        downloaded++;
                        bytes += local.length();
                        progress.copied = downloaded;
                        progress.fileBytes = local.length();
                        if (remoteSize <= 0) {
                            progress.doneBytes = already + local.length();
                            progress.totalBytes += local.length();
                        } else {
                            progress.doneBytes = already + local.length();
                        }
                        refreshEta(progress, speed);
                        publish(listener, progress);
                    } catch (IOException failureEx) {
                        if (isPaused(pause) || "paused".equals(failureEx.getMessage())) {
                            return pauseResult(progress, listener);
                        }
                        failed++;
                        progress.failed = failed;
                        track(tracked, index, entry, remoteSize, "failed");
                        Log.w(TAG, "sync " + entry.path, failureEx);
                        continue;
                    }
                }

                if (isPaused(pause)) return pauseResult(progress, listener);
                progress.phase = SyncProgress.PHASE_DISK;
                publish(listener, progress);
                if (!syncFileToDisk(local)) {
                    failed++;
                    progress.failed = failed;
                    track(tracked, index, entry, local.length(), "failed");
                    Log.w(TAG, "disk sync fail " + entry.path);
                    continue;
                }

                if (isPaused(pause)) return pauseResult(progress, listener);
                progress.phase = SyncProgress.PHASE_VERIFY;
                long localSize = durableLength(local);
                progress.fileBytes = Math.max(0, localSize);
                publish(listener, progress);
                if (localSize < 0 || (remoteSize > 0 && localSize != remoteSize)) {
                    failed++;
                    progress.failed = failed;
                    track(tracked, index, entry, remoteSize, "failed");
                    Log.w(TAG, "verify fail " + entry.path + " local="
                            + localSize + " remote=" + remoteSize);
                    if (local.exists() && remoteSize > 0 && localSize != remoteSize) {
                        //noinspection ResultOfMethodCallIgnored
                        local.delete();
                    }
                    continue;
                }

                if (isPaused(pause)) return pauseResult(progress, listener);
                if (startedOnHd && !DaemonDisk.isPhotosBoundLive(localRoot)) {
                    Log.w(TAG, "HD lost before delete of " + entry.path);
                    return Result.error("hd-lost");
                }
                progress.phase = SyncProgress.PHASE_DELETE;
                publish(listener, progress);
                if (deleteRemote(ftp, entry.path)) {
                    deleted++;
                    progress.deleted = deleted;
                }
                long trackedSize = remoteSize > 0 ? remoteSize : Math.max(0, localSize);
                track(tracked, index, entry, trackedSize, copiedNow ? "copied" : "skipped");
                publish(listener, progress);
            }

            List<String> dirList = new ArrayList<>(dirs);
            Collections.sort(dirList, Comparator.reverseOrder());
            for (String dir : dirList) {
                if (dir == null || "/".equals(dir) || CommonsFtpClient.isUserDirName(dir)) {
                    continue;
                }
                ftp.removeDir(dir);
            }
            progress.copied = downloaded;
            progress.skipped = skipped;
            progress.deleted = deleted;
            progress.failed = failed;
            progress.phase = SyncProgress.PHASE_DONE;
            progress.etaMs = 0;
            progress.fileName = "";
            List<FileLine> fileLines = new ArrayList<>(tracked.values());
            List<String> folderNames = folderNames(fileLines);
            publish(listener, progress);
            return new Result(downloaded, skipped, deleted, failed, bytes, null,
                    folderNames, fileLines, remoteRoot);
        } catch (IOException failure) {
            Log.w(TAG, "sync failed", failure);
            if (isPaused(pause) || "paused".equals(failure.getMessage())) {
                return pauseResult(progress, listener);
            }
            SyncQueue.releaseActive();
            progress.phase = SyncProgress.PHASE_ERROR;
            progress.detail = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            publish(listener, progress);
            if ("missing-user".equals(failure.getMessage())) return Result.error("missing-user");
            return Result.error(failure.getMessage() != null
                    ? failure.getMessage() : failure.getClass().getSimpleName());
        } finally {
            progress.active = false;
        }
    }

    private static File resolveOrCreateLocalUser(File localRoot) {
        File upper = new File(localRoot, "USER");
        File lower = new File(localRoot, "user");
        if (upper.isDirectory()) return upper;
        if (lower.isDirectory()) return lower;
        if (upper.mkdirs()) return upper;
        if (lower.mkdirs()) return lower;
        Log.w(TAG, "cannot create local USER under " + localRoot
                + " canWrite=" + localRoot.canWrite());
        return null;
    }

    private static boolean isPaused(BooleanSupplier pause) {
        try {
            return pause != null && pause.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Result pauseResult(SyncProgress progress, Listener listener) {
        progress.phase = SyncProgress.PHASE_PAUSED;
        progress.active = false;
        SyncQueue.releaseActive();
        publish(listener, progress);
        return Result.error("paused");
    }

    static boolean isPhoto(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        if (n.startsWith(".")) return false;
        return n.endsWith(".fits") || n.endsWith(".fit") || n.endsWith(".fts")
                || n.endsWith(".tif") || n.endsWith(".tiff")
                || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".png") || n.endsWith(".raw")
                || n.endsWith(".cr2") || n.endsWith(".nef");
    }

    static int countLocalPhotos(File localRoot) {
        File user = localUserDir(localRoot);
        return countPhotos(user);
    }

    /**
     * Count photo files still on Vespera {@code /USER}. Returns {@code -1} if FTP
     * is unreachable or listing fails.
     */
    static int countRemotePhotos(Network network) {
        try (CommonsFtpClient ftp = new CommonsFtpClient(network, HOST)) {
            int port = FtpProbe.findVesperaControl(network, HOST);
            if (port <= 0) return -1;
            ftp.connect(port);
            String remoteRoot = ftp.resolveUserDir();
            List<CommonsFtpClient.Entry> remote = ftp.listRecursive(remoteRoot);
            int count = 0;
            for (CommonsFtpClient.Entry entry : remote) {
                if (entry != null && !entry.directory && isPhoto(entry.name)) count++;
            }
            Log.i(TAG, "remote USER photos remaining=" + count + " under " + remoteRoot);
            return count;
        } catch (Exception failure) {
            Log.w(TAG, "count remote USER photos failed", failure);
            return -1;
        }
    }

    static boolean hasIncomplete(File localRoot) {
        if (localRoot == null) return false;
        return hasPartFiles(localUserDir(localRoot));
    }

    private static File localUserDir(File localRoot) {
        if (localRoot == null) return null;
        File upper = new File(localRoot, "USER");
        if (upper.isDirectory()) return upper;
        File lower = new File(localRoot, "user");
        if (lower.isDirectory()) return lower;
        return upper;
    }

    static int purgePartFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int n = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                n += purgePartFiles(child);
            } else if (isPartFile(child.getName())) {
                if (child.delete()) n++;
            }
        }
        return n;
    }

    private static boolean hasPartFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.isDirectory()) {
                if (hasPartFiles(child)) return true;
            } else if (isPartFile(child.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPartFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".part") || n.endsWith(".partial");
    }

    private static int countPhotos(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int n = 0;
        for (File child : children) {
            if (child.isDirectory()) n += countPhotos(child);
            else if (isPhoto(child.getName())) n++;
        }
        return n;
    }

    /**
     * Makes one file durable on the USB HD before the remote original is deleted:
     * {@code fsync} of the file and its directory, then the process-wide {@code sync()}.
     */
    private static boolean syncFileToDisk(File file) {
        if (file == null || !file.isFile()) return false;
        try (FileInputStream in = new FileInputStream(file)) {
            in.getFD().sync();
        } catch (IOException failure) {
            Log.w(TAG, "fsync file " + file, failure);
            return false;
        }
        File parent = file.getParentFile();
        if (parent != null) {
            FileDescriptor dirFd = null;
            try {
                dirFd = Os.open(parent.getAbsolutePath(), OsConstants.O_RDONLY, 0);
                Os.fsync(dirFd);
            } catch (ErrnoException dirFail) {
                Log.w(TAG, "fsync dir " + parent + ": " + dirFail);
            } finally {
                if (dirFd != null) {
                    try {
                        Os.close(dirFd);
                    } catch (ErrnoException ignored) {
                    }
                }
            }
        }
        try {
            Process process = new ProcessBuilder("sync").redirectErrorStream(true).start();
            process.getInputStream().close();
            int code = process.waitFor();
            if (code != 0) {
                Log.w(TAG, "sync exit " + code);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "sync interrupted");
        } catch (IOException failure) {
            Log.w(TAG, "sync()", failure);
        }
        return true;
    }

    private static long durableLength(File file) {
        if (file == null) return -1;
        try {
            return Os.stat(file.getAbsolutePath()).st_size;
        } catch (ErrnoException ignored) {
            return file.isFile() ? file.length() : -1;
        }
    }

    /**
     * Copia già presente: va riallineata se la sua ora non coincide con quella del Vespera.
     * Il LIST non ha fuso orario (può essere spostato di ore intere rispetto a MDTM/UTC),
     * quindi si considera allineata una differenza di ore intere ±2 min.
     */
    private static boolean needsOriginalTime(File local, CommonsFtpClient.Entry entry) {
        if (entry.timeMs <= 0L || !isStackOutput(entry.name)) return false;
        long hour = 3_600_000L;
        long rest = Math.abs(local.lastModified() - entry.timeMs) % hour;
        return rest > 120_000L && rest < hour - 120_000L;
    }

    /** Solo gli stack *-output.jpg servono all'anteprima: pochi file, la copia non rallenta. */
    private static boolean isStackOutput(String name) {
        String low = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        return low.endsWith("-output.jpg") || low.endsWith("-output.jpeg");
    }

    /** Stack copiato sull'HD con l'ora originale del file sul Vespera (MDTM, poi LIST). */
    private static void keepOriginalTime(CommonsFtpClient ftp, CommonsFtpClient.Entry entry, File local) {
        if (!isStackOutput(entry.name)) return;
        long timeMs = ftp.modificationTime(entry.path);
        if (timeMs <= 0L) timeMs = entry.timeMs;
        if (timeMs <= 0L) return;
        if (!local.setLastModified(timeMs)) {
            Log.w(TAG, "setLastModified " + local);
        }
    }

    private static boolean deleteRemote(CommonsFtpClient ftp, String path) {
        try {
            ftp.deleteFile(path);
            return true;
        } catch (IOException failure) {
            Log.w(TAG, "delete " + path, failure);
            return false;
        }
    }

    private static void track(java.util.Map<String, FileLine> tracked, int index,
                              CommonsFtpClient.Entry entry, long size, String kind) {
        tracked.put(entry.path, fileLine(entry, size, kind));
        SyncQueue.mark(index - 1, kind);
    }

    private static FileLine fileLine(CommonsFtpClient.Entry entry, long size, String kind) {
        String relative = relativeUserPath(entry.path);
        String folder = parentFolder(relative);
        return new FileLine(folder, entry.name, size, kind);
    }

    private static String parentFolder(String relative) {
        if (relative == null || relative.isEmpty()) return "USER";
        int slash = relative.lastIndexOf('/');
        if (slash <= 0) return "USER";
        return relative.substring(0, slash);
    }

    private static List<String> folderNames(List<FileLine> files) {
        List<String> names = new ArrayList<>();
        for (FileLine line : files) {
            if (line.folder != null && !names.contains(line.folder)) names.add(line.folder);
        }
        Collections.sort(names);
        return names;
    }

    private static String relativeUserPath(String ftpPath) {
        String path = ftpPath.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        if (path.length() >= 4 && path.regionMatches(true, 0, "user", 0, 4)
                && (path.length() == 4 || path.charAt(4) == '/')) {
            return path.length() == 4 ? "" : path.substring(5);
        }
        return path;
    }

    private static String parentFtpPath(String ftpPath) {
        String path = ftpPath.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return "/";
        return path.substring(0, slash);
    }

    public static String formatBytes(long bytes) {
        if (bytes >= 1_073_741_824L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0);
        }
        if (bytes >= 1_048_576L) {
            return String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static void publish(Listener listener, SyncProgress progress) {
        if (listener != null) listener.onProgress(progress);
    }

    private static void refreshEta(SyncProgress progress, SpeedTracker speed) {
        progress.speedBps = speed.bytesPerSec();
        long remaining = progress.totalBytes - progress.doneBytes;
        if (progress.speedBps > 0 && remaining > 0) {
            progress.etaMs = (remaining * 1000L) / progress.speedBps;
        } else if (remaining <= 0 && progress.totalBytes > 0) {
            progress.etaMs = 0;
        } else {
            progress.etaMs = -1;
        }
    }

    private static final class SpeedTracker {
        private long lastBytes;
        private long lastAt = android.os.SystemClock.elapsedRealtime();
        private long emaBps;

        void resetFile() {
            lastBytes = 0;
            lastAt = android.os.SystemClock.elapsedRealtime();
        }

        void sample(long fileBytes) {
            long now = android.os.SystemClock.elapsedRealtime();
            long dt = now - lastAt;
            if (dt < 250) return;
            long delta = fileBytes - lastBytes;
            if (delta < 0) delta = fileBytes;
            long inst = (delta * 1000L) / Math.max(1, dt);
            if (emaBps <= 0) emaBps = inst;
            else emaBps = (emaBps * 3 + inst) / 4;
            lastBytes = fileBytes;
            lastAt = now;
        }

        long bytesPerSec() {
            return emaBps;
        }
    }
}
