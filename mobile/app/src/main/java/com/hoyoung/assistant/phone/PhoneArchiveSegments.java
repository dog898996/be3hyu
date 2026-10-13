package com.hoyoung.assistant.phone;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import com.hoyoung.assistant.store.BackupPolicy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

/** Immutable, independently verifiable containers for exact original observation files. */
final class PhoneArchiveSegments {
    static final long MAX_SEGMENT_BYTES = 32L * 1024 * 1024;
    static final int DEFAULT_RECORDS = 256;
    private static final int MAX_RECORDS = 4096;
    private static final int MAX_NAME_BYTES = 512;
    private static final long MAGIC = 0x484F594F554E4731L; // HOYOUNG1
    private static final long END = 0x454E445241573031L; // ENDRAW01
    private static final int OVERHEAD = 28;

    static final class Source {
        final String path; final File file;
        Source(String path, File file) throws IOException { validPath(path); this.path = path; this.file = file; }
        long framedLength() throws IOException {
            long length = file.length();
            if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || length > PhoneObservationSpool.MAX_RECORD_BYTES) throw new IOException("Raw journal file is absent, unsafe or oversized");
            return 4L + path.getBytes(StandardCharsets.UTF_8).length + 4 + 32 + length;
        }
    }
    static final class Entry {
        final String path, checksum; final int length;
        Entry(String path, int length, String checksum) { this.path = path; this.length = length; this.checksum = checksum; }
    }
    static final class Verified {
        final List<Entry> entries; final String checksum;
        Verified(List<Entry> entries, String checksum) { this.entries = entries; this.checksum = checksum; }
    }
    interface Visitor { void record(String originalPath, byte[] originalBytes, String sha256) throws IOException; }

    /** The only published name is the hash of the complete, fsynced and verified container. */
    static File write(File destination, List<Source> sources, long limit) throws IOException {
        return write(destination,sources,limit,null);
    }
    static File write(File destination,List<Source> sources,long limit,BackupPolicy.SpaceMonitor monitor)throws IOException {
        if(monitor!=null)monitor.checkpoint("export_snapshot","device-journal/archive/segments",0);
        if (sources.isEmpty() || sources.size() > MAX_RECORDS || limit > MAX_SEGMENT_BYTES || limit < OVERHEAD) throw new IOException("Invalid archive segment bounds");
        directory(destination); long expectedLength = OVERHEAD;
        for (Source source : sources) expectedLength += source.framedLength();
        if (expectedLength > limit) throw new IOException("Archive segment exceeds its explicit bound");
        File partial = new File(destination, ".partial-segment-" + UUID.randomUUID());
        try {
            MessageDigest completeHash = digest();
            try (FileOutputStream file = new FileOutputStream(partial); DataOutputStream output = new DataOutputStream(new DigestOutputStream(monitored(file,monitor), completeHash))) {
                output.writeLong(MAGIC); output.writeInt(1);
                for (Source source : sources) {
                    byte[] name = source.path.getBytes(StandardCharsets.UTF_8);
                    int length = (int) (source.framedLength() - 4 - name.length - 4 - 32);
                    byte[] hash = hashFile(source.file, length);
                    output.writeInt(name.length); output.write(name); output.writeInt(length); output.write(hash);
                    MessageDigest copied = digest(); int total = 0;
                    try (FileInputStream input = new FileInputStream(source.file)) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = input.read(buffer)) != -1) { total += count; if (total > length) throw new IOException("Raw archive source changed during copying"); output.write(buffer, 0, count); copied.update(buffer, 0, count); }
                    }
                    if (total != length || !Arrays.equals(hash, copied.digest())) throw new IOException("Raw archive source changed during copying");
                }
                output.writeInt(-1); output.writeInt(sources.size()); output.writeLong(END); output.flush(); file.getFD().sync();
            }
            String checksum = hex(completeHash.digest());
            Verified verified = verify(partial, false, null);
            if (!checksum.equals(verified.checksum) || partial.length() != expectedLength) throw new IOException("New archive segment verification failed");
            File published = new File(destination, "segment-" + checksum + ".hja");
            if (published.exists()) { verify(published, true, null); Files.delete(partial.toPath()); }
            else Files.move(partial.toPath(), published.toPath(), StandardCopyOption.ATOMIC_MOVE);
            syncDirectory(destination);
            // Do not trust publication alone when deciding whether originals can be removed.
            verify(published, true, null);
            if(monitor!=null)monitor.checkpoint("export_snapshot","device-journal/archive/segments",0);
            return published;
        } finally {
            // Interrupted writes left by a process crash are recovered separately. An ordinary
            // failed write can safely discard this incomplete container; originals remain.
            if (partial.exists()) Files.delete(partial.toPath());
        }
    }
    static OutputStream monitored(OutputStream output,BackupPolicy.SpaceMonitor monitor){
        if(monitor==null)return output;
        return new OutputStream(){
            @Override public void write(int value)throws IOException{monitor.beforeWrite(1);output.write(value);monitor.written(1);}
            @Override public void write(byte[]bytes,int offset,int length)throws IOException{monitor.beforeWrite(length);output.write(bytes,offset,length);monitor.written(length);}
            @Override public void flush()throws IOException{output.flush();}
            @Override public void close()throws IOException{output.close();}
        };
    }

    static Verified verify(File file, boolean enforceName, Visitor visitor) throws IOException {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || file.length() < OVERHEAD || file.length() > MAX_SEGMENT_BYTES) throw new IOException("Archive segment is unsafe, incomplete or oversized");
        MessageDigest completeHash = digest(); List<Entry> entries = new ArrayList<>(); Set<String> names = new HashSet<>();
        try (DataInputStream input = new DataInputStream(new DigestInputStream(new FileInputStream(file), completeHash))) {
            if (input.readLong() != MAGIC || input.readInt() != 1) throw new IOException("Unsupported raw archive segment header");
            while (true) {
                int nameLength = input.readInt();
                if (nameLength == -1) { if (input.readInt() != entries.size() || input.readLong() != END || input.read() != -1 || entries.isEmpty()) throw new IOException("Archive segment footer does not match its records"); break; }
                if (nameLength <= 0 || nameLength > MAX_NAME_BYTES || entries.size() >= MAX_RECORDS) throw new IOException("Invalid archive segment record bounds");
                byte[] nameBytes = new byte[nameLength]; input.readFully(nameBytes); String path = utf8(nameBytes); validPath(path);
                if (!names.add(path)) throw new IOException("Duplicate original filename inside archive segment");
                int length = input.readInt(); if (length < 0 || length > PhoneObservationSpool.MAX_RECORD_BYTES) throw new IOException("Invalid original byte length");
                byte[] expected = new byte[32]; input.readFully(expected); MessageDigest body = digest();
                ByteArrayOutputStream original = visitor == null ? null : new ByteArrayOutputStream(length);
                byte[] buffer = new byte[8192]; int remaining = length;
                while (remaining > 0) { int count = input.read(buffer, 0, Math.min(remaining, buffer.length)); if (count < 0) throw new IOException("Truncated original observation in archive segment"); body.update(buffer, 0, count); if (original != null) original.write(buffer, 0, count); remaining -= count; }
                if (!Arrays.equals(expected, body.digest())) throw new IOException("Original observation byte checksum mismatch");
                String checksum = hex(expected); entries.add(new Entry(path, length, checksum));
                if (visitor != null) visitor.record(path, original.toByteArray(), checksum);
            }
        } catch (EOFException truncated) { throw new IOException("Truncated raw archive segment frame or footer", truncated); }
        String checksum = hex(completeHash.digest());
        if (enforceName && !file.getName().equals("segment-" + checksum + ".hja")) throw new IOException("Complete archive segment checksum mismatch");
        return new Verified(entries, checksum);
    }
    static boolean matches(File original, Entry entry) throws IOException {
        return original.isFile() && !Files.isSymbolicLink(original.toPath()) && original.length() == entry.length && hex(hashFile(original, entry.length)).equals(entry.checksum);
    }
    static void syncDirectory(File directory) throws IOException {
        // API26's java.nio provider supports directory fsync on Android's private Linux filesystems.
        if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) throw new IOException("Private archive directory is unsafe");
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { channel.force(true); return; }
        catch (IOException | UnsupportedOperationException javaProviderUnavailable) {
            // Some Android java.nio providers reject directory channels even though Linux
            // supports directory fsync. Use the public Android POSIX wrapper in that case.
            java.io.FileDescriptor descriptor = null;
            try {
                descriptor = android.system.Os.open(directory.getPath(), android.system.OsConstants.O_RDONLY | android.system.OsConstants.O_NOFOLLOW, 0);
                if (!android.system.OsConstants.S_ISDIR(android.system.Os.fstat(descriptor).st_mode)) throw new IOException("Private archive fsync target is not a directory");
                android.system.Os.fsync(descriptor);
            } catch (android.system.ErrnoException unavailable) { throw new IOException("Private archive directory fsync is unavailable", unavailable); }
            finally { if (descriptor != null) try { android.system.Os.close(descriptor); } catch (android.system.ErrnoException closeFailure) { /* Fsync already decided success. */ } }
        }
    }
    static void directory(File file) throws IOException { if (!file.mkdirs() && !file.isDirectory()) throw new IOException("Cannot initialize raw archive directory"); }
    static void validPath(String path) throws IOException {
        if (path == null || path.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES || !path.matches("[0-9]{1,20}/[A-Za-z0-9][A-Za-z0-9._-]{0,249}\\.json") || path.contains("..")) throw new IOException("Invalid original archive filename");
    }
    private static byte[] hashFile(File file, int expectedLength) throws IOException {
        MessageDigest hash = digest(); int length = 0;
        try (FileInputStream input = new FileInputStream(file)) { byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) { length += count; if (length > expectedLength) throw new IOException("Raw archive byte length changed"); hash.update(buffer, 0, count); } }
        if (length != expectedLength) throw new IOException("Raw archive byte length changed"); return hash.digest();
    }
    private static String utf8(byte[] bytes) throws IOException { try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); } catch (CharacterCodingException invalid) { throw new IOException("Invalid archive filename UTF-8", invalid); } }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static String hex(byte[] bytes) { StringBuilder text = new StringBuilder(bytes.length * 2); for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255)); return text.toString(); }
}
