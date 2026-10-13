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
import java.nio.file.DirectoryStream;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Private fsynced observations, independent of the SQLite/store monitor. */
final class PhoneObservationSpool {
    static final int MAX_RECORD_BYTES = 2 * 1024 * 1024;
    private final File root;
    private final File pending;
    private final File state;
    private final File quarantine;
    private final File archive;
    private final File segments;
    private final Object archiveWorkLock = new Object();
    private final long segmentLimit;
    private final int compactAt;
    private int activeSnapshots;
    private long archiveWrites;
    private volatile boolean archiveRecoveryRequired = true;
    private volatile boolean archiveAuditRequired;
    private long archiveAuditEpoch;
    private long sequence;
    PhoneObservationSpool(File root) throws IOException { this(root, PhoneArchiveSegments.MAX_SEGMENT_BYTES, PhoneArchiveSegments.DEFAULT_RECORDS); }
    PhoneObservationSpool(File root, long segmentLimit, int compactAt) throws IOException {
        if (segmentLimit < 512 || segmentLimit > PhoneArchiveSegments.MAX_SEGMENT_BYTES || compactAt < 1 || compactAt > PhoneArchiveSegments.DEFAULT_RECORDS) throw new IOException("Invalid raw archive compaction bounds");
        this.segmentLimit = segmentLimit; this.compactAt = compactAt;
        this.root = root.getCanonicalFile(); pending = new File(this.root, "pending"); state = new File(this.root, "state"); quarantine = new File(this.root, "quarantine"); archive = new File(this.root, "archive");
        segments = new File(archive, "segments");
        afterRestore();
    }
    synchronized void afterRestore() throws IOException {
        directory(pending); directory(state); directory(quarantine); directory(archive);
        archiveRecoveryRequired = true; archiveWrites = 0;
        archiveAuditRequired = !readState("health.archiveFailure", "").isEmpty();
        archiveAuditEpoch++;
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
        if (file.exists()) { Files.move(file.toPath(), new File(bucket, file.getName()).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); archiveWrites++; }
    }
    synchronized void quarantine(File file) throws IOException { Files.move(file.toPath(), new File(quarantine, UUID.randomUUID() + ".json").toPath(), StandardCopyOption.ATOMIC_MOVE); }
    synchronized String readState(String key, String fallback) {
        File file = new File(state, PhoneEventJournal.hash(key) + ".json"); if (!file.exists()) return fallback;
        try { JSONObject row = readJson(file); verify(row); return key.equals(row.optString("key")) && row.opt("value") instanceof String ? row.getString("value") : fallback; }
        catch (IOException | JSONException invalid) { return fallback; }
    }
    synchronized void writeState(String key, String value) throws IOException { JSONObject row = PhoneEventJournal.object("key", key, "value", value); checksum(row); atomicWrite(new File(state, PhoneEventJournal.hash(key) + ".json"), row); }
    File directory() { return root; }
    boolean archiveMaintenanceNeeded() { synchronized (this) { return archiveRecoveryRequired || archiveWrites >= compactAt; } }
    boolean archiveSnapshotActive() { synchronized (this) { return activeSnapshots > 0; } }
    boolean archiveAuditPending() { return archiveAuditRequired; }
    private synchronized void requireArchiveAudit() { archiveAuditEpoch++; archiveAuditRequired = true; archiveRecoveryRequired = true; }

