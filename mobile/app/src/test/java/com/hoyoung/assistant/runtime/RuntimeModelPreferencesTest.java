package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
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
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Persisted selection versus actual observed fake browser state; no real model/API/subscription call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class RuntimeModelPreferencesTest {
    private Context context;private HoyoungStore store;private AssistantRuntime runtime;private Transport transport;
    @Before public void before()throws Exception{context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);store.putSetting("memoryReviewEnabled","false");store.putSetting("customInstructions","original owner instructions");runtime=new AssistantRuntime(context,store,new InternalFiles(context),new PhoneTools(context,store),Runnable::run);transport=new Transport();runtime.attachTransport(transport);}
    @After public void after(){store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}

    @Test public void defaultRemainsExactlySixPointOneMediumFast()throws Exception{
        JSONObject request=runtime.receive("기본 선택 테스트",new JSONArray());observed("gpt-6.1-sol","medium","standard");assertEquals(0,transport.ids.size());observed("gpt-6.1-sol","high","fast");assertEquals(0,transport.ids.size());observed("gpt-6.1-sol","medium","fast");assertTrue(runtime.state().getBoolean("ready"));assertEquals(request.getString("external_id"),transport.ids.get(0));
    }
    @Test public void newPersistedChoiceRequiresExactObservedModelEffortAndSpeed()throws Exception{
        choose("gpt-6.2-sol","high","standard");JSONObject request=runtime.receive("새 모델 선택 테스트",new JSONArray());observed("gpt-6.1-sol","medium","fast");assertFalse(runtime.state().getBoolean("ready"));assertEquals(0,transport.ids.size());
        observed("gpt-6.2-sol","medium","fast");assertEquals(0,transport.ids.size());observed("gpt-6.2-sol","high","fast");assertEquals(0,transport.ids.size());observed("gpt-6.2-sol","high","standard");assertTrue(runtime.state().getBoolean("ready"));assertEquals(request.getString("external_id"),transport.ids.get(0));
    }
    @Test public void similarModelPrefixCannotSatisfyExactNewSelection()throws Exception{
        choose("gpt-6.2-sol","high","standard");JSONObject request=runtime.receive("접두어 오인 금지",new JSONArray());for(String wrong:new String[]{"gpt-6.20-sol","gpt-6.2-solar","gpt-6.2-sol-preview"}){observed(wrong,"high","standard");assertFalse(runtime.state().getBoolean("ready"));assertEquals(0,transport.ids.size());}
        observed("6.2-sol","high","standard");assertEquals(request.getString("external_id"),transport.ids.get(0));
    }
    @Test public void invalidCombinedSettingsUpdateCannotPartiallyChangeOtherInstructions()throws Exception{
        observed("gpt-6.1-sol","medium","fast");JSONObject[] invalid={object("requiredModel","6.2 sol"),object("requiredEffort","extreme"),object("requiredSpeed","turbo"),object("requiredModel",JSONObject.NULL)};
        for(int i=0;i<invalid.length;i++){JSONObject request=runtime.receive("잘못된 선택은 전체 변경 거부",new JSONArray());JSONObject args=invalid[i].put("customInstructions","must never overwrite original").put("standingGoals","must not commit goals");String actionId="invalid-model-"+i;complete(request,settingsEnvelope(request,actionId,args));
            assertEquals("original owner instructions",store.getSetting("customInstructions",""));assertEquals("",store.getSetting("standingGoals",""));assertEquals("gpt-6.1-sol",store.getSetting("requiredModel","gpt-6.1-sol"));assertEquals("medium",store.getSetting("requiredEffort","medium"));assertEquals("fast",store.getSetting("requiredSpeed","fast"));
            JSONObject receipt=new JSONObject(store.getSetting("actionReceipt:"+request.getString("external_id")+":"+actionId,"{}"));assertFalse(receipt.getJSONObject("result").getBoolean("ok"));assertEquals("invalid_arguments",receipt.getJSONObject("result").getString("code"));JSONObject continuation=active();complete(continuation,object("protocol","hoyoung.v1","requestId",continuation.getString("external_id"),"status","blocked","message","잘못된 설정을 적용하지 않았어.").toString());
        }
    }
    @Test public void validConversationSettingsPersistThenGateContinuationUntilNewSelectionObserved()throws Exception{
        observed("gpt-6.1-sol","medium","fast");JSONObject request=runtime.receive("다음부터 모델을 바꿔",new JSONArray());complete(request,settingsEnvelope(request,"change",object("requiredModel","gpt-6.2-sol","requiredEffort","high","requiredSpeed","standard","customInstructions","updated owner instructions")));
        assertEquals("gpt-6.2-sol",store.getSetting("requiredModel",""));assertEquals("high",store.getSetting("requiredEffort",""));assertEquals("standard",store.getSetting("requiredSpeed",""));assertEquals("updated owner instructions",store.getSetting("customInstructions",""));assertFalse(runtime.state().getBoolean("ready"));assertEquals(1,transport.ids.size());
        observed("gpt-6.2-sol","high","fast");assertEquals(1,transport.ids.size());observed("gpt-6.2-sol","high","standard");assertEquals(2,transport.ids.size());assertTrue(runtime.state().getBoolean("ready"));assertEquals("tool-continuation",active().getString("kind"));
    }
    @Test public void malformedPersistedPreferencesFailClosedEvenWhenBrowserDefaultLooksReady()throws Exception{
        choose("broken model","medium","fast");runtime.receive("설정이 손상된 상황",new JSONArray());observed("gpt-6.1-sol","medium","fast");assertFalse(runtime.state().getBoolean("ready"));assertEquals(0,transport.ids.size());
        choose("gpt-6.1-sol","unsupported","fast");observed("gpt-6.1-sol","medium","fast");assertFalse(runtime.state().getBoolean("ready"));assertEquals(0,transport.ids.size());
    }
    private void choose(String model,String effort,String speed){store.putSetting("requiredModel",model);store.putSetting("requiredEffort",effort);store.putSetting("requiredSpeed",speed);}
    private void observed(String model,String effort,String speed)throws Exception{runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model",model,"effort",effort,"speed",speed,"busy",false,"quota",false)));}
    private JSONObject active()throws Exception{return store.getRequest(runtime.state().getLong("activeStoreId"));}
    private void complete(JSONObject request,String text)throws Exception{runtime.onWebEvent(object("type","completed","requestId",request.getString("external_id"),"text",text,"rawEnvelopeEligible",true));}
    private static String settingsEnvelope(JSONObject request,String id,JSONObject args)throws Exception{return object("protocol","hoyoung.v1","requestId",request.getString("external_id"),"status","working","message","설정 변경 요청","actions",new JSONArray().put(object("id",id,"name","app.settings.update","arguments",args))).toString();}
    private static JSONObject object(Object...pairs)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put(String.valueOf(pairs[i]),pairs[i+1]);return result;}
    private static final class Transport implements AssistantRuntime.Transport{final List<String> ids=new ArrayList<>();@Override public void inspect(){}@Override public void send(String id,String prompt){ids.add(id);}@Override public void openTemporary(){}@Override public void cancel(){}}
}
