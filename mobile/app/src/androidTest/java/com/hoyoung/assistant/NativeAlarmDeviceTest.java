package com.hoyoung.assistant;

import android.app.AlarmManager;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hoyoung.assistant.phone.PhoneEventJournal;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Real AlarmManager → explicit receiver → durable receipt on the offline wiped AVD. */
@RunWith(AndroidJUnit4.class)
public final class NativeAlarmDeviceTest {
    @Test public void actualExactAlarmReceiverPersistsFiringWithoutNotificationOrModelInvocation() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext(); DevicePermissionFixture.assertReady(context);
        AlarmManager manager = context.getSystemService(AlarmManager.class); assertNotNull(manager);
        assertTrue("Fixture must genuinely grant exact-alarm access", manager.canScheduleExactAlarms());
        HoyoungApplication application = (HoyoungApplication) context.getApplicationContext();
        HoyoungStore store = application.getStore(); assertNotNull(store);
        PhoneTools tools = application.getPhoneTools(); assertNotNull(tools);
        PhoneEventJournal journal = PhoneEventJournal.forContext(context);
        String priorPolicy = store.getSetting("toolPermissions", "{}"); String id = null;
        JSONObject policy = new JSONObject(priorPolicy).put("phone_alarm_schedule", true)
                .put("phone_alarm_list", true).put("phone_alarm_cancel", true);
        store.putSetting("toolPermissions", policy.toString()); journal.refreshPolicyBlocking();
        try {
            JSONObject result = tools.execute("phone_alarm_schedule", new JSONObject().put("atMs", System.currentTimeMillis() + 1500)
                    .put("title", "Synthetic offline native reminder").put("message", "Fixture only").put("notify", false));
            assertTrue(result.toString(), result.getBoolean("ok")); JSONObject scheduled = result.getJSONObject("alarm");
            id = scheduled.getString("alarmId"); assertEquals("scheduled", scheduled.getString("state"));
            assertFalse(scheduled.getBoolean("receiverCallbackObserved")); assertFalse(scheduled.getBoolean("completionConfirmed"));
            long deadline = SystemClock.elapsedRealtime() + 30000; JSONObject fired = null;
            do {
                fired = find(tools, id);
                if (fired != null && fired.optBoolean("receiverCallbackObserved")) break;
                SystemClock.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            assertNotNull(fired); assertEquals("Actual Android alarm receiver must fire: " + fired, "fired", fired.getString("state"));
            assertTrue(fired.getBoolean("receiverCallbackObserved")); assertTrue(fired.getBoolean("completionConfirmed"));
            assertEquals("native_receiver_callback_only", fired.getString("completionMeaning"));
            assertTrue(fired.getLong("firedAt") >= scheduled.getLong("atMs"));
            assertFalse(fired.getBoolean("notificationRequested")); assertFalse(fired.getBoolean("modelInvoked"));
            File state = new File(context.getFilesDir(), "device-journal/state/" + PhoneEventJournal.hash("native_alarm.receipt." + id) + ".json");
            assertTrue("Actual persisted receipt file must exist", state.isFile());
            JSONObject envelope = new JSONObject(new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8));
            JSONObject saved = new JSONObject(envelope.getString("value"));
            assertEquals(id, saved.getString("alarmId")); assertTrue(saved.getBoolean("receiverCallbackObserved"));
            assertEquals(fired.getLong("firedAt"), saved.getLong("firedAt"));
            String marker = "HOYOUNG_DEVICE_EXACT_ALARM actual-AlarmManager-to-receiver; durable-fired-receipt; no-notification; no-model-invocation; offline-disposable-AVD-only";
            android.util.Log.i("HoyoungDeviceProof", marker); Bundle proof = new Bundle(); proof.putString("stream", marker + "\n"); instrumentation.sendStatus(0, proof);
        } finally {
            if (id != null) tools.execute("phone_alarm_cancel", new JSONObject().put("alarmId", id));
            store.putSetting("toolPermissions", priorPolicy); journal.refreshPolicyBlocking();
        }
    }

    private static JSONObject find(PhoneTools tools, String id) throws Exception {
        int offset = 0;
        do {
            JSONObject page = tools.execute("phone_alarm_list", new JSONObject().put("offset", offset).put("limit", 100));
            assertTrue(page.toString(), page.getBoolean("ok")); JSONArray alarms = page.getJSONArray("alarms");
            for (int i = 0; i < alarms.length(); i++) if (id.equals(alarms.getJSONObject(i).getString("alarmId"))) return alarms.getJSONObject(i);
            if (page.isNull("nextOffset")) return null; offset = page.getInt("nextOffset");
        } while (true);
    }
}
