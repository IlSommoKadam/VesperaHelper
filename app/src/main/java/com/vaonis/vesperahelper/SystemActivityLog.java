package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Ring buffer of the last automatic activities, newest first. */
final class SystemActivityLog {
    static final String ACTION = "com.vaonis.vesperahelper.SYSTEM_ACTIVITY_LOG";
    static final int MAX = 20;

    static final String KIND_PHOTO_SYNC = "photo_sync";
    static final String KIND_STORAGE_SYNC = "storage_sync";
    static final String KIND_RESUME_SYNC = "resume_sync";
    static final String KIND_SUN_TOO_HIGH = "sun_too_high";
    static final String KIND_PI_SHUTDOWN = "pi_shutdown";
    static final String KIND_HD_MOUNT = "hd_mount";
    static final String KIND_HD_POWER_OFF = "hd_power_off";
    static final String KIND_CLOCK_NTP = "clock_ntp";
    static final String KIND_BOOT = "boot_start";
    static final String KIND_WIFI = "wifi_connect";
    static final String KIND_SINGULARITY = "singularity_start";
    static final String KIND_FTP = "ftp_local";
    static final String KIND_KEEP_ALIVE = "keep_alive";
    static final String KIND_WEATHER_CHECK = "weather_check";
    static final String KIND_WEATHER_PROTECT = "weather_protect";

    static final String DETAIL_OK = "ok";
    static final String DETAIL_FAIL = "fail";
    static final String DETAIL_PAUSED = "paused";
    static final String DETAIL_NOT_STATUS = "not_status";
    static final String DETAIL_SHUTDOWN_OK = "shutdown_ok";
    static final String DETAIL_SHUTDOWN_FAIL = "shutdown_fail";
    static final String DETAIL_NO_NTP = "no_ntp";
    static final String DETAIL_WINDOW_CLOSED = "window_closed";

    static final class Entry {
        final long at;
        final String kind;
        final String detail;

        Entry(long at, String kind, String detail) {
            this.at = at;
            this.kind = kind == null ? "" : kind;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static final String PREFS = "vespera_system_log";
    private static final String KEY = "entries";
    private static final Object LOCK = new Object();
    /** Ignore entries left in the future after NTP / timezone clock jumps. */
    private static final long FUTURE_SKEW_MS = 60_000L;
    private static final Comparator<String> NEWEST_FIRST = (a, b) -> {
        Entry ea = parse(a);
        Entry eb = parse(b);
        long ta = ea == null ? 0L : ea.at;
        long tb = eb == null ? 0L : eb.at;
        return Long.compare(tb, ta);
    };

    private SystemActivityLog() {}

    static void record(Context context, String kind, String detail) {
        if (context == null || kind == null || kind.isEmpty()) return;
        long at = System.currentTimeMillis();
        String line = at + "|" + sanitize(kind) + "|" + sanitize(detail);
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            List<String> lines = prune(split(prefs.getString(KEY, "")), at);
            lines.add(0, line);
            persist(prefs, lines);
        }
        notifyChanged(context);
    }

    /** Newest kept entry, or null. */
    static Entry newest(Context context) {
        if (context == null) return null;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            List<String> lines = prune(split(prefs.getString(KEY, "")), System.currentTimeMillis());
            Collections.sort(lines, NEWEST_FIRST);
            return lines.isEmpty() ? null : parse(lines.get(0));
        }
    }

    static boolean lastIs(Context context, String kind, String detail) {
        Entry newest = newest(context);
        if (newest == null) return false;
        String wantKind = kind == null ? "" : kind;
        String wantDetail = detail == null ? "" : detail;
        return wantKind.equals(newest.kind) && wantDetail.equals(newest.detail);
    }

    /** Newest entry of this kind, regardless of later unrelated activities. */
    static boolean lastKindIs(Context context, String kind, String detail) {
        String wantKind = kind == null ? "" : kind;
        String wantDetail = detail == null ? "" : detail;
        if (wantKind.isEmpty()) return false;
        for (Entry entry : latest(context)) {
            if (wantKind.equals(entry.kind)) return wantDetail.equals(entry.detail);
        }
        return false;
    }

    /** Drop timestamps that became "future" after the system clock moved backwards. */
    static void dropFuture(Context context) {
        if (context == null) return;
        boolean changed;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            List<String> lines = split(prefs.getString(KEY, ""));
            List<String> kept = prune(lines, System.currentTimeMillis());
            changed = kept.size() != lines.size();
            if (changed) persist(prefs, kept);
        }
        if (changed) notifyChanged(context);
    }

    static List<Entry> latest(Context context) {
        List<Entry> out = new ArrayList<>();
        if (context == null) return out;
        String blob;
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            List<String> lines = split(prefs.getString(KEY, ""));
            List<String> kept = prune(lines, System.currentTimeMillis());
            if (kept.size() != lines.size()) persist(prefs, kept);
            blob = join(kept);
        }
        for (String line : split(blob)) {
            Entry entry = parse(line);
            if (entry != null) out.add(entry);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.at, a.at));
        if (out.size() > MAX) return new ArrayList<>(out.subList(0, MAX));
        return out;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void persist(SharedPreferences prefs, List<String> lines) {
        Collections.sort(lines, NEWEST_FIRST);
        while (lines.size() > MAX) lines.remove(lines.size() - 1);
        prefs.edit().putString(KEY, join(lines)).commit();
    }

    private static List<String> prune(List<String> lines, long now) {
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            Entry entry = parse(line);
            if (entry != null && entry.at <= now + FUTURE_SKEW_MS) kept.add(line);
        }
        return kept;
    }

    private static String join(List<String> lines) {
        StringBuilder blob = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) blob.append('\n');
            blob.append(lines.get(i));
        }
        return blob.toString();
    }

    private static void notifyChanged(Context context) {
        try {
            Context app = context.getApplicationContext();
            app.sendBroadcast(new Intent(ACTION).setPackage(app.getPackageName()));
        } catch (Exception ignored) {
        }
    }

    private static Entry parse(String line) {
        if (line == null || line.isEmpty()) return null;
        int first = line.indexOf('|');
        if (first <= 0) return null;
        int second = line.indexOf('|', first + 1);
        try {
            long at = Long.parseLong(line.substring(0, first));
            String kind;
            String detail;
            if (second < 0) {
                kind = line.substring(first + 1);
                detail = "";
            } else {
                kind = line.substring(first + 1, second);
                detail = line.substring(second + 1);
            }
            if (kind.isEmpty()) return null;
            return new Entry(at, kind, detail);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<String> split(String blob) {
        List<String> lines = new ArrayList<>();
        if (blob == null || blob.isEmpty()) return lines;
        int start = 0;
        while (start <= blob.length()) {
            int nl = blob.indexOf('\n', start);
            String line = nl < 0 ? blob.substring(start) : blob.substring(start, nl);
            if (!line.isEmpty()) lines.add(line);
            if (nl < 0) break;
            start = nl + 1;
        }
        return lines;
    }

    private static String sanitize(String text) {
        if (text == null) return "";
        return text.replace('\n', ' ').replace('|', '/').trim();
    }
}
