package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.net.ConnectivityManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import com.hoyoung.assistant.runtime.AutonomyService;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

/** Real Android lifecycle/filesystem tests. Only fresh, offline emulator fixtures are permitted. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class DeviceRuntimeIntegrationTest {
    private Context context;
    private UiDevice device;

    @Before public void requireFixtureEmulator() {
        assertTrue("These tests must never mutate a physical phone", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.hoyoung.assistant", context.getPackageName());
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertNull("The driver must disable all emulator networking before real app tests", connectivity.getActiveNetwork());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    @Test public void aActualSettingsButtonPersistsAndBackupOpensAndroidDocumentPicker() throws Exception {
        HoyoungApplication app = (HoyoungApplication) context.getApplicationContext();
        String previous = app.getStore().getSetting("heartbeatSeconds", "300");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertTrue(device.wait(Until.hasObject(By.text("설정")), 15_000));
            device.findObject(By.text("설정")).click();
            assertTrue(device.wait(Until.hasObject(By.text("자율 모드 켜기")), 10_000));
            for (UiObject2 entry : device.findObjects(By.clazz(EditText.class))) {
                if (entry.getText() != null && entry.getText().matches("[0-9]+")) { entry.setText("120"); break; }
            }
            new UiScrollable(new UiSelector().scrollable(true)).scrollTextIntoView("설정 저장");
            UiObject2 save = device.wait(Until.findObject(By.text("설정 저장")), 10_000);
            assertNotNull(save); save.click();
            UiObject2 acknowledgement = device.wait(Until.findObject(By.text("확인")), 10_000);
            assertNotNull(acknowledgement);
            assertEquals("120", app.getStore().getSetting("heartbeatSeconds", ""));
            acknowledgement.click();
            device.findObject(By.text("파일")).click();
            UiObject2 export = device.wait(Until.findObject(By.text("대화·기억·파일 백업 내보내기")), 10_000);
            assertNotNull(export); export.click();
            assertTrue("Real platform SAF picker must receive the backup request", device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 10_000));
            device.pressBack();
        } finally { app.getStore().putSetting("heartbeatSeconds", previous); }
    }

    @Test public void bRealSQLiteOriginalRevisionsAndWorkingFileSurviveCacheCleanupAndBinaryRestore() throws Exception {
        FixtureContext fixture = new FixtureContext(context);
        HoyoungStore store = new HoyoungStore(fixture);
        try {
            InternalFiles files = new InternalFiles(fixture);
            String original = "DEVICE_FIXTURE_ORIGINAL " + UUID.randomUUID() + " ";
            original = original.repeat(2_000);
            long source = store.addMessage("user", original, null).getLong("id");
            store.upsertMemory("fixture/allergy", "strawberry allergy", "profile", new JSONArray().put(source));
            store.upsertMemory("fixture/allergy", "strawberry and kiwi allergy", "profile", new JSONArray().put(source));
            files.writeFile("fixture/program.py", "print('DEVICE_FIXTURE_FILE')\n");
            Files.write(new File(fixture.getCacheDir(), "discardable-cache").toPath(), new byte[]{1, 2, 3});
            for (File cached : fixture.getCacheDir().listFiles()) assertTrue(cached.delete());
            assertEquals(original, readOriginal(store, source));
            assertEquals("print('DEVICE_FIXTURE_FILE')\n", files.readText("fixture/program.py", 2_000));
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            files.exportBackup(archive, store);
            store.upsertMemory("fixture/allergy", "later temporary edit", "profile", new JSONArray().put(source));
            files.writeFile("fixture/program.py", "modified");
            files.importBackup(new ByteArrayInputStream(archive.toByteArray()), store);
            assertEquals("strawberry and kiwi allergy", store.getMemory("fixture/allergy").getString("content"));
            assertEquals(2, store.memoryRevisions("fixture/allergy", 10).length());
            assertEquals(original, readOriginal(store, source));
            assertEquals("print('DEVICE_FIXTURE_FILE')\n", files.readText("fixture/program.py", 2_000));
            assertEquals("false", store.getSetting("autonomy.enabled", "missing"));
            assertEquals("true", store.getSetting("runtime.restoreReviewRequired", "missing"));
            store.close(); store = new HoyoungStore(fixture);
            assertEquals(original, readOriginal(store, source));
            assertEquals("strawberry and kiwi allergy", store.getMemory("fixture/allergy").getString("content"));
        } finally { store.close(); fixture.removeOwnFixture(); }
    }

    @Test public void cForegroundServiceRemainsForegroundAcrossOneScreenOffMinute() throws Exception {
        HoyoungApplication app = (HoyoungApplication) context.getApplicationContext();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertTrue(device.wait(Until.hasObject(By.text("설정")), 15_000));
            device.findObject(By.text("설정")).click();
            UiObject2 enable = device.wait(Until.findObject(By.text("자율 모드 켜기")), 10_000);
            assertNotNull(enable); enable.click();
            awaitService(app, 10_000);
            assertTrue(actualForegroundService());
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean hasVisibleMode = false;
            for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications())
                if ("hoyoung_autonomy".equals(notification.getNotification().getChannelId())) hasVisibleMode = true;
            assertTrue("Android must actually post the ongoing foreground-mode notification", hasVisibleMode);
            device.pressHome(); device.sleep();
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            assertFalse(power.isInteractive());
            SystemClock.sleep(65_000);
            assertTrue(app.getRuntime().state().getBoolean("serviceRunning"));
            assertTrue("A cached application flag alone is not sufficient", actualForegroundService());
            System.out.println("HOYOUNG_DEVICE_SCREEN_OFF foreground-service-alive-after-65s; no 24-hour or physical-phone guarantee");
            android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_DEVICE_SCREEN_OFF foreground-service-alive-after-65s; no 24-hour or physical-phone guarantee");
        } finally {
            device.wakeUp(); app.getRuntime().stopAutonomy();
            context.stopService(new Intent(context, AutonomyService.class));
        }
    }

    @Test public void dAcknowledgedOwnerDataSurvivesExplicitProcessStop() throws Exception {
        HoyoungApplication app = (HoyoungApplication) context.getApplicationContext();
        File proof = new File(context.getFilesDir(), "device-fixture-recovery.json");
        boolean recovery = "true".equals(InstrumentationRegistry.getArguments().getString("recoveryOnly"));
        if (!recovery) {
            String original = "DEVICE_RECOVERY_ORIGINAL " + UUID.randomUUID();
            long source = app.getStore().addMessage("user", original, null).getLong("id");
            String key = "device-fixture/recovery/" + UUID.randomUUID();
            app.getStore().upsertMemory(key, "DEVICE_RECOVERY_MEMORY", "profile", new JSONArray().put(source));
            JSONObject marker = new JSONObject().put("messageId", source).put("original", original).put("memoryKey", key);
            Files.write(proof.toPath(), marker.toString().getBytes(StandardCharsets.UTF_8));
        }
        assertTrue("A second real process must find the first process's committed fixture", proof.isFile());
        JSONObject marker = new JSONObject(new String(Files.readAllBytes(proof.toPath()), StandardCharsets.UTF_8));
        assertEquals(marker.getString("original"), readOriginal(app.getStore(), marker.getLong("messageId")));
        assertEquals("DEVICE_RECOVERY_MEMORY", app.getStore().getMemory(marker.getString("memoryKey")).getString("content"));
        System.out.println(recovery ? "HOYOUNG_DEVICE_RECOVERY verified-after-explicit-force-stop" : "HOYOUNG_DEVICE_RECOVERY seed-committed");
        android.util.Log.i("HoyoungDeviceProof", recovery ? "HOYOUNG_DEVICE_RECOVERY verified-after-explicit-force-stop" : "HOYOUNG_DEVICE_RECOVERY seed-committed");
    }

    private boolean actualForegroundService() {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(100))
            if (AutonomyService.class.getName().equals(service.service.getClassName())) return service.foreground;
        return false;
    }
    private static void awaitService(HoyoungApplication app, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end && !app.getRuntime().state().optBoolean("serviceRunning")) SystemClock.sleep(100);
        assertTrue(app.getRuntime().state().optBoolean("serviceRunning"));
    }
    private static String readOriginal(HoyoungStore store, long id) throws Exception {
        StringBuilder text = new StringBuilder(); long offset = 0;
        for (;;) {
            JSONObject page = store.readOriginalText(id, offset, 4_096);
            text.append(page.getString("text"));
            if (page.getBoolean("complete")) return text.toString();
            long next = page.getLong("nextOffsetChars"); assertTrue(next > offset); offset = next;
        }
    }
    private static final class FixtureContext extends ContextWrapper {
        private final File root;
        FixtureContext(Context base) { super(base); root = new File(base.getFilesDir(), "device-fixtures/" + UUID.randomUUID()); assertTrue(root.mkdirs()); }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return directory("files"); }
        @Override public File getCacheDir() { return directory("cache"); }
        @Override public File getDatabasePath(String name) { return new File(directory("databases"), name); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
            return SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(), factory, SQLiteDatabase.CREATE_IF_NECESSARY, handler);
        }
        private File directory(String name) { File result = new File(root, name); if (!result.isDirectory()) assertTrue(result.mkdirs()); return result; }
        void removeOwnFixture() { remove(root); }
        private static void remove(File file) { if (file.isDirectory()) for (File child : file.listFiles()) remove(child); assertTrue(file.delete()); }
    }
}
