package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.ui.HoyoungAvatarView;
import com.hoyoung.assistant.ui.TypingIndicatorView;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native API35 UI evidence. All data is synthetic; no authenticated Work session. */
@RunWith(AndroidJUnit4.class)
public final class NativeChatUiDeviceTest {
    private Context context;
    private UiDevice device;

    @Before public void requireRealOfflineFullyPermittedFixture() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DevicePermissionFixture.assertReady(context);
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    @Test public void bundledOwnerPhotoActuallyRendersCircleAndSharesBoundedDecode() throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inScaled = false; options.inSampleSize = 4;
        Bitmap expected = BitmapFactory.decodeResource(context.getResources(), R.drawable.hoyoung_profile, options);
        assertNotNull("The owner-selected local profile asset must be present", expected);
        Bitmap[] rendered = {null};
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View avatar = findDescription(activity.getWindow().getDecorView(), "호영 프로필 사진");
                assertTrue("The live chat header must use the native profile view", avatar instanceof HoyoungAvatarView);
                assertTrue("The native header avatar is actually attached", avatar.isAttachedToWindow());
                Rect visible = new Rect(); assertTrue("The avatar must be visible on the actual display", avatar.getGlobalVisibleRect(visible));
                HoyoungAvatarView full = new HoyoungAvatarView(activity);
                full.measure(View.MeasureSpec.makeMeasureSpec(256, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(256, View.MeasureSpec.EXACTLY));
                full.layout(0, 0, 256, 256);
                rendered[0] = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
                full.draw(new Canvas(rendered[0]));
            });
            Bitmap result = rendered[0];
            assertNotNull(result); assertEquals("Circle corners must remain transparent", 0, Color.alpha(result.getPixel(0, 0)));
            assertEquals(255, Color.alpha(result.getPixel(128, 128)));
            int source = expected.getPixel(expected.getWidth() / 2, expected.getHeight() / 2), actual = result.getPixel(128, 128);
            assertTrue("The native avatar must display the bundled photo rather than its gradient fallback", colorDistance(source, actual) <= 48);
            Set<Integer> colors = new HashSet<>();
            for (int y = 72; y <= 184; y += 8) for (int x = 72; x <= 184; x += 8) colors.add(result.getPixel(x, y));
            assertTrue("The cat photo must retain native picture detail", colors.size() > 100);
            Field cached = HoyoungAvatarView.class.getDeclaredField("profile"); cached.setAccessible(true);
            Bitmap shared = (Bitmap) cached.get(null); assertNotNull(shared);
            assertTrue("Profile caching must remain a bounded local decode", Math.max(shared.getWidth(), shared.getHeight()) <= 512);
            assertFalse("Drawing a second avatar must not recycle the shared photo", shared.isRecycled());
            android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_DEVICE_PROFILE_UI local-cat-photo; native-circle-pixels; bounded-shared-decode=" + shared.getWidth() + "x" + shared.getHeight());
        } finally {
            expected.recycle(); if (rendered[0] != null) rendered[0].recycle();
        }
    }

    @Test public void nativeTypingFramesStopWhenHiddenDetachedBackgroundedAndReducedMotion() throws Exception {
        String previous = shell("settings get global animator_duration_scale").trim();
        assertEquals("The disposable AVD driver starts with reduced motion", 0f, Float.parseFloat(previous), .001f);
        TypingIndicatorView[] indicator = {null}; FrameLayout[] host = {null};
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            scenario.onActivity(activity -> {
                host[0] = new FrameLayout(activity); host[0].setContentDescription("native typing animation fixture");
                FrameLayout screen = (FrameLayout) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
                screen.addView(host[0], new FrameLayout.LayoutParams(180, 120, Gravity.TOP | Gravity.END));
                indicator[0] = new TypingIndicatorView(activity);
                host[0].addView(indicator[0], new FrameLayout.LayoutParams(160, 100)); indicator[0].setActive(true);
            });
            assertIndicator(scenario, indicator[0], true, false);
            shell("settings put global animator_duration_scale 1");
            awaitIndicator(scenario, indicator[0], true, true);
            long first = currentPlayTime(scenario, indicator[0]); SystemClock.sleep(180);
            long later = currentPlayTime(scenario, indicator[0]);
            assertTrue("Actual Android Choreographer frames must advance the running dots", later > first && later > 0);
            scenario.onActivity(activity -> host[0].setVisibility(View.INVISIBLE)); awaitIndicator(scenario, indicator[0], true, false);
            scenario.onActivity(activity -> host[0].setVisibility(View.VISIBLE)); awaitIndicator(scenario, indicator[0], true, true);
            scenario.moveToState(Lifecycle.State.CREATED); awaitIndicator(scenario, indicator[0], true, false);
            scenario.moveToState(Lifecycle.State.RESUMED); awaitIndicator(scenario, indicator[0], true, true);
            shell("settings put global animator_duration_scale 0"); awaitIndicator(scenario, indicator[0], true, false);
            scenario.onActivity(activity -> {
                assertEquals("Reduced motion must preserve an accessible typing state", View.ACCESSIBILITY_LIVE_REGION_POLITE, indicator[0].getAccessibilityLiveRegion());
                assertEquals("호영이 입력 중", indicator[0].getContentDescription());
                host[0].removeView(indicator[0]);
            });
            assertIndicator(scenario, indicator[0], true, false);
            shell("settings put global animator_duration_scale 1"); SystemClock.sleep(100);
            assertIndicator(scenario, indicator[0], true, false);
            scenario.onActivity(activity -> host[0].addView(indicator[0], new FrameLayout.LayoutParams(160, 100)));
            awaitIndicator(scenario, indicator[0], true, true);
            scenario.onActivity(activity -> indicator[0].setActive(false)); awaitIndicator(scenario, indicator[0], false, false);
            android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_DEVICE_TYPING_UI actual-native-frames=" + first + "->" + later
                    + "; hidden-detached-background-stopped; reduced-motion-static-accessible; no-Work-or-runtime-state-injection");
        } finally {
            scenario.onActivity(activity -> { if (indicator[0] != null) indicator[0].setActive(false); });
            scenario.close(); shell("settings put global animator_duration_scale " + previous);
            assertEquals("The test must restore the disposable AVD's original motion setting", 0f,
                    Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1), .001f);
        }
    }

    @Test public void actualSidebarOpensAllDestinationsAndSavesWritableOwnerInstructions() throws Exception {
        HoyoungStore store = ((HoyoungApplication) context.getApplicationContext()).getStore();
        String saved = store.getSetting("customInstructions", "");
        String fixture = "NATIVE_UI_OWNER_INSTRUCTIONS\n답장은 짧고 편하게, 중요한 원문은 정확하게.";
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            DeviceFixtureHealth.openPage(device, "설정");
            UiObject2 editor = DeviceFixtureHealth.waitFor(device, Until.findObject(By.desc("사용자 지침 편집").clazz(EditText.class)), 10_000);
            assertNotNull("The first settings card must expose its real writable instruction editor", editor);
            editor.setText(fixture);
            scenario.onActivity(activity -> {
                View focused = activity.getCurrentFocus();
                if (focused != null) {
                    InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
                    if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                    focused.clearFocus();
                }
            });
            device.waitForIdle(1_000); DeviceFixtureHealth.revealText(device, "설정 저장");
            UiObject2 save = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("설정 저장")), 10_000);
            assertNotNull(save); save.click();
            UiObject2 acknowledgement = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("확인")), 10_000);
            assertNotNull("The real native Save click must open its acknowledgement", acknowledgement); acknowledgement.click();
            assertEquals("The actual editor must persist the exact multiline user instruction", fixture, store.getSetting("customInstructions", ""));
            for (String destination : new String[]{"대화", "기억", "계정", "파일", "권한", "Work", "설정"}) DeviceFixtureHealth.openPage(device, destination);
            UiObject2 reopened = DeviceFixtureHealth.waitFor(device, Until.findObject(By.desc("사용자 지침 편집").clazz(EditText.class)), 10_000);
            assertNotNull(reopened); assertEquals(fixture, reopened.getText());
            DevicePermissionFixture.assertReady(context);
            android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_DEVICE_DRAWER_UI seven-native-destinations; real-editor-touch-save-reopen; full-permission-gate-ready; offline-no-authenticated-Work");
        } finally { store.putSetting("customInstructions", saved); }
    }

    private static int colorDistance(int expected, int actual) {
        return Math.abs(Color.red(expected) - Color.red(actual)) + Math.abs(Color.green(expected) - Color.green(actual)) + Math.abs(Color.blue(expected) - Color.blue(actual));
    }
    private static View findDescription(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) view).getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }
    private static void assertIndicator(ActivityScenario<MainActivity> scenario, TypingIndicatorView indicator, boolean active, boolean animated) {
        scenario.onActivity(activity -> { assertEquals(active, indicator.isActive()); assertEquals(animated, indicator.isAnimating()); });
    }
    private static void awaitIndicator(ActivityScenario<MainActivity> scenario, TypingIndicatorView indicator, boolean active, boolean animated) {
        long deadline = SystemClock.elapsedRealtime() + 5_000; boolean[] matched = {false};
        do {
            scenario.onActivity(activity -> matched[0] = indicator.isActive() == active && indicator.isAnimating() == animated);
            if (matched[0]) return; SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertIndicator(scenario, indicator, active, animated);
    }
    private static long currentPlayTime(ActivityScenario<MainActivity> scenario, TypingIndicatorView indicator) {
        long[] time = {-1}; scenario.onActivity(activity -> {
            try {
                Field field = TypingIndicatorView.class.getDeclaredField("animator"); field.setAccessible(true);
                ValueAnimator animator = (ValueAnimator) field.get(indicator); assertNotNull(animator); time[0] = animator.getCurrentPlayTime();
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }); return time[0];
    }
    private static String shell(String command) throws Exception {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            return new String(input.readNBytes(512), StandardCharsets.UTF_8);
        }
    }
}
