package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.AtomicFile;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.UUID;

/** Android SMS/call-log APIs after actual installer-restricted runtime grants, without taking a role. */
public final class PhoneCommunicationTools {
    private static final String PREFIX = "communication.sms.request.";
    private static final String SENT = "com.hoyoung.assistant.SMS_SENT_RESULT";
    private static final String DELIVERED = "com.hoyoung.assistant.SMS_DELIVERED_RESULT";
    private static final Object CALLBACK_LOCK = new Object();
    private static final java.util.WeakHashMap<Context, Boolean> CALLBACK_PAUSED = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<Context, Boolean> PAUSED_SMS_OBSERVATION_ALLOWED = new java.util.WeakHashMap<>();
    interface SmsBackend {
        boolean available(); int defaultSubscription(); ArrayList<String> divide(String text, int subscription);
        void send(String destination, ArrayList<String> parts, int subscription, ArrayList<PendingIntent> sent, ArrayList<PendingIntent> delivered);
    }
    private final Context context;
    private final PermissionCatalog grants;
    private final SmsBackend sms;
    private PhoneEventJournal journal;
    private volatile boolean restorePaused;
    public PhoneCommunicationTools(Context context) { this(context, null, new AndroidSms(context.getApplicationContext())); new Handler(Looper.getMainLooper()).post(() -> drainDeferredCallbacks(this.context)); }
    PhoneCommunicationTools(Context context, PhoneEventJournal journal, SmsBackend backend) { this.context = context.getApplicationContext(); grants = new PermissionCatalog(this.context); this.journal = journal; sms = backend; }
    private PhoneEventJournal journal() { if (journal == null) journal = PhoneEventJournal.forContext(context); return journal; }
    public void appendCatalog(JSONArray output) {
        tool(output, "phone_sms_read", "Query OS-visible SMS rows after actual READ_SMS grant. Installer allowlisting may be required; default-SMS role is not assumed. Each bounded body preview has an original body hash; use phone_sms_get for the full text.", properties("address", "string", "query", "string", "fromMs", "integer", "toMs", "integer", "limit", "integer"));
        tool(output, "phone_sms_get", "Read one OS SMS ID with a hash-verified paged body (offsetChars/maxChars). Returns original full length/hash and whether more text remains; does not fabricate deleted provider rows.", properties("smsId", "integer", "offsetChars", "integer", "maxChars", "integer"), "smsId");
        tool(output, "phone_sms_send", "Send a real SMS through Android SmsManager after actual SEND_SMS grant, using selected/default SIM. Normal carrier charges can apply. Returns requestId immediately; sent and delivery callbacks remain separate, and pending/unknown sends are never replayed.", properties("number", "string", "text", "string", "subscriptionId", "integer"), "number", "text");
        tool(output, "phone_sms_send_read", "Read this app's durable outbound SMS receipt/body and exact per-part sent/delivery results. A sent callback does not prove delivery; only parsed successful delivery reports confirm it. Unresolved callbacks remain pending/unknown after restart.", properties("requestId", "string"), "requestId");
        tool(output, "phone_call_log_read", "Read up to 100 OS call-log rows after actual READ_CALL_LOG grant. Installer restrictions still apply; only provider-observed numbers/type/time/duration are reported.", properties("number", "string", "fromMs", "integer", "toMs", "integer", "limit", "integer"));
        tool(output, "phone_call_log_delete", "Delete one explicit call-log ID after actual WRITE_CALL_LOG grant. Reports provider row count; does not delete other rows or claim cloud synchronization.", properties("callId", "integer"), "callId");
    }
    public boolean supports(String name) { return "phone_sms_read".equals(name) || "phone_sms_get".equals(name) || "phone_sms_send".equals(name) || "phone_sms_send_read".equals(name) || "phone_call_log_read".equals(name) || "phone_call_log_delete".equals(name); }
    public JSONObject execute(String name, JSONObject supplied) {
        JSONObject args = supplied == null ? new JSONObject() : supplied;
        try {
            if (restorePaused) return failure("restore_in_progress"); journal().refreshPolicyBlocking();
            if (!journal().toolAllowed(name)) return failure("permission_disabled"); drainDeferredCallbacks(context);
            switch (name == null ? "" : name) {
                case "phone_sms_read": return smsRows(args);
                case "phone_sms_get": return smsGet(args);
                case "phone_sms_send": return sendSms(args);
                case "phone_sms_send_read": return readSend(args);
                case "phone_call_log_read": return callRows(args);
                case "phone_call_log_delete": return deleteCall(args);
                default: return failure("unsupported");
            }
        } catch (SecurityException denied) { return failure("permission_missing"); }
        catch (JSONException | IllegalArgumentException invalid) { return failure("invalid_arguments"); }
        catch (RuntimeException unavailable) { return failure("operation_failed"); }
    }
    public boolean canReadArchivedResult(String name, JSONObject result) {
        if ("phone_sms_read".equals(name) || "phone_sms_get".equals(name)) return grants.has(Manifest.permission.READ_SMS);
        if ("phone_sms_send".equals(name)) return grants.has(Manifest.permission.SEND_SMS);
        if ("phone_sms_send_read".equals(name)) return true; // The app's own outcome receipt uses no protected provider API.
        if ("phone_call_log_read".equals(name)) return grants.has(Manifest.permission.READ_CALL_LOG);
        if ("phone_call_log_delete".equals(name)) return grants.has(Manifest.permission.WRITE_CALL_LOG);
        return false;
    }
    public boolean canReadStoredEvent(JSONObject row) {
        if (row == null) return false; String source = row.optString("source");
        if ("android.sms_received".equals(source)) return grants.has(Manifest.permission.READ_SMS) && grants.has(Manifest.permission.RECEIVE_SMS) && journal().toolAllowed("phone_sms_read");
        return "android.sms_manager_result".equals(source) && journal().toolAllowed("phone_sms_send_read");
    }
    private JSONObject smsRows(JSONObject args) throws JSONException {
        rejectUnknown(args, "address", "query", "fromMs", "toMs", "limit"); if (!grants.has(Manifest.permission.READ_SMS)) return missing("sms_read");
        int limit = integer(args, "limit", 50, 1, 100); Selection selection = dates(args, Telephony.Sms.DATE);
        if (args.has("address")) selection.add(Telephony.Sms.ADDRESS + "=?", text(args, "address", 200, false));
        if (args.has("query")) selection.add(Telephony.Sms.BODY + " LIKE ? ESCAPE '\\'", "%" + escapeLike(text(args, "query", 500, false)) + "%");
        String[] projection = {Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.STATUS, Telephony.Sms.THREAD_ID, Telephony.Sms.SUBSCRIPTION_ID};
        JSONArray rows = new JSONArray(); boolean truncated = false;
        try (Cursor cursor = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, projection, selection.sql(), selection.args(), Telephony.Sms.DATE + " DESC")) {
            if (cursor == null) return failure("provider_unavailable");
            while (cursor.moveToNext()) { if (rows.length() >= limit) { truncated = true; break; } rows.put(smsRow(cursor, 0, 5000)); }
        }
        return success().put("messages", rows).put("truncated", truncated).put("source", "android.sms_provider").put("observedAt", System.currentTimeMillis()).put("ownerReadConfirmed", false);
    }
    private JSONObject smsGet(JSONObject args) throws JSONException {
        rejectUnknown(args, "smsId", "offsetChars", "maxChars"); if (!grants.has(Manifest.permission.READ_SMS)) return missing("sms_read");
        long id = positiveLong(args, "smsId"); int offset = integer(args, "offsetChars", 0, 0, Integer.MAX_VALUE), maximum = integer(args, "maxChars", 20000, 1, 50000);
        String[] projection = {Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.STATUS, Telephony.Sms.THREAD_ID, Telephony.Sms.SUBSCRIPTION_ID};
        try (Cursor cursor = context.getContentResolver().query(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), projection, null, null, null)) {
            if (cursor == null) return failure("provider_unavailable"); if (!cursor.moveToFirst()) return failure("target_not_found");
            return success().put("message", smsRow(cursor, offset, maximum)).put("source", "android.sms_provider").put("observedAt", System.currentTimeMillis());
        }
    }
    private static JSONObject smsRow(Cursor cursor, int offset, int maximum) throws JSONException {
        String body = cursor.getString(2); if (body == null) body = ""; if (offset > body.length()) throw new IllegalArgumentException();
        if (offset > 0 && offset < body.length() && Character.isLowSurrogate(body.charAt(offset)) && Character.isHighSurrogate(body.charAt(offset - 1))) throw new IllegalArgumentException();
        int end = (int) Math.min(body.length(), (long) offset + maximum);
        if (end > offset && end < body.length() && Character.isHighSurrogate(body.charAt(end - 1)) && Character.isLowSurrogate(body.charAt(end))) { if (end - 1 == offset) end++; else end--; }
        return PhoneEventJournal.object("smsId", cursor.getLong(0), "address", cursor.getString(1), "body", body.substring(offset, end), "dateMs", cursor.getLong(3), "type", cursor.getInt(4),
                "providerReadFlag", cursor.getInt(5), "status", cursor.getInt(6), "threadId", cursor.getLong(7), "subscriptionId", cursor.getInt(8),
                "offsetChars", offset, "nextOffsetChars", end, "bodyOriginalCharacters", body.length(), "bodyOriginalSha256", PhoneEventJournal.hash(body), "bodyTruncated", end < body.length(), "ownerReadConfirmed", false);
    }
    private JSONObject callRows(JSONObject args) throws JSONException {
        rejectUnknown(args, "number", "fromMs", "toMs", "limit"); if (!grants.has(Manifest.permission.READ_CALL_LOG)) return missing("call_log_read");
        int limit = integer(args, "limit", 50, 1, 100); Selection selection = dates(args, CallLog.Calls.DATE);
        if (args.has("number")) selection.add(CallLog.Calls.NUMBER + "=?", text(args, "number", 200, false));
        JSONArray rows = new JSONArray(); boolean truncated = false;
        String[] projection = {CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION};
        try (Cursor cursor = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, projection, selection.sql(), selection.args(), CallLog.Calls.DATE + " DESC")) {
            if (cursor == null) return failure("provider_unavailable");
            while (cursor.moveToNext()) { if (rows.length() >= limit) { truncated = true; break; } rows.put(PhoneEventJournal.object("callId", cursor.getLong(0), "number", cursor.getString(1), "cachedName", cursor.getString(2), "type", cursor.getInt(3), "dateMs", cursor.getLong(4), "durationSeconds", cursor.getLong(5))); }
        }
        return success().put("calls", rows).put("truncated", truncated).put("source", "android.call_log_provider").put("observedAt", System.currentTimeMillis());
    }
    private JSONObject deleteCall(JSONObject args) throws JSONException {
        rejectUnknown(args, "callId"); if (!grants.has(Manifest.permission.WRITE_CALL_LOG)) return missing("call_log_write"); long id = positiveLong(args, "callId");
        int count = context.getContentResolver().delete(ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, id), null, null);
        return PhoneEventJournal.object("ok", count > 0, "code", count > 0 ? "provider_delete_applied" : "target_not_found", "callId", id, "rowsDeleted", count, "providerConfirmed", count > 0, "cloudSyncConfirmed", false);
    }

    private JSONObject sendSms(JSONObject args) throws JSONException {
        rejectUnknown(args, "number", "text", "subscriptionId"); String destination = text(args, "number", 80, false), body = text(args, "text", 20000, false);
        if (!destination.matches("[+0-9 ()-]{1,80}") || !destination.matches(".*[0-9].*")) throw new IllegalArgumentException();
        if (!grants.has(Manifest.permission.SEND_SMS)) return missing("sms_send"); if (!sms.available()) return failure("sms_unavailable");
        int subscription = args.has("subscriptionId") ? integer(args, "subscriptionId", -1, 0, Integer.MAX_VALUE) : sms.defaultSubscription();
        if (subscription < 0) return failure("sms_subscription_unavailable"); ArrayList<String> parts = sms.divide(body, subscription);
        if (parts == null || parts.isEmpty() || parts.size() > 256) return failure("sms_parts_unsupported");
        for (String part : parts) if (part == null) return failure("sms_parts_unsupported");
        String submitted = String.join("", parts);
        String id = UUID.randomUUID().toString(), token = UUID.randomUUID().toString(); JSONArray statuses = new JSONArray();
        ArrayList<PendingIntent> sent = new ArrayList<>(), delivered = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) { statuses.put(PhoneEventJournal.object("partIndex", i, "sentCode", JSONObject.NULL, "deliveryCode", JSONObject.NULL, "statusReportParsed", false, "deliveryStatus", JSONObject.NULL)); sent.add(callback(id, token, i, SENT)); delivered.add(callback(id, token, i, DELIVERED)); }
        JSONObject receipt = PhoneEventJournal.object("requestId", id, "callbackToken", token, "number", destination, "text", submitted, "textSha256", PhoneEventJournal.hash(submitted), "requestedTextSha256", PhoneEventJournal.hash(body),
                "androidSegmentationChangedText", !submitted.equals(body), "recipientDecodedTextVerified", false, "subscriptionId", subscription,
                "state", "dispatch_pending", "startedAt", System.currentTimeMillis(), "parts", statuses, "sentConfirmed", false, "deliveryConfirmed", false, "completionConfirmed", false,
                "automaticReplay", false, "source", "android.sms_manager_result", "sourceKind", "outbound_sms_result", "isOwnerInstruction", false,
                "assistantGenerated", true, "externalInputTriggerEligible", false, "ownerReadConfirmed", false);
        if (!submitted.equals(body)) receipt.put("requestedText", body);
        if (!save(id, receipt)) return failure("receipt_storage_unavailable");
        try { sms.send(destination, parts, subscription, sent, delivered); }
        catch (SecurityException denied) { receipt.put("state", "permission_denied_before_dispatch"); save(id, receipt); return missing("sms_send").put("requestId", id); }
        catch (RuntimeException uncertain) { receipt.put("state", "dispatch_outcome_unknown"); save(id, receipt); return failure("operation_failed").put("requestId", id).put("automaticReplay", false); }
        synchronized (CALLBACK_LOCK) {
            JSONObject current = load(id); if (current != null && "dispatch_pending".equals(current.optString("state"))) { current.put("state", "awaiting_sent_callbacks"); save(id, current); }
        }
        return success().put("code", "sms_dispatch_requested").put("requestId", id).put("parts", parts.size()).put("requested", true).put("dispatchAccepted", true)
                .put("sentConfirmed", false).put("deliveryConfirmed", false).put("completionConfirmed", false).put("automaticReplay", false);
    }
    private PendingIntent callback(String id, String token, int index, String action) {
        Intent intent = new Intent(context, SmsResultReceiver.class).setAction(action).setData(Uri.parse("hoyoung-sms://callback/" + id + "/" + index + "/" + (SENT.equals(action) ? "sent" : "delivered")))
                .putExtra("requestId", id).putExtra("callbackToken", token).putExtra("partIndex", index);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
    }
    private JSONObject readSend(JSONObject args) throws JSONException {
        rejectUnknown(args, "requestId"); String id = requestId(args); JSONObject receipt = load(id); if (receipt == null) return failure("target_not_found");
        // This private receipt remains readable after SEND_SMS revocation. No provider read is used.
        JSONObject visible = new JSONObject(receipt.toString()); visible.remove("callbackToken");
        return success().put("request", visible).put("completionConfirmed", visible.optBoolean("deliveryConfirmed")).put("automaticReplay", false);
    }
    private boolean save(String id, JSONObject receipt) { String value = receipt.toString(); journal().put(PREFIX + id, value); return value.equals(journal().get(PREFIX + id, "")); }
    private JSONObject load(String id) { try { JSONObject value = new JSONObject(journal().get(PREFIX + id, "{}")); return id.equals(value.optString("requestId")) ? value : null; } catch (JSONException invalid) { return null; } }
    public void pauseForBackupRestore() { synchronized (CALLBACK_LOCK) {
        PAUSED_SMS_OBSERVATION_ALLOWED.put(context, grants.has(Manifest.permission.RECEIVE_SMS) && journal().collectionEnabled() && journal().toolAllowed("phone_sms_read"));
        restorePaused = true; CALLBACK_PAUSED.put(context, true);
    } }
    public void afterBackupRestore() { synchronized (CALLBACK_LOCK) { restorePaused = false; CALLBACK_PAUSED.remove(context); PAUSED_SMS_OBSERVATION_ALLOWED.remove(context); } drainDeferredCallbacks(context); }

    static void applySmsResult(Context context, Intent intent, int resultCode, Integer deliveryStatus, boolean parsed) {
        synchronized (CALLBACK_LOCK) {
            try {
                String action = intent.getAction(); if (!SENT.equals(action) && !DELIVERED.equals(action)) return;
                String id = intent.getStringExtra("requestId"), token = intent.getStringExtra("callbackToken"); int index = intent.getIntExtra("partIndex", -1);
                if (id == null || !id.matches("[0-9a-f-]{36}") || token == null || !token.matches("[0-9a-f-]{36}") || index < 0) return;
                PhoneEventJournal journal = PhoneEventJournal.forContext(context); JSONObject receipt = new JSONObject(journal.get(PREFIX + id, "{}"));
                if (!id.equals(receipt.optString("requestId")) || !token.equals(receipt.optString("callbackToken"))) return;
                JSONArray parts = receipt.optJSONArray("parts"); if (parts == null || index >= parts.length()) return; JSONObject part = parts.optJSONObject(index); if (part == null) return;
                String field = SENT.equals(action) ? "sentCode" : "deliveryCode";
                if (!part.isNull(field)) return; // Duplicate callback does not create a second send or outcome.
                part.put(field, resultCode).put(SENT.equals(action) ? "sentCallbackAt" : "deliveryCallbackAt", System.currentTimeMillis());
                if (DELIVERED.equals(action)) part.put("statusReportParsed", parsed).put("deliveryStatus", deliveryStatus == null ? JSONObject.NULL : deliveryStatus);
                boolean allSent = true, anySendFailure = false, allDelivered = true;
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject item = parts.getJSONObject(i); boolean sent = !item.isNull("sentCode") && item.optInt("sentCode") == Activity.RESULT_OK;
                    allSent &= sent; anySendFailure |= !item.isNull("sentCode") && item.optInt("sentCode") != Activity.RESULT_OK;
                    allDelivered &= !item.isNull("deliveryCode") && item.optInt("deliveryCode") == Activity.RESULT_OK && item.optBoolean("statusReportParsed") && !item.isNull("deliveryStatus") && item.optInt("deliveryStatus") == 0;
                }
                receipt.put("sentCallbacksConfirmed", allSent).put("sentConfirmed", allSent || allDelivered).put("deliveryConfirmed", allDelivered).put("completionConfirmed", allDelivered)
                        .put("state", allDelivered ? "delivery_confirmed" : anySendFailure ? "sent_failed" : allSent ? "sent_confirmed_delivery_unconfirmed" : "awaiting_sent_callbacks").put("lastCallbackAt", System.currentTimeMillis());
                journal.put(PREFIX + id, receipt.toString());
                if (journal.collectionEnabled()) {
                    JSONObject observation = new JSONObject(receipt.toString()); observation.remove("callbackToken");
                    journal.append("communication_result", "android.sms_manager_result", observation, System.currentTimeMillis(), "sms-result:" + id + ":" + index + ":" + field);
                }
            } catch (JSONException | RuntimeException unavailable) { /* Pending/unknown receipt is retained; no success or automatic replay is fabricated. */ }
        }
    }

    public static final class SmsResultReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return; int result = getResultCode(); Integer status = null; boolean parsed = false;
            if (DELIVERED.equals(intent.getAction())) {
                try { byte[] pdu = intent.getByteArrayExtra("pdu"); String format = intent.getStringExtra("format"); SmsMessage report = pdu == null ? null : SmsMessage.createFromPdu(pdu, format == null ? "3gpp" : format);
                    if (report != null && report.isStatusReportMessage()) { status = report.getStatus(); parsed = true; }
                } catch (RuntimeException invalid) { }
            }
            dispatchCallback(context.getApplicationContext(), new Intent(intent), result, status, parsed);
        }
    }
    public static final class SmsObservedReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
            Context app = context.getApplicationContext();
            synchronized (CALLBACK_LOCK) {
                if (Boolean.TRUE.equals(CALLBACK_PAUSED.get(app))) {
                    if (Boolean.TRUE.equals(PAUSED_SMS_OBSERVATION_ALLOWED.get(app)) && new PermissionCatalog(app).has(Manifest.permission.RECEIVE_SMS)) deferIncoming(app, intent);
                    return;
                }
            }
            observeIncoming(app, intent);
        }
    }
    private static void observeIncoming(Context context, Intent intent) {
            try {
                Context app = context.getApplicationContext(); PermissionCatalog grants = new PermissionCatalog(app);
                if (!grants.has(Manifest.permission.RECEIVE_SMS)) return;
                PhoneEventJournal journal = PhoneEventJournal.forContext(app); if (!journal.collectionEnabled() || !journal.toolAllowed("phone_sms_read")) return;
                SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent); if (messages == null || messages.length == 0) return;
                JSONArray parts = new JSONArray(); StringBuilder body = new StringBuilder(); String sender = null; long timestamp = 0;
                for (SmsMessage message : messages) {
                    if (message == null) continue; String text = message.getMessageBody(); if (text != null) body.append(text);
                    if (sender == null) sender = message.getOriginatingAddress(); if (timestamp == 0) timestamp = message.getTimestampMillis();
                    parts.put(PhoneEventJournal.object("sender", message.getOriginatingAddress(), "body", text == null ? "" : text, "timestampMs", message.getTimestampMillis()));
                }
                JSONObject observed = PhoneEventJournal.object("sender", sender == null ? JSONObject.NULL : sender, "body", body.toString(), "parts", parts, "sourceKind", "external_sms", "isOwnerInstruction", false,
                        "ownerReadConfirmed", false, "observedOnly", true, "originalSha256", PhoneEventJournal.hash(body.toString()), "subscriptionId", intent.getIntExtra("subscription", -1));
                journal.append("communication_received", "android.sms_received", observed, Math.max(0, timestamp), "sms-received:" + PhoneEventJournal.hash(sender + ":" + timestamp + ":" + body + ":" + intent.getIntExtra("subscription", -1)));
            } catch (RuntimeException unavailable) { /* SMS provider remains the separately permission-checked recovery source. */ }
    }
    private static void deferIncoming(Context context, Intent intent) {
        try { Object[] raw = (Object[]) intent.getSerializableExtra("pdus"); if (raw == null || raw.length > 256) return; JSONArray pdus = new JSONArray();
            for (Object part : raw) { if (!(part instanceof byte[])) return; pdus.put(android.util.Base64.encodeToString((byte[]) part, android.util.Base64.NO_WRAP)); }
            enqueue(context, PhoneEventJournal.object("kind", "incoming_sms", "pdus", pdus, "format", intent.getStringExtra("format"), "subscription", intent.getIntExtra("subscription", -1)));
        } catch (Exception unavailable) { }
    }
    private static void dispatchCallback(Context context, Intent intent, int result, Integer status, boolean parsed) {
        synchronized (CALLBACK_LOCK) { if (startupReady(context) && !Boolean.TRUE.equals(CALLBACK_PAUSED.get(context))) { applySmsResult(context, intent, result, status, parsed); return; } }
        // A result can arrive while original-source reconciliation is running. Preserve only
        // the callback envelope in an independent private queue, then process after startup.
        try {
            JSONObject callback = PhoneEventJournal.object("kind", "outbound_result", "action", intent.getAction(), "requestId", intent.getStringExtra("requestId"), "callbackToken", intent.getStringExtra("callbackToken"),
                    "partIndex", intent.getIntExtra("partIndex", -1), "resultCode", result, "deliveryStatus", status == null ? JSONObject.NULL : status, "statusReportParsed", parsed);
            enqueue(context, callback);
            scheduleDeferredDrain(context, 40);
        } catch (Exception unavailable) { /* The original pending SMS receipt remains unconfirmed. */ }
    }
    private static void enqueue(Context context, JSONObject callback) throws java.io.IOException {
        File directory = deferredDirectory(context); if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Callback queue unavailable");
        AtomicFile target = new AtomicFile(new File(directory, UUID.randomUUID() + ".json")); FileOutputStream output = target.startWrite();
        try { output.write(callback.toString().getBytes(StandardCharsets.UTF_8)); target.finishWrite(output); }
        catch (java.io.IOException failed) { target.failWrite(output); throw failed; }
    }
    // The small callback envelope queue survives replacement of a restored project/journal
    // namespace. Applied bodies and outcomes live in the ordinary full-backed-up journal.
    private static File deferredDirectory(Context context) { return new File(context.getFilesDir(), "communication-callbacks"); }
    private static boolean startupReady(Context context) { Context app = context.getApplicationContext(); return !(app instanceof HoyoungApplication) || ((HoyoungApplication) app).isStartupReady(); }
    private static void scheduleDeferredDrain(Context context, int attempts) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (startupReady(context)) drainDeferredCallbacks(context); else if (attempts > 0) scheduleDeferredDrain(context, attempts - 1);
        }, 200);
    }
    public static void drainDeferredCallbacks(Context context) {
        if (!startupReady(context)) return; synchronized (CALLBACK_LOCK) {
            if (Boolean.TRUE.equals(CALLBACK_PAUSED.get(context))) return;
            File[] files = deferredDirectory(context).listFiles(file -> file.isFile() && file.getName().matches("[0-9a-f-]{36}\\.json")); if (files == null) return;
            for (File file : files) {
                try { if (file.length() > 256 * 1024) continue; JSONObject callback = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                    if ("incoming_sms".equals(callback.optString("kind"))) {
                        JSONArray encoded = callback.getJSONArray("pdus"); Object[] pdus = new Object[encoded.length()]; for (int i = 0; i < encoded.length(); i++) pdus[i] = android.util.Base64.decode(encoded.getString(i), android.util.Base64.NO_WRAP);
                        Intent incoming = new Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).putExtra("pdus", pdus).putExtra("format", callback.optString("format", "3gpp")).putExtra("subscription", callback.optInt("subscription", -1));
                        observeIncoming(context, incoming); file.delete(); continue;
                    }
                    Intent intent = new Intent(callback.getString("action")).putExtra("requestId", callback.getString("requestId")).putExtra("callbackToken", callback.getString("callbackToken")).putExtra("partIndex", callback.getInt("partIndex"));
                    applySmsResult(context, intent, callback.getInt("resultCode"), callback.isNull("deliveryStatus") ? null : callback.getInt("deliveryStatus"), callback.optBoolean("statusReportParsed"));
                    // The callback is consumed only after the canonical request record exists.
                    if (!PhoneEventJournal.forContext(context).get(PREFIX + callback.getString("requestId"), "").isEmpty()) file.delete();
                } catch (Exception unavailable) { /* Retry from the durable envelope on the next read/startup drain. */ }
            }
        }
    }
    private static final class AndroidSms implements SmsBackend {
        private final Context context;
        AndroidSms(Context context) { this.context = context; }
        public boolean available() { return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY); }
        public int defaultSubscription() { return SmsManager.getDefaultSmsSubscriptionId(); }
        private SmsManager manager(int subscription) { SmsManager manager = Build.VERSION.SDK_INT >= 31 ? context.getSystemService(SmsManager.class) : SmsManager.getDefault(); if (manager == null) throw new IllegalStateException("SMS service is unavailable"); return Build.VERSION.SDK_INT >= 31 ? manager.createForSubscriptionId(subscription) : SmsManager.getSmsManagerForSubscriptionId(subscription); }
        public ArrayList<String> divide(String text, int subscription) { return manager(subscription).divideMessage(text); }
        public void send(String destination, ArrayList<String> parts, int subscription, ArrayList<PendingIntent> sent, ArrayList<PendingIntent> delivered) {
            SmsManager manager = manager(subscription);
            if (parts.size() == 1) manager.sendTextMessage(destination, null, parts.get(0), sent.get(0), delivered.get(0));
            else manager.sendMultipartTextMessage(destination, null, parts, sent, delivered);
        }
    }
    private static final class Selection {
        final ArrayList<String> clauses = new ArrayList<>(), values = new ArrayList<>();
        void add(String sql, String value) { clauses.add(sql); values.add(value); } String sql() { return clauses.isEmpty() ? null : String.join(" AND ", clauses); } String[] args() { return values.isEmpty() ? null : values.toArray(new String[0]); }
    }
    private static Selection dates(JSONObject args, String column) throws JSONException {
        Selection selection = new Selection(); long from = args.has("fromMs") ? nonNegativeLong(args, "fromMs") : 0, to = args.has("toMs") ? nonNegativeLong(args, "toMs") : Long.MAX_VALUE;
        if (to < from) throw new IllegalArgumentException(); if (args.has("fromMs")) selection.add(column + ">=?", String.valueOf(from)); if (args.has("toMs")) selection.add(column + "<=?", String.valueOf(to)); return selection;
    }
    private static String escapeLike(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
    private static JSONObject success() { return PhoneEventJournal.object("ok", true, "code", "ok"); }
    private static JSONObject failure(String code) { return PhoneEventJournal.object("ok", false, "code", code, "completionConfirmed", false); }
    private static JSONObject missing(String id) { return PhoneEventJournal.object("ok", false, "code", "permission_missing", "permissionId", id, "completionConfirmed", false); }
    private static String text(JSONObject args, String key, int maximum, boolean empty) throws JSONException { Object value = args.get(key); if (!(value instanceof String) || ((String) value).length() > maximum || !empty && ((String) value).trim().isEmpty()) throw new IllegalArgumentException(); return (String) value; }
    private static String requestId(JSONObject args) throws JSONException { String value = text(args, "requestId", 36, false); if (!value.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException(); return value; }
    private static long positiveLong(JSONObject args, String key) throws JSONException { long value = nonNegativeLong(args, key); if (value == 0) throw new IllegalArgumentException(); return value; }
    private static long nonNegativeLong(JSONObject args, String key) throws JSONException { Object value = args.get(key); if (!(value instanceof Number)) throw new IllegalArgumentException(); try { long number = new java.math.BigDecimal(value.toString()).longValueExact(); if (number < 0) throw new IllegalArgumentException(); return number; } catch (ArithmeticException | NumberFormatException invalid) { throw new IllegalArgumentException(); } }
    private static int integer(JSONObject args, String key, int fallback, int min, int max) throws JSONException { if (!args.has(key)) return fallback; long number = nonNegativeLong(args, key); if (number < min || number > max) throw new IllegalArgumentException(); return (int) number; }
    private static void rejectUnknown(JSONObject args, String... allowed) { for (java.util.Iterator<String> keys = args.keys(); keys.hasNext();) { String key = keys.next(); boolean okay = false; for (String candidate : allowed) if (key.equals(candidate)) okay = true; if (!okay) throw new IllegalArgumentException(); } }
    private static JSONObject properties(String... pairs) { JSONObject result = new JSONObject(); try { for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], PhoneEventJournal.object("type", pairs[i + 1])); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } return result; }
    private static void tool(JSONArray output, String name, String description, JSONObject properties, String... required) { JSONArray list = new JSONArray(); for (String key : required) list.put(key); output.put(PhoneEventJournal.object("name", name, "description", description, "inputSchema", PhoneEventJournal.object("type", "object", "properties", properties, "required", list, "additionalProperties", false))); }
}
