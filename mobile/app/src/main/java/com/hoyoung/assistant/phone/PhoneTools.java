package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Bounded native device tools. Every execution re-checks the actual Android grant. */
public final class PhoneTools {
    private final Context context;
    private final PermissionCatalog permissions;
    private final PhoneSharedFiles sharedFiles;
    private final PhoneUsageTools usageTools;
    private final PhoneProviderTools providerTools;
    private final PhoneEnvironmentTools environmentTools;
    private final PhoneExtendedTools extendedTools;
    private final PhoneRadioTools radioTools;
    private final PhoneAlarmTools alarmTools;
    private final PhoneOverlayTools overlayTools;
    private final PhoneFileTransferTools fileTransferTools;
    private final PhoneCommunicationTools communicationTools;
    private PhoneEventJournal eventJournal;
    private PhoneActivityTracker activityTracker;
    private PhoneLocationRequests locationRequests;
    private PhoneVoiceRecorder voiceRecorder;
    private PhoneScreenCapture screenCapture;
    public PhoneTools(Context context) { this.context = context.getApplicationContext(); this.permissions = new PermissionCatalog(this.context); this.sharedFiles = new PhoneSharedFiles(this.context); this.usageTools = new PhoneUsageTools(this.context); this.providerTools = new PhoneProviderTools(this.context); this.environmentTools = new PhoneEnvironmentTools(this.context); this.extendedTools = new PhoneExtendedTools(this.context); this.radioTools = new PhoneRadioTools(this.context); this.alarmTools = new PhoneAlarmTools(this.context); this.overlayTools = new PhoneOverlayTools(this.context); this.fileTransferTools = new PhoneFileTransferTools(this.context); this.communicationTools = new PhoneCommunicationTools(this.context); }
    public PhoneTools(Context context, HoyoungStore store) { this(context); eventJournal = PhoneEventJournal.bind(this.context, store); }
    void setActivityTracker(PhoneActivityTracker tracker) { activityTracker = tracker; }
    private PhoneEventJournal journal() { if (eventJournal == null) eventJournal = PhoneEventJournal.forContext(context); return eventJournal; }
    public void applyPermissionPolicyFromOwner(String toolPermissionsJson, boolean phoneContextEnabled) { journal().applyPermissionPolicyFromOwner(toolPermissionsJson, phoneContextEnabled); }
    public void pauseForBackupRestore() {
        if (!com.hoyoung.assistant.runtime.VoiceCaptureService.stopAndAwaitForRestore()) throw new IllegalStateException("Microphone capture did not finish before backup restore");
        if (screenCapture != null) screenCapture.cancelAll("backup_restore");
        radioTools.pauseForBackupRestore();
        alarmTools.pauseForBackupRestore();
        overlayTools.pauseForBackupRestore();
        fileTransferTools.pauseForBackupRestore();
        communicationTools.pauseForBackupRestore();
        journal().pauseForBackupRestore();
    }
    public void afterBackupRestore() { journal().afterBackupRestore(); radioTools.afterBackupRestore(); alarmTools.afterBackupRestore(); overlayTools.afterBackupRestore(); fileTransferTools.afterBackupRestore(); communicationTools.afterBackupRestore(); }

