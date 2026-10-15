package com.hoyoung.assistant.web;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.runtime.AssistantRuntime;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Native queue and durable browser ownership must agree before advertising readiness. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class WorkConversationRecoveryTest {
    private Context context;
    private HoyoungStore store;
    private AssistantRuntime runtime;
    private WorkWebController controller;
    private WorkConversationJournal journal;
    private int sends;

    @Before public void setUp() throws Exception {
        context=RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); clearJournal();
        store=new HoyoungStore(context); store.putSetting("memoryReviewEnabled","false");
        InternalFiles files=new InternalFiles(context);
        runtime=new AssistantRuntime(context,store,files,new PhoneTools(context,store),Runnable::run);
        runtime.attachTransport(new AssistantRuntime.Transport(){
            public void inspect(){} public void send(String id,String prompt){sends++;}
            public void openTemporary(){} public void cancel(){}
        });
        controller=new WorkWebController(context,runtime,files);
        Field field=WorkWebController.class.getDeclaredField("conversations"); field.setAccessible(true);
        journal=(WorkConversationJournal)field.get(controller);
    }
    @After public void tearDown() throws Exception {store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clearJournal();}
    private void clearJournal() throws Exception {
        for(String suffix:new String[]{"",".bak",".new"})Files.deleteIfExists(new File(context.getNoBackupFilesDir(),"work-conversation.json"+suffix).toPath());
    }
    private JSONObject readyState() throws Exception {return new JSONObject().put("authenticated",true).put("workSelected",true).put("busy",false).put("quota",false).put("model","gpt-6.1-sol").put("effort","medium").put("speed","fast");}
    private void emitReady() throws Exception {
        Method method=WorkWebController.class.getDeclaredMethod("emitState",JSONObject.class,String.class);method.setAccessible(true);method.invoke(controller,readyState(),"ready");
    }
    private JSONObject unresolved() throws Exception {
        emitReady();JSONObject request=runtime.receive("이미 확인한 이전 작업",new JSONArray());
        journal.begin(request.getString("external_id"));
        runtime.onWebEvent(new JSONObject().put("type","error").put("code","WORK_PAGE_LOAD_FAILED").put("requestId",request.getString("external_id")));
        assertEquals("uncertain",store.getRequest(request.getLong("id")).getString("status"));return request;
    }
    @Test public void ownerResolvedOldCreationDoesNotBlockNewRequestsOrClaimDeletion() throws Exception {
        JSONObject old=unresolved();runtime.resolveUncertain(old.getLong("id"));emitReady();
        assertEquals("",journal.pendingRequest());assertEquals("",new WorkConversationJournal(context).pendingRequest());
        assertTrue(runtime.state().getBoolean("ready"));
        JSONObject next=runtime.receive("다음 인사",new JSONArray());assertEquals(2,sends);
        journal.begin(next.getString("external_id"));assertTrue(journal.accept(next.getString("external_id"),"new-owned-123"));
        assertEquals("completed",store.getRequest(old.getLong("id")).getString("status"));
        String retired=store.getSetting("workCreationRetired:"+old.getString("external_id"),"");
        assertFalse(retired.isEmpty());assertFalse(new JSONObject(retired).getBoolean("providerDeletionVerified"));
    }
    @Test public void ownerAbandonedCreationIsPreservedWithoutReplayingTheOldTask() throws Exception {
        JSONObject old=unresolved();runtime.abandonUncertain(old.getLong("id"));emitReady();
        assertEquals("",journal.pendingRequest());assertEquals(1,sends);
        assertEquals("abandoned",store.getRequest(old.getLong("id")).getString("status"));
        assertTrue(runtime.state().getBoolean("ready"));
    }
    @Test public void unreviewedOrMissingCreationNeverAdvertisesReadyOrDiscardsItsReceipt() throws Exception {
        JSONObject old=unresolved();emitReady();
        assertFalse(runtime.state().getBoolean("ready"));assertEquals(old.getString("external_id"),journal.pendingRequest());
        assertEquals("conversation_ownership_unresolved",runtime.state().getString("readinessCode"));
        store.getWritableDatabase().execSQL("DELETE FROM requests");emitReady();
        assertFalse(runtime.state().getBoolean("ready"));assertEquals(old.getString("external_id"),journal.pendingRequest());
    }
    @Test public void nativePreflightFailureIsVisibleAndCannotMasqueradeAsAReply() throws Exception {
        emitReady();JSONObject request=runtime.receive("인사해 줘",new JSONArray());
        runtime.onWebEvent(new JSONObject().put("type","error").put("code","conversation_journal_unavailable")
                .put("knownNotSent",true).put("requestId",request.getString("external_id")));
        assertEquals("blocked",store.getRequest(request.getLong("id")).getString("status"));
        int notices=0;JSONArray messages=store.listMessages(20);
        for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);
            if("system".equals(message.optString("role"))&&request.getString("external_id").equals(message.optString("request_id"))
                    &&message.optString("text").startsWith("이 요청은 전송 전에 멈췄어."))notices++;}
        assertEquals(1,notices);
        assertFalse(runtime.state().getBoolean("activeOwnerReply"));assertEquals(1,sends);
    }
    @Test public void persistedNotSentProofReleasesOnlyItsOwnReservationAfterRestart() throws Exception {
        emitReady();JSONObject request=runtime.receive("보내지 않은 요청",new JSONArray());String id=request.getString("external_id");
        journal.begin(id);
        runtime.onWebEvent(new JSONObject().put("type","error").put("code","native_authorization_denied").put("knownNotSent",true).put("requestId",id));
        // Simulate process loss between native receipt and browser journal cleanup.
        emitReady();assertEquals("",new WorkConversationJournal(context).pendingRequest());assertTrue(runtime.state().getBoolean("ready"));
        assertEquals("known_not_sent",new JSONObject(store.getSetting("workCreationRetired:"+id,"")).getString("disposition"));
        assertEquals("blocked",store.getRequest(request.getLong("id")).getString("status"));assertEquals(1,sends);
    }
    @Test public void blockedWithoutProofIsNotTreatedAsUnsentAndOwnedChatsStillRequireDeletion() throws Exception {
        emitReady();JSONObject request=runtime.receive("근거 없는 복구를 거부",new JSONArray());String id=request.getString("external_id");
        runtime.onWebEvent(new JSONObject().put("type","error").put("code","native_authorization_denied").put("knownNotSent",true).put("requestId",id));
        store.putSetting("dispatchKnownNotSent:"+id,"");journal.begin(id);emitReady();
        assertEquals(id,journal.pendingRequest());assertFalse(runtime.state().getBoolean("ready"));
        assertTrue(journal.accept(id,"owned-prior-123"));emitReady();assertEquals("owned-prior-123",journal.id());
        journal.requestCleanup();emitReady();assertFalse(runtime.state().getBoolean("ready"));assertTrue(journal.cleaning());
    }
}
