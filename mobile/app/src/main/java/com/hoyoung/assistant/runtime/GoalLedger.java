package com.hoyoung.assistant.runtime;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.SourcePrivacyLedger;
import com.hoyoung.assistant.store.SourceRecordVisibility;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.HashSet;
import java.util.Set;

/** Durable unfinished goals. Provider-turn completion is not evidence a goal finished. */
public final class GoalLedger {
    public static final int MAX_ACTIVE_GOALS = 256;
    private static final String PREFIX = "activeGoal:", RANGE_END = "activeGoal;";
    private static final String REPORT_PREFIX="goalReportedArchive:",REPORT_END="goalReportedArchive;";
    private final HoyoungStore store;
    private volatile java.util.function.Predicate<JSONObject> deviceVisibility=row->false;
    public void setDeviceSourceVisibility(java.util.function.Predicate<JSONObject> predicate){deviceVisibility=predicate==null?row->false:predicate;}
    public GoalLedger(HoyoungStore store) { this.store = store; }

    public JSONObject update(String rootId, String stage, long sourceId, String lastRequestId,
                             String progress, long nextCheckAt) {
        return update(rootId, stage, sourceId, lastRequestId, progress, nextCheckAt, new JSONObject());
    }

    /** Evidence is supplied by trusted runtime receipts, never passed straight from model JSON. */
    public JSONObject update(String rootId, String reportedStage, long sourceId, String lastRequestId,
                             String progress, long nextCheckAt, JSONObject evidence) {
        validRoot(rootId); validRoot(lastRequestId);
        if (!pending(reportedStage) && !"completed".equals(reportedStage) && !"abandoned".equals(reportedStage))
            throw new IllegalArgumentException("Unsupported goal stage");
        if (sourceId < 0 || nextCheckAt < 0 || progress == null || progress.length() > 2_000)
            throw new IllegalArgumentException("Invalid goal source, progress, or check time");
        JSONObject proof = evidence == null ? new JSONObject() : copy(evidence);
        if (proof.has("completionVerified") && !(proof.opt("completionVerified") instanceof Boolean))
            throw new IllegalArgumentException("completionVerified must be boolean");
        boolean verified = Boolean.TRUE.equals(proof.opt("completionVerified"));
        if (verified && proof.optString("verificationSource").trim().isEmpty())
            throw new IllegalArgumentException("Verified completion needs an actual receipt source");
        return store.withTransaction(db -> {
            JSONObject previous = get(rootId), rootRequest = requestByExternalId(db, rootId);
            if (rootRequest == null) throw new IllegalArgumentException("Unknown root request");
            JSONObject lastRequest = requestByExternalId(db, lastRequestId);
            if (lastRequest == null || (!lastRequestId.equals(rootId)
                    && !rootId.equals(store.getSetting("requestRoot:" + lastRequestId, ""))))
                throw new IllegalArgumentException("Last request is not part of this root goal");
            long effectiveSource = sourceId;
            if (previous != null) {
                long originalSource = previous.optLong("sourceMessageId");
                if (sourceId == 0 && originalSource > 0) effectiveSource = originalSource;
                else if (sourceId != originalSource) throw new IllegalArgumentException("A goal source cannot be replaced");
            }
            validateSource(rootId, effectiveSource, rootRequest);
            boolean finalClaim = "completed".equals(reportedStage) || "abandoned".equals(reportedStage);
            if (previous == null && finalClaim) {
                // Ordinary finished chat replies do not consume unfinished-goal slots.
                return object("ok", true, "tracked", false, "closed", false, "code", "no_pending_goal",
                        "reportedStage", reportedStage, "completionVerified", verified);
            }
            if (previous == null && activeCount(db) >= MAX_ACTIVE_GOALS) {
                store.recordEvent("goal_ledger_capacity_reached", object("rootRequestId", rootId, "limit", MAX_ACTIVE_GOALS));
                return object("ok", false, "code", "goal_capacity_reached", "limit", MAX_ACTIVE_GOALS,
                        "message", "미완료 목표 256개 한도에 도달했어. 기존 목표를 확인·정리해야 새 목표를 추적할 수 있어.");
            }
            String stage = "completed".equals(reportedStage) && !verified ? "awaiting_result"
                    : "abandoned".equals(reportedStage) ? "blocked" : reportedStage;
            long now = System.currentTimeMillis();
            JSONObject record = object("rootRequestId", rootId, "sourceMessageId", effectiveSource,
                    "lastRequestId", lastRequestId, "stage", stage, "reportedStage", reportedStage,
                    "progress", progress, "createdAt", previous == null ? now : previous.optLong("createdAt", now),
                    "updatedAt", Math.max(now, previous == null ? 0 : previous.optLong("updatedAt")),
                    "nextCheckAt", nextCheckAt, "completionVerified", verified,
                    "completionClaimUnverified", "completed".equals(reportedStage) && !verified,
                    "abandonmentNeedsOwnerDecision", "abandoned".equals(reportedStage),
                    "requiresOwnerReconciliation", "blocked".equals(stage) || "uncertain".equals(stage),
                    "mayAutoRepeat", false, "mayScheduleReadOnlyCheck", checkAllowed(stage, nextCheckAt),
                    "requestExcerpt", safePrefix(rootRequest.optString("text"), 500),
                    "requestExcerptIncomplete", rootRequest.optString("text").length() > 500);
            String currentSources=store.getSetting("goalSources:"+rootId,"");
            if(!currentSources.isEmpty()){String historyKey="goalSourceHistory:"+rootId+":"+sourceDigest(currentSources);store.putSetting(historyKey,currentSources);put(record,"progressSourcesKey",historyKey);}
            if (!proof.optString("verificationSource").isEmpty()) put(record, "verificationSource", safePrefix(proof.optString("verificationSource"), 512));
            boolean closed = "completed".equals(stage) || "abandoned".equals(stage);
            if (closed) db.delete("settings", "setting_key = ?", new String[]{PREFIX + rootId});
            else store.putSetting(PREFIX + rootId, record.toString());
            bumpRevision();
            store.recordEvent(closed ? "goal_closed" : "goal_stage_recorded", object("goal", record,
                    "modelReportIsVerification", false, "rawRequestsAndOriginalsRetained", true));
            return object("ok", true, "tracked", !closed, "closed", closed, "goal", visible(db,policy(),record));
        });
    }

