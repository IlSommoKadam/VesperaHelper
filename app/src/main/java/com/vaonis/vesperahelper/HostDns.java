package com.vaonis.vesperahelper;

import android.content.Context;
import android.net.Network;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Hostname resolve that survives broken LAN DNS: try the normal stack, then
 * DNS-over-HTTPS against 8.8.8.8 / 1.1.1.1 by IP, then a hardcoded A record.
 * HTTPS is done with an explicit SNI socket (IP in the URL breaks OkHttp TLS).
 */
final class HostDns {
    private static final String TAG = "VesperaDns";
    private static final int TIMEOUT_MS = 8_000;
    private static final String[] OPEN_METEO_FALLBACK = { "94.130.142.35" };
    private static final String[] NOMINATIM_FALLBACK = { "151.101.1.91", "151.101.65.91" };

    static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private HostDns() {}

    static InetAddress resolve(Context context, String host) throws Exception {
        if (host == null || host.isEmpty()) throw new Exception("empty host");
        if (isIpLiteral(host)) return InetAddress.getByName(host);
        Network network = InternetNetwork.find(context);
        Exception primary = null;
        if (network != null) {
            try {
                InetAddress ipv4 = firstIpv4(network.getAllByName(host));
                if (ipv4 != null) return ipv4;
            } catch (Exception failure) {
                primary = failure;
                Log.w(TAG, "network DNS failed for " + host + ": " + failure.getMessage());
            }
        }
        // On the Pi, ConnectivityService often keeps a stale LAN DNS (e.g. .1)
        // while eth0 actually uses 8.8.8.8. System getAllByName then hangs ~10s
        // before failing — skip it and go straight to DoH / hardcoded IP.
        InetAddress viaDoh = resolveDoh(context, network, host);
        if (viaDoh != null) return viaDoh;
        InetAddress fallback = hardcodedFallback(host);
        if (fallback != null) {
            Log.i(TAG, host + " -> " + fallback.getHostAddress() + " via hardcoded fallback");
            return fallback;
        }
        try {
            InetAddress ipv4 = firstIpv4(InetAddress.getAllByName(host));
            if (ipv4 != null) return ipv4;
        } catch (Exception failure) {
            if (primary == null) primary = failure;
            Log.w(TAG, "system DNS failed for " + host + ": " + failure.getMessage());
        }
        throw primary == null ? new Exception("no A record for " + host) : primary;
    }

    /** HTTPS GET with SNI = host, TCP to resolved IP (works when LAN DNS is broken). */
    static HttpResult httpsGet(Context context, String host, String pathAndQuery) throws Exception {
        return https(context, host, "GET", pathAndQuery, null, null, "application/json");
    }

    /** HTTPS POST JSON. Same unbound-then-bound sockets as {@link #httpsGet}. */
    static HttpResult httpsPost(Context context, String host, String path, byte[] body)
            throws Exception {
        return https(context, host, "POST", path, body, "application/json; charset=UTF-8",
                "application/json");
    }

    private static HttpResult https(Context context, String host, String method, String path,
            byte[] body, String contentType, String accept) throws Exception {
        InetAddress address = resolve(context, host);
        Network network = InternetNetwork.find(context);
        Exception last = null;
        // Ethernet/VPN first. An unbound socket follows Android's default network,
        // which on the Pi is the Vespera Wi‑Fi (no route to the Internet).
        Network[] attempts = network == null
                ? new Network[] { null }
                : new Network[] { network, null };
        for (Network net : attempts) {
            try {
                HttpResult result = httpsOn(net, host, address, method, path, body, contentType, accept);
                Log.i(TAG, host + " " + method + " HTTP " + result.code
                        + " via " + (net == null ? "default" : "network")
                        + " body=" + result.body.length());
                return result;
            } catch (Exception failure) {
                last = failure;
                Log.w(TAG, host + " " + method + " via "
                        + (net == null ? "default" : "network")
                        + " failed: " + failure.getMessage());
            }
        }
        throw last == null ? new Exception("https failed") : last;
    }

