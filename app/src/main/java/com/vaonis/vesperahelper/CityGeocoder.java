package com.vaonis.vesperahelper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Nominatim (OpenStreetMap) city search / reverse over Ethernet/Internet. */
final class CityGeocoder {
    private static final String HOST = "nominatim.openstreetmap.org";

    static final class Hit {
        final String label;
        final double lat;
        final double lon;
        final String countryCode;

        Hit(String label, double lat, double lon, String countryCode) {
            this.label = label;
            this.lat = lat;
            this.lon = lon;
            this.countryCode = countryCode == null ? "" : countryCode;
        }
    }

    private CityGeocoder() {}

    static List<Hit> search(Context context, String query, String language) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) return new ArrayList<>();
        String lang = language == null || language.isEmpty() ? "it" : language;
        String path = "/search?format=jsonv2&limit=6"
                + "&addressdetails=1&accept-language=" + URLEncoder.encode(lang, "UTF-8")
                + "&q=" + URLEncoder.encode(q, "UTF-8");
        JSONArray array = new JSONArray(httpGet(context, path));
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject row = array.optJSONObject(i);
            Hit hit = hitFrom(row);
            if (hit != null) hits.add(hit);
        }
        return hits;
    }

    /** City/town at these coordinates, or null if Nominatim has no result. */
    static Hit reverse(Context context, double lat, double lon, String language) throws Exception {
        if (!valid(lat, lon)) return null;
        String lang = language == null || language.isEmpty() ? "it" : language;
        String path = "/reverse?format=jsonv2"
                + "&addressdetails=1&zoom=14"
                + "&accept-language=" + URLEncoder.encode(lang, "UTF-8")
                + "&lat=" + String.format(Locale.US, "%.7f", lat)
                + "&lon=" + String.format(Locale.US, "%.7f", lon);
        JSONObject row = new JSONObject(httpGet(context, path));
        if (row.has("error")) return null;
        Hit hit = hitFrom(row);
        if (hit == null) return null;
        return new Hit(hit.label, lat, lon, hit.countryCode);
    }

    private static String httpGet(Context context, String pathAndQuery) throws Exception {
        HostDns.HttpResult result = HostDns.httpsGet(context, HOST, pathAndQuery);
        if (result.code >= 400) throw new Exception("HTTP " + result.code);
        if (result.body == null || result.body.isEmpty()) throw new Exception("empty body");
        return result.body;
    }

    private static Hit hitFrom(JSONObject row) {
        if (row == null) return null;
        double lat = parseCoord(row.optString("lat", ""));
        double lon = parseCoord(row.optString("lon", ""));
        if (!valid(lat, lon)) return null;
        String name = labelFrom(row);
        if (name.isEmpty()) {
            name = String.format(Locale.US, "%.3f, %.3f", lat, lon);
        }
        return new Hit(name, lat, lon, countryCode(row));
    }

    private static String labelFrom(JSONObject row) {
        JSONObject address = row.optJSONObject("address");
        if (address != null) {
            String place = first(address, "city", "town", "village", "municipality",
                    "hamlet", "suburb", "isolated_dwelling");
            String area = first(address, "county", "state_district", "province", "state");
            if (place != null && !place.isEmpty()) {
                if (area != null && !area.isEmpty() && !area.equalsIgnoreCase(place)) {
                    return place + ", " + area;
                }
                return place;
            }
        }
        return shortenDisplayName(row.optString("display_name", ""));
    }

    private static String first(JSONObject address, String... keys) {
        for (String key : keys) {
            String value = address.optString(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    private static String shortenDisplayName(String name) {
        String text = name == null ? "" : name.trim();
        if (text.isEmpty()) return "";
        int comma = text.indexOf(',');
        if (comma > 0 && comma < 48) {
            String rest = text.substring(comma + 1).trim();
            int second = rest.indexOf(',');
            if (second > 0) rest = rest.substring(0, second).trim();
            return text.substring(0, comma).trim() + ", " + rest;
        }
        return text;
    }

    private static String countryCode(JSONObject row) {
        JSONObject address = row.optJSONObject("address");
        if (address == null) return "";
        return address.optString("country_code", "").trim().toUpperCase(Locale.US);
    }

    private static boolean valid(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static double parseCoord(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }
}
