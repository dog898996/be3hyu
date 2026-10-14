package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.runtime.AssistantRuntime;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Real owner-only native calls, private-file backup and SQLite; no model/account/network call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SourceControlRuntimeTest {
    private Context context; private HoyoungStore store; private InternalFiles files;
    private AssistantRuntime runtime;
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir());clear(context.getNoBackupFilesDir());
        store=new HoyoungStore(context);files=new InternalFiles(context);
        runtime=new AssistantRuntime(context,store,files,new PhoneTools(context,store),Runnable::run);
    }
    @After public void cleanup() throws Exception {
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir());clear(context.getNoBackupFilesDir());
    }
    @Test public void excludingLatestOwnerKeepsPayloadAndAdvancesOnlyNativeControlEpoch() throws Exception {
        JSONObject source=completedOwner("private phrase owner latest");
        long prior=source.getLong("id");int requestCount=store.listRequests(100).length();
        JSONObject receipt=runtime.controlStoredSource("message",prior,source.getString("content_hash"),false);
        assertEquals("retrieval_excluded",receipt.getString("status"));
        assertTrue(receipt.getBoolean("retrievalExcluded"));assertFalse(receipt.getBoolean("managedLocalDeletionVerified"));
        assertEquals("private phrase owner latest",store.readOriginalText(prior,0,100).getString("text"));
        assertNativeControl(prior);assertEquals(requestCount,store.listRequests(100).length());
        assertHealthyWithout("private phrase owner latest");
    }
    @Test public void deletingLatestOwnerHasVerifiedLocalReceiptAndNextMemoryPrepareWorks() throws Exception {
        JSONObject source=completedOwner("private payload to erase");long prior=source.getLong("id");
        JSONObject receipt=runtime.controlStoredSource("message",prior,source.getString("content_hash"),true);
        assertComplete(receipt);assertNull(store.getMessage(prior));assertNativeControl(prior);
        assertHealthyWithout("private payload to erase");
        assertFalse(receipt.getBoolean("providerDeletionVerified"));
    }
    @Test public void oldBackupCannotRestoreDeletedLatestOwnerOrInventAnOlderOwnerUtterance() throws Exception {
        JSONObject source=completedOwner("five-year older private owner payload");long prior=source.getLong("id");
        ByteArrayOutputStream old=new ByteArrayOutputStream();files.exportBackup(old,store);
        assertComplete(runtime.controlStoredSource("message",prior,source.getString("content_hash"),true));
        runtime.restoreBackup(new ByteArrayInputStream(old.toByteArray()));
        assertNull(store.getMessage(prior));assertNativeControl(prior);
        assertFalse(store.getPrivacyLedger().allowedSource("message",prior,source.getString("content_hash")));
        assertHealthyWithout("five-year older private owner payload");
        assertFalse(runtime.autonomyEnabled());
        for(int i=0;i<store.listRequests(100).length();i++)assertNotEquals("queued",store.listRequests(100).getJSONObject(i).optString("status"));
    }
    @Test public void mismatchingSourceGenerationDoesNotCreatePolicyOrAdvanceOwnerEpoch() throws Exception {
        JSONObject source=completedOwner("same id different source generation");
        String privacy=store.getPrivacyLedger().fingerprint();String owner=store.getSetting("ownerInputRevision","0");
        String wrong="0".repeat(64);if(wrong.equals(source.getString("content_hash")))wrong="1".repeat(64);
        final String mismatched=wrong;
        assertThrows(IllegalArgumentException.class,()->runtime.controlStoredSource("message",source.getLong("id"),mismatched,true));
        assertEquals(privacy,store.getPrivacyLedger().fingerprint());assertEquals(owner,store.getSetting("ownerInputRevision","0"));
        assertNotNull(store.getMessage(source.getLong("id")));
    }
    @Test public void activeWorkRejectsOwnerSourceMutationBeforePrivacyChanges() throws Exception {
        FakeTransport transport=new FakeTransport();runtime.attachTransport(transport);
        runtime.onWebEvent(object("type","state","code","ready","state",readyState()));
        JSONObject request=runtime.receive("active request stays observable",new JSONArray());
        JSONObject source=sourceFor(request);String privacy=store.getPrivacyLedger().fingerprint();
        assertEquals(1,transport.sent.size());
        assertThrows(IllegalStateException.class,()->runtime.controlStoredSource("message",source.getLong("id"),source.getString("content_hash"),true));
        assertEquals(privacy,store.getPrivacyLedger().fingerprint());assertNotNull(store.getMessage(source.getLong("id")));
        assertEquals("dispatching",store.getRequest(request.getLong("id")).getString("status"));
    }
    @Test public void interruptedDatabaseDeletionCanRetryUsingItsDurableHashWhenRowIsAbsent() throws Exception {
        JSONObject source=completedOwner("interrupted exact selected private payload");long prior=source.getLong("id");
        SourcePayloadDeletion interrupted=new SourcePayloadDeletion(context,store,phase->{if("after_database".equals(phase))throw new IOException("synthetic interruption after durable database erasure");});
        JSONObject pending=interrupted.deleteSource("message",prior,source.getString("content_hash"),"synthetic owner delete");
        assertTrue(pending.getBoolean("pendingDeletion"));assertNull(store.getMessage(prior));
        assertEquals(String.valueOf(prior),store.getSetting("ownerInputRevision","0"));
        JSONObject receipt=runtime.controlStoredSource("message",prior,source.getString("content_hash"),true);
        assertComplete(receipt);assertTrue(receipt.getBoolean("retrievalExcluded"));assertNativeControl(prior);
        assertHealthyWithout("interrupted exact selected private payload");
    }
    @Test public void folderPolicyImportPreservesOriginalRepairsOwnerEpochAndMakesNoModelRequest()throws Exception {
        JSONObject source=completedOwner("folder-only private owner payload");
        JSONObject portable=portableExclusion(source);FakeTransport transport=new FakeTransport();runtime.attachTransport(transport);
        int requests=store.listRequests(100).length();runtime.importOwnerControls(portable);
        assertFalse(store.getPrivacyLedger().allowedSource("message",source.getLong("id"),source.getString("content_hash")));
        assertEquals(source.getString("text"),store.getMessage(source.getLong("id")).getString("text"));assertNativeControl(source.getLong("id"));
        String owner=store.getSetting("ownerInputRevision","0");runtime.importOwnerControls(portable);
        assertEquals(owner,store.getSetting("ownerInputRevision","0"));assertEquals(requests,store.listRequests(100).length());assertTrue(transport.sent.isEmpty());
        assertHealthyWithout("folder-only private owner payload");
    }
    @Test public void activeWorkRejectsPortableImportBeforeChangingAnyPolicy()throws Exception {
        FakeTransport transport=new FakeTransport();runtime.attachTransport(transport);runtime.onWebEvent(object("type","state","code","ready","state",readyState()));
        JSONObject request=runtime.receive("active source must not race folder reconnect",new JSONArray()),source=sourceFor(request),portable=portableExclusion(source);
        String before=store.getPrivacyLedger().fingerprint();assertEquals(1,transport.sent.size());
        assertThrows(IllegalStateException.class,()->runtime.importOwnerControls(portable));
        assertEquals(before,store.getPrivacyLedger().fingerprint());assertEquals("dispatching",store.getRequest(request.getLong("id")).getString("status"));assertEquals(1,transport.sent.size());
    }
    @Test public void portableDeleteReconciliationInsideRuntimeRemovesOlderPayloadBeforeNextMemoryPrepare()throws Exception {
        JSONObject source=completedOwner("portable erased owner payload");ByteArrayOutputStream old=new ByteArrayOutputStream();files.exportBackup(old,store);
        assertComplete(runtime.controlStoredSource("message",source.getLong("id"),source.getString("content_hash"),true));JSONObject controls=OwnerPolicyBackup.capture(context,store);
        files.importBackup(new ByteArrayInputStream(old.toByteArray()),store);assertNotNull(store.getMessage(source.getLong("id")));
        runtime.importOwnerControls(controls);assertNull(store.getMessage(source.getLong("id")));assertNativeControl(source.getLong("id"));assertHealthyWithout("portable erased owner payload");
    }
    private JSONObject portableExclusion(JSONObject source)throws Exception {
        Context remote=new android.content.ContextWrapper(context){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){File root=new File(context.getFilesDir(),"remote-owner-controls");root.mkdirs();return root;}};
        SourcePrivacyLedger ledger=new SourcePrivacyLedger(remote);ledger.excludeSource("message",source.getLong("id"),source.getString("content_hash"),"retrieval_exclude","explicit remote owner folder policy");
        // This historical portable record intentionally has the original four-field schema.
        JSONObject body=object("format",OwnerPolicyBackup.LEGACY_FORMAT,"privacy",ledger.snapshot(),"deletions",new JSONArray());
        body.put("checksum",OriginalTextFiles.hex(OriginalTextFiles.digest().digest(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));return OwnerPolicyBackup.validate(body);
    }
    private JSONObject completedOwner(String text)throws Exception {
        JSONObject request=runtime.receive(text,new JSONArray());JSONObject source=sourceFor(request);
        store.markRequest(request.getLong("id"),"completed","synthetic completed locally; no transport");return source;
    }
    private JSONObject sourceFor(JSONObject request)throws Exception {
        long id=Long.parseLong(store.getSetting("requestSource:"+request.getString("external_id"),"0"));
        return store.getMessage(id);
    }
    private void assertNativeControl(long prior)throws Exception {
        long id=Long.parseLong(store.getSetting("ownerInputRevision","0"));assertTrue(id>prior);
        JSONObject row=store.getMessage(id);assertEquals("user",row.getString("role"));
        JSONObject control=new JSONObject(store.readOriginalText(id,0,1000).getString("text"));
        assertTrue(control.has("nativeOwnerControl"));assertFalse(control.getBoolean("startsNewWork"));
        assertFalse(control.getBoolean("naturalLanguageOwnerUtterance"));assertEquals("",store.getSetting("latestOwnerRequestId",""));
    }
    private void assertHealthyWithout(String excluded)throws Exception {
        MemoryGateway gate=new MemoryGateway(store);
        MemoryGateway.Prepared prepared=gate.prepare("safe-next","safe-next","owner_chat",0,"new independent owner question",null,20000);
        assertEquals(5,prepared.receipts().length());assertFalse(prepared.context.contains(excluded));
        assertTrue(prepared.context.contains("nativeOwnerControl"));gate.assertFresh(prepared);
    }
    private static void assertComplete(JSONObject receipt)throws Exception {
        assertEquals(receipt.toString(),"completed_managed_local",receipt.getString("status"));
        assertTrue(receipt.getBoolean("managedLocalDeletionVerified"));assertFalse(receipt.getBoolean("pendingDeletion"));
    }
    private static JSONObject readyState()throws Exception{return object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",false,"quota",false);}
    private static JSONObject object(Object...pairs)throws Exception {JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}
    private static void clear(File file)throws IOException {if(Files.isSymbolicLink(file.toPath())){Files.deleteIfExists(file.toPath());return;}if(!file.exists())return;if(file.isDirectory()){File[]children=file.listFiles();if(children==null)throw new IOException("Cannot inspect fixture");for(File child:children)clear(child);}Files.delete(file.toPath());}
    private static final class FakeTransport implements AssistantRuntime.Transport {
        final List<String> sent=new ArrayList<>();public void inspect(){}public void send(String id,String prompt){sent.add(id);}public void openTemporary(){}public void cancel(){}
    }
}
