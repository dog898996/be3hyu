package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.zip.*;

import static org.junit.Assert.*;

/** Synthetic long histories; no account, device or personal data is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class LongTermStorageTest {
    private Context context;
    private HoyoungStore store;
    private InternalFiles files;

    @Before public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        deleteTree(context.getFilesDir());
        assertTrue(context.getFilesDir().isDirectory() || context.getFilesDir().mkdirs());
        store = new HoyoungStore(context);
        files = new InternalFiles(context);
    }

    @After public void tearDown() throws Exception {
        store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        deleteTree(context.getFilesDir());
    }

    @Test public void keyedMemoryCandidateBoundsPayloadWithoutLosingAuthoritativeContentOrProvenance() throws Exception {
        JSONArray sourceIds = new JSONArray();
        for (int i = 0; i < 130; i++) sourceIds.put(store.addMessage("user", "Synthetic evidence " + i, null).getLong("id"));
        String complete = repeat('x', 12_000) + " original tail evidence";
        store.upsertMemory("large-keyed-candidate", complete, "fact", sourceIds);
        JSONObject candidate = store.getMemoryCandidate("large-keyed-candidate");
        assertEquals(8192, candidate.getString("content").length());
        assertEquals(complete.length(), candidate.getInt("fullContentChars"));
        assertTrue(candidate.getBoolean("candidateExcerpt"));
        assertEquals(128, candidate.getJSONArray("sourceRefs").length());
        assertEquals(130, candidate.getInt("sourceCount"));
        assertTrue(candidate.getBoolean("sourceRefsTruncated"));
        JSONObject original = store.getMemory("large-keyed-candidate");
        assertEquals(complete, original.getString("content"));
        assertEquals(130, original.getJSONArray("sourceRefs").length());
        assertEquals(original.getString("content_hash"), candidate.getString("content_hash"));
        assertNull(store.getMemoryCandidate("unknown-key"));
    }

    @Test public void versionOneMigrationPreservesOriginalsCorrectionHistoryAndLegacyProvenance() throws Exception {
        store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        try (SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(HoyoungStore.DATABASE_NAME), null)) {
            legacy.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,role TEXT NOT NULL,text TEXT NOT NULL,request_id TEXT,created_at INTEGER NOT NULL)");
            legacy.execSQL("CREATE TABLE requests(id INTEGER PRIMARY KEY AUTOINCREMENT,external_id TEXT NOT NULL UNIQUE,text TEXT NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL,result TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
            legacy.execSQL("CREATE INDEX requests_status_created ON requests(status,created_at,id)");
            legacy.execSQL("CREATE TABLE memories(memory_key TEXT PRIMARY KEY,content TEXT NOT NULL,kind TEXT NOT NULL,source_ids TEXT NOT NULL,revision INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
            legacy.execSQL("CREATE TABLE memory_revisions(id INTEGER PRIMARY KEY AUTOINCREMENT,memory_key TEXT NOT NULL,content TEXT NOT NULL,kind TEXT NOT NULL,source_ids TEXT NOT NULL,revision INTEGER NOT NULL,created_at INTEGER NOT NULL,UNIQUE(memory_key,revision))");
            legacy.execSQL("CREATE TABLE settings(setting_key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            legacy.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT,type TEXT NOT NULL,payload TEXT NOT NULL,created_at INTEGER NOT NULL)");
            legacy.execSQL("INSERT INTO messages(id,role,text,created_at) VALUES(1,'user','exact migrated original',10)");
            legacy.execSQL("INSERT INTO memory_revisions(memory_key,content,kind,source_ids,revision,created_at) VALUES('legacy','first','fact','[1]',1,10)");
            legacy.execSQL("INSERT INTO memory_revisions(memory_key,content,kind,source_ids,revision,created_at) VALUES('legacy','corrected','fact','[1]',2,20)");
            legacy.execSQL("INSERT INTO memories(memory_key,content,kind,source_ids,revision,created_at,updated_at) VALUES('legacy','corrected','fact','[1]',2,10,20)");
            legacy.setVersion(1);
        }
        store = new HoyoungStore(context);
        assertEquals(2, store.getReadableDatabase().getVersion());
        assertEquals("exact migrated original", store.getMessage(1).getString("text"));
        assertEquals(2, store.memoryRevisions("legacy", 100).length());
        assertEquals("first", store.memoryRevisions("legacy", 100).getJSONObject(1).getString("content"));
        JSONObject source = store.getMemory("legacy").getJSONArray("sourceRefs").getJSONObject(0);
        assertEquals("message", source.getString("type")); assertEquals(1, source.getLong("id"));
        assertEquals(store.getMessage(1).getString("content_hash"), source.getString("hash"));
        assertEquals(1, store.rankedOriginalCandidates("migrated", 0, 0, 10).length());
        assertTrue(store.verifyIntegrity().getBoolean("ok"));
    }

    @Test public void largeOriginalHasExactRangesSurrogateBoundariesFullTailSearchAndResult() throws Exception {
        String body = repeat('x', 208_036) + "😀 TAIL-EVIDENCE-492 cloud region Osaka.";
        JSONObject message = store.addMessage("assistant", body, null);
        long id = message.getLong("id");
        assertTrue(message.getBoolean("textIsExcerpt"));
        assertEquals(body.length(), message.getLong("raw_char_count"));
        assertEquals(sha256(body), message.getString("raw_content_hash"));
        assertEquals(id, store.rankedOriginalCandidates("TAIL-EVIDENCE-492", 0, 0, 3).getJSONObject(0).getLong("id"));
        JSONObject range = store.readOriginalText(id, 208_036, 1);
        assertEquals("", range.getString("text")); // A one-char budget cannot split this emoji.
        range = store.readOriginalText(id, 208_036, 100);
        assertEquals(body.substring(208_036), range.getString("text"));
        assertTrue(range.getBoolean("complete"));
        assertThrows(IllegalArgumentException.class, () -> store.readOriginalText(id, 208_037, 20));
        try (Reader reader = store.openOriginalReader(id)) { assertEquals(body, readAll(reader)); }
        long task = store.enqueueRequest("synthetic long result", "owner", "large-result").getLong("id");
        store.markRequest(task, "completed", body);
        JSONObject request = store.getRequest(task);
        assertEquals(body.length(), request.getLong("raw_result_char_count"));
        assertEquals(body, new String(Files.readAllBytes(new File(context.getFilesDir(), "originals/" + request.getString("raw_result_file_id")).toPath()), StandardCharsets.UTF_8));
        assertEquals(message.getString("raw_file_id"), request.getString("raw_result_file_id"));
        store.rebuildSearchIndexes();
        assertEquals(id, store.rankedOriginalCandidates("TAIL-EVIDENCE-492", 0, 0, 3).getJSONObject(0).getLong("id"));
        assertTrue(store.verifyIntegrity().getBoolean("ok"));
    }

    @Test public void deviceEventsDeduplicateStrictlyAndMemoryRetainsTypedProvenanceAndMetadataHistory() throws Exception {
        JSONObject payload = new JSONObject().put("text", "old notification evidence");
        JSONObject first = store.appendDeviceEvent("notification", "synthetic.app", payload, 10, "stable-event");
        assertEquals(first.getLong("id"), store.appendDeviceEvent("notification", "synthetic.app", payload, 10, "stable-event").getLong("id"));
        assertThrows(IllegalArgumentException.class, () -> store.appendDeviceEvent("notification", "synthetic.app", new JSONObject(), 10, "stable-event"));
        for (int i = 0; i < 510; i++) store.appendDeviceEvent("activity_sample", "synthetic.sensor", new JSONObject().put("sample", i), 20 + i, "sensor-" + i);
        assertEquals(first.getLong("id"), store.recentDeviceEvents("notification", null, 1).getJSONObject(0).getLong("id"));
        assertEquals(first.getLong("id"), store.rankedDeviceCandidates("notification evidence", 0, 0, 10).getJSONObject(0).getLong("id"));
        JSONArray refs = new JSONArray().put(new JSONObject().put("type", "device_event").put("id", first.getLong("id")));
        store.upsertMemoryWithSources("event-fact", "Observed original", "observation", refs);
        JSONObject metadata = new JSONObject().put("factKey", "observation.demo").put("pinned", true).put("aliases", new JSONArray().put("semantic-alias"))
                .put("validFrom", 10).put("evidenceQuotes", new JSONArray().put("old notification evidence"));
        store.putMemoryMetadata("event-fact", metadata);
        store.upsertMemoryWithSources("event-fact", "Corrected interpretation", "observation", refs);
        store.putMemoryMetadata("event-fact", new JSONObject(metadata.toString()).put("validFrom", 20));
        assertEquals(10, store.memoryRevisions("event-fact", 10).getJSONObject(1).getJSONObject("metadata").getLong("validFrom"));
        assertEquals(20, store.getMemoryMetadata("event-fact").getLong("validFrom"));
        assertEquals(first.getString("content_hash"), store.getMemory("event-fact").getJSONArray("sourceRefs").getJSONObject(0).getString("hash"));
        assertEquals("event-fact", store.retrieveMemoryCandidates("semantic-alias", 1).getJSONObject(0).getString("key"));
        store.rebuildSearchIndexes();
        assertEquals("event-fact", store.retrievePinnedMemories(1).getJSONObject(0).getString("key"));
        assertEquals(2, store.memoryRevisions("event-fact", 10).length());
        assertTrue(store.verifyIntegrity().getBoolean("ok"));
        JSONArray wrongHash = new JSONArray().put(new JSONObject().put("type", "device_event").put("id", first.getLong("id")).put("hash", repeat('0', 64)));
        assertThrows(IllegalArgumentException.class, () -> store.upsertMemoryWithSources("bad-fact", "injected", "fact", wrongHash));
        assertNull(store.getMemory("bad-fact"));
    }

    @Test public void streamingBackupPreservesFullOriginalAndRejectsTamperedEntryBeforeReplacement() throws Exception {
        String body = repeat('z', 210_000) + " EXACT-TAIL-SOURCE-904";
        long id = store.addMessage("user", body, null).getLong("id");
        files.writeFile("keep.txt", "baseline");
        ByteArrayOutputStream archive = new ByteArrayOutputStream(); files.exportBackup(archive, store);
        byte[] valid = archive.toByteArray();
        store.addMessage("assistant", "added after snapshot", null);
        files.importBackup(new ByteArrayInputStream(valid), store);
        assertEquals(1, store.messageCount());
        assertEquals(body.substring(210_000), store.readOriginalText(id, 210_000, 100).getString("text"));
        assertEquals(id, store.rankedOriginalCandidates("EXACT-TAIL-SOURCE-904", 0, 0, 3).getJSONObject(0).getLong("id"));
        byte[] corrupt = rewriteEntry(valid, "projects/keep.txt", "tampered".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> files.importBackup(new ByteArrayInputStream(corrupt), store));
        assertEquals("baseline", files.readText("keep.txt", 100));
        assertEquals(1, store.messageCount());
    }

    @Test public void longExportDoesNotHoldStoreMonitorAndMaintainsOneDatabaseCut() throws Exception {
        store.addMessage("user", "before snapshot", null);
        CountDownLatch blocked = new CountDownLatch(1), resume = new CountDownLatch(1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream slow = new OutputStream() {
            boolean first = true;
            private void pause() throws IOException {
                if (!first) return; first = false; blocked.countDown();
                try { if (!resume.await(15, TimeUnit.SECONDS)) throw new IOException("Test export never resumed"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            }
            @Override public void write(int value) throws IOException { pause(); bytes.write(value); }
            @Override public void write(byte[] data, int offset, int length) throws IOException { pause(); bytes.write(data, offset, length); }
        };
        ExecutorService worker = Executors.newFixedThreadPool(2);
        try {
            Future<?> export = worker.submit(() -> { try { files.exportBackup(slow, store); } catch (IOException error) { throw new UncheckedIOException(error); } });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            Future<Long> concurrentWrite = worker.submit(() -> { store.putSetting("during.export", "persisted"); return store.addMessage("user", "after snapshot", null).optLong("id"); });
            assertTrue("A slow external destination must not freeze regular store access", concurrentWrite.get(3, TimeUnit.SECONDS) > 0);
            assertEquals("persisted", store.getSetting("during.export", ""));
            resume.countDown(); export.get(15, TimeUnit.SECONDS);
            files.importBackup(new ByteArrayInputStream(bytes.toByteArray()), store);
            assertEquals(1, store.messageCount());
            assertEquals("before snapshot", store.getMessage(1).getString("text"));
            assertEquals("", store.getSetting("during.export", ""));
        } finally { resume.countDown(); worker.shutdownNow(); }
    }

    @Test public void streamingHistoryExceedsLegacyArrayCutoffWithoutDroppingEvents() throws Exception {
        final int count = 100_001;
        store.withTransaction(database -> {
            try (SQLiteStatement insert = database.compileStatement("INSERT INTO events(type,payload,created_at) VALUES('synthetic',?,?)")) {
                for (int i = 0; i < count; i++) { insert.bindString(1, "{\"index\":" + i + "}"); insert.bindLong(2, i); insert.executeInsert(); }
            }
            return null;
        });
        assertThrows(IllegalStateException.class, () -> store.exportSnapshot());
        ByteArrayOutputStream archive = new ByteArrayOutputStream(); files.exportBackup(archive, store);
        store.recordEvent("after-backup", new JSONObject());
        files.importBackup(new ByteArrayInputStream(archive.toByteArray()), store);
        try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT COUNT(*),MIN(created_at),MAX(created_at) FROM events WHERE type='synthetic'", null)) {
            assertTrue(cursor.moveToFirst()); assertEquals(count, cursor.getLong(0)); assertEquals(0, cursor.getLong(1)); assertEquals(count - 1, cursor.getLong(2));
        }
        assertTrue(store.verifyIntegrity().getBoolean("ok"));
    }

    @Test public void corruptedRawOriginalIsDetectedInsteadOfClaimingSourceIntegrity() throws Exception {
        JSONObject source = store.addMessage("user", repeat('a', 210_000), null);
        File raw = new File(context.getFilesDir(), "originals/" + source.getString("raw_file_id"));
        byte[] bytes = Files.readAllBytes(raw.toPath()); bytes[bytes.length - 1] = 'b'; Files.write(raw.toPath(), bytes);
        assertTrue(raw.setLastModified(raw.lastModified() + 5_000)); // Explicitly invalidates immutable-file identity cache.
        assertThrows(IOException.class, () -> store.readOriginalText(source.getLong("id"), 0, 20));
        assertThrows(IllegalStateException.class, () -> store.verifyIntegrity());
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder(); for (byte next : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", next & 255)); return value.toString();
    }
    private static String repeat(char value, int count) { char[] text = new char[count]; java.util.Arrays.fill(text, value); return new String(text); }
    private static String readAll(Reader reader) throws IOException { StringBuilder result = new StringBuilder(); char[] buffer = new char[8192]; int n; while ((n = reader.read(buffer)) != -1) result.append(buffer, 0, n); return result.toString(); }
    private static byte[] rewriteEntry(byte[] archive, String path, byte[] replacement) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive)); ZipOutputStream output = new ZipOutputStream(bytes)) {
            ZipEntry entry; byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().equals(path)) output.write(replacement); else { int n; while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n); }
                output.closeEntry(); input.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static void deleteTree(File root) throws IOException {
        if (!root.exists()) return;
        if (root.isDirectory()) { File[] children = root.listFiles(); if (children == null) throw new IOException("Cannot inspect test directory"); for (File child : children) deleteTree(child); }
        if (!root.delete()) throw new IOException("Cannot delete synthetic test file");
    }
}
