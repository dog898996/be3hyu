package com.hoyoung.assistant.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.webkit.MimeTypeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Immutable binary evidence. Its semantic interpretation is a separate, versioned claim. */
final class AttachmentOriginals {
    static final String PREFIX = ".attachment-originals";
    private static final String SIDECAR_SUFFIX = ".metadata.json";
    private final File projects;
    private final File root;

    AttachmentOriginals(File projects) { this.projects = projects; root = new File(projects, PREFIX); }

    static boolean reserved(String id) { return id != null && (id.equals(PREFIX) || id.startsWith(PREFIX + "/")); }
    static boolean sidecar(File file) { return file.getName().endsWith(SIDECAR_SUFFIX); }

    JSONObject save(InputStream source, JSONObject supplied) throws IOException {
        if (source == null) throw new IOException("Attachment input is missing");
        ensureRoot();
        String name = sourceName(supplied), mime = mime(supplied, name);
        File partial = new File(root, ".partial-" + UUID.randomUUID());
        MessageDigest digest = OriginalTextFiles.digest(); long size = 0;
        try {
            try (FileOutputStream file = new FileOutputStream(partial);
                 DigestOutputStream output = new DigestOutputStream(new BufferedOutputStream(file), digest)) {
                byte[] buffer = new byte[16_384]; int n;
                while ((n = source.read(buffer)) != -1) {
                    if (n == 0) continue;
                    if (size > InternalFiles.MAX_FILE_BYTES - n) throw new IOException("Attachment exceeds 64 MiB");
                    output.write(buffer, 0, n); size += n;
                }
                output.flush(); file.getFD().sync();
            }
            String sha = OriginalTextFiles.hex(digest.digest());
            assertClaims(supplied, sha, size);
            String filename = sha + "." + extension(name, mime);
            String id = PREFIX + "/" + filename;
            File destination = payload(id);
            if (destination.exists()) verify(id, sha, size);
            else {
                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
                syncDirectory(root);
            }
            JSONObject metadata = metadata(destination, id, name, mime, sha, size);
            String kind = supplied == null ? "owner_attachment" : supplied.optString("kind", "owner_attachment");
            if (!kind.equals("owner_attachment") && !kind.equals("tool_result")) throw new IOException("Unknown immutable asset kind");
            put(metadata, "kind", kind);
            put(metadata, "mimeSource", supplied == null ? "filename_inferred" : supplied.optString("mimeSource", supplied.has("mime") || supplied.has("mimeType") ? "caller_metadata" : "filename_inferred"));
            for (String key : new String[]{"rootRequestId", "actionId", "totalChars"}) if (supplied != null && supplied.has(key)) put(metadata, key, supplied.opt(key));
            String sourceId = supplied == null ? "" : supplied.optString("id", supplied.optString("fileId", ""));
            if (!sourceId.isEmpty() && !reserved(sourceId)) put(metadata, "sourceFileId", sourceId);
            writeSidecarIfMissing(destination, metadata);
            // Check the published inode, including the reuse path. No metadata claim substitutes for bytes.
            verify(id, sha, size);
            describe(id, false); // Validate any previously published sidecar too.
            put(metadata, "integrity", "sha256_verified");
            return metadata;
        } finally { if (partial.exists() && !partial.delete()) throw new IOException("Incomplete attachment staging could not be removed"); }
    }

    JSONObject describe(String id, boolean verifyBytes) throws IOException {
        File file = payload(id);
        if (!file.isFile() || file.length() > InternalFiles.MAX_FILE_BYTES) throw new IOException("Attachment original is missing or oversized");
        String sha = hashFromId(id);
        JSONObject saved = readSidecar(file);
        if (saved != null) {
            if (!id.equals(saved.optString("id")) || !id.equals(saved.optString("originalAssetId"))) throw new IOException("Attachment metadata identity changed");
            assertClaims(saved, sha, file.length());
        }
        String name = saved == null ? file.getName() : sourceName(saved);
        String mime = saved == null ? mime(null, name) : mime(saved, name);
        if (verifyBytes) verify(id, sha, file.length());
        JSONObject result = metadata(file, id, name, mime, sha, file.length());
        if (saved != null) for (String key : new String[]{"sourceFileId", "kind", "mimeSource", "rootRequestId", "actionId", "totalChars"}) if (saved.has(key)) put(result, key, saved.opt(key));
        if (!result.has("kind")) put(result, "kind", "owner_attachment");
        if (!result.has("mimeSource")) put(result, "mimeSource", "stored_metadata");
        put(result, "integrity", verifyBytes ? "sha256_verified" : "not_checked_on_list");
        return result;
    }

