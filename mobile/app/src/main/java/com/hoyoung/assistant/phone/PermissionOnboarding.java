package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owner-requested first-run and resume gate. Readiness always comes from current Android
 * grants; no remembered "completed" flag can bypass a denial or a later revocation.
 * Tool allow switches are a separate, editable policy and are deliberately not read here.
 */
public final class PermissionOnboarding {
    public static final int REQUEST_CODE = 7304;
    private static final String REQUEST_HISTORY = "hoyoung_permission_requests_v1";
    private final Context context;
    private final PermissionCatalog catalog;
    private final Probe probe;

    public PermissionOnboarding(Context context) {
        this.context = context.getApplicationContext();
        this.catalog = new PermissionCatalog(this.context);
        this.probe = new AndroidProbe(this.context, catalog);
    }

    PermissionOnboarding(Context context, Probe probe) {
        this.context = context.getApplicationContext();
        this.catalog = new PermissionCatalog(this.context);
        this.probe = probe;
    }

    public Snapshot snapshot() {
        try {
            JSONArray entries = probe.entries();
            if (entries == null || entries.length() == 0) return unavailableSnapshot();
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String id = entry.getString("id"), kind = entry.getString("kind");
                JSONArray declared = entry.optJSONArray("runtimePermissions");
                List<String> permissions = new ArrayList<>();
                boolean allDeclared = true, allGranted = true;
                for (int p = 0; declared != null && p < declared.length(); p++) {
                    String permission = declared.getString(p);
                    permissions.add(permission);
                    allDeclared &= probe.declared(permission);
                    allGranted &= probe.has(permission);
                }
                String unsupported = probe.unavailableReason(id);
                boolean required = unsupported.isEmpty();
                if ("device_owner".equals(id)) {
                    required = false;
                    unsupported = "일반 앱에 부여할 수 없는 root·기기 소유자 권한이야.";
                } else if ("restricted".equals(kind) || !allDeclared) {
                    required = false;
                    unsupported = "이 앱에 부여할 수 없는 역할·설치 관리자 제한 권한이야.";
                } else if (entry.optBoolean("installerRestricted") && unsupported.isEmpty()) {
                    String eligibility = entry.optString("grantEligibility", "unknown");
                    boolean knownGranted = false, deniedWithoutPrompt = true;
                    SharedPreferences history = context.getSharedPreferences(REQUEST_HISTORY, Context.MODE_PRIVATE);
                    for (String permission : permissions) {
                        knownGranted |= history.getBoolean("ever_granted." + permission, false);
                        deniedWithoutPrompt &= history.getBoolean("conditional_denied_no_prompt." + permission, false);
                        if (probe.has(permission) && !history.getBoolean("ever_granted." + permission, false)) history.edit().putBoolean("ever_granted." + permission, true).apply();
                    }
                    if (!allGranted && "installer_blocked".equals(eligibility)) {
                        required = false;
                        unsupported = "Android에서 확인한 설치 관리자 허용 목록에 없어 현재 부여할 수 없어. 허용된 권한으로 계산하지 않아.";
                    } else if (!allGranted && "unknown".equals(eligibility) && deniedWithoutPrompt && !knownGranted) {
                        required = false;
                        unsupported = "Android가 미허용으로 반환했고 추가 권한 창을 제공하지 않아. 설치 관리자 제한 여부는 확인할 수 없으며, 허용된 것으로 취급하지 않아. 앱 설정에서 다시 확인할 수 있어.";
                    }
                } else if ("media_selected".equals(id)) {
                    // Selected-only access is an alternative scope, not an extra grant that
                    // must remain enabled after the owner grants the full media library.
                    required = false;
                    unsupported = "전체 사진·영상 허용에 포함되는 선택 범위야. 일부만 선택하면 완료되지 않아.";
                } else if ("all_files".equals(id) && Build.VERSION.SDK_INT < 30) {
                    required = false;
                    unsupported = "이 Android 버전에서는 공유 저장소 읽기·쓰기 권한으로 확인해.";
                } else if ("exact_alarm".equals(id) && Build.VERSION.SDK_INT < 31) {
                    required = false;
                    unsupported = "이 Android 버전에서는 별도 허용이 필요하지 않아.";
                }
                boolean granted = "runtime".equals(kind) ? allGranted : entry.optBoolean("granted");
                if ("notifications".equals(id)) granted &= probe.notificationsEnabled();
                String detail = required ? explanation(id) : unsupported;
                items.add(new Item(id, title(id, entry.optString("label")), detail, kind,
                        required, granted, permissions));
            }
            // Android 8–12 have no POST_NOTIFICATIONS dialog, but the app's notifications
            // can still be switched off by the owner in system settings.
            if (Build.VERSION.SDK_INT < 33) {
                items.add(new Item("notifications", "호영 알림 표시", explanation("notifications"),
                        "special", true, probe.notificationsEnabled(), Collections.emptyList()));
            }
            return new Snapshot(items);
        } catch (RuntimeException | org.json.JSONException unavailable) {
            // A failed platform query is not treated as an unsupported/granted permission.
            return unavailableSnapshot();
        }
    }

    public RequestResult requestNext(Activity activity) {
        List<Item> missing = snapshot().pending();
        return missing.isEmpty() ? new RequestResult(false, "필요한 권한이 모두 허용됐어.")
                : request(activity, missing.get(0).id);
    }

    /** Opens exactly one owner-controlled Android prompt/settings page. Never auto-grants. */
    public RequestResult request(Activity activity, String id) {
        Snapshot state = snapshot();
        Item item = state.find(id);
        if (item == null) return new RequestResult(false, "이 기기에서 별도로 허용할 항목이 아니야.");
        if (!item.required) {
            if (isConditionalId(id) && probe.unavailableReason(id).isEmpty()) return settings(activity, id, item.detail);
            return new RequestResult(false, "이 기기에서 별도로 허용할 항목이 아니야.");
        }
        if (item.granted) return new RequestResult(false, "이미 허용된 권한이야.");
        if ("background_location".equals(id)) {
            Item foreground = state.find("location");
            if (foreground != null && foreground.required && !foreground.granted) return request(activity, "location");
            if (Build.VERSION.SDK_INT >= 30) return settings(activity, id,
                    "위치 권한에서 ‘항상 허용’을 선택하고 돌아오면 다시 확인할게.");
        }
        if ("runtime".equals(item.kind)) {
            Set<String> pending = new LinkedHashSet<>();
            for (String permission : item.permissions) if (probe.declared(permission) && !probe.has(permission)) pending.add(permission);
            if ("location".equals(id) && pending.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Android 12+ ignores a precise-only request on some releases, even when
                // coarse access was already granted. Always request the foreground pair.
                pending.clear();
                pending.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                pending.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if ("media_images".equals(id) || "media_video".equals(id)) {
                // Android 14+ requires the visual-media scope to be requested together.
                for (String permission : new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO}) {
                    if (probe.declared(permission) && !probe.has(permission)) pending.add(permission);
                }
                if (Build.VERSION.SDK_INT >= 34 && probe.declared(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
                    pending.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
                }
            }
            if (pending.isEmpty()) return settings(activity, id, "앱 설정에서 허용 상태를 확인해 줘.");
            SharedPreferences history = context.getSharedPreferences(REQUEST_HISTORY, Context.MODE_PRIVATE);
            for (String permission : pending) {
                if (probe.has(permission)) continue;
                if (history.getBoolean(permission, false) && !activity.shouldShowRequestPermissionRationale(permission)) {
                    return settings(activity, id, "Android가 다시 묻지 않는 권한은 앱 정보 → 권한에서 직접 허용해 줘.");
                }
            }
            SharedPreferences.Editor remember = history.edit();
            for (String permission : pending) remember.putBoolean(permission, true);
            remember.apply();
            try {
                activity.requestPermissions(pending.toArray(new String[0]), REQUEST_CODE);
                return new RequestResult(true, "Android 권한 창에서 허용을 선택해 줘.");
            } catch (RuntimeException unavailable) {
                return settings(activity, id, "권한 창을 열 수 없어 앱 설정으로 이동해.");
            }
        }
        return settings(activity, id, explanation(id));
    }

    private RequestResult settings(Activity activity, String id, String message) {
        return catalog.tryOpenSettings(activity, id) ? new RequestResult(true, message)
                : new RequestResult(false, "설정 화면을 열 수 없어. 휴대폰 설정 → 앱 → 호영에서 직접 허용해 줘. 허용 상태는 아직 미완료야.");
    }

    /** Records an actual completed Android result, never just a prompt launch. */
    public void recordRequestResult(Activity activity, String[] names, int[] results) {
        if (names == null || results == null) return;
        SharedPreferences.Editor update = context.getSharedPreferences(REQUEST_HISTORY, Context.MODE_PRIVATE).edit();
        for (int i = 0; i < names.length && i < results.length; i++) {
            String permission = names[i]; if (!isConditionalPermission(permission)) continue;
            if (results[i] == PackageManager.PERMISSION_GRANTED) {
                update.putBoolean("ever_granted." + permission, true).remove("conditional_denied_no_prompt." + permission);
            } else {
                update.putBoolean("conditional_denied_no_prompt." + permission,
                        !activity.shouldShowRequestPermissionRationale(permission));
            }
        }
        update.apply();
    }
    private static boolean isConditionalPermission(String permission) {
        return Manifest.permission.READ_SMS.equals(permission) || Manifest.permission.SEND_SMS.equals(permission)
                || Manifest.permission.RECEIVE_SMS.equals(permission) || Manifest.permission.READ_CALL_LOG.equals(permission)
                || Manifest.permission.WRITE_CALL_LOG.equals(permission);
    }
    private static boolean isConditionalId(String id) {
        return "sms_read".equals(id) || "sms_send".equals(id) || "sms_receive".equals(id)
                || "call_log_read".equals(id) || "call_log_write".equals(id);
    }

    private static Snapshot unavailableSnapshot() {
        return new Snapshot(Collections.singletonList(new Item("verification_failed", "권한 상태 다시 확인",
                "Android에서 권한 상태를 읽지 못했어. 앱 설정을 확인한 뒤 다시 시도해 줘.",
                "special", true, false, Collections.emptyList())));
    }

    private static String title(String id, String fallback) {
        switch (id) {
            case "location": return "정확한 위치";
            case "background_location": return "항상 위치 허용";
            case "media_images": return "전체 사진";
            case "media_video": return "전체 영상";
            case "device_admin": return "기기 관리자";
            case "exact_alarm": return "알람 및 리마인더";
            default: return fallback;
        }
    }

    private static String explanation(String id) {
        switch (id) {
            case "contacts": return "연락처를 확인하고 요청한 연락처를 추가·수정할 수 있어.";
            case "calendar": return "일정을 확인하고 추가·수정할 수 있어.";
            case "location": return "‘앱 사용 중 허용’과 ‘정확한 위치’를 함께 켜 줘.";
            case "background_location": return "먼저 정확한 위치를 허용한 뒤, 위치 권한을 ‘항상 허용’으로 변경해 줘.";
            case "camera": return "채팅에서 사진을 찍고 이미지 작업에 사용할 수 있어.";
            case "microphone": return "요청한 음성 녹음에 사용할 수 있어.";
            case "phone_state": return "휴대폰이 제공하는 전화·SIM 상태를 확인할 수 있어.";
            case "calls": return "다이얼 화면을 열거나 요청한 번호로 전화를 걸 수 있어. 실제 연결 여부는 별도로 확인해야 해.";
            case "sms_read": return "설치 관리자가 허용한 기기에서 SMS를 읽을 수 있어. Android의 실제 권한 창에서 확인해 줘.";
            case "sms_send": return "설치 관리자가 허용한 기기에서 요청한 SMS를 보낼 수 있어. 통신사 SMS 요금이 적용될 수 있어.";
            case "sms_receive": return "실제 수신한 SMS를 기기 관찰 기록으로 보관할 수 있어. 사용자 명령과는 구분해.";
            case "call_log_read": return "설치 관리자가 허용한 기기에서 통화 기록을 읽을 수 있어.";
            case "call_log_write": return "설치 관리자가 허용한 기기에서 요청한 통화 기록을 삭제할 수 있어.";
            case "nearby": return "Bluetooth 등 주변 기기를 확인할 수 있어.";
            case "nearby_wifi": return "휴대폰 Wi-Fi 정보와 요청한 로컬 핫스팟을 다룰 수 있어. 인터넷 공유와는 별개야.";
            case "activity_recognition": return "지원 센서의 걸음 수와 활동 정보를 기록할 수 있어.";
            case "media_images": case "media_video": return "‘모든 사진 및 동영상 허용’을 선택해 줘. 일부 사진만 허용하면 아직 미완료야.";
            case "media_audio": return "공유 저장소의 오디오 파일을 확인할 수 있어.";
            case "media": case "shared_storage_write": return "공유 저장소의 파일을 읽고 저장할 수 있어.";
            case "notifications": return "호영의 답장과 실행 상태 알림을 표시할 수 있어.";
            case "notification_access": return "다른 앱 알림을 읽는 별도 권한이야. 제한된 설정 안내가 뜨면 앱 정보에서 직접 허용해 줘.";
            case "accessibility": return "화면을 확인하고 요청한 터치 작업을 수행해. 제한된 설정은 앱 정보에서 직접 허용해 줘.";
            case "usage_access": return "앱 사용 정보를 확인할 수 있어.";
            case "notification_policy": return "요청한 방해 금지·소리 설정을 변경할 수 있어.";
            case "all_files": return "공유 저장소의 파일에 접근해. 다른 앱의 비공개 저장소는 포함되지 않아.";
            case "write_settings": return "밝기 등 Android가 허용하는 시스템 설정을 변경할 수 있어.";
            case "overlay": return "다른 앱 위에 표시하는 Android 권한을 허용해 줘.";
            case "battery_optimization": return "배터리 최적화에서 호영을 제외해 줘. Android가 실행을 제한할 수는 있어.";
            case "device_admin": return "화면 잠금용 기기 관리자 권한이야. 기기 소유자나 root 권한은 아니야.";
            case "exact_alarm": return "Android의 알람 및 리마인더 권한을 허용해 줘.";
            default: return "Android 설정에서 이 기능의 권한을 허용해 줘.";
        }
    }

    public static final class Item {
        public final String id, title, detail, kind;
        public final boolean required, granted;
        public final List<String> permissions;
        Item(String id, String title, String detail, String kind, boolean required,
             boolean granted, List<String> permissions) {
            this.id = id; this.title = title; this.detail = detail; this.kind = kind;
            this.required = required; this.granted = granted;
            this.permissions = Collections.unmodifiableList(new ArrayList<>(permissions));
        }
    }

    public static final class Snapshot {
        public final List<Item> items;
        public final int requiredCount, grantedCount;
        Snapshot(List<Item> values) {
            items = Collections.unmodifiableList(new ArrayList<>(values));
            int required = 0, granted = 0;
            for (Item item : items) if (item.required) { required++; if (item.granted) granted++; }
            requiredCount = required; grantedCount = granted;
        }
        public boolean ready() { return requiredCount > 0 && requiredCount == grantedCount; }
        public List<Item> pending() {
            List<Item> result = new ArrayList<>();
            for (Item item : items) if (item.required && !item.granted) result.add(item);
            return Collections.unmodifiableList(result);
        }
        public Item find(String id) { for (Item item : items) if (item.id.equals(id)) return item; return null; }
    }

    public static final class RequestResult {
        public final boolean launched;
        public final String message;
        RequestResult(boolean launched, String message) { this.launched = launched; this.message = message; }
    }

    interface Probe {
        JSONArray entries();
        boolean has(String permission);
        boolean declared(String permission);
        String unavailableReason(String id);
        boolean notificationsEnabled();
    }

    private static final class AndroidProbe implements Probe {
        private final Context context;
        private final PermissionCatalog catalog;
        AndroidProbe(Context context, PermissionCatalog catalog) { this.context = context; this.catalog = catalog; }
        @Override public JSONArray entries() { return catalog.snapshot(); }
        @Override public boolean has(String permission) { return catalog.has(permission); }
        @Override public boolean declared(String permission) { return catalog.declared(permission); }
        @Override public boolean notificationsEnabled() {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.areNotificationsEnabled();
        }
        @Override public String unavailableReason(String id) {
            PackageManager pm = context.getPackageManager();
            boolean supported = true;
            switch (id) {
                case "camera": supported = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY); break;
                case "microphone": supported = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE); break;
                case "location": case "background_location": supported = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION); break;
                case "phone_state": case "calls": case "sms_read": case "sms_send": case "sms_receive":
                case "call_log_read": case "call_log_write": supported = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY); break;
                case "nearby": supported = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) || pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE); break;
                case "nearby_wifi": supported = pm.hasSystemFeature(PackageManager.FEATURE_WIFI); break;
                case "device_admin": supported = pm.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN); break;
                case "activity_recognition":
                    SensorManager sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
                    supported = sensors != null && (sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
                            || sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null);
                    break;
                default: break;
            }
            return supported ? "" : "이 기기에는 해당 하드웨어·시스템 기능이 없어 별도 허용이 필요하지 않아.";
        }
    }
}
