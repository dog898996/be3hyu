package com.hoyoung.assistant.phone;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

/** Requested access within shared external storage; Android grants decide reachability. */
public final class PhoneSharedFiles {
    private static final int MAX_READ_BYTES = 1024 * 1024;
    private final Context context;
    public PhoneSharedFiles(Context context) { this.context = context.getApplicationContext(); }

    public synchronized JSONObject execute(String name, JSONObject args) {
        try {
            if (!"phone_files_list".equals(name) && !"phone_files_read".equals(name) && !"phone_files_write".equals(name)) return error("unsupported", "No such shared-storage tool.");
            validateArguments(name, args);
            boolean writing = "phone_files_write".equals(name);
            if (!allowed(writing)) return error("permission_missing", "Enable shared-storage access in Android Settings.").put("permissionId", "all_files");
            File root = Environment.getExternalStorageDirectory().getCanonicalFile();
            String relative = args.optString("path", "");
            File target = resolve(root, relative, "phone_files_list".equals(name));
            switch (name) {
                case "phone_files_list": return list(root, target, relative, args.optInt("limit", 50));
                case "phone_files_read": return read(target, relative, args.optInt("maxChars", 20000));
                default: return write(root, target, relative, args.getString("content"));
            }
        } catch (SecurityException denied) { return error("permission_missing", "Android denied shared-storage access."); }
        catch (IllegalArgumentException | JSONException invalid) { return error("invalid_arguments", "Use a relative shared-storage path and supported argument types and limits."); }
        catch (PathDenied denied) { return error("path_access_denied", "Use a path within shared storage without traversal or symlinks."); }
        catch (CharacterCodingException binary) { return error("invalid_utf8", "The file is not valid UTF-8 text."); }
        catch (IOException unavailable) { return error("operation_failed", "The shared file could not be accessed; no write success is assumed."); }
    }

    private boolean allowed(boolean writing) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        String required = writing ? Manifest.permission.WRITE_EXTERNAL_STORAGE : Manifest.permission.READ_EXTERNAL_STORAGE;
        return context.checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED;
    }

    private JSONObject list(File root, File directory, String path, int limit) throws IOException, JSONException {
        if (!directory.isDirectory()) return error("not_a_directory", "Select an existing shared directory.");
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Directory not accessible");
        Arrays.sort(children, Comparator.comparing(file -> file.getName().toLowerCase(Locale.ROOT)));
        JSONArray entries = new JSONArray(); boolean truncated = false;
        for (File child : children) {
            String childPath = relative(root, child);
            try { resolve(root, childPath, false); }
            catch (PathDenied protectedChild) { continue; }
            if (entries.length() >= limit) { truncated = true; break; }
            entries.put(new JSONObject().put("path", childPath).put("name", child.getName()).put("type", child.isDirectory() ? "directory" : "file")
                .put("size", child.isFile() ? child.length() : JSONObject.NULL).put("modifiedAt", child.lastModified()));
        }
        return ok().put("root", "shared_external_storage").put("path", path).put("entries", entries).put("truncated", truncated).put("recursive", false);
    }

    private JSONObject read(File file, String path, int maximumChars) throws IOException, JSONException {
        if (!file.isFile()) return error("not_a_file", "Select an existing shared file.");
        if (file.length() > MAX_READ_BYTES) return error("file_too_large", "Text reads are capped at 1 MiB before decoding.");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) { if (output.size() + count > MAX_READ_BYTES) return error("file_too_large", "The file exceeded the bounded text-read size."); output.write(buffer, 0, count); }
        }
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
        boolean truncated = text.length() > maximumChars;
        if (truncated) { int end = maximumChars; if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--; text = text.substring(0, end); }
        return ok().put("root", "shared_external_storage").put("path", path).put("text", text).put("truncated", truncated).put("encoding", "UTF-8");
    }

    private JSONObject write(File root, File target, String path, String content) throws IOException, JSONException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(content));
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        File parent = target.getParentFile();
        createParents(root, parent);
        target = resolve(root, path, false);
        if (target.exists() && !target.isFile()) return error("not_a_file", "A directory cannot be overwritten as a text file.");
        File temporary = new File(parent, ".hoyoung-write-" + UUID.randomUUID());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); output.getFD().sync(); }
            resolve(root, relative(root, temporary), false);
            target = resolve(root, path, false);
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return ok().put("root", "shared_external_storage").put("path", path).put("bytesWritten", bytes.length).put("atomic", true);
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    private static void createParents(File root, File parent) throws IOException {
        if (parent.equals(root)) return;
        resolve(root, relative(root, parent), true);
        if (!parent.exists()) { createParents(root, parent.getParentFile()); if (!parent.mkdir() && !parent.isDirectory()) throw new IOException("Cannot create shared directory"); }
        resolve(root, relative(root, parent), true);
        if (!parent.isDirectory()) throw new IOException("Parent is not a directory");
    }

    private static File resolve(File root, String path, boolean allowRoot) throws IOException {
        if (path == null || path.length() > 1024 || path.startsWith("/") || path.contains("\\") || path.contains(":") || path.indexOf('\0') >= 0) throw new PathDenied();
        if (path.isEmpty()) { if (!allowRoot) throw new PathDenied(); return root; }
        String[] parts = path.split("/", -1);
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..") || part.chars().anyMatch(value -> value < 32)) throw new PathDenied();
        // Do not invent an app-level Android/data or Android/obb ban. Android's
        // storage grants, scoped storage and the real filesystem determine access.
        File candidate = root;
        for (String part : parts) { candidate = new File(candidate, part); if (Files.isSymbolicLink(candidate.toPath())) throw new PathDenied(); }
        File canonical = candidate.getCanonicalFile();
        if (!canonical.getPath().startsWith(root.getPath() + File.separator)) throw new PathDenied();
        return canonical;
    }

    private static String relative(File root, File child) throws IOException {
        String raw = child.getAbsolutePath();
        if (raw.equals(root.getPath())) return "";
        if (!raw.startsWith(root.getPath() + File.separator)) throw new PathDenied();
        return raw.substring(root.getPath().length() + 1);
    }

    private static void validateArguments(String name, JSONObject args) throws JSONException {
        if (args == null) throw new IllegalArgumentException();
        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next(); boolean allowed = key.equals("path") || (name.equals("phone_files_list") && key.equals("limit")) || (name.equals("phone_files_read") && key.equals("maxChars")) || (name.equals("phone_files_write") && key.equals("content"));
            if (!allowed) throw new IllegalArgumentException();
            Object value = args.get(key);
            if (key.equals("path") || key.equals("content")) { if (!(value instanceof String)) throw new IllegalArgumentException(); }
            else if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).longValue()) throw new IllegalArgumentException();
        }
        if (!name.equals("phone_files_list") && (!args.has("path") || args.getString("path").isEmpty())) throw new IllegalArgumentException();
        if (name.equals("phone_files_list")) { long limit = args.optLong("limit", 50); if (limit < 1 || limit > 100) throw new IllegalArgumentException(); }
        if (name.equals("phone_files_read")) { long count = args.optLong("maxChars", 20000); if (count < 1 || count > 50000) throw new IllegalArgumentException(); }
        if (name.equals("phone_files_write") && (!args.has("content") || args.getString("content").length() > 100000)) throw new IllegalArgumentException();
    }

    private static final class PathDenied extends IOException { }
    private static JSONObject ok() { return object(true, "ok", ""); }
    private static JSONObject error(String code, String message) { return object(false, code, message); }
    private static JSONObject object(boolean ok, String code, String message) { try { return new JSONObject().put("ok", ok).put("code", code).put("message", message); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
}
