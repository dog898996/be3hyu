package com.hoyoung.assistant.phone;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

/** Private fsynced observations, independent of the SQLite/store monitor. */
final class PhoneObservationSpool {
    static final int MAX_RECORD_BYTES = 2 * 1024 * 1024;
    private final File root;
    private final File pending;
    private final File state;
    private final File quarantine;
    private final File archive;
    private long sequence;
    PhoneObservationSpool(File root) throws IOException {
        this.root = root.getCanonicalFile(); pending = new File(this.root, "pending"); state = new File(this.root, "state"); quarantine = new File(this.root, "quarantine"); archive = new File(this.root, "archive");
        afterRestore();
    }
    synchronized void afterRestore() throws IOException {
        directory(pending); directory(state); directory(quarantine); directory(archive);
        String[] names = pending.list(); if (names != null) for (String name : names) { String[] parts = name.split("-", 3); if (parts.length == 3 && parts[1].matches("[0-9]{10}")) try { sequence = Math.max(sequence, Long.parseLong(parts[1])); } catch (NumberFormatException ignored) { } }
        recoverPartials();
    }
    synchronized JSONObject append(String category, String source, JSONObject payload, long observedAt, String dedupeKey) throws IOException {
        if (category == null || category.isEmpty() || category.length() > 128 || source == null || source.isEmpty() || source.length() > 256 || observedAt < 0 || payload == null || payload.toString().length() > 500000 || dedupeKey != null && (dedupeKey.isEmpty() || dedupeKey.length() > 512)) throw new IOException("Invalid or oversized observation metadata");
        String id = UUID.randomUUID().toString();
        JSONObject record = PhoneEventJournal.object("version", 1, "spoolId", id, "category", category, "source", source, "payload", payload,
            "observedAt", observedAt, "capturedAt", System.currentTimeMillis(), "captureSequence", ++sequence, "dedupeKey", dedupeKey == null ? "phone-spool:" + id : dedupeKey,
            "receiptRequired", dedupeKey != null && (dedupeKey.startsWith("location-request:") || dedupeKey.startsWith("voice-request:")));
        checksum(record);
        String filename = String.format(java.util.Locale.ROOT, "%019d-%010d-%s.json", Math.max(0, System.currentTimeMillis()), sequence, id);
        atomicWrite(new File(pending, filename), record);
        return record;
    }
    synchronized File[] pending(int limit) throws IOException {
        File[] files = pending.listFiles(file -> file.isFile() && file.getName().endsWith(".json")); if (files == null) throw new IOException("Cannot enumerate pending observations");
        Arrays.sort(files, Comparator.comparing(File::getName)); return files.length <= limit ? files : Arrays.copyOf(files, limit);
    }
    synchronized int pendingCount() { String[] files = pending.list((directory, name) -> name.endsWith(".json")); return files == null ? -1 : files.length; }
    JSONObject read(File file) throws IOException {
        if (!file.getCanonicalPath().startsWith(root.getPath() + File.separator) || Files.isSymbolicLink(file.toPath())) throw new IOException("Spool path escapes its private root");
        JSONObject value = readJson(file); verify(value);
        if (value.optInt("version") != 1 || !value.optString("spoolId").matches("[0-9a-f-]{36}") || value.optJSONObject("payload") == null || value.optLong("observedAt", -1) < 0 || value.optString("dedupeKey").isEmpty()) throw new IOException("Invalid spool record");
        return value;
    }
    synchronized void acknowledge(File file, JSONObject record, JSONObject stored) throws IOException {
        if (record.optBoolean("receiptRequired")) writeState("receipt." + record.optString("spoolId"), PhoneEventJournal.object("deviceEventId", stored.optLong("id"), "spoolId", record.optString("spoolId"), "contentHash", stored.optString("content_hash")).toString());
        // Preserve raw source bytes as well as the SQLite index; acknowledgement is not deletion.
        long day = Math.max(0, record.optLong("capturedAt")) / 86400000L; File bucket = new File(archive, String.valueOf(day)); directory(bucket);
        if (file.exists()) Files.move(file.toPath(), new File(bucket, file.getName()).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    synchronized void quarantine(File file) throws IOException { Files.move(file.toPath(), new File(quarantine, UUID.randomUUID() + ".json").toPath(), StandardCopyOption.ATOMIC_MOVE); }
    synchronized String readState(String key, String fallback) {
        File file = new File(state, PhoneEventJournal.hash(key) + ".json"); if (!file.exists()) return fallback;
        try { JSONObject row = readJson(file); verify(row); return key.equals(row.optString("key")) && row.opt("value") instanceof String ? row.getString("value") : fallback; }
        catch (IOException | JSONException invalid) { return fallback; }
    }
    synchronized void writeState(String key, String value) throws IOException { JSONObject row = PhoneEventJournal.object("key", key, "value", value); checksum(row); atomicWrite(new File(state, PhoneEventJournal.hash(key) + ".json"), row); }
    File directory() { return root; }
    /** Capture immutable files before opening the corresponding SQLite backup snapshot. */
    void captureSnapshot(File destination) throws IOException {
        directory(destination);
        for (File parent : new File[]{pending, state, quarantine, archive}) captureDirectory(parent, new File(destination, parent.getName()));
    }
    private void captureDirectory(File parent, File copiedParent) throws IOException {
            File[] files; synchronized (this) { files = parent.listFiles(file -> !file.getName().startsWith(".partial-")); }
            if (files == null) throw new IOException("Cannot enumerate private journal snapshot"); directory(copiedParent);
            for (File source : files) {
                File target = new File(copiedParent, source.getName()); FileInputStream fallback = null;
                if (source.isDirectory()) { captureDirectory(source, target); continue; }
                synchronized (this) {
                    if (!source.exists()) continue; // Ingestion already committed it; later SQL snapshot includes it.
                    try { Files.createLink(target.toPath(), source.toPath()); continue; }
                    catch (IOException | UnsupportedOperationException cannotLink) { fallback = new FileInputStream(source); }
                }
                // Open descriptor keeps the immutable inode alive if ingestion removes its name.
                try (FileInputStream input = fallback; FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count); output.getFD().sync();
                }
            }
    }
    private void recoverPartials() throws IOException {
        for (File parent : new File[]{pending, state}) {
            File[] partials = parent.listFiles(file -> file.isFile() && file.getName().startsWith(".partial-"));
            if (partials == null) throw new IOException("Cannot check interrupted spool writes");
            for (File file : partials) {
                try {
                    JSONObject record = readJson(file); verify(record);
                    if (parent.equals(pending) && record.optInt("version") == 1 && record.optJSONObject("payload") != null && record.optString("spoolId").matches("[0-9a-f-]{36}")) {
                        Files.move(file.toPath(), new File(pending, String.format(java.util.Locale.ROOT, "%019d-%010d-%s.json", Math.max(0, record.optLong("capturedAt")), record.optLong("captureSequence"), record.optString("spoolId"))).toPath(), StandardCopyOption.ATOMIC_MOVE);
                    } else if (parent.equals(state) && record.opt("value") instanceof String && !record.optString("key").isEmpty()) {
                        File target = new File(state, PhoneEventJournal.hash(record.optString("key")) + ".json");
                        if (target.exists() && target.lastModified() > file.lastModified()) quarantine(file);
                        else Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } else throw new IOException("Interrupted record is incomplete");
                } catch (IOException invalid) { quarantine(file); append("collector_gap", "phone.private_spool", PhoneEventJournal.object("reason", "interrupted_spool_write_quarantined", "filename", file.getName(), "observationsLostOrUnknown", true), System.currentTimeMillis(), null); }
            }
        }
    }
    private static void directory(File file) throws IOException { if (!file.mkdirs() && !file.isDirectory()) throw new IOException("Cannot initialize private observation spool"); }
    private static void atomicWrite(File target, JSONObject value) throws IOException {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8); if (bytes.length > MAX_RECORD_BYTES) throw new IOException("Observation exceeds the explicit spool record bound");
        File partial = new File(target.getParentFile(), ".partial-" + UUID.randomUUID());
        try {
            try (FileOutputStream output = new FileOutputStream(partial)) { output.write(bytes); output.getFD().sync(); }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { if (partial.exists()) partial.delete(); }
    }
    private static JSONObject readJson(File file) throws IOException {
        if (file.length() > MAX_RECORD_BYTES) throw new IOException("Oversized spool data"); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(file)) { byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) != -1) { if (bytes.size() + count > MAX_RECORD_BYTES) throw new IOException("Oversized spool data"); bytes.write(buffer, 0, count); } }
        try { return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)); } catch (JSONException invalid) { throw new IOException("Spool JSON is invalid", invalid); }
    }
    private static void checksum(JSONObject value) { try { value.put("checksum", PhoneEventJournal.hash(value.toString())); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
    private static void verify(JSONObject value) throws IOException {
        String expected = value.optString("checksum"); value.remove("checksum"); String actual = PhoneEventJournal.hash(value.toString());
        try { value.put("checksum", expected); } catch (JSONException impossible) { throw new IOException(impossible); }
        if (!expected.equals(actual)) throw new IOException("Spool content checksum differs");
    }
}
