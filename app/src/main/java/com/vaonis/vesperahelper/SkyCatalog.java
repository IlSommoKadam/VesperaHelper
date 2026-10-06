package com.vaonis.vesperahelper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Resolves a catalog name via CDS SESAME and the object's catalog image.
 * The frame follows the SIMBAD angular size, so the object fills the picture.
 * Multi-night resume matches {@code captureStore} on the telescope.
 * Mosaic framing is not part of the start payload.
 */
final class SkyCatalog {
    private static final String SESAME_HOST = "cds.unistra.fr";
    private static final String SIMBAD_HOST = "simbad.cds.unistra.fr";
    private static final String PREVIEW_HOST = "alasky.cds.unistra.fr";
    private static final String PANSTARRS = "CDS/P/PanSTARRS/DR1/color-z-zg-g";
    private static final String DSS_COLOR = "CDS/P/DSS2/color";

    static final class Target {
        final String query;
        final String name;
        final String typeCode;
        final double raDeg;
        final double decDeg;
        /** JPEG framed on the catalog size of the object, or null if no image arrived. */
        final byte[] previewJpeg;

        Target(String query, String name, String typeCode, double raDeg, double decDeg,
                byte[] previewJpeg) {
            this.query = query == null ? "" : query;
            this.name = name == null ? "" : name;
            this.typeCode = typeCode == null ? "" : typeCode;
            this.raDeg = raDeg;
            this.decDeg = decDeg;
            this.previewJpeg = previewJpeg;
        }

        String objectId() {
            return compact(name.isEmpty() ? query : name);
        }
    }

    /** A stored capture that can be continued on a later night. */
    static final class Session {
        final String storeId;
        final String objectName;
        final int stacks;

        Session(String storeId, String objectName, int stacks) {
            this.storeId = storeId == null ? "" : storeId;
            this.objectName = objectName == null ? "" : objectName;
            this.stacks = Math.max(0, stacks);
        }
    }

    private SkyCatalog() {}

    static Target lookup(Context context, String query) throws Exception {
        String name = query == null ? "" : query.trim();
        if (name.isEmpty()) throw new Exception("empty");
        if (name.length() > 80) name = name.substring(0, 80);
        String path = "/cgi-bin/nph-sesame/-ox/S?"
                + URLEncoder.encode(name, StandardCharsets.UTF_8.name());
        HostDns.HttpResult page = HostDns.httpsGet(context, SESAME_HOST, path);
        if (page.code < 200 || page.code >= 300) throw new Exception("HTTP " + page.code);
        Target parsed = parseSesame(name, page.body);
        double fov = fieldOfViewDeg(angularSizeArcmin(context, parsed.name));
        byte[] jpeg = objectImage(context, PANSTARRS, parsed.raDeg, parsed.decDeg, fov);
        if (jpeg == null) {
            jpeg = objectImage(context, DSS_COLOR, parsed.raDeg, parsed.decDeg, fov);
        }
        return new Target(name, parsed.name, parsed.typeCode, parsed.raDeg, parsed.decDeg, jpeg);
    }

