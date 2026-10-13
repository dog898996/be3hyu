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
    private long owner(String id,String text)throws Exception {
        store.enqueueRequest(text,"user",id);store.putSetting("requestRoot:"+id,id);
        long source=store.addMessage("user",text,id).getLong("id");store.putSetting("requestSource:"+id,String.valueOf(source));return source;
    }
}
