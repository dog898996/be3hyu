package com.hoyoung.assistant.phone;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class PhoneArchiveCompactionTest {
    private File root, snapshot;
    @Before public void setup() throws Exception { root = Files.createTempDirectory("hoyoung-raw-segments-").toFile(); snapshot = Files.createTempDirectory("hoyoung-raw-snapshot-").toFile(); }
    @After public void cleanup() throws Exception { clear(root); clear(snapshot); }

    @Test public void liveRotationRetainsExactBytesNamesAndChecksumsAcrossDays() throws Exception {
        PhoneObservationSpool spool = new PhoneObservationSpool(root, 2048, 4);
        Map<String, byte[]> originals = seedRawFiles(17, 3, 250);
        int guard = 0; while (spool.archiveMaintenanceNeeded()) { spool.compactArchive(); assertTrue(++guard < 50); }
        Map<String, byte[]> actual = readArchive(new File(root, "archive")); assertOriginals(originals, actual);
        File[] segments = segmentFiles(root); assertTrue(segments.length >= 3);
        for (File segment : segments) { assertTrue(segment.length() <= 2048); assertTrue(new File(segment.getPath() + ".done").isFile()); }
        assertTrue(rawFileCount() < 4); assertEquals(0, spool.pendingCount());
    }
    @Test public void backupPacksLargeUncompactedBacklogBelowAnInjectedZipEntryBudget() throws Exception {
        PhoneObservationSpool spool = new PhoneObservationSpool(root);
        Map<String, byte[]> originals = seedRawFiles(1025, 9, 27);
        spool.captureSnapshot(snapshot);
        int simulatedEntryLimit = 10;
        assertTrue("Raw archive would have exceeded the synthetic ZIP entry limit", originals.size() > simulatedEntryLimit);
        assertTrue("Snapshot still uses one inode per raw observation", countFiles(new File(snapshot, "archive")) < simulatedEntryLimit);
        assertEquals(5, segmentFiles(snapshot).length); assertOriginals(originals, readArchive(new File(snapshot, "archive")));
        assertEquals("Backup capture must not delete originals", originals.size(), rawFileCount());
        PhoneObservationSpool restored = new PhoneObservationSpool(snapshot);
        assertEquals("Acknowledged raw archive must never re-enter pending ingestion", 0, restored.pendingCount());
        restored.compactArchive(); assertOriginals(originals, readArchive(new File(snapshot, "archive")));
    }
    @Test public void restartFinishesPublishBeforeCleanupAndMidCleanupWithoutReingestion() throws Exception {
        new PhoneObservationSpool(root); Map<String, byte[]> originals = seedRawFiles(4, 1, 42);
        File segment = PhoneArchiveSegments.write(new File(root, "archive/segments"), sources(originals), PhoneArchiveSegments.MAX_SEGMENT_BYTES);
        assertTrue(new File(root, "archive/" + originals.keySet().iterator().next()).delete()); // Crash after one unlink.
        PhoneObservationSpool restarted = new PhoneObservationSpool(root, PhoneArchiveSegments.MAX_SEGMENT_BYTES, 4);
        restarted.compactArchive(); assertEquals(0, rawFileCount()); assertTrue(new File(segment.getPath() + ".done").isFile());
        assertOriginals(originals, readArchive(new File(root, "archive"))); assertEquals(0, restarted.pendingCount());
        PhoneObservationSpool again = new PhoneObservationSpool(root); again.compactArchive(); assertEquals(1, segmentFiles(root).length);
    }
    @Test public void corruptedPublishedSegmentKeepsOriginalsAndRefusesAFalseValidBackup() throws Exception {
        new PhoneObservationSpool(root); Map<String, byte[]> originals = seedRawFiles(4, 1, 42);
        File segment = PhoneArchiveSegments.write(new File(root, "archive/segments"), sources(originals), PhoneArchiveSegments.MAX_SEGMENT_BYTES);
        byte[] changed = Files.readAllBytes(segment.toPath()); changed[changed.length - 2] ^= 1; Files.write(segment.toPath(), changed);
        PhoneObservationSpool restarted = new PhoneObservationSpool(root, PhoneArchiveSegments.MAX_SEGMENT_BYTES, 4);
        expectIo(restarted::compactArchive); assertEquals(4, rawFileCount()); assertTrue(segment.exists());
        expectIo(() -> restarted.captureSnapshot(snapshot));
        for (Map.Entry<String, byte[]> entry : originals.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(new File(root, "archive/" + entry.getKey()).toPath()));
    }
    @Test public void incompleteContainerIsQuarantinedWithGapWhileCompletePartialIsRecovered() throws Exception {
        PhoneObservationSpool spool = new PhoneObservationSpool(root, PhoneArchiveSegments.MAX_SEGMENT_BYTES, 4);
        Map<String, byte[]> originals = seedRawFiles(4, 1, 42); File segment = PhoneArchiveSegments.write(new File(root, "archive/segments"), sources(originals), PhoneArchiveSegments.MAX_SEGMENT_BYTES);
        File validPartial = new File(segment.getParentFile(), ".partial-segment-complete"); Files.move(segment.toPath(), validPartial.toPath());
        Files.write(new File(segment.getParentFile(), ".partial-segment-truncated").toPath(), "incomplete-original-preserved".getBytes(StandardCharsets.UTF_8));
        spool.compactArchive(); assertFalse(validPartial.exists()); assertEquals(0, rawFileCount()); assertEquals(1, segmentFiles(root).length);
        assertOriginals(originals, readArchive(new File(root, "archive")));
        File[] quarantine = new File(root, "quarantine").listFiles(); assertNotNull(quarantine); assertEquals(1, quarantine.length);
        assertEquals("incomplete-original-preserved", new String(Files.readAllBytes(quarantine[0].toPath()), StandardCharsets.UTF_8));
        JSONObject gap = spool.read(spool.pending(10)[0]); assertEquals("collector_gap", gap.getString("category")); assertEquals("interrupted_archive_segment_quarantined", gap.getJSONObject("payload").getString("reason"));
    }
    @Test public void snapshotPinsPreventThePublishThenDeleteEnumerationRace() throws Exception {
        PhoneObservationSpool spool = new PhoneObservationSpool(root, PhoneArchiveSegments.MAX_SEGMENT_BYTES, 4); Map<String, byte[]> originals = seedRawFiles(4, 1, 42);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        spool.captureSnapshot(snapshot, () -> {
            try { spool.compactArchive(); assertEquals("The snapshot listed segments before publication: originals must remain pinned", 4, rawFileCount()); assertEquals(1, segmentFiles(root).length); }
            catch (Throwable problem) { failure.set(problem); }
        });
        assertNull(failure.get()); assertOriginals(originals, readArchive(new File(snapshot, "archive")));
        spool.compactArchive(); assertEquals(0, rawFileCount()); assertOriginals(originals, readArchive(new File(root, "archive")));
    }
    @Test public void blockedBackgroundSnapshotNeverHoldsTheCallbackMonitor() throws Exception {
        PhoneObservationSpool spool = new PhoneObservationSpool(root, PhoneArchiveSegments.MAX_SEGMENT_BYTES, 4); seedRawFiles(4, 1, 42);
        CountDownLatch snapshotEntered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread backup = new Thread(() -> { try { spool.captureSnapshot(snapshot, () -> { snapshotEntered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } }); } catch (Throwable problem) { failure.set(problem); } }); backup.start();
        try {
            assertTrue(snapshotEntered.await(2, TimeUnit.SECONDS)); long started = System.nanoTime();
            spool.append("device_state", "android.device_state", new JSONObject().put("synthetic", true), 123, null);
            assertTrue("Callback waited for background archive copying", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
            assertEquals(1, spool.pendingCount());
        } finally { release.countDown(); backup.join(3000); }
        assertFalse(backup.isAlive()); assertNull(failure.get());
    }
    @Test public void changedOriginalIsRetainedRatherThanDeletedAfterPublishingEarlierBytes() throws Exception {
        new PhoneObservationSpool(root); Map<String, byte[]> originals = seedRawFiles(4, 1, 42);
        File segment = PhoneArchiveSegments.write(new File(root, "archive/segments"), sources(originals), PhoneArchiveSegments.MAX_SEGMENT_BYTES);
        String first = originals.keySet().iterator().next(); File changed = new File(root, "archive/" + first); byte[] modified = originals.get(first).clone(); modified[0] ^= 1; Files.write(changed.toPath(), modified);
        PhoneObservationSpool restarted = new PhoneObservationSpool(root, PhoneArchiveSegments.MAX_SEGMENT_BYTES, 4); expectIo(restarted::compactArchive);
        assertArrayEquals(modified, Files.readAllBytes(changed.toPath()));
        Map<String, byte[]> retained = new LinkedHashMap<>(); PhoneArchiveSegments.verify(segment, true, (path, bytes, checksum) -> retained.put(path, bytes)); assertOriginals(originals, retained);
        assertFalse(new File(segment.getPath() + ".done").exists());
    }
    @Test public void malformedNamesAndTruncatedFramesCannotBecomeTrustedSegments() throws Exception {
        new PhoneObservationSpool(root); File raw = new File(root, "synthetic.json"); Files.write(raw.toPath(), new byte[]{1, 2, 3});
        expectIo(() -> new PhoneArchiveSegments.Source("../synthetic.json", raw));
        expectIo(() -> new PhoneArchiveSegments.Source("1/../../synthetic.json", raw));
        Map<String, byte[]> originals = seedRawFiles(1, 1, 42); File segment = PhoneArchiveSegments.write(new File(root, "archive/segments"), sources(originals), PhoneArchiveSegments.MAX_SEGMENT_BYTES);
        byte[] bytes = Files.readAllBytes(segment.toPath()); Files.write(segment.toPath(), java.util.Arrays.copyOf(bytes, bytes.length - 16));
        expectIo(() -> PhoneArchiveSegments.verify(segment, true, null)); assertEquals(1, rawFileCount());
    }
    @Test public void repeatSnapshotCannotTruncateExistingHardlinkedOriginals() throws Exception {
        PhoneObservationSpool spool = new PhoneObservationSpool(root);
        spool.append("device_state", "android.device_state", new JSONObject().put("original", "immutable"), 1, null);
        File original = spool.pending(1)[0]; byte[] bytes = Files.readAllBytes(original.toPath());
        spool.captureSnapshot(snapshot); expectIo(() -> spool.captureSnapshot(snapshot));
        assertArrayEquals(bytes, Files.readAllBytes(original.toPath()));
        File copied = new File(snapshot, "pending/" + original.getName()); assertArrayEquals(bytes, Files.readAllBytes(copied.toPath()));
    }
    @Test public void validOuterZipWithInvalidInternalSegmentIsRejectedBeforeAnyRestoreSwap() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        HoyoungStore store = new HoyoungStore(context); InternalFiles files = new InternalFiles(context);
        PhoneObservationSpool spool = new PhoneObservationSpool(root); seedRawFiles(4, 1, 42);
        try {
            files.writeFile("keep.txt", "backup tree"); store.addMessage("user", "backup original", null);
            files.setBackupParticipant(destination -> {
                spool.captureSnapshot(destination);
                File segment = segmentFiles(destination)[0]; byte[] changed = Files.readAllBytes(segment.toPath()); changed[changed.length - 2] ^= 1; Files.write(segment.toPath(), changed);
            });
            // Export hashes the already modified file: the outer ZIP/checksum index is valid.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); files.exportBackup(bytes, store);
            files.writeFile("keep.txt", "active tree"); long id = store.addMessage("user", "active original", null).getLong("id");
            store.putSetting("autonomy.enabled", "true"); store.putSetting("internal.files.restoreToken", "active-durable-token");
            File activeJournal = new File(context.getFilesDir(), "device-journal/active.json"); assertTrue(activeJournal.getParentFile().isDirectory() || activeJournal.getParentFile().mkdirs()); Files.write(activeJournal.toPath(), "active journal".getBytes(StandardCharsets.UTF_8));
            java.util.concurrent.atomic.AtomicBoolean validatorInvoked = new java.util.concurrent.atomic.AtomicBoolean();
            files.setBackupParticipant(new InternalFiles.BackupParticipant() {
                @Override public void capture(File destination) throws IOException { spool.captureSnapshot(destination); }
                @Override public void validate(File staged) throws IOException { validatorInvoked.set(true); PhoneEventJournal.validateBackupSnapshot(staged); }
            });
            expectIo(() -> files.importBackup(new ByteArrayInputStream(bytes.toByteArray()), store));
            assertTrue("The outer checksum passed and the staged internal validator ran", validatorInvoked.get());
            assertEquals("active tree", files.readText("keep.txt", 100)); assertEquals("active original", store.getMessage(id).getString("text"));
            assertEquals("true", store.getSetting("autonomy.enabled", "false")); assertEquals("active-durable-token", store.getSetting("internal.files.restoreToken", ""));
            assertEquals("active journal", new String(Files.readAllBytes(activeJournal.toPath()), StandardCharsets.UTF_8));
            assertFalse(new File(context.getFilesDir(), ".restore-transaction.json").exists());
        } finally { store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); }
    }

    private Map<String, byte[]> seedRawFiles(int count, int days, int bodyLength) throws Exception {
        Map<String, byte[]> output = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String path = (1 + index % days) + "/synthetic-" + index + ".json"; byte[] bytes = ("{\"value\":\"사진 원문 " + index + " " + repeat('x', bodyLength) + "\"}\n").getBytes(StandardCharsets.UTF_8);
            File raw = new File(root, "archive/" + path); assertTrue(raw.getParentFile().isDirectory() || raw.getParentFile().mkdirs()); Files.write(raw.toPath(), bytes); output.put(path, bytes);
        }
        return output;
    }
    private List<PhoneArchiveSegments.Source> sources(Map<String, byte[]> entries) throws IOException { List<PhoneArchiveSegments.Source> sources = new ArrayList<>(); for (String path : entries.keySet()) sources.add(new PhoneArchiveSegments.Source(path, new File(root, "archive/" + path))); return sources; }
    private static Map<String, byte[]> readArchive(File archive) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>(); File[] dirs = archive.listFiles(); assertNotNull(dirs);
        for (File dir : dirs) {
            File[] files = dir.listFiles(); assertNotNull(files);
            if (dir.getName().equals("segments")) {
                for (File segment : files) if (segment.getName().endsWith(".hja")) PhoneArchiveSegments.verify(segment, true, (path, bytes, checksum) -> { assertEquals(PhoneEventJournal.hash(new String(bytes, StandardCharsets.UTF_8)), checksum); byte[] old = result.put(path, bytes); if (old != null) assertArrayEquals(old, bytes); });
            } else for (File raw : files) { String path = dir.getName() + "/" + raw.getName(); byte[] bytes = Files.readAllBytes(raw.toPath()); byte[] old = result.put(path, bytes); if (old != null) assertArrayEquals(old, bytes); }
        }
        return result;
    }
    private File[] segmentFiles(File base) { File[] files = new File(base, "archive/segments").listFiles(file -> file.getName().endsWith(".hja")); return files == null ? new File[0] : files; }
    private int rawFileCount() { int count = 0; File[] dirs = new File(root, "archive").listFiles(); if (dirs != null) for (File dir : dirs) if (!dir.getName().equals("segments")) count += countFiles(dir); return count; }
    private static int countFiles(File file) { if (file.isFile()) return 1; int count = 0; File[] files = file.listFiles(); if (files != null) for (File child : files) count += countFiles(child); return count; }
    private static void assertOriginals(Map<String, byte[]> expected, Map<String, byte[]> actual) { assertEquals(expected.keySet(), actual.keySet()); for (String path : expected.keySet()) assertArrayEquals(path, expected.get(path), actual.get(path)); }
    private static String repeat(char character, int count) { char[] chars = new char[count]; java.util.Arrays.fill(chars, character); return new String(chars); }
    private interface IoCall { void run() throws IOException; }
    private static void expectIo(IoCall operation) throws Exception { try { operation.run(); fail("Expected an explicit I/O validation failure"); } catch (IOException expected) { assertNotNull(expected.getMessage()); } }
    private static void clear(File file) throws Exception { if (file.isDirectory()) { File[] files = file.listFiles(); if (files != null) for (File child : files) clear(child); } if (file.exists()) Files.delete(file.toPath()); }
}
