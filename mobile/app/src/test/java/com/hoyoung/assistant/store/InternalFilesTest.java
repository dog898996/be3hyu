package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class InternalFilesTest {
    private Context context;
    private HoyoungStore store;
    private InternalFiles files;

    @Before public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        deleteTree(new File(context.getFilesDir(), "projects"));
        store = new HoyoungStore(context);
        files = new InternalFiles(context);
    }

    @After public void tearDown() throws Exception {
        store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        deleteTree(new File(context.getFilesDir(), "projects"));
    }

    @Test public void filesAndDatabaseSurviveCacheCleanupAndReopen() throws Exception {
        JSONObject saved = files.writeFile("project/main.txt", "permanent original");
        File actual = new File(saved.getString("path"));
        assertTrue(actual.getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath() + File.separator));
        assertFalse(actual.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath() + File.separator));
        store.addMessage("user", "remember me", null);
        Files.write(new File(context.getCacheDir(), "disposable.tmp").toPath(), new byte[]{1, 2, 3});
        deleteTree(context.getCacheDir());
        store.close();
        store = new HoyoungStore(context);
        files = new InternalFiles(context);
        assertEquals("permanent original", files.readText(saved.getString("id"), 100));
        assertEquals("remember me", store.listMessages(10).getJSONObject(0).getString("text"));
        assertEquals("perm", files.readText(saved.getString("id"), 4));
    }

    @Test public void traversalAndAbsolutePathsCannotReadOrReplacePrivateHostFiles() throws Exception {
        files.writeFile("keep.txt", "keep");
        for (String path : Arrays.asList("../outside.txt", "/tmp/host.txt", "nested/../../outside.txt", "a\\..\\host.txt", "C:/host.txt")) {
            assertThrows(IOException.class, () -> files.writeFile(path, "evil"));
            assertThrows(IOException.class, () -> files.readText(path, 100));
        }
        assertEquals("keep", files.readText("keep.txt", 100));
        assertEquals(1, files.listFiles().length());
    }

    @Test public void oversizedStreamFailsAtomicallyAndLeavesPreviousContent() throws Exception {
        files.writeFile("stable.txt", "before");
        InputStream oversized = new InputStream() {
            long remaining = InternalFiles.MAX_FILE_BYTES + 1;
            @Override public int read() { return remaining-- > 0 ? 0 : -1; }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (remaining <= 0) return -1;
                int count = (int) Math.min(length, remaining);
                Arrays.fill(bytes, offset, offset + count, (byte) 0);
                remaining -= count;
                return count;
            }
        };
        assertThrows(IOException.class, () -> files.writeFile("stable.txt", oversized));
        assertEquals("before", files.readText("stable.txt", 100));
        assertEquals(1, files.listFiles().length());
    }

    @Test public void binaryBackupRoundTripRestoresOriginalsRevisionsFilesAndUncertainQueue() throws Exception {
        byte[] binary = new byte[]{0, -1, 13, 10, 0, 42};
        JSONObject file = files.writeFile("art/image.bin", new ByteArrayInputStream(binary));
        long source = store.addMessage("user", "exact original \n원문", null).getLong("id");
        store.upsertMemory("fact", "first version", "fact", new JSONArray().put(source));
        store.upsertMemory("fact", "corrected version", "fact", new JSONArray().put(source));
        long request = store.enqueueRequest("already sent", "owner", "remote-id").getLong("id");
        store.markRequest(request, "accepted", "remote task");
        store.putSetting("owner", "original setting");
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        files.exportBackup(backup, store);

        files.writeFile("art/image.bin", "modified");
        files.writeFile("extra.txt", "remove during restore");
        store.putSetting("owner", "changed");
        store.addMessage("assistant", "extra message", null);
        files.importBackup(new ByteArrayInputStream(backup.toByteArray()), store);

        ByteArrayOutputStream restored = new ByteArrayOutputStream();
        files.exportFile(file.getString("id"), restored);
        assertArrayEquals(binary, restored.toByteArray());
        assertEquals(1, files.listFiles().length());
        assertEquals("exact original \n원문", store.listMessages(100).getJSONObject(0).getString("text"));
        assertEquals(1, store.listMessages(100).length());
        assertEquals(2, store.memoryRevisions("fact", 100).length());
        assertEquals("original setting", store.getSetting("owner", ""));
        assertEquals("uncertain", store.getRequest(request).getString("status"));
        assertNull(store.nextQueuedRequest());
    }

    @Test public void maliciousZipIsRejectedBeforeReplacingAnyExistingFileOrDatabase() throws Exception {
        files.writeFile("keep.txt", "before");
        store.addMessage("user", "keep original", null);
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new ZipEntry("projects/../../outside.txt"));
            zip.write("evil".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> files.importBackup(new ByteArrayInputStream(zipBytes.toByteArray()), store));
        assertEquals("before", files.readText("keep.txt", 100));
        assertEquals("keep original", store.listMessages(10).getJSONObject(0).getString("text"));
        assertFalse(new File(context.getFilesDir().getParentFile(), "outside.txt").exists());
    }

    @Test public void validZipWithInvalidSourcesCannotReplaceExistingData() throws Exception {
        files.writeFile("keep.txt", "before");
        long original = store.addMessage("user", "keep", null).getLong("id");
        store.upsertMemory("memory", "keep memory", "fact", new JSONArray().put(original));
        JSONObject snapshot = store.exportSnapshot();
        snapshot.getJSONArray("memories").getJSONObject(0).put("sourceMessageIds", new JSONArray().put(99999));
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            entry(zip, "manifest.json", "{\"format\":\"hoyoung-private-backup\",\"version\":1}");
            entry(zip, "hoyoung/store.json", snapshot.toString());
            entry(zip, "projects/keep.txt", "malicious replacement");
        }
        assertThrows(IOException.class, () -> files.importBackup(new ByteArrayInputStream(archive.toByteArray()), store));
        assertEquals("before", files.readText("keep.txt", 100));
        assertEquals("keep", store.getMessage(original).getString("text"));
    }

    @Test public void participantValidationRejectsBeforeAnyLiveNamespaceOrDatabaseSwap() throws Exception {
        files.writeFile("keep.txt", "backup tree");
        store.addMessage("user", "backup original", null);
        files.setBackupParticipant(destination -> {
            File raw = new File(destination, "archive/segments/synthetic.hja");
            assertTrue(raw.getParentFile().mkdirs());
            Files.write(raw.toPath(), "invalid internal frame".getBytes(StandardCharsets.UTF_8));
        });
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        files.exportBackup(backup, store);
        files.writeFile("keep.txt", "active tree");
        long activeId = store.addMessage("user", "active original", null).getLong("id");
        store.putSetting("autonomy.enabled", "true");
        File activeJournal = new File(context.getFilesDir(), "device-journal/active.json");
        assertTrue(activeJournal.getParentFile().isDirectory() || activeJournal.getParentFile().mkdirs());
        Files.write(activeJournal.toPath(), "active journal".getBytes(StandardCharsets.UTF_8));
        files.setBackupParticipant(new InternalFiles.BackupParticipant() {
            @Override public void capture(File destination) { }
            @Override public void validate(File stagedJournal) throws IOException {
                assertTrue(new File(stagedJournal, "archive/segments/synthetic.hja").isFile());
                assertEquals("active tree", files.readText("keep.txt", 100));
                throw new IOException("Synthetic internal frame validation failed");
            }
        });
        assertThrows(IOException.class, () -> files.importBackup(new ByteArrayInputStream(backup.toByteArray()), store));
        assertEquals("active tree", files.readText("keep.txt", 100));
        assertEquals("active original", store.getMessage(activeId).getString("text"));
        assertEquals("true", store.getSetting("autonomy.enabled", "false"));
        assertEquals("active journal", new String(Files.readAllBytes(activeJournal.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(context.getFilesDir(), ".restore-transaction.json").exists());
    }

    @Test public void restartRollsBackFileSwapIfBackupDatabaseNeverCommitted() throws Exception {
        files.writeFile("keep.txt", "original tree");
        JSONObject record = simulateInterruptedDirectorySwap("uncommitted tree");
        files = new InternalFiles(context);
        assertEquals("original tree", files.readText("keep.txt", 100));
        assertFalse(new File(context.getFilesDir(), record.getString("previous")).exists());
        assertFalse(new File(context.getFilesDir(), ".restore-transaction.json").exists());
    }

    @Test public void restartKeepsRestoredFilesIfDatabaseCommitTokenMatches() throws Exception {
        files.writeFile("keep.txt", "old tree");
        JSONObject record = simulateInterruptedDirectorySwap("committed tree");
        store.putSetting(HoyoungStore.FILE_RESTORE_TOKEN_KEY, record.getString("token"));
        files = new InternalFiles(context);
        assertEquals("committed tree", files.readText("keep.txt", 100));
        assertFalse(new File(context.getFilesDir(), record.getString("previous")).exists());
        assertFalse(new File(context.getFilesDir(), ".restore-transaction.json").exists());
    }

    private JSONObject simulateInterruptedDirectorySwap(String newText) throws Exception {
        File privateRoot = context.getFilesDir();
        File staging = new File(privateRoot, ".restore-" + UUID.randomUUID());
        File stagedProjects = new File(staging, "projects");
        assertTrue(stagedProjects.mkdirs());
        Files.write(new File(stagedProjects, "keep.txt").toPath(), newText.getBytes(StandardCharsets.UTF_8));
        File previous = new File(privateRoot, ".previous-projects-" + UUID.randomUUID());
        JSONObject record = new JSONObject().put("token", UUID.randomUUID().toString())
                .put("staging", staging.getName()).put("previous", previous.getName());
        Files.write(new File(privateRoot, ".restore-transaction.json").toPath(), record.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(new File(privateRoot, "projects").toPath(), previous.toPath());
        Files.move(stagedProjects.toPath(), new File(privateRoot, "projects").toPath());
        return record;
    }

    private static void entry(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }

    private static void deleteTree(File root) throws IOException {
        if (!root.exists()) return;
        if (root.isDirectory()) {
            File[] children = root.listFiles();
            if (children == null) throw new IOException("Cannot list test directory");
            for (File child : children) deleteTree(child);
        }
        if (!root.delete()) throw new IOException("Cannot delete test directory");
    }
}
