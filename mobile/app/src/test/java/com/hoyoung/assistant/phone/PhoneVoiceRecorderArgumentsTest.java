package com.hoyoung.assistant.phone;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneVoiceRecorderArgumentsTest {
    @Test public void manualStopAndLongDurationsReplaceTheFormerThirtySecondLimit() throws Exception {
        assertEquals(10_000, PhoneVoiceRecorder.duration(new JSONObject()));
        assertEquals(0, PhoneVoiceRecorder.duration(new JSONObject().put("durationMs", 0)));
        assertEquals(1, PhoneVoiceRecorder.duration(new JSONObject().put("durationMs", 1)));
        assertEquals(3_600_000, PhoneVoiceRecorder.duration(new JSONObject().put("durationMs", 3_600_000)));
        assertEquals(Long.MAX_VALUE, PhoneVoiceRecorder.duration(new JSONObject().put("durationMs", Long.MAX_VALUE)));
    }
    @Test public void MalformedValuesCannotBeSilentlyCoercedToAudioCommands() throws Exception {
        for (Object value : new Object[]{"10000", -1, 1.5, true, JSONObject.NULL, new JSONObject()}) {
            JSONObject arguments = new JSONObject().put("durationMs", value);
            assertThrows(IllegalArgumentException.class, () -> PhoneVoiceRecorder.duration(arguments));
        }
        assertThrows(IllegalArgumentException.class, () -> PhoneVoiceRecorder.duration(null));
    }
}