    private static HttpResult httpsGetOn(Network network, String host, InetAddress address,
            String pathAndQuery, String accept) throws Exception {
        return httpsOn(network, host, address, "GET", pathAndQuery, null, null, accept);
    }

    private static HttpResult httpsOn(Network network, String host, InetAddress address,
            String method, String pathAndQuery, byte[] body, String contentType, String accept)
            throws Exception {
        Socket plain = null;
        SSLSocket ssl = null;
        long t0 = System.currentTimeMillis();
        try {
            Log.i(TAG, "connect " + host + " -> " + address.getHostAddress() + ":443");
            plain = openPlain(network, address, 443);
            plain.setTcpNoDelay(true);
            plain.setSoTimeout(TIMEOUT_MS);
            SSLSocketFactory factory = SSLContext.getDefault().getSocketFactory();
            ssl = (SSLSocket) factory.createSocket(plain, host, 443, true);
            plain = null;
            SSLParameters params = ssl.getSSLParameters();
            params.setServerNames(Collections.singletonList(new SNIHostName(host)));
            // Prefer HTTP/1.1-friendly TLS; avoid hanging on ALPN negotiation quirks.
            try {
                params.setApplicationProtocols(new String[] { "http/1.1" });
            } catch (Exception ignored) {
            }
            ssl.setSSLParameters(params);
            ssl.setUseClientMode(true);
            ssl.setSoTimeout(TIMEOUT_MS);
            ssl.startHandshake();
            Log.i(TAG, "handshake ok " + host + " in " + (System.currentTimeMillis() - t0) + "ms");
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                throw new Exception("hostname not verified: " + host);
            }

            String path = pathAndQuery == null || pathAndQuery.isEmpty() ? "/" : pathAndQuery;
            if (path.charAt(0) != '/') path = "/" + path;
            StringBuilder request = new StringBuilder();
            request.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(host).append("\r\n");
            request.append("User-Agent: VesperaHelper\r\n");
            request.append("Accept: ").append(accept).append("\r\n");
            request.append("Accept-Encoding: identity\r\n");
            if (body != null) {
                request.append("Content-Type: ").append(contentType).append("\r\n");
                request.append("Content-Length: ").append(body.length).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            OutputStream out = ssl.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            if (body != null && body.length > 0) out.write(body);
            out.flush();

            byte[] raw = readAllBytes(ssl.getInputStream());
            Log.i(TAG, "read " + raw.length + " bytes from " + host
                    + " in " + (System.currentTimeMillis() - t0) + "ms");
            return parseHttp(raw);
        } finally {
            try { if (ssl != null) ssl.close(); } catch (Exception ignored) {}
            try { if (plain != null) plain.close(); } catch (Exception ignored) {}
        }
    }

