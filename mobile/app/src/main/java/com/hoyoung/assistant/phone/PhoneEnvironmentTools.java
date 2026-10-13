package com.hoyoung.assistant.phone;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.MediaStore;
import android.telephony.TelephonyManager;
import org.json.JSONArray;
import org.json.JSONObject;

final class PhoneEnvironmentTools {
    private static final String REDACTED_SSID = "<unknown ssid>";
    private final Context context;
    PhoneEnvironmentTools(Context context) { this.context = context.getApplicationContext(); }
    JSONObject bluetooth(JSONObject args) throws Exception {
        PermissionCatalog grants = new PermissionCatalog(context);
        String required = Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH;
        if (!grants.has(required)) return missing("nearby");
        int limit = args.optInt("limit", 50); if (limit < 1 || limit > 100) throw new IllegalArgumentException();
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return PhoneEventJournal.object("ok", false, "code", "bluetooth_unavailable");
        JSONArray devices = new JSONArray(); boolean truncated = false;
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (devices.length() >= limit) { truncated = true; break; }
                devices.put(PhoneEventJournal.object("name", device.getName(), "address", device.getAddress(), "type", device.getType(), "bondState", device.getBondState(), "connectedVerified", false));
            }
            return PhoneEventJournal.object("ok", true, "code", "ok", "enabled", adapter.isEnabled(), "bondedDevices", devices, "truncated", truncated, "source", "android.bluetooth_bonded_devices", "newScanStarted", false);
        } catch (SecurityException revokedDuringRead) {
            // Android can revoke CONNECT after the actual catalog check above.
            return missing("nearby");
        }
    }
    JSONObject network() throws Exception {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = manager == null ? null : manager.getActiveNetwork(); NetworkCapabilities caps = manager == null || active == null ? null : manager.getNetworkCapabilities(active);
        JSONObject result = PhoneEventJournal.object("ok", true, "code", "ok", "connected", active != null, "validatedInternet", caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            "metered", manager != null && manager.isActiveNetworkMetered(), "wifi", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), "cellular", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            "vpn", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN), "source", "android.connectivity_manager", "observedAt", System.currentTimeMillis());
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null && new PermissionCatalog(context).has(Manifest.permission.ACCESS_WIFI_STATE)) {
            WifiInfo info = wifi.getConnectionInfo(); result.put("wifiEnabled", wifi.isWifiEnabled());
            if (info != null) {
                String ssid = info.getSSID(), bssid = info.getBSSID();
                boolean redacted = ssid == null || ssid.equals(REDACTED_SSID) || bssid == null || bssid.equals("02:00:00:00:00:00");
                result.put("ssid", ssid == null || ssid.equals(REDACTED_SSID) ? JSONObject.NULL : ssid).put("bssid", bssid == null || bssid.equals("02:00:00:00:00:00") ? JSONObject.NULL : bssid)
                    .put("sensitiveWifiInfoRedacted", redacted).put("rssiDbm", info.getRssi()).put("linkSpeedMbps", info.getLinkSpeed());
            }
        }
        return result;
    }
    JSONObject phoneState() {
        if (!new PermissionCatalog(context).has(Manifest.permission.READ_PHONE_STATE)) return missing("phone_state");
        TelephonyManager phone = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (phone == null) return PhoneEventJournal.object("ok", false, "code", "telephony_unavailable");
        int state = phone.getCallState();
        return PhoneEventJournal.object("ok", true, "code", "ok", "callState", state, "callStateLabel", state == TelephonyManager.CALL_STATE_IDLE ? "idle" : state == TelephonyManager.CALL_STATE_RINGING ? "ringing" : "offhook",
            "simState", phone.getSimState(), "networkCountryIso", phone.getNetworkCountryIso(), "simCountryIso", phone.getSimCountryIso(), "source", "android.telephony_manager", "identifiersRead", false);
    }
    JSONObject media(JSONObject args) throws Exception {
        String kind = args.getString("kind"); int limit = args.optInt("limit", 50); if (limit < 1 || limit > 100) throw new IllegalArgumentException();
        Uri collection; String permission;
        switch (kind) {
            case "images": collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE; break;
            case "video": collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE; break;
            case "audio": collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE; break;
            default: throw new IllegalArgumentException();
        }
        PermissionCatalog grants = new PermissionCatalog(context); boolean full = grants.has(permission);
        boolean selected = Build.VERSION.SDK_INT >= 34 && !kind.equals("audio") && grants.has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        if (!full && !selected) return missing("media_" + kind);
        String[] fields = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED};
        JSONArray rows = new JSONArray(); boolean truncated = false;
        try (Cursor cursor = context.getContentResolver().query(collection, fields, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            while (cursor != null && cursor.moveToNext()) {
                if (rows.length() >= limit) { truncated = true; break; }
                rows.put(PhoneEventJournal.object("id", cursor.getLong(0), "uri", ContentUris.withAppendedId(collection, cursor.getLong(0)).toString(), "name", cursor.getString(1), "mime", cursor.getString(2), "size", cursor.getLong(3), "modifiedAt", cursor.getLong(4) * 1000));
            }
        }
        return PhoneEventJournal.object("ok", true, "code", "ok", "media", rows, "kind", kind, "truncated", truncated, "access", full ? "os_granted_library_access" : "user_selected_items_only", "source", "android.media_store", "binaryUploaded", false);
    }
    private static JSONObject missing(String id) { return PhoneEventJournal.object("ok", false, "code", "permission_missing", "permissionId", id); }
}