    /** Background worker only. It never holds the callback monitor while hashing/copying data. */
    boolean compactArchive() throws IOException {
        synchronized (archiveWorkLock) {
            boolean recover; long before;
            synchronized (this) { recover = archiveRecoveryRequired; before = archiveWrites; }
            if (recover && !recoverArchiveSegments()) return false; // A snapshot currently pins originals.
            if (!recover && before < compactAt) return false;
            List<PhoneArchiveSegments.Source> raw = rawSources(compactAt);
            if (raw.size() < compactAt) {
                synchronized (this) { archiveRecoveryRequired = archiveAuditRequired; archiveWrites = Math.max(0, archiveWrites - before) + raw.size(); }
                return false;
            }
            List<PhoneArchiveSegments.Source> batch = boundedBatch(raw);
            File published = PhoneArchiveSegments.write(segments, batch, segmentLimit);
            if (!cleanOriginals(published)) { archiveRecoveryRequired = true; return false; }
            int remaining = rawSources(compactAt).size();
            synchronized (this) { archiveRecoveryRequired = archiveAuditRequired; archiveWrites = Math.max(0, archiveWrites - before) + remaining; }
            return remaining >= compactAt;
        }
    }
    private boolean recoverArchiveSegments() throws IOException {
        long auditedEpoch; boolean audit;
        synchronized (this) { auditedEpoch = archiveAuditEpoch; audit = archiveAuditRequired; }
        if (!segments.exists()) { finishArchiveAudit(auditedEpoch); return true; }
        File[] files = segments.listFiles(); if (files == null) throw new IOException("Cannot enumerate raw archive segments");
        for (File file : files) {
            if (file.getName().startsWith(".partial-segment-")) {
                try {
                    PhoneArchiveSegments.Verified verified = PhoneArchiveSegments.verify(file, false, null);
                    File target = new File(segments, "segment-" + verified.checksum + ".hja");
                    if (target.exists()) { PhoneArchiveSegments.verify(target, true, null); Files.delete(file.toPath()); }
                    else Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    PhoneArchiveSegments.syncDirectory(segments);
                } catch (IOException invalid) {
                    if (file.exists()) Files.move(file.toPath(), new File(quarantine, UUID.randomUUID() + ".hja").toPath(), StandardCopyOption.ATOMIC_MOVE);
                    append("collector_gap", "phone.private_archive", PhoneEventJournal.object("reason", "interrupted_archive_segment_quarantined", "originalRawFilesRetained", true), Math.max(0, System.currentTimeMillis()), null);
                }
            }
        }
        files = segments.listFiles(file -> file.getName().endsWith(".hja")); if (files == null) throw new IOException("Cannot enumerate published raw segments");
        for (File file : files) {
            if (audit) PhoneArchiveSegments.verify(file, true, null);
            if (!cleanupComplete(file) && !cleanOriginals(file)) return false;
        }
        finishArchiveAudit(auditedEpoch);
        return true;
    }
    private synchronized void finishArchiveAudit(long auditedEpoch) { if (archiveAuditEpoch == auditedEpoch) archiveAuditRequired = false; }
    private boolean cleanupComplete(File segment) {
        try { JSONObject marker = readJson(new File(segments, segment.getName() + ".done")); verify(marker); return marker.optBoolean("sourceCleanupComplete") && marker.optString("segmentFilename").equals(segment.getName()); }
        catch (IOException invalid) { return false; }
    }
    private boolean cleanOriginals(File segment) throws IOException {
        PhoneArchiveSegments.Verified verified = PhoneArchiveSegments.verify(segment, true, null);
        List<File> changedParents = new ArrayList<>();
        for (PhoneArchiveSegments.Entry entry : verified.entries) {
            File original = new File(archive, entry.path);
            if (!original.getCanonicalPath().startsWith(archive.getCanonicalPath() + File.separator)) throw new IOException("Archived original path escapes its private root");
            if (!original.exists()) continue;
            long modified = original.lastModified();
            if (!PhoneArchiveSegments.matches(original, entry)) throw new IOException("Original archive bytes conflict with a published segment; both retained");
            synchronized (this) {
                if (activeSnapshots > 0) return false;
                if (original.exists()) {
                    if (original.lastModified() != modified || original.length() != entry.length) throw new IOException("Original changed before archive cleanup; both retained");
                    Files.delete(original.toPath()); // One bounded unlink; all hashing happened outside the callback monitor.
                    if (!changedParents.contains(original.getParentFile())) changedParents.add(original.getParentFile());
                }
            }
        }
        for (File parent : changedParents) PhoneArchiveSegments.syncDirectory(parent);
        JSONObject marker = PhoneEventJournal.object("version", 1, "segmentFilename", segment.getName(), "sourceCleanupComplete", true); checksum(marker);
        atomicWrite(new File(segments, segment.getName() + ".done"), marker); PhoneArchiveSegments.syncDirectory(segments);
        return true;
    }
    private List<PhoneArchiveSegments.Source> rawSources(int limit) throws IOException {
        List<PhoneArchiveSegments.Source> output = new ArrayList<>();
        try (DirectoryStream<java.nio.file.Path> buckets = Files.newDirectoryStream(archive.toPath())) {
            for (java.nio.file.Path path : buckets) {
                File bucket = path.toFile(); if (bucket.equals(segments)) continue;
                if (Files.isSymbolicLink(path) || !bucket.isDirectory() || !bucket.getName().matches("[0-9]{1,20}")) throw new IOException("Unexpected raw archive path; preserved for review");
                try (DirectoryStream<java.nio.file.Path> files = Files.newDirectoryStream(path)) {
                    for (java.nio.file.Path raw : files) { output.add(new PhoneArchiveSegments.Source(bucket.getName() + "/" + raw.getFileName(), raw.toFile())); if (output.size() >= limit) return output; }
                }
            }
        }
        return output;
    }
    private List<PhoneArchiveSegments.Source> boundedBatch(List<PhoneArchiveSegments.Source> candidates) throws IOException {
        List<PhoneArchiveSegments.Source> output = new ArrayList<>(); long size = 28;
        for (PhoneArchiveSegments.Source source : candidates) { long length = source.framedLength(); if (size + length > segmentLimit) { if (output.isEmpty()) throw new IOException("One raw observation cannot fit the explicit archive segment bound"); break; } output.add(source); size += length; }
        return output;
    }
    /** Capture immutable files before opening the corresponding SQLite backup snapshot. */
    void captureSnapshot(File destination) throws IOException { captureSnapshot(destination, null); }
    void captureSnapshot(File destination, Runnable beforeRawCapture) throws IOException {
        synchronized (this) { activeSnapshots++; }
        try {
            directory(destination);
            String[] existing = destination.list(); if (existing == null || existing.length != 0) throw new IOException("Private journal snapshot destination must be empty");
            for (File parent : new File[]{pending, state, quarantine}) captureDirectory(parent, new File(destination, parent.getName()));
            File copiedArchive = new File(destination, "archive"), copiedSegments = new File(copiedArchive, "segments"); directory(copiedArchive);
            if (segments.exists()) {
                captureDirectory(segments, copiedSegments);
                File[] copied = copiedSegments.listFiles(file -> file.getName().endsWith(".hja")); if (copied == null) throw new IOException("Cannot enumerate copied archive segments");
                for (File segment : copied) PhoneArchiveSegments.verify(segment, true, null);
            }
            if (beforeRawCapture != null) beforeRawCapture.run();
            // Pack even an old uncompacted backlog in the backup, instead of emitting one ZIP
            // entry per event. An active pin keeps compaction from deleting these originals.
            List<PhoneArchiveSegments.Source> batch = new ArrayList<>(); long size = 28;
            try (DirectoryStream<java.nio.file.Path> buckets = Files.newDirectoryStream(archive.toPath())) {
                for (java.nio.file.Path path : buckets) {
                    File bucket = path.toFile(); if (bucket.equals(segments)) continue;
                    if (Files.isSymbolicLink(path) || !bucket.isDirectory() || !bucket.getName().matches("[0-9]{1,20}")) throw new IOException("Unexpected raw archive path in backup");
                    try (DirectoryStream<java.nio.file.Path> files = Files.newDirectoryStream(path)) {
                        for (java.nio.file.Path raw : files) {
                            PhoneArchiveSegments.Source source = new PhoneArchiveSegments.Source(bucket.getName() + "/" + raw.getFileName(), raw.toFile()); long length = source.framedLength();
                            if (!batch.isEmpty() && (size + length > segmentLimit || batch.size() >= PhoneArchiveSegments.DEFAULT_RECORDS)) { PhoneArchiveSegments.write(copiedSegments, batch, segmentLimit); batch.clear(); size = 28; }
                            batch.add(source); size += length;
                        }
                    }
                }
            }
            if (!batch.isEmpty()) PhoneArchiveSegments.write(copiedSegments, batch, segmentLimit);
        } catch (IOException validationFailure) {
            requireArchiveAudit(); throw validationFailure;
        } finally { synchronized (this) { activeSnapshots--; if (activeSnapshots == 0) archiveRecoveryRequired = true; } }
    }
    static void validateSnapshot(File stagedJournal) throws IOException {
        if (stagedJournal == null || !stagedJournal.exists()) return; // Legacy backup has no phone namespace.
        if (Files.isSymbolicLink(stagedJournal.toPath())) throw new IOException("Staged phone journal root is a symbolic link");
        File canonical = stagedJournal.getCanonicalFile(); validateSnapshotDirectory(canonical, canonical, 0);
    }
    private static void validateSnapshotDirectory(File root, File directory, int depth) throws IOException {
        if (depth > 32 || Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory()) throw new IOException("Unsafe staged phone journal directory");
        File[] files = directory.listFiles(); if (files == null) throw new IOException("Cannot enumerate staged phone journal");
        for (File file : files) {
            if (Files.isSymbolicLink(file.toPath()) || !file.getCanonicalPath().startsWith(root.getPath() + File.separator)) throw new IOException("Staged phone journal path escapes its namespace");
            if (file.isDirectory()) { validateSnapshotDirectory(root, file, depth + 1); continue; }
            if (!file.isFile() || file.length() > 64L * 1024 * 1024) throw new IOException("Staged phone journal file is unavailable or oversized");
            String relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
            if (relative.startsWith("archive/segments/")) {
                if (file.getName().endsWith(".hja")) PhoneArchiveSegments.verify(file, true, null);
                else if (file.getName().endsWith(".hja.done")) {
                    JSONObject marker = readJson(file); verify(marker);
                    String segment = file.getName().substring(0, file.getName().length() - 5);
                    if (!segment.matches("segment-[0-9a-f]{64}\\.hja") || !marker.optBoolean("sourceCleanupComplete") || !segment.equals(marker.optString("segmentFilename")) || !new File(file.getParentFile(), segment).isFile()) throw new IOException("Invalid staged raw archive cleanup marker");
                } else if (!file.getName().startsWith(".partial-segment-")) throw new IOException("Unknown published raw archive file");
            }
        }
    }
    private void captureDirectory(File parent, File copiedParent) throws IOException {
            File[] files; synchronized (this) { files = parent.listFiles(file -> !file.getName().startsWith(".partial-")); }
            if (files == null) throw new IOException("Cannot enumerate private journal snapshot"); directory(copiedParent);
            for (File source : files) {
                File target = new File(copiedParent, source.getName()); FileInputStream fallback = null;
                if (Files.isSymbolicLink(source.toPath()) || !source.getCanonicalPath().startsWith(root.getPath() + File.separator)) throw new IOException("Private journal snapshot contains an unsafe source path");
                if (source.isDirectory()) { captureDirectory(source, target); continue; }
                synchronized (this) {
                    if (!source.exists()) continue; // Ingestion already committed it; later SQL snapshot includes it.
                    try { Files.createLink(target.toPath(), source.toPath()); continue; }
                    catch (IOException | UnsupportedOperationException cannotLink) {
                        if (target.exists() || !target.createNewFile()) throw new IOException("Private journal snapshot target already exists", cannotLink);
                        fallback = new FileInputStream(source);
                    }
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
