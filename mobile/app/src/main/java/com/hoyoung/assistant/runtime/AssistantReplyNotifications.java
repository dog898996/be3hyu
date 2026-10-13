package com.hoyoung.assistant.runtime;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local Android reply notifications. Posting is not proof that a person saw or read a message. */
public final class AssistantReplyNotifications implements AutoCloseable {
    public static final String CHANNEL = "hoyoung_replies_v1";
    public static final String ACTION_OPEN = "com.hoyoung.assistant.OPEN_ASSISTANT_MESSAGE";
    public static final String EXTRA_SOURCE_ID = "com.hoyoung.assistant.assistant_message_id";
    public static final String EXTRA_SOURCE_HASH = "com.hoyoung.assistant.source_message_hash";
    public static final String EXTRA_SOURCE_KIND = "com.hoyoung.assistant.source_kind";
    public static final String EXTRA_STAGE = "com.hoyoung.assistant.reply_stage";
    public static final String EXTRA_OPEN_CONVERSATION = "com.hoyoung.assistant.open_conversation";
    public static final int NOTIFICATION_ID = 2411, MAX_ACTIVE = 32;
    private static final String TAG_PREFIX = "hoyoung_reply:", STATE_PREFIX = "replyNotification:";
    private static final Object[] SOURCE_LOCKS = new Object[128];
    static { for(int i=0;i<SOURCE_LOCKS.length;i++)SOURCE_LOCKS[i]=new Object(); }
    private final Context context;
    private final HoyoungStore store;
    private final NotificationManager manager;
    private final Executor executor;
    private final boolean ownsExecutor;
    private final String processScope;
    private volatile boolean conversationVisible;
    private volatile boolean closed;
    private volatile JSONObject lastState = object("lastCode","not_started","readConfirmed",false);

    public AssistantReplyNotifications(Context context, HoyoungStore store) {
        this(context, store, Executors.newSingleThreadExecutor(),true);
    }
    public AssistantReplyNotifications(Context context, HoyoungStore store, Executor executor) {
        this(context,store,executor,false);
    }
    private AssistantReplyNotifications(Context context,HoyoungStore store,Executor executor,boolean ownsExecutor) {
        this.context = context.getApplicationContext(); this.store = store; this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        manager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        processScope = this.context.getDatabasePath(HoyoungStore.DATABASE_NAME).getAbsolutePath();
        try { ensureChannel(); }
        catch (RuntimeException unavailable) { lastState = object("lastCode","notification_channel_unavailable","readConfirmed",false); }
        executor.execute(() -> {
            try { lastState = new JSONObject(store.getSetting("replyNotifications.lastState", lastState.toString())); }
            catch (Exception invalid) { lastState = object("lastCode","notification_state_unavailable","readConfirmed",false); }
        });
    }

    /** Returns acceptance into this local worker, not a delivery or read receipt. */
    public JSONObject publish(JSONObject savedMessage, String stage) {
        if (savedMessage == null || !(savedMessage.opt("id") instanceof Number) || savedMessage.optLong("id") <= 0
                || ((Number)savedMessage.opt("id")).doubleValue() != savedMessage.optLong("id"))
            throw new IllegalArgumentException("A saved assistant or system source message is required");
        long id = savedMessage.optLong("id"); String sourceHash = savedMessage.optString("content_hash");
        if (sourceHash.isEmpty()) throw new IllegalArgumentException("A saved source record hash is required");
        String kind = stage == null ? "" : stage;
        if (closed) return object("queued",false,"code","notification_worker_closed","notificationPosted",false,"readConfirmed",false);
        if (!eligible(kind)) return object("queued",false,"code","silent_stage","notificationPosted",false,"readConfirmed",false);
        executor.execute(() -> {
            try { synchronized (sourceLock(id)) { publishOne(id, sourceHash, kind); } }
            catch (RuntimeException failure) { report("notification_processing_failed",id,false); }
        });
        return object("queued",true,"sourceMessageId",id,"notificationPosted",false,"readConfirmed",false);
    }

