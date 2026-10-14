package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import com.hoyoung.assistant.store.MemoryGateway;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Actual queue, original files and SQLite; FakeTransport never performs network/model/phone effects. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class RuntimeOwnerInputIdentityTest {
    private Context context;private HoyoungStore store;private InternalFiles files;private OwnerDraftStore drafts;
    private AssistantRuntime runtime;private FakeTransport transport;
    @Before public void setUp(){context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);deleteTree(context.getFilesDir());store=new HoyoungStore(context);files=new InternalFiles(context);store.putSetting("customInstructions","Identity fixture: preserve exact owner input and do not invent completion.");createRuntime();}
    @After public void tearDown(){runtime.detachTransport();store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);deleteTree(context.getFilesDir());}

    @Test public void queuedDuplicateDoesNotAddOwnerSourceChangeMetadataOrResetProgress()throws Exception{
        JSONArray ids=fileIds("first.txt","first immutable bytes");OwnerDraftStore.Snapshot captured=drafts.edit("queue once",ids);JSONObject first=send(captured);long source=source(first);String revision=store.getSetting("ownerInputRevision","");String metadata=metadata(first),fingerprint=fingerprint(first);
        assertTrue(runtime.getMemoryReview().markAssetNeedsReanalysis(source,new JSONArray(metadata).getJSONObject(0).getString("id"),"fixture reanalysis requested"));String coverage=store.getSetting("assetCoverage:"+source,"");store.putSetting("runtime.progress","fixture existing progress");
        JSONObject duplicate=send(captured);assertEquals(first.getLong("id"),duplicate.getLong("id"));assertEquals("queued",duplicate.getString("status"));assertEquals(1,ownerCount());assertEquals(1,requestCount());assertEquals(revision,store.getSetting("ownerInputRevision",""));assertEquals(metadata,metadata(first));assertEquals(fingerprint,fingerprint(first));assertEquals(coverage,store.getSetting("assetCoverage:"+source,""));assertEquals("fixture existing progress",store.getSetting("runtime.progress",""));assertEquals(0,transport.ids.size());
    }

    @Test public void agentAuthoredComposerSendPreservesOriginalWithoutNewOwnerAuthority()throws Exception{
        JSONObject owner=runtime.receive("real owner instruction",new JSONArray());String ownerRevision=store.getSetting("ownerInputRevision","");String ownerRequest=store.getSetting("latestOwnerRequestId","");
        OwnerDraftStore.Snapshot input=drafts.edit("agent wrote its own composer",new JSONArray(),UiAutomationProvenance.AGENT);
        JSONObject request=runtime.receive(input.text,input.attachments(),input.clientInputId,input.revision,input.inputActor);
        assertEquals("agent-authored",request.getString("kind"));assertEquals(ownerRevision,store.getSetting("ownerInputRevision",""));assertEquals(ownerRequest,store.getSetting("latestOwnerRequestId",""));assertEquals(1,ownerCount());assertEquals(2,count("SELECT count(*) FROM requests"));
        JSONObject original=store.getMessage(source(request));assertEquals("system",original.getString("role"));JSONObject evidence=new JSONObject(original.getString("text"));assertEquals(input.text,evidence.getString("text"));assertEquals(UiAutomationProvenance.AGENT,evidence.getString("inputActor"));assertFalse(evidence.getBoolean("naturalLanguageOwnerUtterance"));
        assertEquals(owner.getLong("id"),store.nextQueuedRequest().getLong("id"));assertEquals(request.getLong("id"),runtime.receive(input.text,input.attachments(),input.clientInputId,input.revision,input.inputActor).getLong("id"));assertEquals(2,count("SELECT count(*) FROM requests"));
    }

    @Test public void humanExplicitlySubmittingGeneratedDraftIsOwnerSteeringWithDistinctAuthorship()throws Exception{
        OwnerDraftStore.Snapshot input=drafts.edit("generated draft",new JSONArray(),UiAutomationProvenance.AGENT);
        expectAnyInputRejection(()->runtime.receive(input.text,input.attachments(),input.clientInputId,input.revision,UiAutomationProvenance.OWNER));
        assertEquals(0,ownerCount());same(input,drafts.read());
        String actor=UiAutomationProvenance.submittedActor(input.inputActor,UiAutomationProvenance.OWNER);assertEquals(UiAutomationProvenance.MIXED,actor);
        JSONObject sent=runtime.receive(input.text,input.attachments(),input.clientInputId,input.revision,actor);assertEquals("user",sent.getString("kind"));assertEquals(1,ownerCount());assertEquals(String.valueOf(source(sent)),store.getSetting("ownerInputRevision","0"));
        JSONObject ownerSource=store.getMessage(source(sent));assertEquals(input.text,ownerSource.getString("text"));assertEquals("user",ownerSource.getString("role"));
        JSONObject originLink=new JSONObject(store.getSetting("inputOrigin:"+source(sent),""));JSONObject origin=new JSONObject(store.getMessage(originLink.getLong("sourceMessageId")).getString("text"));assertEquals("owner",origin.getString("submittedBy"));assertEquals("agent_authored",origin.getString("authoredBy"));assertFalse(origin.getBoolean("allTextAuthoredByOwner"));assertTrue(origin.getBoolean("humanOwnerAuthorized"));
        MemoryGateway gate=new MemoryGateway(store);MemoryGateway.Prepared memory=gate.prepare(sent.getString("external_id"),sent.getString("external_id"),"user",source(sent),input.text,null,24000);assertTrue(memory.context.contains("NATIVE_INPUT_ORIGIN (authorship and human submission are distinct)"));assertTrue(memory.context.contains("agent_authored"));gate.assertFresh(memory);
    }

    @Test public void agentClickOnUnsentHumanDraftDoesNotBecomeNewOwnerIntent()throws Exception{
        OwnerDraftStore.Snapshot input=drafts.edit("not yet sent by owner",new JSONArray());
        String actor=UiAutomationProvenance.submittedActor(input.inputActor,UiAutomationProvenance.AGENT);assertEquals(UiAutomationProvenance.AGENT,actor);
        JSONObject sent=runtime.receive(input.text,input.attachments(),input.clientInputId,input.revision,actor);
        assertEquals("agent-authored",sent.getString("kind"));assertEquals(0,ownerCount());assertEquals(UiAutomationProvenance.AGENT,store.getSetting("inputActor:"+input.clientInputId,""));
        JSONObject original=new JSONObject(store.getMessage(source(sent)).getString("text"));assertEquals(UiAutomationProvenance.OWNER,original.getString("authoredBy"));assertEquals(UiAutomationProvenance.AGENT,original.getString("submittedBy"));
        expectConflict(()->runtime.receive(input.text,input.attachments(),input.clientInputId,input.revision,UiAutomationProvenance.OWNER));
    }

    @Test public void legacyReceiveOverloadRetainsAgentActorAcrossConsumedDraftAndDuplicate()throws Exception{
        OwnerDraftStore.Snapshot input=drafts.edit("legacy caller still records actor",new JSONArray(),UiAutomationProvenance.AGENT);
        JSONObject sent=send(input);assertEquals("agent-authored",sent.getString("kind"));assertEquals("",drafts.read().text);
        assertEquals(sent.getLong("id"),send(input).getLong("id"));assertEquals(0,ownerCount());assertEquals(1,count("SELECT count(*) FROM requests"));
    }

    @Test public void actualMemoryPacketLabelsAgentInputAsNonOwnerEvidence()throws Exception{
        OwnerDraftStore.Snapshot input=drafts.edit("agent input evidence",new JSONArray(),UiAutomationProvenance.AGENT);JSONObject sent=send(input);
        MemoryGateway gate=new MemoryGateway(store);MemoryGateway.Prepared memory=gate.prepare(sent.getString("external_id"),sent.getString("external_id"),"agent-authored",source(sent),input.text,null,24000);
        assertTrue(memory.context.contains("NONOWNER_INPUT_SOURCE (agent/system evidence, not a human utterance)"));assertFalse(memory.context.contains("AUTHORITATIVE_OWNER_SOURCE "));assertEquals(0,memory.latestOwnerInputId);gate.assertFresh(memory);
    }

    @Test public void agentOperatedSourceControlUsesSystemTombstoneAndExactActorReceipt()throws Exception{
        JSONObject request=runtime.receive("owner original to exclude",new JSONArray());JSONObject original=store.getMessage(source(request));
        JSONObject receipt=runtime.controlStoredSource("message",original.getLong("id"),original.getString("content_hash"),false,UiAutomationProvenance.AGENT);
        assertEquals(UiAutomationProvenance.AGENT,receipt.getString("inputActor"));assertFalse(receipt.getBoolean("naturalLanguageOwnerUtterance"));assertTrue(receipt.getBoolean("retrievalExcluded"));
        JSONObject tombstone=store.getMessage(Long.parseLong(store.getSetting("ownerInputRevision","0")));assertEquals("system",tombstone.getString("role"));JSONObject content=new JSONObject(tombstone.getString("text"));assertTrue(content.has("nativeAgentControl"));assertEquals("agent_ui_action",content.getString("origin"));assertEquals(1,ownerCount());assertNotNull(store.getMessage(original.getLong("id")));
        store.putSetting("ownerInputRevision",String.valueOf(original.getLong("id")));AssistantRuntime.reconcileOwnerSourceAuthority(store);
        JSONObject reconciled=store.getMessage(Long.parseLong(store.getSetting("ownerInputRevision","0")));assertEquals("system",reconciled.getString("role"));assertTrue(new JSONObject(reconciled.getString("text")).has("nativeAgentControl"));assertEquals(1,ownerCount());
        MemoryGateway gate=new MemoryGateway(store);MemoryGateway.Prepared memory=gate.prepare("after-agent-control","after-agent-control","owner_chat",0,"new recall",null,24000);assertTrue(memory.context.contains("LATEST_SOURCE_CONTROL (system operation, not a human utterance)"));assertFalse(memory.context.contains("LATEST_OWNER_INPUT (takes precedence"));gate.assertFresh(memory);
    }

    @Test public void duplicateAfterActualCompletedFakeTurnKeepsStatusResultAndOneDispatch()throws Exception{
        ready();OwnerDraftStore.Snapshot captured=drafts.edit("do once",new JSONArray());JSONObject first=send(captured);runtime.onWebEvent(object("type","accepted","requestId",first.getString("external_id")));runtime.onWebEvent(object("type","completed","requestId",first.getString("external_id"),"text","fixture task result","rawEnvelopeEligible",true));
        JSONObject before=store.getRequest(first.getLong("id"));assertEquals("completed",before.getString("status"));String revision=store.getSetting("ownerInputRevision","");String progress=store.getSetting("runtime.progress","");
        JSONObject duplicate=send(captured);assertEquals(first.getLong("id"),duplicate.getLong("id"));assertEquals("completed",duplicate.getString("status"));assertEquals(before.getString("result"),duplicate.getString("result"));assertEquals(before.getLong("updated_at"),duplicate.getLong("updated_at"));assertEquals(revision,store.getSetting("ownerInputRevision",""));assertEquals(progress,store.getSetting("runtime.progress",""));assertEquals(1,ownerCount());assertEquals(1,requestCount());assertEquals(1,transport.ids.size());assertTrue(transport.persistedBeforeSend);
    }

    @Test public void uncertainDuplicateNeverQueuesAgainOrClearsUncertainty()throws Exception{
        ready();OwnerDraftStore.Snapshot captured=drafts.edit("external receipt unknown",new JSONArray());JSONObject first=send(captured);runtime.onWebEvent(object("type","error","requestId",first.getString("external_id"),"code","DISPATCH_ACK_UNCERTAIN"));
        JSONObject before=store.getRequest(first.getLong("id"));String revision=store.getSetting("ownerInputRevision","");assertEquals("uncertain",before.getString("status"));assertTrue(runtime.state().getBoolean("uncertaintyBlocked"));
        JSONObject duplicate=send(captured);assertEquals("uncertain",duplicate.getString("status"));assertEquals(before.getString("result"),duplicate.getString("result"));assertEquals(before.getLong("updated_at"),duplicate.getLong("updated_at"));assertEquals(revision,store.getSetting("ownerInputRevision",""));ready();runtime.tick();assertEquals(1,transport.ids.size());assertEquals(1,ownerCount());assertTrue(runtime.state().getBoolean("uncertaintyBlocked"));
    }

    @Test public void concurrentSameClientCohortProducesOneOwnerRecordOneRequestAndOneDispatch()throws Exception{
        JSONArray ids=fileIds("race.txt","shared original");OwnerDraftStore.Snapshot captured=drafts.edit("double taps and competing activity",ids);ExecutorService workers=Executors.newFixedThreadPool(6);CyclicBarrier barrier=new CyclicBarrier(6);
        try{List<Future<JSONObject>> futures=new ArrayList<>();for(int i=0;i<6;i++)futures.add(workers.submit(()->{barrier.await(10,TimeUnit.SECONDS);return send(captured);}));long id=futures.get(0).get(25,TimeUnit.SECONDS).getLong("id");for(Future<JSONObject> future:futures)assertEquals(id,future.get(25,TimeUnit.SECONDS).getLong("id"));}
        finally{workers.shutdownNow();}
        assertEquals(1,ownerCount());assertEquals(1,requestCount());assertEquals("",drafts.read().text);assertEquals("[]",drafts.read().attachmentsJson);assertEquals(captured.revision+1,drafts.read().revision);assertEquals(0,transport.ids.size());ready();assertEquals(1,transport.ids.size());assertEquals(captured.clientInputId,transport.ids.get(0));assertTrue(transport.persistedBeforeSend);
    }

    @Test public void sameClientIdWithDifferentRawTextIsRejectedWithoutChangingOriginal()throws Exception{
        OwnerDraftStore.Snapshot captured=drafts.edit("exact raw text",new JSONArray());JSONObject first=send(captured);String revision=store.getSetting("ownerInputRevision","");
        expectConflict(()->runtime.receive("exact raw text ",new JSONArray(),captured.clientInputId,captured.revision));expectConflict(()->runtime.receive("different",new JSONArray(),captured.clientInputId,captured.revision));
        assertEquals(1,ownerCount());assertEquals(1,requestCount());assertEquals("exact raw text",store.getMessage(source(first)).getString("text"));assertEquals(revision,store.getSetting("ownerInputRevision",""));assertEquals("queued",store.getRequest(first.getLong("id")).getString("status"));
    }

    @Test public void changedWorkingFileBytesCannotReuseInputIdentityAndOldImmutableFileSurvives()throws Exception{
        JSONArray ids=fileIds("changing.txt","ORIGINAL bytes");OwnerDraftStore.Snapshot captured=drafts.edit("inspect file",ids);JSONObject first=send(captured);JSONObject original=new JSONArray(metadata(first)).getJSONObject(0);String previousFingerprint=fingerprint(first);
        files.writeFile("changing.txt","REPLACED bytes");expectConflict(()->send(captured));assertEquals(previousFingerprint,fingerprint(first));assertEquals(original.toString(),new JSONArray(metadata(first)).getJSONObject(0).toString());ByteArrayOutputStream out=new ByteArrayOutputStream();files.exportFile(original.getString("id"),out);assertEquals("ORIGINAL bytes",out.toString(StandardCharsets.UTF_8.name()));assertEquals(1,ownerCount());assertEquals(1,requestCount());
    }

    @Test public void sameClientIdWithReorderedOrAddedAssetsIsRejected()throws Exception{
        JSONArray ids=fileIds("first.txt","first","second.txt","second");OwnerDraftStore.Snapshot captured=drafts.edit("ordered assets",ids);JSONObject first=send(captured);String previousMetadata=metadata(first),previousFingerprint=fingerprint(first);
        expectConflict(()->runtime.receive(captured.text,new JSONArray().put("second.txt").put("first.txt"),captured.clientInputId,captured.revision));expectConflict(()->runtime.receive(captured.text,new JSONArray(),captured.clientInputId,captured.revision));files.writeFile("third.txt","third");expectConflict(()->runtime.receive(captured.text,new JSONArray(ids.toString()).put("third.txt"),captured.clientInputId,captured.revision));
        assertEquals(previousMetadata,metadata(first));assertEquals(previousFingerprint,fingerprint(first));assertEquals(1,ownerCount());assertEquals(1,requestCount());
    }

    @Test public void photoOnlyRawEmptyTextDiffersFromGeneratedPlaceholderAndWhitespace()throws Exception{
        JSONArray ids=fileIds("photo.png","fixture opaque image bytes");OwnerDraftStore.Snapshot captured=drafts.edit("",ids);JSONObject first=send(captured);assertEquals("첨부 파일을 확인해 줘.",first.getString("text"));
        expectConflict(()->runtime.receive("첨부 파일을 확인해 줘.",ids,captured.clientInputId,captured.revision));expectConflict(()->runtime.receive(" ",ids,captured.clientInputId,captured.revision));assertEquals(first.getLong("id"),send(captured).getLong("id"));assertEquals(1,ownerCount());assertEquals(1,requestCount());
    }

    @Test public void frozenMetadataRepresentationAndIncidentalAnnotationsDoNotChangeIdentity()throws Exception{
        JSONArray ids=fileIds("annotation.txt","content");OwnerDraftStore.Snapshot captured=drafts.edit("stable original",ids);JSONObject first=send(captured);JSONObject asset=new JSONArray(metadata(first)).getJSONObject(0);JSONObject reordered=new JSONObject();String[] keys={"mime","size","sourceName","sha256","id"};for(String key:keys)reordered.put(key,asset.get(key));reordered.put("updatedAt",17).put("createdAt",19).put("sourceMessageId",999_999).put("callerAnnotation","fixture only");
        JSONObject duplicate=runtime.receive(captured.text,new JSONArray().put(reordered),captured.clientInputId,captured.revision);assertEquals(first.getLong("id"),duplicate.getLong("id"));assertEquals(1,ownerCount());assertEquals(asset.toString(),new JSONArray(metadata(first)).getJSONObject(0).toString());
    }

    @Test public void aToBToAIsANewIntentAndOlderSendCannotEraseNewA()throws Exception{
        JSONArray ids=fileIds("one.txt","one");OwnerDraftStore.Snapshot originalA=drafts.edit("A",ids);drafts.edit("B",ids);OwnerDraftStore.Snapshot newerA=drafts.edit("A",ids);assertNotEquals(originalA.clientInputId,newerA.clientInputId);
        JSONObject first=send(originalA);same(newerA,drafts.read());JSONObject second=send(newerA);assertNotEquals(first.getLong("id"),second.getLong("id"));assertEquals(2,ownerCount());assertEquals(2,requestCount());assertEquals("",drafts.read().text);assertEquals("[]",drafts.read().attachmentsJson);assertEquals(newerA.revision+1,drafts.read().revision);
    }

    @Test public void committedInputAndConsumeSurviveRecreationBeforeUiCallback()throws Exception{
        JSONArray ids=fileIds("recreate.txt","persisted file");OwnerDraftStore.Snapshot captured=drafts.edit("send then rotate before callback",ids);JSONObject first=send(captured);OwnerDraftStore.Snapshot committed=drafts.read();assertEquals("",committed.text);assertEquals("[]",committed.attachmentsJson);
        restart();same(committed,drafts.read());JSONObject duplicate=send(captured);assertEquals(first.getLong("id"),duplicate.getLong("id"));assertEquals(1,ownerCount());assertEquals(1,requestCount());OwnerDraftStore.Snapshot newInput=drafts.edit("new input after recreation",ids);send(captured);same(newInput,drafts.read());ready();assertEquals(1,transport.ids.size());assertEquals(captured.clientInputId,transport.ids.get(0));
    }

    @Test public void repeatedUncertainInputAfterProcessRecreationStillCannotReplay()throws Exception{
        ready();OwnerDraftStore.Snapshot captured=drafts.edit("uncertain across process death",new JSONArray());JSONObject first=send(captured);runtime.onWebEvent(object("type","error","requestId",captured.clientInputId,"code","DISPATCH_ACK_UNCERTAIN"));restart();
        JSONObject duplicate=send(captured);assertEquals(first.getLong("id"),duplicate.getLong("id"));assertEquals("uncertain",duplicate.getString("status"));ready();runtime.tick();assertEquals(0,transport.ids.size());assertEquals(1,ownerCount());assertEquals(1,requestCount());assertTrue(runtime.state().getBoolean("uncertaintyBlocked"));
    }

    @Test public void failedConsumeMirrorRollsBackRequestMessageFingerprintAndDraftTogether()throws Exception{
        JSONArray ids=fileIds("rollback.txt","original remains reusable");OwnerDraftStore.Snapshot captured=drafts.edit("enqueue atomic with draft",ids);
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_runtime_draft_mirror BEFORE INSERT ON settings WHEN NEW.setting_key='ui.attachments' BEGIN SELECT RAISE(ABORT,'fixture consume failure'); END");
        try{try{send(captured);fail("Enqueue must roll back when consume fails");}catch(SQLiteException expected){assertTrue(expected.toString().contains("fixture consume failure"));}}
        finally{store.getWritableDatabase().execSQL("DROP TRIGGER fail_runtime_draft_mirror");}
        same(captured,drafts.read());assertEquals(0,ownerCount());assertEquals(0,requestCount());assertNull(store.getSetting("inputFingerprint:"+captured.clientInputId,null));assertNull(store.getSetting("requestSource:"+captured.clientInputId,null));assertNull(store.getSetting("attachmentMetadata:"+captured.clientInputId,null));
        JSONObject retried=send(captured);assertEquals(1,ownerCount());assertEquals(1,requestCount());assertEquals(retried.getLong("id"),send(captured).getLong("id"));assertEquals("",drafts.read().text);assertEquals("[]",drafts.read().attachmentsJson);
    }

    @Test public void defaultTwoArgumentReceiveRemainsSeparateIntentForRepeatedText()throws Exception{
        JSONObject first=runtime.receive("intentional repeat",new JSONArray()),second=runtime.receive("intentional repeat",new JSONArray());assertNotEquals(first.getString("external_id"),second.getString("external_id"));assertEquals(2,ownerCount());assertEquals(2,requestCount());assertNull(store.getSetting(OwnerDraftStore.STATE_KEY,null));
    }

    @Test public void falseImmutableHashOrSizeFailsBeforeDuplicateLookupWithoutChangingStoredIntent()throws Exception{
        OwnerDraftStore.Snapshot captured=drafts.edit("integrity first",fileIds("checked.txt","verified bytes"));JSONObject first=send(captured);JSONObject original=new JSONArray(metadata(first)).getJSONObject(0);String savedFingerprint=fingerprint(first);
        for(JSONObject changed:new JSONObject[]{new JSONObject(original.toString()).put("sha256","0".repeat(64)),new JSONObject(original.toString()).put("size",original.getLong("size")+1)}){
            try{runtime.receive(captured.text,new JSONArray().put(changed),captured.clientInputId,captured.revision);fail("False binary claims must fail before enqueue");}catch(IllegalStateException expected){assertTrue(expected.getCause()instanceof java.io.IOException);}
        }
        assertEquals(1,ownerCount());assertEquals(1,requestCount());assertEquals(savedFingerprint,fingerprint(first));assertEquals(original.toString(),new JSONArray(metadata(first)).getJSONObject(0).toString());
    }

    @Test public void matchingCurrentCohortWithWrongRawTextOrAttachmentOrderCannotConsumeRealDraft()throws Exception{
        JSONArray ids=fileIds("first.txt","first","second.txt","second");OwnerDraftStore.Snapshot current=drafts.edit("authoritative unsent intent",ids);
        expectAnyInputRejection(()->runtime.receive("changed before enqueue",ids,current.clientInputId,current.revision));same(current,drafts.read());assertEquals(0,ownerCount());assertEquals(0,requestCount());
        expectAnyInputRejection(()->runtime.receive(current.text,new JSONArray().put("second.txt").put("first.txt"),current.clientInputId,current.revision));same(current,drafts.read());assertEquals(0,ownerCount());assertEquals(0,requestCount());assertNull(store.getSetting("inputFingerprint:"+current.clientInputId,null));
        JSONObject accepted=send(current);assertEquals(current.clientInputId,accepted.getString("external_id"));assertEquals(1,ownerCount());assertEquals("",drafts.read().text);assertEquals("[]",drafts.read().attachmentsJson);
    }

    @Test public void recoveryRequiredCurrentInputCannotBeSubmittedThroughRuntimeBypassingUi()throws Exception{
        store.putSetting("ui.draft","review before sending");store.putSetting("ui.attachments","broken saved selection");OwnerDraftStore.Snapshot recovered=drafts.read();assertTrue(recovered.recoveryRequired);
        expectAnyInputRejection(()->send(recovered));same(recovered,drafts.read());assertTrue(drafts.read().recoveryRequired);assertEquals(0,ownerCount());assertEquals(0,requestCount());assertEquals("broken saved selection",store.getSetting("ui.attachments",""));
    }

    @Test public void wrongRevisionForCurrentIdCannotPoisonLaterLegitimateSend()throws Exception{
        OwnerDraftStore.Snapshot current=drafts.edit("real current intent",fileIds("current.txt","current bytes"));
        for(long wrong:new long[]{-1,current.revision-1,current.revision+1}){
            expectAnyInputRejection(()->runtime.receive("injected replacement",current.attachments(),current.clientInputId,wrong));same(current,drafts.read());assertEquals(0,ownerCount());assertEquals(0,requestCount());assertNull(store.getSetting("inputFingerprint:"+current.clientInputId,null));
        }
        JSONObject legitimate=send(current);assertEquals(current.clientInputId,legitimate.getString("external_id"));assertEquals(current.text,store.getMessage(source(legitimate)).getString("text"));assertEquals(1,ownerCount());assertEquals(1,requestCount());assertEquals("",drafts.read().text);
    }

    private void createRuntime(){drafts=new OwnerDraftStore(store);runtime=new AssistantRuntime(context,store,files,new PhoneTools(context,store),Runnable::run);transport=new FakeTransport();runtime.attachTransport(transport);}
    private void restart(){runtime.detachTransport();store.close();store=new HoyoungStore(context);files=new InternalFiles(context);createRuntime();}
    private JSONObject send(OwnerDraftStore.Snapshot input){return runtime.receive(input.text,input.attachments(),input.clientInputId,input.revision);}
    private void ready()throws Exception{runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",false,"quota",false)));}
    private JSONArray fileIds(String...pairs)throws Exception{JSONArray ids=new JSONArray();for(int i=0;i<pairs.length;i+=2){files.writeFile(pairs[i],pairs[i+1]);ids.put(pairs[i]);}return ids;}
    private long source(JSONObject request){return Long.parseLong(store.getSetting("requestSource:"+request.optString("external_id"),"0"));}
    private String metadata(JSONObject request){return store.getSetting("attachmentMetadata:"+request.optString("external_id"),"missing");}
    private String fingerprint(JSONObject request){return store.getSetting("inputFingerprint:"+request.optString("external_id"),"missing");}
    private long ownerCount(){return count("SELECT count(*) FROM messages WHERE role='user'");}
    private long requestCount(){return count("SELECT count(*) FROM requests WHERE kind='user'");}
    private long count(String sql){try(Cursor rows=store.getReadableDatabase().rawQuery(sql,null)){assertTrue(rows.moveToFirst());return rows.getLong(0);}}
    private static JSONObject object(Object...pairs)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static void same(OwnerDraftStore.Snapshot a,OwnerDraftStore.Snapshot b){assertEquals(a.text,b.text);assertEquals(a.attachmentsJson,b.attachmentsJson);assertEquals(a.clientInputId,b.clientInputId);assertEquals(a.revision,b.revision);}
    private interface Attempt{void run()throws Exception;}
    private static void expectConflict(Attempt attempt)throws Exception{try{attempt.run();fail("Same input identity with another payload must fail");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("identity"));}}
    private static void expectAnyInputRejection(Attempt attempt)throws Exception{try{attempt.run();fail("Captured current input must not be replaced by another payload");}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
    private static void deleteTree(File file){if(file==null||!file.exists())return;File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);if(!file.delete())throw new IllegalStateException("Could not remove fixture file");}
    private final class FakeTransport implements AssistantRuntime.Transport{
        final List<String> ids=new CopyOnWriteArrayList<>();boolean persistedBeforeSend=true;
        public void inspect(){}public void openTemporary(){}public void cancel(){}
        public void send(String id,String prompt){try(Cursor row=store.getReadableDatabase().rawQuery("SELECT status FROM requests WHERE external_id=?",new String[]{id})){persistedBeforeSend&=row.moveToFirst()&&"dispatching".equals(row.getString(0));}ids.add(id);}
    }
}
