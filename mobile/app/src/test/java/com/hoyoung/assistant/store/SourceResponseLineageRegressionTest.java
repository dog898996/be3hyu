package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.runtime.AssistantRuntime;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Actual native runtime publication and later source-derived privacy; transport calls no model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SourceResponseLineageRegressionTest {
    private Context context;private HoyoungStore store;private AssistantRuntime runtime;private Transport transport;
    @Before public void before()throws Exception{context=org.robolectric.RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());context.getFilesDir().mkdirs();store=new HoyoungStore(context);store.putSetting("autonomy.enabled","false");store.putSetting("memoryReviewEnabled","false");runtime=new AssistantRuntime(context,store,new InternalFiles(context),new PhoneTools(context,store),Runnable::run);transport=new Transport();runtime.attachTransport(transport);ready();drain();}
    @After public void after()throws Exception{drain();store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());}
    private void ready(){runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",false,"quota",false)));}
    private static void drain(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private JSONObject request(String text){JSONObject result=runtime.receive(text,new JSONArray());drain();assertEquals(result.optString("external_id"),transport.ids.get(transport.ids.size()-1));return result;}
    private void complete(JSONObject request,String reply)throws Exception{String id=request.getString("external_id");runtime.onWebEvent(object("type","accepted","requestId",id));drain();JSONObject envelope=object("protocol","hoyoung.v1","requestId",id,"status","completed","message",reply,"actions",new JSONArray(),"memories",new JSONArray(),"answerSources",new JSONArray());runtime.onWebEvent(object("type","completed","requestId",id,"text",envelope.toString(),"rawEnvelopeEligible",true));drain();}
    private JSONObject assistant(String requestId){try(android.database.Cursor row=store.getReadableDatabase().rawQuery("SELECT id FROM messages WHERE role='assistant' AND request_id=? ORDER BY id DESC LIMIT 1",new String[]{requestId})){return row.moveToFirst()?store.getMessage(row.getLong(0)):null;}}
    private void exclude(String type,long id,String hash)throws Exception{store.getPrivacyLedger().excludeSource(type,id,hash,"retrieval_exclude","explicit owner exclusion");}
    private boolean visible(JSONObject source)throws Exception{try(HoyoungStore.ReadSnapshot read=store.openReadSnapshot()){return SourceRecordVisibility.allowed(read.database,store.getPrivacyLedger().capture(),"message",source);}}

    @Test public void nativeAnswerBindsReferencedOldSourceEvenWhenItsQuestionParentRemainsAllowed()throws Exception{
        long old=store.addMessage("user","The cobalt archive value is ORIGINAL-7318.",null).getLong("id");JSONObject question=request("What is the cobalt archive value?");assertTrue(transport.prompts.get(transport.prompts.size()-1).contains("ORIGINAL-7318"));complete(question,"The cobalt archive value is ORIGINAL-7318.");
        JSONObject reply=assistant(question.getString("external_id"));assertNotNull(reply);JSONObject lineage=new JSONObject(store.getSetting("answerSources:"+reply.getLong("id"),""));assertEquals(1,lineage.getInt("version"));assertFalse(lineage.getString("memoryFingerprint").isEmpty());boolean nativeOldReference=false;
        JSONArray references=lineage.getJSONArray("sources");for(int i=0;i<references.length();i++){JSONObject ref=references.getJSONObject(i);if("message".equals(ref.getString("type"))&&old==ref.getLong("id")){assertEquals(store.getMessage(old).getString("content_hash"),ref.getString("hash"));nativeOldReference=true;}}assertTrue("An empty model-supplied answerSources must not replace native evidence",nativeOldReference);assertTrue(visible(reply));
        long questionSource=Long.parseLong(store.getSetting("requestSource:"+question.getString("external_id"),"0"));exclude("message",old,store.getMessage(old).getString("content_hash"));assertTrue(store.getPrivacyLedger().capture().allowedSource("message",questionSource,store.getMessage(questionSource).getString("content_hash")));assertFalse(visible(reply));
        MemoryGateway.Prepared packet=new MemoryGateway(store).prepare("new-dispatch","new-root","user",0,"hello",null,80000);assertFalse(packet.context.contains("ORIGINAL-7318"));assertFalse(packet.context.contains("task-result:"+question.getString("external_id")));
    }
    @Test public void exclusionWhileProviderIsWorkingHoldsReplyAndMemoryPromotion()throws Exception{
        long old=store.addMessage("user","The cobalt archive value is INFLIGHT-9221.",null).getLong("id");JSONObject question=request("What is the cobalt archive value?");exclude("message",old,store.getMessage(old).getString("content_hash"));complete(question,"The cobalt archive value is INFLIGHT-9221.");
        assertNull(assistant(question.getString("external_id")));assertNull(store.getMemory("task-result:"+question.getString("external_id")));assertTrue(store.getRequest(question.getLong("id")).getString("result").contains("INFLIGHT-9221"));boolean held=false;JSONArray events=store.events(100);for(int i=0;i<events.length();i++)if("work_output_held".equals(events.getJSONObject(i).optString("type")))held=true;assertTrue(held);
    }
    @Test public void deviceLineageAndMalformedRecordedLineageFailClosed()throws Exception{
        JSONObject device=store.appendDeviceEvent("notification","phone",object("text","DEVICE-REFERENCED-5221"),100,null);JSONObject reply=store.addMessage("assistant","DEVICE-REFERENCED-5221 copied observation",null);long replyId=reply.getLong("id");
        store.putSetting("answerSources:"+replyId,object("version",1,"sources",new JSONArray().put(object("type","device_event","id",device.getLong("id"),"hash",device.getString("content_hash"))),"memoryFingerprint","native-evidence").toString());assertTrue(visible(reply));exclude("device_event",device.getLong("id"),device.getString("content_hash"));assertFalse(visible(reply));
        JSONObject chained=store.addMessage("assistant","DEVICE-REFERENCED-5221 copied from prior answer",null);
        store.putSetting("answerSources:"+chained.getLong("id"),object("version",1,"sources",new JSONArray().put(object("type","message","id",replyId,"hash",reply.getString("content_hash"))),"memoryFingerprint","native-chain").toString());assertFalse("A transitive known answer copy must not resurrect excluded source",visible(chained));
        store.putSetting("answerSources:"+replyId,"{malformed");assertFalse(visible(reply));store.putSetting("answerSources:"+replyId,object("version",1,"sources",new JSONArray().put(object("type","message","id",1,"hash","invalid"))).toString());assertFalse(visible(reply));
        JSONObject allowedOwner=store.addMessage("user","Allowed owner source without excluded ancestors",null);
        store.putSetting("answerSources:"+replyId,object("version",1,"sources",new JSONArray().put(object("type","message","id",allowedOwner.getLong("id"),"hash",""))).toString());boolean blankHashAccepted=visible(reply);
        JSONObject last=allowedOwner;
        for(int i=0;i<130;i++){JSONObject next=store.addMessage("assistant","Allowed reply chain "+i,null);store.putSetting("answerSources:"+next.getLong("id"),object("version",1,"sources",new JSONArray().put(object("type","message","id",last.getLong("id"),"hash",last.getString("content_hash"))),"memoryFingerprint","native-chain").toString());last=next;}
        boolean longChainAllowed=visible(last);assertTrue("Recorded hashes must be exact and unrelated exclusions must preserve legitimate long history: blankHashAccepted="+blankHashAccepted+", longChainAllowed="+longChainAllowed,!blankHashAccepted&&longChainAllowed);
    }
    @Test public void rawSqlOwnNotificationCopyUsesReplyAncestryAndCyclesFailClosed()throws Exception{
        JSONObject owner=store.addMessage("user","OWNER-SECRET-1324",null),reply=store.addMessage("assistant","OWNER-SECRET-1324 copied answer",null),independent=store.addMessage("assistant","unrelated allowed text",null);
        store.putSetting("answerSources:"+reply.getLong("id"),object("version",1,"sources",new JSONArray().put(object("type","message","id",owner.getLong("id"),"hash",owner.getString("content_hash"))),"memoryFingerprint","native-evidence").toString());
        JSONObject event=store.appendDeviceEvent("notification","phone",object("package","com.hoyoung.assistant","sourceReferencePresent",true,"assistantMessageId",reply.getLong("id"),"assistantSourceHash",reply.getString("content_hash"),"text","NOTIFICATION-COPY-6784"),100,null);
        try(android.database.Cursor raw=store.getReadableDatabase().rawQuery("SELECT payload FROM device_events WHERE id=?",new String[]{String.valueOf(event.getLong("id"))})){assertTrue(raw.moveToFirst());event.put("payload",raw.getString(0));}
        assertTrue(event.get("payload") instanceof String);exclude("message",owner.getLong("id"),owner.getString("content_hash"));
        try(HoyoungStore.ReadSnapshot read=store.openReadSnapshot()){assertFalse(SourceRecordVisibility.allowed(read.database,store.getPrivacyLedger().capture(),"device_event",event));}
        MemoryGateway.Prepared packet=new MemoryGateway(store,store.getPrivacyLedger(),row->true).prepare("notification-dispatch","notification-root","user",0,"notification",null,80000);assertFalse(packet.context.contains("NOTIFICATION-COPY-6784"));assertTrue(visible(independent));
        store.putSetting("answerSources:"+independent.getLong("id"),object("version",1,"sources",new JSONArray().put(object("type","message","id",independent.getLong("id"),"hash",independent.getString("content_hash")))).toString());assertFalse("Corrupt self-referential lineage must not loop or allow",visible(independent));
    }
    @Test public void legacyUnboundReceiptArchivesFollowNativeRootAndPreviousResultLinks()throws Exception{
        JSONObject owner=store.addMessage("user","LEGACY-PRIVATE-7352",null);
        store.putSetting("requestSource:legacy-owner-root",String.valueOf(owner.getLong("id")));
        JSONObject receipt=store.addMessage("system",object("receiptKey","actionReceipt:legacy-owner-root:read-one",
                "rawReceipt",object("status","completed","result","LEGACY-PRIVATE-7352").toString()).toString(),null);
        JSONObject prior=store.addMessage("system",object("receiptKey","actionReceipt:other-legacy-root:read-two",
                "rawReceipt",object("previousReceiptSourceMessageId",receipt.getLong("id"),"status","abandoned").toString()).toString(),null);
        assertTrue(visible(receipt));assertTrue(visible(prior));
        exclude("message",owner.getLong("id"),owner.getString("content_hash"));
        assertFalse(visible(receipt));assertFalse(visible(prior));
        MemoryGateway.Prepared packet=new MemoryGateway(store).prepare("legacy-archive-check","legacy-archive-root","user",0,"LEGACY-PRIVATE",null,80000);
        assertFalse(packet.context.contains("LEGACY-PRIVATE-7352"));
        JSONObject unbound=store.addMessage("system",object("receiptKey","actionReceipt:missing-root:read-three",
                "rawReceipt",object("status","completed","result","UNKNOWN-PRIVATE-8932").toString()).toString(),null);
        assertFalse("An archive without native provenance cannot certify privacy",visible(unbound));
        assertTrue(visible(store.addMessage("user","Unrelated allowed owner source",null)));
    }
    @Test public void ownReplyNotificationRequiresExactSourceGenerationIncludingLegacyRecords()throws Exception{
        JSONObject reply=store.addMessage("assistant","generation-bound private reply",null);
        JSONObject valid=object("package","com.hoyoung.assistant","sourceKind","assistant_reply","sourceReferencePresent",true,
                "assistantMessageId",reply.getLong("id"),"assistantSourceHash",reply.getString("content_hash"));
        JSONObject good=store.appendDeviceEvent("notification","phone",valid,200,null);
        try(HoyoungStore.ReadSnapshot read=store.openReadSnapshot()){assertTrue(SourceRecordVisibility.allowed(read.database,store.getPrivacyLedger().capture(),"device_event",good));}
        JSONObject legacy=new JSONObject(valid.toString());legacy.remove("assistantSourceHash");
        JSONObject missingHash=store.appendDeviceEvent("notification","phone",legacy,201,null);
        JSONObject otherGeneration=new JSONObject(valid.toString()).put("assistantSourceHash","0".repeat(64));
        JSONObject changed=store.appendDeviceEvent("notification","phone",otherGeneration,202,null);
        try(HoyoungStore.ReadSnapshot read=store.openReadSnapshot()){
            assertFalse(SourceRecordVisibility.allowed(read.database,store.getPrivacyLedger().capture(),"device_event",missingHash));
            assertFalse(SourceRecordVisibility.allowed(read.database,store.getPrivacyLedger().capture(),"device_event",changed));
        }
    }
    private static JSONObject object(Object...kv){JSONObject r=new JSONObject();try{for(int i=0;i<kv.length;i+=2)r.put((String)kv[i],kv[i+1]);}catch(Exception error){throw new IllegalStateException(error);}return r;}
    private static void delete(File f)throws Exception{if(!f.exists())return;if(f.isDirectory()){File[] children=f.listFiles();if(children==null)throw new java.io.IOException("Unreadable fixture");for(File child:children)delete(child);}if(!f.delete())throw new java.io.IOException("Fixture cleanup failed");}
    private static final class Transport implements AssistantRuntime.Transport{final List<String>ids=new ArrayList<>(),prompts=new ArrayList<>();public void inspect(){}public void send(String id,String prompt){ids.add(id);prompts.add(prompt);}public void openTemporary(){}public void cancel(){}}
}
