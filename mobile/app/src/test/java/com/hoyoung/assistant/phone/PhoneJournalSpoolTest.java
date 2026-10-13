package com.hoyoung.assistant.phone;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class PhoneJournalSpoolTest {
    private Context context; private HoyoungStore store; private PhoneEventJournal journal;
    @Before public void setup() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); clearTestFiles(new File(context.getFilesDir(), "device-journal")); clearTestFiles(new File(context.getCacheDir(), "journal-snapshot")); store = new HoyoungStore(context); journal = PhoneEventJournal.bind(context, store); journal.ingestPendingForTests(); }
    @After public void cleanup() { journal = PhoneEventJournal.bind(context, store); journal.afterBackupRestore(); journal.ingestPendingForTests(); journal.closeWorker(); journal.awaitClosedForTests(); store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); }

    @Test public void callbackDurablySpoolsWhileDatabaseMonitorIsBlockedWithoutWaitingForIt() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread databaseBackup = new Thread(() -> { synchronized (store) { locked.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } } }); databaseBackup.start();
        try {
            assertTrue(locked.await(2, TimeUnit.SECONDS)); long started = System.nanoTime();
            JSONObject receipt = journal.append("notification", "android.notification_listener", new JSONObject().put("text", "synthetic durable content"), 12345, null);
            journal.put("test.counter", "12"); assertEquals("12", journal.get("test.counter", "")); assertTrue(journal.toolAllowed("phone_notifications_read"));
            assertTrue(receipt.toString(), receipt.getBoolean("observationPersisted")); assertFalse(receipt.has("id")); assertFalse(receipt.getBoolean("databaseIngested"));
            assertTrue("Callback waited on the database backup", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
            PhoneObservationSpool spool = new PhoneObservationSpool(journal.spoolDirectoryForTests()); JSONObject pending = spool.read(spool.pending(10)[0]);
            assertEquals("synthetic durable content", pending.getJSONObject("payload").getString("text")); assertEquals(12345, pending.getLong("observedAt")); assertEquals("android.notification_listener", pending.getString("source")); assertFalse(pending.getString("checksum").isEmpty());
        } finally { release.countDown(); databaseBackup.join(2000); }
        journal.ingestPendingForTests(); assertEquals(1, store.deviceEvents("notification", null, 0, 100).length());
    }
    @Test public void crashAfterSqlCommitBeforeSpoolAcknowledgementReplaysExactlyOnce() throws Exception {
        journal.closeWorker(); journal.awaitClosedForTests();
        JSONObject receipt = journal.append("device_state", "android.device_state", new JSONObject().put("interactive", true), 123, null); assertTrue(receipt.getBoolean("observationPersisted"));
        PhoneObservationSpool spool = new PhoneObservationSpool(journal.spoolDirectoryForTests()); JSONObject pending = spool.read(spool.pending(10)[0]);
        long id = store.appendDeviceEvent(pending.getString("category"), pending.getString("source"), pending.getJSONObject("payload"), pending.getLong("observedAt"), pending.getString("dedupeKey")).getLong("id");
        // Simulate a new process using the same private files while SQL is already committed.
        journal = PhoneEventJournal.bind(context, store); journal.ingestPendingForTests();
        assertEquals(1, store.deviceEvents("device_state", null, 0, 100).length()); assertEquals(id, store.deviceEvents("device_state", null, 0, 100).getJSONObject(0).getLong("id")); assertEquals(0, journal.health().getInt("pendingSpoolRecords"));
    }
    @Test public void corruptedSpoolIsPreservedAndCreatesAnExplicitGapInsteadOfSilentlyDisappearing() throws Exception {
        journal.closeWorker(); journal.awaitClosedForTests(); journal.append("device_state", "android.device_state", new JSONObject().put("value", "original"), 1, null);
        PhoneObservationSpool spool = new PhoneObservationSpool(journal.spoolDirectoryForTests()); File pending = spool.pending(10)[0];
        String changed = new String(Files.readAllBytes(pending.toPath()), StandardCharsets.UTF_8).replace("original", "tampered"); Files.write(pending.toPath(), changed.getBytes(StandardCharsets.UTF_8));
        journal = PhoneEventJournal.bind(context, store); journal.ingestPendingForTests();
        assertEquals(0, store.deviceEvents("device_state", null, 0, 100).length()); assertEquals(1, store.deviceEvents("collector_gap", "phone.private_spool", 0, 100).length());
        assertEquals(1, new File(journal.spoolDirectoryForTests(), "quarantine").listFiles().length);
    }
    @Test public void backupHardlinkSnapshotSurvivesLaterAcknowledgementAndReplaysAsADuplicate() throws Exception {
        journal.closeWorker(); journal.awaitClosedForTests(); journal.append("activity_sample", "android.step_sensor", new JSONObject().put("delta", 10), 1, null);
        File snapshot = new File(context.getCacheDir(), "journal-snapshot"); journal.captureBackupSnapshot(snapshot); assertEquals(1, new File(snapshot, "pending").listFiles().length);
        journal = PhoneEventJournal.bind(context, store); journal.ingestPendingForTests(); assertEquals(0, journal.health().getInt("pendingSpoolRecords"));
        PhoneObservationSpool restored = new PhoneObservationSpool(snapshot); JSONObject duplicate = restored.read(restored.pending(10)[0]);
        store.appendDeviceEvent(duplicate.getString("category"), duplicate.getString("source"), duplicate.getJSONObject("payload"), duplicate.getLong("observedAt"), duplicate.getString("dedupeKey"));
        assertEquals(1, store.deviceEvents("activity_sample", null, 0, 100).length());
    }
    @Test public void oversizeObservationFailureIsReportedAndNextDurableWriteRecordsTheGap() throws Exception {
        char[] data = new char[500001]; java.util.Arrays.fill(data, 'x');
        JSONObject rejected = journal.append("notification", "android.notification_listener", new JSONObject().put("text", new String(data)), 1, null); assertFalse(rejected.getBoolean("observationPersisted"));
        assertFalse(journal.health().getBoolean("ok")); journal.closeWorker(); journal.awaitClosedForTests(); journal = PhoneEventJournal.bind(context, store);
        assertFalse("The small persisted storage failure marker must survive restart", journal.health().getBoolean("ok"));
        assertTrue(journal.append("device_state", "android.device_state", new JSONObject().put("ok", true), 2, null).getBoolean("observationPersisted"));
        journal.ingestPendingForTests(); assertEquals(1, store.deviceEvents("collector_gap", null, 0, 100).length());
    }
    @Test public void immediateOwnerRevocationCannotBeOverwrittenByAnOlderBlockedPolicyRead() throws Exception {
        journal.closeWorker(); journal.awaitClosedForTests(); assertTrue(journal.toolAllowed("phone_notifications_read"));
        Thread policyRead = new Thread(journal::refreshPolicyBlocking);
        synchronized (store) {
            policyRead.start(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (policyRead.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(Thread.State.BLOCKED, policyRead.getState());
            journal.applyPermissionPolicyFromOwner("{\"phone_notifications_read\":false}", true);
            assertFalse(journal.toolAllowed("phone_notifications_read")); assertTrue(journal.collectionEnabled());
        }
        policyRead.join(2000); assertFalse(policyRead.isAlive()); assertFalse("Stale policy read undid owner revocation", journal.toolAllowed("phone_notifications_read"));
        journal.applyPermissionPolicyFromOwner("{\"phone_notifications_read\":\"true\"}", true);
        assertFalse("Invalid policy must fail closed", journal.collectionEnabled());
    }
    @Test public void restoreBarrierBlocksCallbacksAndRecreatesLegacyBackupJournalDirectories() throws Exception {
        journal.put("restore.test", "before-restore"); journal.pauseForBackupRestore();
        assertFalse(journal.collectionEnabled()); assertFalse(journal.toolAllowed("phone_notifications_read"));
        assertEquals("restore_in_progress", journal.append("device_state", "android.device_state", new JSONObject(), 1, null).getString("code"));
        File root = journal.spoolDirectoryForTests(); clearTestFiles(root); assertTrue(root.mkdirs()); // Legacy backup has no device-journal namespace content.
        journal.afterBackupRestore(); assertEquals("missing", journal.get("restore.test", "missing"));
        assertTrue(new File(root, "pending").isDirectory()); assertTrue(new File(root, "archive").isDirectory());
        assertTrue(journal.append("device_state", "android.device_state", new JSONObject().put("restored", true), 2, null).getBoolean("observationPersisted"));
        journal.ingestPendingForTests(); assertEquals(1, store.deviceEvents("device_state", null, 0, 100).length());
    }
    @Test public void ingestedRawObservationRemainsInArchiveAndBackupWithTheSameChecksum() throws Exception {
        journal.append("notification", "android.notification_listener", new JSONObject().put("text", "raw source bytes retained"), 999, null); journal.ingestPendingForTests();
        File archive = new File(journal.spoolDirectoryForTests(), "archive"); File[] buckets = archive.listFiles(); assertNotNull(buckets); assertEquals(1, buckets.length);
        File[] raw = buckets[0].listFiles(); assertNotNull(raw); assertEquals(1, raw.length);
        JSONObject original = new PhoneObservationSpool(journal.spoolDirectoryForTests()).read(raw[0]); assertEquals("raw source bytes retained", original.getJSONObject("payload").getString("text"));
        File snapshot = new File(context.getCacheDir(), "journal-snapshot"); journal.captureBackupSnapshot(snapshot);
        File[] copied = new File(new File(snapshot, "archive"), "segments").listFiles(file -> file.getName().endsWith(".hja")); assertNotNull(copied); assertEquals(1, copied.length);
        PhoneArchiveSegments.verify(copied[0], true, (path, bytes, checksum) -> { assertEquals(buckets[0].getName() + "/" + raw[0].getName(), path); assertArrayEquals(Files.readAllBytes(raw[0].toPath()), bytes); });
    }
    @Test public void validFsyncedPartialSurvivesInterruptedRenameAndIsReplayed() throws Exception {
        journal.closeWorker(); journal.awaitClosedForTests(); journal.append("device_state", "android.device_state", new JSONObject().put("value", "interrupted-but-complete"), 12, null);
        PhoneObservationSpool spool = new PhoneObservationSpool(journal.spoolDirectoryForTests()); File pending = spool.pending(10)[0];
        Files.move(pending.toPath(), new File(pending.getParentFile(), ".partial-synthetic-interrupted-write").toPath());
        journal = PhoneEventJournal.bind(context, store); journal.ingestPendingForTests();
        assertEquals(1, store.deviceEvents("device_state", null, 0, 100).length()); assertEquals(0, journal.health().getInt("pendingSpoolRecords"));
    }
    @Test public void archiveCorruptionReportsOneDurableFailureGapAcrossRestartAndClearsOnlyAfterVerification() throws Exception {
        journal.append("device_state", "android.device_state", new JSONObject().put("value", "retained original"), 17, null); journal.ingestPendingForTests();
        journal.closeWorker(); journal.awaitClosedForTests();
        File archive = new File(journal.spoolDirectoryForTests(), "archive"); File bucket = archive.listFiles()[0]; File raw = bucket.listFiles()[0];
        java.util.List<PhoneArchiveSegments.Source> sources = java.util.Collections.singletonList(new PhoneArchiveSegments.Source(bucket.getName() + "/" + raw.getName(), raw));
        File segment = PhoneArchiveSegments.write(new File(archive, "segments"), sources, PhoneArchiveSegments.MAX_SEGMENT_BYTES);
        byte[] originalContainer = Files.readAllBytes(segment.toPath()), changed = originalContainer.clone(); changed[changed.length - 2] ^= 1; Files.write(segment.toPath(), changed);
        journal = PhoneEventJournal.bind(context, store); journal.ingestPendingForTests(); journal.ingestPendingForTests();
        assertFalse(journal.health().getBoolean("ok")); assertFalse(journal.health().getString("archiveFailure").isEmpty()); assertTrue(raw.exists());
        assertEquals(1, store.deviceEvents("collector_gap", "phone.private_archive", 0, 100).length());
        journal.closeWorker(); journal.awaitClosedForTests(); journal = PhoneEventJournal.bind(context, store); journal.ingestPendingForTests();
        assertFalse(journal.health().getBoolean("ok")); assertEquals("Repeated detection of the same unrepaired corruption must not invent new gaps", 1, store.deviceEvents("collector_gap", "phone.private_archive", 0, 100).length());
        journal.closeWorker(); journal.awaitClosedForTests(); Files.write(segment.toPath(), originalContainer);
        journal = PhoneEventJournal.bind(context, store); journal.ingestPendingForTests();
        assertTrue(journal.health().toString(), journal.health().getBoolean("ok")); assertEquals("", journal.health().getString("archiveFailure"));
        assertEquals(1, store.deviceEvents("device_state", null, 0, 100).length()); assertEquals(1, store.deviceEvents("collector_gap", "phone.private_archive", 0, 100).length());
        PhoneArchiveSegments.verify(segment, true, null);
    }
    private static void clearTestFiles(File file) { if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) clearTestFiles(child); } if (file.exists()) assertTrue(file.delete()); }
}
