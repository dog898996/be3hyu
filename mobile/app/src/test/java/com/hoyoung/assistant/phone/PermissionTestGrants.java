package com.hoyoung.assistant.phone;

import android.app.AppOpsManager;
import android.app.Application;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.shadows.ShadowSettings;

/** Test-only OS grant fixture. Production has no ready flag or permission bypass. */
public final class PermissionTestGrants {
    private PermissionTestGrants() { }
    public static void allowAll(Context context) {
        Application app = (Application) context.getApplicationContext();
        try {
            String[] permissions = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            Shadows.shadowOf(app).grantPermissions(permissions);
        } catch (PackageManager.NameNotFoundException impossible) { throw new AssertionError(impossible); }
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners",
                new ComponentName(context, HoyoungNotificationListener.class).flattenToString());
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                new ComponentName(context, HoyoungAccessibilityService.class).flattenToString());
        Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Shadows.shadowOf(notifications).setNotificationsEnabled(true);
        Shadows.shadowOf(notifications).setNotificationPolicyAccessGranted(true);
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        for (String op : new String[]{AppOpsManager.OPSTR_GET_USAGE_STATS, AppOpsManager.OPSTR_WRITE_SETTINGS}) {
            Shadows.shadowOf(ops).setMode(op, android.os.Process.myUid(), context.getPackageName(), AppOpsManager.MODE_ALLOWED);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            ShadowEnvironment.addExternalDir("permission-fixture-shared");
            Shadows.shadowOf(ops).setMode("android:manage_external_storage", android.os.Process.myUid(),
                    context.getPackageName(), AppOpsManager.MODE_ALLOWED);
        }
        ShadowSettings.setCanDrawOverlays(true);
        Shadows.shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(context.getPackageName(), true);
        Shadows.shadowOf((DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE))
                .setActiveAdmin(PermissionCatalog.adminComponent(context));
        if (Build.VERSION.SDK_INT >= 31) ShadowAlarmManager.setCanScheduleExactAlarms(true);
    }
}
