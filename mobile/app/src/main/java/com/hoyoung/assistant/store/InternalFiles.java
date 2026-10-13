package com.hoyoung.assistant.store;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Working files belong in private filesDir/projects, not external storage or cacheDir. */
public final class InternalFiles {
    public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    public static final long MAX_BACKUP_BYTES = StreamingBackup.MAX_BYTES;
    private static final long MAX_SNAPSHOT_BYTES = 16L * 1024 * 1024;
    private static final int MAX_BACKUP_ENTRIES = StreamingBackup.MAX_ENTRIES;
    private static final String RESTORE_JOURNAL = ".restore-transaction.json";
    private final Context context;
    private final File privateRoot;
    private final File projects;
    private final AttachmentOriginals attachmentOriginals;
    private final Object backupLock = new Object();
    private volatile BackupParticipant backupParticipant;
    public interface BackupParticipant { void capture(File destination) throws IOException; }
    public void setBackupParticipant(BackupParticipant participant) { backupParticipant = participant; }

    public InternalFiles(Context context) {
        this.context = context.getApplicationContext();
        this.privateRoot = this.context.getFilesDir();
        this.projects = new File(privateRoot, "projects");
        this.attachmentOriginals = new AttachmentOriginals(projects);
        try { recoverRestore(); }
        catch (IOException e) { throw new IllegalStateException("An interrupted backup restore could not be recovered", e); }
        ensureDirectory(projects);
        ensureDirectory(new File(privateRoot, "originals"));
        try { cleanupAbandonedStaging(); } catch (IOException unavailable) { throw new IllegalStateException("Staging recovery failed", unavailable); }
    }

