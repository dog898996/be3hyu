package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Application;
import android.app.Notification;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class PhoneContextTest {
    private Context context;
    private HoyoungStore store;
    private PhoneTools tools;
    private PhoneEventJournal journal;
    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); clearTestJournal(new java.io.File(context.getFilesDir(), "device-journal"));
        store = new HoyoungStore(context); journal = PhoneEventJournal.bind(context, store); tools = new PhoneTools(context, store); journal.ingestPendingForTests();
    }
    @After public void tearDown() { journal.ingestPendingForTests(); journal.closeWorker(); journal.awaitClosedForTests(); store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); }
    private void notificationGrant() { Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", new ComponentName(context, HoyoungNotificationListener.class).flattenToString()); }
    private void usageGrant() { AppOpsManager manager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE); Shadows.shadowOf(manager).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName(), AppOpsManager.MODE_ALLOWED); }
    private StatusBarNotification notice(int number) {
        Notification notification = new Notification(); notification.extras.putCharSequence(Notification.EXTRA_TITLE, "Synthetic " + number); notification.extras.putCharSequence(Notification.EXTRA_TEXT, "test notification " + number);
        return new StatusBarNotification("example.chat", "example.chat", number, null, Process.myUid(), 0, 0, notification, Process.myUserHandle(), System.currentTimeMillis() + number);
    }

    @Test public void notificationsBeyondOldRingLimitRemainDurableAndPagedWithProvenance() throws Exception {
        notificationGrant(); HoyoungNotificationListener listener = Robolectric.buildService(HoyoungNotificationListener.class).create().get();
        for (int i = 0; i < 310; i++) listener.onNotificationPosted(notice(i));
        journal.ingestPendingForTests();
        JSONArray records = store.deviceEvents("notification", null, 0, 500); assertEquals(310, records.length());
        long firstId = records.getJSONObject(0).getLong("id"), lastId = records.getJSONObject(309).getLong("id");
        JSONObject page = tools.execute("phone_context_read", new JSONObject().put("category", "notification").put("limit", 100));
        assertEquals(100, page.getJSONArray("events").length()); assertTrue(page.getBoolean("hasMore"));
        assertEquals(firstId, page.getJSONArray("events").getJSONObject(0).getLong("id"));
        assertEquals("android.notification_listener", records.getJSONObject(0).getString("source"));
        journal.closeWorker(); journal.awaitClosedForTests(); store.close(); store = new HoyoungStore(context); journal = PhoneEventJournal.bind(context, store); tools = new PhoneTools(context, store); journal.ingestPendingForTests();
        assertEquals(310, store.deviceEvents("notification", null, 0, 500).length());
        assertEquals(lastId, tools.execute("phone_context_read", new JSONObject().put("eventId", lastId)).getJSONObject("event").getLong("id"));
    }

    @Test public void repeatedPostedNotificationIsDeduplicatedButChangedPayloadIsRetained() throws Exception {
        notificationGrant(); HoyoungNotificationListener listener = Robolectric.buildService(HoyoungNotificationListener.class).create().get();
        StatusBarNotification original = notice(1); listener.onNotificationPosted(original); listener.onNotificationPosted(original);
        journal.ingestPendingForTests();
        assertEquals(1, store.deviceEvents("notification", null, 0, 500).length());
        original.getNotification().extras.putCharSequence(Notification.EXTRA_TEXT, "updated text"); listener.onNotificationPosted(original); journal.ingestPendingForTests();
        assertEquals(2, store.deviceEvents("notification", null, 0, 500).length());
    }

    @Test public void ownReplyNotificationsRetainAssistantProvenanceWithoutExternalInputOrReadClaims() throws Exception {
        notificationGrant(); HoyoungNotificationListener listener = Robolectric.buildService(HoyoungNotificationListener.class).create().get();
        long assistantMessageId = store.addMessage("assistant", "assistant-only-synthetic", null).getLong("id");
        Notification notification = new Notification(); notification.extras.putCharSequence(Notification.EXTRA_TEXT, "assistant-only-synthetic");
        notification.extras.putString("com.hoyoung.assistant.source_kind", "assistant_reply"); notification.extras.putLong("com.hoyoung.assistant.assistant_message_id", assistantMessageId);
        notification.extras.putString("com.hoyoung.assistant.source_message_hash",store.getMessage(assistantMessageId).getString("content_hash"));
        notification.extras.putString("com.hoyoung.assistant.reply_stage", "proactive");
        StatusBarNotification own = new StatusBarNotification(context.getPackageName(), context.getPackageName(), 712, null, Process.myUid(), 0, 0, notification, Process.myUserHandle(), System.currentTimeMillis());
        listener.onNotificationPosted(own);
        StatusBarNotification forgedExternal = notice(713); forgedExternal.getNotification().extras.putString("com.hoyoung.assistant.source_kind", "assistant_reply"); forgedExternal.getNotification().extras.putLong("com.hoyoung.assistant.assistant_message_id", assistantMessageId);
        listener.onNotificationPosted(forgedExternal); journal.ingestPendingForTests();
        JSONArray rows = store.deviceEvents("notification", null, 0, 100); assertEquals(2, rows.length());
        JSONObject reply = rows.getJSONObject(0).getJSONObject("payload");
        assertEquals("assistant_reply", reply.getString("sourceKind")); assertEquals(assistantMessageId, reply.getLong("assistantMessageId")); assertEquals("proactive", reply.getString("replyStage"));
        assertEquals(store.getMessage(assistantMessageId).getString("content_hash"),reply.getString("assistantSourceHash"));
        assertTrue(reply.getBoolean("assistantGenerated")); assertFalse(reply.getBoolean("ownerReadConfirmed")); assertFalse(reply.getBoolean("isOwnerInstruction")); assertFalse(reply.getBoolean("externalInputTriggerEligible"));
        JSONObject external = rows.getJSONObject(1).getJSONObject("payload"); assertEquals("external_notification", external.getString("sourceKind")); assertFalse(external.getBoolean("assistantGenerated")); assertFalse(external.has("assistantMessageId"));
        assertTrue(tools.canReadStoredSource(rows.getJSONObject(0))); assertFalse(tools.canUseImplicitMemorySource(rows.getJSONObject(0)));
        assertTrue(tools.canUseImplicitMemorySource(rows.getJSONObject(1)));
        assertFalse(tools.contextSummary("알림 메시지 확인", 12000).contains("assistant-only-synthetic"));
        assertEquals("assistant_reply", tools.execute("phone_context_read", new JSONObject().put("eventId", rows.getJSONObject(0).getLong("id"))).getJSONObject("event").getJSONObject("payload").getString("sourceKind"));
        Notification status = new Notification(); status.extras.putCharSequence(Notification.EXTRA_TEXT, "own-service-status-only-synthetic");
        listener.onNotificationPosted(new StatusBarNotification(context.getPackageName(), context.getPackageName(), 714, null, Process.myUid(), 0, 0, status, Process.myUserHandle(), System.currentTimeMillis()));
        journal.ingestPendingForTests();
        JSONObject statusRow = store.deviceEvents("notification", null, 0, 100).getJSONObject(2);
        assertEquals("assistant_app_notification", statusRow.getJSONObject("payload").getString("sourceKind"));
        assertFalse(statusRow.getJSONObject("payload").getBoolean("externalInputTriggerEligible"));
        assertTrue(tools.canReadStoredSource(statusRow)); assertFalse(tools.canUseImplicitMemorySource(statusRow));
        assertFalse(tools.contextSummary("알림 메시지 확인", 12000).contains("own-service-status-only-synthetic"));
    }

    @Test public void ownReplyReferencingMissingCanonicalMessageStaysArchivedButUnreadable() throws Exception {
        notificationGrant(); HoyoungNotificationListener listener = Robolectric.buildService(HoyoungNotificationListener.class).create().get();
        Notification notification = new Notification(); notification.extras.putCharSequence(Notification.EXTRA_TEXT, "missing-parent-synthetic");
        notification.extras.putString("com.hoyoung.assistant.source_kind", "assistant_reply");
        notification.extras.putLong("com.hoyoung.assistant.assistant_message_id", 987654);
        listener.onNotificationPosted(new StatusBarNotification(context.getPackageName(), context.getPackageName(), 987654,
                null, Process.myUid(), 0, 0, notification, Process.myUserHandle(), System.currentTimeMillis()));
        journal.ingestPendingForTests();
        JSONArray rows = store.deviceEvents("notification", null, 0, 100); assertEquals(1, rows.length());
        JSONObject row = rows.getJSONObject(0); assertNotNull(store.getDeviceEvent(row.getLong("id")));
        assertFalse("Missing claimed reply source cannot become readable merely through the notification archive", tools.canReadStoredSource(row));
        assertFalse(tools.canUseImplicitMemorySource(row));
        assertFalse(tools.execute("phone_context_read", new JSONObject().put("eventId", row.getLong("id"))).getBoolean("ok"));
    }

    @Test public void notificationGrantRevocationAndAppDisableFilterPreviouslySavedData() throws Exception {
        notificationGrant(); journal.append("notification", "android.notification_listener", new JSONObject().put("text", "private synthetic"), 123, null); journal.ingestPendingForTests(); long id = store.deviceEvents("notification", null, 0, 100).getJSONObject(0).getLong("id");
        assertTrue(tools.execute("phone_context_read", new JSONObject().put("eventId", id)).getBoolean("ok"));
        store.putSetting("toolPermissions", "{\"phone_notifications_read\":false}");
        assertEquals("permission_disabled", tools.execute("phone_context_read", new JSONObject().put("eventId", id)).getString("code"));
        store.putSetting("toolPermissions", "{}"); Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", "");
        assertFalse(tools.execute("phone_context_read", new JSONObject().put("eventId", id)).getBoolean("ok"));
        assertNotNull(store.getDeviceEvent(id));
    }

    @Test public void legacyNotificationMigrationKeepsOriginalsAndExplicitlyMarksIrrecoverableOlderHistory() throws Exception {
        JSONArray old = new JSONArray().put(new JSONObject().put("title", "legacy").put("time", 100));
        context.getSharedPreferences("hoyoung_notification_history", Context.MODE_PRIVATE).edit().putString("events", old.toString()).commit();
        HoyoungNotificationListener.notificationSnapshot(context); journal.ingestPendingForTests(); assertEquals(1, HoyoungNotificationListener.notificationSnapshot(context).length());
        assertEquals(1, store.deviceEvents("notification", null, 0, 100).length());
        assertEquals(1, store.deviceEvents("collector_gap", "android.notification_listener.legacy_ring", 0, 100).length());
        HoyoungNotificationListener.notificationSnapshot(context); assertEquals(1, store.deviceEvents("notification", null, 0, 100).length());
    }

    @Test public void stepCounterAggregatesRealSensorEventsAndCounterResetNeverCreatesNegativeSteps() throws Exception {
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE); Sensor sensor = ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER); Shadows.shadowOf(manager).addSensor(sensor);
        PhoneActivityTracker tracker = new PhoneActivityTracker(context, journal); tracker.start(); assertTrue(Shadows.shadowOf(manager).hasListener(tracker));
        sendStep(manager, sensor, 100); sendStep(manager, sensor, 112); tracker.flush(); journal.ingestPendingForTests();
        JSONArray samples = store.deviceEvents("activity_sample", null, 0, 100); assertTrue(samples.length() >= 2);
        assertEquals(12, samples.getJSONObject(samples.length() - 1).getJSONObject("payload").getLong("stepsDeltaSincePreviousReading"));
        sendStep(manager, sensor, 117); sendStep(manager, sensor, 2); tracker.flush(); journal.ingestPendingForTests();
        JSONArray beforeReset = store.deviceEvents("activity_sample", null, 0, 100); boolean preservedFive = false;
        for (int i = 0; i < beforeReset.length(); i++) if (beforeReset.getJSONObject(i).getJSONObject("payload").optLong("stepsDeltaSincePreviousReading") == 5) preservedFive = true;
        assertTrue("Observed pending steps must survive a live counter reset", preservedFive);
        assertTrue(store.deviceEvents("collector_gap", "android.step_sensor", 0, 100).length() > 0);
        assertTrue(tracker.snapshot().getLong("pendingCounterDelta") >= 0);
        tracker.stop("owner_stopped"); journal.ingestPendingForTests(); assertFalse(Shadows.shadowOf(manager).hasListener(tracker));
        long retained = store.deviceEventStats().getLong("count"); sendStep(manager, sensor, 20); assertEquals(retained, store.deviceEventStats().getLong("count"));
    }

    @Test @Config(sdk = 35) public void stepPermissionIsActuallyRequiredAndRevocationUnregistersListener() throws Exception {
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE); Shadows.shadowOf(manager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER));
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION);
        PhoneActivityTracker tracker = new PhoneActivityTracker(context, journal); tracker.start(); assertFalse(Shadows.shadowOf(manager).hasListener(tracker));
        assertEquals("permission_missing", tracker.snapshot().getString("code"));
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION); tracker.reconcilePermissions(); assertTrue(Shadows.shadowOf(manager).hasListener(tracker));
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION); tracker.reconcilePermissions(); assertFalse(Shadows.shadowOf(manager).hasListener(tracker));
    }
    @Test @Config(sdk = 35) public void bondedDeviceReadFailsBeforeBluetoothAccessWithoutActualConnectGrant() throws Exception {
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        JSONObject denied = tools.execute("phone_bluetooth_bonded", new JSONObject().put("limit", 20));
        assertFalse(denied.getBoolean("ok")); assertEquals("permission_missing", denied.getString("code")); assertEquals("nearby", denied.getString("permissionId"));
        assertFalse(denied.has("bondedDevices"));
    }

    @Test public void detectorFallbackReportsOnlyObservedStepsAndMalformedSamplesAreIgnored() throws Exception {
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE); Sensor detector = ShadowSensor.newInstance(Sensor.TYPE_STEP_DETECTOR); Shadows.shadowOf(manager).addSensor(detector);
        PhoneActivityTracker tracker = new PhoneActivityTracker(context, journal); tracker.start();
        sendStep(manager, detector, 1); sendStep(manager, detector, 1); sendStep(manager, detector, Float.NaN); sendStep(manager, detector, -1); sendStep(manager, detector, 0.5f);
        tracker.flush(); assertEquals(2, tracker.snapshot().getLong("detectorStepsObservedThisProcess")); assertEquals("not_inferred", tracker.snapshot().getString("activityClassification"));
        tracker.stop("test_finished");
    }

    @Test public void usageRequiresRealAppOpsAndReturnsOnlySyntheticObservedTransitions() throws Exception {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE); Shadows.shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName(), AppOpsManager.MODE_IGNORED);
        assertEquals("permission_missing", tools.execute("phone_usage_events", new JSONObject()).getString("code")); usageGrant();
        long now = System.currentTimeMillis(); UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        Shadows.shadowOf(manager).addEvent("example.synthetic", now - 1000, UsageEvents.Event.MOVE_TO_FOREGROUND);
        JSONObject result = tools.execute("phone_usage_events", new JSONObject().put("fromMs", now - 10000).put("toMs", now).put("limit", 10));
        assertTrue(result.toString(), result.getBoolean("ok")); assertEquals("example.synthetic", result.getJSONArray("events").getJSONObject(0).getString("package"));
        assertFalse(result.getBoolean("currentAppVerified"));
    }

    @Test public void lastKnownLocationShowsAccuracyAgeProviderAndRejectsStaleFix() throws Exception {
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE); Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        SystemClock.sleep(10000); Location location = new Location(LocationManager.GPS_PROVIDER); location.setLatitude(37.5); location.setLongitude(127); location.setAccuracy(80); location.setTime(System.currentTimeMillis() - 5000); location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - 5_000_000_000L);
        Shadows.shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, location);
        JSONObject result = tools.execute("phone_location_last", new JSONObject().put("maxAgeMs", 10000));
        assertTrue(result.toString(), result.getBoolean("ok")); assertEquals(80, result.getDouble("accuracyMeters"), 0); assertEquals("gps", result.getString("provider")); assertTrue(result.getBoolean("cached"));
        assertEquals("location_stale", tools.execute("phone_location_last", new JSONObject().put("maxAgeMs", 1000)).getString("code"));
    }

    @Test public void contextSearchFindsOlderMatchingArchiveEntriesAndFiltersRevokedSources() throws Exception {
        journal.append("device_state", "android.device_state", new JSONObject().put("text", "older keywordneedle"), 1, null);
        for (int i = 0; i < 510; i++) journal.append("device_state", "android.device_state", new JSONObject().put("value", i), 2 + i, null);
        journal.ingestPendingForTests();
        JSONObject result = tools.execute("phone_context_search", new JSONObject().put("query", "keywordneedle")); assertEquals(1, result.getJSONArray("events").length());
        store.putSetting("toolPermissions", "{\"phone_app_state\":false}"); assertEquals(0, tools.execute("phone_context_search", new JSONObject().put("query", "keywordneedle")).getJSONArray("events").length());
    }
    @Test public void unrelatedHighVolumeEventsCannotCrowdRelevantLocationOutOfContext() throws Exception {
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        journal.append("location_sample", "android.location_manager", new JSONObject().put("location_marker", "saved_measured_location"), 1, null);
        for (int i = 0; i < 510; i++) journal.append("usage_event", "android.usage_events", new JSONObject().put("package", "example.synthetic").put("time", i), 2 + i, null);
        journal.ingestPendingForTests();
        assertTrue(tools.contextSummary("현재 위치", 4000).contains("saved_measured_location"));
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
        assertFalse(tools.contextSummary("현재 위치", 4000).contains("saved_measured_location"));
    }

    @Test public void providerMutationRequiresActualGrantAndStrictTypesBeforeAnyWrite() throws Exception {
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.WRITE_CALENDAR);
        assertEquals("permission_missing", tools.execute("phone_contact_update", new JSONObject().put("rawContactId", 1).put("name", "Synthetic")).getString("code"));
        assertEquals("permission_missing", tools.execute("phone_calendar_update", new JSONObject().put("eventId", 1).put("title", "Synthetic")).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_calendar_update", new JSONObject().put("eventId", "1").put("title", "Synthetic")).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_context_read", new JSONObject().put("afterId", -1)).getString("code"));
    }
    @Test public void oneShotLocationReturnsAnImmediateReceiptAndTimeoutIsNotInventedAsAFix() throws Exception {
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE); Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        JSONObject accepted = tools.execute("phone_location_refresh", new JSONObject().put("timeoutMs", 1000));
        assertTrue(accepted.toString(), accepted.getBoolean("ok")); assertFalse(accepted.getBoolean("completionConfirmed"));
        String id = accepted.getString("requestId"); assertEquals("pending", tools.execute("phone_location_refresh_read", new JSONObject().put("requestId", id)).getJSONObject("request").getString("state"));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(1001));
        journal.ingestPendingForTests();
        JSONObject receipt = tools.execute("phone_location_refresh_read", new JSONObject().put("requestId", id)).getJSONObject("request");
        assertEquals("timed_out", receipt.getString("state")); assertFalse(receipt.getBoolean("completionConfirmed")); assertFalse(receipt.has("measurement"));
        assertEquals(1, store.deviceEvents("collector_gap", "android.location_manager", 0, 100).length());
    }
    @Test public void cameraAndVoiceRequestsFailBeforeAnyCaptureWhenPermissionIsMissing() throws Exception {
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        assertEquals("permission_missing", tools.execute("phone_camera_open", new JSONObject()).getString("code"));
        assertEquals("permission_missing", tools.execute("phone_voice_record", new JSONObject().put("durationMs", 2000)).getString("code"));
        assertEquals(0, store.deviceEvents("capture_result", null, 0, 100).length());
        assertEquals("invalid_arguments", tools.execute("phone_voice_record", new JSONObject().put("durationMs", "2000")).getString("code"));
    }
    @Test public void currentLocationReceiptValidatesCoordinatesAccuracyAndStalenessWithoutFabricatingFixes() throws Exception {
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE); Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMinutes(2));
        for (String invalid : new String[]{"latitude", "longitude", "accuracy", "stale"}) {
            JSONObject accepted = tools.execute("phone_location_refresh", new JSONObject().put("timeoutMs", 1000).put("maxAgeMs", 60000)); assertTrue(accepted.toString(), accepted.getBoolean("ok"));
            Location location = new Location(LocationManager.GPS_PROVIDER); location.setLatitude(37.5); location.setLongitude(127.0); location.setAccuracy(5); location.setTime(System.currentTimeMillis()); location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            if (invalid.equals("latitude")) location.setLatitude(100); else if (invalid.equals("longitude")) location.setLongitude(-190); else if (invalid.equals("accuracy")) location.setAccuracy(Float.NaN); else location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - TimeUnit.MINUTES.toNanos(1) - TimeUnit.SECONDS.toNanos(1));
            Shadows.shadowOf(manager).simulateLocation(LocationManager.GPS_PROVIDER, location); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); journal.ingestPendingForTests();
            JSONObject receipt = tools.execute("phone_location_refresh_read", new JSONObject().put("requestId", accepted.getString("requestId"))).getJSONObject("request");
            assertEquals(invalid.equals("stale") ? "location_stale" : "invalid_location_measurement", receipt.getString("state")); assertFalse(receipt.getBoolean("completionConfirmed")); assertFalse(receipt.has("measurement"));
        }
        assertEquals(0, store.deviceEvents("location_sample", null, 0, 100).length()); assertEquals(4, store.deviceEvents("collector_gap", "android.location_manager", 0, 100).length());
        assertEquals("invalid_arguments", tools.execute("phone_location_refresh", new JSONObject().put("maxAgeMs", 900001)).getString("code"));
    }
    @Test public void currentLocationSuccessKeepsProvenanceAndAdmitsThatAndroidMayReturnACachedFix() throws Exception {
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE); Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        JSONObject accepted = tools.execute("phone_location_refresh", new JSONObject().put("timeoutMs", 1000)); assertTrue(accepted.toString(), accepted.getBoolean("ok"));
        Location location = new Location(LocationManager.GPS_PROVIDER); location.setLatitude(37.5); location.setLongitude(127); location.setAccuracy(7); location.setTime(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)); location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - TimeUnit.SECONDS.toNanos(2));
        Shadows.shadowOf(manager).simulateLocation(LocationManager.GPS_PROVIDER, location); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); journal.ingestPendingForTests();
        JSONObject receipt = tools.execute("phone_location_refresh_read", new JSONObject().put("requestId", accepted.getString("requestId"))).getJSONObject("request");
        assertEquals("observed", receipt.getString("state")); assertTrue(receipt.getBoolean("completionConfirmed")); assertTrue(receipt.getBoolean("observationPersisted")); assertTrue(receipt.getBoolean("databaseIngested"));
        JSONObject measurement = receipt.getJSONObject("measurement"); assertEquals(7, measurement.getDouble("accuracyMeters"), 0); assertTrue(measurement.getBoolean("freshFixRequested")); assertTrue(measurement.getBoolean("cachedFixPossible")); assertFalse("Future provider walltime is not evidence of a new fix", measurement.getBoolean("fixObservedAfterRequest")); assertEquals("elapsed_realtime", measurement.getString("freshnessClock"));
        assertEquals(1, store.deviceEvents("location_sample", "android.location_manager", 0, 100).length());
    }
    private static void sendStep(SensorManager manager, Sensor sensor, float value) { SensorEvent event = ShadowSensorManager.createSensorEvent(1); event.sensor = sensor; event.values[0] = value; event.timestamp = SystemClock.elapsedRealtimeNanos(); Shadows.shadowOf(manager).sendSensorEventToListeners(event); }
    private static void clearTestJournal(java.io.File file) { if (file.isDirectory()) { java.io.File[] children = file.listFiles(); if (children != null) for (java.io.File child : children) clearTestJournal(child); } if (file.exists()) assertTrue(file.delete()); }
}
