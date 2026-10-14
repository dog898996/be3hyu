package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.store.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.File;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;

/** Actual native queue/range ACK/dispatch lineage, fake provider only; no model-quality claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RuntimeReviewPiggybackTest {
    private Context context;private HoyoungStore store;private InternalFiles files;private AssistantRuntime runtime;private Transport transport;private long wallNow;
    @Before public void setup()throws Exception {context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());store=new HoyoungStore(context);store.putSetting("usageOptimizationEnabled","false"); /* Fixed Medium/Fast control; optimized routing has separate regression coverage. */files=new InternalFiles(context);store.putSetting("autonomy.enabled","false");wallNow=System.currentTimeMillis();runtime=new AssistantRuntime(context,store,files,new PhoneTools(context,store),Runnable::run,()->wallNow);transport=new Transport();runtime.attachTransport(transport);ready();}
    @After public void cleanup()throws Exception {store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());}
    @Test public void twentyMinuteOverdueInferenceCarriesReviewAndAdvancesInTwoActualCallsOnly()throws Exception {
        for(int i=0;i<40;i++)source("ordinary archived text item "+i);
        JSONObject first=due();JSONObject batch=batch(first);assertEquals("autonomy",first.getString("kind"));assertEquals(1,transport.ids.size());assertEquals(300_000L,runtime.getAutonomyTickPolicy().snapshot(now()).getLong("nextDueAt")-runtime.getAutonomyTickPolicy().snapshot(now()).getLong("lastDueAt"));
        finish(first,envelope(first).put("review",ack(batch)));long through=store.getWatermark(MemoryReviewCoordinator.WATERMARK);assertTrue(through>0);assertEquals(1,transport.ids.size());assertTrue(runtime.getMemoryReview().hasPending());
        wallNow+=1_200_000L;ready();JSONObject second=active();assertEquals("autonomy",second.getString("kind"));assertEquals(2,transport.ids.size());assertTrue(batch(second).getLong("cursorStart")>=through);
        finish(second,envelope(second).put("review",ack(batch(second))));assertTrue(store.getWatermark(MemoryReviewCoordinator.WATERMARK)>through);assertEquals(2,transport.ids.size());assertFalse(store.events(100).toString().contains("\"kind\":\"memory-review\""));
    }
    @Test public void acceptedTwentyMinuteWorkCoalescesTicksAndStillAcknowledgesWithoutAnExtraReviewCall()throws Exception {
        for(int i=0;i<40;i++)source("ordinary pending source "+i);JSONObject root=due(),original=batch(root);String id=root.getString("external_id");String digest=runtime.registerPreparedPayload(id,payload(root));assertTrue(runtime.authorizeFinalPayload(id,digest));runtime.onWebEvent(object("type","accepted","requestId",id));
        for(int minute=0;minute<20;minute++) {wallNow+=60_000L;runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",true,"quota",false)));}
        assertEquals(1,transport.ids.size());finish(root,envelope(root).put("review",ack(original)));assertTrue(store.getWatermark(MemoryReviewCoordinator.WATERMARK)>0);assertEquals(wallNow,Long.parseLong(store.getSetting("runtime.lastMemoryReviewAt","0")));ready();assertEquals(1,transport.ids.size());assertTrue(runtime.getMemoryReview().hasPending());assertTrue(runtime.getAutonomyTickPolicy().snapshot(now()).getLong("nextDueAt")>now());assertTrue(store.events(100).toString().contains("coalesced_busy"));
    }
    @Test public void independentFullReviewWithMutationDoesNotReplayItsCursorInContinuation()throws Exception {
        long old=source("ordinary saved transcript").getLong("id");JSONObject root=due(),originalBatch=batch(root);
        JSONObject result=envelope(root).put("status","working").put("review",ack(originalBatch)).put("actions",new JSONArray().put(object("id","write","name","files.write","arguments",object("relativePath","piggyback.txt","content","independent actual native result"))));
        finish(root,result);assertEquals(old,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertEquals("independent actual native result",files.readText("piggyback.txt",100));JSONObject continuation=active();assertEquals("tool-continuation",continuation.getString("kind"));assertEquals(2,transport.ids.size());
        assertEquals("",store.getSetting("memoryReview:"+continuation.getString("external_id"),""));assertEquals(originalBatch.toString(),store.getSetting("memoryReview:"+root.getString("external_id"),""));assertFalse(transport.prompts.get(1).contains("미검토 기억 배치"));assertEquals("",store.getSetting("memoryReviewHadRejected:"+continuation.getString("external_id"),""));
    }
    @Test public void rejectedMemoryOrMissingReviewNeverAdvancesCoverage()throws Exception {
        source("ordinary transcript unchanged");JSONObject root=due(),batch=batch(root);JSONObject range=batch.getJSONArray("sourceRanges").getJSONObject(0);
        JSONObject wrong=object("key","piggyback-wrong","content","fabricated claim","kind","fact","sourceRefs",new JSONArray().put(object("type","message","id",range.getLong("messageId"),"quote","THIS QUOTE DOES NOT EXIST")));
        finish(root,envelope(root).put("review",ack(batch)).put("memories",new JSONArray().put(wrong)));assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertNull(store.getMemory("piggyback-wrong"));assertTrue(runtime.getMemoryReview().hasPending());assertEquals(1,transport.ids.size());
        wallNow+=1_200_000L;ready();JSONObject next=active();finish(next,envelope(next));assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertEquals(2,transport.ids.size());
    }
    @Test public void unresolvedReviewReadTransfersOneBatchWithoutClaimingProgress()throws Exception {
        source("ordinary transcript needing context");JSONObject root=due();JSONObject original=batch(root);
        finish(root,envelope(root).put("status","working").put("actions",new JSONArray().put(object("id","lookup","name","memory.search","arguments",object("query","ordinary transcript")))));
        JSONObject continuation=active();assertEquals(original.toString(),store.getSetting("memoryReview:"+continuation.getString("external_id"),""));assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        finish(continuation,envelope(continuation).put("review",ack(original)));assertTrue(store.getWatermark(MemoryReviewCoordinator.WATERMARK)>0);assertEquals(2,transport.ids.size());
    }
    @Test public void proposedMemoryWriteMustFinishBeforeAnyCoverageAck()throws Exception {
        source("ordinary current source");JSONObject root=due(),original=batch(root);long id=original.getJSONArray("sourceRanges").getJSONObject(0).getLong("messageId");
        JSONObject rejected=object("key","bad-tool-review","content","unverified claim","kind","fact","sourceRefs",new JSONArray().put(object("type","message","id",id,"quote","THIS QUOTE DOES NOT EXIST")));
        finish(root,envelope(root).put("review",ack(original)).put("actions",new JSONArray().put(object("id","bad-save","name","memory.save","arguments",rejected))));
        assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertNull(store.getMemory("bad-tool-review"));JSONObject continuation=active();assertEquals(original.toString(),store.getSetting("memoryReview:"+continuation.getString("external_id"),""));assertEquals("true",store.getSetting("memoryReviewHadRejected:"+continuation.getString("external_id"),"false"));
    }
    @Test public void reviewedOldSourceAndNeighborAreBoundAtWireAndExcludedOutputIsHeld()throws Exception {
        JSONObject old=source("PIGGYBACK-PRIVATE-4481 ordinary old text"),neighbor=store.addMessage("assistant","PIGGYBACK-NEIGHBOR-1158 old context",null);
        for(int i=0;i<80;i++)source("new unrelated canonical record number "+i);JSONObject root=due();JSONObject original=batch(root);
        String id=root.getString("external_id");String digest=runtime.registerPreparedPayload(id,payload(root));assertTrue(runtime.authorizeFinalPayload(id,digest));assertFalse(runtime.authorizeFinalPayload(id,digest));long marker=Long.parseLong(store.getSetting("dispatchEvidenceSource:"+id,"0"));assertRef(store.getSetting("answerSources:"+marker,""),old);assertRef(store.getSetting("answerSources:"+marker,""),neighbor);
        store.getPrivacyLedger().excludeSource("message",old.getLong("id"),old.getString("content_hash"),"retrieval_exclude","test while actual dispatch active");store.putSetting("autonomy.enabled","false");
        finish(root,envelope(root).put("message","PIGGYBACK-PRIVATE-4481").put("review",ack(original)));assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertTrue(store.events(100).toString().contains("work_output_held"));assertFalse(store.listMessages(100).toString().contains("\"role\":\"assistant\",\"text\":\"PIGGYBACK-PRIVATE-4481\""));assertTrue(store.getRequest(root.getLong("id")).getString("result").contains("PIGGYBACK-PRIVATE-4481"));
    }
    @Test public void batchMutationAndMalformedCachedReviewCannotPassRegistrationOrEffects()throws Exception {
        source("ordinary clean review source");JSONObject root=due();String key="memoryReview:"+root.getString("external_id");String original=store.getSetting(key,"");
        for(String corrupted:new String[]{original+" trailing","{}","{broken"}) {store.putSetting(key,corrupted);assertThrows(MemoryGateway.Blocked.class,()->runtime.registerPreparedPayload(root.getString("external_id"),payload(root)));}
        store.putSetting("autonomy.enabled","false");finish(root,envelope(root).put("actions",new JSONArray().put(object("id","must-not-write","name","files.write","arguments",object("relativePath","blocked.txt","content","bad")))));assertFalse(new File(context.getFilesDir(),"projects/blocked.txt").exists());assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertTrue(store.events(100).toString().contains("work_output_held"));
    }
    @Test public void oldSnapshotCannotResurrectExcludedReviewSource()throws Exception {
        JSONObject forgotten=source("PIGGYBACK-RESTORE-PRIVATE-2274");source("allowed ordinary source after restore");JSONObject old=store.exportSnapshot();store.getPrivacyLedger().excludeSource("message",forgotten.getLong("id"),forgotten.getString("content_hash"),"retrieval_exclude","old snapshot privacy control");store.importSnapshot(old);
        JSONObject root=due();assertFalse(transport.prompts.get(0).contains("PIGGYBACK-RESTORE-PRIVATE-2274"));assertFalse(batch(root).toString().contains("PIGGYBACK-RESTORE-PRIVATE-2274"));assertTrue(batch(root).getLong("cursorStart")>=forgotten.getLong("id"));assertTrue(batch(root).optJSONObject("policyExcludedRange").optBoolean("payloadRead")==false);
    }
    @Test public void newOwnerInputHoldsOldReviewAndKeepsOwnerPromptFreeOfBacklog()throws Exception {
        source("ordinary pending old review");JSONObject root=due(),original=batch(root);JSONObject owner=runtime.receive("new owner correction takes priority",new JSONArray());assertEquals(1,transport.ids.size());
        finish(root,envelope(root).put("review",ack(original)).put("actions",new JSONArray().put(object("id","old","name","files.write","arguments",object("relativePath","old.txt","content","do not execute")))));assertFalse(new File(context.getFilesDir(),"projects/old.txt").exists());assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertEquals(owner.getString("external_id"),transport.ids.get(1));assertEquals("",store.getSetting("memoryReview:"+owner.getString("external_id"),""));assertFalse(transport.prompts.get(1).contains("미검토 기억 배치"));
    }
    @Test public void crossRootGoalProgressInheritsPiggybackOnlySourcePrivacy()throws Exception {
        JSONObject owner=runtime.receive("keep independent pending task",new JSONArray());finish(owner,envelope(owner).put("status","working").put("message","independent task remains pending"));String goalRoot=owner.getString("external_id");assertNotNull(runtime.getGoalLedger().get(goalRoot));
        JSONObject secret=source("PIGGYBACK-GOAL-PRIVATE-9905 ordinary archived detail");for(int i=0;i<80;i++)source("unrelated recent record "+i);JSONObject automatic=due(),original=batch(automatic);
        java.lang.reflect.Field field=AssistantRuntime.class.getDeclaredField("preparedMemory");field.setAccessible(true);java.util.Map<?,?> prepared=(java.util.Map<?,?>)field.get(runtime);MemoryGateway.Prepared cut=(MemoryGateway.Prepared)prepared.get(automatic.getString("external_id"));
        for(int i=0;i<cut.dependencies().length();i++){JSONObject ref=cut.dependencies().getJSONObject(i);assertFalse("private source must arrive through review, not Gateway",ref.optString("type").equals("message")&&ref.optLong("id")==secret.getLong("id"));}
        finish(automatic,envelope(automatic).put("review",ack(original)).put("actions",new JSONArray().put(object("id","update-other-goal","name","tasks.update","arguments",object("rootRequestId",goalRoot,"stage","working","progress","PIGGYBACK-GOAL-PRIVATE-9905")))));assertTrue(runtime.getGoalLedger().get(goalRoot).toString().contains("PIGGYBACK-GOAL-PRIVATE-9905"));
        store.getPrivacyLedger().excludeSource("message",secret.getLong("id"),secret.getString("content_hash"),"retrieval_exclude","forget old source after cross-root progress update");JSONObject listed=runtime.getGoalLedger().list(null);assertFalse(listed.toString(),listed.toString().contains("PIGGYBACK-GOAL-PRIVATE-9905"));assertTrue(listed.toString().contains("blocked_source_excluded"));
    }
    @Test public void escapedLargeBatchIsPartialBoundedAndDoesNotDropMandatoryOwnerInput()throws Exception {
        long oldest=source("ordinary escaped source:"+"\u0000".repeat(30_000)).getLong("id");JSONObject latest=source("L".repeat(95_000));store.putSetting("ownerInputRevision",String.valueOf(latest.getLong("id")));String instructions="control ".repeat(15_000);store.putSetting("customInstructions",instructions);JSONObject root=due();String serialized=store.getSetting("memoryReview:"+root.getString("external_id"),"");assertTrue(serialized.length()<=6000);JSONObject range=batch(root).getJSONArray("sourceRanges").getJSONObject(0);assertEquals(oldest,range.getLong("messageId"));assertTrue(range.getLong("nextOffsetChars")>0);assertTrue(range.getLong("nextOffsetChars")<30_000);String prompt=transport.prompts.get(0);assertTrue(prompt.contains("L".repeat(95_000)));assertTrue(prompt.contains(instructions));assertTrue(prompt.length()<=250_000);assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        assertTrue(prompt.contains("전체 도구 이름 목록(기억 원문을 보존하기 위해 상세 정의를 별도 조회)"));
        java.lang.reflect.Method method=AssistantRuntime.class.getDeclaredMethod("catalog");method.setAccessible(true);JSONArray all=(JSONArray)method.invoke(runtime);
        for(int i=0;i<all.length();i++)assertTrue("Missing capability "+all.getJSONObject(i).getString("name"),prompt.contains('"'+all.getJSONObject(i).getString("name")+'"'));
        assertTrue(store.events(100).toString().contains("tool_catalog_schema_deferred"));assertEquals(1,transport.ids.size());
        assertEquals(latest.getString("content_hash"),store.getMessage(latest.getLong("id")).getString("content_hash"));
        assertTrue(runtime.registerPreparedPayload(root.getString("external_id"),payload(root)).matches("[a-f0-9]{64}"));
    }
    @Test public void disabledImplicitPhoneReadCannotReturnThroughReviewNeighbor()throws Exception {assertPhoneNeighborOmitted(1);}
    @Test public void disabledPhoneCollectionStillAllowsOrdinaryOwnerReviewAndAutonomy()throws Exception {assertPhoneNeighborOmitted(2);}
    @Test public void revokedActualContactsGrantSkipsOnlyPrivateReviewNeighbor()throws Exception {assertPhoneNeighborOmitted(3);}
    @Test public void temporaryDeviceScopeDenialIsNotAcknowledgedAsAnOwnerExclusion()throws Exception {
        Shadows.shadowOf((Application)context).grantPermissions(android.Manifest.permission.READ_CONTACTS);JSONObject event=store.appendDeviceEvent("provider_snapshot","android.contacts",object("displayName","PRIVATE-SOURCE-SCOPE-2487"),100,null),owner=source("ordinary owner source with native provenance");store.putSetting("answerSources:"+owner.getLong("id"),object("version",1,"sources",new JSONArray().put(object("type","device_event","id",event.getLong("id"),"hash",event.getString("content_hash")))).toString());
        store.putSetting("toolPermissions",object("phone_context_read",false).toString());JSONObject unavailable=runtime.getMemoryReview().prepareBatch(6000);assertEquals(0,unavailable.getJSONArray("sourceRanges").length());assertEquals(0,unavailable.getJSONObject("policyExcludedRange").getLong("sourceCount"));assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertEquals(0,store.getPrivacyLedger().capture().exclusionCount);
        store.putSetting("toolPermissions","{}");JSONObject available=runtime.getMemoryReview().prepareBatch(6000);assertEquals(owner.getLong("id"),available.getJSONArray("sourceRanges").getJSONObject(0).getLong("messageId"));assertTrue(runtime.getMemoryReview().acknowledge(available,ack(available),true,0));assertEquals(owner.getLong("id"),store.getWatermark(MemoryReviewCoordinator.WATERMARK));
    }
    private void assertPhoneNeighborOmitted(int policy)throws Exception {
        Shadows.shadowOf((Application)context).grantPermissions(android.Manifest.permission.READ_CONTACTS);source("ordinary owner source for review");JSONObject event=store.appendDeviceEvent("provider_snapshot","android.contacts",object("displayName","PIGGYBACK-PHONE-PRIVATE-8873"),100,null),copy=store.addMessage("assistant","PIGGYBACK-PHONE-PRIVATE-8873",null);
        store.putSetting("answerSources:"+copy.getLong("id"),object("version",1,"sources",new JSONArray().put(object("type","device_event","id",event.getLong("id"),"hash",event.getString("content_hash")))).toString());for(int i=0;i<80;i++)source("unrelated ordinary recent record "+i);
        if(policy==2)store.putSetting("phoneContextEnabled","false");else if(policy==1)store.putSetting("toolPermissions",object("phone_context_read",false).toString());else Shadows.shadowOf((Application)context).denyPermissions(android.Manifest.permission.READ_CONTACTS);JSONObject root=due();assertEquals("autonomy",root.getString("kind"));assertEquals(1,transport.ids.size());assertFalse("private phone neighbor must not enter either context",transport.prompts.get(0).contains("PIGGYBACK-PHONE-PRIVATE-8873"));assertFalse(batch(root).toString().contains("PIGGYBACK-PHONE-PRIVATE-8873"));assertEquals(0,store.getPrivacyLedger().capture().exclusionCount);
    }
    private JSONObject source(String text)throws Exception{return store.addMessage("user",text,null);}
    private JSONObject due()throws Exception {JSONObject cadence=runtime.getAutonomyTickPolicy().snapshot(now());cadence.put("enabled",true).put("nextDueAt",Math.max(0,now()-1_200_000L));store.putSetting(AutonomyTickPolicy.STATE_KEY,cadence.toString());store.putSetting("autonomy.enabled","true");runtime.tick();return active();}
    private JSONObject active()throws Exception{return store.getRequest(runtime.state().getLong("activeStoreId"));}
    private JSONObject batch(JSONObject request)throws Exception{return new JSONObject(store.getSetting("memoryReview:"+request.getString("external_id"),""));}
    private static JSONObject envelope(JSONObject request)throws Exception{return object("protocol","hoyoung.v1","requestId",request.getString("external_id"),"status","idle","message","");}
    private static JSONObject ack(JSONObject batch)throws Exception {JSONArray ranges=new JSONArray();for(int i=0;i<batch.getJSONArray("sourceRanges").length();i++){JSONObject range=batch.getJSONArray("sourceRanges").getJSONObject(i);ranges.put(object("messageId",range.getLong("messageId"),"offsetChars",range.getLong("offsetChars"),"nextOffsetChars",range.getLong("nextOffsetChars"),"contentHash",range.getString("contentHash")));}return object("batchId",batch.getString("batchId"),"sourceRanges",ranges,"noMemoryNeeded",true,"reason","ordinary text contains no lasting facts or obligations");}
    private JSONObject payload(JSONObject request)throws Exception{return object("requestId",request.getString("external_id"),"text",transport.prompts.get(transport.prompts.size()-1),"requiredModel","gpt-6.1-sol","requiredEffort","medium","requiredSpeed","fast","requiresNativeAuthorization",true,"attachments",new JSONArray());}
    private void finish(JSONObject request,JSONObject envelope)throws Exception{runtime.onWebEvent(object("type","completed","requestId",request.getString("external_id"),"text","```hoyoung\n"+envelope+"\n```","rawEnvelopeEligible",true));}
    private void ready()throws Exception {runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",false,"quota",false)));}
    private long now(){return wallNow;}
    private static void assertRef(String raw,JSONObject expected)throws Exception {JSONArray refs=new JSONObject(raw).getJSONArray("sources");boolean found=false;for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);if(ref.optLong("id")==expected.getLong("id")&&expected.getString("content_hash").equals(ref.optString("hash")))found=true;}assertTrue(raw,found);}
    private static JSONObject object(Object...pairs)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static void clear(File file)throws Exception{if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Cannot inspect fixture");for(File child:children)clear(child);}if(!file.delete())throw new java.io.IOException("Cannot clear fixture");}
    private static final class Transport implements AssistantRuntime.Transport {final List<String> ids=new ArrayList<>(),prompts=new ArrayList<>();public void inspect(){}public void send(String id,String prompt){ids.add(id);prompts.add(prompt);}public void openTemporary(){}public void cancel(){}}
}
