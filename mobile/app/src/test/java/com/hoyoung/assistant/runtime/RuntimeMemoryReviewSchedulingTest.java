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

/** Real persisted extraction backlog and fake browser dispatch; measures scope/budget, not model latency. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class RuntimeMemoryReviewSchedulingTest {
    private Context context; private HoyoungStore store; private AssistantRuntime runtime; private Transport transport; private long oldestSource;
    @Before public void before()throws Exception{
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);
        for(int i=0;i<8;i++){long id=store.addMessage("user","오래된 대화 원문 "+i+":"+"\u0000".repeat(20_000),null).getLong("id");if(i==0)oldestSource=id;}
        store.putSetting("autonomy.enabled","false");store.putSetting("memoryReviewEnabled","true");
        runtime=new AssistantRuntime(context,store,new InternalFiles(context),new PhoneTools(context,store),Runnable::run);transport=new Transport();runtime.attachTransport(transport);
        runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",false,"quota",false)));
    }
    @After public void after(){store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}

    @Test public void interactiveMessageAndItsReadContinuationDoNotCarryTheExtractionBacklog()throws Exception{
        JSONObject owner=runtime.receive("지금 답변에 필요한 현재 조건만 확인해",new JSONArray());String id=owner.getString("external_id");
        assertEquals("",store.getSetting("memoryReview:"+id,""));assertFalse(transport.prompts.get(0).contains("미검토 기억 배치"));assertTrue(runtime.getMemoryReview().hasPending());
        complete(owner,object("protocol","hoyoung.v1","requestId",id,"status","working","message","관련 저장 조건 확인",
                "actions",new JSONArray().put(object("id","lookup","name","memory.search","arguments",object("query","현재 조건")))).toString());
        JSONObject continuation=active();assertEquals("tool-continuation",continuation.getString("kind"));assertEquals(2,transport.ids.size());
        assertEquals("",store.getSetting("memoryReview:"+continuation.getString("external_id"),""));assertFalse(transport.prompts.get(1).contains("미검토 기억 배치"));
        assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertTrue(runtime.getMemoryReview().hasPending());
    }
    @Test public void explicitReviewUsesAnExactBoundedBatchWithoutSkippingTheLargeEscapedSource()throws Exception{
        runtime.requestMemoryReview();JSONObject request=active();assertEquals("memory-review",request.getString("kind"));String id=request.getString("external_id");
        String serialized=store.getSetting("memoryReview:"+id,"");assertFalse(serialized.isEmpty());assertTrue(serialized.length()<=12_000);
        JSONObject batch=new JSONObject(serialized),first=batch.getJSONArray("sourceRanges").getJSONObject(0);assertEquals(oldestSource,first.getLong("messageId"));assertEquals(0,first.getLong("offsetChars"));
        assertTrue(first.getLong("nextOffsetChars")>0);assertTrue(first.getLong("nextOffsetChars")<first.getLong("totalChars"));assertEquals(0,batch.getLong("cursorEnd"));
        JSONObject actual=store.readOriginalText(oldestSource,0,(int)first.getLong("nextOffsetChars"));assertEquals(actual.getString("text"),first.getString("text"));assertEquals(actual.getString("contentHash"),first.getString("contentHash"));
        assertTrue(transport.prompts.get(0).contains(serialized));assertTrue(transport.prompts.get(0).contains("이번 턴 목적은 기억 검토뿐"));assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
    }
    @Test public void backgroundReviewIsItsOwnTurnAndDoesNotConsumeAUserGoalSlot()throws Exception{
        // Background review fills the interval between inference ticks; a due
        // inference judgment has its own priority and is not a review request.
        long nextDue=System.currentTimeMillis()+300_000L;
        JSONObject cadence=runtime.getAutonomyTickPolicy().snapshot(System.currentTimeMillis());cadence.put("enabled",true).put("nextDueAt",nextDue);
        store.putSetting(AutonomyTickPolicy.STATE_KEY,cadence.toString());store.putSetting("runtime.lastMemoryReviewAt","0");
        store.putSetting("autonomy.enabled","true");runtime.tick();JSONObject request=active();assertEquals("memory-review",request.getString("kind"));
        assertEquals("0",store.getSetting("requestSource:"+request.getString("external_id"),""));assertFalse(store.getSetting("memoryReview:"+request.getString("external_id"),"").isEmpty());
        assertEquals(0,runtime.getGoalLedger().list(null).getLong("totalPending"));assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertEquals(1,transport.ids.size());
        assertEquals(nextDue,runtime.getAutonomyTickPolicy().snapshot(System.currentTimeMillis()).getLong("nextDueAt"));
    }
    private JSONObject active()throws Exception{return store.getRequest(runtime.state().getLong("activeStoreId"));}
    private void complete(JSONObject request,String text)throws Exception{runtime.onWebEvent(object("type","completed","requestId",request.getString("external_id"),"text",text,"rawEnvelopeEligible",true));}
    private static JSONObject object(Object...pairs)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put(String.valueOf(pairs[i]),pairs[i+1]);return result;}
    private static final class Transport implements AssistantRuntime.Transport{final List<String> ids=new ArrayList<>(),prompts=new ArrayList<>();@Override public void inspect(){}@Override public void send(String id,String prompt){ids.add(id);prompts.add(prompt);}@Override public void openTemporary(){}@Override public void cancel(){}}
}
