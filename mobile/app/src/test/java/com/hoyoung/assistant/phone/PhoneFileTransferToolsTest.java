package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import com.hoyoung.assistant.store.SourcePrivacyLedger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import static org.junit.Assert.*;

/** Synthetic shared files and provider bytes only; no personal filesystem/media or Work/model calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class PhoneFileTransferToolsTest {
    private Context context;
    private HoyoungStore store;
    private InternalFiles files;
    private PhoneEventJournal journal;
    private PhoneFileTransferTools tools;
    private File shared, fixture;
    private String prefix;

    @Before public void before() throws Exception {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        store = new HoyoungStore(context); files = new InternalFiles(context); journal = PhoneEventJournal.bind(context, store);
        store.putSetting("toolPermissions", "{}"); journal.refreshPolicyBlocking(); tools = new PhoneFileTransferTools(context, files, journal);
        shared = Environment.getExternalStorageDirectory().getCanonicalFile(); prefix = "hoyoung-binary-fixture-" + UUID.randomUUID(); fixture = new File(shared, prefix); assertTrue(fixture.mkdirs());
        Shadows.shadowOf((Application)context).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }
    @After public void after() throws Exception { journal.closeWorker(); delete(fixture); store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); }
    private void grant() { Shadows.shadowOf((Application)context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE); }
    private static JSONObject object(Object... values) throws Exception { JSONObject result = new JSONObject(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result; }
    private String path(String name) { return prefix + "/" + name; }
    private File binary(String name, byte[] bytes) throws Exception { File file = new File(fixture, name); Files.write(file.toPath(), bytes); return file; }
    private JSONObject imported(String name, String requestId, boolean upload) throws Exception {
        JSONObject first = tools.execute("phone_files_import", object("path", path(name), "requestId", requestId, "uploadRequested", upload));
        assertTrue(first.toString(), first.getBoolean("ok")); journal.ingestPendingForTests();
        JSONObject ready = tools.execute("phone_files_transfer_read", object("requestId", requestId)); assertTrue(ready.toString(), ready.getBoolean("ok")); assertFalse(ready.optBoolean("sourceIndexPending")); return ready;
    }
    private byte[] exportedBytes(JSONObject asset) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); files.exportFile(asset.getString("id"), out); return out.toByteArray(); }

    @Test public void permissionsAndOwnerSwitchesPreventBinaryAccessAndMutations() throws Exception {
        binary("private.bin", new byte[]{0, (byte)255, 1});
        assertEquals("permission_missing", tools.execute("phone_files_import", object("path", path("private.bin"), "requestId", "denied")).getString("code"));
        assertEquals("permission_missing", tools.execute("phone_files_delete", object("path", path("private.bin"))).getString("code"));
        grant(); store.putSetting("toolPermissions", "{\"phone_files_copy\":false,\"phone_files_import\":false}");
        assertEquals("permission_disabled", tools.execute("phone_files_import", object("path", path("private.bin"), "requestId", "off")).getString("code"));
        assertEquals("permission_disabled", tools.execute("phone_files_copy", object("path", path("private.bin"), "destination", path("copy.bin"))).getString("code"));
        assertArrayEquals(new byte[]{0, (byte)255, 1}, Files.readAllBytes(new File(fixture, "private.bin").toPath())); assertFalse(new File(fixture, "copy.bin").exists());
    }

    @Test public void binaryImportPreservesInvalidUtf8AndBindsActualDeviceSourceWithoutOwnerOrUploadClaims() throws Exception {
        grant(); byte[] original = {(byte)0xff, (byte)0xc3, 0x28, 0, 10, (byte)0xfe}; binary("raw.data", original);
        JSONObject result = imported("raw.data", "raw", true), asset = result.getJSONObject("asset");
        assertArrayEquals(original, exportedBytes(asset)); assertEquals(hash(original), asset.getString("sha256")); assertEquals(original.length, asset.getLong("size"));
        assertEquals("tool_result", asset.getString("kind")); assertEquals("device_observation", asset.getString("sourceKind")); assertFalse(asset.getBoolean("ownerUtterance"));
        assertTrue(result.getBoolean("uploadRequested")); assertFalse(result.getBoolean("binaryUploaded")); assertFalse(result.getBoolean("fileReadingConfirmed"));
        JSONObject reference = result.getJSONArray("sourceRefs").getJSONObject(0), event = store.getDeviceEvent(reference.getLong("id"));
        assertEquals("device_event", reference.getString("type")); assertEquals(event.getString("content_hash"), reference.getString("hash"));
        assertEquals(PhoneFileTransferTools.SHARED_SOURCE, event.getString("source")); assertTrue(tools.canReadStoredEvent(event));
    }

    @Test public void stableRequestReplayDoesNotRereadAChangedExternalFileAndConflictsNeverImportAnotherPath() throws Exception {
        grant(); binary("one.bin", new byte[]{1, 0, 2}); binary("two.bin", new byte[]{9}); JSONObject first = imported("one.bin", "stable", false);
        binary("one.bin", new byte[]{8, 8, 8}); JSONObject repeated = tools.execute("phone_files_import", object("path", path("one.bin"), "requestId", "stable"));
        assertEquals(first.getJSONObject("asset").getString("sha256"), repeated.getJSONObject("asset").getString("sha256")); assertArrayEquals(new byte[]{1, 0, 2}, exportedBytes(repeated.getJSONObject("asset")));
        assertEquals("request_id_conflict", tools.execute("phone_files_import", object("path", path("two.bin"), "requestId", "stable")).getString("code"));
    }

    @Test public void excludedCanonicalSourceImmediatelyBlocksReadExportAndArchivedVisibility() throws Exception {
        grant(); binary("excluded.pdf", new byte[]{37, 80, 68, 70, 0}); JSONObject imported = imported("excluded.pdf", "excluded", true);
        JSONObject ref = imported.getJSONArray("sourceRefs").getJSONObject(0);
        store.getPrivacyLedger().excludeSource("device_event", ref.getLong("id"), ref.getString("hash"), SourcePrivacyLedger.RETRIEVAL_EXCLUDE, "synthetic exclusion");
        assertEquals("memory_source_excluded_or_missing", tools.execute("phone_files_transfer_read", object("requestId", "excluded")).getString("code"));
        assertFalse(tools.canReadArchivedResult("phone_files_import", imported));
        JSONObject export = tools.execute("phone_files_export", object("requestId", "excluded", "assetId", imported.getJSONObject("asset").getString("id"), "path", path("must-not-exist.pdf")));
        assertFalse(export.getBoolean("ok")); assertFalse(new File(fixture, "must-not-exist.pdf").exists());
    }

    @Test public void originTamperingCannotTurnAnotherImmutableAssetIntoTheImportReceipt() throws Exception {
        grant(); binary("one.bin", new byte[]{1}); binary("other.bin", new byte[]{2}); imported("one.bin", "first", true); JSONObject other = imported("other.bin", "other", true);
        String receiptId = "phone-file-transfers/receipts/" + PhoneEventJournal.hash("first") + ".json";
        JSONObject receipt = new JSONObject(files.readText(receiptId, 32_000)); receipt.put("asset", other.getJSONObject("asset")); files.writeFile(receiptId, receipt.toString());
        assertEquals("memory_source_excluded_or_missing", tools.execute("phone_files_transfer_read", object("requestId", "first")).getString("code"));
    }

    @Test public void grantRevocationAndRestorePauseBlockHistoricalReadsAndFutureWrites() throws Exception {
        grant(); binary("past.bin", new byte[]{3}); JSONObject prior = imported("past.bin", "past", false);
        Shadows.shadowOf((Application)context).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE);
        assertEquals("permission_missing", tools.execute("phone_files_transfer_read", object("requestId", "past")).getString("code")); assertFalse(tools.canReadArchivedResult("phone_files_import", prior));
        grant(); tools.pauseForBackupRestore(); assertEquals("restore_in_progress", tools.execute("phone_files_delete", object("path", path("past.bin"))).getString("code"));
        assertEquals("restore_in_progress", tools.execute("phone_files_transfer_read", object("requestId", "past")).getString("code"));
        tools.afterBackupRestore(); assertTrue(tools.execute("phone_files_transfer_read", object("requestId", "past")).getBoolean("ok"));
    }

    @Test public void copyMoveAndExportPreserveExactBytesAndDefaultDoesNotOverwrite() throws Exception {
        grant(); byte[] bytes = {0, 1, (byte)128, (byte)255}; binary("in.bin", bytes); binary("existing.bin", new byte[]{7});
        assertEquals("destination_exists", tools.execute("phone_files_copy", object("path", path("in.bin"), "destination", path("existing.bin"))).getString("code"));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(new File(fixture, "existing.bin").toPath()));
        JSONObject copy = tools.execute("phone_files_copy", object("path", path("in.bin"), "destination", path("nested/copy.bin"))); assertTrue(copy.toString(), copy.getBoolean("completionConfirmed")); assertEquals(hash(bytes), copy.getString("sha256"));
        JSONObject move = tools.execute("phone_files_move", object("path", path("nested/copy.bin"), "destination", path("moved.bin"))); assertTrue(move.toString(), move.getBoolean("completionConfirmed")); assertFalse(new File(fixture, "nested/copy.bin").exists());
        JSONObject asset = imported("in.bin", "export", false).getJSONObject("asset"); JSONObject export = tools.execute("phone_files_export", object("requestId", "export", "assetId", asset.getString("id"), "path", path("exports/out.bin")));
        assertTrue(export.toString(), export.getBoolean("exported")); assertArrayEquals(bytes, Files.readAllBytes(new File(fixture, "exports/out.bin").toPath()));
        assertEquals("destination_exists", tools.execute("phone_files_export", object("requestId", "export", "assetId", asset.getString("id"), "path", path("exports/out.bin"))).getString("code"));
    }

    @Test public void directoriesAndDeletionHaveActualNonrecursiveOutcomes() throws Exception {
        grant(); JSONObject mkdir = tools.execute("phone_files_mkdir", object("path", path("a/b"), "parents", true)); assertTrue(mkdir.toString(), mkdir.getBoolean("directoryCreated"));
        assertTrue(tools.execute("phone_files_mkdir", object("path", path("a/b"))).getBoolean("alreadyExisted"));
        binary("keep.bin", new byte[]{7}); assertEquals("directory_not_empty", tools.execute("phone_files_delete", object("path", prefix)).getString("code"));
        assertTrue(new File(fixture, "keep.bin").exists()); assertTrue(tools.execute("phone_files_delete", object("path", path("keep.bin"))).getBoolean("deleted"));
        assertTrue(tools.execute("phone_files_delete", object("path", path("a/b"))).getBoolean("deleted"));
        assertEquals("not_a_file", tools.execute("phone_files_copy", object("path", path("a"), "destination", path("copied-directory"))).getString("code"));
    }

    @Test public void traversalAbsolutePathsSymlinksAndUnknownOptionsHaveNoBinaryEffects() throws Exception {
        grant(); binary("source.bin", new byte[]{1});
        for (String path : Arrays.asList("../escape", "/tmp/escape", "a//b", "a/../b", "a\\b", "file://outside"))
            assertEquals(path, "path_access_denied", tools.execute("phone_files_import", object("path", path, "requestId", "bad")).getString("code"));
        File outside = new File(context.getFilesDir(), "synthetic-outside.bin"); Files.write(outside.toPath(), new byte[]{9}); Files.createSymbolicLink(new File(fixture, "link.bin").toPath(), outside.toPath());
        assertEquals("path_access_denied", tools.execute("phone_files_import", object("path", path("link.bin"), "requestId", "link")).getString("code"));
        assertEquals("path_access_denied", tools.execute("phone_files_delete", object("path", path("link.bin"))).getString("code")); assertArrayEquals(new byte[]{9}, Files.readAllBytes(outside.toPath()));
        assertEquals("invalid_arguments", tools.execute("phone_files_delete", object("path", path("source.bin"), "recursive", true)).getString("code"));
    }

    @Test public void oversizedAndEmptyImportsAreExplicitAndDoNotPretendAnAttachmentWasUploaded() throws Exception {
        grant(); File large = new File(fixture, "large.bin"); try (RandomAccessFile output = new RandomAccessFile(large, "rw")) { output.setLength(InternalFiles.MAX_ATTACHMENT_BYTES + 1); }
        assertEquals("file_too_large", tools.execute("phone_files_import", object("path", path("large.bin"), "requestId", "large", "uploadRequested", true)).getString("code"));
        binary("empty.bin", new byte[0]); JSONObject empty = imported("empty.bin", "empty", true); assertFalse(empty.getBoolean("uploadRequested")); assertEquals(0, empty.getJSONObject("asset").getLong("size")); assertFalse(empty.getBoolean("binaryUploaded"));
    }

    @Test public void validMediaStoreUriImportsProviderBytesAndOtherAuthoritiesKindsAndFileUrisAreRejected() throws Exception {
        grant(); byte[] bytes = {5, 0, (byte)255, 9}; File media = new File(context.getCacheDir(), "synthetic-media.bin"); Files.write(media.toPath(), bytes);
        SyntheticMediaProvider provider = new SyntheticMediaProvider(media); android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo(); info.authority = "media"; provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal("media", provider);
        JSONObject initial = tools.execute("phone_media_import", object("uri", "content://media/external/video/media/11", "kind", "video", "requestId", "video", "uploadRequested", true));
        assertTrue(initial.toString(), initial.getBoolean("ok")); journal.ingestPendingForTests(); JSONObject result = tools.execute("phone_files_transfer_read", object("requestId", "video"));
        assertArrayEquals(bytes, exportedBytes(result.getJSONObject("asset"))); assertEquals("video/mp4", result.getJSONObject("asset").getString("mime")); assertEquals(PhoneFileTransferTools.MEDIA_SOURCE, result.getString("source"));
        for (String uri : Arrays.asList("file:///private.bin", "content://other/external/video/media/11", "content://media/external/images/media/11", "content://media/external/video/media/0", "content://media/external/video/media/11?redirect=1", "content://media/external/video/media/%31%31"))
            assertEquals(uri, "invalid_arguments", tools.execute("phone_media_import", object("uri", uri, "kind", "video", "requestId", "invalid-uri")).getString("code"));
    }

    @Test public void originalsAndNativeReceiptsAreIncludedInBackupButDisposableImportStagingIsAbsent() throws Exception {
        grant(); binary("backup.bin", new byte[]{8, 0, 7}); JSONObject result = imported("backup.bin", "backup", false);
        ByteArrayOutputStream backup = new ByteArrayOutputStream(); files.exportBackup(backup, store); List<String> entries = new ArrayList<>();
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(backup.toByteArray()))) { ZipEntry entry; while ((entry = archive.getNextEntry()) != null) entries.add(entry.getName()); }
        assertTrue(entries.toString(), entries.stream().anyMatch(name -> name.contains(result.optJSONObject("asset").optString("id"))));
        assertTrue(entries.toString(), entries.stream().anyMatch(name -> name.contains("phone-file-transfers/receipts/"))); assertFalse(entries.stream().anyMatch(name -> name.contains("phone-file-transfers/staging/") && !name.endsWith("/")));
    }

    private static String hash(byte[] bytes) throws Exception { StringBuilder text = new StringBuilder(); for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format(Locale.ROOT, "%02x", value & 255)); return text.toString(); }
    private static void delete(File file) throws Exception { if (file == null) return; if (Files.isSymbolicLink(file.toPath())) { Files.delete(file.toPath()); return; } if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); } Files.deleteIfExists(file.toPath()); }
    @Test public void privateWorkingBinaryAttachesWithoutExternalStorageGrantAndExportsWithActualWriteGrant() throws Exception {
        byte[] bytes = {0, (byte)255, (byte)128, 9}; files.writeFile("downloads/synthetic.zip", new ByteArrayInputStream(bytes));
        JSONObject attach = tools.execute("phone_files_attach", object("fileId", "downloads/synthetic.zip"));
        assertTrue(attach.toString(), attach.getBoolean("ok")); assertTrue(attach.getBoolean("uploadRequested")); assertFalse(attach.getBoolean("binaryUploaded"));
        assertEquals("ordinary_working_file", attach.getString("sourceScope")); assertEquals("file_observation", attach.getJSONObject("asset").getString("sourceKind"));
        assertArrayEquals(bytes, exportedBytes(attach.getJSONObject("asset"))); assertTrue(tools.canReadArchivedResult("phone_files_attach", attach));
        assertEquals("permission_missing", tools.execute("phone_files_export", object("assetId", "downloads/synthetic.zip", "path", path("ordinary.zip"))).getString("code"));
        grant(); JSONObject export = tools.execute("phone_files_export", object("assetId", "downloads/synthetic.zip", "path", path("ordinary.zip")));
        assertTrue(export.toString(), export.getBoolean("exported")); assertEquals("ordinary_working_file", export.getString("sourceScope")); assertArrayEquals(bytes, Files.readAllBytes(new File(fixture, "ordinary.zip").toPath()));
    }

    @Test public void aRecordedVoiceAttachmentRequiresItsNativeReceiptHashAndCurrentMicAuthority() throws Exception {
        byte[] bytes = {4, 0, (byte)255, 8}; String id = UUID.randomUUID().toString(), fileId = "captures/voice/" + id + ".m4a";
        files.writeFile(fileId, new ByteArrayInputStream(bytes)); JSONObject event = store.appendDeviceEvent("capture_result", "android.media_recorder", object("requestId", id, "fileId", fileId, "contentHash", hash(bytes), "size", bytes.length, "mime", "audio/mp4", "completionConfirmed", true, "fileValid", true), System.currentTimeMillis(), "voice-request:" + id);
        assertEquals("source_proof_required", tools.execute("phone_files_attach", object("fileId", fileId)).getString("code"));
        Shadows.shadowOf((Application)context).grantPermissions(Manifest.permission.RECORD_AUDIO);
        JSONObject attach = tools.execute("phone_files_attach", object("fileId", fileId, "proofRequestId", id));
        assertTrue(attach.toString(), attach.getBoolean("ok")); assertTrue(attach.getBoolean("uploadRequested")); assertEquals("native_capture_bound_file", attach.getString("sourceScope"));
        assertEquals(event.getLong("id"), attach.getJSONArray("sourceRefs").getJSONObject(0).getLong("id")); assertArrayEquals(bytes, exportedBytes(attach.getJSONObject("asset")));
        assertFalse(attach.getJSONObject("asset").getBoolean("ownerUtterance"));
        Shadows.shadowOf((Application)context).denyPermissions(Manifest.permission.RECORD_AUDIO);
        assertFalse(tools.canReadArchivedResult("phone_files_attach", attach)); assertFalse(tools.execute("phone_files_attach", object("fileId", fileId, "proofRequestId", id)).getBoolean("ok"));
    }

    @Test public void excludedOrMutatedVoiceBytesCannotBeForwardedAsTheRecordedOriginal() throws Exception {
        Shadows.shadowOf((Application)context).grantPermissions(Manifest.permission.RECORD_AUDIO); byte[] bytes = {1, 2}; String id = UUID.randomUUID().toString(), fileId = "captures/voice/" + id + ".m4a";
        files.writeFile(fileId, new ByteArrayInputStream(bytes)); JSONObject event = store.appendDeviceEvent("capture_result", "android.media_recorder", object("requestId", id, "fileId", fileId, "contentHash", hash(bytes), "size", bytes.length, "mime", "audio/mp4", "completionConfirmed", true), System.currentTimeMillis(), "voice-request:" + id);
        files.writeFile(fileId, new ByteArrayInputStream(new byte[]{3, 4}));
        assertEquals("integrity_failed", tools.execute("phone_files_attach", object("fileId", fileId, "proofRequestId", id)).getString("code"));
        files.writeFile(fileId, new ByteArrayInputStream(bytes)); store.getPrivacyLedger().excludeSource("device_event", event.getLong("id"), event.getString("content_hash"), SourcePrivacyLedger.RETRIEVAL_EXCLUDE, "synthetic voice exclusion");
        assertEquals("memory_source_excluded_or_missing", tools.execute("phone_files_attach", object("fileId", fileId, "proofRequestId", id)).getString("code"));
    }

    @Test public void messageBoundOriginalNeedsMatchingStoredMetadataHashAndLiveSourceVisibility() throws Exception {
        grant(); JSONObject working = files.writeFile("working/owner.data", new ByteArrayInputStream(new byte[]{0, 8, (byte)255}));
        JSONObject original = files.freezeAttachments(new JSONArray().put(working)).getJSONObject(0); JSONObject message = store.addMessage("user", "Synthetic attachment owner message", null);
        store.putSetting("assetCoverage:" + message.getLong("id"), object("assets", new JSONArray().put(original)).toString());
        JSONObject proof = object("assetId", original.getString("id"), "path", path("owner-export.data"), "sourceMessageId", message.getLong("id"), "sourceHash", message.getString("content_hash"));
        assertEquals("source_proof_required", tools.execute("phone_files_export", object("assetId", original.getString("id"), "path", path("unproved.data"))).getString("code"));
        JSONObject export = tools.execute("phone_files_export", proof); assertTrue(export.toString(), export.getBoolean("exported")); assertEquals("message_bound_original", export.getString("sourceScope"));
        JSONObject attached = tools.execute("phone_files_attach", object("fileId", original.getString("id"), "sourceMessageId", message.getLong("id"), "sourceHash", message.getString("content_hash"))); assertTrue(attached.toString(), attached.getBoolean("uploadRequested"));
        store.getPrivacyLedger().excludeSource("message", message.getLong("id"), message.getString("content_hash"), SourcePrivacyLedger.RETRIEVAL_EXCLUDE, "synthetic owner exclusion");
        assertEquals("memory_source_excluded_or_missing", tools.execute("phone_files_attach", object("fileId", original.getString("id"), "sourceMessageId", message.getLong("id"), "sourceHash", message.getString("content_hash"))).getString("code")); assertFalse(tools.canReadArchivedResult("phone_files_attach", attached));
    }

    @Test public void privateToolReceiptsCannotUseTheOrdinaryWorkingFileExportOrAttachmentShortcut() throws Exception {
        grant(); files.writeFile(".tool-results/private.json", "synthetic excluded tool output");
        assertEquals("source_proof_required", tools.execute("phone_files_attach", object("fileId", ".tool-results/private.json")).getString("code"));
        assertEquals("source_proof_required", tools.execute("phone_files_export", object("assetId", ".tool-results/private.json", "path", path("must-not-export.json"))).getString("code")); assertFalse(new File(fixture, "must-not-export.json").exists());
    }

    @Test public void attachmentPreservesStoredDownloadMimeAndNameWithoutInheritingAnyAuthorshipClaim() throws Exception {
        byte[] bytes = {37, 80, 68, 70, 0, (byte)255}; files.writeFile("downloads/opaque.bin", new ByteArrayInputStream(bytes));
        store.putSetting("fileMeta:downloads/opaque.bin", object("mime", "application/pdf", "displayName", "generated-report.pdf", "ownerUtterance", true).toString());
        JSONObject attached = tools.execute("phone_files_attach", object("fileId", "downloads/opaque.bin")), asset = attached.getJSONObject("asset");
        assertTrue(attached.toString(), attached.getBoolean("uploadRequested")); assertEquals("application/pdf", asset.getString("mime"));
        assertEquals("generated-report.pdf", asset.getString("sourceName")); assertFalse(asset.getBoolean("ownerUtterance")); assertArrayEquals(bytes, exportedBytes(asset));
    }

    @Test @Config(sdk = 34) public void selectedMediaGrantIsRevalidatedForTheExactHistoricalUri() throws Exception {
        Shadows.shadowOf((Application)context).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        Shadows.shadowOf((Application)context).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        File media = new File(context.getCacheDir(), "selected-synthetic.png"); Files.write(media.toPath(), new byte[]{0, 9, (byte)255});
        SyntheticMediaProvider provider = new SyntheticMediaProvider(media); provider.mime = "image/png";
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo(); info.authority = "media"; provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal("media", provider);
        // Robolectric's registered provider shares this app's UID. A real system
        // MediaStore provider does not thereby grant every item to our app.
        Context partialContext = new android.content.ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public int checkUriPermission(Uri uri, int pid, int uid, int flags) { return android.content.pm.PackageManager.PERMISSION_DENIED; }
        };
        tools = new PhoneFileTransferTools(partialContext, files, journal);
        JSONObject imported = tools.execute("phone_media_import", object("uri", "content://media/external/images/media/11", "kind", "images", "requestId", "selected", "uploadRequested", true));
        assertTrue(imported.toString(), imported.getBoolean("ok")); journal.ingestPendingForTests();
        JSONObject available = tools.execute("phone_files_transfer_read", object("requestId", "selected")); assertTrue(available.toString(), available.getBoolean("ok"));
        provider.rejectRead = true;
        assertEquals("permission_missing", tools.execute("phone_files_transfer_read", object("requestId", "selected")).getString("code"));
        assertFalse(tools.canReadArchivedResult("phone_media_import", available));
    }

    private static final class SyntheticMediaProvider extends ContentProvider {
        boolean rejectRead; String mime = "video/mp4";
        private final File file; SyntheticMediaProvider(File file) { this.file = file; }
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return mime; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}); cursor.addRow(new Object[]{"synthetic.mp4", file.length()}); return cursor; }
        @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException { if (rejectRead) throw new SecurityException("Synthetic item is no longer selected"); return new AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length()); }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
