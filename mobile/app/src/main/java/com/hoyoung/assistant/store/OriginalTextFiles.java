package com.hoyoung.assistant.store;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.security.DigestInputStream;
import java.util.HashMap;
import java.util.Map;

/** Exact large originals are independent of summaries and the disposable search index. */
final class OriginalTextFiles {
    static final long MAX_BYTES=40L*1024*1024;
    static final int MAX_ORIGINAL_CHARS = 10_000_000;
    private final File root;
    private final Map<String, String> verified = new HashMap<>();

    OriginalTextFiles(File filesDir) {
        InternalFiles.requireNoPendingRestore(filesDir);
        root = new File(filesDir, "originals");
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Original storage cannot be created");
    }

    JSONObject save(String text) throws IOException {
        if (text == null || text.length() > MAX_ORIGINAL_CHARS) throw new IOException("Original exceeds 10,000,000 UTF-16 characters");
        MessageDigest hashOnly = digest();
        try (OutputStreamWriter writer = new OutputStreamWriter(new DigestOutputStream(new java.io.OutputStream() {
            @Override public void write(int ignored) { }
            @Override public void write(byte[] ignored, int offset, int length) { }
        }, hashOnly), StandardCharsets.UTF_8)) { writeText(writer, text); }
        String hash = hex(hashOnly.digest()), id = hash + ".txt";
        File destination = new File(root, id);
        if (destination.exists()) { verify(id, hash, destination.length()); return metadata(id,text.length(),destination.length(),hash); }
        File temporary = new File(root, ".partial-" + UUID.randomUUID());
        MessageDigest digest = digest();
        try {
            try (FileOutputStream file = new FileOutputStream(temporary);
                 OutputStreamWriter writer = new OutputStreamWriter(new DigestOutputStream(file, digest), StandardCharsets.UTF_8)) {
                writeText(writer, text);
                writer.flush(); file.getFD().sync();
            }
            if (!hash.equals(hex(digest.digest()))) throw new IOException("Original encoding changed during its write");
            if (!destination.exists()) Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
            else if (destination.length() != temporary.length()) throw new IOException("An immutable original has conflicting stored bytes");
            verify(id, hash, destination.length());
            return metadata(id,text.length(),destination.length(),hash);
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    private static void writeText(OutputStreamWriter writer, String text) throws IOException {
        for (int start=0;start<text.length();start+=16_384) writer.write(text,start,Math.min(16_384,text.length()-start)); writer.flush();
    }

    private static JSONObject metadata(String id,long chars,long bytes,String hash) {
        JSONObject result=new JSONObject();
        try { result.put("raw_file_id",id);result.put("raw_char_count",chars);result.put("raw_byte_count",bytes);result.put("raw_content_hash",hash); }
        catch(JSONException impossible){throw new IllegalStateException(impossible);}return result;
    }

    synchronized void verify(String id,String expectedHash,long expectedBytes) throws IOException {
        File file=file(id);
        if(!file.isFile()||file.length()!=expectedBytes||file.length()>MAX_BYTES)throw new IOException("Original bytes are missing or changed");
        String fingerprint=file.length()+":"+file.lastModified()+":"+expectedHash;
        if(fingerprint.equals(verified.get(id)))return;
        MessageDigest digest=digest();
        try(DigestInputStream input=new DigestInputStream(new FileInputStream(file),digest)){
            byte[] buffer=new byte[16_384];while(input.read(buffer)!=-1){ }
        }
        if(!hex(digest.digest()).equals(expectedHash))throw new IOException("Exact original SHA-256 does not match");verified.put(id,fingerprint);
    }

    File file(String id) throws IOException {
        if (id == null || !id.matches("(?:[a-fA-F0-9-]{36}|[a-fA-F0-9]{64})\\.txt")) throw new IOException("Invalid original file id");
        File file = new File(root, id);
        if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)
                || Files.isSymbolicLink(file.toPath())) throw new IOException("Original path escapes private storage");
        return file;
    }

    String read(String id, long offsetChars, int maxChars) throws IOException {
        if (offsetChars < 0) throw new IllegalArgumentException("Negative original offset");
        int bound = Math.max(0, Math.min(1_000_000, maxChars));
        StringBuilder result = new StringBuilder(Math.min(bound, 8192));
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file(id)), StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            long remaining = offsetChars;
            char previous=0;
            while (remaining > 0) {
                int read = reader.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (read < 0) return "";
                if(read>0)previous=buffer[read-1];
                remaining -= read;
            }
            while (result.length() < bound) {
                int read = reader.read(buffer, 0, Math.min(buffer.length, bound - result.length()));
                if (read < 0) break;
                if(result.length()==0&&offsetChars>0&&read>0&&Character.isHighSurrogate(previous)&&Character.isLowSurrogate(buffer[0]))throw new IllegalArgumentException("Offset splits a UTF-16 surrogate pair");
                result.append(buffer, 0, read);
            }
        }
        return result.toString();
    }

    String readEntire(String id) throws IOException {
        File file = file(id);
        if (file.length() > MAX_BYTES) throw new IOException("Original file exceeds its storage bound");
        StringBuilder result = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[16_384]; int read;
            while ((read = reader.read(buffer)) != -1) {
                if (result.length() > MAX_ORIGINAL_CHARS - read) throw new IOException("Original exceeds its character bound");
                result.append(buffer, 0, read);
            }
        }
        return result.toString();
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
