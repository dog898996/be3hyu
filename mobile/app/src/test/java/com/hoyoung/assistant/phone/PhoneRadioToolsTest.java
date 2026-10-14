package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowBluetoothDevice;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Local injected radio callbacks; native journal/SQLite persistence. No real RF scan is requested. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneRadioToolsTest {
    private Context context; private Application application; private HoyoungStore store; private PhoneEventJournal journal;
    private FakeBluetooth bluetooth; private FakeWifi wifi; private PhoneRadioTools tools;
    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext(); application = (Application) context;
        store = new HoyoungStore(context); journal = PhoneEventJournal.bind(context, store); journal.refreshPolicyBlocking();
        bluetooth = new FakeBluetooth(); wifi = new FakeWifi(); tools = new PhoneRadioTools(context, journal, bluetooth, wifi);
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.NEARBY_WIFI_DEVICES);
        Shadows.shadowOf((LocationManager) context.getSystemService(Context.LOCATION_SERVICE)).setLocationEnabled(true);
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
    }
    @After public void cleanup() { tools.stopForRuntime("test_finished"); journal.closeWorker(); journal.awaitClosedForTests(); store.close(); }
    private void allowBluetooth() { Shadows.shadowOf(application).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT); }
    private void allowWifi() { Shadows.shadowOf(application).grantPermissions(Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.ACCESS_FINE_LOCATION); }
    private void allowHotspot() { Shadows.shadowOf(application).grantPermissions(Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.NEARBY_WIFI_DEVICES); }
    private String startBluetooth() throws Exception { JSONObject result = tools.execute("phone_bluetooth_scan", new JSONObject()); assertTrue(result.toString(), result.getBoolean("ok")); return result.getString("requestId"); }
    private JSONObject readBluetooth(String id) throws Exception { return tools.execute("phone_bluetooth_scan_read", new JSONObject().put("requestId", id)).getJSONObject("request"); }
    private JSONObject readHotspot(String id) throws Exception { return tools.execute("phone_wifi_hotspot_read", new JSONObject().put("requestId", id)).getJSONObject("request"); }

    @Test public void catalogContainsEightRealNamesAndStrictSchemas() throws Exception {
        JSONArray catalog = new JSONArray(); tools.appendCatalog(catalog); assertEquals(8, catalog.length());
        for (int i = 0; i < catalog.length(); i++) { JSONObject item = catalog.getJSONObject(i); assertTrue(tools.supports(item.getString("name"))); assertFalse(item.getJSONObject("inputSchema").getBoolean("additionalProperties")); }
    }
    @Test public void bluetoothRequiresBothNearbyGrantsBeforeAnyDiscovery() throws Exception {
        assertEquals("permission_missing", tools.execute("phone_bluetooth_scan", new JSONObject()).getString("code")); assertEquals(0, bluetooth.starts);
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.BLUETOOTH_SCAN);
        assertEquals("permission_missing", tools.execute("phone_bluetooth_scan", new JSONObject()).getString("code")); assertEquals(0, bluetooth.starts);
    }
    @Test public void bluetoothRecordsOnlyActualUniqueCallbacksAndDoesNotClaimEveryNearbyDevice() throws Exception {
        allowBluetooth(); String id = startBluetooth(); assertEquals("pending", readBluetooth(id).getString("state"));
        bluetooth.events.device("11:22:33:44:55:66", "Synthetic headphones", 1, -50);
        bluetooth.events.device("11:22:33:44:55:66", "Duplicate", 1, -50);
        bluetooth.events.device("invalid", "Ignored", 1, -50); bluetooth.events.finished();
        JSONObject result = readBluetooth(id); assertEquals("observed", result.getString("state")); assertTrue(result.getBoolean("scanEndedVerified"));
        assertEquals(1, result.getJSONArray("devices").length()); assertFalse(result.getBoolean("allNearbyDevicesEnumerated")); assertFalse(result.getJSONArray("devices").getJSONObject(0).getBoolean("connectedVerified"));
        assertTrue(result.getBoolean("observationPersisted")); assertEquals(1, bluetooth.detaches);
    }
    @Test public void bluetoothLimitAndTimeoutAreExplicitAndDoNotDiscardWholeHistorySilently() throws Exception {
        allowBluetooth(); JSONObject start = tools.execute("phone_bluetooth_scan", new JSONObject().put("limit", 1).put("timeoutMs", 1000)); String id = start.getString("requestId");
        bluetooth.events.device("11:22:33:44:55:66", "One", 1, -50); bluetooth.events.device("11:22:33:44:55:77", "Two", 1, -50);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001)); JSONObject receipt = readBluetooth(id);
        assertEquals("timed_out", receipt.getString("state")); assertTrue(receipt.getBoolean("truncated")); assertFalse(receipt.getBoolean("completionConfirmed")); assertTrue(receipt.getBoolean("cancelAccepted")); assertEquals(1, bluetooth.cancels);
    }
    @Test public void existingForeignDiscoveryIsNotTakenOverOrCanceled() throws Exception {
        allowBluetooth(); bluetooth.discovering = true;
        assertEquals("scan_busy", tools.execute("phone_bluetooth_scan", new JSONObject()).getString("code")); tools.stopForRuntime("test"); assertEquals(0, bluetooth.starts); assertEquals(0, bluetooth.cancels);
    }
    @Test public void rejectedDiscoveryProducesReceiptWithNoFabricatedAcceptance() throws Exception {
        allowBluetooth(); bluetooth.accepted = false; JSONObject result = tools.execute("phone_bluetooth_scan", new JSONObject());
        assertEquals("scan_rejected_or_throttled", result.getString("code")); JSONObject receipt = readBluetooth(result.getString("requestId")); assertFalse(receipt.getBoolean("scanAccepted")); assertFalse(receipt.getBoolean("completionConfirmed"));
    }
    @Test public void bluetoothCancellationIsRequestScopedAndNeverInventsCompletion() throws Exception {
        allowBluetooth(); String id = startBluetooth();
        assertEquals("target_not_found", tools.execute("phone_bluetooth_scan_cancel", new JSONObject().put("requestId", "00000000-0000-0000-0000-000000000000")).getString("code")); assertEquals(0, bluetooth.cancels);
        JSONObject canceled = tools.execute("phone_bluetooth_scan_cancel", new JSONObject().put("requestId", id)); assertEquals("bluetooth_cancel_requested", canceled.getString("code")); assertFalse(canceled.getBoolean("completionConfirmed"));
        bluetooth.events.device("11:22:33:44:55:66", "Late", 1, -50); assertEquals(0, readBluetooth(id).getJSONArray("devices").length());
    }
    @Test public void revokedBluetoothGrantStopsNewObservationsAndDoesNotExposePriorData() throws Exception {
        allowBluetooth(); String id = startBluetooth(); bluetooth.events.device("11:22:33:44:55:66", "Before", 1, -50);
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT); bluetooth.events.device("11:22:33:44:55:77", "After", 1, -50);
        assertEquals("permission_missing", tools.execute("phone_bluetooth_scan_read", new JSONObject().put("requestId", id)).getString("code"));
        assertEquals("permission_missing", tools.execute("phone_bluetooth_scan_cancel", new JSONObject().put("requestId", id)).getString("code"));
        allowBluetooth(); JSONObject receipt = readBluetooth(id); assertEquals("permission_revoked", receipt.getString("state")); assertEquals(1, receipt.getJSONArray("devices").length());
    }
    @Test @Config(shadows = AppOpsDeniedBluetoothDeviceShadow.class)
    public void androidDeviceAccessorDenialWithRuntimeGrantsNeverFabricatesDiscoveryCompletion() throws Exception {
        allowBluetooth();
        BluetoothAdapter adapter = ((BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        assertNotNull(adapter); Shadows.shadowOf(adapter).setState(BluetoothAdapter.STATE_ON);
        Class<?> backendClass = Class.forName(PhoneRadioTools.class.getName() + "$AndroidBluetooth");
        java.lang.reflect.Constructor<?> constructor = backendClass.getDeclaredConstructor(Context.class); constructor.setAccessible(true);
        PhoneRadioTools.BluetoothBackend nativeBackend = (PhoneRadioTools.BluetoothBackend) constructor.newInstance(context);
        tools = new PhoneRadioTools(context, journal, nativeBackend, wifi);
        String id = startBluetooth();
        java.lang.reflect.Field receiverField = backendClass.getDeclaredField("receiver"); receiverField.setAccessible(true);
        BroadcastReceiver receiver = (BroadcastReceiver) receiverField.get(nativeBackend); assertNotNull(receiver);
        BluetoothDevice denied = adapter.getRemoteDevice("11:22:33:44:55:66");
        // The stock shadow only enforces revoked runtime grants. This independent
        // accessor denial models AppOps/OEM policy while checkSelfPermission is granted.
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN));
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT));
        assertThrows(SecurityException.class, denied::getName);
        receiver.onReceive(context, new Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE, denied));
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN));
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT));
        JSONObject receipt = readBluetooth(id);
        assertEquals("android_permission_denied", receipt.getString("state"));
        assertFalse(receipt.getBoolean("scanEndedVerified")); assertFalse(receipt.getBoolean("completionConfirmed"));
        assertEquals(0, receipt.getJSONArray("devices").length()); assertTrue(receipt.getBoolean("cancelRequested"));
        assertNull(receiverField.get(nativeBackend)); assertFalse(adapter.isDiscovering());
        receiver.onReceive(context, new Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED));
        assertEquals("android_permission_denied", readBluetooth(id).getString("state"));
        assertFalse(readBluetooth(id).getBoolean("completionConfirmed"));
    }
    @Test public void interruptedBluetoothReceiptRemainsUnknownAndNeverRestartsTheRadio() throws Exception {
        allowBluetooth(); String id = startBluetooth(); FakeBluetooth recoveredBackend = new FakeBluetooth(); PhoneRadioTools recovered = new PhoneRadioTools(context, journal, recoveredBackend, wifi);
        JSONObject result = recovered.execute("phone_bluetooth_scan_read", new JSONObject().put("requestId", id));
        assertEquals("outcome_unknown_after_process_restart", result.getJSONObject("request").getString("state")); assertFalse(result.getBoolean("automaticReplay")); assertEquals(0, recoveredBackend.starts);
    }
    @Test public void ownerToolSwitchAloneDisablesRadioDispatch() throws Exception {
        allowBluetooth(); store.putSetting("toolPermissions", "{\"phone_bluetooth_scan\":false}"); journal.applyPermissionPolicyFromOwner("{\"phone_bluetooth_scan\":false}", true);
        assertEquals("permission_disabled", tools.execute("phone_bluetooth_scan", new JSONObject()).getString("code")); assertEquals(0, bluetooth.starts);
    }
    @Test public void wifiScanRequiresPreciseLocationAndLocationServicesDespiteNearbyGrant() throws Exception {
        allowHotspot();
        assertEquals("permission_missing", tools.execute("phone_wifi_scan", new JSONObject()).getString("code")); assertEquals(0, wifi.scans);
        allowWifi(); Shadows.shadowOf((LocationManager) context.getSystemService(Context.LOCATION_SERVICE)).setLocationEnabled(false);
        assertEquals("location_services_disabled", tools.execute("phone_wifi_scan", new JSONObject()).getString("code")); assertEquals(0, wifi.scans);
    }
    @Test public void wifiThrottlingIsReportedWithoutClaimingFreshResults() throws Exception {
        allowWifi(); wifi.scanAccepted = false; JSONObject result = tools.execute("phone_wifi_scan", new JSONObject());
        assertFalse(result.getBoolean("ok")); assertEquals("scan_rejected_or_throttled", result.getString("code")); assertFalse(result.getBoolean("freshResultsConfirmed")); assertFalse(result.getBoolean("completionConfirmed"));
    }
    @Test public void wifiCacheReportsAgeAndCanDistinguishOlderAndNewerObservedTimestamps() throws Exception {
        allowWifi(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); String id = tools.execute("phone_wifi_scan", new JSONObject()).getString("requestId"); long started = SystemClock.elapsedRealtime();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        wifi.results = Arrays.asList(scan("Old", Math.max(1, started - 1) * 1000), scan("New", SystemClock.elapsedRealtime() * 1000));
        JSONObject result = tools.execute("phone_wifi_scan_read", new JSONObject().put("requestId", id)); assertTrue(result.getBoolean("cachedResults")); assertFalse(result.getBoolean("requestCausedScanVerified")); assertTrue(result.getBoolean("sameBootAsRequestVerified"));
        JSONArray rows = result.getJSONArray("accessPoints"); assertFalse(rows.getJSONObject(0).getBoolean("observedAfterRequestedStart")); assertTrue(rows.getJSONObject(1).getBoolean("observedAfterRequestedStart")); assertTrue(rows.getJSONObject(0).getLong("ageMs") > rows.getJSONObject(1).getLong("ageMs"));
    }
    @Test public void wifiTimestampRelationBecomesUnknownAcrossBootsOrWithInvalidClock() throws Exception {
        allowWifi(); String id = tools.execute("phone_wifi_scan", new JSONObject()).getString("requestId"); Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 8);
        wifi.results = Arrays.asList(scan("Current", SystemClock.elapsedRealtime() * 1000), scan("Future", (SystemClock.elapsedRealtime() + 10000) * 1000));
        JSONObject result = tools.execute("phone_wifi_scan_read", new JSONObject().put("requestId", id)); assertFalse(result.getBoolean("sameBootAsRequestVerified"));
        assertTrue(result.getJSONArray("accessPoints").getJSONObject(0).isNull("observedAfterRequestedStart")); assertTrue(result.getJSONArray("accessPoints").getJSONObject(1).isNull("ageMs"));
    }
    @Test public void hotspotActuallyUsesNearbyWifiGrantAndOnlyReportsActiveOnAndroidCallback() throws Exception {
        allowWifi(); assertEquals("permission_missing", tools.execute("phone_wifi_hotspot_start", new JSONObject()).getString("code")); assertEquals(0, wifi.hotspots);
        allowHotspot(); String id = tools.execute("phone_wifi_hotspot_start", new JSONObject()).getString("requestId"); assertEquals("pending", readHotspot(id).getString("state")); assertFalse(readHotspot(id).getBoolean("internetTethering"));
        wifi.events.started("SYNTHETIC-LOCAL-ONLY", "synthetic-only-password"); JSONObject receipt = readHotspot(id); assertEquals("active", receipt.getString("state")); assertTrue(receipt.getBoolean("completionConfirmed")); assertEquals("SYNTHETIC-LOCAL-ONLY", receipt.getString("ssid")); assertFalse(receipt.getBoolean("deviceConnectionVerified"));
    }
    @Test public void hotspotFailureDoesNotBecomeAnActiveNetwork() throws Exception {
        allowHotspot(); String id = tools.execute("phone_wifi_hotspot_start", new JSONObject()).getString("requestId"); wifi.events.failed(4);
        JSONObject result = readHotspot(id); assertEquals("failed", result.getString("state")); assertFalse(result.getBoolean("completionConfirmed")); assertFalse(result.has("ssid"));
    }
    @Test public void hotspotStopIsRequestedUntilCallbackAndCannotControlUnknownReservation() throws Exception {
        allowHotspot(); String id = tools.execute("phone_wifi_hotspot_start", new JSONObject()).getString("requestId"); wifi.events.started("SYNTHETIC", "synthetic");
        JSONObject stopped = tools.execute("phone_wifi_hotspot_stop", new JSONObject().put("requestId", id)); assertFalse(stopped.getBoolean("completionConfirmed")); assertEquals("stop_requested", readHotspot(id).getString("state")); assertEquals(1, wifi.stops);
        wifi.events.stopped(); assertEquals("stopped", readHotspot(id).getString("state")); assertTrue(readHotspot(id).getBoolean("completionConfirmed"));
        assertFalse(tools.execute("phone_wifi_hotspot_stop", new JSONObject().put("requestId", id)).getBoolean("stopRequested")); assertEquals(1, wifi.stops);
    }
    @Test public void stoppingBeforeHotspotCallbackNeverPublishesLateCredentialsAsAnActiveNetwork() throws Exception {
        allowHotspot(); String id = tools.execute("phone_wifi_hotspot_start", new JSONObject()).getString("requestId"); tools.execute("phone_wifi_hotspot_stop", new JSONObject().put("requestId", id));
        wifi.events.started("LATE-SYNTHETIC", "late synthetic"); JSONObject result = readHotspot(id); assertEquals("stop_requested", result.getString("state")); assertFalse(result.has("ssid")); assertEquals(2, wifi.stops);
    }
    @Test public void backupRestoreQuiescesAllCallbacksAndDoesNotRewriteRestoredRequestNamespace() throws Exception {
        allowBluetooth(); allowHotspot(); String bt = startBluetooth(); PhoneRadioTools.BluetoothEvents oldBluetooth = bluetooth.events;
        String hotspot = tools.execute("phone_wifi_hotspot_start", new JSONObject()).getString("requestId"); PhoneRadioTools.HotspotEvents oldHotspot = wifi.events;
        tools.pauseForBackupRestore(); String btBefore = journal.get("radio.request." + bt, ""), hotspotBefore = journal.get("radio.request." + hotspot, "");
        oldBluetooth.device("11:22:33:44:55:66", "Late", 1, -50); oldBluetooth.finished(); oldHotspot.started("Late", "synthetic"); oldHotspot.stopped();
        assertEquals(btBefore, journal.get("radio.request." + bt, "")); assertEquals(hotspotBefore, journal.get("radio.request." + hotspot, ""));
        assertEquals("restore_in_progress", tools.execute("phone_wifi_hotspot_start", new JSONObject()).getString("code")); tools.afterBackupRestore();
        String next = tools.execute("phone_wifi_hotspot_start", new JSONObject()).getString("requestId"); oldHotspot.stopped(); assertEquals("pending", readHotspot(next).getString("state"));
    }
    @Test public void malformedRadioArgsNeverReachActualBackend() throws Exception {
        allowBluetooth(); allowWifi();
        assertEquals("invalid_arguments", tools.execute("phone_bluetooth_scan", new JSONObject().put("timeoutMs", 999)).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_bluetooth_scan", new JSONObject().put("limit", "100")).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_wifi_scan", new JSONObject().put("silentToggle", true)).getString("code"));
        assertEquals(0, bluetooth.starts); assertEquals(0, wifi.scans);
    }
    @Test public void disablingLocationServicesPreservesStoredWifiHistoryUntilActualGrantsOrOwnerSwitchesAreRevoked() throws Exception {
        allowWifi(); wifi.results = Collections.singletonList(scan("Stored synthetic AP", Math.max(1, SystemClock.elapsedRealtime()) * 1000));
        JSONObject observed = tools.execute("phone_wifi_scan_read", new JSONObject()); assertTrue(observed.toString(), observed.getBoolean("ok"));
        journal.ingestPendingForTests(); JSONObject event = journal.events("provider_snapshot", "android.wifi_scan_cache", 0, 10).getJSONObject(0);
        Shadows.shadowOf((LocationManager) context.getSystemService(Context.LOCATION_SERVICE)).setLocationEnabled(false);
        assertTrue(tools.canReadArchivedResult("phone_wifi_scan_read", observed)); assertTrue(tools.canReadStoredEvent(event));
        assertEquals("location_services_disabled", tools.execute("phone_wifi_scan", new JSONObject()).getString("code"));
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION); assertFalse(tools.canReadArchivedResult("phone_wifi_scan_read", observed)); assertFalse(tools.canReadStoredEvent(event));
        allowWifi(); store.putSetting("toolPermissions", "{\"phone_wifi_scan_read\":false}"); journal.applyPermissionPolicyFromOwner("{\"phone_wifi_scan_read\":false}", true); assertFalse(tools.canReadStoredEvent(event));
    }
    @Test @Config(sdk = 28) public void legacyBluetoothStoredObservationsRemainReadableWithLocationOffAndDisappearOnlyWhenAccessIsRevoked() throws Exception {
        grantLegacyBluetooth(); String id = startBluetooth(); bluetooth.events.device("11:22:33:44:55:66", "Stored synthetic Bluetooth", 1, -50); bluetooth.events.finished();
        journal.ingestPendingForTests(); JSONObject event = journal.events("provider_snapshot", "android.bluetooth_discovery", 0, 10).getJSONObject(0);
        Shadows.shadowOf((LocationManager) context.getSystemService(Context.LOCATION_SERVICE)).setLocationEnabled(false);
        assertEquals("observed", readBluetooth(id).getString("state")); assertTrue(tools.canReadArchivedResult("phone_bluetooth_scan_read", new JSONObject())); assertTrue(tools.canReadStoredEvent(event));
        assertEquals("location_services_disabled", tools.execute("phone_bluetooth_scan", new JSONObject()).getString("code"));
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION); assertFalse(tools.canReadArchivedResult("phone_bluetooth_scan_read", new JSONObject())); assertFalse(tools.canReadStoredEvent(event));
        grantLegacyBluetooth(); store.putSetting("toolPermissions", "{\"phone_bluetooth_scan_read\":false}"); journal.applyPermissionPolicyFromOwner("{\"phone_bluetooth_scan_read\":false}", true); assertFalse(tools.canReadStoredEvent(event));
    }
    private void grantLegacyBluetooth() { Shadows.shadowOf(application).grantPermissions(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION); }
    @Implements(BluetoothDevice.class)
    public static final class AppOpsDeniedBluetoothDeviceShadow extends ShadowBluetoothDevice {
        @Implementation @Override protected String getName() {
            throw new SecurityException("Synthetic AppOps/OEM accessor denial despite granted runtime permissions");
        }
    }
    private static ScanResult scan(String ssid, long timestamp) { ScanResult result = new ScanResult(); result.SSID = ssid; result.BSSID = "11:22:33:44:55:66"; result.level = -40; result.frequency = 2412; result.timestamp = timestamp; return result; }
    private static final class FakeBluetooth implements PhoneRadioTools.BluetoothBackend {
        PhoneRadioTools.BluetoothEvents events; int starts, cancels, detaches; boolean accepted = true, discovering;
        public boolean available() { return true; } public boolean enabled() { return true; } public boolean discovering() { return discovering; }
        public boolean start(PhoneRadioTools.BluetoothEvents events) { this.events = events; starts++; return accepted; }
        public boolean cancel() { cancels++; return true; } public void detach() { detaches++; }
    }
    private static final class FakeWifi implements PhoneRadioTools.WifiBackend {
        PhoneRadioTools.HotspotEvents events; int scans, hotspots, stops; boolean scanAccepted = true; List<ScanResult> results = Collections.emptyList();
        public boolean available() { return true; } public boolean scan() { scans++; return scanAccepted; } public List<ScanResult> scanResults() { return results; }
        public void hotspot(PhoneRadioTools.HotspotEvents events) { this.events = events; hotspots++; } public void stopHotspot() { stops++; }
    }
}
