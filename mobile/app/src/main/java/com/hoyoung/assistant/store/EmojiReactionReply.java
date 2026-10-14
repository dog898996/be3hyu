package com.hoyoung.assistant.store;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A reaction is an immutable assistant reply, with a native receipt, not a read receipt. */
public final class EmojiReactionReply {
    private EmojiReactionReply() { }
    private static final String PREFIX = "[hoyoung.reaction.v1]\n";
    private static final String MEANING = "호영이 이 사용자 메시지에 문장 대신 이모지로 응답했다. 무응답이나 읽씹이 아니다.";
    private static final Set<String> EMOJI = new HashSet<>(Arrays.asList(
            "💪", "👍", "❤️", "❤", "🫶", "🔥", "👏", "🙌", "😂", "🤣", "😄", "😊", "😎", "🥰", "😍",
            "🎉", "🥳", "✨", "✅", "👌", "🙏", "👀", "😴", "🌙", "☕", "😢", "😮", "🥲", "🤝", "🫡",
            "💙", "💜", "💛", "🤍", "💚", "🧡", "🩷", "🤗", "🌟", "💯", "🍀", "🏃", "🏋️", "🙆", "🙋"));
    // A conservative veto, not a second model/classifier. Ambiguous semantics still
    // belong to the existing owner reply turn; the prompt requires a normal answer.
    private static final Pattern NEEDS_WORDS = Pattern.compile(
            "[?？]|(?:해\\s*줘|해줘|해주세요|해줄|해볼|해야|하자|하라고|해라|해봐|알려|설명|추천|부탁|도와|기억해|잊어|삭제|정정|취소|중지|수정|바꿔|계속|언제|어떻게|어디|왜|뭐야|뭔|맞아|괜찮아|가능해|할까|인가|인거|거야|거니|없어|못해|안돼|안되|힘들|죽|자해|아파|응급|위급|불안|살려|위험)|"
            + "\\b(?:what|why|when|where|who|how|can|could|would|should|please|help|remember|forget|delete|correct|stop|cancel|urgent|emergency|suicid\\w*|hurt|pain)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static boolean casualCandidate(String text) {
        return text != null && !text.trim().isEmpty() && text.length() <= 500
                && !text.contains("\n") && !NEEDS_WORDS.matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    public static boolean validEmoji(String emoji) {
        if (emoji == null) return false;
        if (EMOJI.contains(emoji)) return true;
        // Skin tones are allowed only on these single-person gestures.
        int[] points = emoji.codePoints().toArray();
        return points.length == 2 && points[1] >= 0x1f3fb && points[1] <= 0x1f3ff
                && Arrays.asList("💪", "👍", "👏", "🙌", "👌", "🙏", "🫶", "🙆", "🙋").contains(new String(Character.toChars(points[0])));
    }

    /** Shape and semantic vetoes; request/freshness/privacy checks are native Runtime responsibilities. */
    public static JSONObject proposal(JSONObject envelope, JSONObject target) {
        if (envelope == null || target == null || !"user".equals(target.optString("role"))
                || !"completed".equals(envelope.optString("status")) || !"".equals(envelope.opt("message"))
                || !casualCandidate(target.optString("text"))) return null;
        JSONArray actions = envelope.optJSONArray("actions");
        if (envelope.has("actions") && (actions == null || actions.length() > 0)) return null;
        if (envelope.has("nextCheckAt")) return null;
        JSONObject reaction = envelope.optJSONObject("reaction");
        if (reaction == null || reaction.length() != 2 || !(reaction.opt("emoji") instanceof String)
                || !validEmoji(reaction.optString("emoji")) || !positiveInteger(reaction.opt("targetMessageId"))
                || reaction.optLong("targetMessageId") != target.optLong("id")) return null;
        String hash = target.optString("content_hash");
        if (!hash.matches("[a-f0-9]{64}")) return null;
        return object("emoji", reaction.optString("emoji"), "targetMessageId", target.optLong("id"), "targetRecordHash", hash,
                "targetText", target.optString("text"));
    }

    public static String originalText(JSONObject reaction) {
        if (!validDescriptor(reaction)) throw new IllegalArgumentException("An exact native reaction descriptor is required");
        return PREFIX + object("emoji", reaction.optString("emoji"), "targetMessageId", reaction.optLong("targetMessageId"),
                "targetRecordHash", reaction.optString("targetRecordHash"), "targetText", reaction.optString("targetText"), "meaning", MEANING).toString();
    }

    /** Add UI projection only after checking the independent native provenance receipt. */
    public static void decorate(JSONObject row, String nativeProvenance) {
        if (row == null) return;
        row.remove("reaction");
        if (!"assistant".equals(row.optString("role")) || !row.optString("text").startsWith(PREFIX)
                || row.optBoolean("textIsExcerpt") || nativeProvenance == null) return;
        try {
            JSONObject receipt = new JSONObject(nativeProvenance), reaction = receipt.optJSONObject("reaction");
            if (receipt.optInt("version") != 1 || !validDescriptor(reaction) || !originalText(reaction).equals(row.optString("text"))
                    || !SourceRecordVisibility.canonicalRecordMatches("message", row)) return;
            JSONArray refs = receipt.optJSONArray("sources"); boolean linked = false;
            for (int i = 0; refs != null && i < refs.length(); i++) {
                JSONObject ref = refs.optJSONObject(i);
                if (ref != null && "message".equals(ref.optString("type")) && ref.optLong("id") == reaction.optLong("targetMessageId")
                        && reaction.optString("targetRecordHash").equals(ref.optString("hash"))) linked = true;
            }
            if (linked) row.put("reaction", new JSONObject(reaction.toString()));
        } catch (JSONException | IllegalArgumentException invalid) { /* Ordinary text remains visible, never a fabricated reaction. */ }
    }

    /** Available on boundedChatMessagePage rows. Ordinary model-written text cannot create this projection. */
    public static JSONObject fromStoredMessage(JSONObject row) {
        if (row == null || !"assistant".equals(row.optString("role"))) return null;
        JSONObject reaction = row.optJSONObject("reaction");
        if (!validDescriptor(reaction) || !originalText(reaction).equals(row.optString("text"))) return null;
        return object("emoji", reaction.optString("emoji"), "targetMessageId", reaction.optLong("targetMessageId"),
                "targetRecordHash", reaction.optString("targetRecordHash"), "targetText", reaction.optString("targetText"));
    }

    private static boolean validDescriptor(JSONObject value) {
        return value != null && value.length() == 4 && value.opt("emoji") instanceof String && validEmoji(value.optString("emoji"))
                && positiveInteger(value.opt("targetMessageId")) && value.opt("targetRecordHash") instanceof String
                && value.optString("targetRecordHash").matches("[a-f0-9]{64}")
                && value.opt("targetText") instanceof String && !value.optString("targetText").trim().isEmpty() && value.optString("targetText").length() <= 500;
    }
    private static boolean positiveInteger(Object value) {
        return value instanceof Number && ((Number) value).longValue() > 0 && ((Number) value).doubleValue() == ((Number) value).longValue();
    }
    private static JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i+1]); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return value;
    }
}
