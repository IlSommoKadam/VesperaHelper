package com.vaonis.vesperahelper;

import android.net.Network;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Reads observatory coordinates from the Vespera HTTP API if Singularity
 * already sent them. The instrument has no GPS of its own.
 */
final class VesperaLocationClient {
    private static final String TAG = "VesperaLoc";
    private static final String HOST = "10.0.0.1";
    private static final String[] PATHS = {
            "/v1/app/status",
            "/v1/device/status",
            "/v1/settings",
            "/dev-location",
            "/gps"
    };

    static final class Site {
        final double lat;
        final double lon;

        Site(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    private VesperaLocationClient() {}

    static Site fetch(Network network) {
        int[] ports = portsToTry();
        for (int port : ports) {
            if (!portOpen(network, port)) continue;
            for (String path : PATHS) {
                String body = httpGet(network, port, path);
                Site site = parse(body);
                if (site != null) {
                    Log.i(TAG, "location from :" + port + path
                            + " lat=" + site.lat + " lon=" + site.lon);
                    return site;
                }
            }
        }
        return null;
    }

    private static int[] portsToTry() {
        int known = InstrumentWatchdog.lastApiPort();
        if (known == 8082) return new int[] {8082, 8083, 8080};
        if (known == 8083) return new int[] {8082, 8083, 8080};
        return new int[] {8082, 8083, 8080};
    }

    private static boolean portOpen(Network network, int port) {
        try (Socket socket = VesperaSockets.create(network)) {
            socket.connect(new InetSocketAddress(HOST, port), 2_000);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String httpGet(Network network, int port, String path) {
        try {
            VesperaHttp.Response response = VesperaHttp.get(network, HOST, port, path, 4_000);
            if (response.code >= 400) {
                Log.w(TAG, path + " HTTP " + response.code);
                return "";
            }
            return response.body;
        } catch (Exception failure) {
            Log.w(TAG, path + " fail: " + failure.getMessage());
            return "";
        }
    }

    static Site parse(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            String trim = body.trim();
            if (trim.startsWith("[")) {
                return walk(new JSONArray(trim), 0);
            }
            if (trim.startsWith("{")) {
                return walk(new JSONObject(trim), 0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Site walk(Object node, int depth) {
        if (node == null || depth > 8) return null;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            Site direct = fromObject(obj);
            if (direct != null) return direct;
            JSONArray names = obj.names();
            if (names == null) return null;
            for (int i = 0; i < names.length(); i++) {
                Site nested = walk(obj.opt(names.optString(i)), depth + 1);
                if (nested != null) return nested;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Site nested = walk(array.opt(i), depth + 1);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static Site fromObject(JSONObject obj) {
        Double lat = firstNumber(obj, "latitude", "lat", "gpsLatitude", "gps_lat");
        Double lon = firstNumber(obj, "longitude", "lon", "lng", "gpsLongitude", "gps_lon");
        if (lat == null || lon == null) return null;
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
        if (Math.abs(lat) < 0.01 && Math.abs(lon) < 0.01) return null;
        return new Site(lat, lon);
    }

    /** Parses lat/lon from a status snapshot (location string or raw JSON). */
    static Site fromSnapshot(VesperaStatusSnapshot snap) {
        if (snap == null) return null;
        Site fromText = parseLocationText(snap.location);
        if (fromText != null) return fromText;
        return parse(snap.rawJson);
    }

    static Site parseLocationText(String loc) {
        if (loc == null || loc.isEmpty()) return null;
        int comma = loc.lastIndexOf(',');
        if (comma <= 0 || comma >= loc.length() - 1) return null;
        String lonPart = loc.substring(comma + 1).trim();
        String before = loc.substring(0, comma).trim();
        int sep = Math.max(before.lastIndexOf(' '), before.lastIndexOf('·'));
        String latPart = sep >= 0 ? before.substring(sep + 1).trim() : before;
        try {
            double lat = Double.parseDouble(latPart);
            double lon = Double.parseDouble(lonPart);
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
            if (Math.abs(lat) < 0.01 && Math.abs(lon) < 0.01) return null;
            return new Site(lat, lon);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Great-circle distance in metres. */
    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earth = 6_371_000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earth * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static Double firstNumber(JSONObject obj, String... keys) {
        for (String key : keys) {
            if (!obj.has(key) || obj.isNull(key)) continue;
            try {
                Object value = obj.get(key);
                if (value instanceof Number) return ((Number) value).doubleValue();
                if (value instanceof String) {
                    String text = ((String) value).trim();
                    if (!text.isEmpty()) return Double.parseDouble(text);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
