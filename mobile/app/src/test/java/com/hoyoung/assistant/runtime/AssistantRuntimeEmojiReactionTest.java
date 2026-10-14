package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.store.EmojiReactionReply;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import com.hoyoung.assistant.store.SourcePayloadDeletion;
import com.hoyoung.assistant.store.SourceRecordVisibility;
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
import org.robolectric.annotation.SQLiteMode;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Actual native queue, source ledger and backup integration; the provider responses are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AssistantRuntimeEmojiReactionTest {
    private Context context; private HoyoungStore store; private AssistantRuntime runtime;
    private final List<String> prompts = new ArrayList<>();
    @Before public void before() throws Exception {
        context=RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clearPolicy();
        context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME); store=new HoyoungStore(context);
        store.putSetting("memoryReviewEnabled","false");store.putSetting("autonomy.enabled","false");
        runtime=new AssistantRuntime(context,store,new InternalFiles(context),new PhoneTools(context,store),Runnable::run,System::currentTimeMillis);
        runtime.attachTransport(new AssistantRuntime.Transport() {
            public void inspect(){} public void openTemporary(){} public void cancel(){}
            public void send(String id,String prompt){prompts.add(prompt);}
        }); ready();
    }
    @After public void after() throws Exception {
        drain(); java.lang.reflect.Field field=AssistantRuntime.class.getDeclaredField("usageBudget");field.setAccessible(true);
        ((UsageBudgetLedger)field.get(runtime)).close();store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
        clearPolicy();
    }

    @Test public void reactionIsExactBoundAssistantOriginalAndRetrievableReplyWithoutExtraInference() throws Exception {
        JSONObject request=runtime.receive("나 운동갔다올게 이제",new JSONArray()), target=target(request);
        finish(request,reaction(request,target,"💪"));
        JSONObject answer=answer(request);assertNotNull(answer);assertEquals("assistant",answer.optString("role"));
        assertTrue(answer.optString("text").contains("문장 대신 이모지로 응답했다"));
        assertTrue(answer.optString("text").contains("무응답이나 읽씹이 아니다"));
        assertEquals(answer.optString("text"),store.readOriginalText(answer.optLong("id"),0,2000).optString("text"));
        JSONObject descriptor=EmojiReactionReply.fromStoredMessage(pageRow(answer.optLong("id")));
        assertEquals("💪",descriptor.optString("emoji"));assertEquals(target.optLong("id"),descriptor.optLong("targetMessageId"));
        assertEquals(target.optString("content_hash"),descriptor.optString("targetRecordHash"));
        JSONObject memory=store.getMemory("task-result:"+request.optString("external_id"));
        assertNotNull(memory);assertEquals(answer.optString("text"),memory.optString("content"));
        assertEquals(1,prompts.size());assertEquals("completed",store.getRequest(request.optLong("id")).optString("status"));
        assertEquals("fast",runtime.requiredModelPreferences(request.optString("external_id")).optString("speed"));
        assertTrue(prompts.get(0).contains("질문·요청·정정·중요/긴급/애매한 말에는 평소처럼 문장"));
        runtime.receive("방금 운동 간다는 말에 네가 어떻게 답했지?",new JSONArray());
        assertEquals(2,prompts.size());assertTrue(prompts.get(1).contains("문장 대신 이모지로 응답했다"));
        assertTrue(prompts.get(1).contains("💪"));
    }

    @Test public void restartAndSnapshotRestoreKeepReplyIdentityAndNativeTargetLink() throws Exception {
        JSONObject request=runtime.receive("고마워",new JSONArray());finish(request,reaction(request,target(request),"❤️"));
        JSONObject answer=answer(request);long id=answer.optLong("id");String hash=answer.optString("content_hash");
        JSONObject snapshot=store.exportSnapshot();
        try(HoyoungStore reopened=new HoyoungStore(context)) {
            JSONObject row=reopened.boundedChatMessagePage(0,40,8000).optJSONArray("messages").optJSONObject(
                    reopened.boundedChatMessagePage(0,40,8000).optJSONArray("messages").length()-1);
            assertEquals(hash,row.optString("content_hash"));assertEquals("❤️",EmojiReactionReply.fromStoredMessage(row).optString("emoji"));
        }
        store.importSnapshot(snapshot);
        assertEquals(hash,store.getMessage(id).optString("content_hash"));
        assertEquals("❤️",EmojiReactionReply.fromStoredMessage(pageRow(id)).optString("emoji"));
    }

    @Test public void notificationDisplaysEmojiWithoutDumpingItsProtocolOrClaimingItWasRead() {
        android.app.NotificationManager manager=(android.app.NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancelAll();Shadows.shadowOf(manager).setNotificationsEnabled(true);
        try(AssistantReplyNotifications replies=new AssistantReplyNotifications(context,store,Runnable::run)) {
            runtime.setReplyNotifications(replies);
            JSONObject request=runtime.receive("나 운동갔다올게 이제",new JSONArray());finish(request,reaction(request,target(request),"💪"));
            android.service.notification.StatusBarNotification[] notifications=manager.getActiveNotifications();
            assertEquals(1,notifications.length);
            String preview=String.valueOf(notifications[0].getNotification().extras.getCharSequence(android.app.Notification.EXTRA_TEXT));
            assertEquals("내 메시지에 💪 반응했어",preview);assertFalse(preview.contains("targetRecordHash"));
            assertFalse(replies.state().optBoolean("readConfirmed"));
        } finally {manager.cancelAll();}
    }

    @Test public void shortChatExcerptCannotBeMisreadAsCompleteReactionReceipt() {
        JSONObject request=runtime.receive("고마워",new JSONArray());finish(request,reaction(request,target(request),"😊"));
        JSONArray rows=store.boundedChatMessagePage(0,40,30).optJSONArray("messages");
        JSONObject row=rows.optJSONObject(rows.length()-1);assertTrue(row.optBoolean("textIsExcerpt"));
        assertNull(EmojiReactionReply.fromStoredMessage(row));
    }

    @Test public void oldReactionRemainsSearchableByOriginalOwnerWordsOutsideRecentChatPage() {
        JSONObject request=runtime.receive("나 수영갔다올게 이제",new JSONArray());finish(request,reaction(request,target(request),"💪"));
        JSONObject answer=answer(request);
        for(int i=0;i<120;i++)store.addMessage("user","다른 이야기 기록 "+i,null);
        assertNull(pageRow(answer.optLong("id")));
        JSONArray retrieved=store.rankedOriginalCandidates("수영갔다올게",0,Long.MAX_VALUE,30);boolean found=false;
        for(int i=0;i<retrieved.length();i++) {
            JSONObject row=retrieved.optJSONObject(i);
            if(row.optLong("id")==answer.optLong("id")) {
                assertTrue(row.optString("text").contains("💪"));assertTrue(row.optString("text").contains("이모지로 응답했다"));found=true;
            }
        }
        assertTrue("The reaction itself must be retrieved by the original conversation words",found);
    }

    @Test public void typingStateSeparatesOwnerRepliesFromRoutineAutonomy() {
        assertFalse(runtime.state().optBoolean("activeOwnerReply"));
        JSONObject request=runtime.receive("고마워",new JSONArray());assertTrue(runtime.state().optBoolean("activeOwnerReply"));
        finish(request,reaction(request,target(request),"😊"));assertFalse(runtime.state().optBoolean("activeOwnerReply"));
        store.putSetting("autonomy.enabled","true");runtime.tick();drain();
        assertFalse(runtime.state().isNull("activeRequestId"));assertFalse(runtime.state().optBoolean("activeOwnerReply"));
    }

    @Test public void exclusionBlocksReactionAndAnOldBackupDoesNotUndoTheExclusion() throws Exception {
        JSONObject request=runtime.receive("나 운동갔다올게 이제",new JSONArray()),target=target(request);
        finish(request,reaction(request,target,"💪"));JSONObject answer=answer(request),snapshot=store.exportSnapshot();
        assertTrue(SourceRecordVisibility.allowed(store.getReadableDatabase(),store.getPrivacyLedger().capture(),"message",answer));
        store.getPrivacyLedger().excludeSource("message",target.optLong("id"),target.optString("content_hash"),"retrieval_exclude","owner exclusion");
        assertFalse(SourceRecordVisibility.allowed(store.getReadableDatabase(),store.getPrivacyLedger().capture(),"message",answer));
        store.importSnapshot(snapshot);
        assertFalse(SourceRecordVisibility.allowed(store.getReadableDatabase(),store.getPrivacyLedger().capture(),"message",store.getMessage(answer.optLong("id"))));
    }

    @Test public void physicalSourceDeletionAlsoDeletesReactionAndItsGeneratedMemory() throws Exception {
        JSONObject request=runtime.receive("나 운동갔다올게 이제",new JSONArray()),target=target(request);
        finish(request,reaction(request,target,"💪"));JSONObject answer=answer(request);
        JSONObject result=new SourcePayloadDeletion(context,store).deleteSource("message",target.optLong("id"),target.optString("content_hash"),"explicit deletion");
        assertTrue(result.toString(),result.optBoolean("managedLocalDeletionVerified"));
        assertNull(store.getMessage(target.optLong("id")));assertNull(store.getMessage(answer.optLong("id")));
        assertEquals("",store.getSetting("answerSources:"+answer.optLong("id"),""));
        assertEquals(0,store.searchMemories("task-result:"+request.optString("external_id"),10).length());
    }

    @Test public void questionRequestCorrectionAndUrgentMessagesCannotBeSilencedByAnEmoji() {
        for(String text:new String[]{"어떤 운동이 좋아?","운동 루틴 추천해줘","기억을 정정해","지금 너무 아파", "Can you help me"}) {
            JSONObject request=runtime.receive(text,new JSONArray());finish(request,reaction(request,target(request),"👍"));
            assertNull(EmojiReactionReply.fromStoredMessage(pageRow(answer(request).optLong("id"))));
            assertTrue(answer(request).optString("text").contains("문장으로 확인해야 해"));
        }
    }

    @Test public void wrongTargetAndInvalidEmojiKeepNormalTextFallbackWithoutNewCall() throws Exception {
        JSONObject request=runtime.receive("고마워",new JSONArray()),target=target(request),response=reaction(request,target,"👍");
        response.optJSONObject("reaction").put("targetMessageId",target.optLong("id")+1);response.put("message","천만에");
        finish(request,response);assertEquals("천만에",answer(request).optString("text"));assertEquals(1,prompts.size());
        JSONObject next=runtime.receive("나 운동갔다올게 이제",new JSONArray());
        finish(next,reaction(next,target(next),"💪💪"));assertNull(EmojiReactionReply.fromStoredMessage(pageRow(answer(next).optLong("id"))));
        assertEquals(2,prompts.size());
    }

    @Test public void staleReactionAfterNewOwnerInputIsHeldLikeEveryOtherResponse() {
        JSONObject first=runtime.receive("나 운동갔다올게 이제",new JSONArray()),original=target(first);
        runtime.onWebEvent(object("type","accepted","requestId",first.optString("external_id")));drain();
        runtime.receive("아니 운동 취소했어",new JSONArray());finish(first,reaction(first,original,"💪"));
        assertNull(answer(first));
        assertTrue(store.events(30).toString().contains("work_output_held"));
    }

    @Test public void plainAssistantTextCannotImpersonateNativeReactionReceipt() {
        JSONObject user=store.addMessage("user","고마워",null);
        JSONObject descriptor=EmojiReactionReply.proposal(object("status","completed","message","","reaction",object("emoji","❤️","targetMessageId",user.optLong("id"))),user);
        JSONObject fake=store.addMessage("assistant",EmojiReactionReply.originalText(descriptor),null);
        assertNull(EmojiReactionReply.fromStoredMessage(pageRow(fake.optLong("id"))));
        store.putSetting("answerSources:"+fake.optLong("id"),object("version",1,"sources",new JSONArray(),"reaction",descriptor).toString());
        assertNull(EmojiReactionReply.fromStoredMessage(pageRow(fake.optLong("id"))));
    }

    @Test public void modelActionsStillExecuteAndAreNeverReplacedByReaction() {
        JSONObject request=runtime.receive("고마워",new JSONArray()),response=reaction(request,target(request),"👍");
        put(response,"actions",new JSONArray().put(object("id","read","name","app.settings.get","arguments",new JSONObject())));
        finish(request,response);assertNull(EmojiReactionReply.fromStoredMessage(pageRow(answer(request).optLong("id"))));
        assertFalse(store.getSetting("actionReceipt:"+request.optString("external_id")+":read","").isEmpty());
        assertEquals(2,prompts.size());
        assertTrue(runtime.state().optBoolean("activeOwnerReply"));
    }

    @Test public void lightAutonomyReactionRequiresMediumReviewInsteadOfSilentNoAction() {
        JSONObject value=object("status","idle","message","","routing",object("decision","NO_ACTION","reason","nothing due"),"reaction",object("emoji","👍","targetMessageId",1));
        assertEquals("medium",ModelRoutingPolicy.promotionEffort(value));
    }

    private JSONObject target(JSONObject request){return store.getMessage(Long.parseLong(store.getSetting("requestSource:"+request.optString("external_id"),"0")));}
    private JSONObject answer(JSONObject request){JSONArray rows=store.listMessages(100);for(int i=rows.length()-1;i>=0;i--){JSONObject row=rows.optJSONObject(i);if("assistant".equals(row.optString("role"))&&request.optString("external_id").equals(row.optString("request_id")))return row;}return null;}
    private JSONObject pageRow(long id){JSONArray rows=store.boundedChatMessagePage(0,40,8000).optJSONArray("messages");for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i).optLong("id")==id)return rows.optJSONObject(i);return null;}
    private static JSONObject reaction(JSONObject request,JSONObject target,String emoji){return object("protocol","hoyoung.v1","requestId",request.optString("external_id"),"status","completed","message","","actions",new JSONArray(),"reaction",object("emoji",emoji,"targetMessageId",target.optLong("id")));}
    private void ready(){runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",false,"quota",false)));drain();}
    private void finish(JSONObject request,JSONObject envelope){runtime.onWebEvent(object("type","completed","requestId",request.optString("external_id"),"text",envelope.toString(),"rawEnvelopeEligible",true));drain();}
    private void clearPolicy() throws Exception {for(String file:new String[]{"privacy-ledger.json","privacy-ledger.anchor.json",".privacy-ledger.lock"})java.nio.file.Files.deleteIfExists(new java.io.File(context.getFilesDir(),file).toPath());}
    private static void drain(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static JSONObject object(Object...pairs){JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)put(value,(String)pairs[i],pairs[i+1]);return value;}
    private static void put(JSONObject value,String key,Object content){try{value.put(key,content);}catch(Exception error){throw new AssertionError(error);}}
}
