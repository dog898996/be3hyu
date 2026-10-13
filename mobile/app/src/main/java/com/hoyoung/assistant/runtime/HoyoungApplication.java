package com.hoyoung.assistant.runtime;

import android.app.Application;

import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.phone.PhoneContextCollector;
import com.hoyoung.assistant.phone.PhoneEventJournal;
import com.hoyoung.assistant.memory.MemoryRetriever;
import com.hoyoung.assistant.memory.SemanticAnnCache;
import com.hoyoung.assistant.memory.local.LocalSemanticEncoder;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import com.hoyoung.assistant.web.WorkWebController;

/** One application-owned WebView transport; Activity recreation only reparents its view. */
public final class HoyoungApplication extends Application {
    private HoyoungStore store;
    private InternalFiles files;
    private PhoneTools phoneTools;
    private AssistantRuntime runtime;
    private WorkWebController webController;
    private PhoneContextCollector phoneContext;
    private LocalSemanticEncoder semanticEncoder;
    private MemoryRetriever memoryRetriever;
    private AssistantReplyNotifications replyNotifications;
    private final java.util.concurrent.ExecutorService maintenance = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean indexing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean startupReady;
    private volatile org.json.JSONObject startupState = state(false, "starting", "");

    @Override public void onCreate() {
        super.onCreate();
        initializeCore();
    }

    /** Recovery never manufactures an empty canonical namespace before checking the journal. */
    private synchronized void initializeCore() {
        if (startupReady) return;
        try {
            files = new InternalFiles(this);
            if (files.startupBlocked()) { startupState = files.startupDiagnostics(); return; }
            store = new HoyoungStore(this);
        } catch (RuntimeException storageUnavailable) {
            startupState = state(false, "storage_unavailable", storageUnavailable.getClass().getSimpleName());
            return;
        }
        phoneTools = new PhoneTools(this, store);
        phoneContext = new PhoneContextCollector(this, phoneTools, store);
        files.setBackupParticipant(new InternalFiles.BackupParticipant() {
            @Override public void capture(java.io.File destination) throws java.io.IOException {
                PhoneEventJournal.captureBackupSnapshot(HoyoungApplication.this, destination);
            }
            @Override public void capture(java.io.File destination, com.hoyoung.assistant.store.BackupPolicy.SpaceMonitor monitor) throws java.io.IOException {
                PhoneEventJournal.captureBackupSnapshot(HoyoungApplication.this, destination, monitor);
            }
            @Override public void validate(java.io.File stagedJournal) throws java.io.IOException {
                PhoneEventJournal.validateBackupSnapshot(stagedJournal);
            }
        });
        semanticEncoder = new LocalSemanticEncoder(this);
        memoryRetriever = new MemoryRetriever(store, semanticEncoder);
        memoryRetriever.setDeviceSourceVisibility(phoneTools::canUseImplicitMemorySource);
        memoryRetriever.setSemanticCandidateProvider(new SemanticAnnCache(store, semanticEncoder, new java.io.File(getNoBackupFilesDir(), "derived-indexes/semantic-ann"), true));
        runtime = new AssistantRuntime(this, store, files, phoneTools);
        replyNotifications = new AssistantReplyNotifications(this, store);
        runtime.setReplyNotifications(replyNotifications);
        runtime.setMemoryRetriever(memoryRetriever);
        runtime.setPhoneContextCollector(phoneContext);
        webController = new WorkWebController(this, runtime, files);
        runtime.attachTransport(webController);
        startupReady = true;
        startupState = state(true, "ready", "");
        webController.start();
    }

    public boolean isStartupReady() { return startupReady; }
    public org.json.JSONObject startupState() {
        try { return new org.json.JSONObject(startupState.toString()); }
        catch (org.json.JSONException invalid) { return state(false, "storage_unavailable", "diagnostic_unavailable"); }
    }
    /** Explicit retry on an IO worker; a failed retry retains previous files and its journal. */
    public synchronized void retryStartupRecovery() { initializeCore(); }
    private static org.json.JSONObject state(boolean ready, String status, String errorType) {
        return PhoneEventJournal.object("ready", ready, "blocked", !ready, "status", status, "errorType", errorType);
    }

    public AssistantRuntime getRuntime() { return runtime; }
    public HoyoungStore getStore() { return store; }
    public InternalFiles getFiles() { return files; }
    public PhoneTools getPhoneTools() { return phoneTools; }
    public WorkWebController getWebController() { return webController; }
    public PhoneContextCollector getPhoneContext() { return phoneContext; }
    public MemoryRetriever getMemoryRetriever() { return memoryRetriever; }
    public LocalSemanticEncoder getSemanticEncoder() { return semanticEncoder; }
    public AssistantReplyNotifications getReplyNotifications() { return replyNotifications; }

    public void maintainMemory() {
        if (!startupReady) return;
        boolean enabled = "true".equals(store.getSetting("semanticSearchEnabled", "true"));
        memoryRetriever.setSemanticEnabled(enabled);
        if (!enabled) return;
        semanticEncoder.prepareAsync();
        if (!semanticEncoder.isReady() || !indexing.compareAndSet(false, true)) return;
        maintenance.execute(() -> {
            try { memoryRetriever.indexPending(8, 2500); }
            catch (RuntimeException error) { store.recordEvent("semantic_index_deferred", new org.json.JSONObject()); }
            finally { indexing.set(false); }
        });
    }
}
