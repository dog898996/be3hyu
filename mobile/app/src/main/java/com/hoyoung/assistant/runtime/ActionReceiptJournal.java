package com.hoyoung.assistant.runtime;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Durable action intent and a pending-only index; never loads the archived receipt history into an array. */
public final class ActionReceiptJournal {
    private static final String RECEIPTS = "actionReceipt:", RECEIPTS_END = "actionReceipt;";
    private static final String PENDING = "pendingReceipt:", PENDING_END = "pendingReceipt;";
    private static final String VERSION = "receiptIndexVersion", COUNT = "indexedReceiptCount", CURSOR = "receiptIndexCursor";
    private static final int PAGE_SIZE = 128;
    private final HoyoungStore store;
    private final Object migrationLock = new Object();
    public ActionReceiptJournal(HoyoungStore store) { this.store = store; }

    /** Save dispatching before the external effect. Source and pending index succeed or roll back together. */
    public void put(String key, JSONObject receipt) {
        validateKey(key); JSONObject copy = strict(receipt == null ? null : receipt.toString());
        store.withTransaction(db -> {
            boolean existed = value(db, key) != null;
            store.putSetting(key, copy.toString()); index(db, key, copy.optString("status"));
            if (!existed && "1".equals(store.getSetting(VERSION, "0")))
                store.putSetting(COUNT, String.valueOf(number(store.getSetting(COUNT, "0")) + 1));
            return null;
        });
    }

    /** Null means no receipt. Empty, malformed, or unknown-status records are errors, never new actions. */
    public JSONObject get(String key) {
        validateKey(key);
        synchronized (store) {
            String raw = value(store.getReadableDatabase(), key);
            if (raw == null) return null;
            try { return strict(raw); }
            catch (IllegalArgumentException corrupt) { throw new IllegalStateException("Stored action receipt is unreadable; automatic replay is forbidden", corrupt); }
        }
    }

    public JSONObject ensureIndex() { return ensureIndex(false); }

    /** Run in a background executor. A completed migration is reused unless receipt count or restore invalidates it. */
    public JSONObject ensureIndex(boolean forceRebuild) {
        synchronized (migrationLock) {
            boolean ready = store.withTransaction(db -> "1".equals(store.getSetting(VERSION, "0"))
                    && number(store.getSetting(COUNT, "0")) == receiptCount(db));
            if (ready && !forceRebuild) return object("ready", true, "migrated", false, "scanned", 0,
                    "indexedReceiptCount", number(store.getSetting(COUNT, "0")));
            if (forceRebuild || "1".equals(store.getSetting(VERSION, "0"))) {
                store.withTransaction(db -> {
                    db.delete("settings", "setting_key >= ? AND setting_key < ?", new String[]{PENDING, PENDING_END});
                    store.putSetting(VERSION, "0"); store.putSetting(COUNT, "0"); store.putSetting(CURSOR, "");
                    return null;
                });
            }
            String after = store.getSetting(CURSOR, ""); long scanned = 0, corrupt = 0;
            while (true) {
                List<String> page = keys(RECEIPTS, RECEIPTS_END, after, PAGE_SIZE);
                if (page.isEmpty()) break;
                for (String key : page) {
                    final String sourceKey = key;
                    boolean malformed = store.withTransaction(db -> {
                        String raw = value(db, sourceKey); boolean invalid = false;
                        if (raw != null) {
                            String status;
                            try { status = strict(raw).optString("status"); }
                            catch (IllegalArgumentException unreadable) { status = "corrupt"; invalid = true; }
                            index(db, sourceKey, status);
                        }
                        store.putSetting(CURSOR, sourceKey); store.putSetting(VERSION, "0");
                        return invalid;
                    });
                    scanned++; if (malformed) corrupt++; after = key;
                }
            }
            long count = store.withTransaction(db -> {
                long total = receiptCount(db); store.putSetting(COUNT, String.valueOf(total)); store.putSetting(VERSION, "1");
                store.recordEvent("action_receipt_index_ready", object("indexedReceiptCount", total, "pendingOnly", true,
                        "loadsEntireHistory", false, "forceRebuild", forceRebuild)); return total;
            });
            return object("ready", true, "migrated", true, "scanned", scanned, "corruptReceipts", corrupt,
                    "indexedReceiptCount", count);
        }
    }

