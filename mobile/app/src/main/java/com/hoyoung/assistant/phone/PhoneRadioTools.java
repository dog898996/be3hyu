package com.hoyoung.assistant.phone;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;
import java.util.UUID;

/** Explicit owner-controlled radio operations. Cached observations never become proof of a new scan. */
public final class PhoneRadioTools {
    interface BluetoothEvents { void device(String address, String name, int type, int rssi); void finished(); }
    interface BluetoothBackend {
        boolean available(); boolean enabled(); boolean discovering(); boolean start(BluetoothEvents events);
        boolean cancel(); void detach();
    }
    interface HotspotEvents { void started(String ssid, String passphrase); void stopped(); void failed(int reason); }
    interface WifiBackend {
        boolean available(); boolean scan(); List<ScanResult> scanResults();
        void hotspot(HotspotEvents events); void stopHotspot();
    }
    private static final String PREFIX = "radio.request.";
    private final Context context;
    private final PermissionCatalog grants;
    private final BluetoothBackend bluetooth;
    private final WifiBackend wifi;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PhoneEventJournal journal;
    private BluetoothRequest discovery;
    private String liveHotspotId;
    private volatile boolean restorePaused;

    public PhoneRadioTools(Context context) { this(context, null, new AndroidBluetooth(context.getApplicationContext()), new AndroidWifi(context.getApplicationContext())); }
    PhoneRadioTools(Context context, PhoneEventJournal journal, BluetoothBackend bluetooth, WifiBackend wifi) {
        this.context = context.getApplicationContext(); this.journal = journal; this.bluetooth = bluetooth; this.wifi = wifi; grants = new PermissionCatalog(this.context);
    }
    private PhoneEventJournal journal() { if (journal == null) journal = PhoneEventJournal.forContext(context); return journal; }

