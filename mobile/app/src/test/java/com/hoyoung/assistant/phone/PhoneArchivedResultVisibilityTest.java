package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Application;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Process;
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
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class PhoneArchivedResultVisibilityTest {
    private Context context; private HoyoungStore store; private PhoneTools tools; private PhoneEventJournal journal;
    @Before public void setup() { context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); store = new HoyoungStore(context); journal = PhoneEventJournal.bind(context, store); tools = new PhoneTools(context, store); journal.ingestPendingForTests(); }
    @After public void cleanup() { journal.ingestPendingForTests(); journal.closeWorker(); journal.awaitClosedForTests(); store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); }
    @Test public void oldNotificationAndMixedContextCannotBypassARevokedSourceGrant() throws Exception {
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", new ComponentName(context, HoyoungNotificationListener.class).flattenToString());
        JSONObject notification = new JSONObject().put("ok", true).put("notifications", new JSONArray().put(new JSONObject().put("text", "synthetic old message")));
        assertTrue(tools.canReadArchivedToolResult("phone_notifications_read", notification));
        JSONObject unpersisted = new JSONObject().put("category", "notification").put("source", "android.notification_listener").put("payload", new JSONObject().put("text", "synthetic"));
        assertFalse("An event without canonical source identity must never be admitted",
                tools.canReadArchivedToolResult("phone_context_read", new JSONObject().put("events", new JSONArray().put(unpersisted))));
        JSONObject event = store.appendDeviceEvent("notification", "android.notification_listener",
                new JSONObject().put("text", "synthetic"), 123, "archived-result-visibility-fixture");
        JSONObject contextResult = new JSONObject().put("events", new JSONArray().put(event)); assertTrue(tools.canReadArchivedToolResult("phone_context_read", contextResult));
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", "");
        assertFalse(tools.canReadArchivedToolResult("phone_notifications_read", notification)); assertFalse(tools.canReadArchivedToolResult("phone_context_read", contextResult));
    }
    @Test public void sourceFeatureRevocationAndGlobalContextDisableHideHistoricalPhoneData() throws Exception {
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
        JSONObject saved = new JSONObject().put("latitude", 37.5).put("longitude", 127); assertTrue(tools.canReadArchivedToolResult("phone_location_last", saved));
        store.putSetting("toolPermissions", "{\"phone_location_last\":false}"); assertFalse(tools.canReadArchivedToolResult("phone_location_last", saved)); assertFalse(tools.canReadArchivedToolResult("phone_location_refresh_read", saved));
        store.putSetting("toolPermissions", "{}"); store.putSetting("phoneContextEnabled", "false"); assertFalse(tools.canReadArchivedToolResult("phone_location_last", saved));
        assertTrue(tools.canReadArchivedToolResult("phone_device_info", new JSONObject().put("model", "synthetic-device"))); assertFalse(tools.canReadArchivedToolResult("phone_unknown_future_tool", new JSONObject()));
    }
    @Test public void historicalContactMutationsNeedReadWriteGrantsButClipboardHasNoAppForegroundVeto() throws Exception {
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS);
        assertTrue(tools.canReadArchivedToolResult("phone_contact_update", new JSONObject()));
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.READ_CONTACTS); assertFalse(tools.canReadArchivedToolResult("phone_contact_update", new JSONObject()));
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE); ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo(); process.pid = Process.myPid(); process.uid = Process.myUid(); process.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
        Shadows.shadowOf(manager).setProcesses(java.util.Collections.singletonList(process)); assertTrue(tools.canReadArchivedToolResult("phone_clipboard_read", new JSONObject().put("text", "synthetic clipboard")));
        process.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND; Shadows.shadowOf(manager).setProcesses(java.util.Collections.singletonList(process)); assertTrue(tools.canReadArchivedToolResult("phone_clipboard_read", new JSONObject().put("text", "synthetic clipboard")));
    }
    @Test @Config(sdk = 35) public void selectedPhotoGrantCannotExposeAnUnverifiedHistoricalFullLibraryResult() throws Exception {
        JSONObject images = new JSONObject().put("kind", "images").put("access", "os_granted_library_access").put("media", new JSONArray());
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES); assertTrue(tools.canReadArchivedToolResult("phone_media_list", images));
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES); Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        assertFalse(tools.canReadArchivedToolResult("phone_media_list", images)); images.put("access", "user_selected_items_only"); assertFalse("Old selected items may no longer be selected", tools.canReadArchivedToolResult("phone_media_list", images));
    }
    @Test public void networkReceiptsWithUnredactedWifiIdentifiersRecheckTheLocationGrant() throws Exception {
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.ACCESS_FINE_LOCATION);
        JSONObject network = new JSONObject().put("ssid", "synthetic wifi").put("bssid", "00:11:22:33:44:55"); assertTrue(tools.canReadArchivedToolResult("phone_network_state", network));
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION); assertFalse(tools.canReadArchivedToolResult("phone_network_state", network));
        assertTrue(tools.canReadArchivedToolResult("phone_network_state", new JSONObject().put("connected", true).put("ssid", JSONObject.NULL).put("bssid", JSONObject.NULL)));
    }
}
