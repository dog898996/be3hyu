package com.hoyoung.assistant.phone;

import android.content.Intent;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import com.hoyoung.assistant.runtime.AutonomyService;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Synthetic lifecycle and battery broadcasts only; no actual phone or model access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = HoyoungApplication.class)
public final class AutonomyResourceModeTest {
    private HoyoungApplication app; private ServiceController<AutonomyService> controller; private AutonomyService service;
    @Before public void setup() {
        app = (HoyoungApplication) RuntimeEnvironment.getApplication();
        app.getStore().putSetting("semanticSearchEnabled", "false"); app.getStore().putSetting("phoneContextEnabled", "false"); app.getStore().putSetting("autonomy.enabled", "true");
        app.sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 0));
    }
    @After public void cleanup() { if (controller != null) controller.destroy(); drainLocal(); }
    private void start(String mode) {
        app.getStore().putSetting("wakeMode", mode); controller = Robolectric.buildService(AutonomyService.class).create(); service = controller.get();
        service.onStartCommand(new Intent(app, AutonomyService.class), 0, 1); drainLocal();
    }
    private void drainLocal() {
        Handler worker = ReflectionHelpers.getField(app.getPhoneContext(), "worker");
        for (int i = 0; i < 3; i++) { Shadows.shadowOf(worker.getLooper()).idle(); Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    }
    @Test public void continuousModeKeepsItsBoundedLeaseWhileIdleAndReleasesOnDestroy() throws Exception {
        start("continuous"); assertTrue(AutonomyService.resourceState().getBoolean("partialWakeLockHeld"));
        assertFalse(AutonomyService.resourceState().getBoolean("batteryCostMeasured")); assertEquals("best_effort", AutonomyService.resourceState().getString("timing"));
        PowerManager.WakeLock lease = Shadows.shadowOf(app).getLatestWakeLock(); assertNotNull(lease); assertTrue(lease.isHeld());
        controller.destroy(); controller = null; assertFalse(lease.isHeld()); assertFalse(AutonomyService.resourceState().getBoolean("serviceRunning"));
    }
    @Test public void whileWorkingModeReleasesIdleLeaseAndOwnerModeChangesTakeEffectWithoutRestart() throws Exception {
        start("while_working"); assertFalse(AutonomyService.resourceState().getBoolean("partialWakeLockHeld"));
        app.getStore().putSetting("wakeMode", "continuous"); AutonomyService.reevaluateActiveService(); drainLocal(); assertTrue(AutonomyService.resourceState().getBoolean("partialWakeLockHeld"));
        app.getStore().putSetting("wakeMode", "while_working"); AutonomyService.reevaluateActiveService(); drainLocal(); assertFalse(AutonomyService.resourceState().getBoolean("partialWakeLockHeld"));
    }
    @Test public void chargingModeFollowsActualPlugBroadcastAndReturnsToIdleReleaseWhenUnplugged() throws Exception {
        start("charging"); assertEquals("while_working", AutonomyService.resourceState().getString("effectiveWakeMode"));
        app.sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)); drainLocal();
        assertTrue(AutonomyService.resourceState().getBoolean("plugged")); assertEquals("continuous", AutonomyService.resourceState().getString("effectiveWakeMode")); assertTrue(AutonomyService.resourceState().getBoolean("partialWakeLockHeld"));
        app.sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 0)); drainLocal();
        assertFalse(AutonomyService.resourceState().getBoolean("partialWakeLockHeld")); assertEquals("while_working", AutonomyService.resourceState().getString("effectiveWakeMode"));
    }
    @Test public void invalidRestoredModeIsVisibleAndActiveOrLocalWorkStillKeepsABoundedLease() throws Exception {
        start("typo-mode"); assertFalse(AutonomyService.resourceState().getBoolean("validConfiguredWakeMode")); assertEquals("continuous", AutonomyService.resourceState().getString("configuredWakeMode"));
        assertFalse(AutonomyService.validWakeMode(null)); assertFalse(AutonomyService.validWakeMode("Continuous"));
        assertTrue(AutonomyService.shouldKeepAwake("while_working", true, false)); assertTrue(AutonomyService.shouldKeepAwake("while_working", false, true)); assertFalse(AutonomyService.shouldKeepAwake("while_working", false, false));
    }
}
