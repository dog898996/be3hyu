package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.AssistantIdentity;
import com.hoyoung.assistant.store.BackupFailure;
import com.hoyoung.assistant.store.BackupPolicy;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import com.hoyoung.assistant.store.MemoryGateway;
import com.hoyoung.assistant.store.OwnerPolicyBackup;
import com.hoyoung.assistant.store.SourcePayloadDeletion;
import com.hoyoung.assistant.store.SourcePrivacyLedger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

/** Small synthetic fixtures on real Android SQLite, files, reliable pipes and DocumentsUI.
 * No model, account, network, physical-phone or long-duration correctness claim is made. */
@RunWith(AndroidJUnit4.class)
public final class MemoryExplorerDeviceTest {
    private Context target;
    private FixtureContext fixture;
    private HoyoungStore store;
    private InternalFiles files;

    @Rule public final TestWatcher failureEvidence = new TestWatcher() {
        @Override protected void failed(Throwable failure, Description description) {
            if (fixture == null || target == null) return;
            try {
                File folder = new File(target.getExternalFilesDir(null), "explorer-memory");
                if (!folder.isDirectory() && !folder.mkdirs()) return;
                UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
                device.takeScreenshot(new File(folder, description.getMethodName() + ".png"));
                device.dumpWindowHierarchy(new File(folder, description.getMethodName() + ".xml"));
            } catch (Exception unavailable) {
                proof("failure-evidence", unavailable.getClass().getSimpleName());
            }
        }
    };