    public JSONArray catalog() {
        JSONArray tools = new JSONArray();
        tool(tools, "phone_device_info", "Read non-identifier device and permission state.", new JSONObject());
        tool(tools, "phone_apps_list", "List a bounded set of installed apps visible to Android package queries.", properties("limit", "integer", "query", "string"));
        tool(tools, "phone_app_launch", "Request opening a specified installed app; Android may block background launches.", properties("package", "string"), "package");
        tool(tools, "phone_contacts_read", "Read up to 100 address-book phone entries after READ_CONTACTS grant.", properties("query", "string", "limit", "integer"));
        tool(tools, "phone_contacts_create", "Create one address-book contact after WRITE_CONTACTS grant.", properties("name", "string", "phone", "string", "email", "string"), "name");
        tool(tools, "phone_calendar_read", "Read visible calendars and up to 100 events in at most a 31-day window. Millisecond timestamps.", properties("fromMs", "integer", "toMs", "integer", "limit", "integer"));
        tool(tools, "phone_calendar_create", "Create one event in a user-selected writable calendar. Millisecond timestamps.", properties("calendarId", "integer", "title", "string", "startMs", "integer", "endMs", "integer", "description", "string", "location", "string"), "calendarId", "title", "startMs", "endMs");
        tool(tools, "phone_notifications_read", "Read a bounded subset of durable notification observations only when notification access is enabled. Explicit tool execution is required; collection does not upload content.", properties("limit", "integer", "package", "string"));
        tool(tools, "phone_location_last", "Read only a sufficiently recent last-known location. Does not start continuous tracking.", properties("maxAgeMs", "integer"));
        tool(tools, "phone_volume_set", "Set music/ring/alarm volume within that stream's actual range.", properties("stream", "string", "level", "integer"), "stream", "level");
        tool(tools, "phone_flashlight_set", "Request the back camera flashlight after CAMERA grant.", properties("enabled", "boolean"), "enabled");
        tool(tools, "phone_clipboard_read", "Read Android-exposed clipboard text; OS may withhold it. No app foreground veto.", new JSONObject());
        tool(tools, "phone_ui_snapshot", "Read bounded visible UI, including Android-exposed editable/password metadata and text.", new JSONObject());
        tool(tools, "phone_ui_click", "Click a nodeId from the latest UI snapshot, or a unique exact visible text.", properties("nodeId", "string", "text", "string"));
        tool(tools, "phone_ui_type", "Set supplied text up to20000 UTF-16 units in a fresh editable node, including passwords. Requires Accessibility and tool permission. Password input acceptance does not prove login; results do not echo text.", properties("nodeId", "string", "text", "string"), "nodeId", "text");
        tool(tools, "phone_ui_global", "Request home/back/recents/notifications navigation using a user-enabled accessibility service.", properties("action", "string"), "action");
        tool(tools, "phone_ui_tap", "Request an on-screen accessibility tap. Android's accepted status is not completion.", properties("x", "number", "y", "number"), "x", "y");
        tool(tools, "phone_ui_swipe", "Request an on-screen accessibility swipe; duration 40–1500 ms.", properties("x1", "number", "y1", "number", "x2", "number", "y2", "number", "durationMs", "integer"), "x1", "y1", "x2", "y2");
        tool(tools, "phone_brightness_set", "Write screen brightness 0–255 after explicit Write System Settings grant.", properties("value", "integer"), "value");
        tool(tools, "phone_dial_open", "Open the dialer with a phone number. Does not place the call.", properties("number", "string"), "number");
        tool(tools, "phone_url_open", "Request opening one HTTP/HTTPS URL with Android's default web handler. Returns launch requested, not browser loading/authentication/completion.", properties("url", "string"), "url");
        tool(tools, "phone_files_list", "List one shared-storage directory, at most 100 entries. Requires actual all-files access; excludes protected app data.", properties("path", "string", "limit", "integer"));
        tool(tools, "phone_files_read", "Read valid UTF-8 shared text, at most 50000 characters and 1 MiB file size. Relative path only.", properties("path", "string", "maxChars", "integer"), "path");
        tool(tools, "phone_files_write", "Atomically write at most 100000 text characters to a relative shared-storage path. Requires actual storage grant.", properties("path", "string", "content", "string"), "path", "content");
        tool(tools, "phone_activity_read", "Read actual observed step counter/detector state, freshness, reset and coverage gaps. Does not infer walking/driving or promise whole-day step totals.", new JSONObject());
        tool(tools, "phone_usage_read", "Read bounded Android usage aggregates; returned buckets may extend past the requested range.", properties("fromMs", "integer", "toMs", "integer", "package", "string", "limit", "integer"));
        tool(tools, "phone_usage_events", "Read observed app/screen transitions, up to 200 in a 24-hour query window; not proof of the currently active app.", properties("fromMs", "integer", "toMs", "integer", "package", "string", "limit", "integer"));
        tool(tools, "phone_app_state", "Read screen/keyguard/self-process state, plus optional package inactive state after Usage Access.", properties("package", "string"));
        tool(tools, "phone_contact_get", "Read bounded name/phone/email/address/organization fields, with rawContactId for an explicit later update.", properties("contactId", "integer"), "contactId");
        tool(tools, "phone_contact_update", "Replace selected name/phone/email fields in one explicit raw contact; requires read/write permission. All existing values of a requested field kind are replaced.", properties("rawContactId", "integer", "name", "string", "phone", "string", "email", "string"), "rawContactId");
        tool(tools, "phone_calendar_update", "Update one explicit event. Provide start and end together when changing its time.", properties("eventId", "integer", "title", "string", "description", "string", "location", "string", "startMs", "integer", "endMs", "integer"), "eventId");
        tool(tools, "phone_bluetooth_bonded", "Read up to 100 OS-visible paired Bluetooth devices after Nearby grant. Pairing does not prove a live connection; no scan is started.", properties("limit", "integer"));
        tool(tools, "phone_network_state", "Read active connectivity, metering and Wi-Fi information that Android actually exposes; redacted fields remain unknown.", new JSONObject());
        tool(tools, "phone_call_state", "Read actual call/SIM state after phone-state permission, without device identifiers or placing calls.", new JSONObject());
        tool(tools, "phone_media_list", "List up to 100 media metadata entries by kind(images/video/audio). Selected-photo grants remain partial; no binary upload.", properties("kind", "string", "limit", "integer"), "kind");
        tool(tools, "phone_context_search", "Search relevant durable local device observations with event IDs and provenance. Current OS and per-source app grants filter results again.", properties("query", "string", "category", "string", "limit", "integer"), "query");
        tool(tools, "phone_context_read", "Read an explicit durable eventId or page forward from afterId, with permission filtering. Source/category optional; max 100 results.", properties("eventId", "integer", "afterId", "integer", "category", "string", "source", "string", "limit", "integer"));
        tool(tools, "phone_location_refresh", "Request a bounded one-shot fix asynchronously. Android may return a recent cached fix. maxAgeMs defaults to60000 and caps at900000. Background access requires actual background-location grant.", properties("timeoutMs", "integer", "maxAgeMs", "integer"));
        tool(tools, "phone_location_refresh_read", "Read a specific one-shot location request receipt. Process interruption is reported as unknown, not silently retried.", properties("requestId", "string"), "requestId");
        tool(tools, "phone_camera_open", "Request opening the system camera UI, where a user or permitted UI tool can take a photo. This does not claim a photo was already captured.", new JSONObject());
        tool(tools, "phone_voice_record", "Request microphone FGS capture; durationMs>=0,0=until stopped. Android may deny background starts. Receipts distinguish request, recording and saved audio.", properties("durationMs", "integer"));
        tool(tools, "phone_voice_record_read", "Read a voice capture receipt/fileId; audio is local and not automatically uploaded.", properties("requestId", "string"), "requestId");
        tool(tools, "phone_voice_record_stop", "Stop one microphone request; read its receipt for saved audio and actual stop state.", properties("requestId", "string"), "requestId");
        extendedTools.appendCatalog(tools);
        radioTools.appendCatalog(tools);
        alarmTools.appendCatalog(tools);
        overlayTools.appendCatalog(tools);
        fileTransferTools.appendCatalog(tools);
        communicationTools.appendCatalog(tools);
        JSONArray captureTools = PhoneScreenCapture.catalog();
        for (int i = 0; i < captureTools.length(); i++) tools.put(captureTools.optJSONObject(i));
        return tools;
    }

