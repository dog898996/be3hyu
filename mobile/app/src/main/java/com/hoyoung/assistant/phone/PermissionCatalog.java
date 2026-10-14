package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Reports actual Android grants. It never changes a grant on the user's behalf. */
public final class PermissionCatalog {
    private final Context context;
    public PermissionCatalog(Context context) { this.context = context.getApplicationContext(); }

    public JSONArray snapshot() {
        JSONArray result = new JSONArray();
        runtime(result, "contacts", "주소록 읽기·수정", "READ_CONTACTS and WRITE_CONTACTS are checked independently by each tool.", Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS);
        runtime(result, "calendar", "일정 읽기·추가", "Each tool checks its own read or write permission.", Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR);
        runtime(result, "location", "앱 사용 중 위치", "Approximate location is valid; precise location is optional.", Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 29) runtime(result, "background_location", "백그라운드 위치", "Grant foreground location first, then enable background access separately in Android Settings. This is not included in the batch permission request.", Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        runtime(result, "camera", "카메라", "Camera access does not grant screen capture.", Manifest.permission.CAMERA);
        runtime(result, "microphone", "마이크", "Recording requires an explicit feature and applicable foreground service.", Manifest.permission.RECORD_AUDIO);
        runtime(result, "phone_state", "전화 상태·내 번호", "No device identifier or phone number is promised; the OS and SIM may withhold it.", Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS);
        runtime(result, "calls", "전화 걸기", "The shipped tool opens the dialer; it does not place a call automatically.", Manifest.permission.CALL_PHONE);
        restricted(result, "sms", "SMS", "SMS permissions are installer/role restricted. A normal personal assistant is not automatically eligible; no SMS sending tool is provided.", Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS);
        restricted(result, "call_log", "통화 기록", "Call-log permissions are installer/role restricted. Declaring them does not grant access.", Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG);
        if (Build.VERSION.SDK_INT >= 31) runtime(result, "nearby", "주변 Bluetooth 기기", "Android 12+ requires Nearby Devices grants. Bluetooth scanning is not automatically started.", Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN);
        if (Build.VERSION.SDK_INT >= 29) runtime(result, "activity_recognition", "신체 활동 인식", "This permits supported motion/activity APIs, not health-record access.", Manifest.permission.ACTIVITY_RECOGNITION);
        if (Build.VERSION.SDK_INT >= 33) {
            runtime(result, "nearby_wifi", "주변 Wi-Fi 기기", "Permission alone does not permit silently connecting or changing Wi-Fi networks.", Manifest.permission.NEARBY_WIFI_DEVICES);
            runtime(result, "media_images", "사진", "Android 14+ can grant only selected images. The system photo picker remains available without broad access.", Manifest.permission.READ_MEDIA_IMAGES);
            if (Build.VERSION.SDK_INT >= 34) runtime(result, "media_selected", "선택한 사진·영상", "A limited selection is not full-library access.", Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            runtime(result, "media_video", "영상", "A partial grant is not full-library access.", Manifest.permission.READ_MEDIA_VIDEO);
            runtime(result, "media_audio", "오디오 파일", "This does not grant microphone recording.", Manifest.permission.READ_MEDIA_AUDIO);
            runtime(result, "notifications", "앱 알림 표시", "Permission to show this app's notifications is separate from reading other apps' notifications.", Manifest.permission.POST_NOTIFICATIONS);
        } else runtime(result, "media", "공유 저장소 읽기", "Scoped storage still applies. Protected app directories remain inaccessible.", Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT <= 29) runtime(result, "shared_storage_write", "공유 저장소 쓰기", "Legacy permission for Android 8–10; Android 10 scoped-storage rules can still restrict actual file access.", Manifest.permission.WRITE_EXTERNAL_STORAGE);
        special(result, "notification_access", "다른 앱 알림 접근", notificationAccess(), "open_settings", "Android may redact sensitive notification content. Only notifications received while this service is connected are retained locally.");
        special(result, "accessibility", "화면 확인·터치", accessibilityAccess(), "open_settings", "Explicit user enablement is required. Password/editable input contents are omitted; this service never grants permissions automatically.");
        special(result, "usage_access", "앱 사용 정보", usageAccess(), "open_settings", "Usage access is not permission to read another app's private files.");
        android.app.NotificationManager nm = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        special(result, "notification_policy", "방해 금지 설정 접근", nm != null && nm.isNotificationPolicyAccessGranted(), "open_settings", "Some ringtone/volume changes require this separate grant. It does not provide notification contents.");
        special(result, "all_files", "공유 저장소의 모든 파일", allFilesAccess(), "open_settings", "This is not root. Other apps' private data and Android/data or Android/obb remain protected on recent Android versions.");
        special(result, "write_settings", "시스템 설정 수정", Settings.System.canWrite(context), "open_settings", "Only writable System settings are available; secure/global settings require privileged access.");
        special(result, "overlay", "다른 앱 위에 표시", Settings.canDrawOverlays(context), "open_settings", "Overlay permission does not grant screen capture or touch injection.");
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        special(result, "battery_optimization", "배터리 최적화 제외", pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName()), "open_settings", "An exemption still does not guarantee 24-hour execution or survival after force-stop.");
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        special(result, "device_admin", "기기 관리자", dpm != null && dpm.isAdminActive(adminComponent(context)), "open_settings", "Legacy device admin is not device owner or root. No destructive administration tool is supplied.");
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        special(result, "exact_alarm", "정확한 알람", Build.VERSION.SDK_INT < 31 || (am != null && am.canScheduleExactAlarms()), "open_settings", "Exact alarms are not a permission to run an AI worker every minute indefinitely.");
        special(result, "device_owner", "Device Owner·root", false, "unsupported", "Unavailable in a normal installation. No root, privileged signature permission, or device-owner status is fabricated.");
        return result;
    }

    /** Safe ordinary-runtime request batch; background and restricted permissions are separate. */
    public String[] missingRuntimePermissions() {
        Set<String> missing = new LinkedHashSet<>();
        JSONArray entries = snapshot();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !"runtime".equals(entry.optString("kind")) || "background_location".equals(entry.optString("id"))) continue;
            JSONArray permissions = entry.optJSONArray("runtimePermissions");
            for (int j = 0; permissions != null && j < permissions.length(); j++) {
                String permission = permissions.optString(j);
                if (declared(permission) && !has(permission)) missing.add(permission);
            }
        }
        return missing.toArray(new String[0]);
    }

    public boolean has(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }

    public boolean allFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) return has(Manifest.permission.READ_EXTERNAL_STORAGE);
        // Unmounted or unavailable shared storage is not evidence of an access grant.
        try { return Environment.isExternalStorageManager(); }
        catch (RuntimeException storageUnavailable) { return false; }
    }

    public void openSettings(Activity activity, String id) {
        tryOpenSettings(activity, id);
    }

    /** Returns only whether a settings screen opened, never whether access was granted. */
    public boolean tryOpenSettings(Activity activity, String id) {
        Uri app = Uri.parse("package:" + context.getPackageName());
        Intent intent;
        switch (id == null ? "" : id) {
            case "notification_access": intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS); break;
            case "accessibility": intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS); break;
            case "usage_access": intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS); break;
            case "notification_policy": intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS); break;
            case "notifications": intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName()); break;
            case "all_files": intent = Build.VERSION.SDK_INT >= 30 ? new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, app) : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app); break;
            case "write_settings": intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, app); break;
            case "overlay": intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, app); break;
            case "battery_optimization": intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS); break;
            case "device_admin": intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context)).putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "화면 잠금 정책만 요청합니다. 기기 소유자나 root 권한이 아닙니다."); break;
            case "exact_alarm": intent = Build.VERSION.SDK_INT >= 31 ? new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, app) : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app); break;
            case "sms": intent = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS); break;
            case "device_owner": return false;
            default: intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app);
        }
        try { activity.startActivity(intent); return true; }
        catch (RuntimeException unavailable) {
            try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)); return true; }
            catch (RuntimeException ignored) { return false; }
        }
    }

    public boolean notificationAccess() { return enabledComponent(Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners"), new ComponentName(context, HoyoungNotificationListener.class)); }
    public boolean accessibilityAccess() { return enabledComponent(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), new ComponentName(context, HoyoungAccessibilityService.class)); }
    public boolean usageAccess() {
        android.app.AppOpsManager ops = (android.app.AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName());
        return mode == android.app.AppOpsManager.MODE_ALLOWED;
    }
    public static ComponentName adminComponent(Context context) { return new ComponentName(context, HoyoungDeviceAdminReceiver.class); }
    private static boolean enabledComponent(String list, ComponentName component) {
        if (list == null) return false;
        for (String value : list.split(":")) if (component.equals(ComponentName.unflattenFromString(value))) return true;
        return false;
    }
    boolean declared(String permission) {
        try {
            String[] declared = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (declared != null) for (String candidate : declared) if (permission.equals(candidate)) return true;
        } catch (PackageManager.NameNotFoundException ignored) { }
        return false;
    }
    private void runtime(JSONArray output, String id, String label, String notes, String... permissions) { entry(output, id, label, "runtime", permissions, "request_runtime", notes); }
    private void restricted(JSONArray output, String id, String label, String notes, String... permissions) { entry(output, id, label, "restricted", permissions, "open_settings", notes); }
    private void entry(JSONArray output, String id, String label, String kind, String[] permissions, String action, String notes) {
        JSONArray list = new JSONArray(); boolean granted = true;
        for (String permission : permissions) { list.put(permission); if (!has(permission)) granted = false; }
        // Coarse location remains usable even when precise location is declined.
        if ("location".equals(id)) granted = has(Manifest.permission.ACCESS_COARSE_LOCATION) || has(Manifest.permission.ACCESS_FINE_LOCATION);
        try { output.put(new JSONObject().put("id", id).put("label", label).put("kind", kind).put("runtimePermissions", list).put("granted", granted).put("action", action).put("notes", notes)); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private void special(JSONArray output, String id, String label, boolean granted, String action, String notes) {
        try { output.put(new JSONObject().put("id", id).put("label", label).put("kind", "special").put("runtimePermissions", new JSONArray()).put("granted", granted).put("action", action).put("notes", notes)); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
