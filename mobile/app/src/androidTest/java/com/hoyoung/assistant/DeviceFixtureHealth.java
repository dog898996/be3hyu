package com.hoyoung.assistant;

import android.app.Activity;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.SearchCondition;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.util.function.Predicate;
import org.junit.Assert;

/** Fixture-only recovery for the known AOSP launcher dialog; never dismisses an app ANR. */
final class DeviceFixtureHealth {
    private static int launcherRecoveries;
    private static final String APP = "com.hoyoung.assistant";

    /** Click the actual drawer controls; never call a private navigation method. */
    static void openPage(UiDevice device, String page) {
        awaitForeground(device);
        clickSafely(device, view -> description(view).equals("메뉴 열기"), "sidebar-open");
        clickSafely(device, view -> description(view).startsWith(page + " · "), "sidebar-" + page);
        Assert.assertTrue("The sidebar must dismiss after real navigation", waitFor(device, Until.gone(By.desc("메뉴").pkg(APP)), 10_000));
        awaitForeground(device);
        String title = "대화".equals(page) ? "호영" : page;
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        boolean[] correctHeader = {false};
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Activity activity = resumedActivity();
                if (activity == null) return;
                View menu = find(activity.getWindow().getDecorView(), view -> description(view).equals("메뉴 열기"));
                if (menu != null && menu.getParent() instanceof View)
                    correctHeader[0] = find((View) menu.getParent(), view -> view instanceof TextView && title.contentEquals(((TextView) view).getText())) != null;
            });
            if (correctHeader[0]) break;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        Assert.assertTrue("Drawer navigation must display the actual app header for " + page, correctHeader[0]);
    }

    static void expandSection(UiDevice device, String section) throws Exception {
        awaitForeground(device);
        if (device.hasObject(By.desc(section + " 접기").pkg(APP))) return;
        clickSafely(device, view -> description(view).equals(section + " 펼치기"), "expand-" + section);
        Assert.assertTrue(waitFor(device, Until.hasObject(By.desc(section + " 접기").pkg(APP)), 10_000));
    }

    static void revealText(UiDevice device, String text) throws Exception {
        reveal(device, view -> view instanceof TextView && text.contentEquals(((TextView) view).getText()), "text-" + text);
    }

    static void revealDescription(UiDevice device, String description) {
        reveal(device, view -> description(view).equals(description), "description-" + description);
    }

    /** Observe the real resumed window. This neither launches nor privately navigates the app. */
    static void awaitForeground(UiDevice device) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        Geometry last;
        do {
            recoverKnownLauncherDialog(device);
            last = geometry(view -> false);
            if (last.focused && APP.equals(device.getCurrentPackageName())) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        proof("foreground-failed", last);
        Assert.fail("The real app window must be foreground and focused before touching its controls: " + last + "; uiPackage=" + device.getCurrentPackageName());
    }

    private static void clickSafely(UiDevice device, Predicate<View> match, String label) {
        Geometry target = reveal(device, match, label);
        proof("before-click-" + label, target);
        Assert.assertTrue("The actual control must receive a touch inside its fully visible native bounds", device.click(target.full.centerX(), target.full.centerY()));
        device.waitForIdle(1_000);
    }

    private static Geometry reveal(UiDevice device, Predicate<View> match, String label) {
        awaitForeground(device);
        Geometry current = null;
        long deadline = SystemClock.elapsedRealtime() + 45_000;
        int gestures = 0;
        do {
            current = geometry(match);
            if (current.focused && current.fullyVisible()) { proof("visible-" + label, current); return current; }
            if (!current.found) { SystemClock.sleep(50); continue; }
            Assert.assertTrue("The app must remain focused while revealing " + label + ": " + current, current.focused && APP.equals(device.getCurrentPackageName()));
            Assert.assertFalse("The actual control requires a native scroll viewport: " + label + ": " + current, current.viewport.isEmpty());
            Assert.assertTrue("The real native scroll must reach " + label + ": " + current, ++gestures <= 40);
            Rect viewport = current.viewport;
            int inset = Math.max(8, Math.min(viewport.width(), viewport.height()) / 10);
            int x = viewport.centerX(), top = viewport.top + inset, bottom = viewport.bottom - inset;
            Assert.assertTrue("A physical swipe must remain inside the app viewport: " + current, bottom > top);
            proof("before-scroll-" + label, current);
            boolean down = current.full.bottom > viewport.bottom;
            Assert.assertTrue("The actual viewport must accept a physical scroll", device.swipe(x, down ? bottom : top, x, down ? top : bottom, 24));
            device.waitForIdle(1_000);
        } while (SystemClock.elapsedRealtime() < deadline);
        proof("unreachable-" + label, current);
        throw new AssertionError("The actual native control must be fully visible inside its safe viewport: " + label + ": " + current);
    }

    private static Geometry geometry(Predicate<View> match) {
        Geometry[] result = {new Geometry()};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Activity activity = resumedActivity();
            if (activity == null) return;
            Geometry value = result[0]; value.focused = activity.hasWindowFocus();
            View decor = activity.getWindow().getDecorView(); value.safe = bounds(decor);
            WindowInsets windowInsets = decor.getRootWindowInsets();
            if (windowInsets != null) {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                value.safe.left += bars.left; value.safe.top += bars.top;
                value.safe.right -= bars.right; value.safe.bottom -= bars.bottom;
            }
            View target = find(decor, match);
            if (target == null) return;
            value.found = true; value.full = bounds(target); target.getGlobalVisibleRect(value.visible);
            boolean scrollAncestor = false;
            for (ViewParent parent = target.getParent(); parent instanceof View; parent = parent.getParent()) {
                if (parent instanceof ScrollView) {
                    scrollAncestor = true;
                    ((View) parent).getGlobalVisibleRect(value.viewport);
                    if (!value.viewport.intersect(value.safe)) value.viewport.setEmpty();
                    break;
                }
            }
            if (!scrollAncestor) value.viewport.set(value.safe);
        });
        return result[0];
    }

    private static Activity resumedActivity() {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (activity instanceof MainActivity) return activity;
        return null;
    }
    private static View find(View root, Predicate<View> match) {
        if (root.getVisibility() == View.GONE) return null;
        if (match.test(root)) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View child = find(((ViewGroup) root).getChildAt(i), match); if (child != null) return child;
        }
        return null;
    }
    private static String description(View view) { return view.getContentDescription() == null ? "" : view.getContentDescription().toString(); }
    private static Rect bounds(View view) {
        int[] position = new int[2]; view.getLocationOnScreen(position);
        return new Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight());
    }
    private static void proof(String phase, Geometry geometry) {
        String message = "HOYOUNG_FIXTURE_UI_GEOMETRY phase=" + phase + "; " + geometry;
        android.util.Log.i("HoyoungDeviceProof", message);
        Bundle status = new Bundle(); status.putString("stream", message + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
    private static final class Geometry {
        boolean focused, found;
        Rect full = new Rect(), visible = new Rect(), viewport = new Rect(), safe = new Rect();
        boolean fullyVisible() { return found && !full.isEmpty() && visible.contains(full) && viewport.contains(full) && safe.contains(full); }
        @Override public String toString() { return "focused=" + focused + "; found=" + found + "; full=" + full + "; visible=" + visible + "; viewport=" + viewport + "; safe=" + safe; }
    }

    static void recoverKnownLauncherDialog(UiDevice device) {
        UiObject2 title = device.findObject(By.text("Quickstep isn't responding").pkg("android"));
        if (title == null) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assert.assertTrue("Launcher recovery is only permitted on the disposable emulator",
                Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        Assert.assertNull("A real account or online phone must not be changed by fixture recovery",
                context.getSystemService(ConnectivityManager.class).getActiveNetwork());
        Assert.assertTrue("Repeated launcher ANRs invalidate this fixture rather than hiding an app failure", ++launcherRecoveries <= 2);
        // Search only the exact launcher's dialog subtree. A global button
        // lookup could close a different app's simultaneously visible dialog.
        UiObject2 close = null;
        UiObject2 panel = title.getParent();
        for (int depth = 0; panel != null && depth < 12 && close == null; depth++) {
            close = panel.findObject(By.text("Close app").pkg("android").clazz(android.widget.Button.class));
            panel = panel.getParent();
        }
        Assert.assertNotNull("The exact AOSP launcher dialog must expose its real Close app button", close);
        android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_FIXTURE_LAUNCHER_RECOVERY exact-Quickstep-ANR; native-close-button; own-app-ANR-never-dismissed; attempt=" + launcherRecoveries);
        close.click(); device.waitForIdle(1_000);
    }

    static <T> T waitFor(UiDevice device, SearchCondition<T> condition, long timeoutMillis) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        T result;
        do {
            recoverKnownLauncherDialog(device);
            result = device.wait(condition, Math.max(1, Math.min(500, deadline - SystemClock.elapsedRealtime())));
            if (result instanceof Boolean ? Boolean.TRUE.equals(result) : result != null) return result;
        } while (SystemClock.elapsedRealtime() < deadline);
        return result;
    }
}
