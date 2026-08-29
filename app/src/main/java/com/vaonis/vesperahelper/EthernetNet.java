package com.vaonis.vesperahelper;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Ethernet DHCP/static via rooted {@code vespera-netd.sh} ({@code eth.req}/{@code eth.ack}). */
final class EthernetNet {
    private static final String TAG = "VesperaEth";
    private static final String ACK = "eth.ack";
    private static final long STATUS_TIMEOUT_MS = 8_000;
    private static final long APPLY_TIMEOUT_MS = 20_000;

    static final class Status {
        final boolean ok;
        final boolean timeout;
        final String mode;
        final String ip;
        final String prefix;
        final String gateway;
        final String dns1;
        final String dns2;
        final boolean hasDefaultRoute;
        final String raw;
        final String error;

        Status(boolean ok, boolean timeout, String mode, String ip, String prefix,
                String gateway, String dns1, String dns2, boolean hasDefaultRoute,
                String raw, String error) {
            this.ok = ok;
            this.timeout = timeout;
            this.mode = mode == null ? "" : mode;
            this.ip = ip == null ? "" : ip;
            this.prefix = prefix == null ? "" : prefix;
            this.gateway = gateway == null ? "" : gateway;
            this.dns1 = dns1 == null ? "" : dns1;
            this.dns2 = dns2 == null ? "" : dns2;
            this.hasDefaultRoute = hasDefaultRoute;
            this.raw = raw == null ? "" : raw;
            this.error = error == null ? "" : error;
        }

        static Status timeout() {
            return new Status(false, true, "", "", "", "", "", "", false, "", "timeout");
        }

        static Status fail(String error, String raw) {
            return new Status(false, false, "", "", "", "", "", "", false, raw, error);
        }
    }

    private EthernetNet() {}

    static Status status(Context context) {
        return parse(request(context, "eth-status", STATUS_TIMEOUT_MS));
    }

    static Status applyDhcp(Context context) {
        return parse(request(context, "eth-dhcp", APPLY_TIMEOUT_MS));
    }

    static Status applyStatic(Context context, String ip, String prefix, String gateway,
            String dns1, String dns2) {
        String cmd = "eth-static|"
                + sanitize(ip) + "|"
                + sanitize(prefix) + "|"
                + sanitize(gateway) + "|"
                + sanitize(dns1) + "|"
                + sanitize(dns2);
        return parse(request(context, cmd, APPLY_TIMEOUT_MS));
    }

    /** Best-effort check that eth0 can reach the public Internet. */
    static boolean probeUplink(Context context) {
        try {
            java.net.InetAddress target = java.net.InetAddress.getByName("8.8.8.8");
            android.net.Network network = InternetNetwork.find(context);
            if (network != null) {
                try {
                    java.net.Socket socket = network.getSocketFactory().createSocket();
                    try {
                        socket.connect(new java.net.InetSocketAddress(target, 53), 3_000);
                        return true;
                    } finally {
                        try { socket.close(); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {
                }
            }
            java.net.Socket socket = new java.net.Socket();
            try {
                socket.connect(new java.net.InetSocketAddress(target, 53), 3_000);
                return true;
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        return value.trim().replace("|", "");
    }

    static Status parse(String ack) {
        if (ack == null) return Status.timeout();
        String line = firstLine(ack);
        if (line.isEmpty()) return Status.timeout();
        if (line.startsWith("unknown:")) {
            return Status.fail("daemon_old", line);
        }
        if (line.startsWith("eth-err|")) {
            String[] p = line.split("\\|", -1);
            String code = p.length > 1 ? p[1] : "error";
            return Status.fail(code, line);
        }
        if (!line.startsWith("eth-status|")) {
            return Status.fail("bad_ack", line);
        }
        String[] p = line.split("\\|", -1);
        String mode = p.length > 1 ? p[1] : "";
        String ip = p.length > 2 ? p[2] : "";
        String prefix = p.length > 3 ? p[3] : "";
        String gw = p.length > 4 ? p[4] : "";
        String dns1 = p.length > 5 ? p[5] : "";
        String dns2 = p.length > 6 ? p[6] : "";
        boolean route = false;
        if (p.length > 7) {
            route = "route=1".equals(p[7]) || p[7].endsWith("=1");
        }
        return new Status(true, false, mode, dashToEmpty(ip), dashToEmpty(prefix),
                dashToEmpty(gw), dashToEmpty(dns1), dashToEmpty(dns2), route, line, "");
    }

    private static String dashToEmpty(String value) {
        if (value == null || "-".equals(value)) return "";
        return value;
    }

    private static String request(Context context, String command, long timeoutMs) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        File ack = new File(dir, ACK);
        if (ack.exists() && !ack.delete()) {
            Log.w(TAG, "could not clear " + ACK);
        }
        boolean written = VesperaConnectionService.writeEthRequest(context, command);
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
                String body = readQuietly(ack);
                // Ignore stale startup line if we just issued eth-*.
                String line = firstLine(body);
                if (line.startsWith("eth-") || line.startsWith("unknown:")) {
                    return body;
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Log.w(TAG, "timeout waiting for " + ACK + " after " + command);
        return null;
    }

    private static String readQuietly(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 8_192)];
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