    @Before public void requireOfflineEmulator() throws Exception {
        assertTrue("Only a disposable emulator may run this fixture",
                Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNull("Memory exploration must not contact accounts or paid services",
                target.getSystemService(ConnectivityManager.class).getActiveNetwork());
        fixture = new FixtureContext(target);
        reopen();
        proof("identity", "sdk=" + Build.VERSION.SDK_INT + "; hardware=" + Build.HARDWARE
                + "; fingerprint=" + Build.FINGERPRINT);
    }

    @After public void cleanup() throws Exception {
        if (store != null) store.close();
        if (fixture != null) remove(fixture.root);
    }

    @Test public void repeatedStaleRestoreKeepsNewerDeletionAndExclusionWithExactCorrectionSources() throws Exception {
        JSONObject old = store.addMessage("user", "MEMORY_CITY originally Oslo", null);
        store.upsertMemory("city", "MEMORY_CITY Oslo", "fact", ids(old));
        JSONObject correction = store.addMessage("user", "MEMORY_CITY correction: Bergen", null);
        store.upsertMemory("city", "MEMORY_CITY Bergen", "fact", ids(correction));
        String correctedRefs = store.getMemory("city").getJSONArray("sourceRefs").toString();
        JSONObject hidden = store.addMessage("user", "MEMORY_PRIVATE_SECRET address", null);
        store.upsertMemory("private-address", "MEMORY_PRIVATE_SECRET derived address", "fact", ids(hidden));
        JSONObject deleted = store.addMessage("user", "MEMORY_DELETE_SECRET raw source", null);
        store.upsertMemory("deleted-note", "MEMORY_DELETE_SECRET derived note", "fact", ids(deleted));
        byte[] stale = backup();

        store.getPrivacyLedger().excludeSource("message", hidden.getLong("id"), hidden.getString("content_hash"),
                SourcePrivacyLedger.RETRIEVAL_EXCLUDE, "synthetic owner exclusion after backup");
        assertEquals("completed_managed_local", new SourcePayloadDeletion(fixture, store).deleteSource(
                "message", deleted.getLong("id"), deleted.getString("content_hash"), "synthetic owner deletion after backup").getString("status"));
        String privacy = store.getPrivacyLedger().fingerprint();

        for (int attempt = 0; attempt < 2; attempt++) {
            files.importBackup(new ByteArrayInputStream(stale), store);
            OwnerPolicyBackup.reconcile(fixture, store);
            assertEquals(privacy, store.getPrivacyLedger().fingerprint());
            assertNull(store.getMessage(deleted.getLong("id")));
            assertNull(store.getMemory("deleted-note"));
            assertEquals(hidden.getString("text"), store.getMessage(hidden.getLong("id")).getString("text"));
            assertFalse(store.getPrivacyLedger().allowedSource("message", hidden.getLong("id"), hidden.getString("content_hash")));
            assertEquals("MEMORY_CITY Bergen", store.getMemory("city").getString("content"));
            assertEquals(correctedRefs, store.getMemory("city").getJSONArray("sourceRefs").toString());
            assertEquals(2, store.memoryRevisions("city", 10).length());
            assertEquals(correction.getString("content_hash"), store.getMessage(correction.getLong("id")).getString("content_hash"));
            assertEquals(old.getString("text"), readOriginal(old.getLong("id")));

            MemoryGateway.Prepared current = new MemoryGateway(store).prepare("memory-explorer-" + attempt,
                    "memory-explorer-root", "user", 0, "MEMORY_CITY MEMORY_PRIVATE_SECRET MEMORY_DELETE_SECRET", null, 80_000);
            // The query itself is required input, so inspect only the source-bearing lines.
            for (String line : current.context.split("\n")) {
                if (!line.startsWith("VERBATIM ") && !line.startsWith("SEMANTIC ")
                        && !line.startsWith("EPISODIC ") && !line.startsWith("PROCEDURAL ")) continue;
                assertFalse("Excluded originals and derived notes cannot enter the next context", line.contains("MEMORY_PRIVATE_SECRET"));
                assertFalse("Deleted originals and derived notes cannot enter the next context", line.contains("MEMORY_DELETE_SECRET"));
            }
            assertTrue(current.context.contains("MEMORY_CITY Bergen"));
            assertNoStaging();
        }
        reopen();
        assertEquals("MEMORY_CITY Bergen", store.getMemory("city").getString("content"));
        assertNull(store.getMessage(deleted.getLong("id")));
        assertEquals(privacy, store.getPrivacyLedger().fingerprint());
        proof("stale-restore", "two-real-SQLite-restores; latest-deletion-and-exclusion-retained; correction-source-hash-and-revisions-exact; reopened");
    }

    @Test public void coldRestoreRetainsIdentityAvatarPersonalSettingsAndRejectsStaleProfileEdit() throws Exception {
        JSONObject expected = new JSONObject().put("customInstructions", "한국어로 간결하게, 원문을 정확하게 확인해")
                .put("personalizedInstructions", "긴 답변보다 정확한 기억을 선호해")
                .put("heartbeatSeconds", "240").put("requiredModel", "gpt-6.1-sol")
                .put("requiredEffort", "high").put("requiredSpeed", "fast")
                .put("phoneContextEnabled", "false").put("toolPermissions", "{\"phone_sms_send\":false}");
        for (Iterator<String> keys = expected.keys(); keys.hasNext();) {
            String key = keys.next(); store.putSetting(key, expected.getString(key));
        }
        AssistantIdentity identity = new AssistantIdentity(fixture, store, files);
        JSONObject picture;
        try (InputStream input = fixture.getResources().openRawResource(R.drawable.hoyoung_profile)) {
            picture = files.writeFile("memory-explorer/profile.jpg", input);
        }
        JSONObject before = identity.update("보리", picture.getString("id"), false, "default").getJSONObject("identity");
        String avatarHash = before.getJSONObject("avatar").getString("sha256");
        assertTrue(identity.acceptObservation(before.getString("revision"), avatarHash, "합성 테스트의 선택된 고양이 사진", "offline-fixture"));
        String exactIdentity = identity.snapshot().toString();
        byte[] archive = backup();

        store.close(); store = null; remove(fixture.root); reopen();
        files.importBackup(new ByteArrayInputStream(archive), store);
        OwnerPolicyBackup.reconcile(fixture, store);
        reopen();
        identity = new AssistantIdentity(fixture, store, files);
        assertEquals(exactIdentity, identity.snapshot().toString());
        for (Iterator<String> keys = expected.keys(); keys.hasNext();) {
            String key = keys.next(); assertEquals(key, expected.getString(key), store.getSetting(key, "missing"));
        }
        JSONObject restoredAvatar = identity.avatarForUpload();
        assertEquals(avatarHash, restoredAvatar.getString("sha256"));
        Bitmap decoded = AssistantIdentity.decode(files, restoredAvatar, 128);
        assertNotNull(decoded); decoded.recycle();
        assertEquals("false", store.getSetting("autonomy.enabled", "missing"));
        assertEquals("true", store.getSetting("runtime.restoreReviewRequired", "missing"));
        JSONObject changed = identity.update("나비", null, false, before.getString("revision")).getJSONObject("identity");
        assertFalse(identity.update("이전 화면 이름", null, false, before.getString("revision")).getBoolean("ok"));
        assertFalse(identity.acceptObservation(before.getString("revision"), avatarHash, "이전 요청의 설명", "stale-request"));
        assertEquals(changed.getString("revision"), identity.snapshot().getString("revision"));
        assertEquals("나비", AssistantIdentity.name(store));
        assertNoStaging();
        proof("identity-restore", "isolated-private-reset; exact-settings-and-avatar-original; native-image-decode; stale-edit-rejected; autonomy-paused");
    }

    @Test public void reliablePipeLossDuringRestorePreservesCurrentMemorySettingsAndFile() throws Exception {
        JSONObject saved = store.addMessage("user", "saved source", null);
        store.upsertMemory("pipe-memory", "saved memory", "fact", ids(saved));
        files.writeFile("pipe.txt", "saved file"); store.putSetting("customInstructions", "saved instructions");
        byte[] archive = backup();
        JSONObject latest = store.addMessage("user", "latest exact source", null);
        store.upsertMemory("pipe-memory", "latest exact memory", "fact", ids(latest));
        files.writeFile("pipe.txt", "latest exact file"); store.putSetting("customInstructions", "latest exact instructions");

        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                output.write(archive, 0, Math.min(256, archive.length / 2));
                output.flush();
                pipe[1].closeWithError("Synthetic provider lost access mid-read");
            } catch (Throwable failure) { writerFailure.set(failure); }
        }, "memory-explorer-interrupted-provider");
        writer.start();
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
            BackupFailure failure = assertThrows(BackupFailure.class, () -> files.importBackup(input, store));
            assertFalse(failure.diagnostics().getBoolean("restoreCommitted"));
        } finally {
            writer.join(10_000); pipe[1].close();
        }
        assertFalse("The real pipe writer must finish", writer.isAlive());
        if (writerFailure.get() != null) throw new AssertionError(writerFailure.get());
        assertEquals("latest exact memory", store.getMemory("pipe-memory").getString("content"));
        assertEquals("latest exact source", readOriginal(latest.getLong("id")));
        assertEquals("latest exact file", files.readText("pipe.txt", 100));
        assertEquals("latest exact instructions", store.getSetting("customInstructions", "missing"));
        assertFalse(files.restoreRecoveryPending()); assertNoStaging();
        reopen();
        assertEquals("latest exact memory", store.getMemory("pipe-memory").getString("content"));
        proof("interrupted-restore", "actual-Android-reliable-pipe-error; failure-before-commit; current-SQLite-and-file-generation-preserved; reopened");
    }

    @Test public void realPrivateSpaceProbeRefusesHighReserveBeforeConsumingBackup() throws Exception {
        JSONObject source = store.addMessage("user", "low-space exact source", null);
        files.writeFile("space.txt", "low-space exact file"); byte[] archive = backup();
        // Raise only this test's reserve above the actual free-space probe. Do not fill the disk.
        long reserve = fixture.getFilesDir().getUsableSpace() + 16L * 1024 * 1024;
        assertTrue("The emulator's real data partition must fit the supported policy", reserve < BackupPolicy.MAX_ARCHIVE_BYTES);
        BackupPolicy measured = new BackupPolicy(BackupPolicy.MAX_ARCHIVE_BYTES, reserve, File::getUsableSpace);
        InputStream unread = new ByteArrayInputStream(archive) {
            @Override public int read(byte[] bytes, int offset, int length) {
                fail("Low private-space preflight must run before any archive read"); return -1;
            }
        };
        BackupFailure failure = assertThrows(BackupFailure.class, () -> files.importBackup(unread, store, measured));
        assertEquals("backup_private_space", failure.code); assertEquals("restore_preflight", failure.phase);
        assertTrue(failure.availableBytes < failure.requiredBytes);
        assertEquals(source.getString("text"), readOriginal(source.getLong("id")));
        assertEquals("low-space exact file", files.readText("space.txt", 100)); assertNoStaging();
        proof("space-preflight", "actual-statvfs; test-raised-reserve; no-disk-filling; archive-unread; current-source-intact");
    }

    @Test public void unavailableProviderAndClosedExportDescriptorLeaveLocalOriginalsIntact() throws Exception {
        JSONObject source = store.addMessage("user", "provider-failure exact source", null);
        files.writeFile("provider.txt", "provider-failure exact file");
        Uri absent = Uri.parse("content://com.hoyoung.assistant.test.unavailable/backup.zip");
        assertThrows(FileNotFoundException.class, () -> target.getContentResolver().openInputStream(absent));
        File destination = new File(fixture.getCacheDir(), "closed-export.zip");
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(destination,
                ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY);
        OutputStream closed = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor); closed.close();
        BackupFailure failure = assertThrows(BackupFailure.class, () -> files.exportBackup(closed, store));
        assertEquals("backup_destination_io", failure.code); assertFalse(failure.diagnostics().getBoolean("complete"));
        assertEquals(source.getString("text"), readOriginal(source.getLong("id")));
        assertEquals("provider-failure exact file", files.readText("provider.txt", 100)); assertNoStaging();
        proof("provider-failure", "real-ContentResolver-unavailable-authority; actual-closed-PFD-export; explicit-incomplete; local-originals-intact");
    }

    @Test public void repeatedActualAndroidRestorePickerCancellationLeavesIdentityAndSettingsUntouched() throws Exception {
        DevicePermissionFixture.assertReady(target);
        UiDevice device = readyUiDevice();
        HoyoungApplication app = (HoyoungApplication) target.getApplicationContext();
        String identity = app.getStore().getSetting(AssistantIdentity.KEY, "{}");
        String instructions = app.getStore().getSetting("customInstructions", "");
        long messages = app.getStore().messageCount();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            for (int attempt = 0; attempt < 3; attempt++) {
                DeviceFixtureHealth.openPage(device, "파일");
                DeviceFixtureHealth.revealText(device, "백업 복원");
                UiObject2 restore = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("백업 복원").pkg(target.getPackageName())), 15_000);
                assertNotNull(restore); restore.click();
                assertTrue("Android's real document picker must receive every restore request",
                        DeviceFixtureHealth.waitFor(device, Until.hasObject(By.pkg("com.android.documentsui")), 15_000));
                device.pressBack(); DeviceFixtureHealth.awaitForeground(device);
                assertFalse(app.getRuntime().state().optBoolean("restoringBackup"));
                assertEquals(identity, app.getStore().getSetting(AssistantIdentity.KEY, "{}"));
                assertEquals(instructions, app.getStore().getSetting("customInstructions", ""));
                assertEquals(messages, app.getStore().messageCount());
            }
        }
        proof("picker-cancel", "three-real-DocumentsUI-roundtrips; no-restore-start; identity-settings-and-message-count-unchanged");
    }

    @Test public void ordinaryAccountFormRotationPreservesUnsavedFields() throws Exception {
        DevicePermissionFixture.assertReady(target);
        UiDevice device = readyUiDevice();
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            DeviceFixtureHealth.openPage(device, "계정");
            UiObject2 add = DeviceFixtureHealth.waitFor(device,
                    Until.findObject(By.text("계정 추가").pkg(target.getPackageName())), 15_000);
            assertNotNull(add); add.click();
            UiObject2 service = DeviceFixtureHealth.waitFor(device,
                    Until.findObject(By.desc("서비스").clazz(android.widget.EditText.class)), 10_000);
            assertNotNull(service); service.setText("offline tablet rotation fixture");
            UiObject2 label = device.findObject(By.desc("표시 이름").clazz(android.widget.EditText.class));
            assertNotNull(label); label.setText("작성 중인 합성 계정");
            device.setOrientationLeft();
            assertTrue("Rotating an unfinished account form must preserve the editor",
                    DeviceFixtureHealth.waitFor(device, Until.hasObject(By.text("새 계정").pkg(target.getPackageName())), 10_000));
            service = device.findObject(By.desc("서비스").clazz(android.widget.EditText.class));
            label = device.findObject(By.desc("표시 이름").clazz(android.widget.EditText.class));
            assertNotNull(service); assertNotNull(label);
            assertEquals("offline tablet rotation fixture", service.getText());
            assertEquals("작성 중인 합성 계정", label.getText());
            proof("account-rotation", "actual-display-rotation; unsaved-native-form-text-retained; no-account-saved");
        } finally {
            device.setOrientationNatural(); device.unfreezeRotation();
        }
    }

    private void reopen() throws IOException {
        if (store != null) store.close();
        files = new InternalFiles(fixture); store = new HoyoungStore(fixture);
    }
    private UiDevice readyUiDevice() throws Exception {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard");
        device.executeShellCommand("cmd statusbar collapse"); return device;
    }
    private byte[] backup() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); files.exportBackup(output, store); return output.toByteArray();
    }
    private static JSONArray ids(JSONObject source) throws Exception { return new JSONArray().put(source.getLong("id")); }
    private String readOriginal(long id) throws Exception {
        StringBuilder result = new StringBuilder(); long offset = 0;
        for (;;) {
            JSONObject page = store.readOriginalText(id, offset, 4096); result.append(page.getString("text"));
            if (page.getBoolean("complete")) return result.toString();
            long next = page.getLong("nextOffsetChars"); assertTrue(next > offset); offset = next;
        }
    }
    private void assertNoStaging() {
        for (File entry : Objects.requireNonNull(fixture.getFilesDir().listFiles()))
            assertFalse(entry.getName(), entry.getName().startsWith(".restore-")
                    || entry.getName().startsWith(".previous-") || entry.getName().startsWith(".backup-"));
    }
    private static void proof(String scenario, String detail) {
        android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_MEMORY_EXPLORER " + scenario + "; " + detail);
    }
    private static void remove(File path) throws IOException {
        if (!path.exists()) return;
        if (path.isDirectory()) for (File child : Objects.requireNonNull(path.listFiles())) remove(child);
        if (!path.delete()) throw new IOException("Could not remove isolated memory fixture");
    }
    private static final class FixtureContext extends ContextWrapper {
        final String id = UUID.randomUUID().toString();
        final File root;
        FixtureContext(Context base) { super(base); root = new File(base.getFilesDir(), "device-fixtures/memory-" + id); }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return directory("files"); }
        @Override public File getCacheDir() { return directory("cache"); }
        @Override public File getNoBackupFilesDir() { return directory("no-backup"); }
        @Override public File getDatabasePath(String name) { return new File(directory("databases"), name); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return getBaseContext().getSharedPreferences("memory-fixture-" + id + "-" + name, mode);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
            return SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(), factory, SQLiteDatabase.CREATE_IF_NECESSARY, handler);
        }
        private File directory(String name) {
            File path = new File(root, name); if (!path.isDirectory()) assertTrue(path.mkdirs()); return path;
        }
    }
}