    void verify(String id, String expectedHash, long expectedSize) throws IOException {
        File file = payload(id);
        if (!hashFromId(id).equals(expectedHash) || !file.isFile() || file.length() != expectedSize
                || expectedSize > InternalFiles.MAX_FILE_BYTES) throw new IOException("Attachment original identity or size changed");
        MessageDigest digest = OriginalTextFiles.digest();
        try (InputStream input = new DigestInputStream(new BufferedInputStream(new FileInputStream(file)), digest)) {
            byte[] buffer = new byte[16_384]; while (input.read(buffer) != -1) { }
        }
        if (!OriginalTextFiles.hex(digest.digest()).equals(expectedHash)) throw new IOException("Attachment original SHA-256 mismatch");
    }

    private File payload(String id) throws IOException {
        if (id == null || !id.startsWith(PREFIX + "/") || !id.substring(PREFIX.length() + 1).matches("[a-f0-9]{64}\\.[a-z0-9]{1,12}")) throw new IOException("Invalid immutable attachment id");
        if (Files.isSymbolicLink(root.toPath())) throw new IOException("Attachment storage must not be a symbolic link");
        File result = new File(projects, id);
        if (!result.getCanonicalPath().startsWith(projects.getCanonicalPath() + File.separator)
                || Files.isSymbolicLink(result.toPath())) throw new IOException("Attachment path escapes private storage");
        return result;
    }

    private static String hashFromId(String id) { return id.substring(PREFIX.length() + 1, PREFIX.length() + 65); }
    private void ensureRoot() throws IOException {
        boolean existed = root.isDirectory();
        if (Files.isSymbolicLink(root.toPath()) || (!existed && !root.mkdirs())) throw new IOException("Immutable attachment directory cannot be created");
        if (!existed) syncDirectory(projects);
    }

    private static JSONObject metadata(File file, String id, String name, String mime, String hash, long size) throws IOException {
        JSONObject result = new JSONObject();
        put(result, "id", id); put(result, "originalAssetId", id); put(result, "sha256", hash);
        put(result, "name", name); put(result, "displayName", name); put(result, "sourceName", name);
        put(result, "mime", mime); put(result, "mimeType", mime); put(result, "size", size);
        put(result, "path", file.getCanonicalPath()); put(result, "updatedAt", file.lastModified());
        put(result, "storage", "private-internal-files"); put(result, "immutable", true); put(result, "coverage", "raw_saved");
        return result;
    }

