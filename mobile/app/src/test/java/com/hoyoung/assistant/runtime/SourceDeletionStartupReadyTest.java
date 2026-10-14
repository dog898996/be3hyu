package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.hoyoung.assistant.MainActivity;
import com.hoyoung.assistant.phone.PhoneEventJournal;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import com.hoyoung.assistant.store.MemoryGateway;
import com.hoyoung.assistant.store.SourcePayloadDeletion;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real SQLite/private originals and the Activity loading boundary. WebView is Robolectric's
 * shadow and no model, account, HTTP request, physical phone observation or Gradle is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SourceDeletionStartupReadyTest {
    private Context context;
    private HoyoungStore seedStore;
    private InternalFiles seedFiles;
    private HoyoungApplication app;
    private ActivityController<MainActivity> activity;
    private CountDownLatch maintenanceRelease;
    private JSONObject source;
    private OwnerDraftStore.Snapshot draft;
    private String body;
    private File original;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir()); clear(context.getNoBackupFilesDir());
        seedFiles = new InternalFiles(context); seedStore = new HoyoungStore(context);
        // Avoid downloads/provider sampling; these tests exercise local admission, not network.
        seedStore.putSetting("semanticSearchEnabled", "false");
        seedStore.putSetting("phoneContextEnabled", "false");
        seedStore.putSetting("autonomy.enabled", "false");
        body = "selected old owner payload / synthetic private datum ".repeat(7000);
        source = seedStore.addMessage("user", body, null);
        seedStore.putSetting("ownerInputRevision", String.valueOf(source.getLong("id")));
        original = new File(context.getFilesDir(), "originals/" + source.getString("raw_file_id"));
        assertTrue(original.isFile());
        JSONObject unsentAttachment = seedFiles.writeFile("unsent-draft.txt", "owner unsent attachment payload");
        draft = new OwnerDraftStore(seedStore).edit("unsent owner draft survives startup", new JSONArray().put(unsentAttachment));
    }

    @After public void cleanup() throws Exception {
        if (maintenanceRelease != null) maintenanceRelease.countDown();
        if (activity != null) {
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); if (dialog != null) dialog.dismiss();
            ((Handler) field(activity.get(), "main")).removeCallbacksAndMessages(null);
            activity.pause().stop().destroy();
            stopExecutor(activity.get(), "io"); stopExecutor(activity.get(), "commands");
            stopExecutor(activity.get(), "display"); stopExecutor(activity.get(), "fileListing");
        }
        closeApp();
        if (seedStore != null) seedStore.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir()); clear(context.getNoBackupFilesDir());
    }

    @Test public void unusedDeletionStateKeepsSynchronousStartupAndOriginals() throws Exception {
        byte[] before = Files.readAllBytes(original.toPath()); closeSeed();
        app = attached(); app.onCreate();
        assertReady(); assertEquals("ready", app.startupState().getString("status"));
        assertArrayEquals(before, Files.readAllBytes(original.toPath()));
        assertEquals(body, app.getStore().readOriginalText(source.getLong("id"), 0, body.length()).getString("text"));
        assertFalse(new File(context.getFilesDir(), "source-deletions.key").exists());
        assertDraftUnchanged();
    }

    @Test public void restoredDeletedOriginalIsReErasedBeforeAnyReadyComponentExists() throws Exception {
        restoreOldBackupAfterDeletion(); String privacy = seedStore.getPrivacyLedger().fingerprint();
        assertEquals(body, seedStore.readOriginalText(source.getLong("id"), 0, body.length()).getString("text"));
        closeSeed(); app = attached(); holdMaintenance(); app.onCreate();
        assertReconciling(); assertTrue(original.isFile()); assertNotNull(app.getStore().getMessage(source.getLong("id")));
        releaseAndFinishMaintenance(); assertReady();
        assertNull(app.getStore().getMessage(source.getLong("id"))); assertFalse(original.exists());
        assertEquals(privacy, app.getStore().getPrivacyLedger().fingerprint());
        assertNativeOwnerControl(); assertDraftUnchanged();
        MemoryGateway.Prepared prepared = new MemoryGateway(app.getStore()).prepare("after-delete", "after-delete", "owner_chat", 0, "unrelated fresh question", null, 20000);
        assertEquals(5, prepared.receipts().length()); assertFalse(prepared.context.contains("synthetic private datum"));
    }

    @Test public void tamperedIntentBlocksReadinessWithoutDeletingRestoredRawBytes() throws Exception {
        restoreOldBackupAfterDeletion(); byte[] before = Files.readAllBytes(original.toPath());
        Files.write(job().toPath(), "{\"record\":\"corrupt\",\"authentication\":\"00\"}".getBytes(StandardCharsets.UTF_8));
        assertFailedReconciliation(before);
    }

    @Test public void missingEstablishedIntentBlocksReadinessAndRetainsOriginalForExplicitRecovery() throws Exception {
        restoreOldBackupAfterDeletion(); byte[] before = Files.readAllBytes(original.toPath()); Files.delete(job().toPath());
        assertFailedReconciliation(before);
    }

    @Test public void missingAuthenticationKeyDoesNotManufactureNewDeletionTrustAtBoot() throws Exception {
        restoreOldBackupAfterDeletion(); byte[] before = Files.readAllBytes(original.toPath());
        Files.delete(new File(context.getFilesDir(), "source-deletions.key").toPath());
        assertFailedReconciliation(before);
        assertFalse(new File(context.getFilesDir(), "source-deletions.key").exists());
    }

    @Test public void keyWithoutJobsOrAnchorStillSignalsEstablishedDeletionState() throws Exception {
        restoreOldBackupAfterDeletion(); byte[] before = Files.readAllBytes(original.toPath());
        clear(new File(context.getFilesDir(), "source-deletions"));
        Files.delete(new File(context.getFilesDir(), "source-deletions.anchor.json").toPath());
        assertTrue(new File(context.getFilesDir(), "source-deletions.key").isFile());
        assertFailedReconciliation(before);
    }

    @Test public void activityOpenedDuringReconciliationAutomaticallyShowsReadyUiAndRetainsOwnerDraft() throws Exception {
        restoreOldBackupAfterDeletion(); closeSeed(); app = attached(); holdMaintenance(); app.onCreate();
        assertReconciling(); openActivity();
        assertFalse((Boolean) field(activity.get(), "uiReady")); assertNull(field(activity.get(), "composer"));
        assertTrue(viewText(activity.get().getWindow().getDecorView()).contains("기억을 준비하고 있어"));
        assertFalse(viewText(activity.get().getWindow().getDecorView()).contains("synthetic private datum"));
        releaseAndFinishMaintenance();
        // Product's already-scheduled 500 ms refresh, with no retry button or owner tap.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertReady(); assertTrue((Boolean) field(activity.get(), "uiReady"));
        assertEquals(draft.text, ((EditText) field(activity.get(), "composer")).getText().toString());
        assertDraftUnchanged(); assertFalse(original.exists());
        assertFalse(viewText(activity.get().getWindow().getDecorView()).contains("기억을 준비하고 있어"));
        assertEquals(0, app.getStore().listRequests(100).length());
    }

    @Test public void ownerUiExclusionKeepsDraftAndDoesNotExposeDeletionAsAutonomousTool() throws Exception {
        closeSeed(); app = attached(); app.onCreate(); assertReady(); openActivity();
        Method catalog = AssistantRuntime.class.getDeclaredMethod("catalog"); catalog.setAccessible(true);
        JSONArray tools = (JSONArray) catalog.invoke(app.getRuntime());
        for (int index = 0; index < tools.length(); index++) {
            String name = tools.getJSONObject(index).getString("name");
            assertFalse(name, name.equals("memory.source.delete") || name.equals("memory.source.exclude")
                    || name.equals("memory.source.control") || name.equals("source.payload.delete") || name.equals("source.control"));
        }
        Button memoryTab = findButton(activity.get().getWindow().getDecorView(), "기억"); assertNotNull(memoryTab); memoryTab.performClick();
        EditText sourceId = findHint(activity.get().getWindow().getDecorView(), "원문 ID"); assertNotNull(sourceId); sourceId.setText(String.valueOf(source.getLong("id")));
        Button inspect = findButton(activity.get().getWindow().getDecorView(), "원문 확인 후 관리"); assertNotNull(inspect); assertTrue(inspect.isAttachedToWindow()); inspect.performClick();
        finishExecutor(activity.get(), "io"); Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog); assertTrue(dialog.isShowing());
        assertNotNull(app.getStore().getMessage(source.getLong("id"))); // Inspection alone grants no mutation.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        // Android's AlertDialog dispatches its button listener through the main Handler.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        finishExecutor(activity.get(), "io"); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(app.getStore().getPrivacyLedger().allowedSource("message", source.getLong("id"), source.getString("content_hash")));
        assertNotNull(app.getStore().getMessage(source.getLong("id"))); assertTrue(original.exists());
        assertDraftUnchanged(); assertNativeOwnerControl(); assertEquals(0, app.getStore().listRequests(100).length());
    }

    @Test public void ownerUiDeletionNeedsSeparateConfirmationAndPreservesUnsentDraftIdentity() throws Exception {
        closeSeed(); app = attached(); app.onCreate(); assertReady(); openActivity();
        findButton(activity.get().getWindow().getDecorView(), "기억").performClick();
        findHint(activity.get().getWindow().getDecorView(), "원문 ID").setText(String.valueOf(source.getLong("id")));
        findButton(activity.get().getWindow().getDecorView(), "원문 확인 후 관리").performClick();
        finishExecutor(activity.get(), "io"); Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog inspection = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(inspection);
        inspection.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog(); assertNotSame(inspection, confirmation);
        assertTrue(confirmation.isShowing());
        // Selecting deletion does not erase anything until its distinct confirmation click.
        assertNotNull(app.getStore().getMessage(source.getLong("id"))); assertTrue(original.isFile());
        assertTrue(app.getStore().getPrivacyLedger().allowedSource("message", source.getLong("id"), source.getString("content_hash")));
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        finishExecutor(activity.get(), "io"); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(app.getStore().getMessage(source.getLong("id"))); assertFalse(original.exists());
        assertFalse(app.getStore().getPrivacyLedger().allowedSource("message", source.getLong("id"), source.getString("content_hash")));
        assertDraftUnchanged(); assertNativeOwnerControl(); assertEquals(0, app.getStore().listRequests(100).length());
        AlertDialog result = ShadowAlertDialog.getLatestAlertDialog(); assertNotSame(confirmation, result); assertTrue(result.isShowing());
        String receiptText = viewText(result.getWindow().getDecorView());
        assertTrue(receiptText, receiptText.contains("외부 제공자"));
    }

    private void restoreOldBackupAfterDeletion() throws Exception {
        ByteArrayOutputStream old = new ByteArrayOutputStream(); seedFiles.exportBackup(old, seedStore);
        JSONObject receipt = new SourcePayloadDeletion(context, seedStore).deleteSource("message", source.getLong("id"), source.getString("content_hash"), "explicit synthetic owner deletion");
        assertEquals(receipt.toString(), "completed_managed_local", receipt.getString("status"));
        assertNull(seedStore.getMessage(source.getLong("id"))); assertFalse(original.exists());
        seedFiles.importBackup(new ByteArrayInputStream(old.toByteArray()), seedStore);
        assertNotNull(seedStore.getMessage(source.getLong("id"))); assertTrue(original.isFile());
        assertFalse(seedStore.getPrivacyLedger().allowedSource("message", source.getLong("id"), source.getString("content_hash")));
    }
    private void assertFailedReconciliation(byte[] before) throws Exception {
        closeSeed(); app = attached(); holdMaintenance(); app.onCreate(); assertReconciling();
        releaseAndFinishMaintenance(); assertComponentsAbsent();
        assertEquals("source_deletion_reconciliation_failed", app.startupState().getString("status"));
        assertArrayEquals(before, Files.readAllBytes(original.toPath())); assertNotNull(app.getStore().getMessage(source.getLong("id")));
        assertFalse(app.getStore().getPrivacyLedger().allowedSource("message", source.getLong("id"), source.getString("content_hash")));
        app.maintainMemory(); assertComponentsAbsent(); assertDraftUnchanged();
    }
    private void assertReconciling() throws Exception {
        assertComponentsAbsent(); assertEquals("source_deletion_reconciling", app.startupState().getString("status")); assertNotNull(app.getStore());
    }
    private void assertComponentsAbsent() {
        assertFalse(app.isStartupReady()); assertNull(app.getRuntime()); assertNull(app.getWebController());
        assertNull(app.getPhoneTools()); assertNull(app.getPhoneContext()); assertNull(app.getMemoryRetriever());
        assertNull(app.getSemanticEncoder()); assertNull(app.getReplyNotifications());
        // Backup restore may legitimately recreate the preserved empty journal namespace.
        // Its collector must still refuse to open/bind before readiness.
        assertThrows(IllegalStateException.class, () -> PhoneEventJournal.forContext(app));
    }
    private void assertReady() {
        assertTrue(app.startupState().toString(), app.isStartupReady()); assertNotNull(app.getRuntime()); assertNotNull(app.getWebController());
        assertNotNull(app.getPhoneTools()); assertNotNull(app.getPhoneContext()); assertNotNull(app.getMemoryRetriever());
    }
    private void assertNativeOwnerControl() throws Exception {
        long current = Long.parseLong(app.getStore().getSetting("ownerInputRevision", "0")); assertTrue(current > source.getLong("id"));
        JSONObject control = new JSONObject(app.getStore().readOriginalText(current, 0, 4000).getString("text"));
        assertTrue(control.has("nativeOwnerControl")); assertFalse(control.getBoolean("startsNewWork"));
        assertFalse(control.getBoolean("naturalLanguageOwnerUtterance"));
    }
    private void assertDraftUnchanged() throws Exception {
        OwnerDraftStore.Snapshot actual = new OwnerDraftStore(app.getStore()).read();
        assertEquals(draft.text, actual.text); assertEquals(draft.attachmentsJson, actual.attachmentsJson);
        assertEquals(draft.clientInputId, actual.clientInputId); assertEquals(draft.revision, actual.revision);
        assertEquals("owner unsent attachment payload", app.getFiles().readText("unsent-draft.txt", 100));
    }
    private void openActivity() throws Exception {
        activity = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(activity.get()).setApplication(app);
        activity.create().start().resume().visible();
    }
    private void holdMaintenance() throws Exception {
        maintenanceRelease = new CountDownLatch(1); CountDownLatch held = new CountDownLatch(1);
        ((ExecutorService) field(app, "maintenance")).submit(() -> { held.countDown(); maintenanceRelease.await(10, TimeUnit.SECONDS); return null; });
        assertTrue("Fixture IO barrier did not start", held.await(3, TimeUnit.SECONDS));
    }
    private void releaseAndFinishMaintenance() throws Exception {
        maintenanceRelease.countDown(); finishExecutor(app, "maintenance");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static void finishExecutor(Object owner, String name) throws Exception {
        ((ExecutorService) field(owner, name)).submit(() -> {}).get(15, TimeUnit.SECONDS);
    }
    private HoyoungApplication attached() throws Exception {
        HoyoungApplication result = new HoyoungApplication();
        Context base = new ContextWrapper(context) { @Override public Context getApplicationContext() { return result; } };
        Method attach = Application.class.getDeclaredMethod("attach", Context.class); attach.setAccessible(true); attach.invoke(result, base); return result;
    }
    private File job() throws Exception { return new File(context.getFilesDir(), "source-deletions/message-" + source.getLong("id") + "-" + source.getString("content_hash") + ".json"); }
    private void closeSeed() { seedStore.close(); seedStore = null; }
    private void closeApp() throws Exception {
        if (app == null) return;
        ((Handler) field(app, "main")).removeCallbacksAndMessages(null); stopExecutor(app, "maintenance");
        if (app.getRuntime() != null) { app.getRuntime().detachTransport(); stopExecutor(app.getRuntime(), "promptExecutor"); }
        if (app.getWebController() != null) {
            ((Handler) field(app.getWebController(), "main")).removeCallbacksAndMessages(null);
            stopExecutor(app.getWebController(), "downloads"); stopExecutor(app.getWebController(), "responseCaptures"); stopExecutor(app.getWebController(), "attachmentPreparation");
            if (app.getWebController().getExistingView() != null) app.getWebController().getExistingView().destroy();
        }
        if (app.getPhoneContext() != null) {
            HandlerThread thread = (HandlerThread) field(app.getPhoneContext(), "thread"); thread.quitSafely(); thread.join(3000); assertFalse(thread.isAlive());
            Object journal = field(app.getPhoneContext(), "journal"); Method close = journal.getClass().getDeclaredMethod("closeWorker"); close.setAccessible(true); close.invoke(journal); stopExecutor(journal, "worker");
        }
        if (app.getSemanticEncoder() != null) app.getSemanticEncoder().close(); if (app.getStore() != null) app.getStore().close();
    }
    private static void stopExecutor(Object owner, String name) throws Exception {
        ExecutorService worker = (ExecutorService) field(owner, name); worker.shutdownNow(); assertTrue(name, worker.awaitTermination(3, TimeUnit.SECONDS));
    }
    private static Object field(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static Button findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int index = 0; index < group.getChildCount(); index++) { Button found = findButton(group.getChildAt(index), text); if (found != null) return found; } } return null;
    }
    private static EditText findHint(View view, String hint) {
        if (view instanceof EditText && hint.contentEquals(((EditText) view).getHint())) return (EditText) view;
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int index = 0; index < group.getChildCount(); index++) { EditText found = findHint(group.getChildAt(index), hint); if (found != null) return found; } } return null;
    }
    private static String viewText(View view) {
        StringBuilder text = new StringBuilder(); if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int index = 0; index < group.getChildCount(); index++) text.append(viewText(group.getChildAt(index))); } return text.toString();
    }
    private static void clear(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) { Files.deleteIfExists(file.toPath()); return; } if (!file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children == null) throw new IOException("Cannot inspect synthetic fixture"); for (File child : children) clear(child); } Files.delete(file.toPath());
    }
}
