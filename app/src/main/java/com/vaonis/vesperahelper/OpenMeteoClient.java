package com.vaonis.vesperahelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Queries the Open-Meteo forecast API over Ethernet/Internet (not the Vespera
 * Wi-Fi) for short-term precipitation.
 *
 * <p>Uses the most temporally granular field available ({@code minutely_15}
 * precipitation, i.e. mm per 15-minute bucket) and falls back to {@code hourly}
 * precipitation when the 15-minute series is missing. Times are requested as
 * unix timestamps in UTC to avoid timezone parsing.
 */
final class OpenMeteoClient {
    private static final String ENDPOINT = "https://api.open-meteo.com/v1/forecast";
    private static final int TIMEOUT_MS = 8_000;
    private static final long FIFTEEN_MIN_SEC = 15 * 60L;
    private static final long ONE_HOUR_SEC = 60 * 60L;

    static final class Forecast {
        final boolean success;
        /** Max precipitation (mm) forecast within the look-ahead window. */
        final double precipitationMm;
        /** Max precipitation probability (%) within the window, or -1 if unknown. */
        final int probabilityPercent;
        /** Which series was used: "minutely_15" or "hourly". */
        final String granularity;
        final String error;

        private Forecast(boolean success, double precipitationMm, int probabilityPercent,
                String granularity, String error) {
            this.success = success;
            this.precipitationMm = precipitationMm;
            this.probabilityPercent = probabilityPercent;
            this.granularity = granularity == null ? "" : granularity;
            this.error = error == null ? "" : error;
        }

        static Forecast ok(double precip, int prob, String granularity) {
            return new Forecast(true, precip, prob, granularity, "");
        }

        static Forecast fail(String error) {
            return new Forecast(false, 0d, -1, "", error);
        }
    }

    private OpenMeteoClient() {}

    /**
     * Fetches precipitation forecast and returns the max value that overlaps the
     * window [now, now + lookAheadMin]. The bucket covering "now" is included so
     * imminent rain that has just started is not missed.
     */
    static Forecast fetch(double latitude, double longitude, int lookAheadMin) {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return Forecast.fail("no_coords");
        }
        int lookAhead = Math.max(1, lookAheadMin);
        String url = ENDPOINT
                + "?latitude=" + String.format(Locale.US, "%.5f", latitude)
                + "&longitude=" + String.format(Locale.US, "%.5f", longitude)
                + "&minutely_15=precipitation,precipitation_probability"
                + "&hourly=precipitation,precipitation_probability"
                + "&forecast_days=1&timeformat=unixtime&timezone=UTC";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "VesperaHelper (weather protection)");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(stream);
            if (code >= 400) {
                return Forecast.fail("HTTP " + code);
            }
            JSONObject root = new JSONObject(body);
            long nowSec = System.currentTimeMillis() / 1000L;
            long windowEnd = nowSec + lookAhead * 60L;

            Forecast minutely = fromSeries(root.optJSONObject("minutely_15"),
                    nowSec, windowEnd, FIFTEEN_MIN_SEC, "minutely_15");
            if (minutely != null) return minutely;

            Forecast hourly = fromSeries(root.optJSONObject("hourly"),
                    nowSec, windowEnd, ONE_HOUR_SEC, "hourly");
            if (hourly != null) return hourly;

            return Forecast.fail("no_forecast_data");
        } catch (Exception failure) {
            String message = failure.getMessage();
            return Forecast.fail(message == null ? failure.getClass().getSimpleName() : message);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Scans a time/precipitation series for buckets overlapping the look-ahead
     * window. A bucket starting at {@code t} covers [t, t + bucketSec); it is
     * relevant when it overlaps [now, windowEnd].
     */
    private static Forecast fromSeries(JSONObject series, long nowSec, long windowEnd,
            long bucketSec, String granularity) {
        if (series == null) return null;
        JSONArray times = series.optJSONArray("time");
        JSONArray precip = series.optJSONArray("precipitation");
        if (times == null || precip == null || times.length() == 0) return null;
        JSONArray prob = series.optJSONArray("precipitation_probability");

        double maxPrecip = 0d;
        int maxProb = -1;
        boolean found = false;
        double firstUpcomingPrecip = 0d;
        int firstUpcomingProb = -1;
        boolean haveUpcoming = false;

        for (int i = 0; i < times.length() && i < precip.length(); i++) {
            long start = times.optLong(i, Long.MIN_VALUE);
            if (start == Long.MIN_VALUE) continue;
            long end = start + bucketSec;
            double value = precip.optDouble(i, 0d);
            int p = prob != null && i < prob.length() ? prob.optInt(i, -1) : -1;

            // First bucket that ends in the future — fallback if nothing overlaps.
            if (!haveUpcoming && end > nowSec) {
                firstUpcomingPrecip = value;
                firstUpcomingProb = p;
                haveUpcoming = true;
            }
            boolean overlaps = end > nowSec && start <= windowEnd;
            if (overlaps) {
                found = true;
                if (value > maxPrecip) maxPrecip = value;
                if (p > maxProb) maxProb = p;
            }
        }

        if (found) {
            return Forecast.ok(maxPrecip, maxProb, granularity);
        }
        if (haveUpcoming) {
            return Forecast.ok(firstUpcomingPrecip, firstUpcomingProb, granularity);
        }
        return null;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = stream.read(buf)) > 0) out.write(buf, 0, n);
        stream.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
