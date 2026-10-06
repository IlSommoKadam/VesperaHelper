package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import java.util.Locale;

/**
 * Internal photo storage on the Vespera. {@code /v1/app/status} usually has no
 * disk fields, so the primary reading is FTP {@code /USER} size versus the
 * model capacity. Extra REST paths are only a fallback.
 */
final class VesperaInternalStorage {
    private static final String TAG = "VesperaStorage";
    private static final Object LOCK = new Object();
    private static final long GB = 1024L * 1024L * 1024L;
    private static volatile Usage lastUsage;
    private static volatile long lastUsageAt;
    private static volatile String lastError = "";
    private static final String[] EXTRA_PATHS = {
            "/v1/device/status",
            "/v1/storage",
            "/v1/app/storage"
    };

    static final class Usage {
        final int usedPercent;
        final String label;

        Usage(int usedPercent, String label) {
            this.usedPercent = usedPercent;
            this.label = label == null ? "" : label;
        }
    }

    private VesperaInternalStorage() {}

    static Usage lastKnown() {
        return lastUsage;
    }

    /** elapsedRealtime dell'ultima lettura riuscita (0 = mai). */
    static long lastKnownAt() {
        return lastUsageAt;
    }

    static String lastError() {
        return lastError == null ? "" : lastError;
    }

    static Usage probe(Network network, String host, int apiPort, String model) {
        synchronized (LOCK) {
            if (host == null || host.isEmpty()) host = "10.0.0.1";
            Usage ftp = fromFtp(network, host, model);
            if (ftp != null) {
                lastError = "";
                lastUsage = ftp;
                lastUsageAt = android.os.SystemClock.elapsedRealtime();
                return ftp;
            }
            Usage rest = fromRest(network, host, apiPort);
            if (rest != null && rest.usedPercent >= 0 && !rest.label.isEmpty()) {
                lastError = "";
                lastUsage = rest;
                lastUsageAt = android.os.SystemClock.elapsedRealtime();
                return rest;
            }
            return lastUsage;
        }
    }

    private static Usage fromRest(Network network, String host, int apiPort) {
        if (apiPort <= 0) {
            VesperaPortScan scan = VesperaPortScanner.lastScan();
            if (scan != null) apiPort = scan.apiRestPort;
        }
        if (apiPort <= 0) apiPort = 8082;
        for (String path : EXTRA_PATHS) {
            try {
                VesperaHttp.Response response = VesperaHttp.get(
                        network, host, apiPort, path, 1_500);
                if (response.code < 200 || response.code >= 300) continue;
                VesperaStatusSnapshot snap = VesperaStatusClient.parse(
                        host + ":" + apiPort + path, response.body);
                if (snap.storageUsedPercent >= 0 && !snap.storage.isEmpty()) {
                    Log.i(TAG, "storage from " + path + " " + snap.storage);
                    return new Usage(snap.storageUsedPercent, snap.storage);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Usage fromFtp(Network network, String host, String model) {
        try (CommonsFtpClient ftp = new CommonsFtpClient(network, host)) {
            int port = resolveFtpPort(network, host);
            if (port <= 0) {
                lastError = "porta non trovata";
                Log.w(TAG, "FTP storage: no control port on " + host);
                return null;
            }
            try {
                ftp.connect(port);
            } catch (Exception first) {
                int found = FtpProbe.findVesperaControl(network, host);
                if (found <= 0 || found == port) throw first;
                ftp.connect(found);
                port = found;
            }
            String user = ftp.resolveUserDir();
            long used = 0;
            int files = 0;
            for (CommonsFtpClient.Entry entry : ftp.listRecursive(user)) {
                if (entry != null && !entry.directory && entry.size > 0) {
                    used += entry.size;
                    files++;
                }
            }
            long total = capacityBytes(model);
            if (total <= 0) total = 10 * GB;
            if (used > total) total = used;
            int percent = (int) Math.round(100.0 * used / (double) total);
            if (percent > 100) percent = 100;
            String label = percent + "%  ·  " + formatBytes(used) + " / " + formatBytes(total);
            Log.i(TAG, "storage from FTP :" + port + " USER=" + formatBytes(used)
                    + " files=" + files + " cap=" + formatBytes(total) + " model=" + model);
            return new Usage(percent, label);
        } catch (Exception failure) {
            String msg = failure.getMessage();
            lastError = (msg == null || msg.isEmpty()) ? "errore" : trimError(msg);
            Log.w(TAG, "FTP storage: " + lastError);
            return null;
        }
    }

    private static int resolveFtpPort(Network network, String host) {
        VesperaPortScan scan = VesperaPortScanner.lastScan();
        if (scan != null && scan.ftpPort > 0) return scan.ftpPort;
        int cached = FtpProbe.lastVesperaPort();
        if (cached > 0) return cached;
        return FtpProbe.findVesperaControl(network, host);
    }

    private static String trimError(String msg) {
        String text = msg.replace('\n', ' ').trim();
        if (text.length() > 80) text = text.substring(0, 80);
        return text;
    }

    private static long capacityBytes(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.US);
        if (m.contains("pro")) return 50 * GB;
        if (m.contains("ii") || m.contains(" 2") || m.endsWith("2")) return 25 * GB;
        return 10 * GB;
    }

    private static String formatBytes(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb);
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.US, "%.0f MB", mb);
    }
}
