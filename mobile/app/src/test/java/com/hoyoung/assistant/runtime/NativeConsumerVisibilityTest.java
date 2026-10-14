package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import com.hoyoung.assistant.memory.MemoryRetriever;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.store.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Public/native consumers must enforce source visibility even with an empty tombstone ledger. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NativeConsumerVisibilityTest {
    private Context context;private HoyoungStore store;private InternalFiles files;private AssistantRuntime runtime;private NotificationManager manager;
    @Before public void setup()throws Exception {context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());store=new HoyoungStore(context);files=new InternalFiles(context);runtime=new AssistantRuntime(context,store,files,new PhoneTools(context,store),Runnable::run);manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);manager.cancelAll();Shadows.shadowOf(manager).setNotificationsEnabled(true);}
    @After public void cleanup()throws Exception {manager.cancelAll();store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());}
    @Test public void retrieverAndDerivedNoteRespectDeviceScopeWithNoOwnerExclusions()throws Exception {
        JSONObject copy=deviceCopy("consumer copy CONSUMER-PRIVATE-7934");
        store.upsertMemoryWithSources("consumer-derived-note","consumer copy CONSUMER-PRIVATE-7934","observation",new JSONArray().put(ref("message",copy)));
        assertEquals(0,store.getPrivacyLedger().capture().exclusionCount);
        MemoryRetriever retriever=new MemoryRetriever(store);retriever.setSemanticEnabled(false);retriever.setDeviceSourceVisibility(row->false);
        JSONObject hidden=retriever.search(object("query","consumer copy","maxChars",12000));assertFalse(hidden.optString("context").contains("CONSUMER-PRIVATE-7934"));
        assertFalse(hidden.getJSONArray("memories").toString().contains("consumer-derived-note"));assertFalse(hidden.getJSONArray("originalEvidence").toString().contains("CONSUMER-PRIVATE-7934"));
        retriever.setDeviceSourceVisibility(row->true);assertTrue(retriever.search(object("query","consumer copy","maxChars",12000)).optString("context").contains("CONSUMER-PRIVATE-7934"));
    }
    @Test public void actualSourceReadConsumerRejectsDeviceCopyAfterContactsGrantIsRevoked()throws Exception {
        Shadows.shadowOf((Application)context).grantPermissions(android.Manifest.permission.READ_CONTACTS);
        JSONObject copy=deviceCopy("CONTACTS-COPY-PRIVATE-9351");
        JSONObject allowed=executeTool("memory.source.read",object("messageId",copy.getLong("id"),"offsetChars",0,"maxChars",1000));assertTrue(allowed.toString(),allowed.optBoolean("ok",true));assertTrue(allowed.toString().contains("CONTACTS-COPY-PRIVATE-9351"));
        Shadows.shadowOf((Application)context).denyPermissions(android.Manifest.permission.READ_CONTACTS);
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS));assertEquals(0,store.getPrivacyLedger().capture().exclusionCount);
        JSONObject denied=executeTool("memory.source.read",object("messageId",copy.getLong("id"),"offsetChars",0,"maxChars",1000));assertFalse(denied.toString(),denied.optBoolean("ok",true));assertFalse(denied.toString().contains("CONTACTS-COPY-PRIVATE-9351"));assertFalse(denied.has("original"));
    }
    @Test public void malformedBoundContinuationReceiptScopeCannotBecomeAnEmptyAllowedArray()throws Exception {
        FakeTransport transport=new FakeTransport();runtime.attachTransport(transport);ready();JSONObject request=runtime.receive("safe native listing",new JSONArray());runtime.detachTransport();
        String id=request.getString("external_id");JSONObject envelope=object("protocol","hoyoung.v1","requestId",id,"message","","actions",new JSONArray().put(object("id","native-list","name","files.list","arguments",new JSONObject())));
        runtime.onWebEvent(object("type","completed","requestId",id,"text","```hoyoung\n"+envelope+"\n```","rawEnvelopeEligible",true));
        JSONObject queued=store.nextQueuedRequest();assertNotNull(queued);assertEquals("tool-continuation",queued.getString("kind"));
        for(String corrupt:new String[]{"{broken receipt scope","{}","[] trailing"}){
            store.putSetting("continuationReceipts:"+queued.getString("external_id"),corrupt);store.markRequest(queued.getLong("id"),"queued",null);
            runtime.attachTransport(transport);ready();assertEquals(corrupt,1,transport.ids.size());assertEquals(corrupt,"blocked",store.getRequest(queued.getLong("id")).getString("status"));
        }
        assertTrue(store.events(100).toString().contains("prompt_unavailable"));
    }
    @Test public void notificationConsumerCancelsRevokedDeviceCopyAndKeepsIndependentReply()throws Exception {
        JSONObject copy=deviceCopy("NOTIFICATION-CONSUMER-PRIVATE-6523"),independent=store.addMessage("assistant","independent reply stays available",null);
        AssistantReplyNotifications replies=new AssistantReplyNotifications(context,store,Runnable::run);replies.setDeviceSourceVisibility(row->true);replies.publish(copy,"completed");assertTrue(activeContains("NOTIFICATION-CONSUMER-PRIVATE-6523"));
        replies.setDeviceSourceVisibility(row->false);assertFalse(activeContains("NOTIFICATION-CONSUMER-PRIVATE-6523"));assertEquals(0,store.getPrivacyLedger().capture().exclusionCount);
        replies.publish(independent,"completed");assertTrue(activeContains("independent reply stays available"));assertFalse(activeContains("NOTIFICATION-CONSUMER-PRIVATE-6523"));
    }
    @Test public void nativePermissionDiagnosticRemainsReadableButPrivateOrUnrelatedReceiptsDoNot()throws Exception {
        Shadows.shadowOf((Application)context).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION);
        FakeTransport transport=new FakeTransport();runtime.attachTransport(transport);ready();JSONObject request=runtime.receive("check native location permission",new JSONArray());runtime.detachTransport();
        String root=request.getString("external_id"),key="actionReceipt:"+root+":location-diagnostic";
        JSONObject envelope=object("protocol","hoyoung.v1","requestId",root,"message","","actions",new JSONArray().put(object("id","location-diagnostic","name","phone_location_refresh","arguments",new JSONObject())));
        runtime.onWebEvent(object("type","completed","requestId",root,"text","```hoyoung\n"+envelope+"\n```","rawEnvelopeEligible",true));
        JSONObject receipt=new JSONObject(store.getSetting(key,"{}"));assertEquals("permission_missing",receipt.getJSONObject("result").getString("code"));
        JSONObject args=object("rootRequestId",root,"actionId","location-diagnostic","offsetChars",0,"maxChars",1000);
        JSONObject allowed=executeTool("actions.receipt.read",args);assertTrue(allowed.toString(),allowed.optBoolean("ok"));assertTrue(allowed.toString().contains("permission_missing"));
        for(JSONObject forbidden:new JSONObject[]{object("ok",false,"code","permission_missing","permissionId","location","latitude",1.25),object("ok",false,"code","permission_missing","permissionId","location","message","PRIVATE-LOCATION-DIAGNOSTIC-8934"),object("ok",true,"code","ok","latitude",1.25)}) {
            JSONObject altered=new JSONObject(receipt.toString()).put("result",forbidden);store.putSetting(key,altered.toString());
            JSONObject denied=executeTool("actions.receipt.read",args);assertFalse(denied.toString(),denied.optBoolean("ok",true));assertFalse(denied.toString().contains("latitude"));assertFalse(denied.toString().contains("PRIVATE-LOCATION-DIAGNOSTIC-8934"));
        }
        store.putSetting(key,new JSONObject(receipt.toString()).put("name","phone_contacts_read").toString());
        assertFalse(executeTool("actions.receipt.read",args).optBoolean("ok",true));
    }
    private JSONObject deviceCopy(String text)throws Exception {JSONObject event=store.appendDeviceEvent("provider_snapshot","android.contacts",object("displayName",text),100,null),copy=store.addMessage("assistant",text,null);store.putSetting("answerSources:"+copy.getLong("id"),object("version",1,"sources",new JSONArray().put(ref("device_event",event))).toString());return copy;}
    private boolean activeContains(String text){for(StatusBarNotification n:manager.getActiveNotifications())if(n.getNotification().extras.toString().contains(text))return true;return false;}
    private JSONObject executeTool(String name,JSONObject args)throws Exception {Method method=AssistantRuntime.class.getDeclaredMethod("executeTool",String.class,JSONObject.class);method.setAccessible(true);return (JSONObject)method.invoke(runtime,name,args);}
    private void ready()throws Exception {runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",false,"quota",false)));}
    private static JSONObject ref(String type,JSONObject source)throws Exception {return object("type",type,"id",source.getLong("id"),"hash",source.getString("content_hash"));}
    private static JSONObject object(Object...pairs)throws Exception {JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}
    private static void clear(File file)throws Exception {if(!file.exists())return;if(file.isDirectory()){File[]children=file.listFiles();if(children==null)throw new java.io.IOException("Cannot inspect fixture");for(File child:children)clear(child);}if(!file.delete())throw new java.io.IOException("Cannot remove fixture");}
    private static final class FakeTransport implements AssistantRuntime.Transport {final List<String> ids=new ArrayList<>();public void inspect(){}public void send(String id,String prompt){ids.add(id);}public void openTemporary(){}public void cancel(){}}
}
