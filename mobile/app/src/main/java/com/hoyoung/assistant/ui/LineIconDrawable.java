package com.hoyoung.assistant.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Small, resolution-independent icons sharing a quiet, rounded 24-unit stroke. */
public final class LineIconDrawable extends Drawable {
    public enum Icon {
        MENU, BACK, CHEVRON, ATTACHMENT, IMAGE, FILE, SEND, MORE, STOP,
        SETTINGS, SHIELD, MEMORY, SPARK, FOLDER, WORK, CLOSE, CHECK,
        CAMERA, SEARCH, ADD, DOWNLOAD, EDIT, BELL, CLOCK, INFO, CHAT
    }

    private final Icon icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int size;

    public LineIconDrawable(Icon icon, int color, float density) {
        this.icon = icon;
        size = Math.max(1, Math.round(24 * density));
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float scale = Math.min(bounds.width(), bounds.height()) / 24f;
        if (scale <= 0) return;
        int save = canvas.save();
        canvas.translate(bounds.exactCenterX() - 12 * scale, bounds.exactCenterY() - 12 * scale);
        canvas.scale(scale, scale);
        path.reset();
        switch (icon) {
            case CHAT:
                path.moveTo(7, 3); path.lineTo(17, 3); path.quadTo(21, 3, 21, 7);
                path.lineTo(21, 14); path.quadTo(21, 18, 17, 18); path.lineTo(10, 18);
                path.lineTo(4, 22); path.lineTo(4.5f, 17); path.quadTo(3, 16, 3, 14);
                path.lineTo(3, 7); path.quadTo(3, 3, 7, 3); canvas.drawPath(path, paint);
                line(canvas, 7.5f, 8.5f, 16.5f, 8.5f); line(canvas, 7.5f, 12.5f, 13.5f, 12.5f); break;
            case MENU:
                line(canvas, 4, 6.5f, 20, 6.5f); line(canvas, 4, 12, 16, 12); line(canvas, 4, 17.5f, 20, 17.5f); break;
            case BACK:
                poly(canvas, 14.5f, 5, 7.5f, 12, 14.5f, 19); break;
            case CHEVRON:
                poly(canvas, 9, 5.5f, 15.5f, 12, 9, 18.5f); break;
            case ATTACHMENT:
                path.moveTo(9.2f, 13.7f); path.lineTo(15, 7.9f);
                path.cubicTo(17.5f, 5.4f, 20.7f, 8.6f, 18.2f, 11.1f);
                path.lineTo(10.2f, 19.1f); path.cubicTo(6.4f, 22.9f, .9f, 17.4f, 4.7f, 13.6f);
                path.lineTo(13.2f, 5.1f); path.cubicTo(18.4f, -.1f, 25.1f, 6.6f, 19.9f, 11.8f);
                canvas.drawPath(path, paint); break;
            case IMAGE:
                canvas.drawRoundRect(3, 3, 21, 21, 4, 4, paint);
                canvas.drawCircle(8, 8.2f, 1.4f, paint);
                poly(canvas, 4, 17, 9, 12, 13, 16, 16, 13, 20, 17); break;
            case FILE:
                path.moveTo(13.5f, 3); path.lineTo(6, 3); path.quadTo(4, 3, 4, 5);
                path.lineTo(4, 19); path.quadTo(4, 21, 6, 21); path.lineTo(18, 21);
                path.quadTo(20, 21, 20, 19); path.lineTo(20, 9.5f); path.close();
                canvas.drawPath(path, paint); poly(canvas, 13, 3.5f, 13, 10, 19.5f, 10);
                line(canvas, 8, 14, 16, 14); line(canvas, 8, 17.5f, 13, 17.5f); break;
            case SEND:
                path.moveTo(3, 10.3f); path.lineTo(20.5f, 3.5f); path.lineTo(14, 21);
                path.lineTo(10, 14); path.close(); canvas.drawPath(path, paint);
                line(canvas, 10, 14, 20, 4); break;
            case MORE:
                dot(canvas, 5, 12, 1.6f); dot(canvas, 12, 12, 1.6f); dot(canvas, 19, 12, 1.6f); break;
            case STOP:
                canvas.drawRoundRect(6, 6, 18, 18, 3, 3, paint); break;
            case SETTINGS:
                canvas.drawCircle(12, 12, 3.2f, paint);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * i / 4;
                    float x = (float) Math.cos(angle), y = (float) Math.sin(angle);
                    line(canvas, 12 + x * 7, 12 + y * 7, 12 + x * 9.5f, 12 + y * 9.5f);
                }
                canvas.drawCircle(12, 12, 7, paint); break;
            case SHIELD:
                path.moveTo(12, 2.5f); path.lineTo(20, 6); path.lineTo(20, 11);
                path.cubicTo(20, 16, 16.5f, 19.5f, 12, 22); path.cubicTo(7.5f, 19.5f, 4, 16, 4, 11);
                path.lineTo(4, 6); path.close(); canvas.drawPath(path, paint);
                poly(canvas, 8.5f, 11.5f, 11, 14, 15.5f, 9.5f); break;
            case MEMORY:
            case SPARK:
                spark(canvas, 11, 12, 8);
                if (icon == Icon.SPARK) spark(canvas, 20, 4, 2.3f);
                else { line(canvas, 20, 15.5f, 20, 20.5f); line(canvas, 17.5f, 18, 22.5f, 18); }
                break;
            case FOLDER:
                path.moveTo(3, 8); path.lineTo(3, 6); path.quadTo(3, 4, 5, 4);
                path.lineTo(10, 4); path.lineTo(12.5f, 7); path.lineTo(19, 7);
                path.quadTo(21, 7, 21, 9); path.lineTo(21, 18); path.quadTo(21, 20, 19, 20);
                path.lineTo(5, 20); path.quadTo(3, 20, 3, 18); path.close(); canvas.drawPath(path, paint);
                line(canvas, 3.5f, 10, 20.5f, 10); break;
            case WORK:
                canvas.drawRoundRect(3, 4, 21, 18, 3, 3, paint);
                poly(canvas, 7, 8, 10, 11, 7, 14); line(canvas, 13, 14, 17, 14);
                line(canvas, 8, 21, 16, 21); line(canvas, 12, 18, 12, 21); break;
            case CLOSE:
                line(canvas, 6, 6, 18, 18); line(canvas, 18, 6, 6, 18); break;
            case CHECK:
                poly(canvas, 4, 12, 9.5f, 17.5f, 20, 7); break;
            case CAMERA:
                path.moveTo(4.5f, 7); path.lineTo(7, 7); path.lineTo(9, 4); path.lineTo(15, 4);
                path.lineTo(17, 7); path.lineTo(19.5f, 7); path.quadTo(22, 7, 22, 9.5f);
                path.lineTo(22, 18); path.quadTo(22, 20, 19.5f, 20); path.lineTo(4.5f, 20);
                path.quadTo(2, 20, 2, 18); path.lineTo(2, 9.5f); path.quadTo(2, 7, 4.5f, 7);
                canvas.drawPath(path, paint); canvas.drawCircle(12, 13.5f, 3.7f, paint); break;
            case SEARCH:
                canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); line(canvas, 15.5f, 15.5f, 21, 21); break;
            case ADD:
                line(canvas, 5, 12, 19, 12); line(canvas, 12, 5, 12, 19); break;
            case DOWNLOAD:
                line(canvas, 12, 3, 12, 15); poly(canvas, 7.5f, 10.5f, 12, 15, 16.5f, 10.5f);
                poly(canvas, 4, 16, 4, 21, 20, 21, 20, 16); break;
            case EDIT:
                poly(canvas, 4, 16, 16.5f, 3.5f, 20.5f, 7.5f, 8, 20, 3, 21, 4, 16);
                line(canvas, 13.5f, 6.5f, 17.5f, 10.5f); break;
            case BELL:
                path.moveTo(5, 16); path.quadTo(7, 14, 7, 10); path.cubicTo(7, 3, 17, 3, 17, 10);
                path.quadTo(17, 14, 19, 16); path.lineTo(19, 17.5f); path.lineTo(5, 17.5f); path.close();
                canvas.drawPath(path, paint); canvas.drawArc(9, 18, 15, 23, 20, 140, false, paint);
                line(canvas, 12, 2.5f, 12, 4); break;
            case CLOCK:
                canvas.drawCircle(12, 12, 9, paint); poly(canvas, 12, 6.5f, 12, 12, 16, 14.5f); break;
            case INFO:
                canvas.drawCircle(12, 12, 9, paint); dot(canvas, 12, 7.5f, 1);
                line(canvas, 12, 11, 12, 17); break;
        }
        canvas.restoreToCount(save);
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2) { canvas.drawLine(x1, y1, x2, y2, paint); }
    private void poly(Canvas canvas, float... points) {
        path.reset(); path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        canvas.drawPath(path, paint);
    }
    private void dot(Canvas canvas, float x, float y, float radius) {
        paint.setStyle(Paint.Style.FILL); canvas.drawCircle(x, y, radius, paint); paint.setStyle(Paint.Style.STROKE);
    }
    private void spark(Canvas canvas, float x, float y, float radius) {
        path.reset(); path.moveTo(x, y - radius);
        path.quadTo(x + radius * .22f, y - radius * .22f, x + radius, y);
        path.quadTo(x + radius * .22f, y + radius * .22f, x, y + radius);
        path.quadTo(x - radius * .22f, y + radius * .22f, x - radius, y);
        path.quadTo(x - radius * .22f, y - radius * .22f, x, y - radius);
        path.close(); canvas.drawPath(path, paint);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public int getAlpha() { return paint.getAlpha(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
}
