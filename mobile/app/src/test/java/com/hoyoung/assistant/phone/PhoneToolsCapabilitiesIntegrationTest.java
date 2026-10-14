package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Process;
import android.provider.Settings;
import com.hoyoung.assistant.runtime.VoiceCaptureService;
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
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Native facade integration only: synthetic clipboard, receipts and grants; no account or external action. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public final class PhoneToolsCapabilitiesIntegrationTest {
    private Context context; private HoyoungStore store; private PhoneEventJournal journal; private PhoneTools tools;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        store = new HoyoungStore(context); journal = PhoneEventJournal.bind(context, store); tools = new PhoneTools(context, store);
        journal.refreshPolicyBlocking();
    }
    @After public void cleanup() {
        VoiceCaptureService.stopActive("test_cleanup"); journal.ingestPendingForTests(); journal.closeWorker(); journal.awaitClosedForTests();
        store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
    }
    @Test public void catalogExportsEachExtendedScreenAndStopSchemaExactlyOnce() throws Exception {
        Set<String> names = new HashSet<>(); JSONArray catalog = tools.catalog();
        for (int i = 0; i < catalog.length(); i++) { JSONObject tool = catalog.getJSONObject(i); assertTrue(tool.getString("name"), names.add(tool.getString("name"))); assertFalse(tool.getJSONObject("inputSchema").getBoolean("additionalProperties")); }
        JSONArray expected = new JSONArray(); new PhoneExtendedTools(context).appendCatalog(expected);
        new PhoneRadioTools(context).appendCatalog(expected); new PhoneAlarmTools(context).appendCatalog(expected); new PhoneOverlayTools(context).appendCatalog(expected);
        new PhoneFileTransferTools(context).appendCatalog(expected); new PhoneCommunicationTools(context).appendCatalog(expected);
        JSONArray screen = PhoneScreenCapture.catalog(); for (int i = 0; i < screen.length(); i++) expected.put(screen.getJSONObject(i));
        for (int i = 0; i < expected.length(); i++) assertTrue(expected.getJSONObject(i).getString("name"), names.contains(expected.getJSONObject(i).getString("name")));
        assertTrue(names.contains("phone_voice_record_stop"));
        assertEquals("invalid_arguments", tools.execute("phone_call_place", new JSONObject()).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_clipboard_write", new JSONObject().put("text", "synthetic").put("unknown", true)).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_screen_capture_read", new JSONObject()).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_voice_record_stop", new JSONObject()).getString("code"));
    }
    @Test public void extendedFacadeRechecksOwnerToggleBeforePerformingClipboardWrite() throws Exception {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "old synthetic clip"));
        store.putSetting("toolPermissions", "{\"phone_clipboard_write\":false}");
        assertEquals("permission_disabled", tools.execute("phone_clipboard_write", new JSONObject().put("text", "replacement")).getString("code"));
        assertEquals("old synthetic clip", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        store.putSetting("toolPermissions", "{}");
        assertTrue(tools.execute("phone_clipboard_write", new JSONObject().put("text", "new synthetic clip")).getBoolean("ok"));
        assertEquals("new synthetic clip", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
    }
    @Test public void readableClipboardIsNotRejectedJustBecauseOurProcessIsInBackground() throws Exception {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo(); process.pid = Process.myPid(); process.uid = Process.myUid(); process.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
        Shadows.shadowOf(manager).setProcesses(java.util.Collections.singletonList(process));
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE); clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "background synthetic clip"));
        JSONObject exposed = tools.execute("phone_clipboard_read", new JSONObject()); assertTrue(exposed.getBoolean("ok")); assertEquals("background synthetic clip", exposed.getString("text"));
        clipboard.clearPrimaryClip(); JSONObject missing = tools.execute("phone_clipboard_read", new JSONObject()); assertEquals("empty_or_withheld_by_android", missing.getString("coverage")); assertFalse(missing.getBoolean("readableClipPresent"));
        assertTrue(tools.canReadArchivedToolResult("phone_clipboard_read", exposed));
    }
    @Test public void newReadOnlyClassificationDoesNotLabelPhysicalEffectsAsReads() {
        for (String name : new String[]{"phone_notifications_active", "phone_dnd_read", "phone_numbers_read", "phone_device_admin_state", "phone_screen_capture_read", "phone_voice_record_read", "phone_bluetooth_scan_read", "phone_wifi_scan_read", "phone_wifi_hotspot_read", "phone_alarm_list"}) assertTrue(name, PhoneTools.isReadOnly(name));
        for (String name : new String[]{"phone_notification_reply", "phone_call_place", "phone_screen_lock", "phone_clipboard_write", "phone_screen_capture", "phone_screen_capture_cancel", "phone_voice_record", "phone_voice_record_stop", "phone_bluetooth_scan", "phone_wifi_hotspot_start", "phone_alarm_schedule", "phone_alarm_cancel", "phone_overlay_show", "phone_overlay_update", "phone_overlay_hide", "phone_future_unknown"}) assertFalse(name, PhoneTools.isReadOnly(name));
    }
    @Test public void explicitScreenAndMicrophoneArchivesUseTheirOwnCurrentPermissions() throws Exception {
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, new ComponentName(context, HoyoungAccessibilityService.class).flattenToString());
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.RECORD_AUDIO);
        store.putSetting("phoneContextEnabled", "false");
        JSONObject screen = new JSONObject().put("source", "android.accessibility_screenshot"), voice = new JSONObject().put("source", "android.media_recorder");
        assertTrue(tools.canReadArchivedToolResult("phone_screen_capture_read", screen)); assertTrue(tools.canReadArchivedToolResult("phone_voice_record_read", voice));
        JSONObject screenSource = store.appendDeviceEvent("capture_result", "android.accessibility_screenshot", screen, 100, "synthetic-screen-source");
        JSONObject voiceSource = store.appendDeviceEvent("capture_result", "android.media_recorder", voice, 100, "synthetic-voice-source");
        assertTrue(tools.canReadStoredSource(screenSource)); assertTrue(tools.canReadStoredSource(voiceSource));
        store.putSetting("toolPermissions", "{\"phone_screen_capture_read\":false,\"phone_voice_record_read\":false}");
        assertFalse(tools.canReadArchivedToolResult("phone_screen_capture_read", screen)); assertFalse(tools.canReadArchivedToolResult("phone_voice_record_read", voice));
        assertFalse(tools.canReadStoredSource(screenSource)); assertFalse(tools.canReadStoredSource(voiceSource));
        store.putSetting("toolPermissions", "{}"); Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ""); Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.RECORD_AUDIO);
        assertFalse(tools.canReadArchivedToolResult("phone_screen_capture_read", screen)); assertFalse(tools.canReadArchivedToolResult("phone_voice_record_read", voice));
    }
    @Test public void collectorLocationCancellationDoesNotStopAnIndependentMicrophoneRequest() throws Exception {
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.RECORD_AUDIO);
        JSONObject pending = tools.execute("phone_voice_record", new JSONObject().put("durationMs", 0)); assertTrue(pending.toString(), pending.getBoolean("ok"));
        String id = pending.getString("requestId"); tools.cancelOutstandingLocation("collector_stopped");
        JSONObject unchanged = tools.execute("phone_voice_record_read", new JSONObject().put("requestId", id)); assertEquals("service_start_requested", unchanged.getJSONObject("request").getString("state"));
        assertEquals("stop_requested", tools.execute("phone_voice_record_stop", new JSONObject().put("requestId", id)).getString("code"));
        JSONObject stopped = tools.execute("phone_voice_record_read", new JSONObject().put("requestId", id)); assertEquals("cancelled_before_recording", stopped.getJSONObject("request").getString("state")); assertFalse(stopped.getJSONObject("request").getBoolean("recorderStarted"));
    }
}
