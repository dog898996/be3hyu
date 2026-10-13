package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class HoyoungStoreTest {
    private Context context;
    private HoyoungStore store;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        store = new HoyoungStore(context);
    }

    @After public void tearDown() {
        store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
    }

    @Test public void queueReopenRetainsCompletedAndQueuedButDoesNotReplayInFlight() throws Exception {
        JSONObject queued = store.enqueueRequest("first", "owner", "same-owner-id");
        JSONObject accepted = store.enqueueRequest("remote accepted", "owner", "accepted-id");
        JSONObject dispatching = store.enqueueRequest("sending now", "owner", "sending-id");
        JSONObject done = store.enqueueRequest("finished", "owner", "completed-id");
        store.markRequest(accepted.getLong("id"), "accepted", "remote task 123");
        store.markRequest(dispatching.getLong("id"), "dispatching", null);
        store.markRequest(done.getLong("id"), "completed", "done");
        store.close();
        store = new HoyoungStore(context);

        assertEquals("uncertain", store.getRequest(accepted.getLong("id")).getString("status"));
        assertEquals("remote task 123", store.getRequest(accepted.getLong("id")).getString("result"));
        assertEquals("uncertain", store.getRequest(dispatching.getLong("id")).getString("status"));
        assertEquals("completed", store.getRequest(done.getLong("id")).getString("status"));
        assertEquals(queued.getLong("id"), store.nextQueuedRequest().getLong("id"));
        assertEquals(queued.getLong("id"), store.enqueueRequest("first", "owner", "same-owner-id").getLong("id"));
        assertEquals(4, store.listRequests(100).length());
        assertThrows(IllegalArgumentException.class, () -> store.enqueueRequest("changed", "owner", "same-owner-id"));

        store.restoreRequest(accepted.getLong("id"));
        assertEquals("queued", store.getRequest(accepted.getLong("id")).getString("status"));
        assertEquals("request_explicitly_restored", store.events(1).getJSONObject(0).getString("type"));
        assertThrows(IllegalStateException.class, () -> store.restoreRequest(done.getLong("id")));
    }

    @Test public void memoryUpdatesPreserveExactOriginalsAndEarlierSourceLinkedRevision() throws Exception {
        String original = "우리 이름은 호영이. 줄바꿈\n수정하지 않은 원문과 % _ \\ 그대로.";
        JSONObject first = store.addMessage("user", original, null);
        JSONObject second = store.addMessage("user", "선호 언어를 한국어로 바꿔", null);
        JSONArray firstSource = new JSONArray().put(first.getLong("id"));
        store.upsertMemory("owner.language", "English", "preference", firstSource);
        store.upsertMemory("owner.language", "한국어", "preference", new JSONArray().put(second.getLong("id")));
        store.upsertMemory("owner.language", "한국어", "preference", new JSONArray().put(second.getLong("id")));

        assertEquals(original, store.getMessage(first.getLong("id")).getString("text"));
        assertEquals(2, store.listMessages(100).length());
        JSONArray revisions = store.memoryRevisions("owner.language", 100);
        assertEquals(2, revisions.length());
        assertEquals("English", revisions.getJSONObject(1).getString("content"));
        assertEquals(first.getLong("id"), revisions.getJSONObject(1).getJSONArray("sourceMessageIds").getLong(0));
        assertEquals(second.getLong("id"), revisions.getJSONObject(0).getJSONArray("sourceMessageIds").getLong(0));
        assertTrue(store.memoryContext("한국어", 500).contains("sourceMessageIds="));
        assertTrue(store.memoryContext("한국어", 25).length() <= 25);
        assertThrows(IllegalArgumentException.class, () -> store.upsertMemory("invented", "unsourced",
                "fact", new JSONArray().put(999999L)));
        assertEquals(0, store.searchMemories("unsourced", 10).length());
    }

    @Test public void searchEscapesSqlLikeWildcardsAndBoundsResults() throws Exception {
        long source = store.addMessage("user", "Original", null).getLong("id");
        JSONArray ids = new JSONArray().put(source);
        store.upsertMemory("literal", "literal % percent and under_score", "fact", ids);
        store.upsertMemory("other", "a completely different entry", "fact", ids);
        assertEquals(1, store.searchMemories("%", 10).length());
        assertEquals("literal", store.searchMemories("_", 10).getJSONObject(0).getString("key"));
        assertEquals(1, store.searchMemories("", 1).length());
        assertEquals(0, store.memoryContext("", 0).length());
    }

    @Test public void anOlderSpecificMemoryIsNotHiddenByHundredsOfRecentWeakMatches() throws Exception {
        long source = store.addMessage("user", "original source", null).getLong("id");
        JSONArray ids = new JSONArray().put(source);
        store.upsertMemory("needle-important", "exact preference", "preference", ids);
        for (int i = 0; i < 220; i++) store.upsertMemory("newer-" + i, "mentioned needle in passing", "event", ids);
        assertEquals("needle-important", store.searchMemories("needle", 1).getJSONObject(0).getString("key"));
    }

    @Test public void invalidSnapshotDoesNotDestroyExistingMessagesOrSettings() throws Exception {
        long source = store.addMessage("user", "keep this original", null).getLong("id");
        store.putSetting("runtime", "keep");
        store.upsertMemory("fact", "known fact", "fact", new JSONArray().put(source));
        JSONObject snapshot = store.exportSnapshot();
        snapshot.getJSONArray("memories").getJSONObject(0).put("sourceMessageIds", new JSONArray().put(987654321));
        assertThrows(IllegalArgumentException.class, () -> store.importSnapshot(snapshot));
        assertEquals("keep this original", store.getMessage(source).getString("text"));
        assertEquals("keep", store.getSetting("runtime", ""));
    }

    @Test public void backupCannotDropEarlierMemoryRevisionsOrHideDuplicateRequestIds() throws Exception {
        long source = store.addMessage("user", "original", null).getLong("id");
        store.upsertMemory("fact", "earlier fact", "fact", new JSONArray().put(source));
        store.upsertMemory("fact", "new fact", "fact", new JSONArray().put(source));
        JSONObject request = store.enqueueRequest("one task", "owner", "unique-external-id");
        JSONObject missingHistory = store.exportSnapshot();
        missingHistory.put("memory_revisions", new JSONArray().put(missingHistory.getJSONArray("memory_revisions").getJSONObject(1)));
        assertThrows(IllegalArgumentException.class, () -> store.validateSnapshot(missingHistory));
        JSONObject duplicate = store.exportSnapshot();
        JSONObject duplicatedRequest = new JSONObject(request.toString()).put("id", request.getLong("id") + 100);
        duplicate.getJSONArray("requests").put(duplicatedRequest);
        assertThrows(IllegalArgumentException.class, () -> store.importSnapshot(duplicate));
        assertEquals(1, store.listRequests(100).length());
        assertEquals(2, store.memoryRevisions("fact", 100).length());
    }

    @Test public void lightweightPollAndUncertaintyGatesDoNotDependOnRecentHistoryLimits() throws Exception {
        assertEquals(0, store.lastMessageId());
        long firstMessage = store.addMessage("user", "first original", null).getLong("id");
        long lastMessage = store.addMessage("assistant", "latest", null).getLong("id");
        assertEquals(lastMessage, store.lastMessageId());
        assertTrue(lastMessage > firstMessage);
        long uncertain = store.enqueueRequest("older unresolved remote task", "owner", "old-root").getLong("id");
        store.markRequest(uncertain, "uncertain", null);
        SQLiteDatabase database = store.getWritableDatabase();
        database.beginTransaction();
        try {
            for (int i = 0; i < 1001; i++) {
                ContentValues values = new ContentValues();
                values.put("external_id", "newer-" + i); values.put("text", "newer completed task");
                values.put("kind", "owner"); values.put("status", "completed");
                values.put("created_at", 1L); values.put("updated_at", 1L);
                database.insertOrThrow("requests", null, values);
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        assertEquals("completed", store.listRequests(1000).getJSONObject(999).getString("status"));
        assertTrue(store.hasUncertainRequests());
        assertFalse(store.hasUncertainRequests(uncertain));
        assertEquals(1, store.getUncertainCount());
        assertFalse(store.hasUncertainActionReceipts());
        store.putSetting("unrelatedSetting", "malformed is not a receipt");
        store.putSetting("actionReceipt:root:a", "{\"status\":\"completed\"}");
        assertFalse(store.hasUncertainActionReceipts());
        store.putSetting("actionReceipt:root:b", "{\"status\":\"dispatching\"}");
        store.putSetting("actionReceipt:root:c", "malformed receipt");
        assertTrue(store.hasUncertainActionReceipts());
        assertEquals(1, store.listUncertainActionReceipts(1).length());
        assertEquals("actionReceipt:root:b", store.listUncertainActionReceipts(1).getJSONObject(0).getString("key"));
        assertEquals(2, store.listUncertainActionReceipts(10).length());
        store.putSetting("actionReceipt:root:b", "{\"status\":\"completed\"}");
        assertTrue(store.hasUncertainActionReceipts());
        store.putSetting("actionReceipt:root:c", "{\"status\":\"completed\"}");
        assertFalse(store.hasUncertainActionReceipts());
    }

    @Test public void explicitAbandonmentRetainsUnknownOutcomeWithoutCompletionOrReplay() throws Exception {
        long source = store.addMessage("user", "original task instruction", null).getLong("id");
        long request = store.enqueueRequest("original task instruction", "owner", "lost-temporary-work").getLong("id");
        store.markRequest(request, "uncertain", "last known remote task id: work-123");
        store.markRequest(request, "abandoned", null);
        assertEquals("abandoned", store.getRequest(request).getString("status"));
        assertEquals("last known remote task id: work-123", store.getRequest(request).getString("result"));
        assertEquals("original task instruction", store.getMessage(source).getString("text"));
        assertNull(store.nextQueuedRequest());
        assertFalse(store.hasUncertainRequests());
        assertEquals(0, store.getUncertainCount());
        assertEquals("", store.taskContext(1000));
        assertThrows(IllegalStateException.class, () -> store.restoreRequest(request));
        JSONObject originalOutcome = new JSONObject().put("code", "provider_outcome_unknown").put("verified", false);
        JSONObject receipt = new JSONObject().put("status", "abandoned").put("result", originalOutcome)
                .put("ownerAbandoned", true).put("providerOutcomeVerified", false);
        store.putSetting("actionReceipt:lost-temporary-work:one", receipt.toString());
        assertFalse(store.hasUncertainActionReceipts());
        assertEquals(0, store.listUncertainActionReceipts(100).length());
        store.importSnapshot(store.exportSnapshot());
        assertEquals("abandoned", store.getRequest(request).getString("status"));
        JSONObject restoredReceipt = new JSONObject(store.getSetting("actionReceipt:lost-temporary-work:one", "{}"));
        assertEquals("abandoned", restoredReceipt.getString("status"));
        assertEquals(originalOutcome.toString(), restoredReceipt.getJSONObject("result").toString());
        assertFalse(restoredReceipt.getBoolean("providerOutcomeVerified"));
        assertNull(store.nextQueuedRequest());
    }

    @Test public void restoredOldQueueIsQuarantinedEvenIfItExecutedAfterSnapshotExport() throws Exception {
        long source = store.addMessage("user", "unaltered source before backup", null).getLong("id");
        String[] statuses = {"queued", "blocked", "quota", "dispatching", "accepted", "completed", "abandoned", "uncertain"};
        long[] ids = new long[statuses.length];
        for (int i = 0; i < statuses.length; i++) {
            ids[i] = store.enqueueRequest("original " + statuses[i] + " instruction", "owner", "restore-" + statuses[i]).getLong("id");
            store.markRequest(ids[i], statuses[i], "original result for " + statuses[i]);
        }
        JSONObject oldSnapshot = store.exportSnapshot();
        // The backup still calls this queued, although the actual task has now executed.
        store.markRequest(ids[0], "completed", "executed after the old snapshot was exported");
        store.importSnapshot(oldSnapshot);
        assertNull(store.nextQueuedRequest());
        assertEquals(6, store.getUncertainCount());
        for (int i = 0; i < 5; i++) {
            assertEquals("uncertain", store.getRequest(ids[i]).getString("status"));
            assertEquals("original result for " + statuses[i], store.getRequest(ids[i]).getString("result"));
            assertEquals("original " + statuses[i] + " instruction", store.getRequest(ids[i]).getString("text"));
        }
        assertEquals("completed", store.getRequest(ids[5]).getString("status"));
        assertEquals("abandoned", store.getRequest(ids[6]).getString("status"));
        assertEquals("unaltered source before backup", store.getMessage(source).getString("text"));
        JSONObject summary = store.events(1).getJSONObject(0);
        assertEquals("backup_restore_quarantine", summary.getString("type"));
        assertEquals(5, summary.getJSONObject("payload").getLong("requestCount"));
        assertEquals(1, summary.getJSONObject("payload").getJSONObject("previousStatusCounts").getLong("queued"));
        boolean queuedProvenanceFound = false;
        JSONArray events = store.events(100);
        for (int i = 0; i < events.length(); i++) {
            JSONObject payload = events.getJSONObject(i).getJSONObject("payload");
            if ("queued".equals(payload.optString("previousStatus"))) {
                queuedProvenanceFound = payload.getJSONArray("requestIds").getLong(0) == ids[0];
            }
        }
        assertTrue(queuedProvenanceFound);
    }
}
