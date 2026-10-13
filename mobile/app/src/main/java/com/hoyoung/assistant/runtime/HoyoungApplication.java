package com.hoyoung.assistant.runtime;

import android.app.Application;

import com.hoyoung.assistant.phone.PhoneTools;
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

    @Override public void onCreate() {
        super.onCreate();
        store = new HoyoungStore(this);
        files = new InternalFiles(this);
        phoneTools = new PhoneTools(this);
        runtime = new AssistantRuntime(this, store, files, phoneTools);
        webController = new WorkWebController(this, runtime, files);
        runtime.attachTransport(webController);
        webController.start();
    }

    public AssistantRuntime getRuntime() { return runtime; }
    public HoyoungStore getStore() { return store; }
    public InternalFiles getFiles() { return files; }
    public PhoneTools getPhoneTools() { return phoneTools; }
    public WorkWebController getWebController() { return webController; }
}
