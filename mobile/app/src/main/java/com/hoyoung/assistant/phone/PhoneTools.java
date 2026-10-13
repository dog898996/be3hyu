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
    public PhoneTools(Context context) { this.context = context.getApplicationContext(); this.permissions = new PermissionCatalog(this.context); this.sharedFiles = new PhoneSharedFiles(this.context); }

    public JSONArray catalog() {
        JSONArray tools = new JSONArray();
        tool(tools, "phone_device_info", "Read non-identifier device and permission state.", new JSONObject());
        tool(tools, "phone_apps_list", "List a bounded set of installed apps visible to Android package queries.", properties("limit", "integer", "query", "string"));
        tool(tools, "phone_app_launch", "Request opening a specified installed app; Android may block background launches.", properties("package", "string"), "package");
        tool(tools, "phone_contacts_read", "Read up to 100 address-book phone entries after READ_CONTACTS grant.", properties("query", "string", "limit", "integer"));
        tool(tools, "phone_contacts_create", "Create one address-book contact after WRITE_CONTACTS grant.", properties("name", "string", "phone", "string", "email", "string"), "name");
        tool(tools, "phone_calendar_read", "Read visible calendars and up to 100 events in at most a 31-day window. Millisecond timestamps.", properties("fromMs", "integer", "toMs", "integer", "limit", "integer"));
        tool(tools, "phone_calendar_create", "Create one event in a user-selected writable calendar. Millisecond timestamps.", properties("calendarId", "integer", "title", "string", "startMs", "integer", "endMs", "integer", "description", "string", "location", "string"), "calendarId", "title", "startMs", "endMs");
        tool(tools, "phone_notifications_read", "Read the locally retained notification ring only when notification access is enabled. This requires explicit tool execution; collection does not upload content.", properties("limit", "integer", "package", "string"));
        tool(tools, "phone_location_last", "Read only a sufficiently recent last-known location. Does not start continuous tracking.", properties("maxAgeMs", "integer"));
        tool(tools, "phone_volume_set", "Set music/ring/alarm volume within that stream's actual range.", properties("stream", "string", "level", "integer"), "stream", "level");
        tool(tools, "phone_flashlight_set", "Request the back camera flashlight after CAMERA grant.", properties("enabled", "boolean"), "enabled");
        tool(tools, "phone_clipboard_read", "Read text clipboard only while this app is foreground, subject to Android restrictions.", new JSONObject());
        tool(tools, "phone_ui_snapshot", "Take an on-demand bounded visible UI tree; password nodes and editable text are omitted.", new JSONObject());
        tool(tools, "phone_ui_click", "Click a nodeId from the latest UI snapshot, or a unique exact visible text.", properties("nodeId", "string", "text", "string"));
        tool(tools, "phone_ui_global", "Request home/back/recents/notifications navigation using a user-enabled accessibility service.", properties("action", "string"), "action");
        tool(tools, "phone_ui_tap", "Request an on-screen accessibility tap. Android's accepted status is not completion.", properties("x", "number", "y", "number"), "x", "y");
        tool(tools, "phone_ui_swipe", "Request an on-screen accessibility swipe; duration 40–1500 ms.", properties("x1", "number", "y1", "number", "x2", "number", "y2", "number", "durationMs", "integer"), "x1", "y1", "x2", "y2");
        tool(tools, "phone_brightness_set", "Write screen brightness 0–255 after explicit Write System Settings grant.", properties("value", "integer"), "value");
        tool(tools, "phone_dial_open", "Open the dialer with a phone number. Does not place the call.", properties("number", "string"), "number");
        tool(tools, "phone_files_list", "List one shared-storage directory, at most 100 entries. Requires actual all-files access; excludes protected app data.", properties("path", "string", "limit", "integer"));
        tool(tools, "phone_files_read", "Read valid UTF-8 shared text, at most 50000 characters and 1 MiB file size. Relative path only.", properties("path", "string", "maxChars", "integer"), "path");
        tool(tools, "phone_files_write", "Atomically write at most 100000 text characters to a relative shared-storage path. Requires actual storage grant.", properties("path", "string", "content", "string"), "path", "content");
        return tools;
    }

    public JSONObject execute(String name, JSONObject arguments) {
        JSONObject args = arguments == null ? new JSONObject() : arguments;
        try {
            JSONObject schema = definition(name);
            if (schema == null) return failure("unsupported", "No such native phone tool is installed.");
            validate(args, schema);
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
                case "phone_ui_snapshot": case "phone_ui_click": case "phone_ui_global": case "phone_ui_tap": case "phone_ui_swipe": return accessibility(name, args);
                case "phone_brightness_set": return brightness(args);
                case "phone_dial_open": return dial(args);
                case "phone_files_list": case "phone_files_read": case "phone_files_write": return sharedFiles.execute(name, args);
                default: return failure("unsupported", "No such native phone tool is installed.");
            }
        } catch (SecurityException denied) { return failure("permission_missing", "Android denied this operation. Check the current grant, role and device policy."); }
        catch (JSONException | IllegalArgumentException invalid) { return failure("invalid_arguments", "Arguments are missing or outside the supported range."); }
        catch (Exception unavailable) { return failure("operation_failed", "Android could not complete this operation; no success is assumed."); }
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
            try { Location candidate = manager.getLastKnownLocation(provider); if (candidate != null && (best == null || candidate.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos())) best = candidate; } catch (SecurityException deniedProvider) { }
        }
        if (best == null) return failure("location_unavailable", "No last-known location is available. Continuous tracking was not started.");
        long age = (android.os.SystemClock.elapsedRealtimeNanos() - best.getElapsedRealtimeNanos()) / 1000000;
        if (age < 0 || age > maximumAge) return failure("location_stale", "The cached location exceeds the requested maximum age.").put("ageMs", age);
        return success().put("latitude", best.getLatitude()).put("longitude", best.getLongitude()).put("accuracyMeters", best.hasAccuracy() ? best.getAccuracy() : JSONObject.NULL).put("ageMs", age).put("timeMs", best.getTime()).put("precisionPermission", fine ? "fine" : "coarse");
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
        ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo(); ActivityManager.getMyMemoryState(process);
        if (process.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return failure("foreground_required", "Android limits clipboard reading to the foreground app or permitted IME.");
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData data = manager == null ? null : manager.getPrimaryClip(); if (data == null || data.getItemCount() == 0) return success().put("text", "");
        // Do not coerce arbitrary URI clipboard data into a provider read.
        CharSequence value = data.getItemAt(0).getText(); String text = value == null ? "" : value.toString();
        return success().put("text", text.length() > 8000 ? text.substring(0, 8000) : text).put("truncated", text.length() > 8000);
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
            case "phone_ui_global": return service.global(requiredString(args, "action", 30));
            case "phone_ui_tap": return service.tap((float) args.getDouble("x"), (float) args.getDouble("y"));
            default: return service.swipe((float) args.getDouble("x1"), (float) args.getDouble("y1"), (float) args.getDouble("x2"), (float) args.getDouble("y2"), args.optLong("durationMs", 300));
        }
    }
    private static int limit(JSONObject args, int fallback, int maximum) throws JSONException { int value = args.has("limit") ? args.getInt("limit") : fallback; if (value < 1 || value > maximum) throw new IllegalArgumentException(); return value; }
    private static String optionalString(JSONObject args, String key, int maximum) throws JSONException { if (!args.has(key) || args.isNull(key)) return ""; Object value = args.get(key); if (!(value instanceof String) || ((String) value).length() > maximum) throw new IllegalArgumentException(); return (String) value; }
    private static String requiredString(JSONObject args, String key, int maximum) throws JSONException { String value = optionalString(args, key, maximum); if (value.trim().isEmpty()) throw new IllegalArgumentException(); return value; }
    private static JSONObject success() { return object(true, "ok", ""); }
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
