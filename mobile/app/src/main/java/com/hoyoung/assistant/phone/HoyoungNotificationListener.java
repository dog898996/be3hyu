package com.hoyoung.assistant.phone;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.SourcePrivacyLedger;
import com.hoyoung.assistant.store.SourceRecordVisibility;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Append-only private history; no network path, silent ring eviction, or permission auto-enable. */
public final class HoyoungNotificationListener extends NotificationListenerService {
    private static volatile boolean connected;
    private static volatile HoyoungNotificationListener instance;
    private static final java.util.concurrent.ConcurrentHashMap<PhoneEventJournal, Boolean> MIGRATING = new java.util.concurrent.ConcurrentHashMap<>();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private long connectionGeneration;
    private boolean initialSnapshotScheduled;
    @Override public synchronized void onListenerConnected() {
        instance = this; connected = true; connectionGeneration++; initialSnapshotScheduled = false;
        scheduleInitialSnapshot();
    }
    /** Application recovery may finish after Android connected this listener. */
    public static void onStartupReady(Context context) {
        HoyoungNotificationListener current = instance;
        if (current != null && current.getApplicationContext() == context.getApplicationContext()) current.scheduleInitialSnapshot();
    }
    private synchronized void scheduleInitialSnapshot() {
        if (initialSnapshotScheduled || instance != this || !connected || startupUnavailable(this)) return;
        PhoneEventJournal journal = PhoneEventJournal.forContext(this); long generation = connectionGeneration;
        initialSnapshotScheduled = true;
        if (!journal.runBackground(() -> {
            try { journal.refreshPolicyBlocking(); }
            catch (RuntimeException unavailable) { journal.gap("android.notification_listener", System.currentTimeMillis(), System.currentTimeMillis(), "initial_snapshot_policy_unavailable"); return; }
            main.post(() -> captureInitialSnapshot(journal, generation));
        })) initialSnapshotScheduled = false;
    }
    private synchronized void captureInitialSnapshot(PhoneEventJournal journal, long generation) {
        if (instance != this || !connected || generation != connectionGeneration || startupUnavailable(this)) return;
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
    private synchronized void disconnected(String reason) {
        connectionGeneration++; initialSnapshotScheduled = false;
        if (instance == this) instance = null;
        connected = false; if (startupUnavailable(this)) return;
        PhoneEventJournal journal = PhoneEventJournal.forContext(this); long now = System.currentTimeMillis();
        if (number(journal.get("notification.gapSince", "0")) == 0) journal.put("notification.gapSince", String.valueOf(now));
        journal.append("collector_state", "android.notification_listener", PhoneEventJournal.object("state", reason, "futureObservationsUnavailable", true), now, null);
    }
    @Override public void onNotificationPosted(StatusBarNotification notification) { retain(notification, "posted", 0); }
    @Override public void onNotificationRemoved(StatusBarNotification notification) { retain(notification, "removed", 0); }
    @Override public void onNotificationRemoved(StatusBarNotification notification, RankingMap ranking, int reason) { retain(notification, "removed", reason); }
    public static boolean isConnected() { return connected; }
    /** The live Android connection only; durable history is not an executable notification. */
    public static HoyoungNotificationListener connectedInstance() {
        HoyoungNotificationListener current = instance;
        return current != null && connected && !startupUnavailable(current) ? current : null;
    }
    private static boolean startupUnavailable(Context context) {
        Context app = context.getApplicationContext();
        return app instanceof com.hoyoung.assistant.runtime.HoyoungApplication
                && !((com.hoyoung.assistant.runtime.HoyoungApplication) app).isStartupReady();
    }

    /** Recent-view compatibility; every older record remains in durable, paged deviceEvents. */
    public static JSONArray notificationSnapshot(Context context) {
        if (startupUnavailable(context)) return new JSONArray();
        try {
            JSONArray newest = notificationHistory(context, 500, "").optJSONArray("notifications"), output = new JSONArray();
            for (int i = newest == null ? -1 : newest.length() - 1; i >= 0; i--) output.put(newest.opt(i));
            return output;
        } catch (IOException | JSONException unavailable) { return new JSONArray(); }
    }
    /** One indexed, bounded read and one ancestry memo, discarded before returning. */
    static JSONObject notificationHistory(Context context, int limit, String packageName) throws IOException, JSONException {
        PhoneEventJournal journal = PhoneEventJournal.forContext(context);
        journal.refreshPolicyBlocking();
        if (!historyAllowed(context, journal)) return PhoneEventJournal.object("ok", false, "code", "permission_disabled");
        migrateLegacy(context, journal);
        HoyoungStore store = journal.canonicalStore(); SourcePrivacyLedger.Snapshot privacy = store.getPrivacyLedger().capture();
        JSONArray output = new JSONArray(), references = new JSONArray(); int scanned = 0, omitted = 0; boolean truncated = false;
        try (HoyoungStore.ReadSnapshot snapshot = store.openReadSnapshot()) {
            SourceRecordVisibility.ReadContext visibility = new SourceRecordVisibility.ReadContext(snapshot, privacy);
            // category,id has an index. The extra candidate only establishes that the bounded view has more rows.
            try (Cursor rows = snapshot.rawQuery("SELECT * FROM device_events WHERE category = ? ORDER BY id DESC LIMIT 501", new String[]{"notification"})) {
                while (rows.moveToNext()) {
                    if (scanned == 500 || output.length() == limit) { truncated = true; break; }
                    scanned++;
                    JSONObject row = notificationRecord(rows), payload = row.optJSONObject("payload");
                    if (payload == null || !packageName.isEmpty() && !packageName.equals(payload.optString("package"))) continue;
                    if (!visibility.allowed("device_event", row)) { omitted++; continue; }
                    JSONObject reference = sourceReference(row);
                    JSONObject entry = new JSONObject(payload.toString()).put("deviceEventId", row.getLong("id"))
                            .put("source", row.optString("source")).put("observedAt", row.optLong("observed_at")).put("recordedAt", row.optLong("recorded_at"))
                            .put("sourceRefs", new JSONArray().put(reference));
                    output.put(entry); references.put(reference);
                }
            }
        }
        journal.refreshPolicyBlocking();
        if (!historyAllowed(context, journal)) return PhoneEventJournal.object("ok", false, "code", "permission_disabled");
        if (!privacy.fingerprint.equals(store.getPrivacyLedger().fingerprint())) throw new IOException("Notification source controls changed during read");
        return PhoneEventJournal.object("ok", true, "code", "ok", "notifications", output, "sourceRefs", references,
                "scannedCandidates", scanned, "omittedForCurrentControls", omitted, "truncated", truncated,
                "coverage", "newest_500_durable_notification_candidates; older_records_remain_available_through_phone_context_read");
    }
    /** Historical output must retain its exact canonical identities, including large-result pointers. */
    static boolean archivedHistoryAllowed(Context context, JSONObject result) {
        try {
            PhoneEventJournal journal = PhoneEventJournal.forContext(context);
            if (!historyAllowed(context, journal)) return false;
            JSONArray entries = result.optJSONArray("notifications"), references = result.optJSONArray("sourceRefs");
            if (references == null || references.length() > 500 || entries == null && !result.optBoolean("fullResultArchived")) return false;
            if (entries != null && entries.length() != references.length()) return false;
            HoyoungStore store = journal.canonicalStore(); SourcePrivacyLedger.Snapshot privacy = store.getPrivacyLedger().capture();
            try (HoyoungStore.ReadSnapshot snapshot = store.openReadSnapshot()) {
                SourceRecordVisibility.ReadContext visibility = new SourceRecordVisibility.ReadContext(snapshot, privacy);
                for (int i = 0; i < references.length(); i++) {
                    JSONObject reference = references.optJSONObject(i);
                    if (reference == null || !"device_event".equals(reference.optString("type")) || !(reference.opt("id") instanceof Number)
                            || ((Number) reference.opt("id")).doubleValue() != reference.optLong("id") || reference.optLong("id") <= 0
                            || !reference.optString("hash").matches("[a-f0-9]{64}")) return false;
                    JSONObject entry = entries == null ? null : entries.optJSONObject(i);
                    if (entries != null && (entry == null || entry.optLong("deviceEventId") != reference.optLong("id"))) return false;
                    try (Cursor rows = snapshot.rawQuery("SELECT * FROM device_events WHERE id = ? AND category = ?",
                            new String[]{String.valueOf(reference.optLong("id")), "notification"})) {
                        if (!rows.moveToFirst()) return false;
                        JSONObject row = notificationRecord(rows);
                        if (!reference.optString("hash").equals(row.optString("content_hash")) || !visibility.allowed("device_event", row)) return false;
                    }
                }
            }
            journal.refreshPolicyBlocking();
            return historyAllowed(context, journal) && privacy.fingerprint.equals(store.getPrivacyLedger().fingerprint());
        } catch (IOException | JSONException | RuntimeException unavailable) { return false; }
    }
    private static boolean historyAllowed(Context context, PhoneEventJournal journal) {
        return !startupUnavailable(context) && journal.collectionEnabled() && journal.toolAllowed("phone_notifications_read")
                && new PermissionCatalog(context).notificationAccess();
    }
    private static JSONObject notificationRecord(Cursor row) throws JSONException {
        return PhoneEventJournal.object("id", row.getLong(row.getColumnIndexOrThrow("id")), "category", row.getString(row.getColumnIndexOrThrow("category")),
                "source", row.getString(row.getColumnIndexOrThrow("source")), "payload", new JSONObject(row.getString(row.getColumnIndexOrThrow("payload"))),
                "observed_at", row.getLong(row.getColumnIndexOrThrow("observed_at")), "recorded_at", row.getLong(row.getColumnIndexOrThrow("recorded_at")),
                "content_hash", row.getString(row.getColumnIndexOrThrow("content_hash")));
    }
    private static JSONObject sourceReference(JSONObject row) {
        return PhoneEventJournal.object("type", "device_event", "id", row.optLong("id"), "hash", row.optString("content_hash"));
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
                Object sourceHash=extras.get("com.hoyoung.assistant.source_message_hash");
                String messageHash=sourceHash instanceof String&&((String)sourceHash).matches("[a-f0-9]{64}")?(String)sourceHash:"";
                payload.put("assistantMessageId", messageId > 0 ? messageId : JSONObject.NULL);
                payload.put("assistantSourceHash",messageHash);
                payload.put("sourceReferencePresent", messageId > 0&&!messageHash.isEmpty());
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
