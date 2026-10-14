package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.provider.Settings;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import static org.junit.Assert.*;

/** Synthetic clock/AlarmManager only; no real notifications or model calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public final class PhoneAlarmToolsTest {
    private Context context;
    private HoyoungStore store;
    private PhoneEventJournal journal;
    private PhoneAlarmTools tools;
    private AlarmManager manager;
    private long wallTime;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        remove(new java.io.File(context.getFilesDir(), "device-journal"));
        store = new HoyoungStore(context); journal = PhoneEventJournal.bind(context, store); journal.ingestPendingForTests();
        wallTime = System.currentTimeMillis();
        manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE); tools = new PhoneAlarmTools(context, () -> wallTime); tools.afterBackupRestore();
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    }
    @After public void tearDown() {
        tools.afterBackupRestore();
        journal.ingestPendingForTests(); journal.closeWorker(); journal.awaitClosedForTests(); store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
    }

    @Test public void exactAccessAndOwnerGrantAreCheckedBeforeRegisteringAnything() throws Exception {
        ShadowAlarmManager.setCanScheduleExactAlarms(false);
        assertEquals("permission_missing", tools.execute("phone_alarm_schedule", args()).getString("code"));
        assertTrue(Shadows.shadowOf(manager).getScheduledAlarms().isEmpty());
        ShadowAlarmManager.setCanScheduleExactAlarms(true); store.putSetting("toolPermissions", "{\"phone_alarm_schedule\":false}"); journal.ingestPendingForTests();
        assertEquals("permission_denied_by_owner", tools.execute("phone_alarm_schedule", args()).getString("code"));
        assertTrue(Shadows.shadowOf(manager).getScheduledAlarms().isEmpty());
    }
    @Test public void futureScheduleCreatesRealPendingIntentAndDurableUnfiredReceipt() throws Exception {
        JSONObject result = tools.execute("phone_alarm_schedule", args()); assertTrue(result.toString(), result.getBoolean("ok"));
        JSONObject receipt = result.getJSONObject("alarm");
        assertEquals("scheduled", receipt.getString("state")); assertFalse(receipt.getBoolean("receiverCallbackObserved"));
        assertFalse(receipt.getBoolean("completionConfirmed")); assertFalse(receipt.getBoolean("modelInvoked"));
        assertEquals(1, Shadows.shadowOf(manager).getScheduledAlarms().size());
        JSONObject listed = new PhoneAlarmTools(context).execute("phone_alarm_list", new JSONObject()).getJSONArray("alarms").getJSONObject(0);
        assertEquals(receipt.getString("alarmId"), listed.getString("alarmId"));
        assertTrue(listed.getBoolean("pendingIntentExists")); assertFalse(listed.getBoolean("registrationReverified"));
    }
    @Test public void invalidPastAndFractionalSchedulesHaveNoSideEffects() throws Exception {
        assertEquals("invalid_arguments", tools.execute("phone_alarm_schedule", args().put("atMs", wallTime - 1)).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_alarm_schedule", args().put("atMs", 1.5)).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_alarm_schedule", args().put("unexpected", true)).getString("code"));
        assertTrue(Shadows.shadowOf(manager).getScheduledAlarms().isEmpty());
    }
    @Test public void cancellationRemovesActualScheduledAlarmAndPreventsLateCallback() throws Exception {
        String id = schedule(false); JSONObject result = tools.execute("phone_alarm_cancel", new JSONObject().put("alarmId", id));
        assertTrue(result.toString(), result.getBoolean("ok")); assertTrue(Shadows.shadowOf(manager).getScheduledAlarms().isEmpty());
        wallTime += 120000; tools.delivered(id);
        JSONObject row = first(); assertEquals("canceled", row.getString("state")); assertFalse(row.getBoolean("receiverCallbackObserved"));
    }
    @Test public void callbackIsRecordedOnlyAfterDueAndDuplicateCannotPostTwice() throws Exception {
        String id = schedule(true); tools.delivered(id); assertFalse(first().getBoolean("receiverCallbackObserved"));
        wallTime += 120000; tools.delivered(id);
        JSONObject fired = first(); assertEquals("fired", fired.getString("state")); assertTrue(fired.getBoolean("receiverCallbackObserved"));
        assertTrue(fired.getBoolean("completionConfirmed")); assertTrue(fired.getBoolean("notificationRequested")); assertFalse(fired.getBoolean("modelInvoked"));
        long time = fired.getLong("firedAt"); wallTime += 1000; tools.delivered(id);
        assertEquals(time, first().getLong("firedAt"));
        assertEquals(1, Shadows.shadowOf((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).size());
    }
    @Test public void revokedNotificationGrantDoesNotInventSuccessfulNotification() throws Exception {
        String id = schedule(true); Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        wallTime += 120000; tools.delivered(id);
        JSONObject row = first(); assertTrue(row.getBoolean("receiverCallbackObserved")); assertFalse(row.getBoolean("notificationRequested"));
        assertEquals("android_notification_access_missing", row.getString("notificationOutcome"));
    }
    @Test public void revokedScheduleToolSuppressesNotificationWithoutErasingActualCallback() throws Exception {
        String id = schedule(true); store.putSetting("toolPermissions", "{\"phone_alarm_schedule\":false}"); journal.ingestPendingForTests();
        wallTime += 120000; tools.delivered(id);
        assertTrue(first().getBoolean("receiverCallbackObserved")); assertFalse(first().getBoolean("notificationRequested"));
    }
    @Test public void rebootOrMissingRegistrationIsUnknownAndNeverAutomaticallyReplays() throws Exception {
        String id = schedule(false); int count = Shadows.shadowOf(manager).getScheduledAlarms().size();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 8);
        assertEquals("interrupted_by_reboot", first().getString("effectiveState"));
        assertEquals(count, Shadows.shadowOf(manager).getScheduledAlarms().size()); assertEquals(id, first().getString("alarmId"));
        assertFalse(first().getBoolean("receiverCallbackObserved"));
    }
    @Test public void cancellationAfterFiringPreservesTheDeliveryEvidence() throws Exception {
        String id = schedule(false); wallTime += 120000; tools.delivered(id);
        JSONObject result = tools.execute("phone_alarm_cancel", new JSONObject().put("alarmId", id)).getJSONObject("alarm");
        assertEquals("fired_then_canceled", result.getString("state")); assertTrue(result.getBoolean("receiverCallbackObserved"));
    }
    @Test public void listPaginatesAndMissingReceiptIdNeverCreatesAnAlarm() throws Exception {
        schedule(false); schedule(false);
        JSONObject page = tools.execute("phone_alarm_list", new JSONObject().put("limit", 1)); assertEquals(1, page.getJSONArray("alarms").length()); assertEquals(1, page.getInt("nextOffset"));
        assertEquals(1, tools.execute("phone_alarm_list", new JSONObject().put("offset", 1)).getJSONArray("alarms").length());
        assertEquals("alarm_not_found", tools.execute("phone_alarm_cancel", new JSONObject().put("alarmId", "00000000-0000-0000-0000-000000000000")).getString("code"));
    }
    @Test public void restoreQuiescesReceiverAndDoesNotReplayOnResume() throws Exception {
        String id = schedule(false); tools.pauseForBackupRestore(); wallTime += 120000;
        new PhoneAlarmTools(context).delivered(id);
        assertEquals("restore_in_progress", tools.execute("phone_alarm_schedule", args()).getString("code"));
        tools.afterBackupRestore(); assertFalse(first().getBoolean("receiverCallbackObserved"));
        assertTrue("Restore resume must not add a new registration", Shadows.shadowOf(manager).getScheduledAlarms().size() <= 1);
    }
    private JSONObject args() throws Exception { return new JSONObject().put("atMs", wallTime + 60000).put("title", "Synthetic reminder").put("message", "Local fixture only"); }
    private String schedule(boolean notify) throws Exception { JSONObject result = tools.execute("phone_alarm_schedule", args().put("notify", notify)); assertTrue(result.toString(), result.getBoolean("ok")); return result.getJSONObject("alarm").getString("alarmId"); }
    private JSONObject first() throws Exception { return tools.execute("phone_alarm_list", new JSONObject()).getJSONArray("alarms").getJSONObject(0); }
    private static void remove(java.io.File file) { if (file.isDirectory()) { java.io.File[] children = file.listFiles(); if (children != null) for (java.io.File child : children) remove(child); } file.delete(); }
}
