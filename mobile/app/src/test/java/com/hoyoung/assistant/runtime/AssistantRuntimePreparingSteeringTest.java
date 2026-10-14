package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Actual synchronized attachment-freeze boundary; no injected fake preparation counter or external action. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class AssistantRuntimePreparingSteeringTest {
    private Context context;private HoyoungStore store;private InternalFiles files;private AssistantRuntime runtime;private Transport transport;
    @Before public void before()throws Exception{
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);deleteTree(new java.io.File(context.getFilesDir(),"projects"));store=new HoyoungStore(context);files=new InternalFiles(context);
        runtime=new AssistantRuntime(context,store,files,new PhoneTools(context,store),Runnable::run);transport=new Transport();runtime.attachTransport(transport);
        runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",false,"quota",false)));
    }
    @After public void after(){store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);deleteTree(new java.io.File(context.getFilesDir(),"projects"));}

    @Test public void preparingSteeringHoldsOldEffectBeforeNewOwnerSourceExistsAndThenPrioritizesOwner()throws Exception{
        JSONObject old=runtime.receive("파일을 작성해 줘",new JSONArray());runtime.onWebEvent(object("type","accepted","requestId",old.getString("external_id")));String previousRevision=store.getSetting("ownerInputRevision","");String steering="  첫 요청의 파일 작성은 취소야.\n새 파일을 쓰지 마.  ";Input incoming=new Input(steering);
        synchronized(files){
            incoming.startAndAwaitFreeze();assertEquals(previousRevision,store.getSetting("ownerInputRevision",""));
            complete(old,proposal(old,"old-effect","old-plan.txt"));
            assertEquals(0,files.listFiles().length());assertNull(store.getSetting("actionReceipt:"+old.getString("external_id")+":old-effect",null));assertEquals(1,transport.ids.size());
            runtime.tick();assertEquals(1,transport.ids.size());
        }
        incoming.awaitDone();assertNull(incoming.failure.get());JSONObject newest=incoming.request.get();assertNotNull(newest);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2,transport.ids.size());assertEquals(newest.getString("external_id"),transport.ids.get(1));assertNotEquals(previousRevision,store.getSetting("ownerInputRevision",""));
        long source=Long.parseLong(store.getSetting("requestSource:"+newest.getString("external_id"),"0"));assertEquals(steering,store.getMessage(source).getString("text"));
        complete(newest,"취소 지시를 보존했어.");Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(3,transport.ids.size());JSONObject continuation=store.getRequest(runtime.state().getLong("activeStoreId"));
        assertEquals(old.getString("external_id"),store.getSetting("requestRoot:"+continuation.getString("external_id"),""));
        String prompt=transport.prompts.get(2);assertTrue(prompt.contains("LATEST_OWNER_INPUT (takes precedence for corrections/cancellations)"));assertTrue(prompt.contains(JSONObject.quote(steering)));assertTrue(prompt.contains(store.getMessage(source).getString("content_hash")));assertTrue(prompt.contains("owner_steering_pending"));
        complete(continuation,object("protocol","hoyoung.v1","requestId",continuation.getString("external_id"),"status","blocked","message","최신 취소 지시에 따라 파일을 작성하지 않았어.").toString());
        assertEquals(0,files.listFiles().length());assertEquals("blocked",runtime.getGoalLedger().get(old.getString("external_id")).getString("stage"));assertEquals(0,transport.cancels);assertFalse(store.events(100).toString().contains("\"cloudStoppedConfirmed\":true"));
    }
    @Test public void rejectedEmptyInputReleasesPreparationGateWithoutInventingNewOwnerOrReplayingEffect()throws Exception{
        JSONObject old=runtime.receive("원래 파일 작업",new JSONArray());runtime.onWebEvent(object("type","accepted","requestId",old.getString("external_id")));String revision=store.getSetting("ownerInputRevision","");Input incoming=new Input("   ");
        synchronized(files){incoming.startAndAwaitFreeze();complete(old,proposal(old,"held-effect","rejected-input.txt"));assertEquals(0,files.listFiles().length());assertEquals(1,transport.ids.size());assertNull(store.getSetting("actionReceipt:"+old.getString("external_id")+":held-effect",null));}
        incoming.awaitDone();assertTrue(incoming.failure.get() instanceof IllegalArgumentException);assertNull(incoming.request.get());assertEquals(revision,store.getSetting("ownerInputRevision",""));Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2,transport.ids.size());JSONObject continuation=store.getRequest(runtime.state().getLong("activeStoreId"));assertEquals(old.getString("external_id"),store.getSetting("requestRoot:"+continuation.getString("external_id"),""));assertEquals(0,files.listFiles().length());int ownerMessages=0;for(int i=0;i<store.listMessages(100).length();i++)if("user".equals(store.listMessages(100).getJSONObject(i).getString("role")))ownerMessages++;assertEquals(1,ownerMessages);
        complete(continuation,object("protocol","hoyoung.v1","requestId",continuation.getString("external_id"),"status","blocked","message","입력 검증 실패 뒤 원래 계획을 다시 확인해야 해.").toString());assertEquals(0,files.listFiles().length());
    }
    private final class Input{
        final AtomicReference<JSONObject> request=new AtomicReference<>();final AtomicReference<Throwable> failure=new AtomicReference<>();final CountDownLatch started=new CountDownLatch(1),done=new CountDownLatch(1);final Thread thread;
        Input(String text){thread=new Thread(()->{started.countDown();try{request.set(runtime.receive(text,new JSONArray()));}catch(Throwable error){failure.set(error);}finally{done.countDown();}},"test-owner-attachment-freeze");thread.setDaemon(true);}
        void startAndAwaitFreeze()throws Exception{thread.start();assertTrue(started.await(5,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(thread.getState()!=Thread.State.BLOCKED&&done.getCount()!=0&&System.nanoTime()<deadline)Thread.yield();assertEquals(Thread.State.BLOCKED,thread.getState());boolean freezeFrame=false;for(StackTraceElement frame:thread.getStackTrace())if(frame.getClassName().equals(InternalFiles.class.getName())&&frame.getMethodName().equals("freezeAttachments"))freezeFrame=true;assertTrue("Receiver must be blocked at the actual synchronized freezeAttachments boundary",freezeFrame);}
        void awaitDone()throws Exception{assertTrue(done.await(5,TimeUnit.SECONDS));thread.join(1000);assertFalse(thread.isAlive());}
    }
    private void complete(JSONObject request,String response)throws Exception{runtime.onWebEvent(object("type","completed","requestId",request.getString("external_id"),"text",response,"rawEnvelopeEligible",true));}
    private static String proposal(JSONObject request,String actionId,String path)throws Exception{return object("protocol","hoyoung.v1","requestId",request.getString("external_id"),"message","이전 파일 계획","actions",new JSONArray().put(object("id",actionId,"name","files.write","arguments",object("relativePath",path,"content","must not be written while owner input prepares")))).toString();}
    private static JSONObject object(Object...pairs)throws Exception{JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)value.put(String.valueOf(pairs[i]),pairs[i+1]);return value;}
    private final class Transport implements AssistantRuntime.Transport{final List<String> ids=new ArrayList<>(),prompts=new ArrayList<>();int cancels;@Override public void inspect(){}@Override public void send(String id,String prompt){ids.add(id);prompts.add(prompt);}@Override public void openTemporary(){}@Override public void cancel(){cancels++;}}
    private static void deleteTree(java.io.File file){if(!file.exists())return;java.io.File[] children=file.listFiles();if(children!=null)for(java.io.File child:children)deleteTree(child);if(!file.delete())throw new IllegalStateException("Could not remove test working file");}
}
