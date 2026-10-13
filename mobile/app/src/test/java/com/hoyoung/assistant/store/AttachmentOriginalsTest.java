package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AttachmentOriginalsTest {
    private Context context;
    private InternalFiles files;
    private HoyoungStore store;
    @Before public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); deleteTree(context.getFilesDir());
        store = new HoyoungStore(context); files = new InternalFiles(context);
    }
    @After public void tearDown() throws Exception { store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); deleteTree(context.getFilesDir()); }

    @Test public void ownerBinarySnapshotCannotBeOverwrittenEvenThroughInternalSymlinkAlias() throws Exception {
        byte[] original = {0, -1, 13, 10, 42};
        JSONObject working = files.writeFile("work/picture.bin", new ByteArrayInputStream(original));
        JSONObject frozen = files.freezeAttachments(new JSONArray().put(working)).getJSONObject(0);
        String id = frozen.getString("id");
        assertEquals(id, frozen.getString("originalAssetId")); assertEquals(sha(original), frozen.getString("sha256"));
        assertEquals("raw_saved", frozen.getString("coverage")); assertEquals("owner_attachment", frozen.getString("kind"));
        assertFalse(frozen.has("path"));
        files.writeFile("work/picture.bin", "replacement");
        assertArrayEquals(original, export(id));
        assertThrows(IOException.class, () -> files.writeFile(id, "cannot replace"));
        assertThrows(IOException.class, () -> files.writeFile(id + ".metadata.json", "cannot alter proof"));
        Files.createSymbolicLink(new File(context.getFilesDir(), "projects/alias").toPath(), new File(context.getFilesDir(), "projects/.attachment-originals").toPath());
        String alias = "alias/" + id.substring(id.lastIndexOf('/') + 1);
        assertThrows(IOException.class, () -> files.writeFile(alias, "cannot replace through alias"));
        assertArrayEquals(original, export(id));
        // Directory iteration may remove the target before its alias on another host.
        // A broken symlink must still be removed as an inode, without following it.
        deleteTree(new File(context.getFilesDir(), "projects/.attachment-originals"));
        File danglingAlias = new File(context.getFilesDir(), "projects/alias");
        assertTrue(Files.isSymbolicLink(danglingAlias.toPath()));
        assertFalse(danglingAlias.exists());
        deleteTree(danglingAlias);
        assertFalse(Files.isSymbolicLink(danglingAlias.toPath()));
    }

    @Test public void freezeReusesPayloadAndPreservesMetadataAfterReopenWhileHidingPrivateSidecars() throws Exception {
        JSONObject source = files.writeFile("old source.txt", "unchanged payload");
        JSONObject first = files.freezeAttachments(new JSONArray().put(source.getString("id"))).getJSONObject(0);
        JSONObject second = files.freezeAttachments(new JSONArray().put(first)).getJSONObject(0);
        assertEquals(first.getString("id"), second.getString("id"));
        files = new InternalFiles(context);
        JSONArray listed = files.listFiles();
        assertEquals(2, listed.length()); // One working source + one immutable payload, no sidecar.
        JSONObject reopened = files.freezeAttachments(new JSONArray().put(first.getString("id"))).getJSONObject(0);
        assertEquals("old source.txt", reopened.getString("sourceName")); assertEquals("text/plain", reopened.getString("mime"));
        assertEquals(first.getString("sha256"), reopened.getString("sha256"));
    }

    @Test public void safImportUsesActualProviderMimeAndExactPrivateBytes() throws Exception {
        File input = new File(context.getCacheDir(), "synthetic-import.bin"); Files.write(input.toPath(), new byte[]{1, 2, 3});
        SourceProvider provider = new SourceProvider(input);
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo(); info.authority = "synthetic.assets"; info.exported = true;
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal("synthetic.assets", provider);
        JSONObject imported = files.saveFromUri(Uri.parse("content://synthetic.assets/one"), null);
        assertEquals("image/png", imported.getString("mimeType")); assertEquals("content_resolver", imported.getString("mimeSource")); assertEquals("provider-original.dat", imported.getString("sourceName"));
        assertTrue(imported.getString("id").endsWith(".png"));
        Files.write(input.toPath(), new byte[]{7, 8, 9});
        assertArrayEquals(new byte[]{1, 2, 3}, export(imported.getString("id")));
        files = new InternalFiles(context);
        assertEquals("image/png", files.listFiles().getJSONObject(0).getString("mime"));
    }

    @Test public void malformedClaimsAndUnknownIdsDoNotBecomeSourceEvidence() throws Exception {
        JSONObject working = files.writeFile("keep.bin", "real bytes");
        JSONObject bad = new JSONObject(working.toString()).put("sha256", repeat('0', 64));
        assertThrows(IOException.class, () -> files.freezeAttachments(new JSONArray().put(bad)));
        JSONObject invalidSize = new JSONObject(working.toString()).put("size", 0);
        assertThrows(IOException.class, () -> files.freezeAttachments(new JSONArray().put(invalidSize)));
        assertThrows(IOException.class, () -> files.freezeAttachments(new JSONArray().put("../outside")));
        assertThrows(IOException.class, () -> files.freezeAttachments(new JSONArray().put("missing")));
        assertThrows(IOException.class, () -> files.freezeAttachments(new JSONArray().put(".attachment-originals")));
        assertEquals("real bytes", files.readText("keep.bin", 100));
    }

    @Test public void everySourceReadDetectsModifiedBinaryEvenWhenSizeAndTimestampArePreserved() throws Exception {
        JSONObject frozen = files.freezeAttachments(new JSONArray().put(files.writeFile("binary.dat", "AAAA"))).getJSONObject(0);
        File payload = new File(context.getFilesDir(), "projects/" + frozen.getString("id")); long timestamp = payload.lastModified();
        Files.write(payload.toPath(), "BBBB".getBytes(StandardCharsets.UTF_8)); assertTrue(payload.setLastModified(timestamp));
        assertThrows(IOException.class, () -> files.exportFile(frozen.getString("id"), new ByteArrayOutputStream()));
        assertThrows(IOException.class, () -> files.readText(frozen.getString("id"), 100));
        assertThrows(IOException.class, () -> files.freezeAttachments(new JSONArray().put(frozen)));
    }

    @Test public void backupPreservesAttachmentsAndRejectsCoherentZipWithFalseContentAddressBeforeReplacingAnything() throws Exception {
        JSONObject frozen = files.freezeAttachments(new JSONArray().put(files.writeFile("evidence.txt", "AAA"))).getJSONObject(0);
        store.putSetting("attachmentMetadata:owner-message", new JSONArray().put(frozen).toString());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); files.exportBackup(bytes, store); byte[] archive = bytes.toByteArray();
        files.writeFile("evidence.txt", "mutable source changed");
        files.importBackup(new ByteArrayInputStream(archive), store);
        assertEquals("AAA", new String(export(frozen.getString("id")), StandardCharsets.UTF_8));
        byte[] malicious = coherentReplacement(archive, "projects/" + frozen.getString("id"), "BBB".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> files.importBackup(new ByteArrayInputStream(malicious), store));
        assertEquals("AAA", files.readText("evidence.txt", 100));
        assertEquals("AAA", new String(export(frozen.getString("id")), StandardCharsets.UTF_8));
        assertEquals(frozen.getString("sha256"), new JSONArray(store.getSetting("attachmentMetadata:owner-message", "[]")).getJSONObject(0).getString("sha256"));
    }

    @Test public void largeToolResultRetainsPurposeExactBytesAndPagedEmojiRanges() throws Exception {
        String text = repeat('x', 220_000) + "😀 END";
        JSONObject saved = files.archiveToolResult("synthetic-root", "result-1", text);
        assertEquals("tool_result", saved.getString("kind")); assertEquals(text.length(), saved.getInt("totalChars"));
        assertEquals("application/json", saved.getString("mime"));
        assertEquals(text, new String(export(saved.getString("id")), StandardCharsets.UTF_8));
        JSONObject chunk = files.readTextRange(saved.getString("id"), 220_000, 1);
        assertEquals("", chunk.getString("text"));
        chunk = files.readTextRange(saved.getString("id"), 220_000, 100);
        assertEquals("😀 END", chunk.getString("text")); assertTrue(chunk.getBoolean("complete"));
        assertEquals(220_006, chunk.getLong("nextOffsetChars")); assertEquals(saved.getString("sha256"), chunk.getString("contentHash"));
        assertThrows(IOException.class, () -> files.readTextRange(saved.getString("id"), 220_001, 100));
        assertThrows(IOException.class, () -> files.archiveToolResult("root", "too-large", repeat('x', 10_000_001)));
        files = new InternalFiles(context);
        assertEquals("tool_result", files.listFiles().getJSONObject(0).getString("kind"));
    }

    @Test public void filesReadToolPagesWithoutSilentTruncationAndRejectsInvalidUtf8AndArgumentTypes() throws Exception {
        String body = repeat('a', 24_000) + "😀 exact next page";
        files.writeFile("paged.txt", body);
        JSONObject first = files.execute("files.read", new JSONObject().put("fileId", "paged.txt").put("maxChars", 24_000));
        assertEquals(24_000, first.getString("text").length()); assertFalse(first.getBoolean("complete"));
        assertEquals(body.length(), first.getLong("totalChars"));
        JSONObject last = files.execute("files.read", new JSONObject().put("fileId", "paged.txt").put("offsetChars", first.getLong("nextOffsetChars")));
        assertEquals("😀 exact next page", last.getString("text")); assertTrue(last.getBoolean("complete"));
        assertEquals(first.getString("contentHash"), last.getString("contentHash"));
        assertThrows(IOException.class, () -> files.execute("files.read", new JSONObject().put("fileId", "paged.txt").put("maxChars", 24_001)));
        assertThrows(IOException.class, () -> files.execute("files.read", new JSONObject().put("fileId", "paged.txt").put("offsetChars", "0")));
        assertThrows(IOException.class, () -> files.execute("files.read", new JSONObject().put("fileId", "paged.txt").put("maxChars", .5)));
        files.writeFile("invalid.txt", new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28}));
        assertThrows(IOException.class, () -> files.execute("files.read", new JSONObject().put("fileId", "invalid.txt")));
        assertThrows(IOException.class, () -> files.readTextRange("invalid.txt", 0, 10));
        assertNotNull(files.readText("invalid.txt", 10)); // Legacy direct view is deliberately unchanged.
    }

    public static final class SourceProvider extends ContentProvider {
        final File input; SourceProvider(File input) { this.input = input; }
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "image/png"; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME}); cursor.addRow(new Object[]{"provider-original.dat"}); return cursor; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException { return ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY); }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
    private byte[] export(String id) throws IOException { ByteArrayOutputStream output = new ByteArrayOutputStream(); files.exportFile(id, output); return output.toByteArray(); }
    private static String repeat(char value, int count) { char[] text = new char[count]; Arrays.fill(text, value); return new String(text); }
    private static String sha(byte[] bytes) throws Exception { byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder text = new StringBuilder(); for (byte value : hash) text.append(String.format(Locale.ROOT, "%02x", value & 255)); return text.toString(); }
    private static byte[] coherentReplacement(byte[] archive, String path, byte[] replacement) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) { ZipEntry entry; while ((entry = input.getNextEntry()) != null) { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = input.read(buf)) != -1) bytes.write(buf, 0, n); entries.put(entry.getName(), bytes.toByteArray()); } }
        entries.put(path, replacement);
        StringBuilder checksums = new StringBuilder();
        for (String line : new String(entries.get("checksums.ndjson"), StandardCharsets.UTF_8).split("\n")) {
            JSONObject row = new JSONObject(line); if (path.equals(row.getString("path"))) row.put("bytes", replacement.length).put("sha256", sha(replacement)); checksums.append(row).append('\n');
        }
        byte[] index = checksums.toString().getBytes(StandardCharsets.UTF_8); entries.put("checksums.ndjson", index);
        JSONObject manifest = new JSONObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8)); manifest.getJSONObject("checksumIndex").put("bytes", index.length).put("sha256", sha(index));
        entries.put("manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (ZipOutputStream zip = new ZipOutputStream(bytes)) { for (Map.Entry<String, byte[]> entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); } } return bytes.toByteArray();
    }
    private static void deleteTree(File root) throws IOException { if (Files.isSymbolicLink(root.toPath())) { Files.deleteIfExists(root.toPath()); return; } if (!root.exists()) return; if (root.isDirectory()) { File[] children = root.listFiles(); if (children == null) throw new IOException("Cannot inspect synthetic files"); for (File child : children) deleteTree(child); } if (!root.delete()) throw new IOException("Cannot delete synthetic file"); }
}
