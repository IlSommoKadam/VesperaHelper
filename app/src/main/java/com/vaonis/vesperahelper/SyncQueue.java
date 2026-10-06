package com.vaonis.vesperahelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Coda file dell'ultima sync foto {@code /USER} → HD: elenco dei file trovati sul Vespera
 * con lo stato di ognuno. Esposta ai client Vespera Control in {@code remote.state.json → sync}.
 */
final class SyncQueue {
    static final String PENDING = "pending";
    static final String ACTIVE = "active";
    static final String COPIED = "copied";
    static final String SKIPPED = "skipped";
    static final String FAILED = "failed";

    /** Righe massime inviate ai client (il file di stato viene riscritto ogni 2,5 s). */
    private static final int MAX_ITEMS = 400;

    private static final class Item {
        final String folder;
        final String name;
        final long size;
        String status = PENDING;

        Item(String folder, String name, long size) {
            this.folder = folder;
            this.name = name;
            this.size = size;
        }
    }

    private static List<Item> items = Collections.emptyList();
    private static long startedAt;
    private static long updatedAt;

    private SyncQueue() {}

    static synchronized void begin(List<CommonsFtpClient.Entry> photos) {
        List<Item> next = new ArrayList<>(photos.size());
        for (CommonsFtpClient.Entry e : photos) {
            next.add(new Item(folderOf(e.path), e.name, Math.max(0, e.size)));
        }
        items = next;
        startedAt = System.currentTimeMillis();
        updatedAt = startedAt;
    }

    static synchronized void mark(int index, String status) {
        if (index < 0 || index >= items.size()) return;
        items.get(index).status = status;
        updatedAt = System.currentTimeMillis();
    }

    /** Pausa/errore: il file in corso torna in attesa. */
    static synchronized void releaseActive() {
        for (Item item : items) {
            if (ACTIVE.equals(item.status)) item.status = PENDING;
        }
        updatedAt = System.currentTimeMillis();
    }

    static synchronized void putJson(JSONObject o) throws Exception {
        int pending = 0, copied = 0, skipped = 0, failed = 0, active = -1;
        long pendingBytes = 0;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            switch (item.status) {
                case ACTIVE:
                    active = i;
                    pending++;
                    pendingBytes += item.size;
                    break;
                case PENDING:
                    pending++;
                    pendingBytes += item.size;
                    break;
                case COPIED: copied++; break;
                case SKIPPED: skipped++; break;
                default: failed++; break;
            }
        }
        o.put("queueStartedAt", startedAt);
        o.put("queueUpdatedAt", updatedAt);
        o.put("queueTotal", items.size());
        o.put("queuePending", pending);
        o.put("queuePendingBytes", pendingBytes);
        o.put("queueCopied", copied);
        o.put("queueSkipped", skipped);
        o.put("queueFailed", failed);
        o.put("queueActive", active);
        // Finestra attorno al file in corso: qualche riga già fatta, poi quelle in attesa.
        int from = active >= 0 ? Math.max(0, active - 20) : 0;
        if (active < 0 && items.size() > MAX_ITEMS) from = items.size() - MAX_ITEMS;
        int to = Math.min(items.size(), from + MAX_ITEMS);
        o.put("queueFrom", from);
        JSONArray arr = new JSONArray();
        for (int i = from; i < to; i++) {
            Item item = items.get(i);
            JSONObject row = new JSONObject();
            row.put("folder", item.folder);
            row.put("name", item.name);
            row.put("size", item.size);
            row.put("status", item.status);
            arr.put(row);
        }
        o.put("queue", arr);
    }

    private static String folderOf(String path) {
        if (path == null) return "";
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = p.lastIndexOf('/');
        if (slash <= 0) return "";
        String parent = p.substring(0, slash);
        int prev = parent.lastIndexOf('/');
        return prev >= 0 ? parent.substring(prev + 1) : parent;
    }
}
