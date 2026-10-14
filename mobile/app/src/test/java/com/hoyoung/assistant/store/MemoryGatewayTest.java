package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Real native SQLite reads and prepared-context barriers; no account or model is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryGatewayTest {
    private Context context;private HoyoungStore store;private MemoryGateway gateway;
    @Before public void before()throws Exception{
        context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());
        assertTrue(context.getFilesDir().mkdirs()||context.getFilesDir().isDirectory());store=new HoyoungStore(context);gateway=new MemoryGateway(store);
    }
    @After public void after()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());}
    private MemoryGateway.Prepared prepare(String text)throws Exception{return gateway.prepare("dispatch-1","root-1","user",0,text,null,80_000,1_000_000);}
    private long source(String text)throws Exception{return store.addMessage("user",text,null).getLong("id");}
    private JSONObject memory(String key,String text,String kind,long source)throws Exception{return store.upsertMemory(key,text,kind,new JSONArray().put(source));}
    private static JSONObject receipt(MemoryGateway.Prepared p,String name)throws Exception{JSONArray all=p.receipts();for(int i=0;i<all.length();i++)if(name.equals(all.getJSONObject(i).getString("layer")))return all.getJSONObject(i);throw new AssertionError("Missing layer "+name);}
    private void blocked(MemoryGateway.Prepared p,String reason)throws Exception{try{gateway.assertFresh(p,1_000_001);fail("Old context must not be released");}catch(MemoryGateway.Blocked expected){assertEquals(reason,expected.reason);}}

    @Test public void fiveActualEmptyReadsStillAllowAnOrdinaryReply()throws Exception{
        MemoryGateway.Prepared p=prepare("안녕");assertEquals(5,p.receipts().length());Set<String> layers=new HashSet<>();
        for(int i=0;i<5;i++){JSONObject r=p.receipts().getJSONObject(i);assertTrue(layers.add(r.getString("layer")));assertEquals("empty",r.getString("status"));assertTrue(r.getJSONArray("queries").length()>0);assertEquals(p.snapshotId,r.getString("snapshotId"));assertEquals("canonical_query_scope_at_snapshot",r.getString("coverage"));}
        assertTrue(p.context.contains("안녕"));gateway.assertFresh(p,1_000_001);
    }

    @Test public void everyLayerCarriesItsRealCanonicalEvidence()throws Exception{
        long raw=source("오래된 원문 조건은 달빛접수 7138이다.");
        memory("semantic", "달빛접수 개인 사실", "fact",raw);memory("episode", "달빛접수 프로젝트 결정", "project",raw);
        memory("style-answer", "달빛접수 답변은 공식문서 존댓말", "preference",raw);
        store.putSetting("customInstructions","달빛접수 사용자 형식 지침");
        store.putSetting("activeGoal:root-1",new JSONObject().put("rootRequestId","root-1").put("sourceMessageId",raw).put("stage","blocked").put("progress","달빛접수는 재실행 금지").toString());
        MemoryGateway.Prepared p=prepare("달빛접수");
        for(String name:new String[]{"verbatim","semantic","episodic","procedural","active"})assertEquals("success",receipt(p,name).getString("status"));
        for(String evidence:new String[]{"7138","semantic","episode","style-answer","사용자 형식 지침","재실행 금지"})assertTrue(evidence,p.context.contains(evidence));
        gateway.assertFresh(p,1_000_001);
    }

    @Test public void authoritativeInputIsNeverLostOrDuplicatedAsRecentText()throws Exception{
        String text="현재 사용자 전용 원문 기호 3920";long id=source(text);store.putSetting("ownerInputRevision",String.valueOf(id));
        MemoryGateway.Prepared p=gateway.prepare("dispatch-1","root-1","user",id,text,null,80_000,1_000_000);
        assertTrue(p.context.contains(text));assertEquals(p.context.indexOf(text),p.context.lastIndexOf(text));assertEquals(id,p.latestOwnerInputId);
        assertEquals("success",receipt(p,"verbatim").getString("status"));gateway.assertFresh(p,1_000_001);
    }

    @Test public void receiptAndDependencyJsonCopiesCannotAlterPreparedEvidence()throws Exception{
        long id=source("proof original");memory("proof","proof fact","fact",id);MemoryGateway.Prepared p=prepare("proof");String fingerprint=p.evidenceFingerprint;
        p.receipts().remove(0);p.dependencies().remove(0);p.diagnostics().put("modelInternalUseVerified",true);
        assertEquals(5,p.receipts().length());assertTrue(p.dependencies().length()>0);assertFalse(p.diagnostics().getBoolean("modelInternalUseVerified"));assertEquals(fingerprint,p.evidenceFingerprint);gateway.assertFresh(p,1_000_001);
    }

    @Test public void currentMemoryRevisionChangesInvalidatePreparedContext()throws Exception{
        long id=source("proof original");memory("proof","proof old fact","fact",id);MemoryGateway.Prepared p=prepare("proof");memory("proof","proof corrected fact","fact",id);blocked(p,"memory_revision_changed");
    }

    @Test public void changedInlineOriginalCannotBorrowItsOldStoredHash()throws Exception{
        long id=source("proof original");MemoryGateway.Prepared p=prepare("proof");store.getWritableDatabase().execSQL("UPDATE messages SET text='proof tampered' WHERE id=?",new Object[]{id});blocked(p,"memory_source_integrity_failed");
    }

    @Test public void aCorruptMemoryDoesNotBecomeAnEmptySuccessfulLayer()throws Exception{
        long id=source("proof original");memory("proof","proof fact","fact",id);store.getWritableDatabase().execSQL("UPDATE memories SET content='proof invented fact' WHERE memory_key='proof'");
        try{prepare("proof");fail("Corrupt semantic data must not silently pass");}catch(MemoryGateway.Blocked expected){assertEquals("memory_revision_integrity_failed",expected.reason);}
    }

    @Test public void policyOwnerInputAndPauseChangesInvalidateButAuditAppendDoesNot()throws Exception{
        MemoryGateway.Prepared p=prepare("hello");store.recordEvent("audit_only",new JSONObject().put("status","inspected"));gateway.assertFresh(p,1_000_001);
        store.putSetting("customInstructions","new authoritative instruction");blocked(p,"memory_control_changed");
        p=prepare("hello");store.putSetting("ownerInputRevision","1");blocked(p,"memory_control_changed");store.putSetting("ownerInputRevision","0");
        p=prepare("hello");store.putSetting("pausedRoot:root-1","true");blocked(p,"memory_control_changed");
    }

    @Test public void activeGoalRawMutationIsDetectedEvenWithoutRevisionBump()throws Exception{
        store.putSetting("activeGoal:root-1",new JSONObject().put("stage","working").put("progress","initial").toString());MemoryGateway.Prepared p=prepare("hello");
        store.putSetting("activeGoal:root-1",new JSONObject().put("stage","blocked").put("progress","do not repeat").toString());blocked(p,"memory_control_changed");
    }

    @Test public void ownerMemoryExclusionStillPerformsReadsWithoutDisclosingPastText()throws Exception{
        long id=source("PRIVATE-MEMORY-8843");memory("private","PRIVATE-MEMORY-8843","fact",id);store.putSetting("toolPermissions","{\"memory.search\":false}");
        MemoryGateway.Prepared p=prepare("hello");assertFalse(p.context.contains("PRIVATE-MEMORY-8843"));assertEquals(5,p.receipts().length());assertEquals("memory_search_excluded_by_owner",receipt(p,"semantic").getString("exclusion"));gateway.assertFresh(p,1_000_001);
    }

    @Test public void staleHybridIdAndHashCannotInjectAnInventedOriginal()throws Exception{
        long id=source("real canonical source");JSONObject hybrid=new JSONObject().put("originalEvidence",new JSONArray().put(new JSONObject().put("type","message").put("id",id).put("hash","0".repeat(64)).put("excerpt","INVENTED-HYBRID-TEXT")));
        MemoryGateway.Prepared p=gateway.prepare("dispatch-1","root-1","user",0,"hello",hybrid,80_000,1_000_000);assertFalse(p.context.contains("INVENTED-HYBRID-TEXT"));assertTrue(p.context.contains("real canonical source"));gateway.assertFresh(p,1_000_001);
    }

    @Test public void temporalBoundaryExpiresButLongWorkOutputCanOutliveAdmissionTtl()throws Exception{
        MemoryGateway.Prepared p=prepare("hello");assertEquals(1_600_000,p.validUntil);gateway.assertFresh(p,1_600_001,false);
        try{gateway.assertFresh(p,1_600_001);fail("Admission TTL expired");}catch(MemoryGateway.Blocked expected){assertEquals("memory_context_expired",expected.reason);}
        long id=source("proof fact");memory("proof","proof fact","fact",id);store.putMemoryMetadata("proof",new JSONObject().put("validTo",1_000_100));p=prepare("proof");assertEquals(1_000_100,p.validUntil);
        try{gateway.assertFresh(p,1_000_100,false);fail("Output must not ignore a real validity boundary");}catch(MemoryGateway.Blocked expected){assertEquals("memory_temporal_boundary_changed",expected.reason);}
    }

    @Test public void mandatoryActiveOverflowBlocksInsteadOfSilentlyOmittingConstraints()throws Exception{
        store.putSetting("standingGoals","MANDATORY-CONSTRAINT-".repeat(600));
        try{gateway.prepare("dispatch-1","root-1","user",0,"hello",null,4096,1_000_000);fail("Oversized native constraints must not disappear");}catch(MemoryGateway.Blocked expected){assertEquals("mandatory_memory_context_overflow",expected.reason);}
    }

    @Test public void malformedActiveGoalIsAnErrorNotAnEmptyLayer()throws Exception{
        store.putSetting("activeGoal:root-1","{broken");try{prepare("hello");fail("Unreadable task state must not imply no task");}catch(MemoryGateway.Blocked expected){assertEquals("memory_json_invalid",expected.reason);}
    }

    @Test public void excludedOriginalCannotReturnThroughCurrentOrHistoricalMemory()throws Exception{
        SourcePrivacyLedger ledger=new SourcePrivacyLedger(context);gateway=new MemoryGateway(store,ledger);
        long old=source("FORGOTTEN-OLD-TEXT-9218"),current=source("allowed new source");memory("history","FORGOTTEN-OLD-TEXT-9218","fact",old);memory("history","allowed new source","fact",current);
        ledger.excludeSource("message",old,store.getMessage(old).getString("content_hash"),"retrieval_exclude","owner selected source exclusion");
        MemoryGateway.Prepared p=prepare("history");assertFalse(p.context.contains("FORGOTTEN-OLD-TEXT-9218"));assertTrue(p.context.contains("allowed new source"));gateway.assertFresh(p,1_000_001);
        ledger.excludeSource("message",current,store.getMessage(current).getString("content_hash"),"retrieval_exclude","owner excludes current source");blocked(p,"memory_privacy_changed");
    }

    @Test public void invalidatedAssertionIsMarkedWrongAndCurrentOwnerCorrectionIsMandatory()throws Exception{
        long old=source("incorrect statement: height 152cm");memory("height","incorrect statement: height 152cm","fact",old);
        store.putMemoryMetadata("height",new JSONObject().put("status","invalidated").put("correctionReason","original assertion was wrong"));
        String correction="Correction: the 152cm claim was wrong, not a former height. Height is 180cm.";long current=source(correction);store.putSetting("ownerInputRevision",String.valueOf(current));
        MemoryGateway.Prepared p=gateway.prepare("dispatch-1","root-1","user",current,"What is my height?",null,80_000,1_000_000);
        assertTrue(p.context.contains(correction));assertTrue(p.context.contains("\"memoryStatus\":\"invalidated\""));assertTrue(p.context.contains("\"invalidatedIsNotFormerlyTrue\":true"));
        assertTrue(p.context.contains("invalidated means an erroneous assertion"));gateway.assertFresh(p,1_000_001);
    }

    @Test public void supersededFactRetainsItsLegitimateEarlierValidityWithoutBecomingCurrent()throws Exception{
        long old=source("I lived in Seoul in 2021.");memory("address","Seoul was the valid residence in 2021.","fact",old);
        store.putMemoryMetadata("address",new JSONObject().put("status","superseded").put("validFrom",10).put("validTo",100));
        MemoryGateway.Prepared p=prepare("address Seoul");assertTrue(p.context.contains("\"memoryStatus\":\"superseded\""));assertTrue(p.context.contains("\"validTo\":100"));assertTrue(p.context.contains("Seoul was the valid residence in 2021."));assertTrue(p.context.contains("\"invalidatedIsNotFormerlyTrue\":false"));gateway.assertFresh(p,1_000_001);
    }

    @Test public void oldestRankedCanonicalEvidenceSurvivesGenericRecentDistractorsAndTightBudget()throws Exception{
        long old=source("First-year cobalt archive decision was NORTH-7314.");
        JSONObject target=memory("first-year-cobalt","cobalt archive decision NORTH-7314","fact",old);
        for(int i=0;i<40;i++){long recent=source("archive recent discussion "+i+" "+"routine note ".repeat(60));memory("recent-"+i,"archive recent discussion "+i,"fact",recent);}
        JSONObject hybrid=new JSONObject().put("originalEvidence",new JSONArray().put(new JSONObject().put("type","message").put("id",old).put("hash",store.getMessage(old).getString("content_hash"))))
                .put("memories",new JSONArray().put(new JSONObject().put("key",target.getString("key")).put("revision",target.getLong("revision")).put("content_hash",target.getString("content_hash"))));
        MemoryGateway.Prepared p=gateway.prepare("dispatch-1","root-1","user",0,"cobalt archive decision",hybrid,6000,1_000_000);
        assertTrue(p.context.contains("NORTH-7314"));assertTrue(p.context.contains("\"key\":\"first-year-cobalt\""));assertTrue(p.diagnostics().getInt("omittedOptionalItems")>0);assertEquals(5,p.receipts().length());gateway.assertFresh(p,1_000_001);
    }

    @Test public void directSqlFallbackRanksSpecificTermsBeforeGenericRecentMatches()throws Exception{
        source("cobalt archive decision FALLBACK-6038");for(int i=0;i<35;i++)source("archive routine note "+i+" "+"ordinary recent text ".repeat(50));
        MemoryGateway.Prepared p=gateway.prepare("dispatch-1","root-1","user",0,"cobalt archive decision",null,4096,1_000_000);
        assertTrue(p.context.contains("FALLBACK-6038"));gateway.assertFresh(p,1_000_001);
    }

    @Test public void deniedDeviceRowsAndTheirDerivedMemoriesNeverReachContext()throws Exception{
        JSONObject event=store.appendDeviceEvent("notification","phone",new JSONObject().put("text","DEVICE-PRIVATE-7412"),100,null);
        store.upsertMemoryWithSources("device-private","DEVICE-PRIVATE-7412","observation",new JSONArray().put(new JSONObject().put("type","device_event").put("id",event.getLong("id"))));
        MemoryGateway.Prepared p=prepare("DEVICE-PRIVATE-7412");assertFalse(p.context.contains("payloadExcerpt"));assertFalse(p.context.contains("\"key\":\"device-private\""));gateway.assertFresh(p,1_000_001);
        gateway.setDeviceSourceVisibility(row->true);p=prepare("DEVICE-PRIVATE-7412");assertTrue(p.context.contains("payloadExcerpt"));gateway.setDeviceSourceVisibility(row->false);blocked(p,"memory_source_permission_changed");
    }

    @Test public void excludedDeviceHashReplayWithNewIdCannotReappear()throws Exception{
        gateway.setDeviceSourceVisibility(row->true);JSONObject payload=new JSONObject().put("text","DEVICE-REPLAY-8448");JSONObject first=store.appendDeviceEvent("notification","phone",payload,100,null);
        store.getPrivacyLedger().excludeSource("device_event",first.getLong("id"),first.getString("content_hash"),"retrieval_exclude","owner exclusion");
        JSONObject replay=store.appendDeviceEvent("notification","phone",payload,100,null);assertNotEquals(first.getLong("id"),replay.getLong("id"));
        MemoryGateway.Prepared p=prepare("DEVICE-REPLAY-8448");assertFalse(p.context.contains("payloadExcerpt"));gateway.assertFresh(p,1_000_001);
    }

    @Test public void forgottenParentCannotLeakThroughAssistantCopyDerivedMemoryOrGoal()throws Exception{
        long parent=source("FORGOTTEN-PARENT-3855");store.putSetting("requestSource:reply-copy",String.valueOf(parent));
        long copy=store.addMessage("assistant","FORGOTTEN-PARENT-3855 copied response","reply-copy").getLong("id");memory("copy","FORGOTTEN-PARENT-3855 derived","fact",copy);
        store.putSetting("activeGoal:root-1",new JSONObject().put("sourceMessageId",copy).put("progress","FORGOTTEN-PARENT-3855 action").toString());
        store.getPrivacyLedger().excludeSource("message",parent,store.getMessage(parent).getString("content_hash"),"retrieval_exclude","owner exclusion");
        MemoryGateway.Prepared p=prepare("hello");assertFalse(p.context.contains("FORGOTTEN-PARENT-3855"));assertTrue(p.context.contains("blocked_source_excluded"));gateway.assertFresh(p,1_000_001);
    }

    @Test public void isoValidityBoundariesFenceCurrentAndHistoricalMemory()throws Exception{
        long boundary=com.hoyoung.assistant.memory.MemoryQuery.date("2030-01-14");long id=source("proof original");memory("proof","proof old","fact",id);
        store.putMemoryMetadata("proof",new JSONObject().put("validTo","2030-01-14"));memory("proof","proof current","fact",id);store.putMemoryMetadata("proof",new JSONObject().put("validFrom","2020-01-01"));
        MemoryGateway.Prepared p=gateway.prepare("dispatch-1","root-1","user",0,"proof",null,80_000,boundary-1000);assertEquals(boundary,p.temporalValidUntil);
        try{gateway.assertFresh(p,boundary,false);fail("ISO historical boundary must expire");}catch(MemoryGateway.Blocked expected){assertEquals("memory_temporal_boundary_changed",expected.reason);}
        store.putMemoryMetadata("proof",new JSONObject().put("validTo","not-a-date"));try{prepare("proof");fail("Invalid boundary must not become zero");}catch(MemoryGateway.Blocked expected){assertEquals("memory_validity_invalid",expected.reason);}
    }

    @Test public void concurrentWriterCannotMixControlsAndMemoryAcrossOneNativeReadSnapshot()throws Exception{
        long id=source("proof original");memory("proof","proof BEFORE-CUT-4172","fact",id);store.putSetting("customInstructions","BEFORE-CUT-INSTRUCTION");
        store.appendDeviceEvent("notification","phone",new JSONObject().put("text","proof"),100,null);
        java.util.concurrent.CountDownLatch snapshotPaused=new java.util.concurrent.CountDownLatch(1),writerCommitted=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean once=new java.util.concurrent.atomic.AtomicBoolean();
        gateway.setDeviceSourceVisibility(row->{if(once.compareAndSet(false,true)){snapshotPaused.countDown();try{if(!writerCommitted.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Snapshot barrier timeout");}catch(InterruptedException interrupted){throw new IllegalStateException(interrupted);}}return true;});
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            java.util.concurrent.Future<MemoryGateway.Prepared> future=executor.submit(()->gateway.prepare("dispatch-1","root-1","user",0,"proof",null,80_000,1_000_000));
            assertTrue(snapshotPaused.await(10,java.util.concurrent.TimeUnit.SECONDS));memory("proof","proof AFTER-CUT-9811","fact",id);store.putSetting("customInstructions","AFTER-CUT-INSTRUCTION");writerCommitted.countDown();
            MemoryGateway.Prepared p=future.get(10,java.util.concurrent.TimeUnit.SECONDS);assertTrue(p.context.contains("BEFORE-CUT-4172"));assertTrue(p.context.contains("BEFORE-CUT-INSTRUCTION"));assertFalse(p.context.contains("AFTER-CUT-9811"));assertFalse(p.context.contains("AFTER-CUT-INSTRUCTION"));blocked(p,"memory_control_changed");
        }finally{writerCommitted.countDown();executor.shutdownNow();}
    }

    @Test public void rankedOldDeviceEvidenceSurvivesManyRecentGenericMessagesAndEvents()throws Exception{
        gateway.setDeviceSourceVisibility(row->true);JSONObject old=store.appendDeviceEvent("booking","phone",new JSONObject().put("text","cobalt reservation confirmed DEVICE-OLD-7346").put("confirmedSeats",2),100,null);
        for(int i=0;i<35;i++){source("reservation generic recent note "+i);store.appendDeviceEvent("booking","phone",new JSONObject().put("text","reservation generic pending "+i),200+i,null);}
        JSONObject hybrid=new JSONObject().put("originalEvidence",new JSONArray().put(new JSONObject().put("type","device_event").put("id",old.getLong("id")).put("hash",old.getString("content_hash"))));MemoryGateway.Prepared p=gateway.prepare("dispatch-1","root-1","user",0,"cobalt reservation confirmed",hybrid,6000,1_000_000);assertTrue(p.context.contains("DEVICE-OLD-7346"));assertTrue(p.context.contains("confirmedSeats"));gateway.assertFresh(p,1_000_001);
    }
    @Test public void canonicalHybridOriginalRangePreservesOldTailAndDerivedExamplesDoNotDuplicateIt()throws Exception{
        String text="ordinary prefix ".repeat(15000)+"TAIL-CANONICAL-4197 cobalt decision";long id=source(text);int offset=text.indexOf("TAIL-CANONICAL-4197");JSONObject note=memory("cobalt","cobalt canonical decision","fact",id);
        JSONObject hybrid=new JSONObject().put("originalEvidence",new JSONArray().put(new JSONObject().put("type","message").put("id",id).put("hash",store.getMessage(id).getString("content_hash")).put("exactOriginalRange",true).put("offsetChars",offset))).put("memories",new JSONArray().put(new JSONObject().put("key","cobalt").put("revision",note.getLong("revision")).put("content_hash",note.getString("content_hash"))));MemoryGateway.Prepared p=gateway.prepare("dispatch-1","root-1","user",0,"cobalt decision",hybrid,8000,1_000_000);assertTrue(p.context.contains("TAIL-CANONICAL-4197"));assertFalse(p.context.contains("hydratedSourceExamples"));gateway.assertFresh(p,1_000_001);
    }
    private static void delete(File file)throws Exception{if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Unreadable fixture");for(File child:children)delete(child);}if(!file.delete())throw new java.io.IOException("Fixture cleanup failed");}
}
