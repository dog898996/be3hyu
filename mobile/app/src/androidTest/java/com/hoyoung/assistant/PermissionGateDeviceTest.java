package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.hoyoung.assistant.phone.PermissionOnboarding;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Starts before the shell driver grants permissions. No production gate override. */
@RunWith(AndroidJUnit4.class)
public final class PermissionGateDeviceTest {
    @Test public void actualMissingOsGrantBlocksConversationAndSidebar() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Only the driver's fresh disposable AVD may be tested", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        assertEquals(35, Build.VERSION.SDK_INT);
        assertNull("The permission test must never access a real account", context.getSystemService(ConnectivityManager.class).getActiveNetwork());
        assertEquals("The driver must establish actual OS denial before this test", PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.READ_CONTACTS));
        PermissionOnboarding.Snapshot snapshot = new PermissionOnboarding(context).snapshot();
        assertFalse(snapshot.ready());
        assertFalse(snapshot.pending().isEmpty());
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            UiObject2 start = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("호영과 대화 시작")), 10_000);
            // The complete permission list can be longer than the real display.
            if (start == null) {
                try { DeviceFixtureHealth.revealText(device, "호영과 대화 시작"); }
                catch (Exception failure) { throw new AssertionError("The blocked start control must remain reachable", failure); }
                start = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("호영과 대화 시작")), 10_000);
            }
            assertNotNull("The actual setup screen must expose its disabled start control", start);
            assertFalse("No permission skip button is permitted", start.isEnabled());
            scenario.onActivity(activity -> {
                assertNull("A blocked setup screen must not expose a composer", findDescription(activity.getWindow().getDecorView(), "메시지 입력"));
                assertNull("No owner send control is available before permission completion", findDescription(activity.getWindow().getDecorView(), "메시지 보내기"));
            });
            UiObject2 menu = DeviceFixtureHealth.waitFor(device, Until.findObject(By.desc("메뉴 열기")), 10_000);
            assertNotNull(menu); menu.click();
            assertFalse("The sidebar cannot provide a route around setup", device.hasObject(By.descStartsWith("설정 · ")));
            scenario.onActivity(activity -> assertNull(findDescription(activity.getWindow().getDecorView(), "메시지 입력")));
            assertEquals("Checking the UI must not silently grant the OS permission", PackageManager.PERMISSION_DENIED,
                    context.checkSelfPermission(Manifest.permission.READ_CONTACTS));
            android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_DEVICE_PERMISSION_BLOCK actual-OS-denial; disabled-start; no-composer-or-sidebar-bypass; no-grant-mutation");
        }
    }

    private static View findDescription(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description); if (found != null) return found;
            }
        }
        return null;
    }
}
