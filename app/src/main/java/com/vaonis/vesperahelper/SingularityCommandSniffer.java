package com.vaonis.vesperahelper;

import android.content.Context;
import android.net.Network;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logs Singularity commands sent to this telescope only.
 * The root daemon redirects {@code com.vaonis.barnard} traffic toward
 * {@code 10.0.0.1:8082} and {@code :8083} into a local relay. Other hosts,
 * including the Vaonis cloud, are not redirected. Authorization headers are
 * not kept.
 */
final class SingularityCommandSniffer {
    private static final String TAG = "SingularitySniff";
    static final String PKG = "com.vaonis.barnard";
    private static final String ACK_FILE = "sniff.ack";
    private static final int LOCAL_REST = 18082;
    private static final int LOCAL_IO = 18083;
    private static final int REMOTE_REST = 8082;
    private static final int REMOTE_IO = 8083;
    private static final int MAX_LINES = 60;
    private static final int MAX_BODY = 4000;
    private static final long ACK_TIMEOUT_MS = 12_000L;
    /** Persistent copy of the sniff log, pullable via ADB next to remote.*. */
    static final String LOG_FILE = "sniff.log";
    private static final String LOG_FILE_OLD = "sniff.log.1";
    private static final long LOG_MAX_BYTES = 1024L * 1024L;

    interface Ui {
        void onState(boolean running, String code);
        void onLog(String text);
    }

    private static final ExecutorService pool = Executors.newCachedThreadPool();
    private static final ExecutorService fileWriter = Executors.newSingleThreadExecutor();
    private static volatile Context appContext;
    private static final Object logLock = new Object();
    private static final List<String> lines = new ArrayList<>();
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static volatile Ui ui;
    private static volatile ServerSocket restServer;
    private static volatile ServerSocket ioServer;
    private static volatile String host = "10.0.0.1";
    private static volatile Network network;
    private static volatile boolean expectOff;

    private SingularityCommandSniffer() {}

    static void setUi(Ui listener) {
        ui = listener;
        Ui current = ui;
        if (current == null) return;
        current.onState(running.get(), running.get() ? "on" : "off");
        current.onLog(logText());
    }

    static boolean isRunning() {
        return running.get();
    }

    static void clear() {
        synchronized (logLock) {
            lines.clear();
        }
        notifyLog();
    }

    static void start(Context context, String telescopeHost, Network vesperaNetwork) {
        if (!busy.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        appContext = app;
        host = telescopeHost == null || telescopeHost.isEmpty() ? "10.0.0.1" : telescopeHost;
        network = vesperaNetwork;
        pool.execute(() -> {
            try {
                notifyState(false, "starting");
                if (!openServers()) {
                    notifyState(false, "bind");
                    return;
                }
                String previous = readAck(app);
                long before = ackModified(app);
                if (!VesperaConnectionService.writeSniffRequest(
                        app, "sniff-on|" + LOCAL_REST + "|" + LOCAL_IO)) {
                    closeServers();
                    notifyState(false, "daemon");
                    return;
                }
                String ack = pollAck(app, before, previous);
                if (ack.startsWith("sniff-on")) {
                    appendFile("=== sniff ON  host=" + host + " ===");
                    running.set(true);
                    expectOff = false;
                    notifyState(true, "on");
                    pool.execute(() -> watchAck(app));
                    return;
                }
                closeServers();
                notifyState(false, codeOf(ack));
            } finally {
                busy.set(false);
            }
        });
    }

    static void stop(Context context) {
        Context app = context.getApplicationContext();
        if (!busy.compareAndSet(false, true)) return;
        pool.execute(() -> {
            try {
                expectOff = true;
                running.set(false);
                String previous = readAck(app);
                long before = ackModified(app);
                VesperaConnectionService.writeSniffRequest(app, "sniff-off");
                pollAck(app, before, previous);
                closeServers();
                appendFile("=== sniff OFF ===");
                notifyState(false, "off");
            } finally {
                busy.set(false);
            }
        });
    }

    private static void watchAck(Context app) {
        while (running.get()) {
            sleep(3_000L);
            if (!running.get() || expectOff) return;
            String ack = readAck(app);
            if (ack.startsWith("sniff-off")) {
                running.set(false);
                closeServers();
                notifyState(false, ack.contains("proxy-down") ? "proxy-down" : "off");
                return;
            }
        }
    }

    private static boolean openServers() {
        closeServers();
        try {
            restServer = bind(LOCAL_REST);
            ioServer = bind(LOCAL_IO);
            pool.execute(() -> acceptLoop(restServer, REMOTE_REST));
            pool.execute(() -> acceptLoop(ioServer, REMOTE_IO));
            return true;
        } catch (IOException failure) {
            Log.w(TAG, "bind failed: " + failure.getMessage());
            closeServers();
            return false;
        }
    }

    private static ServerSocket bind(int port) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", port));
        return server;
    }

