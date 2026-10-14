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
import com.hoyoung.assistant.store.SourcePayloadDeletion;
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
    private final java.util.concurrent.ExecutorService policyMirror = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean policyMirroring = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long policyMirrorAttemptAt;
    private final java.util.concurrent.atomic.AtomicBoolean indexing = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean sourceControlPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean sourceReconciliationRunning;
    private volatile boolean startupReady;
    private volatile org.json.JSONObject startupState = state(false, "starting", "");

    @Override public void onCreate() {
        super.onCreate();
        initializeCore();
    }

    /** Recovery never manufactures an empty canonical namespace before checking the journal. */
    private synchronized void initializeCore() {
        if (startupReady||sourceReconciliationRunning) return;
        try {
            files = new InternalFiles(this);
            if (files.startupBlocked()) { startupState = files.startupDiagnostics(); return; }
            store = new HoyoungStore(this);
        } catch (RuntimeException storageUnavailable) {
            startupState = state(false, "storage_unavailable", storageUnavailable.getClass().getSimpleName());
            return;
        }
        // Deletion intent survives backups outside their restored namespaces.
        // Recheck tracked originals on an IO worker before any UI/Work/index sees them.
        if(hasSourceDeletionState()) {
            sourceReconciliationRunning=true;
            startupState=state(false,"source_deletion_reconciling","");
            maintenance.execute(()->{
                Throwable failed=null;
                try {
                    com.hoyoung.assistant.store.OwnerPolicyBackup.reconcile(HoyoungApplication.this,store);
                    AssistantRuntime.reconcileOwnerSourceAuthority(store);
                }catch(java.io.IOException|RuntimeException failure){failed=failure;}
                final Throwable result=failed;
                main.post(()->{
                    synchronized(HoyoungApplication.this) {
                        sourceReconciliationRunning=false;
                        if(result!=null){startupState=state(false,"source_deletion_reconciliation_failed",result.getClass().getSimpleName());return;}
                        initializeReadyComponents();
                        if("true".equals(store.getSetting("autonomy.enabled","false")))runtime.startAutonomy();
                    }
                });
            });
            return;
        }
        // Retrieval-only exclusions can also change the restored latest owner source.
        try {AssistantRuntime.reconcileOwnerSourceAuthority(store);}
        catch(java.io.IOException|RuntimeException invalid){startupState=state(false,"owner_source_reconciliation_failed",invalid.getClass().getSimpleName());return;}
        initializeReadyComponents();
    }

    private boolean hasSourceDeletionState() {
        for(String name:new String[]{"source-deletions.anchor.json","source-deletions.key","source-deletions","owner-policy-portable.json","owner-policy-portable.anchor.json"}) {
            java.io.File path=new java.io.File(getFilesDir(),name);
            if(path.exists()||java.nio.file.Files.isSymbolicLink(path.toPath()))return true;
        }
        return false;
    }

    private void initializeReadyComponents() {
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
        com.hoyoung.assistant.store.ExternalOwnerPolicyFolder folder=new com.hoyoung.assistant.store.ExternalOwnerPolicyFolder(this);
        if(folder.connected()){folder.markPending();scheduleOwnerPolicyMirror(true);}
    }

    /** Native owner UI only, called on an IO worker. Serializes index maintenance and backups. */
    public org.json.JSONObject controlStoredSource(String type,long id,String hash,boolean deletePayload) throws java.io.IOException {
        if(!startupReady)throw new java.io.IOException("Private source storage is not ready");
        if(!sourceControlPending.compareAndSet(false,true))throw new java.io.IOException("Another source control is running");
        try {
            java.util.concurrent.Future<org.json.JSONObject> work=maintenance.submit(()->
                    files.withExclusiveStorageMaintenance(()->runtime.controlStoredSource(type,id,hash,deletePayload)));
            try {org.json.JSONObject result=work.get();
                com.hoyoung.assistant.store.ExternalOwnerPolicyFolder folder=new com.hoyoung.assistant.store.ExternalOwnerPolicyFolder(this);
                if(folder.connected()){folder.markPending();scheduleOwnerPolicyMirror(true);}
                try{result.put("externalPolicy",folder.status());}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}
                return result;}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException("Source control remains unconfirmed; do not claim deletion",interrupted);}
            catch(java.util.concurrent.ExecutionException failed){
                Throwable cause=failed.getCause();
                if(cause instanceof java.io.IOException)throw (java.io.IOException)cause;
                if(cause instanceof RuntimeException)throw (RuntimeException)cause;
                throw new java.io.IOException("Source control failed",cause);
            }
        } finally {sourceControlPending.set(false);}
    }

    /** The owner must select a shared folder again after reinstall because Android drops SAF grants. */
    public org.json.JSONObject connectOwnerPolicyFolder(android.net.Uri tree,int flags)throws java.io.IOException {
        if(!startupReady)throw new java.io.IOException("Private memory storage is not ready");
        if(!sourceControlPending.compareAndSet(false,true))throw new java.io.IOException("Another owner memory control is running");
        try{
            java.util.concurrent.Future<org.json.JSONObject> work=maintenance.submit(()->{
                com.hoyoung.assistant.store.ExternalOwnerPolicyFolder folder=new com.hoyoung.assistant.store.ExternalOwnerPolicyFolder(this);
                org.json.JSONObject controls=folder.readFromFolder(tree);
                if(controls!=null)files.withExclusiveStorageMaintenance(()->runtime.importOwnerControls(controls));
                folder.rememberFolder(tree,flags);folder.markPending();return folder.status();
            });
            try{return work.get();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException("Owner-folder reconnection remains unconfirmed",interrupted);}
            catch(java.util.concurrent.ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof java.io.IOException)throw (java.io.IOException)cause;if(cause instanceof RuntimeException)throw (RuntimeException)cause;throw new java.io.IOException("Owner-folder connection failed",cause);}
        }finally{sourceControlPending.set(false);scheduleOwnerPolicyMirror(true);}
    }
    public org.json.JSONObject ownerPolicyFolderStatus(){return new com.hoyoung.assistant.store.ExternalOwnerPolicyFolder(this).status();}
    public String exportBackupToOwnerFolder()throws java.io.IOException {
        com.hoyoung.assistant.store.ExternalOwnerPolicyFolder folder=new com.hoyoung.assistant.store.ExternalOwnerPolicyFolder(this);
        long generation=folder.requestedGeneration();
        org.json.JSONObject controls=files.withExclusiveStorageMaintenance(()->com.hoyoung.assistant.store.OwnerPolicyBackup.capture(this,store));
        folder.sync(controls,generation);return folder.exportMemoryBackup(files,store);
    }
    private void scheduleOwnerPolicyMirror(boolean immediate){
        if(!startupReady)return;
        com.hoyoung.assistant.store.ExternalOwnerPolicyFolder folder=new com.hoyoung.assistant.store.ExternalOwnerPolicyFolder(this);
        if(!folder.connected()||!folder.pending()||!immediate&&android.os.SystemClock.elapsedRealtime()-policyMirrorAttemptAt<60_000||!policyMirroring.compareAndSet(false,true))return;
        policyMirrorAttemptAt=android.os.SystemClock.elapsedRealtime();
        policyMirror.execute(()->{
            try{
                long generation=folder.requestedGeneration();
                org.json.JSONObject controls=files.withExclusiveStorageMaintenance(()->com.hoyoung.assistant.store.OwnerPolicyBackup.capture(this,store));
                folder.sync(controls,generation);
            }catch(java.io.IOException|RuntimeException failure){folder.failure(failure);}
            finally{policyMirroring.set(false);}
        });
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
        scheduleOwnerPolicyMirror(false);
        if (!startupReady||sourceControlPending.get()||runtime.isRestoringBackup()) return;
        boolean enabled = "true".equals(store.getSetting("semanticSearchEnabled", "true"));
        memoryRetriever.setSemanticEnabled(enabled);
        if (!enabled) return;
        semanticEncoder.prepareAsync();
        if (!semanticEncoder.isReady() || !indexing.compareAndSet(false, true)) return;
        maintenance.execute(() -> {
            try {if(!sourceControlPending.get()&&!runtime.isRestoringBackup())memoryRetriever.indexPending(8, 2500); }
            catch (RuntimeException error) { store.recordEvent("semantic_index_deferred", new org.json.JSONObject()); }
            finally { indexing.set(false); }
        });
    }
}