    /** Fail closed while migration is unfinished. Ready lookups inspect one indexed pending key only. */
    public boolean hasUnresolved() {
        synchronized (store) {
            if (!"1".equals(store.getSetting(VERSION, "0"))) return true;
            try (Cursor pending = store.getReadableDatabase().rawQuery("SELECT 1 FROM settings WHERE setting_key >= ? AND setting_key < ? LIMIT 1", new String[]{PENDING, PENDING_END})) {
                return pending.moveToFirst();
            }
        }
    }

    /** Explicit owner reconciliation for one root. A closure never replays an action or verifies its external outcome. */
    public JSONObject resolveFor(String rootRequestId, boolean abandoned) {
        if (rootRequestId == null || !rootRequestId.matches("[A-Za-z0-9_.-]{1,450}")) throw new IllegalArgumentException("Invalid root request id");
        if (!"1".equals(store.getSetting(VERSION, "0"))) throw new IllegalStateException("Action receipt index is still being prepared");
        String prefix = RECEIPTS + rootRequestId + ":", end = RECEIPTS + rootRequestId + ";", after = "";
        int resolved = 0, corrupt = 0;
        while (true) {
            List<String> page = keys(prefix, end, after, PAGE_SIZE);
            if (page.isEmpty()) break;
            for (String key : page) {
                final String sourceKey = key;
                int changed = store.withTransaction(db -> {
                    // Archived final receipts may be huge. Their payloads are not loaded during owner reconciliation.
                    if (value(db, pendingKey(sourceKey)) == null) return 0;
                    String raw = value(db, sourceKey); if (raw == null) throw new IllegalStateException("Pending receipt source is missing");
                    JSONObject oldReceipt; boolean invalid = false;
                    try { oldReceipt = strict(raw); }
                    catch (IllegalArgumentException unreadable) { invalid = true; oldReceipt = new JSONObject(); }
                    if (!invalid && finalStatus(oldReceipt.optString("status"))) { index(db, sourceKey, oldReceipt.optString("status")); return 0; }
                    JSONObject preserved = store.addMessage("system", object("receiptKey", sourceKey, "rawReceipt", raw).toString(), null);
                    JSONObject receipt = object("previousReceiptSourceMessageId", preserved.optLong("id"), "previousReceiptHash", digest(raw));
                    if (invalid) put(receipt, "previousReceiptUnreadable", true);
                    // Keep the request identity in the hot record; the full previous outcome stays in its immutable original.
                    String fingerprint = oldReceipt.optString("fingerprint");
                    if (!fingerprint.isEmpty()) put(receipt, "fingerprint", fingerprint.length() <= 512 ? fingerprint : "invalid_legacy_fingerprint_archived");
                    String name = oldReceipt.optString("name"); if (!name.isEmpty()) put(receipt, "name", name.substring(0, Math.min(256, name.length())));
                    for (String time : new String[]{"startedAt", "completedAt"}) if (oldReceipt.opt(time) instanceof Number) put(receipt, time, oldReceipt.opt(time));
                    put(receipt, "status", abandoned ? "abandoned" : "completed"); put(receipt, "ownerResolved", true);
                    put(receipt, "providerOutcomeVerified", false); put(receipt, "replayed", false);
                    if (abandoned) put(receipt, "ownerAbandoned", true);
                    else put(receipt, "result", object("ok", false, "code", "owner_resolved_outcome", "replayed", false, "providerOutcomeVerified", false));
                    put(receipt, "resolvedAt", System.currentTimeMillis());
                    put(sourceKey, receipt); return invalid ? 2 : 1;
                });
                if (changed > 0) resolved++; if (changed == 2) corrupt++; after = key;
            }
        }
        final int totalResolved = resolved, totalCorrupt = corrupt;
        store.recordEvent("action_receipts_owner_resolved", object("rootRequestId", rootRequestId, "resolvedCount", totalResolved,
                "corruptReceiptCount", totalCorrupt, "abandoned", abandoned, "automaticReplay", false, "providerOutcomeVerified", false));
        return object("ok", true, "resolvedCount", resolved, "corruptReceiptCount", corrupt,
                "abandoned", abandoned, "replayed", false, "providerOutcomeVerified", false);
    }