    private static void acceptLoop(ServerSocket server, int remotePort) {
        while (server != null && !server.isClosed()) {
            try {
                Socket client = server.accept();
                pool.execute(() -> relay(client, remotePort));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private static void relay(Socket client, int remotePort) {
        Socket remote = null;
        try {
            client.setTcpNoDelay(true);
            remote = VesperaSockets.create(network);
            remote.connect(new InetSocketAddress(host, remotePort), 8_000);
            remote.setTcpNoDelay(true);
            push("— :" + remotePort);
            ClientTap tap = new ClientTap(remotePort);
            Socket upstream = remote;
            pool.execute(() -> pump(client, upstream, tap));
            pump(upstream, client, null);
        } catch (IOException failure) {
            Log.w(TAG, "relay :" + remotePort + " " + failure.getMessage());
            push("— :" + remotePort + " " + failure.getMessage());
        } finally {
            closeQuiet(client);
            closeQuiet(remote);
        }
    }

    private static void pump(Socket from, Socket to, ClientTap tap) {
        byte[] buf = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                if (tap != null) tap.onBytes(buf, n);
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // Peer closed the relay.
        } finally {
            closeQuiet(from);
            closeQuiet(to);
        }
    }

    private static void closeServers() {
        closeQuiet(restServer);
        closeQuiet(ioServer);
        restServer = null;
        ioServer = null;
    }

    private static void closeQuiet(ServerSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuiet(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static String codeOf(String ack) {
        if (ack.isEmpty()) return "timeout";
        if (ack.contains("no-uid")) return "no-uid";
        if (ack.contains("iptables") || ack.contains("no-iptables")) return "iptables";
        if (ack.contains("proxy-down")) return "proxy-down";
        return "daemon";
    }

    private static String pollAck(Context context, long modifiedAfter, String previous) {
        long deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            File ack = ackFile(context);
            if (ack != null && ack.exists()) {
                String line = readFirstLine(ack);
                boolean fresh = ack.lastModified() > modifiedAfter;
                boolean changed = previous == null || !line.equals(previous);
                if (!line.isEmpty() && (fresh || changed) && line.startsWith("sniff-")) {
                    return line;
                }
            }
            sleep(200L);
        }
        return "";
    }

    private static String readAck(Context context) {
        File ack = ackFile(context);
        if (ack == null || !ack.exists()) return "";
        return readFirstLine(ack);
    }

    private static long ackModified(Context context) {
        File ack = ackFile(context);
        return ack != null && ack.exists() ? ack.lastModified() : 0L;
    }

    private static File ackFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        if (dir == null) return null;
        return new File(dir, ACK_FILE);
    }

    private static String readFirstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception failure) {
            return "";
        }
    }

    private static void push(String entry) {
        String stamp = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
        synchronized (logLock) {
            lines.add(stamp + "  " + entry);
            while (lines.size() > MAX_LINES) lines.remove(0);
        }
        appendFile(entry);
        notifyLog();
    }

