package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import javax.net.SocketFactory;

/**
 * FTP client for the Vespera based on Apache Commons Net, bound to the instrument
 * {@link Network} so PASV data sockets follow Wi‑Fi rather than Ethernet.
 */
final class CommonsFtpClient implements Closeable {
    private static final String TAG = "VesperaFtp";
    static final class Entry {
        final String path;
        final String name;
        final boolean directory;
        final long size;
        /** Ora del file sul Vespera (ms epoch) dal LIST, 0 se ignota. */
        final long timeMs;

        Entry(String path, String name, boolean directory, long size) {
            this(path, name, directory, size, 0L);
        }

        Entry(String path, String name, boolean directory, long size, long timeMs) {
            this.path = path;
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.timeMs = Math.max(0L, timeMs);
        }
    }

    /** Ora esatta del file remoto via MDTM (ms epoch, UTC), 0 se non disponibile. */
    long modificationTime(String path) {
        try {
            ensureFtp();
            String raw = ftp.getModificationTime(path);
            if (raw == null) return 0L;
            raw = raw.trim();
            int space = raw.lastIndexOf(' ');
            if (space >= 0) raw = raw.substring(space + 1);
            if (raw.length() < 14) return 0L;
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date when = fmt.parse(raw.substring(0, 14));
            return when == null ? 0L : when.getTime();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    interface ByteListener {
        void onBytes(long transferred) throws IOException;
    }

    interface ListListener {
        void onDir(String dir, int filesSoFar) throws IOException;
    }

    private static final int LIST_DATA_TIMEOUT_MS = 25_000;
    private static final int TRANSFER_DATA_TIMEOUT_MS = 180_000;

    private final Network network;
    private final String host;
    private FTPClient ftp;

    CommonsFtpClient(Network network, String host) {
        this.network = network;
        this.host = host;
    }

    void connect(int port) throws IOException {
        IOException last = null;
        String[][] attempts = {
                {"anonymous", "vespera@helper"},
                {"anonymous", ""},
                {"ftp", "ftp"}
        };
        for (String[] pair : attempts) {
            try {
                openControl(port);
                if (!ftp.login(pair[0], pair[1])) {
                    last = new IOException("FTP login " + pair[0] + ": " + reply());
                    closeQuiet();
                    continue;
                }
                ftp.setFileType(FTP.BINARY_FILE_TYPE);
                ftp.enterLocalPassiveMode();
                ftp.setRemoteVerificationEnabled(false);
                ftp.setUseEPSVwithIPv4(false);
                ftp.setBufferSize(256 * 1024);
                ftp.setControlKeepAliveTimeout(30);
                ftp.setControlKeepAliveReplyTimeout(10_000);
                return;
            } catch (IOException failure) {
                last = failure;
                closeQuiet();
            }
        }
        throw last != null ? last : new IOException("FTP login failed");
    }

    boolean cwd(String path) throws IOException {
        ensureFtp();
        return ftp.changeWorkingDirectory(path);
    }

    /**
     * Vespera firmware exposes {@code /user} (lowercase). Older units used
     * {@code /USER}. Resolve the actual directory name from the FTP root.
     */
    String resolveUserDir() throws IOException {
        ensureFtp();
        String[] candidates = {"/user", "/USER", "user", "USER"};
        for (String candidate : candidates) {
            if (ftp.changeWorkingDirectory(candidate)) return normalizeDir(candidate);
        }
        if (ftp.changeWorkingDirectory("/")) {
            FTPFile[] listed = listCurrent();
            for (FTPFile file : listed) {
                if (file == null || !file.isDirectory()) continue;
                String name = file.getName();
                if (name == null || !isUserDirName(name)) continue;
                String path = "/" + name;
                if (ftp.changeWorkingDirectory(path)) return path;
            }
        }
        throw new IOException("missing-user");
    }

    List<Entry> listRecursive(String rootPath) throws IOException {
        return listRecursive(rootPath, null);
    }

    List<Entry> listRecursive(String rootPath, ListListener listener) throws IOException {
        List<Entry> files = new ArrayList<>();
        walk(normalizeDir(rootPath), files, listener);
        return files;
    }

    long sizeOf(String path) {
        try {
            ensureFtp();
            ftp.sendCommand("SIZE", path);
            if (ftp.getReplyCode() != 213) return -1;
            String[] parts = reply().trim().split("\\s+");
            if (parts.length < 2) return -1;
            return Long.parseLong(parts[1].trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** Downloads {@code remotePath} to {@code localFile}. Returns CRC32 of written bytes. */
    long retrieve(String remotePath, File localFile, ByteListener listener) throws IOException {
        ensureFtp();
        File parent = localFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdir " + parent);
        }
        File part = new File(localFile.getAbsolutePath() + ".part");
        CRC32 crc = new CRC32();
        final long[] written = {0};
        try (FileOutputStream fos = new FileOutputStream(part);
             OutputStream counted = new CrcOutputStream(fos, crc, n -> {
                 written[0] += n;
                 if (listener != null) listener.onBytes(written[0]);
             })) {
            boolean ok = ftp.retrieveFile(remotePath, counted);
            counted.flush();
            fos.getFD().sync();
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("RETR " + remotePath + ": " + reply());
            }
        } catch (IOException failure) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw failure;
        }
        if (localFile.exists() && !localFile.delete()) {
            throw new IOException("replace " + localFile);
        }
        if (!part.renameTo(localFile)) {
            throw new IOException("rename " + part);
        }
        return crc.getValue();
    }

    void deleteFile(String remotePath) throws IOException {
        ensureFtp();
        if (!ftp.deleteFile(remotePath)) {
            throw new IOException("DELE " + remotePath + ": " + reply());
        }
    }

    void removeDir(String remotePath) {
        try {
            ensureFtp();
            ftp.removeDirectory(remotePath);
        } catch (IOException ignored) {
        }
    }

    String reply() {
        if (ftp == null) return "";
        String text = ftp.getReplyString();
        return text == null ? "" : text.trim();
    }

    @Override public void close() {
        closeQuiet();
    }

    private void openControl(int port) throws IOException {
        closeQuiet();
        ftp = new FTPClient();
        FTPClientConfig config = new FTPClientConfig(FTPClientConfig.SYST_UNIX);
        config.setServerLanguageCode("en");
        ftp.configure(config);
        ftp.setConnectTimeout(8_000);
        ftp.setDefaultTimeout(20_000);
        ftp.setDataTimeout(TRANSFER_DATA_TIMEOUT_MS);
        ftp.setSocketFactory(new NetworkBoundSocketFactory(network, host));
        ftp.connect(host, port);
        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
            String msg = reply();
            closeQuiet();
            throw new IOException("FTP connect: " + msg);
        }
    }

    private void walk(String dir, List<Entry> files, ListListener listener) throws IOException {
        if (listener != null) listener.onDir(dir, files.size());
        if (!ftp.changeWorkingDirectory(dir)) {
            if (isUserDirName(dir)) throw new IOException("missing-user");
            return;
        }
        FTPFile[] listed;
        try {
            listed = listCurrent();
        } catch (IOException fail) {
            if (isUserDirName(dir) || "/".equals(dir)) throw fail;
            Log.w(TAG, "LIST skip " + dir + ": " + fail.getMessage());
            return;
        }
        List<String> subdirs = new ArrayList<>();
        String prefix = dir.endsWith("/") ? dir : dir + "/";
        for (FTPFile file : listed) {
            if (file == null) continue;
            String name = file.getName();
            if (name == null || name.isEmpty()) {
                String raw = file.getRawListing();
                if (raw == null || raw.trim().isEmpty()) continue;
                List<SimpleFtpClient.Entry> parsed = SimpleFtpClient.parseList(dir, raw);
                if (parsed.isEmpty()) continue;
                SimpleFtpClient.Entry one = parsed.get(0);
                name = one.name;
                if (one.directory) {
                    subdirs.add(one.path);
                    continue;
                }
                files.add(new Entry(one.path, one.name, false, one.size));
                continue;
            }
            if (".".equals(name) || "..".equals(name)) continue;
            String path = prefix + name;
            if (file.isDirectory() || shouldTreatAsDirectory(file, name, path)) {
                subdirs.add(path);
            } else {
                long timeMs = file.getTimestamp() == null ? 0L : file.getTimestamp().getTimeInMillis();
                files.add(new Entry(path, name, false, file.getSize(), timeMs));
            }
        }
        Log.i(TAG, "LIST " + dir + " files=" + listed.length + " total=" + files.size());
        if (listener != null) listener.onDir(dir, files.size());
        for (String child : subdirs) {
            walk(child, files, listener);
        }
    }

    /**
     * Pure-FTPd LIST is UNIX, but Commons Net sometimes marks observation
     * folders as unknown/file. Probe CWD so those folders are still walked.
     */
    private boolean shouldTreatAsDirectory(FTPFile file, String name, String path) {
        if (file != null && file.isDirectory()) return true;
        if (PhotoSyncEngine.isPhoto(name)) return false;
        if (file != null && file.isFile() && file.getSize() > 0 && name.contains(".")) {
            return false;
        }
        return probeDirectory(path);
    }

    private boolean probeDirectory(String path) {
        if (ftp == null) return false;
        try {
            String here = ftp.printWorkingDirectory();
            if (!ftp.changeWorkingDirectory(path)) return false;
            if (here != null && !here.isEmpty()) {
                ftp.changeWorkingDirectory(here);
            }
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    /** LIST only. MLSD hangs on several Vespera firmware builds until the data timeout. */
    private FTPFile[] listCurrent() throws IOException {
        ftp.setDataTimeout(LIST_DATA_TIMEOUT_MS);
        try {
            FTPFile[] listed = ftp.listFiles();
            if (listed == null) {
                throw new IOException("LIST failed: " + reply());
            }
            return listed;
        } finally {
            ftp.setDataTimeout(TRANSFER_DATA_TIMEOUT_MS);
        }
    }

    private void ensureFtp() throws IOException {
        if (ftp == null || !ftp.isConnected()) {
            throw new IOException("FTP closed");
        }
    }

    private void closeQuiet() {
        if (ftp == null) return;
        try {
            if (ftp.isConnected()) ftp.logout();
        } catch (Exception ignored) {
        }
        try {
            ftp.disconnect();
        } catch (Exception ignored) {
        }
        ftp = null;
    }

    static boolean isUserDirName(String dir) {
        if (dir == null) return false;
        String n = dir.replace('\\', '/');
        if (n.length() > 1 && n.endsWith("/")) n = n.substring(0, n.length() - 1);
        if (n.startsWith("/")) n = n.substring(1);
        return "user".equalsIgnoreCase(n);
    }

    private static String normalizeDir(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (!path.startsWith("/")) return "/" + path;
        if (path.length() > 1 && path.endsWith("/")) return path.substring(0, path.length() - 1);
        return path;
    }

    private static final class CrcOutputStream extends FilterOutputStream {
        private final CRC32 crc;
        private final ByteListener listener;

        CrcOutputStream(OutputStream out, CRC32 crc, ByteListener listener) {
            super(out);
            this.crc = crc;
            this.listener = listener;
        }

        @Override public void write(int b) throws IOException {
            out.write(b);
            crc.update(b);
            if (listener != null) listener.onBytes(1);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            crc.update(b, off, len);
            if (listener != null) listener.onBytes(len);
        }
    }

    /**
     * Binds every control/data socket to the Vespera {@link Network}. If PASV
     * advertises 127.0.0.1 / 0.0.0.0, rewrite the destination to the control host.
     */
    private static final class NetworkBoundSocketFactory extends SocketFactory {
        private final Network network;
        private final String fallbackHost;

        NetworkBoundSocketFactory(Network network, String fallbackHost) {
            this.network = network;
            this.fallbackHost = fallbackHost;
        }

        @Override public Socket createSocket() throws IOException {
            return VesperaSockets.create(network);
        }

        @Override public Socket createSocket(String host, int port) throws IOException {
            return connect(createSocket(), host, port, 15_000);
        }

        @Override public Socket createSocket(String host, int port, java.net.InetAddress local, int localPort)
                throws IOException {
            Socket socket = createSocket();
            if (local != null && !socket.isBound()) {
                socket.bind(new InetSocketAddress(local, localPort));
            }
            return connect(socket, host, port, 15_000);
        }

        @Override public Socket createSocket(java.net.InetAddress host, int port) throws IOException {
            return connect(createSocket(), host.getHostAddress(), port, 15_000);
        }

        @Override public Socket createSocket(java.net.InetAddress host, int port,
                                             java.net.InetAddress local, int localPort) throws IOException {
            Socket socket = createSocket();
            if (local != null && !socket.isBound()) {
                socket.bind(new InetSocketAddress(local, localPort));
            }
            return connect(socket, host.getHostAddress(), port, 15_000);
        }

        private Socket connect(Socket socket, String host, int port, int timeoutMs) throws IOException {
            String dest = host;
            if (dest == null || dest.startsWith("127.") || dest.startsWith("0.")
                    || "localhost".equalsIgnoreCase(dest)) {
                dest = fallbackHost;
            }
            socket.connect(new InetSocketAddress(dest, port), timeoutMs);
            return socket;
        }
    }
}
