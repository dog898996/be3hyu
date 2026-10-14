package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import com.hoyoung.assistant.MainActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Native one-shot reminders. Scheduling never claims delivery or invokes the model. */
public final class PhoneAlarmTools {
    static final String ACTION = "com.hoyoung.assistant.NATIVE_REMINDER";
    private static final String INDEX = "native_alarm.index.v1";
    private static final String PREFIX = "native_alarm.receipt.";
    private static final String CHANNEL = "hoyoung_native_reminders";
    private static final Object LOCK = new Object();
    private static boolean restorePaused;
    private final Context context;
    private final java.util.function.LongSupplier wallClock;
    public PhoneAlarmTools(Context context) { this(context, System::currentTimeMillis); }
    PhoneAlarmTools(Context context, java.util.function.LongSupplier wallClock) { this.context = context.getApplicationContext(); this.wallClock = wallClock; }

    public void appendCatalog(JSONArray output) {
        add(output, "phone_alarm_schedule", "Schedule one native exact reminder at a future Unix-millisecond timestamp. Android may delay delivery; accepted scheduling is not a fired reminder. Optional local notification; no model invocation.",
                properties("atMs", "integer", "title", "string", "message", "string", "notify", "boolean"), "atMs", "title");
        add(output, "phone_alarm_list", "Read durable native-reminder receipts with bounded pagination. Reports actual receiver callbacks separately from schedule requests; restored/rebooted registrations are not assumed active.", properties("limit", "integer", "offset", "integer"));
        add(output, "phone_alarm_cancel", "Cancel one native reminder ID using Android AlarmManager and persist cancellation. Does not erase a previously observed firing.", properties("alarmId", "string"), "alarmId");
    }
    public boolean supports(String name) { return "phone_alarm_schedule".equals(name) || "phone_alarm_list".equals(name) || "phone_alarm_cancel".equals(name); }
    public boolean canReadArchivedResult(String name, JSONObject result) { return supports(name); }
    public void pauseForBackupRestore() { synchronized (LOCK) { restorePaused = true; } }
    public void afterBackupRestore() { synchronized (LOCK) { restorePaused = false; } }

    public JSONObject execute(String name, JSONObject args) {
        synchronized (LOCK) {
            try {
                if (restorePaused) return fail("restore_in_progress");
                if (!supports(name)) return fail("unsupported_tool");
                if (args == null) args = new JSONObject();
                PhoneEventJournal journal = PhoneEventJournal.forContext(context);
                journal.refreshPolicyBlocking();
                if (!journal.toolAllowed(name)) return fail("permission_denied_by_owner");
                switch (name) {
                    case "phone_alarm_schedule": return schedule(journal, args);
                    case "phone_alarm_list": return list(journal, args);
                    default: return cancel(journal, args);
                }
            } catch (IllegalArgumentException | org.json.JSONException invalid) { return fail("invalid_arguments"); }
            catch (SecurityException denied) { return fail("android_permission_denied"); }
            catch (RuntimeException unavailable) { return fail("alarm_storage_or_android_unavailable"); }
        }
    }

