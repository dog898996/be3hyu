package com.hoyoung.assistant.ui;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class AttachmentPreviewTest {
    private Context context; private InternalFiles files; private ExecutorService io;
    @Before public void setUp() throws Exception { context = ApplicationProvider.getApplicationContext(); deleteTree(context.getFilesDir()); files = new InternalFiles(context); io = Executors.newSingleThreadExecutor(); }
    @After public void tearDown() throws Exception { io.shutdownNow(); deleteTree(context.getFilesDir()); }

    @Test public void verifiedLocalPngIsSampledAndScaledWithinThumbnailBounds() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.BLUE);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)); bitmap.recycle();
        JSONObject metadata = saved(encoded.toByteArray(), "picture.png");
        AttachmentPreview.Result result = worker(metadata, AttachmentPreview.THUMBNAIL_SIZE);
        assertTrue(result.detail, result.ready()); assertTrue(result.width <= 512); assertTrue(result.height <= 512);
        assertEquals(Color.BLUE, result.bitmap.getPixel(0, 0)); result.bitmap.recycle();
    }

    @Test public void corruptImageAndFalseIdentityBecomeExplicitPlaceholderStatuses() throws Exception {
        JSONObject corrupt = saved("not a PNG".getBytes(java.nio.charset.StandardCharsets.UTF_8), "broken.png");
        AttachmentPreview.Result result = worker(corrupt, 512); assertFalse(result.ready()); assertEquals("corrupt_image", result.status);
        JSONObject falseIdentity = new JSONObject(corrupt.toString()).put("sha256", repeat('0', 64));
        result = worker(falseIdentity, 512); assertFalse(result.ready()); assertEquals("integrity_failed", result.status);
        JSONObject huge = new JSONObject(corrupt.toString()).put("size", AttachmentPreview.MAX_ENCODED_BYTES + 1L);
        result = worker(huge, 512); assertFalse(result.ready()); assertEquals("encoded_too_large", result.status);
    }

    @Test public void extremeLogicalImageDimensionsAreRejectedBeforePixelAllocation() throws Exception {
        AttachmentPreview.Result result = worker(saved(headerPng(50_000, 50_000), "bomb.png"), 512);
        assertFalse(result.ready()); assertTrue(result.status, result.status.equals("dimensions_too_large") || result.status.equals("corrupt_image"));
    }

    @Test public void mainThreadAndMutableOrNonImageSourcesAreRejected() throws Exception {
        JSONObject metadata = saved(new byte[]{1, 2, 3}, "picture.png");
        assertEquals("background_required", AttachmentPreview.loadResult(context, files, metadata, 512).status);
        JSONObject mutable = new JSONObject(metadata.toString()).put("id", "working.png").put("originalAssetId", "working.png");
        assertEquals("original_missing", worker(mutable, 512).status);
        JSONObject nonImage = new JSONObject(metadata.toString()).put("mime", "application/json");
        assertEquals("not_image", worker(nonImage, 512).status);
    }

    @Test public void exifRotationAndMirrorMatricesMapPixelAxesCorrectly() throws Exception {
        java.lang.reflect.Method method = AttachmentPreview.class.getDeclaredMethod("orientationMatrix", int.class); method.setAccessible(true);
        float[] point = {2, 3}; ((Matrix) method.invoke(null, ExifInterface.ORIENTATION_TRANSPOSE)).mapPoints(point); assertArrayEquals(new float[]{3, 2}, point, .001f);
        point = new float[]{2, 3}; ((Matrix) method.invoke(null, ExifInterface.ORIENTATION_TRANSVERSE)).mapPoints(point); assertArrayEquals(new float[]{-3, -2}, point, .001f);
        point = new float[]{2, 3}; ((Matrix) method.invoke(null, ExifInterface.ORIENTATION_ROTATE_90)).mapPoints(point); assertArrayEquals(new float[]{-3, 2}, point, .001f);
    }

    private AttachmentPreview.Result worker(JSONObject metadata, int size) throws Exception { return io.submit(() -> AttachmentPreview.loadResult(context, files, metadata, size)).get(15, TimeUnit.SECONDS); }
    private JSONObject saved(byte[] bytes, String name) throws Exception { JSONObject working = files.writeFile(name, new ByteArrayInputStream(bytes)); return files.freezeAttachments(new JSONArray().put(working)).getJSONObject(0); }
    private static String repeat(char value, int count) { char[] text = new char[count]; java.util.Arrays.fill(text, value); return new String(text); }
    private static byte[] headerPng(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream file = new DataOutputStream(bytes); file.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        ByteArrayOutputStream header = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(header); data.writeInt(width); data.writeInt(height); data.write(new byte[]{8,6,0,0,0}); chunk(file,"IHDR",header.toByteArray());
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(); try(DeflaterOutputStream deflate = new DeflaterOutputStream(compressed)){deflate.write(new byte[]{0,0,0,0,0});} chunk(file,"IDAT",compressed.toByteArray()); chunk(file,"IEND",new byte[0]); return bytes.toByteArray();
    }
    private static void chunk(DataOutputStream file, String name, byte[] data) throws IOException { byte[] type=name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);file.writeInt(data.length);file.write(type);file.write(data);CRC32 crc=new CRC32();crc.update(type);crc.update(data);file.writeInt((int)crc.getValue()); }
    private static void deleteTree(File root) throws IOException { if(!root.exists())return;if(Files.isSymbolicLink(root.toPath())){if(!root.delete())throw new IOException();return;}if(root.isDirectory()){File[] children=root.listFiles();if(children==null)throw new IOException();for(File child:children)deleteTree(child);}if(!root.delete())throw new IOException(); }
}
