package com.hoyoung.assistant.phone;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Append-only private history; no network path, silent ring eviction, or permission auto-enable. */
public final class HoyoungNotificationListener extends NotificationListenerService {
    private static volatile boolean connected;
    private static final java.util.concurrent.ConcurrentHashMap<PhoneEventJournal, Boolean> MIGRATING = new java.util.concurrent.ConcurrentHashMap<>();
    @Override public void onListenerConnected() {
        connected = true;
        if (startupUnavailable(this)) return;
        PhoneEventJournal journal = PhoneEventJournal.forContext(this);
        migrateLegacyAsync(this, journal);
        long gap = number(journal.get("notification.gapSince", "0"));
        if (gap == 0) gap = number(journal.get("notification.lastObservedAt", "0"));
        if (gap > 0) journal.gap("android.notification_listener", gap, System.currentTimeMillis(), "listener_disconnected_or_process_gap");
        journal.put("notification.gapSince", "0");
        journal.append("collector_state", "android.notification_listener", PhoneEventJournal.object("state", "connected", "observedOnly", true), System.currentTimeMillis(), null);
        if (!allowed(journal)) return;
        try {
            StatusBarNotification[] current = getActiveNotifications();
            if (current != null) {
                for (int i = 0; i < current.length && i < 100; i++) retain(current[i], "active_on_connect", 0);
                if (current.length > 100) journal.gap("android.notification_listener", System.currentTimeMillis(), System.currentTimeMillis(), "active_snapshot_limited_to_100; total=" + current.length);
            }
        } catch (SecurityException unavailable) { journal.gap("android.notification_listener", System.currentTimeMillis(), System.currentTimeMillis(), "active_snapshot_denied"); }
    }
    @Override public void onListenerDisconnected() { disconnected("listener_disconnected"); }
    @Override public void onDestroy() { disconnected("listener_destroyed"); super.onDestroy(); }
    private void disconnected(String reason) {
        connected = false; if (startupUnavailable(this)) return;
        PhoneEventJournal journal = PhoneEventJournal.forContext(this); long now = System.currentTimeMillis();
        if (number(journal.get("notification.gapSince", "0")) == 0) journal.put("notification.gapSince", String.valueOf(now));
        journal.append("collector_state", "android.notification_listener", PhoneEventJournal.object("state", reason, "futureObservationsUnavailable", true), now, null);
    }
    @Override public void onNotificationPosted(StatusBarNotification notification) { retain(notification, "posted", 0); }
    @Override public void onNotificationRemoved(StatusBarNotification notification) { retain(notification, "removed", 0); }
    @Override public void onNotificationRemoved(StatusBarNotification notification, RankingMap ranking, int reason) { retain(notification, "removed", reason); }
    public static boolean isConnected() { return connected; }
    private static boolean startupUnavailable(Context context) {
        Context app = context.getApplicationContext();
        return app instanceof com.hoyoung.assistant.runtime.HoyoungApplication
                && !((com.hoyoung.assistant.runtime.HoyoungApplication) app).isStartupReady();
    }

