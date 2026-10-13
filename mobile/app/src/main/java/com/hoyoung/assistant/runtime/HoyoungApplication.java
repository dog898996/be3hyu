package com.hoyoung.assistant.runtime;

import android.app.Application;

import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.phone.PhoneContextCollector;
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
    private final java.util.concurrent.ExecutorService maintenance = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean indexing = new java.util.concurrent.atomic.AtomicBoolean();

    @Override public void onCreate() {
        super.onCreate();
        store = new HoyoungStore(this);
        files = new InternalFiles(this);
        phoneTools = new PhoneTools(this, store);
        phoneContext = new PhoneContextCollector(this, phoneTools, store);
        semanticEncoder = new LocalSemanticEncoder(this);
        memoryRetriever = new MemoryRetriever(store, semanticEncoder);
        memoryRetriever.setDeviceSourceVisibility(phoneTools::canReadStoredSource);
        memoryRetriever.setSemanticCandidateProvider(new SemanticAnnCache(store, semanticEncoder, new java.io.File(getNoBackupFilesDir(), "derived-indexes/semantic-ann"), true));
        runtime = new AssistantRuntime(this, store, files, phoneTools);
        runtime.setMemoryRetriever(memoryRetriever);
        runtime.setPhoneContextCollector(phoneContext);
        webController = new WorkWebController(this, runtime, files);
        runtime.attachTransport(webController);
        webController.start();
    }

    public AssistantRuntime getRuntime() { return runtime; }
    public HoyoungStore getStore() { return store; }
    public InternalFiles getFiles() { return files; }
    public PhoneTools getPhoneTools() { return phoneTools; }
    public WorkWebController getWebController() { return webController; }
    public PhoneContextCollector getPhoneContext() { return phoneContext; }
    public MemoryRetriever getMemoryRetriever() { return memoryRetriever; }
    public LocalSemanticEncoder getSemanticEncoder() { return semanticEncoder; }

    public void maintainMemory() {
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