    /** Main-thread friendly: changing visibility does not touch the database. */
    public void setConversationVisible(boolean visible) { conversationVisible = visible; }

    /** Stops accepting new work and drains already accepted work without blocking the caller. */
    @Override public void close(){closed=true;if(ownsExecutor)((ExecutorService)executor).shutdown();}

    /** The caller must have actually displayed the source message; opening another tab is not a read receipt. */
    public void markSeen(long sourceId) {
        if (sourceId <= 0) throw new IllegalArgumentException("Positive source message id required");
        if (closed) return;
        executor.execute(() -> {
            try { synchronized (sourceLock(sourceId)) {
                if (manager != null) manager.cancel(tag(sourceId),NOTIFICATION_ID);
                store.withTransaction(db -> {
                    JSONObject source = store.getMessage(sourceId);
                    if (source == null || !("assistant".equals(source.optString("role")) || "system".equals(source.optString("role")))) return null;
                    JSONObject record = parse(store.getSetting(STATE_PREFIX + sourceId,"{}"));
                    put(record,"sourceMessageId",sourceId); put(record,"sourceHash",source.optString("content_hash"));
                    put(record,"status","seen"); put(record,"seenAt",System.currentTimeMillis()); put(record,"readConfirmedByUi",true);
                    store.putSetting(STATE_PREFIX + sourceId,record.toString());
                    store.recordEvent("assistant_reply_seen_in_conversation",object("sourceMessageId",sourceId,"notificationCancelled",true)); return null;
                });
            }} catch (RuntimeException failure) { report("notification_seen_record_failed",sourceId,false); }
        });
    }

