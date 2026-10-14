package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONArray;
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
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Local transport checks for prompt reuse and request-scoped routing changes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class AssistantRuntimeRoutingStabilityRegressionTest {
    private Context context;
    private HoyoungStore store;
    private AssistantRuntime runtime;
    private final List<String> prompts = new ArrayList<>();

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
        store = new HoyoungStore(context);
        store.putSetting("customInstructions", "Preserve source evidence. ROUTING_STABLE_PREFIX_6389");
        store.putSetting("memoryReviewEnabled", "false");
        store.putSetting("autonomy.enabled", "false");
        runtime = new AssistantRuntime(context, store, new InternalFiles(context),
                new PhoneTools(context, store), Runnable::run);
        runtime.attachTransport(new AssistantRuntime.Transport() {
            public void inspect() {}
            public void send(String id, String prompt) { prompts.add(prompt); }
            public void openTemporary() {}
            public void cancel() { fail("Changing the foreground session must not cancel Work"); }
        });
        drain();
    }

    @After public void cleanup() throws Exception {
        drain();
        java.lang.reflect.Field ledger = AssistantRuntime.class.getDeclaredField("usageBudget");
        ledger.setAccessible(true);
        ((UsageBudgetLedger) ledger.get(runtime)).close();
        store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
    }

    @Test public void sessionExitReentryAndSelectionKeepInstructionsAndCatalogPrefixStable() {
        runtime.enterForegroundRouting();
        String firstSession = runtime.foregroundRoutingState().optString("sessionId");
        JSONObject first = send("First request", "medium");
        String prefix = prefix(prompts.get(0));
        assertTrue(prefix.contains("ROUTING_STABLE_PREFIX_6389"));
        assertTrue(prefix.contains("app.routing.session.set"));
        assertFalse(prefix.contains(firstSession));
        assertTrue(suffix(prompts.get(0)).contains(firstSession));
        finish(first, "First reply");

        runtime.leaveForegroundRouting();
        runtime.enterForegroundRouting();
        String nextSession = runtime.foregroundRoutingState().optString("sessionId");
        assertNotEquals(firstSession, nextSession);
        JSONObject second = send("Second request", "medium");
        assertEquals(prefix, prefix(prompts.get(1)));
        assertTrue(suffix(prompts.get(1)).contains(nextSession));
        finish(second, "Second reply");

        selectXhigh();
        send("Third request", "xhigh");
        assertEquals(prefix, prefix(prompts.get(2)));
        assertTrue(suffix(prompts.get(2)).contains(runtime.foregroundRoutingState().toString()));
    }

    @Test public void permanentRoutingEditDoesNotDiscardAnAcceptedExplicitXhighReply() throws Exception {
        runtime.enterForegroundRouting();
        selectXhigh();
        JSONObject request = send("Keep working with the accepted choice", "xhigh");
        accept(request);
        assertTrue(runtime.updatePersonalSettings(json("requiredModel", "gpt-6-sol",
                "requiredEffort", "high", "requiredSpeed", "standard"), null).optBoolean("ok"));
        JSONObject route = runtime.requiredModelPreferences(request.optString("external_id"));
        assertEquals("gpt-6.1-sol", route.optString("model"));
        assertEquals("xhigh", route.optString("effort"));
        assertEquals("fast", route.optString("speed"));

        finish(request, "ACCEPTED_XHIGH_REPLY_5281");
        assertTrue(hasAssistantReply(request, "ACCEPTED_XHIGH_REPLY_5281"));
        assertEquals(1, prompts.size());
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
    }

    @Test public void currentInstructionCorrectionStillHoldsAnAcceptedReply() throws Exception {
        runtime.enterForegroundRouting();
        selectXhigh();
        JSONObject request = send("Work under the current instructions", "xhigh");
        accept(request);
        runtime.updatePersonalSettings(json("customInstructions", "CORRECTED_OWNER_INSTRUCTIONS_8247"), null);

        finish(request, "OBSOLETE_INSTRUCTION_REPLY_7512");
        assertFalse(hasAssistantReply(request, "OBSOLETE_INSTRUCTION_REPLY_7512"));
        assertEquals("reply_context_changed", runtime.state().optString("lastCode"));
        assertEquals(1, prompts.size());
    }

    @Test public void acceptedDefaultRouteSurvivesUiChangesBeforeItsContinuationDispatches() throws Exception {
        JSONObject request = send("Continue using the model that accepted this task", "medium");
        accept(request);
        observe("gpt-6.1-sol", "medium", true);
        finishAction(request, "app.settings.get", new JSONObject());
        JSONObject continuation = store.nextQueuedRequest();
        assertNotNull(continuation);
        assertEquals("tool-continuation", continuation.optString("kind"));
        assertEquals(1, prompts.size());

        runtime.updatePersonalSettings(json("requiredModel", "gpt-6-sol", "requiredEffort", "xhigh"), null);
        observe("gpt-6-sol", "xhigh", false);
        assertEquals("An accepted task must wait for its own model selection", 1, prompts.size());
        JSONObject frozen = runtime.requiredModelPreferences();
        assertEquals("gpt-6.1-sol", frozen.optString("model"));
        assertEquals("medium", frozen.optString("effort"));
        assertEquals("fast", frozen.optString("speed"));

        observe("gpt-6.1-sol", "medium", false);
        assertEquals(2, prompts.size());
        assertEquals("medium", runtime.requiredModelPreferences(continuation.optString("external_id")).optString("effort"));
        finish(continuation, "The original task is complete");
        assertTrue(hasAssistantReply(continuation, "The original task is complete"));
        assertEquals("gpt-6-sol", runtime.requiredModelPreferences().optString("model"));
        assertEquals("xhigh", runtime.requiredModelPreferences().optString("effort"));
    }

    @Test public void explicitPermanentChatChangeUpdatesItsOwnDefaultRouteForContinuation() {
        JSONObject request = send("Use XHigh from now on and finish this task", "medium");
        accept(request);
        finishAction(request, "app.settings.update", json("requiredEffort", "xhigh"));
        assertEquals("xhigh", store.getSetting("requiredEffort", ""));
        assertEquals("xhigh", runtime.requiredModelPreferences().optString("effort"));
        assertEquals(1, prompts.size());

        observe("gpt-6.1-sol", "xhigh", false);
        assertEquals(2, prompts.size());
        JSONObject continuation = store.getRequest(runtime.state().optLong("activeStoreId"));
        assertNotNull(continuation);
        assertEquals("tool-continuation", continuation.optString("kind"));
        assertEquals("xhigh", runtime.requiredModelPreferences(continuation.optString("external_id")).optString("effort"));
        assertEquals("fast", runtime.requiredModelPreferences(continuation.optString("external_id")).optString("speed"));
    }

    @Test public void newerOwnerRunsFirstWhileOldOutputAndXhighRouteStayBoundToOriginalTask() throws Exception {
        runtime.enterForegroundRouting();
        selectXhigh();
        JSONObject original = send("Review my settings before changing them", "xhigh");
        accept(original);
        runtime.leaveForegroundRouting();
        runtime.enterForegroundRouting();
        JSONObject newer = runtime.receive("Keep the settings unchanged and answer this new message first", new JSONArray());
        JSONObject oldOutput = json("protocol", "hoyoung.v1", "requestId", original.optString("external_id"),
                "status", "working", "message", "OLD_PROVIDER_PROPOSAL_4397", "actions", new JSONArray().put(
                        json("id", "old-settings-change", "name", "app.settings.update", "arguments",
                                json("standingGoals", "STALE_SETTINGS_EFFECT_7391"))));
        complete(original, oldOutput);
        assertEquals("", store.getSetting("standingGoals", ""));
        assertNull(store.getSetting("actionReceipt:" + original.optString("external_id") + ":old-settings-change", null));
        assertFalse(hasAssistantReply(original, "OLD_PROVIDER_PROPOSAL_4397"));

        observe("gpt-6.1-sol", "medium", false);
        assertEquals(2, prompts.size());
        assertEquals(newer.optString("external_id"), runtime.state().optString("activeRequestId"));
        assertEquals("medium", runtime.requiredModelPreferences(newer.optString("external_id")).optString("effort"));
        JSONObject continuation = store.nextQueuedRequest();
        assertNotNull(continuation);
        assertEquals(original.optString("external_id"), store.getSetting("requestRoot:" + continuation.optString("external_id"), ""));

        JSONObject observation = rebaseObservation(original);
        assertNotNull(observation);
        JSONObject observed = new JSONObject(observation.optString("text"));
        assertEquals(oldOutput.toString(), observed.optString("observedProviderOutput"));
        assertFalse(observed.optBoolean("nativeActionsExecuted", true));
        JSONObject provenance = new JSONObject(store.getSetting("answerSources:" + observation.optLong("id"), "{}"));
        assertFalse(provenance.optString("memoryFingerprint").isEmpty());
        long sourceId = Long.parseLong(store.getSetting("requestSource:" + original.optString("external_id"), "0"));
        JSONObject source = store.getMessage(sourceId);
        JSONArray sources = provenance.getJSONArray("sources");
        boolean originalBound = false;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject ref = sources.getJSONObject(i);
            if ("message".equals(ref.optString("type")) && ref.optLong("id") == sourceId
                    && source.optString("content_hash").equals(ref.optString("hash"))) originalBound = true;
        }
        assertTrue("Observed output must retain its canonical source evidence", originalBound);
        assertTrue(continuation.optString("text").contains(observation.optString("content_hash")));

        finish(newer, "The new message was handled first");
        observe("gpt-6.1-sol", "xhigh", false);
        assertEquals(3, prompts.size());
        assertEquals(continuation.optString("external_id"), runtime.state().optString("activeRequestId"));
        assertEquals("xhigh", runtime.requiredModelPreferences(continuation.optString("external_id")).optString("effort"));
        assertEquals("fast", runtime.requiredModelPreferences(continuation.optString("external_id")).optString("speed"));
        assertTrue(prompts.get(2).contains(observation.optString("content_hash")));
        assertTrue(prompts.get(2).contains(newer.optString("text")));
        assertEquals("", store.getSetting("standingGoals", ""));
        assertNull(store.getSetting("actionReceipt:" + original.optString("external_id") + ":old-settings-change", null));
    }

    @Test public void sourceExclusionStillHoldsOldOutputInsteadOfRebasingItAfterNewOwnerInput() throws Exception {
        JSONObject original = send("Use this original only while its source is allowed", "medium");
        accept(original);
        JSONObject newer = runtime.receive("Handle this newer independent request", new JSONArray());
        long sourceId = Long.parseLong(store.getSetting("requestSource:" + original.optString("external_id"), "0"));
        JSONObject source = store.getMessage(sourceId);
        store.getPrivacyLedger().excludeSource("message", sourceId, source.optString("content_hash"),
                "retrieval_exclude", "Owner excluded the original source");
        finishAction(original, "app.settings.update", json("standingGoals", "EXCLUDED_OLD_EFFECT_8294"));

        assertEquals("", store.getSetting("standingGoals", ""));
        assertNull(store.getSetting("actionReceipt:" + original.optString("external_id") + ":routing-check", null));
        assertNull(rebaseObservation(original));
        assertEquals(2, store.listRequests(20).length());
        assertEquals(newer.optString("external_id"), runtime.state().optString("activeRequestId"));
        assertTrue(store.events(30).toString().contains("work_output_held"));
        assertTrue(store.events(30).toString().contains("memory_privacy_changed"));
    }

    private JSONObject send(String text, String effort) {
        JSONObject request = runtime.receive(text, new JSONArray());
        observe("gpt-6.1-sol", effort, false);
        return request;
    }

    private void observe(String model, String effort, boolean busy) {
        runtime.onWebEvent(json("type", "state", "code", "ready", "state", json(
                "authenticated", true, "workSelected", true, "temporary", true,
                "model", model, "effort", effort, "speed", "fast", "busy", busy, "quota", false)));
        drain();
    }

    private void selectXhigh() {
        JSONObject current = runtime.foregroundRoutingState();
        assertTrue(runtime.selectForegroundRouting(current.optString("sessionId"), current.optLong("revision"),
                "gpt-6.1-sol", "xhigh", false).optBoolean("ok"));
    }

    private void accept(JSONObject request) {
        runtime.onWebEvent(json("type", "accepted", "requestId", request.optString("external_id")));
        drain();
        assertEquals("accepted", store.getRequest(request.optLong("id")).optString("status"));
    }

    private void finish(JSONObject request, String message) {
        complete(request, json("protocol", "hoyoung.v1", "requestId", request.optString("external_id"),
                "status", "completed", "message", message));
    }

    private void finishAction(JSONObject request, String name, JSONObject arguments) {
        complete(request, json("protocol", "hoyoung.v1", "requestId", request.optString("external_id"),
                "status", "working", "message", "", "actions", new JSONArray().put(
                        json("id", "routing-check", "name", name, "arguments", arguments))));
    }

    private void complete(JSONObject request, JSONObject envelope) {
        runtime.onWebEvent(json("type", "completed", "requestId", request.optString("external_id"),
                "text", envelope.toString(), "rawEnvelopeEligible", true));
        drain();
    }

    private boolean hasAssistantReply(JSONObject request, String text) {
        JSONArray messages = store.boundedChatMessagePage(0, 40, 8000).optJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            if ("assistant".equals(row.optString("role")) && request.optString("external_id").equals(row.optString("request_id"))
                    && text.equals(row.optString("text"))) return true;
        }
        return false;
    }

    private JSONObject rebaseObservation(JSONObject request) throws Exception {
        JSONArray messages = store.listMessages(100);
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.getJSONObject(i);
            if (!"system".equals(row.optString("role")) || !row.optString("text").startsWith("{")) continue;
            JSONObject content = new JSONObject(row.optString("text"));
            if ("owner_input_changed_during_inference".equals(content.optString("type"))
                    && request.optString("external_id").equals(content.optString("previousRequestId"))) return row;
        }
        return null;
    }

    private static String prefix(String prompt) {
        int boundary = prompt.indexOf("\nREQUEST_SCOPE:");
        assertTrue(boundary > 0);
        return prompt.substring(0, boundary);
    }

    private static String suffix(String prompt) { return prompt.substring(prompt.indexOf("\nREQUEST_SCOPE:")); }
    private static void drain() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static JSONObject json(Object... fields) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); }
        catch (Exception invalid) { throw new AssertionError(invalid); }
        return result;
    }
}
