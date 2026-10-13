package com.hoyoung.assistant.phone;

import android.content.Context;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Callback writes fsync independently; a serial background worker ingests exactly once. */
public final class PhoneEventJournal {
    private static final WeakHashMap<Context, PhoneEventJournal> INSTANCES = new WeakHashMap<>();
    private final HoyoungStore store;
    private final PhoneObservationSpool spool;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(operation -> { Thread thread = new Thread(operation, "hoyoung-phone-journal-ingest"); thread.setDaemon(true); return thread; });
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Map<String, String> localState = new ConcurrentHashMap<>();
    private final Map<String, String> dirtyState = new ConcurrentHashMap<>();
    private final Object ingestionLock = new Object();
    private final Object callbackLock = new Object();
    private volatile boolean policyReady;
    private volatile String permissionJson = "{}";
    private volatile boolean collectionEnabled;
    private volatile long failedSince;
    private volatile long failedEvents;
    private volatile String lastFailure = "";
    private volatile boolean closed;
    private volatile long lastIngestedId;
    private final AtomicLong policyEpoch = new AtomicLong();
    private final Object policyLock = new Object();
    private volatile boolean restorePaused;
    private volatile String archiveFailure = "";
    private volatile long nextArchiveAttemptAt;
    private final Object archiveHealthLock = new Object();

