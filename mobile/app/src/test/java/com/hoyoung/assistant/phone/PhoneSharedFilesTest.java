package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.os.Environment;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class PhoneSharedFilesTest {
    private Context context;
    private PhoneTools tools;
    private File root;
    private File ownDirectory;
    private String ownPath;

    @Before public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        tools = new PhoneTools(context);
        root = Environment.getExternalStorageDirectory().getCanonicalFile();
        ownPath = "hoyoung-test-" + UUID.randomUUID(); ownDirectory = new File(root, ownPath);
        assertTrue(ownDirectory.mkdirs() || ownDirectory.isDirectory());
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }
    @After public void cleanUp() throws Exception { if (ownDirectory != null) deleteOwnTree(ownDirectory); }
    private void grantStorage() { Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE); }

    @Test public void missingPermissionsPreventReadAndWriteWithoutTouchingTheFile() throws Exception {
        String path = ownPath + "/keep.txt";
        Files.write(new File(root, path).toPath(), "before".getBytes(StandardCharsets.UTF_8));
        assertEquals("permission_missing", tools.execute("phone_files_read", new JSONObject().put("path", path)).getString("code"));
        assertEquals("permission_missing", tools.execute("phone_files_write", new JSONObject().put("path", path).put("content", "after")).getString("code"));
        assertEquals("before", new String(Files.readAllBytes(new File(root, path).toPath()), StandardCharsets.UTF_8));
    }

    @Test public void grantedStorageSupportsAtomicUnicodeWriteBoundedReadAndSingleDirectoryListing() throws Exception {
        grantStorage(); String path = ownPath + "/project/hello.txt";
        JSONObject written = tools.execute("phone_files_write", new JSONObject().put("path", path).put("content", "안녕 🌙 world"));
        assertTrue(written.toString(), written.getBoolean("ok")); assertTrue(written.getBoolean("atomic"));
        assertEquals("안녕 🌙 world", tools.execute("phone_files_read", new JSONObject().put("path", path)).getString("text"));
        JSONObject bounded = tools.execute("phone_files_read", new JSONObject().put("path", path).put("maxChars", 2));
        assertEquals("안녕", bounded.getString("text")); assertTrue(bounded.getBoolean("truncated"));
        JSONObject listed = tools.execute("phone_files_list", new JSONObject().put("path", ownPath).put("limit", 1));
        assertTrue(listed.toString(), listed.getBoolean("ok")); assertFalse(listed.getBoolean("recursive"));
        assertEquals(1, listed.getJSONArray("entries").length()); assertEquals("directory", listed.getJSONArray("entries").getJSONObject(0).getString("type"));
    }

    @Test public void traversalAbsoluteAndProtectedPathsCannotBeWritten() throws Exception {
        grantStorage();
        for (String path : Arrays.asList("../outside.txt", "/tmp/host.txt", "nested/../../outside.txt", "a\\..\\host.txt", "C:/host.txt", "Android/data/other.app/private.txt", "Android/obb/other.app/file.txt", "android/DATA/other.app/file.txt")) {
            JSONObject response = tools.execute("phone_files_write", new JSONObject().put("path", path).put("content", "blocked"));
            assertFalse(path, response.getBoolean("ok")); assertEquals(path, "path_access_denied", response.getString("code"));
        }
        assertEquals(0, ownDirectory.listFiles().length);
    }

    @Test public void symlinkEscapeCannotReadOrModifyItsTarget() throws Exception {
        grantStorage(); File privateFile = new File(context.getFilesDir(), "phone-shared-outside-" + UUID.randomUUID());
        Files.write(privateFile.toPath(), "private".getBytes(StandardCharsets.UTF_8));
        try {
            Files.createSymbolicLink(new File(ownDirectory, "link.txt").toPath(), privateFile.toPath());
            assertEquals("path_access_denied", tools.execute("phone_files_read", new JSONObject().put("path", ownPath + "/link.txt")).getString("code"));
            assertEquals("path_access_denied", tools.execute("phone_files_write", new JSONObject().put("path", ownPath + "/link.txt").put("content", "changed")).getString("code"));
            assertEquals("private", new String(Files.readAllBytes(privateFile.toPath()), StandardCharsets.UTF_8));
        } finally { privateFile.delete(); }
    }

    @Test public void malformedUtf8AndOversizedFilesAreReportedWithoutDecodingReplacementText() throws Exception {
        grantStorage(); File invalid = new File(ownDirectory, "invalid.txt"); Files.write(invalid.toPath(), new byte[]{(byte) 0xC3, (byte) 0x28});
        assertEquals("invalid_utf8", tools.execute("phone_files_read", new JSONObject().put("path", ownPath + "/invalid.txt")).getString("code"));
        File oversized = new File(ownDirectory, "oversized.txt"); try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(oversized, "rw")) { output.setLength(1024 * 1024 + 1); }
        assertEquals("file_too_large", tools.execute("phone_files_read", new JSONObject().put("path", ownPath + "/oversized.txt")).getString("code"));
    }

    @Test public void invalidTypesBoundsAndUnknownArgumentsDoNotWrite() throws Exception {
        grantStorage();
        assertEquals("invalid_arguments", tools.execute("phone_files_write", new JSONObject().put("path", ownPath + "/no.txt").put("content", 123)).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_files_read", new JSONObject().put("path", "anything").put("maxChars", 50001)).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_files_list", new JSONObject().put("limit", 1.5)).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_files_list", new JSONObject().put("limit", 101)).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_files_write", new JSONObject().put("path", ownPath + "/no.txt").put("content", "x").put("delete", true)).getString("code"));
        char[] huge = new char[100001]; Arrays.fill(huge, 'x');
        assertEquals("invalid_arguments", tools.execute("phone_files_write", new JSONObject().put("path", ownPath + "/no.txt").put("content", new String(huge))).getString("code"));
        assertFalse(new File(ownDirectory, "no.txt").exists());
    }

    @Test public void permissionRevocationImmediatelyPreventsSubsequentWrite() throws Exception {
        grantStorage(); String path = ownPath + "/revoked.txt";
        assertTrue(tools.execute("phone_files_write", new JSONObject().put("path", path).put("content", "before")).getBoolean("ok"));
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertEquals("permission_missing", tools.execute("phone_files_write", new JSONObject().put("path", path).put("content", "after")).getString("code"));
        assertEquals("before", tools.execute("phone_files_read", new JSONObject().put("path", path)).getString("text"));
    }

    private static void deleteOwnTree(File file) throws Exception {
        if (Files.isSymbolicLink(file.toPath())) { Files.delete(file.toPath()); return; }
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteOwnTree(child); }
        if (file.exists()) assertTrue(file.delete());
    }
}
