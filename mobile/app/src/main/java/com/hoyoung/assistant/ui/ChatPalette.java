package com.hoyoung.assistant.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;

/** Shared native-chat tokens. No web content, external assets, or additional dependency. */
public final class ChatPalette {
    public static final int BG = Color.rgb(250, 249, 247);
    public static final int SURFACE = Color.WHITE;
    public static final int INK = Color.rgb(34, 32, 43);
    public static final int MUTED = Color.rgb(117, 115, 128);
    public static final int ACCENT = Color.rgb(94, 74, 228);
    public static final int ACCENT_END = Color.rgb(143, 86, 238);
    public static final int BORDER = Color.rgb(232, 230, 237);
    public static final int SOFT_ACCENT = Color.rgb(239, 235, 255);
    private ChatPalette() { }

    public static int dp(Context context, float value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }

    public static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp)); return drawable;
    }

    public static GradientDrawable outlined(Context context, int color, int strokeColor, float radiusDp) {
        GradientDrawable drawable = rounded(context, color, radiusDp);
        drawable.setStroke(Math.max(1, dp(context, 1)), strokeColor); return drawable;
    }

    public static GradientDrawable outgoing(Context context) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{ACCENT, ACCENT_END});
        drawable.setCornerRadius(dp(context, 23)); return drawable;
    }

    public static RippleDrawable ripple(Context context, int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x145E4AE4), rounded(context, color, radiusDp), rounded(context, Color.WHITE, radiusDp));
    }

    /** 48dp touch target; the visible icon remains a light 24dp shape. */
    public static ImageButton iconButton(Context context, LineIconDrawable.Icon icon, String description, int tint) {
        ImageButton button = new ImageButton(context);
        button.setImageDrawable(new LineIconDrawable(icon, tint, context.getResources().getDisplayMetrics().density));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int padding = dp(context, 12); button.setPadding(padding, padding, padding, padding);
        button.setBackground(ripple(context, Color.TRANSPARENT, 24));
        button.setContentDescription(description); button.setTooltipText(description);
        button.setLayoutParams(new ViewGroup.LayoutParams(dp(context, 48), dp(context, 48)));
        return button;
    }

    public static boolean animationsEnabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    /** For a newly inserted bubble only; do not replay when reconstructing chat history. */
    public static void enter(View view, long delayMs) {
        view.animate().cancel();
        if (!animationsEnabled()) { resetMotion(view); return; }
        view.setAlpha(0); view.setTranslationY(dp(view.getContext(), 12));
        view.setScaleX(.97f); view.setScaleY(.97f);
        view.animate().alpha(1).translationY(0).scaleX(1).scaleY(1)
                .setStartDelay(Math.max(0, delayMs)).setDuration(230)
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();
    }

    public static void resetMotion(View view) {
        view.animate().cancel(); view.setAlpha(1); view.setTranslationY(0); view.setScaleX(1); view.setScaleY(1);
    }
}
