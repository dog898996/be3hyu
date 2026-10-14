package com.hoyoung.assistant.phone;

import android.content.Context;
import com.hoyoung.assistant.runtime.VoiceCaptureService;
import org.json.JSONException;
import org.json.JSONObject;

/** Explicit microphone requests; service acceptance and saved audio are separate proofs. */
public final class PhoneVoiceRecorder {
    private final Context context;
    private final PhoneEventJournal journal;
    public PhoneVoiceRecorder(Context context, PhoneEventJournal journal) {
        this.context = context.getApplicationContext(); this.journal = journal;
    }
    public JSONObject start(JSONObject args) throws Exception {
        return VoiceCaptureService.requestStart(context, journal, duration(args));
    }
    public JSONObject stop(JSONObject args) throws JSONException {
        return VoiceCaptureService.requestStop(journal, requestId(args));
    }
    public JSONObject read(JSONObject args) throws JSONException {
        return VoiceCaptureService.readReceipt(journal, requestId(args));
    }
    public void finish(String reason) { VoiceCaptureService.stopActive(reason); }
    /** Zero means stop explicitly; a positive requested duration has no application cap. */
    static long duration(JSONObject args) {
        if (args == null) throw new IllegalArgumentException("Arguments are required");
        if (!args.has("durationMs")) return 10_000L;
        Object value = args.opt("durationMs");
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))
            throw new IllegalArgumentException("durationMs must be a nonnegative integer");
        long duration = ((Number) value).longValue();
        if (duration < 0) throw new IllegalArgumentException("durationMs must be a nonnegative integer");
        return duration;
    }
    private static String requestId(JSONObject args) throws JSONException {
        if (args == null || !(args.opt("requestId") instanceof String)) throw new IllegalArgumentException("requestId must be a string");
        String value = args.getString("requestId");
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Unknown recording request identity");
        return value;
    }
}
