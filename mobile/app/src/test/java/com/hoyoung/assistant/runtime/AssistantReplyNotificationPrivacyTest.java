package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import com.hoyoung.assistant.store.HoyoungStore;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowNotificationManager;
import java.nio.file.Files;
import java.util.ArrayDeque;
import static org.junit.Assert.*;

/** Exact source identity and durable owner exclusions at the local Android boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AssistantReplyNotificationPrivacyTest {
    private Context context;private HoyoungStore store;private NotificationManager manager;private AssistantReplyNotifications replies;
    @Before public void before()throws Exception {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clearPolicy();
        store=new HoyoungStore(context);manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancelAll();Shadows.shadowOf(manager).setNotificationsEnabled(true);
        replies=new AssistantReplyNotifications(context,store,Runnable::run);
    }
    @After public void after()throws Exception {manager.cancelAll();store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clearPolicy();}

    @Test public void excludedQueuedReplyCannotPublishItsRetainedRawPayload()throws Exception {
        JSONObject source=assistant("private retained reply");ArrayDeque<Runnable> queue=new ArrayDeque<>();
        AssistantReplyNotifications delayed=new AssistantReplyNotifications(context,store,queue::add);drain(queue);
        assertTrue(delayed.publish(source,"completed").getBoolean("queued"));exclude("message",source);drain(queue);
        assertNull(active(source));assertEquals("notification_source_excluded",delayed.state().getString("lastCode"));
        assertEquals("private retained reply",store.readOriginalText(source.getLong("id"),0,100).getString("text"));
        assertFalse(delayed.state().toString().contains("private retained reply"));assertFalse(delayed.state().getBoolean("readConfirmed"));
    }
    @Test public void excludedOwnerMasksTransitiveKnownRepliesWithoutMaskingIndependentReply()throws Exception {
        JSONObject owner=store.addMessage("user","private owner value",null),first=assistant("first value copy"),second=assistant("second value copy"),independent=assistant("independent reply");
        bind(first,"message",owner);bind(second,"message",first);exclude("message",owner);
        replies.publish(second,"proactive");assertNull(active(second));assertEquals("notification_source_excluded",replies.state().getString("lastCode"));
        replies.publish(independent,"completed");assertNotNull(active(independent));
    }
    @Test public void excludedDeviceEvidenceCannotReappearAsAssistantPreview()throws Exception {
        JSONObject observation=store.appendDeviceEvent("location","fixture.location",new JSONObject().put("latitude",37.11),1_700_000_000_000L,null);
        JSONObject response=assistant("private location copied into reply");bind(response,"device_event",observation);exclude("device_event",observation);
        replies.publish(response,"completed");assertNull(active(response));assertEquals("notification_source_excluded",replies.state().getString("lastCode"));
    }
    @Test public void excludedRequestParentMasksReplyEvenWithoutAnswerSources()throws Exception {
        JSONObject owner=store.addMessage("user","private request", "root-owner");store.putSetting("requestSource:root-owner",String.valueOf(owner.getLong("id")));
        JSONObject response=store.addMessage("assistant","request-derived private copy","root-owner");exclude("message",owner);
        replies.publish(response,"completed");assertNull(active(response));assertEquals("notification_source_excluded",replies.state().getString("lastCode"));
    }
    @Test public void oldBackupAndBarrierCannotResurrectExcludedRetainedReply()throws Exception {
        JSONObject source=assistant("old backup private reply"),snapshot=store.exportSnapshot();exclude("message",source);
        replies.beginRestore();try{store.importSnapshot(snapshot);}finally{replies.endRestore();}
        JSONObject restored=store.getMessage(source.getLong("id"));assertEquals(source.getString("content_hash"),restored.getString("content_hash"));
        replies.publish(restored,"completed");assertNull(active(restored));assertEquals("notification_source_excluded",replies.state().getString("lastCode"));
    }
    @Test public void restoreInvalidatesQueuedPrivatePreviewAndNewQueueStillChecksExclusion()throws Exception {
        JSONObject source=assistant("queued old backup reply"),snapshot=store.exportSnapshot();ArrayDeque<Runnable> queue=new ArrayDeque<>();
        AssistantReplyNotifications delayed=new AssistantReplyNotifications(context,store,queue::add);drain(queue);delayed.publish(source,"completed");
        replies.beginRestore();try{exclude("message",source);store.importSnapshot(snapshot);}finally{replies.endRestore();}drain(queue);
        assertNull(active(source));assertEquals("notification_restore_epoch_changed",delayed.state().getString("lastCode"));
        delayed.publish(store.getMessage(source.getLong("id")),"completed");drain(queue);
        assertNull(active(source));assertEquals("notification_source_excluded",delayed.state().getString("lastCode"));
    }
    @Test public void sameIdNewHashIsNotMisclassifiedAsPreviouslyExcludedIdentity()throws Exception {
        JSONObject old=assistant("old excluded identity");exclude("message",old);long id=old.getLong("id");
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);
        JSONObject replacement=assistant("new independent identity");assertEquals(id,replacement.getLong("id"));assertNotEquals(old.getString("content_hash"),replacement.getString("content_hash"));
        replies=new AssistantReplyNotifications(context,store,Runnable::run);replies.publish(replacement,"completed");assertNotNull(active(replacement));
        replies.publish(old,"completed");assertNotNull(active(replacement));assertEquals("source_unavailable_or_changed",replies.state().getString("lastCode"));
    }
    @Test public void excludedPostedReplyIsCancelledBeforeDeduplicationCanClaimAlreadyHandled()throws Exception {
        JSONObject source=assistant("posted then excluded");replies.publish(source,"completed");assertNotNull(active(source));foreground();
        exclude("message",source);replies.publish(source,"completed");assertNull(active(source));assertNotNull(Shadows.shadowOf(manager).getNotification(2410));
        assertEquals("notification_source_excluded",replies.state().getString("lastCode"));assertFalse(replies.state().getBoolean("notificationPosted"));
        assertEquals("posted",record(source).getString("status"));assertFalse(record(source).getBoolean("readConfirmedByUi"));
    }
    @Test public void publisherRecreationCancelsExcludedPostedDescendantWithoutReposting()throws Exception {
        JSONObject owner=store.addMessage("user","owner seed",null),response=assistant("posted seed copy");bind(response,"message",owner);
        replies.publish(response,"completed");assertNotNull(active(response));exclude("message",owner);
        replies=new AssistantReplyNotifications(context,store,Runnable::run);assertNull(active(response));assertEquals("posted",record(response).getString("status"));
        assertEquals("notification_privacy_cancelled",replies.state().getString("lastCode"));assertFalse(replies.state().getBoolean("notificationPosted"));
        replies.publish(response,"completed");assertNull(active(response));assertEquals("notification_source_excluded",replies.state().getString("lastCode"));
    }
    @Test public void unreadableEstablishedPrivacyCancelsPostedPreviewsAndBlocksNewOnes()throws Exception {
        JSONObject posted=assistant("private posted before policy failure"),newReply=assistant("private future preview");replies.publish(posted,"completed");foreground();
        exclude("message",store.addMessage("user","unrelated owner exclusion",null));Files.delete(new java.io.File(context.getFilesDir(),"privacy-ledger.json").toPath());
        replies=new AssistantReplyNotifications(context,store,Runnable::run);assertNull(active(posted));assertNotNull(Shadows.shadowOf(manager).getNotification(2410));
        replies.publish(newReply,"completed");assertNull(active(newReply));assertEquals("notification_privacy_unavailable",replies.state().getString("lastCode"));
        assertFalse(replies.state().toString().contains("private future preview"));
    }
    @Test public void removedCanonicalReplyCannotRemainActiveAfterWorkerRestart()throws Exception {
        JSONObject source=assistant("removed payload");replies.publish(source,"completed");assertNotNull(active(source));
        store.getWritableDatabase().delete("messages","id=?",new String[]{String.valueOf(source.getLong("id"))});
        replies=new AssistantReplyNotifications(context,store,Runnable::run);assertNull(active(source));
        replies.publish(source,"completed");assertEquals("source_unavailable_or_changed",replies.state().getString("lastCode"));
    }
    @Test public void retainedClaimedHashCannotAuthenticateTamperedSqlTextOrUiDisplay()throws Exception {
        JSONObject source=assistant("untampered text");android.content.ContentValues value=new android.content.ContentValues();value.put("text","tampered private preview");
        store.getWritableDatabase().update("messages",value,"id=?",new String[]{String.valueOf(source.getLong("id"))});
        replies.publish(source,"completed");assertNull(active(source));assertEquals("source_unavailable_or_changed",replies.state().getString("lastCode"));
        replies.markSeen(source.getLong("id"),source.getString("content_hash"));assertEquals("seen_source_changed",replies.state().getString("lastCode"));
        assertNull(store.getSetting("replyNotification:"+source.getLong("id"),null));
    }
    @Test public void explicitOwnerUiDisplayRetainsTruthfulReceiptWithoutReexposingExcludedPayload()throws Exception {
        JSONObject source=assistant("owner-visible retained original");replies.publish(source,"completed");replies.beginRestore();try{exclude("message",source);}finally{replies.endRestore();}
        replies.markSeen(source.getLong("id"),source.getString("content_hash"));assertEquals("seen",record(source).getString("status"));
        assertTrue(record(source).getBoolean("readConfirmedByUi"));assertFalse(replies.state().getBoolean("readConfirmed"));
        JSONObject receipt=store.events(1).getJSONObject(0);assertEquals("assistant_reply_seen_in_conversation",receipt.getString("type"));
        assertFalse(receipt.toString().contains("owner-visible retained original"));
        replies.publish(source,"completed");assertNull(active(source));assertEquals("notification_source_excluded",replies.state().getString("lastCode"));
    }
    @Test public void malformedKnownLineageCannotCertifyReplyPrivacy()throws Exception {
        JSONObject source=assistant("reply with unverifiable known provenance");store.putSetting("answerSources:"+source.getLong("id"),new JSONObject().put("version",1).put("sources",new JSONArray().put(new JSONObject().put("type","message").put("id",5).put("hash",""))).toString());
        replies.publish(source,"completed");assertNull(active(source));assertEquals("notification_source_excluded",replies.state().getString("lastCode"));
    }
    @Test @Config(shadows=PrivacyChangingNotificationManager.class) public void independentLedgerMutationAtAndroidBoundaryCannotLeaveStalePrivatePreviewPosted()throws Exception {
        JSONObject source=assistant("private preview changed while notifying");
        PrivacyChangingNotificationManager.afterNotify=()->{try{exclude("message",source);}catch(Exception failure){throw new IllegalStateException(failure);}};
        try{replies.publish(source,"completed");}finally{PrivacyChangingNotificationManager.afterNotify=null;}
        assertNull(active(source));assertEquals("notification_source_changed_after_notify",replies.state().getString("lastCode"));
        assertEquals("not_allowed",record(source).getString("status"));assertFalse(record(source).getBoolean("readConfirmedByUi"));
        replies.publish(source,"completed");assertNull(active(source));assertEquals("notification_source_excluded",replies.state().getString("lastCode"));
    }
    @Test @Config(shadows=PrivacyChangingNotificationManager.class) public void sourceRemovalAtAndroidBoundaryCannotLeaveRemovedReplyPreviewPosted()throws Exception {
        JSONObject source=assistant("private removed while notifying");
        PrivacyChangingNotificationManager.afterNotify=()->store.getWritableDatabase().delete("messages","id=?",new String[]{String.valueOf(source.optLong("id"))});
        try{replies.publish(source,"completed");}finally{PrivacyChangingNotificationManager.afterNotify=null;}
        assertNull(active(source));assertEquals("notification_source_changed_after_notify",replies.state().getString("lastCode"));
        assertEquals("not_allowed",record(source).getString("status"));
    }
    @Implements(NotificationManager.class)
    public static final class PrivacyChangingNotificationManager extends ShadowNotificationManager {
        static Runnable afterNotify;
        @Implementation @Override protected void notify(String tag,int id,Notification notification) {
            super.notify(tag,id,notification);Runnable callback=afterNotify;afterNotify=null;if(callback!=null)callback.run();
        }
    }
    private JSONObject assistant(String text){return store.addMessage("assistant",text,null);}
    private void exclude(String type,JSONObject source)throws Exception {store.getPrivacyLedger().excludeSource(type,source.getLong("id"),source.getString("content_hash"),"retrieval_exclude","explicit owner control fixture");}
    private void bind(JSONObject reply,String type,JSONObject source)throws Exception {store.putSetting("answerSources:"+reply.getLong("id"),new JSONObject().put("version",1).put("sources",new JSONArray().put(new JSONObject().put("type",type).put("id",source.getLong("id")).put("hash",source.getString("content_hash")))).toString());}
    private JSONObject record(JSONObject source)throws Exception{return new JSONObject(store.getSetting("replyNotification:"+source.getLong("id"),"{}"));}
    private StatusBarNotification active(JSONObject source)throws Exception {for(StatusBarNotification item:manager.getActiveNotifications())if(("hoyoung_reply:"+source.getLong("id")).equals(item.getTag())&&item.getId()==AssistantReplyNotifications.NOTIFICATION_ID&&source.getString("content_hash").equals(item.getNotification().extras.getString(AssistantReplyNotifications.EXTRA_SOURCE_HASH)))return item;return null;}
    private void foreground(){manager.createNotificationChannel(new NotificationChannel("hoyoung_autonomy","status",NotificationManager.IMPORTANCE_LOW));manager.notify(2410,new Notification.Builder(context,"hoyoung_autonomy").setSmallIcon(android.R.drawable.ic_dialog_info).setContentText("foreground worker").build());}
    private static void drain(ArrayDeque<Runnable> queue){while(!queue.isEmpty())queue.remove().run();}
    private void clearPolicy()throws Exception {for(String file:new String[]{"privacy-ledger.json","privacy-ledger.anchor.json",".privacy-ledger.lock"})Files.deleteIfExists(new java.io.File(context.getFilesDir(),file).toPath());}
}
