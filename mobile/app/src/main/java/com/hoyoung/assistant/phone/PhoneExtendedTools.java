package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Notification;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DeviceAdminInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.service.notification.StatusBarNotification;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;

/** Native operations behind the owner's tool switches. Android grants are checked at execution. */
public final class PhoneExtendedTools {
    interface ActiveNotifications {
        StatusBarNotification[] current();
        void dismiss(String key);
    }
    private final Context context;
    private final PermissionCatalog grants;
    private final ActiveNotifications testNotifications;

    public PhoneExtendedTools(Context context) { this(context, null); }
    PhoneExtendedTools(Context context, ActiveNotifications notifications) {
        this.context = context.getApplicationContext();
        grants = new PermissionCatalog(this.context);
        testNotifications = notifications;
    }

    public void appendCatalog(JSONArray output) {
        tool(output, "phone_call_place", "Request a real outgoing call through Android ACTION_CALL after CALL_PHONE grant. Android can deny background/emergency calls; acceptance never proves connection.", properties("number", "string"), "number");
        tool(output, "phone_contacts_delete", "Delete one explicit aggregate contact ID using Android Contacts provider after WRITE_CONTACTS. Includes all linked raw contacts; provider row count is reported.", properties("contactId", "integer"), "contactId");
        tool(output, "phone_calendar_delete", "Delete one explicit event ID using Android Calendar provider after WRITE_CALENDAR. Recurring event IDs may represent the complete series.", properties("eventId", "integer"), "eventId");
        tool(output, "phone_clipboard_write", "Write plain text to Android clipboard. Returns Android API acceptance, not a later successful paste; Android may auto-clear it.", properties("text", "string"), "text");
        tool(output, "phone_notifications_active", "Read current Android-visible notifications and executable actions after notification access. Returns notification key and postedAt; historical notifications are not executable.", properties("limit", "integer", "package", "string"));
        tool(output, "phone_notification_dismiss", "Request dismissal of one currently active notification matching exact key and expectedPostedAt. Android may retain ongoing notifications.", properties("key", "string", "expectedPostedAt", "integer"), "key", "expectedPostedAt");
        tool(output, "phone_notification_open", "Send the current notification's content PendingIntent after exact key/postedAt check. Android accepted dispatch is not proof of app opening or reading.", properties("key", "string", "expectedPostedAt", "integer"), "key", "expectedPostedAt");
        tool(output, "phone_notification_action", "Send one current notification action by index after exact key/postedAt check. Actions requiring inline reply use phone_notification_reply; app/Android authentication can still be required.", properties("key", "string", "expectedPostedAt", "integer", "actionIndex", "integer"), "key", "expectedPostedAt", "actionIndex");
        tool(output, "phone_notification_reply", "Send text through one current notification action's explicit free-form RemoteInput resultKey. Exact key/postedAt and actionIndex are required. PendingIntent dispatch does not prove message delivery.", properties("key", "string", "expectedPostedAt", "integer", "actionIndex", "integer", "resultKey", "string", "text", "string"), "key", "expectedPostedAt", "actionIndex", "resultKey", "text");
        tool(output, "phone_dnd_read", "Read Android's current interruption filter and whether Do Not Disturb policy access is granted. On Android15+, app-owned automatic rules may affect DND separately.", new JSONObject());
        tool(output, "phone_dnd_set", "Request Android interruption filter all/priority/none/alarms after Notification Policy access. Reports the immediately observed global filter separately; Android15 may apply an app-owned rule.", properties("filter", "string"), "filter");
        tool(output, "phone_vibrate", "Request a device vibration lasting 1–10000ms after VIBRATE grant. Reports supported hardware and accepted request, not proof that the user felt it.", properties("durationMs", "integer"), "durationMs");
        tool(output, "phone_screen_lock", "Request immediate screen lock using the activated legacy Device Admin force-lock policy. This is not unlock, root, or Device Owner access.", new JSONObject());
        tool(output, "phone_device_admin_state", "Read actual Device Admin activation and force-lock policy grant, separate Device Owner/Profile Owner status, and observed keyguard state. Does not claim root or an ability to unlock.", new JSONObject());
        tool(output, "phone_numbers_read", "Read OS-exposed active SIM phone numbers after READ_PHONE_STATE and READ_PHONE_NUMBERS. Empty or withheld numbers remain unknown; no IMEI or other device identifier is read.", new JSONObject());
    }

