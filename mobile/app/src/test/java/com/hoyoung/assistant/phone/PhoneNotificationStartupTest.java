package com.hoyoung.assistant.phone;

import android.app.Application;
import android.app.Notification;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.provider.Settings;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.HoyoungStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class PhoneNotificationStartupTest {
    private Context context;
    private HoyoungApplication app;
    private HoyoungStore store;
    private PhoneEventJournal journal;
    private HoyoungNotificationListener listener;
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); clear(context.getFilesDir());
        app = new HoyoungApplication(); Context base = new ContextWrapper(context) { @Override public Context getApplicationContext() { return app; } };
        Method attach = Application.class.getDeclaredMethod("attach", Context.class); attach.setAccessible(true); attach.invoke(app, base);
        store = new HoyoungStore(app); field(app, "store").set(app, store); journal = PhoneEventJournal.bind(app, store); journal.ingestPendingForTests();
        listener = new HoyoungNotificationListener(); Method serviceAttach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        serviceAttach.setAccessible(true); serviceAttach.invoke(listener, app); Field application = Service.class.getDeclaredField("mApplication"); application.setAccessible(true); application.set(listener, app);
        Settings.Secure.putString(app.getContentResolver(), "enabled_notification_listeners", new ComponentName(app, HoyoungNotificationListener.class).flattenToString());
        Notification notification = new Notification(); notification.extras.putCharSequence(Notification.EXTRA_TEXT, "active-before-startup-ready");
        Shadows.shadowOf(listener).addActiveNotification("example.chat", 1, notification);
    }
    @After public void cleanup() throws Exception {
        listener.onDestroy(); journal.ingestPendingForTests(); journal.closeWorker(); journal.awaitClosedForTests(); store.close();
        for (String name : new String[]{"maintenance", "policyMirror"}) ((ExecutorService) field(app, name).get(app)).shutdownNow();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME); clear(context.getFilesDir());
    }
    @Test public void connectedBeforeStartupReadyCapturesExistingNotificationsOnceAfterPolicyRefresh() throws Exception {
        journal.applyPermissionPolicyFromOwner("{}", false);
        listener.onListenerConnected(); assertEquals(0, store.deviceEvents("notification", null, 0, 100).length());
        field(app, "startupReady").setBoolean(app, true);
        HoyoungNotificationListener.onStartupReady(app); finishBackgroundSnapshot();
        JSONArray records = store.deviceEvents("notification", null, 0, 100);
        assertEquals(1, records.length()); assertEquals("active_on_connect", records.getJSONObject(0).getJSONObject("payload").getString("event"));
        assertEquals("active-before-startup-ready", records.getJSONObject(0).getJSONObject("payload").getString("text"));
        HoyoungNotificationListener.onStartupReady(app); finishBackgroundSnapshot();
        assertEquals("A readiness notification cannot schedule another connection snapshot", 1, store.deviceEvents("notification", null, 0, 100).length());
    }
    @Test public void disconnectedListenerCannotReplayItsQueuedStartupSnapshot() throws Exception {
        listener.onListenerConnected(); listener.onListenerDisconnected(); field(app, "startupReady").setBoolean(app, true);
        HoyoungNotificationListener.onStartupReady(app); finishBackgroundSnapshot();
        assertEquals(0, store.deviceEvents("notification", null, 0, 100).length());
    }
    private void finishBackgroundSnapshot() throws Exception {
        CountDownLatch ready = new CountDownLatch(1); assertTrue(journal.runBackground(ready::countDown)); assertTrue(ready.await(5, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle(); journal.ingestPendingForTests();
    }
    private static Field field(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field; }
    private static void clear(java.io.File file) { if (file.isDirectory()) { java.io.File[] children = file.listFiles(); if (children != null) for (java.io.File child : children) clear(child); } if (file.exists()) assertTrue(file.delete()); }
}
