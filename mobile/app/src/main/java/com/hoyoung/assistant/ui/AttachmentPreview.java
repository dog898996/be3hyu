package com.hoyoung.assistant.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Looper;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Local verified image previews. Run on an IO worker; returned bitmaps belong to the UI. */
public final class AttachmentPreview {
    public static final int THUMBNAIL_SIZE = 512;
    public static final int EXPANDED_SIZE = 1536;
    public static final int MAX_ENCODED_BYTES = 10 * 1024 * 1024;
    private static final long MAX_SOURCE_PIXELS = 100_000_000L;
    private static final long MAX_DECODED_PIXELS = 2_000_000L;
    private AttachmentPreview() { }

    public static final class Result {
        public final Bitmap bitmap;
        public final String status;
        public final String detail;
        public final int width, height;
        private Result(Bitmap bitmap, String status, String detail) {
            this.bitmap = bitmap; this.status = status; this.detail = detail;
            width = bitmap == null ? 0 : bitmap.getWidth(); height = bitmap == null ? 0 : bitmap.getHeight();
        }
        public boolean ready() { return bitmap != null; }
    }

    /** Convenience API; use loadResult to render the explicit placeholder status on failure. */
    public static Bitmap load(Context context, InternalFiles files, JSONObject metadata, int maxPixels) {
        return loadResult(context, files, metadata, maxPixels).bitmap;
    }

    /** maxPixels is the requested longest side; it is capped at 1536 for expanded views. */
    public static Result loadResult(Context context, InternalFiles files, JSONObject metadata, int maxPixels) {
        if (Looper.myLooper() == Looper.getMainLooper()) return failed("background_required", "이미지 미리보기는 백그라운드에서 읽어야 해.");
        if (context == null || files == null || metadata == null || maxPixels < 1) return failed("invalid_arguments", "미리보기 정보가 올바르지 않아.");
        String mime = metadata.optString("mime", metadata.optString("mimeType", "")).toLowerCase(Locale.ROOT);
        if (!mime.startsWith("image/")) return failed("not_image", "이미지 첨부가 아니야.");
        String id = metadata.optString("originalAssetId", metadata.optString("id", ""));
        if (!id.matches("\\.attachment-originals/[a-f0-9]{64}\\.[a-z0-9]{1,12}")
                || (metadata.has("id") && !id.equals(metadata.optString("id")))) return failed("original_missing", "고정된 첨부 원본을 찾을 수 없어.");
        String expectedHash = metadata.optString("sha256", "");
        if (!expectedHash.matches("[a-f0-9]{64}")) return failed("invalid_identity", "첨부 원본의 hash가 없어.");
        if (metadata.optLong("size", -1) > MAX_ENCODED_BYTES) return failed("encoded_too_large", "10 MiB를 넘는 이미지는 미리보기에서 제외해.");
        Bitmap decoded = null;
        try {
            BoundedBytes output = new BoundedBytes(); files.exportFile(id, output);
            byte[] bytes = output.bytes();
            if (!expectedHash.equals(sha256(bytes))) return failed("integrity_failed", "첨부 원본이 저장 당시와 달라졌어.");
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return failed("corrupt_image", "이미지 내용을 읽을 수 없어.");
            if ((long) bounds.outWidth * bounds.outHeight > MAX_SOURCE_PIXELS
                    || bounds.outWidth > 32_768 || bounds.outHeight > 32_768) return failed("dimensions_too_large", "이미지 크기가 미리보기 한도를 넘어.");
            int edge = Math.min(EXPANDED_SIZE, maxPixels), sample = 1;
            while ((bounds.outWidth + (long) sample - 1) / sample > edge
                    || (bounds.outHeight + (long) sample - 1) / sample > edge
                    || ((bounds.outWidth + (long) sample - 1) / sample) * ((bounds.outHeight + (long) sample - 1) / sample) > MAX_DECODED_PIXELS) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888; options.inScaled = false;
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (decoded == null) return failed("corrupt_image", "이미지 내용을 읽을 수 없어.");
            if ((long) decoded.getWidth() * decoded.getHeight() > MAX_DECODED_PIXELS
                    || decoded.getWidth() > edge || decoded.getHeight() > edge) {
                decoded.recycle(); decoded = null; return failed("decode_limit_exceeded", "이미지 decoder가 크기 제한을 지키지 않았어.");
            }
            int orientation = ExifInterface.ORIENTATION_NORMAL; boolean exifKnown = true;
            try {
                ExifInterface exif = new ExifInterface(new ByteArrayInputStream(bytes));
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (IOException | RuntimeException unavailable) { exifKnown = false; }
            Matrix transform = orientationMatrix(orientation);
            if (!transform.isIdentity()) {
                Bitmap oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), transform, true);
                if (oriented != decoded) { decoded.recycle(); decoded = oriented; }
            }
            Result result = new Result(decoded, exifKnown ? "ready" : "ready_orientation_unknown", exifKnown ? "" : "회전 정보를 확인하지 못했어.");
            decoded = null; return result;
        } catch (LimitExceeded tooLarge) { return failed("encoded_too_large", "10 MiB를 넘는 이미지는 미리보기에서 제외해."); }
        catch (IOException unreadable) { return failed("source_unavailable", "첨부 원본을 확인하지 못했어."); }
        catch (RuntimeException corrupt) { return failed("corrupt_image", "이미지 내용을 읽을 수 없어."); }
        catch (OutOfMemoryError exhausted) { return failed("memory_unavailable", "이미지 미리보기에 사용할 메모리가 부족해."); }
        finally { if (decoded != null && !decoded.isRecycled()) decoded.recycle(); }
    }

    private static Matrix orientationMatrix(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            default: break;
        }
        return matrix;
    }
    private static Result failed(String status, String detail) { return new Result(null, status, detail); }
    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder text = new StringBuilder();
            for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 255)); return text.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static final class LimitExceeded extends IOException { private static final long serialVersionUID = 1; }
    private static final class BoundedBytes extends OutputStream {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        @Override public void write(int value) throws IOException { if (output.size() >= MAX_ENCODED_BYTES) throw new LimitExceeded(); output.write(value); }
        @Override public void write(byte[] bytes, int offset, int count) throws IOException { if ((long) output.size() + count > MAX_ENCODED_BYTES) throw new LimitExceeded(); output.write(bytes, offset, count); }
        byte[] bytes() { return output.toByteArray(); }
    }
}
