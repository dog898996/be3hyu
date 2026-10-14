package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.hoyoung.assistant.runtime.AutonomyService;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.runtime.OwnerDraftStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Selected by the offline AVD driver only; real stop/death/crash/reboot happen outside instrumentation. */
@RunWith(AndroidJUnit4.class)
public final class DeviceRestartIntegrationTest {
    @Test public void selectedRealProcessRecoveryPhase() throws Exception {
        String phase = InstrumentationRegistry.getArguments().getString("restartPhase");
        Assume.assumeTrue("Explicit disposable-emulator recovery phases only", phase != null);
        assertTrue("Never mutate a physical phone", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNull("Every recovery phase must remain offline", context.getSystemService(ConnectivityManager.class).getActiveNetwork());
        HoyoungApplication app = (HoyoungApplication) context.getApplicationContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard"); device.waitForIdle(1_000);
        File markerFile = new File(context.getFilesDir(), "device-restart-fixture.json");
        if ("seed".equals(phase)) {
            String draft = "DEVICE_RESTART_DRAFT " + UUID.randomUUID() + " 😀 한글";
            try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
                UiObject2 composer = DeviceFixtureHealth.waitFor(device, Until.findObject(By.clazz(EditText.class).pkg(context.getPackageName())), 15_000);
                assertNotNull("The real native conversation must expose its composer", composer); composer.setText(draft);
                OwnerDraftStore.Snapshot snapshot = awaitDraft(app, draft);
                activity.onActivity(actual -> {
                    android.view.View focused = actual.getCurrentFocus();
                    if (focused != null) {
                        android.view.inputmethod.InputMethodManager keyboard = actual.getSystemService(android.view.inputmethod.InputMethodManager.class);
                        if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                        focused.clearFocus();
                    }
                });
                device.waitForIdle(1_000);
                UiObject2 settings = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("설정")), 10_000); assertNotNull(settings); settings.click();
                UiObject2 enable = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("자율 모드 켜기")), 10_000); assertNotNull(enable); enable.click();
                awaitForeground(context, 15_000);
                JSONObject marker = new JSONObject().put("draft", draft).put("clientInputId", snapshot.clientInputId)
                        .put("revision", snapshot.revision).put("seedPid", Process.myPid())
                        .put("bootCount", Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1));
                Files.write(markerFile.toPath(), marker.toString().getBytes(StandardCharsets.UTF_8));
                device.pressHome();
            }
            assertTrue("Closing the Activity must retain its foreground service", foreground(context));
            android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_RESTART_SEEDED actual-composer-draft; source-id-and-revision-committed; background-foreground-service");
            return;
        }
        assertTrue("A fresh real process must preserve the recovery fixture", markerFile.isFile());
        JSONObject marker = new JSONObject(new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8));
        OwnerDraftStore.Snapshot draft = new OwnerDraftStore(app.getStore()).read();
        assertEquals(marker.getString("draft"), draft.text); assertEquals(marker.getString("clientInputId"), draft.clientInputId);
        assertEquals(marker.getLong("revision"), draft.revision); assertFalse(draft.recoveryRequired);
        if ("boot".equals(phase)) {
            assertTrue("The driver must observe BootReceiver's real service before launching a test Activity", foreground(context));
            int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            assertTrue("An actual Android reboot must advance its boot counter", boot > marker.getInt("bootCount"));
        } else {
            assertTrue("Force-stop, process death or crash must produce another process", Process.myPid() != marker.getInt("seedPid"));
        }
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            assertTrue("The native composer must load the exact durable unsent draft", DeviceFixtureHealth.waitFor(device, Until.hasObject(
                    By.text(marker.getString("draft")).clazz(EditText.class).pkg(context.getPackageName())), 15_000));
            awaitForeground(context, 15_000);
            app.getSemanticEncoder().prepareAsync();
            long started = SystemClock.elapsedRealtime(), deadline = started + 30_000;
            while (!app.getSemanticEncoder().isReady() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertTrue("The same verified model files must reload in a real process without network", app.getSemanticEncoder().isReady());
            float[] actual = app.getSemanticEncoder().encode("내 대화와 기억을 이어서 불러와.");
            assertEquals(512, actual.length); double norm = 0;
            for (float value : actual) { assertTrue(Float.isFinite(value)); norm += value * value; }
            assertEquals(1.0, norm, 0.002);
            android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_RESTART_RECOVERED phase=" + phase
                    + "; actual-native-draft-and-identity; foreground-service; offline-model-ready-ms="
                    + (SystemClock.elapsedRealtime() - started) + "; actual-512D-unit-vector; no-24-hour-or-S24-claim");
            device.pressHome();
        } finally { if ("boot".equals(phase)) app.getRuntime().stopAutonomy(); }
    }

    private static OwnerDraftStore.Snapshot awaitDraft(HoyoungApplication app, String text) {
        long end = SystemClock.elapsedRealtime() + 10_000;
        OwnerDraftStore.Snapshot snapshot;
        do { snapshot = new OwnerDraftStore(app.getStore()).read(); if (text.equals(snapshot.text)) return snapshot; SystemClock.sleep(50); }
        while (SystemClock.elapsedRealtime() < end);
        fail("The actual owner edit was not durably saved"); return snapshot;
    }
    private static boolean foreground(Context context) {
        for (ActivityManager.RunningServiceInfo item : context.getSystemService(ActivityManager.class).getRunningServices(100))
            if (AutonomyService.class.getName().equals(item.service.getClassName())) return item.foreground;
        return false;
    }
    private static void awaitForeground(Context context, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!foreground(context) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50);
        assertTrue("Android must report an actual foreground AutonomyService", foreground(context));
    }
}
