package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.admin.DevicePolicyManager;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

/** Synthetic local calls and notifications only. No real account, call, or message is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public final class PhoneExtendedToolsTest {
    private Context context;
    private Application application;
    private PhoneExtendedTools tools;
    private final ActiveFixture active = new ActiveFixture();

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext(); application = (Application) context;
        tools = new PhoneExtendedTools(context, active);
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.CALL_PHONE, Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.VIBRATE);
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", "");
    }

    @Test public void allCatalogEntriesHaveStrictSchemasAndAreExecutableNames() throws Exception {
        JSONArray definitions = new JSONArray(); tools.appendCatalog(definitions);
        assertEquals(15, definitions.length()); Set<String> names = new HashSet<>();
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject definition = definitions.getJSONObject(i); String name = definition.getString("name");
            assertTrue(names.add(name)); assertTrue(tools.supports(name));
            JSONObject schema = definition.getJSONObject("inputSchema"); assertFalse(schema.getBoolean("additionalProperties"));
            JSONArray required = schema.getJSONArray("required");
            for (int j = 0; j < required.length(); j++) assertTrue(schema.getJSONObject("properties").has(required.getString(j)));
        }
        assertFalse(tools.supports("phone_fake_root"));
    }

    @Test public void directCallNeedsActualGrantAndDoesNotBecomeAnOrdinaryDialRequest() throws Exception {
        JSONObject args = new JSONObject().put("number", "+82 10 0000 0000");
        assertEquals("permission_missing", tools.execute("phone_call_place", args).getString("code"));
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE);
        Shadows.shadowOf(context.getPackageManager()).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true);
        Intent expected = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", "+82 10 0000 0000", null));
        ResolveInfo resolve = new ResolveInfo(); resolve.activityInfo = new ActivityInfo(); resolve.activityInfo.packageName = "synthetic.dialer"; resolve.activityInfo.name = "SyntheticCallActivity";
        Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(expected, resolve);
        JSONObject result = tools.execute("phone_call_place", args);
        assertTrue(result.toString(), result.getBoolean("ok")); assertTrue(result.getBoolean("callRequested"));
        assertFalse(result.getBoolean("callConnected")); assertFalse(result.getBoolean("completionConfirmed"));
        Intent sent = Shadows.shadowOf(application).getNextStartedActivity(); assertEquals(Intent.ACTION_CALL, sent.getAction()); assertEquals(expected.getData(), sent.getData());
    }

    @Test public void callWithoutTelephonyAndMalformedCallAreNotClaimedSuccessful() throws Exception {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.CALL_PHONE);
        Shadows.shadowOf(context.getPackageManager()).setSystemFeature(PackageManager.FEATURE_TELEPHONY, false);
        assertEquals("unsupported", tools.execute("phone_call_place", new JSONObject().put("number", "01000000000")).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_call_place", new JSONObject().put("number", "https://example.invalid")).getString("code"));
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());
    }

    @Test public void explicitContactDeletionUsesOnlySelectedProviderUriAndReportsRows() throws Exception {
        DeleteProvider provider = provider(ContactsContract.AUTHORITY); provider.rows = 3;
        JSONObject args = new JSONObject().put("contactId", 17);
        assertEquals("permission_missing", tools.execute("phone_contacts_delete", args).getString("code")); assertEquals(0, provider.calls);
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.WRITE_CONTACTS);
        JSONObject result = tools.execute("phone_contacts_delete", args);
        assertTrue(result.toString(), result.getBoolean("ok")); assertEquals(3, result.getInt("rowsDeleted")); assertTrue(result.getBoolean("providerConfirmed"));
        assertFalse(result.getBoolean("cloudSyncConfirmed")); assertEquals("content://com.android.contacts/contacts/17", provider.lastUri.toString()); assertNull(provider.selection);
    }

    @Test public void calendarDeleteDoesNotClaimMissingEventAndRespectsRevokedWriteGrant() throws Exception {
        DeleteProvider provider = provider(CalendarContract.AUTHORITY); Shadows.shadowOf(application).grantPermissions(Manifest.permission.WRITE_CALENDAR);
        JSONObject args = new JSONObject().put("eventId", 8); provider.rows = 0;
        JSONObject missing = tools.execute("phone_calendar_delete", args); assertEquals("target_not_found", missing.getString("code")); assertFalse(missing.getBoolean("providerConfirmed"));
        provider.rows = 1; JSONObject deleted = tools.execute("phone_calendar_delete", args); assertTrue(deleted.getBoolean("ok")); assertEquals("content://com.android.calendar/events/8", provider.lastUri.toString());
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.WRITE_CALENDAR);
        assertEquals("permission_missing", tools.execute("phone_calendar_delete", args).getString("code")); assertEquals(2, provider.calls);
    }

    @Test public void invalidDeleteTargetsDoNotReachProvider() throws Exception {
        DeleteProvider provider = provider(ContactsContract.AUTHORITY); Shadows.shadowOf(application).grantPermissions(Manifest.permission.WRITE_CONTACTS);
        for (Object id : new Object[]{0, -1, 1.5, "17", JSONObject.NULL, new java.math.BigInteger("18446744073709551617")}) {
            assertEquals("invalid_arguments", tools.execute("phone_contacts_delete", new JSONObject().put("contactId", id)).getString("code"));
        }
        assertEquals(0, provider.calls);
    }

    @Test public void clipboardWritesUnicodeAndEmptyTextWithoutClaimingPaste() throws Exception {
        String text = "호영 🌙 synthetic only";
        JSONObject result = tools.execute("phone_clipboard_write", new JSONObject().put("text", text));
        assertTrue(result.toString(), result.getBoolean("ok")); assertFalse(result.getBoolean("pasteConfirmed")); assertFalse(result.getBoolean("completionConfirmed")); assertFalse(result.toString().contains(text));
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals(text, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertTrue(tools.execute("phone_clipboard_write", new JSONObject().put("text", "")).getBoolean("ok"));
        assertEquals("", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
    }

    @Test public void activeNotificationSnapshotIsPermissionScopedAndExposesReplyKeys() throws Exception {
        StatusBarNotification notification = replyNotification(101); active.notifications = new StatusBarNotification[]{notification};
        assertEquals("permission_missing", tools.execute("phone_notifications_active", new JSONObject()).getString("code")); assertEquals(0, active.reads);
        grantNotificationAccess(); JSONObject result = tools.execute("phone_notifications_active", new JSONObject());
        assertTrue(result.toString(), result.getBoolean("ok")); assertFalse(result.getBoolean("historical"));
        JSONObject row = result.getJSONArray("notifications").getJSONObject(0); assertEquals(notification.getKey(), row.getString("key")); assertEquals(101, row.getLong("postedAt"));
        JSONObject input = row.getJSONArray("actions").getJSONObject(0).getJSONArray("remoteInputs").getJSONObject(0);
        assertEquals("synthetic_reply", input.getString("resultKey")); assertTrue(input.getBoolean("allowFreeFormInput"));
    }

    @Test public void notificationListingHonorsFilterAndReportsTruncation() throws Exception {
        grantNotificationAccess(); active.notifications = new StatusBarNotification[]{plainNotification(1, 101, "one.app"), plainNotification(2, 102, "two.app")};
        JSONObject bounded = tools.execute("phone_notifications_active", new JSONObject().put("limit", 1)); assertTrue(bounded.getBoolean("truncated")); assertEquals(1, bounded.getJSONArray("notifications").length());
        JSONObject filtered = tools.execute("phone_notifications_active", new JSONObject().put("package", "two.app")); assertEquals(1, filtered.getJSONArray("notifications").length()); assertFalse(filtered.getBoolean("truncated"));
        assertEquals("invalid_arguments", tools.execute("phone_notifications_active", new JSONObject().put("limit", 1.5)).getString("code"));
    }

    @Test public void staleOrRemovedNotificationsNeverDispatchOrDismiss() throws Exception {
        grantNotificationAccess(); StatusBarNotification notification = plainNotification(1, 200, "synthetic.app"); active.notifications = new StatusBarNotification[]{notification};
        JSONObject stale = tools.execute("phone_notification_dismiss", target(notification).put("expectedPostedAt", 100));
        assertEquals("target_changed", stale.getString("code")); assertNull(active.dismissed);
        active.notifications = new StatusBarNotification[0]; assertEquals("target_not_found", tools.execute("phone_notification_open", target(notification)).getString("code"));
        assertTrue(Shadows.shadowOf(application).getBroadcastIntents().isEmpty());
    }

    @Test public void dismissalRecordsOnlyRequestedUntilAndroidActuallyRemovesIt() throws Exception {
        grantNotificationAccess(); StatusBarNotification notification = plainNotification(1, 100, "synthetic.app"); active.notifications = new StatusBarNotification[]{notification};
        JSONObject result = tools.execute("phone_notification_dismiss", target(notification));
        assertTrue(result.getBoolean("accepted")); assertFalse(result.getBoolean("dismissalConfirmed")); assertEquals(notification.getKey(), active.dismissed);
        assertEquals(1, active.notifications.length);
    }

    @Test public void inlineReplySuppliesRealRemoteInputButDoesNotClaimServerDelivery() throws Exception {
        grantNotificationAccess(); StatusBarNotification notification = replyNotification(101); active.notifications = new StatusBarNotification[]{notification};
        JSONObject result = tools.execute("phone_notification_reply", target(notification).put("actionIndex", 0).put("resultKey", "synthetic_reply").put("text", "운동 끝나고 연락할게 🌙"));
        assertTrue(result.toString(), result.getBoolean("ok")); assertTrue(result.getBoolean("replyInputAttached")); assertFalse(result.getBoolean("messageDelivered")); assertFalse(result.getBoolean("completionConfirmed"));
        List<Intent> sent = Shadows.shadowOf(application).getBroadcastIntents(); assertEquals(1, sent.size());
        Bundle results = RemoteInput.getResultsFromIntent(sent.get(0)); assertNotNull(results); assertEquals("운동 끝나고 연락할게 🌙", results.getCharSequence("synthetic_reply").toString());
    }

    @Test @Config(sdk = 26) public void inlineReplyWorksOnMinimumAndroidWithoutAndroid28ResultsSourceCall() throws Exception {
        grantNotificationAccess(); StatusBarNotification notification = replyNotification(101); active.notifications = new StatusBarNotification[]{notification};
        JSONObject result = tools.execute("phone_notification_reply", target(notification).put("actionIndex", 0).put("resultKey", "synthetic_reply").put("text", "synthetic"));
        assertTrue(result.toString(), result.getBoolean("ok")); assertNotNull(RemoteInput.getResultsFromIntent(Shadows.shadowOf(application).getBroadcastIntents().get(0)));
    }

    @Test public void missingReplyFieldAndGenericActionDoNotSendEmptyOrWrongReply() throws Exception {
        grantNotificationAccess(); StatusBarNotification notification = replyNotification(101); active.notifications = new StatusBarNotification[]{notification};
        assertEquals("unsupported", tools.execute("phone_notification_reply", target(notification).put("actionIndex", 0).put("resultKey", "wrong").put("text", "synthetic")).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_notification_action", target(notification).put("actionIndex", 0)).getString("code"));
        assertTrue(Shadows.shadowOf(application).getBroadcastIntents().isEmpty());
    }

    @Test public void canceledNotificationIntentNeverReportsAcceptedAndRevocationBlocksCurrentAction() throws Exception {
        grantNotificationAccess(); PendingIntent pending = pending(23); pending.cancel(); Notification notification = new Notification.Builder(context, "synthetic").setContentIntent(pending).build();
        StatusBarNotification sbn = new StatusBarNotification("synthetic.app", "synthetic.app", 23, "test", 12345, 0, 0, notification, android.os.Process.myUserHandle(), 101); active.notifications = new StatusBarNotification[]{sbn};
        assertEquals("target_unavailable", tools.execute("phone_notification_open", target(sbn)).getString("code"));
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", "");
        assertEquals("permission_missing", tools.execute("phone_notification_dismiss", target(sbn)).getString("code")); assertNull(active.dismissed);
    }

    @Test public void notificationContentAndOrdinaryActionSendAvailablePendingIntent() throws Exception {
        grantNotificationAccess(); Notification notification = new Notification.Builder(context, "synthetic").setContentIntent(pending(35))
                .addAction(new Notification.Action.Builder(null, "Synthetic action", pending(36)).build()).build();
        StatusBarNotification sbn = new StatusBarNotification("synthetic.app", "synthetic.app", 35, "test", 12345, 0, 0, notification, android.os.Process.myUserHandle(), 101); active.notifications = new StatusBarNotification[]{sbn};
        assertTrue(tools.execute("phone_notification_open", target(sbn)).getBoolean("dispatchAccepted"));
        assertTrue(tools.execute("phone_notification_action", target(sbn).put("actionIndex", 0)).getBoolean("dispatchAccepted"));
        assertEquals(2, Shadows.shadowOf(application).getBroadcastIntents().size());
    }

    @Test public void dndRequiresActualPolicyAccessAndSeparatelyReportsGlobalObservation() throws Exception {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Shadows.shadowOf(manager).setNotificationPolicyAccessGranted(false);
        assertEquals("permission_missing", tools.execute("phone_dnd_set", new JSONObject().put("filter", "none")).getString("code"));
        Shadows.shadowOf(manager).setNotificationPolicyAccessGranted(true);
        JSONObject result = tools.execute("phone_dnd_set", new JSONObject().put("filter", "alarms"));
        assertTrue(result.toString(), result.getBoolean("accepted")); assertFalse(result.getBoolean("completionConfirmed")); assertTrue(result.getBoolean("android15AppRuleMayApply"));
        assertEquals("alarms", tools.execute("phone_dnd_read", new JSONObject()).getString("filter"));
        assertEquals("invalid_arguments", tools.execute("phone_dnd_set", new JSONObject().put("filter", "airplane")).getString("code"));
    }

    @Test public void vibrationChecksGrantHardwareAndReportsOnlyActualRequest() throws Exception {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE); Shadows.shadowOf(vibrator).setHasVibrator(true);
        assertEquals("permission_missing", tools.execute("phone_vibrate", new JSONObject().put("durationMs", 450)).getString("code"));
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.VIBRATE);
        JSONObject result = tools.execute("phone_vibrate", new JSONObject().put("durationMs", 450)); assertTrue(result.toString(), result.getBoolean("accepted")); assertFalse(result.getBoolean("completionConfirmed"));
        assertTrue(Shadows.shadowOf(vibrator).isVibrating()); assertEquals(450, Shadows.shadowOf(vibrator).getMilliseconds());
        Shadows.shadowOf(vibrator).setHasVibrator(false); assertEquals("unsupported", tools.execute("phone_vibrate", new JSONObject().put("durationMs", 1)).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_vibrate", new JSONObject().put("durationMs", 0)).getString("code"));
    }

    @Test public void screenLockRequiresAnActuallyActivatedAdminAndNeverClaimsUnlock() throws Exception {
        assertEquals("permission_missing", tools.execute("phone_screen_lock", new JSONObject()).getString("code"));
        DevicePolicyManager manager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        Shadows.shadowOf(manager).setActiveAdmin(PermissionCatalog.adminComponent(context));
        JSONObject result = tools.execute("phone_screen_lock", new JSONObject()); assertTrue(result.toString(), result.getBoolean("accepted")); assertFalse(result.getBoolean("unlockSupported"));
        assertTrue(result.getBoolean("lockNowCalled")); assertTrue(((android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked());
    }

    @Test public void deviceAdminStateReportsRealForceLockPolicyWithoutImplyingDeviceOwner() throws Exception {
        JSONObject initial = tools.execute("phone_device_admin_state", new JSONObject()); assertFalse(initial.getBoolean("adminActive")); assertFalse(initial.getBoolean("forceLockPolicyGranted"));
        DevicePolicyManager manager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        Shadows.shadowOf(manager).setActiveAdmin(PermissionCatalog.adminComponent(context));
        Shadows.shadowOf(manager).grantPolicy(PermissionCatalog.adminComponent(context), android.app.admin.DeviceAdminInfo.USES_POLICY_FORCE_LOCK);
        JSONObject granted = tools.execute("phone_device_admin_state", new JSONObject()); assertTrue(granted.getBoolean("adminActive")); assertTrue(granted.getBoolean("forceLockPolicyGranted"));
        assertFalse(granted.getBoolean("deviceOwner")); assertFalse(granted.getBoolean("profileOwner")); assertFalse(granted.getBoolean("rootVerified"));
    }

    @Test public void phoneNumbersRequireBothGrantsAndHistoricalReceiptsFollowRevocation() throws Exception {
        assertEquals("permission_missing", tools.execute("phone_numbers_read", new JSONObject()).getString("code"));
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS);
        assertEquals("permission_missing", tools.execute("phone_numbers_read", new JSONObject()).getString("code"));
        assertFalse(tools.canReadArchivedResult("phone_numbers_read", new JSONObject()));
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.READ_PHONE_STATE); assertTrue(tools.canReadArchivedResult("phone_numbers_read", new JSONObject()));
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS); assertFalse(tools.canReadArchivedResult("phone_numbers_read", new JSONObject()));
        grantNotificationAccess(); assertTrue(tools.canReadArchivedResult("phone_notification_reply", new JSONObject()));
        Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", ""); assertFalse(tools.canReadArchivedResult("phone_notification_reply", new JSONObject()));
    }

    private void grantNotificationAccess() { Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", new ComponentName(context, HoyoungNotificationListener.class).flattenToString()); }
    private JSONObject target(StatusBarNotification notification) throws Exception { return new JSONObject().put("key", notification.getKey()).put("expectedPostedAt", notification.getPostTime()); }
    private PendingIntent pending(int requestCode) {
        return PendingIntent.getBroadcast(context, requestCode, new Intent("synthetic.test.action." + requestCode).setPackage(context.getPackageName()), PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private StatusBarNotification replyNotification(long postedAt) {
        RemoteInput input = new RemoteInput.Builder("synthetic_reply").setLabel("Reply").setAllowFreeFormInput(true).build();
        Notification.Action action = new Notification.Action.Builder(null, "Reply", pending(17)).addRemoteInput(input).build();
        Notification notification = new Notification.Builder(context, "synthetic").setContentTitle("Synthetic only").setContentText("no real recipient").addAction(action).build();
        return new StatusBarNotification("synthetic.app", "synthetic.app", 17, "test", 12345, 0, 0, notification, android.os.Process.myUserHandle(), postedAt);
    }
    private StatusBarNotification plainNotification(int id, long postedAt, String packageName) {
        return new StatusBarNotification(packageName, packageName, id, "test", 12345, 0, 0, new Notification.Builder(context, "synthetic").build(), android.os.Process.myUserHandle(), postedAt);
    }
    private DeleteProvider provider(String authority) {
        DeleteProvider provider = new DeleteProvider(); ProviderInfo info = new ProviderInfo(); info.authority = authority; provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal(authority, provider); return provider;
    }
    private static final class ActiveFixture implements PhoneExtendedTools.ActiveNotifications {
        StatusBarNotification[] notifications = new StatusBarNotification[0]; String dismissed; int reads;
        public StatusBarNotification[] current() { reads++; return notifications; }
        public void dismiss(String key) { dismissed = key; }
    }
    public static final class DeleteProvider extends ContentProvider {
        int rows = 1, calls; Uri lastUri; String selection;
        @Override public boolean onCreate() { return true; }
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) { calls++; lastUri = uri; this.selection = selection; return rows; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    }
}