    /** Recent-view compatibility; every older record remains in durable, paged deviceEvents. */
    public static JSONArray notificationSnapshot(Context context) {
        if (startupUnavailable(context)) return new JSONArray();
        PhoneEventJournal journal = PhoneEventJournal.forContext(context); migrateLegacy(context, journal);
        JSONArray recent = journal.recent("notification", null, 500), output = new JSONArray();
        for (int i = 0; i < recent.length(); i++) {
            JSONObject row = recent.optJSONObject(i);
            if (row == null || !"notification".equals(row.optString("category"))) continue;
            JSONObject entry = row.optJSONObject("payload"); if (entry == null) continue;
            try { JSONObject copy = new JSONObject(entry.toString()); copy.put("deviceEventId", row.getLong("id")).put("source", row.optString("source")).put("observedAt", row.optLong("observed_at")).put("recordedAt", row.optLong("recorded_at")); output.put(copy); }
            catch (JSONException corrupt) { /* Invalid rows are retained in store, never fabricated. */ }
        }
        return output;
    }
    private void retain(StatusBarNotification sbn, String event, int reason) {
        if (sbn == null || startupUnavailable(this)) return;
        PhoneEventJournal journal = PhoneEventJournal.forContext(this); migrateLegacyAsync(this, journal);
        if (!allowed(journal)) { if (number(journal.get("notification.policyGapSince", "0")) == 0) journal.put("notification.policyGapSince", String.valueOf(System.currentTimeMillis())); return; }
        long gap = number(journal.get("notification.policyGapSince", "0"));
        if (gap > 0) { journal.gap("android.notification_listener", gap, System.currentTimeMillis(), "collection_disabled_by_app_or_permission"); journal.put("notification.policyGapSince", "0"); }
        try {
            Bundle extras = sbn.getNotification() == null ? Bundle.EMPTY : sbn.getNotification().extras;
            CharSequence title = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TEXT);
            JSONObject payload = PhoneEventJournal.object("package", sbn.getPackageName(), "key", limit(sbn.getKey(), 512), "title", limit(title, 1000), "text", limit(text, 6000),
                "postedAt", sbn.getPostTime(), "event", event, "removalReason", reason, "titleTruncated", title != null && title.length() > 1000,
                "textTruncated", text != null && text.length() > 6000, "contentsMayBeRedactedByAndroid", true, "observedOnly", true,
                "ownerReadConfirmed", false, "isOwnerInstruction", false);
            JSONObject original = NotificationObservedContent.capture(sbn.getNotification());
            payload.put("observedOriginal", original).put("originalTruncated", original.optBoolean("originalTruncated"))
                .put("originalContentSha256", original.optString("sha256"));
            boolean own = getPackageName().equals(sbn.getPackageName());
            boolean assistantReply = own && extras != null && "assistant_reply".equals(extras.get("com.hoyoung.assistant.source_kind"));
            payload.put("sourceKind", assistantReply ? "assistant_reply" : own ? "assistant_app_notification" : "external_notification");
            payload.put("assistantGenerated", own);
            if (own) payload.put("externalInputTriggerEligible", false);
            if (assistantReply) {
                Object sourceId = extras.get("com.hoyoung.assistant.assistant_message_id");
                long messageId = sourceId instanceof Long ? (Long) sourceId : -1;
                payload.put("assistantMessageId", messageId > 0 ? messageId : JSONObject.NULL);
                payload.put("sourceReferencePresent", messageId > 0);
                payload.put("replyStage", extras.get("com.hoyoung.assistant.reply_stage") instanceof String ? limit((String) extras.get("com.hoyoung.assistant.reply_stage"), 64) : "");
                payload.put("externalInputTriggerEligible", false);
            }
            long observed = "posted".equals(event) ? Math.max(0, sbn.getPostTime()) : System.currentTimeMillis();
            String key = "notification:" + PhoneEventJournal.hash(sbn.getKey() + ":" + observed + ":" + payload.toString());
            JSONObject saved = journal.append("notification", "android.notification_listener", payload, observed, key);
            if (saved.has("id") || saved.optBoolean("observationPersisted")) journal.put("notification.lastObservedAt", String.valueOf(System.currentTimeMillis()));
        } catch (RuntimeException | JSONException unavailable) { journal.gap("android.notification_listener", System.currentTimeMillis(), System.currentTimeMillis(), "notification_payload_unavailable"); }
    }
    private boolean allowed(PhoneEventJournal journal) { return journal.collectionEnabled() && journal.toolAllowed("phone_notifications_read") && new PermissionCatalog(this).notificationAccess(); }
    private static void migrateLegacyAsync(Context context, PhoneEventJournal journal) {
        if ("true".equals(journal.get("notification.legacyMigrated", "false")) || MIGRATING.putIfAbsent(journal, Boolean.TRUE) != null) return;
        Context app = context.getApplicationContext();
        if (!journal.runBackground(() -> { try { migrateLegacy(app, journal); } finally { MIGRATING.remove(journal); } })) MIGRATING.remove(journal);
    }
    private static synchronized void migrateLegacy(Context context, PhoneEventJournal journal) {
        if ("true".equals(journal.get("notification.legacyMigrated", "false"))) return;
        SharedPreferences old = context.getApplicationContext().getSharedPreferences("hoyoung_notification_history", Context.MODE_PRIVATE);
        try {
            JSONArray events = new JSONArray(old.getString("events", "[]"));
            for (int i = 0; i < events.length(); i++) {
                JSONObject original = events.optJSONObject(i); if (original == null) continue;
                long time = Math.max(0, original.optLong("time", 0));
                JSONObject stored = journal.append("notification", "android.notification_listener.legacy_ring", original, time, "legacy-notification:" + PhoneEventJournal.hash(original.toString()));
                if (!stored.has("id") && !stored.optBoolean("observationPersisted")) return;
            }
            if (events.length() > 0) journal.gap("android.notification_listener.legacy_ring", 0, events.optJSONObject(0).optLong("time", 0), "older_ring_evictions_cannot_be_recovered; migrated=" + events.length());
            journal.put("notification.legacyMigrated", "true"); old.edit().remove("events").apply();
        } catch (JSONException corrupt) { journal.gap("android.notification_listener.legacy_ring", 0, System.currentTimeMillis(), "legacy_notification_json_invalid"); }
    }
    private static long number(String text) { try { return Long.parseLong(text); } catch (NumberFormatException invalid) { return 0; } }
    private static String limit(CharSequence value, int maximum) { if (value == null) return ""; String text = value.toString(); return text.length() > maximum ? text.substring(0, maximum) : text; }
}
