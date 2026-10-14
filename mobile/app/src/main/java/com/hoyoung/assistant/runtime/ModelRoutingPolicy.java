package com.hoyoung.assistant.runtime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Request-scoped inference policy. It routes work; it never infers billing or executes effects. */
public final class ModelRoutingPolicy {
    private ModelRoutingPolicy() {}

    public static JSONObject choose(JSONObject settings,String kind,String purpose,boolean reviewPending,
                                    boolean dueGoalOrDoubt,String minimumEffort) {
        JSONObject config=settings==null?new JSONObject():settings;
        String model=config.optString("requiredModel","gpt-6.1-sol");
        String ownerEffort=config.optString("requiredEffort","medium");
        String legacySpeed=config.optString("requiredSpeed","fast");
        AssistantRuntime.validateModelPreferences(model,ownerEffort,legacySpeed);
        boolean optimize=config.optBoolean("usageOptimizationEnabled",true);
        boolean owner="user".equals(kind)||"user".equals(purpose);
        String effort,speed,lane,reason;
        boolean decisionOnly=false;
        if(owner) {
            effort=atLeast(ownerEffort,"medium",minimumEffort);speed="fast";lane="owner";reason="owner_direct_fast";
        } else if(!optimize) {
            boolean writer="memory-review".equals(purpose)||reviewPending;
            effort=writer?atLeast(ownerEffort,config.optString("memoryMinimumEffort","medium"),"medium",minimumEffort)
                    :atLeast(ownerEffort,"medium",minimumEffort);
            speed=legacySpeed;lane=writer?"memory":"legacy";reason=writer?"memory_writer_quality_floor":"optimization_disabled";
        } else {
            speed=config.optString("backgroundSpeed","standard");
            // An owner's explicitly higher inference level must never be reduced by optimization.
            String explicitFloor=rank(ownerEffort)>rank("medium")?ownerEffort:"low";
            if("memory-review".equals(purpose)||reviewPending) {
                effort=atLeast(config.optString("memoryMinimumEffort","medium"),"medium",explicitFloor,minimumEffort);
                lane="memory";reason="memory_writer_quality_floor";
            } else if("autonomy".equals(kind)&&"autonomy".equals(purpose)&&!dueGoalOrDoubt&&rank(minimumEffort)<=rank("low")) {
                effort=atLeast(config.optString("autonomousEffort","medium"),explicitFloor,minimumEffort);
                decisionOnly="low".equals(effort);lane=decisionOnly?"autonomy_decision":"background";
                reason=decisionOnly?"bounded_decision_candidate":"configured_autonomy_effort";
            } else {
                effort=atLeast(ownerEffort,"medium",explicitFloor,minimumEffort);lane="background";reason="task_or_uncertainty_requires_reasoning";
            }
        }
        AssistantRuntime.validateModelPreferences(model,effort,speed);
        return object("model",model,"effort",effort,"speed",speed,"lane",lane,"reason",reason,"decisionOnly",decisionOnly,
                "backendModelAppliedVerified",false);
    }

    /** A Light candidate can report only an empty, request-bound idle decision. */
    public static String promotionEffort(JSONObject envelope) {
        if(envelope==null)return "medium";
        JSONObject routing=envelope.optJSONObject("routing");
        if(routing==null||!(routing.opt("decision") instanceof String)||!(routing.opt("reason") instanceof String)
                ||routing.optString("reason").trim().isEmpty())return "medium";
        String decision=routing.optString("decision","");
        if("ESCALATE_HIGH".equals(decision))return "high";
        if("HANDLE_MEDIUM".equals(decision))return "medium";
        if(!"NO_ACTION".equals(decision))return "medium";
        if(!"idle".equals(envelope.optString("status"))||!envelope.optString("message","").trim().isEmpty()
                ||nonempty(envelope,"actions")||nonempty(envelope,"memories")||nonempty(envelope,"assetAnalyses")
                ||envelope.has("review")||envelope.has("nextCheckAt")||envelope.has("reaction"))return "medium";
        return "";
    }

    public static String atLeast(String... efforts) {
        String result="low";
        for(String effort:efforts)if(effort!=null&&!effort.isEmpty()) {
            int rank=rank(effort);if(rank>rank(result))result=effort;
        }
        return result;
    }
    public static int rank(String effort) {
        if(effort==null||effort.isEmpty())return -1;
        switch(effort) {case "low":return 0;case "medium":return 1;case "high":return 2;case "xhigh":return 3;case "max":return 4;case "ultra":return 5;default:throw new IllegalArgumentException("Unknown inference effort");}
    }
    private static boolean nonempty(JSONObject value,String field) {JSONArray rows=value.optJSONArray(field);return value.has(field)&&(rows==null||rows.length()>0);}
    private static JSONObject object(Object... pairs) {JSONObject result=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);}catch(JSONException impossible){throw new IllegalStateException(impossible);}return result;}
}
