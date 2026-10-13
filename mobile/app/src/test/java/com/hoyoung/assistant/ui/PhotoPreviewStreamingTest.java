package com.hoyoung.assistant.ui;

import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.*;

/** Valid synthetic PNGs are streamed; no full-resolution source bitmap or raw image is allocated. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PhotoPreviewStreamingTest {
    private static final long MIB = 1024L * 1024;
    private static final long MAX_OUTPUT_PIXELS = 2_000_000L;
    private Context context;
    private InternalFiles files;
    private ExecutorService io;

    @Before public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();
        deleteTree(context.getFilesDir());
        assertTrue(context.getFilesDir().isDirectory() || context.getFilesDir().mkdirs());
        files = new InternalFiles(context);
        io = Executors.newSingleThreadExecutor();
    }

    @After public void tearDown() throws IOException {
        io.shutdownNow();
        deleteTree(context.getFilesDir());
    }

    @Test public void validPngAboveWireSizeStillHasNativeLocalThumbnailAndExactOriginal() throws Exception {
        File fixture = fixture("padded-blue.png");
        writeBluePng(fixture, 1000, 800, 11 * MIB);
        assertTrue("Local preview must cover images above the independent wire limit", fixture.length() > 10 * MIB);
        assertTrue(fixture.length() < 64 * MIB);
        String sourceHash = sha256(fixture);
        JSONObject metadata = files.saveFromUri(Uri.fromFile(fixture), "padded-blue.png");
        assertEquals(sourceHash, metadata.getString("sha256"));
        assertEquals(fixture.length(), metadata.getLong("size"));
        File original = original(metadata);
        String frozenHash = sha256(original);
        long frozenLength = original.length();

        AttachmentPreview.Result result = worker(metadata, AttachmentPreview.THUMBNAIL_SIZE);
        try { assertBluePreview(result, AttachmentPreview.THUMBNAIL_SIZE); }
        finally { if (result.bitmap != null) result.bitmap.recycle(); }

        assertEquals(sourceHash, frozenHash);
        assertEquals(frozenHash, sha256(original));
        assertEquals(frozenLength, original.length());
        assertEquals(sourceHash, sha256(fixture));
        assertEquals(sourceHash, metadata.getString("sha256"));
    }

    @Test public void valid200MegapixelSourceUsesBoundedNativeThumbnailAndExpandedDecode() throws Exception {
        int width = 16_000, height = 12_500;
        assertEquals(200_000_000L, (long) width * height);
        File fixture = fixture("200mp-blue.png");
        writeBluePng(fixture, width, height, 0);
        assertTrue("A flat source is genuinely PNG encoded, not a raw 600 MB buffer", fixture.length() < 10 * MIB);
        String sourceHash = sha256(fixture);
        JSONObject metadata = files.saveFromUri(Uri.fromFile(fixture), "200mp-blue.png");
        File original = original(metadata);
        long frozenLength = original.length();

        AttachmentPreview.Result thumbnail = worker(metadata, AttachmentPreview.THUMBNAIL_SIZE);
        try { assertBluePreview(thumbnail, AttachmentPreview.THUMBNAIL_SIZE); }
        finally { if (thumbnail.bitmap != null) thumbnail.bitmap.recycle(); }
        AttachmentPreview.Result expanded = worker(metadata, AttachmentPreview.EXPANDED_SIZE);
        try { assertBluePreview(expanded, AttachmentPreview.EXPANDED_SIZE); }
        finally { if (expanded.bitmap != null) expanded.bitmap.recycle(); }

        assertEquals(sourceHash, metadata.getString("sha256"));
        assertEquals(sourceHash, sha256(original));
        assertEquals(frozenLength, original.length());
        assertEquals(sourceHash, sha256(fixture));
    }

    @Test public void modifiedFrozenSourceCannotProducePreviewAndIsNeverRewrittenByDecoder() throws Exception {
        File fixture = fixture("changed-blue.png");
        writeBluePng(fixture, 128, 96, 0);
        JSONObject metadata = files.saveFromUri(Uri.fromFile(fixture), "changed-blue.png");
        File original = original(metadata);
        String declaredHash = metadata.getString("sha256");
        long frozenLength = original.length();
        try (RandomAccessFile mutation = new RandomAccessFile(original, "rw")) {
            mutation.seek(0); mutation.write(0); // Deliberate synthetic corruption of the PNG signature.
        }
        String corruptedHash = sha256(original);
        assertNotEquals(declaredHash, corruptedHash);

        AttachmentPreview.Result result = worker(metadata, AttachmentPreview.THUMBNAIL_SIZE);
        assertFalse(result.status + ": " + result.detail, result.ready());
        assertNull(result.bitmap);
        assertTrue(result.status, result.status.equals("integrity_failed") || result.status.equals("source_unavailable"));
        assertEquals(corruptedHash, sha256(original));
        assertEquals(frozenLength, original.length());
        assertEquals(declaredHash, metadata.getString("sha256"));
        assertEquals(declaredHash, sha256(fixture));
    }

    @Test public void hashVerifiedMalformedImageHasExplicitFailureWithoutChangingSavedBytes() throws Exception {
        File fixture = fixture("malformed.png");
        try (OutputStream output = new FileOutputStream(fixture)) {
            output.write("synthetic malformed PNG bytes".getBytes(StandardCharsets.UTF_8));
        }
        JSONObject metadata = files.saveFromUri(Uri.fromFile(fixture), "malformed.png");
        File original = original(metadata);
        String beforeHash = sha256(original);
        long beforeLength = original.length();

        AttachmentPreview.Result result = worker(metadata, AttachmentPreview.THUMBNAIL_SIZE);
        assertFalse(result.ready()); assertEquals("corrupt_image", result.status); assertNull(result.bitmap);
        assertEquals(beforeHash, sha256(original));
        assertEquals(beforeLength, original.length());
        assertEquals(beforeHash, metadata.getString("sha256"));
    }

    private AttachmentPreview.Result worker(JSONObject metadata, int side) throws Exception {
        return io.submit(() -> AttachmentPreview.loadResult(context, files, metadata, side)).get(60, TimeUnit.SECONDS);
    }

    private static void assertBluePreview(AttachmentPreview.Result result, int edge) {
        assertTrue(result.status + ": " + result.detail, result.ready());
        assertTrue(result.width > 0 && result.height > 0);
        assertTrue(result.width <= edge && result.height <= edge);
        assertTrue((long) result.width * result.height <= MAX_OUTPUT_PIXELS);
        assertEquals(Color.BLUE, result.bitmap.getPixel(0, 0));
        assertEquals(Color.BLUE, result.bitmap.getPixel(result.width / 2, result.height / 2));
        assertEquals(Color.BLUE, result.bitmap.getPixel(result.width - 1, result.height - 1));
    }

    private File fixture(String name) throws IOException {
        File directory = new File(context.getFilesDir(), "synthetic-photo-fixtures");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create synthetic fixture directory");
        return new File(directory, name);
    }

    private File original(JSONObject metadata) throws Exception {
        return new File(new File(context.getFilesDir(), "projects"), metadata.getString("originalAssetId"));
    }

    /** RGB PNG, filter zero per scanline, fixed-size IDAT chunks, optional valid ancillary padding. */
    private static void writeBluePng(File destination, int width, int height, long paddingBytes) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(destination)))) {
            output.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            byte[] header = new byte[13];
            integer(header, 0, width); integer(header, 4, height);
            header[8] = 8; header[9] = 2; // 8-bit RGB, no alpha or interlace.
            chunk(output, "IHDR", header, header.length);
            byte[] scanline = new byte[1 + width * 3];
            for (int pixel = 0; pixel < width; pixel++) scanline[1 + pixel * 3 + 2] = (byte) 255;
            Deflater compressor = new Deflater(Deflater.BEST_SPEED);
            try (DeflaterOutputStream encoded = new DeflaterOutputStream(new IdatChunks(output), compressor, 16_384)) {
                for (int row = 0; row < height; row++) encoded.write(scanline);
            } finally { compressor.end(); }
            if (paddingBytes > 0) paddingChunk(output, paddingBytes);
            chunk(output, "IEND", new byte[0], 0);
        }
    }

    private static final class IdatChunks extends OutputStream {
        private final DataOutputStream output;
        private final byte[] buffer = new byte[64 * 1024];
        private int count;
        IdatChunks(DataOutputStream output) { this.output = output; }
        @Override public void write(int value) throws IOException {
            buffer[count++] = (byte) value; if (count == buffer.length) publish();
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
                int copied = Math.min(length, buffer.length - count);
                System.arraycopy(bytes, offset, buffer, count, copied);
                offset += copied; length -= copied; count += copied;
                if (count == buffer.length) publish();
            }
        }
        private void publish() throws IOException {
            if (count != 0) { chunk(output, "IDAT", buffer, count); count = 0; }
        }
        @Override public void close() throws IOException { publish(); }
    }

    private static void paddingChunk(DataOutputStream output, long bytes) throws IOException {
        if (bytes > Integer.MAX_VALUE) throw new IOException("Synthetic PNG padding is too large");
        byte[] type = "hoYg".getBytes(StandardCharsets.US_ASCII); // Unknown ancillary/private/safe-to-copy chunk.
        output.writeInt((int) bytes); output.write(type);
        CRC32 crc = new CRC32(); crc.update(type);
        byte[] zeros = new byte[16_384];
        while (bytes != 0) {
            int count = (int) Math.min(bytes, zeros.length);
            output.write(zeros, 0, count); crc.update(zeros, 0, count); bytes -= count;
        }
        output.writeInt((int) crc.getValue());
    }

    private static void chunk(DataOutputStream output, String name, byte[] bytes, int count) throws IOException {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(count); output.write(type); output.write(bytes, 0, count);
        CRC32 crc = new CRC32(); crc.update(type); crc.update(bytes, 0, count);
        output.writeInt((int) crc.getValue());
    }

    private static void integer(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24); bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8); bytes[offset + 3] = (byte) value;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[16_384];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static void deleteTree(File root) throws IOException {
        if (!root.exists()) return;
        if (Files.isSymbolicLink(root.toPath())) { if (!root.delete()) throw new IOException("Cannot remove synthetic link"); return; }
        if (root.isDirectory()) {
            File[] children = root.listFiles(); if (children == null) throw new IOException("Cannot inspect synthetic fixtures");
            for (File child : children) deleteTree(child);
        }
        if (!root.delete()) throw new IOException("Cannot remove synthetic fixture");
    }
}
