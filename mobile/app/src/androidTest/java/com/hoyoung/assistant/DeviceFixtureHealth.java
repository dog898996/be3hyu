package com.hoyoung.assistant;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.SearchCondition;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import org.junit.Assert;

/** Fixture-only recovery for the known AOSP launcher dialog; never dismisses an app ANR. */
final class DeviceFixtureHealth {
    private static int launcherRecoveries;

    /** Click the actual drawer controls; never call a private navigation method. */
    static void openPage(UiDevice device, String page) {
        UiObject2 menu = waitFor(device, Until.findObject(By.desc("메뉴 열기")), 15_000);
        Assert.assertNotNull("The real app must expose its sidebar button", menu); menu.click();
        UiObject2 destination = waitFor(device, Until.findObject(By.descStartsWith(page + " · ")), 10_000);
        Assert.assertNotNull("The actual sidebar must contain " + page, destination); destination.click();
        Assert.assertTrue("The sidebar must dismiss after real navigation", waitFor(device, Until.gone(By.desc("메뉴")), 10_000));
    }

    static void expandSection(UiDevice device, String section) throws Exception {
        if (device.hasObject(By.desc(section + " 접기"))) return;
        UiScrollable scroll = new UiScrollable(new UiSelector().className(android.widget.ScrollView.class.getName()).scrollable(true));
        Assert.assertTrue("The real settings section must be reachable", scroll.scrollIntoView(new UiSelector().description(section + " 펼치기")));
        UiObject2 heading = waitFor(device, Until.findObject(By.desc(section + " 펼치기")), 10_000);
        Assert.assertNotNull(heading); heading.click();
        Assert.assertTrue(waitFor(device, Until.hasObject(By.desc(section + " 접기")), 10_000));
    }

    static void revealText(UiDevice device, String text) throws Exception {
        Assert.assertTrue("The real scrollable control must be reachable: " + text,
                new UiScrollable(new UiSelector().className(android.widget.ScrollView.class.getName()).scrollable(true)).scrollTextIntoView(text));
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
