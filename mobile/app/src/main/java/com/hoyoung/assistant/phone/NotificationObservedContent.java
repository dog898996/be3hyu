package com.hoyoung.assistant.phone;

import android.app.Notification;
import android.app.Person;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Only Android's known observable textual extras; no arbitrary bundles, media or unseen chat. */
final class NotificationObservedContent {
    static final int MAX_RAW_UTF16 = 200000;
    static final int MAX_SERIALIZED_CHARS = 430000;
    private static final int MAX_ENCODED_TEXT_CHARS = 240000;
    private static final int MAX_MESSAGES = 96;
    private static final int MAX_LINES = 96;
    private static final int MAX_INSPECTED_ITEMS = 4096;
    private final List<Field> retainedFields = new ArrayList<>();
    private final Set<String> reasons = new LinkedHashSet<>();
    private int retainedUnits, encodedCharacters, retainedMessages;
    private long observedUnits;
    private boolean observedCountsComplete = true, incomplete, truncated;

    static JSONObject capture(Notification notification) {
        NotificationObservedContent reader = new NotificationObservedContent();
        try { return reader.read(notification == null ? null : notification.extras); }
        catch (JSONException impossible) { throw new IllegalStateException("Known notification content cannot be represented", impossible); }
    }
    private JSONObject read(Bundle extras) throws JSONException {
        JSONObject fields = new JSONObject();
        for (String[] field : new String[][]{{"title", Notification.EXTRA_TITLE}, {"text", Notification.EXTRA_TEXT}, {"bigText", Notification.EXTRA_BIG_TEXT},
            {"titleBig", Notification.EXTRA_TITLE_BIG}, {"subText", Notification.EXTRA_SUB_TEXT}, {"summaryText", Notification.EXTRA_SUMMARY_TEXT},
            {"conversationTitle", Notification.EXTRA_CONVERSATION_TITLE}, {"selfDisplayName", Notification.EXTRA_SELF_DISPLAY_NAME}}) {
            fields.put(field[0], text(read(extras, field[1]), field[1]));
        }
        JSONObject messages = messages(read(extras, Notification.EXTRA_MESSAGES), Notification.EXTRA_MESSAGES);
        JSONObject historic = messages(read(extras, Notification.EXTRA_HISTORIC_MESSAGES), Notification.EXTRA_HISTORIC_MESSAGES);
        JSONObject lines = lines(read(extras, Notification.EXTRA_TEXT_LINES));
        JSONObject result = PhoneEventJournal.object("format", "android_notification_observed_text_v1", "source", "android.notification.extras", "extrasPresent", extras != null,
            "standardText", fields, "messages", messages, "historicMessages", historic, "textLines", lines,
            "observedOnly", true, "allAppMessagesCaptured", false, "rawBinaryCaptured", false, "textFormattingPreserved", false,
            "redactionStatus", "unknown_android_may_hide_or_summarize_content", "redactionConfirmed", false,
            "limitRawUtf16Units", MAX_RAW_UTF16, "limitSerializedChars", MAX_SERIALIZED_CHARS);
        finish(result);
        // Reserved metadata normally keeps us well below this cap. This final measured guard
        // also covers pathological escaping and future field additions without rejecting the
        // whole notification or claiming its original was fully retained.
        while (result.toString().length() > MAX_SERIALIZED_CHARS - 256) {
            Field largest = null;
            for (Field field : retainedFields) if (field.units > 0 && (largest == null || field.units > largest.units)) largest = field;
            if (largest == null) throw new IllegalStateException("Notification metadata exceeds its explicit bound");
            retainedUnits -= largest.units;
            largest.units = safePrefix(largest.original, largest.units / 2);
            retainedUnits += largest.units; largest.save();
            truncated = true; reasons.add("serialized_notification_budget"); finish(result);
        }
        result.put("sha256", PhoneEventJournal.hash(result.toString())).put("checksumScope", "observed_text_capture_json_before_checksum_fields");
        return result;
    }
    private void finish(JSONObject result) throws JSONException {
        result.put("originalTruncated", truncated).put("incompleteObservation", incomplete).put("observedTextUtf16Units", observedUnits)
            .put("observedTextUnitCountsComplete", observedCountsComplete).put("retainedTextUtf16Units", retainedUnits)
            .put("truncationReasons", new JSONArray(reasons));
    }
    private JSONObject text(Observed value, String sourceKey) throws JSONException {
        JSONObject result = PhoneEventJournal.object("sourceKey", sourceKey, "present", value.failed ? JSONObject.NULL : value.present, "value", JSONObject.NULL);
        if (value.failed) { result.put("readFailed", true); incomplete = true; observedCountsComplete = false; return result; }
        if (!value.present || value.value == null) { result.put("observedUtf16Units", 0).put("retainedUtf16Units", 0).put("originalTruncated", false); return result; }
        if (!(value.value instanceof CharSequence)) { result.put("unsupportedValueType", value.value.getClass().getSimpleName()); incomplete = true; observedCountsComplete = false; return result; }
        String original;
        try { original = value.value.toString(); } catch (RuntimeException unreadable) { result.put("readFailed", true); incomplete = true; observedCountsComplete = false; return result; }
        observedUnits += original.length();
        boolean base64 = malformedUtf16(original);
        int maximum = Math.min(original.length(), MAX_RAW_UTF16 - retainedUnits), kept = safePrefix(original, Math.max(0, maximum));
        int budget = Math.max(0, MAX_ENCODED_TEXT_CHARS - encodedCharacters);
        if (base64) { kept = Math.min(kept, budget / 8 * 3); } // Conservative UTF16BE base64 bound.
        else { int cost = 0, count = 0; while (count < kept) { int next = quotedCost(original.charAt(count)); if (cost + next > budget) break; cost += next; count++; } kept = safePrefix(original, count); }
        Field field = new Field(result, original, kept, base64); field.save(); retainedFields.add(field);
        encodedCharacters += base64 ? ((kept * 2 + 2) / 3) * 4 : quotedLength(original, kept); retainedUnits += kept;
        result.put("sha256Utf16BE", hashText(original)).put("checksumScope", "complete_observed_field_utf16be");
        if (kept < original.length()) { truncated = true; reasons.add(maximum < original.length() ? "total_raw_utf16_budget" : "serialized_text_budget"); }
        return result;
    }
    private final class Field {
        final JSONObject result; final String original; final boolean base64; int units;
        Field(JSONObject result, String original, int units, boolean base64) { this.result = result; this.original = original; this.units = units; this.base64 = base64; }
        void save() throws JSONException {
            String prefix = original.substring(0, units);
            result.put("value", base64 ? Base64.encodeToString(utf16Bytes(prefix), Base64.NO_WRAP) : prefix)
                .put("valueEncoding", base64 ? "utf16be_base64" : "text").put("observedUtf16Units", original.length())
                .put("retainedUtf16Units", units).put("originalTruncated", units < original.length());
            if (base64) result.put("malformedUtf16PreservedAsBytes", true);
        }
    }
    private JSONObject lines(Observed value) throws JSONException {
        JSONObject result = arrayState(value, Notification.EXTRA_TEXT_LINES); JSONArray output = new JSONArray(); result.put("values", output);
        if (!value.present || value.failed || value.value == null) return result;
        if (!(value.value instanceof CharSequence[])) { result.put("unsupportedValueType", value.value.getClass().getSimpleName()); incomplete = true; observedCountsComplete = false; return result; }
        CharSequence[] lines = (CharSequence[]) value.value; int inspected = Math.min(lines.length, MAX_INSPECTED_ITEMS);
        for (int index = 0; index < inspected; index++) {
            if (index < MAX_LINES) output.put(PhoneEventJournal.object("index", index, "text", text(new Observed(true, lines[index], false), Notification.EXTRA_TEXT_LINES + "[" + index + "]")));
            else observeOnly(lines[index]);
        }
        arrayCounts(result, lines.length, inspected, output.length()); return result;
    }
    private JSONObject messages(Observed value, String sourceKey) throws JSONException {
        JSONObject result = arrayState(value, sourceKey); JSONArray output = new JSONArray(); result.put("values", output);
        if (!value.present || value.failed || value.value == null) return result;
        if (!(value.value instanceof Parcelable[])) { result.put("unsupportedValueType", value.value.getClass().getSimpleName()); incomplete = true; observedCountsComplete = false; return result; }
        Parcelable[] messages = (Parcelable[]) value.value; int inspected = Math.min(messages.length, MAX_INSPECTED_ITEMS);
        for (int index = 0; index < inspected; index++) {
            if (!(messages[index] instanceof Bundle)) { incomplete = true; observedCountsComplete = false; if (retainedMessages < MAX_MESSAGES) { output.put(PhoneEventJournal.object("index", index, "messageBundleReadable", false)); retainedMessages++; } continue; }
            Bundle message = (Bundle) messages[index]; Observed body = read(message, "text"), sender = read(message, "sender"), person = read(message, "sender_person"), time = read(message, "time");
            Observed personName = new Observed(false, null, false);
            if (person.failed) personName = person;
            else if (Build.VERSION.SDK_INT >= 28 && person.value instanceof Person) personName = new Observed(true, ((Person) person.value).getName(), false);
            else if (person.present && person.value != null) { incomplete = true; personName = new Observed(false, null, true); }
            if (retainedMessages >= MAX_MESSAGES) { observeOnly(body); observeOnly(sender); observeOnly(personName); continue; }
            String prefix = sourceKey + "[" + index + "].";
            JSONObject entry = PhoneEventJournal.object("index", index, "messageBundleReadable", true,
                "text", text(body, prefix + "text"), "sender", text(sender, prefix + "sender"),
                "senderPersonName", text(personName, prefix + "sender_person.name"), "senderPersonPresent", person.failed ? JSONObject.NULL : person.present,
                "timePresent", time.failed ? JSONObject.NULL : time.present, "time", time.value instanceof Long ? time.value : JSONObject.NULL,
                "timeReadable", !time.failed && time.value instanceof Long, "nonTextMessageDataCaptured", false);
            if (time.failed || time.present && time.value != null && !(time.value instanceof Long)) { entry.put("timeUnavailableOrWrongType", true); incomplete = true; }
            output.put(entry); retainedMessages++;
        }
        arrayCounts(result, messages.length, inspected, output.length()); return result;
    }
    private JSONObject arrayState(Observed value, String sourceKey) {
        if (value.failed) { incomplete = true; observedCountsComplete = false; }
        return PhoneEventJournal.object("sourceKey", sourceKey, "present", value.failed ? JSONObject.NULL : value.present, "readFailed", value.failed, "values", new JSONArray());
    }
    private void arrayCounts(JSONObject result, int total, int inspected, int retained) throws JSONException {
        result.put("observedEntryCount", total).put("inspectedEntryCount", inspected).put("retainedEntryCount", retained).put("omittedEntryCount", total - retained).put("originalTruncated", retained < total);
        if (retained < total) { truncated = true; reasons.add("known_text_array_entry_budget"); }
        if (inspected < total) { observedCountsComplete = false; incomplete = true; reasons.add("array_inspection_budget"); }
    }
    private void observeOnly(Observed value) { if (value.failed) { observedCountsComplete = false; incomplete = true; } else observeOnly(value.value); }
    private void observeOnly(Object value) {
        if (value instanceof CharSequence) { try { observedUnits += value.toString().length(); } catch (RuntimeException unavailable) { observedCountsComplete = false; incomplete = true; } }
        else if (value != null) { observedCountsComplete = false; incomplete = true; }
    }
    private static final class Observed {
        final boolean present, failed; final Object value;
        Observed(boolean present, Object value, boolean failed) { this.present = present; this.value = value; this.failed = failed; }
    }
    private static Observed read(Bundle bundle, String key) {
        if (bundle == null) return new Observed(false, null, false);
        try { return new Observed(bundle.containsKey(key), bundle.get(key), false); }
        catch (RuntimeException unreadable) { return new Observed(false, null, true); }
    }
    private static int safePrefix(String text, int length) { return length > 0 && length < text.length() && Character.isHighSurrogate(text.charAt(length - 1)) && Character.isLowSurrogate(text.charAt(length)) ? length - 1 : length; }
    private static int quotedCost(char value) { return value < 32 && value != '\n' && value != '\r' && value != '\t' && value != '\b' && value != '\f' || value == '\u2028' || value == '\u2029' ? 6 : value < 32 || value == '\\' || value == '"' || value == '/' ? 2 : 1; }
    private static int quotedLength(String text, int length) { int count = 0; for (int index = 0; index < length; index++) count += quotedCost(text.charAt(index)); return count; }
    private static boolean malformedUtf16(String text) { for (int index = 0; index < text.length(); index++) { char value = text.charAt(index); if (Character.isHighSurrogate(value)) { if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++index))) return true; } else if (Character.isLowSurrogate(value)) return true; } return false; }
    private static byte[] utf16Bytes(String text) { byte[] bytes = new byte[text.length() * 2]; for (int index = 0; index < text.length(); index++) { bytes[index * 2] = (byte) (text.charAt(index) >>> 8); bytes[index * 2 + 1] = (byte) text.charAt(index); } return bytes; }
    private static String hashText(String text) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[8192]; int used = 0;
            for (int index = 0; index < text.length(); index++) { char value = text.charAt(index); buffer[used++] = (byte) (value >>> 8); buffer[used++] = (byte) value; if (used == buffer.length) { hash.update(buffer); used = 0; } }
            hash.update(buffer, 0, used); StringBuilder output = new StringBuilder(); for (byte value : hash.digest()) output.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16)); return output.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