    public void appendCatalog(JSONArray output) {
        tool(output, "phone_bluetooth_scan", "Start one bounded classic Bluetooth discovery (1–30 seconds) after Nearby/legacy-location grants. Returns requestId immediately; only Android-observed discoverable devices are recorded, not all nearby devices.", properties("timeoutMs", "integer", "limit", "integer"));
        tool(output, "phone_bluetooth_scan_read", "Read a Bluetooth discovery receipt and the devices actually observed. Interrupted process state remains unknown; no scan is retried. Read-only receipt follows the normal memory backup.", properties("requestId", "string"), "requestId");
        tool(output, "phone_bluetooth_scan_cancel", "Request stopping this app's current Bluetooth discovery. Does not cancel discovery started by another app; Android cancellation acceptance is not proof of radio completion.", properties("requestId", "string"), "requestId");
        tool(output, "phone_wifi_scan", "Request an Android Wi-Fi scan after fine-location/location-services and CHANGE_WIFI_STATE grants. Android may throttle or reject it. Returns requestId; acceptance does not prove fresh scan results.", new JSONObject());
        tool(output, "phone_wifi_scan_read", "Read currently OS-exposed Wi-Fi scan cache with elapsed-clock age/freshness for each AP. Optional requestId compares cache timestamps with that request; fresh timestamps do not prove this request caused the scan.", properties("requestId", "string", "limit", "integer"));
        tool(output, "phone_wifi_hotspot_start", "Request a local-only Wi-Fi hotspot using Android's official API and Nearby Wi-Fi grant (location on older Android). It does not share cellular Internet. Android can reject background, concurrent, or unsupported requests.", new JSONObject());
        tool(output, "phone_wifi_hotspot_read", "Read one local-only hotspot request receipt, including the actual SSID/passphrase only after Android's started callback. Process interruption is unknown and never invents a running network.", properties("requestId", "string"), "requestId");
        tool(output, "phone_wifi_hotspot_stop", "Close this process's explicit local-only hotspot reservation. Reports requested stop until Android's stopped callback; it does not control another app's hotspot.", properties("requestId", "string"), "requestId");
    }
    public boolean supports(String name) {
        return "phone_bluetooth_scan".equals(name) || "phone_bluetooth_scan_read".equals(name) || "phone_bluetooth_scan_cancel".equals(name)
            || "phone_wifi_scan".equals(name) || "phone_wifi_scan_read".equals(name) || "phone_wifi_hotspot_start".equals(name)
            || "phone_wifi_hotspot_read".equals(name) || "phone_wifi_hotspot_stop".equals(name);
    }
    public JSONObject execute(String name, JSONObject supplied) {
        JSONObject args = supplied == null ? new JSONObject() : supplied;
        try {
            if (restorePaused) return failure("restore_in_progress");
            journal().refreshPolicyBlocking();
            if (!journal().toolAllowed(name)) return failure("permission_disabled");
            switch (name == null ? "" : name) {
                case "phone_bluetooth_scan": return startBluetooth(args);
                case "phone_bluetooth_scan_read": return readBluetooth(args);
                case "phone_bluetooth_scan_cancel": return cancelBluetooth(args);
                case "phone_wifi_scan": return scanWifi(args);
                case "phone_wifi_scan_read": return readWifi(args);
                case "phone_wifi_hotspot_start": return startHotspot(args);
                case "phone_wifi_hotspot_read": return readHotspot(args);
                case "phone_wifi_hotspot_stop": return stopHotspot(args);
                default: return failure("unsupported");
            }
        } catch (SecurityException denied) { return failure("permission_missing"); }
        catch (JSONException | IllegalArgumentException invalid) { return failure("invalid_arguments"); }
        catch (RuntimeException unavailable) { return failure("operation_failed"); }
    }
    public boolean canReadArchivedResult(String name, JSONObject result) {
        if (name != null && name.startsWith("phone_bluetooth_scan")) return bluetoothPermissions() == null;
        if ("phone_wifi_scan".equals(name) || "phone_wifi_scan_read".equals(name)) return wifiScanPermissions() == null;
        if (name != null && name.startsWith("phone_wifi_hotspot_")) return hotspotPermissions() == null;
        return false;
    }
    public boolean canReadStoredEvent(JSONObject event) {
        if (event == null) return false; String source = event.optString("source");
        if ("android.bluetooth_discovery".equals(source)) return bluetoothPermissions() == null && journal().toolAllowed("phone_bluetooth_scan_read");
        if ("android.wifi_scan_cache".equals(source)) return wifiScanPermissions() == null && journal().toolAllowed("phone_wifi_scan_read");
        if ("android.local_only_hotspot".equals(source)) return hotspotPermissions() == null && journal().toolAllowed("phone_wifi_hotspot_read");
        return false;
    }

