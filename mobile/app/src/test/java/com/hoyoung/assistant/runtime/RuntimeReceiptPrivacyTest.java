package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Actual native tool/read/cache/output entry points; transport has no provider connection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RuntimeReceiptPrivacyTest {
    private Context context;private HoyoungStore store;private InternalFiles files;private AssistantRuntime runtime;private ActionReceiptJournal journal;private FakeTransport transport;
    @Before public void setup()throws Exception {context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());store=new HoyoungStore(context);files=new InternalFiles(context);runtime=new AssistantRuntime(context,store,files,new PhoneTools(context,store),Runnable::run);journal=new ActionReceiptJournal(store);transport=new FakeTransport();}
    @After public void cleanup()throws Exception {store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());}
    @Test public void actualReceiptReadToolDoesNotReturnExcludedOriginalOrItsLargeArchivePointer()throws Exception {
        JSONObject owner=store.addMessage("user","allowed receipt owner question","question");store.putSetting("requestSource:question",String.valueOf(owner.getLong("id")));store.putSetting("requestSourceHash:question",owner.getString("content_hash"));
        JSONObject old=store.addMessage("user","PRIVATE-HOT-READ-5214",null);JSONObject refs=provenance(old);
        JSONObject receipt=new JSONObject().put("status","completed").put("name","memory.source.read").put("nativeResultSources",refs).put("result",new JSONObject().put("original",store.readOriginalText(old.getLong("id"),0,1000)));
        journal.put("actionReceipt:question:read",receipt);store.getPrivacyLedger().excludeSource("message",old.getLong("id"),old.getString("content_hash"),"retrieval_exclude","explicit owner exclusion");
        assertDeniedWithoutPrivate(executeTool("actions.receipt.read",object("rootRequestId","question","actionId","read")),"PRIVATE-HOT-READ-5214");
        JSONObject archive=files.writeFile("large-result.json","PRIVATE-HOT-READ-5214".repeat(20000));receipt.put("result",new JSONObject().put("fullResultArchived",true).put("artifact",archive));journal.put("actionReceipt:question:read",receipt);
        assertDeniedWithoutPrivate(executeTool("actions.receipt.read",object("rootRequestId","question","actionId","read")),"PRIVATE-HOT-READ-5214");
        assertTrue(journal.get("actionReceipt:question:read").getJSONObject("result").getBoolean("fullResultArchived"));
    }
    @Test public void completedCachedActionNeverRepeatsOrEchoesExcludedNativeResult()throws Exception {
        JSONObject old=store.addMessage("user","PRIVATE-CACHED-RESULT-8345",null);store.getPrivacyLedger().excludeSource("message",old.getLong("id"),old.getString("content_hash"),"retrieval_exclude","explicit owner exclusion");ready();
        JSONObject request=runtime.receive("check an existing action receipt",new JSONArray());String root=request.getString("external_id");JSONObject args=object("relativePath","never-read-again.txt","maxChars",1000);
        JSONObject cached=object("status","completed","name","files.read","fingerprint",sha("files.read\n"+args.toString()),"nativeResultSources",provenance(old),"result",object("ok",true,"text","PRIVATE-CACHED-RESULT-8345"));
        journal.put("actionReceipt:"+root+":cached-read",cached);
        complete(root,"",new JSONArray().put(object("id","cached-read","name","files.read","arguments",args)));
        assertEquals("completed",journal.get("actionReceipt:"+root+":cached-read").getString("status"));assertEquals(0,files.listFiles().length());
        assertFalse(store.events(100).toString().contains("PRIVATE-CACHED-RESULT-8345"));assertTrue(store.events(100).toString().contains("source_"));
        for(String prompt:transport.prompts)assertFalse(prompt.contains("PRIVATE-CACHED-RESULT-8345"));assertEquals(0,store.memoryCount());
    }
    @Test public void actualToolOnlyOriginalBecomesNativeOutcomeAndAnswerDependency()throws Exception {
        JSONObject old=store.addMessage("user","PRIVATE-TOOL-ONLY-SOURCE-7812",null);
        store.withTransaction(db->{for(int i=0;i<30;i++)store.addMessage("user","unrelated filler row "+i,null);return null;});ready();
        JSONObject request=runtime.receive("read the indicated record",new JSONArray());String root=request.getString("external_id");
        assertFalse(transport.prompts.get(0).contains("PRIVATE-TOOL-ONLY-SOURCE-7812"));
        complete(root,"",new JSONArray().put(object("id","original-read","name","memory.source.read","arguments",object("messageId",old.getLong("id"),"offsetChars",0,"maxChars",1000))));
        JSONObject receipt=journal.get("actionReceipt:"+root+":original-read");long outcome=receipt.getLong("nativeOutcomeSourceMessageId");
        assertRefsContain(store.getSetting("answerSources:"+outcome,"{}"),old);
        assertEquals(2,transport.ids.size());String next=transport.ids.get(1);complete(next,"PRIVATE-TOOL-ONLY-SOURCE-7812",null);
        long answer=0;JSONArray rows=store.listMessages(100);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if("assistant".equals(row.optString("role"))&&next.equals(row.optString("request_id")))answer=row.getLong("id");}assertTrue(answer>0);
        assertRefsContain(store.getSetting("answerSources:"+answer,"{}"),old);
        runtime.controlStoredSource("message",old.getLong("id"),old.getString("content_hash"),false);
        MemoryGateway.Prepared prepared=new MemoryGateway(store).prepare("safe-after-tool","safe-after-tool","user",0,"a new independent question",null,80000);
        assertFalse(prepared.context.contains("PRIVATE-TOOL-ONLY-SOURCE-7812"));assertNotNull(store.getMessage(answer));
    }
    @Test public void actualOsGrantRevocationHoldsProviderOutputBeforeReplyAndMemoryPromotion()throws Exception {
        Shadows.shadowOf((Application)context).grantPermissions(android.Manifest.permission.READ_CONTACTS);
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS));ready();
        JSONObject request=runtime.receive("current permission-sensitive request",new JSONArray());
        Shadows.shadowOf((Application)context).denyPermissions(android.Manifest.permission.READ_CONTACTS);
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS));
        String raw="```hoyoung\n"+object("protocol","hoyoung.v1","requestId",request.getString("external_id"),"message","REVOKED-GRANT-OUTPUT-8425","memories",new JSONArray().put(object("key","stale-os-note","content","REVOKED-GRANT-OUTPUT-8425","kind","generated_summary")))+"\n```";
        runtime.onWebEvent(object("type","completed","requestId",request.getString("external_id"),"text",raw,"rawEnvelopeEligible",true));
        assertEquals(raw,store.getRequest(request.getLong("id")).getString("result"));assertEquals(0,store.memoryCount());
        assertTrue(store.events(100).toString().contains("device_source_grant_changed"));
        JSONArray messages=store.listMessages(100);for(int i=0;i<messages.length();i++)if("assistant".equals(messages.getJSONObject(i).optString("role")))assertFalse(messages.getJSONObject(i).optString("text").contains("REVOKED-GRANT-OUTPUT-8425"));
    }
    @Test public void actualFinalPacketMarkerTracksHeldRawProviderPayloadForLaterPhysicalErasure()throws Exception {
        JSONObject old=store.addMessage("user","marker remembered source HELD-RAW-MARKER-8264",null);ready();
        JSONObject request=runtime.receive("recall the marker remembered source",new JSONArray());String id=request.getString("external_id");long question=Long.parseLong(store.getSetting("requestSource:"+id,"0"));
        String digest=runtime.registerPreparedPayload(id,object("requestId",id,"text",transport.prompts.get(0),"requiredModel","gpt-6.1-sol","requiredEffort","medium","requiredSpeed","fast","requiresNativeAuthorization",true,"attachments",new JSONArray()));
        assertTrue(runtime.authorizeFinalPayload(id,digest));assertFalse(runtime.authorizeFinalPayload(id,digest));
        long marker=Long.parseLong(store.getSetting("dispatchEvidenceSource:"+id,"0"));assertTrue(marker>0);assertRefsContain(store.getSetting("answerSources:"+marker,"{}"),old);
        store.getPrivacyLedger().excludeSource("message",old.getLong("id"),old.getString("content_hash"),"retrieval_exclude","synthetic concurrent owner privacy change");
        String raw="```hoyoung\n"+object("protocol","hoyoung.v1","requestId",id,"message","HELD-RAW-MARKER-8264 ".repeat(11000))+"\n```";
        runtime.onWebEvent(object("type","completed","requestId",id,"text",raw,"rawEnvelopeEligible",true));
        JSONObject retained=store.getRequest(request.getLong("id"));assertTrue(retained.getString("result").contains("HELD-RAW-MARKER-8264"));assertTrue(store.events(100).toString().contains("work_output_held"));
        File original=new File(context.getFilesDir(),"originals/"+retained.getString("raw_result_file_id"));assertTrue(original.isFile());
        JSONObject receipt=runtime.controlStoredSource("message",old.getLong("id"),old.getString("content_hash"),true);
        assertTrue(receipt.toString(),receipt.getBoolean("managedLocalDeletionVerified"));assertFalse(receipt.getBoolean("pendingDeletion"));assertFalse(original.exists());
        JSONObject scrubbed=store.getRequest(request.getLong("id"));assertEquals("",scrubbed.getString("result"));assertTrue(scrubbed.isNull("raw_result_file_id"));assertNull(store.getMessage(marker));
        assertNotNull(store.getMessage(question));assertFalse(receipt.getBoolean("providerDeletionVerified"));
    }
    private void ready()throws Exception {runtime.attachTransport(transport);runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",false,"quota",false)));}
    private void complete(String requestId,String message,JSONArray actions)throws Exception {JSONObject envelope=object("protocol","hoyoung.v1","requestId",requestId,"message",message);if(actions!=null)envelope.put("actions",actions);runtime.onWebEvent(object("type","completed","requestId",requestId,"text","```hoyoung\n"+envelope+"\n```","rawEnvelopeEligible",true));}
    private JSONObject executeTool(String name,JSONObject args)throws Exception {Method method=AssistantRuntime.class.getDeclaredMethod("executeTool",String.class,JSONObject.class);method.setAccessible(true);return (JSONObject)method.invoke(runtime,name,args);}
    private static void assertDeniedWithoutPrivate(JSONObject result,String text)throws Exception {assertFalse(result.toString(),result.optBoolean("ok",true));assertFalse(result.toString().contains(text));assertFalse(result.has("original"));}
    private static void assertRefsContain(String raw,JSONObject source)throws Exception {JSONArray refs=new JSONObject(raw).getJSONArray("sources");boolean found=false;for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);if("message".equals(ref.optString("type"))&&ref.optLong("id")==source.getLong("id")&&source.getString("content_hash").equals(ref.optString("hash")))found=true;}assertTrue(raw,found);}
    private static JSONObject provenance(JSONObject source)throws Exception {return object("version",1,"sources",new JSONArray().put(object("type","message","id",source.getLong("id"),"hash",source.getString("content_hash"))));}
    private static JSONObject object(Object...pairs)throws Exception {JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}
    private static String sha(String text)throws Exception {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static void clear(File file)throws Exception {if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Cannot inspect fixture");for(File child:children)clear(child);}if(!file.delete())throw new java.io.IOException("Cannot remove fixture");}
    private static final class FakeTransport implements AssistantRuntime.Transport {final List<String> ids=new ArrayList<>(),prompts=new ArrayList<>();public void inspect(){}public void send(String id,String prompt){ids.add(id);prompts.add(prompt);}public void openTemporary(){}public void cancel(){}}
}
