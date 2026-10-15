package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** Synthetic chat paging only; never opens an account or device data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BoundedChatMessagePageTest {
    private Context context;
    private HoyoungStore store;

    @Before public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        deleteTree(context.getFilesDir());
        assertTrue(context.getFilesDir().isDirectory() || context.getFilesDir().mkdirs());
        store = new HoyoungStore(context);
    }

    @After public void tearDown() throws IOException {
        store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        deleteTree(context.getFilesDir());
    }

    @Test public void clearingChatViewPreservesOriginalSearchMemoryQueueAndBackupAcrossReopen() throws Exception {
        JSONObject original=store.addMessage("user","ARCHIVEKEY 운동 기록은 계속 기억해",null);
        store.addMessage("assistant","계속 기억할게",null);
        store.upsertMemory("routine","ARCHIVEKEY 운동", "preference",new JSONArray().put(original.getLong("id")));
        store.enqueueRequest("다음 요청도 계속 수행","user","queued-keep");
        JSONObject before=store.exportSnapshot();
        store.clearChatView();
        JSONObject hidden=store.visibleChatMessagePage(0,40,8000);
        assertEquals(0,hidden.getJSONArray("messages").length());assertFalse(hidden.getBoolean("hasMore"));
        for(String table:new String[]{"messages","memories","memory_revisions","requests"})
            assertEquals(table,before.getJSONArray(table).toString(),store.exportSnapshot().getJSONArray(table).toString());
        assertEquals(original.getString("text"),store.readOriginalText(original.getLong("id"),0,8000).getString("text"));
        assertTrue(store.queryOriginals("ARCHIVEKEY",0,Long.MAX_VALUE,0,20).length()>0);
        assertEquals(2,store.boundedChatMessagePage(0,40,8000).getJSONArray("messages").length());
        assertTrue(store.verifyIntegrity().getBoolean("ok"));
        JSONObject backup=store.exportSnapshot();
        store.close();store=new HoyoungStore(context);
        assertEquals(0,store.visibleChatMessagePage(0,40,8000).getJSONArray("messages").length());
        store.restoreChatView();assertEquals(2,store.visibleChatMessagePage(0,40,8000).getJSONArray("messages").length());
        store.importSnapshot(backup);
        assertEquals(0,store.visibleChatMessagePage(0,40,8000).getJSONArray("messages").length());
        assertNotNull(store.getMemory("routine"));
        JSONObject fresh=store.addMessage("assistant","비운 뒤 도착한 새 답장",null);
        assertEquals(fresh.getLong("id"),store.visibleChatMessagePage(0,40,8000).getJSONArray("messages").getJSONObject(0).getLong("id"));
        store.restoreChatView();assertEquals(3,store.visibleChatMessagePage(0,40,8000).getJSONArray("messages").length());
    }

    @Test public void clearBoundaryLimitsOlderPagingAndAnUnrelatedBoundaryCannotHideNewMessages() throws Exception {
        for(int i=0;i<43;i++)store.addMessage("user","이전 화면 "+i,null);
        store.clearChatView();long boundary=store.chatViewHiddenThroughId();
        for(int i=0;i<45;i++)store.addMessage("assistant","새 화면 "+i,null);
        JSONObject latest=store.visibleChatMessagePage(0,40,8000);assertTrue(latest.getBoolean("hasMore"));
        JSONObject older=store.visibleChatMessagePage(latest.getLong("nextBeforeId"),40,8000);
        assertEquals(5,older.getJSONArray("messages").length());assertFalse(older.getBoolean("hasMore"));
        assertEquals(boundary+1,older.getJSONArray("messages").getJSONObject(0).getLong("id"));
        assertEquals(0,store.visibleChatMessagePage(boundary+1,40,8000).getJSONArray("messages").length());
        store.putSetting("ui.chatView.hiddenThrough",new JSONObject().put("id",boundary).put("hash","wrong-database-hash").toString());
        assertEquals(0,store.chatViewHiddenThroughId());
        assertEquals(40,store.visibleChatMessagePage(0,40,8000).getJSONArray("messages").length());
    }

    @Test public void hugeOriginalHasBoundedDisplayButExactTailAndHashesRemainAvailable() throws Exception {
        String body = repeat('x', 240_000) + "\n😀 EXACT-CHAT-TAIL-718 \t";
        JSONObject original = store.addMessage("assistant", body, "synthetic-request");
        long id = original.getLong("id");
        JSONObject page = store.boundedChatMessagePage(0, 1, 128);
        JSONObject row = page.getJSONArray("messages").getJSONObject(0);
        assertEquals(repeat('x', 128), row.getString("text"));
        assertEquals(128, row.getInt("textExcerptChars"));
        assertEquals(body.length(), row.getLong("textTotalChars"));
        assertTrue(row.getBoolean("textIsExcerpt"));
        assertTrue(row.getBoolean("textTruncatedForPage"));
        for (String field : new String[]{"id", "role", "request_id", "created_at", "content_hash", "raw_file_id", "raw_char_count", "raw_byte_count", "raw_content_hash"}) {
            assertEquals("Metadata must remain unchanged: " + field, original.get(field), row.get(field));
        }
        assertFalse(row.has("sqlPageTruncated"));
        assertEquals(sha256(body), row.getString("raw_content_hash"));
        JSONObject tail = store.readOriginalText(id, 240_000, 100);
        assertEquals(body.substring(240_000), tail.getString("text"));
        assertEquals(row.getString("raw_content_hash"), tail.getString("contentHash"));
        assertTrue(tail.getBoolean("complete"));
        assertEquals(original.getString("text"), store.getMessage(id).getString("text"));
        assertEquals(original.getString("text"), store.listMessages(1).getJSONObject(0).getString("text"));
        assertEquals(id, page.getLong("nextBeforeId"));
        assertFalse(page.getBoolean("hasMore"));
        assertTrue(store.verifyIntegrity().getBoolean("ok"));
    }

    @Test public void internalEvidenceCannotCrowdOutConversationAndRemainsExactlyReadable() throws Exception {
        long user = store.addMessage("user", "{\"user supplied JSON\":true}", "owner").getLong("id");
        long reply = store.addMessage("assistant", "사진 원본을 보관했어.", "reply").getLong("id");
        String record = "{\"type\":\"native_tool_outcome\",\"requested\":true,\"completionConfirmed\":false}";
        JSONObject original = null;
        for (int i = 0; i < 200; i++) original = store.addMessage("system", record, "internal-" + i);
        JSONObject latest = store.boundedChatMessagePage(0, 1, 8000);
        assertEquals(reply, latest.getJSONArray("messages").getJSONObject(0).getLong("id"));
        assertTrue(latest.getBoolean("hasMore"));
        JSONObject older = store.boundedChatMessagePage(latest.getLong("nextBeforeId"), 1, 8000);
        assertEquals(user, older.getJSONArray("messages").getJSONObject(0).getLong("id"));
        assertFalse(older.getBoolean("hasMore"));
        assertEquals(reply, store.lastChatMessageId());
        assertEquals(original.getLong("id"), store.lastMessageId());
        assertEquals(record, store.readOriginalText(original.getLong("id"), 0, 8000).getString("text"));
        assertEquals(sha256(record), original.getString("raw_content_hash"));
        assertEquals(202, store.exportSnapshot().getJSONArray("messages").length());
        assertTrue(store.verifyIntegrity().getBoolean("ok"));
    }

    @Test public void plainQuotaNoticesRemainVisibleWhileStructuredOnlyHistoryIsNotAChatPage() throws Exception {
        assertEquals(0, store.lastChatMessageId());
        store.addMessage("system", "{\"type\":\"goal_provider_reported_complete_archive\"}", null);
        assertEquals(0, store.lastChatMessageId());
        JSONObject empty = store.boundedChatMessagePage(0, 40, 8000);
        assertEquals(0, empty.getJSONArray("messages").length());
        assertFalse(empty.getBoolean("hasMore"));
        String quota = "사용 한도를 소진했어. 메시지와 작업은 폰에 저장돼.";
        long notice = store.addMessage("system", quota, null).getLong("id");
        JSONObject page = store.boundedChatMessagePage(0, 40, 8000);
        assertEquals(1, page.getJSONArray("messages").length());
        assertEquals(quota, page.getJSONArray("messages").getJSONObject(0).getString("text"));
        assertEquals(notice, store.lastChatMessageId());
    }

    @Test public void keysetPagesHaveNoLossOrDuplicatesAcrossIdHolesAndNewerArrival() throws Exception {
        for (int i = 1; i <= 23; i++) store.addMessage("user", "synthetic " + i, null);
        // Deliberate synthetic gaps exercise keyset pagination, not offset arithmetic.
        store.getWritableDatabase().delete("messages", "id IN (4,9,15)", null);
        Set<Long> expected = new HashSet<>();
        try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT id FROM messages", null)) {
            while (cursor.moveToNext()) expected.add(cursor.getLong(0));
        }
        Set<Long> seen = new HashSet<>();
        long before = 0;
        int pages = 0;
        while (true) {
            JSONObject page = store.boundedChatMessagePage(before, 4, 100);
            JSONArray rows = page.getJSONArray("messages");
            assertTrue(rows.length() > 0 && rows.length() <= 4);
            long previous = 0;
            for (int i = 0; i < rows.length(); i++) {
                long id = rows.getJSONObject(i).getLong("id");
                assertTrue("Each displayed page is chronological", id > previous);
                if (before != 0) assertTrue("Cursor excludes already displayed ids", id < before);
                assertTrue("No source may appear twice", seen.add(id));
                previous = id;
            }
            long next = page.getLong("nextBeforeId");
            assertEquals(rows.getJSONObject(0).getLong("id"), next);
            if (before != 0) assertTrue(next < before);
            before = next; pages++;
            if (pages == 1) store.addMessage("assistant", "new arrival during historical paging", null);
            if (!page.getBoolean("hasMore")) break;
            assertTrue("Keyset traversal must terminate", pages < 10);
        }
        assertEquals(5, pages);
        assertEquals(expected, seen);
        JSONObject exhausted = store.boundedChatMessagePage(before, 4, 100);
        assertEquals(0, exhausted.getJSONArray("messages").length());
        assertEquals(0, exhausted.getLong("nextBeforeId"));
        assertFalse(exhausted.getBoolean("hasMore"));
    }

    @Test public void preservesWhitespaceAndUnicodeWhileAvoidingSplitSurrogatePairs() throws Exception {
        String body = " \t😀가나다\n trailing  ";
        JSONObject original = store.addMessage("user", body, null);
        JSONObject split = store.boundedChatMessagePage(0, 1, 3).getJSONArray("messages").getJSONObject(0);
        assertEquals(" \t", split.getString("text"));
        assertEquals(2, split.getInt("textExcerptChars"));
        assertEquals(body.length(), split.getLong("textTotalChars"));
        assertTrue(split.getBoolean("textIsExcerpt"));
        JSONObject emoji = store.boundedChatMessagePage(0, 1, 4).getJSONArray("messages").getJSONObject(0);
        assertEquals(" \t😀", emoji.getString("text"));
        assertEquals(4, emoji.getInt("textExcerptChars"));
        JSONObject complete = store.boundedChatMessagePage(0, 1, 8000).getJSONArray("messages").getJSONObject(0);
        assertEquals(body, complete.getString("text"));
        assertEquals(body.length(), complete.getInt("textExcerptChars"));
        assertFalse(complete.getBoolean("textIsExcerpt"));
        assertFalse(complete.getBoolean("textTruncatedForPage"));
        assertEquals(original.getString("raw_content_hash"), complete.getString("raw_content_hash"));
        assertEquals(body, store.readOriginalText(original.getLong("id"), 0, 8000).getString("text"));
    }

    @Test public void utf16LimitStillHoldsForAstralTextAndBoundedSqlBytes() throws Exception {
        String body = repeat("😀", 8_100) + " FULL-TAIL";
        JSONObject original = store.addMessage("assistant", body, null);
        JSONObject row = store.boundedChatMessagePage(0, 40, 8000).getJSONArray("messages").getJSONObject(0);
        assertEquals(repeat("😀", 4_000), row.getString("text"));
        assertEquals(8000, row.getInt("textExcerptChars"));
        assertEquals(body.length(), row.getLong("textTotalChars"));
        assertTrue(row.getBoolean("textIsExcerpt"));
        assertTrue(row.getBoolean("textTruncatedForPage"));
        assertEquals(body, store.getMessage(original.getLong("id")).getString("text"));
        assertEquals(original.getString("content_hash"), row.getString("content_hash"));
    }

    @Test public void sqlByteBoundaryCannotDiscardCompleteKoreanPrefixOrInjectReplacementText() throws Exception {
        JSONObject original = store.addMessage("user", "한글", null);
        JSONObject small = store.boundedChatMessagePage(0, 1, 1).getJSONArray("messages").getJSONObject(0);
        assertEquals("한", small.getString("text")); // Four SQL bytes split the second character.
        assertEquals(1, small.getInt("textExcerptChars"));
        assertTrue(small.getBoolean("textIsExcerpt"));
        assertEquals("한글", store.getMessage(original.getLong("id")).getString("text"));
        String largeBody = repeat("가", 12_000) + " exact tail";
        JSONObject largeOriginal = store.addMessage("assistant", largeBody, null);
        JSONObject large = store.boundedChatMessagePage(0, 1, 8000).getJSONArray("messages").getJSONObject(0);
        assertEquals(repeat("가", 8000), large.getString("text")); // 32,000 SQL bytes split a later character.
        assertEquals(8000, large.getInt("textExcerptChars"));
        assertFalse(large.getString("text").contains("\ufffd"));
        assertEquals(largeBody.length(), large.getLong("textTotalChars"));
        assertEquals(largeOriginal.getString("content_hash"), large.getString("content_hash"));
        assertEquals(largeBody, store.getMessage(largeOriginal.getLong("id")).getString("text"));
    }

    @Test public void embeddedNulDoesNotDropTheRemainingOriginalOrItsDisplayExcerpt() throws Exception {
        String body = " \tabc\u0000😀 suffix\n  ";
        JSONObject original = store.addMessage("user", body, null);
        JSONObject complete = store.boundedChatMessagePage(0, 1, 8000).getJSONArray("messages").getJSONObject(0);
        assertEquals(body, complete.getString("text"));
        assertFalse(complete.getBoolean("textIsExcerpt"));
        assertFalse(complete.getBoolean("textTruncatedForPage"));
        assertEquals(body.length(), complete.getLong("textTotalChars"));
        JSONObject excerpt = store.boundedChatMessagePage(0, 1, 8).getJSONArray("messages").getJSONObject(0);
        assertEquals(body.substring(0, 8), excerpt.getString("text"));
        assertTrue(excerpt.getBoolean("textTruncatedForPage"));
        assertTrue(excerpt.getBoolean("textIsExcerpt"));
        assertEquals(original.getString("raw_content_hash"), excerpt.getString("raw_content_hash"));
        assertEquals(body, store.getMessage(original.getLong("id")).getString("text"));
    }

    @Test public void limitsAreStrictAndLookaheadDoesNotLeakAnExtraDisplayedMessage() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> store.boundedChatMessagePage(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> store.boundedChatMessagePage(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> store.boundedChatMessagePage(0, 41, 1));
        assertThrows(IllegalArgumentException.class, () -> store.boundedChatMessagePage(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> store.boundedChatMessagePage(0, 1, 8001));
        JSONObject empty = store.boundedChatMessagePage(0, 40, 8000);
        assertEquals(0, empty.getJSONArray("messages").length());
        assertEquals(0, empty.getLong("nextBeforeId"));
        assertFalse(empty.getBoolean("hasMore"));
        for (int i = 0; i < 45; i++) store.addMessage("user", "message " + i, null);
        JSONObject page = store.boundedChatMessagePage(Long.MAX_VALUE, 40, 8000);
        assertEquals(40, page.getJSONArray("messages").length());
        assertTrue(page.getBoolean("hasMore"));
        assertEquals(6, page.getLong("nextBeforeId"));
        JSONObject last = store.boundedChatMessagePage(6, 5, 1);
        assertEquals(5, last.getJSONArray("messages").length());
        assertEquals(1, last.getLong("nextBeforeId"));
        assertFalse(last.getBoolean("hasMore"));
        for (int i = 0; i < 5; i++) {
            JSONObject row = last.getJSONArray("messages").getJSONObject(i);
            assertEquals("m", row.getString("text"));
            assertEquals(1, row.getInt("textExcerptChars"));
            assertTrue(row.getBoolean("textIsExcerpt"));
        }
        assertEquals(45, store.listMessages(1000).length());
    }

    @Test public void legacyMissingCountCanStillReportCompleteUnicodeTextTruthfully() throws Exception {
        String body = "  😀 한국어\n";
        long id = store.addMessage("user", body, null).getLong("id");
        store.getWritableDatabase().execSQL("UPDATE messages SET raw_char_count=NULL WHERE id=?", new Object[]{id});
        JSONObject row = store.boundedChatMessagePage(0, 1, 8000).getJSONArray("messages").getJSONObject(0);
        assertEquals(body, row.getString("text"));
        assertEquals(body.length(), row.getInt("textTotalChars"));
        assertEquals(body.length(), row.getInt("textExcerptChars"));
        assertFalse(row.getBoolean("textIsExcerpt"));
    }

    private static String repeat(char value, int count) {
        char[] data = new char[count]; java.util.Arrays.fill(data, value); return new String(data);
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte next : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", next & 255));
        return result.toString();
    }

    private static void deleteTree(File root) throws IOException {
        if (!root.exists()) return;
        if (root.isDirectory()) {
            File[] children = root.listFiles(); if (children == null) throw new IOException("Cannot inspect synthetic storage");
            for (File child : children) deleteTree(child);
        }
        if (!root.delete()) throw new IOException("Cannot remove synthetic storage");
    }
}
