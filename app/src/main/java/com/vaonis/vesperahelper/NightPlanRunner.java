package com.vaonis.vesperahelper;

import android.content.Context;
import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Esegue sul Pi il piano notturno inviato da Vespera Control
 * ({@code cmd|plan|load|{json}}): all'inizio di ogni passo punta l'oggetto,
 * continuando la sessione multi-notte se esiste, altrimenti ne crea una.
 * Nelle pause (nuvole) non manda nulla: il firmware si ferma da solo quando
 * non riconosce più il campo. Dentro un passo, se il Vespera si è fermato
 * (es. nuvole passate) riprova l'oggetto ogni 10 minuti, al massimo 3 volte.
 * Il piano è letto ogni minuto: se nell'orario di un passo il Vespera riprende
 * un altro oggetto (passo precedente che sfora o target cambiato a mano) lo
 * ferma e avvia quello giusto, al massimo 5 volte per passo.
 * A fine piano parcheggia (se {@code parkAtEnd}).
 * Il piano è salvato in {@code plan.json} e sopravvive al riavvio.
 */
final class NightPlanRunner {
    private static final String TAG = "VesperaPlan";
    private static final String FILE = "plan.json";
    private static final long TICK_MS = 60_000L;
    private static final long RETRY_FAIL_MS = 2 * 60_000L;
    private static final long RETRY_IDLE_MS = 10 * 60_000L;
    private static final int MAX_FAIL = 5;
    private static final int MAX_IDLE_RETRY = 3;
    private static final long MIN_LEFT_MS = 15 * 60_000L;

    private static NightPlanRunner instance;

    private final Context app;
    private final Handler handler;
    private JSONObject plan;
    private String message = "";

    private NightPlanRunner(Context context) {
        app = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("vespera-night-plan");
        thread.start();
        handler = new Handler(thread.getLooper());
        plan = readPlan();
        handler.postDelayed(tick, 5_000L);
    }

    static synchronized NightPlanRunner get(Context context) {
        if (instance == null) instance = new NightPlanRunner(context);
        return instance;
    }

