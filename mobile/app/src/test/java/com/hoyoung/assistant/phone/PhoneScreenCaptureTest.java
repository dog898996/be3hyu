package com.hoyoung.assistant.phone;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Looper;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import com.hoyoung.assistant.store.SourcePrivacyLedger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.SQLiteMode;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** Offline injected OS-callback fixtures; image encoding/SQLite/storage are native.
 * These tests do not claim that Android returned a real display screenshot. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneScreenCaptureTest {
    private Context context;
    private HoyoungStore store;
    private PhoneEventJournal journal;
    private FakeScreen backend;
    private PhoneScreenCapture captures;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication(); store = new HoyoungStore(context);
        journal = PhoneEventJournal.bind(context, store); journal.refreshPolicyBlocking();
        backend = new FakeScreen(); captures = new PhoneScreenCapture(context, journal, backend, Runnable::run);
    }
    @After public void cleanup() {
        captures.cancelAll("test_finished"); journal.closeWorker(); journal.awaitClosedForTests(); store.close();
    }
    private String request() throws Exception {
        JSONObject result = captures.execute("phone_screen_capture", new JSONObject());
        assertTrue(result.toString(), result.getBoolean("ok")); assertEquals("capture_requested", result.getString("code"));
        assertFalse(result.getBoolean("completionConfirmed")); return result.getString("requestId");
    }
    private JSONObject read(String id) throws Exception { journal.ingestPendingForTests(); return captures.execute("phone_screen_capture_read", new JSONObject().put("requestId", id)); }
    private void policy(String json) { store.putSetting("toolPermissions", json); journal.applyPermissionPolicyFromOwner(json, true); }
    private File projectFile(String id) { return new File(context.getFilesDir(), "projects/" + id); }
    private static Bitmap fixture() {
        Bitmap image = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888); image.eraseColor(Color.BLUE);
        image.setPixel(0, 0, Color.MAGENTA); image.setPixel(11, 7, Color.GREEN); return image;
    }

    @Test public void actualPngBytesAreImmutableHashVerifiedAndRemainADeviceObservation() throws Exception {
        String id = request(); JSONObject pending = read(id);
        assertEquals("pending", pending.getJSONObject("request").getString("state")); assertFalse(pending.has("asset"));
        Bitmap image = fixture(); backend.success(image, 444);
        assertTrue("Callback-owned bitmap must be released", image.isRecycled());
        JSONObject result = read(id), receipt = result.getJSONObject("request"), asset = result.getJSONObject("asset");
        assertTrue(result.toString(), result.getBoolean("uploadRequested")); assertTrue(result.getBoolean("completionConfirmed"));
        assertEquals("observed", receipt.getString("state")); assertEquals(444, receipt.getLong("frameTimestampMs"));
        assertEquals(12, receipt.getInt("width")); assertEquals(8, receipt.getInt("height"));
        assertEquals("tool_result", asset.getString("kind")); assertTrue(asset.getBoolean("immutable"));
        assertEquals("image/png", asset.getString("mime")); assertEquals("sha256_verified", asset.getString("integrity"));
        assertEquals("android.accessibility_screenshot", asset.getString("source")); assertEquals("device_observation", asset.getString("sourceKind"));
        assertFalse(asset.getBoolean("ownerUtterance")); assertFalse(result.getBoolean("imageReadingConfirmed"));
        assertTrue(receipt.getBoolean("protectedPixelsMayBeOmittedByAndroid")); assertFalse(receipt.getBoolean("captureCompletenessGuaranteed"));
        assertFalse(result.getBoolean("binaryUploaded")); assertFalse(asset.has("sourceMessageId"));
        File working = projectFile(receipt.getString("fileId")), immutable = projectFile(asset.getString("id"));
        assertTrue(working.isFile()); assertTrue(immutable.isFile()); assertArrayEquals(Files.readAllBytes(working.toPath()), Files.readAllBytes(immutable.toPath()));
        Bitmap decoded = BitmapFactory.decodeFile(immutable.getPath()); assertNotNull(decoded);
        try { assertEquals(Color.MAGENTA, decoded.getPixel(0, 0)); assertEquals(Color.GREEN, decoded.getPixel(11, 7)); }
        finally { decoded.recycle(); }
        assertTrue(receipt.optBoolean("observationPersisted")); assertTrue(receipt.getString("spoolId").length() > 0);
        journal.ingestPendingForTests(); JSONObject observed = read(id).getJSONObject("request");
        assertTrue(observed.getLong("deviceEventId") > 0);
        JSONObject event = journal.read(observed.getLong("deviceEventId")); assertEquals("capture_result", event.getString("category"));
        assertEquals("android.accessibility_screenshot", event.getString("source"));
        assertEquals("device_observation", event.getJSONObject("payload").getString("sourceKind"));
    }

    @Test public void missingAndroidGrantDoesNotDispatchOrCreateAReceipt() throws Exception {
        backend.granted = false;
        assertEquals("permission_missing", captures.execute("phone_screen_capture", new JSONObject()).getString("code"));
        assertEquals(0, backend.requests); assertFalse(projectFile("captures/screen").exists());
    }
    @Test public void unsupportedPlatformAndDisconnectedServiceAreTruthful() throws Exception {
        backend.api = 29; assertEquals("unsupported_android_version", captures.execute("phone_screen_capture", new JSONObject()).getString("code"));
        backend.api = 35; backend.connected = false; assertEquals("accessibility_service_unavailable", captures.execute("phone_screen_capture", new JSONObject()).getString("code"));
        assertEquals(0, backend.requests);
    }
    @Test public void ownerDisabledCaptureBlocksNativeDispatchAndRead() throws Exception {
        policy("{\"phone_screen_capture\":false}");
        assertEquals("permission_disabled", captures.execute("phone_screen_capture", new JSONObject()).getString("code")); assertEquals(0, backend.requests);
        assertEquals("permission_disabled", read("00000000-0000-0000-0000-000000000000").getString("code"));
    }
    @Test public void eachActualAndroidFailureIsStoredWithoutManufacturingAnImage() throws Exception {
        int[] codes = {AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR, AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS,
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT, AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY,
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW, AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW, 2345};
        for (int code : codes) {
            String id = request(); backend.failure(code); JSONObject receipt = read(id).getJSONObject("request");
            assertEquals("failed", receipt.getString("state")); assertEquals(code, receipt.getInt("androidError"));
            assertEquals(PhoneScreenCapture.platformCode(code), receipt.getString("code"));
            assertFalse(receipt.getBoolean("completionConfirmed")); assertFalse(receipt.has("asset"));
            assertFalse(projectFile("captures/screen/" + id + ".png").exists());
        }
    }
    @Test public void cancelledRequestDiscardsAndRecyclesTheLateBitmap() throws Exception {
        String id = request(); JSONObject cancelled = captures.execute("phone_screen_capture_cancel", new JSONObject().put("requestId", id));
        assertEquals("capture_cancelled", cancelled.getString("code")); assertTrue(cancelled.getBoolean("lateCallbackWillBeDiscarded"));
        Bitmap late = fixture(); backend.success(late, 10); assertTrue(late.isRecycled());
        assertEquals("cancelled", read(id).getJSONObject("request").getString("state"));
        assertFalse(projectFile("captures/screen/" + id + ".png").exists());
    }
    @Test public void restoredPendingReceiptIsUnknownAndDoesNotTriggerAnAutomaticCapture() throws Exception {
        String id = request(); FakeScreen freshBackend = new FakeScreen();
        PhoneScreenCapture restarted = new PhoneScreenCapture(context, journal, freshBackend, Runnable::run);
        JSONObject result = restarted.execute("phone_screen_capture_read", new JSONObject().put("requestId", id));
        assertEquals("outcome_unknown_after_process_restart", result.getJSONObject("request").getString("state"));
        assertFalse(result.getBoolean("automaticReplay")); assertEquals(0, freshBackend.requests);
        assertFalse(result.has("asset"));
    }
    @Test public void timeoutReceiptIsFinalAndLatePixelsAreRecycled() throws Exception {
        JSONObject started = captures.execute("phone_screen_capture", new JSONObject().put("timeoutMs", 1000)); String id = started.getString("requestId");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001));
        assertEquals("timed_out", read(id).getJSONObject("request").getString("state"));
        Bitmap late = fixture(); backend.success(late, 1); assertTrue(late.isRecycled()); assertFalse(read(id).has("asset"));
    }
    @Test public void corruptImmutablePixelsCannotBeUploadedFromAValidLookingReceipt() throws Exception {
        String id = request(); backend.success(fixture(), 1); JSONObject first = read(id);
        File immutable = projectFile(first.getJSONObject("asset").getString("id")); Files.write(immutable.toPath(), new byte[]{1, 2, 3});
        JSONObject changed = read(id); assertFalse(changed.getBoolean("ok")); assertEquals("capture_storage_unavailable", changed.getString("code"));
        assertFalse(changed.has("asset")); assertFalse(changed.optBoolean("uploadRequested"));
    }
    @Test public void completedPixelsAndReceiptCanBeReadAfterHelperRecreationWithoutServiceConnection() throws Exception {
        String id = request(); backend.success(fixture(), 10); JSONObject first = read(id);
        FakeScreen recreatedBackend = new FakeScreen(); recreatedBackend.connected = false;
        PhoneScreenCapture recreated = new PhoneScreenCapture(context, journal, recreatedBackend, Runnable::run);
        JSONObject restored = recreated.execute("phone_screen_capture_read", new JSONObject().put("requestId", id));
        assertEquals(first.getJSONObject("asset").getString("sha256"), restored.getJSONObject("asset").getString("sha256"));
        assertTrue(restored.getBoolean("uploadRequested")); assertEquals(0, recreatedBackend.requests);
    }
    @Test public void ownerExcludedScreenshotSourceCannotBeReuploadedAfterHelperRecreation() throws Exception {
        String id = request(); backend.success(fixture(), 1); JSONObject initial = read(id);
        JSONObject source = initial.getJSONArray("sourceRefs").getJSONObject(0);
        store.getPrivacyLedger().excludeSource("device_event", source.getLong("id"), source.getString("hash"), SourcePrivacyLedger.RETRIEVAL_EXCLUDE, "synthetic owner forget request");
        assertEquals("memory_source_excluded_or_missing", read(id).getString("code"));
        PhoneScreenCapture recreated = new PhoneScreenCapture(context, journal, new FakeScreen(), Runnable::run);
        JSONObject blocked = recreated.execute("phone_screen_capture_read", new JSONObject().put("requestId", id));
        assertFalse(blocked.getBoolean("ok")); assertFalse(blocked.has("asset")); assertFalse(blocked.optBoolean("uploadRequested"));
        assertTrue("Exclusion preserves original bytes while denying retrieval", projectFile(initial.getJSONObject("asset").getString("id")).exists());
    }
    @Test public void aValidUnrelatedFrozenImageCannotReplaceCanonicalCapturedBytesInTheReceipt() throws Exception {
        String id = request(); backend.success(fixture(), 1); JSONObject initial = read(id);
        String second = request(); Bitmap different = fixture(); different.eraseColor(Color.RED); backend.success(different, 2); JSONObject other = read(second);
        String receiptId = initial.getJSONObject("request").getString("receiptFileId");
        JSONObject tampered = new JSONObject(new InternalFiles(context).readText(receiptId, 32000)); tampered.put("asset", other.getJSONObject("asset"));
        new InternalFiles(context).writeFile(receiptId, tampered.toString());
        JSONObject denied = read(id); assertEquals("capture_origin_mismatch", denied.getString("code")); assertFalse(denied.has("asset"));
    }
    @Test public void restoringAnOlderFullBackupCannotResurrectAnExcludedScreenshot() throws Exception {
        String id = request(); backend.success(fixture(), 1); JSONObject observed = read(id);
        JSONObject source = observed.getJSONArray("sourceRefs").getJSONObject(0);
        InternalFiles portable = new InternalFiles(context);
        portable.setBackupParticipant(new InternalFiles.BackupParticipant() {
            @Override public void capture(File destination) throws java.io.IOException { journal.captureBackupSnapshot(destination); }
            @Override public void validate(File stagedJournal) throws java.io.IOException { PhoneEventJournal.validateBackupSnapshot(stagedJournal); }
        });
        ByteArrayOutputStream oldBackup = new ByteArrayOutputStream(); portable.exportBackup(oldBackup, store);
        store.getPrivacyLedger().excludeSource("device_event", source.getLong("id"), source.getString("hash"), SourcePrivacyLedger.RETRIEVAL_EXCLUDE, "synthetic owner forget after backup");
        journal.pauseForBackupRestore();
        try { portable.importBackup(new ByteArrayInputStream(oldBackup.toByteArray()), store); }
        finally { journal.afterBackupRestore(); journal.refreshPolicyBlocking(); }
        PhoneScreenCapture recreated = new PhoneScreenCapture(context, journal, new FakeScreen(), Runnable::run);
        JSONObject blocked = recreated.execute("phone_screen_capture_read", new JSONObject().put("requestId", id));
        assertEquals("memory_source_excluded_or_missing", blocked.getString("code")); assertFalse(blocked.has("asset"));
        assertFalse(blocked.optBoolean("uploadRequested"));
    }
    @Test public void grantOrOwnerToggleRevocationBeforeCallbackPreventsSavingPixels() throws Exception {
        String first = request(); backend.granted = false; Bitmap image = fixture(); backend.success(image, 1); assertTrue(image.isRecycled());
        backend.granted = true; assertEquals("permission_missing", read(first).getJSONObject("request").getString("code")); assertFalse(projectFile("captures/screen/" + first + ".png").exists());
        String second = request(); policy("{\"phone_screen_capture\":false}");
        Bitmap other = fixture(); backend.success(other, 1); assertTrue(other.isRecycled());
        policy("{}");
        assertEquals("permission_disabled", read(second).getJSONObject("request").getString("code")); assertFalse(projectFile("captures/screen/" + second + ".png").exists());
    }
    @Test public void disabledReadToggleDoesNotUploadAnAlreadySavedPng() throws Exception {
        String id = request(); backend.success(fixture(), 1); policy("{\"phone_screen_capture_read\":false}");
        JSONObject denied = read(id); assertEquals("permission_disabled", denied.getString("code")); assertFalse(denied.has("asset"));
    }
    @Test public void busyCaptureNeverReplaysAndDuplicateCallbackNeverOverwritesCompletedPixels() throws Exception {
        String id = request(); assertEquals("capture_busy", captures.execute("phone_screen_capture", new JSONObject()).getString("code")); assertEquals(1, backend.requests);
        backend.success(fixture(), 1); String sha = read(id).getJSONObject("asset").getString("sha256");
        Bitmap duplicate = fixture(); duplicate.eraseColor(Color.RED); backend.success(duplicate, 2); assertTrue(duplicate.isRecycled());
        assertEquals(sha, read(id).getJSONObject("asset").getString("sha256"));
        assertEquals("capture_not_pending", captures.execute("phone_screen_capture_cancel", new JSONObject().put("requestId", id)).getString("code"));
    }
    @Test public void malformedArgumentsAndReceiptPathsNeverDispatch() throws Exception {
        for (JSONObject args : new JSONObject[]{new JSONObject().put("displayId", -1), new JSONObject().put("displayId", 1.2),
                new JSONObject().put("timeoutMs", "1000"), new JSONObject().put("timeoutMs", 999), new JSONObject().put("timeoutMs", 30001), new JSONObject().put("password", true)}) {
            assertEquals("invalid_arguments", captures.execute("phone_screen_capture", args).getString("code"));
        }
        for (Object bad : new Object[]{123, "../outside", "00000000-0000-0000-0000-000000000000/elsewhere", JSONObject.NULL})
            assertEquals("invalid_arguments", captures.execute("phone_screen_capture_read", new JSONObject().put("requestId", bad)).getString("code"));
        assertEquals(0, backend.requests); assertFalse(projectFile("captures/screen").exists());
    }
    @Test public void pendingReceiptStorageFailurePreventsDispatch() throws Exception {
        new InternalFiles(context).writeFile("captures/screen", "this synthetic path is a file");
        JSONObject failed = captures.execute("phone_screen_capture", new JSONObject());
        assertFalse(failed.getBoolean("ok")); assertEquals(0, backend.requests);
    }
    @Test public void nullBitmapIsAnActualFailureAndProducesNoAttachment() throws Exception {
        String id = request(); backend.success(null, 1); JSONObject result = read(id);
        assertEquals("capture_empty_bitmap", result.getJSONObject("request").getString("code")); assertFalse(result.has("asset"));
    }
    @Test public void ordinaryFullBackupIncludesExactPngReceiptAndImmutableOriginal() throws Exception {
        String id = request(); backend.success(fixture(), 1); JSONObject observed = read(id);
        journal.ingestPendingForTests();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); new InternalFiles(context).exportBackup(bytes, store);
        Set<String> entries = new HashSet<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ZipEntry entry; while ((entry = input.getNextEntry()) != null) {
                entries.add(entry.getName());
                if (entry.getName().equals("projects/" + observed.getJSONObject("asset").getString("id")))
                    assertArrayEquals(Files.readAllBytes(projectFile(observed.getJSONObject("asset").getString("id")).toPath()), input.readAllBytes());
            }
        }
        assertTrue(entries.contains("projects/" + observed.getJSONObject("request").getString("fileId")));
        assertTrue(entries.contains("projects/" + observed.getJSONObject("request").getString("receiptFileId")));
        assertTrue(entries.contains("projects/" + observed.getJSONObject("asset").getString("id")));
    }

    private static final class FakeScreen implements PhoneScreenCapture.ScreenBackend {
        int api = 35, requests; boolean granted = true, connected = true; PhoneScreenCapture.FrameCallback callback;
        @Override public int apiLevel() { return api; }
        @Override public boolean granted() { return granted; }
        @Override public boolean connected() { return connected; }
        @Override public void request(int display, Executor executor, PhoneScreenCapture.FrameCallback callback) { requests++; this.callback = callback; }
        void success(Bitmap value, long timestamp) { callback.success(value, timestamp); }
        void failure(int code) { callback.failure(code); }
    }
}
