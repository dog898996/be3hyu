package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import com.hoyoung.assistant.phone.PhoneEventJournal;
import com.hoyoung.assistant.phone.HoyoungNotificationListener;
import com.hoyoung.assistant.phone.HoyoungAccessibilityService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real boot construction, SQLite and private raw originals; no model/network/physical-device action. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class StartupRecoveryBootTest {
    private Context context;
    private HoyoungStore seedStore;
    private InternalFiles seedFiles;
    private HoyoungApplication app;
    private JSONObject owner;
    private String rawId;
    private String body;

    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); delete(context.getFilesDir());
        seedStore = new HoyoungStore(context); seedFiles = new InternalFiles(context);
        seedFiles.writeFile("keep.txt", "canonical working bytes");
        body = "recoverable original owner source ".repeat(9000);
        owner = seedStore.addMessage("user", body, null); rawId = owner.getString("raw_file_id");
        assertTrue(new File(context.getFilesDir(), "originals/" + rawId).isFile());
    }
    @After public void after() throws Exception {
        closeApplicationWorkers(); if (seedStore != null) seedStore.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME); delete(context.getFilesDir());
    }

    @Test public void truncatedJournalShowsBlockedBootWithoutCreatingRuntimeStoreOrCollectors() throws Exception {
        byte[] journalBytes = "{\"token\":\"truncated".getBytes(StandardCharsets.UTF_8);
        File journal = journal(); Files.write(journal.toPath(), journalBytes); byte[] rawBytes = rawBytes(); closeSeed();
        app = attached(context); app.onCreate(); assertBlockedApp();
        assertTrue(app.startupState().getBoolean("recoveryPending"));
        assertFalse(app.getFiles().startupDiagnostics().getBoolean("automaticReplay"));
        assertArrayEquals(journalBytes, Files.readAllBytes(journal.toPath())); assertArrayEquals(rawBytes, rawBytes());
        assertEquals("canonical working bytes", utf8(new File(context.getFilesDir(), "projects/keep.txt")));
        assertFalse(new File(context.getFilesDir(), "device-journal").exists());
    }

    @Test public void missingCommittedOriginalsBlocksApplicationBeforeInventingNamespaceOrDeletingPreviousSource() throws Exception {
        JSONObject fixture = committedMissingOriginals(false); byte[] original = previousRawBytes(fixture), journalBytes = Files.readAllBytes(journal().toPath()); closeSeed();
        app = attached(context); app.onCreate(); assertBlockedApp();
        assertFalse(new File(context.getFilesDir(), "originals").exists());
        assertArrayEquals(original, previousRawBytes(fixture)); assertArrayEquals(journalBytes, Files.readAllBytes(journal().toPath()));
        assertTrue(file(fixture, "staging").isDirectory()); assertEquals("recoverable staging", utf8(new File(file(fixture, "staging"), "preserve.txt")));
        assertTrue(app.getFiles().startupDiagnostics().getString("failure").contains("missing a namespace"));
    }

    @Test public void accidentalStoreBeforeRecoveryCannotCreateBlankOriginalsOrOpenDatabase() throws Exception {
        JSONObject fixture = committedMissingOriginals(false); byte[] original = previousRawBytes(fixture); closeSeed();
        File database = context.getDatabasePath(HoyoungStore.DATABASE_NAME); byte[] databaseBytes = Files.readAllBytes(database.toPath());
        InternalFiles.RestoreRecoveryPendingException rejected = assertThrows(InternalFiles.RestoreRecoveryPendingException.class, () -> new HoyoungStore(context));
        assertEquals("restore_recovery_pending", rejected.code); assertFalse(new File(context.getFilesDir(), "originals").exists());
        assertArrayEquals(original, previousRawBytes(fixture)); assertArrayEquals(databaseBytes, Files.readAllBytes(database.toPath()));
        assertTrue(journal().isFile()); assertTrue(file(fixture, "staging").isDirectory());
    }

    @Test public void everyNamespaceIsPrevalidatedBeforeAnyEarlierPreviousGenerationIsRetired() throws Exception {
        JSONObject fixture = committedMissingOriginals(true); byte[] original = previousRawBytes(fixture); closeSeed();
        File previousProjects = file(fixture, "previousProjects"), previousJournal = file(fixture, "previousDeviceJournal");
        byte[] oldProject = Files.readAllBytes(new File(previousProjects, "old.txt").toPath());
        byte[] oldDevice = Files.readAllBytes(new File(previousJournal, "old.json").toPath());
        app = attached(context); app.onCreate(); assertBlockedApp();
        assertArrayEquals(original, previousRawBytes(fixture)); assertArrayEquals(oldProject, Files.readAllBytes(new File(previousProjects, "old.txt").toPath()));
        assertArrayEquals(oldDevice, Files.readAllBytes(new File(previousJournal, "old.json").toPath()));
        assertEquals("canonical working bytes", utf8(new File(context.getFilesDir(), "projects/keep.txt")));
        assertTrue(journal().isFile()); assertTrue(file(fixture, "staging").isDirectory());
    }

    @Test public void repeatedFailedRetryPreservesExactJournalAndRecoverableGenerations() throws Exception {
        JSONObject fixture = committedMissingOriginals(true); byte[] original = previousRawBytes(fixture), journalBytes = Files.readAllBytes(journal().toPath()); closeSeed();
        app = attached(context); app.onCreate(); assertBlockedApp();
        retryOnWorker(); retryOnWorker(); assertBlockedApp();
        assertArrayEquals(original, previousRawBytes(fixture)); assertArrayEquals(journalBytes, Files.readAllBytes(journal().toPath()));
        assertFalse(new File(context.getFilesDir(), "originals").exists()); assertTrue(file(fixture, "previousProjects").isDirectory());
        assertTrue(file(fixture, "previousDeviceJournal").isDirectory()); assertTrue(file(fixture, "staging").isDirectory());
    }

    @Test public void sameApplicationRetryAfterValidJournalCorrectionRecoversSourcesAndInitializesCoreOnce() throws Exception {
        File root = context.getFilesDir(), previous = new File(root, ".previous-projects-" + UUID.randomUUID()), staging = new File(root, ".restore-" + UUID.randomUUID());
        assertTrue(staging.mkdir()); Files.move(new File(root, "projects").toPath(), previous.toPath()); assertTrue(new File(root, "projects").mkdir());
        Files.write(new File(root, "projects/keep.txt").toPath(), "uncommitted new tree".getBytes(StandardCharsets.UTF_8));
        String token = UUID.randomUUID().toString(); JSONObject corrected = new JSONObject().put("token", token).put("staging", staging.getName()).put("previous", previous.getName());
        Files.write(journal().toPath(), "{broken journal".getBytes(StandardCharsets.UTF_8)); byte[] original = rawBytes(); closeSeed();
        app = attached(context); app.onCreate(); assertBlockedApp(); assertArrayEquals(original, rawBytes());
        Files.write(journal().toPath(), corrected.toString().getBytes(StandardCharsets.UTF_8)); retryOnWorker();
        assertTrue(app.startupState().toString(), app.isStartupReady()); assertFalse(app.startupState().getBoolean("blocked"));
        assertNotNull(app.getStore()); assertNotNull(app.getRuntime()); assertNotNull(app.getPhoneContext()); assertNotNull(app.getWebController());
        assertFalse(app.getFiles().startupBlocked()); assertFalse(app.getFiles().restoreRecoveryPending());
        assertEquals("canonical working bytes", app.getFiles().readText("keep.txt", 100));
        assertEquals(body, app.getStore().readOriginalText(owner.getLong("id"), 0, body.length()).getString("text"));
        assertEquals(owner.getString("content_hash"), app.getStore().getMessage(owner.getLong("id")).getString("content_hash"));
        assertArrayEquals(original, rawBytes()); assertFalse(previous.exists()); assertFalse(staging.exists()); assertFalse(journal().exists());
        HoyoungStore sameStore = app.getStore(); AssistantRuntime sameRuntime = app.getRuntime(); retryOnWorker();
        assertSame(sameStore, app.getStore()); assertSame(sameRuntime, app.getRuntime());
        assertFalse("Core initialized does not establish provider authentication", app.getRuntime().state().getBoolean("ready"));
    }

    @Test public void orphanCleanupWarningKeepsConsistentCanonicalStorageAvailable() throws Exception {
        File realRoot = context.getFilesDir(), stage = new File(realRoot, ".restore-" + UUID.randomUUID()); assertTrue(stage.mkdir());
        Files.write(new File(stage, "orphan.txt").toPath(), new byte[]{1, 2, 3});
        File inaccessible = new File(stage.getPath()) { @Override public File[] listFiles() { return null; } };
        File rootView = new File(realRoot.getPath()) { @Override public File[] listFiles() { return new File[]{inaccessible}; } };
        Context wrapper = new ContextWrapper(context) { @Override public Context getApplicationContext() { return this; } @Override public File getFilesDir() { return rootView; } };
        InternalFiles files = new InternalFiles(wrapper); JSONObject diagnostic = files.startupDiagnostics();
        assertFalse(files.startupBlocked()); assertTrue(diagnostic.getBoolean("ready")); assertFalse(diagnostic.getBoolean("blocked"));
        assertFalse(diagnostic.getBoolean("recoveryPending")); assertEquals("ready_cleanup_deferred", diagnostic.getString("status"));
        assertFalse(diagnostic.getString("warning").isEmpty()); assertFalse(diagnostic.getBoolean("automaticReplay"));
        assertEquals("canonical working bytes", files.readText("keep.txt", 100));
        assertEquals(body, seedStore.readOriginalText(owner.getLong("id"), 0, body.length()).getString("text")); assertTrue(new File(stage, "orphan.txt").isFile());
    }

    @Test public void blockedRealApplicationIdentityRejectsPhoneFallbackAndSafelyHandlesBootAndServiceCallbacks() throws Exception {
        JSONObject fixture = committedMissingOriginals(true); byte[] original = previousRawBytes(fixture), journalBytes = Files.readAllBytes(journal().toPath()); closeSeed();
        app = attached(context); assertSame(app, app.getApplicationContext()); app.onCreate(); assertBlockedApp();
        app.maintainMemory();
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> PhoneEventJournal.forContext(app));
        assertEquals("Storage startup recovery must finish before phone observations start", refused.getMessage());
        assertEquals(0, HoyoungNotificationListener.notificationSnapshot(app).length());
        HoyoungNotificationListener listener = new HoyoungNotificationListener(); attachService(listener, app);
        listener.onListenerConnected(); listener.onNotificationPosted(null); listener.onNotificationRemoved(null); listener.onListenerDisconnected(); listener.onDestroy();
        AutonomyService service = new AutonomyService(); attachService(service, app);
        service.onCreate(); assertEquals(Service.START_NOT_STICKY, service.onStartCommand(new Intent(), 0, 1)); service.onDestroy();
        HoyoungAccessibilityService accessibility = new HoyoungAccessibilityService(); attachService(accessibility, app);
        Method connected = HoyoungAccessibilityService.class.getDeclaredMethod("onServiceConnected"); connected.setAccessible(true); connected.invoke(accessibility);
        accessibility.onInterrupt(); accessibility.onDestroy();
        assertBlockedApp(); assertArrayEquals(original, previousRawBytes(fixture)); assertArrayEquals(journalBytes, Files.readAllBytes(journal().toPath()));
        assertFalse(new File(context.getFilesDir(), "originals").exists());
        assertEquals("previous phone evidence", utf8(new File(file(fixture, "previousDeviceJournal"), "old.json")));
    }

    private JSONObject committedMissingOriginals(boolean allNamespaces) throws Exception {
        File root = context.getFilesDir(), previous = new File(root, ".previous-originals-" + UUID.randomUUID()), staging = new File(root, ".restore-" + UUID.randomUUID());
        assertTrue(staging.mkdir()); Files.write(new File(staging, "preserve.txt").toPath(), "recoverable staging".getBytes(StandardCharsets.UTF_8));
        String token = UUID.randomUUID().toString(); seedStore.putSetting(restoreTokenSettingKey(), token); Files.move(new File(root, "originals").toPath(), previous.toPath());
        JSONArray moves = new JSONArray(); JSONObject fixture = new JSONObject().put("previousOriginals", previous.getName()).put("staging", staging.getName());
        if (allNamespaces) {
            File oldProjects = new File(root, ".previous-projects-" + UUID.randomUUID()), oldDevice = new File(root, ".previous-device-journal-" + UUID.randomUUID());
            assertTrue(oldProjects.mkdir()); assertTrue(oldDevice.mkdir()); assertTrue(new File(root, "device-journal").mkdir());
            Files.write(new File(oldProjects, "old.txt").toPath(), "previous project evidence".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(oldDevice, "old.json").toPath(), "previous phone evidence".getBytes(StandardCharsets.UTF_8));
            fixture.put("previousProjects", oldProjects.getName()).put("previousDeviceJournal", oldDevice.getName());
            moves.put(new JSONObject().put("current", "projects").put("previous", oldProjects.getName()));
            moves.put(new JSONObject().put("current", "originals").put("previous", previous.getName()));
            moves.put(new JSONObject().put("current", "device-journal").put("previous", oldDevice.getName()));
        } else moves.put(new JSONObject().put("current", "originals").put("previous", previous.getName()));
        JSONObject record = new JSONObject().put("token", token).put("staging", staging.getName()).put("moves", moves);
        Files.write(journal().toPath(), record.toString().getBytes(StandardCharsets.UTF_8)); return fixture;
    }
    private void assertBlockedApp() throws Exception {
        assertFalse(app.isStartupReady()); JSONObject state = app.startupState(); assertFalse(state.getBoolean("ready")); assertTrue(state.getBoolean("blocked"));
        assertNotNull(app.getFiles()); assertTrue(app.getFiles().startupBlocked());
        assertNull(app.getStore()); assertNull(app.getRuntime()); assertNull(app.getPhoneTools()); assertNull(app.getPhoneContext());
        assertNull(app.getMemoryRetriever()); assertNull(app.getSemanticEncoder()); assertNull(app.getWebController()); assertNull(app.getReplyNotifications());
        assertTrue(journal().isFile());
    }
    private static HoyoungApplication attached(Context context) throws Exception {
        HoyoungApplication result = new HoyoungApplication();
        Context base = new ContextWrapper(context) { @Override public Context getApplicationContext() { return result; } };
        Method attach = Application.class.getDeclaredMethod("attach", Context.class); attach.setAccessible(true); attach.invoke(result, base); return result;
    }
    private static void attachService(Service service, HoyoungApplication app) throws Exception {
        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(service, app);
        Field application = Service.class.getDeclaredField("mApplication"); application.setAccessible(true); application.set(service, app);
    }
    private static String restoreTokenSettingKey() throws Exception { Field field = HoyoungStore.class.getDeclaredField("FILE_RESTORE_TOKEN_KEY"); field.setAccessible(true); return (String) field.get(null); }
    private void retryOnWorker() throws Exception { FutureTask<Void> retry = new FutureTask<>(() -> { app.retryStartupRecovery(); return null; }); Thread worker = new Thread(retry, "startup-recovery-test-worker"); worker.start(); try { retry.get(15, TimeUnit.SECONDS); } finally { worker.interrupt(); worker.join(1000); } assertFalse(worker.isAlive()); }
    private void closeSeed() { if (seedStore != null) { seedStore.close(); seedStore = null; } }
    private File journal() { return new File(context.getFilesDir(), ".restore-transaction.json"); }
    private File file(JSONObject fixture, String field) throws Exception { return new File(context.getFilesDir(), fixture.getString(field)); }
    private byte[] previousRawBytes(JSONObject fixture) throws Exception { return Files.readAllBytes(new File(file(fixture, "previousOriginals"), rawId).toPath()); }
    private byte[] rawBytes() throws IOException { return Files.readAllBytes(new File(context.getFilesDir(), "originals/" + rawId).toPath()); }
    private static String utf8(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }

    /** Dispose only fixture-owned background workers before closing its SQLite database. */
    private void closeApplicationWorkers() throws Exception {
        if (app == null) return;
        if (app.getRuntime() != null) { app.getRuntime().detachTransport(); stopExecutor(app.getRuntime(), "promptExecutor"); }
        stopExecutor(app, "maintenance");
        if (app.getWebController() != null) {
            Field main = app.getWebController().getClass().getDeclaredField("main"); main.setAccessible(true); ((Handler) main.get(app.getWebController())).removeCallbacksAndMessages(null);
            stopExecutor(app.getWebController(), "downloads"); stopExecutor(app.getWebController(), "responseCaptures"); stopExecutor(app.getWebController(), "attachmentPreparation");
            if (app.getWebController().getExistingView() != null) app.getWebController().getExistingView().destroy();
        }
        if (app.getPhoneContext() != null) {
            Field thread = app.getPhoneContext().getClass().getDeclaredField("thread"); thread.setAccessible(true); HandlerThread worker = (HandlerThread) thread.get(app.getPhoneContext()); worker.quitSafely(); worker.join(2000); assertFalse("Fixture phone collector thread did not stop", worker.isAlive());
            Field journal = app.getPhoneContext().getClass().getDeclaredField("journal"); journal.setAccessible(true); Object actualJournal = journal.get(app.getPhoneContext());
            Method close = actualJournal.getClass().getDeclaredMethod("closeWorker"); close.setAccessible(true); close.invoke(actualJournal); stopExecutor(actualJournal, "worker");
        }
        if (app.getSemanticEncoder() != null) app.getSemanticEncoder().close(); if (app.getStore() != null) app.getStore().close();
    }
    private static void stopExecutor(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); Object value = field.get(owner); if (value instanceof ExecutorService) { ExecutorService executor = (ExecutorService) value; executor.shutdownNow(); assertTrue("Fixture worker did not stop: " + name, executor.awaitTermination(3, TimeUnit.SECONDS)); } }
    private static void delete(File file) throws IOException { if (Files.isSymbolicLink(file.toPath())) { Files.deleteIfExists(file.toPath()); return; } if (!file.exists()) return; if (file.isDirectory()) { File[] children = file.listFiles(); if (children == null) throw new IOException("Cannot inspect fixture directory"); for (File child : children) delete(child); } Files.delete(file.toPath()); }
}