    /** Appends one entry (full date) to files/sniff.log, rotating at 1 MB. */
    private static void appendFile(String entry) {
        Context app = appContext;
        if (app == null || entry == null) return;
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String record = stamp + "  " + entry.replace("\n", "\n    ") + "\n";
        fileWriter.execute(() -> {
            File dir = app.getExternalFilesDir(null);
            if (dir == null) dir = app.getFilesDir();
            if (dir == null) return;
            File log = new File(dir, LOG_FILE);
            if (log.length() > LOG_MAX_BYTES) {
                File old = new File(dir, LOG_FILE_OLD);
                if (old.exists() && !old.delete()) Log.w(TAG, "cannot delete " + old);
                if (!log.renameTo(old)) Log.w(TAG, "cannot rotate " + log);
            }
            try (FileOutputStream out = new FileOutputStream(log, true)) {
                out.write(record.getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                Log.w(TAG, "sniff.log: " + failure.getMessage());
            }
        });
    }

    private static String logText() {
        synchronized (logLock) {
            if (lines.isEmpty()) return "";
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) out.append("\n\n");
                out.append(lines.get(i));
            }
            return out.toString();
        }
    }

    private static void notifyLog() {
        Ui current = ui;
        if (current != null) current.onLog(logText());
    }

    private static void notifyState(boolean active, String code) {
        Ui current = ui;
        if (current != null) current.onState(active, code);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Client bytes toward the telescope: HTTP commands, then websocket text. */
    private static final class ClientTap {
        private final int remotePort;
        private byte[] buf = new byte[0];
        private boolean websocket;

        ClientTap(int remotePort) {
            this.remotePort = remotePort;
        }

        void onBytes(byte[] data, int len) {
            byte[] next = new byte[buf.length + len];
            System.arraycopy(buf, 0, next, 0, buf.length);
            System.arraycopy(data, 0, next, buf.length, len);
            buf = next;
            if (!websocket) drainHttp();
            if (websocket) drainWs();
            if (!websocket && buf.length > 65_536 && !methodPrefix(buf)) buf = new byte[0];
            if (buf.length > 512 * 1024) buf = new byte[0];
        }

        private void drainHttp() {
            while (buf.length > 0 && !websocket) {
                int skip = skipLeadingBreaks(buf);
                if (skip > 0) {
                    buf = slice(buf, skip);
                    continue;
                }
                if (!startsWithMethod(buf)) {
                    if (buf.length > 8 && !methodPrefix(buf)) {
                        websocket = (buf[0] & 0x80) != 0;
                        if (!websocket) buf = new byte[0];
                    }
                    return;
                }
                int hdrEnd = indexOf(buf, new byte[] {'\r', '\n', '\r', '\n'});
                if (hdrEnd < 0) return;
                int headerLen = hdrEnd + 4;
                String headers = new String(buf, 0, headerLen, StandardCharsets.ISO_8859_1);
                String requestLine = headers.split("\r\n", 2)[0];
                String[] parts = requestLine.split(" ");
                String method = parts.length > 0 ? parts[0] : "";
                String path = parts.length > 1 ? parts[1] : "";
                Integer contentLength = contentLength(headers);
                int bodyLen;
                if (contentLength != null) {
                    bodyLen = contentLength;
                    if (buf.length < headerLen + bodyLen) return;
                } else if (isBodyless(method)) {
                    bodyLen = 0;
                } else if (buf.length == headerLen) {
                    return;
                } else {
                    int json = jsonLength(buf, headerLen);
                    if (json < 0) {
                        if (buf.length - headerLen < 8192) return;
                        bodyLen = 8192;
                    } else {
                        bodyLen = json;
                    }
                }
                byte[] body = new byte[bodyLen];
                System.arraycopy(buf, headerLen, body, 0, bodyLen);
                if (!skipGet(method, path)) emitHttp(method, path, body);
                buf = slice(buf, headerLen + bodyLen);
                if (headers.toLowerCase(Locale.US).contains("upgrade: websocket")) {
                    websocket = true;
                    return;
                }
            }
        }

        private void drainWs() {
            while (buf.length >= 2) {
                int b1 = buf[1] & 0xff;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7f;
                int header = 2;
                if (len == 126) {
                    if (buf.length < 4) return;
                    len = ((buf[2] & 0xff) << 8) | (buf[3] & 0xff);
                    header = 4;
                } else if (len == 127) {
                    if (buf.length < 10) return;
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | (buf[2 + i] & 0xff);
                    header = 10;
                }
                int maskAt = header;
                if (masked) header += 4;
                if (len < 0 || len > 1_000_000) {
                    buf = new byte[0];
                    return;
                }
                if (buf.length < header + len) return;
                byte[] payload = new byte[(int) len];
                System.arraycopy(buf, header, payload, 0, payload.length);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= buf[maskAt + (i % 4)];
                    }
                }
                int opcode = buf[0] & 0x0f;
                buf = slice(buf, header + (int) len);
                if (opcode == 0x1) emitWs(payload);
            }
        }

        private void emitHttp(String method, String path, byte[] body) {
            String text = method + " " + path;
            String payload = bodyText(body);
            if (!payload.isEmpty()) text = text + "\n" + payload;
            push(text);
        }

        private void emitWs(byte[] payload) {
            String text = bodyText(payload).trim();
            if (text.isEmpty() || "2".equals(text) || "3".equals(text)) return;
            push("WS :" + remotePort + "\n" + text);
        }
    }

    private static boolean skipGet(String method, String path) {
        if (!"GET".equals(method) && !"HEAD".equals(method)) return false;
        String lower = path.toLowerCase(Locale.US);
        return lower.contains("status") || lower.contains("health");
    }

    private static boolean isBodyless(String method) {
        return "GET".equals(method) || "HEAD".equals(method)
                || "DELETE".equals(method) || "OPTIONS".equals(method);
    }

    private static Integer contentLength(String headers) {
        String[] lines = headers.split("\r\n");
        Integer value = null;
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (!line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) continue;
            try {
                value = Integer.parseInt(line.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return value;
    }

    /** Byte length of one JSON value, or -1 if the value is still incomplete. */
    private static int jsonLength(byte[] data, int offset) {
        if (offset >= data.length || data[offset] != '{') return -1;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = offset; i < data.length; i++) {
            byte c = data[i];
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i - offset + 1;
            }
        }
        return -1;
    }

    private static String bodyText(byte[] body) {
        if (body == null || body.length == 0) return "";
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.length() > MAX_BODY) text = text.substring(0, MAX_BODY) + "…";
        return text;
    }

    private static boolean startsWithMethod(byte[] data) {
        return startsWith(data, "POST ") || startsWith(data, "GET ")
                || startsWith(data, "PUT ") || startsWith(data, "PATCH ")
                || startsWith(data, "DELETE ") || startsWith(data, "HEAD ")
                || startsWith(data, "OPTIONS ");
    }

    private static boolean methodPrefix(byte[] data) {
        if (data.length == 0) return false;
        int b = data[0] & 0xff;
        if ("PGUDHO".indexOf(b) < 0) return false;
        String asText = new String(data, 0, Math.min(data.length, 8), StandardCharsets.US_ASCII);
        return "POST".startsWith(asText) || "GET".startsWith(asText)
                || "PUT".startsWith(asText) || "PATCH".startsWith(asText)
                || "DELETE".startsWith(asText) || "HEAD".startsWith(asText)
                || "OPTIONS".startsWith(asText)
                || asText.startsWith("POST") || asText.startsWith("GET")
                || asText.startsWith("PUT") || asText.startsWith("PATCH")
                || asText.startsWith("DELETE") || asText.startsWith("HEAD")
                || asText.startsWith("OPTIONS");
    }

    private static boolean startsWith(byte[] data, String prefix) {
        byte[] raw = prefix.getBytes(StandardCharsets.US_ASCII);
        if (data.length < raw.length) return false;
        for (int i = 0; i < raw.length; i++) {
            if (data[i] != raw[i]) return false;
        }
        return true;
    }

    private static int skipLeadingBreaks(byte[] data) {
        int i = 0;
        while (i < data.length && (data[i] == '\r' || data[i] == '\n')) i++;
        return i;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] slice(byte[] data, int from) {
        if (from >= data.length) return new byte[0];
        byte[] out = new byte[data.length - from];
        System.arraycopy(data, from, out, 0, out.length);
        return out;
    }
}
