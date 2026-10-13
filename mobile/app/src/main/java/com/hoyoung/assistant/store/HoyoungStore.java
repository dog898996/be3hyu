package com.hoyoung.assistant.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Private, durable originals and source-backed memory. Nothing in this class uses cacheDir. */
public final class HoyoungStore extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "hoyoung.db";
    public static final int SNAPSHOT_VERSION = 1;
    static final String FILE_RESTORE_TOKEN_KEY = "internal.files.restoreToken";
    private static final int MAX_TEXT_CHARS = 500_000;
    private static final int MAX_CONTEXT_CHARS = 100_000;
    private static final String[] STATUSES = {
            "queued", "dispatching", "accepted", "completed", "uncertain", "blocked", "quota", "abandoned"
    };

    public HoyoungStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, 1);
        // A process death cannot tell us whether the remote side accepted an in-flight request.
        // Preserve it for explicit owner reconciliation instead of sending it a second time.
        recoverInterruptedRequests();
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, "
                + "text TEXT NOT NULL, request_id TEXT, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE requests (id INTEGER PRIMARY KEY AUTOINCREMENT, external_id TEXT NOT NULL UNIQUE, "
                + "text TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, result TEXT, "
                + "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX requests_status_created ON requests(status, created_at, id)");
        db.execSQL("CREATE TABLE memories (memory_key TEXT PRIMARY KEY, content TEXT NOT NULL, kind TEXT NOT NULL, "
                + "source_ids TEXT NOT NULL, revision INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE memory_revisions (id INTEGER PRIMARY KEY AUTOINCREMENT, memory_key TEXT NOT NULL, "
                + "content TEXT NOT NULL, kind TEXT NOT NULL, source_ids TEXT NOT NULL, revision INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, UNIQUE(memory_key, revision))");
        db.execSQL("CREATE TABLE settings (setting_key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, "
                + "payload TEXT NOT NULL, created_at INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("A non-destructive migration is required before upgrading the store");
    }

    public synchronized JSONObject addMessage(String role, String text, Object requestIdNullable) {
        ContentValues row = new ContentValues();
        row.put("role", required(role, "role", 64));
        row.put("text", required(text, "text", MAX_TEXT_CHARS));
        if (requestIdNullable != null) row.put("request_id", required(String.valueOf(requestIdNullable), "requestId", 512));
        row.put("created_at", System.currentTimeMillis());
        long id = getWritableDatabase().insertOrThrow("messages", null, row);
        return findMessage(id);
    }

    public synchronized JSONArray listMessages(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        JSONArray recent = queryRows(db, "SELECT * FROM messages ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(boundedLimit(limit, 1000))});
        JSONArray chronological = new JSONArray();
        for (int i = recent.length() - 1; i >= 0; i--) chronological.put(recent.opt(i));
        return chronological;
    }

    public synchronized JSONObject getMessage(long id) { return findMessage(id); }

    /** UI polling can compare this value without loading or parsing historical message bodies. */
    public synchronized long lastMessageId() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT MAX(id) FROM messages", null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0;
        }
    }

    public synchronized JSONObject getRequest(long id) {
        return one(getReadableDatabase(), "SELECT * FROM requests WHERE id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized boolean hasUncertainRequests() { return hasUncertainRequests(-1); }

    public synchronized boolean hasUncertainRequests(long excludeId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM requests WHERE status = 'uncertain' AND id != ? LIMIT 1",
                new String[]{String.valueOf(excludeId)})) { return cursor.moveToFirst(); }
    }

    public synchronized long getUncertainCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM requests WHERE status = 'uncertain'", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    /** An unknown/malformed receipt is unresolved; it must never silently permit replay. */
    public synchronized boolean hasUncertainActionReceipts() {
        try (Cursor cursor = receiptCursor()) {
            while (cursor.moveToNext()) if (unresolvedReceipt(cursor.getString(1))) return true;
            return false;
        }
    }

    /** Bounded presentation only. Use hasUncertainActionReceipts for an exhaustive gate. */
    public synchronized JSONArray listUncertainActionReceipts(int limit) {
        int bound = boundedLimit(limit, 1000);
        JSONArray result = new JSONArray();
        try (Cursor cursor = receiptCursor()) {
            while (cursor.moveToNext() && result.length() < bound) if (unresolvedReceipt(cursor.getString(1))) {
                JSONObject row = new JSONObject();
                put(row, "key", cursor.getString(0)); put(row, "value", cursor.getString(1));
                result.put(row);
            }
        }
        return result;
    }

    public synchronized JSONObject enqueueRequest(String text, String kind, String externalId) {
        text = required(text, "text", MAX_TEXT_CHARS);
        kind = required(kind, "kind", 128);
        externalId = externalId == null || externalId.trim().isEmpty()
                ? UUID.randomUUID().toString() : required(externalId, "externalId", 512);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject previous = one(db, "SELECT * FROM requests WHERE external_id = ?", new String[]{externalId});
            if (previous != null) {
                if (!text.equals(previous.optString("text")) || !kind.equals(previous.optString("kind"))) {
                    throw new IllegalArgumentException("externalId is already used for a different request");
                }
                db.setTransactionSuccessful();
                return previous;
            }
            long now = System.currentTimeMillis();
            ContentValues row = new ContentValues();
            row.put("external_id", externalId);
            row.put("text", text);
            row.put("kind", kind);
            row.put("status", "queued");
            row.put("created_at", now);
            row.put("updated_at", now);
            long id = db.insertOrThrow("requests", null, row);
            JSONObject result = one(db, "SELECT * FROM requests WHERE id = ?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }

    /** Read only. The caller must persist dispatching BEFORE starting any remote action. */
    public synchronized JSONObject nextQueuedRequest() {
        return one(getReadableDatabase(), "SELECT * FROM requests WHERE status = 'queued' ORDER BY id ASC LIMIT 1", null);
    }

    public synchronized void markRequest(long id, String status, String resultNullable) {
        requireStatus(status);
        if (resultNullable != null && resultNullable.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("result is too long");
        ContentValues values = new ContentValues();
        values.put("status", status);
        if (resultNullable == null) {
            // Owner abandonment describes a decision to stop tracking; it is not a new
            // provider outcome and must not erase the last known result/remote task id.
            if (!"abandoned".equals(status)) values.putNull("result");
        } else values.put("result", resultNullable);
        values.put("updated_at", System.currentTimeMillis());
        if (getWritableDatabase().update("requests", values, "id = ?", new String[]{String.valueOf(id)}) != 1) {
            throw new IllegalArgumentException("Unknown request id");
        }
    }

    /** Explicit retry after the owner has checked whether the earlier request already ran. */
    public synchronized JSONObject restoreRequest(long id) {
        SQLiteDatabase db = getWritableDatabase();
        JSONObject previous = one(db, "SELECT * FROM requests WHERE id = ?", new String[]{String.valueOf(id)});
        if (previous == null) throw new IllegalArgumentException("Unknown request id");
        String status = previous.optString("status");
        if (!(status.equals("uncertain") || status.equals("blocked") || status.equals("quota"))) {
            throw new IllegalStateException("Only uncertain, blocked, or quota requests can be explicitly restored");
        }
        markRequest(id, "queued", previous.optString("result", null));
        JSONObject payload = new JSONObject();
        put(payload, "requestId", id);
        put(payload, "previousStatus", status);
        recordEvent("request_explicitly_restored", payload);
        return one(db, "SELECT * FROM requests WHERE id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized JSONArray listRequests(int limit) {
        return queryRows(getReadableDatabase(), "SELECT * FROM requests ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(boundedLimit(limit, 1000))});
    }

    /** Store every revision; updating a summary does not remove originals or previous facts. */
    public synchronized JSONObject upsertMemory(String key, String content, String kind, JSONArray sourceMessageIds) {
        key = required(key, "key", 512);
        content = required(content, "content", MAX_TEXT_CHARS);
        kind = required(kind, "kind", 128);
        JSONArray sources = normalizeSources(sourceMessageIds);
        SQLiteDatabase db = getWritableDatabase();
        for (int i = 0; i < sources.length(); i++) {
            if (findMessage(sources.optLong(i, -1)) == null) throw new IllegalArgumentException("Memory references an unknown source message");
        }
        db.beginTransaction();
        try {
            JSONObject old = one(db, "SELECT * FROM memories WHERE memory_key = ?", new String[]{key});
            if (old != null && content.equals(old.optString("content")) && kind.equals(old.optString("kind"))
                    && sources.toString().equals(old.optJSONArray("sourceMessageIds").toString())) {
                db.setTransactionSuccessful();
                return old;
            }
            long now = System.currentTimeMillis();
            int revision = old == null ? 1 : old.optInt("revision") + 1;
            ContentValues row = new ContentValues();
            row.put("memory_key", key); row.put("content", content); row.put("kind", kind);
            row.put("source_ids", sources.toString()); row.put("revision", revision);
            row.put("created_at", old == null ? now : old.optLong("created_at")); row.put("updated_at", now);
            db.insertWithOnConflict("memories", null, row, SQLiteDatabase.CONFLICT_REPLACE);
            ContentValues historic = new ContentValues(row);
            historic.remove("updated_at"); historic.put("created_at", now);
            db.insertOrThrow("memory_revisions", null, historic);
            JSONObject result = one(db, "SELECT * FROM memories WHERE memory_key = ?", new String[]{key});
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }

    public synchronized JSONArray memoryRevisions(String key, int limit) {
        return queryRows(getReadableDatabase(), "SELECT * FROM memory_revisions WHERE memory_key = ? ORDER BY revision DESC LIMIT ?",
                new String[]{required(key, "key", 512), String.valueOf(boundedLimit(limit, 1000))});
    }

    public synchronized JSONArray searchMemories(String query, int limit) {
        int count = boundedLimit(limit, 100);
        List<String> tokens = tokens(query);
        String sql = "SELECT * FROM memories";
        List<String> args = new ArrayList<>();
        if (!tokens.isEmpty()) {
            StringBuilder relevance = new StringBuilder();
            StringBuilder filter = new StringBuilder(" WHERE ");
            List<String> filterArgs = new ArrayList<>();
            for (String token : tokens) {
                if (!args.isEmpty()) {
                    relevance.append(" + ");
                    filter.append(" OR ");
                }
                relevance.append("(CASE WHEN memory_key LIKE ? ESCAPE '\\' THEN 4 ELSE 0 END)")
                        .append(" + (CASE WHEN content LIKE ? ESCAPE '\\' THEN 1 ELSE 0 END)");
                filter.append("(memory_key LIKE ? ESCAPE '\\' OR content LIKE ? ESCAPE '\\')");
                String pattern = "%" + escapeLike(token) + "%";
                args.add(pattern); args.add(pattern);
                filterArgs.add(pattern); filterArgs.add(pattern);
            }
            args.addAll(filterArgs);
            sql = "SELECT *, (" + relevance + ") AS relevance_score FROM memories" + filter;
        }
        sql += tokens.isEmpty() ? " ORDER BY updated_at DESC LIMIT 200" : " ORDER BY relevance_score DESC, updated_at DESC LIMIT 200";
        JSONArray candidates = queryRows(getReadableDatabase(), sql, args.toArray(new String[0]));
        List<JSONObject> scored = new ArrayList<>();
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject memory = candidates.optJSONObject(i);
            String key = memory.optString("key").toLowerCase(Locale.ROOT);
            String text = memory.optString("content").toLowerCase(Locale.ROOT);
            int score = 0;
            for (String token : tokens) {
                if (key.contains(token)) score += 4;
                if (text.contains(token)) score += 1;
            }
            put(memory, "score", score);
            scored.add(memory);
        }
        scored.sort((a, b) -> {
            int comparison = Integer.compare(b.optInt("score"), a.optInt("score"));
            return comparison != 0 ? comparison : Long.compare(b.optLong("updated_at"), a.optLong("updated_at"));
        });
        JSONArray result = new JSONArray();
        for (int i = 0; i < Math.min(count, scored.size()); i++) result.put(scored.get(i));
        return result;
    }

    public synchronized String memoryContext(String query, int maxChars) {
        int bound = boundedChars(maxChars);
        StringBuilder result = new StringBuilder();
        JSONArray memories = searchMemories(query, 100);
        for (int i = 0; i < memories.length() && result.length() < bound; i++) {
            JSONObject memory = memories.optJSONObject(i);
            String line = "[memory key=" + memory.optString("key") + " kind=" + memory.optString("kind")
                    + " revision=" + memory.optInt("revision") + " sourceMessageIds="
                    + memory.optJSONArray("sourceMessageIds") + "]\n" + memory.optString("content") + "\n\n";
            appendBounded(result, line, bound);
        }
        return result.toString();
    }

    public synchronized String getSetting(String key, String defaultValue) {
        JSONObject row = one(getReadableDatabase(), "SELECT * FROM settings WHERE setting_key = ?",
                new String[]{required(key, "key", 512)});
        return row == null ? defaultValue : row.optString("value", defaultValue);
    }

    public synchronized void putSetting(String key, String value) {
        ContentValues row = new ContentValues();
        row.put("setting_key", required(key, "key", 512));
        if (value == null || value.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("Invalid setting value");
        row.put("value", value);
        getWritableDatabase().insertWithOnConflict("settings", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String taskContext(int maxChars) {
        int bound = boundedChars(maxChars);
        StringBuilder result = new StringBuilder();
        JSONArray rows = queryRows(getReadableDatabase(), "SELECT * FROM requests WHERE status NOT IN ('completed', 'abandoned') ORDER BY id DESC LIMIT 50", null);
        for (int i = 0; i < rows.length() && result.length() < bound; i++) {
            JSONObject row = rows.optJSONObject(i);
            appendBounded(result, "[request " + row.optLong("id") + " kind=" + row.optString("kind")
                    + " status=" + row.optString("status") + "] " + row.optString("text") + "\n", bound);
        }
        return result.toString();
    }

    public synchronized void recordEvent(String type, JSONObject payload) {
        ContentValues row = new ContentValues();
        row.put("type", required(type, "type", 128));
        String text = payload == null ? "{}" : payload.toString();
        if (text.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("Event payload is too long");
        row.put("payload", text); row.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("events", null, row);
    }

    public synchronized JSONArray events(int limit) {
        return queryRows(getReadableDatabase(), "SELECT * FROM events ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(boundedLimit(limit, 1000))});
    }

    public synchronized JSONObject exportSnapshot() {
        SQLiteDatabase db = getReadableDatabase();
        db.beginTransaction();
        try {
            JSONObject snapshot = new JSONObject();
            put(snapshot, "version", SNAPSHOT_VERSION);
            put(snapshot, "exportedAt", System.currentTimeMillis());
            for (String table : tables()) put(snapshot, table, queryRows(db, "SELECT * FROM " + table, null));
            db.setTransactionSuccessful();
            return snapshot;
        } finally { db.endTransaction(); }
    }

    /** Validate before touching files or replacing a database. Also called by importSnapshot. */
    public synchronized void validateSnapshot(JSONObject snapshot) {
        if (snapshot == null || snapshot.optInt("version", -1) != SNAPSHOT_VERSION) throw new IllegalArgumentException("Unsupported backup version");
        Set<Long> messageIds = new HashSet<>();
        Map<String, JSONObject> currentMemories = new HashMap<>();
        Map<String, Map<Integer, JSONObject>> revisions = new HashMap<>();
        for (String table : tables()) {
            JSONArray rows = snapshot.optJSONArray(table);
            if (rows == null || rows.length() > 100_000) throw new IllegalArgumentException("Missing or oversized backup table: " + table);
            Set<String> primaryKeys = new HashSet<>();
            Set<String> externalIds = new HashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) throw new IllegalArgumentException("Malformed backup row");
                snapshotValues(table, row);
                String primaryKey = table.equals("memories") || table.equals("settings") ? row.optString("key") : String.valueOf(row.optLong("id"));
                if (!primaryKeys.add(primaryKey)) throw new IllegalArgumentException("Duplicate backup row key");
                if (table.equals("messages") && !messageIds.add(row.optLong("id"))) throw new IllegalArgumentException("Duplicate message id");
                if (table.equals("requests") && !externalIds.add(row.optString("external_id"))) throw new IllegalArgumentException("Duplicate request external id");
                if (table.equals("memories")) currentMemories.put(row.optString("key"), row);
                if (table.equals("memory_revisions")) {
                    Map<Integer, JSONObject> history = revisions.computeIfAbsent(row.optString("key"), ignored -> new HashMap<>());
                    if (history.put(row.optInt("revision"), row) != null) throw new IllegalArgumentException("Duplicate memory revision");
                }
            }
        }
        for (String table : new String[]{"memories", "memory_revisions"}) {
            JSONArray rows = snapshot.optJSONArray(table);
            for (int i = 0; i < rows.length(); i++) {
                JSONArray ids = rows.optJSONObject(i).optJSONArray("sourceMessageIds");
                for (int j = 0; j < ids.length(); j++) if (!messageIds.contains(ids.optLong(j))) {
                    throw new IllegalArgumentException("Backup memory has no original source message");
                }
            }
        }
        for (Map.Entry<String, JSONObject> entry : currentMemories.entrySet()) {
            JSONObject current = entry.getValue();
            int revision = current.optInt("revision");
            Map<Integer, JSONObject> history = revisions.get(entry.getKey());
            if (history == null || history.size() != revision) throw new IllegalArgumentException("Incomplete memory revision history");
            for (int i = 1; i <= revision; i++) if (!history.containsKey(i)) throw new IllegalArgumentException("Missing memory revision");
            JSONObject latest = history.get(revision);
            if (!current.optString("content").equals(latest.optString("content"))
                    || !current.optString("kind").equals(latest.optString("kind"))
                    || !current.optJSONArray("sourceMessageIds").toString().equals(latest.optJSONArray("sourceMessageIds").toString())) {
                throw new IllegalArgumentException("Current memory does not match its revision history");
            }
        }
        for (String key : revisions.keySet()) if (!currentMemories.containsKey(key)) throw new IllegalArgumentException("Orphan memory revision history");
    }

    /** Full replacement is deliberate; the caller must pause its runtime during restore. */
    public synchronized void importSnapshot(JSONObject snapshot) {
        importSnapshot(snapshot, null);
    }

    synchronized void importSnapshot(JSONObject snapshot, String fileRestoreToken) {
        validateSnapshot(snapshot);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String table : tables()) db.delete(table, null, null);
            for (String table : tables()) {
                JSONArray rows = snapshot.optJSONArray(table);
                for (int i = 0; i < rows.length(); i++) db.insertOrThrow(table, null, snapshotValues(table, rows.optJSONObject(i)));
            }
            quarantineRestoredRequests(db);
            if (fileRestoreToken != null) {
                ContentValues marker = new ContentValues();
                marker.put("setting_key", FILE_RESTORE_TOKEN_KEY);
                marker.put("value", fileRestoreToken);
                db.insertWithOnConflict("settings", null, marker, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private synchronized void recoverInterruptedRequests() {
        recoverInterruptedRequests(getWritableDatabase());
    }

    private static void recoverInterruptedRequests(SQLiteDatabase db) {
        ContentValues changes = new ContentValues();
        changes.put("status", "uncertain"); changes.put("updated_at", System.currentTimeMillis());
        db.update("requests", changes, "status IN ('dispatching', 'accepted')", null);
    }

    /** An old queued snapshot may refer to work that already ran after its export. */
    private void quarantineRestoredRequests(SQLiteDatabase db) {
        String pending = "status IN ('queued', 'blocked', 'quota', 'dispatching', 'accepted')";
        JSONObject statusCounts = new JSONObject();
        long count = 0;
        String batchStatus = null;
        JSONArray batchIds = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT id, status FROM requests WHERE " + pending + " ORDER BY status, id", null)) {
            while (cursor.moveToNext()) {
                String status = cursor.getString(1);
                if (batchStatus != null && (!batchStatus.equals(status) || batchIds.length() >= 500)) {
                    recordQuarantineBatch(batchStatus, batchIds);
                    batchIds = new JSONArray();
                }
                batchStatus = status;
                batchIds.put(cursor.getLong(0));
                put(statusCounts, status, statusCounts.optLong(status, 0) + 1);
                count++;
            }
        }
        if (batchIds.length() != 0) recordQuarantineBatch(batchStatus, batchIds);
        ContentValues changes = new ContentValues();
        changes.put("status", "uncertain"); changes.put("updated_at", System.currentTimeMillis());
        // Preserve every original text/result and the original status in the audit batches.
        db.update("requests", changes, pending, null);
        JSONObject summary = new JSONObject();
        put(summary, "requestCount", count); put(summary, "previousStatusCounts", statusCounts);
        put(summary, "automaticReplay", false); put(summary, "reason", "restored_snapshot_may_precede_execution");
        recordEvent("backup_restore_quarantine", summary);
    }

    private void recordQuarantineBatch(String previousStatus, JSONArray ids) {
        JSONObject provenance = new JSONObject();
        put(provenance, "requestIds", ids); put(provenance, "previousStatus", previousStatus);
        put(provenance, "restoredStatus", "uncertain"); put(provenance, "automaticReplay", false);
        recordEvent("backup_restore_quarantine_batch", provenance);
    }

    private JSONObject findMessage(long id) {
        return one(getReadableDatabase(), "SELECT * FROM messages WHERE id = ?", new String[]{String.valueOf(id)});
    }

    private Cursor receiptCursor() {
        return getReadableDatabase().rawQuery("SELECT setting_key, value FROM settings WHERE setting_key LIKE 'actionReceipt:%' ORDER BY setting_key", null);
    }

    private static boolean unresolvedReceipt(String value) {
        try {
            String status = new JSONObject(value).optString("status");
            return !"completed".equals(status) && !"abandoned".equals(status);
        }
        catch (JSONException malformed) { return true; }
    }

    private static JSONObject one(SQLiteDatabase db, String sql, String[] args) {
        JSONArray rows = queryRows(db, sql, args);
        return rows.length() == 0 ? null : rows.optJSONObject(0);
    }

    private static JSONArray queryRows(SQLiteDatabase db, String sql, String[] args) {
        JSONArray rows = new JSONArray();
        try (Cursor cursor = db.rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    String column = cursor.getColumnName(i);
                    Object value;
                    if (cursor.isNull(i)) value = JSONObject.NULL;
                    else if (cursor.getType(i) == Cursor.FIELD_TYPE_INTEGER) value = cursor.getLong(i);
                    else value = cursor.getString(i);
                    if (column.equals("source_ids")) {
                        column = "sourceMessageIds";
                        try { value = new JSONArray(String.valueOf(value)); } catch (JSONException e) { throw new IllegalStateException("Corrupt source references", e); }
                    } else if (column.equals("memory_key")) column = "key";
                    else if (column.equals("setting_key")) column = "key";
                    else if (column.equals("payload")) {
                        try { value = new JSONObject(String.valueOf(value)); } catch (JSONException e) { throw new IllegalStateException("Corrupt event payload", e); }
                    }
                    put(row, column, value);
                }
                rows.put(row);
            }
        }
        return rows;
    }

    private static ContentValues snapshotValues(String table, JSONObject row) {
        ContentValues value = new ContentValues();
        if (table.equals("messages") || table.equals("requests") || table.equals("memory_revisions") || table.equals("events")) {
            long id = row.optLong("id", -1);
            if (id <= 0) throw new IllegalArgumentException("Invalid backup row id");
            value.put("id", id);
        }
        if (!table.equals("settings")) value.put("created_at", nonnegative(row, "created_at"));
        switch (table) {
            case "messages":
                value.put("role", required(row.optString("role", null), "role", 64));
                value.put("text", required(row.optString("text", null), "text", MAX_TEXT_CHARS));
                if (!row.isNull("request_id")) value.put("request_id", required(row.optString("request_id", null), "request_id", 512));
                break;
            case "requests":
                value.put("external_id", required(row.optString("external_id", null), "external_id", 512));
                value.put("text", required(row.optString("text", null), "text", MAX_TEXT_CHARS));
                value.put("kind", required(row.optString("kind", null), "kind", 128));
                String status = row.optString("status", null); requireStatus(status); value.put("status", status);
                if (!row.isNull("result")) value.put("result", boundedOptional(row.optString("result", null), "result", MAX_TEXT_CHARS));
                value.put("updated_at", nonnegative(row, "updated_at"));
                break;
            case "memories":
            case "memory_revisions":
                value.put("memory_key", required(row.optString("key", null), "key", 512));
                value.put("content", required(row.optString("content", null), "content", MAX_TEXT_CHARS));
                value.put("kind", required(row.optString("kind", null), "kind", 128));
                JSONArray sources = row.optJSONArray("sourceMessageIds");
                if (sources == null) throw new IllegalArgumentException("Missing memory source references");
                value.put("source_ids", normalizeSources(sources).toString());
                int revision = row.optInt("revision", 0);
                if (revision < 1) throw new IllegalArgumentException("Invalid memory revision");
                value.put("revision", revision);
                if (table.equals("memories")) value.put("updated_at", nonnegative(row, "updated_at"));
                break;
            case "settings":
                value.put("setting_key", required(row.optString("key", null), "key", 512));
                value.put("value", boundedOptional(row.optString("value", null), "value", MAX_TEXT_CHARS));
                break;
            case "events":
                value.put("type", required(row.optString("type", null), "type", 128));
                JSONObject payload = row.optJSONObject("payload");
                if (payload == null) throw new IllegalArgumentException("Invalid event payload");
                value.put("payload", boundedOptional(payload.toString(), "payload", MAX_TEXT_CHARS));
                break;
            default: throw new IllegalArgumentException("Unknown backup table");
        }
        return value;
    }

    private static long nonnegative(JSONObject row, String key) {
        long value = row.optLong(key, -1);
        if (value < 0) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }

    private static JSONArray normalizeSources(JSONArray input) {
        JSONArray result = new JSONArray();
        Set<Long> seen = new HashSet<>();
        if (input == null) return result;
        if (input.length() > 10_000) throw new IllegalArgumentException("Too many source messages");
        for (int i = 0; i < input.length(); i++) {
            Object raw = input.opt(i);
            if (!(raw instanceof Number) || ((Number) raw).doubleValue() != ((Number) raw).longValue()) {
                throw new IllegalArgumentException("Source message ids must be positive integers");
            }
            long id = ((Number) raw).longValue();
            if (id <= 0) throw new IllegalArgumentException("Source message ids must be positive integers");
            if (seen.add(id)) result.put(id);
        }
        return result;
    }

    private static String[] tables() { return new String[]{"messages", "requests", "memories", "memory_revisions", "settings", "events"}; }
    private static String required(String value, String name, int max) {
        if (value == null || value.trim().isEmpty() || value.length() > max) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
    private static String boundedOptional(String value, String name, int max) {
        if (value == null || value.length() > max) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
    private static void requireStatus(String status) {
        for (String allowed : STATUSES) if (allowed.equals(status)) return;
        throw new IllegalArgumentException("Unknown request status");
    }
    private static int boundedLimit(int limit, int maximum) { return Math.max(1, Math.min(maximum, limit)); }
    private static int boundedChars(int limit) { return Math.max(0, Math.min(MAX_CONTEXT_CHARS, limit)); }
    private static void appendBounded(StringBuilder target, String text, int maximum) {
        int count = Math.min(text.length(), maximum - target.length());
        if (count > 0) target.append(text, 0, count);
    }
    private static List<String> tokens(String query) {
        List<String> result = new ArrayList<>();
        if (query == null) return result;
        String bounded = query.substring(0, Math.min(query.length(), 2048)).trim().toLowerCase(Locale.ROOT);
        for (String word : bounded.split("\\s+")) if (!word.isEmpty() && !result.contains(word)) {
            result.add(word.substring(0, Math.min(word.length(), 128)));
            if (result.size() == 12) break;
        }
        return result;
    }
    private static String escapeLike(String text) { return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
