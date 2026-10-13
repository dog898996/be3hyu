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
    public static final long MAX_BACKUP_BYTES = 64L * 1024 * 1024;
    private static final long MAX_SNAPSHOT_BYTES = 16L * 1024 * 1024;
    private static final int MAX_BACKUP_ENTRIES = 10_000;
    private static final String RESTORE_JOURNAL = ".restore-transaction.json";
    private final Context context;
    private final File privateRoot;
    private final File projects;

    public InternalFiles(Context context) {
        this.context = context.getApplicationContext();
        this.privateRoot = this.context.getFilesDir();
        this.projects = new File(privateRoot, "projects");
        try { recoverRestore(); }
        catch (IOException e) { throw new IllegalStateException("An interrupted backup restore could not be recovered", e); }
        ensureDirectory(projects);
    }

    public synchronized JSONObject saveFromUri(Uri uri, String displayName) throws IOException {
        if (uri == null) throw new IllegalArgumentException("A source URI is required");
        if (displayName == null || displayName.trim().isEmpty()) {
            try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) displayName = cursor.getString(0);
            } catch (RuntimeException ignored) { /* A provider may omit display-name metadata. */ }
        }
        if (displayName == null || displayName.trim().isEmpty()) displayName = "attachment";
        String fileId = UUID.randomUUID() + "-" + safeBasename(displayName);
        try (InputStream source = context.getContentResolver().openInputStream(uri)) {
            if (source == null) throw new IOException("The selected document cannot be opened");
            JSONObject result = writeFile(fileId, source);
            put(result, "displayName", displayName);
            String mimeType = context.getContentResolver().getType(uri);
            if (mimeType != null) put(result, "mimeType", mimeType);
            return result;
        }
    }

    public synchronized JSONArray listFiles() throws IOException {
        List<File> items = new ArrayList<>();
        collect(projects, items, 0);
        items.sort(Comparator.comparingLong(File::lastModified).reversed());
        JSONArray result = new JSONArray();
        for (File file : items) result.put(metadata(file));
        return result;
    }

    public synchronized JSONArray catalog() {
        JSONArray result = new JSONArray();
        result.put(tool("files.list", "List the owner's private saved working files.", new JSONObject(), new JSONArray()));
        JSONObject read = new JSONObject();
        put(read, "fileId", property("string", "Relative id from files.list; not a host path."));
        put(read, "maxChars", property("integer", "Maximum text characters, capped at 1,000,000."));
        result.put(tool("files.read", "Read a private working file as UTF-8 text.", read, new JSONArray().put("fileId")));
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
                String fileId = arguments.optString("fileId", null);
                put(result, "fileId", fileId);
                put(result, "text", readText(fileId, arguments.optInt("maxChars", 20_000)));
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
        File target = resolve(projects, relativePath);
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
    public synchronized void exportBackup(OutputStream destination, HoyoungStore store) throws IOException {
        if (destination == null || store == null) throw new IllegalArgumentException("A destination and store are required");
        // Store writes cannot interleave with the JSON snapshot. Runtime must be paused for a
        // project-wide consistency point if a task is also producing files outside this helper.
        synchronized (store) {
            byte[] snapshot = store.exportSnapshot().toString().getBytes(StandardCharsets.UTF_8);
            if (snapshot.length > MAX_SNAPSHOT_BYTES) throw new IOException("Store snapshot exceeds the 16 MiB backup limit");
            List<File> files = new ArrayList<>();
            collect(projects, files, 0);
            if (files.size() + 2 > MAX_BACKUP_ENTRIES) throw new IOException("Too many backup files");
            long total = snapshot.length;
            for (File file : files) {
                if (file.length() > MAX_FILE_BYTES) throw new IOException("A working file exceeds 64 MiB");
                total += file.length();
                if (total > MAX_BACKUP_BYTES - 4096) throw new IOException("Backup exceeds the 64 MiB total limit");
            }
            JSONObject manifest = new JSONObject();
            put(manifest, "format", "hoyoung-private-backup");
            put(manifest, "version", 1);
            put(manifest, "createdAt", System.currentTimeMillis());
            LimitedOutputStream limited = new LimitedOutputStream(new NonClosingOutputStream(destination), MAX_BACKUP_BYTES);
            try (ZipOutputStream zip = new ZipOutputStream(limited)) {
                writeEntry(zip, "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
                writeEntry(zip, "hoyoung/store.json", snapshot);
                long copied = snapshot.length + manifest.toString().getBytes(StandardCharsets.UTF_8).length;
                for (File file : files) {
                    zip.putNextEntry(new ZipEntry("projects/" + relativeId(file)));
                    try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                        long bytes = copy(input, zip, Math.min(MAX_FILE_BYTES, MAX_BACKUP_BYTES - copied));
                        copied += bytes;
                    }
                    zip.closeEntry();
                }
                zip.finish();
            }
            destination.flush();
        }
    }

    /**
     * Restore explicitly, with runtime paused. Validate/extract a bounded ZIP privately first;
     * traversal, duplicate paths, unknown entries, and partial snapshots are rejected.
     */
    public synchronized void importBackup(InputStream source, HoyoungStore store) throws IOException {
        if (source == null || store == null) throw new IllegalArgumentException("A backup and store are required");
        File staging = new File(privateRoot, ".restore-" + UUID.randomUUID());
        File stagedProjects = new File(staging, "projects");
        ensureDirectory(stagedProjects);
        File oldProjects = new File(privateRoot, ".previous-projects-" + UUID.randomUUID());
        String restoreToken = UUID.randomUUID().toString();
        JSONObject snapshot = null;
        JSONObject manifest = null;
        Set<String> entries = new HashSet<>();
        long extracted = 0;
        boolean movedOld = false;
        boolean replaced = false;
        try {
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
                    new LimitedInputStream(new NonClosingInputStream(source), MAX_BACKUP_BYTES)))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entries.size() >= MAX_BACKUP_ENTRIES) throw new IOException("Too many backup entries");
                    String name = entry.getName();
                    if (entry.isDirectory()) throw new IOException("Backup directory entries are not supported");
                    if (!entries.add(name)) throw new IOException("Duplicate backup entry");
                    if (name.equals("manifest.json") || name.equals("hoyoung/store.json")) {
                        long limit = name.equals("manifest.json") ? 4096 : MAX_SNAPSHOT_BYTES;
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        long read = copy(zip, bytes, Math.min(limit, MAX_BACKUP_BYTES - extracted));
                        extracted += read;
                        try {
                            JSONObject parsed = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                            if (name.equals("manifest.json")) manifest = parsed; else snapshot = parsed;
                        } catch (JSONException e) { throw new IOException("Invalid backup JSON", e); }
                    } else if (name.startsWith("projects/")) {
                        String relative = name.substring("projects/".length());
                        File target = resolve(stagedProjects, relative);
                        if (relative.startsWith(".partial-")) throw new IOException("Partial files are not backup content");
                        ensureDirectory(target.getParentFile());
                        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                            extracted += copy(zip, output, Math.min(MAX_FILE_BYTES, MAX_BACKUP_BYTES - extracted));
                        }
                    } else throw new IOException("Unknown backup entry");
                    if (extracted > MAX_BACKUP_BYTES) throw new IOException("Expanded backup exceeds 64 MiB");
                    zip.closeEntry();
                }
            }
            if (manifest == null || !"hoyoung-private-backup".equals(manifest.optString("format"))
                    || manifest.optInt("version", -1) != 1 || snapshot == null) throw new IOException("Incomplete or unsupported backup");
            synchronized (store) {
                try { store.validateSnapshot(snapshot); }
                catch (RuntimeException e) { throw new IOException("The backup store failed validation", e); }
                writeRestoreJournal(restoreToken, staging, oldProjects);
                atomicMove(projects, oldProjects);
                movedOld = true;
                atomicMove(stagedProjects, projects);
                replaced = true;
                try { store.importSnapshot(snapshot, restoreToken); }
                catch (RuntimeException e) {
                    deleteTree(projects);
                    atomicMove(oldProjects, projects);
                    movedOld = false;
                    replaced = false;
                    throw new IOException("Backup database restore failed; previous files were restored", e);
                }
            }
            deleteTree(oldProjects);
            movedOld = false;
            removeRestoreJournal();
        } finally {
            // If replacement failed before the database changed, put the original tree back.
            if (movedOld && !replaced && !projects.exists()) atomicMove(oldProjects, projects);
            deleteTree(staging);
            if (!replaced && new File( privateRoot, RESTORE_JOURNAL).exists()) removeRestoreJournal();
        }
    }

    /** The DB commit token decides whether a crashed directory swap commits or rolls back. */
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
        File previous = journalDirectory(record.optString("previous", ""), ".previous-projects-");
        boolean committed = false;
        File databaseFile = context.getDatabasePath(HoyoungStore.DATABASE_NAME);
        if (databaseFile.isFile()) {
            try (SQLiteDatabase database = SQLiteDatabase.openDatabase(databaseFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor cursor = database.rawQuery("SELECT value FROM settings WHERE setting_key = ?",
                         new String[]{HoyoungStore.FILE_RESTORE_TOKEN_KEY})) {
                committed = cursor.moveToFirst() && token.equals(cursor.getString(0));
            } catch (RuntimeException e) { throw new IOException("Cannot inspect interrupted restore commit", e); }
        }
        if (committed) {
            if (!projects.isDirectory()) throw new IOException("Committed restore is missing its project directory");
            deleteTree(previous);
        } else if (previous.exists()) {
            deleteTree(projects);
            atomicMove(previous, projects);
        } else if (!projects.isDirectory()) throw new IOException("Original project directory is missing during restore recovery");
        deleteTree(staging);
        removeRestoreJournal();
    }

    private File journalDirectory(String name, String prefix) throws IOException {
        if (!name.startsWith(prefix)) throw new IOException("Invalid restore directory");
        try { UUID.fromString(name.substring(prefix.length())); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid restore directory", e); }
        File directory = new File(privateRoot, name);
        if (!directory.getCanonicalPath().startsWith(privateRoot.getCanonicalPath() + File.separator)) throw new IOException("Unsafe restore directory");
        return directory;
    }

    private void writeRestoreJournal(String token, File staging, File previous) throws IOException {
        JSONObject record = new JSONObject();
        put(record, "token", token); put(record, "staging", staging.getName()); put(record, "previous", previous.getName());
        File temporary = new File(privateRoot, RESTORE_JOURNAL + ".partial");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(record.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
        }
        atomicMove(temporary, new File(privateRoot, RESTORE_JOURNAL));
    }

    private void removeRestoreJournal() throws IOException {
        File journal = new File(privateRoot, RESTORE_JOURNAL);
        if (journal.exists() && !journal.delete()) throw new IOException("Completed restore journal cannot be removed");
    }

    private JSONObject metadata(File file) throws IOException {
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
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".partial-") || part.length() > 255) throw new IOException("Unsafe project path");
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
        return name.substring(0, Math.min(name.length(), 160));
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