    public JSONObject get(String rootId) {
        validRoot(rootId);
        String serialized = store.getSetting(PREFIX + rootId, null);
        return serialized == null ? null : readActive(serialized,rootId);
    }

    /** Keyset pagination advances across scanned rows even if stage/time filters select none. */
    public JSONObject list(JSONObject arguments) {
        JSONObject args = arguments == null ? new JSONObject() : arguments;
        rejectUnknown(args, "afterRootRequestId", "limit", "stage", "dueBefore", "scope");
        String scope=args.optString("scope","active");
        if("reported_archive".equals(scope))return listReported(args);
        if(!"active".equals(scope))throw new IllegalArgumentException("Unknown goal list scope");
        String after = args.optString("afterRootRequestId", ""); if (!after.isEmpty()) validRoot(after);
        int limit = intArgument(args, "limit", 1, 100, 25);
        String stage = args.optString("stage", ""); if (!stage.isEmpty() && !pending(stage)) throw new IllegalArgumentException("Invalid stage filter");
        long dueBefore = longArgument(args, "dueBefore", 0, Long.MAX_VALUE, Long.MAX_VALUE);
        return store.withTransaction(db -> {
            SourcePrivacyLedger.Snapshot frozen=policy();
            JSONArray goals = new JSONArray(), invalid = new JSONArray();
            int scanned = 0; String last = after; boolean more = false;
            try (Cursor cursor = db.rawQuery("SELECT setting_key,value FROM settings WHERE setting_key >= ? AND setting_key < ? AND setting_key > ? ORDER BY setting_key ASC LIMIT ?",
                    new String[]{PREFIX, RANGE_END, PREFIX + after, String.valueOf(limit + 1)})) {
                while (cursor.moveToNext()) {
                    if (scanned == limit) { more = true; break; }
                    String key = cursor.getString(0); last = key.substring(PREFIX.length()); scanned++;
                    try {
                        JSONObject goal = readActive(cursor.getString(1),last);
                        if ((stage.isEmpty() || stage.equals(goal.optString("stage")))
                                && (!args.has("dueBefore") || (goal.optLong("nextCheckAt") > 0 && goal.optLong("nextCheckAt") <= dueBefore))) goals.put(visible(db,frozen,goal));
                    } catch (Exception unreadable) { invalid.put(last); }
                }
            }
            checkPolicy(frozen);
            return object("ok", true, "goals", goals, "totalPending", activeCount(db), "limit", MAX_ACTIVE_GOALS,
                    "scannedCount", scanned, "returnedCount", goals.length(), "truncated", more,
                    "nextAfterRootRequestId", more ? last : JSONObject.NULL, "invalidRecordCount", invalid.length(), "invalidRootRequestIds", invalid);
        });
    }