    public JSONObject execute(String name, JSONObject arguments) {
        JSONObject args = arguments == null ? new JSONObject() : arguments;
        try {
            JSONObject schema = definition(name);
            if (schema == null) return failure("unsupported", "No such native phone tool is installed.");
            validate(args, schema);
            if ("phone_context_read".equals(name) || "phone_context_search".equals(name)) journal().refreshPolicyBlocking();
            if (extendedTools.supports(name)) {
                journal().refreshPolicyBlocking();
                if (!journal().toolAllowed(name)) return failure("permission_disabled", "This phone tool is disabled in app settings.");
                return extendedTools.execute(name, args);
            }
            if (radioTools.supports(name) || alarmTools.supports(name) || overlayTools.supports(name) || fileTransferTools.supports(name) || communicationTools.supports(name)) {
                journal().refreshPolicyBlocking();
                if (!journal().toolAllowed(name)) return failure("permission_disabled", "This phone tool is disabled in app settings.");
                if (radioTools.supports(name)) return radioTools.execute(name, args);
                if (alarmTools.supports(name)) return alarmTools.execute(name, args);
                if (overlayTools.supports(name)) return overlayTools.execute(name, args);
                return fileTransferTools.supports(name) ? fileTransferTools.execute(name, args) : communicationTools.execute(name, args);
            }
            switch (name == null ? "" : name) {
                case "phone_device_info": return deviceInfo();
                case "phone_apps_list": return apps(args);
                case "phone_app_launch": return launch(args);
                case "phone_contacts_read": return contacts(args);
                case "phone_contacts_create": return createContact(args);
                case "phone_calendar_read": return calendar(args);
                case "phone_calendar_create": return createEvent(args);
                case "phone_notifications_read": return notifications(args);
                case "phone_location_last": return location(args);
                case "phone_volume_set": return volume(args);
                case "phone_flashlight_set": return flashlight(args);
                case "phone_clipboard_read": return clipboard();
                case "phone_ui_snapshot": case "phone_ui_click": case "phone_ui_type": case "phone_ui_global": case "phone_ui_tap": case "phone_ui_swipe": return accessibility(name, args);
                case "phone_brightness_set": return brightness(args);
                case "phone_dial_open": return dial(args);
                case "phone_url_open": return openUrl(args);
                case "phone_files_list": case "phone_files_read": case "phone_files_write": return sharedFiles.execute(name, args);
                case "phone_activity_read":
                    if (!journal().toolAllowed(name)) return failure("permission_disabled", "Activity observations are disabled in app settings.");
                    if (activityTracker == null) activityTracker = new PhoneActivityTracker(context, journal());
                    return activityTracker.snapshot();
                case "phone_usage_read": return usageTools.usage(args);
                case "phone_usage_events": return usageTools.events(args);
                case "phone_app_state": return usageTools.appState(args);
                case "phone_contact_get": return providerTools.contact(args);
                case "phone_contact_update": return providerTools.updateContact(args);
                case "phone_calendar_update": return providerTools.updateEvent(args);
                case "phone_bluetooth_bonded": return environmentTools.bluetooth(args);
                case "phone_network_state": return environmentTools.network();
                case "phone_call_state": return environmentTools.phoneState();
                case "phone_media_list": return environmentTools.media(args);
                case "phone_context_search": return contextSearch(args);
                case "phone_context_read": return contextRead(args);
                case "phone_location_refresh": if (locationRequests == null) locationRequests = new PhoneLocationRequests(context, journal()); return locationRequests.request(args);
                case "phone_location_refresh_read": if (locationRequests == null) locationRequests = new PhoneLocationRequests(context, journal()); return locationRequests.read(args);
                case "phone_camera_open":
                    if (!permissions.has(Manifest.permission.CAMERA)) return missing("camera");
                    Intent camera = new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (camera.resolveActivity(context.getPackageManager()) == null) return failure("unsupported", "No system camera is available.");
                    context.startActivity(camera); return success().put("requested", true).put("photoCaptured", false).put("completionConfirmed", false);
                case "phone_voice_record": if (voiceRecorder == null) voiceRecorder = new PhoneVoiceRecorder(context, journal()); return voiceRecorder.start(args);
                case "phone_voice_record_read":
                    journal().refreshPolicyBlocking();
                    if (!permissions.has(Manifest.permission.RECORD_AUDIO)) return missing("microphone");
                    if (!journal().toolAllowed(name) || !journal().toolAllowed("phone_voice_record")) return failure("permission_disabled", "Microphone capture is disabled in app settings.");
                    if (voiceRecorder == null) voiceRecorder = new PhoneVoiceRecorder(context, journal()); return voiceRecorder.read(args);
                case "phone_voice_record_stop":
                    journal().refreshPolicyBlocking();
                    if (!permissions.has(Manifest.permission.RECORD_AUDIO)) return missing("microphone");
                    if (!journal().toolAllowed(name)) return failure("permission_disabled", "This phone tool is disabled in app settings.");
                    if (voiceRecorder == null) voiceRecorder = new PhoneVoiceRecorder(context, journal()); return voiceRecorder.stop(args);
                case "phone_screen_capture": case "phone_screen_capture_read": case "phone_screen_capture_cancel":
                    journal().refreshPolicyBlocking();
                    if (screenCapture == null) screenCapture = PhoneScreenCapture.forContext(context, journal());
                    return screenCapture.execute(name, args);
                default: return failure("unsupported", "No such native phone tool is installed.");
            }
        } catch (SecurityException denied) { return failure("permission_missing", "Android denied this operation. Check the current grant, role and device policy."); }
        catch (JSONException | IllegalArgumentException invalid) { return failure("invalid_arguments", "Arguments are missing or outside the supported range."); }
        catch (Exception unavailable) { return failure("operation_failed", "Android could not complete this operation; no success is assumed."); }
    }

    /** Native effect classification shares this registry with runtime scheduling. */
    public static boolean isReadOnly(String name) {
        if (name == null) return false;
        switch (name) {
            case "phone_device_info": case "phone_apps_list": case "phone_contacts_read":
            case "phone_calendar_read": case "phone_notifications_read": case "phone_location_last":
            case "phone_clipboard_read": case "phone_ui_snapshot": case "phone_files_list": case "phone_files_read":
            case "phone_activity_read": case "phone_usage_read": case "phone_usage_events": case "phone_app_state":
            case "phone_contact_get": case "phone_bluetooth_bonded": case "phone_network_state": case "phone_call_state":
            case "phone_media_list": case "phone_context_search": case "phone_context_read":
            case "phone_location_refresh_read": case "phone_voice_record_read":
            case "phone_notifications_active": case "phone_dnd_read": case "phone_numbers_read":
            case "phone_device_admin_state": case "phone_screen_capture_read":
            case "phone_bluetooth_scan_read": case "phone_wifi_scan_read": case "phone_wifi_hotspot_read":
            case "phone_alarm_list": return true;
            case "phone_files_transfer_read": case "phone_sms_read": case "phone_sms_get": case "phone_sms_send_read": case "phone_call_log_read": return true;
            default: return false;
        }
    }

