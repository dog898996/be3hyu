package com.hoyoung.assistant.runtime;

import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Request routing cannot downgrade owner replies or turn a Light proposal into an effect. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class ModelRoutingPolicyTest {
    @Test public void ownerGoesDirectlyToMediumFastEvenWithBackgroundAndLegacyStandard() throws Exception {
        JSONObject settings=object("requiredEffort","low","requiredSpeed","standard","backgroundSpeed","standard");
        JSONObject route=choose(settings,"user","user",false,false,"");
        assertRoute(route,"medium","fast","owner",false);
        assertEquals("owner_direct_fast",route.getString("reason"));
        assertFalse("Requested selection is not evidence of backend application",route.getBoolean("backendModelAppliedVerified"));
    }

    @Test public void ownerHigherEffortIsNeverReducedToMediumByOptimization() throws Exception {
        for(String effort:new String[]{"high","xhigh","max","ultra"}) {
            JSONObject route=choose(object("requiredEffort",effort,"requiredSpeed","standard"),"user","user",false,false,"");
            assertRoute(route,effort,"fast","owner",false);
        }
    }

    @Test public void ownerToolContinuationRetainsFastAndOwnerQualityFloor() throws Exception {
        JSONObject route=choose(object("requiredEffort","low","requiredSpeed","standard"),"tool-continuation","user",true,true,"high");
        assertRoute(route,"high","fast","owner",false);
    }

    @Test public void ownerKindHasPriorityEvenWhenPurposeLooksLikeBackgroundReview() throws Exception {
        JSONObject route=choose(object("memoryMinimumEffort","high"),"user","memory-review",true,true,"");
        assertRoute(route,"medium","fast","owner",false);
    }

    @Test public void memoryReviewUsesStandardWithAtLeastMediumEvenIfConfiguredLow() throws Exception {
        JSONObject route=choose(object("memoryMinimumEffort","low"),"memory-review","memory-review",false,false,"");
        assertRoute(route,"medium","standard","memory",false);
        assertEquals("memory_writer_quality_floor",route.getString("reason"));
    }

    @Test public void PendingMemoryPreventsAnOtherwiseEligibleLightTick() throws Exception {
        JSONObject route=choose(new JSONObject(),"autonomy","autonomy",true,false,"");
        assertRoute(route,"medium","standard","memory",false);
    }

    @Test public void memoryWriterRespectsConfiguredAndStickyHigherFloors() throws Exception {
        assertRoute(choose(object("memoryMinimumEffort","high"),"autonomy","autonomy",true,false,""),"high","standard","memory",false);
        assertRoute(choose(object("memoryMinimumEffort","medium"),"autonomy","autonomy",true,false,"xhigh"),"xhigh","standard","memory",false);
    }

    @Test public void explicitlyHigherOwnerEffortAlsoProtectsAllBackgroundWork() throws Exception {
        for(String effort:new String[]{"high","xhigh","max","ultra"}) {
            JSONObject settings=object("requiredEffort",effort);
            assertRoute(choose(settings,"autonomy","autonomy",false,false,""),effort,"standard","background",false);
            assertRoute(choose(settings,"autonomy","autonomy",true,false,""),effort,"standard","memory",false);
        }
    }

    @Test public void onlyQuietAutonomyWithoutMemoryMayEnterLightDecisionLane() throws Exception {
        JSONObject route=choose(new JSONObject(),"autonomy","autonomy",false,false,"");
        assertRoute(route,"low","standard","autonomy_decision",true);
        assertEquals("bounded_decision_candidate",route.getString("reason"));
        assertRoute(choose(new JSONObject(),"autonomy","autonomy",false,false,"low"),"low","standard","autonomy_decision",true);
    }

    @Test public void dueGoalOrDoubtRetainsMediumWithoutFirstTryingLight() throws Exception {
        assertRoute(choose(new JSONObject(),"autonomy","autonomy",false,true,""),"medium","standard","background",false);
    }

    @Test public void backgroundTaskAndContinuationCannotBeMistakenForIdleDecision() throws Exception {
        assertRoute(choose(new JSONObject(),"autonomy","task",false,false,""),"medium","standard","background",false);
        assertRoute(choose(new JSONObject(),"tool-continuation","autonomy",false,false,""),"medium","standard","background",false);
    }

    @Test public void stickyPromotionFloorSurvivesLaterQuietTicks() throws Exception {
        assertRoute(choose(new JSONObject(),"autonomy","autonomy",false,false,"medium"),"medium","standard","background",false);
        assertRoute(choose(new JSONObject(),"autonomy","autonomy",false,false,"high"),"high","standard","background",false);
        assertRoute(choose(new JSONObject(),"tool-continuation","user",false,false,"ultra"),"ultra","fast","owner",false);
    }

    @Test public void canonicalAutonomousEffortSettingKeepsExplicitHigherSelection() throws Exception {
        assertRoute(choose(object("autonomousEffort","medium"),"autonomy","autonomy",false,false,""),"medium","standard","background",false);
        assertRoute(choose(object("autonomousEffort","high"),"autonomy","autonomy",false,false,""),"high","standard","background",false);
    }

    @Test public void optimizationDisabledPreservesLegacyBackgroundButOwnerStillFast() throws Exception {
        JSONObject settings=object("usageOptimizationEnabled",false,"requiredEffort","low","requiredSpeed","standard");
        assertRoute(choose(settings,"autonomy","autonomy",false,true,""),"medium","standard","legacy",false);
        assertRoute(choose(settings,"user","user",false,false,""),"medium","fast","owner",false);
        assertRoute(choose(settings,"tool-continuation","user",false,false,""),"medium","fast","owner",false);
    }
    @Test public void turningOptimizationOffCannotLowerTheMemoryWriterBelowMedium() throws Exception {
        JSONObject settings=object("usageOptimizationEnabled",false,"requiredEffort","low","requiredSpeed","standard","memoryMinimumEffort","low");
        assertRoute(choose(settings,"autonomy","autonomy",true,false,""),"medium","standard","memory",false);
        assertRoute(choose(settings,"memory-review","memory-review",false,false,""),"medium","standard","memory",false);
    }

    @Test public void invalidRequestedInferenceSettingsCannotSilentlySelectAnAvailableFallback() throws Exception {
        assertThrows(IllegalArgumentException.class,()->choose(object("requiredModel","invalid model"),"user","user",false,false,""));
        assertThrows(IllegalArgumentException.class,()->choose(object("requiredEffort","light"),"user","user",false,false,""));
        assertThrows(IllegalArgumentException.class,()->choose(object("backgroundSpeed","turbo"),"autonomy","autonomy",false,false,""));
        assertThrows(IllegalArgumentException.class,()->choose(object("autonomousEffort","extreme"),"autonomy","autonomy",false,false,""));
    }

    @Test public void onlyReasonedEmptyIdleNoActionCanFinishWithoutPromotion() throws Exception {
        JSONObject envelope=idle();
        envelope.put("actions",new JSONArray()).put("memories",new JSONArray()).put("assetAnalyses",new JSONArray());
        String before=envelope.toString();
        assertEquals("",ModelRoutingPolicy.promotionEffort(envelope));
        assertEquals("Routing evaluation must not execute or rewrite a proposal",before,envelope.toString());
    }

    @Test public void missingMalformedOrUnreasonedRoutingAlwaysPromotes() throws Exception {
        assertEquals("medium",ModelRoutingPolicy.promotionEffort(null));
        assertPromotes(new JSONObject(),"medium");
        assertPromotes(idle().put("routing",JSONObject.NULL),"medium");
        assertPromotes(idle().put("routing",new JSONArray()),"medium");
        assertPromotes(idle().put("routing",object("decision","NO_ACTION")),"medium");
        assertPromotes(idle().put("routing",object("decision","NO_ACTION","reason"," \n ")) ,"medium");
        assertPromotes(idle().put("routing",object("decision","NO_ACTION","reason",17)),"medium");
        assertPromotes(idle().put("routing",object("decision",true,"reason","quiet")),"medium");
        assertPromotes(idle().put("routing",object("decision","UNKNOWN","reason","quiet")),"medium");
    }

    @Test public void explicitMediumOrHighRequestPromotesWithoutExecutingProposedEffects() throws Exception {
        JSONObject medium=idle().put("routing",object("decision","HANDLE_MEDIUM","reason","Need to read original evidence"));
        medium.put("actions",new JSONArray().put(object("name","phone.contacts.write","arguments",object("name","proposal only"))));
        assertPromotes(medium,"medium");
        JSONObject high=idle().put("routing",object("decision","ESCALATE_HIGH","reason","Conflicting goals require deeper analysis"));
        high.put("memories",new JSONArray().put(object("text","Do not persist until promoted")));
        assertPromotes(high,"high");
    }

    @Test public void anyActionMemoryOrAssetProposalMustPromoteEvenWhenMarkedNoAction() throws Exception {
        for(String field:new String[]{"actions","memories","assetAnalyses"}) {
            assertPromotes(idle().put(field,new JSONArray().put(object("text","pending proposal"))),"medium");
        }
    }

    @Test public void malformedEffectFieldsCannotMasqueradeAsEmptyNoAction() throws Exception {
        for(String field:new String[]{"actions","memories","assetAnalyses"}) {
            for(Object malformed:new Object[]{new JSONObject(),"",true,JSONObject.NULL}) {
                assertPromotes(idle().put(field,malformed),"medium");
            }
        }
    }

    @Test public void reviewAcknowledgmentOrSchedulingAlwaysRequiresMedium() throws Exception {
        assertPromotes(idle().put("review",new JSONObject()),"medium");
        assertPromotes(idle().put("review",JSONObject.NULL),"medium");
        assertPromotes(idle().put("nextCheckAt",0),"medium");
        assertPromotes(idle().put("nextCheckAt",JSONObject.NULL),"medium");
    }

    @Test public void userMessageAndEveryNonIdleOutcomeRequirePromotion() throws Exception {
        assertPromotes(idle().put("message","주인에게 보낼 초안"),"medium");
        for(String status:new String[]{"working","blocked","completed","failed","","IDLE"}) {
            assertPromotes(idle().put("status",status),"medium");
        }
    }

    private static JSONObject choose(JSONObject settings,String kind,String purpose,boolean pending,boolean doubt,String minimum) {
        return ModelRoutingPolicy.choose(settings,kind,purpose,pending,doubt,minimum);
    }
    private static void assertRoute(JSONObject route,String effort,String speed,String lane,boolean decisionOnly) throws Exception {
        assertEquals("gpt-6.1-sol",route.getString("model"));
        assertEquals(effort,route.getString("effort"));
        assertEquals(speed,route.getString("speed"));
        assertEquals(lane,route.getString("lane"));
        assertEquals(decisionOnly,route.getBoolean("decisionOnly"));
    }
    private static void assertPromotes(JSONObject envelope,String effort) {
        String before=envelope.toString();
        assertEquals(effort,ModelRoutingPolicy.promotionEffort(envelope));
        assertEquals("Promotion is a pure decision and leaves proposals unexecuted",before,envelope.toString());
    }
    private static JSONObject idle() throws Exception {
        return object("status","idle","message","","routing",object("decision","NO_ACTION","reason","No due goals or pending work"));
    }
    private static JSONObject object(Object... pairs) throws Exception {
        JSONObject value=new JSONObject();
        for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);
        return value;
    }
}