    public String context(int maxChars) {
        SourcePrivacyLedger.Snapshot frozen=policy();
        if (maxChars < 256 || maxChars > 50_000) throw new IllegalArgumentException("Goal context budget must be 256..50,000");
        JSONObject page = list(object("limit", 100)); JSONArray goals = new JSONArray();
        java.util.List<JSONObject> ranked = new java.util.ArrayList<>(); int invalidCount = 0;
        int scanned = 0;
        while (true) {
            JSONArray items = page.optJSONArray("goals");
            for (int i = 0; i < items.length(); i++) ranked.add(items.optJSONObject(i));
            invalidCount += page.optInt("invalidRecordCount"); scanned += page.optInt("scannedCount");
            if (!page.optBoolean("truncated") || scanned >= MAX_ACTIVE_GOALS) break;
            page = list(object("limit", 100, "afterRootRequestId", page.optString("nextAfterRootRequestId")));
        }
        long now = System.currentTimeMillis();
        ranked.sort((left, right) -> {
            int order = Integer.compare(priority(left, now), priority(right, now));
            if (order != 0) return order;
            order = Long.compare(left.optLong("updatedAt"), right.optLong("updatedAt"));
            return order != 0 ? order : left.optString("rootRequestId").compareTo(right.optString("rootRequestId"));
        });
        for (JSONObject goal : ranked) goals.put(goal);
        JSONArray packed = new JSONArray(); boolean truncated = page.optBoolean("truncated");
        JSONObject result = object("totalPending", page.optLong("totalPending"), "goals", packed,
                "truncated", truncated, "invalidRecordCount", invalidCount,
                "executionRule", "blocked/uncertain 목표 자동재실행 금지. due는 상태 확인 시점이며 행동 재승인이 아님.");
        for (int i = 0; i < goals.length(); i++) {
            JSONObject goal = goals.optJSONObject(i);
            JSONObject compact = object("rootRequestId", goal.optString("rootRequestId"), "sourceMessageId", goal.optLong("sourceMessageId"),
                    "lastRequestId", goal.optString("lastRequestId"), "stage", goal.optString("stage"),
                    "reportedStage", goal.optString("reportedStage"), "progress", goal.optString("progress"),
                    "requestExcerpt", goal.optString("requestExcerpt"), "requestExcerptIncomplete", goal.optBoolean("requestExcerptIncomplete"),
                    "nextCheckAt", goal.optLong("nextCheckAt"), "completionVerified", goal.optBoolean("completionVerified"),
                    "mayAutoRepeat", false, "requiresOwnerReconciliation", goal.optBoolean("requiresOwnerReconciliation"));
            packed.put(compact);
            if (result.toString().length() > maxChars - 32) { packed.remove(packed.length() - 1); truncated = true; break; }
        }
        put(result, "truncated", truncated); put(result, "shownCount", packed.length());
        if (result.toString().length() > maxChars) result.remove("executionRule");
        checkPolicy(frozen);return result.toString();
    }

    /** Call only for an explicit owner decision; this is not an independently verified outcome. */
    public JSONObject resolve(String rootId, String finalStage, String reason) {
        validRoot(rootId);
        if (!("completed".equals(finalStage) || "abandoned".equals(finalStage))
                || reason == null || reason.trim().isEmpty() || reason.length() > 2_000) throw new IllegalArgumentException("Explicit owner closure and reason required");
        return store.withTransaction(db -> {
            String raw=store.getSetting(PREFIX+rootId,null);
            if(raw==null)return object("ok",false,"code","goal_not_active");
            JSONObject previous;
            try{previous=readActive(raw,rootId);}
            catch(IllegalStateException unreadable){
                JSONObject preserved=store.addMessage("system",object("type","owner_closed_unreadable_goal","rootRequestId",rootId,"rawGoal",raw,"outcomeVerified",false).toString(),null);
                recordNativeSource(db,preserved.optLong("id"),object("rootRequestId",rootId));
                previous=object("rootRequestId",rootId,"previousGoalUnreadable",true,"sourceUnknown",true,
                        "previousGoalSourceMessageId",preserved.optLong("id"),"previousGoalSourceHash",preserved.optString("content_hash"),
                        "previousGoalBodyHash",preserved.optString("raw_content_hash"));
            }
            put(previous, "stage", finalStage); put(previous, "reportedStage", finalStage);
            put(previous, "ownerResolved", true); put(previous, "completionVerified", false);
            put(previous, "providerOutcomeVerified", false); put(previous, "resolutionReason", reason);
            put(previous, "mayScheduleReadOnlyCheck", false); put(previous, "requiresOwnerReconciliation", false);
            put(previous, "mayAutoRepeat", false);
            put(previous, "updatedAt", System.currentTimeMillis());
            db.delete("settings", "setting_key = ?", new String[]{PREFIX + rootId});
            bumpRevision();
            store.recordEvent("goal_owner_resolved", object("goal", previous, "automaticReplay", false, "rawRequestsAndOriginalsRetained", true));
            return object("ok", true, "closed", true, "goal", visible(db,policy(),previous));
        });
    }
    public JSONObject remove(String rootId, String reason) { return resolve(rootId, "abandoned", reason); }