    public boolean supports(String name) {
        if (name == null) return false;
        switch (name) {
            case "phone_call_place": case "phone_contacts_delete": case "phone_calendar_delete":
            case "phone_clipboard_write": case "phone_notifications_active": case "phone_notification_dismiss":
            case "phone_notification_open": case "phone_notification_action": case "phone_notification_reply":
            case "phone_dnd_read": case "phone_dnd_set": case "phone_vibrate": case "phone_screen_lock":
            case "phone_numbers_read": case "phone_device_admin_state": return true;
            default: return false;
        }
    }

    /** Used after the PhoneTools schema validator and runtime's current owner-permission gate. */
    public JSONObject execute(String name, JSONObject args) {
        try {
            switch (name == null ? "" : name) {
                case "phone_call_place": return call(args);
                case "phone_contacts_delete": return deleteContact(args);
                case "phone_calendar_delete": return deleteEvent(args);
                case "phone_clipboard_write": return clipboard(args);
                case "phone_notifications_active": return activeNotifications(args);
                case "phone_notification_dismiss": case "phone_notification_open":
                case "phone_notification_action": case "phone_notification_reply": return notificationOperation(name, args);
                case "phone_dnd_read": return dndRead();
                case "phone_dnd_set": return dndSet(args);
                case "phone_vibrate": return vibrate(args);
                case "phone_screen_lock": return lock();
                case "phone_numbers_read": return numbers();
                case "phone_device_admin_state": return adminState();
                default: return failure("unsupported", "No such extended native phone tool is installed.");
            }
        } catch (PendingIntent.CanceledException canceled) {
            return failure("target_unavailable", "The source app canceled this notification operation before dispatch.");
        } catch (SecurityException denied) {
            return failure("permission_missing", "Android denied this operation under the current grant, role, or device policy.");
        } catch (JSONException | IllegalArgumentException invalid) {
            return failure("invalid_arguments", "Arguments are missing, invalid, or outside the supported range.");
        } catch (Exception unavailable) {
            // The runtime preserves this receipt. No remote completion or automatic replay is inferred.
            return failure("operation_failed", "Android could not establish this operation's result; no completion is assumed.");
        }
    }

    public boolean canReadArchivedResult(String name, JSONObject result) {
        switch (name == null ? "" : name) {
            case "phone_call_place": return grants.has(Manifest.permission.CALL_PHONE);
            case "phone_contacts_delete": return grants.has(Manifest.permission.WRITE_CONTACTS);
            case "phone_calendar_delete": return grants.has(Manifest.permission.WRITE_CALENDAR);
            case "phone_notifications_active": case "phone_notification_dismiss": case "phone_notification_open":
            case "phone_notification_action": case "phone_notification_reply": return grants.notificationAccess();
            case "phone_dnd_set": return notificationManager() != null && notificationManager().isNotificationPolicyAccessGranted();
            case "phone_numbers_read": return grants.has(Manifest.permission.READ_PHONE_STATE) && grants.has(Manifest.permission.READ_PHONE_NUMBERS);
            case "phone_screen_lock": return adminActive();
            case "phone_vibrate": return grants.has(Manifest.permission.VIBRATE);
            case "phone_dnd_read": case "phone_clipboard_write": case "phone_device_admin_state": return true;
            default: return false;
        }
    }