    private synchronized JSONObject startBluetooth(JSONObject args) throws JSONException {
        rejectUnknown(args, "timeoutMs", "limit"); int timeout = integer(args, "timeoutMs", 15000, 1000, 30000), limit = integer(args, "limit", 100, 1, 200);
        JSONObject missing = bluetoothGrant(); if (missing != null) return missing;
        if (!bluetooth.available()) return failure("bluetooth_unavailable"); if (!bluetooth.enabled()) return failure("bluetooth_disabled");
        if (discovery != null || bluetooth.discovering()) return failure("scan_busy");
        BluetoothRequest request = new BluetoothRequest(UUID.randomUUID().toString(), timeout, limit);
        if (!save(request.id, bluetoothReceipt(request, "pending", false))) return failure("receipt_storage_unavailable");
        discovery = request;
        try {
            boolean accepted = bluetooth.start(new BluetoothEvents() {
                public void device(String address, String name, int type, int rssi) { observeBluetooth(request, address, name, type, rssi); }
                public void finished() { finishBluetooth(request, "observed", true, false); }
            });
            if (!accepted) { finishBluetooth(request, "scan_rejected_or_throttled", false, false); return failure("scan_rejected_or_throttled").put("requestId", request.id); }
            request.accepted = true;
            if (discovery == request) { save(request.id, bluetoothReceipt(request, "pending", false)); main.postDelayed(request.timeout, timeout); }
            else { JSONObject receipt = load(request.id, "bluetooth"); if (receipt != null) { receipt.put("scanAccepted", true); save(request.id, receipt); } }
            return result("bluetooth_scan_requested", request.id).put("accepted", true).put("deadlineAt", request.startedAt + timeout).put("completionConfirmed", false);
        } catch (SecurityException denied) { finishBluetooth(request, "permission_revoked", false, false); return missing("nearby").put("requestId", request.id); }
        catch (RuntimeException unavailable) { finishBluetooth(request, "dispatch_failed", false, false); return failure("operation_failed").put("requestId", request.id); }
    }
    private synchronized void observeBluetooth(BluetoothRequest request, String address, String name, int type, int rssi) {
        if (discovery != request) return;
        if (bluetoothGrant() != null || !journal().toolAllowed("phone_bluetooth_scan")) { finishBluetooth(request, "permission_revoked", false, true); return; }
        if (address == null || !address.matches("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")) return;
        for (int i = 0; i < request.devices.length(); i++) if (address.equals(request.devices.optJSONObject(i).optString("address"))) return;
        if (request.devices.length() >= request.limit) { request.truncated = true; save(request.id, bluetoothReceipt(request, "pending", false)); return; }
        request.devices.put(PhoneEventJournal.object("address", address, "name", name == null ? JSONObject.NULL : name, "type", type, "rssiDbm", rssi, "observedAt", System.currentTimeMillis(), "connectedVerified", false));
        save(request.id, bluetoothReceipt(request, "pending", false));
    }
    private synchronized void finishBluetooth(BluetoothRequest request, String state, boolean finishedObserved, boolean cancel) {
        if (discovery != request) return; discovery = null; main.removeCallbacks(request.timeout);
        if (bluetoothGrant() != null || !journal().toolAllowed("phone_bluetooth_scan")) { state = "permission_revoked"; finishedObserved = false; }
        boolean cancelAccepted = false;
        try { if (cancel) cancelAccepted = bluetooth.cancel(); } catch (RuntimeException ignored) { }
        try { bluetooth.detach(); } catch (RuntimeException ignored) { }
        JSONObject receipt = bluetoothReceipt(request, state, finishedObserved);
        put(receipt, "completedAt", System.currentTimeMillis()); put(receipt, "cancelRequested", cancel); put(receipt, "cancelAccepted", cancelAccepted);
        if (journal().collectionEnabled() && journal().toolAllowed("phone_bluetooth_scan") && bluetoothGrant() == null) attachObservation(receipt, "android.bluetooth_discovery");
        save(request.id, receipt);
    }
    private synchronized JSONObject readBluetooth(JSONObject args) throws JSONException {
        rejectUnknown(args, "requestId"); JSONObject missing = bluetoothPermissions(); if (missing != null) return missing;
        String id = requestId(args); JSONObject receipt = load(id, "bluetooth");
        if (receipt == null) return failure("target_not_found");
        if ("pending".equals(receipt.optString("state")) && (discovery == null || !id.equals(discovery.id))) {
            receipt.put("state", "outcome_unknown_after_process_restart").put("completionConfirmed", false).put("scanEndedVerified", false); save(id, receipt);
        }
        return result("ok", id).put("request", receipt).put("automaticReplay", false);
    }
    private synchronized JSONObject cancelBluetooth(JSONObject args) throws JSONException {
        rejectUnknown(args, "requestId"); String id = requestId(args);
        JSONObject missing = bluetoothGrant();
        if (missing != null) { if (discovery != null && id.equals(discovery.id)) finishBluetooth(discovery, "permission_revoked", false, true); return missing; }
        if (discovery != null && id.equals(discovery.id)) {
            BluetoothRequest current = discovery; finishBluetooth(current, "cancel_requested", false, true);
            return result("bluetooth_cancel_requested", id).put("request", load(id, "bluetooth")).put("completionConfirmed", false);
        }
        JSONObject existing = load(id, "bluetooth"); if (existing == null) return failure("target_not_found");
        return result("scan_not_pending", id).put("cancelRequested", false).put("request", existing);
    }
    private JSONObject bluetoothReceipt(BluetoothRequest request, String state, boolean ended) {
        return PhoneEventJournal.object("requestId", request.id, "kind", "bluetooth", "state", state, "startedAt", request.startedAt, "startedElapsedMs", request.startedElapsed,
                "scanAccepted", request.accepted, "devices", request.devices, "truncated", request.truncated, "scanEndedVerified", ended, "completionConfirmed", ended,
                "allNearbyDevicesEnumerated", false, "source", "android.bluetooth_discovery", "sourceKind", "device_observation", "ownerUtterance", false, "automaticReplay", false);
    }
    private final class BluetoothRequest {
        final String id; final long startedAt = System.currentTimeMillis(), startedElapsed = SystemClock.elapsedRealtime(); final int limit;
        final JSONArray devices = new JSONArray(); boolean accepted, truncated;
        final Runnable timeout = () -> finishBluetooth(this, "timed_out", false, true);
        BluetoothRequest(String id, int timeout, int limit) { this.id = id; this.limit = limit; }
    }