    /** Operationally archive an observed provider claim; neither overall nor cloud success is verified. */
    public JSONObject archiveReportedCompletion(String rootId,String actorRequestId,String progress,JSONObject nativeSummary) {
        validRoot(rootId);validRoot(actorRequestId);
        if(progress==null||progress.length()>2_000||nativeSummary==null)throw new IllegalArgumentException("Bounded report and trusted native summary required");
        JSONObject summary=copy(nativeSummary);
        return store.withTransaction(db->{
            JSONObject previous=get(rootId);
            if(previous==null)return object("ok",false,"archived",false,"code","goal_not_active");
            if("blocked".equals(previous.optString("stage"))||"uncertain".equals(previous.optString("stage"))
                    ||"true".equals(store.getSetting("pausedRoot:"+rootId,"false")))return object("ok",false,"archived",false,"code","goal_requires_reconciliation");
            JSONObject originalRequest=requestByExternalId(db,rootId);
            if(originalRequest==null)return object("ok",false,"archived",false,"code","goal_source_unavailable");
            try{validateSource(rootId,previous.optLong("sourceMessageId"),originalRequest);}catch(IllegalArgumentException unavailable){return object("ok",false,"archived",false,"code","goal_source_unavailable");}
            JSONObject actor=requestByExternalId(db,actorRequestId);
            if(actor==null||!("accepted".equals(actor.optString("status"))||"completed".equals(actor.optString("status")))
                    ||!actor.optString("raw_result_hash").matches("[0-9a-f]{64}"))return object("ok",false,"archived",false,"code","observed_report_required");
            if(!validNativeSummary(summary,rootId))return object("ok",false,"archived",false,"code","native_effects_unconfirmed");
            String actualSnapshot=new ActionReceiptJournal(store).snapshotHash(rootId);
            if(!actualSnapshot.equals(summary.optString("snapshotHash")))return object("ok",false,"archived",false,"code","native_receipt_snapshot_changed");
            long now=System.currentTimeMillis();String priorRaw=store.getSetting(PREFIX+rootId,"");
            JSONObject report=copy(previous);put(report,"previousStage",previous.optString("stage"));put(report,"stage","reported_completed");put(report,"reportedStage","completed");
            put(report,"progress",progress);put(report,"updatedAt",now);put(report,"reportActorRequestId",actorRequestId);put(report,"reportRawResponseHash",actor.optString("raw_result_hash"));
            put(report,"completionVerified",false);put(report,"overallOutcomeVerified",false);put(report,"providerOutcomeVerified",false);put(report,"mayAutoRepeat",false);
            put(report,"mayScheduleReadOnlyCheck",false);put(report,"operationallyArchived",true);put(report,"knownNativeEffectsReconciled",true);put(report,"nativeSummary",summary);
            JSONObject source=store.addMessage("system",object("type","goal_provider_reported_complete_archive","goal",report,"previousActiveRecord",priorRaw,"modelReportIsIndependentProof",false).toString(),null);
            recordNativeSource(db,source.optLong("id"),report);
            JSONObject index=object("rootRequestId",rootId,"sourceMessageId",previous.optLong("sourceMessageId"),"stage","reported_completed","reportedStage","completed",
                    "progress",safePrefix(progress,500),"progressIncomplete",progress.length()>500,"reportedAt",now,"actorRequestId",actorRequestId,
                    "reportRawResponseHash",actor.optString("raw_result_hash"),"reportSourceMessageId",source.optLong("id"),"reportSourceHash",source.optString("content_hash"),"reportBodyHash",source.optString("raw_content_hash"),
                    "nativeSnapshotHash",actualSnapshot,"nativeReceiptCount",summary.optLong("receiptCount"),"confirmedNativeMutationCount",summary.optLong("confirmedMutationCount"),
                    "completionVerified",false,"overallOutcomeVerified",false,"providerOutcomeVerified",false,"operationallyArchived",true,"mayAutoRepeat",false);
            store.putSetting(REPORT_PREFIX+rootId,index.toString());db.delete("settings","setting_key = ?",new String[]{PREFIX+rootId});bumpRevision();
            store.recordEvent("goal_reported_complete_archived",object("report",index,"immutableSourceStored",true,"modelReportIsVerification",false));
            return object("ok",true,"archived",true,"tracked",false,"goal",visible(db,policy(),index),"completionConfirmed",true,"confirmationScope","goal_ledger_archival_only","overallOutcomeVerified",false);
        });
    }
    private static boolean validNativeSummary(JSONObject summary,String rootId) {
        if(!Boolean.TRUE.equals(summary.opt("ready"))||!rootId.equals(summary.optString("rootRequestId"))||!"native_receipts_only".equals(summary.optString("proofScope"))
                ||!Boolean.FALSE.equals(summary.opt("overallOutcomeVerified"))||!summary.optString("snapshotHash").matches("[0-9a-f]{64}"))return false;
        for(String key:new String[]{"receiptCount","successfulCount","failedCount","unfinishedCount","mutationCount","confirmedMutationCount","unconfirmedMutationCount","failedMutationCount","readOnlyCount","failedReadOnlyCount","corruptCount"}){
            Object value=summary.opt(key);if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).longValue()||((Number)value).longValue()<0||((Number)value).longValue()>10_000_000)return false;
        }
        return summary.optLong("unfinishedCount")==0&&summary.optLong("unconfirmedMutationCount")==0&&summary.optLong("failedMutationCount")==0&&summary.optLong("corruptCount")==0
                &&summary.optLong("mutationCount")==summary.optLong("confirmedMutationCount")
                &&summary.optLong("receiptCount")==summary.optLong("successfulCount")+summary.optLong("failedCount")
                &&summary.optLong("failedCount")==summary.optLong("failedReadOnlyCount")
                &&summary.optLong("receiptCount")==summary.optLong("mutationCount")+summary.optLong("readOnlyCount");
    }
    private JSONObject listReported(JSONObject args) {
        String after=args.optString("afterRootRequestId","");if(!after.isEmpty())validRoot(after);int limit=intArgument(args,"limit",1,100,25);
        if(args.has("dueBefore")||args.has("stage")&&!"reported_completed".equals(args.optString("stage")))throw new IllegalArgumentException("Archived reports are not active due goals");
        return store.withTransaction(db->{
            SourcePrivacyLedger.Snapshot frozen=policy();
            JSONArray reports=new JSONArray(),invalid=new JSONArray();String last=after;int scanned=0;boolean more=false;
            try(Cursor rows=db.rawQuery("SELECT setting_key,value FROM settings WHERE setting_key >= ? AND setting_key < ? AND setting_key > ? ORDER BY setting_key ASC LIMIT ?",new String[]{REPORT_PREFIX,REPORT_END,REPORT_PREFIX+after,String.valueOf(limit+1)})){
                while(rows.moveToNext()){if(scanned==limit){more=true;break;}last=rows.getString(0).substring(REPORT_PREFIX.length());scanned++;
                    try{JSONObject index=readReportIndex(rows.getString(1),last);JSONObject source=store.getMessage(index.optLong("reportSourceMessageId"));
                        if(!sourcesAllowed(db,frozen,index,deviceVisibility)){reports.put(opaque(index));continue;}
                        if(source==null||!index.optString("reportSourceHash").equals(source.optString("content_hash"))||!index.optString("reportBodyHash").equals(source.optString("raw_content_hash")))throw new IllegalStateException("Archive original identity unavailable");
                        put(index,"archiveSourceReferenceMatches",true);put(index,"archiveOriginalContentVerified",false);reports.put(index);
                    }catch(RuntimeException unavailable){invalid.put(last);}
                }
            }
            long total;try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM settings WHERE setting_key >= ? AND setting_key < ?",new String[]{REPORT_PREFIX,REPORT_END})){count.moveToFirst();total=count.getLong(0);}
            checkPolicy(frozen);
            return object("ok",true,"scope","reported_archive","goals",reports,"totalReportedArchives",total,"totalPending",activeCount(db),"scannedCount",scanned,"returnedCount",reports.length(),
                    "truncated",more,"nextAfterRootRequestId",more?last:JSONObject.NULL,"invalidRecordCount",invalid.length(),"invalidRootRequestIds",invalid,"reportedCompletionIsIndependentProof",false);
        });
    }
    private static JSONObject readReportIndex(String raw,String rootId){
        if(raw==null||raw.length()>8_192)throw new IllegalStateException("Unreadable report index");
        try{JSONObject index=wholeObject(raw);if(!rootId.equals(index.optString("rootRequestId"))||!"reported_completed".equals(index.optString("stage"))
                    ||!Boolean.FALSE.equals(index.opt("completionVerified"))||!Boolean.FALSE.equals(index.opt("overallOutcomeVerified"))||!Boolean.FALSE.equals(index.opt("mayAutoRepeat"))
                    ||index.optLong("reportSourceMessageId")<=0||!index.optString("reportSourceHash").matches("[0-9a-f]{64}")||!index.optString("reportBodyHash").matches("[0-9a-f]{64}"))throw new IllegalStateException("Invalid archived report");return index;
        }catch(org.json.JSONException invalid){throw new IllegalStateException("Unreadable report index",invalid);}
    }

    private SourcePrivacyLedger.Snapshot policy(){try{return store.getPrivacyLedger().capture();}catch(java.io.IOException unavailable){throw new IllegalStateException("goal_privacy_unavailable",unavailable);}}
    /** Immutable archive originals inherit native provenance before becoming searchable. */
    private void recordNativeSource(SQLiteDatabase db,long messageId,JSONObject goal){
        JSONArray refs=new JSONArray();String fingerprint="";boolean malformed=false;
        try(Cursor stored=db.rawQuery("SELECT value FROM settings WHERE setting_key=?",new String[]{"goalSources:"+goal.optString("rootRequestId")})){
            if(stored.moveToFirst())try{JSONObject provenance=wholeObject(stored.getString(0));JSONArray old=provenance.optJSONArray("sources");if(provenance.optInt("version")!=1||old==null)malformed=true;else{for(int i=0;i<old.length();i++)refs.put(old.opt(i));fingerprint=provenance.optString("memoryFingerprint");}}catch(org.json.JSONException|IllegalStateException invalid){malformed=true;}
        }
        String history=goal.optString("progressSourcesKey");
        if(!history.isEmpty())try(Cursor stored=db.rawQuery("SELECT value FROM settings WHERE setting_key=?",new String[]{history})){
            if(!history.startsWith("goalSourceHistory:"+goal.optString("rootRequestId")+":")||!stored.moveToFirst())malformed=true;
            else try{String raw=stored.getString(0);JSONObject old=wholeObject(raw);JSONArray values=old.optJSONArray("sources");if(!history.equals("goalSourceHistory:"+goal.optString("rootRequestId")+":"+sourceDigest(raw))||old.optInt("version")!=1||values==null)malformed=true;else for(int i=0;i<values.length();i++)refs.put(values.opt(i));}catch(org.json.JSONException|IllegalStateException invalid){malformed=true;}
        }
        long owner=goal.optLong("sourceMessageId");if(owner>0)addCanonicalRef(db,refs,owner);
        for(String key:new String[]{"lastRequestId","actorRequestId","reportActorRequestId"}){String actor=goal.optString(key);if(actor.isEmpty())continue;try(Cursor reply=db.rawQuery("SELECT id FROM messages WHERE role='assistant' AND request_id=? ORDER BY id DESC LIMIT 1",new String[]{actor})){if(reply.moveToFirst())addCanonicalRef(db,refs,reply.getLong(0));}}
        store.putSetting("answerSources:"+messageId,object("version",malformed?0:1,"sources",refs,"memoryFingerprint",fingerprint,"nativeProvenance","goal_archive").toString());
    }
    private static void addCanonicalRef(SQLiteDatabase db,JSONArray refs,long id){try(Cursor source=db.rawQuery("SELECT content_hash FROM messages WHERE id=?",new String[]{String.valueOf(id)})){if(source.moveToFirst())refs.put(object("type","message","id",id,"hash",source.getString(0)));}}
    private static String sourceDigest(String raw){try{byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder(64);for(byte b:bytes)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private void checkPolicy(SourcePrivacyLedger.Snapshot frozen){if(!frozen.fingerprint.equals(policy().fingerprint))throw new IllegalStateException("goal_privacy_changed");}
    private JSONObject visible(SQLiteDatabase db,SourcePrivacyLedger.Snapshot frozen,JSONObject goal){JSONObject result=sourcesAllowed(db,frozen,goal,deviceVisibility)?copy(goal):opaque(goal);checkPolicy(frozen);return result;}
    /** Opaque constraints retain the run fence and schedule, never the excluded report body. */
    public static JSONObject opaque(JSONObject goal){return object("rootRequestId",goal.optString("rootRequestId"),"stage","blocked_source_excluded","nextCheckAt",goal.optLong("nextCheckAt"),"mayAutoRepeat",false,"mayScheduleReadOnlyCheck",false,"requiresOwnerReconciliation",true,"sourcePolicyExcluded",true,"completionVerified",false);}
    /** All SQL uses the supplied canonical read view; no store live reader or model work. */
    public static boolean sourcesAllowed(SQLiteDatabase db,SourcePrivacyLedger.Snapshot policy,JSONObject goal,java.util.function.Predicate<JSONObject> deviceScope){
        if(goal.optLong("sourceMessageId")>0&&!allowed(db,policy,"message",goal.optLong("sourceMessageId"),"",deviceScope))return false;
        String root=goal.optString("rootRequestId");java.util.List<String> keys=new java.util.ArrayList<>();keys.add("goalSources:"+root);String history=goal.optString("progressSourcesKey");if(!history.isEmpty()){if(!history.startsWith("goalSourceHistory:"+root+":"))return false;keys.add(history);}
        for(String sourceKey:keys){String raw=null;try(Cursor value=db.rawQuery("SELECT value FROM settings WHERE setting_key=?",new String[]{sourceKey})){if(value.moveToFirst())raw=value.getString(0);}
        if(raw==null&&!sourceKey.startsWith("goalSources:"))return false;
        if(raw!=null&&sourceKey.startsWith("goalSourceHistory:")&&!sourceKey.equals("goalSourceHistory:"+root+":"+sourceDigest(raw)))return false;
        if(raw!=null)try{JSONObject recorded=wholeObject(raw);JSONArray refs=recorded.optJSONArray("sources");if(recorded.optInt("version")!=1||refs==null)return false;
            for(int i=0;i<refs.length();i++){JSONObject ref=refs.optJSONObject(i);if(ref==null||!(ref.opt("id") instanceof Number)||((Number)ref.opt("id")).doubleValue()!=ref.optLong("id")||ref.optLong("id")<=0||!ref.optString("hash").matches("[a-f0-9]{64}")||!allowed(db,policy,ref.optString("type"),ref.optLong("id"),ref.optString("hash"),deviceScope))return false;}
        }catch(org.json.JSONException|IllegalStateException malformed){return false;}}
        for(String key:new String[]{"lastRequestId","actorRequestId","reportActorRequestId"}){String request=goal.optString(key);if(request.isEmpty())continue;
            try(Cursor reply=db.rawQuery("SELECT id FROM messages WHERE role='assistant' AND request_id=? ORDER BY id DESC LIMIT 1",new String[]{request})){if(reply.moveToFirst()&&!allowed(db,policy,"message",reply.getLong(0),"",deviceScope))return false;}}
        if(goal.optLong("reportSourceMessageId")>0&&!allowed(db,policy,"message",goal.optLong("reportSourceMessageId"),goal.optString("reportSourceHash"),deviceScope))return false;
        return true;
    }
    private static boolean allowed(SQLiteDatabase db,SourcePrivacyLedger.Snapshot policy,String type,long id,String hash,java.util.function.Predicate<JSONObject> deviceScope){
        String table="message".equals(type)?"messages":"device_event".equals(type)?"device_events":null;if(table==null)return false;
        try(Cursor cursor=db.rawQuery("SELECT * FROM "+table+" WHERE id=?",new String[]{String.valueOf(id)})){if(!cursor.moveToFirst())return false;JSONObject row=new JSONObject();
            for(int i=0;i<cursor.getColumnCount();i++)put(row,cursor.getColumnName(i),cursor.isNull(i)?JSONObject.NULL:cursor.getType(i)==Cursor.FIELD_TYPE_INTEGER?cursor.getLong(i):cursor.getType(i)==Cursor.FIELD_TYPE_FLOAT?cursor.getDouble(i):cursor.getString(i));
            if(!hash.isEmpty()&&!hash.equals(row.optString("content_hash"))||!SourceRecordVisibility.canonicalRecordMatches(type,row)||!SourceRecordVisibility.allowed(db,policy,type,row,deviceScope))return false;
            if("device_event".equals(type)){try{put(row,"payload",new JSONObject(row.optString("payload")));}catch(org.json.JSONException malformed){return false;}return deviceScope!=null&&deviceScope.test(row);}return true;
        }
    }

    private static JSONObject readActive(String raw,String rootId) {
        if(raw==null||raw.isEmpty()||raw.length()>32_768)throw new IllegalStateException("Unreadable active goal record");
        try{
            JSONObject goal=wholeObject(raw);
            if(!rootId.equals(goal.optString("rootRequestId"))||!pending(goal.optString("stage"))
                    ||!(goal.opt("lastRequestId") instanceof String)||!goal.optString("lastRequestId").matches("[A-Za-z0-9_.:-]{1,450}")
                    ||!(goal.opt("progress") instanceof String)||goal.optString("progress").length()>2_000)throw new IllegalStateException("Malformed active goal record");
            for(String field:new String[]{"sourceMessageId","createdAt","updatedAt","nextCheckAt"}){
                Object value=goal.opt(field);if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).longValue()||((Number)value).longValue()<0)throw new IllegalStateException("Malformed active goal number");
            }
            return goal;
        }catch(org.json.JSONException unreadable){throw new IllegalStateException("Unreadable active goal record",unreadable);}
    }
    private static JSONObject wholeObject(String raw)throws org.json.JSONException{
        int depth=0;boolean quoted=false,escaped=false;for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>64)throw new IllegalStateException("Goal nesting too deep");}else if(c=='}'||c==']'){if(--depth<0)throw new IllegalStateException("Malformed goal delimiters");}}
        JSONTokener tokener=new JSONTokener(raw);Object parsed=tokener.nextValue();if(!(parsed instanceof JSONObject)||tokener.nextClean()!=0)throw new IllegalStateException("Goal must be one whole object");return (JSONObject)parsed;
    }

    private void bumpRevision() {
        long previous = 0;
        try { previous = Math.max(0, Long.parseLong(store.getSetting("goalLedgerRevision", "0"))); }
        catch (NumberFormatException invalid) { /* Repair the derived invalidation counter without dropping goals. */ }
        store.putSetting("goalLedgerRevision", String.valueOf(previous == Long.MAX_VALUE ? 1 : previous + 1));
    }
    private void validateSource(String rootId, long sourceId, JSONObject rootRequest) {
        if (sourceId > 0) {
            JSONObject source = store.getMessage(sourceId);
            if (source == null || !"user".equals(source.optString("role")) || !rootId.equals(source.optString("request_id")))
                throw new IllegalArgumentException("Root goal requires its actual owner source message");
        } else if (!"autonomy".equals(rootRequest.optString("kind"))
                || !rootId.equals(store.getSetting("requestRoot:" + rootId, rootId)))
            throw new IllegalArgumentException("An owner request cannot become a source-free automatic goal");
    }
    private static JSONObject requestByExternalId(SQLiteDatabase db, String id) {
        try (Cursor cursor = db.rawQuery("SELECT id,external_id,text,kind,status,raw_result_hash FROM requests WHERE external_id = ?", new String[]{id})) {
            if (!cursor.moveToFirst()) return null;
            return object("id", cursor.getLong(0), "external_id", cursor.getString(1), "text", cursor.getString(2), "kind", cursor.getString(3), "status", cursor.getString(4),"raw_result_hash",cursor.getString(5));
        }
    }
    private static long activeCount(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM settings WHERE setting_key >= ? AND setting_key < ?", new String[]{PREFIX, RANGE_END})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }
    private static int priority(JSONObject goal, long now) {
        if (checkAllowed(goal.optString("stage"), goal.optLong("nextCheckAt")) && goal.optLong("nextCheckAt") <= now) return 0;
        return goal.optBoolean("requiresOwnerReconciliation") ? 1 : 2;
    }
    private static boolean pending(String stage) { return "queued".equals(stage) || "working".equals(stage) || "awaiting_result".equals(stage) || "blocked".equals(stage) || "uncertain".equals(stage); }
    private static boolean checkAllowed(String stage, long nextCheckAt) { return nextCheckAt > 0 && ("working".equals(stage) || "awaiting_result".equals(stage)); }
    private static void validRoot(String id) { if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,450}")) throw new IllegalArgumentException("Invalid goal root request id"); }
    private static int intArgument(JSONObject args, String key, int min, int max, int fallback) {
        return (int) longArgument(args, key, min, max, fallback);
    }
    private static long longArgument(JSONObject args, String key, long min, long max, long fallback) {
        if (!args.has(key)) return fallback; Object value = args.opt(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).longValue()) throw new IllegalArgumentException("Invalid " + key);
        long result = ((Number) value).longValue(); if (result < min || result > max) throw new IllegalArgumentException("Invalid " + key); return result;
    }
    private static void rejectUnknown(JSONObject args, String... allowed) {
        Set<String> keys = new HashSet<>(); for (String key : allowed) keys.add(key);
        JSONArray names = args.names(); if (names != null) for (int i = 0; i < names.length(); i++) if (!keys.contains(names.optString(i))) throw new IllegalArgumentException("Unknown paging argument");
    }
    private static String safePrefix(String value, int count) {
        int end = Math.min(value.length(), count);
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }
    private static JSONObject copy(JSONObject value) { try { return new JSONObject(value.toString()); } catch (Exception invalid) { throw new IllegalArgumentException("Invalid evidence", invalid); } }
    private static JSONObject object(Object... pairs) { JSONObject result = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) put(result, String.valueOf(pairs[i]), pairs[i + 1]); return result; }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception invalid) { throw new IllegalStateException(invalid); } }
}
