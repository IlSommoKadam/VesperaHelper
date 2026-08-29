package com.vaonis.vesperahelper;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Queries the Open-Meteo forecast API over Ethernet/Internet (not the Vespera
 * Wi-Fi) for short-term precipitation and overnight icons, temperature and rain.
 *
 * <p>Hostnames are resolved via {@link HostDns} so devices with broken LAN DNS
 * (common on the observatory Pi) still reach the API.
 */
final class OpenMeteoClient {
    private static final String TAG = "OpenMeteo";
    private static final String HOST = "api.open-meteo.com";
    private static final String PATH = "/v1/forecast";
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

    /** One overnight forecast cell (3-hour step). */
    static final class HourSlot {
        final long epochMs;
        final int weatherCode;
        /** Precipitation summed over the 3 h step (mm). */
        final double precipitationMm;
        final int probabilityPercent;
        /** Temperature at the slot hour (°C), or {@link Double#NaN} if unknown. */
        final double temperatureC;

        HourSlot(long epochMs, int weatherCode, double precipitationMm, int probabilityPercent,
                double temperatureC) {
            this.epochMs = epochMs;
            this.weatherCode = weatherCode;
            this.precipitationMm = precipitationMm;
            this.probabilityPercent = probabilityPercent;
            this.temperatureC = temperatureC;
        }
    }

    /** One observational night: dusk → dawn inclusive of dawn hour. */
    static final class NightWindow {
        final long nightStartMs;
        final long nightEndMs;
        final List<HourSlot> slots;

        NightWindow(long nightStartMs, long nightEndMs, List<HourSlot> slots) {
            this.nightStartMs = nightStartMs;
            this.nightEndMs = nightEndMs;
            this.slots = slots == null ? new ArrayList<>() : slots;
        }
    }

    /** Overnight fetch result with optional error for the UI footer. */
    static final class NightResult {
        final List<HourSlot> slots;
        final List<NightWindow> nights;
        final String error;

        NightResult(List<HourSlot> slots, List<NightWindow> nights, String error) {
            this.slots = slots == null ? new ArrayList<>() : slots;
            this.nights = nights == null ? new ArrayList<>() : nights;
            this.error = error == null ? "" : error;
        }

        static NightResult ok(List<NightWindow> nights) {
            List<HourSlot> first = nights.isEmpty() ? new ArrayList<>() : nights.get(0).slots;
            return new NightResult(first, nights, "");
        }

        static NightResult fail(String error) {
            return new NightResult(new ArrayList<>(), new ArrayList<>(), error);
        }
    }

    private OpenMeteoClient() {}

    private static final int NIGHT_FORECAST_DAYS = 7;