    private JSONObject scanWifi(JSONObject args) throws JSONException {
        rejectUnknown(args); JSONObject missing = wifiScanGrant(); if (missing != null) return missing;
        if (!wifi.available()) return failure("wifi_unavailable"); String id = UUID.randomUUID().toString();
        JSONObject receipt = PhoneEventJournal.object("requestId", id, "kind", "wifi", "state", "pending", "startedAt", System.currentTimeMillis(), "startedElapsedMs", SystemClock.elapsedRealtime(), "bootCount", bootCount(), "completionConfirmed", false);
        if (!save(id, receipt)) return failure("receipt_storage_unavailable");
        boolean accepted = wifi.scan(); receipt.put("state", accepted ? "scan_requested" : "scan_rejected_or_throttled").put("scanAccepted", accepted).put("source", "android.wifi_manager"); save(id, receipt);
        return PhoneEventJournal.object("ok", accepted, "code", accepted ? "wifi_scan_requested" : "scan_rejected_or_throttled", "requestId", id,
                "requested", true, "accepted", accepted, "completionConfirmed", false, "freshResultsConfirmed", false, "source", "android.wifi_manager");
    }
    private JSONObject readWifi(JSONObject args) throws JSONException {
        rejectUnknown(args, "requestId", "limit"); JSONObject missing = wifiScanGrant(); if (missing != null) return missing;
        int limit = integer(args, "limit", 50, 1, 200); String id = args.has("requestId") ? requestId(args) : "";
        JSONObject request = id.isEmpty() ? null : load(id, "wifi"); if (!id.isEmpty() && request == null) return failure("target_not_found");
        if (!wifi.available()) return failure("wifi_unavailable"); List<ScanResult> raw = wifi.scanResults(); if (raw == null) return failure("scan_cache_unavailable");
        long now = SystemClock.elapsedRealtime(); JSONArray rows = new JSONArray(); boolean truncated = false;
        int boot = bootCount(); boolean sameBoot = request != null && boot >= 0 && request.optInt("bootCount", -1) == boot;
        for (ScanResult scan : raw) {
            if (scan == null) continue; if (rows.length() >= limit) { truncated = true; break; }
            long observed = scan.timestamp / 1000; long age = now - observed; boolean clockValid = scan.timestamp > 0 && age >= 0;
            rows.put(PhoneEventJournal.object("ssid", scan.SSID == null ? JSONObject.NULL : scan.SSID, "bssid", scan.BSSID == null ? JSONObject.NULL : scan.BSSID,
                    "rssiDbm", scan.level, "frequencyMhz", scan.frequency, "capabilities", scan.capabilities == null ? "" : scan.capabilities,
                    "observedElapsedMs", clockValid ? observed : JSONObject.NULL, "ageMs", clockValid ? age : JSONObject.NULL, "timestampValid", clockValid,
                    "observedAfterRequestedStart", clockValid && sameBoot ? observed >= request.optLong("startedElapsedMs") : JSONObject.NULL, "connectionVerified", false));
        }
        JSONObject result = PhoneEventJournal.object("ok", true, "code", "ok", "accessPoints", rows, "truncated", truncated, "source", "android.wifi_scan_cache",
                "sourceKind", "device_observation", "ownerUtterance", false, "cachedResults", true, "sameBootAsRequestVerified", sameBoot, "requestCausedScanVerified", false, "clock", "elapsed_realtime", "observedAt", System.currentTimeMillis());
        if (request != null) result.put("request", request);
        if (journal().collectionEnabled()) attachObservation(result, "android.wifi_scan_cache");
        return result;
    }