    private List<String> keys(String start, String end, String after, int limit) {
        List<String> result = new ArrayList<>(limit);
        synchronized (store) {
            try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT setting_key FROM settings WHERE setting_key >= ? AND setting_key < ? AND setting_key > ? ORDER BY setting_key ASC LIMIT ?",
                    new String[]{start, end, after, String.valueOf(limit)})) {
                while (cursor.moveToNext()) result.add(cursor.getString(0));
            }
        }
        return result;
    }
    private void index(SQLiteDatabase db, String key, String status) {
        String pendingKey = pendingKey(key);
        if (finalStatus(status)) db.delete("settings", "setting_key = ?", new String[]{pendingKey});
        else store.putSetting(pendingKey, object("receiptKey", key, "status", status).toString());
    }
    private static String pendingKey(String key) {
        String ordinary = PENDING + key;
        return ordinary.length() <= 512 ? ordinary : PENDING + "hash:" + digest(key);
    }
    private static boolean finalStatus(String status) { return "completed".equals(status) || "abandoned".equals(status); }
    private static JSONObject strict(String raw) {
        if (raw == null || raw.length() > 500_000) throw new IllegalArgumentException("Invalid receipt");
        checkDepth(raw);
        try {
            JSONTokener tokener = new JSONTokener(raw); Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException("Receipt must be one whole object");
            JSONObject receipt = (JSONObject) value; Object status = receipt.opt("status");
            if (!(status instanceof String) || !("dispatching".equals(status) || "uncertain".equals(status)
                    || "completed".equals(status) || "abandoned".equals(status))) throw new IllegalArgumentException("Unknown receipt status");
            return receipt;
        } catch (org.json.JSONException malformed) { throw new IllegalArgumentException("Malformed receipt", malformed); }
    }
    private static void checkDepth(String raw) {
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char value = raw.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
            } else if (value == '"') quoted = true;
            else if (value == '{' || value == '[') { if (++depth > 64) throw new IllegalArgumentException("Receipt nesting is too deep"); }
            else if (value == '}' || value == ']') { if (--depth < 0) throw new IllegalArgumentException("Receipt delimiters are malformed"); }
        }
    }
    private static String value(SQLiteDatabase db, String key) {
        try (Cursor row = db.rawQuery("SELECT value FROM settings WHERE setting_key = ?", new String[]{key})) { return row.moveToFirst() ? row.getString(0) : null; }
    }
    private static long receiptCount(SQLiteDatabase db) {
        try (Cursor count = db.rawQuery("SELECT COUNT(*) FROM settings WHERE setting_key >= ? AND setting_key < ?", new String[]{RECEIPTS, RECEIPTS_END})) { return count.moveToFirst() ? count.getLong(0) : 0; }
    }
    private static void validateKey(String key) { if (key == null || !key.startsWith(RECEIPTS) || key.length() <= RECEIPTS.length() || key.length() > 512) throw new IllegalArgumentException("Invalid receipt key"); }
    private static long number(String value) { try { return Math.max(0, Long.parseLong(value)); } catch (NumberFormatException malformed) { return 0; } }
    private static String digest(String text) {
        try { StringBuilder result = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) result.append(String.format(Locale.ROOT,"%02x",b&255)); return result.toString(); }
        catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static JSONObject object(Object... pairs) { JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)put(result,String.valueOf(pairs[i]),pairs[i+1]);return result; }
    private static void put(JSONObject target,String key,Object value) { try { target.put(key,value); } catch(Exception invalid) {throw new IllegalStateException(invalid);} }
}
