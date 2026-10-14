package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.store.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;

/** Actual queue, continuation and settings stores. Provider is a deterministic local transport. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class ForegroundRoutingIntegrationTest {
    private Context context;private HoyoungStore store;private InternalFiles files;private AssistantRuntime runtime;
    private final List<AssistantRuntime> runtimes=new ArrayList<>();private Transport transport;
    @Before public void setup(){
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
        store=new HoyoungStore(context);files=new InternalFiles(context);store.putSetting("memoryReviewEnabled","false");store.putSetting("customInstructions","Preserve source evidence.");
        runtime=create();transport=new Transport();runtime.attachTransport(transport);
    }
    private AssistantRuntime create(){AssistantRuntime value=new AssistantRuntime(context,store,files,new PhoneTools(context,store),Runnable::run);runtimes.add(value);return value;}
    @After public void cleanup()throws Exception{
        for(AssistantRuntime value:runtimes){java.lang.reflect.Field f=AssistantRuntime.class.getDeclaredField("usageBudget");f.setAccessible(true);((UsageBudgetLedger)f.get(value)).close();}
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
    }
    @Test public void exitResetsOnlyFutureMessagesWhileAcceptedTaskAndContinuationStayXhigh(){
        runtime.enterForegroundRouting();select("xhigh");JSONObject first=runtime.receive("파일 목록을 확인해서 알려줘",new JSONArray());ready("xhigh","fast");
        assertEquals(1,transport.ids.size());runtime.leaveForegroundRouting();assertFalse(runtime.foregroundRoutingState().optBoolean("selected"));
        assertRoute(first,"xhigh","fast");finish(first,actions(first,"app.settings.get",new JSONObject()));JSONObject next=active();
        assertEquals("tool-continuation",next.optString("kind"));assertRoute(next,"xhigh","fast");finish(next,done(next));
        runtime.enterForegroundRouting();JSONObject fresh=runtime.receive("다음 새 대화",new JSONArray());assertRoute(fresh,"medium","fast");ready("medium","fast");assertEquals(fresh.optString("external_id"),transport.ids.get(transport.ids.size()-1));
    }
    @Test public void repeatedMessagesKeepSelectionAndBackgroundKeepsMediumStandard(){
        runtime.enterForegroundRouting();select("xhigh");JSONObject first=runtime.receive("첫 번째 작업",new JSONArray()),second=runtime.receive("두 번째 지시",new JSONArray());
        assertRoute(first,"xhigh","fast");assertRoute(second,"xhigh","fast");
        JSONObject background=store.enqueueRequest("정기 판단","autonomy",UUID.randomUUID().toString());assertRoute(background,"medium","standard");
        assertEquals("medium",store.getSetting("autonomousEffort",""));
    }
    @Test public void standardSelectionSurvivesScreenExitAndContinuationWithoutChangingNewDefaults()throws Exception{
        runtime.enterForegroundRouting();JSONObject choice=runtime.foregroundRoutingState();
        assertTrue(runtime.selectForegroundRouting(choice.optString("sessionId"),choice.optLong("revision"),"gpt-6.1-sol","high","standard",false).optBoolean("ok"));
        JSONObject first=runtime.receive("Standard로 작업을 계속해",new JSONArray());ready("high","standard");runtime.leaveForegroundRouting();assertRoute(first,"high","standard");
        finish(first,actions(first,"app.settings.get",new JSONObject()));JSONObject next=active();assertRoute(next,"high","standard");finish(next,done(next));
        runtime.enterForegroundRouting();assertRoute(runtime.receive("다음 새 메시지",new JSONArray()),"medium","fast");
        assertEquals("standard",new JSONObject(store.getSetting("ownerRouteOverride:"+first.optString("external_id"),"{}")).optString("speed"));
    }
    @Test public void chatFastOffAndLaterEffortOnlyChangeKeepStandard(){
        runtime.enterForegroundRouting();JSONObject first=runtime.receive("이번 작업 Fast 끄고 high로",new JSONArray());ready("medium","fast");
        finish(first,actions(first,"app.routing.session.set",json("effort","high","speed","standard")));ready("high","standard");
        JSONObject next=active();assertRoute(next,"high","standard");finish(next,done(next));
        JSONObject second=runtime.receive("강도만 xhigh로 올려",new JSONArray());ready("high","standard");finish(second,actions(second,"app.routing.session.set",json("effort","xhigh")));
        assertEquals("standard",runtime.foregroundRoutingState().optString("speed"));assertRoute(active(),"xhigh","standard");
    }
    @Test public void sendTapSnapshotSurvivesImmediateScreenOffBeforeAttachmentIo(){
        runtime.enterForegroundRouting();select("xhigh");JSONObject atTap=runtime.foregroundRoutingState();runtime.leaveForegroundRouting();
        JSONObject request=runtime.receive("전송 직후 화면 꺼짐",new JSONArray(),UUID.randomUUID().toString(),-1,UiAutomationProvenance.OWNER,atTap);assertRoute(request,"xhigh","fast");
        AssistantRuntime restarted=create();assertFalse(restarted.foregroundRoutingState().optBoolean("selected"));runtime=restarted;transport=new Transport();runtime.attachTransport(transport);ready("xhigh","fast");assertEquals("xhigh",restarted.requiredModelPreferences(request.optString("external_id")).optString("effort"));
    }
    @Test public void chatTemporarySelectionChangesContinuationAndLaterOwnerMessagesOnly(){
        runtime.enterForegroundRouting();JSONObject first=runtime.receive("이번 작업 xhigh로 해",new JSONArray());ready("medium","fast");
        finish(first,actions(first,"app.routing.session.set",json("effort","xhigh")));assertTrue(runtime.foregroundRoutingState().optBoolean("selected"));
        assertEquals("medium",store.getSetting("requiredEffort","medium"));assertEquals("medium",store.getSetting("autonomousEffort",""));
        ready("xhigh","fast");JSONObject next=active();assertRoute(next,"xhigh","fast");finish(next,done(next));
        assertRoute(runtime.receive("추가 지시",new JSONArray()),"xhigh","fast");
    }
    @Test public void staleChatSelectionCannotOverwriteNewManualChoiceOrReviveExitedSession(){
        runtime.enterForegroundRouting();JSONObject first=runtime.receive("이번 작업 high로 해",new JSONArray());ready("medium","fast");select("xhigh");
        finish(first,actions(first,"app.routing.session.set",json("effort","high")));
        assertEquals("xhigh",runtime.foregroundRoutingState().optString("effort"));assertEquals("screen_selection_changed",receipt(first).optString("code"));
        JSONObject next=active();finish(next,done(next));JSONObject second=runtime.receive("이번에는 high로",new JSONArray());ready("xhigh","fast");runtime.leaveForegroundRouting();
        finish(second,actions(second,"app.routing.session.set",json("effort","high")));
        assertEquals("screen_selection_changed",receipt(second).optString("code"));assertFalse(runtime.foregroundRoutingState().optBoolean("selected"));assertRoute(active(),"xhigh","fast");
    }
    @Test public void selectingModelAndTaskEffectsInOneEnvelopeDefersEffectsUntilChosenModel(){
        runtime.enterForegroundRouting();JSONObject first=runtime.receive("xhigh로 하고 파일 만들어",new JSONArray());ready("medium","fast");
        JSONObject envelope=actions(first,"app.routing.session.set",json("effort","xhigh"));
        envelope.optJSONArray("actions").put(json("id","write","name","files.write","arguments",json("relativePath","must-wait.txt","content","not-yet")));
        finish(first,envelope);assertFalse(new java.io.File(context.getFilesDir(),"projects/must-wait.txt").exists());
        ready("xhigh","fast");assertRoute(active(),"xhigh","fast");
    }
    @Test public void settingsCasMergesUnrelatedEditsAndRejectsSameFieldConflict()throws Exception{
        JSONObject baseline=runtime.personalSettingsBaseline();runtime.updatePersonalSettings(json("requiredEffort","high"),null);
        assertTrue(runtime.updatePersonalSettings(json("standingGoals","새 목표"),baseline).optBoolean("ok"));
        JSONObject conflict=runtime.updatePersonalSettings(json("requiredEffort","xhigh"),baseline);assertEquals("settings_changed",conflict.optString("code"));
        assertEquals("high",store.getSetting("requiredEffort",""));assertEquals("새 목표",store.getSetting("standingGoals",""));
    }
    @Test public void olderChatCannotOverwriteNewerPermanentUiSetting()throws Exception{
        JSONObject request=runtime.receive("앞으로 high로 해",new JSONArray());ready("medium","fast");
        runtime.updatePersonalSettings(json("requiredEffort","xhigh"),null);finish(request,actions(request,"app.settings.update",json("requiredEffort","high")));
        JSONObject result=receipt(request);if(result!=null)assertEquals("settings_changed",result.optString("code"));
        else assertEquals("completed",store.getRequest(request.optLong("id")).optString("status")); // A changed source policy can hold the old turn before tool dispatch.
        assertEquals("xhigh",store.getSetting("requiredEffort",""));
    }
    @Test public void renameDuringInferenceUsesNewIdentityWithoutChangingTaskModel()throws Exception{
        runtime.enterForegroundRouting();select("xhigh");JSONObject first=runtime.receive("네 이름을 알려줘",new JSONArray());ready("xhigh","fast");
        runtime.updateAssistantIdentity("하루",null,false,"default");finish(first,json("protocol","hoyoung.v1","requestId",first.optString("external_id"),"status","completed","message","나는 호영이야"));
        JSONObject next=active();assertNotNull(next);assertRoute(next,"xhigh","fast");assertEquals("하루",runtime.assistantIdentity().optString("name"));
        assertTrue(transport.prompts.get(transport.prompts.size()-1).contains("\"name\":\"하루\""));
        JSONArray page=store.boundedChatMessagePage(0,40,8000).optJSONArray("messages");
        for(int i=0;i<page.length();i++){JSONObject message=page.optJSONObject(i);if("assistant".equals(message.optString("role")))assertNotEquals("나는 호영이야",message.optString("text"));}
    }
    @Test public void migrationUpgradesLowOnceAndNeverDowngradesExplicitHigherOrRestoredChoice(){
        assertEquals("medium",store.getSetting("autonomousEffort",""));store.putSetting("autonomousEffort","low");create();assertEquals("low",store.getSetting("autonomousEffort",""));
        store.putSetting("routing.mediumDefaultMigration","");store.putSetting("autonomousEffort","high");create();assertEquals("high",store.getSetting("autonomousEffort",""));
    }
    @Test public void newProfileIsActuallyOfferedAsAttachmentAndDescriptionNeedsUploadReceipt()throws Exception{
        JSONObject file;try(java.io.InputStream input=context.getResources().openRawResource(com.hoyoung.assistant.R.drawable.hoyoung_profile)){file=files.writeFile("profile.jpg",input);}
        runtime.updateAssistantIdentity("하루",file.optString("id"),false,"default");
        JSONObject first=runtime.receive("내 프사가 어떤지 알아?",new JSONArray());ready("medium","fast");
        JSONArray metadata=new JSONArray(store.getSetting("attachmentMetadata:"+first.optString("external_id"),"[]"));assertEquals(1,metadata.length());
        JSONObject asset=metadata.optJSONObject(0),identity=runtime.assistantIdentity();assertEquals(0,asset.optLong("sourceMessageId"));assertEquals("assistant_profile",asset.optString("purpose"));
        JSONObject analysis=json("revision",identity.optString("revision"),"sha256",asset.optString("sha256"),"description","흰 모자를 쓴 고양이");
        JSONObject response=done(first);response.put("assistantAvatarAnalysis",analysis);finish(first,response);assertFalse(runtime.assistantIdentity().has("avatarDescription"));
        JSONObject second=runtime.receive("사진을 실제로 확인해",new JSONArray());ready("medium","fast");
        runtime.onWebEvent(json("type","accepted","requestId",second.optString("external_id"),"uploadObserved",true,"attachmentNames",new JSONArray().put(asset.optString("name",asset.optString("sourceName")))));
        response=done(second);response.put("assistantAvatarAnalysis",analysis);finish(second,response);assertEquals("흰 모자를 쓴 고양이",runtime.assistantIdentity().optString("avatarDescription"));
        JSONObject third=runtime.receive("평범한 다음 메시지",new JSONArray());ready("medium","fast");assertEquals(0,new JSONArray(store.getSetting("attachmentMetadata:"+third.optString("external_id"),"[]")).length());
    }

    private void select(String effort){JSONObject state=runtime.foregroundRoutingState();assertTrue(runtime.selectForegroundRouting(state.optString("sessionId"),state.optLong("revision"),"gpt-6.1-sol",effort,false).optBoolean("ok"));}
    private void assertRoute(JSONObject request,String effort,String speed){assertNotNull(request);JSONObject route;try{route=runtime.requiredModelPreferences(request.optString("external_id"));}catch(IllegalArgumentException notYetPrepared){try{java.lang.reflect.Method choose=AssistantRuntime.class.getDeclaredMethod("routeFor",JSONObject.class);choose.setAccessible(true);route=(JSONObject)choose.invoke(runtime,request);}catch(Exception failure){throw new AssertionError(failure);}}assertEquals(effort,route.optString("effort"));assertEquals(speed,route.optString("speed"));}
    private void ready(String effort,String speed){runtime.onWebEvent(json("type","state","code","ready","state",json("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort",effort,"speed",speed,"busy",false,"quota",false)));drain();}
    private void finish(JSONObject request,JSONObject envelope){runtime.onWebEvent(json("type","completed","requestId",request.optString("external_id"),"text",envelope.toString(),"rawEnvelopeEligible",true));drain();}
    private JSONObject active(){return store.getRequest(runtime.state().optLong("activeStoreId"));}
    private JSONObject receipt(JSONObject request){try{return new JSONObject(store.getSetting("actionReceipt:"+request.optString("external_id")+":change","{}")).optJSONObject("result");}catch(Exception e){throw new AssertionError(e);}}
    private static JSONObject done(JSONObject request){return json("protocol","hoyoung.v1","requestId",request.optString("external_id"),"status","completed","message","처리했어");}
    private static JSONObject actions(JSONObject request,String name,JSONObject args){return json("protocol","hoyoung.v1","requestId",request.optString("external_id"),"status","working","message","","actions",new JSONArray().put(json("id","change","name",name,"arguments",args)));}
    static JSONObject json(Object...pairs){JSONObject result=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);}catch(Exception e){throw new AssertionError(e);}return result;}
    private static void drain(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static class Transport implements AssistantRuntime.Transport{final List<String>ids=new ArrayList<>(),prompts=new ArrayList<>();public void inspect(){}public void send(String id,String prompt){ids.add(id);prompts.add(prompt);}public void openTemporary(){}public void cancel(){throw new AssertionError("Screen exit must never cancel the accepted Work task");}}
}
