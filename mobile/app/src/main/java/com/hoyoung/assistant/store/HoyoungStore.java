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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;

/** Private, durable originals and source-backed memory. Nothing in this class uses cacheDir. */
public final class HoyoungStore extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "hoyoung.db";
    public static final int SCHEMA_VERSION = 2;
    public static final int SNAPSHOT_VERSION = 2;
    static final String FILE_RESTORE_TOKEN_KEY = "internal.files.restoreToken";
    private static final int MAX_TEXT_CHARS = 500_000;
    private static final int MAX_CONTEXT_CHARS = 100_000;
    private static final int SQL_MESSAGE_EXCERPT_CHARS = 200_000;
    private final OriginalTextFiles originalFiles;
    private final SourcePrivacyLedger privacyLedger;
    private final Context applicationContext;
    private volatile boolean storageClosed;
    private volatile boolean deviceSearchBootstrapRunning;
    private volatile String deviceSearchError = "";
    private static final String[] STATUSES = {
            "queued", "dispatching", "accepted", "completed", "uncertain", "blocked", "quota", "abandoned"
    };

    public HoyoungStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, SCHEMA_VERSION);
        applicationContext=context.getApplicationContext();
        // SQLiteOpenHelper's constructor has not opened the database yet. Preserve a
        // pending generation before OriginalTextFiles can create a missing namespace.
        InternalFiles.requireNoPendingRestore(context.getApplicationContext().getFilesDir());
        originalFiles = new OriginalTextFiles(context.getApplicationContext().getFilesDir());
        privacyLedger = new SourcePrivacyLedger(context.getApplicationContext());
        setWriteAheadLoggingEnabled(true);
        // A process death cannot tell us whether the remote side accepted an in-flight request.
        // Preserve it for explicit owner reconciliation instead of sending it a second time.
        recoverInterruptedRequests();
    }
    public SourcePrivacyLedger getPrivacyLedger(){return privacyLedger;}
    Context ownerControlContext(){return applicationContext;}

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        db.execSQL("PRAGMA synchronous=FULL");
    }

    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (db.isReadOnly()) return;
        db.beginTransaction();
        try { DeviceEventSearch.ensure(db); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
        if (DeviceEventSearch.state(db)[2] == 0 && !storageClosed && !deviceSearchBootstrapRunning) {
            deviceSearchBootstrapRunning = true;
            Thread worker = new Thread(() -> {
                try {
                    while (!storageClosed) {
                        boolean complete;
                        synchronized (HoyoungStore.this) {
                            if (storageClosed) break;
                            complete = rebuildDeviceEventSearchBatch(512).optBoolean("complete");
                        }
                        if (complete) break;
                        try { Thread.sleep(10); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
                    }
                } catch (RuntimeException unavailable) { deviceSearchError = unavailable.getClass().getSimpleName(); }
                finally { deviceSearchBootstrapRunning = false; }
            }, "hoyoung-device-search");
            worker.setDaemon(true); worker.start();
        }
    }

    @Override public synchronized void close() { storageClosed = true; super.close(); }

    public synchronized JSONObject deviceSearchIndexStatus() { return DeviceEventSearch.status(getReadableDatabase(), deviceSearchError); }

    public synchronized JSONObject rebuildDeviceEventSearchBatch(int limit) {
        return withTransaction(db -> DeviceEventSearch.batch(db, limit));
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createBaseSchema(db);
        upgradeToVersionTwo(db);
    }

    private static void createBaseSchema(SQLiteDatabase db) {
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
        if (oldVersion == 1 && newVersion >= 2) upgradeToVersionTwo(db);
        else throw new IllegalStateException("Unsupported non-destructive schema migration: " + oldVersion + " -> " + newVersion);
    }

    static void createSchema(SQLiteDatabase db) {
        createBaseSchema(db);
        upgradeToVersionTwo(db);
    }

    private static void upgradeToVersionTwo(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN content_hash TEXT");
        db.execSQL("ALTER TABLE messages ADD COLUMN raw_file_id TEXT");
        db.execSQL("ALTER TABLE messages ADD COLUMN raw_char_count INTEGER");
        db.execSQL("ALTER TABLE messages ADD COLUMN raw_byte_count INTEGER");
        db.execSQL("ALTER TABLE messages ADD COLUMN raw_content_hash TEXT");
        db.execSQL("ALTER TABLE requests ADD COLUMN raw_result_file_id TEXT");
        db.execSQL("ALTER TABLE requests ADD COLUMN raw_result_char_count INTEGER");
        db.execSQL("ALTER TABLE requests ADD COLUMN raw_result_byte_count INTEGER");
        db.execSQL("ALTER TABLE requests ADD COLUMN raw_result_hash TEXT");
        db.execSQL("ALTER TABLE memories ADD COLUMN source_refs TEXT NOT NULL DEFAULT '[]'");
        db.execSQL("ALTER TABLE memories ADD COLUMN content_hash TEXT");
        db.execSQL("ALTER TABLE memory_revisions ADD COLUMN source_refs TEXT NOT NULL DEFAULT '[]'");
        db.execSQL("CREATE INDEX messages_role_id ON messages(role, id)");
        db.execSQL("CREATE INDEX messages_created_id ON messages(created_at, id)");
        db.execSQL("CREATE INDEX memories_updated_key ON memories(updated_at, memory_key)");
        db.execSQL("CREATE INDEX events_type_id ON events(type, id)");
        db.execSQL("CREATE TABLE device_events (id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, source TEXT NOT NULL, "
                + "payload TEXT NOT NULL, observed_at INTEGER NOT NULL, recorded_at INTEGER NOT NULL, dedupe_key TEXT UNIQUE, content_hash TEXT NOT NULL)");
        db.execSQL("CREATE INDEX device_events_category_id ON device_events(category, id)");
        db.execSQL("CREATE INDEX device_events_source_id ON device_events(source, id)");
        db.execSQL("CREATE INDEX device_events_observed_id ON device_events(observed_at, id)");
        db.execSQL("CREATE TABLE memory_sources (memory_key TEXT NOT NULL, revision INTEGER NOT NULL, source_type TEXT NOT NULL, "
                + "source_id INTEGER NOT NULL, source_hash TEXT NOT NULL, source_role TEXT, observed_at INTEGER NOT NULL, "
                + "PRIMARY KEY(memory_key, revision, source_type, source_id))");
        db.execSQL("CREATE INDEX memory_sources_original ON memory_sources(source_type, source_id)");
        db.execSQL("CREATE TABLE memory_metadata (memory_key TEXT PRIMARY KEY, metadata_json TEXT NOT NULL, retrieval_text TEXT NOT NULL, "
                + "pinned INTEGER NOT NULL DEFAULT 0, confidence REAL, valid_from INTEGER, valid_to INTEGER, metadata_revision INTEGER NOT NULL, "
                + "memory_revision INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX memory_metadata_pinned ON memory_metadata(pinned, memory_key)");
        db.execSQL("CREATE TABLE memory_metadata_revisions (id INTEGER PRIMARY KEY AUTOINCREMENT, memory_key TEXT NOT NULL, "
                + "metadata_json TEXT NOT NULL, retrieval_text TEXT NOT NULL, pinned INTEGER NOT NULL, confidence REAL, valid_from INTEGER, valid_to INTEGER, "
                + "metadata_revision INTEGER NOT NULL, memory_revision INTEGER NOT NULL, created_at INTEGER NOT NULL, UNIQUE(memory_key, metadata_revision))");
        db.execSQL("CREATE TABLE semantic_vectors (memory_key TEXT NOT NULL, revision INTEGER NOT NULL, model_id TEXT NOT NULL, "
                + "source_hash TEXT NOT NULL, dimension INTEGER NOT NULL, encoding TEXT NOT NULL, vector BLOB NOT NULL, updated_at INTEGER NOT NULL, "
                + "PRIMARY KEY(memory_key, model_id))");
        db.execSQL("CREATE INDEX semantic_vectors_model_key ON semantic_vectors(model_id, memory_key)");
        db.execSQL("CREATE INDEX memory_metadata_history_lookup ON memory_metadata_revisions(memory_key,memory_revision,metadata_revision)");
        createSearchSchema(db);
        try (Cursor cursor = db.rawQuery("SELECT * FROM messages ORDER BY id", null)) {
            while (cursor.moveToNext()) {
                JSONObject original = cursorRow(cursor);
                ContentValues value = new ContentValues();
                value.put("raw_char_count", original.optString("text").length());
                value.put("raw_byte_count", original.optString("text").getBytes(StandardCharsets.UTF_8).length);
                value.put("raw_content_hash", hash(original.optString("text")));
                put(original, "raw_char_count", value.getAsLong("raw_char_count")); put(original, "raw_byte_count", value.getAsLong("raw_byte_count"));
                put(original, "raw_content_hash", value.getAsString("raw_content_hash")); value.put("content_hash", messageHash(original));
                db.update("messages", value, "id = ?", new String[]{String.valueOf(original.optLong("id"))});
            }
        }
        rebuildDerivedIndexes(db);
    }

    private static void createSearchSchema(SQLiteDatabase db) {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS message_search USING fts4(text, tokenize=unicode61)");
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memory_search USING fts4(memory_key, content, retrieval_text, tokenize=unicode61)");
        DeviceEventSearch.ensure(db);
        db.execSQL("CREATE TRIGGER IF NOT EXISTS messages_fts_before_delete BEFORE DELETE ON messages BEGIN DELETE FROM message_search WHERE docid=old.id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS messages_fts_before_update BEFORE UPDATE OF text ON messages BEGIN DELETE FROM message_search WHERE docid=old.id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS messages_fts_after_insert AFTER INSERT ON messages BEGIN INSERT INTO message_search(docid,text) VALUES(new.id,new.text); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS messages_fts_after_update AFTER UPDATE OF text ON messages BEGIN INSERT INTO message_search(docid,text) VALUES(new.id,new.text); END");
    }

    public interface TransactionOperation<T> { T run(SQLiteDatabase database); }

    /** Private single-connection read transaction; writers and UI keep using the WAL pool. */
    public static final class ReadSnapshot implements AutoCloseable {
        final SQLiteDatabase database;
        private final boolean modern;
        private final Thread owner = Thread.currentThread();
        ReadSnapshot(SQLiteDatabase database) {
            this.database = database; modern = android.os.Build.VERSION.SDK_INT >= 35;
            if (android.os.Build.VERSION.SDK_INT >= 35) database.beginTransactionReadOnly();
            else database.execSQL("SAVEPOINT hoyoung_read_snapshot");
            // Establish the read view now, before export can yield to another writer.
            scalar(database, "SELECT COUNT(*) FROM sqlite_master");
        }
        void requireOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("Read snapshots belong to their opening thread"); }
        /** Caller closes the cursor before this thread-owned read-only snapshot. */
        public Cursor rawQuery(String sql, String[] args) { requireOwner(); return database.rawQuery(sql, args); }
        @Override public void close() {
            requireOwner();
            try { if (modern) database.endTransaction(); else database.execSQL("RELEASE hoyoung_read_snapshot"); }
            finally { database.close(); }
        }
    }

    public synchronized ReadSnapshot openReadSnapshot() {
        String path = getReadableDatabase().getPath();
        SQLiteDatabase database = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        try { return new ReadSnapshot(database); }
        catch (RuntimeException unavailable) { database.close(); throw unavailable; }
    }

    /** One lock order for producers and backups: store monitor, then SQLite transaction. */
    public synchronized <T> T withTransaction(TransactionOperation<T> operation) {
        if (operation == null) throw new IllegalArgumentException("A transaction operation is required");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try { T result = operation.run(db); db.setTransactionSuccessful(); return result; }
        finally { db.endTransaction(); }
    }

    public synchronized JSONObject addMessage(String role, String text, Object requestIdNullable) {
        required(text, "text", OriginalTextFiles.MAX_ORIGINAL_CHARS);
        ContentValues row = new ContentValues();
        row.put("role", required(role, "role", 64));
        row.put("text", excerpt(text, SQL_MESSAGE_EXCERPT_CHARS));
        if (text.length() > SQL_MESSAGE_EXCERPT_CHARS) {
            try {
                JSONObject saved = originalFiles.save(text);
                row.put("raw_file_id", saved.optString("raw_file_id")); row.put("raw_char_count", saved.optLong("raw_char_count"));
                row.put("raw_byte_count", saved.optLong("raw_byte_count")); row.put("raw_content_hash", saved.optString("raw_content_hash"));
            } catch (IOException unavailable) { throw new IllegalStateException("The exact received original could not be saved", unavailable); }
        } else {
            row.put("raw_char_count", text.length()); row.put("raw_byte_count", text.getBytes(StandardCharsets.UTF_8).length);
            row.put("raw_content_hash", hash(text));
        }
        if (requestIdNullable != null) row.put("request_id", required(String.valueOf(requestIdNullable), "requestId", 512));
        row.put("created_at", System.currentTimeMillis());
        return withTransaction(db -> {
            long id = db.insertOrThrow("messages", null, row);
            JSONObject original = findMessage(id);
            ContentValues integrity = new ContentValues(); integrity.put("content_hash", messageHash(original));
            db.update("messages", integrity, "id = ?", new String[]{String.valueOf(id)});
            if (text.length() > SQL_MESSAGE_EXCERPT_CHARS) replaceMessageSearch(db, id, text);
            return findMessage(id);
        });
    }

    public synchronized JSONObject readOriginalText(long id, long offsetChars, int maxChars) throws IOException {
        return readOriginalText(getReadableDatabase(),id,offsetChars,maxChars);
    }

    /** Hydrate the source from the same established database view as retrieval candidates. */
    public JSONObject readOriginalText(ReadSnapshot snapshot,long id,long offsetChars,int maxChars)throws IOException {
        if(snapshot==null)throw new IllegalArgumentException("A read snapshot is required");
        snapshot.requireOwner();return readOriginalText(snapshot.database,id,offsetChars,maxChars);
    }

    private JSONObject readOriginalText(SQLiteDatabase database,long id,long offsetChars,int maxChars)throws IOException {
        JSONObject message = one(database,"SELECT * FROM messages WHERE id = ?",new String[]{String.valueOf(id)});
        if (message == null) throw new IllegalArgumentException("Unknown source message");
        if (offsetChars < 0) throw new IllegalArgumentException("Negative original offset");
        long total = message.optLong("raw_char_count", message.optString("text").length());
        long start = Math.min(total, offsetChars);
        String body;
        boolean spilled = !message.isNull("raw_file_id") && !message.optString("raw_file_id").isEmpty();
        if (spilled) {
            originalFiles.verify(message.optString("raw_file_id"), message.optString("raw_content_hash"), message.optLong("raw_byte_count"));
            body = originalFiles.read(message.optString("raw_file_id"), start, maxChars);
        }
        else {
            String text = message.optString("text"); int position = (int) Math.min(text.length(), start);
            if (position > 0 && position < text.length() && Character.isLowSurrogate(text.charAt(position))
                    && Character.isHighSurrogate(text.charAt(position - 1))) throw new IllegalArgumentException("Offset splits a UTF-16 surrogate pair");
            int count = Math.min(text.length() - position, Math.max(0, Math.min(1_000_000, maxChars)));
            body = text.substring(position, position + count);
        }
        if (!body.isEmpty() && start + body.length() < total && Character.isHighSurrogate(body.charAt(body.length() - 1))) body = body.substring(0, body.length() - 1);
        JSONObject result = new JSONObject();
        put(result, "messageId", id); put(result, "text", body); put(result, "offsetChars", start);
        put(result, "nextOffsetChars", start + body.length()); put(result, "totalChars", total);
        put(result, "complete", start + body.length() >= total); put(result, "contentHash", message.optString("raw_content_hash"));
        put(result, "recordHash", message.optString("content_hash")); put(result, "originalStored", true);
        return result;
    }

    public synchronized java.io.Reader openOriginalReader(long id) throws IOException {
        JSONObject message = findMessage(id); if (message == null) throw new IllegalArgumentException("Unknown source message");
        if (!message.isNull("raw_file_id")) {
            originalFiles.verify(message.optString("raw_file_id"), message.optString("raw_content_hash"), message.optLong("raw_byte_count"));
            return new InputStreamReader(new java.io.FileInputStream(originalFiles.file(message.optString("raw_file_id"))), StandardCharsets.UTF_8);
        }
        return new java.io.StringReader(message.optString("text"));
    }

    private static String excerpt(String text, int maximum) {
        int end=Math.min(text.length(),maximum);
        if(end<text.length()&&end>0&&Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;
        return text.substring(0,end);
    }

    public synchronized JSONArray listMessages(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        JSONArray recent = queryRows(db, "SELECT * FROM messages ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(boundedLimit(limit, 1000))});
        JSONArray chronological = new JSONArray();
        for (int i = recent.length() - 1; i >= 0; i--) chronological.put(recent.opt(i));
        return chronological;
    }

    // Plain system notices are visible app replies; structured system records are internal
    // source evidence. Their full originals remain available to source.read/search/backup.
    private static final String CHAT_VISIBLE = "(role<>'system' OR substr(text,1,1)<>'{')";

    /** Bounded visible chat excerpts; exact originals and hashes remain authoritative. */
    public synchronized JSONObject boundedChatMessagePage(long beforeId, int limit, int textChars) {
        if (beforeId < 0) throw new IllegalArgumentException("beforeId must be nonnegative");
        if (limit < 1 || limit > 40) throw new IllegalArgumentException("limit must be between 1 and 40");
        if (textChars < 1 || textChars > 8000) throw new IllegalArgumentException("textChars must be between 1 and 8000");
        // Bound UTF-8 bytes before CursorWindow, then apply the UTF-16 display limit in Java.
        // BLOB substr also preserves embedded NUL, where SQLite's TEXT substr would stop.
        // Four bytes per requested character ensure any partial UTF-8 tail is beyond the
        // final display boundary, while each SQL row remains at most 32 KiB of text bytes.
        int sqlTextBytes = textChars * 4;
        String sql = "SELECT id,role,substr(CAST(text AS BLOB),1,?) AS textBytes,request_id,created_at,content_hash,"
                + "raw_file_id,raw_char_count,raw_byte_count,raw_content_hash,"
                + "CASE WHEN raw_char_count IS NULL THEN length(text) ELSE raw_char_count END AS textTotalChars,"
                + "CASE WHEN length(CAST(text AS BLOB))>? THEN 1 ELSE 0 END AS sqlPageTruncated FROM messages"
                + " WHERE " + CHAT_VISIBLE + (beforeId == 0 ? "" : " AND id<?") + " ORDER BY id DESC LIMIT ?";
        List<String> arguments = new ArrayList<>();
        arguments.add(String.valueOf(sqlTextBytes)); arguments.add(String.valueOf(sqlTextBytes));
        if (beforeId != 0) arguments.add(String.valueOf(beforeId));
        arguments.add(String.valueOf(limit + 1));
        JSONArray descending = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, arguments.toArray(new String[0]))) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int column = 0; column < cursor.getColumnCount(); column++) {
                    String name = cursor.getColumnName(column);
                    if (name.equals("textBytes")) {
                        // Native CursorWindow TEXT decoding can reject an incomplete UTF-8
                        // tail. Java safely replaces that tail beyond the display boundary.
                        put(row, "text", new String(cursor.getBlob(column), StandardCharsets.UTF_8));
                    } else if (cursor.isNull(column)) put(row, name, JSONObject.NULL);
                    else if (cursor.getType(column) == Cursor.FIELD_TYPE_INTEGER) put(row, name, cursor.getLong(column));
                    else put(row, name, cursor.getString(column));
                }
                descending.put(row);
            }
        }
        boolean hasMore = descending.length() > limit;
        int returned = Math.min(limit, descending.length());
        JSONArray messages = new JSONArray();
        for (int i = returned - 1; i >= 0; i--) {
            JSONObject row = descending.optJSONObject(i);
            String projected = row.optString("text");
            boolean sqlTruncated = row.optInt("sqlPageTruncated") != 0;
            row.remove("sqlPageTruncated");
            // A legacy row without counts can still supply the exact total when SQL returned
            // its complete text. New and migrated records already carry UTF-16 raw counts.
            if (row.isNull("raw_char_count") && !sqlTruncated) put(row, "textTotalChars", projected.length());
            String excerpt = projected.substring(0, Math.min(textChars, projected.length()));
            if (!excerpt.isEmpty() && Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 1))) {
                excerpt = excerpt.substring(0, excerpt.length() - 1);
            }
            boolean pageTruncated = sqlTruncated || excerpt.length() < projected.length();
            put(row, "text", excerpt); put(row, "textExcerptChars", excerpt.length());
            put(row, "textTruncatedForPage", pageTruncated);
            put(row, "textIsExcerpt", pageTruncated || row.optLong("textTotalChars") > excerpt.length());
            if ("assistant".equals(row.optString("role")) && row.optString("text").startsWith("[hoyoung.reaction.v1]\n"))
                EmojiReactionReply.decorate(row, getSetting("answerSources:" + row.optLong("id"), null));
            messages.put(row);
        }
        JSONObject result = new JSONObject();
        put(result, "messages", messages); put(result, "hasMore", hasMore);
        put(result, "nextBeforeId", returned == 0 ? 0 : descending.optJSONObject(returned - 1).optLong("id"));
        return result;
    }

    public synchronized JSONObject getMessage(long id) { return findMessage(id); }

    /** UI polling can compare this value without loading or parsing historical message bodies. */
    public synchronized long lastMessageId() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT MAX(id) FROM messages", null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0;
        }
    }

    /** Internal execution evidence must not invalidate the visible conversation page. */
    public synchronized long lastChatMessageId() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT MAX(id) FROM messages WHERE " + CHAT_VISIBLE, null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0;
        }
    }

    public synchronized JSONArray listMessagesAfter(long afterId, int limit) {
        return queryRows(getReadableDatabase(), "SELECT * FROM messages WHERE id > ? ORDER BY id ASC LIMIT ?",
                new String[]{String.valueOf(Math.max(0, afterId)), String.valueOf(boundedLimit(limit, 500))});
    }

    public synchronized JSONArray messagesAfter(long afterId, int limit) { return listMessagesAfter(afterId, limit); }

    public synchronized JSONArray listUserMessagesAfter(long afterId, int limit) {
        return queryRows(getReadableDatabase(), "SELECT * FROM messages WHERE role = 'user' AND id > ? ORDER BY id ASC LIMIT ?",
                new String[]{String.valueOf(Math.max(0, afterId)), String.valueOf(boundedLimit(limit, 500))});
    }

    public synchronized long lastUserMessageId() { return scalarLong("SELECT MAX(id) FROM messages WHERE role='user'", null); }
    public synchronized long messageCount() { return scalarLong("SELECT COUNT(*) FROM messages", null); }

    public synchronized long getWatermark(String name) {
        try { return Math.max(0, Long.parseLong(getSetting("watermark:" + required(name, "watermark name", 480), "0"))); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("Malformed durable watermark", invalid); }
    }

    public synchronized boolean advanceWatermark(String name, long expected, long updated) {
        if (expected < 0 || updated < expected) throw new IllegalArgumentException("Watermarks advance monotonically");
        return withTransaction(db -> {
            if (getWatermark(name) != expected) return false;
            putSetting("watermark:" + name, String.valueOf(updated)); return true;
        });
    }

    public synchronized JSONObject appendDeviceEvent(String category, String source, JSONObject payload, long observedAt, String dedupeKey) {
        category = required(category, "category", 128); source = required(source, "source", 256);
        if (payload == null || observedAt < 0) throw new IllegalArgumentException("A payload and nonnegative observation time are required");
        String raw = payload.toString();
        if (raw.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("Device payload is too long");
        if (dedupeKey != null) dedupeKey = required(dedupeKey, "dedupeKey", 512);
        String hash = deviceHash(category, source, payload, observedAt);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (dedupeKey != null) {
                JSONObject previous = one(db, "SELECT * FROM device_events WHERE dedupe_key = ?", new String[]{dedupeKey});
                if (previous != null) {
                    if (!hash.equals(previous.optString("content_hash"))) throw new IllegalArgumentException("dedupeKey belongs to a different observation");
                    db.setTransactionSuccessful(); return previous;
                }
            }
            ContentValues values = new ContentValues();
            values.put("category", category); values.put("source", source); values.put("payload", raw);
            values.put("observed_at", observedAt); values.put("recorded_at", System.currentTimeMillis());
            if (dedupeKey != null) values.put("dedupe_key", dedupeKey);
            values.put("content_hash", hash);
            long id = db.insertOrThrow("device_events", null, values);
            JSONObject result = one(db, "SELECT * FROM device_events WHERE id = ?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful(); return result;
        } finally { db.endTransaction(); }
    }

    public synchronized JSONObject getDeviceEvent(long id) {
        return one(getReadableDatabase(), "SELECT * FROM device_events WHERE id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized JSONArray deviceEvents(String category, String source, long afterId, int limit) {
        StringBuilder where = new StringBuilder("id > ?");
        List<String> args = new ArrayList<>(); args.add(String.valueOf(Math.max(0, afterId)));
        deviceFilters(where, args, category, source);
        args.add(String.valueOf(boundedLimit(limit, 500)));
        return queryRows(getReadableDatabase(), "SELECT * FROM device_events WHERE " + where + " ORDER BY id ASC LIMIT ?", args.toArray(new String[0]));
    }

    public synchronized JSONArray listDeviceEvents(int limit) { return recentDeviceEvents(null, null, limit); }

    public synchronized JSONArray recentDeviceEvents(String category, String source, int limit) {
        StringBuilder where = new StringBuilder("1=1"); List<String> args = new ArrayList<>();
        deviceFilters(where, args, category, source); args.add(String.valueOf(boundedLimit(limit, 500)));
        JSONArray descending = queryRows(getReadableDatabase(), "SELECT * FROM device_events WHERE " + where + " ORDER BY id DESC LIMIT ?", args.toArray(new String[0]));
        JSONArray result = new JSONArray(); for (int i = descending.length() - 1; i >= 0; i--) result.put(descending.opt(i));
        return result;
    }

    public synchronized JSONArray searchDeviceEvents(String query, String category, int limit) {
        StringBuilder where = new StringBuilder("1=1"); List<String> args = new ArrayList<>();
        deviceFilters(where, args, category, null);
        List<String> words = tokens(query);
        if (!words.isEmpty()) {
            where.append(" AND (");
            for (int i = 0; i < words.size(); i++) {
                if (i != 0) where.append(" OR ");
                where.append("payload LIKE ? ESCAPE '\\'"); args.add("%" + escapeLike(words.get(i)) + "%");
            }
            where.append(")");
        }
        args.add(String.valueOf(boundedLimit(limit, 500)));
        return queryRows(getReadableDatabase(), "SELECT * FROM device_events WHERE " + where + " ORDER BY id DESC LIMIT ?", args.toArray(new String[0]));
    }

    public synchronized long lastDeviceEventId() { return scalarLong("SELECT MAX(id) FROM device_events", null); }

    public synchronized JSONObject deviceEventStats() {
        JSONObject result = new JSONObject();
        put(result, "count", scalarLong("SELECT COUNT(*) FROM device_events", null));
        put(result, "firstId", scalarLong("SELECT MIN(id) FROM device_events", null)); put(result, "lastId", lastDeviceEventId());
        put(result, "oldestObservedAt", scalarLong("SELECT MIN(observed_at) FROM device_events", null));
        put(result, "newestObservedAt", scalarLong("SELECT MAX(observed_at) FROM device_events", null));
        put(result, "coverage", "Only observed and durably saved events; permission/offline gaps are not invented");
        return result;
    }

    public synchronized String deviceContext(String category, int maxChars) {
        int bound = boundedChars(maxChars); StringBuilder result = new StringBuilder();
        appendBounded(result, "Device context contains saved observations only. It does not prove continuous coverage.\n", bound);
        JSONArray rows = recentDeviceEvents(category, null, 30);
        for (int i = 0; i < rows.length() && result.length() < bound; i++) {
            JSONObject row = rows.optJSONObject(i);
            appendBounded(result, "[device_event id=" + row.optLong("id") + " category=" + row.optString("category") + " source="
                    + row.optString("source") + " observedAt=" + row.optLong("observed_at") + " hash=" + row.optString("content_hash") + "] "
                    + row.optJSONObject("payload") + "\n", bound);
        }
        return result.toString();
    }

    public synchronized JSONObject recordDeviceGap(String source, long startedAt, long endedAt, String reason) {
        if (startedAt < 0 || endedAt < startedAt) throw new IllegalArgumentException("Invalid collection gap interval");
        JSONObject payload = new JSONObject(); put(payload, "startedAt", startedAt); put(payload, "endedAt", endedAt);
        put(payload, "reason", required(reason, "reason", 4096)); put(payload, "continuousCoverage", false);
        return appendDeviceEvent("collector_gap", source, payload, endedAt, null);
    }

    private static void deviceFilters(StringBuilder where, List<String> args, String category, String source) {
        if (category != null && !category.isEmpty()) { where.append(" AND category = ?"); args.add(required(category, "category", 128)); }
        if (source != null && !source.isEmpty()) { where.append(" AND source = ?"); args.add(required(source, "source", 256)); }
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
        return one(getReadableDatabase(), "SELECT * FROM requests WHERE status = 'queued' ORDER BY CASE WHEN kind='user' THEN 0 ELSE 1 END,id ASC LIMIT 1", null);
    }

    public synchronized void markRequest(long id, String status, String resultNullable) {
        requireStatus(status);
        if (resultNullable != null && resultNullable.length() > OriginalTextFiles.MAX_ORIGINAL_CHARS) throw new IllegalArgumentException("result is too long");
        ContentValues values = new ContentValues();
        values.put("status", status);
        if (resultNullable == null) {
            // Owner abandonment describes a decision to stop tracking; it is not a new
            // provider outcome and must not erase the last known result/remote task id.
            if (!"abandoned".equals(status)) values.putNull("result");
        } else {
            values.put("result", excerpt(resultNullable, SQL_MESSAGE_EXCERPT_CHARS));
            if (resultNullable.length() > SQL_MESSAGE_EXCERPT_CHARS) {
                try {
                    JSONObject saved = originalFiles.save(resultNullable);
                    values.put("raw_result_file_id", saved.optString("raw_file_id")); values.put("raw_result_char_count", saved.optLong("raw_char_count"));
                    values.put("raw_result_byte_count", saved.optLong("raw_byte_count")); values.put("raw_result_hash", saved.optString("raw_content_hash"));
                } catch (IOException unavailable) { throw new IllegalStateException("The exact result could not be saved", unavailable); }
            } else {
                values.putNull("raw_result_file_id"); values.put("raw_result_char_count", resultNullable.length());
                values.put("raw_result_byte_count", resultNullable.getBytes(StandardCharsets.UTF_8).length); values.put("raw_result_hash", hash(resultNullable));
            }
        }
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
        JSONArray references = new JSONArray();
        JSONArray sources = normalizeSources(sourceMessageIds);
        for (int i = 0; i < sources.length(); i++) {
            JSONObject reference = new JSONObject(); put(reference, "type", "message"); put(reference, "id", sources.optLong(i)); references.put(reference);
        }
        return upsertMemoryWithSources(key, content, kind, references);
    }

    public synchronized JSONObject upsertMemoryWithSources(String key, String content, String kind, JSONArray references) {
        key = required(key, "key", 512);
        content = required(content, "content", MAX_TEXT_CHARS);
        kind = required(kind, "kind", 128);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONArray verified = normalizeReferences(db, references);
            JSONArray sources = messageIds(verified);
            JSONArray compact = compactReferences(verified);
            JSONObject old = one(db, "SELECT * FROM memories WHERE memory_key = ?", new String[]{key});
            if (old != null && content.equals(old.optString("content")) && kind.equals(old.optString("kind"))
                    && compact.toString().equals(old.optJSONArray("sourceRefs").toString())) {
                db.setTransactionSuccessful();
                return enrichMemory(db, old);
            }
            long now = System.currentTimeMillis();
            int revision = old == null ? 1 : old.optInt("revision") + 1;
            ContentValues row = new ContentValues();
            row.put("memory_key", key); row.put("content", content); row.put("kind", kind);
            row.put("source_ids", sources.toString()); row.put("source_refs", compact.toString()); row.put("revision", revision);
            row.put("created_at", old == null ? now : old.optLong("created_at")); row.put("updated_at", now);
            JSONObject document = new JSONObject(); put(document, "key", key); put(document, "content", content); put(document, "kind", kind);
            put(document, "sourceRefs", verified); put(document, "metadata", getMemoryMetadata(key));
            row.put("content_hash", hash(canonical(document)));
            if (old == null) db.insertOrThrow("memories", null, row);
            else if (db.update("memories", row, "memory_key = ?", new String[]{key}) != 1) throw new IllegalStateException("Memory update failed");
            ContentValues historic = new ContentValues(row);
            historic.remove("updated_at"); historic.remove("content_hash"); historic.put("created_at", now);
            db.insertOrThrow("memory_revisions", null, historic);
            insertSourceLinks(db, key, revision, verified);
            JSONObject existingMetadata = one(db, "SELECT metadata_json FROM memory_metadata WHERE memory_key = ?", new String[]{key});
            if (existingMetadata != null) saveMetadata(db, key, existingMetadata.optJSONObject("metadata"), revision, true);
            updateMemoryHash(db, key);
            db.delete("semantic_vectors", "memory_key = ?", new String[]{key});
            rebuildMemorySearchRow(db, key);
            JSONObject result = enrichMemory(db, one(db, "SELECT * FROM memories WHERE memory_key = ?", new String[]{key}));
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }

    public synchronized JSONArray memoryRevisions(String key, int limit) {
        SQLiteDatabase db = getReadableDatabase();
        JSONArray result = queryRows(db, "SELECT * FROM memory_revisions WHERE memory_key = ? ORDER BY revision DESC LIMIT ?",
                new String[]{required(key, "key", 512), String.valueOf(boundedLimit(limit, 1000))});
        for (int i = 0; i < result.length(); i++) {
            JSONObject row = result.optJSONObject(i);
            JSONObject metadata = one(db, "SELECT metadata_json FROM memory_metadata_revisions WHERE memory_key = ? AND memory_revision = ? ORDER BY metadata_revision DESC LIMIT 1",
                    new String[]{key, String.valueOf(row.optInt("revision"))});
            put(row, "metadata", metadata == null ? JSONObject.NULL : metadata.optJSONObject("metadata"));
            put(row, "sourceRefs", sourceLinks(db, key, row.optInt("revision")));
        }
        return result;
    }

    public synchronized JSONObject getMemory(String key) {
        return enrichMemory(getReadableDatabase(), one(getReadableDatabase(), "SELECT * FROM memories WHERE memory_key = ?", new String[]{required(key, "key", 512)}));
    }

    /** Bounded candidate view; use getMemory for the complete authoritative memory. */
    public synchronized JSONObject getMemoryCandidate(String key) {
        SQLiteDatabase db = getReadableDatabase();
        JSONObject row = one(db, "SELECT " + memoryCandidateProjection("m") + " FROM memories m WHERE m.memory_key=?",
                new String[]{required(key, "key", 512)});
        return row == null ? null : enrichMemoryCandidate(db, row);
    }

    public synchronized JSONObject getMemoryMetadata(String key) {
        JSONObject row = one(getReadableDatabase(), "SELECT metadata_json FROM memory_metadata WHERE memory_key = ?", new String[]{required(key, "key", 512)});
        return row == null ? new JSONObject() : row.optJSONObject("metadata");
    }

    public synchronized void putMemoryMetadata(String key, JSONObject metadata) {
        key = required(key, "key", 512);
        if (metadata == null || metadata.toString().length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("Invalid memory metadata");
        final String memoryKey = key;
        withTransaction(db -> {
            JSONObject memory = one(db, "SELECT * FROM memories WHERE memory_key = ?", new String[]{memoryKey});
            if (memory == null) throw new IllegalArgumentException("Unknown memory key");
            saveMetadata(db, memoryKey, metadata, memory.optInt("revision"), false);
            updateMemoryHash(db, memoryKey); db.delete("semantic_vectors", "memory_key = ?", new String[]{memoryKey});
            rebuildMemorySearchRow(db, memoryKey); return null;
        });
    }

    public synchronized JSONArray searchMemories(String query, int limit) {
        return retrieveMemoryCandidates(query, limit);
    }

    public synchronized JSONArray retrieveMemoryCandidates(String query, int limit) {
        return memoryCandidates(query, limit, true);
    }

    private JSONArray memoryCandidates(String query, int limit, boolean useFts) {
        List<String> words = tokens(query); List<String> args = new ArrayList<>();
        StringBuilder score = new StringBuilder("0"), matches = new StringBuilder();
        for (String word : words) {
            String pattern = "%" + escapeLike(word) + "%";
            score.append(" + CASE WHEN m.memory_key LIKE ? ESCAPE '\\' THEN 12 ELSE 0 END")
                    .append(" + CASE WHEN m.content LIKE ? ESCAPE '\\' THEN 3 ELSE 0 END")
                    .append(" + CASE WHEN x.retrieval_text LIKE ? ESCAPE '\\' THEN 8 ELSE 0 END");
            args.add(pattern); args.add(pattern); args.add(pattern);
            if (matches.length() > 0) matches.append(" OR ");
            matches.append("(m.memory_key LIKE ? ESCAPE '\\' OR m.content LIKE ? ESCAPE '\\' OR x.retrieval_text LIKE ? ESCAPE '\\')");
        }
        String full = query == null ? "" : query.trim().substring(0, Math.min(query.trim().length(), 2048));
        if (!full.isEmpty()) {
            score.append(" + CASE WHEN m.memory_key = ? COLLATE NOCASE THEN 1000 ELSE 0 END + CASE WHEN m.content LIKE ? ESCAPE '\\' THEN ")
                    .append(words.size() > 1 ? 80 : 8).append(" ELSE 0 END");
            args.add(full); args.add("%" + escapeLike(full) + "%");
        }
        List<String> filters = new ArrayList<>();
        for (String word : words) { String pattern = "%" + escapeLike(word) + "%"; filters.add(pattern); filters.add(pattern); filters.add(pattern); }
        List<String> ftsGroups = useFts ? ftsGroups(words) : Collections.emptyList();
        String fts = ftsGroups.isEmpty() ? "" : ftsGroups.get(0);
        if (fts.isEmpty()) args.addAll(filters); else args.addAll(ftsGroups);
        args.add(String.valueOf(boundedLimit(limit, 500)));
        String where = matches.length() == 0 ? "" : fts.isEmpty() ? " WHERE " + matches : " WHERE m.rowid IN (" + ftsUnion("memory_search", ftsGroups.size()) + ")";
        JSONArray rows = queryRows(getReadableDatabase(), "SELECT " + memoryCandidateProjection("m") + ", (" + score + ") AS score FROM memories m LEFT JOIN memory_metadata x ON x.memory_key=m.memory_key"
                + where + " ORDER BY score DESC, m.updated_at DESC, m.memory_key ASC LIMIT ?", args.toArray(new String[0]));
        for (int i = 0; i < rows.length(); i++) enrichMemoryCandidate(getReadableDatabase(), rows.optJSONObject(i));
        if (rows.length() == 0 && !fts.isEmpty()) return memoryCandidates(query, limit, false);
        return rows;
    }

    public synchronized JSONArray retrievePinnedMemories(int limit) {
        JSONArray rows = queryRows(getReadableDatabase(), "SELECT " + memoryCandidateProjection("m") + " FROM memory_metadata x JOIN memories m ON m.memory_key=x.memory_key "
                + "WHERE x.pinned=1 ORDER BY m.memory_key ASC LIMIT ?", new String[]{String.valueOf(boundedLimit(limit, 1000))});
        for (int i = 0; i < rows.length(); i++) enrichMemoryCandidate(getReadableDatabase(), rows.optJSONObject(i));
        return rows;
    }

    public synchronized JSONArray pageAllMemories(long beforeUpdatedAt, String beforeKey, int limit) {
        String where = beforeUpdatedAt <= 0 ? "" : " WHERE updated_at < ? OR (updated_at = ? AND memory_key > ?)";
        String[] args = beforeUpdatedAt <= 0 ? new String[]{String.valueOf(boundedLimit(limit, 500))}
                : new String[]{String.valueOf(beforeUpdatedAt), String.valueOf(beforeUpdatedAt), beforeKey == null ? "" : beforeKey, String.valueOf(boundedLimit(limit, 500))};
        JSONArray rows = queryRows(getReadableDatabase(), "SELECT " + memoryCandidateProjection("memories") + " FROM memories" + where + " ORDER BY updated_at DESC, memory_key ASC LIMIT ?", args);
        for (int i = 0; i < rows.length(); i++) enrichMemoryCandidate(getReadableDatabase(), rows.optJSONObject(i));
        return rows;
    }

    public synchronized long memoryCount() { return scalarLong("SELECT COUNT(*) FROM memories", null); }

    public synchronized JSONArray queryOriginals(String query, long fromInclusive, long toExclusive, long beforeId, int limit) {
        return originalCandidates(query, fromInclusive, toExclusive, beforeId, limit, false);
    }

    public synchronized JSONArray rankedOriginalCandidates(String query, long fromInclusive, long toExclusive, int limit) {
        return originalCandidates(query, fromInclusive, toExclusive, 0, limit, true);
    }

    private JSONArray originalCandidates(String query, long fromInclusive, long toExclusive, long beforeId, int limit, boolean ranked) {
        List<String> words = tokens(query); List<String> ftsGroups = ftsGroups(words); String fts = ftsGroups.isEmpty() ? "" : ftsGroups.get(0); List<String> args = new ArrayList<>();
        StringBuilder score = new StringBuilder(fts.isEmpty() ? "0" : "hits.term_score"), where = new StringBuilder("1=1");
        String from = "messages m";
        if (!fts.isEmpty()) {
            from = "(" + ftsScoredUnion("message_search", ftsGroups.size()) + ") hits JOIN messages m ON m.id=hits.docid JOIN message_search search_doc ON search_doc.docid=m.id";
            // SQL bind order is SELECT expression before the FROM subquery.
        }
        if (ranked && query != null && !query.trim().isEmpty()) {
            score.append(" + CASE WHEN " + (fts.isEmpty() ? "m.text" : "search_doc.text") + " LIKE ? ESCAPE '\\' THEN 100 ELSE 0 END");
            args.add("%" + escapeLike(query.substring(0, Math.min(query.length(), 2048))) + "%");
        }
        if (!fts.isEmpty()) args.addAll(ftsGroups);
        else if (!words.isEmpty()) {
            where.append(" AND (");
            for (int i = 0; i < words.size(); i++) {
                if (i > 0) where.append(" OR "); where.append("m.text LIKE ? ESCAPE '\\'"); args.add("%" + escapeLike(words.get(i)) + "%");
            }
            where.append(')');
        }
        if (fromInclusive > 0) { where.append(" AND m.created_at >= ?"); args.add(String.valueOf(fromInclusive)); }
        if (toExclusive > 0) { where.append(" AND m.created_at < ?"); args.add(String.valueOf(toExclusive)); }
        if (beforeId > 0) { where.append(" AND m.id < ?"); args.add(String.valueOf(beforeId)); }
        args.add(String.valueOf(boundedLimit(limit, 500)));
        JSONArray rows = queryRows(getReadableDatabase(), "SELECT m.id,m.role,substr(m.text,1,4096) AS text,m.request_id,m.created_at,m.content_hash,"
                + "m.raw_file_id,m.raw_char_count,m.raw_byte_count,m.raw_content_hash, (" + score + ") AS score FROM " + from + " WHERE " + where
                + (ranked ? " ORDER BY score DESC, m.id DESC LIMIT ?" : " ORDER BY m.id DESC LIMIT ?"), args.toArray(new String[0]));
        for (int i = 0; i < rows.length(); i++) { put(rows.optJSONObject(i), "candidateExcerpt", true); put(rows.optJSONObject(i), "textExcerptChars", rows.optJSONObject(i).optString("text").length()); }
        return rows;
    }

    public synchronized JSONArray rankedDeviceCandidates(String query, long fromInclusive, long toExclusive, int limit) {
        List<String> words = tokens(query); List<String> ftsGroups = ftsGroups(words); String fts = ftsGroups.isEmpty() ? "" : ftsGroups.get(0);
        List<String> args = new ArrayList<>(), filters = new ArrayList<>();
        StringBuilder score = new StringBuilder(fts.isEmpty() ? "0" : "10"), matches = new StringBuilder();
        for (String word : words) {
            score.append(" + CASE WHEN payload LIKE ? ESCAPE '\\' THEN 10 ELSE 0 END"); args.add("%" + escapeLike(word) + "%");
            if (matches.length() > 0) matches.append(" OR "); matches.append("payload LIKE ? ESCAPE '\\'"); filters.add("%" + escapeLike(word) + "%");
        }
        if (query != null && !query.trim().isEmpty()) {
            score.append(" + CASE WHEN payload LIKE ? ESCAPE '\\' THEN 100 ELSE 0 END"); args.add("%" + escapeLike(query.substring(0, Math.min(query.length(), 2048))) + "%");
        }
        StringBuilder where = new StringBuilder("1=1");
        if (!fts.isEmpty()) {
            long[] state = DeviceEventSearch.state(getReadableDatabase());
            where.append(" AND id IN (").append(ftsUnion("device_event_search", ftsGroups.size())); args.addAll(ftsGroups);
            if (state[2] == 0) {
                where.append(" UNION SELECT id FROM device_events WHERE id>? AND id<=? AND (").append(matches.length() == 0 ? "1=1" : matches).append(")");
                args.add(String.valueOf(state[0])); args.add(String.valueOf(state[1])); args.addAll(filters);
            }
            where.append(")");
        } else if (!words.isEmpty()) { where.append(" AND (").append(matches).append(")"); args.addAll(filters); }
        if (fromInclusive > 0) { where.append(" AND observed_at >= ?"); args.add(String.valueOf(fromInclusive)); }
        if (toExclusive > 0) { where.append(" AND observed_at < ?"); args.add(String.valueOf(toExclusive)); }
        args.add(String.valueOf(boundedLimit(limit, 500)));
        JSONArray rows = queryRows(getReadableDatabase(), "SELECT id,category,source,observed_at,recorded_at,content_hash,substr(payload,1,4096) AS payloadExcerpt,"
                + "length(payload) AS payloadChars,(" + score + ") AS score FROM device_events WHERE " + where + " ORDER BY score DESC,id DESC LIMIT ?", args.toArray(new String[0]));
        for (int i = 0; i < rows.length(); i++) put(rows.optJSONObject(i), "candidateExcerpt", true);
        return rows;
    }

    private static String memoryCandidateProjection(String alias) {
        return alias + ".memory_key," + alias + ".kind,substr(" + alias + ".content,1,8192) AS content,length(" + alias + ".content) AS fullContentChars,"
                + alias + ".revision," + alias + ".created_at," + alias + ".updated_at," + alias + ".content_hash";
    }

    private static JSONObject enrichMemoryCandidate(SQLiteDatabase db, JSONObject memory) {
        String key = memory.optString("key"); int revision = memory.optInt("revision");
        JSONArray refs = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT source_type,source_id,source_hash,source_role,observed_at FROM memory_sources WHERE memory_key=? AND revision=? ORDER BY source_type,source_id LIMIT 128",
                new String[]{key, String.valueOf(revision)})) {
            while (cursor.moveToNext()) {
                JSONObject ref = new JSONObject(); put(ref, "type", cursor.getString(0)); put(ref, "id", cursor.getLong(1));
                put(ref, "hash", cursor.getString(2)); put(ref, "role", cursor.getString(3)); put(ref, "observedAt", cursor.getLong(4)); refs.put(ref);
            }
        }
        long count;
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM memory_sources WHERE memory_key=? AND revision=?", new String[]{key, String.valueOf(revision)})) { count=cursor.moveToFirst()?cursor.getLong(0):0; }
        put(memory, "sourceRefs", refs); put(memory, "sourceMessageIds", messageIds(refs)); put(memory, "sourceCount", count);
        put(memory, "sourceRefsTruncated", count > refs.length()); put(memory, "candidateExcerpt", true);
        JSONObject metadata = one(db, "SELECT metadata_json FROM memory_metadata WHERE memory_key=?", new String[]{key});
        JSONObject full = metadata == null ? new JSONObject() : metadata.optJSONObject("metadata");
        put(memory, "metadata", boundedMetadata(full)); put(memory, "metadataIsCandidateView", full.toString().length() > 16_384);
        return memory;
    }

    private static JSONObject boundedMetadata(JSONObject metadata) {
        if (metadata.toString().length() <= 16_384) return metadata;
        JSONObject bounded = new JSONObject(); int remaining = 16_384;
        for (String key : new String[]{"factKey", "pinned", "confidence", "validFrom", "validTo", "provenance", "evidenceState", "userConfirmed", "importance", "aliases", "entities", "topics", "retrievalText"}) {
            Object value = metadata.opt(key); if (value == null || value == JSONObject.NULL) continue;
            String text = canonical(value); if (text.length() > remaining) continue;
            put(bounded, key, value); remaining -= text.length();
        }
        return bounded;
    }

    private static String ftsQuery(List<String> words) {
        List<String> groups = ftsGroups(words); return groups.isEmpty() ? "" : groups.get(0);
    }

    private static List<String> ftsGroups(List<String> words) {
        List<String> groups = new ArrayList<>();
        for (String word : words) {
            List<String> parts = new ArrayList<>();
            for (String part : word.split("[^\\p{L}\\p{N}_]+")) if (!part.isEmpty()) parts.add("\"" + part.replace("\"", "\"\"") + "*\"");
            // Implicit conjunction works in both legacy Android and enhanced FTS parsers.
            if (!parts.isEmpty()) groups.add(String.join(" ", parts));
        }
        return groups;
    }

    private static String ftsScoredUnion(String table, int groups) {
        List<String> lanes = new ArrayList<>();
        for (int i = 0; i < groups; i++) lanes.add("SELECT docid,10 AS word_score FROM " + table + " WHERE " + table + " MATCH ?");
        // Count real prefix matches once per query word. LIKE substring hits are not equivalent.
        return "SELECT docid,SUM(word_score) AS term_score FROM (" + String.join(" UNION ALL ", lanes) + ") GROUP BY docid";
    }

    private static String ftsUnion(String table, int groups) {
        List<String> lanes = new ArrayList<>();
        for (int i = 0; i < groups; i++) lanes.add("SELECT docid FROM " + table + " WHERE " + table + " MATCH ?");
        // SQL UNION preserves every whitespace-term lane without FTS parser OR precedence.
        return String.join(" UNION ", lanes);
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
            for (String table : tables()) if (scalar(db, "SELECT COUNT(*) FROM " + table) > 100_000) throw new IllegalStateException("Use streaming backup for a large history");
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
        int version = snapshot == null ? -1 : snapshot.optInt("version", -1);
        if (version != 1 && version != SNAPSHOT_VERSION) throw new IllegalArgumentException("Unsupported backup version");
        Set<Long> messageIds = new HashSet<>();
        Map<String, JSONObject> currentMemories = new HashMap<>();
        Map<String, Map<Integer, JSONObject>> revisions = new HashMap<>();
        Set<Long> deviceIds = new HashSet<>();
        for (String table : snapshotTables(version)) {
            JSONArray rows = snapshot.optJSONArray(table);
            if (rows == null) throw new IllegalArgumentException("Missing backup table: " + table);
            Set<String> primaryKeys = new HashSet<>();
            Set<String> externalIds = new HashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) throw new IllegalArgumentException("Malformed backup row");
                snapshotValues(table, row);
                String primaryKey = table.equals("memories") || table.equals("settings") || table.equals("memory_metadata") ? row.optString("key") : String.valueOf(row.optLong("id"));
                if (!primaryKeys.add(primaryKey)) throw new IllegalArgumentException("Duplicate backup row key");
                if (table.equals("messages") && !messageIds.add(row.optLong("id"))) throw new IllegalArgumentException("Duplicate message id");
                if (table.equals("device_events")) deviceIds.add(row.optLong("id"));
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
                JSONArray refs = rows.optJSONObject(i).optJSONArray("sourceRefs");
                if (refs != null) for (int j = 0; j < refs.length(); j++) {
                    JSONObject ref = refs.optJSONObject(j);
                    if (ref == null || !("message".equals(ref.optString("type")) ? messageIds.contains(ref.optLong("id"))
                            : "device_event".equals(ref.optString("type")) && deviceIds.contains(ref.optLong("id")))) {
                        throw new IllegalArgumentException("Backup memory references an unknown typed source");
                    }
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
            for (String table : snapshotTables(snapshot.optInt("version"))) {
                JSONArray rows = snapshot.optJSONArray(table);
                for (int i = 0; i < rows.length(); i++) db.insertOrThrow(table, null, snapshotValues(table, rows.optJSONObject(i)));
            }
            validateDatabase(db);
            rebuildDerivedIndexes(db);
            rebuildLargeOriginalSearch(db);
            quarantineRestoredRequests(db);
            putSetting("autonomy.enabled", "false");
            putSetting("runtime.restoreReviewRequired", "true");
            if (fileRestoreToken != null) {
                ContentValues marker = new ContentValues();
                marker.put("setting_key", FILE_RESTORE_TOKEN_KEY);
                marker.put("value", fileRestoreToken);
                if (db.insertWithOnConflict("settings", null, marker, SQLiteDatabase.CONFLICT_REPLACE) == -1) throw new IllegalStateException("Restore commit marker could not be saved");
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
            while (cursor.moveToNext()) rows.put(cursorRow(cursor));
        }
        return rows;
    }

    private static JSONObject cursorRow(Cursor cursor) {
        JSONObject row = new JSONObject();
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            String column = cursor.getColumnName(i); Object value;
            if (cursor.isNull(i)) value = JSONObject.NULL;
            else if (cursor.getType(i) == Cursor.FIELD_TYPE_INTEGER) value = cursor.getLong(i);
            else if (cursor.getType(i) == Cursor.FIELD_TYPE_FLOAT) value = cursor.getDouble(i);
            else value = cursor.getString(i);
            try {
                if (column.equals("source_ids") || column.equals("source_refs")) {
                    value = new JSONArray(String.valueOf(value));
                    column = column.equals("source_ids") ? "sourceMessageIds" : "sourceRefs";
                } else if (column.equals("memory_key") || column.equals("setting_key")) column = "key";
                else if (column.equals("payload") || column.equals("metadata_json")) {
                    value = new JSONObject(String.valueOf(value));
                    if (column.equals("metadata_json")) column = "metadata";
                }
            } catch (JSONException corrupt) { throw new IllegalStateException("Corrupt authoritative JSON field: " + column, corrupt); }
            put(row, column, value);
        }
        if (row.has("role") && row.has("text")) put(row, "textIsExcerpt", row.optLong("raw_char_count", row.optString("text").length()) > row.optString("text").length());
        return row;
    }

    private long scalarLong(String sql, String[] args) {
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) { return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0; }
    }

    private static String hash(String value) { return OriginalTextFiles.hex(OriginalTextFiles.digest().digest(value.getBytes(StandardCharsets.UTF_8))); }

    private static String messageHash(JSONObject message) {
        JSONObject original = new JSONObject();
        for (String key : new String[]{"id", "role", "text", "request_id", "created_at", "raw_file_id", "raw_char_count", "raw_byte_count", "raw_content_hash"}) {
            put(original, key, message.has(key) ? message.opt(key) : JSONObject.NULL);
        }
        return hash(canonical(original));
    }

    private static String deviceHash(String category, String source, JSONObject payload, long observedAt) {
        JSONObject original = new JSONObject(); put(original, "category", category); put(original, "source", source);
        put(original, "payload", payload); put(original, "observed_at", observedAt);
        return hash(canonical(original));
    }

    private static String canonical(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; List<String> keys = new ArrayList<>();
            java.util.Iterator<String> iterator = object.keys(); while (iterator.hasNext()) keys.add(iterator.next()); Collections.sort(keys);
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) { if (result.length() > 1) result.append(','); result.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key))); }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) { if (i > 0) result.append(','); result.append(canonical(array.opt(i))); }
            return result.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }

    private static void replaceMessageSearch(SQLiteDatabase db, long id, String text) {
        db.delete("message_search", "docid = ?", new String[]{String.valueOf(id)});
        ContentValues value = new ContentValues(); value.put("docid", id); value.put("text", text);
        db.insertOrThrow("message_search", null, value);
    }

    private static JSONArray normalizeReferences(SQLiteDatabase db, JSONArray references) {
        JSONArray result = new JSONArray(); Set<String> seen = new HashSet<>();
        if (references == null) return result;
        if (references.length() > 10_000) throw new IllegalArgumentException("Too many memory sources");
        for (int i = 0; i < references.length(); i++) {
            JSONObject reference = references.optJSONObject(i);
            if (reference == null) throw new IllegalArgumentException("Source references must be objects");
            String type = reference.optString("type"); long id = reference.optLong("id", -1);
            Object rawId = reference.opt("id");
            if (!(rawId instanceof Number) || ((Number) rawId).doubleValue() != id || id <= 0
                    || !(type.equals("message") || type.equals("device_event"))) throw new IllegalArgumentException("Invalid typed source reference");
            if (!seen.add(type + ":" + id)) continue;
            String table = type.equals("message") ? "messages" : "device_events";
            JSONObject source = one(db, "SELECT * FROM " + table + " WHERE id = ?", new String[]{String.valueOf(id)});
            if (source == null) throw new IllegalArgumentException("Unknown original source: " + type + ":" + id);
            String actual = type.equals("message") ? messageHash(source) : deviceHash(source.optString("category"), source.optString("source"), source.optJSONObject("payload"), source.optLong("observed_at"));
            if (!actual.equals(source.optString("content_hash"))) throw new IllegalStateException("Original source hash does not match its stored content");
            if (reference.has("hash") && !actual.equals(reference.optString("hash"))) throw new IllegalArgumentException("Reference hash does not match the original");
            JSONObject verified = new JSONObject(); put(verified, "type", type); put(verified, "id", id); put(verified, "hash", actual);
            put(verified, "role", type.equals("message") ? source.optString("role") : "observed_device_event");
            put(verified, "observedAt", source.optLong(type.equals("message") ? "created_at" : "observed_at"));
            if (type.equals("message")) put(verified, "bodyHash", source.optString("raw_content_hash"));
            else put(verified, "source", source.optString("source"));
            result.put(verified);
        }
        return result;
    }

    private static JSONArray compactReferences(JSONArray references) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < references.length(); i++) {
            JSONObject source = references.optJSONObject(i), compact = new JSONObject();
            put(compact, "type", source.optString("type")); put(compact, "id", source.optLong("id")); result.put(compact);
        }
        return result;
    }

    private static JSONArray messageIds(JSONArray references) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < references.length(); i++) {
            JSONObject source = references.optJSONObject(i); if ("message".equals(source.optString("type"))) result.put(source.optLong("id"));
        }
        return result;
    }

    private static JSONArray legacyReferences(JSONArray messageIds) {
        JSONArray result = new JSONArray();
        if (messageIds != null) for (int i = 0; i < messageIds.length(); i++) {
            JSONObject ref = new JSONObject(); put(ref, "type", "message"); put(ref, "id", messageIds.optLong(i)); result.put(ref);
        }
        return result;
    }

    private static void insertSourceLinks(SQLiteDatabase db, String key, int revision, JSONArray references) {
        for (int i = 0; i < references.length(); i++) {
            JSONObject source = references.optJSONObject(i); ContentValues row = new ContentValues();
            row.put("memory_key", key); row.put("revision", revision); row.put("source_type", source.optString("type"));
            row.put("source_id", source.optLong("id")); row.put("source_hash", source.optString("hash"));
            row.put("source_role", source.optString("role")); row.put("observed_at", source.optLong("observedAt"));
            db.insertOrThrow("memory_sources", null, row);
        }
    }

    private static JSONArray sourceLinks(SQLiteDatabase db, String key, int revision) {
        JSONArray result = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT * FROM memory_sources WHERE memory_key = ? AND revision = ? ORDER BY source_type, source_id",
                new String[]{key, String.valueOf(revision)})) {
            while (cursor.moveToNext()) {
                JSONObject row = cursorRow(cursor), ref = new JSONObject();
                put(ref, "type", row.optString("source_type")); put(ref, "id", row.optLong("source_id")); put(ref, "hash", row.optString("source_hash"));
                put(ref, "role", row.optString("source_role")); put(ref, "observedAt", row.optLong("observed_at")); result.put(ref);
            }
        }
        return result;
    }

    private static JSONObject enrichMemory(SQLiteDatabase db, JSONObject memory) {
        if (memory == null) return null;
        String key = memory.optString("key");
        put(memory, "sourceRefs", sourceLinks(db, key, memory.optInt("revision")));
        JSONObject metadata = one(db, "SELECT metadata_json FROM memory_metadata WHERE memory_key = ?", new String[]{key});
        put(memory, "metadata", metadata == null ? new JSONObject() : metadata.optJSONObject("metadata"));
        return memory;
    }

    private static void saveMetadata(SQLiteDatabase db, String key, JSONObject metadata, int memoryRevision, boolean carryForward) {
        JSONObject previous = one(db, "SELECT metadata_json, metadata_revision, memory_revision FROM memory_metadata WHERE memory_key = ?", new String[]{key});
        if (!carryForward && previous != null && canonical(metadata).equals(canonical(previous.optJSONObject("metadata")))) return;
        int revision = previous == null ? 1 : previous.optInt("metadata_revision") + 1;
        long now = System.currentTimeMillis(); ContentValues row = new ContentValues();
        row.put("memory_key", key); row.put("metadata_json", metadata.toString()); row.put("retrieval_text", retrievalText(metadata));
        row.put("pinned", metadata.optBoolean("pinned", false) ? 1 : 0);
        Object confidence = metadata.opt("confidence");
        if (confidence instanceof Number) {
            double value = ((Number) confidence).doubleValue();
            if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Confidence must be between zero and one");
            row.put("confidence", value);
        } else row.putNull("confidence");
        optionalTimestamp(row, "valid_from", metadata.opt("validFrom")); optionalTimestamp(row, "valid_to", metadata.opt("validTo"));
        row.put("metadata_revision", revision); row.put("memory_revision", memoryRevision); row.put("updated_at", now);
        if (previous == null) db.insertOrThrow("memory_metadata", null, row);
        else if (db.update("memory_metadata", row, "memory_key = ?", new String[]{key}) != 1) throw new IllegalStateException("Metadata update failed");
        ContentValues historic = new ContentValues(row); historic.remove("updated_at"); historic.put("created_at", now);
        db.insertOrThrow("memory_metadata_revisions", null, historic);
    }

    private static void optionalTimestamp(ContentValues row, String key, Object value) {
        if (value instanceof Number && ((Number) value).longValue() >= 0) row.put(key, ((Number) value).longValue()); else row.putNull(key);
    }

    private static String retrievalText(JSONObject metadata) {
        StringBuilder text = new StringBuilder();
        for (String key : new String[]{"retrievalText", "aliases", "entities", "tags", "keywords", "topics"}) {
            Object value = metadata.opt(key); if (value != null && value != JSONObject.NULL) text.append(value instanceof String ? value : canonical(value)).append('\n');
        }
        return text.substring(0, Math.min(text.length(), MAX_TEXT_CHARS));
    }

    private static void rebuildMemorySearchRow(SQLiteDatabase db, String key) {
        db.delete("memory_search", "memory_key = ?", new String[]{key});
        db.execSQL("INSERT INTO memory_search(docid, memory_key, content, retrieval_text) "
                + "SELECT m.rowid, m.memory_key, m.content, COALESCE(x.retrieval_text,'') FROM memories m "
                + "LEFT JOIN memory_metadata x ON x.memory_key=m.memory_key WHERE m.memory_key = ?", new Object[]{key});
    }

    private static void updateMemoryHash(SQLiteDatabase db, String key) {
        JSONObject document = enrichMemory(db, one(db, "SELECT * FROM memories WHERE memory_key = ?", new String[]{key}));
        if (document == null) return;
        JSONObject effective = new JSONObject();
        for (String field : new String[]{"key", "content", "kind", "sourceRefs", "metadata"}) put(effective, field, document.opt(field));
        ContentValues row = new ContentValues(); row.put("content_hash", hash(canonical(effective)));
        db.update("memories", row, "memory_key = ?", new String[]{key});
    }

    private static void rebuildDerivedIndexes(SQLiteDatabase db) {
        try{rebuildDerivedIndexes(db,null);}
        catch(IOException failure){throw new IllegalStateException("Search index rebuild failed",failure);}
    }

    private static void rebuildDerivedIndexes(SQLiteDatabase db,BackupPolicy.Session space)throws IOException {
        if(space!=null)space.checkpoint("restore_index_rebuild","message_search",64L*1024);
        db.delete("message_search", null, null);
        if(space==null)db.execSQL("INSERT INTO message_search(docid,text) SELECT id,text FROM messages");
        else{
            long after=0;
            while(true){
                long upper;
                try(Cursor row=db.rawQuery("SELECT MAX(id) FROM (SELECT id FROM messages WHERE id>? ORDER BY id LIMIT 512)",new String[]{String.valueOf(after)})){
                    upper=row.moveToFirst()&&!row.isNull(0)?row.getLong(0):0;
                }
                if(upper<=after)break;
                space.checkpoint("restore_index_rebuild","message_search",64L*1024);
                db.execSQL("INSERT INTO message_search(docid,text) SELECT id,text FROM messages WHERE id>? AND id<=?",new Object[]{after,upper});
                after=upper;
            }
        }
        db.delete("memory_search", null, null); db.delete("memory_sources", null, null); db.delete("semantic_vectors", null, null);
        if(space!=null)space.checkpoint("restore_index_rebuild","device_event_search",64L*1024);
        DeviceEventSearch.rebuild(db,space);
        for (String cache : new String[]{"semantic_chunks", "semantic_chunk_progress", "semantic_index_progress"}) {
            if (one(db, "SELECT name FROM sqlite_master WHERE type='table' AND name=?", new String[]{cache}) != null) db.delete(cache, null, null);
        }
        try (Cursor cursor = db.rawQuery("SELECT * FROM memory_revisions ORDER BY memory_key, revision", null)) {
            int visited=0;
            while (cursor.moveToNext()) {
                if(space!=null&&visited++%64==0)space.checkpoint("restore_index_rebuild","memory_sources",64L*1024);
                JSONObject history = cursorRow(cursor); JSONArray references = history.optJSONArray("sourceRefs");
                if (references == null || references.length() == 0) references = legacyReferences(history.optJSONArray("sourceMessageIds"));
                JSONArray verified = normalizeReferences(db, references);
                insertSourceLinks(db, history.optString("key"), history.optInt("revision"), verified);
                ContentValues row = new ContentValues(); row.put("source_refs", compactReferences(verified).toString());
                db.update("memory_revisions", row, "id = ?", new String[]{String.valueOf(history.optLong("id"))});
            }
        }
        try (Cursor cursor = db.rawQuery("SELECT memory_key, revision FROM memories", null)) {
            int visited=0;
            while (cursor.moveToNext()) {
                if(space!=null&&visited++%64==0)space.checkpoint("restore_index_rebuild","memory_search",64L*1024);
                String key = cursor.getString(0); JSONArray references = sourceLinks(db, key, cursor.getInt(1));
                ContentValues row = new ContentValues(); row.put("source_refs", compactReferences(references).toString());
                db.update("memories", row, "memory_key = ?", new String[]{key});
                updateMemoryHash(db, key); rebuildMemorySearchRow(db, key);
            }
        }
    }

    public synchronized JSONObject rebuildSearchIndexes() {
        return withTransaction(db -> {
            rebuildDerivedIndexes(db);
            long largeOriginals = rebuildLargeOriginalSearch(db);
            JSONObject result = new JSONObject(); put(result, "messageCount", messageCount()); put(result, "largeOriginalsIndexed", largeOriginals);
            put(result, "memoryCount", scalar(db, "SELECT COUNT(*) FROM memories")); put(result, "sourceLinks", scalar(db, "SELECT COUNT(*) FROM memory_sources"));
            return result;
        });
    }

    private long rebuildLargeOriginalSearch(SQLiteDatabase db) {
        try{return rebuildLargeOriginalSearch(db,null);}
        catch(IOException failure){throw new IllegalStateException("Cannot rebuild an unavailable original",failure);}
    }

    private long rebuildLargeOriginalSearch(SQLiteDatabase db,BackupPolicy.Session space)throws IOException {
        long count = 0;
        try (Cursor cursor = db.rawQuery("SELECT id,raw_file_id,raw_content_hash,raw_byte_count FROM messages WHERE raw_file_id IS NOT NULL", null)) {
            while (cursor.moveToNext()) {
                try {
                    if(space!=null)space.checkpoint("restore_index_rebuild","originals/"+cursor.getString(1),cursor.getLong(3)*2);
                    originalFiles.verify(cursor.getString(1), cursor.getString(2), cursor.getLong(3));
                    replaceMessageSearch(db, cursor.getLong(0), originalFiles.readEntire(cursor.getString(1)));
                } catch (BackupFailure failure){throw failure;}
                catch (IOException missing) { throw new IllegalStateException("Cannot rebuild an unavailable original", missing); }
                count++;
            }
        }
        return count;
    }

    private static long scalar(SQLiteDatabase db, String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) { return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0; }
    }

    static void validateDatabase(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("PRAGMA quick_check", null)) {
            if (!cursor.moveToFirst() || !"ok".equals(cursor.getString(0))) throw new IllegalStateException("SQLite integrity check failed");
        }
        try (Cursor cursor = db.rawQuery("PRAGMA foreign_key_check", null)) {
            if (cursor.moveToFirst()) throw new IllegalStateException("Foreign-key integrity check failed");
        }
        try (Cursor cursor = db.rawQuery("SELECT * FROM messages ORDER BY id", null)) {
            while (cursor.moveToNext()) {
                JSONObject original = cursorRow(cursor);
                if (!messageHash(original).equals(original.optString("content_hash"))) throw new IllegalStateException("Original message hash mismatch");
                if (original.isNull("raw_file_id") && !hash(original.optString("text")).equals(original.optString("raw_content_hash"))) throw new IllegalStateException("Original body hash mismatch");
            }
        }
        try (Cursor cursor = db.rawQuery("SELECT * FROM device_events ORDER BY id", null)) {
            while (cursor.moveToNext()) {
                JSONObject original = cursorRow(cursor);
                if (!deviceHash(original.optString("category"), original.optString("source"), original.optJSONObject("payload"), original.optLong("observed_at"))
                        .equals(original.optString("content_hash"))) throw new IllegalStateException("Device observation hash mismatch");
            }
        }
        if (one(db, "SELECT m.memory_key FROM memories m LEFT JOIN memory_revisions r ON r.memory_key=m.memory_key AND r.revision=m.revision "
                + "WHERE r.id IS NULL OR m.content!=r.content OR m.kind!=r.kind OR m.source_ids!=r.source_ids OR m.source_refs!=r.source_refs LIMIT 1", null) != null) {
            throw new IllegalStateException("Current memory differs from its source-backed history");
        }
        if (one(db, "SELECT r.memory_key FROM memory_revisions r LEFT JOIN memories m ON m.memory_key=r.memory_key "
                + "GROUP BY r.memory_key HAVING m.memory_key IS NULL OR MIN(r.revision)!=1 OR MAX(r.revision)!=COUNT(*) OR MAX(r.revision)!=m.revision LIMIT 1", null) != null) {
            throw new IllegalStateException("Memory revision history is incomplete");
        }
        try (Cursor cursor = db.rawQuery("SELECT source_ids,source_refs FROM memory_revisions", null)) {
            while (cursor.moveToNext()) {
                JSONArray ids; JSONArray references;
                try { ids = new JSONArray(cursor.getString(0)); references = new JSONArray(cursor.getString(1)); }
                catch (JSONException invalid) { throw new IllegalStateException("Invalid source references", invalid); }
                if (references.length() == 0) references = legacyReferences(ids);
                JSONArray verified = normalizeReferences(db, references);
                if (!new HashSet<>(arrayLongs(ids)).equals(new HashSet<>(arrayLongs(messageIds(verified))))) throw new IllegalStateException("Typed references contradict legacy message provenance");
            }
        }
        if (one(db, "SELECT x.memory_key FROM memory_metadata x LEFT JOIN memories m ON m.memory_key=x.memory_key "
                + "LEFT JOIN memory_metadata_revisions r ON r.memory_key=x.memory_key AND r.metadata_revision=x.metadata_revision "
                + "WHERE m.memory_key IS NULL OR r.id IS NULL OR x.metadata_json!=r.metadata_json OR x.memory_revision!=m.revision OR x.memory_revision!=r.memory_revision LIMIT 1", null) != null) {
            throw new IllegalStateException("Current metadata differs from its version history");
        }
        if (one(db, "SELECT x.memory_key FROM memory_metadata_revisions x LEFT JOIN memory_metadata m ON m.memory_key=x.memory_key "
                + "LEFT JOIN memory_revisions r ON r.memory_key=x.memory_key AND r.revision=x.memory_revision "
                + "GROUP BY x.memory_key HAVING m.memory_key IS NULL OR MIN(x.metadata_revision)!=1 OR MAX(x.metadata_revision)!=COUNT(*) "
                + "OR MAX(x.metadata_revision)!=m.metadata_revision OR COUNT(r.id)!=COUNT(*) LIMIT 1", null) != null) throw new IllegalStateException("Metadata history is incomplete");
    }

    private static List<Long> arrayLongs(JSONArray array) {
        List<Long> result = new ArrayList<>(); for (int i = 0; i < array.length(); i++) result.add(array.optLong(i)); return result;
    }

    public synchronized JSONObject verifyIntegrity() {
        validateDatabase(getReadableDatabase());
        try { validateOriginalFiles(getReadableDatabase(), originalFiles); }
        catch (IOException changed) { throw new IllegalStateException("Exact original files failed integrity verification", changed); }
        JSONObject result = new JSONObject(); put(result, "ok", true); put(result, "messages", messageCount());
        put(result, "memories", memoryCount()); put(result, "deviceEvents", scalarLong("SELECT COUNT(*) FROM device_events", null));
        put(result, "sourceIntegrity", "Stored original hashes and complete revision/source links verified; factual truth is separate");
        return result;
    }

    void validateOriginalFiles(File databaseFile, File root) throws IOException {
        OriginalTextFiles originals = new OriginalTextFiles(root);
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(databaseFile.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            validateOriginalFiles(database, originals);
        } catch (RuntimeException invalid) { throw new IOException("Original file validation failed", invalid); }
    }

    private static void validateOriginalFiles(SQLiteDatabase database, OriginalTextFiles originals) throws IOException {
        for (String table : new String[]{"messages", "requests"}) {
            String prefix = table.equals("messages") ? "raw_" : "raw_result_";
            String hashColumn = table.equals("messages") ? "raw_content_hash" : "raw_result_hash";
            String textColumn = table.equals("messages") ? "text" : "result";
            try (Cursor cursor = database.rawQuery("SELECT " + prefix + "file_id," + prefix + "byte_count," + prefix + "char_count," + hashColumn
                    + "," + textColumn + " FROM " + table + " WHERE " + prefix + "file_id IS NOT NULL", null)) {
                while (cursor.moveToNext()) {
                    String fileId = cursor.getString(0); originals.verify(fileId, cursor.getString(3), cursor.getLong(1));
                    String preview = cursor.isNull(4) ? "" : cursor.getString(4);
                    if (!preview.equals(originals.read(fileId, 0, preview.length()))) throw new IOException("Source preview differs from the exact original");
                    long characters = 0;
                    try (InputStreamReader reader = new InputStreamReader(new java.io.FileInputStream(originals.file(fileId)), StandardCharsets.UTF_8)) {
                        char[] buffer = new char[16_384]; int read;
                        while ((read = reader.read(buffer)) != -1) {
                            characters += read; if (characters > OriginalTextFiles.MAX_ORIGINAL_CHARS) throw new IOException("Original character limit exceeded");
                        }
                    }
                    if (characters != cursor.getLong(2)) throw new IOException("Original character count mismatch");
                }
            }
        }
    }

    /** Streaming authoritative tables; no table-size array or whole-history string is built. */
    synchronized long writeTable(String table, OutputStream destination) throws IOException {
        return writeTable(getReadableDatabase(), table, destination);
    }

    static long writeTable(SQLiteDatabase database, String table, OutputStream destination) throws IOException {
        requireTable(table); long rows = 0;
        try (Cursor cursor = database.rawQuery("SELECT * FROM " + table, null)) {
            while (cursor.moveToNext()) {
                byte[] line = (cursorRow(cursor).toString() + "\n").getBytes(StandardCharsets.UTF_8);
                if (line.length > 8 * 1024 * 1024) throw new IOException("An authoritative row exceeds the streaming row bound");
                destination.write(line); rows++;
            }
        }
        return rows;
    }

    static long readTable(SQLiteDatabase db, String table, InputStream source) throws IOException {
        return readTable(db,table,source,null);
    }

    static long readTable(SQLiteDatabase db, String table, InputStream source,BackupPolicy.Session space) throws IOException {
        requireTable(table); long rows = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8), 16_384)) {
            String line;
            while ((line = boundedLine(reader, 8 * 1024 * 1024)) != null) {
                if (line.isEmpty()) throw new IOException("Empty authoritative row");
                checkJsonDepth(line);
                if(space!=null&&rows%64==0)space.checkpoint("restore_database_stage","store/"+table+".ndjson",Math.min(BackupPolicy.SPACE_RECHECK_BYTES,Math.max(64L*1024,line.length()*6L)));
                try {
                    JSONObject row = new JSONObject(line);
                    if ((table.equals("messages") || table.equals("memories") || table.equals("device_events")) && row.isNull("content_hash")) throw new IOException("A source hash is missing");
                    db.insertOrThrow(table, null, snapshotValues(table, row)); rows++;
                } catch (JSONException | RuntimeException invalid) { throw new IOException("Invalid authoritative row in " + table, invalid); }
            }
        }
        return rows;
    }

    static String boundedLine(BufferedReader reader, int maximum) throws IOException {
        StringBuilder line = new StringBuilder(); int value;
        while ((value = reader.read()) != -1) {
            if (value == '\n') return line.toString();
            if (line.length() >= maximum) throw new IOException("Streaming row exceeds its bound");
            line.append((char) value);
        }
        return line.length() == 0 ? null : line.toString();
    }

    static void checkJsonDepth(String text) throws IOException {
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
            } else if (value == '"') quoted = true;
            else if (value == '{' || value == '[') { if (++depth > 64) throw new IOException("Backup JSON nesting is too deep"); }
            else if (value == '}' || value == ']') depth--;
        }
    }

    private static void requireTable(String table) {
        for (String allowed : tables()) if (allowed.equals(table)) return;
        throw new IllegalArgumentException("Not an authoritative backup table");
    }

    synchronized void importStagedDatabase(File stagedDatabase, String fileRestoreToken) {
        try{importStagedDatabase(stagedDatabase,fileRestoreToken,null);}
        catch(IOException failure){throw new IllegalStateException("Backup database restore failed",failure);}
    }

    synchronized void importStagedDatabase(File stagedDatabase, String fileRestoreToken,BackupPolicy.Session space)throws IOException {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("ATTACH DATABASE ? AS restored_source", new Object[]{stagedDatabase.getPath()});
        try {
            db.beginTransaction();
            try {
                for (String table : tables()) {
                    if(space!=null)space.checkpoint("restore_commit",table,64L*1024);
                    db.delete(table, null, null);
                }
                for (String table : tables()) {
                    if(space==null)db.execSQL("INSERT INTO main." + table + " SELECT * FROM restored_source." + table);
                    else{
                        long after=0;
                        while(true){
                            long upper;
                            try(Cursor row=db.rawQuery("SELECT MAX(rowid) FROM (SELECT rowid FROM restored_source."+table+" WHERE rowid>? ORDER BY rowid LIMIT 512)",new String[]{String.valueOf(after)})){
                                upper=row.moveToFirst()&&!row.isNull(0)?row.getLong(0):0;
                            }
                            if(upper<=after)break;
                            space.checkpoint("restore_commit",table,Math.min(BackupPolicy.SPACE_RECHECK_BYTES,Math.max(64L*1024,stagedDatabase.length())));
                            db.execSQL("INSERT INTO main."+table+" SELECT * FROM restored_source."+table+" WHERE rowid>? AND rowid<=?",new Object[]{after,upper});
                            after=upper;
                        }
                    }
                }
                if(space!=null)space.checkpoint("restore_index_rebuild","",Math.min(BackupPolicy.SPACE_RECHECK_BYTES,stagedDatabase.length()));
                validateDatabase(db); rebuildDerivedIndexes(db,space); rebuildLargeOriginalSearch(db,space); quarantineRestoredRequests(db);
                putSetting("autonomy.enabled", "false"); putSetting("runtime.restoreReviewRequired", "true");
                putSetting(FILE_RESTORE_TOKEN_KEY, fileRestoreToken);
                if(space!=null)space.checkpoint("restore_commit_finalize","",64L*1024);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } finally { db.execSQL("DETACH DATABASE restored_source"); }
    }

    static void populateSnapshotDatabase(SQLiteDatabase database, JSONObject snapshot) {
        for (String table : snapshotTables(snapshot.optInt("version"))) {
            JSONArray rows = snapshot.optJSONArray(table);
            if (rows == null) throw new IllegalArgumentException("Missing authoritative legacy table");
            for (int i = 0; i < rows.length(); i++) database.insertOrThrow(table, null, snapshotValues(table, rows.optJSONObject(i)));
        }
        validateDatabase(database);
    }

    static ContentValues snapshotValues(String table, JSONObject row) {
        ContentValues value = new ContentValues();
        if (table.equals("messages") || table.equals("requests") || table.equals("memory_revisions") || table.equals("events")
                || table.equals("device_events") || table.equals("memory_metadata_revisions")) {
            long id = row.optLong("id", -1);
            if (id <= 0) throw new IllegalArgumentException("Invalid backup row id");
            value.put("id", id);
        }
        if (!(table.equals("settings") || table.equals("device_events") || table.equals("memory_metadata"))) value.put("created_at", nonnegative(row, "created_at"));
        switch (table) {
            case "messages":
                value.put("role", required(row.optString("role", null), "role", 64));
                value.put("text", required(row.optString("text", null), "text", MAX_TEXT_CHARS));
                if (!row.isNull("request_id")) value.put("request_id", required(row.optString("request_id", null), "request_id", 512));
                String rawId = row.optString("raw_file_id", "");
                if (!row.isNull("raw_file_id") && !rawId.isEmpty()) {
                    if (!rawId.matches("(?:[a-fA-F0-9-]{36}|[a-fA-F0-9]{64})\\.txt")) throw new IllegalArgumentException("Invalid original file id");
                    value.put("raw_file_id", rawId); value.put("raw_char_count", nonnegative(row, "raw_char_count"));
                    value.put("raw_byte_count", nonnegative(row, "raw_byte_count")); value.put("raw_content_hash", requiredHash(row.optString("raw_content_hash")));
                } else {
                    value.putNull("raw_file_id"); value.put("raw_char_count", value.getAsString("text").length());
                    value.put("raw_byte_count", value.getAsString("text").getBytes(StandardCharsets.UTF_8).length);
                    value.put("raw_content_hash", hash(value.getAsString("text")));
                    if (row.has("raw_content_hash") && !row.isNull("raw_content_hash") && !value.getAsString("raw_content_hash").equals(row.optString("raw_content_hash"))) throw new IllegalArgumentException("Original body hash mismatch");
                }
                value.put("content_hash", row.has("content_hash") && !row.isNull("content_hash")
                        ? requiredHash(row.optString("content_hash")) : messageHash(valuesObject(value)));
                break;
            case "requests":
                value.put("external_id", required(row.optString("external_id", null), "external_id", 512));
                value.put("text", required(row.optString("text", null), "text", MAX_TEXT_CHARS));
                value.put("kind", required(row.optString("kind", null), "kind", 128));
                String status = row.optString("status", null); requireStatus(status); value.put("status", status);
                if (!row.isNull("result")) value.put("result", boundedOptional(row.optString("result", null), "result", MAX_TEXT_CHARS));
                if (!row.isNull("raw_result_file_id") && !row.optString("raw_result_file_id").isEmpty()) {
                    String resultId = row.optString("raw_result_file_id");
                    if (!resultId.matches("(?:[a-fA-F0-9-]{36}|[a-fA-F0-9]{64})\\.txt")) throw new IllegalArgumentException("Invalid result original id");
                    value.put("raw_result_file_id", resultId); value.put("raw_result_char_count", nonnegative(row, "raw_result_char_count"));
                    value.put("raw_result_byte_count", nonnegative(row, "raw_result_byte_count")); value.put("raw_result_hash", requiredHash(row.optString("raw_result_hash")));
                } else if (!row.isNull("result")) {
                    value.put("raw_result_char_count", row.optString("result").length()); value.put("raw_result_byte_count", row.optString("result").getBytes(StandardCharsets.UTF_8).length);
                    value.put("raw_result_hash", hash(row.optString("result")));
                }
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
                JSONArray refs = row.optJSONArray("sourceRefs");
                value.put("source_refs", (refs == null ? legacyReferences(sources) : refs).toString());
                int revision = row.optInt("revision", 0);
                if (revision < 1) throw new IllegalArgumentException("Invalid memory revision");
                value.put("revision", revision);
                if (table.equals("memories")) {
                    value.put("updated_at", nonnegative(row, "updated_at"));
                    if (!row.isNull("content_hash") && row.has("content_hash")) value.put("content_hash", requiredHash(row.optString("content_hash")));
                }
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
            case "device_events":
                value.put("category", required(row.optString("category"), "category", 128)); value.put("source", required(row.optString("source"), "source", 256));
                JSONObject observation = row.optJSONObject("payload");
                if (observation == null || observation.toString().length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("Invalid device observation");
                value.put("payload", observation.toString()); value.put("observed_at", nonnegative(row, "observed_at")); value.put("recorded_at", nonnegative(row, "recorded_at"));
                if (!row.isNull("dedupe_key")) value.put("dedupe_key", required(row.optString("dedupe_key"), "dedupe_key", 512));
                value.put("content_hash", requiredHash(row.optString("content_hash")));
                break;
            case "memory_metadata":
            case "memory_metadata_revisions":
                value.put("memory_key", required(row.optString("key"), "key", 512));
                JSONObject metadata = row.optJSONObject("metadata");
                if (metadata == null || metadata.toString().length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("Invalid memory metadata");
                value.put("metadata_json", metadata.toString()); value.put("retrieval_text", retrievalText(metadata));
                value.put("pinned", metadata.optBoolean("pinned", false) ? 1 : 0);
                Object confidence = metadata.opt("confidence");
                if (confidence instanceof Number) {
                    double confidenceValue = ((Number) confidence).doubleValue();
                    if (!Double.isFinite(confidenceValue) || confidenceValue < 0 || confidenceValue > 1) throw new IllegalArgumentException("Invalid metadata confidence");
                    value.put("confidence", confidenceValue);
                }
                optionalTimestamp(value, "valid_from", metadata.opt("validFrom")); optionalTimestamp(value, "valid_to", metadata.opt("validTo"));
                if (row.optInt("metadata_revision") < 1 || row.optInt("memory_revision") < 1) throw new IllegalArgumentException("Invalid metadata revision");
                value.put("metadata_revision", row.optInt("metadata_revision")); value.put("memory_revision", row.optInt("memory_revision"));
                if (table.equals("memory_metadata")) value.put("updated_at", nonnegative(row, "updated_at"));
                break;
            default: throw new IllegalArgumentException("Unknown backup table");
        }
        return value;
    }

    private static JSONObject valuesObject(ContentValues values) {
        JSONObject result = new JSONObject();
        for (Map.Entry<String, Object> entry : values.valueSet()) put(result, entry.getKey(), entry.getValue() == null ? JSONObject.NULL : entry.getValue());
        return result;
    }

    private static String requiredHash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid SHA-256 value"); return value;
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

    static String[] tables() { return new String[]{"messages", "requests", "memories", "memory_revisions", "settings", "events", "device_events", "memory_metadata", "memory_metadata_revisions"}; }
    private static String[] snapshotTables(int version) {
        return version == 1 ? new String[]{"messages", "requests", "memories", "memory_revisions", "settings", "events"} : tables();
    }
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