    private static String sourceName(JSONObject metadata) throws IOException {
        String name = metadata == null ? "attachment" : metadata.optString("sourceName", metadata.optString("displayName", metadata.optString("name", "attachment")));
        name = name.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) name = "attachment";
        if (name.length() > 1024) throw new IOException("Attachment source name is too long");
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) throw new IOException("Attachment source name contains control characters");
        return name;
    }
    private static String mime(JSONObject metadata, String name) throws IOException {
        String mime = metadata == null ? "" : metadata.optString("mime", metadata.optString("mimeType", ""));
        if (mime.isEmpty()) {
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1);
            String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
            if (guessed == null) guessed = java.net.URLConnection.guessContentTypeFromName(name);
            mime = guessed == null ? "application/octet-stream" : guessed;
        }
        if (mime.length() > 256 || !mime.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")) throw new IOException("Invalid attachment MIME metadata");
        return mime.toLowerCase(Locale.ROOT);
    }
    private static String extension(String name, String mime) {
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (extension == null) {
            switch (mime) {
                case "image/png": extension = "png"; break;
                case "image/jpeg": extension = "jpg"; break;
                case "image/webp": extension = "webp"; break;
                case "image/gif": extension = "gif"; break;
                case "application/pdf": extension = "pdf"; break;
                case "application/json": extension = "json"; break;
                case "text/plain": extension = "txt"; break;
                default: int dot = name.lastIndexOf('.'); extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
        }
        return extension.matches("[a-z0-9]{1,12}") ? extension : "bin";
    }
    private static void assertClaims(JSONObject supplied, String hash, long size) throws IOException {
        if (supplied == null) return;
        if (supplied.has("sha256") && !hash.equals(supplied.optString("sha256"))) throw new IOException("Attachment hash claim differs from bytes");
        if (supplied.has("size") && (! (supplied.opt("size") instanceof Number) || supplied.optLong("size", -1) != size)) throw new IOException("Attachment size claim differs from bytes");
    }
    private void writeSidecarIfMissing(File payload, JSONObject metadata) throws IOException {
        File sidecar = new File(root, payload.getName() + SIDECAR_SUFFIX);
        if (sidecar.exists()) { readSidecar(payload); return; }
        JSONObject safe;
        try { safe = new JSONObject(metadata.toString()); safe.remove("path"); }
        catch (JSONException impossible) { throw new IOException(impossible); }
        File partial = new File(root, ".partial-" + UUID.randomUUID());
        try {
            try (FileOutputStream output = new FileOutputStream(partial)) { output.write(safe.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
            Files.move(partial.toPath(), sidecar.toPath(), StandardCopyOption.ATOMIC_MOVE); syncDirectory(root);
        } finally { if (partial.exists()) partial.delete(); }
    }
    private JSONObject readSidecar(File payload) throws IOException {
        File sidecar = new File(root, payload.getName() + SIDECAR_SUFFIX);
        if (!sidecar.exists()) return null;
        if (!sidecar.isFile() || Files.isSymbolicLink(sidecar.toPath()) || sidecar.length() > 16_384) throw new IOException("Invalid attachment metadata file");
        byte[] bytes = Files.readAllBytes(sidecar.toPath());
        try { return new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
        catch (JSONException invalid) { throw new IOException("Corrupt attachment metadata", invalid); }
    }

    static void validateForRestore(File projects, File databaseFile) throws IOException {
        AttachmentOriginals originals = new AttachmentOriginals(projects);
        if (originals.root.exists()) {
            if (Files.isSymbolicLink(originals.root.toPath()) || !originals.root.isDirectory()) throw new IOException("Invalid attachment namespace");
            File[] entries = originals.root.listFiles(); if (entries == null) throw new IOException("Attachment archive cannot be read");
            for (File entry : entries) {
                if (sidecar(entry)) {
                    String name = entry.getName().substring(0, entry.getName().length() - SIDECAR_SUFFIX.length());
                    originals.describe(PREFIX + "/" + name, true);
                } else originals.describe(PREFIX + "/" + entry.getName(), true);
            }
        }
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(databaseFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor cursor = database.rawQuery("SELECT setting_key,value FROM settings WHERE setting_key LIKE 'attachmentMetadata:%' OR setting_key LIKE 'ownerMessageMetadata:%' OR setting_key LIKE 'fileMeta:%'", null)) {
            while (cursor.moveToNext()) {
                Object value;
                try {
                    String raw = cursor.getString(1);
                    value = raw.trim().startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
                } catch (JSONException invalid) { throw new IOException("Attachment source metadata is invalid", invalid); }
                verifyMetadataReferences(originals, value, 0);
            }
        }
    }
    private static void verifyMetadataReferences(AttachmentOriginals originals, Object value, int depth) throws IOException {
        if (depth > 32) throw new IOException("Attachment metadata nesting is too deep");
        if (value instanceof JSONArray) {
            JSONArray items = (JSONArray) value; for (int i = 0; i < items.length(); i++) verifyMetadataReferences(originals, items.opt(i), depth + 1);
        } else if (value instanceof JSONObject) {
            JSONObject item = (JSONObject) value;
            String id = item.optString("originalAssetId", item.optString("id", ""));
            if (reserved(id)) { JSONObject actual = originals.describe(id, true); assertClaims(item, actual.optString("sha256"), actual.optLong("size")); }
            java.util.Iterator<String> keys = item.keys(); while (keys.hasNext()) { Object next = item.opt(keys.next()); if (next instanceof JSONObject || next instanceof JSONArray) verifyMetadataReferences(originals, next, depth + 1); }
        }
    }
    private static void syncDirectory(File directory) throws IOException {
        // A file fsync alone does not commit a newly created directory entry.
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) { channel.force(true); }
    }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
}