    private PhoneEventJournal(Context context, HoyoungStore store) {
        this.store = store;
        try { spool = new PhoneObservationSpool(new File(context.getFilesDir(), "device-journal")); }
        catch (IOException failure) { throw new IllegalStateException("Private device journal cannot be initialized", failure); }
        restoreFailureMarker();
        if (!spool.readState("health.archiveFailure", "").isEmpty()) archiveFailure = "prior_archive_failure_pending_reverification";
        // No SQLite calls on this constructor/callback path. Previously persisted policy starts
        // fail-closed until it is verified against current app settings by the background worker.
        collectionEnabled = false; policyReady = false;
        requestDrain();
    }
    public static synchronized PhoneEventJournal bind(Context context, HoyoungStore store) {
        Context app = context.getApplicationContext(); PhoneEventJournal existing = INSTANCES.get(app);
        if (existing != null && existing.store == store && !existing.closed) return existing;
        if (existing != null) existing.closeWorker();
        PhoneEventJournal journal = new PhoneEventJournal(app, store); INSTANCES.put(app, journal); return journal;
    }
    public static synchronized PhoneEventJournal forContext(Context context) {
        Context app = context.getApplicationContext(); PhoneEventJournal found = INSTANCES.get(app); if (found != null && !found.closed) return found;
        if (app instanceof HoyoungApplication) { HoyoungStore store = ((HoyoungApplication) app).getStore(); if (store != null) return bind(app, store); }
        return bind(app, new HoyoungStore(app));
    }
    /** Never obtains the store monitor; receipt acknowledges private spool persistence only. */
    public JSONObject append(String category, String source, JSONObject payload, long observedAt, String dedupeKey) {
        synchronized (callbackLock) {
            if (restorePaused) return object("ok", false, "code", "restore_in_progress", "observationPersisted", false);
            try {
                recoverFailedGap(source);
                JSONObject record = spool.append(category, source, payload, observedAt, dedupeKey); requestDrain();
                return object("ok", true, "code", "spooled", "spoolId", record.optString("spoolId"), "observationPersisted", true, "databaseIngested", false);
            } catch (IOException | RuntimeException failure) { failed(source, failure); return object("ok", false, "code", "journal_unavailable", "observationPersisted", false, "failure", lastFailure); }
        }
    }
    private synchronized void recoverFailedGap(String source) throws IOException {
        if (failedEvents == 0) return;
        long now = Math.max(0, System.currentTimeMillis()); spool.append("collector_gap", source, object("startedAt", Math.min(failedSince, now), "endedAt", now, "reason", "spool_write_failed", "failedEvents", failedEvents, "observationsLostOrUnknown", true), now, null);
        failedSince = 0; failedEvents = 0; lastFailure = "";
        spool.writeState("health.storageFailure", "{}");
    }
    private synchronized void failed(String source, Exception failure) {
        if (failedEvents == 0) failedSince = Math.max(0, System.currentTimeMillis()); failedEvents++; lastFailure = "private_spool_write_failed; source=" + source;
        // A small failure marker may still fit when one large record fails. Total storage loss
        // can prevent even this marker; health then remains explicitly unconfirmed in memory.
        try { spool.writeState("health.storageFailure", object("startedAt", failedSince, "failedEvents", failedEvents, "source", source).toString()); } catch (IOException cannotPersistFailure) { }
    }
    private synchronized void restoreFailureMarker() {
        try { JSONObject marker = new JSONObject(spool.readState("health.storageFailure", "{}")); failedEvents = Math.max(0, marker.optLong("failedEvents")); failedSince = Math.max(0, marker.optLong("startedAt")); if (failedEvents > 0) lastFailure = "private_spool_write_failed; source=" + marker.optString("source", "unknown"); }
        catch (JSONException invalid) { failedEvents = 1; failedSince = Math.max(0, System.currentTimeMillis()); lastFailure = "private_storage_failure_marker_invalid"; }
    }
    public JSONObject gap(String source, long from, long to, String reason) {
        long end = Math.max(0, to), start = Math.min(end, Math.max(0, from));
        return append("collector_gap", source, object("startedAt", start, "endedAt", end, "reason", reason, "observedOnly", true, "clockOrderAdjusted", start != from || end != to), end, null);
    }
    private void requestDrain() {
        if (!closed && !restorePaused && scheduled.compareAndSet(false, true)) worker.execute(() -> {
            boolean retry = false; long archiveDelay = 0;
            try { refreshPolicyBlocking(); ingestPending(); mirrorState(); archiveDelay = maintainArchive(); if (failedEvents == 0 && lastFailure.startsWith("database_")) lastFailure = ""; }
            catch (RuntimeException failure) { lastFailure = "database_ingest_unavailable; observations_remain_spooled"; retry = true; }
            finally { scheduled.set(false); }
            if (!closed && !restorePaused && (retry || spool.pendingCount() > 0 || !dirtyState.isEmpty() || spool.archiveMaintenanceNeeded())) {
                long delay = retry ? 1000 : spool.pendingCount() > 0 || !dirtyState.isEmpty() ? 10 : Math.max(10, archiveDelay);
                worker.schedule(this::requestDrain, delay, TimeUnit.MILLISECONDS);
            }
        });
    }
    private long maintainArchive() {
        synchronized (ingestionLock) {
            if (closed || restorePaused || !spool.archiveMaintenanceNeeded()) return 0;
            long now = android.os.SystemClock.elapsedRealtime(); if (nextArchiveAttemptAt > now) return nextArchiveAttemptAt - now;
            try {
                spool.compactArchive(); nextArchiveAttemptAt = 0;
                synchronized (archiveHealthLock) {
                    if (!spool.archiveAuditPending()) {
                        archiveFailure = "";
                        if (!spool.readState("health.archiveFailure", "").isEmpty()) spool.writeState("health.archiveFailure", "");
                    }
                }
                return spool.archiveSnapshotActive() ? 1000 : 10;
            } catch (IOException failure) {
                nextArchiveAttemptAt = now + 30000;
                recordArchiveFailure(failure);
                return 30000;
            }
        }
    }
    private void recordArchiveFailure(IOException failure) {
        synchronized (archiveHealthLock) {
            archiveFailure = "archive_compaction_or_validation_unavailable; existing_originals_and_segments_retained";
            String signature = hash(failure.getClass().getName() + ":" + failure.getMessage());
            if (!signature.equals(spool.readState("health.archiveFailure", ""))) {
                try {
                    spool.append("collector_gap", "phone.private_archive", object("reason", "raw_archive_compaction_or_validation_failed", "existingOriginalsAndSegmentsRetained", true, "archiveCompletenessUnconfirmed", true), Math.max(0, System.currentTimeMillis()), null);
                    spool.writeState("health.archiveFailure", signature);
                } catch (IOException gapUnavailable) { failed("phone.private_archive", gapUnavailable); }
            }
        }
    }
    private void ingestPending() {
        synchronized (ingestionLock) {
            try {
                for (File file : spool.pending(128)) {
                    if (closed || restorePaused) return;
                    JSONObject record;
                    try { record = spool.read(file); }
                    catch (IOException corrupt) { spool.quarantine(file); spool.append("collector_gap", "phone.private_spool", object("reason", "corrupt_spool_record_quarantined", "filename", file.getName(), "observationsLostOrUnknown", true), System.currentTimeMillis(), null); continue; }
                    JSONObject stored;
                    try { stored = store.appendDeviceEvent(record.optString("category"), record.optString("source"), record.optJSONObject("payload"), record.optLong("observedAt"), record.optString("dedupeKey")); }
                    catch (IllegalArgumentException invalid) { spool.quarantine(file); spool.append("collector_gap", "phone.private_spool", object("reason", "spool_ingest_rejected_or_dedupe_conflict", "filename", file.getName(), "originalPreservedInQuarantine", true), System.currentTimeMillis(), null); continue; }
                    if (stored == null || !stored.has("id")) throw new IllegalStateException("Database returned no durable observation ID");
                    lastIngestedId = stored.optLong("id"); spool.acknowledge(file, record, stored);
                }
            } catch (IOException failure) { throw new IllegalStateException("Device spool ingestion failed without deleting pending data", failure); }
        }
    }
    private void mirrorState() {
        synchronized (ingestionLock) {
            for (Map.Entry<String, String> entry : dirtyState.entrySet()) { if (closed || restorePaused) return; String key = entry.getKey(), value = entry.getValue(); store.putSetting("phone.context." + key, value); dirtyState.remove(key, value); }
        }
    }
    /** Background policy refresh; callers on an Android callback must use cached predicates. */
    public void refreshPolicyBlocking() {
        long epoch = policyEpoch.get();
        try {
            String raw = store.getSetting("toolPermissions", "{}"); JSONObject parsed = new JSONObject(raw);
            for (java.util.Iterator<String> keys = parsed.keys(); keys.hasNext();) if (!(parsed.opt(keys.next()) instanceof Boolean)) throw new JSONException("Invalid tool policy value");
            boolean enabled = "true".equals(store.getSetting("phoneContextEnabled", "true"));
            synchronized (policyLock) { if (epoch == policyEpoch.get()) { permissionJson = raw; collectionEnabled = enabled; policyReady = true; } }
        } catch (JSONException corrupt) { synchronized (policyLock) { if (epoch == policyEpoch.get()) { policyReady = false; collectionEnabled = false; } } }
    }
    /** Apply an already persisted owner UI/runtime setting without querying SQLite on a callback. */
    public void applyPermissionPolicyFromOwner(String raw, boolean enabled) {
        synchronized (policyLock) {
            policyEpoch.incrementAndGet();
            try { JSONObject parsed = new JSONObject(raw); for (java.util.Iterator<String> keys = parsed.keys(); keys.hasNext();) if (!(parsed.opt(keys.next()) instanceof Boolean)) throw new JSONException("Invalid tool policy value"); permissionJson = raw; collectionEnabled = enabled; policyReady = true; }
            catch (JSONException invalid) { policyReady = false; collectionEnabled = false; }
        }
    }
    public void refreshPolicy() { requestDrain(); }
    boolean runBackground(Runnable operation) {
        if (closed || restorePaused) return false;
        try { worker.execute(operation); return true; } catch (java.util.concurrent.RejectedExecutionException stopped) { return false; }
    }
    /** Background restore thread only: stop collectors first, then quiesce file/SQL ingestion. */
    public void pauseForBackupRestore() {
        synchronized (callbackLock) { synchronized (policyLock) { restorePaused = true; policyEpoch.incrementAndGet(); } }
        synchronized (ingestionLock) { /* Wait for the currently committing observation. */ }
    }
    /** Called after explicit backup replacement or failure, with the collector already stopped. */
    public void afterBackupRestore() {
        synchronized (callbackLock) {
            localState.clear(); dirtyState.clear();
            try { spool.afterRestore(); } catch (IOException unavailable) { failed("phone.backup_restore", unavailable); throw new IllegalStateException("Restored device journal is unavailable; collection remains paused", unavailable); }
            failedEvents = 0; failedSince = 0; lastFailure = ""; restoreFailureMarker();
            archiveFailure = spool.readState("health.archiveFailure", "").isEmpty() ? "" : "prior_archive_failure_pending_reverification"; nextArchiveAttemptAt = 0;
            synchronized (policyLock) { policyEpoch.incrementAndGet(); policyReady = false; collectionEnabled = false; restorePaused = false; }
        }
        if (!closed) worker.execute(this::refreshPolicyBlocking);
        requestDrain();
    }
    public void captureBackupSnapshot(File destination) throws IOException {captureBackupSnapshot(destination,null);}
    public void captureBackupSnapshot(File destination,com.hoyoung.assistant.store.BackupPolicy.SpaceMonitor monitor) throws IOException { try { spool.captureSnapshot(destination,null,monitor); } catch (IOException validationFailure) { recordArchiveFailure(validationFailure); throw validationFailure; } finally { requestDrain(); } }
    public static void captureBackupSnapshot(Context context, File destination) throws IOException { forContext(context).captureBackupSnapshot(destination); }
    public static void captureBackupSnapshot(Context context,File destination,com.hoyoung.assistant.store.BackupPolicy.SpaceMonitor monitor)throws IOException{forContext(context).captureBackupSnapshot(destination,monitor);}
    /** Pure background validation of staged raw segments, before any live restore replacement. */
    public static void validateBackupSnapshot(File stagedJournal) throws IOException { PhoneObservationSpool.validateSnapshot(stagedJournal); }
    public JSONArray events(String category, String source, long afterId, int limit) { return store.deviceEvents(category, source, afterId, limit); }
    public JSONArray recent(int limit) { return store.listDeviceEvents(limit); }
    public JSONArray recent(String category, String source, int limit) { return store.recentDeviceEvents(category, source, limit); }
    public JSONArray search(String query, String category, int limit) { return store.searchDeviceEvents(query, category, limit); }
    public JSONObject read(long id) { return store.getDeviceEvent(id); }
    public JSONObject stats() { return store.deviceEventStats(); }
    /** Local state is fsynced independently; background mirroring is only for backup/index compatibility. */
    public String get(String key, String fallback) { String cached = localState.get(key); if (cached != null) return cached; String value = spool.readState(key, fallback); localState.put(key, value); return value; }
    public void put(String key, String value) {
        synchronized (callbackLock) {
            if (restorePaused) return;
            try { spool.writeState(key, value); localState.put(key, value); dirtyState.put(key, value); requestDrain(); }
            catch (IOException failure) { failed("phone.local_state", failure); }
        }
    }
    public String rootSetting(String key, String fallback) { return key.equals("toolPermissions") ? permissionJson : key.equals("phoneContextEnabled") ? String.valueOf(collectionEnabled()) : fallback; }
    public boolean toolAllowed(String name) { if (restorePaused || !policyReady) return false; try { JSONObject settings = new JSONObject(permissionJson); return !settings.has(name) || Boolean.TRUE.equals(settings.opt(name)); } catch (JSONException corrupt) { return false; } }
    public boolean collectionEnabled() { return !restorePaused && policyReady && collectionEnabled; }
    public JSONObject receipt(String spoolId) { try { return new JSONObject(spool.readState("receipt." + spoolId, "{}")); } catch (JSONException invalid) { return new JSONObject(); } }
    public JSONObject health() { return object("ok", failedEvents == 0 && archiveFailure.isEmpty(), "pendingGapSince", failedEvents == 0 ? JSONObject.NULL : failedSince, "failedEvents", failedEvents,
        "pendingSpoolRecords", spool.pendingCount(), "lastIngestedDeviceEventId", lastIngestedId, "policyVerified", policyReady, "lastFailure", lastFailure, "archiveFailure", archiveFailure,
        "rawArchiveFormat", "exact_original_files_or_sha256_verified_immutable_segments", "rawArchiveSegmentMaxBytes", PhoneArchiveSegments.MAX_SEGMENT_BYTES, "durability", "private_file_fsync_and_atomic_rename_before_sqlite_ingest"); }
    void ingestPendingForTests() { if (closed || restorePaused) throw new IllegalStateException("Cannot drain a closed or restoring journal"); refreshPolicyBlocking(); do { ingestPending(); } while (spool.pendingCount() > 0); mirrorState(); maintainArchive(); }
    void closeWorker() { closed = true; worker.shutdownNow(); }
    void awaitClosedForTests() { synchronized (ingestionLock) { } }
    File spoolDirectoryForTests() { return spool.directory(); }
    public static String hash(String value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder output = new StringBuilder(); for (byte part : digest) output.append(String.format(Locale.ROOT, "%02x", part & 255)); return output.toString(); } catch (Exception impossible) { throw new IllegalStateException(impossible); } }
    public static JSONObject object(Object... values) { JSONObject output = new JSONObject(); try { for (int i = 0; i < values.length; i += 2) output.put((String) values[i], values[i + 1]); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } return output; }
}
