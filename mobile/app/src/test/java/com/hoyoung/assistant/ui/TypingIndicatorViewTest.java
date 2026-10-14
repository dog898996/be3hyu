package com.hoyoung.assistant.ui;

import android.app.Activity;
import android.app.Application;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TypingIndicatorViewTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout container;
    private TypingIndicatorView indicator;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1);
        container = new LinearLayout(activity); activity.setContentView(container);
        indicator = new TypingIndicatorView(activity); container.addView(indicator);
        controller.visible();
        windowVisible(true);
        frame();
        assertEquals("Fixture must deliver the window-manager app-visibility event", View.VISIBLE, indicator.getWindowVisibility());
        // After the window's initial attachment, advance animation frames only
        // with the test clock. Robolectric otherwise completes every future
        // frame immediately and turns INFINITE repeat into two finite cycles.
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
    }

    @After public void tearDown() {
        indicator.setActive(false);
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1);
        controller.pause().stop().destroy();
    }

    @Test public void actualReplyStartsDotsAndTerminalStateStopsThem() {
        assertFalse(indicator.isAnimating());
        indicator.setActive(true); frame(); assertTrue(state(), indicator.isAnimating());
        indicator.setActive(false); frame(); assertFalse(indicator.isAnimating());
        assertEquals("호영이 입력 중", indicator.getContentDescription());
    }

    @Test public void removingVisibleTypingViewReleasesAnimatorAndReattachmentRestartsIt() {
        indicator.setActive(true); frame(); assertTrue(indicator.isAnimating());
        container.removeView(indicator); frame(); assertFalse(indicator.isAnimating());
        assertTrue("A screen change is not a reply completion", indicator.isActive());
        container.addView(indicator); frame(); assertTrue(indicator.isAnimating());
    }

    @Test public void hiddenTabAndHiddenParentConsumeNoAnimationFrames() {
        indicator.setActive(true); frame(); assertTrue(indicator.isAnimating());
        indicator.setVisibility(View.GONE); frame(); assertFalse(indicator.isAnimating());
        indicator.setVisibility(View.VISIBLE); frame(); assertTrue(indicator.isAnimating());
        container.setVisibility(View.INVISIBLE); frame(); assertFalse(indicator.isAnimating());
        container.setVisibility(View.VISIBLE); frame(); assertTrue(indicator.isAnimating());
    }

    @Test public void backgroundWindowStopsAnimationAndForegroundWindowRestartsIt() {
        indicator.setActive(true); frame(); assertTrue(indicator.isAnimating());
        windowVisible(false); frame(); assertFalse(indicator.isAnimating());
        windowVisible(true); frame(); assertTrue(indicator.isAnimating());
    }

    @Test public void reducedMotionWhileScreenOpenStopsDotsAndLaterRestartsOnlyIfStillActive() {
        indicator.setActive(true); frame(); assertTrue(indicator.isAnimating());
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0); frame();
        assertFalse(indicator.isAnimating());
        assertTrue("Reduced motion must not erase the reply state", indicator.isActive());
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1); frame();
        assertTrue(indicator.isAnimating());
        indicator.setActive(false);
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0); frame();
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1); frame();
        assertFalse(indicator.isAnimating());
    }

    @Test public void alreadyReducedMotionShowsAccessibleStaticTypingWithoutAnimator() {
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0);
        indicator.setActive(true); frame(); assertFalse(indicator.isAnimating());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, indicator.getAccessibilityLiveRegion());
        container.removeView(indicator);
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1); frame();
        assertFalse("Detached dots cannot restart after a settings change", indicator.isAnimating());
    }

    private void frame() { shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); }
    private void windowVisible(boolean visible) {
        // API 28's Robolectric window manager attaches the view but does not send
        // the real OS app-visibility callback; its window otherwise stays GONE.
        Object viewRoot = ReflectionHelpers.callInstanceMethod(activity.getWindow().getDecorView(), "getViewRootImpl");
        ReflectionHelpers.callInstanceMethod(viewRoot, "handleAppVisibility", ReflectionHelpers.ClassParameter.from(boolean.class, visible));
    }
    private String state() {
        android.animation.ValueAnimator animator = ReflectionHelpers.getField(indicator, "animator");
        return "attached=" + indicator.isAttachedToWindow() + ", shown=" + indicator.isShown()
                + ", window=" + indicator.getWindowVisibility() + ", animators=" + ChatPalette.animationsEnabled()
                + ", durationScale=" + Settings.Global.getFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1)
                + ", active=" + indicator.isActive() + ", animator=" + (animator == null ? "null" : "started=" + animator.isStarted() + ", play=" + animator.getCurrentPlayTime() + ", value=" + animator.getAnimatedValue())
                + ", frameDelay=" + ShadowChoreographer.getFrameDelay();
    }
}
