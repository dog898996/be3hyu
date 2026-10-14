package com.hoyoung.assistant.runtime;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Real local file and Android shadow receipts. No live browser, cloud account, network call or message send. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class RuntimeGoalArchiveIntegrationTest {
    private Context context;private HoyoungStore store;private InternalFiles files;private AssistantRuntime runtime;private Transport transport;
    @Before public void before()throws Exception{
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);deleteTree(new java.io.File(context.getFilesDir(),"projects"));store=new HoyoungStore(context);store.putSetting("usageOptimizationEnabled","false"); /* Fixed Medium/Fast control; optimized routing has separate regression coverage. */files=new InternalFiles(context);store.putSetting("memoryReviewEnabled","false");
        runtime=new AssistantRuntime(context,store,files,new PhoneTools(context,store),Runnable::run);transport=new Transport();runtime.attachTransport(transport);ready();
    }
    @After public void after(){store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);deleteTree(new java.io.File(context.getFilesDir(),"projects"));}

    @Test public void actualFileWriteThenExplicitBoundCompletionArchivesOnlyAReportedOutcome()throws Exception{
        JSONObject root=writeThenContinuation();assertEquals("independent local file content",files.readText("confirmed.txt",100));assertEquals("working",runtime.getGoalLedger().get(root.getString("external_id")).getString("stage"));
        JSONObject continuation=active();complete(continuation,bound(continuation,"completed","파일을 작성했고 완료로 보고해.").toString());assertNull(runtime.getGoalLedger().get(root.getString("external_id")));
        JSONObject reports=reports();assertEquals(1,reports.getLong("totalReportedArchives"));JSONObject report=reports.getJSONArray("goals").getJSONObject(0);assertEquals("reported_completed",report.getString("stage"));assertEquals(1,report.getLong("confirmedNativeMutationCount"));assertFalse(report.getBoolean("completionVerified"));assertFalse(report.getBoolean("overallOutcomeVerified"));assertFalse(report.getBoolean("mayAutoRepeat"));
        assertEquals(continuation.getString("external_id"),report.getString("actorRequestId"));assertEquals(store.getRequest(continuation.getLong("id")).getString("raw_result_hash"),report.getString("reportRawResponseHash"));assertTrue(store.events(100).toString().contains("goal_reported_complete_archived"));
    }
    @Test public void acceptedAndroidUrlRequestCannotBeArchivedByLaterModelCompletionClaim()throws Exception{
        String url="https://example.invalid/local-shadow-test";Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);ResolveInfo resolver=new ResolveInfo();resolver.activityInfo=new ActivityInfo();resolver.activityInfo.packageName="synthetic.browser";resolver.activityInfo.name="synthetic.browser.MainActivity";resolver.activityInfo.applicationInfo=new ApplicationInfo();resolver.activityInfo.applicationInfo.packageName="synthetic.browser";Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(intent,resolver);
        JSONObject root=runtime.receive("브라우저를 열고 로딩 결과 확인",new JSONArray());complete(root,bound(root,"working","브라우저 요청",new JSONArray().put(action("open-url","phone_url_open",object("url",url)))).toString());
        JSONObject receipt=new JSONObject(store.getSetting("actionReceipt:"+root.getString("external_id")+":open-url","{}"));assertTrue(receipt.getJSONObject("result").getBoolean("requested"));assertFalse(receipt.getJSONObject("result").getBoolean("completionConfirmed"));assertFalse(receipt.getJSONObject("result").getBoolean("browserLoaded"));
        JSONObject continuation=active();complete(continuation,bound(continuation,"completed","다 완료됐다고 보고").toString());JSONObject pending=runtime.getGoalLedger().get(root.getString("external_id"));assertNotNull(pending);assertEquals("awaiting_result",pending.getString("stage"));assertFalse(pending.getBoolean("completionVerified"));assertEquals(0,reports().getLong("totalReportedArchives"));
    }
    @Test public void sourceZeroReadOnlyAutonomyThenIdleCreatesNoPhantomPendingGoal()throws Exception{
        JSONObject root=autonomy();complete(root,bound(root,"working","저장된 내용을 확인",new JSONArray().put(action("read","memory.search",object("query","존재하지 않는 테스트용 정보")))).toString());assertNull(runtime.getGoalLedger().get(root.getString("external_id")));
        JSONObject continuation=active();complete(continuation,bound(continuation,"idle","").toString());assertEquals(0,runtime.getGoalLedger().list(null).getLong("totalPending"));assertEquals(0,reports().getLong("totalReportedArchives"));assertEquals(2,transport.ids.size());
        JSONObject receipt=new JSONObject(store.getSetting("actionReceipt:"+root.getString("external_id")+":read","{}"));assertEquals("completed",receipt.getString("status"));assertTrue(receipt.getJSONObject("result").getBoolean("ok"));
    }
    @Test public void sourceZeroLocationRefreshDiagnosticThenIdleDoesNotCreateRoutineGoal()throws Exception{
        Shadows.shadowOf((Application)context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION);JSONObject root=autonomy();complete(root,bound(root,"working","현재 위치 새로 확인",new JSONArray().put(action("refresh","phone_location_refresh",new JSONObject()))).toString());
        assertNull(runtime.getGoalLedger().get(root.getString("external_id")));JSONObject receipt=new JSONObject(store.getSetting("actionReceipt:"+root.getString("external_id")+":refresh","{}"));assertEquals("completed",receipt.getString("status"));assertFalse(receipt.getJSONObject("result").getBoolean("ok"));
        JSONObject continuation=active();complete(continuation,bound(continuation,"idle","").toString());assertEquals(0,runtime.getGoalLedger().list(null).getLong("totalPending"));assertEquals(0,reports().getLong("totalReportedArchives"));assertFalse(store.events(100).toString().contains("\"gpsConfirmed\":true"));
    }
    @Test public void plainProviderTurnSayingStillWorkingIsNotAReportedGoalCompletion()throws Exception{
        JSONObject root=writeThenContinuation();JSONObject continuation=active();complete(continuation,"아직 전체 목표는 진행 중이고 다음 결과를 확인해야 해.");assertNotNull(runtime.getGoalLedger().get(root.getString("external_id")));assertEquals("awaiting_result",runtime.getGoalLedger().get(root.getString("external_id")).getString("stage"));assertEquals(0,reports().getLong("totalReportedArchives"));
    }
    @Test public void boundEnvelopeWithoutExplicitStatusCannotClaimGoalCompletion()throws Exception{
        JSONObject root=writeThenContinuation();JSONObject continuation=active();complete(continuation,object("protocol","hoyoung.v1","requestId",continuation.getString("external_id"),"message","다음 결과를 기다리는 중").toString());assertNotNull(runtime.getGoalLedger().get(root.getString("external_id")));assertEquals(0,reports().getLong("totalReportedArchives"));
    }
    @Test public void wrongRequestEnvelopeCannotArchiveEarlierGoalEvenWithACompletedWord()throws Exception{
        JSONObject root=writeThenContinuation();JSONObject continuation=active();complete(continuation,object("protocol","hoyoung.v1","requestId","wrong-request","status","completed","message","허위 완료").toString());assertNotNull(runtime.getGoalLedger().get(root.getString("external_id")));assertEquals(0,reports().getLong("totalReportedArchives"));
    }
    private JSONObject writeThenContinuation()throws Exception{JSONObject root=runtime.receive("로컬 파일 작성 후 전체 결과 보고",new JSONArray());complete(root,bound(root,"working","파일 작성 요청",new JSONArray().put(action("write","files.write",object("relativePath","confirmed.txt","content","independent local file content")))).toString());assertEquals(2,transport.ids.size());return root;}
    private JSONObject autonomy()throws Exception{store.putSetting("autonomy.enabled","true");store.putSetting("runtime.lastAutonomyAt","0");runtime.tick();JSONObject root=active();assertEquals("autonomy",root.getString("kind"));assertEquals("0",store.getSetting("requestSource:"+root.getString("external_id"),""));return root;}
    private JSONObject active()throws Exception{return store.getRequest(runtime.state().getLong("activeStoreId"));}
    private JSONObject reports()throws Exception{return runtime.getGoalLedger().list(object("scope","reported_archive"));}
    private void ready()throws Exception{runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",false,"quota",false)));}
    private void complete(JSONObject request,String response)throws Exception{runtime.onWebEvent(object("type","completed","requestId",request.getString("external_id"),"text",response,"rawEnvelopeEligible",true));}
    private static JSONObject bound(JSONObject request,String status,String message)throws Exception{return object("protocol","hoyoung.v1","requestId",request.getString("external_id"),"status",status,"message",message);}
    private static JSONObject bound(JSONObject request,String status,String message,JSONArray actions)throws Exception{return bound(request,status,message).put("actions",actions);}
    private static JSONObject action(String id,String name,JSONObject args)throws Exception{return object("id",id,"name",name,"arguments",args);}
    private static JSONObject object(Object...pairs)throws Exception{JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)value.put(String.valueOf(pairs[i]),pairs[i+1]);return value;}
    private static void deleteTree(java.io.File file){if(!file.exists())return;java.io.File[] children=file.listFiles();if(children!=null)for(java.io.File child:children)deleteTree(child);if(!file.delete())throw new IllegalStateException("Could not remove test working file");}
    private final class Transport implements AssistantRuntime.Transport{final List<String> ids=new ArrayList<>();@Override public void inspect(){}@Override public void send(String id,String prompt){ids.add(id);}@Override public void openTemporary(){}@Override public void cancel(){}}
}
