package com.hoyoung.assistant.phone;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Synthetic local history only; no live notifications, device actions or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneNotificationVisibilityTest {
    private Context context;
    private HoyoungStore store;
    private PhoneEventJournal journal;
    private PhoneTools tools;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); clear(context.getFilesDir());
        store = new HoyoungStore(context); journal = PhoneEventJournal.bind(context, store); tools = new PhoneTools(context, store);
        journal.ingestPendingForTests(); grant(true);
    }
    @After public void cleanup() {
        journal.ingestPendingForTests(); journal.closeWorker(); journal.awaitClosedForTests(); store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME); clear(context.getFilesDir());
    }
    @Test public void exclusionHidesOnlySelectedNotificationAndInvalidatesItsSavedResult() throws Exception {
        JSONObject retained = notice("still-visible", "example.chat");
        JSONObject excluded = notice("excluded-notification", "example.chat");
        JSONObject original = read(); assertEquals(2, original.getJSONArray("notifications").length());
        assertTrue(tools.canReadArchivedToolResult("phone_notifications_read", original));
        exclude("device_event", excluded);

        JSONObject current = read(); JSONArray rows = current.getJSONArray("notifications");
        assertEquals(1, rows.length()); assertEquals("still-visible", rows.getJSONObject(0).getString("text"));
        JSONObject reference = current.getJSONArray("sourceRefs").getJSONObject(0);
        assertEquals("device_event", reference.getString("type")); assertEquals(retained.getLong("id"), reference.getLong("id"));
        assertEquals(retained.getString("content_hash"), reference.getString("hash"));
        assertEquals(reference.toString(), rows.getJSONObject(0).getJSONArray("sourceRefs").getJSONObject(0).toString());
        assertFalse(tools.canReadArchivedToolResult("phone_notifications_read", original));
        assertTrue(tools.canReadArchivedToolResult("phone_notifications_read", current));
        assertNotNull("Owner exclusion retains the original", store.getDeviceEvent(excluded.getLong("id")));
    }
    @Test public void excludedAssistantReplyParentCannotReenterThroughNotificationHistory() throws Exception {
        JSONObject parent = store.addMessage("assistant", "synthetic-assistant-reply", null);
        JSONObject payload = new JSONObject().put("package", context.getPackageName()).put("text", "synthetic-assistant-reply")
                .put("sourceKind", "assistant_reply").put("sourceReferencePresent", true)
                .put("assistantMessageId", parent.getLong("id")).put("assistantSourceHash", parent.getString("content_hash"));
        store.appendDeviceEvent("notification", "android.notification_listener", payload, 100, null);
        notice("unrelated-visible", "example.chat");
        JSONObject original = read(); assertEquals(2, original.getJSONArray("notifications").length());
        exclude("message", parent);
        JSONObject current = read(); assertEquals(1, current.getJSONArray("notifications").length());
        assertFalse(current.toString().contains("synthetic-assistant-reply"));
        assertFalse(tools.canReadArchivedToolResult("phone_notifications_read", original));
    }
    @Test public void eachReadRechecksCollectionSwitchToolSwitchAndAndroidGrant() throws Exception {
        notice("current-grant-fixture", "example.chat"); JSONObject saved = read();
        store.putSetting("phoneContextEnabled", "false");
        assertEquals("permission_disabled", read().getString("code"));
        assertFalse(tools.canReadArchivedToolResult("phone_notifications_read", saved));
        store.putSetting("phoneContextEnabled", "true"); store.putSetting("toolPermissions", "{\"phone_notifications_read\":false}");
        assertEquals("permission_disabled", read().getString("code"));
        store.putSetting("toolPermissions", "{}"); grant(false);
        assertEquals("permission_missing", read().getString("code"));
        assertFalse(tools.canReadArchivedToolResult("phone_notifications_read", saved));
        grant(true); assertEquals(1, read().getJSONArray("notifications").length());
    }
    @Test public void oversizedResultPointersKeepExactSourceBindings() throws Exception {
        JSONObject source = notice("large-result-fixture", "example.chat"); JSONObject saved = read();
        JSONObject pointer = new JSONObject().put("ok", true).put("fullResultArchived", true).put("sourceRefs", saved.getJSONArray("sourceRefs"));
        assertTrue(tools.canReadArchivedToolResult("phone_notifications_read", pointer));
        exclude("device_event", source);
        assertFalse(tools.canReadArchivedToolResult("phone_notifications_read", pointer));
        pointer.remove("sourceRefs");
        assertFalse("Old unbound pointers cannot certify source visibility", tools.canReadArchivedToolResult("phone_notifications_read", pointer));
    }
    @Test public void recentReadsStayBoundedAsHistoryGrowsAndDoNotReuseAnOldSnapshot() throws Exception {
        notice("older-target", "example.target");
        store.withTransaction(db -> {
            for (int i = 0; i < 501; i++) notice("recent-fixture-" + i, "example.other");
            return null;
        });
        JSONObject args = new JSONObject().put("package", "example.target").put("limit", 1);
        JSONObject oldWindow = tools.execute("phone_notifications_read", args);
        assertEquals(0, oldWindow.getJSONArray("notifications").length()); assertEquals(500, oldWindow.getInt("scannedCandidates"));
        assertTrue(oldWindow.getBoolean("truncated")); assertTrue(oldWindow.getString("coverage").contains("phone_context_read"));
        notice("new-target", "example.target");
        JSONObject latest = tools.execute("phone_notifications_read", args);
        assertEquals("new-target", latest.getJSONArray("notifications").getJSONObject(0).getString("text"));
        assertEquals(1, latest.getInt("scannedCandidates"));
    }
    private JSONObject read() { return tools.execute("phone_notifications_read", new JSONObject()); }
    private JSONObject notice(String text, String packageName) {
        return store.appendDeviceEvent("notification", "android.notification_listener", PhoneEventJournal.object("text", text, "package", packageName), 100, null);
    }
    private void exclude(String type, JSONObject row) throws Exception {
        store.getPrivacyLedger().excludeSource(type, row.getLong("id"), row.getString("content_hash"), "retrieval_exclude", "explicit fixture owner exclusion");
    }
    private void grant(boolean enabled) {
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", enabled ? new ComponentName(context, HoyoungNotificationListener.class).flattenToString() : "");
    }
    private static void clear(java.io.File file) {
        if (file.isDirectory()) { java.io.File[] children = file.listFiles(); if (children != null) for (java.io.File child : children) clear(child); }
        if (file.exists()) assertTrue(file.delete());
    }
}