    private JSONObject schedule(PhoneEventJournal journal, JSONObject args) throws org.json.JSONException {
        keys(args, "atMs", "title", "message", "notify");
        long at = integer(args, "atMs", -1), now = wallClock.getAsLong();
        if (at <= now) throw new IllegalArgumentException();
        String title = text(args, "title", 240, true), message = text(args, "message", 4000, false);
        if (args.has("notify") && !(args.opt("notify") instanceof Boolean)) throw new IllegalArgumentException();
        boolean notify = args.optBoolean("notify", true);
        AlarmManager manager = alarms();
        if (manager == null) return fail("alarm_service_unavailable");
        if (!canSchedule(manager)) return fail("permission_missing").put("permissionId", "exact_alarm");
        String id = UUID.randomUUID().toString();
        JSONObject receipt = PhoneEventJournal.object("alarmId", id, "state", "scheduling", "requestedAt", now, "atMs", at,
                "bootCount", bootCount(), "title", title, "message", message, "notify", notify,
                "receiverCallbackObserved", false, "notificationRequested", false, "completionConfirmed", false,
                "source", "android.native_reminder", "modelInvoked", false);
        JSONArray index = new JSONArray(journal.get(INDEX, "[]")); index.put(id);
        if (!save(journal, id, receipt) || !putVerified(journal, INDEX, index.toString())) return fail("alarm_receipt_not_persisted");
        PendingIntent operation = operation(id, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation);
            receipt.put("state", "scheduled").put("scheduleAcceptedAt", wallClock.getAsLong());
            if (!save(journal, id, receipt)) {
                manager.cancel(operation); operation.cancel();
                return fail("alarm_receipt_not_persisted").put("scheduleRolledBack", true);
            }
            return ok("alarm_schedule_accepted").put("alarm", receipt);
        } catch (RuntimeException failure) {
            try { manager.cancel(operation); operation.cancel(); } catch (RuntimeException ignored) { }
            receipt.put("state", "schedule_failed").put("androidFailure", failure.getClass().getSimpleName()); save(journal, id, receipt);
            return fail(failure instanceof SecurityException ? "android_permission_denied" : "android_alarm_schedule_failed").put("alarm", receipt);
        }
    }

    private JSONObject list(PhoneEventJournal journal, JSONObject args) throws org.json.JSONException {
        keys(args, "limit", "offset");
        long limit = integer(args, "limit", 50), offset = integer(args, "offset", 0);
        if (limit < 1 || limit > 100 || offset < 0 || offset > Integer.MAX_VALUE) throw new IllegalArgumentException();
        JSONArray index = new JSONArray(journal.get(INDEX, "[]")), items = new JSONArray();
        for (int i = (int) offset; i < index.length() && items.length() < limit; i++) {
            String id = index.getString(i); JSONObject receipt = read(journal, id);
            if (receipt.length() == 0) { items.put(PhoneEventJournal.object("alarmId", id, "state", "receipt_unavailable")); continue; }
            String state = receipt.optString("state");
            if ("scheduled".equals(state) || "scheduling".equals(state)) {
                int recordedBoot = receipt.optInt("bootCount", -1), currentBoot = bootCount();
                boolean changedBoot = recordedBoot >= 0 && currentBoot >= 0 && recordedBoot != currentBoot;
                boolean operationExists = operation(id, PendingIntent.FLAG_NO_CREATE) != null;
                receipt.put("pendingIntentExists", operationExists).put("registrationReverified", false)
                        .put("deliveryUnknown", true).put("currentExactAlarmAccess", canSchedule(alarms()));
                if (changedBoot) receipt.put("effectiveState", "interrupted_by_reboot");
                else if (!operationExists) receipt.put("effectiveState", "registration_not_observed");
                else receipt.put("effectiveState", "registered_request_delivery_unconfirmed");
            }
            items.put(receipt);
        }
        long next = offset + items.length();
        return ok("ok").put("alarms", items).put("total", index.length()).put("nextOffset", next < index.length() ? next : JSONObject.NULL)
                .put("rebootAutoReplay", false);
    }

    private JSONObject cancel(PhoneEventJournal journal, JSONObject args) throws org.json.JSONException {
        keys(args, "alarmId"); String id = id(args); JSONObject receipt = read(journal, id);
        if (receipt.length() == 0) return fail("alarm_not_found");
        AlarmManager manager = alarms(); if (manager == null) return fail("alarm_service_unavailable");
        PendingIntent operation = operation(id, PendingIntent.FLAG_NO_CREATE);
        if (operation != null) { manager.cancel(operation); operation.cancel(); }
        boolean fired = receipt.optBoolean("receiverCallbackObserved");
        receipt.put("state", fired ? "fired_then_canceled" : "canceled").put("canceledAt", wallClock.getAsLong())
                .put("cancelAccepted", true);
        if (!save(journal, id, receipt)) return fail("alarm_cancel_receipt_not_persisted").put("androidCancelAccepted", true);
        return ok("alarm_cancel_accepted").put("alarm", receipt);
    }

    /** Called only by the explicit non-exported receiver; records receipt before any notification. */
    void delivered(String id) {
        synchronized (LOCK) {
            try {
                if (restorePaused) return;
                if (!validId(id)) return;
                PhoneEventJournal journal = PhoneEventJournal.forContext(context); journal.refreshPolicyBlocking(); JSONObject receipt = read(journal, id);
                String state = receipt.optString("state");
                if (!"scheduled".equals(state) && !"scheduling".equals(state)) return;
                if (receipt.optLong("atMs", Long.MAX_VALUE) > wallClock.getAsLong()) return;
                receipt.put("state", "fired").put("firedAt", wallClock.getAsLong()).put("receiverCallbackObserved", true)
                        .put("completionConfirmed", true).put("completionMeaning", "native_receiver_callback_only").put("modelInvoked", false)
                        .put("notificationOutcome", "not_attempted");
                // An acknowledged callback is never replayed after process death, including the
                // small window between posting a notification and saving its outcome.
                if (!save(journal, id, receipt)) return;
                if (!receipt.optBoolean("notify") || !journal.toolAllowed("phone_alarm_schedule")) {
                    receipt.put("notificationOutcome", "disabled_by_request_or_current_owner_policy");
                } else {
                    NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    boolean allowed = notifications != null && notifications.areNotificationsEnabled()
                            && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
                    if (!allowed) receipt.put("notificationOutcome", "android_notification_access_missing");
                    else try {
                        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "호영 리마인더", NotificationManager.IMPORTANCE_DEFAULT));
                        Intent open = new Intent(context, MainActivity.class).setData(Uri.parse("hoyoung-reminder://open/" + id));
                        PendingIntent target = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                        Notification notification = new Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                                .setContentTitle(receipt.optString("title")).setContentText(receipt.optString("message"))
                                .setStyle(new Notification.BigTextStyle().bigText(receipt.optString("message")))
                                .setContentIntent(target).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
                        notifications.notify("hoyoung-reminder:" + id, 0, notification);
                        receipt.put("notificationRequested", true).put("notificationOutcome", "android_notify_accepted").put("userSawNotification", false);
                    } catch (RuntimeException denied) { receipt.put("notificationOutcome", "android_notification_failed"); }
                }
                save(journal, id, receipt);
                journal.append("native_reminder", "android.alarm_receiver", receipt, receipt.getLong("firedAt"), "native-alarm-fired:" + id);
            } catch (RuntimeException | org.json.JSONException unavailable) { /* No invented delivery if local storage is unavailable. */ }
        }
    }

    private PendingIntent operation(String id, int flags) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, PhoneAlarmReceiver.class).setAction(ACTION)
                .setData(Uri.parse("hoyoung-alarm://reminder/" + id)), flags | PendingIntent.FLAG_IMMUTABLE);
    }
    private AlarmManager alarms() { return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE); }
    private boolean canSchedule(AlarmManager manager) { return manager != null && (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()); }
    private int bootCount() { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
    private static JSONObject read(PhoneEventJournal journal, String id) throws org.json.JSONException { return new JSONObject(journal.get(PREFIX + id, "{}")); }
    private static boolean save(PhoneEventJournal journal, String id, JSONObject receipt) { return putVerified(journal, PREFIX + id, receipt.toString()); }
    private static boolean putVerified(PhoneEventJournal journal, String key, String value) { journal.put(key, value); return value.equals(journal.get(key, "")); }
    private static String id(JSONObject args) { String id = text(args, "alarmId", 36, true); if (!validId(id)) throw new IllegalArgumentException(); return id; }
    private static boolean validId(String id) { return id != null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
    private static long integer(JSONObject args, String name, long fallback) {
        Object value = args.opt(name); if (value == null) return fallback;
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException(); return ((Number) value).longValue();
    }
    private static String text(JSONObject args, String name, int max, boolean required) {
        Object value = args.opt(name); if (value == null && !required) return "";
        if (!(value instanceof String) || ((String) value).length() > max || required && ((String) value).trim().isEmpty()) throw new IllegalArgumentException();
        return (String) value;
    }
    private static void keys(JSONObject args, String... allowed) {
        java.util.Set<String> names = new java.util.HashSet<>(java.util.Arrays.asList(allowed));
        java.util.Iterator<String> keys = args.keys(); while (keys.hasNext()) if (!names.contains(keys.next())) throw new IllegalArgumentException();
    }
    private static JSONObject ok(String code) { return PhoneEventJournal.object("ok", true, "code", code, "completionConfirmed", false, "modelInvoked", false); }
    private static JSONObject fail(String code) { return PhoneEventJournal.object("ok", false, "code", code, "completionConfirmed", false); }
    private static JSONObject properties(String... fields) {
        JSONObject result = new JSONObject(); try { for (int i = 0; i < fields.length; i += 2) result.put(fields[i], new JSONObject().put("type", fields[i + 1])); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); } return result;
    }
    private static void add(JSONArray output, String name, String description, JSONObject properties, String... required) {
        try { output.put(new JSONObject().put("name", name).put("description", description).put("inputSchema", new JSONObject().put("type", "object").put("properties", properties).put("required", new JSONArray(required)).put("additionalProperties", false))); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
