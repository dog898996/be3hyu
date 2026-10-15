package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.hoyoung.assistant.runtime.AssistantReplyNotifications;
import com.hoyoung.assistant.runtime.AutonomyService;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.runtime.OwnerDraftStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Independent real-Android exploration. No transport stub, cloud account, or semantic model needed. */
@RunWith(AndroidJUnit4.class)
public final class LifecycleExplorerDeviceTest {
    private Context context;
    private HoyoungApplication app;
    private UiDevice device;

    @Before public void requireDisposableOfflineAndroid() throws Exception {
        assertTrue("Disposable Android emulator only", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
        assertNull("Every experiment must remain offline", network.getActiveNetwork());
        assertEquals("No connected adapter may survive isolation", 0, network.getAllNetworks().length);
        app = (HoyoungApplication) context.getApplicationContext();
        assertTrue("Actual canonical storage must be ready", app.isStartupReady());
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp();
        device.executeShellCommand("wm dismiss-keyguard");
        device.executeShellCommand("cmd statusbar collapse");
    }

    @Test public void draftSurvivesScreenOffBackgroundRecreationAndActualReplyTap() throws Exception {
        DevicePermissionFixture.assertReady(context);
        String body = "EXPLORE_REPLY_" + UUID.randomUUID();
        String text = "임시 입력: 😀 한글\n두 번째 줄 " + UUID.randomUUID();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        long sourceId = 0;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            UiObject2 composer = composer();
            composer.setText(text);
            OwnerDraftStore.Snapshot saved = awaitDraft(text);
            device.pressHome();
            await("Background must clear actual conversation visibility", () -> !app.getReplyNotifications().state().optBoolean("conversationVisible"));
            device.sleep();
            assertFalse("The display must really turn off", context.getSystemService(PowerManager.class).isInteractive());
            JSONObject source = app.getStore().addMessage("assistant", body, null);
            sourceId = source.getLong("id");
            app.getReplyNotifications().publish(source, "completed");
            await("Screen-off Android reply notification", () -> active(source) != null);
            awaitReceipt(source, "posted");
            assertFalse("A posted reply is not a read receipt", app.getReplyNotifications().state().getBoolean("readConfirmed"));
            device.wakeUp();
            device.executeShellCommand("wm dismiss-keyguard");
            device.openNotification();
            UiObject2 notification = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text(body)), 20_000);
            assertNotNull("System UI must display the saved reply", notification);
            notification.click();
            assertTrue("A real notification tap must display its exact source", DeviceFixtureHealth.waitFor(device,
                    Until.hasObject(By.text(body).pkg(context.getPackageName())), 20_000));
            assertEquals(text, composer().getText());
            assertSameDraft(saved);
            awaitReceipt(source, "seen");
            scenario.recreate();
            assertEquals("Recreation must retain the unsent multiline Unicode text", text, composer().getText());
            assertSameDraft(saved);
            proof("screen-off-reply-tap-and-recreation", "exact-draft-text-id-revision; actual-SystemUI-tap; no-human-read-claim");
        } finally {
            device.wakeUp();
            if (sourceId > 0) manager.cancel("hoyoung_reply:" + sourceId, AssistantReplyNotifications.NOTIFICATION_ID);
        }
    }

    @Test public void dismissedNotificationIsNotReadAndDoesNotReplay() throws Exception {
        DevicePermissionFixture.assertReady(context);
        device.pressHome();
        await("Background conversation visibility", () -> !app.getReplyNotifications().state().optBoolean("conversationVisible"));
        String text = "EXPLORE_DISMISS_" + UUID.randomUUID();
        JSONObject source = app.getStore().addMessage("assistant", text, null);
        try {
            app.getReplyNotifications().publish(source, "completed");
            await("Real notification must post before dismissal", () -> active(source) != null);
            awaitReceipt(source, "posted");
            device.openNotification();
            UiObject2 row = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text(text)), 20_000);
            assertNotNull("The actual System UI notification row must be present", row);
            android.graphics.Rect bounds = row.getVisibleBounds();
            assertTrue(device.swipe(bounds.centerX(), bounds.centerY(), device.getDisplayWidth() - 2, bounds.centerY(), 12));
            await("Actual System UI swipe must dismiss the reply", () -> active(source) == null);
            JSONObject receipt = receipt(source);
            assertEquals("Dismissal cannot promote a notification to seen", "posted", receipt.getString("status"));
            assertFalse(receipt.optBoolean("readConfirmedByUi"));
            try (AssistantReplyNotifications fresh = new AssistantReplyNotifications(context, app.getStore(), Runnable::run)) {
                fresh.publish(source, "completed");
                assertNull("Recreating the worker must not replay a dismissed source", active(source));
                assertEquals("already_handled", fresh.state().getString("lastCode"));
                assertFalse(fresh.state().getBoolean("readConfirmed"));
            }
            proof("notification-dismiss", "real-SystemUI-swipe; durable-posted-receipt; fresh-worker-no-replay; no-read-promotion");
        } finally {
            context.getSystemService(NotificationManager.class).cancel("hoyoung_reply:" + source.getLong("id"), AssistantReplyNotifications.NOTIFICATION_ID);
            device.executeShellCommand("cmd statusbar collapse");
        }
    }

    @Test public void actualForegroundStopControlReleasesServiceAndPreservesDraft() throws Exception {
        DevicePermissionFixture.assertReady(context);
        String previousMode = app.getStore().getSetting("wakeMode", "continuous");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            String text = "EXPLORE_STOP_DRAFT " + UUID.randomUUID();
            composer().setText(text);
            OwnerDraftStore.Snapshot saved = awaitDraft(text);
            app.getStore().putSetting("wakeMode", "continuous");
            scenario.onActivity(activity -> app.getRuntime().startAutonomy());
            await("Android must report the actual foreground service", this::foreground);
            await("Continuous policy must actually hold a partial wake lock", () -> AutonomyService.resourceState().optBoolean("partialWakeLockHeld"));
            assertEquals("best_effort", AutonomyService.resourceState().getString("timing"));
            assertFalse(AutonomyService.resourceState().getBoolean("batteryCostMeasured"));
            device.pressHome();
            device.openNotification();
            UiObject2 stop = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("자동 실행 중지")), 20_000);
            if (stop == null) {
                UiObject2 title = device.findObject(By.textContains("자율 실행 중"));
                if (title != null) {
                    android.graphics.Rect bounds = title.getVisibleBounds();
                    device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), Math.min(device.getDisplayHeight() - 100, bounds.bottom + 300), 20);
                }
                stop = DeviceFixtureHealth.waitFor(device, Until.findObject(By.text("자동 실행 중지")), 10_000);
            }
            assertNotNull("The foreground notification must offer its actual stop control", stop);
            stop.click();
            await("OS foreground service must stop", () -> !foreground());
            await("Stop must persist the disabled owner preference", () -> !app.getRuntime().autonomyEnabled());
            await("Stop must release its wake lock", () -> !AutonomyService.resourceState().optBoolean("partialWakeLockHeld"));
            assertFalse(AutonomyService.resourceState().getBoolean("serviceRunning"));
            assertSameDraft(saved);
            proof("foreground-notification-stop", "actual-SystemUI-stop; persisted-disabled; OS-service-gone; wake-lock-released; draft-preserved");
        } finally {
            app.getRuntime().stopAutonomy();
            app.getStore().putSetting("wakeMode", previousMode);
            device.executeShellCommand("cmd statusbar collapse");
        }
    }

    /** External driver supplies genuine process death, force-stop, notification revoke, or reboot. */
    @Test public void externalProcessBoundaryPhase() throws Exception {
        String phase = InstrumentationRegistry.getArguments().getString("lifecyclePhase");
        org.junit.Assume.assumeTrue("Explicit externally driven disposable emulator phase", phase != null);
        File markerFile = new File(context.getFilesDir(), "lifecycle-explorer.json");
        if ("seed".equals(phase)) {
            DevicePermissionFixture.assertReady(context);
            String text = "EXPLORE_RESTART_DRAFT 한글 😀 " + UUID.randomUUID();
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                composer().setText(text);
                OwnerDraftStore.Snapshot saved = awaitDraft(text);
                String pendingId = "lifecycle-explorer-" + UUID.randomUUID();
                JSONObject pending = app.getStore().enqueueRequest("LOCAL_ONLY_PENDING " + pendingId, "user", pendingId);
                scenario.onActivity(activity -> app.getRuntime().startAutonomy());
                await("Seed foreground service", this::foreground);
                JSONObject marker = new JSONObject().put("text", saved.text).put("clientInputId", saved.clientInputId)
                        .put("revision", saved.revision).put("pendingId", pendingId).put("pendingText", pending.getString("text"))
                        .put("pid", Process.myPid()).put("bootCount", bootCount());
                Files.write(markerFile.toPath(), marker.toString().getBytes(StandardCharsets.UTF_8));
                device.pressHome();
            }
            proof("seed", "real-editor-draft; durable-pending-request; actual-foreground-service; pid=" + Process.myPid());
            return;
        }
        assertTrue("An earlier real process must have seeded the fixture", markerFile.isFile());
        JSONObject marker = new JSONObject(new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8));
        OwnerDraftStore.Snapshot saved = new OwnerDraftStore(app.getStore()).read();
        assertEquals(marker.getString("text"), saved.text);
        assertEquals(marker.getString("clientInputId"), saved.clientInputId);
        assertEquals(marker.getLong("revision"), saved.revision);
        assertFalse(saved.recoveryRequired);
        JSONObject pending = app.getStore().getRequestByExternalId(marker.getString("pendingId"));
        assertNotNull("An acknowledged pending request cannot disappear across a process boundary", pending);
        assertEquals(marker.getString("pendingText"), pending.getString("text"));
        assertNotEquals("Offline fixtures cannot manufacture cloud completion", "completed", pending.getString("status"));
        if ("notificationDenied".equals(phase)) {
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
            JSONObject source = app.getStore().addMessage("assistant", "EXPLORE_DENIED_" + UUID.randomUUID(), null);
            try (AssistantReplyNotifications fresh = new AssistantReplyNotifications(context, app.getStore(), Runnable::run)) {
                fresh.publish(source, "completed");
                assertNull(active(source));
                assertEquals("notification_permission_denied", fresh.state().getString("lastCode"));
                assertFalse(fresh.state().getBoolean("notificationPosted"));
            }
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                assertTrue(DeviceFixtureHealth.waitFor(device, Until.hasObject(By.text("시작할 준비")), 20_000));
                assertFalse("A missing required grant must not resume autonomy", foreground());
            }
        } else if ("forceStopped".equals(phase)) {
            assertNotEquals(marker.getInt("pid"), Process.myPid());
            DevicePermissionFixture.assertAccessibilityRevokedByForceStop(context);
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                assertTrue(DeviceFixtureHealth.waitFor(device, Until.hasObject(By.text("시작할 준비")), 20_000));
                assertFalse(foreground());
            }
        } else {
            DevicePermissionFixture.assertReady(context);
            if ("boot".equals(phase)) {
                assertTrue("The actual boot counter must advance", bootCount() > marker.getInt("bootCount"));
                assertTrue("BootReceiver service must exist before launching an Activity", foreground());
            } else if (!"permissionRestored".equals(phase)) assertNotEquals(marker.getInt("pid"), Process.myPid());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                assertEquals(marker.getString("text"), composer().getText());
                await("Opening with real required permissions resumes preference", this::foreground);
                device.pressHome();
            }
        }
        proof(phase, "draft-id-revision-and-pending-original-retained; request-status=" + pending.getString("status") + "; pid=" + Process.myPid() + "; bootCount=" + bootCount());
    }

    private UiObject2 composer() {
        UiObject2 value = DeviceFixtureHealth.waitFor(device, Until.findObject(By.clazz(EditText.class).desc("메시지 입력").pkg(context.getPackageName())), 20_000);
        assertNotNull("Actual native composer must be visible", value);
        return value;
    }
    private OwnerDraftStore.Snapshot awaitDraft(String text) {
        await("Actual owner edit must become durable", () -> text.equals(new OwnerDraftStore(app.getStore()).read().text));
        return new OwnerDraftStore(app.getStore()).read();
    }
    private void assertSameDraft(OwnerDraftStore.Snapshot expected) {
        OwnerDraftStore.Snapshot current = new OwnerDraftStore(app.getStore()).read();
        assertEquals(expected.text, current.text); assertEquals(expected.attachmentsJson, current.attachmentsJson);
        assertEquals(expected.clientInputId, current.clientInputId); assertEquals(expected.revision, current.revision); assertFalse(current.recoveryRequired);
    }
    private StatusBarNotification active(JSONObject source) {
        for (StatusBarNotification row : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if (row.getId() == AssistantReplyNotifications.NOTIFICATION_ID && source.optString("content_hash").equals(row.getNotification().extras.getString(AssistantReplyNotifications.EXTRA_SOURCE_HASH))) return row;
        return null;
    }
    private JSONObject receipt(JSONObject source) throws Exception {
        return new JSONObject(app.getStore().getSetting("replyNotification:" + source.getLong("id"), "{}"));
    }
    private void awaitReceipt(JSONObject source, String status) {
        await("Exact source receipt must reach " + status, () -> { try { JSONObject value = receipt(source); return status.equals(value.optString("status")) && source.optString("content_hash").equals(value.optString("sourceHash")); } catch (Exception invalid) { return false; } });
    }
    private boolean foreground() {
        for (ActivityManager.RunningServiceInfo row : context.getSystemService(ActivityManager.class).getRunningServices(100))
            if (AutonomyService.class.getName().equals(row.service.getClassName())) return row.foreground;
        return false;
    }
    private int bootCount() { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
    private static void await(String message, BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 20_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
        assertTrue(message, condition.getAsBoolean());
    }
    private void proof(String phase, String observations) {
        String value = "HOYOUNG_EXPLORER phase=" + phase + "; " + observations;
        android.util.Log.i("HoyoungDeviceProof", value);
        android.os.Bundle bundle = new android.os.Bundle(); bundle.putString("stream", value + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle);
        File directory = new File(context.getExternalFilesDir(null), "lifecycle-explorer");
        if (directory.isDirectory() || directory.mkdirs()) {
            device.takeScreenshot(new File(directory, phase + ".png"));
            try { device.dumpWindowHierarchy(new File(directory, phase + ".xml")); }
            catch (java.io.IOException ignored) { android.util.Log.w("HoyoungDeviceProof", "UI hierarchy capture unavailable for " + phase); }
        }
    }
}
