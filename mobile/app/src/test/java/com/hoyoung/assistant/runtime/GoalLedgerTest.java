package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class GoalLedgerTest {
    private Context context; private HoyoungStore store; private GoalLedger ledger;
    @Before public void before() { context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);ledger=new GoalLedger(store); }
    @After public void after() { store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME); }

    @Test public void completedProviderTurnDoesNotEraseAnUnfinishedGoalAcrossRestart() throws Exception {
        long source=owner("root","사진을 정리하고 결과를 확인해");ledger.update("root","awaiting_result",source,"root","사진 이동 완료 확인 대기",1234);
        long request=store.listRequests(1).getJSONObject(0).getLong("id");store.markRequest(request,"completed","다음 확인 필요");
        store.close();store=new HoyoungStore(context);ledger=new GoalLedger(store);
        assertEquals("awaiting_result",ledger.get("root").getString("stage"));assertEquals(source,ledger.get("root").getLong("sourceMessageId"));
        assertEquals("completed",store.getRequest(request).getString("status"));assertTrue(ledger.context(4000).contains("사진 이동 완료 확인 대기"));
    }
    @Test public void pendingLedgerIsIncludedInSnapshotAndRestoreWithoutAutomaticReplay() throws Exception {
        long source=owner("root","앱 설치 결과 확인");ledger.update("root","uncertain",source,"root","접수 여부 미확인",1234);
        JSONObject backup=store.exportSnapshot();ledger.remove("root","잠깐 제외");assertNull(ledger.get("root"));store.importSnapshot(backup);
        assertEquals("uncertain",ledger.get("root").getString("stage"));assertFalse(ledger.get("root").getBoolean("mayAutoRepeat"));
        assertEquals("false",store.getSetting("autonomy.enabled","missing"));assertTrue(ledger.context(4000).contains("접수 여부 미확인"));
    }
    @Test public void fabricatedAssistantAndUnrelatedOwnerSourcesCannotCreateGoals() throws Exception {
        long source=owner("root","사용자가 맡긴 일");long unrelated=owner("other","다른 요청");
        long assistant=store.addMessage("assistant","생성된 주장","root").getLong("id");
        assertThrows(IllegalArgumentException.class,()->ledger.update("root","working",99999,"root","진행",0));
        assertThrows(IllegalArgumentException.class,()->ledger.update("root","working",assistant,"root","진행",0));
        assertThrows(IllegalArgumentException.class,()->ledger.update("root","working",unrelated,"root","진행",0));
        assertThrows(IllegalArgumentException.class,()->ledger.update("invented","working",source,"root","진행",0));
        assertEquals(0,ledger.list(null).getLong("totalPending"));
    }
    @Test public void continuationMustBelongToItsRootAndCannotReplaceSource() throws Exception {
        long source=owner("root","기존 명령");long other=owner("other","다른 명령");ledger.update("root","queued",source,"root","대기",0);
        store.enqueueRequest("도구 결과","tool-continuation","child");store.putSetting("requestRoot:child","root");
        assertTrue(ledger.update("root","working",0,"child","도구 결과 확인",0).getBoolean("ok"));assertEquals(source,ledger.get("root").getLong("sourceMessageId"));
        assertThrows(IllegalArgumentException.class,()->ledger.update("root","working",other,"child","진행",0));
        assertThrows(IllegalArgumentException.class,()->ledger.update("root","working",source,"other","진행",0));
    }
    @Test public void modelCompletionClaimRetainsExistingGoalButFinishedSmallTalkCreatesNone() throws Exception {
        long source=owner("root","스크립트 실행 결과 확인");ledger.update("root","working",source,"root","실행 중",0);
        JSONObject response=ledger.update("root","completed",source,"root","모델의 완료 보고",0);
        assertFalse(response.getBoolean("closed"));assertEquals("awaiting_result",ledger.get("root").getString("stage"));
        assertEquals("completed",ledger.get("root").getString("reportedStage"));assertFalse(ledger.get("root").getBoolean("completionVerified"));
        long hello=owner("hello","안녕");assertFalse(ledger.update("hello","completed",hello,"hello","안녕",0).getBoolean("tracked"));assertNull(ledger.get("hello"));
    }
    @Test public void verifiedClosureRemovesOnlyActiveSettingAndRetainsRawJournal() throws Exception {
        long source=owner("root","파일 쓰기");ledger.update("root","working",source,"root","파일 결과 대기",0);
        JSONObject proof=new JSONObject().put("completionVerified",true).put("verificationSource","local_file_sha256_receipt");
        assertTrue(ledger.update("root","completed",source,"root","파일 내용 확인됨",0,proof).getBoolean("closed"));assertNull(ledger.get("root"));
        assertNotNull(store.getMessage(source));assertEquals(1,store.listRequests(10).length());
        JSONObject event=store.events(1).getJSONObject(0);assertEquals("goal_closed",event.getString("type"));assertTrue(new JSONObject(event.getString("payload")).getJSONObject("goal").getBoolean("completionVerified"));
    }
    @Test public void ownerClosureDoesNotPretendProviderOutcomeWasVerified() throws Exception {
        long source=owner("root","외부 업로드");ledger.update("root","uncertain",source,"root","외부 결과 모름",0);
        JSONObject result=ledger.resolve("root","completed","사용자가 직접 확인하고 추적 종료");
        assertTrue(result.getBoolean("closed"));assertTrue(result.getJSONObject("goal").getBoolean("ownerResolved"));
        assertFalse(result.getJSONObject("goal").getBoolean("providerOutcomeVerified"));assertFalse(result.getJSONObject("goal").getBoolean("completionVerified"));
        assertNull(ledger.get("root"));assertEquals("goal_owner_resolved",store.events(1).getJSONObject(0).getString("type"));
    }
    @Test public void modelAbandonmentCannotSilentlyDiscardOwnersPendingTask() throws Exception {
        long source=owner("root","맡긴 작업");ledger.update("root","working",source,"root","진행",0);
        assertFalse(ledger.update("root","abandoned",source,"root","모델이 포기하겠다고 함",0).getBoolean("closed"));
        assertEquals("blocked",ledger.get("root").getString("stage"));assertTrue(ledger.get("root").getBoolean("abandonmentNeedsOwnerDecision"));
        assertTrue(ledger.remove("root","사용자 요청으로 제외").getBoolean("closed"));
    }
    @Test public void readingBlockedGoalsNeverQueuesOrRepeatsExternalWork() throws Exception {
        long source=owner("root","발송 결과 확인");ledger.update("root","blocked",source,"root","한도 소진",1);
        int requests=store.listRequests(100).length();for(int i=0;i<5;i++){ledger.list(null);ledger.context(4000);ledger.get("root");}
        JSONObject record=ledger.get("root");assertFalse(record.getBoolean("mayAutoRepeat"));assertFalse(record.getBoolean("mayScheduleReadOnlyCheck"));assertTrue(record.getBoolean("requiresOwnerReconciliation"));
        assertEquals(requests,store.listRequests(100).length());
    }
    @Test public void filteredKeysetPagesStillAdvanceAcrossExcludedRows() throws Exception {
        for(String id:new String[]{"a","b","c","d"}){long source=owner(id,"할 일 "+id);ledger.update(id,"b".equals(id)||"d".equals(id)?"working":"blocked",source,id,"진행",100);}
        JSONObject first=ledger.list(new JSONObject().put("limit",1).put("stage","working"));assertEquals(0,first.getJSONArray("goals").length());assertEquals("a",first.getString("nextAfterRootRequestId"));
        JSONObject second=ledger.list(new JSONObject().put("limit",1).put("stage","working").put("afterRootRequestId",first.getString("nextAfterRootRequestId")));
        assertEquals("b",second.getJSONArray("goals").getJSONObject(0).getString("rootRequestId"));assertEquals("b",second.getString("nextAfterRootRequestId"));
    }
    @Test public void dueGoalBeyondFirstHundredAndUnrelatedSettingsIsAvailableInContext() throws Exception {
        store.withTransaction(db->{for(int i=0;i<2000;i++)store.putSetting("unrelated:"+i,"value");return null;});
        for(int i=0;i<120;i++){String id=String.format(java.util.Locale.ROOT,"a-%03d",i);long source=owner(id,"일반 목표 "+i);ledger.update(id,"working",source,id,"진행",Long.MAX_VALUE);}
        long due=owner("zz-due","지금 확인할 결과");ledger.update("zz-due","awaiting_result",due,"zz-due","희귀한 미완료 결과",1);
        String context=ledger.context(4000);assertTrue(context.contains("zz-due"));assertTrue(context.length()<=4000);assertTrue(new JSONObject(context).getBoolean("truncated"));
        assertEquals(121,ledger.list(null).getLong("totalPending"));
    }
    @Test public void capacityIsExplicitAndDoesNotEvictOlderUnfinishedGoals() throws Exception {
        for(int i=0;i<GoalLedger.MAX_ACTIVE_GOALS;i++){String id="goal-"+i;long source=owner(id,"해야 할 일 "+i);assertTrue(ledger.update(id,"queued",source,id,"대기",0).getBoolean("ok"));}
        long source=owner("overflow","추가할 일");JSONObject result=ledger.update("overflow","queued",source,"overflow","대기",0);
        assertFalse(result.getBoolean("ok"));assertEquals("goal_capacity_reached",result.getString("code"));assertEquals(256,ledger.list(null).getLong("totalPending"));assertNotNull(ledger.get("goal-0"));assertNull(ledger.get("overflow"));
    }
    @Test public void malformedRecordsAreReportedWithoutDeletionOrFalseEmptyClaim() throws Exception {
        store.putSetting("activeGoal:corrupt","not-json");JSONObject list=ledger.list(null);assertEquals(1,list.getLong("totalPending"));assertEquals(1,list.getInt("invalidRecordCount"));
        assertEquals("not-json",store.getSetting("activeGoal:corrupt",""));assertThrows(IllegalStateException.class,()->ledger.get("corrupt"));
    }
    @Test public void escapedProgressCannotOverrunContextBudget() throws Exception {
        long source=owner("root","실행");ledger.update("root","working",source,"root",String.valueOf((char)0).repeat(2000),0);
        String context=ledger.context(512);assertTrue(context.length()<=512);JSONObject shown=new JSONObject(context);assertEquals(1,shown.getLong("totalPending"));assertTrue(shown.getBoolean("truncated"));
    }
    @Test public void explicitOwnerCanCloseUnreadableSlotWhileKeepingExactRawEvidence()throws Exception{
        for(String raw:new String[]{"","not-json","{}","{} trailing","{\"nested\":"+"[".repeat(1000)+"0"+"]".repeat(1000)+"}"}){
            store.putSetting("activeGoal:corrupt",raw);assertEquals(1,ledger.list(null).getInt("invalidRecordCount"));assertThrows(IllegalStateException.class,()->ledger.get("corrupt"));
            String before=store.getSetting("goalLedgerRevision","0");JSONObject result=ledger.remove("corrupt","사용자가 손상된 추적 항목만 제외");assertTrue(result.getBoolean("closed"));JSONObject goal=result.getJSONObject("goal");assertTrue(goal.getBoolean("previousGoalUnreadable"));assertTrue(goal.getBoolean("sourceUnknown"));assertFalse(goal.getBoolean("completionVerified"));assertFalse(goal.getBoolean("providerOutcomeVerified"));
            JSONObject preserved=new JSONObject(store.readOriginalText(goal.getLong("previousGoalSourceMessageId"),0,24_000).getString("text"));assertEquals(raw,preserved.getString("rawGoal"));assertNotEquals(before,store.getSetting("goalLedgerRevision","0"));assertNull(store.getSetting("activeGoal:corrupt",null));assertEquals(0,ledger.list(null).getLong("totalPending"));
        }
    }
    @Test public void strictWholeReaderRejectsTrailingDeepAndFractionalRecordsWithoutDeletingThem()throws Exception{
        long source=owner("root","보존할 목표");ledger.update("root","working",source,"root","진행",0);String valid=ledger.get("root").toString();
        for(String raw:new String[]{valid+" trailing",new JSONObject(valid).put("sourceMessageId",1.5).toString(),new JSONObject(valid).put("progress",7).toString(),"{\"x\":"+"[".repeat(1000)+"0"+"]".repeat(1000)+"}"}){
            store.putSetting("activeGoal:root",raw);assertThrows(IllegalStateException.class,()->ledger.get("root"));assertEquals(1,ledger.list(null).getInt("invalidRecordCount"));assertEquals(raw,store.getSetting("activeGoal:root",null));
        }
        store.putSetting("activeGoal:root",valid);assertEquals("working",ledger.get("root").getString("stage"));
    }
    @Test public void corruptOwnerClosureRollsBackEvidenceAndSettingOnJournalFailure()throws Exception{
        store.putSetting("activeGoal:corrupt","broken exact original");long before=store.listMessages(100).length();
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_goal_close BEFORE INSERT ON events WHEN NEW.type = 'goal_owner_resolved' BEGIN SELECT RAISE(ABORT,'test close failure'); END");
        assertThrows(RuntimeException.class,()->ledger.remove("corrupt","확인한 손상 항목 제외"));assertEquals("broken exact original",store.getSetting("activeGoal:corrupt",null));assertEquals(before,store.listMessages(100).length());assertEquals("0",store.getSetting("goalLedgerRevision","0"));
    }
    @Test public void archivedProviderClaimHasImmutableSourceButNeverOverallCompletionProof()throws Exception{
        long source=owner("root","로컬 작업 후 결과 보고");ledger.update("root","working",source,"root","작업 중",0);observed("root");ActionReceiptJournal receipts=new ActionReceiptJournal(store);JSONObject summary=nativeSummary(receipts,"root");String previous=ledger.get("root").toString();
        JSONObject closed=ledger.archiveReportedCompletion("root","root","모델의 완료 보고",summary);assertTrue(closed.getBoolean("archived"));assertEquals("goal_ledger_archival_only",closed.getString("confirmationScope"));assertFalse(closed.getBoolean("overallOutcomeVerified"));assertNull(ledger.get("root"));
        JSONObject index=closed.getJSONObject("goal");assertEquals("reported_completed",index.getString("stage"));assertFalse(index.getBoolean("completionVerified"));assertFalse(index.getBoolean("mayAutoRepeat"));
        JSONObject archived=store.getMessage(index.getLong("reportSourceMessageId"));assertEquals(index.getString("reportSourceHash"),archived.getString("content_hash"));JSONObject body=new JSONObject(store.readOriginalText(archived.getLong("id"),0,24_000).getString("text"));assertEquals(previous,body.getString("previousActiveRecord"));assertFalse(body.getBoolean("modelReportIsIndependentProof"));
        JSONObject list=ledger.list(new JSONObject().put("scope","reported_archive"));assertEquals(1,list.getLong("totalReportedArchives"));assertEquals(0,list.getLong("totalPending"));assertEquals("reported_completed",list.getJSONArray("goals").getJSONObject(0).getString("stage"));assertNotNull(store.getMessage(source));
    }
    @Test public void threeHundredConfirmedRoutineReportsReclaimSlotsWhileUnconfirmedTaskSurvives()throws Exception{
        ActionReceiptJournal receipts=new ActionReceiptJournal(store);long waiting=owner("waiting","접수만 된 폰 실행 확인");ledger.update("waiting","awaiting_result",waiting,"waiting","실제 결과를 기다리는 중",1);observed("waiting");receipts.put("actionReceipt:waiting:a",nativeReceipt(false));assertFalse(ledger.archiveReportedCompletion("waiting","waiting","완료라고 주장",nativeSummary(receipts,"waiting")).getBoolean("archived"));
        for(int i=0;i<300;i++){String root=String.format(java.util.Locale.ROOT,"routine-%04d",i);long source=owner(root,"확인된 로컬 처리 "+i);assertTrue(ledger.update(root,"working",source,root,"처리 중",0).getBoolean("ok"));observed(root);receipts.put("actionReceipt:"+root+":a",nativeReceipt(true));assertTrue(ledger.archiveReportedCompletion(root,root,"처리 완료 보고",nativeSummary(receipts,root)).getBoolean("archived"));}
        assertEquals(1,ledger.list(null).getLong("totalPending"));assertEquals("awaiting_result",ledger.get("waiting").getString("stage"));int retrieved=0;String after="";while(true){JSONObject args=new JSONObject().put("scope","reported_archive").put("limit",100);if(!after.isEmpty())args.put("afterRootRequestId",after);JSONObject page=ledger.list(args);retrieved+=page.getJSONArray("goals").length();assertEquals(300,page.getLong("totalReportedArchives"));if(!page.getBoolean("truncated"))break;after=page.getString("nextAfterRootRequestId");}assertEquals(300,retrieved);
    }
    @Test public void blockedUncertainAndOwnerPausedGoalsCannotBeArchivedByModelReport()throws Exception{
        ActionReceiptJournal receipts=new ActionReceiptJournal(store);for(String stage:new String[]{"blocked","uncertain","working"}){String root="root-"+stage;long source=owner(root,"진행 미확인");ledger.update(root,stage,source,root,"확인 필요",0);observed(root);if("working".equals(stage))store.putSetting("pausedRoot:"+root,"true");JSONObject result=ledger.archiveReportedCompletion(root,root,"모델 완료 보고",nativeSummary(receipts,root));assertFalse(result.getBoolean("archived"));assertEquals("goal_requires_reconciliation",result.getString("code"));assertEquals(stage,ledger.get(root).getString("stage"));}
    }
    @Test public void actualObservedForeignActorCanReportButUnknownOrUnobservedActorsCannot()throws Exception{
        long source=owner("target","과거 목표 확인");ledger.update("target","working",source,"target","진행",0);ActionReceiptJournal receipts=new ActionReceiptJournal(store);JSONObject summary=nativeSummary(receipts,"target");
        assertEquals("observed_report_required",ledger.archiveReportedCompletion("target","invented","허위 보고",summary).getString("code"));store.enqueueRequest("자율 상태 확인","autonomy","actor");assertFalse(ledger.archiveReportedCompletion("target","actor","미관측 보고",summary).getBoolean("archived"));observed("actor");
        JSONObject done=ledger.archiveReportedCompletion("target","actor","자율 상태 확인 후 보고",summary);assertTrue(done.getBoolean("archived"));assertEquals(source,done.getJSONObject("goal").getLong("sourceMessageId"));assertEquals("actor",done.getJSONObject("goal").getString("actorRequestId"));assertFalse(done.getJSONObject("goal").getBoolean("overallOutcomeVerified"));
    }
    @Test public void changedActualReceiptSnapshotCannotUseEarlierReadySummary()throws Exception{
        long source=owner("root","도구 결과 확인");ledger.update("root","working",source,"root","처리 중",0);observed("root");ActionReceiptJournal receipts=new ActionReceiptJournal(store);JSONObject before=nativeSummary(receipts,"root");receipts.put("actionReceipt:root:new",nativeReceipt(false));JSONObject result=ledger.archiveReportedCompletion("root","root","완료 주장",before);assertFalse(result.getBoolean("archived"));assertEquals("native_receipt_snapshot_changed",result.getString("code"));assertNotNull(ledger.get("root"));
        assertFalse(ledger.archiveReportedCompletion("root","root","완료 주장",nativeSummary(receipts,"root")).getBoolean("archived"));
    }
    @Test public void reportedArchiveSurvivesSnapshotRestartAndCorruptIndexIsExplicit()throws Exception{
        long source=owner("root","보고 이력 보존");ledger.update("root","working",source,"root","진행",0);observed("root");ActionReceiptJournal receipts=new ActionReceiptJournal(store);ledger.archiveReportedCompletion("root","root","완료 보고",nativeSummary(receipts,"root"));JSONObject backup=store.exportSnapshot();store.importSnapshot(backup);store.close();store=new HoyoungStore(context);ledger=new GoalLedger(store);
        JSONObject page=ledger.list(new JSONObject().put("scope","reported_archive"));assertEquals(1,page.getJSONArray("goals").length());assertFalse(page.getBoolean("reportedCompletionIsIndependentProof"));store.putSetting("goalReportedArchive:root",store.getSetting("goalReportedArchive:root","")+" trailing");JSONObject invalid=ledger.list(new JSONObject().put("scope","reported_archive"));assertEquals(1,invalid.getLong("totalReportedArchives"));assertEquals(1,invalid.getInt("invalidRecordCount"));assertEquals(0,invalid.getJSONArray("goals").length());
    }
    @Test public void archiveJournalFailureRollsBackOriginalIndexAndPendingDeletion()throws Exception{
        long source=owner("root","원자적 보고 기록");ledger.update("root","working",source,"root","진행",0);observed("root");String revision=store.getSetting("goalLedgerRevision","");int messages=store.listMessages(100).length();ActionReceiptJournal receipts=new ActionReceiptJournal(store);JSONObject summary=nativeSummary(receipts,"root");
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_goal_report BEFORE INSERT ON events WHEN NEW.type = 'goal_reported_complete_archived' BEGIN SELECT RAISE(ABORT,'test archive failure'); END");assertThrows(RuntimeException.class,()->ledger.archiveReportedCompletion("root","root","완료 보고",summary));assertNotNull(ledger.get("root"));assertNull(store.getSetting("goalReportedArchive:root",null));assertEquals(messages,store.listMessages(100).length());assertEquals(revision,store.getSetting("goalLedgerRevision",""));
    }
    private void observed(String root)throws Exception{long id;try(android.database.Cursor request=store.getReadableDatabase().rawQuery("SELECT id FROM requests WHERE external_id = ?",new String[]{root})){assertTrue(request.moveToFirst());id=request.getLong(0);}store.markRequest(id,"accepted",new JSONObject().put("protocol","hoyoung.v1").put("requestId",root).put("status","completed").put("message","보고").toString());}
    private static JSONObject nativeSummary(ActionReceiptJournal receipts,String root){return receipts.completionSummary(root,name->!"memory.search".equals(name),receipt->receipt.optJSONObject("result")!=null&&receipt.optJSONObject("result").optBoolean("completionConfirmed"));}
    private static JSONObject nativeReceipt(boolean confirmed)throws Exception{return new JSONObject().put("status","completed").put("name","files.write").put("fingerprint","f".repeat(64)).put("result",new JSONObject().put("ok",true).put("completionConfirmed",confirmed));}
    private long owner(String id,String text)throws Exception {
        store.enqueueRequest(text,"user",id);store.putSetting("requestRoot:"+id,id);
        long source=store.addMessage("user",text,id).getLong("id");store.putSetting("requestSource:"+id,String.valueOf(source));return source;
    }
}