    public JSONObject state() {
        JSONObject state = parse(lastState.toString());
        put(state,"conversationVisible",conversationVisible); put(state,"permissionGranted",permissionGranted());
        try {
            put(state,"appNotificationsEnabled",manager != null && manager.areNotificationsEnabled());
            NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL);
            put(state,"channelBlocked",channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE);
        }catch(RuntimeException unavailable){put(state,"appNotificationsEnabled",false);put(state,"osNotificationStateUnavailable",true);}
        put(state,"channelId",CHANNEL); put(state,"postingDoesNotMeanRead",true); return state;
    }

    private void publishOne(long id,String expectedHash,String stage) {
        JSONObject source = store.getMessage(id);
        if (source == null || !expectedHash.equals(source.optString("content_hash"))
                || !("assistant".equals(source.optString("role")) || "system".equals(source.optString("role")))) { report("source_unavailable_or_changed",id,false); return; }
        String previousRaw = store.getSetting(STATE_PREFIX + id,null);
        JSONObject previous;
        try { previous = previousRaw == null ? new JSONObject() : dispatchState(previousRaw,id); }
        catch (IllegalArgumentException corrupt) { report("notification_state_corrupt_no_replay",id,false); return; }
        if (expectedHash.equals(previous.optString("sourceHash"))) {
            String status = previous.optString("status");
            if ("posted".equals(status) || "seen".equals(status) || "suppressed_visible".equals(status)
                    || "outcome_unknown".equals(status)) { report("already_handled",id,false); return; }
            if ("dispatching".equals(status)) {
                StatusBarNotification active = active(id,expectedHash);
                if (active != null) { save(id,expectedHash,stage,"posted","recovered_existing_notification",active.getPostTime()); report("recovered_existing_notification",id,true); }
                else { save(id,expectedHash,stage,"outcome_unknown","notification_outcome_unknown_no_replay",0); report("notification_outcome_unknown_no_replay",id,false); }
                return;
            }
        }
        if (conversationVisible) { save(id,expectedHash,stage,"suppressed_visible","conversation_visible",0); report("conversation_visible",id,false); return; }
        String blocked = blockedReason();
        if (!blocked.isEmpty()) { save(id,expectedHash,stage,"not_allowed",blocked,0); report(blocked,id,false); return; }
        save(id,expectedHash,stage,"dispatching","intent_saved_before_notify",0);
        // Recheck the actual policy after the persisted intent and before crossing the Android boundary.
        if (conversationVisible) { save(id,expectedHash,stage,"suppressed_visible","conversation_visible",0); report("conversation_visible",id,false); return; }
        blocked = blockedReason();
        if (!blocked.isEmpty()) { save(id,expectedHash,stage,"not_allowed",blocked,0); report(blocked,id,false); return; }
        try {
            trimActive(); manager.notify(tag(id),NOTIFICATION_ID,notification(source,stage));
            save(id,expectedHash,stage,"posted","android_notify_returned",System.currentTimeMillis()); report("notification_posted",id,true);
        } catch (SecurityException denied) { save(id,expectedHash,stage,"not_allowed","notification_permission_denied",0); report("notification_permission_denied",id,false); }
        catch (RuntimeException unknown) { save(id,expectedHash,stage,"outcome_unknown","notification_outcome_unknown_no_replay",0); report("notification_outcome_unknown_no_replay",id,false); }
    }

    private Notification notification(JSONObject source,String stage) {
        long id = source.optLong("id"); String hash = source.optString("content_hash");
        Intent open = new Intent(ACTION_OPEN).setClassName(context.getPackageName(),context.getPackageName()+".MainActivity")
                .setData(Uri.parse("hoyoung://assistant/message/"+id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SOURCE_ID,id).putExtra(EXTRA_SOURCE_HASH,hash).putExtra(EXTRA_OPEN_CONVERSATION,true);
        PendingIntent content = PendingIntent.getActivity(context,(int)(id^(id>>>32)),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String title = "quota".equals(stage)||"quota_exhausted".equals(stage)?"호영 · 한도 안내":"error".equals(stage)||"blocked".equals(stage)||"uncertain".equals(stage)?"호영 · 확인할 내용":"호영";
        String body = preview(source.optString("text"),400), expanded = preview(source.optString("text"),1_200);
        Notification publicVersion = new Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("호영").setContentText("새로운 메시지가 있어. 앱에서 확인해.").setVisibility(Notification.VISIBILITY_PUBLIC).build();
        Bundle extras = new Bundle(); extras.putString(EXTRA_SOURCE_KIND,"assistant_reply");extras.putLong(EXTRA_SOURCE_ID,id);extras.putString(EXTRA_SOURCE_HASH,hash);extras.putString(EXTRA_STAGE,stage);
        Notification.Builder builder = new Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(expanded))
                .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion).setCategory(Notification.CATEGORY_MESSAGE).addExtras(extras)
                .setWhen(source.optLong("created_at",System.currentTimeMillis())).setShowWhen(true);
        if (Build.VERSION.SDK_INT>=29) builder.setAllowSystemGeneratedContextualActions(false);
        return builder.build();
    }

    private void ensureChannel() {
        if (manager == null || manager.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,"호영의 답장과 선톡",NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("답장·필요한 선톡·한도와 오류 안내. 게시되었다고 읽음으로 처리하지 않음.");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE); manager.createNotificationChannel(channel);
    }
    private String blockedReason() {
        if (!"true".equals(store.getSetting("replyNotificationsEnabled","true"))) return "reply_notifications_disabled";
        if (manager == null) return "notification_manager_unavailable";
        if (!permissionGranted()) return "notification_permission_denied";
        if (!manager.areNotificationsEnabled()) return "app_notifications_disabled";
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        if (channel == null) return "notification_channel_unavailable";
        return channel.getImportance()==NotificationManager.IMPORTANCE_NONE?"notification_channel_disabled":"";
    }
    private boolean permissionGranted() { try{return Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;}catch(SecurityException unavailable){return false;} }
    private Object sourceLock(long id){return SOURCE_LOCKS[(processScope+":"+id).hashCode()&(SOURCE_LOCKS.length-1)];}
    private StatusBarNotification active(long id,String hash) {
        if (manager==null) return null;
        for (StatusBarNotification item:manager.getActiveNotifications()) {
            if (tag(id).equals(item.getTag()) && item.getId()==NOTIFICATION_ID && item.getNotification()!=null
                    && hash.equals(item.getNotification().extras.getString(EXTRA_SOURCE_HASH))) return item;
        }
        return null;
    }
    private void trimActive() {
        List<StatusBarNotification> replies=new ArrayList<>();
        for(StatusBarNotification item:manager.getActiveNotifications())if(item.getTag()!=null&&item.getTag().startsWith(TAG_PREFIX)&&item.getId()==NOTIFICATION_ID)replies.add(item);
        replies.sort(Comparator.comparingLong(StatusBarNotification::getPostTime));
        for(int i=0;i<=replies.size()-MAX_ACTIVE;i++)manager.cancel(replies.get(i).getTag(),NOTIFICATION_ID);
    }
    private void save(long id,String hash,String stage,String status,String reason,long postedAt) {
        store.withTransaction(db->{
            JSONObject existing=parse(store.getSetting(STATE_PREFIX+id,"{}"));
            if ("seen".equals(existing.optString("status"))&&hash.equals(existing.optString("sourceHash")))return null;
            store.putSetting(STATE_PREFIX+id,object("sourceMessageId",id,"sourceHash",hash,"stage",stage,"status",status,"reason",reason,
                    "updatedAt",System.currentTimeMillis(),"postedAt",postedAt,"readConfirmedByUi",false).toString());return null;
        });
    }
    private void report(String code,long id,boolean posted) {
        JSONObject value=object("lastCode",code,"sourceMessageId",id,"notificationPosted",posted,"readConfirmed",false,"postingDoesNotMeanRead",true);
        lastState=value;
        try {store.putSetting("replyNotifications.lastState",value.toString());}catch(RuntimeException unavailable){/* Original message and dispatch intent remain the source of truth. */}
    }
    private static boolean eligible(String stage) {return "completed".equals(stage)||"proactive".equals(stage)||"assistant_reply".equals(stage)||"awaiting_result".equals(stage)||"quota".equals(stage)||"quota_exhausted".equals(stage)||"error".equals(stage)||"blocked".equals(stage)||"uncertain".equals(stage)||"outcome_uncertain".equals(stage);}
    private static String tag(long id){return TAG_PREFIX+id;}
    private static String preview(String text,int max){StringBuilder result=new StringBuilder();for(int i=0;i<text.length()&&result.length()<max;i++){char c=text.charAt(i);result.append(Character.isISOControl(c)&&c!=10&&c!=9?' ':c);}if(result.length()>0&&result.length()<text.length()&&Character.isHighSurrogate(result.charAt(result.length()-1)))result.setLength(result.length()-1);return result.toString();}
    private static JSONObject dispatchState(String raw,long id) {
        if (raw.isEmpty() || raw.length()>4_096) throw new IllegalArgumentException("Unreadable notification state");
        int depth=0;boolean quoted=false,escaped=false;
        for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>16)throw new IllegalArgumentException("Notification state nesting too deep");}else if(c=='}'||c==']'){if(--depth<0)throw new IllegalArgumentException("Malformed notification state");}}
        try {
            JSONTokener tokener=new JSONTokener(raw);Object value=tokener.nextValue();
            if(!(value instanceof JSONObject)||tokener.nextClean()!=0)throw new IllegalArgumentException("Notification state must be one object");
            JSONObject record=(JSONObject)value;String status=record.optString("status");
            if(record.optLong("sourceMessageId")!=id||!record.optString("sourceHash").matches("[0-9a-f]{64}")
                    ||!("dispatching".equals(status)||"posted".equals(status)||"seen".equals(status)||"suppressed_visible".equals(status)||"outcome_unknown".equals(status)||"not_allowed".equals(status)))throw new IllegalArgumentException("Invalid notification dispatch state");
            return record;
        }catch(org.json.JSONException invalid){throw new IllegalArgumentException("Unreadable notification state",invalid);}
    }
    private static JSONObject parse(String value){try{return new JSONObject(value);}catch(Exception invalid){return new JSONObject();}}
    private static JSONObject object(Object...pairs){JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)put(value,String.valueOf(pairs[i]),pairs[i+1]);return value;}
    private static void put(JSONObject value,String key,Object content){try{value.put(key,content);}catch(Exception invalid){throw new IllegalStateException(invalid);}}
}
