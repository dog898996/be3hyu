package com.hoyoung.assistant.runtime;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class AssistantReplyNotificationsTest {
    private Context context; private HoyoungStore store; private NotificationManager manager; private AssistantReplyNotifications replies;
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);manager.cancelAll();Shadows.shadowOf(manager).setNotificationsEnabled(true);Shadows.shadowOf((Application)context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);replies=new AssistantReplyNotifications(context,store,Runnable::run);}
    @After public void after(){manager.cancelAll();store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}

    @Test public void queueAcceptanceDoesNotClaimDeliveryAndVisibilityIsRecheckedOnWorker()throws Exception{
        ArrayDeque<Runnable> queue=new ArrayDeque<>();Executor delayed=queue::add;AssistantReplyNotifications worker=new AssistantReplyNotifications(context,store,delayed);JSONObject source=assistant("waiting reply");
        JSONObject accepted=worker.publish(source,"completed");assertTrue(accepted.getBoolean("queued"));assertFalse(accepted.getBoolean("notificationPosted"));assertFalse(accepted.getBoolean("readConfirmed"));assertEquals(0,manager.getActiveNotifications().length);
        worker.setConversationVisible(true);while(!queue.isEmpty())queue.remove().run();assertEquals(0,manager.getActiveNotifications().length);assertEquals("conversation_visible",worker.state().getString("lastCode"));
    }
    @Test public void actualReplyIsPrivateAndPublicVersionHasNoMessageBody()throws Exception{
        JSONObject source=assistant("private-message-42");replies.publish(source,"completed");Notification notification=active(source).getNotification();
        assertEquals(Notification.VISIBILITY_PRIVATE,notification.visibility);assertEquals("private-message-42",notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString());assertNotNull(notification.publicVersion);
        assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("private-message-42"));assertEquals(Notification.VISIBILITY_PUBLIC,notification.publicVersion.visibility);
        assertEquals("assistant_reply",notification.extras.getString(AssistantReplyNotifications.EXTRA_SOURCE_KIND));assertEquals(source.getLong("id"),notification.extras.getLong(AssistantReplyNotifications.EXTRA_SOURCE_ID));assertEquals(source.getString("content_hash"),notification.extras.getString(AssistantReplyNotifications.EXTRA_SOURCE_HASH));
        assertTrue(replies.state().getBoolean("notificationPosted"));assertFalse(replies.state().getBoolean("readConfirmed"));assertTrue(replies.state().getBoolean("postingDoesNotMeanRead"));
    }
    @Test public void contentIntentOpensSpecificConversationSourceWithoutMutableRemoteInput()throws Exception{
        JSONObject source=assistant("open this reply");replies.publish(source,"proactive");Notification notification=active(source).getNotification();Intent open=Shadows.shadowOf(notification.contentIntent).getSavedIntent();
        assertEquals(AssistantReplyNotifications.ACTION_OPEN,open.getAction());assertEquals("hoyoung://assistant/message/"+source.getLong("id"),open.getDataString());assertTrue(open.getBooleanExtra(AssistantReplyNotifications.EXTRA_OPEN_CONVERSATION,false));assertEquals(source.getLong("id"),open.getLongExtra(AssistantReplyNotifications.EXTRA_SOURCE_ID,0));
        assertEquals(context.getPackageName()+".MainActivity",open.getComponent().getClassName());assertTrue((open.getFlags()&Intent.FLAG_ACTIVITY_SINGLE_TOP)!=0);assertNull(notification.actions);
    }
    @Test public void chatVisibilitySuppressesSourcePermanentlyRatherThanAlertingAfterUserLeaves()throws Exception{
        JSONObject source=assistant("already visible");replies.setConversationVisible(true);replies.publish(source,"completed");assertEquals(0,manager.getActiveNotifications().length);assertEquals("suppressed_visible",record(source).getString("status"));assertFalse(record(source).getBoolean("readConfirmedByUi"));
        replies.setConversationVisible(false);replies.publish(source,"completed");assertEquals(0,manager.getActiveNotifications().length);assertEquals("already_handled",replies.state().getString("lastCode"));
    }
    @Test public void idleProgressAndMemoryReviewDoNotCreateNotificationSpam()throws Exception{
        JSONObject source=assistant("internal work");for(String stage:new String[]{"working","initiated","idle","memory-review","queued",""})assertFalse(replies.publish(source,stage).getBoolean("queued"));
        assertEquals(0,manager.getActiveNotifications().length);assertNull(store.getSetting("replyNotification:"+source.getLong("id"),null));
    }
    @Test public void proactiveQuotaAndErrorsAreUserMessagesWithTheirOwnChannel()throws Exception{
        JSONObject proactive=assistant("필요한 선톡");replies.publish(proactive,"proactive");JSONObject quota=store.addMessage("system","이번 한도는 소진됐어.",null);replies.publish(quota,"quota_exhausted");JSONObject error=store.addMessage("system","연결 오류를 확인해줘.",null);replies.publish(error,"error");
        assertEquals(3,manager.getActiveNotifications().length);assertEquals("호영 · 한도 안내",active(quota).getNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString());assertEquals("호영 · 확인할 내용",active(error).getNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals(AssistantReplyNotifications.CHANNEL,active(proactive).getNotification().getChannelId());assertNotEquals("hoyoung_autonomy",active(proactive).getNotification().getChannelId());
    }
    @Test @Config(sdk=35) public void actualOsPermissionDenialBlocksPostingAndExplicitLaterRetryRespectsGrant()throws Exception{
        JSONObject source=assistant("permission-dependent");Shadows.shadowOf((Application)context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);replies.publish(source,"completed");assertEquals(0,manager.getActiveNotifications().length);assertEquals("notification_permission_denied",replies.state().getString("lastCode"));assertFalse(replies.state().getBoolean("permissionGranted"));
        Shadows.shadowOf((Application)context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);replies.publish(source,"completed");assertNotNull(active(source));
    }
    @Test public void appNotificationSettingIsRespected()throws Exception{
        JSONObject source=assistant("blocked at app level");Shadows.shadowOf(manager).setNotificationsEnabled(false);replies.publish(source,"completed");assertEquals(0,manager.getActiveNotifications().length);assertEquals("app_notifications_disabled",replies.state().getString("lastCode"));assertEquals("not_allowed",record(source).getString("status"));
    }
    @Test public void editableReplySettingCanDisablePostingWithoutChangingOsChannel()throws Exception{
        JSONObject source=assistant("setting blocked");store.putSetting("replyNotificationsEnabled","false");replies.publish(source,"completed");assertNull(active(source));assertEquals("reply_notifications_disabled",replies.state().getString("lastCode"));assertEquals(NotificationManager.IMPORTANCE_DEFAULT,manager.getNotificationChannel(AssistantReplyNotifications.CHANNEL).getImportance());
        store.putSetting("replyNotificationsEnabled","true");replies.publish(source,"awaiting_result");assertNotNull(active(source));assertEquals("awaiting_result",record(source).getString("stage"));
    }
    @Test public void twoPublisherInstancesSerializeSameSourceAcrossConcurrentWaiters()throws Exception{
        store.getWritableDatabase().execSQL("CREATE TABLE notification_test_writes(value TEXT)");store.getWritableDatabase().execSQL("CREATE TRIGGER count_reply_writes AFTER INSERT ON settings WHEN NEW.setting_key >= 'replyNotification:' AND NEW.setting_key < 'replyNotification;' BEGIN INSERT INTO notification_test_writes(value) VALUES(NEW.value); END");
        JSONObject source=assistant("one response despite concurrent publishers");AssistantReplyNotifications second=new AssistantReplyNotifications(context,store,Runnable::run);java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(4);java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);java.util.List<java.util.concurrent.Future<?>> futures=new java.util.ArrayList<>();
        try{for(int i=0;i<12;i++){AssistantReplyNotifications publisher=i%2==0?replies:second;futures.add(workers.submit(()->{try{start.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}publisher.publish(source,"completed");}));}start.countDown();for(java.util.concurrent.Future<?> future:futures)future.get(10,java.util.concurrent.TimeUnit.SECONDS);}finally{workers.shutdownNow();}
        assertEquals(1,manager.getActiveNotifications().length);assertEquals("posted",record(source).getString("status"));try(android.database.Cursor count=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM notification_test_writes",null)){count.moveToFirst();assertEquals(2,count.getLong(0));}
    }
    @Test public void ownerDisabledChannelIsNeverReenabledByPublisherRecreation()throws Exception{
        manager.deleteNotificationChannel(AssistantReplyNotifications.CHANNEL);NotificationChannel blocked=new NotificationChannel(AssistantReplyNotifications.CHANNEL,"owner choice",NotificationManager.IMPORTANCE_NONE);manager.createNotificationChannel(blocked);
        replies=new AssistantReplyNotifications(context,store,Runnable::run);JSONObject source=assistant("blocked channel");replies.publish(source,"completed");assertEquals(0,manager.getActiveNotifications().length);assertTrue(replies.state().getBoolean("channelBlocked"));assertEquals(NotificationManager.IMPORTANCE_NONE,manager.getNotificationChannel(AssistantReplyNotifications.CHANNEL).getImportance());
    }
    @Test public void persistentSourceDeduplicationSurvivesProcessRecreation()throws Exception{
        JSONObject source=assistant("once");replies.publish(source,"completed");String prior=record(source).toString();Notification original=active(source).getNotification();store.close();store=new HoyoungStore(context);replies=new AssistantReplyNotifications(context,store,Runnable::run);replies.publish(source,"completed");
        assertEquals(1,manager.getActiveNotifications().length);assertSame(original,active(source).getNotification());assertEquals(prior,record(source).toString());assertEquals("already_handled",replies.state().getString("lastCode"));
    }
    @Test public void uiSeenCancelsOnlyThatSourceAndCannotBeReplayed()throws Exception{
        JSONObject first=assistant("first"),second=assistant("second");replies.publish(first,"completed");replies.publish(second,"completed");foregroundNotification();replies.markSeen(first.getLong("id"),first.getString("content_hash"));
        assertNull(active(first));assertNotNull(active(second));assertNotNull(Shadows.shadowOf(manager).getNotification(2410));assertEquals("seen",record(first).getString("status"));assertTrue(record(first).getBoolean("readConfirmedByUi"));assertEquals("assistant_reply_seen_in_conversation",store.events(1).getJSONObject(0).getString("type"));
        replies.publish(first,"completed");assertNull(active(first));assertFalse(replies.state().getBoolean("readConfirmed"));
    }
    @Test public void forgedOrChangedSourceAndOwnerMessagesAreNeverPublished()throws Exception{
        JSONObject owner=store.addMessage("user","owner says something",null);replies.publish(owner,"completed");assertEquals(0,manager.getActiveNotifications().length);assertEquals("source_unavailable_or_changed",replies.state().getString("lastCode"));
        JSONObject original=assistant("trusted original"),forged=new JSONObject(original.toString()).put("content_hash","f".repeat(64));replies.publish(forged,"completed");assertNull(active(original));assertNull(store.getSetting("replyNotification:"+original.getLong("id"),null));
        assertThrows(IllegalArgumentException.class,()->replies.publish(new JSONObject(),"completed"));JSONObject fractional=new JSONObject(original.toString()).put("id",1.5);assertThrows(IllegalArgumentException.class,()->replies.publish(fractional,"completed"));
    }
    @Test public void interruptedIntentWithActualActiveNotificationRecoversWithoutCallingNotifyAgain()throws Exception{
        JSONObject source=assistant("recover active");replies.publish(source,"completed");Notification original=active(source).getNotification();store.putSetting("replyNotification:"+source.getLong("id"),record(source).put("status","dispatching").toString());
        replies=new AssistantReplyNotifications(context,store,Runnable::run);replies.publish(source,"completed");assertSame(original,active(source).getNotification());assertEquals("posted",record(source).getString("status"));assertEquals("recovered_existing_notification",replies.state().getString("lastCode"));
    }
    @Test public void interruptedIntentWithNoActiveNotificationIsUnknownAndNeverAutomaticallyReplayed()throws Exception{
        JSONObject source=assistant("possibly dismissed");store.putSetting("replyNotification:"+source.getLong("id"),intentRecord(source,"dispatching").toString());replies.publish(source,"completed");assertEquals(0,manager.getActiveNotifications().length);assertEquals("outcome_unknown",record(source).getString("status"));assertEquals("notification_outcome_unknown_no_replay",replies.state().getString("lastCode"));
        replies=new AssistantReplyNotifications(context,store,Runnable::run);replies.publish(source,"completed");assertEquals(0,manager.getActiveNotifications().length);assertEquals("already_handled",replies.state().getString("lastCode"));
    }
    @Test public void corruptEmptyTrailingAndDeepStoredStateFailConservativeWithoutErasingEvidence()throws Exception{
        for(String corrupt:new String[]{"","not-json","{}","{} trailing","{\"status\":\"posted\",\"payload\":"+"[".repeat(30)+"0"+"]".repeat(30)+"}"}){JSONObject source=assistant("corrupt state source");String key="replyNotification:"+source.getLong("id");store.putSetting(key,corrupt);replies.publish(source,"completed");assertNull(active(source));assertEquals(corrupt,store.getSetting(key,null));assertEquals("notification_state_corrupt_no_replay",replies.state().getString("lastCode"));}
    }
    @Test public void changedSavedIdentityAtSameLocalIdCanReplaceOlderSnapshotNotification()throws Exception{
        JSONObject source=assistant("current snapshot identity");store.putSetting("replyNotification:"+source.getLong("id"),intentRecord(source,"posted").put("sourceHash","f".repeat(64)).toString());replies.publish(source,"completed");assertNotNull(active(source));assertEquals(source.getString("content_hash"),record(source).getString("sourceHash"));
    }
    @Test public void activeNotificationsAreBoundedWithoutCancellingForegroundStatusOrDeletingMessages()throws Exception{
        foregroundNotification();JSONObject oldest=null,latest=null;for(int i=0;i<45;i++){JSONObject source=assistant("bounded source "+i);if(oldest==null)oldest=source;latest=source;replies.publish(source,"proactive");}
        assertEquals(AssistantReplyNotifications.MAX_ACTIVE+1,manager.getActiveNotifications().length);assertNull(active(oldest));assertNotNull(active(latest));assertNotNull(Shadows.shadowOf(manager).getNotification(2410));assertNotNull(store.getMessage(oldest.getLong("id")));assertFalse(record(oldest).getBoolean("readConfirmedByUi"));
    }
    @Test public void previewIsBoundedControlSafeAndDoesNotSplitSupplementaryCharacters()throws Exception{
        String content="x".repeat(399)+"😀"+String.valueOf((char)0)+" end"+"y".repeat(2000);JSONObject source=assistant(content);replies.publish(source,"completed");Notification notification=active(source).getNotification();String compact=notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),expanded=notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString();
        assertEquals(399,compact.length());assertTrue(expanded.length()<=1200);assertFalse(expanded.contains(String.valueOf((char)0)));assertEquals(content,store.getMessage(source.getLong("id")).getString("text"));
    }
    @Test public void failurePersistingDispatchIntentPreventsExternalNotificationEffect()throws Exception{
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_reply_intent BEFORE INSERT ON settings WHEN NEW.setting_key >= 'replyNotification:' AND NEW.setting_key < 'replyNotification;' BEGIN SELECT RAISE(ABORT,'test intent disk failure'); END");
        JSONObject source=assistant("do not emit without intent");replies.publish(source,"completed");assertEquals(0,manager.getActiveNotifications().length);assertEquals("notification_processing_failed",replies.state().getString("lastCode"));assertNull(store.getSetting("replyNotification:"+source.getLong("id"),null));
    }
    @Test public void idOnlySeenRequestCannotPromoteAnUnknownSnapshotIdentity()throws Exception{
        JSONObject source=assistant("identity required");replies.publish(source,"completed");replies.markSeen(source.getLong("id"));assertNotNull(active(source));assertEquals("posted",record(source).getString("status"));assertEquals("seen_source_identity_required",replies.state().getString("lastCode"));
    }
    @Test public void displayedOldHashDoesNotCancelOrMarkSameIdNewSnapshotMessageSeen()throws Exception{
        JSONObject old=assistant("old displayed identity");replies.publish(old,"completed");JSONObject replacement=replaceIdentity(old,"new snapshot identity");replies.publish(replacement,"completed");replies.markSeen(old.getLong("id"),old.getString("content_hash"));
        assertNotNull(active(replacement));assertEquals("posted",record(replacement).getString("status"));assertEquals("seen_source_changed",replies.state().getString("lastCode"));assertFalse(record(replacement).getBoolean("readConfirmedByUi"));
    }
    @Test public void queuedSeenCallbackBeforeRestoreCannotMarkReusedSourceIdReadAfterRestore()throws Exception{
        JSONObject old=assistant("queued old visible message");replies.publish(old,"completed");ArrayDeque<Runnable> queue=new ArrayDeque<>();AssistantReplyNotifications delayed=new AssistantReplyNotifications(context,store,queue::add);while(!queue.isEmpty())queue.remove().run();delayed.markSeen(old.getLong("id"),old.getString("content_hash"));
        JSONObject replacement;replies.beginRestore();try{replacement=replaceIdentity(old,"restored never displayed message");}finally{replies.endRestore();}replies.publish(replacement,"completed");while(!queue.isEmpty())queue.remove().run();
        assertNotNull(active(replacement));assertEquals("posted",record(replacement).getString("status"));assertFalse(record(replacement).getBoolean("readConfirmedByUi"));assertEquals("seen_restore_epoch_changed",delayed.state().getString("lastCode"));
    }
    @Test public void queuedPublisherBeforeRestoreCannotPostAfterRestoreCancellation()throws Exception{
        ArrayDeque<Runnable> queue=new ArrayDeque<>();AssistantReplyNotifications delayed=new AssistantReplyNotifications(context,store,queue::add);while(!queue.isEmpty())queue.remove().run();JSONObject source=assistant("old pending source");delayed.publish(source,"completed");foregroundNotification();
        replies.beginRestore();try{assertTrue(replies.state().getBoolean("restoreInProgress"));}finally{replies.endRestore();}while(!queue.isEmpty())queue.remove().run();assertNull(active(source));assertEquals("notification_restore_epoch_changed",delayed.state().getString("lastCode"));assertNotNull(Shadows.shadowOf(manager).getNotification(2410));
        delayed.publish(source,"completed");while(!queue.isEmpty())queue.remove().run();assertNotNull(active(source));
    }
    @Test public void restoreSuppressesPublishAcrossInstancesUntilAllNestedBarriersExit()throws Exception{
        AssistantReplyNotifications other=new AssistantReplyNotifications(context,store,Runnable::run);JSONObject source=assistant("after restore only");replies.beginRestore();other.beginRestore();try{other.publish(source,"completed");assertNull(active(source));replies.endRestore();other.publish(source,"completed");assertNull(active(source));assertTrue(other.state().getBoolean("restoreInProgress"));}finally{other.endRestore();}
        other.publish(source,"completed");assertNotNull(active(source));assertFalse(other.state().getBoolean("restoreInProgress"));assertThrows(IllegalStateException.class,other::endRestore);
    }
    @Test public void repeatedUiDisplayIsIdempotentAndDoesNotAppendAnotherSeenEvent()throws Exception{
        JSONObject source=assistant("one display event");replies.publish(source,"completed");replies.markSeen(source.getLong("id"),source.getString("content_hash"));String first=record(source).toString();long seenEvents=eventCount("assistant_reply_seen_in_conversation");
        replies.markSeen(source.getLong("id"),source.getString("content_hash"));assertEquals(first,record(source).toString());assertEquals(seenEvents,eventCount("assistant_reply_seen_in_conversation"));assertNull(active(source));
    }
    @Test public void closeStopsNewWorkButDrainsAlreadyAcceptedExternalExecutorTasks()throws Exception{
        ArrayDeque<Runnable> queue=new ArrayDeque<>();AssistantReplyNotifications delayed=new AssistantReplyNotifications(context,store,queue::add);JSONObject source=assistant("already accepted");delayed.publish(source,"completed");delayed.close();assertFalse(delayed.publish(source,"completed").getBoolean("queued"));while(!queue.isEmpty())queue.remove().run();assertNotNull(active(source));assertTrue(delayed.state().getBoolean("workerClosed"));
    }
    private JSONObject replaceIdentity(JSONObject old,String text)throws Exception{
        JSONObject snapshot=store.exportSnapshot();org.json.JSONArray sources=snapshot.getJSONArray("messages");for(int i=0;i<sources.length();i++){JSONObject row=sources.getJSONObject(i);if(row.getLong("id")==old.getLong("id")){row.put("text",text);row.put("raw_char_count",text.length());row.put("raw_byte_count",text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);row.put("raw_content_hash",sha(text));row.remove("content_hash");row.put("raw_file_id",JSONObject.NULL);}}
        store.importSnapshot(snapshot);return store.getMessage(old.getLong("id"));
    }
    private long eventCount(String type){try(android.database.Cursor count=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events WHERE type = ?",new String[]{type})){count.moveToFirst();return count.getLong(0);}}
    private static String sha(String text)throws Exception{StringBuilder result=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();}
    private JSONObject assistant(String text){return store.addMessage("assistant",text,null);}
    private JSONObject record(JSONObject source)throws Exception{return new JSONObject(store.getSetting("replyNotification:"+source.getLong("id"),"{}"));}
    private JSONObject intentRecord(JSONObject source,String status)throws Exception{return new JSONObject().put("sourceMessageId",source.getLong("id")).put("sourceHash",source.getString("content_hash")).put("status",status).put("stage","completed");}
    private StatusBarNotification active(JSONObject source)throws Exception{for(StatusBarNotification value:manager.getActiveNotifications())if(("hoyoung_reply:"+source.getLong("id")).equals(value.getTag())&&value.getId()==AssistantReplyNotifications.NOTIFICATION_ID)return value;return null;}
    private void foregroundNotification(){NotificationChannel channel=new NotificationChannel("hoyoung_autonomy","status",NotificationManager.IMPORTANCE_LOW);manager.createNotificationChannel(channel);manager.notify(2410,new Notification.Builder(context,"hoyoung_autonomy").setSmallIcon(android.R.drawable.ic_dialog_info).setContentText("foreground worker").build());}
}
