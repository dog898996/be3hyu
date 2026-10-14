package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import com.hoyoung.assistant.phone.PhoneEventJournal;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.runtime.ActionReceiptJournal;
import com.hoyoung.assistant.runtime.AssistantRuntime;
import com.hoyoung.assistant.runtime.UsageBudgetLedger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Real native executeActions/cache path; only synthetic credentials and an in-memory SAF provider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RecoveryPublicationRuntimeTest {
    private Context context; private HoyoungStore store; private AccountStore accounts;
    private AssistantRuntime runtime; private ActionReceiptJournal receipts;
    private final AtomicBoolean providerFails=new AtomicBoolean(true);
    private MemoryTree tree; private JSONObject account,request; private String root;
    @Before public void setup()throws Exception {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
        clear(context.getFilesDir());clear(context.getNoBackupFilesDir());context.getSharedPreferences("owner-policy-folder",0).edit().clear().commit();
        store=new HoyoungStore(context);store.putSetting("memoryReviewEnabled","false");store.putSetting("autonomy.enabled","false");store.putSetting("usageOptimizationEnabled","false");
        accounts=new AccountStore(context,store);account=accounts.saveFromUi(input());tree=new MemoryTree();
        context.getSharedPreferences("owner-policy-folder",0).edit().putString("tree","content://synthetic.documents/tree/owner").commit();
        ExternalOwnerPolicyFolder folder=new ExternalOwnerPolicyFolder(context,uri->{if(providerFails.get())throw new IOException("synthetic provider unavailable");return tree;});
        PhoneTools phone=new PhoneTools(context,store);AccountTools facade=new AccountTools(context,store,phone);
        Field factor=AccountTools.class.getDeclaredField("mfa");factor.setAccessible(true);factor.set(facade,new AccountMfaTools(context,store,System::currentTimeMillis,null,folder));
        runtime=new AssistantRuntime(context,store,new InternalFiles(context),phone,Runnable::run);
        Field connected=AssistantRuntime.class.getDeclaredField("accountTools");connected.setAccessible(true);connected.set(runtime,facade);
        receipts=new ActionReceiptJournal(store);request=runtime.receive("use my synthetic recovery code",new JSONArray());root=request.getString("external_id");
    }
    @After public void cleanup()throws Exception {
        PhoneEventJournal journal=PhoneEventJournal.forContext(context);
        for(String name:new String[]{"closeWorker","awaitClosedForTests"}){Method method=PhoneEventJournal.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(journal);}
        Field field=AssistantRuntime.class.getDeclaredField("usageBudget");field.setAccessible(true);((UsageBudgetLedger)field.get(runtime)).close();
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
        clear(context.getFilesDir());clear(context.getNoBackupFilesDir());context.getSharedPreferences("owner-policy-folder",0).edit().clear().commit();
    }
    private JSONObject input()throws Exception{return new JSONObject().put("service","synthetic-recovery-site").put("username","synthetic-recovery-user").put("password","synthetic-only-password")
            .put("extra",new JSONObject().put("recoveryCodes",new JSONArray().put("synthetic-code-A").put("synthetic-code-B")));}
    private JSONObject arguments()throws Exception{return new JSONObject().put("accountId",account.getString("accountId"));}
    @SuppressWarnings("unchecked") private JSONObject action(String id,JSONObject args)throws Exception {
        long source=Long.parseLong(store.getSetting("requestSource:"+root,"0"));
        MemoryGateway.Prepared prepared=new MemoryGateway(store).prepare(root,root,"user",source,request.getString("text"),null,80_000);
        Field map=AssistantRuntime.class.getDeclaredField("preparedMemory");map.setAccessible(true);((Map<String,MemoryGateway.Prepared>)map.get(runtime)).put(root,prepared);
        store.putSetting("dispatchOwnerRevision:"+root,store.getSetting("ownerInputRevision","0"));
        JSONObject operation=new JSONObject().put("id",id).put("name","accounts.recovery.reserve").put("arguments",args);
        Method execute=AssistantRuntime.class.getDeclaredMethod("executeActions",String.class,String.class,JSONArray.class,boolean.class);execute.setAccessible(true);
        return ((JSONArray)execute.invoke(runtime,root,root,new JSONArray().put(operation),false)).getJSONObject(0);
    }
    private JSONObject pending()throws Exception {
        JSONObject response=action("reserve",arguments()),result=response.getJSONObject("result");
        assertEquals("recovery_portable_persistence_pending",result.getString("code"));assertFalse(result.has("recoveryCode"));
        assertEquals(1,new RecoveryCodeLedger(context).snapshot().getJSONArray("reservations").length());return response;
    }
    @Test public void sameActualActionResumesWithheldPublicationAndThenOnlyCachesSuccessfulResult()throws Exception {
        JSONObject initial=pending(),prior=receipts.get("actionReceipt:"+root+":reserve");providerFails.set(false);
        JSONObject resumed=action("reserve",arguments()),result=resumed.getJSONObject("result");
        assertEquals("synthetic-code-A",result.getString("recoveryCode"));assertFalse(resumed.optBoolean("cached"));assertTrue(result.getJSONObject("reservation").getBoolean("reusedReservation"));
        JSONObject saved=receipts.get("actionReceipt:"+root+":reserve");assertTrue(saved.getBoolean("resumesWithheldRecoveryPublication"));
        assertEquals(prior.getLong("nativeOutcomeSourceMessageId"),saved.getLong("previousReceiptSourceMessageId"));
        assertTrue(saved.getJSONObject("nativeResultSources").getJSONArray("sources").toString().contains(prior.getString("nativeOutcomeSourceHash")));
        assertEquals(1,new RecoveryCodeLedger(context).snapshot().getJSONArray("reservations").length());int writes=tree.writes;
        JSONObject cached=action("reserve",arguments());assertTrue(cached.getBoolean("cached"));assertEquals("synthetic-code-A",cached.getJSONObject("result").getString("recoveryCode"));assertEquals(writes,tree.writes);
        assertFalse(initial.toString().contains("synthetic-code-A"));
    }
    @Test public void correctedOrReorderedAccountCannotAllocateAnotherCodeUnderOldAction()throws Exception {
        pending();providerFails.set(false);JSONObject corrected=input().put("accountId",account.getString("accountId"));
        corrected.getJSONObject("extra").put("recoveryCodes",new JSONArray().put("synthetic-code-B"));accounts.saveFromUi(corrected);
        JSONObject rejected=action("reserve",arguments()).getJSONObject("result");assertEquals("recovery_resume_source_changed",rejected.getString("code"));assertFalse(rejected.has("recoveryCode"));
        assertEquals(1,new RecoveryCodeLedger(context).snapshot().getJSONArray("reservations").length());assertEquals(0,tree.writes);
    }
    @Test public void excludedOriginalCannotBeRepublishedEvenAfterProviderRecovers()throws Exception {
        pending();providerFails.set(false);JSONObject source=account.getJSONArray("sourceRefs").getJSONObject(0);
        store.getPrivacyLedger().excludeSource("message",source.getLong("id"),source.getString("hash"),"retrieval_exclude","synthetic owner exclusion");
        JSONObject rejected=action("reserve",arguments());assertEquals("cached_source_unavailable",rejected.getString("code"));assertFalse(rejected.toString().contains("synthetic-code-A"));assertEquals(0,tree.writes);
    }
    @Test public void changedArgumentsConflictInsteadOfResumingOrReserving()throws Exception {
        pending();providerFails.set(false);JSONObject rejected=action("reserve",arguments().put("index",1));assertEquals("action_id_conflict",rejected.getString("code"));
        assertEquals(1,new RecoveryCodeLedger(context).snapshot().getJSONArray("reservations").length());assertEquals(0,tree.writes);
    }
    @Test public void pausedOwnerWorkDoesNotResumePublication()throws Exception {
        pending();providerFails.set(false);store.putSetting("pausedRoot:"+root,"true");JSONObject rejected=action("reserve",arguments());assertEquals("current_work_paused",rejected.getString("code"));assertEquals(0,tree.writes);
    }
    @Test public void differentNativeActionChoosesNextCodeAndNeverReusesWithheldOne()throws Exception {
        pending();providerFails.set(false);JSONObject next=action("other-reservation",arguments()).getJSONObject("result");assertEquals("synthetic-code-B",next.getString("recoveryCode"));
        assertFalse(next.getJSONObject("reservation").getBoolean("reusedReservation"));assertEquals(2,new RecoveryCodeLedger(context).snapshot().getJSONArray("reservations").length());
    }
    @Test public void uncertainReceiptCannotUseThisPublicationRetryException()throws Exception {
        pending();providerFails.set(false);String key="actionReceipt:"+root+":reserve";JSONObject previous=receipts.get(key);previous.put("status","uncertain");receipts.put(key,previous);
        JSONObject rejected=action("reserve",arguments());assertEquals("action_outcome_uncertain",rejected.getString("code"));assertEquals(0,tree.writes);
    }
    private JSONObject state()throws Exception {
        Method method=AssistantRuntime.class.getDeclaredMethod("executeTool",String.class,JSONObject.class);method.setAccessible(true);
        return (JSONObject)method.invoke(runtime,"accounts.recovery.state",arguments());
    }
    /** Finish a real provider turn as awaiting_result, then receive an independent human message. */
    private void finishAwaitingTurn()throws Exception {
        runtime.attachTransport(new AssistantRuntime.Transport(){public void inspect(){}public void send(String id,String prompt){}public void openTemporary(){}public void cancel(){}});
        runtime.onWebEvent(new JSONObject().put("type","state").put("state",new JSONObject().put("authenticated",true).put("workSelected",true).put("temporary",true).put("model","gpt-6.1-sol").put("effort","medium").put("speed","fast").put("busy",false).put("quota",false)));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(root,runtime.state().optString("activeRequestId"));
        runtime.onWebEvent(new JSONObject().put("type","accepted").put("requestId",root));
        JSONObject envelope=new JSONObject().put("protocol","hoyoung.v1").put("requestId",root).put("status","awaiting_result").put("message","외부 폴더 접근이 회복되면 이어갈게.").put("nextCheckAt",System.currentTimeMillis()+300_000);
        runtime.onWebEvent(new JSONObject().put("type","completed").put("requestId",root).put("text","```hoyoung\n"+envelope+"\n```").put("rawEnvelopeEligible",true));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("completed",store.getRequest(request.getLong("id")).getString("status"));
        assertEquals("awaiting_result",new com.hoyoung.assistant.runtime.GoalLedger(store).get(root).getString("stage"));
    }
    private void newOwnerRequest()throws Exception {request=runtime.receive("폴더 다시 연결했어. 이제 완료해.",new JSONArray());root=request.getString("external_id");}
    @Test public void newOwnerMessageAfterAwaitingTurnDiscoversAndPublishesOriginalReservation()throws Exception {
        pending();String originalRoot=root;finishAwaitingTurn();newOwnerRequest();assertNotEquals(originalRoot,root);
        JSONObject discovery=state();JSONArray refs=discovery.getJSONArray("resumeCandidates");assertEquals(1,refs.length());JSONObject reference=refs.getJSONObject(0);assertEquals(originalRoot,reference.getString("rootRequestId"));
        assertFalse(discovery.toString().contains("synthetic-code-A"));providerFails.set(false);
        JSONObject resumed=action("resume-original",arguments().put("resume",reference)).getJSONObject("result");assertEquals("synthetic-code-A",resumed.getString("recoveryCode"));assertTrue(resumed.getBoolean("publicationSealed"));
        assertEquals(1,new RecoveryCodeLedger(context).snapshot().getJSONArray("reservations").length());assertEquals(0,state().getJSONArray("resumeCandidates").length());
        JSONObject repeated=action("another-new-action",arguments().put("resume",reference));assertEquals("recovery_resume_unavailable",repeated.getString("code"));assertFalse(repeated.toString().contains("synthetic-code-A"));
        assertTrue(action("resume-original",arguments().put("resume",reference)).getBoolean("cached"));
    }
    @Test public void secondNewRequestCanResumeAfterFirstCrossRootProviderFailureButOldReferenceCannot()throws Exception {
        pending();JSONObject originalReference=state().getJSONArray("resumeCandidates").getJSONObject(0);finishAwaitingTurn();newOwnerRequest();
        JSONObject withheld=action("resume-first",arguments().put("resume",originalReference)).getJSONObject("result");assertEquals("recovery_portable_persistence_pending",withheld.getString("code"));assertFalse(withheld.has("recoveryCode"));
        JSONObject newer=state().getJSONArray("resumeCandidates").getJSONObject(0);assertEquals(root,newer.getString("rootRequestId"));assertEquals("resume-first",newer.getString("actionId"));
        finishAwaitingTurn();newOwnerRequest();providerFails.set(false);
        assertEquals("recovery_resume_unavailable",action("stale-reference",arguments().put("resume",originalReference)).getString("code"));
        assertEquals("synthetic-code-A",action("resume-second",arguments().put("resume",newer)).getJSONObject("result").getString("recoveryCode"));
        assertEquals(1,new RecoveryCodeLedger(context).snapshot().getJSONArray("reservations").length());
    }
    @Test public void correctionAndExcludedSourceRemoveCrossRequestDiscoveryAndPreventPublication()throws Exception {
        pending();JSONObject reference=state().getJSONArray("resumeCandidates").getJSONObject(0);finishAwaitingTurn();newOwnerRequest();providerFails.set(false);
        JSONObject corrected=input().put("accountId",account.getString("accountId"));corrected.getJSONObject("extra").put("recoveryCodes",new JSONArray().put("synthetic-code-B"));accounts.saveFromUi(corrected);
        assertEquals(0,state().getJSONArray("resumeCandidates").length());assertEquals("recovery_resume_unavailable",action("changed-account",arguments().put("resume",reference)).getString("code"));
        JSONObject source=account.getJSONArray("sourceRefs").getJSONObject(0);store.getPrivacyLedger().excludeSource("message",source.getLong("id"),source.getString("hash"),"retrieval_exclude","synthetic owner exclusion");
        assertEquals("recovery_resume_unavailable",action("excluded-account",arguments().put("resume",reference)).getString("code"));assertEquals(1,new RecoveryCodeLedger(context).snapshot().getJSONArray("reservations").length());assertEquals(0,tree.writes);
    }
    @Test public void forgedOutcomeHashOrChangedOriginalActionCannotProvideResumeAuthority()throws Exception {
        pending();JSONObject reference=state().getJSONArray("resumeCandidates").getJSONObject(0);finishAwaitingTurn();newOwnerRequest();providerFails.set(false);
        JSONObject wrong=new JSONObject(reference.toString()).put("outcomeSourceHash",String.join("",Collections.nCopies(64,"0")));
        assertEquals("recovery_resume_unavailable",action("forged-hash",arguments().put("resume",wrong)).getString("code"));
        wrong=new JSONObject(reference.toString()).put("actionId","not-original");assertEquals("recovery_resume_unavailable",action("wrong-action",arguments().put("resume",wrong)).getString("code"));assertEquals(0,tree.writes);
    }
    @Test public void oldZipCannotReviveAPendingReferenceAfterCrossRootPublicationSucceeded()throws Exception {
        pending();JSONObject reference=state().getJSONArray("resumeCandidates").getJSONObject(0);ByteArrayOutputStream zip=new ByteArrayOutputStream();new InternalFiles(context).exportBackup(zip,store);
        finishAwaitingTurn();newOwnerRequest();providerFails.set(false);assertEquals("synthetic-code-A",action("resume-live",arguments().put("resume",reference)).getJSONObject("result").getString("recoveryCode"));
        new InternalFiles(context).importBackup(new ByteArrayInputStream(zip.toByteArray()),store);OwnerPolicyBackup.reconcile(context,store);
        // Simulate the app's independent post-restore owner input; the private monotonic publication ledger survives the ZIP.
        runtime.detachTransport();newOwnerRequest();assertEquals(0,state().getJSONArray("resumeCandidates").length());
        JSONObject rejected=action("resume-from-old-zip",arguments().put("resume",reference));assertEquals("recovery_resume_unavailable",rejected.getString("code"));assertFalse(rejected.toString().contains("synthetic-code-A"));
    }
    private static final class MemoryTree implements ExternalOwnerPolicyFolder.TreeAccess {
        private final Map<Uri,String> names=new LinkedHashMap<>();private final Map<Uri,byte[]> data=new LinkedHashMap<>();private int sequence,writes;
        public List<ExternalOwnerPolicyFolder.Document> list(){List<ExternalOwnerPolicyFolder.Document> result=new ArrayList<>();for(Map.Entry<Uri,String> entry:names.entrySet())result.add(new ExternalOwnerPolicyFolder.Document(entry.getKey(),entry.getValue()));return result;}
        public Uri create(String mime,String name){Uri uri=Uri.parse("content://synthetic.documents/document/"+(++sequence));names.put(uri,name);data.put(uri,new byte[0]);return uri;}
        public InputStream read(Uri document)throws IOException{byte[] value=data.get(document);if(value==null)throw new IOException("synthetic missing document");return new ByteArrayInputStream(value);}
        public OutputStream write(Uri document){writes++;return new ByteArrayOutputStream(){public void close(){data.put(document,toByteArray());}};}
        public Uri rename(Uri document,String name){names.put(document,name);return document;}
        public void delete(Uri document){names.remove(document);data.remove(document);}
    }
    private static void clear(File file){if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)clear(child);}file.delete();}
}
