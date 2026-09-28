package com.vaonis.vesperahelper;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FTP server for the mounted HD over Tailscale / LAN.
 * Download and delete (files and directories) are allowed; upload stays blocked.
 * Listens on all interfaces (port {@link #PORT}); PASV advertises the control-socket local IP.
 */
public final class SimpleFtpServer {
    public static final int PORT = FtpProbe.HD_PREFERRED;
    private static final String TAG = "VesperaFtpd";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket server;
    private volatile File root;
    private volatile int listenPort = PORT;
    private ExecutorService pool;

    public int getPort() {
        return listenPort;
    }

    public synchronized void start(File rootDir) throws IOException {
        start(rootDir, -1);
    }

    public synchronized void start(File rootDir, int avoidPort) throws IOException {
        stop();
        if (rootDir == null || !rootDir.isDirectory()) {
            throw new IOException("FTP root missing");
        }
        root = rootDir.getCanonicalFile();
        pool = Executors.newCachedThreadPool();
        server = new ServerSocket();
        server.setReuseAddress(true);
        listenPort = FtpProbe.bindPreferred(server, PORT, avoidPort);
        running.set(true);
        Thread accept = new Thread(this::acceptLoop, "vespera-ftpd");
        accept.setDaemon(true);
        accept.start();
        Log.i(TAG, "listening on " + listenPort + " root=" + root);
    }

    public synchronized void stop() {
        running.set(false);
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    public boolean isRunning() {
        return running.get() && server != null && !server.isClosed();
    }

    private void acceptLoop() {
        ServerSocket listen = server;
        while (running.get() && listen != null && !listen.isClosed()) {
            try {
                Socket client = listen.accept();
                ExecutorService workers = pool;
                if (workers == null) {
                    client.close();
                    return;
                }
                workers.execute(() -> handle(client));
            } catch (SocketException closed) {
                break;
            } catch (IOException failure) {
                if (running.get()) Log.w(TAG, "accept", failure);
            }
        }
    }

    private void handle(Socket socket) {
        File cwd = root;
        ServerSocket pasv = null;
        try {
            socket.setSoTimeout(120_000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStreamWriter writer = new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8);
            reply(writer, "220 VesperaHelper photo FTP");
            boolean authed = false;
            String line;
            while ((line = reader.readLine()) != null) {
                String cmd = line.trim();
                if (cmd.isEmpty()) continue;
                String upper = cmd.toUpperCase(Locale.US);
                String arg = "";
                int sp = cmd.indexOf(' ');
                if (sp > 0) {
                    upper = cmd.substring(0, sp).toUpperCase(Locale.US);
                    arg = cmd.substring(sp + 1).trim();
                }
                if ("QUIT".equals(upper)) {
                    reply(writer, "221 Bye");
                    break;
                }
                if ("USER".equals(upper) || "PASS".equals(upper)) {
                    authed = true;
                    reply(writer, "USER".equals(upper) ? "331 Password ok (anonymous)" : "230 Logged in");
                    continue;
                }
                if ("SYST".equals(upper)) {
                    reply(writer, "215 UNIX Type: L8");
                    continue;
                }
                if ("FEAT".equals(upper)) {
                    writer.write("211-Features:\r\n PASV\r\n EPSV\r\n SIZE\r\n UTF8\r\n211 End\r\n");
                    writer.flush();
                    continue;
                }
                if ("NOOP".equals(upper) || "OPTS".equals(upper) || "TYPE".equals(upper)
                        || "MODE".equals(upper) || "STRU".equals(upper)) {
                    reply(writer, "200 OK");
                    continue;
                }
                if ("PWD".equals(upper) || "XPWD".equals(upper)) {
                    reply(writer, "257 \"" + ftpPath(cwd) + "\" is current directory");
                    continue;
                }
                if ("CWD".equals(upper) || "XCWD".equals(upper)) {
                    File next = resolve(cwd, arg);
                    if (next != null && next.isDirectory()) {
                        cwd = next;
                        reply(writer, "250 Directory changed");
                    } else {
                        reply(writer, "550 No such directory");
                    }
                    continue;
                }
                if ("CDUP".equals(upper)) {
                    File parent = cwd.getParentFile();
                    if (parent != null && insideRoot(parent)) {
                        cwd = parent;
                        reply(writer, "250 Directory changed");
                    } else {
                        reply(writer, "550 Failed");
                    }
                    continue;
                }
                if ("PASV".equals(upper) || "EPSV".equals(upper)) {
                    try { if (pasv != null) pasv.close(); } catch (Exception ignored) {}
                    InetAddress local = socket.getLocalAddress();
                    pasv = new ServerSocket();
                    pasv.setReuseAddress(true);
                    pasv.bind(new InetSocketAddress(local, 0));
                    int p = pasv.getLocalPort();
                    if ("EPSV".equals(upper)) {
                        reply(writer, "229 Entering Extended Passive Mode (|||" + p + "|)");
                    } else {
                        byte[] a = local.getAddress();
                        if (a == null || a.length != 4) {
                            reply(writer, "522 Use EPSV");
                            continue;
                        }
                        reply(writer, "227 Entering Passive Mode ("
                                + (a[0] & 0xff) + "," + (a[1] & 0xff) + ","
                                + (a[2] & 0xff) + "," + (a[3] & 0xff) + ","
                                + (p / 256) + "," + (p % 256) + ")");
                    }
                    continue;
                }
                if ("SIZE".equals(upper)) {
                    File file = resolve(cwd, arg);
                    if (file != null && file.isFile()) {
                        reply(writer, "213 " + file.length());
                    } else {
                        reply(writer, "550 No such file");
                    }
                    continue;
                }
                if ("LIST".equals(upper) || "NLST".equals(upper)) {
                    File dir = arg.isEmpty() ? cwd : resolve(cwd, arg);
                    if (dir == null || !dir.isDirectory() || pasv == null) {
                        reply(writer, "425 Use PASV first / no directory");
                        continue;
                    }
                    reply(writer, "150 Opening data connection");
                    try (Socket data = pasv.accept();
                         OutputStream raw = data.getOutputStream()) {
                        data.setSoTimeout(30_000);
                        File[] children = dir.listFiles();
                        if (children != null) {
                            SimpleDateFormat fmt = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
                            for (File child : children) {
                                if ("LIST".equals(upper)) {
                                    String row = String.format(Locale.US,
                                            "%s 1 ftp ftp %d %s %s\r\n",
                                            child.isDirectory() ? "drwxrwxrwx" : "-rw-rw-rw-",
                                            child.isDirectory() ? 0 : child.length(),
                                            fmt.format(new Date(child.lastModified())),
                                            child.getName());
                                    raw.write(row.getBytes(StandardCharsets.UTF_8));
                                } else {
                                    raw.write((child.getName() + "\r\n").getBytes(StandardCharsets.UTF_8));
                                }
                            }
                        }
                        raw.flush();
                    } finally {
                        try { pasv.close(); } catch (Exception ignored) {}
                        pasv = null;
                    }
                    reply(writer, "226 Transfer complete");
                    continue;
                }
                if ("RETR".equals(upper)) {
                    File file = resolve(cwd, arg);
                    if (file == null || !file.isFile() || pasv == null) {
                        reply(writer, "550 File unavailable");
                        continue;
                    }
                    reply(writer, "150 Opening data connection");
                    try (Socket data = pasv.accept();
                         FileInputStream fis = new FileInputStream(file);
                         OutputStream raw = new BufferedOutputStream(data.getOutputStream())) {
                        data.setSoTimeout(180_000);
                        byte[] buf = new byte[65_536];
                        int n;
                        while ((n = fis.read(buf)) >= 0) {
                            if (n > 0) raw.write(buf, 0, n);
                        }
                        raw.flush();
                    } finally {
                        try { pasv.close(); } catch (Exception ignored) {}
                        pasv = null;
                    }
                    reply(writer, "226 Transfer complete");
                    continue;
                }
                if ("DELE".equals(upper)) {
                    File file = resolve(cwd, unquote(arg));
                    if (file == null || isRoot(file) || !file.isFile()) {
                        reply(writer, "550 No such file");
                        continue;
                    }
                    if (!deleteEntry(file)) {
                        Log.w(TAG, "DELE failed " + file);
                        reply(writer, "550 Delete failed");
                        continue;
                    }
                    reply(writer, "250 Deleted");
                    continue;
                }
                if ("RMD".equals(upper) || "XRMD".equals(upper)) {
                    File dir = resolve(cwd, unquote(arg));
                    if (dir == null || isRoot(dir) || !dir.isDirectory()) {
                        reply(writer, "550 No such directory");
                        continue;
                    }
                    if (sameOrInside(cwd, dir)) {
                        File parent = dir.getParentFile();
                        cwd = parent != null && insideRoot(parent) ? parent : root;
                    }
                    if (!deleteTree(dir)) {
                        reply(writer, "550 Remove failed");
                        continue;
                    }
                    reply(writer, "250 Directory removed");
                    continue;
                }
                if ("STOR".equals(upper) || "MKD".equals(upper) || "XMKD".equals(upper)
                        || "RNFR".equals(upper) || "APPE".equals(upper)) {
                    reply(writer, "550 Upload not allowed");
                    continue;
                }
                if (!authed) {
                    reply(writer, "530 Login first");
                    continue;
                }
                reply(writer, "502 Command not implemented");
            }
        } catch (Exception failure) {
            Log.w(TAG, "client", failure);
        } finally {
            try { if (pasv != null) pasv.close(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private File resolve(File cwd, String ftpPath) {
        try {
            File target;
            if (ftpPath == null || ftpPath.isEmpty() || ".".equals(ftpPath)) {
                target = cwd;
            } else if (ftpPath.startsWith("/")) {
                target = new File(root, ftpPath.substring(1));
            } else {
                target = new File(cwd, ftpPath);
            }
            File canonical = target.getCanonicalFile();
            if (!insideRoot(canonical)) return null;
            return canonical;
        } catch (IOException ignored) {
            return null;
        }
    }

    private boolean isRoot(File file) {
        return file.getAbsolutePath().equals(root.getAbsolutePath());
    }

    /** True when {@code cwd} is {@code dir} or a descendant, so rmdir would leave the session inside a gone folder. */
    private static boolean sameOrInside(File cwd, File dir) {
        String current = cwd.getAbsolutePath();
        String base = dir.getAbsolutePath();
        return current.equals(base) || current.startsWith(base + File.separator);
    }

    private static String unquote(String arg) {
        if (arg == null) return "";
        if (arg.length() >= 2 && arg.charAt(0) == '"' && arg.charAt(arg.length() - 1) == '"') {
            return arg.substring(1, arg.length() - 1).replace("\"\"", "\"");
        }
        return arg;
    }

    private static boolean deleteEntry(File file) {
        if (file.delete()) return true;
        file.setWritable(true, false);
        if (file.isDirectory()) file.setExecutable(true, false);
        return file.delete();
    }

    /** Removes a directory and everything inside it. Never called on the FTP root. */
    private boolean deleteTree(File dir) {
        ArrayDeque<File> pending = new ArrayDeque<>();
        ArrayDeque<File> post = new ArrayDeque<>();
        pending.push(dir);
        while (!pending.isEmpty()) {
            File current = pending.pop();
            post.push(current);
            if (!current.isDirectory()) continue;
            File[] children = current.listFiles();
            if (children == null) {
                Log.w(TAG, "list failed " + current);
                return false;
            }
            for (File child : children) pending.push(child);
        }
        while (!post.isEmpty()) {
            File current = post.pop();
            if (!deleteEntry(current)) {
                Log.w(TAG, "delete failed " + current);
                return false;
            }
        }
        return true;
    }

    private boolean insideRoot(File file) {
        String path = file.getAbsolutePath();
        String base = root.getAbsolutePath();
        return path.equals(base) || path.startsWith(base + File.separator);
    }

    private String ftpPath(File dir) {
        String path = dir.getAbsolutePath();
        String base = root.getAbsolutePath();
        if (path.equals(base)) return "/";
        String rel = path.substring(base.length()).replace('\\', '/');
        if (!rel.startsWith("/")) rel = "/" + rel;
        return rel;
    }

    private static void reply(OutputStreamWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write("\r\n");
        writer.flush();
    }
}