    private synchronized JSONObject startHotspot(JSONObject args) throws JSONException {
        rejectUnknown(args); JSONObject missing = hotspotGrant(); if (missing != null) return missing;
        if (!wifi.available()) return failure("wifi_unavailable"); if (liveHotspotId != null) return failure("hotspot_busy");
        String id = UUID.randomUUID().toString(); JSONObject receipt = PhoneEventJournal.object("requestId", id, "kind", "hotspot", "state", "pending",
                "startedAt", System.currentTimeMillis(), "completionConfirmed", false, "internetTethering", false, "source", "android.local_only_hotspot");
        if (!save(id, receipt)) return failure("receipt_storage_unavailable"); liveHotspotId = id;
        try { wifi.hotspot(new HotspotEvents() {
            public void started(String ssid, String passphrase) { hotspotStarted(id, ssid, passphrase); }
            public void stopped() { hotspotFinished(id, "stopped", 0); }
            public void failed(int reason) { hotspotFinished(id, "failed", reason); }
        }); }
        catch (RuntimeException rejected) { liveHotspotId = null; receipt.put("state", "dispatch_failed"); save(id, receipt); throw rejected; }
        return result("hotspot_start_requested", id).put("accepted", true).put("completionConfirmed", false).put("internetTethering", false);
    }
    private synchronized void hotspotStarted(String id, String ssid, String password) {
        if (!id.equals(liveHotspotId)) return;
        if (hotspotGrant() != null || !journal().toolAllowed("phone_wifi_hotspot_start")) {
            try { wifi.stopHotspot(); } catch (RuntimeException ignored) { }
            hotspotFinished(id, "permission_revoked", 0); return;
        }
        JSONObject receipt = load(id, "hotspot"); if (receipt == null) return;
        if ("stop_requested".equals(receipt.optString("state"))) { try { wifi.stopHotspot(); } catch (RuntimeException ignored) { } return; }
        put(receipt, "state", "active"); put(receipt, "startedCallbackAt", System.currentTimeMillis()); put(receipt, "completionConfirmed", true);
        put(receipt, "ssid", ssid == null ? JSONObject.NULL : ssid); put(receipt, "passphrase", password == null ? JSONObject.NULL : password);
        put(receipt, "configurationAvailable", ssid != null); put(receipt, "deviceConnectionVerified", false);
        if (journal().collectionEnabled()) attachObservation(receipt, "android.local_only_hotspot"); save(id, receipt);
    }
    private synchronized void hotspotFinished(String id, String state, int reason) {
        if (!id.equals(liveHotspotId)) return; liveHotspotId = null; JSONObject receipt = load(id, "hotspot"); if (receipt == null) return;
        put(receipt, "state", state); put(receipt, "reasonCode", reason); put(receipt, "completedAt", System.currentTimeMillis()); put(receipt, "completionConfirmed", "stopped".equals(state)); save(id, receipt);
    }
    private synchronized JSONObject readHotspot(JSONObject args) throws JSONException {
        rejectUnknown(args, "requestId"); JSONObject missing = hotspotPermissions(); if (missing != null) return missing;
        String id = requestId(args); JSONObject receipt = load(id, "hotspot"); if (receipt == null) return failure("target_not_found");
        if (("pending".equals(receipt.optString("state")) || "active".equals(receipt.optString("state")) || "stop_requested".equals(receipt.optString("state"))) && !id.equals(liveHotspotId)) {
            receipt.put("state", "outcome_unknown_after_process_restart").put("completionConfirmed", false); save(id, receipt);
        }
        return result("ok", id).put("request", receipt).put("automaticReplay", false);
    }
    private synchronized JSONObject stopHotspot(JSONObject args) throws JSONException {
        rejectUnknown(args, "requestId"); String id = requestId(args); JSONObject missing = hotspotGrant(); if (missing != null) return missing;
        JSONObject receipt = load(id, "hotspot"); if (receipt == null) return failure("target_not_found");
        if (!id.equals(liveHotspotId)) return result("hotspot_not_owned_by_this_process", id).put("stopRequested", false).put("completionConfirmed", false);
        wifi.stopHotspot();
        JSONObject current = load(id, "hotspot"); if (id.equals(liveHotspotId) && current != null) { current.put("state", "stop_requested").put("completionConfirmed", false); save(id, current); }
        return result("hotspot_stop_requested", id).put("stopRequested", true).put("completionConfirmed", false);
    }
    public synchronized void stopForRuntime(String reason) {
        if (discovery != null) finishBluetooth(discovery, reason == null ? "runtime_stopped" : reason, false, true);
        if (liveHotspotId != null) { String id = liveHotspotId; try { wifi.stopHotspot(); } catch (RuntimeException ignored) { } hotspotFinished(id, "outcome_unknown_after_runtime_stop", 0); }
    }
    /** Quiesce callbacks before the journal/database backup replacement begins. */
    public synchronized void pauseForBackupRestore() { restorePaused = true; stopForRuntime("backup_restore_paused"); }
    public synchronized void afterBackupRestore() { restorePaused = false; }

