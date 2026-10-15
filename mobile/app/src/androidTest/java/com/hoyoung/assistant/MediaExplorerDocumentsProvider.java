package com.hoyoung.assistant;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test APK only. Real SAF binder/URI grants with synthetic, deterministic local files. */
public final class MediaExplorerDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = "com.hoyoung.assistant.test.media";
    static final String ROOT_TITLE = "Hoyoung media explorer";
    private final Map<String, String> types = new LinkedHashMap<>();
    private File root;

    @Override public boolean onCreate() {
        root = new File(getContext().getFilesDir(), "media-explorer");
        if (!root.isDirectory() && !root.mkdirs()) return false;
        try {
            Bitmap photo = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
            photo.eraseColor(Color.MAGENTA);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            photo.compress(Bitmap.CompressFormat.PNG, 100, png); photo.recycle();
            add("01-photo.png", "image/png", png.toByteArray());
            add("02-audio.wav", "audio/wav", wav());
            try (java.io.InputStream clip = getContext().getAssets().open("media-explorer/03-clip.mp4")) {
                add("03-clip.mp4", "video/mp4", clip.readAllBytes());
            }
            add("04-document.pdf", "application/pdf", ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                    + "2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n").getBytes(StandardCharsets.UTF_8));
            add("05-unknown.dat", "application/octet-stream", new byte[]{0, 1, 2, 3, (byte) 255, 0, 17});
            add("06-empty.jpg", "image/jpeg", new byte[0]);
            add("07-unreadable.txt", "text/plain", "Open deliberately fails in the synthetic provider".getBytes(StandardCharsets.UTF_8));
            add("08-slow.txt", "text/plain", "Slow provider stream completed intact".getBytes(StandardCharsets.UTF_8));
            add("09-extra.txt", "text/plain", "Ninth distinct local attachment".getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private void add(String name, String type, byte[] bytes) throws IOException {
        types.put(name, type);
        try (FileOutputStream output = new FileOutputStream(new File(root, name))) { output.write(bytes); }
    }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES} : projection;
        MatrixCursor cursor = new MatrixCursor(columns); MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) row.add(column, switch (column) {
            case DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID -> "root";
            case DocumentsContract.Root.COLUMN_TITLE -> ROOT_TITLE;
            case DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_LOCAL_ONLY;
            case DocumentsContract.Root.COLUMN_MIME_TYPES -> "*/*";
            default -> null;
        });
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        require(id); MatrixCursor cursor = cursor(projection); addRow(cursor, id); return cursor;
    }

    @Override public Cursor queryChildDocuments(String id, String[] projection, String order) throws FileNotFoundException {
        if (!"root".equals(id)) throw new FileNotFoundException(id);
        MatrixCursor cursor = cursor(projection);
        for (String name : types.keySet()) addRow(cursor, name);
        return cursor;
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        require(id);
        if (!"r".equals(mode) || "root".equals(id)) throw new FileNotFoundException("Read-only local fixture");
        if ("07-unreadable.txt".equals(id)) throw new FileNotFoundException("Synthetic document revoked before open");
        if ("08-slow.txt".equals(id)) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> {
                    SystemClock.sleep(8_000);
                    try (java.io.OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        output.write("Slow provider stream completed intact".getBytes(StandardCharsets.UTF_8));
                    } catch (IOException cancelled) { /* A consumer may cancel this deliberately slow source. */ }
                }, "media-explorer-slow-document").start();
                return pipe[0];
            } catch (IOException unavailable) { throw new FileNotFoundException(unavailable.toString()); }
        }
        return ParcelFileDescriptor.open(new File(root, id), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void require(String id) throws FileNotFoundException {
        if (!"root".equals(id) && !types.containsKey(id)) throw new FileNotFoundException(id);
    }
    private static MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection == null ? new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE} : projection);
    }
    private void addRow(MatrixCursor cursor, String id) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) row.add(column, switch (column) {
            case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id;
            case DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "root".equals(id) ? ROOT_TITLE : id;
            case DocumentsContract.Document.COLUMN_MIME_TYPE -> "root".equals(id) ? DocumentsContract.Document.MIME_TYPE_DIR : types.get(id);
            case DocumentsContract.Document.COLUMN_FLAGS -> 0;
            case DocumentsContract.Document.COLUMN_SIZE -> "root".equals(id) ? null : new File(root, id).length();
            default -> null;
        });
    }
    private static byte[] wav() {
        int length = 1600;
        ByteBuffer buffer = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + length);
        buffer.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        buffer.putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(length);
        while (buffer.remaining() >= 2) buffer.putShort((short) 0);
        return buffer.array();
    }
}