    private JSONObject deviceInfo() throws JSONException {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return success().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("androidVersion", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("locale", Locale.getDefault().toLanguageTag()).put("timeZone", TimeZone.getDefault().getID()).put("interactive", power != null && power.isInteractive())
            .put("permissions", permissions.snapshot()).put("rootAvailable", false).put("deviceOwnerAvailable", false);
    }

    private JSONObject apps(JSONObject args) throws JSONException {
        int limit = limit(args, 100, 200); String query = optionalString(args, "query", 100).toLowerCase(Locale.ROOT);
        PackageManager pm = context.getPackageManager(); JSONArray output = new JSONArray(); boolean truncated = false;
        for (ApplicationInfo info : pm.getInstalledApplications(0)) {
            String label = String.valueOf(pm.getApplicationLabel(info));
            if (!query.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(query) && !info.packageName.toLowerCase(Locale.ROOT).contains(query)) continue;
            if (output.length() >= limit) { truncated = true; break; }
            output.put(new JSONObject().put("package", info.packageName).put("label", label).put("launchable", pm.getLaunchIntentForPackage(info.packageName) != null));
        }
        return success().put("apps", output).put("truncated", truncated).put("visibility", "Only packages visible under this installation's Android package-query policy are returned.");
    }

    private JSONObject launch(JSONObject args) throws JSONException {
        String packageName = requiredString(args, "package", 200);
        if (!packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException();
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) return failure("app_unavailable", "The app is not installed, visible, or launchable.");
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return success().put("requested", true).put("completionConfirmed", false).put("package", packageName);
    }

    private JSONObject contacts(JSONObject args) throws JSONException {
        if (!permissions.has(Manifest.permission.READ_CONTACTS)) return missing("contacts");
        int limit = limit(args, 50, 100); String query = optionalString(args, "query", 200);
        String selection = query.isEmpty() ? null : ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?";
        String[] selectionArgs = query.isEmpty() ? null : new String[]{"%" + query + "%"};
        JSONArray items = new JSONArray();
        String[] projection = {ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor cursor = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, selection, selectionArgs, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            while (cursor != null && cursor.moveToNext() && items.length() < limit) items.put(new JSONObject().put("contactId", cursor.getLong(0)).put("name", cursor.getString(1)).put("phone", cursor.getString(2)));
        }
        return success().put("contacts", items).put("limit", limit);
    }

    private JSONObject createContact(JSONObject args) throws Exception {
        if (!permissions.has(Manifest.permission.WRITE_CONTACTS)) return missing("contacts");
        String name = requiredString(args, "name", 200), phone = optionalString(args, "phone", 80), email = optionalString(args, "email", 200);
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        operations.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build());
        operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build());
        if (!phone.isEmpty()) operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone).withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build());
        if (!email.isEmpty()) operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email).build());
        android.content.ContentProviderResult[] results = context.getContentResolver().applyBatch(ContactsContract.AUTHORITY, operations);
        return success().put("rawContactUri", results.length > 0 && results[0].uri != null ? results[0].uri.toString() : JSONObject.NULL);
    }

    private JSONObject calendar(JSONObject args) throws JSONException {
        if (!permissions.has(Manifest.permission.READ_CALENDAR)) return missing("calendar");
        long from = args.optLong("fromMs", System.currentTimeMillis()), to = args.optLong("toMs", from + 7L * 86400000);
        if (from < 0 || to <= from || to - from > 31L * 86400000) throw new IllegalArgumentException();
        int limit = limit(args, 50, 100); JSONArray calendars = new JSONArray(), events = new JSONArray();
        String[] projection = {CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL};
        try (Cursor cursor = context.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)) {
            while (cursor != null && cursor.moveToNext() && calendars.length() < 100) calendars.put(new JSONObject().put("id", cursor.getLong(0)).put("name", cursor.getString(1)).put("writable", cursor.getInt(2) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR));
        }
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon(); ContentUris.appendId(uri, from); ContentUris.appendId(uri, to);
        String[] fields = {CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION};
        try (Cursor cursor = context.getContentResolver().query(uri.build(), fields, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (cursor != null && cursor.moveToNext() && events.length() < limit) events.put(new JSONObject().put("eventId", cursor.getLong(0)).put("calendarId", cursor.getLong(1)).put("title", cursor.getString(2)).put("startMs", cursor.getLong(3)).put("endMs", cursor.getLong(4)).put("location", cursor.getString(5)));
        }
        return success().put("calendars", calendars).put("events", events).put("fromMs", from).put("toMs", to);
    }

    private JSONObject createEvent(JSONObject args) throws JSONException {
        if (!permissions.has(Manifest.permission.WRITE_CALENDAR)) return missing("calendar");
        long calendarId = args.getLong("calendarId"), start = args.getLong("startMs"), end = args.getLong("endMs");
        if (calendarId < 0 || start < 0 || end <= start || end - start > 366L * 86400000) throw new IllegalArgumentException();
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId); values.put(CalendarContract.Events.TITLE, requiredString(args, "title", 300));
        values.put(CalendarContract.Events.DTSTART, start); values.put(CalendarContract.Events.DTEND, end); values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        values.put(CalendarContract.Events.DESCRIPTION, optionalString(args, "description", 4000)); values.put(CalendarContract.Events.EVENT_LOCATION, optionalString(args, "location", 500));
        Uri uri = context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values);
        return uri == null ? failure("operation_failed", "The calendar provider returned no event.") : success().put("eventUri", uri.toString());
    }

    private JSONObject notifications(JSONObject args) throws JSONException {
        if (!permissions.notificationAccess()) return missing("notification_access");
        int limit = limit(args, 50, 250); String packageName = optionalString(args, "package", 200);
        JSONArray all = HoyoungNotificationListener.notificationSnapshot(context), result = new JSONArray();
        for (int i = all.length() - 1; i >= 0 && result.length() < limit; i--) { JSONObject entry = all.optJSONObject(i); if (entry != null && (packageName.isEmpty() || packageName.equals(entry.optString("package")))) result.put(entry); }
        return success().put("notifications", result).put("listenerConnected", HoyoungNotificationListener.isConnected()).put("historical", true).put("sensitive", true).put("requestScoped", true).put("source", "local_notification_listener_history");
    }

    private JSONObject location(JSONObject args) throws JSONException {
        boolean fine = permissions.has(Manifest.permission.ACCESS_FINE_LOCATION), coarse = permissions.has(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!fine && !coarse) return missing("location");
        long maximumAge = args.optLong("maxAgeMs", 15L * 60000); if (maximumAge < 0 || maximumAge > 24L * 3600000) throw new IllegalArgumentException();
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE); if (manager == null) return failure("unsupported", "Location service is unavailable.");
        Location best = null;
        for (String provider : manager.getProviders(true)) {
            if (!fine && LocationManager.GPS_PROVIDER.equals(provider)) continue;
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate == null || !Double.isFinite(candidate.getLatitude()) || !Double.isFinite(candidate.getLongitude()) || Math.abs(candidate.getLatitude()) > 90 || Math.abs(candidate.getLongitude()) > 180
                    || candidate.getElapsedRealtimeNanos() > android.os.SystemClock.elapsedRealtimeNanos() || (candidate.hasAccuracy() && (!Float.isFinite(candidate.getAccuracy()) || candidate.getAccuracy() < 0))) continue;
                if (best == null || candidate.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos()) best = candidate;
            } catch (SecurityException deniedProvider) { }
        }
        if (best == null) return failure("location_unavailable", "No last-known location is available. Continuous tracking was not started.");
        long age = (android.os.SystemClock.elapsedRealtimeNanos() - best.getElapsedRealtimeNanos()) / 1000000;
        if (age < 0 || age > maximumAge) return failure("location_stale", "The cached location exceeds the requested maximum age.").put("ageMs", age);
        return success().put("latitude", best.getLatitude()).put("longitude", best.getLongitude()).put("accuracyMeters", best.hasAccuracy() ? best.getAccuracy() : JSONObject.NULL).put("ageMs", age).put("timeMs", best.getTime()).put("precisionPermission", fine ? "fine" : "coarse")
            .put("provider", best.getProvider()).put("mock", Build.VERSION.SDK_INT >= 31 ? best.isMock() : best.isFromMockProvider()).put("source", "android.location_manager").put("cached", true).put("freshFixRequested", false);
    }

    private JSONObject volume(JSONObject args) throws JSONException {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE); if (manager == null) return failure("unsupported", "Audio service is unavailable.");
        String stream = requiredString(args, "stream", 20); int type;
        switch (stream) { case "music": type = AudioManager.STREAM_MUSIC; break; case "ring": type = AudioManager.STREAM_RING; break; case "alarm": type = AudioManager.STREAM_ALARM; break; default: throw new IllegalArgumentException(); }
        int level = args.getInt("level"), max = manager.getStreamMaxVolume(type), min = Build.VERSION.SDK_INT >= 28 ? manager.getStreamMinVolume(type) : 0;
        if (level < min || level > max) return failure("invalid_arguments", "Volume must be in the stream's current range.").put("minimum", min).put("maximum", max);
        manager.setStreamVolume(type, level, 0); return success().put("stream", stream).put("level", manager.getStreamVolume(type)).put("maximum", max);
    }

    private JSONObject flashlight(JSONObject args) throws Exception {
        if (!permissions.has(Manifest.permission.CAMERA)) return missing("camera");
        boolean enabled = args.getBoolean("enabled"); CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return failure("unsupported", "Camera service is unavailable.");
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics info = manager.getCameraCharacteristics(id);
            if (Boolean.TRUE.equals(info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) && Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(info.get(CameraCharacteristics.LENS_FACING))) {
                manager.setTorchMode(id, enabled); return success().put("requested", true).put("completionConfirmed", false).put("enabled", enabled);
            }
        }
        return failure("unsupported", "No back-camera flashlight was found.");
    }

    private JSONObject clipboard() throws JSONException {
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return failure("unsupported", "Clipboard service is unavailable.");
        ClipData data = manager.getPrimaryClip(); if (data == null || data.getItemCount() == 0) return success().put("text", "").put("readableClipPresent", false).put("coverage", "empty_or_withheld_by_android");
        // Do not coerce arbitrary URI clipboard data into a provider read.
        CharSequence value = data.getItemAt(0).getText(); String text = value == null ? "" : value.toString();
        return success().put("text", text.length() > 8000 ? text.substring(0, 8000) : text).put("readableClipPresent", true).put("truncated", text.length() > 8000).put("coverage", "os_exposed_plain_text");
    }

    private JSONObject brightness(JSONObject args) throws JSONException {
        if (!Settings.System.canWrite(context)) return missing("write_settings"); int value = args.getInt("value"); if (value < 0 || value > 255) throw new IllegalArgumentException();
        boolean written = Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
        return written ? success().put("value", value).put("automaticBrightnessMayOverride", true) : failure("operation_failed", "Android refused to write brightness.");
    }

    private JSONObject dial(JSONObject args) throws JSONException {
        String number = requiredString(args, "number", 80); if (!number.matches("[+0-9 ()-]{1,80}")) throw new IllegalArgumentException();
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) == null) return failure("unsupported", "No dialer is available.");
        context.startActivity(intent); return success().put("dialerRequested", true).put("callPlaced", false);
    }

    private JSONObject accessibility(String name, JSONObject args) throws JSONException {
        if (!permissions.accessibilityAccess()) return missing("accessibility");
        HoyoungAccessibilityService service = HoyoungAccessibilityService.getInstance();
        if (service == null) return failure("service_unavailable", "Accessibility is enabled but not connected. Wait for Android to connect it.");
        switch (name) {
            case "phone_ui_snapshot": return service.snapshot();
            case "phone_ui_click": return service.click(optionalString(args, "nodeId", 200), optionalString(args, "text", 1000));
            case "phone_ui_type": return service.type(requiredString(args, "nodeId", 200), optionalString(args, "text", 20000));
            case "phone_ui_global": return service.global(requiredString(args, "action", 30));
            case "phone_ui_tap": return service.tap((float) args.getDouble("x"), (float) args.getDouble("y"));
            default: return service.swipe((float) args.getDouble("x1"), (float) args.getDouble("y1"), (float) args.getDouble("x2"), (float) args.getDouble("y2"), args.optLong("durationMs", 300));
        }
    }
    private JSONObject openUrl(JSONObject args) throws Exception {
        String url = requiredString(args, "url", 4000); java.net.URI parsed;
        try { parsed = new java.net.URI(url); } catch (java.net.URISyntaxException malformed) { throw new IllegalArgumentException("Invalid web URL", malformed); }
        String scheme = parsed.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || parsed.getHost() == null || parsed.getHost().isEmpty() || parsed.getPort() == 0 || parsed.getPort() > 65535) throw new IllegalArgumentException();
        String asciiUrl = parsed.toASCIIString(); String normalizedUrl = scheme.toLowerCase(Locale.ROOT) + asciiUrl.substring(scheme.length());
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.content.ComponentName handler = intent.resolveActivity(context.getPackageManager());
        if (handler == null) return failure("unsupported", "Android has no installed web handler for this URL.");
        context.startActivity(intent);
        return success().put("code", "url_launch_requested").put("requested", true).put("completionConfirmed", false).put("browserLoaded", false).put("authenticationVerified", false).put("handlerPackage", handler.getPackageName());
    }
    private static int limit(JSONObject args, int fallback, int maximum) throws JSONException { int value = args.has("limit") ? args.getInt("limit") : fallback; if (value < 1 || value > maximum) throw new IllegalArgumentException(); return value; }
    private static String optionalString(JSONObject args, String key, int maximum) throws JSONException { if (!args.has(key) || args.isNull(key)) return ""; Object value = args.get(key); if (!(value instanceof String) || ((String) value).length() > maximum) throw new IllegalArgumentException(); return (String) value; }
    private static String requiredString(JSONObject args, String key, int maximum) throws JSONException { String value = optionalString(args, key, maximum); if (value.trim().isEmpty()) throw new IllegalArgumentException(); return value; }
    private static JSONObject success() { return object(true, "ok", ""); }
    private JSONObject contextRead(JSONObject args) throws JSONException {
        PhoneEventJournal journal = journal(); if (!journal.collectionEnabled()) return failure("permission_disabled", "Phone observation context is disabled.");
        if (args.has("eventId")) {
            long id = args.getLong("eventId"); if (id <= 0 || args.has("afterId")) throw new IllegalArgumentException();
            JSONObject event = journal.read(id);
            if (event == null) return failure("target_not_found", "No such durable device event.");
            if (!mayExpose(event)) return failure("permission_disabled", "The event's current source permission or tool access is disabled.");
            return success().put("event", event).put("coverage", "observations_only");
        }
        long after = args.optLong("afterId", 0); int limit = args.optInt("limit", 50); if (after < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException();
        String category = optionalString(args, "category", 100), source = optionalString(args, "source", 200);
        JSONArray raw = journal.events(category.isEmpty() ? null : category, source.isEmpty() ? null : source, after, 500), visible = new JSONArray(); long next = after; int omitted = 0;
        for (int i = 0; i < raw.length() && visible.length() < limit; i++) { JSONObject event = raw.optJSONObject(i); if (event == null) continue; next = event.optLong("id", next); if (mayExpose(event)) visible.put(event); else omitted++; }
        return success().put("events", visible).put("nextAfterId", next).put("omittedForCurrentPermissions", omitted).put("hasMore", raw.length() == 500 || next < (raw.length() == 0 ? after : raw.optJSONObject(raw.length() - 1).optLong("id"))).put("coverage", "observations_only; collection gaps remain explicit journal records");
    }
    private JSONObject contextSearch(JSONObject args) throws JSONException {
        if (!journal().collectionEnabled()) return failure("permission_disabled", "Phone observation context is disabled.");
        String query = requiredString(args, "query", 2000).toLowerCase(Locale.ROOT), category = optionalString(args, "category", 100);
        int limit = args.optInt("limit", 20); if (limit < 1 || limit > 100) throw new IllegalArgumentException();
        JSONArray candidates = journal().search(query, category.isEmpty() ? null : category, 500), output = new JSONArray();
        for (int i = 0; i < candidates.length() && output.length() < limit; i++) {
            JSONObject row = candidates.optJSONObject(i); if (row == null || !mayExpose(row) || (!category.isEmpty() && !category.equals(row.optString("category")))) continue;
            output.put(row);
        }
        return success().put("events", output).put("searchScope", "durable_archive; up_to_500_matching_candidates_per_search").put("coverage", "observations_only");
    }
    /** External memory/model retrieval hook: invoke on its background retrieval thread. */
    public boolean canReadStoredSource(JSONObject row) { journal().refreshPolicyBlocking(); return row != null && mayExpose(row); }
    /** Permission-aware implicit retrieval; display of our own reply is not external input. */
    public boolean canUseImplicitMemorySource(JSONObject row) { return canReadStoredSource(row) && !isOwnAssistantNotification(row); }
    private boolean isOwnAssistantNotification(JSONObject row) {
        JSONObject payload = row == null ? null : row.optJSONObject("payload");
        return row != null && "notification".equals(row.optString("category")) && payload != null
            && context.getPackageName().equals(payload.optString("package"));
    }
    /** Historical receipt visibility; call on the background retrieval thread, never in an OS callback. */
    public boolean canReadArchivedToolResult(String name, JSONObject result) {
        PhoneEventJournal journal = journal(); journal.refreshPolicyBlocking();
        if (name == null || definition(name) == null || result == null || !journal.toolAllowed(name)) return false;
        if (extendedTools.supports(name)) return extendedTools.canReadArchivedResult(name, result);
        if (radioTools.supports(name)) return radioTools.canReadArchivedResult(name, result);
        if (alarmTools.supports(name)) return alarmTools.canReadArchivedResult(name, result);
        if (overlayTools.supports(name)) return overlayTools.canReadArchivedResult(name, result);
        if (fileTransferTools.supports(name)) return fileTransferTools.canReadArchivedResult(name, result);
        if (communicationTools.supports(name)) return communicationTools.canReadArchivedResult(name, result);
        switch (name) {
            case "phone_device_info": case "phone_apps_list": case "phone_app_launch": case "phone_url_open": case "phone_dial_open": case "phone_volume_set": return true;
            case "phone_network_state": return (result.isNull("ssid") && result.isNull("bssid")) || permissions.has(Manifest.permission.ACCESS_WIFI_STATE) && permissions.has(Manifest.permission.ACCESS_FINE_LOCATION);
            case "phone_brightness_set": return Settings.System.canWrite(context);
            case "phone_ui_snapshot": case "phone_ui_click": case "phone_ui_type": case "phone_ui_global": case "phone_ui_tap": case "phone_ui_swipe": return permissions.accessibilityAccess();
            case "phone_flashlight_set": case "phone_camera_open": return permissions.has(Manifest.permission.CAMERA);
            case "phone_files_list": case "phone_files_read": case "phone_files_write": return Build.VERSION.SDK_INT >= 30 ? android.os.Environment.isExternalStorageManager() : permissions.has(Manifest.permission.READ_EXTERNAL_STORAGE) && (!"phone_files_write".equals(name) || permissions.has(Manifest.permission.WRITE_EXTERNAL_STORAGE));
            case "phone_clipboard_read": return true;
            case "phone_screen_capture": case "phone_screen_capture_read": case "phone_screen_capture_cancel":
                return Build.VERSION.SDK_INT >= 30 && permissions.accessibilityAccess() && journal.toolAllowed("phone_screen_capture") && journal.toolAllowed("phone_screen_capture_read");
            case "phone_voice_record": case "phone_voice_record_read": case "phone_voice_record_stop":
                return permissions.has(Manifest.permission.RECORD_AUDIO) && journal.toolAllowed("phone_voice_record");
            default: if (!journal.collectionEnabled()) return false;
        }
        switch (name) {
            case "phone_notifications_read": return permissions.notificationAccess();
            case "phone_location_last": case "phone_location_refresh": case "phone_location_refresh_read": return (permissions.has(Manifest.permission.ACCESS_FINE_LOCATION) || permissions.has(Manifest.permission.ACCESS_COARSE_LOCATION)) && journal.toolAllowed("phone_location_last") && journal.toolAllowed("phone_location_refresh_read".equals(name) ? "phone_location_refresh" : name);
            case "phone_activity_read": return Build.VERSION.SDK_INT < 29 || permissions.has(Manifest.permission.ACTIVITY_RECOGNITION);
            case "phone_contacts_read": case "phone_contact_get": case "phone_contacts_create": case "phone_contact_update": return permissions.has(Manifest.permission.READ_CONTACTS) && journal.toolAllowed("phone_contacts_read") && (!"phone_contacts_create".equals(name) && !"phone_contact_update".equals(name) || permissions.has(Manifest.permission.WRITE_CONTACTS));
            case "phone_calendar_read": case "phone_calendar_create": case "phone_calendar_update": return permissions.has(Manifest.permission.READ_CALENDAR) && journal.toolAllowed("phone_calendar_read") && ("phone_calendar_read".equals(name) || permissions.has(Manifest.permission.WRITE_CALENDAR));
            case "phone_usage_read": case "phone_usage_events": return permissions.usageAccess();
            case "phone_app_state": return !result.has("inactive") && !result.has("package") || permissions.usageAccess();
            case "phone_call_state": return permissions.has(Manifest.permission.READ_PHONE_STATE);
            case "phone_bluetooth_bonded": return permissions.has(Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH);
            case "phone_media_list": {
                String kind = result.optString("kind"); String permission = "images".equals(kind) ? Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE : "video".equals(kind) ? Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE : "audio".equals(kind) ? Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE : "";
                // A selected-media grant cannot verify that every item in a historical result
                // remains in the current selection. A new bounded live query can establish it.
                return !permission.isEmpty() && permissions.has(permission);
            }
            case "phone_context_read": case "phone_context_search": {
                JSONObject event = result.optJSONObject("event"); if (event != null && !mayExpose(event)) return false;
                JSONArray events = result.optJSONArray("events"); for (int i = 0; events != null && i < events.length(); i++) { JSONObject row = events.optJSONObject(i); if (row == null || !mayExpose(row)) return false; }
                return event != null || events != null;
            }
            default: return false;
        }
    }
    boolean mayExpose(JSONObject row) {
        if(row==null)return false;
        try {
            HoyoungStore canonical=journal().canonicalStore();
            com.hoyoung.assistant.store.SourcePrivacyLedger.Snapshot scope=canonical.getPrivacyLedger().capture();
            synchronized(canonical){if(!com.hoyoung.assistant.store.SourceRecordVisibility.allowed(canonical.getReadableDatabase(),scope,"device_event",row))return false;}
        }catch(java.io.IOException|RuntimeException unavailable){return false;}
        String source = row.optString("source"), category = row.optString("category"); PhoneEventJournal journal = journal();
        if (source.equals("android.accessibility_screenshot")) return Build.VERSION.SDK_INT >= 30 && permissions.accessibilityAccess() && journal.toolAllowed("phone_screen_capture") && journal.toolAllowed("phone_screen_capture_read");
        if (source.contains("media_recorder")) return permissions.has(Manifest.permission.RECORD_AUDIO) && journal.toolAllowed("phone_voice_record") && journal.toolAllowed("phone_voice_record_read");
        if (source.equals("android.bluetooth_discovery") || source.equals("android.wifi_scan_cache") || source.equals("android.local_only_hotspot")) return radioTools.canReadStoredEvent(row);
        if (source.equals("android.alarm_receiver")) return journal.toolAllowed("phone_alarm_list");
        if (source.equals("android.shared_storage_import") || source.equals("android.media_store_import")) return fileTransferTools.canReadStoredEvent(row);
        if (source.equals("android.sms_received") || source.equals("android.sms_manager_result")) return communicationTools.canReadStoredEvent(row);
        if (!journal.collectionEnabled()) return false;
        if (source.contains("notification")) return permissions.notificationAccess() && journal.toolAllowed("phone_notifications_read");
        if (source.contains("step_sensor")) return (Build.VERSION.SDK_INT < 29 || permissions.has(Manifest.permission.ACTIVITY_RECOGNITION)) && journal.toolAllowed("phone_activity_read");
        if (source.contains("location")) return (permissions.has(Manifest.permission.ACCESS_FINE_LOCATION) || permissions.has(Manifest.permission.ACCESS_COARSE_LOCATION)) && journal.toolAllowed("phone_location_last");
        if (source.contains("contacts")) return permissions.has(Manifest.permission.READ_CONTACTS) && journal.toolAllowed("phone_contacts_read") && journal.toolAllowed("phone_contact_get");
        if (source.contains("calendar")) return permissions.has(Manifest.permission.READ_CALENDAR) && journal.toolAllowed("phone_calendar_read");
        if (source.contains("usage")) return permissions.usageAccess() && journal.toolAllowed("phone_usage_events");
        return (category.equals("collector_state") || category.equals("collector_gap") || source.equals("android.device_state")) && journal.toolAllowed("phone_app_state");
    }
    String contextSummary(String query, int maximum) {
        if (maximum == 0) return "";
        StringBuilder text = new StringBuilder("Local phone observations only; missing intervals are not inferred.\n");
        JSONArray events = new JSONArray(); java.util.Set<Long> selectedIds = new java.util.HashSet<>();
        for (String category : new String[]{"collector_gap", "collector_state", "device_state", "activity_sample", "location_sample", "notification", "provider_snapshot", "provider_change", "usage_event"}) {
            JSONArray recent = journal().recent(category, null, category.equals("collector_state") ? 3 : 5);
            for (int i = 0; i < recent.length(); i++) { JSONObject row = recent.optJSONObject(i); if (row != null && selectedIds.add(row.optLong("id"))) events.put(row); }
        }
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (int i = events.length() - 1; i >= 0; i--) {
            JSONObject event = events.optJSONObject(i); if (event == null || !mayExpose(event)) continue;
            String category = event.optString("category");
            // Assistant reply/service events remain in the explicit archive, but cannot feed back into
            // implicit external-input context as if the owner had sent/read a message.
            if (isOwnAssistantNotification(event)) continue;
            boolean relevant = category.equals("collector_gap") || category.equals("collector_state") || category.equals("device_state")
                || category.equals("activity_sample") && lower.matches("(?s).*(걸음|걷|운동|활동|step|activity|지금|상태|현재).*" )
                || category.equals("location_sample") && lower.matches("(?s).*(위치|어디|근처|이동|location|where|지금|현재).*" )
                || category.equals("notification") && lower.matches("(?s).*(알림|메시지|연락|notification|message|할 일|확인).*" )
                || event.optString("source").contains("contacts") && lower.matches("(?s).*(연락처|주소록|contact|전화번호).*" )
                || event.optString("source").contains("calendar") && lower.matches("(?s).*(일정|약속|calendar|schedule|할 일).*" )
                || category.equals("usage_event") && lower.matches("(?s).*(사용|앱|화면|usage|app|지금|현재).*" );
            if (!relevant) continue;
            String line = event.toString() + "\n";
            if (line.length() > 2500) line = PhoneEventJournal.object("id", event.optLong("id"), "category", category, "source", event.optString("source"), "observedAt", event.optLong("observed_at"), "payloadPreview", line.substring(0, 1800), "previewTruncated", true, "readFullUsing", "phone_context_read").toString() + "\n";
            if (text.length() + line.length() > maximum) { text.append("Further relevant observations omitted from this bounded context; query phone_context_read/search.\n"); break; }
            text.append(line);
        }
        return text.substring(0, Math.min(maximum, text.length()));
    }
    void cancelOutstandingLocation(String reason) { if (locationRequests != null) locationRequests.cancelAll(reason); }
    private static JSONObject missing(String id) { JSONObject result = failure("permission_missing", "Enable the required permission in the app's permission page or Android Settings."); try { result.put("permissionId", id); } catch (JSONException impossible) { } return result; }
    private static JSONObject failure(String code, String message) { return object(false, code, message); }
    private static JSONObject object(boolean ok, String code, String message) { try { return new JSONObject().put("ok", ok).put("code", code).put("message", message); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
    private static JSONObject properties(String... pairs) { JSONObject result = new JSONObject(); try { for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], new JSONObject().put("type", pairs[i + 1])); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } return result; }
    private JSONObject definition(String name) {
        JSONArray definitions = catalog();
        for (int i = 0; i < definitions.length(); i++) { JSONObject definition = definitions.optJSONObject(i); if (definition != null && definition.optString("name").equals(name)) return definition.optJSONObject("inputSchema"); }
        return null;
    }
    private static void validate(JSONObject args, JSONObject schema) throws JSONException {
        JSONObject properties = schema.getJSONObject("properties");
        JSONArray required = schema.getJSONArray("required");
        for (int i = 0; i < required.length(); i++) if (!args.has(required.getString(i)) || args.isNull(required.getString(i))) throw new IllegalArgumentException();
        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next(); if (!properties.has(key)) throw new IllegalArgumentException();
            Object value = args.get(key); String type = properties.getJSONObject(key).getString("type");
            if ("string".equals(type) && !(value instanceof String)) throw new IllegalArgumentException();
            if ("boolean".equals(type) && !(value instanceof Boolean)) throw new IllegalArgumentException();
            if ("number".equals(type) || "integer".equals(type)) {
                if (!(value instanceof Number)) throw new IllegalArgumentException();
                double number = ((Number) value).doubleValue();
                if (Double.isNaN(number) || Double.isInfinite(number) || ("integer".equals(type) && number != Math.rint(number))) throw new IllegalArgumentException();
            }
        }
    }
    private static void tool(JSONArray output, String name, String description, JSONObject properties, String... required) {
        try { JSONArray requiredList = new JSONArray(); for (String key : required) requiredList.put(key); output.put(new JSONObject().put("name", name).put("description", description).put("inputSchema", new JSONObject().put("type", "object").put("properties", properties).put("required", requiredList).put("additionalProperties", false))); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
