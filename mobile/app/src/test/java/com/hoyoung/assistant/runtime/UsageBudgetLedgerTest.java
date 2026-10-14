package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.time.Instant;
import static org.junit.Assert.*;

/** No real subscription/model calls. Provider-screen fixtures are explicitly simulated. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class UsageBudgetLedgerTest {
    private static final long DAY=Instant.parse("2026-10-13T15:00:00Z").toEpochMilli();
    private static final long MINUTE=60_000L, RESET=DAY+7*24*60*MINUTE;
    private Context context; private UsageBudgetLedger ledger;
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);ledger=new UsageBudgetLedger(context);}
    @After public void after(){ledger.close();context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);context.deleteDatabase(HoyoungStore.DATABASE_NAME);}

    @Test public void unknownUsageNeverTurnsCharacterCountsIntoTokensOrVerifiedBudget()throws Exception{
        stage("r",12345,DAY);ledger.recordAccepted("r",selection("fast"),DAY+1);
        JSONObject state=ledger.snapshot(DAY+MINUTE), stats=state.getJSONObject("requestStatistics");
        assertEquals("unknown",state.getString("budgetState"));assertTrue(state.isNull("measuredLowerPercent"));assertFalse(state.getBoolean("tokensMeasured"));
        assertFalse(state.getBoolean("budgetTargetGuaranteed"));assertTrue(state.getBoolean("backgroundAllowed"));assertEquals(1,stats.getLong("acceptedRequests"));
        assertEquals(12345,stats.getLong("promptUtf16Chars"));assertFalse(stats.getBoolean("backendModelAppliedVerified"));
    }
    @Test public void ownerReportsRemainOwnerReportsAndCannotPretendProviderProof()throws Exception{
        ledger.recordObservation(observation(DAY,RESET,10,0,"owner_reported"),DAY);
        ledger.recordObservation(observation(DAY+MINUTE,RESET,90,0,"owner_reported"),DAY+MINUTE);
        JSONObject state=ledger.snapshot(DAY+MINUTE);
        assertEquals("owner_reported_only",state.getString("budgetState"));assertTrue(state.isNull("measuredLowerPercent"));assertFalse(state.getBoolean("verifiedObservationFresh"));
        assertTrue(ledger.backgroundDecision(DAY+MINUTE).getBoolean("allowed"));assertEquals("owner_reported",state.getJSONObject("observation").getString("origin"));
    }
    @Test public void freshExactMidnightBaselineProvesObservedIntervalOnly()throws Exception{
        observe(DAY,10,0);observe(DAY+10*MINUTE,15,0);
        JSONObject state=ledger.snapshot(DAY+11*MINUTE);
        assertEquals("within_measured_target",state.getString("budgetState"));assertFalse(state.getBoolean("partialDayCoverage"));
        assertEquals(5,state.getDouble("measuredLowerPercent"),0);assertEquals(5,state.getDouble("measuredUpperPercent"),0);
        assertTrue(state.getBoolean("accountShared"));assertFalse(state.getBoolean("appAttributed"));assertFalse(state.getBoolean("backendUsageVerified"));
    }
    @Test public void firstObservationMiddayCannotProveWholeDayEvenIfDeltaIsSmall()throws Exception{
        observe(DAY+8*60*MINUTE,50,0);observe(DAY+8*60*MINUTE+MINUTE,51,0);
        JSONObject state=ledger.snapshot(DAY+8*60*MINUTE+MINUTE);
        assertEquals("partial_day_unknown",state.getString("budgetState"));assertTrue(state.getBoolean("partialDayCoverage"));
        assertEquals(1,state.getDouble("measuredLowerPercent"),0);
    }
    @Test public void measuredPartialDayLowerBoundIsAdvisoryAndAlwaysAllowsJudgmentAndUserFast()throws Exception{
        long begin=DAY+8*60*MINUTE;observe(begin,50,1);observe(begin+MINUTE,62,1);
        JSONObject state=ledger.snapshot(begin+MINUTE), decision=ledger.backgroundDecision(begin+MINUTE);
        assertEquals(10,state.getDouble("measuredLowerPercent"),0);assertEquals(14,state.getDouble("measuredUpperPercent"),0);
        assertEquals("target_exceeded",state.getString("budgetState"));assertTrue(decision.getBoolean("allowed"));assertTrue(decision.getBoolean("userRequestsAllowed"));
        assertFalse(decision.getBoolean("guaranteedTenPercent"));
        stage("owner-fast",1000,begin+MINUTE);ledger.recordAccepted("owner-fast",selection("fast"),begin+MINUTE+1);
        assertEquals(1,ledger.snapshot(begin+MINUTE+1).getJSONObject("requestStatistics").getLong("fastSelectionObservedAccepted"));
    }
    @Test public void confirmedSameDayTargetRemainsObservedAfterScreenGrowsStale()throws Exception{
        observe(DAY,0,0);observe(DAY+MINUTE,12,0);
        assertTrue(ledger.backgroundDecision(DAY+MINUTE).getBoolean("allowed"));
        JSONObject stale=ledger.snapshot(DAY+MINUTE+UsageBudgetLedger.OBSERVATION_FRESH_MS+1);
        assertEquals("target_exceeded",stale.getString("budgetState"));assertFalse(stale.getBoolean("verifiedObservationFresh"));assertTrue(stale.getBoolean("backgroundAllowed"));
        assertEquals("pool_reset_pending",ledger.snapshot(RESET).getString("budgetState"));
    }
    @Test public void belowTargetStaleObservationsCannotClaimCurrentBudgetPass()throws Exception{
        observe(DAY,0,0);observe(DAY+MINUTE,2,0);
        JSONObject stale=ledger.snapshot(DAY+MINUTE+UsageBudgetLedger.OBSERVATION_FRESH_MS+1);
        assertEquals("stale",stale.getString("budgetState"));assertFalse(stale.getBoolean("verifiedObservationFresh"));assertTrue(stale.getBoolean("backgroundAllowed"));
    }
    @Test public void roundedUnchangedDisplayUsesEndpointBoundsNotPerObservationAccumulation()throws Exception{
        observe(DAY,20,1);
        for(int minute=1;minute<=10;minute++)observe(DAY+minute*MINUTE,20,1);
        JSONObject state=ledger.snapshot(DAY+10*MINUTE);
        assertEquals(0,state.getDouble("measuredLowerPercent"),0);assertEquals(2,state.getDouble("measuredUpperPercent"),0);
        assertEquals("within_measured_target",state.getString("budgetState"));
    }
    @Test public void roundedDeltaAtTargetIsUncertainRatherThanFalselyVerified()throws Exception{
        observe(DAY,20,1);observe(DAY+MINUTE,30,1);
        JSONObject state=ledger.snapshot(DAY+MINUTE);
        assertEquals(8,state.getDouble("measuredLowerPercent"),0);assertEquals(12,state.getDouble("measuredUpperPercent"),0);
        assertEquals("rounding_uncertain",state.getString("budgetState"));assertTrue(state.getBoolean("backgroundAllowed"));assertTrue(state.getBoolean("userRequestsAllowed"));
    }
    @Test public void outOfOrderAndDuplicateObservationsCannotRewriteBaseline()throws Exception{
        observe(DAY,10,0);observe(DAY+2*MINUTE,15,0);
        JSONObject duplicate=ledger.recordObservation(observation(DAY+2*MINUTE,RESET,15,0,"provider_ui_observed"),DAY+3*MINUTE);
        assertFalse(duplicate.getBoolean("recorded"));assertEquals("duplicate_observation",duplicate.getString("code"));
        JSONObject old=ledger.recordObservation(observation(DAY+MINUTE,RESET,80,0,"provider_ui_observed"),DAY+3*MINUTE);
        assertEquals("out_of_order_observation",old.getString("code"));assertEquals(5,ledger.snapshot(DAY+3*MINUTE).getDouble("measuredLowerPercent"),0);
    }
    @Test public void decreasingMeterWithoutActualResetInvalidatesProviderIntegrity()throws Exception{
        observe(DAY,40,0);observe(DAY+MINUTE,45,0);
        JSONObject bad=ledger.recordObservation(observation(DAY+2*MINUTE,RESET,5,0,"provider_ui_observed"),DAY+2*MINUTE);
        assertFalse(bad.getBoolean("ok"));assertEquals("decreasing_usage_without_reset",bad.getString("code"));
        assertEquals("observation_integrity_unknown",ledger.snapshot(DAY+2*MINUTE).getString("budgetState"));
        assertTrue(ledger.backgroundDecision(DAY+2*MINUTE).getBoolean("allowed"));
        observe(DAY+3*MINUTE,56,0);assertEquals("target_exceeded",ledger.snapshot(DAY+3*MINUTE).getString("budgetState"));
    }
    @Test public void resetCannotBeInventedEarlyToClearUsage()throws Exception{
        observe(DAY,80,0);
        JSONObject early=ledger.recordObservation(observation(DAY+MINUTE,RESET+7*24*60*MINUTE,0,0,"provider_ui_observed"),DAY+MINUTE);
        assertEquals("unverified_pool_reset",early.getString("code"));assertEquals(80,ledger.snapshot(DAY+MINUTE).getJSONObject("latestProviderObservation").getDouble("usedPercent"),0);
    }
    @Test public void realResetDoesNotSubtractUsageAndRetainsPartialGapWarning()throws Exception{
        long reset=DAY+5*MINUTE;
        ledger.recordObservation(observation(DAY,reset,80,0,"provider_ui_observed"),DAY);
        ledger.recordObservation(observation(DAY+4*MINUTE,reset,90,0,"provider_ui_observed"),DAY+4*MINUTE);
        ledger.recordObservation(observation(DAY+6*MINUTE,RESET,3,0,"provider_ui_observed"),DAY+6*MINUTE);
        JSONObject state=ledger.snapshot(DAY+6*MINUTE);
        assertEquals(13,state.getDouble("measuredLowerPercent"),0);assertTrue(state.getBoolean("partialDayCoverage"));assertEquals("target_exceeded",state.getString("budgetState"));
    }
    @Test public void realResetWithDifferentPoolIdentifierPreservesEarlierSameDayConsumption()throws Exception{
        long reset=DAY+5*MINUTE;
        ledger.recordObservation(observation(DAY,reset,80,0,"provider_ui_observed").put("poolId","work-codex:"+reset),DAY);
        ledger.recordObservation(observation(DAY+4*MINUTE,reset,90,0,"provider_ui_observed").put("poolId","work-codex:"+reset),DAY+4*MINUTE);
        ledger.recordObservation(observation(DAY+6*MINUTE,RESET,3,0,"provider_ui_observed").put("poolId","work-codex:"+RESET),DAY+6*MINUTE);
        JSONObject state=ledger.snapshot(DAY+6*MINUTE);assertEquals(13,state.getDouble("measuredLowerPercent"),0);
        assertEquals("target_exceeded",state.getString("budgetState"));assertTrue(state.getBoolean("backgroundAllowed"));
    }
    @Test public void unrelatedUnexpiredPoolCannotReplaceCounterOrClearEstablishedBudget()throws Exception{
        observe(DAY,0,0);observe(DAY+MINUTE,12,0);
        JSONObject switchResult=ledger.recordObservation(observation(DAY+2*MINUTE,RESET+MINUTE,0,0,"provider_ui_observed").put("poolId","unrelated-pool"),DAY+2*MINUTE);
        assertEquals("unsupported_pool_identity_switch",switchResult.getString("code"));
        assertEquals("target_exceeded",ledger.snapshot(DAY+2*MINUTE).getString("budgetState"));assertTrue(ledger.backgroundDecision(DAY+2*MINUTE).getBoolean("allowed"));
    }
    @Test public void partialDayRoundedUpperBoundIsAdvisoryWithoutClaimingActualExceeded()throws Exception{
        long begin=DAY+8*60*MINUTE;observe(begin,50,1);observe(begin+MINUTE,58,1);
        JSONObject state=ledger.snapshot(begin+MINUTE);assertEquals(6,state.getDouble("measuredLowerPercent"),0);assertEquals(10,state.getDouble("measuredUpperPercent"),0);
        assertTrue(state.getBoolean("partialDayCoverage"));assertEquals("rounding_uncertain",state.getString("budgetState"));assertTrue(state.getBoolean("backgroundAllowed"));
        assertTrue(state.getBoolean("userRequestsAllowed"));assertFalse(state.getBoolean("budgetTargetGuaranteed"));
    }
    @Test public void seoulMidnightResetsDailyDeltaWithoutInventingAMidnightObservation()throws Exception{
        long tomorrow=DAY+24*60*MINUTE;observe(tomorrow-MINUTE,70,0);observe(tomorrow+MINUTE,72,0);
        JSONObject state=ledger.snapshot(tomorrow+MINUTE);
        assertEquals("2026-10-15",state.getString("day"));assertEquals(0,state.getDouble("measuredLowerPercent"),0);assertTrue(state.getBoolean("partialDayCoverage"));
    }
    @Test public void knownPoolResetExactlyAtMidnightSuppliesActualZeroBoundary()throws Exception{
        long tomorrow=DAY+24*60*MINUTE;
        ledger.recordObservation(observation(tomorrow-MINUTE,tomorrow,88,0,"provider_ui_observed"),tomorrow-MINUTE);
        ledger.recordObservation(observation(tomorrow+MINUTE,RESET,3,0,"provider_ui_observed"),tomorrow+MINUTE);
        JSONObject state=ledger.snapshot(tomorrow+MINUTE);assertFalse(state.getBoolean("partialDayCoverage"));assertEquals(3,state.getDouble("measuredLowerPercent"),0);
    }
    @Test public void accountAndPoolSwitchDoNotAttributeOtherAccountBaseline()throws Exception{
        observe(DAY,20,0);observe(DAY+MINUTE,35,0);
        JSONObject other=observation(DAY+2*MINUTE,RESET,50,0,"provider_ui_observed").put("accountScope","other-account");ledger.recordObservation(other,DAY+2*MINUTE);
        JSONObject state=ledger.snapshot(DAY+2*MINUTE);assertEquals(0,state.getDouble("measuredLowerPercent"),0);assertEquals("partial_day_unknown",state.getString("budgetState"));
        assertEquals("other-account",state.getJSONObject("latestProviderObservation").getString("accountScope"));
    }
    @Test public void dailyShareIsExplicitOptionalAndPoolAliasKeepsDefaultTenPercent()throws Exception{
        ledger.configure(10,"pool");assertEquals(10,ledger.snapshot(DAY).getDouble("effectiveTargetPercent"),0);
        assertEquals("pool_per_day",ledger.snapshot(DAY).getString("basis"));
        ledger.configure(10,"daily_share");assertEquals(10d/7,ledger.snapshot(DAY).getDouble("effectiveTargetPercent"),0);
        assertThrows(IllegalArgumentException.class,()->ledger.configure(Double.NaN,"pool"));assertThrows(IllegalArgumentException.class,()->ledger.configure(10,"invented"));
    }
    @Test public void acceptedAndStagedReceiptsDedupeDurablyAcrossRestart()throws Exception{
        stage("r",500,DAY);stage("r",500,DAY+1);ledger.recordSelectionObserved("r",selection("fast"),DAY+2);
        ledger.recordAccepted("r",null,DAY+3);ledger.close();ledger=new UsageBudgetLedger(context);
        assertFalse(ledger.recordAccepted("r",selection("fast"),DAY+4).getBoolean("recorded"));
        assertFalse(ledger.recordStaged("r","owner","different","high","standard",1,DAY+5).getBoolean("recorded"));
        JSONObject stats=ledger.snapshot(DAY+5).getJSONObject("requestStatistics");assertEquals(1,stats.getLong("stagedRequests"));assertEquals(1,stats.getLong("acceptedRequests"));
        assertEquals(500,stats.getLong("promptUtf16Chars"));assertEquals(1,stats.getLong("fastSelectionObservedAccepted"));
        assertFalse(ledger.requestSnapshot("r").getBoolean("backendModelAppliedVerified"));
    }
    @Test public void sourceGuardRebuiltUnacceptedPromptCountsLatestStagedSizeOnlyOnAcceptance()throws Exception{
        stage("r",500,DAY);ledger.recordSelectionObserved("r",selection("fast"),DAY+1);
        ledger.recordStaged("r","owner","gpt-6.1-sol","high","standard",6000,DAY+2);
        assertEquals(0,ledger.snapshot(DAY+2).getJSONObject("requestStatistics").getLong("acceptedRequests"));
        assertEquals(0,ledger.requestSnapshot("r").getJSONObject("observedSelection").length());
        ledger.recordAccepted("r",selection("standard"),DAY+3);
        JSONObject stats=ledger.snapshot(DAY+3).getJSONObject("requestStatistics");assertEquals(1,stats.getLong("stagedRequests"));assertEquals(6000,stats.getLong("promptUtf16Chars"));
        assertEquals(0,stats.getLong("fastSelectionObservedAccepted"));
        stage("r",99999,DAY+4);assertEquals(6000,ledger.requestSnapshot("r").getInt("promptUtf16Chars"));
    }
    @Test public void unacceptedOrUnknownReceiptsNeverCountAsSuccessfulModelCalls()throws Exception{
        stage("r",500,DAY);ledger.recordOutcome("r","dispatch_cancelled",DAY+1);
        assertFalse(ledger.recordAccepted("unknown",selection("fast"),DAY+2).getBoolean("ok"));
        JSONObject stats=ledger.snapshot(DAY+2).getJSONObject("requestStatistics");assertEquals(0,stats.getLong("acceptedRequests"));assertEquals(0,stats.getLong("promptUtf16Chars"));
    }
    @Test public void selectionArrivingAfterAcceptanceCountsOnceAndSeparatesRequestedFromObserved()throws Exception{
        stage("r",200,DAY);ledger.recordAccepted("r",null,DAY+1);ledger.recordSelectionObserved("r",selection("fast"),DAY+2);ledger.recordSelectionObserved("r",selection("fast"),DAY+3);
        JSONObject stats=ledger.snapshot(DAY+3).getJSONObject("requestStatistics");assertEquals(1,stats.getLong("selectionObservedAccepted"));assertEquals(1,stats.getLong("fastSelectionObservedAccepted"));
        ledger.recordSelectionObserved("r",selection("standard"),DAY+4);assertEquals(0,ledger.snapshot(DAY+4).getJSONObject("requestStatistics").getLong("fastSelectionObservedAccepted"));
        assertEquals("fast",ledger.requestSnapshot("r").getString("speed"));assertEquals("standard",ledger.requestSnapshot("r").getJSONObject("observedSelection").getString("speed"));
    }
    @Test public void retainedCountsBoundHistoryAndDoNotPruneUnresolvedStagedRequests()throws Exception{
        stage("pending",100,DAY);stage("accepted",200,DAY+1);ledger.recordAccepted("accepted",selection("fast"),DAY+2);
        long later=DAY+40*24*60*MINUTE;stage("new",300,later);
        assertNotNull(ledger.requestSnapshot("pending"));assertNull(ledger.requestSnapshot("accepted"));
        assertFalse(ledger.recordAccepted("accepted",selection("fast"),later+1).getBoolean("ok"));
        assertEquals(0,ledger.snapshot(DAY).getJSONObject("requestStatistics").getLong("acceptedRequests"));
        assertTrue(ledger.recordAccepted("pending",selection("fast"),later+2).getBoolean("recorded"));
    }
    @Test public void ordinaryMemoryRestoreCannotRewindCurrentQuotaOrAcceptedReceipts()throws Exception{
        HoyoungStore store=new HoyoungStore(context);
        try{store.addMessage("user","원본 기억",null);JSONObject backup=store.exportSnapshot();
            observe(DAY,10,0);observe(DAY+MINUTE,22,0);stage("r",100,DAY+MINUTE);ledger.recordAccepted("r",selection("fast"),DAY+MINUTE+1);
            ledger.configure(10,"pool_per_day");store.importSnapshot(backup);
            assertEquals("target_exceeded",ledger.snapshot(DAY+MINUTE+1).getString("budgetState"));assertEquals(1,ledger.snapshot(DAY+MINUTE+1).getJSONObject("requestStatistics").getLong("acceptedRequests"));
            assertFalse(ledger.recordAccepted("r",selection("fast"),DAY+MINUTE+2).getBoolean("recorded"));
        }finally{store.close();}
    }
    @Test public void invalidNumericAndFutureObservationsNeverBecomeValidProviderEvidence()throws Exception{
        assertThrows(IllegalArgumentException.class,()->ledger.recordObservation(observation(DAY+MINUTE,RESET,10,0,"provider_ui_observed"),DAY));
        assertThrows(IllegalArgumentException.class,()->ledger.recordObservation(observation(DAY,DAY,10,0,"provider_ui_observed"),DAY));
        assertThrows(IllegalArgumentException.class,()->ledger.recordObservation(observation(DAY,RESET,101,0,"provider_ui_observed"),DAY));
        assertThrows(IllegalArgumentException.class,()->ledger.recordObservation(observation(DAY,RESET,10,0,"provider_ui_observed").put("usedPercent","10"),DAY));
        assertThrows(IllegalArgumentException.class,()->ledger.recordObservation(observation(DAY,RESET,10,0,"provider_ui_observed").put("evidenceRef",""),DAY));
        assertEquals("unknown",ledger.snapshot(DAY).getString("budgetState"));
    }
    private void stage(String id,int chars,long at){ledger.recordStaged(id,"owner","gpt-6.1-sol","medium","fast",chars,at);}
    private void observe(long at,double used,double precision)throws Exception{assertTrue(ledger.recordObservation(observation(at,RESET,used,precision,"provider_ui_observed"),at).getBoolean("ok"));}
    private static JSONObject selection(String speed)throws Exception{return new JSONObject().put("model","gpt-6.1-sol").put("effort","medium").put("speed",speed);}
    private static JSONObject observation(long at,long reset,double used,double precision,String origin)throws Exception{return new JSONObject().put("accountScope","test-account")
            .put("poolId","weekly-pool").put("observedAt",at).put("resetAt",reset).put("usedPercent",used).put("precisionPercent",precision).put("origin",origin).put("evidenceRef","simulated usage-screen fixture");}
}
