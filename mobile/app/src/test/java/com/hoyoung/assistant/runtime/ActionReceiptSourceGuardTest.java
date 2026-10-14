package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.store.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ActionReceiptSourceGuardTest {
    private Context context;private HoyoungStore store;private ActionReceiptJournal journal;
    @Before public void setup()throws Exception {context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());store=new HoyoungStore(context);journal=new ActionReceiptJournal(store);journal.ensureIndex();}
    @After public void cleanup()throws Exception {store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());}
    @Test public void hotReadAndLoadedCacheRespectExcludedOriginalButKeepUnrelatedQuestionAvailable()throws Exception {
        owner("question","allowed owner question");owner("clean","unrelated clean question");JSONObject old=owner("old","OLD-HOT-RECEIPT-8731");
        JSONObject receipt=completed("memory.source.read",new JSONObject().put("original",store.readOriginalText(old.getLong("id"),0,2000)));
        journal.put("actionReceipt:question:read",receipt);journal.put("actionReceipt:clean:read",completed("files.read",new JSONObject().put("text","CLEAN-UNRELATED-8123")));
        assertTrue(journal.readAllowed("question","read",row->true));assertTrue(journal.receiptAllowed("question",receipt,row->true));
        JSONArray verified=journal.nativeSourcesForReceipt("question",receipt,row->true);assertEquals(2,verified.length());
        assertEquals(old.getString("content_hash"),verified.getJSONObject(1).getString("hash"));exclude(old);
        assertFalse(journal.readAllowed("question","read",row->true));assertFalse(journal.receiptAllowed("question",receipt,row->true));assertTrue(journal.readAllowed("clean","read",row->true));
        assertThrows(IllegalStateException.class,()->journal.nativeSourcesForReceipt("question",receipt,row->true));
        assertTrue(journal.get("actionReceipt:question:read").toString().contains("OLD-HOT-RECEIPT-8731")); // raw owner auditing is preserved
    }
    @Test public void nativePacketSourcesFencePlainCopiedResultsAndTheirNewArchive()throws Exception {
        owner("question","allowed owner question");JSONObject old=owner("old","PLAIN-COPIED-RECEIPT-4217");
        JSONObject receipt=completed("files.write",new JSONObject().put("text","PLAIN-COPIED-RECEIPT-4217"));receipt.put("nativeResultSources",provenance(ref("message",old)));
        journal.put("actionReceipt:question:write",receipt);assertTrue(journal.receiptAllowed("question",receipt,row->true));exclude(old);
        assertFalse(journal.readAllowed("question","write",row->true));journal.resolveFor("question",true);
        // A completed receipt is not reconciled again, so seed the same native body as uncertain.
        receipt.put("status","uncertain");journal.put("actionReceipt:question:write",receipt);journal.resolveFor("question",true);
        long archived=journal.get("actionReceipt:question:write").getLong("previousReceiptSourceMessageId");
        JSONObject packet=new JSONObject(store.getSetting("answerSources:"+archived,"{}"));assertEquals(old.getString("content_hash"),packet.getJSONArray("sources").getJSONObject(1).getString("hash"));
        MemoryGateway.Prepared prepared=new MemoryGateway(store).prepare("plain-safe","safe","user",0,"hello",null,80000);assertFalse(prepared.context.contains("PLAIN-COPIED-RECEIPT-4217"));
    }
    @Test public void nestedDeviceReferencesNeedCurrentGrantAndExactSourceGeneration()throws Exception {
        owner("question","allowed owner question");JSONObject device=store.appendDeviceEvent("notification","phone",new JSONObject().put("text","DEVICE-RESULT-7328"),100,null);
        JSONObject result=new JSONObject().put("memories",new JSONArray().put(new JSONObject().put("content","device-derived result").put("sourceRefs",new JSONArray().put(ref("device_event",device)))));
        JSONObject receipt=completed("memory.search",result);journal.put("actionReceipt:question:search",receipt);
        assertTrue(journal.readAllowed("question","search",row->true));assertFalse(journal.readAllowed("question","search",row->false));
        JSONObject wrong=ref("device_event",device).put("hash","0".repeat(64));assertFalse(journal.receiptAllowed("question",completed("memory.search",new JSONObject().put("originalEvidence",new JSONArray().put(wrong))),row->true));
        exclude(device,"device_event");assertFalse(journal.receiptAllowed("question",receipt,row->true));
    }
    @Test public void reusedMessageIdAllowsNewExactHashButNeverRebindsOldCachedBytes()throws Exception {
        owner("question","allowed owner question");JSONObject old=owner("old","OLD-GENERATION-8124");long id=old.getLong("id");JSONObject oldReceipt=completed("memory.search",new JSONObject().put("sourceRefs",new JSONArray().put(ref("message",old))));exclude(old);
        store.getWritableDatabase().delete("messages","id=?",new String[]{String.valueOf(id)});store.getWritableDatabase().execSQL("UPDATE sqlite_sequence SET seq=? WHERE name='messages'",new Object[]{id-1});
        JSONObject fresh=owner("new","NEW-GENERATION-9472");assertEquals(id,fresh.getLong("id"));assertTrue(store.getPrivacyLedger().allowedSource("message",id,fresh.getString("content_hash")));
        assertFalse(journal.receiptAllowed("question",oldReceipt,row->true));
        assertTrue(journal.receiptAllowed("question",completed("memory.search",new JSONObject().put("sourceRefs",new JSONArray().put(ref("message",fresh)))),row->true));
        assertTrue(journal.receiptAllowed("new",completed("files.read",new JSONObject().put("text","NEW-GENERATION-9472")),row->true));
        assertFalse(journal.receiptAllowed("old",completed("files.read",new JSONObject().put("text","OLD-GENERATION-8124")),row->true));
    }
    @Test public void actualNativeAutonomyAndReviewRootsCanHaveZeroOwnerButUnknownOrUserRootsCannot()throws Exception {
        JSONObject excluded=owner("forgotten","forgotten unrelated source");exclude(excluded);
        for(String kind:new String[]{"autonomy","memory-review"}){
            String root="native-"+kind;store.enqueueRequest("actual native "+kind,kind,root);store.putSetting("requestSource:"+root,"0");
            JSONObject receipt=completed("files.list",new JSONObject().put("files",new JSONArray())).put("nativeResultSources",new JSONObject().put("version",1).put("sources",new JSONArray()));
            assertTrue(journal.receiptAllowed(root,receipt,row->true));assertEquals(0,journal.nativeSourcesForReceipt(root,receipt,row->true).length());
            receipt.put("nativeResultSources",provenance(ref("message",excluded)));assertFalse(journal.receiptAllowed(root,receipt,row->true));
        }
        JSONObject clean=completed("files.list",new JSONObject().put("files",new JSONArray())).put("nativeResultSources",new JSONObject().put("version",1).put("sources",new JSONArray()));
        store.putSetting("requestSource:unknown","0");assertFalse(journal.receiptAllowed("unknown",clean,row->true));
        store.enqueueRequest("native user request with damaged owner source","user","damaged-user");store.putSetting("requestSource:damaged-user","0");assertFalse(journal.receiptAllowed("damaged-user",clean,row->true));
    }
    private JSONObject owner(String root,String text)throws Exception {JSONObject source=store.addMessage("user",text,root);store.putSetting("requestSource:"+root,String.valueOf(source.getLong("id")));store.putSetting("requestSourceHash:"+root,source.getString("content_hash"));return source;}
    private void exclude(JSONObject source)throws Exception {exclude(source,"message");}
    private void exclude(JSONObject source,String type)throws Exception {store.getPrivacyLedger().excludeSource(type,source.getLong("id"),source.getString("content_hash"),"retrieval_exclude","explicit selected source exclusion");}
    private static JSONObject completed(String name,JSONObject result)throws Exception {return new JSONObject().put("status","completed").put("name",name).put("result",result);}
    private static JSONObject ref(String type,JSONObject source)throws Exception {return new JSONObject().put("type",type).put("id",source.getLong("id")).put("hash",source.getString("content_hash"));}
    private static JSONObject provenance(JSONObject source)throws Exception {return new JSONObject().put("version",1).put("sources",new JSONArray().put(source));}
    private static void clear(File file)throws Exception {if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Cannot inspect fixture");for(File child:children)clear(child);}if(!file.delete())throw new java.io.IOException("Cannot remove fixture");}
}
