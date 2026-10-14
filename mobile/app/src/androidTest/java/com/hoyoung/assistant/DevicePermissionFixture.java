package com.hoyoung.assistant;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.SystemClock;
import com.hoyoung.assistant.phone.PermissionOnboarding;
import static org.junit.Assert.*;

/** Verifies actual OS grants established by the wiped-offline-AVD shell driver. */
final class DevicePermissionFixture {
    private DevicePermissionFixture() { }
    static void assertReady(Context context) {
        assertTrue("Never operate a physical phone fixture", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        assertNull("Permission fixtures must remain offline", context.getSystemService(ConnectivityManager.class).getActiveNetwork());
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        PermissionOnboarding gate = new PermissionOnboarding(context);
        PermissionOnboarding.Snapshot state;
        do {
            state = gate.snapshot();
            if (state.ready()) break;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        StringBuilder missing = new StringBuilder();
        for (PermissionOnboarding.Item item : state.pending()) missing.append(item.id).append(' ');
        assertTrue("The real Android setup gate must be complete; missing grants: " + missing, state.ready());
        android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_DEVICE_PERMISSION_GATE actual-required-grants="
                + state.requiredCount + "; all-granted; no-production-bypass");
    }
}
