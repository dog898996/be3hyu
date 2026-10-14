package com.hoyoung.assistant.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Three softly rising dots. Animation belongs to an attached, visible view only. */
public final class TypingIndicatorView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private ValueAnimator animator;
    private float phase;
    private boolean active;
    private boolean observing;
    private int dotColor = ChatPalette.MUTED;
    private final ContentObserver motionObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { updateAnimation(); }
    };

    public TypingIndicatorView(Context context) {
        super(context); density = context.getResources().getDisplayMetrics().density;
        setContentDescription("호영이 입력 중");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        setBackground(ChatPalette.rounded(context, Color.WHITE, 20));
        setMinimumWidth(ChatPalette.dp(context, 62)); setMinimumHeight(ChatPalette.dp(context, 36));
    }

    /** Set true while a reply is actually being prepared, false on completion/cancel/failure. */
    public void setActive(boolean value) { active = value; updateAnimation(); }
    public boolean isActive() { return active; }
    public boolean isAnimating() { return animator != null && animator.isRunning(); }
    public void setDotColor(int color) { dotColor = color; invalidate(); }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec), resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            getContext().getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver);
            observing = true;
        } catch (RuntimeException unavailable) { observing = false; }
        updateAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        stopAnimation();
        if (observing) { getContext().getContentResolver().unregisterContentObserver(motionObserver); observing = false; }
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); updateAnimation(); }
    @Override protected void onVisibilityChanged(View changedView, int visibility) { super.onVisibilityChanged(changedView, visibility); updateAnimation(); }
    @Override public void onVisibilityAggregated(boolean isVisible) { super.onVisibilityAggregated(isVisible); updateAnimation(); }

    private void updateAnimation() {
        boolean enabled = active && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE && motionEnabled();
        if (!enabled) { stopAnimation(); return; }
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0, 1); animator.setDuration(1200);
        animator.setRepeatCount(ValueAnimator.INFINITE); animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(frame -> { phase = (float) frame.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    private boolean motionEnabled() {
        // Query the setting too: accessibility may change it while this screen stays attached.
        try {
            if (Settings.Global.getFloat(getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return false;
        } catch (RuntimeException unavailable) { /* The platform animator still honors its duration scale. */ }
        return ChatPalette.animationsEnabled();
    }

    private void stopAnimation() {
        if (animator != null) { ValueAnimator previous = animator; animator = null; previous.removeAllUpdateListeners(); previous.cancel(); }
        phase = 0; invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float center = getWidth() / 2f;
        for (int i = 0; i < 3; i++) {
            double wave = Math.sin((phase - i * .13f) * 2 * Math.PI);
            float lift = isAnimating() ? Math.max(0, (float) wave) : 0;
            paint.setColor(dotColor); paint.setAlpha(isAnimating() ? Math.round(150 + 105 * lift) : 210);
            canvas.drawCircle(center + (i - 1) * 10 * density, getHeight() / 2f - lift * 2.7f * density, 3 * density, paint);
        }
    }
}
