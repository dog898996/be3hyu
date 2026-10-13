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
import org.junit.Assert;

/** Fixture-only recovery for the known AOSP launcher dialog; never dismisses an app ANR. */
final class DeviceFixtureHealth {
    private static int launcherRecoveries;

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
