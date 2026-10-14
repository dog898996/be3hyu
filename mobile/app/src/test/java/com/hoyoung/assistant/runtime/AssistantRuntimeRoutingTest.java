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
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Native routing/queue/source integration. No account login, real model or paid API is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AssistantRuntimeRoutingTest {
    private Context context;
    private HoyoungStore store;
    private AssistantRuntime runtime;
    private Transport transport;
    private AtomicLong clock;

    @Before public void before() {
        context=RuntimeEnvironment.getApplication();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
        store=new HoyoungStore(context);
        store.putSetting("customInstructions","새 사실은 근거를 확인하고 정정·삭제를 지켜. 고유지침 ROUTING_OWNER_SENTINEL");
        store.putSetting("memoryReviewEnabled","false");
        store.putSetting("usageOptimizationEnabled","true");
        store.putSetting("heartbeatSeconds","300");
        store.putSetting("autonomy.enabled","false");
        clock=new AtomicLong(System.currentTimeMillis());
        runtime=new AssistantRuntime(context,store,new InternalFiles(context),new PhoneTools(context,store),Runnable::run,clock::get);
        transport=new Transport();runtime.attachTransport(transport);drain();
    }
    @After public void after() throws Exception {
        drain();
        java.lang.reflect.Field field=AssistantRuntime.class.getDeclaredField("usageBudget");field.setAccessible(true);
        ((UsageBudgetLedger)field.get(runtime)).close();
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
    }

    @Test public void ownerUsesFastDirectlyAndPreservesConfiguredHigherEffort() {
        store.putSetting("requiredSpeed","standard");store.putSetting("requiredEffort","high");
        JSONObject request=runtime.receive("복잡한 계획을 자세히 검토해 줘",new JSONArray());
        ready("high","standard");assertEquals(0,transport.ids.size());
        assertEquals("fast",runtime.requiredModelPreferences().optString("speed"));
        ready("high","fast");assertEquals(1,transport.ids.size());
        assertEquals(request.optString("external_id"),transport.ids.get(0));
        assertEquals("high",runtime.requiredModelPreferences(request.optString("external_id")).optString("effort"));
    }

    @Test public void lightBackgroundWaitsForActualStandardSelectionWithoutSkippingTheJudgment() throws Exception {
        ready("medium","fast");enable();
        assertEquals(0,transport.ids.size());assertEquals("preparing",runtime.state().optString("activePhase"));
        assertEquals("low",runtime.requiredModelPreferences().optString("effort"));
        assertEquals("standard",runtime.requiredModelPreferences().optString("speed"));
        ready("low","fast");assertEquals(0,transport.ids.size());
        ready("low","standard");assertEquals(1,transport.ids.size());
        String prompt=transport.prompts.get(0);
        assertTrue(prompt.contains("ROUTING_OWNER_SENTINEL"));assertTrue(prompt.contains("Light 판단 전용"));
        String marker="도구 이름 전체 목록(상세 인수는 Medium 이상 실행 턴에서 제공):\n";
        int start=prompt.indexOf(marker)+marker.length();assertTrue(start>=marker.length());
        JSONArray names=new JSONArray(prompt.substring(start,prompt.indexOf('\n',start)));
        java.lang.reflect.Method method=AssistantRuntime.class.getDeclaredMethod("catalog");method.setAccessible(true);
        JSONArray full=(JSONArray)method.invoke(runtime);assertEquals(full.length(),names.length());
        for(int i=0;i<full.length();i++)assertEquals(full.getJSONObject(i).optString("name"),names.optString(i));
    }

    @Test public void explicitNoActionFinishesOneActualJudgmentAndNextFiveMinuteJudgmentStillRuns() {
        ready("low","standard");enable();assertEquals(1,transport.ids.size());
        JSONObject first=active();accept(first);finish(first,noAction(first));
        assertEquals("no_action",runtime.getAutonomyTickPolicy().snapshot(clock.get()).optString("lastOutcome"));
        assertEquals(0,store.messageCount());
        runtime.tick();drain();assertEquals(1,transport.ids.size());
        clock.addAndGet(300_000L);ready("low","standard");
        assertEquals(2,transport.ids.size());assertNotEquals(first.optString("external_id"),active().optString("external_id"));
    }

    @Test public void malformedOrUnboundLightDecisionPromotesInsteadOfInventingNoAction() {
        ready("low","standard");enable();JSONObject first=active();accept(first);
        finish(first,object("protocol","hoyoung.v1","requestId",first.optString("external_id"),"status","idle","message",""));
        assertEquals(1,transport.ids.size());assertEquals("medium",runtime.requiredModelPreferences().optString("effort"));
        assertNotEquals("no_action",runtime.getAutonomyTickPolicy().snapshot(clock.get()).optString("lastOutcome"));
        ready("medium","standard");assertEquals(2,transport.ids.size());
        JSONObject next=active();assertEquals(first.optString("external_id"),store.getSetting("requestRoot:"+next.optString("external_id"),""));
        assertEquals("autonomy",store.getSetting("requestPurpose:"+next.optString("external_id"),""));
    }

    @Test public void lightActionMemoryAndReviewProposalsHaveNoNativeEffectsBeforePromotion() {
        ready("low","standard");enable();JSONObject first=active();accept(first);
        JSONObject proposal=noAction(first);
        put(proposal,"actions",new JSONArray().put(object("id","do-not-execute","name","files.write","arguments",object("relativePath","routing-forbidden.txt","content","unsafe"))));
        put(proposal,"memories",new JSONArray().put(object("key","routing.forbidden.fact","content","invented","kind","fact","sourceMessageIds",new JSONArray())));
        put(proposal,"review",object("batchId","untrusted","noMemoryNeeded",true));
        finish(first,proposal);
        assertFalse(new java.io.File(context.getFilesDir(),"projects/routing-forbidden.txt").exists());
        assertEquals(0,store.searchMemories("routing.forbidden.fact",10).length());
        assertEquals(0,store.messageCount());assertEquals("medium",runtime.requiredModelPreferences().optString("effort"));
        assertEquals("",store.getSetting("actionReceipt:"+first.optString("external_id")+":do-not-execute",""));
        ready("medium","standard");assertEquals(2,transport.ids.size());
        assertEquals("medium",store.getSetting("routingMinimumEffort:"+first.optString("external_id"),""));
    }

    @Test public void explicitHighEscalationPreservesRootAndDoesNotStartProposedActions() {
        ready("low","standard");enable();JSONObject first=active();accept(first);
        JSONObject proposal=noAction(first);put(proposal,"routing",object("decision","ESCALATE_HIGH","reason","여러 제약과 기한을 동시에 계산해야 함"));
        finish(first,proposal);assertEquals("high",runtime.requiredModelPreferences().optString("effort"));
        ready("high","standard");assertEquals(2,transport.ids.size());
        JSONObject next=active();assertEquals(first.optString("external_id"),store.getSetting("requestRoot:"+next.optString("external_id"),""));
        assertEquals("high",store.getSetting("routingMinimumEffort:"+first.optString("external_id"),""));
    }

    @Test public void pendingMemoryBatchSelectsMediumBeforeDispatchAndKeepsItsOriginals() {
        store.putSetting("memoryReviewEnabled","true");
        JSONObject source=store.addMessage("user","내 고양이는 나비야. 이름이 바뀌면 최신 정정을 지켜.",null);
        ready("low","standard");enable();assertEquals(0,transport.ids.size());
        assertEquals("medium",runtime.requiredModelPreferences().optString("effort"));
        ready("medium","standard");assertEquals(1,transport.ids.size());
        JSONObject request=active();String batch=store.getSetting("memoryReview:"+request.optString("external_id"),"");
        assertFalse(batch.isEmpty());assertTrue(transport.prompts.get(0).contains("내 고양이는 나비야"));
        assertEquals("medium",runtime.requiredModelPreferences(request.optString("external_id")).optString("effort"));
        assertNotNull(store.getMessage(source.optLong("id")));
    }

    @Test public void routeSnapshotsCannotBeMutatedByBrowserWorkersOrLaterSettings() {
        JSONObject request=runtime.receive("기억을 기준으로 답해 줘",new JSONArray());ready("medium","fast");
        JSONObject detached=runtime.requiredModelPreferences(request.optString("external_id"));put(detached,"speed","standard");put(detached,"effort","low");
        store.putSetting("requiredEffort","ultra");store.putSetting("requiredSpeed","standard");
        JSONObject admitted=runtime.requiredModelPreferences(request.optString("external_id"));
        assertEquals("medium",admitted.optString("effort"));assertEquals("fast",admitted.optString("speed"));
    }

    @Test public void emptyLoadingStateDuringDispatchCannotDiscardAcceptanceOrCrashAccounting() {
        JSONObject request=runtime.receive("계속 처리해",new JSONArray());ready("medium","fast");assertEquals(1,transport.ids.size());
        runtime.onWebEvent(object("type","state","state",new JSONObject()));
        accept(request);assertEquals("accepted",store.getRequest(request.optLong("id")).optString("status"));
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
    }

    @Test public void malformedPersistedModelKeepsTheUnsentQueueAndStateFailClosed() {
        store.putSetting("requiredModel","broken model");
        JSONObject request=runtime.receive("손상된 설정을 고친 뒤 수행해",new JSONArray());ready("medium","fast");
        assertEquals(0,transport.ids.size());assertFalse(runtime.state().optBoolean("ready"));
        assertEquals("queued",store.getRequest(request.optLong("id")).optString("status"));
        assertFalse(runtime.state().optJSONObject("requestModelPreferences").optBoolean("valid",true));
        store.putSetting("requiredModel","gpt-6.1-sol");ready("medium","fast");assertEquals(1,transport.ids.size());
    }

    @Test public void uncertainSelectionBeforeWireBlocksOnlyTheUnsentPromptAndAllowsExplicitRecovery() {
        ready("low","standard");
        JSONObject request=runtime.receive("선택 확인이 끝난 다음 처리해",new JSONArray());
        assertEquals("preparing",runtime.state().optString("activePhase"));assertEquals(0,transport.ids.size());
        runtime.onWebEvent(object("type","state","code","preference_selection_uncertain","state",object("authenticated",true,"workSelected",true,
                "temporary",true,"model","gpt-6.1-sol","effort","low","speed","standard","busy",false,"quota",false)));
        assertEquals("blocked",store.getRequest(request.optLong("id")).optString("status"));
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));assertTrue(runtime.state().isNull("activeRequestId"));
        // A late selector observation cannot send the blocked original automatically.
        ready("medium","fast");runtime.tick();drain();assertEquals(0,transport.ids.size());
        runtime.openTemporary();assertEquals(1,transport.opens);
        JSONObject retried=runtime.retryRequest(request.optLong("id"));ready("medium","fast");
        assertEquals(1,transport.ids.size());assertEquals(retried.optString("external_id"),transport.ids.get(0));
        assertNotEquals(request.optString("external_id"),transport.ids.get(0));
    }

    @Test public void newlyObservedDeviceEventSelectsMediumBeforeUpdatingTheReservedWatermark() {
        store.appendDeviceEvent("device","routing-native-fixture",object("event","new relevant observation"),clock.get(),null);
        ready("low","standard");enable();assertEquals(0,transport.ids.size());
        assertEquals("medium",runtime.requiredModelPreferences().optString("effort"));
        ready("medium","standard");assertEquals(1,transport.ids.size());
        String id=active().optString("external_id");assertEquals("medium",store.getSetting("routingMinimumEffort:"+id,""));
    }

    @Test public void deviceEventArrivingDuringLowPreparationDiscardsLowAndRebuildsMediumBeforeSend() {
        ready("medium","fast");enable();assertEquals(0,transport.ids.size());
        assertEquals("low",runtime.requiredModelPreferences().optString("effort"));
        store.appendDeviceEvent("device","routing-native-fixture",object("event","arrived during preparation"),clock.get(),null);
        ready("low","standard");assertEquals(0,transport.ids.size());
        assertEquals("medium",runtime.requiredModelPreferences().optString("effort"));
        ready("medium","standard");assertEquals(1,transport.ids.size());
        assertFalse(runtime.requiredModelPreferences(active().optString("external_id")).optBoolean("decisionOnly"));
    }

    @Test public void controlledPromptFootprintRetainsAllCapabilitiesAndTheSameFullMandatoryOriginal() throws Exception {
        String original="FOOTPRINT_CANONICAL_OWNER_SOURCE_7391. 나는 긴급한 알림을 놓치지 않게 해달라고 했고, 정정과 삭제는 최신 정책을 지켜.";
        JSONObject owner=store.addMessage("user",original,null);
        store.putSetting("ownerInputRevision",String.valueOf(owner.optLong("id")));
        // Controlled fixture precondition: this source is already reviewed. No semantic/model claim is made.
        assertTrue(store.advanceWatermark("memory.review.owner",0,owner.optLong("id")));
        ready("low","standard");enable();assertEquals(1,transport.ids.size());
        JSONObject candidate=active();String compact=transport.prompts.get(0);
        assertTrue(compact.contains(original));assertTrue(compact.contains("LATEST_OWNER_INPUT"));
        java.lang.reflect.Method method=AssistantRuntime.class.getDeclaredMethod("catalog");method.setAccessible(true);
        JSONArray capabilities=(JSONArray)method.invoke(runtime);
        for(int i=0;i<capabilities.length();i++)assertTrue(compact.contains('"'+capabilities.getJSONObject(i).optString("name")+'"'));
        runtime.onWebEvent(object("type","error","requestId",candidate.optString("external_id"),"code","preflight_rejected","knownNotSent",true));
        store.putSetting("usageOptimizationEnabled","false");store.putSetting("requiredEffort","medium");store.putSetting("requiredSpeed","standard");
        JSONObject baseline=runtime.retryRequest(candidate.optLong("id"));ready("medium","standard");assertEquals(2,transport.ids.size());
        String complete=transport.prompts.get(1);assertTrue(complete.contains(original));assertTrue(complete.contains("LATEST_OWNER_INPUT"));
        assertEquals(candidate.optString("text"),baseline.optString("text"));
        assertEquals("0",store.getSetting("requestSource:"+candidate.optString("external_id"),""));
        assertEquals("0",store.getSetting("requestSource:"+baseline.optString("external_id"),""));
        assertEquals(owner.optString("content_hash"),store.getMessage(owner.optLong("id")).optString("content_hash"));
        assertTrue("Compact decision framing should reduce repeated request text without trimming sources",compact.length()<complete.length());
        // Capability names, exact schemas and owner-requested operating instructions grew.
        // Historical whole-prompt ceilings do not measure repeated framing in this catalog.
        // Measure that growth explicitly rather than claiming the old free window is unchanged.
        JSONArray names=new JSONArray();for(int i=0;i<capabilities.length();i++)names.put(capabilities.getJSONObject(i).getString("name"));
        LosslessToolCatalog.Result wire=LosslessToolCatalog.formatResult(capabilities);
        assertTrue(wire.roundTripVerified);assertTrue(wire.text.length()<capabilities.toString().length());
        assertTrue(compact.length()<=250_000);assertTrue(complete.length()<=250_000);
        System.out.println("HOYOUNG_PROMPT_FOOTPRINT "+object("measurement","native_fixture_core_prompt_utf16_chars","lightStandardChars",compact.length(),
                "mediumStandardChars",complete.length(),"retainedCharacterRatio",(double)compact.length()/complete.length(),
                "avoidedCharacters",complete.length()-compact.length(),"allCapabilitiesRetained",capabilities.length(),
                "completeCapabilityNamesChars",names.toString().length(),"completeRawCatalogChars",capabilities.toString().length(),
                "completeLosslessCatalogChars",wire.text.length(),"lightNonCatalogChars",compact.length()-names.toString().length(),
                "mediumNonCatalogChars",complete.length()-capabilities.toString().length(),"historicalWholePromptCeilingsComparable",false,
                "sameCanonicalOwnerHash",owner.optString("content_hash"),"fullMandatoryOriginalPreserved",true,
                "actualModelCalls",0,"tokensMeasured",false,"subscriptionPercentMeasured",false,"semanticAccuracyCompared",false));
    }

    @Test public void expandedCatalogRetainsFullLargeOwnerInputActiveConstraintAndPinnedMemory() throws Exception {
        // This entire pinned fixture fits the existing 2,500-character memory projection.
        // Longer saved notes already use explicit source reads; this test does not raise that cap.
        String pinned="PINNED_FOOTPRINT_MEMORY_9184 "+"실제 원문 선호를 정확히 유지한다. ".repeat(100);
        JSONObject source=store.addMessage("user",pinned,null);
        store.upsertMemory("PINNED_FOOTPRINT_MEMORY_9184",pinned,"preference",new JSONArray().put(source.getLong("id")));
        store.putMemoryMetadata("PINNED_FOOTPRINT_MEMORY_9184",object("pinned",true,"confidence",1));
        String goals="MANDATORY_FOOTPRINT_ACTIVE_7134 원문과 정정을 그대로 참고해.";store.putSetting("standingGoals",goals);
        String current="PINNED_FOOTPRINT_MEMORY_9184 "+"원문 ".repeat(33_300)+"OWNER_TAIL_COMPLETE_8271";
        assertTrue(current.length()>=99_000);assertTrue(current.length()<=100_000);
        JSONObject first=runtime.receive(current,new JSONArray());ready("medium","fast");assertEquals(1,transport.ids.size());
        String packed=transport.prompts.get(0);assertTrue("Full current owner input",packed.contains(current));assertTrue("Entire pinned projection",packed.contains(pinned));assertTrue("Full mandatory active constraint",packed.contains(goals));
        com.hoyoung.assistant.store.MemoryGateway.Prepared memory=prepared(first);
        assertEquals(0,memory.diagnostics().getInt("omittedOptionalItems"));assertTrue(memory.context.contains("\"pinned\":true"));
        assertTrue(packed.length()<=250_000);
        runtime.onWebEvent(object("type","error","requestId",first.getString("external_id"),"code","preflight_rejected","knownNotSent",true));
        store.putSetting("usageOptimizationEnabled","false");JSONObject second=runtime.retryRequest(first.getLong("id"));ready("medium","fast");assertEquals(2,transport.ids.size());
        String plain=transport.prompts.get(1);assertTrue(plain.contains(current));assertTrue(plain.contains(pinned));assertTrue(plain.contains(goals));
        assertEquals(0,prepared(second).diagnostics().getInt("omittedOptionalItems"));assertTrue(plain.length()<=250_000);
        assertEquals(source.getString("content_hash"),store.getMessage(source.getLong("id")).getString("content_hash"));
        assertEquals(store.getSetting("requestSource:"+first.getString("external_id"),""),store.getSetting("requestSource:"+second.getString("external_id"),""));
    }

    @Test public void describeReturnsExactNativeDefinitionsAndHonorsOwnerDisable() throws Exception {
        java.lang.reflect.Method catalog=AssistantRuntime.class.getDeclaredMethod("catalog");catalog.setAccessible(true);
        JSONArray originals=(JSONArray)catalog.invoke(runtime);java.lang.reflect.Method execute=AssistantRuntime.class.getDeclaredMethod("executeTool",String.class,JSONObject.class);execute.setAccessible(true);
        JSONArray names=new JSONArray().put("phone_ui_type").put("accounts.save").put("files.read");
        JSONObject described=(JSONObject)execute.invoke(runtime,"app.tools.describe",object("names",names));
        assertTrue(described.getBoolean("ok"));assertTrue(described.getBoolean("completeDefinitions"));assertFalse(described.getBoolean("capabilityExecution"));
        JSONArray definitions=described.getJSONArray("definitions");assertEquals(names.length(),definitions.length());
        for(int i=0;i<names.length();i++) {
            JSONObject expected=null;for(int j=0;j<originals.length();j++){JSONObject item=originals.getJSONObject(j);if(names.getString(i).equals(item.getString("name")))expected=item;}
            assertNotNull(expected);assertEquals(expected.toString(),definitions.getJSONObject(i).toString());
        }
        store.putSetting("toolPermissions",object("app.tools.describe",false).toString());
        JSONObject denied=(JSONObject)execute.invoke(runtime,"app.tools.describe",object("names",names));assertFalse(denied.getBoolean("ok"));assertEquals("permission_disabled",denied.getString("code"));
    }

    private com.hoyoung.assistant.store.MemoryGateway.Prepared prepared(JSONObject request) throws Exception {
        java.lang.reflect.Field field=AssistantRuntime.class.getDeclaredField("preparedMemory");field.setAccessible(true);
        return (com.hoyoung.assistant.store.MemoryGateway.Prepared)((java.util.Map<?,?>)field.get(runtime)).get(request.getString("external_id"));
    }

    @Test public void stableCompletePrefixPreservesExactRequestContractAndFullToolSchemas() throws Exception {
        ready("medium","fast");
        JSONObject first=runtime.receive("FIRST_DYNAMIC_OWNER_TEXT_9327",new JSONArray());
        String a=transport.prompts.get(0),marker="\nREQUEST_SCOPE:";
        assertTrue(a.indexOf(marker)>0);
        String prefix=a.substring(0,a.indexOf(marker));
        assertTrue(prefix.startsWith("기본 운영 지침:"));
        assertTrue(prefix.contains("ROUTING_OWNER_SENTINEL"));
        assertFalse(prefix.contains(first.optString("external_id")));
        assertFalse(prefix.contains("FIRST_DYNAMIC_OWNER_TEXT_9327"));
        String rows=prefix.substring(prefix.indexOf(LosslessToolCatalog.HEADER)+LosslessToolCatalog.HEADER.length()).trim();
        JSONArray decoded=LosslessToolCatalog.decode(new JSONArray(rows));
        java.lang.reflect.Method catalog=AssistantRuntime.class.getDeclaredMethod("catalog");catalog.setAccessible(true);
        assertEquals(((JSONArray)catalog.invoke(runtime)).toString(),decoded.toString());
        assertTrue(a.contains("\"requestId\":\""+first.optString("external_id")+"\""));
        assertTrue(a.contains("CURRENT_INPUT")); assertTrue(a.contains("FIRST_DYNAMIC_OWNER_TEXT_9327"));
        finish(first,object("protocol","hoyoung.v1","requestId",first.optString("external_id"),"status","completed","message","첫 응답"));
        clock.addAndGet(1000);
        JSONObject second=runtime.receive("SECOND_DYNAMIC_OWNER_TEXT_8842",new JSONArray());
        String b=transport.prompts.get(1);
        assertEquals(prefix,b.substring(0,b.indexOf(marker)));
        assertTrue(b.contains("\"requestId\":\""+second.optString("external_id")+"\""));
        assertTrue(b.contains("SECOND_DYNAMIC_OWNER_TEXT_8842"));
        System.out.println("HOYOUNG_STABLE_PREFIX "+object("identicalPrefixUtf16Chars",prefix.length(),"completeInstructionsRepeated",true,
                "completeToolSchemasRoundTrip",true,"providerCacheHitMeasured",false,"semanticAccuracyCompared",false));
    }

    @Test public void changingOwnerInstructionsReplacesTheCompletePrefixWithoutReusingStaleSettings() {
        ready("medium","fast"); JSONObject first=runtime.receive("확인",new JSONArray());
        String a=transport.prompts.get(0);
        finish(first,object("protocol","hoyoung.v1","requestId",first.optString("external_id"),"status","completed","message","확인했어"));
        store.putSetting("customInstructions","NEW_COMPLETE_INSTRUCTIONS_4458 · 중요한 정정은 생략하지 마.");
        runtime.receive("새 지침 확인",new JSONArray()); String b=transport.prompts.get(1);
        assertTrue(a.contains("ROUTING_OWNER_SENTINEL"));
        String prefix=b.substring(0,b.indexOf("\nREQUEST_SCOPE:"));
        assertTrue(prefix.contains("NEW_COMPLETE_INSTRUCTIONS_4458 · 중요한 정정은 생략하지 마."));
        assertFalse(prefix.contains("ROUTING_OWNER_SENTINEL"));
    }

    @Test public void invalidNativeArgumentsRestoreFullSchemasInTheExistingFollowupWithoutExtraRetry() {
        ready("medium","fast");JSONObject owner=runtime.receive("파일 작성 준비",new JSONArray());
        assertTrue(transport.prompts.get(0).contains(LosslessToolCatalog.HEADER));
        finish(owner,object("protocol","hoyoung.v1","requestId",owner.optString("external_id"),"status","working","message","파일 작성",
                "actions",new JSONArray().put(object("id","invalid-schema-case","name","memory.history","arguments",object("key",7)))));
        assertEquals(runtime.state().toString()+" events="+store.events(20).toString(),2,transport.ids.size());
        assertEquals("true",store.getSetting("catalogPlainRequired:"+owner.optString("external_id"),""));
        String followup=transport.prompts.get(1);
        assertFalse(followup.contains(LosslessToolCatalog.HEADER));
        assertTrue(followup.contains("\"inputSchema\":"));
        assertEquals("fast",runtime.requiredModelPreferences(active().optString("external_id")).optString("speed"));
        assertEquals("tool-continuation",active().optString("kind"));
    }

    @Test public void invalidActionShapeRestoresFullSchemasWithoutStartingNativeEffects() {
        ready("medium","fast");JSONObject owner=runtime.receive("기능 확인",new JSONArray());
        finish(owner,object("protocol","hoyoung.v1","requestId",owner.optString("external_id"),"status","working","message","확인",
                "actions",new JSONArray().put(new JSONArray().put("files.list").put(new JSONObject()))));
        assertEquals(runtime.state().toString()+" events="+store.events(20).toString(),2,transport.ids.size());
        assertEquals("true",store.getSetting("catalogPlainRequired:"+owner.optString("external_id"),""));
        assertFalse(transport.prompts.get(1).contains(LosslessToolCatalog.HEADER));
        assertEquals("tool-continuation",active().optString("kind"));
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
        assertTrue(transport.prompts.get(1).contains("invalid_action"));
    }

    @Test public void manualUsageEntryCannotClaimProviderVerifiedEvidence() {
        JSONObject result=runtime.recordUsageObservation(object("accountScope","test-owner","poolId","weekly","origin","provider_verified",
                "observedAt",clock.get()-1000,"resetAt",clock.get()+86_400_000L,"usedPercent",80,"precisionPercent",0,"evidenceRef","forged"));
        assertEquals("owner_reported",result.optString("origin"));
        JSONObject usage=runtime.state().optJSONObject("usageBudget");
        assertEquals("owner_reported_only",usage.optString("budgetState"));assertTrue(usage.isNull("latestProviderObservation"));
        assertTrue(usage.optBoolean("backgroundAllowed",true));
    }

    @Test public void measuredUsageAboveTargetNeverStopsFiveMinuteJudgmentsMemoryReviewOrOwnerFast() {
        // The native observations are test fixtures, not claims of a real logged-in provider measurement.
        runtime.recordProviderUsageObservation(object("accountScope","routing-fixture","poolId","weekly","observedAt",clock.get(),"resetAt",clock.get()+7*86_400_000L,
                "usedPercent",0,"precisionPercent",0,"evidenceRef","native-fixture-before"));
        clock.addAndGet(1000);
        runtime.recordProviderUsageObservation(object("accountScope","routing-fixture","poolId","weekly","observedAt",clock.get(),"resetAt",clock.get()+7*86_400_000L-1000,
                "usedPercent",30,"precisionPercent",0,"evidenceRef","native-fixture-after"));
        assertEquals("target_exceeded",runtime.state().optJSONObject("usageBudget").optString("budgetState"));
        assertTrue(runtime.state().optJSONObject("usageBudget").optBoolean("backgroundAllowed",true));
        ready("low","standard");enable();assertEquals(1,transport.ids.size());
        JSONObject first=active();accept(first);finish(first,noAction(first));
        clock.addAndGet(300_000L);ready("low","standard");assertEquals(2,transport.ids.size());
        JSONObject second=active();accept(second);finish(second,noAction(second));
        store.putSetting("memoryReviewEnabled","true");store.addMessage("user","사용량 목표와 별개로 기억 검토를 계속해.",null);
        runtime.requestMemoryReview();ready("medium","standard");assertEquals(3,transport.ids.size());
        JSONObject review=active();accept(review);
        finish(review,object("protocol","hoyoung.v1","requestId",review.optString("external_id"),"status","idle","message",""));
        JSONObject owner=runtime.receive("지금 즉시 답해",new JSONArray());ready("medium","fast");
        assertEquals(4,transport.ids.size());assertEquals(owner.optString("external_id"),transport.ids.get(3));
        assertEquals("fast",runtime.requiredModelPreferences(owner.optString("external_id")).optString("speed"));
        assertFalse(runtime.state().optBoolean("quotaBlocked"));
    }

    private void enable() {store.putSetting("autonomy.enabled","true");runtime.tick();drain();}
    private void ready(String effort,String speed) {runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort",effort,"speed",speed,"busy",false,"quota",false)));drain();}
    private JSONObject active() {return store.getRequest(runtime.state().optLong("activeStoreId"));}
    private void accept(JSONObject request) {runtime.onWebEvent(object("type","accepted","requestId",request.optString("external_id")));drain();}
    private void finish(JSONObject request,JSONObject envelope) {runtime.onWebEvent(object("type","completed","requestId",request.optString("external_id"),"text",envelope.toString(),"rawEnvelopeEligible",true));drain();}
    private static JSONObject noAction(JSONObject request) {return object("protocol","hoyoung.v1","requestId",request.optString("external_id"),"status","idle","message","","routing",object("decision","NO_ACTION","reason","시각·제약·진행 목표를 실제 검토했고 현재 필요한 행동이 없음"));}
    private static void drain() {Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static JSONObject object(Object...pairs) {JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)put(value,String.valueOf(pairs[i]),pairs[i+1]);return value;}
    private static void put(JSONObject value,String key,Object content) {try{value.put(key,content);}catch(Exception error){throw new AssertionError(error);}}
    private static final class Transport implements AssistantRuntime.Transport {
        final List<String> ids=new ArrayList<>(),prompts=new ArrayList<>();
        int opens;
        public void inspect(){}public void send(String id,String prompt){ids.add(id);prompts.add(prompt);}public void openTemporary(){opens++;}public void cancel(){}
    }
}
