package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.net.ConnectivityManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
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
import com.hoyoung.assistant.runtime.AssistantReplyNotifications;
import com.hoyoung.assistant.runtime.DispatchPayloadRegistry;
import com.hoyoung.assistant.runtime.GoalLedger;
import com.hoyoung.assistant.phone.PhoneEventJournal;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import com.hoyoung.assistant.store.MemoryGateway;
import com.hoyoung.assistant.store.SourcePrivacyLedger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.xmlpull.v1.XmlPullParser;

/** Real Android lifecycle/filesystem tests. Only fresh, offline emulator fixtures are permitted. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class DeviceRuntimeIntegrationTest {
    private Context context;
    private UiDevice device;
    private boolean disposableOfflineFixtureConfirmed;

    @Rule public final TestWatcher failureDiagnostics = new TestWatcher() {
        @Override protected void failed(Throwable failure, Description description) {
            captureFailureUiFixture(description.getMethodName());
        }
    };

    private void captureFailureUiFixture(String test) {
        if (!disposableOfflineFixtureConfirmed || device == null) return;
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            JSONObject state = new JSONObject().put("test", test)
                    .put("currentUiPackage", device.getCurrentPackageName())
                    .put("screenInteractive", power.isInteractive()).put("keyguardLocked", keyguard.isKeyguardLocked());
            ByteArrayOutputStream hierarchy = new ByteArrayOutputStream(); device.dumpWindowHierarchy(hierarchy);
            XmlPullParser parser = android.util.Xml.newPullParser();
            parser.setInput(new StringReader(hierarchy.toString(StandardCharsets.UTF_8.name())));
            JSONArray nodes = new JSONArray();
            java.util.List<String> publicLabels = java.util.Arrays.asList("설정", "확인", "파일", "Wait", "Close app", "System UI isn't responding");
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT && nodes.length() < 80; event = parser.next()) {
                if (event != XmlPullParser.START_TAG || !"node".equals(parser.getName())) continue;
                String text = parser.getAttributeValue(null, "text");
                JSONObject node = new JSONObject().put("package", parser.getAttributeValue(null, "package"))
                        .put("class", parser.getAttributeValue(null, "class")).put("bounds", parser.getAttributeValue(null, "bounds"))
                        .put("enabled", parser.getAttributeValue(null, "enabled")).put("clickable", parser.getAttributeValue(null, "clickable"))
                        .put("textLength", text == null ? 0 : text.length());
                if (publicLabels.contains(text)) node.put("publicLabel", text);
                nodes.put(node);
            }
            state.put("uiNodesWithoutPrivateText", nodes);
            logUiFixtureChunks("HOYOUNG_UI_FAILURE_DIAG", test, state.toString());
            // The driver creates a wiped, offline AVD and never imports an account.
            // A small test-only screenshot goes to free CI logs, not an uploaded artifact.
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                ByteArrayOutputStream image = new ByteArrayOutputStream();
                screenshot.compress(Bitmap.CompressFormat.JPEG, 40, image); screenshot.recycle();
                logUiFixtureChunks("HOYOUNG_UI_FAILURE_JPEG_BASE64", test,
                        android.util.Base64.encodeToString(image.toByteArray(), android.util.Base64.NO_WRAP));
            }
        } catch (Exception unavailable) { android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_UI_FAILURE_DIAG_UNAVAILABLE " + unavailable.getClass().getSimpleName()); }
    }

    private static void logUiFixtureChunks(String marker, String test, String payload) {
        int size = 2_000, chunks = (payload.length() + size - 1) / size;
        for (int part = 0; part < chunks; part++) {
            String header = marker + " " + test + " " + part + "/" + chunks + " ";
            android.util.Log.i("HoyoungDeviceProof", header + payload.substring(part * size, Math.min(payload.length(), (part + 1) * size)));
        }
    }

    @Before public void requireFixtureEmulator() throws Exception {
        assertTrue("These tests must never mutate a physical phone", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.hoyoung.assistant", context.getPackageName());
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertNull("The driver must disable all emulator networking before real app tests", connectivity.getActiveNetwork());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        disposableOfflineFixtureConfirmed = true;
        device.wakeUp();
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("wm dismiss-keyguard"))) {
            byte[] buffer = new byte[512]; while (output.read(buffer) != -1) { }
        }
        device.waitForIdle(1_000);
        assertTrue("The real emulator display must be awake before UI interactions", context.getSystemService(PowerManager.class).isInteractive());
        assertFalse("A keyguard must not cover the app's actual UI", context.getSystemService(KeyguardManager.class).isKeyguardLocked());
    }

    @Test public void aActualSettingsButtonPersistsAndBackupOpensAndroidDocumentPicker() throws Exception {
        HoyoungApplication app = (HoyoungApplication) context.getApplicationContext();
        String previous = app.getStore().getSetting("heartbeatSeconds", "300");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertTrue(DeviceFixtureHealth.waitFor(device, Until.hasObject(By.text("설정")), 15_000));
            device.findObject(By.text("설정")).click();
            assertTrue(DeviceFixtureHealth.waitFor(device, Until.hasObject(By.text("자율 모드 켜기")), 10_000));
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
            new UiScrollable(new UiSelector().className(ScrollView.class.getName()).scrollable(true)).scrollTextIntoView("설정 저장");
            boolean[] fullyVisible = {false};
            for (int attempt = 0; attempt < 6 && !fullyVisible[0]; attempt++) {
                int[] geometry = new int[4];
                scenario.onActivity(activity -> {
                    TextView actual = findText(activity.getWindow().getDecorView(), "설정 저장");
                    assertTrue("The actual settings Save control must remain a Button", actual instanceof Button);
                    Rect visible = new Rect(), screen = new Rect();
                    activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(screen);
                    boolean isVisible = actual.getGlobalVisibleRect(visible);
                    fullyVisible[0] = isVisible && actual.getHeight() > 0 && visible.height() == actual.getHeight()
                            && visible.width() == actual.getWidth() && screen.contains(visible.centerX(), visible.centerY());
                    int[] location = new int[2]; actual.getLocationOnScreen(location);
                    Rect scrollViewport = new Rect(screen);
                    android.view.ViewParent parent = actual.getParent();
                    while (parent instanceof View && !(parent instanceof ScrollView)) parent = parent.getParent();
                    if (parent instanceof ScrollView) ((ScrollView) parent).getGlobalVisibleRect(scrollViewport);
                    geometry[0] = scrollViewport.centerX(); geometry[1] = location[1] + actual.getHeight() / 2;
                    geometry[2] = scrollViewport.top; geometry[3] = scrollViewport.bottom;
                });
                if (!fullyVisible[0]) {
                    int center = (geometry[2] + geometry[3]) / 2;
                    int start = geometry[1] > center ? geometry[3] - 100 : geometry[2] + 100;
                    int end = geometry[1] > center ? start - 180 : start + 180;
                    device.swipe(geometry[0], start, geometry[0], end, 16); device.waitForIdle(1_000);
                }
            }
            assertTrue("Save must be fully visible before the real touchscreen click", fullyVisible[0]);
            UiObject2 save = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("설정 저장").clazz(Button.class)), 10_000);
            assertNotNull(save); save.click();
            UiObject2 acknowledgement = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("확인")), 10_000);
            if (acknowledgement == null) captureFailureUiFixture("aActualSettingsButtonPersistsAndBackupOpensAndroidDocumentPicker-before-close");
            assertNotNull("Actual Save click must open its dialog; persisted interval="
                    + app.getStore().getSetting("heartbeatSeconds", "missing"), acknowledgement);
            assertEquals("120", app.getStore().getSetting("heartbeatSeconds", ""));
            acknowledgement.click();
            UiObject2 filesTab = DeviceFixtureHealth.waitFor(device,
                    Until.findObject(By.text("파일").clazz(Button.class)), 10_000);
            if (filesTab == null) captureFailureUiFixture("aActualSettingsButtonPersistsAndBackupOpensAndroidDocumentPicker-files-tab-before-close");
            assertNotNull("The real Files tab must return after the Save dialog dismisses", filesTab);
            filesTab.click();
            UiObject2 export = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("대화·기억·파일 백업 내보내기")), 10_000);
            assertNotNull(export); export.click();
            assertTrue("Real platform SAF picker must receive the backup request", DeviceFixtureHealth.waitFor(device, Until.hasObject(By.pkg("com.android.documentsui")), 10_000));
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
            assertTrue(DeviceFixtureHealth.waitFor(device, Until.hasObject(By.text("설정")), 15_000));
            device.findObject(By.text("설정")).click();
            UiObject2 enable = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("자율 모드 켜기")), 10_000);
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
            assertTrue("The actual chat must expose the attachment thumbnail", DeviceFixtureHealth.waitFor(device, Until.hasObject(By.desc(name)), 15_000));
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
            UiObject2 close = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("닫기")), 10_000);
            assertNotNull("The real thumbnail click must open the native image-viewer dialog", close);
            assertTrue(device.hasObject(By.text(name))); close.click();
        }
        ByteArrayOutputStream preserved = new ByteArrayOutputStream();
        app.getFiles().exportFile(immutable.getJSONObject(0).getString("id"), preserved);
        assertArrayEquals(png.toByteArray(), preserved.toByteArray());
        System.out.println("HOYOUNG_DEVICE_PHOTO_UI actual-private-PNG-decoded; immutable-source-thumbnail; native-viewer-opened; no-cloud-upload-claimed");
        android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_DEVICE_PHOTO_UI actual-private-PNG-decoded; immutable-source-thumbnail; native-viewer-opened; no-cloud-upload-claimed");
    }

    @Test public void gActualChatPagesFortyMessagesAndOpensLargeUnicodeOriginal() throws Exception {
        HoyoungApplication app = (HoyoungApplication) context.getApplicationContext();
        String marker = "DEVICE_PAGE_" + UUID.randomUUID();
        for (int i = 1; i <= 95; i++) {
            String text = marker + " row " + i;
            if (i == 95) text += " 😀한글 " + "😀".repeat(20_000);
            app.getStore().addMessage("user", text, null);
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            java.util.List<String> latest = awaitPageTexts(scenario, marker, " row 95", 40);
            assertTrue(latest.stream().allMatch(text -> text.length() <= 8_000));
            String excerpt = latest.stream().filter(text -> text.startsWith(marker + " row 95")).findFirst().get();
            assertTrue(excerpt.contains("😀한글")); assertFalse(Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 1)));
            UiObject2 original = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("보관된 전체 원문 읽기")), 10_000);
            assertNotNull("The large message must offer its preserved original", original); original.click();
            assertTrue(DeviceFixtureHealth.waitFor(device, Until.hasObject(By.text("다음 부분")), 10_000));
            device.findObject(By.text("닫기")).click();
            UiObject2 previous = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("이전 대화 40개")), 10_000);
            assertNotNull(previous); previous.click();
            java.util.List<String> older = awaitPageTexts(scenario, marker, " row 55", 40);
            assertTrue(older.stream().noneMatch(text -> text.startsWith(marker + " row 95")));
            device.findObject(By.text("이전 대화 40개")).click();
            java.util.List<String> oldest = awaitPageTexts(scenario, marker, " row 1", 15);
            assertTrue(oldest.stream().noneMatch(text -> text.startsWith(marker + " row 55")));
            device.findObject(By.text("최신 대화")).click();
            awaitPageTexts(scenario, marker, " row 95", 40);
            android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_DEVICE_PAGED_CHAT 95-synthetic-messages; actual-40-row-view-tree-pages; bounded-8000-char-Unicode-preview; preserved-original-dialog; no-live-account");
        }
    }

    @Test public void hActualAndroidReplyNotificationOpensOlderSourceAndMarksOnlyVisibleHash() throws Exception {
        HoyoungApplication app = (HoyoungApplication) context.getApplicationContext();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String previous = app.getStore().getSetting("replyNotificationsEnabled", "true");
        String body = "DEVICE_REPLY_NOTIFY_" + UUID.randomUUID();
        JSONObject source = app.getStore().addMessage("assistant", body, null);
        long id = source.getLong("id"); String hash = source.getString("content_hash");
        try {
            device.pressHome();
            app.getStore().putSetting("replyNotificationsEnabled", "true");
            assertFalse("Closing the actual conversation must reset app notification visibility", app.getReplyNotifications().state().getBoolean("conversationVisible"));
            assertTrue(app.getReplyNotifications().publish(source, "completed").getBoolean("queued"));
            android.service.notification.StatusBarNotification posted = null;
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            while (posted == null && SystemClock.elapsedRealtime() < deadline) {
                for (android.service.notification.StatusBarNotification candidate : manager.getActiveNotifications()) {
                    if (candidate.getId() == AssistantReplyNotifications.NOTIFICATION_ID
                            && hash.equals(candidate.getNotification().extras.getString(AssistantReplyNotifications.EXTRA_SOURCE_HASH))) posted = candidate;
                }
                if (posted == null) SystemClock.sleep(50);
            }
            assertNotNull("Android must actually post the saved assistant reply", posted);
            assertEquals(id, posted.getNotification().extras.getLong(AssistantReplyNotifications.EXTRA_SOURCE_ID));
            // Android can expose notify() before the worker commits its dispatch receipt.
            JSONObject postedReceipt = null;
            deadline = SystemClock.elapsedRealtime() + 10_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                JSONObject receipt = new JSONObject(app.getStore().getSetting("replyNotification:" + id, "{}"));
                if ("posted".equals(receipt.optString("status")) && hash.equals(receipt.optString("sourceHash"))) {
                    postedReceipt = receipt; break;
                }
                SystemClock.sleep(50);
            }
            assertNotNull("The worker must commit this exact posted source hash", postedReceipt);
            assertFalse("Posting a notification does not prove conversation display", app.getReplyNotifications().state().getBoolean("readConfirmed"));
            for (int i = 0; i < 60; i++) app.getStore().addMessage("user", "DEVICE_AFTER_NOTIFICATION " + i, null);
            assertEquals("posted", new JSONObject(app.getStore().getSetting("replyNotification:" + id, "{}")).getString("status"));
            device.openNotification();
            UiObject2 notification = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text(body)), 10_000);
            assertNotNull("The real System UI notification must expose this synthetic message", notification); notification.click();
            assertTrue("The OS tap must open the older source, not merely the latest forty rows", DeviceFixtureHealth.waitFor(device, Until.hasObject(By.text(body).pkg(context.getPackageName())), 15_000));
            JSONObject seen = null; deadline = SystemClock.elapsedRealtime() + 10_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                JSONObject value = new JSONObject(app.getStore().getSetting("replyNotification:" + id, "{}"));
                if ("seen".equals(value.optString("status"))) { seen = value; break; }
                SystemClock.sleep(50);
            }
            assertNotNull("Only the actually displayed native body may acknowledge its hash", seen);
            assertEquals(hash, seen.getString("sourceHash")); assertTrue(seen.getBoolean("readConfirmedByUi"));
            assertFalse("UI display is not a claim that a human read it", app.getReplyNotifications().state().getBoolean("readConfirmed"));
            android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_DEVICE_REPLY_NOTIFICATION actual-Android-post; real-SystemUI-tap; older-source-page-anchor; exact-visible-source-hash; no-human-read-claim");
        } finally { app.getStore().putSetting("replyNotificationsEnabled", previous); manager.cancel("hoyoung_reply:" + id, AssistantReplyNotifications.NOTIFICATION_ID); device.pressHome(); }
    }

    @Test public void iActualFiveLayerSnapshotAndFinalPayloadGateRejectStalePrivacy() throws Exception {
        FixtureContext fixture = new FixtureContext(context);
        HoyoungStore store = new HoyoungStore(fixture);
        try {
            String marker = "FIVE_LAYER_DEVICE_NOTE_7312", rootId = "device-five-layer-root";
            String originalText = marker + "의 보관 규칙과 프로젝트 결정을 기록해.";
            store.enqueueRequest(originalText, "user", rootId);
            JSONObject original = store.addMessage("user", originalText, rootId);
            long sourceId = original.getLong("id");
            store.upsertMemory("device-semantic", marker + "의 보관 규칙", "fact", new JSONArray().put(sourceId));
            store.upsertMemory("device-episodic", marker + "의 프로젝트 결정", "project", new JSONArray().put(sourceId));
            store.upsertMemory("workflow-device-procedural", marker + "의 보고서는 정중하게 작성", "preference", new JSONArray().put(sourceId));
            store.putSetting("customInstructions", marker + "의 실제 사용자 형식 지침");
            new GoalLedger(store).update(rootId, "blocked", sourceId, rootId, marker + "는 재실행 금지", 0);
            String current = marker + "의 규칙을 확인해 줘.";
            long inputId = store.addMessage("user", current, rootId).getLong("id");
            store.putSetting("ownerInputRevision", String.valueOf(inputId));
            store.putSetting("latestOwnerRequestId", rootId);
            MemoryGateway gateway = new MemoryGateway(store);
            MemoryGateway.Prepared prepared = gateway.prepare("device-five-layer-dispatch", rootId, "user", inputId, current, null, 80_000);
            assertEquals("This check needs the real API35 readonly transaction", "android_readonly_transaction", prepared.diagnostics().getString("snapshotBackend"));
            assertEquals(5, prepared.receipts().length());
            java.util.Set<String> layers = new java.util.HashSet<>();
            for (int i = 0; i < prepared.receipts().length(); i++) {
                JSONObject receipt = prepared.receipts().getJSONObject(i);
                assertTrue(layers.add(receipt.getString("layer")));
                assertEquals("success", receipt.getString("status"));
                assertEquals(prepared.snapshotId, receipt.getString("snapshotId"));
                assertTrue(receipt.getJSONArray("queries").length() > 0);
            }
            assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("verbatim", "semantic", "episodic", "procedural", "active")), layers);
            assertTrue(prepared.context.contains(current));
            assertTrue(prepared.context.contains("device-semantic"));
            assertTrue(prepared.context.contains("device-episodic"));
            assertTrue(prepared.context.contains("workflow-device-procedural"));
            assertTrue(prepared.context.contains("재실행 금지"));
            java.util.Set<String> populatedMemoryLayers = new java.util.HashSet<>();
            for (String line : prepared.context.split("\n")) {
                if (line.startsWith("SEMANTIC ") && line.contains("\"key\":\"device-semantic\"")) populatedMemoryLayers.add("semantic");
                if (line.startsWith("EPISODIC ") && line.contains("\"key\":\"device-episodic\"")) populatedMemoryLayers.add("episodic");
                if (line.startsWith("PROCEDURAL ") && line.contains("\"key\":\"workflow-device-procedural\"")) populatedMemoryLayers.add("procedural");
            }
            assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("semantic", "episodic", "procedural")), populatedMemoryLayers);
            gateway.assertFresh(prepared);
            JSONObject payload = new JSONObject().put("requestId", prepared.dispatchId).put("text", prepared.context)
                    .put("requiredModel", "gpt-6.1-sol").put("requiredEffort", "medium").put("requiredSpeed", "fast")
                    .put("requiresNativeAuthorization", true).put("attachments", new JSONArray());
            DispatchPayloadRegistry registry = new DispatchPayloadRegistry();
            long registeredAt = SystemClock.elapsedRealtime();
            String digest = registry.register(prepared.dispatchId, payload, prepared.evidenceFingerprint, registeredAt);
            assertFalse(registry.consume(prepared.dispatchId, digest, "different-memory-fingerprint", SystemClock.elapsedRealtime()));
            assertTrue(registry.consume(prepared.dispatchId, digest, prepared.evidenceFingerprint, SystemClock.elapsedRealtime()));
            assertFalse("A final payload permit can be consumed only once", registry.consume(prepared.dispatchId, digest, prepared.evidenceFingerprint, SystemClock.elapsedRealtime()));
            store.getPrivacyLedger().excludeSource("message", sourceId, original.getString("content_hash"), SourcePrivacyLedger.RETRIEVAL_EXCLUDE, "synthetic device owner withdrawal");
            try { gateway.assertFresh(prepared); fail("An excluded source must invalidate a previously prepared context"); }
            catch (MemoryGateway.Blocked expected) { assertEquals("memory_privacy_changed", expected.reason); }
            android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_DEVICE_FIVE_LAYER_GATE actual-API35-readonly-SQLite; five-layer-query-receipts; canonical-evidence; one-use-final-payload; withdrawal-rejects-stale-scope; no-live-model-or-S24-claim");
        } finally { store.close(); fixture.removeOwnFixture(); }
    }

    private static java.util.List<String> awaitPageTexts(ActivityScenario<MainActivity> scenario, String marker, String requiredSuffix, int expectedCount) {
        java.util.List<String> texts = new java.util.ArrayList<>();
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> { texts.clear(); collectPageTexts(activity.getWindow().getDecorView(), marker, texts); });
            if (texts.size() == expectedCount && texts.stream().anyMatch(text -> text.startsWith(marker + requiredSuffix))) return new java.util.ArrayList<>(texts);
            SystemClock.sleep(50);
        }
        fail("The actual bounded chat view did not render " + expectedCount + " fixture messages for " + requiredSuffix + "; rendered=" + texts.size());
        return texts;
    }
    private static void collectPageTexts(View view, String marker, java.util.List<String> texts) {
        if (view instanceof TextView && ((TextView) view).getText().toString().startsWith(marker)) texts.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectPageTexts(group.getChildAt(i), marker, texts);
        }
    }

    private static ImageView findImage(View view, String description) {
        if (view instanceof ImageView && description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return (ImageView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { ImageView image = findImage(group.getChildAt(i), description); if (image != null) return image; }
        }
        return null;
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { TextView found = findText(group.getChildAt(i), text); if (found != null) return found; }
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
