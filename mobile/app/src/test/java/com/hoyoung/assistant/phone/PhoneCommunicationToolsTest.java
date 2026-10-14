package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Synthetic provider/callback fixtures only; no Android SMS transmission or carrier is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneCommunicationToolsTest {
    private Context context; private Application application; private HoyoungStore store; private PhoneEventJournal journal;
    private PhoneCommunicationTools tools; private FakeSms sms; private FixtureProvider smsProvider, calls;
    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext(); application = (Application) context; store = new HoyoungStore(context);
        journal = PhoneEventJournal.bind(context, store); journal.refreshPolicyBlocking(); sms = new FakeSms(); tools = new PhoneCommunicationTools(context, journal, sms);
        smsProvider = provider(Telephony.Sms.CONTENT_URI.getAuthority(), false); calls = provider(CallLog.Calls.CONTENT_URI.getAuthority(), true);
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG);
        clearCallbacks();
    }
    @After public void cleanup() { tools.afterBackupRestore(); clearCallbacks(); journal.closeWorker(); journal.awaitClosedForTests(); store.close(); }
    private void grant(String... permissions) { Shadows.shadowOf(application).grantPermissions(permissions); }
    private JSONObject send() throws Exception { grant(Manifest.permission.SEND_SMS); JSONObject result = tools.execute("phone_sms_send", new JSONObject().put("number", "+821000000000").put("text", "Synthetic only 🌙")); assertTrue(result.toString(), result.getBoolean("ok")); return result; }
    private JSONObject receipt(String id) throws Exception { JSONObject result = tools.execute("phone_sms_send_read", new JSONObject().put("requestId", id)); assertTrue(result.toString(), result.getBoolean("ok")); return result.getJSONObject("request"); }
    private Intent callback(boolean delivered, int index) { return new Intent(Shadows.shadowOf((delivered ? sms.delivered : sms.sent).get(index)).getSavedIntent()); }
    private void sent(int index, int result) { PhoneCommunicationTools.applySmsResult(context, callback(false, index), result, null, false); }
    private void delivered(int index, int result, Integer status, boolean parsed) { PhoneCommunicationTools.applySmsResult(context, callback(true, index), result, status, parsed); }

    @Test public void strictCatalogHasSixRealApiToolsWithoutAMadeUpSmsRole() throws Exception {
        JSONArray catalog = new JSONArray(); tools.appendCatalog(catalog); assertEquals(6, catalog.length());
        for (int i = 0; i < catalog.length(); i++) { JSONObject definition = catalog.getJSONObject(i); assertTrue(tools.supports(definition.getString("name"))); assertFalse(definition.getJSONObject("inputSchema").getBoolean("additionalProperties")); }
    }
    @Test public void providerReadsAndDeletesNeedTheirActualRuntimeGrants() throws Exception {
        assertEquals("permission_missing", tools.execute("phone_sms_read", new JSONObject()).getString("code")); assertEquals(0, smsProvider.queries);
        assertEquals("permission_missing", tools.execute("phone_call_log_read", new JSONObject()).getString("code")); assertEquals(0, calls.queries);
        assertEquals("permission_missing", tools.execute("phone_call_log_delete", new JSONObject().put("callId", 7)).getString("code")); assertEquals(0, calls.deletes);
    }
    @Test public void smsQueryUsesBoundParametersAndDoesNotMarkOwnerRead() throws Exception {
        grant(Manifest.permission.READ_SMS); JSONObject result = tools.execute("phone_sms_read", new JSONObject().put("address", "+821000000000").put("query", "50%_'").put("fromMs", 100).put("toMs", 200));
        assertTrue(result.toString(), result.getBoolean("ok")); assertFalse(result.getBoolean("ownerReadConfirmed")); assertFalse(result.getJSONArray("messages").getJSONObject(0).getBoolean("ownerReadConfirmed"));
        assertFalse(smsProvider.selection.contains("50%")); assertArrayEquals(new String[]{"100", "200", "+821000000000", "%50\\%\\_'%"}, smsProvider.selectionArgs); assertEquals(Telephony.Sms.DATE + " DESC", smsProvider.order);
    }
    @Test public void pagedSmsReadsPreserveTheFullOriginalHashAndExplicitRemainder() throws Exception {
        grant(Manifest.permission.READ_SMS); smsProvider.body = "처음 🌙 그리고 마지막";
        JSONObject first = tools.execute("phone_sms_get", new JSONObject().put("smsId", 11).put("maxChars", 3)).getJSONObject("message");
        JSONObject second = tools.execute("phone_sms_get", new JSONObject().put("smsId", 11).put("offsetChars", first.getInt("nextOffsetChars"))).getJSONObject("message");
        assertEquals(smsProvider.body, first.getString("body") + second.getString("body")); assertEquals(PhoneEventJournal.hash(smsProvider.body), first.getString("bodyOriginalSha256")); assertEquals(first.getString("bodyOriginalSha256"), second.getString("bodyOriginalSha256"));
        assertTrue(first.getBoolean("bodyTruncated")); assertFalse(second.getBoolean("bodyTruncated")); assertEquals("content://sms/11", smsProvider.lastUri.toString());
    }
    @Test public void smsPagingNeverCutsAnEmojiOrAcceptsAnOffsetInsideASurrogatePair() throws Exception {
        grant(Manifest.permission.READ_SMS); smsProvider.body = "🌙끝";
        JSONObject first = tools.execute("phone_sms_get", new JSONObject().put("smsId", 11).put("maxChars", 1)).getJSONObject("message"); assertEquals("🌙", first.getString("body")); assertEquals(2, first.getInt("nextOffsetChars"));
        assertEquals("invalid_arguments", tools.execute("phone_sms_get", new JSONObject().put("smsId", 11).put("offsetChars", 1)).getString("code"));
    }
    @Test public void smsPreviewReportsTruncationAndOriginalIdentityWithoutDiscardingProviderText() throws Exception {
        grant(Manifest.permission.READ_SMS); smsProvider.body = "x".repeat(6000); JSONObject message = tools.execute("phone_sms_read", new JSONObject()).getJSONArray("messages").getJSONObject(0);
        assertEquals(5000, message.getString("body").length()); assertTrue(message.getBoolean("bodyTruncated")); assertEquals(6000, message.getInt("bodyOriginalCharacters")); assertEquals(PhoneEventJournal.hash(smsProvider.body), message.getString("bodyOriginalSha256"));
    }
    @Test public void callLogRowsAndSelectedDeleteUseOnlyTheActualProviderOutcome() throws Exception {
        grant(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG);
        JSONObject read = tools.execute("phone_call_log_read", new JSONObject().put("number", "+821000000000")); assertTrue(read.toString(), read.getBoolean("ok")); assertEquals(25, read.getJSONArray("calls").getJSONObject(0).getLong("durationSeconds"));
        JSONObject deleted = tools.execute("phone_call_log_delete", new JSONObject().put("callId", 7)); assertTrue(deleted.getBoolean("providerConfirmed")); assertFalse(deleted.getBoolean("cloudSyncConfirmed")); assertEquals("content://call_log/calls/7", calls.lastUri.toString());
        calls.rowsDeleted = 0; assertEquals("target_not_found", tools.execute("phone_call_log_delete", new JSONObject().put("callId", 7)).getString("code"));
    }
    @Test public void sendingDoesNotUseAnActualRadioBeforeGrantAndRecordsExactOutboundText() throws Exception {
        assertEquals("permission_missing", tools.execute("phone_sms_send", new JSONObject().put("number", "01000000000").put("text", "Synthetic")).getString("code")); assertEquals(0, sms.sends);
        JSONObject requested = send(); assertFalse(requested.getBoolean("sentConfirmed")); assertFalse(requested.getBoolean("deliveryConfirmed")); assertFalse(requested.getBoolean("completionConfirmed"));
        JSONObject receipt = receipt(requested.getString("requestId")); assertEquals("Synthetic only 🌙", receipt.getString("text")); assertEquals(PhoneEventJournal.hash("Synthetic only 🌙"), receipt.getString("textSha256")); assertFalse(receipt.has("callbackToken")); assertFalse(receipt.getBoolean("isOwnerInstruction")); assertEquals(1, sms.sends);
    }
    @Test public void noDefaultSimAndMalformedDestinationNeverReachBackendSend() throws Exception {
        grant(Manifest.permission.SEND_SMS); sms.subscription = -1;
        assertEquals("sms_subscription_unavailable", tools.execute("phone_sms_send", new JSONObject().put("number", "01000000000").put("text", "Synthetic")).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_sms_send", new JSONObject().put("number", "https://invalid").put("text", "Synthetic")).getString("code")); assertEquals(0, sms.sends);
    }
    @Test public void receiptPreservesTheExactTextSubmittedByAndroidSegmentationAlongsideTheOwnerRequest() throws Exception {
        sms.translated = true; String id = send().getString("requestId"); JSONObject receipt = receipt(id);
        assertEquals("Synthetic normalized", receipt.getString("text")); assertEquals("Synthetic only 🌙", receipt.getString("requestedText")); assertTrue(receipt.getBoolean("androidSegmentationChangedText"));
        assertEquals(PhoneEventJournal.hash("Synthetic normalized"), receipt.getString("textSha256")); assertFalse(receipt.getBoolean("recipientDecodedTextVerified"));
    }
    @Test public void sentCallbackConfirmsSendingButNeverInventsRecipientDelivery() throws Exception {
        String id = send().getString("requestId"); sent(0, Activity.RESULT_OK); JSONObject receipt = receipt(id);
        assertTrue(receipt.getBoolean("sentConfirmed")); assertTrue(receipt.getBoolean("sentCallbacksConfirmed")); assertFalse(receipt.getBoolean("deliveryConfirmed")); assertFalse(receipt.getBoolean("completionConfirmed")); assertEquals("sent_confirmed_delivery_unconfirmed", receipt.getString("state"));
    }
    @Test public void multipartCompletionNeedsEveryParsedSuccessfulDeliveryReport() throws Exception {
        sms.multipart = true; String id = send().getString("requestId"); sent(0, Activity.RESULT_OK); sent(1, Activity.RESULT_OK); delivered(0, Activity.RESULT_OK, 0, true);
        assertFalse(receipt(id).getBoolean("deliveryConfirmed")); delivered(1, Activity.RESULT_OK, 0, true); JSONObject result = receipt(id); assertTrue(result.getBoolean("deliveryConfirmed")); assertTrue(result.getBoolean("completionConfirmed")); assertEquals("delivery_confirmed", result.getString("state")); assertEquals(1, sms.sends);
    }
    @Test public void oneFailedSentPartRemainsAFailureRatherThanPartialMessageSuccess() throws Exception {
        sms.multipart = true; String id = send().getString("requestId"); sent(0, Activity.RESULT_OK); sent(1, SmsManager.RESULT_ERROR_NO_SERVICE);
        JSONObject receipt = receipt(id); assertEquals("sent_failed", receipt.getString("state")); assertFalse(receipt.getBoolean("sentConfirmed")); assertFalse(receipt.getBoolean("completionConfirmed")); assertEquals(1, sms.sends);
    }
    @Test public void callbackWithoutParsedStatusReportOrPendingCarrierStatusIsNotDelivery() throws Exception {
        String id = send().getString("requestId"); delivered(0, Activity.RESULT_OK, null, false); assertFalse(receipt(id).getBoolean("deliveryConfirmed"));
        String next = send().getString("requestId"); delivered(0, Activity.RESULT_OK, 32, true); assertFalse(receipt(next).getBoolean("deliveryConfirmed"));
    }
    @Test public void verifiedDeliverySupersedesAnEarlierSendErrorWithoutRewritingCallbackHistory() throws Exception {
        String id = send().getString("requestId"); sent(0, SmsManager.RESULT_ERROR_GENERIC_FAILURE); delivered(0, Activity.RESULT_OK, 0, true);
        JSONObject receipt = receipt(id); assertEquals("delivery_confirmed", receipt.getString("state")); assertTrue(receipt.getBoolean("deliveryConfirmed")); assertTrue(receipt.getBoolean("completionConfirmed"));
        assertTrue(receipt.getBoolean("sentConfirmed")); assertFalse(receipt.getBoolean("sentCallbacksConfirmed")); assertEquals(SmsManager.RESULT_ERROR_GENERIC_FAILURE, receipt.getJSONArray("parts").getJSONObject(0).getInt("sentCode")); assertEquals(1, sms.sends);
    }
    @Test public void duplicateAndWrongScopeCallbacksNeverResendOrOverwriteActualResults() throws Exception {
        String id = send().getString("requestId"); Intent wrong = callback(false, 0).putExtra("callbackToken", "00000000-0000-0000-0000-000000000000"); PhoneCommunicationTools.applySmsResult(context, wrong, Activity.RESULT_OK, null, false);
        assertTrue(receipt(id).getJSONArray("parts").getJSONObject(0).isNull("sentCode")); sent(0, Activity.RESULT_OK); sent(0, SmsManager.RESULT_ERROR_NO_SERVICE);
        assertEquals(Activity.RESULT_OK, receipt(id).getJSONArray("parts").getJSONObject(0).getInt("sentCode")); assertEquals(1, sms.sends);
    }
    @Test public void sendGrantRevocationBlocksNewSmsButAllowsReadingOwnPriorOutcomeReceipt() throws Exception {
        String id = send().getString("requestId"); Shadows.shadowOf(application).denyPermissions(Manifest.permission.SEND_SMS);
        assertEquals("permission_missing", tools.execute("phone_sms_send", new JSONObject().put("number", "01000000000").put("text", "Synthetic")).getString("code")); assertEquals(id, receipt(id).getString("requestId")); assertEquals(1, sms.sends);
    }
    @Test public void explicitOwnerSendSwitchDisablesItEvenWhenAndroidGrantsSms() throws Exception {
        grant(Manifest.permission.SEND_SMS); store.putSetting("toolPermissions", "{\"phone_sms_send\":false}"); journal.applyPermissionPolicyFromOwner("{\"phone_sms_send\":false}", true);
        assertEquals("permission_disabled", tools.execute("phone_sms_send", new JSONObject().put("number", "01000000000").put("text", "Synthetic")).getString("code")); assertEquals(0, sms.sends);
    }
    @Test public void pendingIntentResultDuringBackupPauseIsDurableAndAppliedOnlyAfterRestore() throws Exception {
        String id = send().getString("requestId"); tools.pauseForBackupRestore(); sms.sent.get(0).send(context, Activity.RESULT_OK, null); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("restore_in_progress", tools.execute("phone_sms_send_read", new JSONObject().put("requestId", id)).getString("code"));
        File queue = new File(context.getFilesDir(), "communication-callbacks"); assertNotNull(queue.listFiles()); assertEquals(1, queue.listFiles().length);
        tools.afterBackupRestore(); assertTrue(receipt(id).getBoolean("sentConfirmed")); assertEquals(0, queue.listFiles().length); assertEquals(1, sms.sends);
    }
    @Test public void receivedSmsUsesRealPduParsingAndIsNeverAnOwnerCommandOrProofOfReading() throws Exception {
        byte[] pdu = hex("00040B912801214365F7000862014121000000020048"); SmsMessage parsed = SmsMessage.createFromPdu(pdu, "3gpp"); assertNotNull(parsed); assertEquals("H", parsed.getMessageBody());
        Intent incoming = new Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).putExtra("format", "3gpp").putExtra("pdus", new Object[]{pdu});
        PhoneCommunicationTools.SmsObservedReceiver receiver = new PhoneCommunicationTools.SmsObservedReceiver(); receiver.onReceive(context, incoming); journal.ingestPendingForTests(); assertEquals(0, journal.events("communication_received", "android.sms_received", 0, 10).length());
        grant(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS); receiver.onReceive(context, incoming); journal.ingestPendingForTests(); JSONObject observed = journal.events("communication_received", "android.sms_received", 0, 10).getJSONObject(0).getJSONObject("payload");
        assertEquals("H", observed.getString("body")); assertEquals("external_sms", observed.getString("sourceKind")); assertFalse(observed.getBoolean("isOwnerInstruction")); assertFalse(observed.getBoolean("ownerReadConfirmed"));
    }
    @Test public void incomingSmsDuringBackupPauseRetainsPduAndDoesNotWriteTheReplacingJournal() throws Exception {
        grant(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS); tools.pauseForBackupRestore();
        byte[] pdu = hex("00040B912801214365F7000862014121000000020048"); new PhoneCommunicationTools.SmsObservedReceiver().onReceive(context, new Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).putExtra("format", "3gpp").putExtra("pdus", new Object[]{pdu}));
        journal.ingestPendingForTests(); assertEquals(0, journal.events("communication_received", "android.sms_received", 0, 10).length());
        tools.afterBackupRestore(); journal.ingestPendingForTests(); assertEquals(1, journal.events("communication_received", "android.sms_received", 0, 10).length());
    }
    @Test public void impossibleSmsIdsAndInvalidArgumentsDoNotPerformProviderMutationsOrSend() throws Exception {
        grant(Manifest.permission.READ_SMS, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.SEND_SMS);
        assertEquals("invalid_arguments", tools.execute("phone_sms_get", new JSONObject().put("smsId", new java.math.BigInteger("18446744073709551617"))).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_call_log_delete", new JSONObject().put("callId", 1.5)).getString("code"));
        assertEquals("invalid_arguments", tools.execute("phone_sms_send", new JSONObject().put("number", "01000000000").put("text", "Synthetic").put("pretendDelivered", true)).getString("code")); assertEquals(0, smsProvider.queries); assertEquals(0, calls.deletes); assertEquals(0, sms.sends);
    }
    private FixtureProvider provider(String authority, boolean callLog) { FixtureProvider provider = new FixtureProvider(); provider.callLog = callLog; ProviderInfo info = new ProviderInfo(); info.authority = authority; provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal(authority, provider); return provider; }
    private void clearCallbacks() { File[] files = new File(context.getFilesDir(), "communication-callbacks").listFiles(); if (files != null) for (File file : files) file.delete(); }
    private static byte[] hex(String value) { byte[] bytes = new byte[value.length() / 2]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16); return bytes; }
    private static final class FakeSms implements PhoneCommunicationTools.SmsBackend {
        int sends, subscription = 11; boolean multipart, translated; ArrayList<PendingIntent> sent, delivered;
        public boolean available() { return true; } public int defaultSubscription() { return subscription; }
        public ArrayList<String> divide(String body, int sid) { return new ArrayList<>(translated ? Arrays.asList("Synthetic normalized") : multipart ? Arrays.asList(body.substring(0, body.length() / 2), body.substring(body.length() / 2)) : Arrays.asList(body)); }
        public void send(String number, ArrayList<String> parts, int sid, ArrayList<PendingIntent> sent, ArrayList<PendingIntent> delivered) { sends++; this.sent = sent; this.delivered = delivered; }
    }
    public static final class FixtureProvider extends ContentProvider {
        boolean callLog; int queries, deletes, rowsDeleted = 1; String body = "Synthetic SMS 🌙", selection, order; String[] selectionArgs; Uri lastUri;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { queries++; lastUri = uri; this.selection = selection; this.selectionArgs = selectionArgs; order = sortOrder; MatrixCursor cursor = new MatrixCursor(projection);
            cursor.addRow(callLog ? new Object[]{7L, "+821000000000", "Synthetic caller", CallLog.Calls.INCOMING_TYPE, 123L, 25L} : new Object[]{11L, "+821000000000", body, 123L, Telephony.Sms.MESSAGE_TYPE_INBOX, 0, -1, 9L, 11}); return cursor; }
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) { deletes++; lastUri = uri; return rowsDeleted; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    }
}
