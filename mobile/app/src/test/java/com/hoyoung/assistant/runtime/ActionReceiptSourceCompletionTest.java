package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Native origin and exclusion regressions; no model or real device action is performed. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ActionReceiptSourceCompletionTest {
    private Context context;private HoyoungStore store;private ActionReceiptJournal journal;
    @Before public void before()throws Exception{context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());store=new HoyoungStore(context);journal=new ActionReceiptJournal(store);journal.ensureIndex();}
    @After public void after()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());}
    @Test public void strictlyBoundAgentRootAllowsNativeResultsWithoutCreatingOwnerAuthority()throws Exception {
        JSONObject source=agent("agent-root","agent continues the existing task");JSONObject receipt=receipt("files.write",source);
        journal.put("actionReceipt:agent-root:write",receipt);assertTrue(journal.readAllowed("agent-root","write",row->true));
        assertEquals(source.getString("content_hash"),journal.nativeSourcesForReceipt("agent-root",receipt,row->true).getJSONObject(0).getString("hash"));
        JSONObject summary=summary("agent-root",true);assertTrue(summary.toString(),summary.getBoolean("ready"));assertEquals(1,summary.getInt("confirmedMutationCount"));assertEquals(0,summary.getInt("sourceUnavailableCount"));
        assertEquals("system",store.getMessage(source.getLong("id")).getString("role"));assertNull(store.getSetting("ownerInputRevision",null));
    }
    @Test public void agentOriginCannotBeForgedByChangingKindActorHashOrRequestText()throws Exception {
        JSONObject source=agent("agent-root","original native continuation");JSONObject receipt=receipt("files.write",source);
        store.putSetting("inputActor:agent-root","owner");assertFalse(journal.receiptAllowed("agent-root",receipt,row->true));store.putSetting("inputActor:agent-root",UiAutomationProvenance.AGENT);
        store.putSetting("requestSourceHash:agent-root","b".repeat(64));assertFalse(journal.receiptAllowed("agent-root",receipt,row->true));store.putSetting("requestSourceHash:agent-root",source.getString("content_hash"));
        store.getWritableDatabase().execSQL("UPDATE requests SET kind='user' WHERE external_id='agent-root'");assertFalse(journal.receiptAllowed("agent-root",receipt,row->true));store.getWritableDatabase().execSQL("UPDATE requests SET kind='agent-authored',text='different instruction' WHERE external_id='agent-root'");assertFalse(journal.receiptAllowed("agent-root",receipt,row->true));
    }
    @Test public void arbitrarySystemSelfRootIsNotAnAgentInstruction()throws Exception {
        String root="system-root";store.enqueueRequest("unbound system wording","agent-authored",root);JSONObject source=store.addMessage("system","unbound system wording",root);bind(root,source);store.putSetting("inputActor:"+root,UiAutomationProvenance.AGENT);
        assertFalse(journal.receiptAllowed(root,receipt("files.write",source),row->true));
    }
    @Test public void excludedDirectCompletionCannotCloseEvenWhenNativeFlagSaysConfirmed()throws Exception {
        String root="owner-root";JSONObject source=owner(root);journal.put("actionReceipt:"+root+":write",receipt("files.write",source));assertTrue(summary(root,true).getBoolean("ready"));exclude(source);
        JSONObject denied=summary(root,true);assertFalse(denied.getBoolean("ready"));assertEquals(1,denied.getInt("sourceUnavailableCount"));assertEquals(0,denied.getInt("confirmedMutationCount"));assertNotNull(journal.get("actionReceipt:"+root+":write"));
    }
    @Test public void revokedDeviceSourcePreventsDirectNativeCompletion()throws Exception {
        String root="owner-root";JSONObject source=owner(root),device=store.appendDeviceEvent("notification","phone",new JSONObject().put("text","synthetic device source"),100,null);
        JSONObject receipt=receipt("files.write",source);receipt.getJSONObject("nativeResultSources").getJSONArray("sources").put(ref("device_event",device));journal.put("actionReceipt:"+root+":write",receipt);
        assertTrue(journal.completionSummary(root,name->true,row->true,row->true).getBoolean("ready"));JSONObject denied=journal.completionSummary(root,name->true,row->true,row->false);assertFalse(denied.getBoolean("ready"));assertEquals(1,denied.getInt("sourceUnavailableCount"));
    }
    @Test public void visibleCallbackDoesNotCertifyOriginalMutationWithExcludedDependency()throws Exception {
        String root="owner-root",id="11111111-1111-4111-8111-111111111111";JSONObject owner=owner(root),old=store.addMessage("user","separate original dependency",null);
        JSONObject start=receipt("phone_screen_capture",owner);start.put("result",new JSONObject().put("ok",true).put("requestId",id).put("completionConfirmed",false));start.getJSONObject("nativeResultSources").getJSONArray("sources").put(ref("message",old));
        JSONObject callback=receipt("phone_screen_capture_read",owner);callback.put("result",new JSONObject().put("ok",true).put("completionConfirmed",true).put("uploadRequested",true).put("request",new JSONObject().put("requestId",id).put("state","observed").put("completionConfirmed",true)));
        journal.put("actionReceipt:"+root+":capture",start);journal.put("actionReceipt:"+root+":read",callback);exclude(old);
        JSONObject summary=journal.completionSummary(root,name->"phone_screen_capture".equals(name),row->false,row->true);assertFalse(summary.getBoolean("ready"));assertEquals(1,summary.getInt("sourceUnavailableCount"));assertEquals(0,summary.getInt("confirmedMutationCount"));
    }
    private JSONObject summary(String root,boolean direct){return journal.completionSummary(root,name->true,row->direct,row->true);}
    private JSONObject agent(String root,String text)throws Exception {
        store.enqueueRequest(text,"agent-authored",root);JSONObject body=new JSONObject().put("type","agent_authored_input").put("inputActor",UiAutomationProvenance.AGENT).put("authoredBy",UiAutomationProvenance.AGENT).put("submittedBy",UiAutomationProvenance.AGENT).put("text",text).put("naturalLanguageOwnerUtterance",false);
        JSONObject source=store.addMessage("system",body.toString(),root);bind(root,source);store.putSetting("inputActor:"+root,UiAutomationProvenance.AGENT);return source;
    }
    private JSONObject owner(String root)throws Exception {store.enqueueRequest("owner request","user",root);JSONObject source=store.addMessage("user","owner request",root);bind(root,source);return source;}
    private void bind(String root,JSONObject source)throws Exception {store.putSetting("requestSource:"+root,String.valueOf(source.getLong("id")));store.putSetting("requestSourceHash:"+root,source.getString("content_hash"));}
    private void exclude(JSONObject source)throws Exception {store.getPrivacyLedger().excludeSource("message",source.getLong("id"),source.getString("content_hash"),"retrieval_exclude","owner test exclusion");}
    private static JSONObject ref(String type,JSONObject source)throws Exception {return new JSONObject().put("type",type).put("id",source.getLong("id")).put("hash",source.getString("content_hash"));}
    private static JSONObject receipt(String name,JSONObject source)throws Exception {return new JSONObject().put("status","completed").put("name",name).put("result",new JSONObject().put("ok",true).put("completionConfirmed",true)).put("nativeResultSources",new JSONObject().put("version",1).put("sources",new JSONArray().put(ref("message",source))));}
    private static void clear(java.io.File file)throws Exception{if(!file.exists())return;if(file.isDirectory()){java.io.File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Synthetic fixture cleanup failed");for(java.io.File child:children)clear(child);}if(!file.delete())throw new java.io.IOException("Synthetic fixture cleanup failed");}
}