    private JSONObject bluetoothGrant() {
        JSONObject missing = bluetoothPermissions(); if (missing != null) return missing;
        return Build.VERSION.SDK_INT >= 31 || locationEnabled() ? null : failure("location_services_disabled");
    }
    private JSONObject bluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) return grants.has(Manifest.permission.BLUETOOTH_SCAN) && grants.has(Manifest.permission.BLUETOOTH_CONNECT) ? null : missing("nearby");
        if (!grants.has(Manifest.permission.BLUETOOTH) || !grants.has(Manifest.permission.BLUETOOTH_ADMIN)) return missing("nearby");
        if (!grants.has(Manifest.permission.ACCESS_FINE_LOCATION)) return missing("location");
        return null;
    }
    private JSONObject wifiScanGrant() {
        JSONObject missing = wifiScanPermissions(); if (missing != null) return missing;
        return locationEnabled() ? null : failure("location_services_disabled");
    }
    private JSONObject wifiScanPermissions() {
        if (!grants.has(Manifest.permission.ACCESS_WIFI_STATE) || !grants.has(Manifest.permission.CHANGE_WIFI_STATE)) return missing("wifi_state");
        if (Build.VERSION.SDK_INT >= 29 ? !grants.has(Manifest.permission.ACCESS_FINE_LOCATION) : !grants.has(Manifest.permission.ACCESS_FINE_LOCATION) && !grants.has(Manifest.permission.ACCESS_COARSE_LOCATION)) return missing("location");
        return null;
    }
    private JSONObject hotspotGrant() {
        JSONObject missing = hotspotPermissions(); if (missing != null) return missing;
        return Build.VERSION.SDK_INT >= 33 || locationEnabled() ? null : failure("location_services_disabled");
    }
    private JSONObject hotspotPermissions() {
        if (!grants.has(Manifest.permission.ACCESS_WIFI_STATE) || !grants.has(Manifest.permission.CHANGE_WIFI_STATE)) return missing("wifi_state");
        if (Build.VERSION.SDK_INT >= 33) return grants.has(Manifest.permission.NEARBY_WIFI_DEVICES) ? null : missing("nearby_wifi");
        return grants.has(Manifest.permission.ACCESS_FINE_LOCATION) ? null : missing("location");
    }
    private boolean locationEnabled() {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE); if (manager == null) return false;
        return Build.VERSION.SDK_INT >= 28 ? manager.isLocationEnabled() : manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }
    private int bootCount() { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
    private boolean save(String id, JSONObject receipt) { String value = receipt.toString(); journal().put(PREFIX + id, value); return value.equals(journal().get(PREFIX + id, "")); }
    private JSONObject load(String id, String kind) {
        try { JSONObject result = new JSONObject(journal().get(PREFIX + id, "{}")); return id.equals(result.optString("requestId")) && kind.equals(result.optString("kind")) ? result : null; }
        catch (JSONException corrupt) { return null; }
    }
    private void attachObservation(JSONObject receipt, String source) {
        JSONObject captured = journal().append("provider_snapshot", source, receipt, System.currentTimeMillis(), null);
        put(receipt, "spoolId", captured.optString("spoolId")); put(receipt, "observationPersisted", captured.optBoolean("observationPersisted"));
    }
    private static JSONObject result(String code, String id) { return PhoneEventJournal.object("ok", true, "code", code, "requestId", id, "completionConfirmed", false); }
    private static JSONObject failure(String code) { return PhoneEventJournal.object("ok", false, "code", code, "completionConfirmed", false); }
    private static JSONObject missing(String id) { return PhoneEventJournal.object("ok", false, "code", "permission_missing", "permissionId", id, "completionConfirmed", false); }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
    private static void rejectUnknown(JSONObject args, String... allowed) { for (java.util.Iterator<String> keys = args.keys(); keys.hasNext();) { String key = keys.next(); boolean found = false; for (String permitted : allowed) if (key.equals(permitted)) found = true; if (!found) throw new IllegalArgumentException(); } }
    private static String requestId(JSONObject args) throws JSONException { Object id = args.get("requestId"); if (!(id instanceof String) || !((String) id).matches("[0-9a-f-]{36}")) throw new IllegalArgumentException(); return (String) id; }
    private static int integer(JSONObject args, String key, int fallback, int min, int max) throws JSONException {
        if (!args.has(key)) return fallback; Object raw = args.get(key); if (!(raw instanceof Number)) throw new IllegalArgumentException(); double n = ((Number) raw).doubleValue();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < min || n > max) throw new IllegalArgumentException(); return (int) n;
    }
    private static JSONObject properties(String... pairs) { JSONObject result = new JSONObject(); try { for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], new JSONObject().put("type", pairs[i + 1])); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } return result; }
    private static void tool(JSONArray output, String name, String description, JSONObject properties, String... required) {
        JSONArray list = new JSONArray(); for (String key : required) list.put(key);
        output.put(PhoneEventJournal.object("name", name, "description", description, "inputSchema", PhoneEventJournal.object("type", "object", "properties", properties, "required", list, "additionalProperties", false)));
    }

    private static final class AndroidBluetooth implements BluetoothBackend {
        private final Context context; private final BluetoothAdapter adapter; private BroadcastReceiver receiver;
        AndroidBluetooth(Context context) { this.context = context; BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE); adapter = manager == null ? null : manager.getAdapter(); }
        public boolean available() { return adapter != null; }
        public boolean enabled() { return adapter != null && adapter.isEnabled(); }
        public boolean discovering() {
            try { return adapter != null && adapter.isDiscovering(); }
            catch (SecurityException denied) {
                // Keep denial distinct from "no discovery" for the typed tool-result boundary.
                throw new SecurityException("Android denied Bluetooth discovery state access.", denied);
            }
        }
        public boolean start(BluetoothEvents events) {
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) { events.finished(); return; }
                    if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;
                    BluetoothDevice device = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class) : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device == null) return;
                    try { events.device(device.getAddress(), device.getName(), device.getType(), intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)); }
                    catch (SecurityException revoked) { events.finished(); }
                }
            };
            IntentFilter filter = new IntentFilter(); filter.addAction(BluetoothDevice.ACTION_FOUND); filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            // These are Android protected broadcasts, also emitted by the privileged Bluetooth app.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED); else context.registerReceiver(receiver, filter);
            try { return adapter.startDiscovery(); }
            catch (SecurityException denied) {
                try { detach(); } catch (RuntimeException cleanupUnavailable) { }
                throw new SecurityException("Android denied Bluetooth discovery start.", denied);
            }
        }
        public boolean cancel() {
            try { return adapter != null && adapter.cancelDiscovery(); }
            catch (SecurityException denied) { throw new SecurityException("Android denied Bluetooth discovery cancellation.", denied); }
        }
        public void detach() { if (receiver != null) { BroadcastReceiver old = receiver; receiver = null; context.unregisterReceiver(old); } }
    }
    private static final class AndroidWifi implements WifiBackend {
        private final WifiManager manager; private final Handler main = new Handler(Looper.getMainLooper()); private volatile HotspotAttempt attempt;
        private static final class HotspotAttempt { volatile boolean canceled; volatile WifiManager.LocalOnlyHotspotReservation reservation; }
        AndroidWifi(Context context) { manager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE); }
        public boolean available() { return manager != null; }
        public boolean scan() { return manager.startScan(); }
        public List<ScanResult> scanResults() {
            try { return manager.getScanResults(); }
            catch (SecurityException denied) {
                // An OS denial is not evidence of an empty or fresh Wi-Fi cache.
                throw new SecurityException("Android denied Wi-Fi scan cache access.", denied);
            }
        }
        public void hotspot(HotspotEvents events) {
            HotspotAttempt work = new HotspotAttempt(); attempt = work;
            try { manager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation started) {
                    if (attempt != work || work.canceled) { started.close(); return; }
                    work.reservation = started;
                    if (attempt != work || work.canceled) { started.close(); work.reservation = null; return; }
                    if (Build.VERSION.SDK_INT >= 30) { SoftApConfiguration configuration = started.getSoftApConfiguration(); events.started(configuration == null ? null : configuration.getSsid(), configuration == null ? null : configuration.getPassphrase()); }
                    else { WifiConfiguration configuration = started.getWifiConfiguration(); events.started(configuration == null ? null : configuration.SSID, configuration == null ? null : configuration.preSharedKey); }
                }
                @Override public void onStopped() { if (attempt == work) { work.reservation = null; events.stopped(); } }
                @Override public void onFailed(int reason) { if (attempt == work && !work.canceled) { work.reservation = null; events.failed(reason); } }
            }, main); }
            catch (SecurityException denied) {
                work.canceled = true;
                if (attempt == work) attempt = null;
                if (work.reservation != null) { try { work.reservation.close(); } catch (RuntimeException cleanupUnavailable) { } work.reservation = null; }
                // A late callback closes this exact reservation; it cannot become a new active one.
                throw new SecurityException("Android denied local-only hotspot start.", denied);
            }
        }
        public void stopHotspot() { HotspotAttempt current = attempt; if (current != null) { current.canceled = true; if (current.reservation != null) { current.reservation.close(); current.reservation = null; } } }
    }
}
