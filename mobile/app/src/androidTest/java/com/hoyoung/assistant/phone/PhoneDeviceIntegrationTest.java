package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.HoyoungStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Real providers and service callbacks on the disposable, offline API 35 AVD only. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class PhoneDeviceIntegrationTest {
    private Context context;
    private HoyoungStore store;
    private PhoneTools tools;
    private PermissionCatalog permissions;
    private String savedToolPermissions;
    private String savedCollectionEnabled;
    private final List<Uri> createdContacts = new ArrayList<>();
    private final List<Uri> createdCalendars = new ArrayList<>();
    private PhoneContextCollector collector;
    private String collectorStopReason;
    private String notificationChannel;
    private boolean listenerEnabledByTest;
    private static final String NOTIFICATION_TAG = "hoyoung-phone-ci-fixture";
    private static final int NOTIFICATION_ID = 37041;

    @Before public void setUp() {
        requireEmulator();
        assertEquals("These integration tests require the declared API 35 AVD.", 35, Build.VERSION.SDK_INT);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assertEquals("Only the test-installed target package may be modified.", "com.hoyoung.assistant", context.getPackageName());
        assertTrue(context instanceof HoyoungApplication);
        store = ((HoyoungApplication) context).getStore();
        assertNotNull(store);
        tools = new PhoneTools(context, store);
        permissions = new PermissionCatalog(context);
        savedToolPermissions = store.getSetting("toolPermissions", "{}");
        savedCollectionEnabled = store.getSetting("phoneContextEnabled", "true");
    }

    @After public void cleanUpOnlyOwnFixtures() throws Exception {
        requireEmulator();
        if (context == null) return;
        try {
            if (collector != null) {
                long after = store.lastDeviceEventId();
                collectorStopReason = "phone_ci_finished_" + UUID.randomUUID();
                collector.stop(collectorStopReason);
                awaitJson("collector did not finish unregistering its observers", () -> {
                    JSONArray rows = store.deviceEvents("collector_state", "android.device_collector", after, 100);
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i);
                        if (collectorStopReason.equals(row.getJSONObject("payload").optString("reason"))) return row;
                    }
                    return null;
                });
            }
        } finally {
            try {
                NotificationManager manager = context.getSystemService(NotificationManager.class);
                if (notificationChannel != null && manager != null) {
                    requireEmulator();
                    manager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
                    manager.deleteNotificationChannel(notificationChannel);
                }
                if (listenerEnabledByTest) shell("cmd notification disallow_listener " + listenerComponent());
            } finally {
                try {
                    for (Uri rawContact : createdContacts) {
                        requireEmulator();
                        context.getContentResolver().delete(rawContact.buildUpon()
                                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(), null, null);
                    }
                    for (Uri calendar : createdCalendars) {
                        requireEmulator();
                        context.getContentResolver().delete(calendar, null, null);
                    }
                } finally {
                    if (store != null && savedToolPermissions != null && savedCollectionEnabled != null) {
                        requireEmulator();
                        store.putSetting("toolPermissions", savedToolPermissions);
                        store.putSetting("phoneContextEnabled", savedCollectionEnabled);
                    }
                }
            }
        }
        // pm revoke can kill the instrumentation UID. The driver establishes the
        // denied baseline before launch; the disposable AVD is removed afterward.
    }

    @Test public void aContactsDeniedThenGrantedReadCreateUpdate() throws Exception {
        assertFalse("Install without -g and revoke before instrumentation.", permissions.has(Manifest.permission.READ_CONTACTS));
        assertFalse(permissions.has(Manifest.permission.WRITE_CONTACTS));
        String name = marker("contact");
        JSONObject arguments = new JSONObject().put("name", name).put("phone", "+1 202 555 0147")
                .put("email", "fixture@example.invalid");
        assertMissing(mutate("phone_contacts_create", arguments));
        assertMissing(tools.execute("phone_contacts_read", new JSONObject().put("query", name)));
        assertFalse(permissionEntry("contacts").getBoolean("granted"));

        grant(Manifest.permission.READ_CONTACTS);
        assertTrue(permissions.has(Manifest.permission.READ_CONTACTS));
        assertFalse(permissions.has(Manifest.permission.WRITE_CONTACTS));
        assertMissing(mutate("phone_contacts_create", arguments));
        JSONObject absent = tools.execute("phone_contacts_read", new JSONObject().put("query", name));
        assertOk(absent);
        assertEquals("Neither denied write may have created a row.", 0, absent.getJSONArray("contacts").length());
        assertFalse("Read-only permission is not a full contacts grant.", permissionEntry("contacts").getBoolean("granted"));

        grant(Manifest.permission.WRITE_CONTACTS);
        assertTrue(permissionEntry("contacts").getBoolean("granted"));
        Uri rawContact = createContact(arguments);
        JSONObject contact = awaitJson("ContactsProvider did not expose the new phone row", () ->
                named(tools.execute("phone_contacts_read", new JSONObject().put("query", name)).optJSONArray("contacts"), "name", name));
        assertEquals("+1 202 555 0147", contact.getString("phone"));
        long contactId = contact.getLong("contactId");
        JSONObject details = tools.execute("phone_contact_get", new JSONObject().put("contactId", contactId));
        assertOk(details);
        assertTrue(hasValue(details.getJSONArray("fields"), "fixture@example.invalid"));

        String updatedName = name + " Updated";
        assertOk(mutate("phone_contact_update", new JSONObject()
                .put("rawContactId", ContentUris.parseId(rawContact)).put("name", updatedName)));
        JSONObject updated = awaitJson("Provider-backed contact update did not become readable", () ->
                named(tools.execute("phone_contacts_read", new JSONObject().put("query", updatedName)).optJSONArray("contacts"), "name", updatedName));
        assertEquals(contactId, updated.getLong("contactId"));
    }

    @Test public void bCalendarDeniedThenGrantedReadCreateUpdate() throws Exception {
        assertFalse("Calendar permissions must be denied before instrumentation.", permissions.has(Manifest.permission.READ_CALENDAR));
        assertFalse(permissions.has(Manifest.permission.WRITE_CALENDAR));
        long start = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2);
        String title = marker("calendar");
        JSONObject arguments = new JSONObject().put("calendarId", 1L).put("title", title)
                .put("startMs", start).put("endMs", start + TimeUnit.MINUTES.toMillis(20));
        assertMissing(mutate("phone_calendar_create", arguments));
        assertMissing(tools.execute("phone_calendar_read", new JSONObject()));
        grant(Manifest.permission.READ_CALENDAR);
        assertMissing(mutate("phone_calendar_create", arguments));
        JSONObject absent = tools.execute("phone_calendar_read", calendarWindow(start));
        assertOk(absent);
        assertNull("Denied writes must not insert a synthetic event.", named(absent.getJSONArray("events"), "title", title));
        assertFalse(permissionEntry("calendar").getBoolean("granted"));

        grant(Manifest.permission.WRITE_CALENDAR);
        assertTrue(permissionEntry("calendar").getBoolean("granted"));
        Uri calendar = createLocalCalendar(marker("local_calendar"));
        arguments.put("calendarId", ContentUris.parseId(calendar));
        JSONObject created = mutate("phone_calendar_create", arguments);
        assertOk(created);
        long eventId = ContentUris.parseId(Uri.parse(created.getString("eventUri")));
        JSONObject event = awaitJson("CalendarProvider instances did not expose the new event", () ->
                named(tools.execute("phone_calendar_read", calendarWindow(start)).optJSONArray("events"), "title", title));
        assertEquals(eventId, event.getLong("eventId"));
        assertEquals(ContentUris.parseId(calendar), event.getLong("calendarId"));
        assertEquals(start, event.getLong("startMs"));

        String updatedTitle = title + " Updated";
        assertOk(mutate("phone_calendar_update", new JSONObject().put("eventId", eventId).put("title", updatedTitle)));
        try (Cursor cursor = context.getContentResolver().query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                new String[]{CalendarContract.Events.TITLE}, null, null, null)) {
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            assertEquals(updatedTitle, cursor.getString(0));
        }
    }

    @Test public void cCollectorObservesRealProviderChangeAndFiltersDisabledSource() throws Exception {
        grant(Manifest.permission.READ_CONTACTS);
        grant(Manifest.permission.WRITE_CONTACTS);
        collectionPolicy(true);
        collector = new PhoneContextCollector(context, tools, store);
        CountDownLatch initialSample = new CountDownLatch(1);
        collector.setChangeListener(initialSample::countDown);
        requireEmulator();
        collector.start();
        assertTrue("Collector worker did not finish its initial registration/sample.", initialSample.await(10, TimeUnit.SECONDS));
        long after = store.lastDeviceEventId();
        String name = marker("observed_contact");
        createContact(new JSONObject().put("name", name).put("phone", "+1 202 555 0183"));

        JSONObject change = awaitJson("Real provider mutation did not reach ContentObserver", () ->
                first(store.deviceEvents("provider_change", "android.contacts_provider", after, 100)));
        assertEquals("android.contacts_provider", change.getString("source"));
        assertTrue(change.getJSONObject("payload").getBoolean("changedFieldsUnknown"));
        assertTrue(change.getLong("observed_at") > 0);
        JSONObject snapshot = awaitJson("Collector did not retain the real updated contact sample", () -> {
            JSONArray rows = store.deviceEvents("provider_snapshot", "android.contacts_provider", after, 100);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (named(row.getJSONObject("payload").optJSONArray("contacts"), "name", name) != null) return row;
            }
            return null;
        });
        JSONObject visible = tools.execute("phone_context_read", new JSONObject().put("eventId", snapshot.getLong("id")));
        assertOk(visible);
        assertEquals("observations_only", visible.getString("coverage"));
        assertEquals(snapshot.getLong("id"), visible.getJSONObject("event").getLong("id"));

        JSONObject disabled = new JSONObject(store.getSetting("toolPermissions", "{}"));
        disabled.put("phone_contacts_read", false);
        requireEmulator();
        store.putSetting("toolPermissions", disabled.toString());
        JSONObject filtered = tools.execute("phone_context_read", new JSONObject().put("eventId", snapshot.getLong("id")));
        assertFalse(filtered.getBoolean("ok"));
        assertEquals("permission_disabled", filtered.getString("code"));
        assertNotNull("Disabling a source must not erase its durable observation.", store.getDeviceEvent(snapshot.getLong("id")));
    }

    @Test public void dNotificationListenerObservesActualSyntheticNotification() throws Exception {
        collectionPolicy(true);
        boolean previouslyEnabled = permissions.notificationAccess();
        if (!previouslyEnabled) {
            assertMissing(tools.execute("phone_notifications_read", new JSONObject()));
            shell("cmd notification allow_listener " + listenerComponent());
            listenerEnabledByTest = true;
        }
        awaitJson("Android did not enable and connect the declared notification listener", () ->
                permissions.notificationAccess() && HoyoungNotificationListener.isConnected() ? new JSONObject().put("connected", true) : null);
        assertTrue(permissionEntry("notification_access").getBoolean("granted"));
        grant(Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        assertNotNull(manager);
        notificationChannel = marker("notification_channel");
        String title = marker("notification_title");
        String text = "Synthetic Android notification callback fixture.";
        long after = store.lastDeviceEventId();
        requireEmulator();
        manager.createNotificationChannel(new NotificationChannel(notificationChannel, "Synthetic CI fixture", NotificationManager.IMPORTANCE_LOW));
        requireEmulator();
        manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, new Notification.Builder(context, notificationChannel)
                .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(text)
                .setOnlyAlertOnce(true).build());

        JSONObject event = awaitJson("Real notification post was not retained by the connected listener", () -> {
            JSONArray rows = store.deviceEvents("notification", "android.notification_listener", after, 100);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i), payload = row.getJSONObject("payload");
                if (title.equals(payload.optString("title")) && "posted".equals(payload.optString("event"))) return row;
            }
            return null;
        });
        assertEquals(context.getPackageName(), event.getJSONObject("payload").getString("package"));
        assertEquals(text, event.getJSONObject("payload").getString("text"));
        assertTrue(event.getJSONObject("payload").getBoolean("observedOnly"));
        JSONObject result = tools.execute("phone_notifications_read", new JSONObject().put("package", context.getPackageName()).put("limit", 100));
        assertOk(result);
        assertNotNull(named(result.getJSONArray("notifications"), "title", title));
        assertTrue(result.getBoolean("listenerConnected"));
    }

    private void collectionPolicy(boolean enabled) throws Exception {
        JSONObject policy = new JSONObject(savedToolPermissions);
        for (String name : new String[]{"phone_contacts_read", "phone_contact_get", "phone_calendar_read", "phone_app_state", "phone_notifications_read"}) policy.put(name, true);
        // This fixture observes real provider changes only; it does not manufacture
        // absent step sensors, location fixes, or usage history on the AVD.
        for (String name : new String[]{"phone_activity_read", "phone_location_last", "phone_usage_events"}) policy.put(name, false);
        requireEmulator();
        store.putSetting("phoneContextEnabled", String.valueOf(enabled));
        requireEmulator();
        store.putSetting("toolPermissions", policy.toString());
    }

    private JSONObject mutate(String name, JSONObject arguments) {
        requireEmulator();
        return tools.execute(name, arguments);
    }

    private Uri createContact(JSONObject arguments) throws Exception {
        JSONObject created = mutate("phone_contacts_create", arguments);
        assertOk(created);
        assertFalse(created.isNull("rawContactUri"));
        Uri uri = Uri.parse(created.getString("rawContactUri"));
        assertEquals(ContactsContract.AUTHORITY, uri.getAuthority());
        createdContacts.add(uri);
        return uri;
    }

    private Uri createLocalCalendar(String name) {
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Calendars.ACCOUNT_NAME, name);
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        values.put(CalendarContract.Calendars.NAME, name);
        values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name);
        values.put(CalendarContract.Calendars.OWNER_ACCOUNT, name);
        values.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
        values.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xff3366cc);
        values.put(CalendarContract.Calendars.VISIBLE, 1);
        values.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        values.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC");
        values.put(CalendarContract.Calendars.ALLOWED_REMINDERS, "0,1");
        values.put(CalendarContract.Calendars.ALLOWED_AVAILABILITY, "0,1,2");
        values.put(CalendarContract.Calendars.ALLOWED_ATTENDEE_TYPES, "0,1,2");
        values.put(CalendarContract.Calendars.MAX_REMINDERS, 5);
        Uri syncUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build();
        requireEmulator();
        Uri inserted = context.getContentResolver().insert(syncUri, values);
        assertNotNull("The official AOSP image must provide writable CalendarProvider.", inserted);
        Uri cleanup = inserted.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build();
        createdCalendars.add(cleanup);
        return inserted;
    }

    private void grant(String permission) throws Exception {
        assertTrue(permission.matches("android\\.permission\\.[A-Z_]+"));
        shell("pm grant --user 0 " + context.getPackageName() + " " + permission);
        awaitJson("Android did not grant " + permission, () -> permissions.has(permission) ? new JSONObject().put("granted", true) : null);
    }

    private void shell(String command) throws Exception {
        requireEmulator();
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command)) {
            assertNotNull(descriptor);
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                String response = output.toString(StandardCharsets.UTF_8.name());
                assertFalse("Targeted fixture command failed: " + response, response.contains("Exception") || response.contains("Error:"));
            }
        }
    }

    private String listenerComponent() {
        String value = new ComponentName(context, HoyoungNotificationListener.class).flattenToShortString();
        assertTrue(value.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+"));
        return value;
    }

    private JSONObject permissionEntry(String id) throws Exception {
        JSONObject entry = named(permissions.snapshot(), "id", id);
        assertNotNull("Permission catalog entry missing: " + id, entry);
        return entry;
    }

    private static JSONObject calendarWindow(long start) throws Exception {
        return new JSONObject().put("fromMs", start - TimeUnit.HOURS.toMillis(1))
                .put("toMs", start + TimeUnit.HOURS.toMillis(1)).put("limit", 100);
    }

    private static JSONObject named(JSONArray rows, String field, String value) throws Exception {
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (value.equals(row.optString(field))) return row;
        }
        return null;
    }

    private static JSONObject first(JSONArray rows) throws Exception { return rows.length() == 0 ? null : rows.getJSONObject(0); }
    private static boolean hasValue(JSONArray rows, String value) throws Exception { return named(rows, "value", value) != null; }
    private static String marker(String name) { return "Hoyoung CI " + name + " " + UUID.randomUUID(); }
    private static void assertOk(JSONObject result) throws Exception { assertTrue(result.toString(), result.getBoolean("ok")); }
    private static void assertMissing(JSONObject result) throws Exception {
        assertFalse(result.getBoolean("ok"));
        assertEquals("permission_missing", result.getString("code"));
    }

    private interface JsonObservation { JSONObject read() throws Exception; }
    private static JSONObject awaitJson(String failure, JsonObservation observation) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            JSONObject result = observation.read();
            if (result != null) return result;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError(failure);
    }

    private static void requireEmulator() {
        if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE)) {
            throw new AssertionError("Phone fixture mutations are restricted to the disposable Android emulator.");
        }
    }
}
