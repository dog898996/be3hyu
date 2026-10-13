package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.net.ConnectivityManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
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
import com.hoyoung.assistant.phone.PhoneEventJournal;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
            scenario.onActivity(activity -> {
                View focused = activity.getCurrentFocus();
                if (focused != null) {
                    InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
                    if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                    focused.clearFocus();
                }
            });
            device.waitForIdle(1_000);
            new UiScrollable(new UiSelector().scrollable(true)).scrollTextIntoView("설정 저장");
            UiObject2 save = device.wait(Until.findObject(By.text("설정 저장").clazz(Button.class)), 10_000);
            assertNotNull(save); save.click();
            UiObject2 acknowledgement = device.wait(Until.findObject(By.text("확인")), 10_000);
            assertNotNull("Actual Save click must open its dialog; persisted interval="
                    + app.getStore().getSetting("heartbeatSeconds", "missing"), acknowledgement);
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

    @Test public void eActualApplicationJournalBackupCapturesArchivedAndPendingOriginals() throws Exception {
        HoyoungApplication app = (HoyoungApplication) context.getApplicationContext();
        PhoneEventJournal journal = PhoneEventJournal.forContext(app);
        String source = "device-fixture.backup/" + UUID.randomUUID();
        File journalRoot = new File(app.getFilesDir(), "device-journal");
        JSONObject archived = journal.append("device_fixture", source,
                new JSONObject().put("note", "ARCHIVED_APP_BACKUP_FIXTURE"), System.currentTimeMillis(), source + ":archived");
        assertTrue(archived.getBoolean("observationPersisted"));
        File archivedFile = null;
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (archivedFile == null && SystemClock.elapsedRealtime() < deadline) {
            archivedFile = findJournalOriginal(new File(journalRoot, "archive"), archived.getString("spoolId"));
            if (archivedFile == null) SystemClock.sleep(50);
        }
        assertNotNull("The actual app journal must ingest and retain its archived original", archivedFile);
        byte[] archivedBytes = Files.readAllBytes(archivedFile.toPath());
        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        byte[] pendingBytes;
        String pendingId;
        // Block only this fixture's SQL ingestion while exporting on the same reentrant
        // store owner. Journal callback persistence and capture do not take this monitor.
        synchronized (app.getStore()) {
            JSONObject pending = journal.append("device_fixture", source,
                    new JSONObject().put("note", "PENDING_APP_BACKUP_FIXTURE"), System.currentTimeMillis(), source + ":pending");
            assertTrue(pending.getBoolean("observationPersisted"));
            pendingId = pending.getString("spoolId");
            File pendingFile = findJournalOriginal(new File(journalRoot, "pending"), pendingId);
            assertNotNull("A durable callback must exist before its SQL ingestion", pendingFile);
            pendingBytes = Files.readAllBytes(pendingFile.toPath());
            assertEquals(1, app.getStore().deviceEvents("device_fixture", source, 0, 10).length());
            // No test-installed participant: this proves HoyoungApplication registered it.
            app.getFiles().exportBackup(exported, app.getStore());
        }
        File staged = new File(app.getCacheDir(), "device-backup-journal-" + UUID.randomUUID());
        assertTrue(staged.mkdirs());
        boolean pendingFound = false, archivedFound = false, segmentFound = false;
        try {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(exported.toByteArray()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.getName().startsWith("device-journal/")) continue;
                    String relative = entry.getName().substring("device-journal/".length());
                    File output = new File(staged, relative);
                    assertTrue("Fixture extraction must stay within the staged journal", output.getCanonicalPath().startsWith(staged.getCanonicalPath() + File.separator));
                    if (entry.isDirectory()) { if (!output.isDirectory()) assertTrue(output.mkdirs()); continue; }
                    if (!output.getParentFile().isDirectory()) assertTrue(output.getParentFile().mkdirs());
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8_192]; int count;
                    while ((count = zip.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    byte[] original = bytes.toByteArray(); Files.write(output.toPath(), original);
                    if (relative.startsWith("pending/") && relative.endsWith("-" + pendingId + ".json")) {
                        assertArrayEquals(pendingBytes, original); pendingFound = true;
                    }
                    if (relative.startsWith("archive/segments/") && relative.endsWith(".hja")) {
                        segmentFound = true;
                        if (containsBytes(original, archivedBytes)) archivedFound = true;
                    }
                }
            }
            assertTrue("The app-registered participant must capture pending originals outside SQLite", pendingFound);
            assertTrue("The actual app backup must pack archived raw originals into a verified HJA segment", segmentFound);
            assertTrue("Archived original bytes must be retained exactly inside that segment", archivedFound);
            PhoneEventJournal.validateBackupSnapshot(staged);
            File cacheOnly = new File(app.getCacheDir(), "device-backup-discardable-" + UUID.randomUUID());
            Files.write(cacheOnly.toPath(), new byte[]{1, 2, 3}); assertTrue(cacheOnly.delete());
            assertArrayEquals("Removing cache must not change the private archived original", archivedBytes, Files.readAllBytes(archivedFile.toPath()));
            System.out.println("HOYOUNG_DEVICE_APP_JOURNAL_BACKUP registered-application-participant; exact-archived-and-pending-originals; verified-segment; no-live-database-restore");
            android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_DEVICE_APP_JOURNAL_BACKUP registered-application-participant; exact-archived-and-pending-originals; verified-segment; no-live-database-restore");
        } finally { FixtureContext.remove(staged); }
    }

    @Test public void fActualChatThumbnailAndNativeViewerUseImmutablePhotoOriginal() throws Exception {
        HoyoungApplication app = (HoyoungApplication) context.getApplicationContext();
        String name = "DEVICE_PHOTO_" + UUID.randomUUID() + ".png";
        Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)); bitmap.recycle();
        JSONObject working = app.getFiles().writeFile("device-fixtures/" + name, new ByteArrayInputStream(png.toByteArray()));
        JSONArray immutable = app.getFiles().freezeAttachments(new JSONArray().put(working));
        String requestId = "device-photo-fixture-" + UUID.randomUUID();
        app.getStore().putSetting("attachmentMetadata:" + requestId, immutable.toString());
        app.getStore().addMessage("user", "DEVICE_PHOTO_FIXTURE", requestId);
        app.getFiles().writeFile(working.getString("id"), "mutable working file replaced after original capture");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertTrue("The actual chat must expose the attachment thumbnail", device.wait(Until.hasObject(By.desc(name)), 15_000));
            boolean[] decoded = {false};
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            while (!decoded[0] && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> {
                    ImageView image = findImage(activity.getWindow().getDecorView(), name);
                    if (image != null && image.getDrawable() instanceof BitmapDrawable) {
                        Bitmap rendered = ((BitmapDrawable) image.getDrawable()).getBitmap();
                        assertEquals(32, rendered.getWidth()); assertEquals(24, rendered.getHeight());
                        assertEquals(Color.RED, rendered.getPixel(16, 12)); decoded[0] = true;
                    }
                });
                if (!decoded[0]) SystemClock.sleep(50);
            }
            assertTrue("Android must actually decode immutable image bytes, not just display a placeholder", decoded[0]);
            device.findObject(By.desc(name)).click();
            UiObject2 close = device.wait(Until.findObject(By.text("닫기")), 10_000);
            assertNotNull("The real thumbnail click must open the native image-viewer dialog", close);
            assertTrue(device.hasObject(By.text(name))); close.click();
        }
        ByteArrayOutputStream preserved = new ByteArrayOutputStream();
        app.getFiles().exportFile(immutable.getJSONObject(0).getString("id"), preserved);
        assertArrayEquals(png.toByteArray(), preserved.toByteArray());
        System.out.println("HOYOUNG_DEVICE_PHOTO_UI actual-private-PNG-decoded; immutable-source-thumbnail; native-viewer-opened; no-cloud-upload-claimed");
        android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_DEVICE_PHOTO_UI actual-private-PNG-decoded; immutable-source-thumbnail; native-viewer-opened; no-cloud-upload-claimed");
    }

    private static ImageView findImage(View view, String description) {
        if (view instanceof ImageView && description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return (ImageView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { ImageView image = findImage(group.getChildAt(i), description); if (image != null) return image; }
        }
        return null;
    }

    private static File findJournalOriginal(File parent, String spoolId) {
        File[] files = parent.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith("-" + spoolId + ".json")) return file;
            if (file.isDirectory() && file.getName().matches("[0-9]{1,20}")) {
                File found = findJournalOriginal(file, spoolId); if (found != null) return found;
            }
        }
        return null;
    }
    private static boolean containsBytes(byte[] container, byte[] original) {
        for (int at = 0; at <= container.length - original.length; at++) {
            int i = 0; while (i < original.length && container[at + i] == original[i]) i++;
            if (i == original.length) return true;
        }
        return false;
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