    public synchronized JSONObject saveFromUri(Uri uri, String displayName) throws IOException {
        if (uri == null) throw new IllegalArgumentException("A source URI is required");
        if (displayName == null || displayName.trim().isEmpty()) {
            try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) displayName = cursor.getString(0);
            } catch (RuntimeException ignored) { /* A provider may omit display-name metadata. */ }
        }
        if (displayName == null || displayName.trim().isEmpty()) displayName = "attachment";
        JSONObject supplied = new JSONObject(); put(supplied, "displayName", displayName);
        String mimeType = null;
        try { mimeType = context.getContentResolver().getType(uri); }
        catch (RuntimeException unavailable) { /* A provider may omit MIME metadata. */ }
        if (mimeType != null) { put(supplied, "mimeType", mimeType); put(supplied, "mimeSource", "content_resolver"); }
        try (InputStream source = context.getContentResolver().openInputStream(uri)) {
            if (source == null) throw new IOException("The selected document cannot be opened");
            return attachmentOriginals.save(source, supplied);
        }
    }

    /** Freeze before linking an attachment to an owner message. Call from a background worker. */
    public synchronized JSONArray freezeAttachments(JSONArray attachments) throws IOException {
        JSONArray frozen = new JSONArray();
        if (attachments == null) return frozen;
        if (attachments.length() > 8) throw new IOException("A message supports at most eight attachments");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < attachments.length(); i++) {
            Object value = attachments.opt(i);
            JSONObject supplied = value instanceof JSONObject ? (JSONObject) value : new JSONObject();
            String id = value instanceof String ? (String) value : supplied.optString("originalAssetId", supplied.optString("id", supplied.optString("fileId", "")));
            if (supplied.has("originalAssetId") && supplied.has("id") && !supplied.optString("originalAssetId").equals(supplied.optString("id"))) throw new IOException("Attachment metadata has contradictory ids");
            if (id.isEmpty() || !seen.add(id)) throw new IOException("Missing or duplicate attachment id");
            JSONObject original;
            if (AttachmentOriginals.reserved(id)) {
                original = attachmentOriginals.describe(id, true);
                if (supplied.has("sha256") && !original.optString("sha256").equals(supplied.optString("sha256"))) throw new IOException("Attachment source hash changed");
                if (supplied.has("size") && supplied.optLong("size", -1) != original.optLong("size")) throw new IOException("Attachment source size changed");
                // Names are per-message provenance; retain the immutable record's binary identity.
                // Original identity and trusted import MIME/name come from the private sidecar.
                // Caller labels remain separate message annotations, not replacements for source metadata.
            } else {
                File source = resolve(projects, id);
                if (!source.isFile() || Files.isSymbolicLink(source.toPath())) throw new IOException("No such attachment source");
                JSONObject metadata;
                try { metadata = value instanceof JSONObject ? new JSONObject(supplied.toString()) : metadata(source); }
                catch (JSONException malformed) { throw new IOException("Invalid attachment metadata", malformed); }
                if (!metadata.has("id")) put(metadata, "id", id);
                try (InputStream input = new FileInputStream(source)) { original = attachmentOriginals.save(input, metadata); }
            }
            original.remove("path"); frozen.put(original);
        }
        return frozen;
    }

    /** Exact large action results use the same immutable payload archive as owner attachments. */
    public synchronized JSONObject archiveToolResult(String rootRequestId, String actionId, String text) throws IOException {
        if (text == null || text.length() > OriginalTextFiles.MAX_ORIGINAL_CHARS) throw new IOException("Tool result exceeds 10,000,000 UTF-16 characters");
        if (rootRequestId == null || rootRequestId.isEmpty() || rootRequestId.length() > 512
                || actionId == null || actionId.isEmpty() || actionId.length() > 512) throw new IOException("Tool result needs bounded request and action ids");
        JSONObject supplied = new JSONObject(); put(supplied, "sourceName", safeBasename(actionId) + ".json");
        put(supplied, "mime", "application/json"); put(supplied, "mimeSource", "tool_contract"); put(supplied, "kind", "tool_result");
        put(supplied, "rootRequestId", rootRequestId); put(supplied, "actionId", actionId); put(supplied, "totalChars", text.length());
        try (InputStream source = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
            JSONObject saved = attachmentOriginals.save(source, supplied); saved.remove("path"); return saved;
        }
    }

    /** A bounded UTF-8 text view. Binary identity is the SHA-256 of the exact saved bytes. */
    public synchronized JSONObject readTextRange(String fileId, long offsetChars, int maxChars) throws IOException {
        if (offsetChars < 0) throw new IOException("Text offset cannot be negative");
        int bound = Math.max(0, Math.min(maxChars, 1_000_000));
        File file = resolve(projects, fileId);
        if (!file.isFile() || file.length() > MAX_FILE_BYTES) throw new IOException("Working file is missing or oversized");
        String expectedHash = AttachmentOriginals.reserved(fileId) ? attachmentOriginals.describe(fileId, false).optString("sha256") : "";
        java.security.MessageDigest digest = OriginalTextFiles.digest();
        long total = 0; StringBuilder text = new StringBuilder(Math.min(bound, 8192)); char previous = 0;
        try (InputStreamReader reader = new InputStreamReader(new java.security.DigestInputStream(new FileInputStream(file), digest), StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))) {
            char[] chars = new char[8192]; int count;
            while ((count = reader.read(chars)) != -1) {
                for (int i = 0; i < count; i++, total++) {
                    char current = chars[i];
                    if (total == offsetChars && offsetChars > 0 && Character.isHighSurrogate(previous) && Character.isLowSurrogate(current)) throw new IOException("Text offset splits a UTF-16 pair");
                    if (total >= offsetChars && text.length() < bound) text.append(current);
                    previous = current;
                }
            }
        }
        String hash = OriginalTextFiles.hex(digest.digest());
        if (!expectedHash.isEmpty() && !hash.equals(expectedHash)) throw new IOException("Attachment original SHA-256 mismatch");
        long start = Math.min(offsetChars, total);
        if (text.length() > 0 && start + text.length() < total && Character.isHighSurrogate(text.charAt(text.length() - 1))) text.setLength(text.length() - 1);
        JSONObject range = new JSONObject(); put(range, "fileId", fileId); put(range, "text", text.toString());
        put(range, "offsetChars", start); put(range, "nextOffsetChars", start + text.length()); put(range, "totalChars", total);
        put(range, "complete", start + text.length() >= total); put(range, "contentHash", hash); put(range, "decodedTextView", true);
        return range;
    }

    public synchronized JSONArray listFiles() throws IOException {
        List<File> items = new ArrayList<>();
        collect(projects, items, 0);
        items.sort(Comparator.comparingLong(File::lastModified).reversed());
        JSONArray result = new JSONArray();
        for (File file : items) {
            String id = relativeId(file);
            if (AttachmentOriginals.reserved(id) && AttachmentOriginals.sidecar(file)) continue;
            result.put(metadata(file));
        }
        return result;
    }

    public synchronized JSONArray catalog() {
        JSONArray result = new JSONArray();
        result.put(tool("files.list", "List the owner's private saved working files.", new JSONObject(), new JSONArray()));
        JSONObject read = new JSONObject();
        put(read, "fileId", property("string", "Relative id from files.list; not a host path."));
        put(read, "offsetChars", property("integer", "UTF-16 offset returned by the previous read; default zero."));
        put(read, "maxChars", property("integer", "Maximum text characters: 0–24,000; default 20,000."));
        result.put(tool("files.read", "Read an exact UTF-8 range with hash, nextOffsetChars, totalChars and complete. Continue at nextOffsetChars when incomplete.", read, new JSONArray().put("fileId")));
        JSONObject write = new JSONObject();
        put(write, "relativePath", property("string", "Relative path within the private projects folder."));
        put(write, "content", property("string", "UTF-8 file content; writes are bounded and atomic."));
        result.put(tool("files.write", "Create or replace a private working file.", write, new JSONArray().put("relativePath").put("content")));
        return result;
    }

    public synchronized JSONObject execute(String name, JSONObject arguments) throws IOException {
        if (arguments == null) arguments = new JSONObject();
        JSONObject result = new JSONObject();
        switch (name == null ? "" : name) {
            case "files.list": put(result, "files", listFiles()); break;
            case "files.read":
                if (!(arguments.opt("fileId") instanceof String)) throw new IOException("fileId must be a string");
                String fileId = arguments.optString("fileId");
                long offset = strictInteger(arguments, "offsetChars", 0, 0, MAX_FILE_BYTES);
                int count = (int) strictInteger(arguments, "maxChars", 20_000, 0, 24_000);
                result = readTextRange(fileId, offset, count);
                break;
            case "files.write":
                if (!arguments.has("content") || arguments.isNull("content")) throw new IOException("File content is required");
                put(result, "file", writeFile(arguments.optString("relativePath", null), arguments.optString("content")));
                break;
            default: throw new IOException("Unknown private-files tool");
        }
        return result;
    }

    public synchronized String readText(String fileId, int maxChars) throws IOException {
        int limit = Math.max(0, Math.min(maxChars, 1_000_000));
        File file = resolve(projects, fileId);
        if (AttachmentOriginals.reserved(fileId)) attachmentOriginals.describe(fileId, true);
        if (!file.isFile()) throw new IOException("No such working file");
        if (file.length() > MAX_FILE_BYTES) throw new IOException("Working file exceeds the 64 MiB limit");
        StringBuilder text = new StringBuilder(Math.min(limit, 8192));
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            while (text.length() < limit) {
                int read = reader.read(buffer, 0, Math.min(buffer.length, limit - text.length()));
                if (read < 0) break;
                text.append(buffer, 0, read);
            }
        }
        return text.toString();
    }

    public synchronized JSONObject writeFile(String relativePath, String content) throws IOException {
        if (content == null) throw new IllegalArgumentException("File content is required");
        if (content.length() > MAX_FILE_BYTES) throw new IOException("Working file exceeds the 64 MiB limit");
        return writeFile(relativePath, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** The caller owns the input stream. A failed/oversized write leaves the old file intact. */
    public synchronized JSONObject writeFile(String relativePath, InputStream source) throws IOException {
        if (source == null) throw new IllegalArgumentException("File content is required");
        if (AttachmentOriginals.reserved(relativePath)) throw new IOException("Attachment originals are immutable; use a separate working path");
        File target = resolve(projects, relativePath);
        if (AttachmentOriginals.reserved(relativeId(target))) throw new IOException("Attachment originals cannot be replaced through an alias");
        ensureDirectory(target.getParentFile());
        // Resolve again after making parents, in case the tree changed outside this helper.
        target = resolve(projects, relativePath);
        File temporary = new File(target.getParentFile(), ".partial-" + UUID.randomUUID());
        try {
            try (FileOutputStream raw = new FileOutputStream(temporary); OutputStream output = new BufferedOutputStream(raw)) {
                copy(source, output, MAX_FILE_BYTES);
                output.flush();
                raw.getFD().sync();
            }
            atomicMove(temporary, target);
            return metadata(target);
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    /** Export to the user-selected Storage Access Framework destination. */
    public synchronized void exportFile(String fileId, OutputStream destination) throws IOException {
        if (destination == null) throw new IllegalArgumentException("A destination is required");
        File source = resolve(projects, fileId);
        if (AttachmentOriginals.reserved(fileId)) attachmentOriginals.describe(fileId, true);
        if (!source.isFile()) throw new IOException("No such working file");
        try (InputStream input = new BufferedInputStream(new FileInputStream(source))) {
            copy(input, destination, MAX_FILE_BYTES);
        }
        destination.flush();
    }

    /**
     * Snapshot originals, all memory revisions, requests/settings/events, and working files.
     * OutputStream ownership stays with the caller. Export this outside the app before uninstall.
     */
    public void exportBackup(OutputStream destination, HoyoungStore store) throws IOException {
        if (destination == null || store == null) throw new IllegalArgumentException("A destination and store are required");
        synchronized (backupLock) {
            File journalSnapshot = new File(privateRoot, ".backup-snapshot-" + UUID.randomUUID());
            try {
                BackupParticipant participant = backupParticipant;
                File deviceJournal = null;
                if (participant != null) {
                    deviceJournal = new File(journalSnapshot, "device-journal");
                    ensureDirectory(deviceJournal);
                    participant.capture(deviceJournal);
                }
                // The journal snapshot precedes the SQLite cut: duplication can be deduped,
                // but an observation committed/deleted between the cuts must not disappear.
                StreamingBackup.exportArchive(destination, store, privateRoot, deviceJournal);
                destination.flush();
            } finally { deleteTree(journalSnapshot); }
        }
    }

    public void importBackup(InputStream source, HoyoungStore store) throws IOException {
        if (source == null || store == null) throw new IllegalArgumentException("A backup and store are required");
        synchronized (backupLock) {
            synchronized (this) {
                recoverRestore(); cleanupAbandonedStaging();
                File staging = new File(privateRoot, ".restore-" + UUID.randomUUID());
                ensureDirectory(staging);
                String token = UUID.randomUUID().toString();
                File oldProjects = new File(privateRoot, ".previous-projects-" + UUID.randomUUID());
                File oldOriginals = new File(privateRoot, ".previous-originals-" + UUID.randomUUID());
                File oldDeviceJournal = new File(privateRoot, ".previous-device-journal-" + UUID.randomUUID());
                boolean journalWritten = false;
                try {
                    StreamingBackup.PreparedBackup prepared = StreamingBackup.prepare(source, store, staging);
                    store.validateOriginalFiles(prepared.databaseFile, staging);
                    AttachmentOriginals.validateForRestore(prepared.projectsDirectory, prepared.databaseFile);
                    File deviceJournal = new File(privateRoot, "device-journal");
                    ensureDirectory(deviceJournal);
                    File stagedDeviceJournal = new File(staging, "device-journal");
                    ensureDirectory(stagedDeviceJournal);
                    writeRestoreJournal(token, staging, oldProjects, oldOriginals, oldDeviceJournal);
                    journalWritten = true;
                    atomicMove(projects, oldProjects);
                    atomicMove(new File(privateRoot, "originals"), oldOriginals);
                    atomicMove(deviceJournal, oldDeviceJournal);
                    atomicMove(prepared.projectsDirectory, projects);
                    atomicMove(prepared.originalsDirectory, new File(privateRoot, "originals"));
                    atomicMove(stagedDeviceJournal, deviceJournal);
                    store.importStagedDatabase(prepared.databaseFile, token);
                    // Recovery uses the transaction token to finish cleanup after either a
                    // successful commit or a process death in the directory-swap window.
                    recoverRestore();
                } catch (RuntimeException invalid) {
                    throw new IOException("Backup restore failed", invalid);
                } finally {
                    if (journalWritten) recoverRestore();
                    deleteTree(staging);
                }
            }
        }
    }

    /** The DB commit token decides whether every namespace swap commits or rolls back. */
    private void recoverRestore() throws IOException {
        File journal = new File(privateRoot, RESTORE_JOURNAL);
        if (!journal.exists()) return;
        if (journal.length() > 8192) throw new IOException("Invalid restore journal");
        JSONObject record;
        try (InputStream input = new FileInputStream(journal); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            copy(input, bytes, 8192);
            try { record = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)); }
            catch (JSONException e) { throw new IOException("Invalid restore journal", e); }
        }
        String token = record.optString("token", "");
        try { UUID.fromString(token); } catch (IllegalArgumentException e) { throw new IOException("Invalid restore token", e); }
        File staging = journalDirectory(record.optString("staging", ""), ".restore-");
        JSONArray moves = record.optJSONArray("moves");
        if (moves == null) {
            moves = new JSONArray();
            JSONObject legacy = new JSONObject(); put(legacy, "current", "projects"); put(legacy, "previous", record.optString("previous")); moves.put(legacy);
        }
        boolean committed = false;
        File databaseFile = context.getDatabasePath(HoyoungStore.DATABASE_NAME);
        if (databaseFile.isFile()) {
            try (SQLiteDatabase database = SQLiteDatabase.openDatabase(databaseFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor cursor = database.rawQuery("SELECT value FROM settings WHERE setting_key = ?", new String[]{HoyoungStore.FILE_RESTORE_TOKEN_KEY})) {
                committed = cursor.moveToFirst() && token.equals(cursor.getString(0));
            } catch (RuntimeException e) { throw new IOException("Cannot inspect interrupted restore commit", e); }
        }
        for (int i = 0; i < moves.length(); i++) {
            JSONObject move = moves.optJSONObject(i); if (move == null) throw new IOException("Invalid namespace move");
            String namespace = move.optString("current");
            if (!(namespace.equals("projects") || namespace.equals("originals") || namespace.equals("device-journal"))) throw new IOException("Invalid restore namespace");
            File current = new File(privateRoot, namespace);
            File previous = journalDirectory(move.optString("previous"), ".previous-" + namespace + "-");
            if (committed) {
                if (!current.isDirectory()) throw new IOException("Committed restore is missing a namespace");
                deleteTree(previous);
            } else if (previous.exists()) {
                deleteTree(current); atomicMove(previous, current);
            } else if (!current.isDirectory()) throw new IOException("Original namespace is missing during rollback");
        }
        deleteTree(staging); removeRestoreJournal();
    }

    private File journalDirectory(String name, String prefix) throws IOException {
        if (!name.startsWith(prefix)) throw new IOException("Invalid restore directory");
        try { UUID.fromString(name.substring(prefix.length())); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid restore directory", e); }
        File directory = new File(privateRoot, name);
        if (!directory.getCanonicalPath().startsWith(privateRoot.getCanonicalPath() + File.separator)) throw new IOException("Unsafe restore directory");
        return directory;
    }

    private void writeRestoreJournal(String token, File staging, File... previous) throws IOException {
        JSONObject record = new JSONObject(); put(record, "token", token); put(record, "staging", staging.getName());
        JSONArray moves = new JSONArray();
        String[] namespaces = {"projects", "originals", "device-journal"};
        for (int i = 0; i < previous.length; i++) {
            JSONObject move = new JSONObject(); put(move, "current", namespaces[i]); put(move, "previous", previous[i].getName()); moves.put(move);
        }
        put(record, "moves", moves);
        File temporary = new File(privateRoot, RESTORE_JOURNAL + ".partial");
        try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(record.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
        atomicMove(temporary, new File(privateRoot, RESTORE_JOURNAL));
    }

    private void removeRestoreJournal() throws IOException {
        File journal = new File(privateRoot, RESTORE_JOURNAL);
        if (journal.exists() && !journal.delete()) throw new IOException("Completed restore journal cannot be removed");
    }

    private void cleanupAbandonedStaging() throws IOException {
        if (new File(privateRoot, RESTORE_JOURNAL).exists()) return;
        File[] children = privateRoot.listFiles(); if (children == null) throw new IOException("Cannot inspect staging recovery");
        for (File file : children) {
            String name = file.getName();
            for (String prefix : new String[]{".restore-", ".backup-snapshot-", ".backup-index-"}) {
                if (!name.startsWith(prefix)) continue;
                try { UUID.fromString(name.substring(prefix.length())); deleteTree(file); }
                catch (IllegalArgumentException ignored) { /* Unknown owner files are never removed. */ }
            }
        }
    }

    private JSONObject metadata(File file) throws IOException {
        String id = relativeId(file);
        if (AttachmentOriginals.reserved(id)) return attachmentOriginals.describe(id, false);
        JSONObject result = new JSONObject();
        put(result, "id", relativeId(file));
        put(result, "name", file.getName());
        put(result, "displayName", file.getName());
        put(result, "path", file.getCanonicalPath());
        put(result, "size", file.length());
        put(result, "updatedAt", file.lastModified());
        put(result, "storage", "private-internal-files");
        return result;
    }

    private String relativeId(File file) throws IOException {
        String base = projects.getCanonicalPath() + File.separator;
        String path = file.getCanonicalPath();
        if (!path.startsWith(base)) throw new IOException("File is outside private projects");
        return path.substring(base.length()).replace(File.separatorChar, '/');
    }

    private static File resolve(File root, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.length() > 2048
                || relative.startsWith("/") || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0) {
            throw new IOException("Only a relative private-project path is allowed");
        }
        String[] parts = relative.split("/", -1);
        if (parts.length > 33) throw new IOException("Project path is too deep");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".partial-") || part.getBytes(StandardCharsets.UTF_8).length > 255) throw new IOException("Unsafe project path");
            for (int i = 0; i < part.length(); i++) if (Character.isISOControl(part.charAt(i))) throw new IOException("Unsafe project path");
        }
        File target = new File(root, relative);
        String base = root.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(base)) throw new IOException("Path escapes the private project folder");
        return target;
    }

    private static String safeBasename(String displayName) {
        String name = displayName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}:]", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.isEmpty()) name = "attachment";
        // Reserve bytes for the UUID prefix; filesystem limits count UTF-8 bytes, not characters.
        int end = Math.min(name.length(), 160);
        while (end > 0 && name.substring(0, end).getBytes(StandardCharsets.UTF_8).length > 180) end--;
        if (end > 0 && end < name.length() && Character.isHighSurrogate(name.charAt(end - 1))
                && Character.isLowSurrogate(name.charAt(end))) end--;
        return end == 0 ? "attachment" : name.substring(0, end);
    }

    private static void collect(File folder, List<File> result, int depth) throws IOException {
        if (depth > 32) throw new IOException("Project directory nesting is too deep");
        File[] children = folder.listFiles();
        if (children == null) throw new IOException("Private working files cannot be listed");
        for (File child : children) {
            if (child.getName().startsWith(".partial-")) continue;
            // Do not follow symlinks, even ones that currently point inside the tree.
            if (Files.isSymbolicLink(child.toPath())) throw new IOException("Symbolic links are not supported in private projects");
            if (child.isDirectory()) collect(child, result, depth + 1);
            else if (child.isFile()) result.add(child);
            else throw new IOException("Unsupported private working file");
            if (result.size() > MAX_BACKUP_ENTRIES) throw new IOException("Too many private working files");
        }
    }

    private static void ensureDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Private storage directory cannot be created");
    }

    private static void atomicMove(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static long copy(InputStream input, OutputStream output, long maximum) throws IOException {
        if (maximum < 0) throw new IOException("Storage limit exceeded");
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            if (total > maximum - read) throw new IOException("Storage limit exceeded");
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        if (Files.isSymbolicLink(file.toPath())) {
            if (!file.delete()) throw new IOException("Restore temporary link cannot be removed");
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Restore directory cannot be listed");
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("Restore temporary file cannot be removed");
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private static long strictInteger(JSONObject input, String key, long defaultValue, long minimum, long maximum) throws IOException {
        if (!input.has(key)) return defaultValue;
        Object value = input.opt(key);
        if (!(value instanceof Number)) throw new IOException(key + " must be an integer");
        double number = ((Number) value).doubleValue(); long integer = ((Number) value).longValue();
        if (!Double.isFinite(number) || number != (double) integer || integer < minimum || integer > maximum) throw new IOException(key + " is outside its integer bounds");
        return integer;
    }

    private static JSONObject property(String type, String description) {
        JSONObject result = new JSONObject();
        put(result, "type", type); put(result, "description", description);
        return result;
    }

    private static JSONObject tool(String name, String description, JSONObject properties, JSONArray required) {
        JSONObject schema = new JSONObject();
        put(schema, "type", "object"); put(schema, "properties", properties); put(schema, "required", required);
        put(schema, "additionalProperties", false);
        JSONObject result = new JSONObject();
        put(result, "name", name); put(result, "description", description); put(result, "inputSchema", schema);
        return result;
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream stream) { super(stream); }
        @Override public void close() throws IOException { flush(); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); }
    }
    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream stream) { super(stream); }
        @Override public void close() { /* Caller owns the selected-document stream. */ }
    }
    private static final class LimitedOutputStream extends FilterOutputStream {
        private long remaining;
        LimitedOutputStream(OutputStream stream, long maximum) { super(stream); remaining = maximum; }
        @Override public void write(int value) throws IOException {
            if (remaining < 1) throw new IOException("Compressed backup exceeds 64 MiB");
            out.write(value); remaining--;
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (remaining < length) throw new IOException("Compressed backup exceeds 64 MiB");
            out.write(bytes, offset, length); remaining -= length;
        }
    }
    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream stream, long maximum) { super(stream); remaining = maximum; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value != -1 && remaining-- <= 0) throw new IOException("Compressed backup exceeds 64 MiB");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, (int) Math.min(length, Math.max(1, remaining)));
            if (read != -1 && read > remaining) throw new IOException("Compressed backup exceeds 64 MiB");
            if (read > 0) remaining -= read;
            return read;
        }
    }
}