    /** Carica un nuovo piano; sostituisce quello attivo. */
    synchronized String load(String json) throws Exception {
        JSONObject in = new JSONObject(json);
        JSONArray steps = in.optJSONArray("steps");
        if (steps == null || steps.length() == 0) return "ERR|plan|empty";
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.getJSONObject(i);
            if (s.optLong("start") <= 0 || s.optLong("end") <= s.optLong("start")
                    || s.optString("name", "").isEmpty()) {
                return "ERR|plan|bad_step|" + i;
            }
            s.put("status", "");
            s.put("started", false);
            s.put("fails", 0);
            s.put("idleRetries", 0);
            s.put("switches", 0);
            s.put("lastTry", 0L);
        }
        in.put("loadedAt", System.currentTimeMillis());
        in.put("parked", false);
        in.put("done", false);
        plan = in;
        message = "Piano caricato: " + steps.length() + " passi";
        writePlan();
        handler.removeCallbacks(tick);
        handler.post(tick);
        Log.i(TAG, "plan loaded steps=" + steps.length());
        return "OK|plan|load|" + steps.length();
    }

    synchronized String cancel() {
        plan = null;
        message = "Piano annullato";
        //noinspection ResultOfMethodCallIgnored
        new File(dir(), FILE).delete();
        Log.i(TAG, "plan cancelled");
        return "OK|plan|cancel";
    }

    /** Stato per remote.state.json. */
    synchronized JSONObject stateJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("active", plan != null && !plan.optBoolean("done"));
            o.put("message", message);
            if (plan == null) return o;
            o.put("title", plan.optString("title", ""));
            o.put("night", plan.optString("night", ""));
            o.put("done", plan.optBoolean("done"));
            JSONArray out = new JSONArray();
            JSONArray steps = plan.optJSONArray("steps");
            int current = -1;
            long now = System.currentTimeMillis();
            for (int i = 0; steps != null && i < steps.length(); i++) {
                JSONObject s = steps.getJSONObject(i);
                if (s.optLong("start") <= now && now < s.optLong("end")) current = i;
                JSONObject c = new JSONObject();
                c.put("name", s.optString("name"));
                c.put("start", s.optLong("start"));
                c.put("end", s.optLong("end"));
                c.put("status", s.optString("status"));
                out.put(c);
            }
            o.put("current", current);
            o.put("steps", out);
            // righe dei periodi e tempo per oggetto mandati dal client (0.2.32+)
            JSONArray lines = plan.optJSONArray("lines");
            if (lines != null) o.put("lines", lines);
            JSONArray totals = plan.optJSONArray("totals");
            o.put("totals", totals != null ? totals : totalsFromSteps(steps));
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * Tempo per oggetto dai passi (piani caricati da client più vecchi). La visibilità si ricava
     * SOLO dall'altezza (ottima ≥ 50°, buona ≥ 30°, scarsa ≥ 15°, bassa sotto), calcolata ogni
     * 5 min con la posizione di Sistema; questi piani non hanno il meteo.
     */
    private JSONArray totalsFromSteps(JSONArray steps) {
        PhotoSyncStore store = PhotoSyncStore.from(app);
        boolean site = store.hasSite();
        double lat = store.siteLat();
        double lon = store.siteLon();
        java.util.LinkedHashMap<String, long[]> byName = new java.util.LinkedHashMap<>();
        long[] all = new long[5];
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s == null) continue;
            long[] row = byName.get(s.optString("name"));
            if (row == null) {
                row = new long[5];
                byName.put(s.optString("name"), row);
            }
            long start = s.optLong("start");
            long end = s.optLong("end");
            for (long t = start; t < end; t += 5 * 60_000L) {
                long step = Math.min(5 * 60_000L, end - t);
                int level = 4; // 4 = sconosciuta (manca la posizione)
                if (site && s.has("ra") && s.has("dec")) {
                    double alt = SkyCatalog.altAz(lat, lon, s.optDouble("ra"), s.optDouble("dec"), t + step / 2)[0];
                    level = alt >= 50 ? 3 : alt >= 30 ? 2 : alt >= 15 ? 1 : 0;
                }
                row[level] += step;
                all[level] += step;
            }
        }
        JSONArray out = new JSONArray();
        for (java.util.Map.Entry<String, long[]> e : byName.entrySet()) {
            out.put(e.getKey() + " · " + withLevels(e.getValue()));
        }
        if (byName.size() > 1) out.put("Totale piano " + withLevels(all));
        return out;
    }

    /** "4h10 · visibilità: ottima 2h00, buona 2h10 · meteo n.d." (ms per livello). */
    private static String withLevels(long[] ms) {
        String[] names = {"bassa <15°", "scarsa", "buona", "ottima"};
        long sum = 0;
        StringBuilder parts = new StringBuilder();
        for (int level = 3; level >= 0; level--) {
            sum += ms[level];
            if (ms[level] < 60_000L) continue;
            if (parts.length() > 0) parts.append(", ");
            parts.append(names[level]).append(' ').append(duration(Math.round(ms[level] / 60000.0)));
        }
        sum += ms[4];
        return duration(Math.round(sum / 60000.0))
                + (parts.length() > 0 ? " · visibilità: " + parts : "")
                + " · meteo n.d. (piano senza meteo)";
    }

    static String duration(long minutes) {
        long h = minutes / 60;
        long m = minutes % 60;
        if (h == 0) return m + " min";
        return m == 0 ? h + "h" : String.format(java.util.Locale.ITALY, "%dh%02d", h, m);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                step();
            } catch (Exception e) {
                Log.w(TAG, "tick", e);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void step() throws Exception {
        JSONObject current;
        JSONArray steps;
        synchronized (this) {
            if (plan == null || plan.optBoolean("done")) return;
            steps = plan.optJSONArray("steps");
            if (steps == null || steps.length() == 0) return;
            current = plan;
        }
        long now = System.currentTimeMillis();
        long lastEnd = steps.getJSONObject(steps.length() - 1).optLong("end");
        if (now >= lastEnd) {
            finish(current);
            return;
        }
        JSONObject active = null;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.getJSONObject(i);
            if (s.optLong("start") <= now && now < s.optLong("end")) {
                active = s;
                break;
            }
        }
        if (active == null) {
            setMessage("Pausa: in attesa del prossimo oggetto");
            return;
        }
        if (active.optLong("end") - now < MIN_LEFT_MS && !active.optBoolean("started")) {
            return;
        }
        Network network = VesperaConnectionService.getActiveNetwork();
        int port = InstrumentWatchdog.lastApiPort();
        if (port <= 0) port = 8082;
        if (network == null) {
            setMessage("Vespera non raggiungibile");
            return;
        }
        VesperaStatusClient.Result status = VesperaStatusClient.fetchResult("10.0.0.1", port, network);
        VesperaStatusSnapshot snap = status.snapshot;
        if (snap == null) {
            setMessage("Stato Vespera non disponibile");
            return;
        }
        if (snap.isShuttingDown()) {
            setMessage("Vespera in spegnimento: piano fermo");
            return;
        }
        String name = active.optString("name");
        boolean started = active.optBoolean("started");
        long lastTry = active.optLong("lastTry");
        if (snap.isAutoInitRunning()) return;
        boolean busy = snap.isTrackingAcquisition();
        boolean known = !snap.targetName.isEmpty();
        // nome ignoto mentre lavora: è nostro solo se il passo è già partito
        boolean onTarget = busy && (known ? sameTarget(snap.targetName, name) : started);
        if (onTarget) {
            if (!started) {
                // già sull'oggetto giusto (avviato a mano o prima del riavvio)
                synchronized (this) {
                    active.put("started", true);
                    active.put("fails", 0);
                    active.put("status", "in corso");
                    message = "In corso: " + name;
                    writePlan();
                }
            }
            return;
        }
        // nell'orario del passo il Vespera è su un altro oggetto (passo precedente
        // che sfora o target cambiato a mano): va fermato, altrimenti il firmware
        // rifiuta il nuovo con APP.REQUIRE_RESOURCES_FAILED (AZ/ALT/DER/CAMERA).
        // Vale finché il piano è attivo; annullato o terminato non si tocca nulla.
        boolean wrongTarget = busy;
        if (wrongTarget) {
            int switches = active.optInt("switches");
            if (switches >= MAX_FAIL || now - lastTry < RETRY_FAIL_MS) return;
            active.put("switches", switches + 1);
            active.put("fails", 0);
        } else if (started) {
            int retries = active.optInt("idleRetries");
            if (retries >= MAX_IDLE_RETRY || now - lastTry < RETRY_IDLE_MS
                    || active.optLong("end") - now < MIN_LEFT_MS) {
                return;
            }
            active.put("idleRetries", retries + 1);
        } else {
            int fails = active.optInt("fails");
            if (fails >= MAX_FAIL || (fails > 0 && now - lastTry < RETRY_FAIL_MS)) return;
        }
        active.put("lastTry", now);
        if (wrongTarget) {
            String other = known ? snap.targetName : "l'osservazione in corso";
            setMessage("Fermo " + other + " per passare a " + name);
            VesperaCommandClient.Result stop = VesperaCommandClient.send(
                    "10.0.0.1", port, network, VesperaCommandClient.Command.STOP);
            Log.i(TAG, "stop " + other + " before " + name + " ok=" + stop.success + " " + stop.message);
            if (!waitUntilStopped(port, network)) {
                synchronized (this) {
                    active.put("status", "errore: " + other + " non si ferma");
                    message = "Errore su " + name + ": " + other + " non si ferma, riprovo";
                    writePlan();
                }
                return;
            }
        }
        SkyCatalog.Session session = SkyCatalog.findSession(snap.rawJson, name, name);
        String body;
        if (session != null) {
            body = SkyCatalog.resumeBody(session);
        } else {
            SkyCatalog.Target target = new SkyCatalog.Target(name, name,
                    active.optString("type", ""), active.optDouble("ra"), active.optDouble("dec"), null);
            body = SkyCatalog.startBody(target, snap);
        }
        VesperaLocationClient.Site site = VesperaLocationClient.fetch(network);
        VesperaCommandClient.Result result = VesperaCommandClient.observe(
                "10.0.0.1", port, network, site, body, session != null);
        synchronized (this) {
            if (result.success) {
                active.put("started", true);
                active.put("fails", 0);
                active.put("status", session != null ? "continuato" : "avviato");
                message = "In corso: " + name;
            } else {
                String why = readableError(result.message);
                active.put("fails", active.optInt("fails") + 1);
                active.put("status", "errore: " + why);
                message = "Errore su " + name + ": " + why;
            }
            writePlan();
        }
        Log.i(TAG, "step " + name + " resume=" + (session != null) + " ok=" + result.success
                + " msg=" + result.message);
    }

    private void finish(JSONObject current) throws Exception {
        if (!current.optBoolean("parked") && current.optBoolean("parkAtEnd", true)) {
            Network network = VesperaConnectionService.getActiveNetwork();
            int port = InstrumentWatchdog.lastApiPort();
            if (port <= 0) port = 8082;
            if (network != null) {
                VesperaCommandClient.Result r = VesperaCommandClient.send(
                        "10.0.0.1", port, network, VesperaCommandClient.Command.PARK);
                Log.i(TAG, "plan end park ok=" + r.success + " " + r.message);
            }
        }
        synchronized (this) {
            current.put("parked", true);
            current.put("done", true);
            message = "Piano terminato";
            writePlan();
        }
    }

    /** Attende (max 2 min) che il Vespera abbia davvero chiuso l'osservazione. */
    private static boolean waitUntilStopped(int port, Network network) {
        long deadline = System.currentTimeMillis() + 2 * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            VesperaStatusSnapshot s = VesperaStatusClient.fetchResult("10.0.0.1", port, network).snapshot;
            if (s != null && !s.isObserving()) return true;
        }
        Log.w(TAG, "stop: still busy after 2 min");
        return false;
    }

    /** Frase leggibile al posto del JSON d'errore del firmware. */
    static String readableError(String text) {
        if (text == null) return "";
        int start = text.indexOf("{\"");
        if (start < 0) return text;
        String raw = text.substring(start);
        if (raw.contains("RESOURCE_IS_NOT_AVAILABLE") || raw.contains("REQUIRE_RESOURCES_FAILED")) {
            java.util.LinkedHashSet<String> res = new java.util.LinkedHashSet<>();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"resourceName\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
            while (m.find()) res.add(m.group(1));
            return text.substring(0, start) + "Vespera occupato da un'altra osservazione"
                    + (res.isEmpty() ? "" : " (in uso: " + android.text.TextUtils.join(", ", res) + ")");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
        return text.substring(0, start) + (m.find() ? "firmware " + m.group(1) : "firmware: comando rifiutato");
    }

    private synchronized void setMessage(String text) {
        message = text;
    }

    private static boolean sameTarget(String running, String wanted) {
        String a = compact(running);
        String b = compact(wanted);
        return !a.isEmpty() && (a.equals(b) || a.contains(b) || b.contains(a));
    }

    private static String compact(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == ' ' || c == '_' || c == '-') continue;
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }

    private File dir() {
        File d = app.getExternalFilesDir(null);
        return d == null ? app.getFilesDir() : d;
    }

    private JSONObject readPlan() {
        File f = new File(dir(), FILE);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return new JSONObject(new String(data, 0, off, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private void writePlan() {
        if (plan == null) return;
        File f = new File(dir(), FILE);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(plan.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "writePlan", e);
        }
    }
}