    static Session findSession(String rawJson, String objectName, String query) {
        if (rawJson == null || rawJson.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(rawJson);
            JSONObject store = captureStore(root);
            if (store == null) return null;
            JSONArray captures = store.optJSONArray("storedCaptures");
            if (captures == null) return null;
            Session best = null;
            for (int i = 0; i < captures.length(); i++) {
                JSONObject item = captures.optJSONObject(i);
                if (item == null || !matches(item, objectName, query) || !resumable(item)) continue;
                int stacks = item.optInt("totalStackingCount", 0);
                String id = item.optString("storeId", "");
                if (id.isEmpty()) continue;
                JSONObject target = item.optJSONObject("target");
                String label = target == null ? "" : target.optString("objectName", objectName);
                if (best == null || stacks >= best.stacks) {
                    best = new Session(id, label, stacks);
                }
            }
            return best;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Fresh multi-night: the telescope creates a resumable capture store. */
    static String startBody(Target target, VesperaStatusSnapshot snap) {
        if (target == null) return "";
        try {
            JSONObject body = new JSONObject();
            body.put("ra", target.raDeg);
            body.put("de", target.decDeg);
            body.put("dec", target.decDeg);
            body.put("rot", 0);
            body.put("type", "CATALOG");
            body.put("objectType", "ODC");
            body.put("objectId", target.objectId());
            body.put("objectName", target.name);
            body.put("resume", false);
            int gain = snap != null && snap.gain > 0 ? snap.gain : 150;
            long exposure = snap != null && snap.exposureMicroSec > 0
                    ? snap.exposureMicroSec : 10_000_000L;
            body.put("gain", gain);
            body.put("exposureMicroSec", exposure);
            body.put("histogramEnabled", true);
            body.put("histogramLow", -0.75);
            body.put("histogramMedium", 5);
            body.put("histogramHigh", 0);
            body.put("backgroundEnabled", true);
            body.put("backgroundPolyorder", 2);
            return body.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    static String resumeBody(Session session) {
        if (session == null || session.storeId.isEmpty()) return "";
        try {
            JSONObject body = new JSONObject();
            body.put("storeId", session.storeId);
            return body.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    static String raText(double raDeg) {
        double hours = raDeg / 15.0;
        hours = hours % 24.0;
        if (hours < 0) hours += 24.0;
        int h = (int) hours;
        double minutesFull = (hours - h) * 60.0;
        int m = (int) minutesFull;
        double s = (minutesFull - m) * 60.0;
        return String.format(Locale.US, "%02dh %02dm %04.1fs", h, m, s);
    }

    static String decText(double decDeg) {
        String sign = decDeg < 0 ? "−" : "+";
        double abs = Math.abs(decDeg);
        int d = (int) abs;
        double minutesFull = (abs - d) * 60.0;
        int m = (int) minutesFull;
        double s = (minutesFull - m) * 60.0;
        return String.format(Locale.US, "%s%02d° %02d′ %04.1f″", sign, d, m, s);
    }

    /** Altitude and azimuth (north = 0, east = 90) for the given site and UTC time. */
    static double[] altAz(double latDeg, double lonDeg, double raDeg, double decDeg, long utcMs) {
        double jd = utcMs / 86_400_000.0 + 2_440_587.5;
        double days = jd - 2_451_545.0;
        double gmst = 280.46061837 + 360.98564736629 * days;
        gmst = gmst % 360.0;
        if (gmst < 0) gmst += 360.0;
        double haDeg = gmst + lonDeg - raDeg;
        while (haDeg > 180) haDeg -= 360;
        while (haDeg < -180) haDeg += 360;
        double lat = Math.toRadians(latDeg);
        double dec = Math.toRadians(decDeg);
        double ha = Math.toRadians(haDeg);
        double sinAlt = Math.sin(lat) * Math.sin(dec)
                + Math.cos(lat) * Math.cos(dec) * Math.cos(ha);
        sinAlt = Math.max(-1, Math.min(1, sinAlt));
        double alt = Math.toDegrees(Math.asin(sinAlt));
        double az = Math.toDegrees(Math.atan2(
                -Math.sin(ha) * Math.cos(dec),
                Math.sin(dec) * Math.cos(lat) - Math.cos(dec) * Math.sin(lat) * Math.cos(ha)));
        az = (az % 360.0 + 360.0) % 360.0;
        return new double[] { alt, az };
    }

    static String typeLabel(String code) {
        if (code == null || code.isEmpty()) return "";
        String c = code.toUpperCase(Locale.US);
        if (c.startsWith("V*")) return "stella variabile";
        if (c.equals("GLC")) return "ammasso globulare";
        if (c.equals("PN")) return "nebulosa planetaria";
        if (c.equals("SNR")) return "resto di supernova";
        if (c.equals("OPC") || c.equals("CL*") || c.equals("AS*")) return "ammasso aperto";
        if (c.contains("NEB") || c.equals("HII") || c.equals("ISM") || c.equals("RFN")) {
            return "nebulosa";
        }
        if (c.contains("*") || "HS".equals(c) || "BD".equals(c)) return "stella";
        if (c.startsWith("G") || c.contains("AGN") || c.equals("LIN") || c.equals("SY")
                || c.equals("EMG") || c.equals("SB") || c.equals("SAB")) return "galassia";
        return code;
    }

    /**
     * Major axis from SIMBAD, in arcminutes. Zero when the catalog has no size.
     * Only the first two numbers count: the third on that line is the position angle.
     */
    private static double angularSizeArcmin(Context context, String objectName) {
        if (objectName == null || objectName.isEmpty()) return 0;
        try {
            String path = "/simbad/sim-id?Ident="
                    + URLEncoder.encode(objectName, StandardCharsets.UTF_8.name())
                    + "&output.format=ASCII";
            HostDns.HttpResult page = HostDns.httpsGet(context, SIMBAD_HOST, path);
            if (page.code < 200 || page.code >= 300) return 0;
            return parseAngularSize(page.body);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double parseAngularSize(String text) {
        if (text == null) return 0;
        int at = text.indexOf("Angular size:");
        if (at < 0) return 0;
        int end = text.indexOf('\n', at);
        String line = end < 0 ? text.substring(at) : text.substring(at, end);
        double major = 0;
        int found = 0;
        int i = 0;
        while (i < line.length() && found < 2) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') {
                i++;
                continue;
            }
            int j = i + 1;
            while (j < line.length()) {
                char d = line.charAt(j);
                if ((d >= '0' && d <= '9') || d == '.') j++;
                else break;
            }
            try {
                double value = Double.parseDouble(line.substring(i, j));
                if (value > major) major = value;
                found++;
            } catch (Exception ignored) {
                // skip a broken token and keep scanning this line
            }
            i = j;
        }
        return major;
    }

    /** Field a bit wider than the catalog size, so the whole object is in frame. */
    private static double fieldOfViewDeg(double majorArcmin) {
        if (majorArcmin <= 0) return 0.5;
        double fov = majorArcmin / 60.0 * 2.6;
        if (fov < 0.02) return 0.02;
        if (fov > 10.0) return 10.0;
        return fov;
    }

    private static byte[] objectImage(Context context, String survey, double raDeg, double decDeg,
            double fovDeg) {
        try {
            String hips = URLEncoder.encode(survey, StandardCharsets.UTF_8.name());
            String image = String.format(Locale.US,
                    "/hips-image-services/hips2fits?hips=%s&ra=%f&dec=%f"
                            + "&fov=%f&width=1100&height=740&format=jpg",
                    hips, raDeg, decDeg, fovDeg);
            byte[] jpeg = HostDns.httpsGetBytes(context, PREVIEW_HOST, image);
            if (jpeg.length < 32 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
                return null;
            }
            return jpeg;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Target parseSesame(String query, String xml) throws Exception {
        if (xml == null) throw new Exception("not_found");
        int resolver = xml.indexOf("<Resolver");
        if (resolver < 0) throw new Exception("not_found");
        String slice = xml.substring(resolver);
        String oname = tag(slice, "oname");
        String type = tag(slice, "otype");
        String ra = tag(slice, "jradeg");
        String dec = tag(slice, "jdedeg");
        if (ra.isEmpty() || dec.isEmpty()) throw new Exception("not_found");
        double raDeg = Double.parseDouble(ra);
        double decDeg = Double.parseDouble(dec);
        if (raDeg < 0 || raDeg >= 360 || decDeg < -90 || decDeg > 90) {
            throw new Exception("not_found");
        }
        return new Target(query, oname.isEmpty() ? query : oname, type, raDeg, decDeg, null);
    }

    private static String tag(String xml, String name) {
        String open = "<" + name + ">";
        String close = "</" + name + ">";
        int a = xml.indexOf(open);
        if (a < 0) return "";
        int b = xml.indexOf(close, a + open.length());
        if (b < 0) return "";
        return xml.substring(a + open.length(), b).trim();
    }

    /** captureStore dallo stato grezzo del Vespera (per i client), o null. */
    static JSONObject captureStoreOf(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) return null;
        try {
            return captureStore(new JSONObject(rawJson));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject captureStore(JSONObject root) {
        JSONObject store = root.optJSONObject("captureStore");
        if (store != null) return store;
        JSONObject nested = root.optJSONObject("result");
        if (nested == null) nested = root.optJSONObject("data");
        return nested == null ? null : nested.optJSONObject("captureStore");
    }

    private static boolean resumable(JSONObject item) {
        String state = item.optString("storeState", "");
        JSONObject store = item.optJSONObject("store");
        if (state.isEmpty() && store != null) state = store.optString("state", "");
        if (state.isEmpty()) state = item.optString("state", "");
        String upper = state.toUpperCase(Locale.US);
        return !upper.contains("NON_RESUMABLE");
    }

    private static boolean matches(JSONObject item, String objectName, String query) {
        String wantName = compact(objectName);
        String wantQuery = compact(query);
        JSONObject target = item.optJSONObject("target");
        String id = compact(target == null ? "" : target.optString("objectId", ""));
        String name = compact(target == null ? "" : target.optString("objectName", ""));
        String store = compact(item.optString("storeId", ""));
        if (!wantName.isEmpty() && (wantName.equals(id) || wantName.equals(name)
                || store.endsWith(wantName))) {
            return true;
        }
        return !wantQuery.isEmpty() && (wantQuery.equals(id) || wantQuery.equals(name)
                || store.endsWith(wantQuery));
    }

    private static String compact(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '_' || c == '-') continue;
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }
}