    /**
     * Observational nights in 3-hour steps from dusk through dawn (inclusive),
     * for several upcoming nights. {@code nightStartHour}/{@code nightEndHour}
     * are site day-end / day-start hours.
     */
    static NightResult fetchNightSlots(Context context, double latitude, double longitude,
            java.util.TimeZone zone, int nightStartHour, int nightEndHour) {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return NightResult.fail("no_coords");
        }
        if (zone == null) zone = java.util.TimeZone.getDefault();
        String query = "?latitude=" + String.format(Locale.US, "%.5f", latitude)
                + "&longitude=" + String.format(Locale.US, "%.5f", longitude)
                + "&hourly=weather_code,precipitation,precipitation_probability,temperature_2m"
                + "&forecast_days=" + NIGHT_FORECAST_DAYS
                + "&timeformat=unixtime&timezone=UTC";
        try {
            HostDns.HttpResult http = HostDns.httpsGet(context, HOST, PATH + query);
            if (http.code >= 400) {
                Log.w(TAG, "night forecast HTTP " + http.code);
                return NightResult.fail("HTTP " + http.code);
            }
            String body = http.body;
            JSONObject root = new JSONObject(body);
            JSONObject hourly = root.optJSONObject("hourly");
            if (hourly == null) return NightResult.fail("no_hourly");
            JSONArray times = hourly.optJSONArray("time");
            JSONArray codes = hourly.optJSONArray("weather_code");
            JSONArray precip = hourly.optJSONArray("precipitation");
            JSONArray probs = hourly.optJSONArray("precipitation_probability");
            JSONArray temps = hourly.optJSONArray("temperature_2m");
            if (times == null || codes == null || times.length() == 0) {
                return NightResult.fail("no_series");
            }

            long firstNightStart = nightWindowStartMs(zone, nightStartHour, nightEndHour);
            List<NightWindow> nights = new ArrayList<>();
            java.util.Calendar nightCal = java.util.Calendar.getInstance(zone);
            nightCal.setTimeInMillis(firstNightStart);
            for (int n = 0; n < NIGHT_FORECAST_DAYS - 1; n++) {
                if (n > 0) nightCal.add(java.util.Calendar.DAY_OF_YEAR, 1);
                long nightStartMs = nightCal.getTimeInMillis();
                long nightEndMs = nightEndMs(zone, nightStartMs, nightEndHour);
                List<HourSlot> slots = new ArrayList<>();
                for (int i = 0; i < times.length() && i < codes.length(); i++) {
                    long startSec = times.optLong(i, Long.MIN_VALUE);
                    if (startSec == Long.MIN_VALUE) continue;
                    long epochMs = startSec * 1000L;
                    // Inclusive dawn (= fine osservazioni).
                    if (epochMs < nightStartMs || epochMs > nightEndMs) continue;
                    java.util.Calendar cal = java.util.Calendar.getInstance(zone);
                    cal.setTimeInMillis(epochMs);
                    int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
                    boolean dawnSlot = hour == nightEndHour && epochMs == nightEndMs;
                    if (hour % 3 != 0 && !dawnSlot) continue;
                    int wx = codes.optInt(i, 0);
                    double tempC = slotTemperatureC(temps, i);
                    PrecipSum precipSum = sumPrecipUntilNextSlot(
                            times, precip, probs, i, nightStartMs, nightEndMs,
                            zone, nightEndHour);
                    slots.add(new HourSlot(epochMs, wx, precipSum.mm, precipSum.maxProb, tempC));
                }
                if (!slots.isEmpty()) {
                    nights.add(new NightWindow(nightStartMs, nightEndMs, slots));
                }
            }
            if (nights.isEmpty()) return NightResult.fail("no_series");
            return NightResult.ok(nights);
        } catch (Exception failure) {
            String message = failure.getMessage();
            if (message == null || message.isEmpty()) {
                message = failure.getClass().getSimpleName();
            }
            Log.w(TAG, "night forecast failed: " + message);
            return NightResult.fail(message);
        }
    }

    private static double slotTemperatureC(JSONArray temps, int index) {
        if (temps == null || index < 0 || index >= temps.length() || temps.isNull(index)) {
            return Double.NaN;
        }
        return temps.optDouble(index, Double.NaN);
    }

    private static final class PrecipSum {
        final double mm;
        final int maxProb;

        PrecipSum(double mm, int maxProb) {
            this.mm = mm;
            this.maxProb = maxProb;
        }
    }

    /**
     * Sums hourly precipitation from {@code startIndex} until the next displayed
     * 3 h (or dawn) slot, still inside the night window.
     */
    private static PrecipSum sumPrecipUntilNextSlot(JSONArray times, JSONArray precip,
            JSONArray probs, int startIndex, long nightStartMs, long nightEndMs,
            java.util.TimeZone zone, int nightEndHour) {
        double mm = 0d;
        int maxProb = -1;
        for (int h = 0; startIndex + h < times.length(); h++) {
            long tSec = times.optLong(startIndex + h, Long.MIN_VALUE);
            if (tSec == Long.MIN_VALUE) break;
            long tMs = tSec * 1000L;
            if (tMs < nightStartMs || tMs > nightEndMs) break;
            if (h > 0) {
                java.util.Calendar next = java.util.Calendar.getInstance(zone);
                next.setTimeInMillis(tMs);
                int nextHour = next.get(java.util.Calendar.HOUR_OF_DAY);
                boolean nextDawn = nextHour == nightEndHour && tMs == nightEndMs;
                if (nextHour % 3 == 0 || nextDawn) break;
            }
            int idx = startIndex + h;
            if (precip != null && idx < precip.length() && !precip.isNull(idx)) {
                mm += precip.optDouble(idx, 0d);
            }
            if (probs != null && idx < probs.length() && !probs.isNull(idx)) {
                int p = probs.optInt(idx, -1);
                if (p > maxProb) maxProb = p;
            }
        }
        return new PrecipSum(mm, maxProb);
    }

    /**
     * Start of the night window to display: the night currently in progress, or
     * the next upcoming night after dawn.
     */
    private static long nightWindowStartMs(java.util.TimeZone zone,
            int nightStartHour, int nightEndHour) {
        long nowMs = System.currentTimeMillis();
        java.util.Calendar start = java.util.Calendar.getInstance(zone);
        start.setTimeInMillis(nowMs);
        start.set(java.util.Calendar.MINUTE, 0);
        start.set(java.util.Calendar.SECOND, 0);
        start.set(java.util.Calendar.MILLISECOND, 0);
        start.set(java.util.Calendar.HOUR_OF_DAY, nightStartHour);

        java.util.Calendar end = (java.util.Calendar) start.clone();
        end.set(java.util.Calendar.HOUR_OF_DAY, nightEndHour);
        if (end.getTimeInMillis() <= start.getTimeInMillis()) {
            end.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }

        if (nowMs < start.getTimeInMillis()) {
            java.util.Calendar prevStart = (java.util.Calendar) start.clone();
            prevStart.add(java.util.Calendar.DAY_OF_YEAR, -1);
            java.util.Calendar prevEnd = (java.util.Calendar) end.clone();
            prevEnd.add(java.util.Calendar.DAY_OF_YEAR, -1);
            if (nowMs >= prevStart.getTimeInMillis() && nowMs < prevEnd.getTimeInMillis()) {
                return prevStart.getTimeInMillis();
            }
            return start.getTimeInMillis();
        }
        if (nowMs < end.getTimeInMillis()) {
            return start.getTimeInMillis();
        }
        start.add(java.util.Calendar.DAY_OF_YEAR, 1);
        return start.getTimeInMillis();
    }

    private static long nightEndMs(java.util.TimeZone zone, long nightStartMs, int nightEndHour) {
        java.util.Calendar cal = java.util.Calendar.getInstance(zone);
        cal.setTimeInMillis(nightStartMs);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        cal.set(java.util.Calendar.HOUR_OF_DAY, nightEndHour);
        if (cal.getTimeInMillis() <= nightStartMs) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    /**
     * Fetches precipitation forecast and returns the max value that overlaps the
     * window [now, now + lookAheadMin]. The bucket covering "now" is included so
     * imminent rain that has just started is not missed.
     */
    static Forecast fetch(Context context, double latitude, double longitude, int lookAheadMin) {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return Forecast.fail("no_coords");
        }
        int lookAhead = Math.max(1, lookAheadMin);
        String query = "?latitude=" + String.format(Locale.US, "%.5f", latitude)
                + "&longitude=" + String.format(Locale.US, "%.5f", longitude)
                + "&minutely_15=precipitation,precipitation_probability"
                + "&hourly=precipitation,precipitation_probability"
                + "&forecast_days=1&timeformat=unixtime&timezone=UTC";
        try {
            HostDns.HttpResult http = HostDns.httpsGet(context, HOST, PATH + query);
            if (http.code >= 400) {
                return Forecast.fail("HTTP " + http.code);
            }
            JSONObject root = new JSONObject(http.body);
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
}
