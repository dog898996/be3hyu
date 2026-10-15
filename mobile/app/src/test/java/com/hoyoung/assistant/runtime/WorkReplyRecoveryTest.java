package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class WorkReplyRecoveryTest {
    Context context; HoyoungStore store; AssistantRuntime runtime;
    final List<String> sent=new ArrayList<>();
    @Before public void before() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        store=new HoyoungStore(context);store.putSetting("memoryReviewEnabled","false");store.putSetting("autonomy.enabled","false");
        runtime=new AssistantRuntime(context,store,new InternalFiles(context),new PhoneTools(context,store),Runnable::run);
        runtime.attachTransport(new AssistantRuntime.Transport(){public void inspect(){} public void send(String id,String prompt){sent.add(id);} public void openTemporary(){} public void cancel(){}});
        Shadows.shadowOf(Looper.getMainLooper()).idle();ready();
    }
    @After public void after(){store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    private void ready(){runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",false,"quota",false)));}
    private JSONObject interrupted(){JSONObject request=runtime.receive("안녕, 대답해 줘",new JSONArray());
        assertEquals(1,sent.size());runtime.onWebEvent(object("type","error","code","navigation_uncertain","requestId",request.optString("external_id"),"mayHaveBeenAccepted",true));
        assertTrue(runtime.state().optBoolean("uncertaintyBlocked"));return request;}
    private JSONObject proof(JSONObject request,boolean proposals){String id=request.optString("external_id");JSONObject envelope=object("protocol","hoyoung.v1","requestId",id,"message","안녕! 반가워.","status","completed");
        if(proposals){put(envelope,"actions",new JSONArray().put(object("id","never-replay","name","files.write","arguments",object("relativePath","must-not-exist.txt","content","NO"))));
            put(envelope,"memories",new JSONArray().put(object("key","must-not-promote","content","검증 안 된 제안","kind","fact")));}
        return object("ok",true,"captureComplete",true,"requestId",id,"conversationId","recovered-conversation-123","envelope",envelope.toString());}
    @Test public void oldReplyIsImportedOnceAndUnlocksTheQueueWithoutResubmittingTheOldRequest(){
        JSONObject request=interrupted();JSONObject next=runtime.receive("두 번째 지시",new JSONArray());assertEquals(1,sent.size());
        assertTrue(runtime.recoverObservedWebReply(proof(request,false)));assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
        assertEquals("completed",store.getRequest(request.optLong("id")).optString("status"));assertTrue(store.listMessages(100).toString().contains("복구한 이전 답변"));
        int count=store.listMessages(100).length();assertFalse(runtime.recoverObservedWebReply(proof(request,false)));assertEquals(count,store.listMessages(100).length());
        ready();assertEquals(2,sent.size());assertEquals(next.optString("external_id"),sent.get(1));assertEquals(1,sent.stream().filter(request.optString("external_id")::equals).count());
    }
    @Test public void staleToolAndMemoryProposalsStayInTheExactOriginalAndAreNotReplayed(){
        JSONObject request=interrupted(),proof=proof(request,true);assertTrue(runtime.recoverObservedWebReply(proof));
        assertEquals("blocked",store.getRequest(request.optLong("id")).optString("status"));
        assertEquals(proof.optString("envelope"),store.getRequest(request.optLong("id")).optString("result"));
        assertNull(store.getSetting("actionReceipt:"+request.optString("external_id")+":never-replay",null));
        assertFalse(store.memoryContext("must-not-promote",20000).contains("검증 안 된 제안"));assertEquals(1,sent.size());
    }
    @Test public void mismatchedRequestOrAnUnsettledCaptureCannotReleaseTheRequest(){
        JSONObject request=interrupted(),p=proof(request,false);put(p,"requestId","another-request");assertFalse(runtime.recoverObservedWebReply(p));
        p=proof(request,false);put(p,"captureComplete",false);assertFalse(runtime.recoverObservedWebReply(p));assertTrue(runtime.state().optBoolean("uncertaintyBlocked"));
    }
    @Test public void changedPrivacyContextKeepsTheOldReplyArchivedInsteadOfPublishingIt(){
        JSONObject request=interrupted();store.putSetting("requestPrivacyFingerprint:"+request.optString("external_id"),"obsolete-context");
        assertTrue(runtime.recoverObservedWebReply(proof(request,false)));
        assertFalse(store.listMessages(100).toString().contains("안녕! 반가워."));assertTrue(store.getRequest(request.optLong("id")).optString("result").contains("안녕! 반가워."));
    }
    private static JSONObject object(Object...pairs){JSONObject o=new JSONObject();for(int i=0;i<pairs.length;i+=2)put(o,String.valueOf(pairs[i]),pairs[i+1]);return o;}
    private static void put(JSONObject o,String key,Object value){try{o.put(key,value);}catch(Exception e){throw new IllegalArgumentException(e);}}
}
