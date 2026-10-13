package com.hoyoung.assistant.phone;

import android.Manifest;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** App-started local observation. Foreground service lifecycle belongs to the runtime. */
public final class PhoneContextCollector {
    private final Context context;
    private final PhoneTools tools;
    private final PhoneEventJournal journal;
    private volatile PhoneActivityTracker activity;
    private final HandlerThread thread = new HandlerThread("hoyoung-device-context");
    private final Handler worker;
    private volatile boolean running;
    private boolean contactsRegistered;
    private boolean calendarRegistered;
    private volatile Runnable changeListener;
    private volatile Runnable processingListener;
    private final java.util.concurrent.atomic.AtomicInteger pendingOperations = new java.util.concurrent.atomic.AtomicInteger();
    private final ContentObserver contacts;
    private final ContentObserver calendar;

    public PhoneContextCollector(Context context, PhoneTools tools, HoyoungStore store) {
        this.context = context.getApplicationContext(); this.tools = tools; this.journal = PhoneEventJournal.bind(this.context, store);
        this.activity = new PhoneActivityTracker(this.context, journal); tools.setActivityTracker(activity);
        thread.start(); worker = new Handler(thread.getLooper());
        contacts = observer("contacts"); calendar = observer("calendar");
    }
    public void setChangeListener(Runnable listener) { changeListener = listener; }
    public void setProcessingListener(Runnable listener) { processingListener = listener; }
    public boolean isProcessing() { return pendingOperations.get() > 0; }
    public void start() {
        if (running) { reconcilePermissions(); return; }
        running = true;
        postOperation(() -> {
            long now = System.currentTimeMillis(), previous = number(journal.get("lastPulse", "0"));
            if (previous > 0 && now - previous > 120000) journal.gap("android.device_collector", previous, now, "collector_not_running_or_process_gap");
            journal.append("collector_state", "android.device_collector", PhoneEventJournal.object("state", "started", "phoneContextEnabled", journal.collectionEnabled(), "rawAlwaysRecording", false), now, null);
            safely(() -> { reconcileOnWorker(); sampleOnWorker(); });
        });
    }
    public void stop(String reason) {
        running = false;
        activity.stop(reason == null ? "collector_stopped" : reason);
        tools.cancelOutstandingLocation(reason);
        postOperation(() -> {
            unregisterProviders();
            journal.append("collector_state", "android.device_collector", PhoneEventJournal.object("state", "stopped", "reason", reason == null ? "collector_stopped" : reason, "futureObservationsUnavailable", true), System.currentTimeMillis(), null);
        });
    }
    /** Invoke on Activity resume and each service pulse; OS grants can be revoked independently. */
    public void reconcilePermissions() { postOperation(() -> safely(this::reconcileOnWorker)); }
    /** Invoke on the visible autonomy-service pulse. It never schedules cloud/model work itself. */
    public void sampleNow() { postOperation(() -> safely(this::sampleOnWorker)); }
    public void afterBackupRestore() {
        if (running) throw new IllegalStateException("Stop the device collector before replacing its backup.");
        journal.afterBackupRestore();
        postOperation(() -> { activity = new PhoneActivityTracker(context, journal); tools.setActivityTracker(activity); notifyChanged(); });
    }
    public JSONObject state() { return PhoneEventJournal.object("running", running, "enabled", journal.collectionEnabled(), "activity", activity.snapshot(), "journal", journal.health(), "retention", "durable_until_explicit_owner_deletion"); }
    private ContentObserver observer(String source) {
        return new ContentObserver(worker) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                if (!running || !journal.collectionEnabled()) return;
                String tool = source.equals("contacts") ? "phone_contacts_read" : "phone_calendar_read";
                if (!journal.toolAllowed(tool)) return;
                if (!new PermissionCatalog(context).has(source.equals("contacts") ? Manifest.permission.READ_CONTACTS : Manifest.permission.READ_CALENDAR)) return;
                journal.append("provider_change", "android." + source + "_provider", PhoneEventJournal.object("uri", uri == null ? JSONObject.NULL : uri.toString(), "selfChange", selfChange, "changedFieldsUnknown", true), System.currentTimeMillis(), null);
                worker.removeCallbacks(source.equals("contacts") ? contactSample : calendarSample);
                worker.postDelayed(source.equals("contacts") ? contactSample : calendarSample, 1500);
                notifyChanged();
            }
        };
    }
    private final Runnable contactSample = () -> runObservedOperation(() -> sampleProvider("contacts"));
    private final Runnable calendarSample = () -> runObservedOperation(() -> sampleProvider("calendar"));
    private void reconcileOnWorker() {
        journal.refreshPolicyBlocking();
        if (!running || !journal.collectionEnabled()) { activity.stop("collection_disabled"); unregisterProviders(); return; }
        if (journal.toolAllowed("phone_activity_read")) { activity.start(); activity.reconcilePermissions(); } else activity.stop("activity_tool_disabled");
        PermissionCatalog grants = new PermissionCatalog(context);
        boolean allowContacts = grants.has(Manifest.permission.READ_CONTACTS) && journal.toolAllowed("phone_contacts_read");
        boolean allowCalendar = grants.has(Manifest.permission.READ_CALENDAR) && journal.toolAllowed("phone_calendar_read");
        if (!allowContacts && contactsRegistered) { context.getContentResolver().unregisterContentObserver(contacts); contactsRegistered = false; journal.gap("android.contacts_provider", number(journal.get("lastPulse", "0")), System.currentTimeMillis(), "read_permission_or_tool_revoked"); }
        if (!allowCalendar && calendarRegistered) { context.getContentResolver().unregisterContentObserver(calendar); calendarRegistered = false; journal.gap("android.calendar_provider", number(journal.get("lastPulse", "0")), System.currentTimeMillis(), "read_permission_or_tool_revoked"); }
        if (allowContacts && !contactsRegistered) { try { context.getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contacts); context.getContentResolver().registerContentObserver(ContactsContract.Data.CONTENT_URI, true, contacts); contactsRegistered = true; sampleProvider("contacts"); } catch (SecurityException denied) { journal.gap("android.contacts_provider", System.currentTimeMillis(), System.currentTimeMillis(), "observer_registration_denied"); } }
        if (allowCalendar && !calendarRegistered) { try { context.getContentResolver().registerContentObserver(CalendarContract.Events.CONTENT_URI, true, calendar); calendarRegistered = true; sampleProvider("calendar"); } catch (SecurityException denied) { journal.gap("android.calendar_provider", System.currentTimeMillis(), System.currentTimeMillis(), "observer_registration_denied"); } }
    }
    private void unregisterProviders() {
        if (contactsRegistered) { context.getContentResolver().unregisterContentObserver(contacts); contactsRegistered = false; }
        if (calendarRegistered) { context.getContentResolver().unregisterContentObserver(calendar); calendarRegistered = false; }
        worker.removeCallbacks(contactSample); worker.removeCallbacks(calendarSample);
    }
    private void sampleOnWorker() {
        if (!running || !journal.collectionEnabled()) return;
        reconcileOnWorker(); long now = System.currentTimeMillis();
        sampleDistinct("device_state", "android.device_state", tools.execute("phone_app_state", new JSONObject()), "deviceState", "phone_app_state");
        if (journal.toolAllowed("phone_location_last")) sampleDistinct("location_sample", "android.location_manager", tools.execute("phone_location_last", PhoneEventJournal.object("maxAgeMs", 900000)), "location", "phone_location_last");
        if (journal.toolAllowed("phone_usage_events")) sampleUsage(now);
        activity.flush(); journal.put("lastPulse", String.valueOf(now)); notifyChanged();
    }
    private void sampleProvider(String source) {
        if (!running || !journal.collectionEnabled()) return;
        String tool = source.equals("contacts") ? "phone_contacts_read" : "phone_calendar_read";
        if (!journal.toolAllowed(tool)) return;
        JSONObject result = tools.execute(tool, PhoneEventJournal.object("limit", 100));
        try { result.put("coverage", source.equals("contacts") ? "Bounded sample of phone-bearing contacts, not a complete address-book export." : "Bounded events in the default upcoming seven-day window."); }
        catch (JSONException impossible) { }
        sampleDistinct("provider_snapshot", "android." + source + "_provider", result, source, tool);
    }
    private void sampleDistinct(String category, String source, JSONObject result, String key, String tool) {
        if (!journal.toolAllowed(tool)) return;
        JSONObject fingerprint;
        try { fingerprint = new JSONObject(result.toString()); } catch (JSONException invalid) { return; }
        for (String volatileField : new String[]{"observedAt", "ageMs", "fromMs", "toMs"}) fingerprint.remove(volatileField);
        String hash = PhoneEventJournal.hash(fingerprint.toString());
        if (hash.equals(journal.get(key + ".hash", ""))) return;
        String actualCategory = result.optBoolean("ok") ? category : "collector_state";
        JSONObject stored = journal.append(actualCategory, source, result, System.currentTimeMillis(), null);
        if (stored.has("id") || stored.optBoolean("observationPersisted")) journal.put(key + ".hash", hash);
    }
    private void sampleUsage(long now) {
        long previous = number(journal.get("usage.cursor", "0")); long from = previous > 0 ? Math.max(0, previous - 1) : now - 900000;
        if (now - from > 86400000) { journal.gap("android.usage_events", from, now - 86400000, "backfill_window_limited_to_24_hours"); from = now - 86400000; }
        JSONObject result = tools.execute("phone_usage_events", PhoneEventJournal.object("fromMs", Math.max(0, from), "toMs", now, "limit", 200));
        if (!result.optBoolean("ok")) { sampleDistinct("usage_event", "android.usage_events", result, "usageStatus", "phone_usage_events"); return; }
        JSONArray events = result.optJSONArray("events");
        for (int i = 0; events != null && i < events.length(); i++) { JSONObject event = events.optJSONObject(i); if (event != null) journal.append("usage_event", "android.usage_events", event, event.optLong("time", now), "usage:" + PhoneEventJournal.hash(event.toString())); }
        if (result.optBoolean("truncated")) journal.gap("android.usage_events", result.optLong("scannedThroughMs", from), now, "sample_limited_to_200_events; query_history_tool_for_uncollected_transitions");
        journal.put("usage.cursor", String.valueOf(now));
    }
    /** Relevant local observations only, with provenance; permission/feature revocation filters again. */
    public String relevantContext(String query, int maxChars) {
        if (!journal.collectionEnabled()) return "Phone observation context is disabled.";
        return tools.contextSummary(query, Math.max(0, Math.min(maxChars, 12000)));
    }
    private void notifyChanged() { Runnable listener = changeListener; if (listener != null) listener.run(); }
    private void postOperation(Runnable operation) {
        pendingOperations.incrementAndGet();
        if (!worker.post(() -> { try { operation.run(); } finally { processingEnded(); } })) processingEnded();
    }
    private void runObservedOperation(Runnable operation) { pendingOperations.incrementAndGet(); try { operation.run(); } finally { processingEnded(); } }
    private void processingEnded() { pendingOperations.decrementAndGet(); Runnable listener = processingListener; if (listener != null) listener.run(); }
    private void safely(Runnable operation) { try { operation.run(); } catch (RuntimeException failure) { journal.gap("android.device_collector", number(journal.get("lastPulse", "0")), System.currentTimeMillis(), "collector_operation_failed; observations_not_inferred"); } }
    private static long number(String value) { try { return Long.parseLong(value); } catch (NumberFormatException invalid) { return 0; } }
}
