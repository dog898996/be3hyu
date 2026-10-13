package com.hoyoung.assistant.phone;

import android.app.Application;
import android.app.Notification;
import android.app.Person;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Process;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.SpannableString;
import android.util.Base64;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class NotificationObservedContentTest {
    private HoyoungStore store;
    private PhoneEventJournal journal;
    @After public void cleanup() {
        if (journal != null) { journal.ingestPendingForTests(); journal.closeWorker(); journal.awaitClosedForTests(); }
        if (store != null) { store.close(); RuntimeEnvironment.getApplication().deleteDatabase(HoyoungStore.DATABASE_NAME); }
    }
    @Test public void fullAccessibleTitleTextAndBigTextKeepExactCharactersBeyondDisplayPreviews() throws Exception {
        Notification notification = new Notification(); String title = repeat("제목", 1100), text = repeat("원문", 8000), big = repeat("큰 본문\n", 6000);
        notification.extras.putCharSequence(Notification.EXTRA_TITLE, title); notification.extras.putCharSequence(Notification.EXTRA_TEXT, text); notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, big);
        JSONObject result = NotificationObservedContent.capture(notification);
        assertEquals(title, field(result, "title").getString("value")); assertEquals(text, field(result, "text").getString("value")); assertEquals(big, field(result, "bigText").getString("value"));
        assertEquals(shaUtf16(text), field(result, "text").getString("sha256Utf16BE")); assertFalse(result.getBoolean("originalTruncated"));
        assertEquals(title.length() + text.length() + big.length(), result.getLong("observedTextUtf16Units"));
        assertEquals("android.notification.extras", result.getString("source")); assertFalse(result.getBoolean("allAppMessagesCaptured"));
        assertFalse(result.getBoolean("redactionConfirmed")); assertEquals("unknown_android_may_hide_or_summarize_content", result.getString("redactionStatus"));
    }
    @Test public void realMessagingStyleBundlesRetainSenderTextTimestampAndHistoricSources() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Person self = new Person.Builder().setName("나").build(), sender = new Person.Builder().setName("친구").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(self).setConversationTitle("대화방")
            .addMessage(new Notification.MessagingStyle.Message("실제 SDK 메시지", 12345, sender))
            .addHistoricMessage(new Notification.MessagingStyle.Message("이전 SDK 메시지", 11111, sender));
        Notification notification = new Notification.Builder(context, "fixture").setStyle(style).build();
        Parcelable[] observed = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES); assertNotNull(observed);
        String longer = repeat("긴 메시지 ", 2000); ((Bundle) observed[0]).putCharSequence("text", longer);
        JSONObject result = NotificationObservedContent.capture(notification), message = result.getJSONObject("messages").getJSONArray("values").getJSONObject(0);
        assertEquals(longer, message.getJSONObject("text").getString("value")); assertEquals(12345, message.getLong("time"));
        assertEquals("친구", message.getJSONObject("senderPersonName").getString("value")); assertTrue(message.getBoolean("timeReadable"));
        assertEquals("이전 SDK 메시지", result.getJSONObject("historicMessages").getJSONArray("values").getJSONObject(0).getJSONObject("text").getString("value"));
        assertEquals("대화방", field(result, "conversationTitle").getString("value")); assertFalse(result.getBoolean("rawBinaryCaptured"));
    }
    @Test @Config(sdk = 26) public void android26LegacyMessageSenderAndAbsentValuesRemainObservedOnly() throws Exception {
        Notification notification = new Notification(); Bundle message = message("원문", "구형 발신자", 123);
        notification.extras.putParcelableArray(Notification.EXTRA_MESSAGES, new Parcelable[]{message});
        JSONObject result = NotificationObservedContent.capture(notification), captured = result.getJSONObject("messages").getJSONArray("values").getJSONObject(0);
        assertEquals("구형 발신자", captured.getJSONObject("sender").getString("value")); assertEquals(123, captured.getLong("time"));
        assertFalse(captured.getJSONObject("senderPersonName").getBoolean("present")); assertTrue(captured.getJSONObject("senderPersonName").isNull("value"));
        assertFalse(field(result, "bigText").getBoolean("present")); assertTrue(field(result, "bigText").isNull("value"));
        assertFalse(result.getBoolean("originalTruncated")); assertFalse(result.getBoolean("incompleteObservation"));
    }
    @Test public void textLinesPreserveObservedIndicesNullsAndPlainTextWithoutInventingFormatting() throws Exception {
        Notification notification = new Notification(); String longLine = repeat("줄 원문", 2000);
        notification.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, new CharSequence[]{new SpannableString(longLine), null, "마지막"});
        JSONObject result = NotificationObservedContent.capture(notification); JSONArray lines = result.getJSONObject("textLines").getJSONArray("values");
        assertEquals(3, lines.length()); assertEquals(longLine, lines.getJSONObject(0).getJSONObject("text").getString("value"));
        assertEquals(1, lines.getJSONObject(1).getInt("index")); assertTrue(lines.getJSONObject(1).getJSONObject("text").isNull("value"));
        assertEquals("마지막", lines.getJSONObject(2).getJSONObject("text").getString("value")); assertFalse(result.getBoolean("textFormattingPreserved"));
    }
    @Test public void rawUnitLimitRetainsPrefixAndReportsFullObservedCountsAndFullFieldHash() throws Exception {
        Notification notification = new Notification(); String text = repeat("a", 199000), big = repeat("b", 5000);
        notification.extras.putCharSequence(Notification.EXTRA_TEXT, text); notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, big);
        JSONObject result = NotificationObservedContent.capture(notification); assertEquals(text, field(result, "text").getString("value"));
        assertEquals(1000, field(result, "bigText").getString("value").length()); assertEquals(5000, field(result, "bigText").getInt("observedUtf16Units"));
        assertEquals(shaUtf16(big), field(result, "bigText").getString("sha256Utf16BE")); assertTrue(field(result, "bigText").getBoolean("originalTruncated"));
        assertEquals(204000, result.getLong("observedTextUtf16Units")); assertEquals(200000, result.getInt("retainedTextUtf16Units"));
        assertTrue(result.getBoolean("originalTruncated")); assertTrue(result.getBoolean("observedTextUnitCountsComplete"));
    }
    @Test public void escapingBudgetCannotOverflowSpoolOrSqlRowAndExplainsOriginalTruncation() throws Exception {
        Notification notification = new Notification(); String text = repeat("\u0000", 200000); notification.extras.putCharSequence(Notification.EXTRA_TEXT, text);
        JSONObject result = NotificationObservedContent.capture(notification);
        assertTrue(result.toString().length() <= NotificationObservedContent.MAX_SERIALIZED_CHARS); assertTrue(result.getBoolean("originalTruncated"));
        assertEquals(200000, field(result, "text").getInt("observedUtf16Units")); assertEquals(shaUtf16(text), field(result, "text").getString("sha256Utf16BE"));
        assertTrue(field(result, "text").getInt("retainedUtf16Units") < 200000);
        initJournal(); JSONObject payload = new JSONObject().put("observedOriginal", result);
        assertTrue(payload.toString().length() < 500000); assertTrue(payload.toString().getBytes(StandardCharsets.UTF_8).length < PhoneObservationSpool.MAX_RECORD_BYTES);
        assertTrue(journal.append("notification", "android.notification_listener", payload, 1, null).getBoolean("observationPersisted"));
        journal.ingestPendingForTests(); JSONObject read = store.deviceEvents("notification", null, 0, 1).getJSONObject(0).getJSONObject("payload").getJSONObject("observedOriginal");
        assertEquals(field(result, "text").getString("value"), field(read, "text").getString("value"));
    }
    @Test public void messageAndLineEntryBudgetsReportOmissionsRatherThanClaimFullHistory() throws Exception {
        Notification notification = new Notification(); Parcelable[] current = new Parcelable[100], historic = new Parcelable[30]; CharSequence[] lines = new CharSequence[120];
        for (int index = 0; index < current.length; index++) current[index] = message("m", "sender", index);
        for (int index = 0; index < historic.length; index++) historic[index] = message("m", "sender", index);
        java.util.Arrays.fill(lines, "l"); notification.extras.putParcelableArray(Notification.EXTRA_MESSAGES, current); notification.extras.putParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES, historic); notification.extras.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, lines);
        JSONObject result = NotificationObservedContent.capture(notification), messages = result.getJSONObject("messages"), history = result.getJSONObject("historicMessages");
        assertEquals(100, messages.getInt("observedEntryCount")); assertEquals(96, messages.getInt("retainedEntryCount")); assertEquals(4, messages.getInt("omittedEntryCount"));
        assertEquals(30, history.getInt("observedEntryCount")); assertEquals(0, history.getInt("retainedEntryCount")); assertEquals(30, history.getInt("omittedEntryCount"));
        assertEquals(96, result.getJSONObject("textLines").getInt("retainedEntryCount")); assertEquals(24, result.getJSONObject("textLines").getInt("omittedEntryCount"));
        assertEquals(1030, result.getLong("observedTextUtf16Units")); assertTrue(result.getBoolean("originalTruncated")); assertTrue(result.getBoolean("observedTextUnitCountsComplete"));
    }
    @Test public void malformedKnownExtraTypesAreFlaggedAndArbitraryBinaryExtrasAreExcluded() throws Exception {
        Notification notification = new Notification(); notification.extras.putInt(Notification.EXTRA_TEXT, 17); notification.extras.putByteArray("private_binary", new byte[1024]);
        JSONObject result = NotificationObservedContent.capture(notification);
        assertTrue(field(result, "text").getBoolean("present")); assertTrue(field(result, "text").isNull("value")); assertEquals("Integer", field(result, "text").getString("unsupportedValueType"));
        assertTrue(result.getBoolean("incompleteObservation")); assertFalse(result.getBoolean("observedTextUnitCountsComplete"));
        assertFalse(result.toString().contains("private_binary")); assertFalse(result.getBoolean("rawBinaryCaptured"));
    }
    @Test public void malformedUtf16OriginalCodeUnitsSurviveJsonAndUtf8PersistenceAsExplicitBytes() throws Exception {
        Notification notification = new Notification(); String text = "prefix\uD800suffix"; notification.extras.putCharSequence(Notification.EXTRA_TEXT, text);
        JSONObject result = NotificationObservedContent.capture(notification), field = field(result, "text");
        JSONObject persisted = new JSONObject(new String(result.toString().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertEquals("utf16be_base64", field.getString("valueEncoding")); assertTrue(field.getBoolean("malformedUtf16PreservedAsBytes"));
        assertArrayEquals(utf16Bytes(text), Base64.decode(field(persisted, "text").getString("value"), Base64.NO_WRAP)); assertEquals(shaUtf16(text), field.getString("sha256Utf16BE"));
        assertFalse(result.getBoolean("originalTruncated"));
    }
    @Test public void realListenerPersistsExtendedBodyAndKeepsOwnStatusAndExternalSpoofProvenance() throws Exception {
        initJournal(); Context context = RuntimeEnvironment.getApplication(); Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", new ComponentName(context, HoyoungNotificationListener.class).flattenToString());
        HoyoungNotificationListener listener = Robolectric.buildService(HoyoungNotificationListener.class).create().get();
        String text = repeat("본문", 5000) + "exact-source-tail", big = repeat("확장", 8000);
        Notification external = new Notification(); external.extras.putCharSequence(Notification.EXTRA_TEXT, text); external.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, big);
        external.extras.putString("com.hoyoung.assistant.source_kind", "assistant_reply"); external.extras.putLong("com.hoyoung.assistant.assistant_message_id", 91);
        listener.onNotificationPosted(notice("example.external", external, 91));
        Notification ownStatus = new Notification(); ownStatus.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, big); listener.onNotificationPosted(notice(context.getPackageName(), ownStatus, 92));
        journal.ingestPendingForTests(); JSONArray rows = store.deviceEvents("notification", null, 0, 100); assertEquals(2, rows.length());
        JSONObject payload = rows.getJSONObject(0).getJSONObject("payload"); assertEquals(6000, payload.getString("text").length()); assertTrue(payload.getBoolean("textTruncated")); assertFalse(payload.getBoolean("originalTruncated"));
        assertEquals(text, field(payload.getJSONObject("observedOriginal"), "text").getString("value")); assertEquals(big, field(payload.getJSONObject("observedOriginal"), "bigText").getString("value"));
        assertEquals("external_notification", payload.getString("sourceKind")); assertFalse(payload.has("assistantMessageId")); assertFalse(payload.getBoolean("ownerReadConfirmed"));
        JSONObject status = rows.getJSONObject(1).getJSONObject("payload"); assertEquals("assistant_app_notification", status.getString("sourceKind")); assertFalse(status.getBoolean("externalInputTriggerEligible"));
        PhoneTools tools = new PhoneTools(context, store); assertTrue(tools.canReadStoredSource(rows.getJSONObject(1))); assertFalse(tools.canUseImplicitMemorySource(rows.getJSONObject(1)));
        assertEquals(big, field(status.getJSONObject("observedOriginal"), "bigText").getString("value"));
    }
    private void initJournal() { Context context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); clear(new File(context.getFilesDir(), "device-journal")); store = new HoyoungStore(context); journal = PhoneEventJournal.bind(context, store); journal.ingestPendingForTests(); }
    private static StatusBarNotification notice(String packageName, Notification notification, int id) { return new StatusBarNotification(packageName, packageName, id, null, Process.myUid(), 0, 0, notification, Process.myUserHandle(), System.currentTimeMillis() + id); }
    private static Bundle message(String text, String sender, long time) { Bundle bundle = new Bundle(); bundle.putCharSequence("text", text); bundle.putCharSequence("sender", sender); bundle.putLong("time", time); return bundle; }
    private static JSONObject field(JSONObject result, String name) throws Exception { return result.getJSONObject("standardText").getJSONObject(name); }
    private static String repeat(String text, int count) { StringBuilder result = new StringBuilder(text.length() * count); for (int index = 0; index < count; index++) result.append(text); return result.toString(); }
    private static byte[] utf16Bytes(String text) { byte[] bytes = new byte[text.length() * 2]; for (int index = 0; index < text.length(); index++) { bytes[index * 2] = (byte) (text.charAt(index) >>> 8); bytes[index * 2 + 1] = (byte) text.charAt(index); } return bytes; }
    private static String shaUtf16(String text) throws Exception { StringBuilder result = new StringBuilder(); for (byte value : MessageDigest.getInstance("SHA-256").digest(utf16Bytes(text))) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return result.toString(); }
    private static void clear(File file) { if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) clear(child); } if (file.exists()) assertTrue(file.delete()); }
}
