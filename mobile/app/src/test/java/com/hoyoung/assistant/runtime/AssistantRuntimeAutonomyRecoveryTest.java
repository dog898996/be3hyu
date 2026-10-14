package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** A quota interruption must not permanently occupy the single periodic judgment slot. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AssistantRuntimeAutonomyRecoveryTest {
    private Context context;
    private HoyoungStore store;
    private AssistantRuntime runtime;
    private AtomicLong clock;
    private final List<String> sent = new ArrayList<>();

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
        store = new HoyoungStore(context);
        store.putSetting("memoryReviewEnabled", "false");
        store.putSetting("heartbeatSeconds", "300");
        store.putSetting("autonomy.enabled", "false");
        clock = new AtomicLong(System.currentTimeMillis());
        runtime = new AssistantRuntime(context, store, new InternalFiles(context),
                new PhoneTools(context, store), Runnable::run, clock::get);
        runtime.attachTransport(new AssistantRuntime.Transport() {
            public void inspect() {}
            public void send(String id, String prompt) { sent.add(id); }
            public void openTemporary() {}
            public void cancel() {}
        });
        drain();
    }

    @After public void after() throws Exception {
        drain();
        java.lang.reflect.Field field = AssistantRuntime.class.getDeclaredField("usageBudget");
        field.setAccessible(true);
        ((UsageBudgetLedger) field.get(runtime)).close();
        store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
    }

    @Test public void quotaBeforeSendThenSuccessfulOwnerRetryAllowsNextPeriodicJudgment() {
        // The idle composer is Fast, so the automatic Medium/Standard request is still unsent.
        ready("fast");
        store.putSetting("autonomy.enabled", "true");
        runtime.tick();
        drain();
        JSONObject interrupted = store.getRequest(runtime.state().optLong("activeStoreId"));
        assertNotNull(interrupted);
        assertEquals("preparing", runtime.state().optString("activePhase"));
        assertEquals(0, sent.size());
        assertEquals(interrupted.optString("external_id"), tickState().optString("inFlightTickId"));

        runtime.onWebEvent(object("type", "quota", "requestId", interrupted.optString("external_id"),
                "code", "quota_exhausted", "knownNotSent", true));
        assertEquals("quota", store.getRequest(interrupted.optLong("id")).optString("status"));
        assertTrue(runtime.state().optBoolean("quotaBlocked"));
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));

        JSONObject retry = runtime.retryRequest(interrupted.optLong("id"));
        ready("standard");
        assertEquals(1, sent.size());
        assertEquals(retry.optString("external_id"), sent.get(0));
        runtime.onWebEvent(object("type", "accepted", "requestId", retry.optString("external_id")));
        JSONObject idle = object("protocol", "hoyoung.v1", "requestId", retry.optString("external_id"),
                "status", "idle", "message", "");
        runtime.onWebEvent(object("type", "completed", "requestId", retry.optString("external_id"),
                "text", idle.toString(), "rawEnvelopeEligible", true));
        drain();
        assertEquals("completed", store.getRequest(retry.optLong("id")).optString("status"));
        assertEquals("", tickState().optString("inFlightTickId"));

        clock.addAndGet(300_000L);
        ready("standard");
        assertEquals(2, sent.size());
        assertNotEquals(retry.optString("external_id"), sent.get(1));
        assertEquals("autonomy", store.getRequest(runtime.state().optLong("activeStoreId")).optString("kind"));
    }

    private JSONObject tickState() { return runtime.getAutonomyTickPolicy().snapshot(clock.get()); }
    private void ready(String speed) {
        runtime.onWebEvent(object("type", "state", "code", "ready", "state", object(
                "authenticated", true, "workSelected", true, "temporary", true,
                "model", "gpt-6.1-sol", "effort", "medium", "speed", speed, "busy", false, "quota", false)));
        drain();
    }
    private static void drain() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject();
        try { for (int i=0; i<pairs.length; i+=2) value.put((String) pairs[i], pairs[i+1]); }
        catch (Exception invalid) { throw new AssertionError(invalid); }
        return value;
    }
}