    private static Socket openPlain(Network network, InetAddress address, int port) throws Exception {
        Socket socket = network != null ? network.getSocketFactory().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(address, port), TIMEOUT_MS);
        return socket;
    }

    private static InetAddress hardcodedFallback(String host) {
        String[] ips = null;
        if ("api.open-meteo.com".equalsIgnoreCase(host)) ips = OPEN_METEO_FALLBACK;
        else if ("nominatim.openstreetmap.org".equalsIgnoreCase(host)) ips = NOMINATIM_FALLBACK;
        if (ips == null) return null;
        try {
            return InetAddress.getByName(ips[0]);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static InetAddress firstIpv4(InetAddress[] all) {
        if (all == null) return null;
        for (InetAddress address : all) {
            if (address instanceof Inet4Address) return address;
        }
        return null;
    }

    private static InetAddress resolveDoh(Context context, Network network, String host) {
        String[] endpoints = {
                "https://8.8.8.8/resolve?name=" + host + "&type=A",
                "https://1.1.1.1/dns-query?name=" + host + "&type=A"
        };
        String[] hosts = { "dns.google", "cloudflare-dns.com" };
        Network[] nets = network == null
                ? new Network[] { null }
                : new Network[] { network, null };
        for (Network net : nets) {
            for (int i = 0; i < endpoints.length; i++) {
                try {
                    // DoH itself talks to a literal IP — use the same SNI socket path.
                    String path = endpoints[i].substring(endpoints[i].indexOf('/', 8));
                    HttpResult result = httpsGetLiteral(net, hosts[i],
                            i == 0 ? "8.8.8.8" : "1.1.1.1", path);
                    if (result.code >= 400) continue;
                    JSONObject json = new JSONObject(result.body);
                    JSONArray answers = json.optJSONArray("Answer");
                    if (answers == null) continue;
                    for (int a = 0; a < answers.length(); a++) {
                        JSONObject row = answers.optJSONObject(a);
                        if (row == null || row.optInt("type", 0) != 1) continue;
                        String data = row.optString("data", "").trim();
                        if (data.isEmpty() || !isIpLiteral(data)) continue;
                        Log.i(TAG, host + " -> " + data + " via DoH"
                                + (net == null ? " (default)" : " (network)"));
                        return InetAddress.getByName(data);
                    }
                } catch (Exception failure) {
                    Log.w(TAG, "DoH " + hosts[i] + " via "
                            + (net == null ? "default" : "network")
                            + ": " + failure.getMessage());
                }
            }
        }
        return null;
    }

    /** HTTPS GET when the TCP peer is already a literal IP. */
    private static HttpResult httpsGetLiteral(Network network, String sniHost, String ip, String path)
            throws Exception {
        return httpsGetOn(network, sniHost, InetAddress.getByName(ip), path, "application/dns-json");
    }

    private static HttpResult parseHttp(byte[] raw) throws Exception {
        byte[] sep = { '\r', '\n', '\r', '\n' };
        int headerEnd = indexOf(raw, sep, 0);
        if (headerEnd < 0) throw new Exception("bad http response");
        String header = new String(raw, 0, headerEnd, StandardCharsets.US_ASCII);
        int bodyStart = headerEnd + 4;
        int code = 0;
        String first = header.split("\r\n", 2)[0];
        String[] parts = first.split(" ");
        if (parts.length >= 2) {
            try { code = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
        }
        byte[] bodyBytes;
        if (header.toLowerCase(Locale.US).contains("transfer-encoding: chunked")) {
            // Chunk sizes are bytes, not UTF-16 chars — decode before String conversion
            // or a single "°C" in Open-Meteo JSON desyncs the parser.
            bodyBytes = decodeChunked(raw, bodyStart);
        } else {
            bodyBytes = Arrays.copyOfRange(raw, bodyStart, raw.length);
        }
        return new HttpResult(code, new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static byte[] decodeChunked(byte[] raw, int offset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = { '\r', '\n' };
        int i = offset;
        while (i < raw.length) {
            int lineEnd = indexOf(raw, crlf, i);
            if (lineEnd < 0) break;
            String sizeHex = new String(raw, i, lineEnd - i, StandardCharsets.US_ASCII).trim();
            int semi = sizeHex.indexOf(';');
            if (semi >= 0) sizeHex = sizeHex.substring(0, semi).trim();
            int size;
            try {
                size = Integer.parseInt(sizeHex, 16);
            } catch (Exception e) {
                break;
            }
            i = lineEnd + 2;
            if (size == 0) break;
            if (i + size > raw.length) {
                out.write(raw, i, raw.length - i);
                break;
            }
            out.write(raw, i, size);
            i += size + 2; // skip data + CRLF
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!(c == '.' || (c >= '0' && c <= '9'))) return false;
        }
        return host.indexOf('.') > 0;
    }

    private static byte[] readAllBytes(InputStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = stream.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