    private JSONObject call(JSONObject args) throws Exception {
        String number = text(args, "number", 80, false);
        if (!number.matches("[+0-9 ()*#,;.-]{1,80}") || !number.matches(".*[0-9].*")) throw new IllegalArgumentException();
        if (!grants.has(Manifest.permission.CALL_PHONE)) return missing("calls");
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return failure("unsupported", "This device does not expose telephony hardware.");
        Intent intent = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) == null) return failure("unsupported", "No installed handler can place this call.");
        context.startActivity(intent);
        return accepted("call_requested").put("callRequested", true).put("callConnected", false).put("callPlacedVerified", false);
    }

    private JSONObject deleteContact(JSONObject args) throws Exception {
        long id = positiveLong(args, "contactId");
        if (!grants.has(Manifest.permission.WRITE_CONTACTS)) return missing("contacts");
        Uri uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id);
        int count = context.getContentResolver().delete(uri, null, null);
        return deletion("contactId", id, count, "android.contacts_provider").put("scope", "selected_aggregate_contact_and_linked_raw_contacts");
    }

    private JSONObject deleteEvent(JSONObject args) throws Exception {
        long id = positiveLong(args, "eventId");
        if (!grants.has(Manifest.permission.WRITE_CALENDAR)) return missing("calendar");
        int count = context.getContentResolver().delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null);
        return deletion("eventId", id, count, "android.calendar_provider").put("recurringSeriesMayBeAffected", true);
    }

    private JSONObject clipboard(JSONObject args) throws Exception {
        String value = text(args, "text", 100000, true);
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return failure("unsupported", "Android clipboard service is unavailable.");
        manager.setPrimaryClip(ClipData.newPlainText("호영", value));
        return accepted("clipboard_write_accepted").put("characters", value.length()).put("textEchoed", false).put("pasteConfirmed", false);
    }

    private JSONObject activeNotifications(JSONObject args) throws Exception {
        if (!grants.notificationAccess()) return missing("notification_access");
        int limit = args.has("limit") ? (int) boundedLong(args, "limit", 1, 100) : 50;
        String packageName = args.has("package") ? text(args, "package", 200, true) : "";
        ActiveNotifications accessor = notifications();
        if (accessor == null) return failure("service_unavailable", "Android notification access is enabled but its listener is not connected.");
        StatusBarNotification[] all = accessor.current();
        if (all == null) return failure("service_unavailable", "Android did not return the active notification snapshot.");
        JSONArray rows = new JSONArray(); boolean truncated = false;
        for (StatusBarNotification sbn : all) {
            if (sbn == null || !packageName.isEmpty() && !packageName.equals(sbn.getPackageName())) continue;
            if (rows.length() >= limit) { truncated = true; break; }
            Notification n = sbn.getNotification();
            Bundle extras = n == null || n.extras == null ? Bundle.EMPTY : n.extras;
            JSONArray actions = new JSONArray();
            if (n != null && n.actions != null) for (int i = 0; i < n.actions.length && i < 20; i++) {
                Notification.Action action = n.actions[i]; if (action == null) continue;
                JSONArray inputs = new JSONArray(); RemoteInput[] remoteInputs = action.getRemoteInputs();
                if (remoteInputs != null) for (RemoteInput input : remoteInputs) if (input != null) {
                    inputs.put(new JSONObject().put("resultKey", input.getResultKey()).put("label", bounded(input.getLabel(), 300)).put("allowFreeFormInput", input.getAllowFreeFormInput()));
                }
                JSONObject item = new JSONObject().put("actionIndex", i).put("title", bounded(action.title, 300)).put("dispatchAvailable", action.actionIntent != null).put("remoteInputs", inputs);
                if (Build.VERSION.SDK_INT >= 31) item.put("authenticationRequired", action.isAuthenticationRequired());
                actions.put(item);
            }
            rows.put(new JSONObject().put("key", sbn.getKey()).put("package", sbn.getPackageName()).put("postedAt", sbn.getPostTime())
                .put("title", bounded(extras.getCharSequence(Notification.EXTRA_TITLE), 1000)).put("text", bounded(extras.getCharSequence(Notification.EXTRA_TEXT), 6000))
                .put("ongoing", sbn.isOngoing()).put("clearable", sbn.isClearable()).put("contentIntentAvailable", n != null && n.contentIntent != null)
                .put("actions", actions).put("actionsTruncated", n != null && n.actions != null && n.actions.length > 20));
        }
        return success().put("notifications", rows).put("truncated", truncated).put("historical", false).put("observedAt", System.currentTimeMillis())
            .put("source", "android.active_notification_snapshot").put("contentsMayBeRedactedByAndroid", true);
    }

    private JSONObject notificationOperation(String name, JSONObject args) throws Exception {
        String key = text(args, "key", 1024, false); long expected = boundedLong(args, "expectedPostedAt", 0, Long.MAX_VALUE);
        if (!grants.notificationAccess()) return missing("notification_access");
        ActiveNotifications accessor = notifications();
        if (accessor == null) return failure("service_unavailable", "Android's notification listener is not connected.");
        StatusBarNotification target = null; StatusBarNotification[] active = accessor.current();
        if (active == null) return failure("service_unavailable", "Android could not supply current notification state.");
        for (StatusBarNotification sbn : active) if (sbn != null && key.equals(sbn.getKey())) { target = sbn; break; }
        if (target == null) return failure("target_not_found", "This notification is no longer active.");
        if (target.getPostTime() != expected) return failure("target_changed", "This notification changed after it was observed. Read its current actions before dispatching.");
        if ("phone_notification_dismiss".equals(name)) {
            accessor.dismiss(key);
            return accepted("notification_dismiss_requested").put("key", key).put("dismissalConfirmed", false).put("ongoing", target.isOngoing());
        }
        Notification notification = target.getNotification();
        if (notification == null) return failure("target_unavailable", "Android did not expose this notification's content.");
        PendingIntent intent; Intent fillIn = null; int index = -1;
        if ("phone_notification_open".equals(name)) intent = notification.contentIntent;
        else {
            index = (int) boundedLong(args, "actionIndex", 0, 1000);
            Notification.Action[] actions = notification.actions;
            if (actions == null || index >= actions.length || actions[index] == null) return failure("target_not_found", "No such current notification action exists.");
            Notification.Action action = actions[index]; intent = action.actionIntent;
            if ("phone_notification_reply".equals(name)) {
                String resultKey = text(args, "resultKey", 1000, false), reply = text(args, "text", 20000, false);
                RemoteInput selected = null; RemoteInput[] inputs = action.getRemoteInputs();
                if (inputs != null) for (RemoteInput input : inputs) if (input != null && resultKey.equals(input.getResultKey()) && input.getAllowFreeFormInput()) { selected = input; break; }
                if (selected == null) return failure("unsupported", "This current action has no matching free-form text reply field.");
                fillIn = new Intent(); Bundle results = new Bundle(); results.putCharSequence(resultKey, reply);
                RemoteInput.addResultsToIntent(new RemoteInput[]{selected}, fillIn, results);
                if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT);
            } else if (action.getRemoteInputs() != null && action.getRemoteInputs().length > 0) {
                return failure("invalid_arguments", "This notification action needs reply inputs. Use phone_notification_reply with the exposed resultKey.");
            }
        }
        if (intent == null) return failure("unsupported", "The source app did not expose an executable PendingIntent for this operation.");
        intent.send(context, 0, fillIn);
        JSONObject result = accepted("notification_dispatch_accepted").put("key", key).put("actionIndex", index).put("dispatchAccepted", true).put("messageDelivered", false).put("appOpenedVerified", false);
        if ("phone_notification_reply".equals(name)) result.put("replyInputAttached", true).put("textEchoed", false);
        return result;
    }

    private ActiveNotifications notifications() {
        if (testNotifications != null) return testNotifications;
        HoyoungNotificationListener listener = HoyoungNotificationListener.connectedInstance();
        if (listener == null) return null;
        return new ActiveNotifications() {
            public StatusBarNotification[] current() { return listener.getActiveNotifications(); }
            public void dismiss(String key) { listener.cancelNotification(key); }
        };
    }

    private NotificationManager notificationManager() { return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE); }
    private JSONObject dndRead() throws Exception {
        NotificationManager manager = notificationManager();
        if (manager == null) return failure("unsupported", "Android notification service is unavailable.");
        int current = manager.getCurrentInterruptionFilter();
        return success().put("filter", filterName(current)).put("filterCode", current).put("policyAccess", manager.isNotificationPolicyAccessGranted()).put("observedAt", System.currentTimeMillis());
    }
    private JSONObject dndSet(JSONObject args) throws Exception {
        String name = text(args, "filter", 20, false); int filter;
        switch (name) {
            case "all": filter = NotificationManager.INTERRUPTION_FILTER_ALL; break;
            case "priority": filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY; break;
            case "none": filter = NotificationManager.INTERRUPTION_FILTER_NONE; break;
            case "alarms": filter = NotificationManager.INTERRUPTION_FILTER_ALARMS; break;
            default: throw new IllegalArgumentException();
        }
        NotificationManager manager = notificationManager();
        if (manager == null) return failure("unsupported", "Android notification service is unavailable.");
        if (!manager.isNotificationPolicyAccessGranted()) return missing("notification_policy");
        manager.setInterruptionFilter(filter);
        int observed = manager.getCurrentInterruptionFilter();
        return accepted("dnd_change_requested").put("requestedFilter", name).put("observedGlobalFilter", filterName(observed)).put("requestedGlobalFilterObserved", observed == filter)
                .put("android15AppRuleMayApply", Build.VERSION.SDK_INT >= 35);
    }
    private static String filterName(int value) {
        switch (value) {
            case NotificationManager.INTERRUPTION_FILTER_ALL: return "all";
            case NotificationManager.INTERRUPTION_FILTER_PRIORITY: return "priority";
            case NotificationManager.INTERRUPTION_FILTER_NONE: return "none";
            case NotificationManager.INTERRUPTION_FILTER_ALARMS: return "alarms";
            default: return "unknown";
        }
    }
    private JSONObject vibrate(JSONObject args) throws Exception {
        long duration = boundedLong(args, "durationMs", 1, 10000);
        if (!grants.has(Manifest.permission.VIBRATE)) return missing("vibration");
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return failure("unsupported", "This device does not expose vibration hardware.");
        vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
        return accepted("vibration_requested").put("durationMs", duration);
    }
    private boolean adminActive() {
        DevicePolicyManager manager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return manager != null && manager.isAdminActive(PermissionCatalog.adminComponent(context));
    }
    private JSONObject lock() throws Exception {
        DevicePolicyManager manager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (!adminActive()) return missing("device_admin");
        manager.lockNow(); KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return accepted("screen_lock_requested").put("lockNowCalled", true).put("keyguardLockedObserved", keyguard == null ? JSONObject.NULL : keyguard.isKeyguardLocked()).put("unlockSupported", false);
    }
    private JSONObject adminState() throws Exception {
        DevicePolicyManager manager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (manager == null) return failure("unsupported", "Android device policy service is unavailable.");
        boolean active = manager.isAdminActive(PermissionCatalog.adminComponent(context));
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return success().put("adminActive", active).put("forceLockPolicyGranted", active && manager.hasGrantedPolicy(PermissionCatalog.adminComponent(context), DeviceAdminInfo.USES_POLICY_FORCE_LOCK))
                .put("deviceOwner", manager.isDeviceOwnerApp(context.getPackageName())).put("profileOwner", manager.isProfileOwnerApp(context.getPackageName()))
                .put("keyguardLockedObserved", keyguard == null ? JSONObject.NULL : keyguard.isKeyguardLocked()).put("rootVerified", false).put("unlockSupported", false).put("observedAt", System.currentTimeMillis());
    }
    private JSONObject numbers() throws Exception {
        if (!grants.has(Manifest.permission.READ_PHONE_STATE) || !grants.has(Manifest.permission.READ_PHONE_NUMBERS)) return missing("phone_state");
        SubscriptionManager subscriptions = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        TelephonyManager phone = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (subscriptions == null || phone == null) return failure("unsupported", "This device does not expose SIM subscription services.");
        List<SubscriptionInfo> active = subscriptions.getActiveSubscriptionInfoList(); JSONArray result = new JSONArray();
        if (active != null) for (SubscriptionInfo subscription : active) {
            if (subscription == null || result.length() >= 20) continue;
            String number = Build.VERSION.SDK_INT >= 33 ? subscriptions.getPhoneNumber(subscription.getSubscriptionId())
                : phone.createForSubscriptionId(subscription.getSubscriptionId()).getLine1Number();
            boolean available = number != null && !number.trim().isEmpty();
            result.put(new JSONObject().put("subscriptionId", subscription.getSubscriptionId()).put("simSlot", subscription.getSimSlotIndex())
                    .put("number", available ? number : JSONObject.NULL).put("numberAvailable", available));
        }
        return success().put("subscriptions", result).put("source", "android.telephony_subscriptions").put("deviceIdentifiersRead", false)
                .put("subscriptionListAvailable", active != null).put("subscriptionListTruncated", active != null && active.size() > 20).put("observedAt", System.currentTimeMillis());
    }

    private static JSONObject deletion(String key, long id, int count, String source) throws Exception {
        if (count < 0) return failure("operation_failed", "Android returned an invalid provider result.");
        return new JSONObject().put("ok", count > 0).put("code", count > 0 ? "provider_delete_applied" : "target_not_found")
                .put(key, id).put("rowsDeleted", count).put("providerConfirmed", count > 0).put("cloudSyncConfirmed", false).put("source", source);
    }
    private static JSONObject success() { return PhoneEventJournal.object("ok", true, "code", "ok"); }
    private static JSONObject accepted(String code) { return PhoneEventJournal.object("ok", true, "code", code, "requested", true, "accepted", true, "completionConfirmed", false); }
    private static JSONObject failure(String code, String message) { return PhoneEventJournal.object("ok", false, "code", code, "message", message, "completionConfirmed", false); }
    private static JSONObject missing(String id) { return PhoneEventJournal.object("ok", false, "code", "permission_missing", "permissionId", id, "completionConfirmed", false); }
    private static String text(JSONObject args, String key, int maximum, boolean emptyAllowed) throws Exception {
        Object value = args.get(key); if (!(value instanceof String)) throw new IllegalArgumentException(); String result = (String) value;
        if (result.length() > maximum || !emptyAllowed && result.trim().isEmpty()) throw new IllegalArgumentException(); return result;
    }
    private static long positiveLong(JSONObject args, String key) throws Exception { return boundedLong(args, key, 1, Long.MAX_VALUE); }
    private static long boundedLong(JSONObject args, String key, long minimum, long maximum) throws Exception {
        Object value = args.get(key); if (!(value instanceof Number)) throw new IllegalArgumentException(); Number number = (Number) value;
        double d = number.doubleValue(); if (!Double.isFinite(d) || d != Math.rint(d)) throw new IllegalArgumentException();
        if (value instanceof Double || value instanceof Float) { if (d < minimum || d >= 9223372036854775808.0) throw new IllegalArgumentException(); }
        long result;
        try { result = new java.math.BigDecimal(number.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException overflow) { throw new IllegalArgumentException("Number is not an exact supported integer.", overflow); }
        if (result < minimum || result > maximum) throw new IllegalArgumentException(); return result;
    }
    private static String bounded(CharSequence value, int maximum) { if (value == null) return ""; String text = value.toString(); return text.substring(0, Math.min(text.length(), maximum)); }
    private static JSONObject properties(String... pairs) {
        JSONObject result = new JSONObject(); try { for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], new JSONObject().put("type", pairs[i + 1])); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); } return result;
    }
    private static void tool(JSONArray output, String name, String description, JSONObject properties, String... required) {
        try { JSONArray requiredKeys = new JSONArray(); for (String key : required) requiredKeys.put(key);
            output.put(new JSONObject().put("name", name).put("description", description).put("inputSchema", new JSONObject().put("type", "object").put("properties", properties).put("required", requiredKeys).put("additionalProperties", false)));
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
