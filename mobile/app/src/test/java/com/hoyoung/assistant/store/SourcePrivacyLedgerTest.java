package com.hoyoung.assistant.store;
import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SourcePrivacyLedgerTest {
    private Context context;private HoyoungStore store;private InternalFiles files;private SourcePrivacyLedger policy;
    @Before public void setup()throws Exception{context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());files=new InternalFiles(context);store=new HoyoungStore(context);policy=new SourcePrivacyLedger(context);}
    @After public void cleanup()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());}
    @Test public void exclusionSurvivesRestartIsIdempotentAndLeavesOriginalPayloadUntouched()throws Exception{
        JSONObject source=store.addMessage("user","kept private original",null);long id=source.getLong("id");String hash=source.getString("content_hash");
        JSONObject receipt=policy.excludeSource("message",id,hash,"retrieval_exclude","explicit owner request");assertTrue(receipt.getBoolean("retrievalExcluded"));assertFalse(receipt.getBoolean("originalPayloadDeleted"));assertTrue(receipt.getBoolean("originalPayloadRetained"));
        SourcePrivacyLedger reopened=new SourcePrivacyLedger(context);assertFalse(reopened.allowedSource("message",id,hash));assertEquals(1,reopened.version());
        assertFalse(reopened.excludeSource("message",id,hash,"retrieval_exclude","same owner intent").getBoolean("changed"));assertEquals(1,reopened.version());assertEquals(source.getString("text"),store.readOriginalText(id,0,100).getString("text"));
    }
    @Test public void frozenScopeIsImmutableWhileAnotherInstanceUpdatesCurrentPolicy()throws Exception{
        String hash="a".repeat(64);SourcePrivacyLedger.Snapshot before=policy.capture();SourcePrivacyLedger other=new SourcePrivacyLedger(context);other.excludeSource("message",8,hash,"retrieval_exclude","");
        assertTrue(before.allowedSource("message",8,hash));assertFalse(policy.allowedSource("message",8,hash));assertNotEquals(before.fingerprint,policy.fingerprint());
        JSONObject exposed=policy.snapshot();exposed.put("exclusions",new JSONArray());assertFalse(policy.allowedSource("message",8,hash));assertFalse(SourcePrivacyLedger.allowedSource(exposed,"message",8,hash));
        assertFalse(policy.capture().allowedSourceRefs(new JSONArray().put(ref("message",7,"b".repeat(64))).put(ref("message",8,hash))));
        assertTrue(policy.capture().allowedSource("device_event",8,hash));assertTrue(policy.capture().allowedSource("message",8,"b".repeat(64)));
    }
    @Test public void restoringOldBackupCannotResurrectExcludedRawOrItsDerivedMemoryPolicy()throws Exception{
        JSONObject source=store.addMessage("user","owner raw original \ud83d\ude00 ".repeat(14000),null);long id=source.getLong("id");String hash=source.getString("content_hash");
        store.upsertMemory("derived-note","owner source summary","fact",new JSONArray().put(id));ByteArrayOutputStream archive=new ByteArrayOutputStream();files.exportBackup(archive,store);
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){assertFalse(entry.getName().contains("privacy-ledger"));}}
        policy.excludeSource("message",id,hash,"retrieval_exclude","exclude this source");String fingerprint=policy.fingerprint();byte[]savedPolicy=Files.readAllBytes(ledger().toPath());
        store.addMessage("user","later source",null);files.importBackup(new ByteArrayInputStream(archive.toByteArray()),store);
        assertEquals(fingerprint,new SourcePrivacyLedger(context).fingerprint());assertArrayEquals(savedPolicy,Files.readAllBytes(ledger().toPath()));assertFalse(policy.allowedSource("message",id,hash));
        JSONObject note=store.getMemory("derived-note");assertNotNull(note);assertFalse(policy.capture().allowedSourceRefs(note.getJSONArray("sourceRefs")));
        assertEquals(hash,store.getMessage(id).getString("content_hash"));assertEquals(source.getString("raw_content_hash"),store.readOriginalText(id,0,100).getString("contentHash"));
    }
    @Test public void invalidPolicyFailsClosedRatherThanReturningEmptyExclusions()throws Exception{
        policy.excludeSource("message",1,"a".repeat(64),"retrieval_exclude","");byte[]raw=Files.readAllBytes(ledger().toPath());JSONObject changed=new JSONObject(new String(raw,StandardCharsets.UTF_8));changed.getJSONArray("exclusions").getJSONObject(0).put("hash","b".repeat(64));Files.write(ledger().toPath(),changed.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,policy::capture);assertThrows(IOException.class,()->policy.allowedSource("message",1,"a".repeat(64)));assertFalse(policy.diagnostics().getBoolean("available"));assertTrue(policy.diagnostics().getBoolean("retrievalBlocked"));
        assertFalse(SourcePrivacyLedger.allowedSource(changed,"message",1,"a".repeat(64)));
    }
    @Test public void unsupportedPhysicalDeletionAndInvalidIdentitiesNeverCreatePolicyOrTouchSources()throws Exception{
        JSONObject source=store.addMessage("user","physical original retained",null);long id=source.getLong("id");String hash=source.getString("content_hash");
        assertThrows(IllegalArgumentException.class,()->policy.excludeSource("message",id,hash,"delete_payload",""));assertThrows(IllegalArgumentException.class,()->policy.excludeSource(null,id,hash,"retrieval_exclude",""));assertThrows(IllegalArgumentException.class,()->policy.excludeSource("message",0,hash,"retrieval_exclude",""));
        assertFalse(ledger().exists());assertEquals(source.getString("text"),store.readOriginalText(id,0,100).getString("text"));assertEquals(0,policy.version());
    }
    @Test public void concurrentInstancesAppendWithoutLosingEitherOwnersExclusions()throws Exception{
        SourcePrivacyLedger other=new SourcePrivacyLedger(context);ExecutorService workers=Executors.newFixedThreadPool(2);
        try{Future<?> first=workers.submit(()->{try{for(int id=1;id<=10;id++)policy.excludeSource("message",id,"a".repeat(64),"retrieval_exclude","");}catch(IOException failure){throw new UncheckedIOException(failure);}});Future<?> second=workers.submit(()->{try{for(int id=1;id<=10;id++)other.excludeSource("device_event",id,"b".repeat(64),"retrieval_exclude","");}catch(IOException failure){throw new UncheckedIOException(failure);}});first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS);}
        finally{workers.shutdownNow();}
        SourcePrivacyLedger.Snapshot snapshot=policy.capture();assertEquals(20,snapshot.version);for(int id=1;id<=10;id++){assertFalse(snapshot.allowedSource("message",id,"a".repeat(64)));assertFalse(snapshot.allowedSource("device_event",id,"b".repeat(64)));}
    }
    @Test public void ledgerSymlinkIsRejectedWithoutReadingOrOverwritingOutsideTarget()throws Exception{
        File target=new File(context.getCacheDir(),"synthetic-privacy-target.txt");Files.write(target.toPath(),"outside bytes".getBytes(StandardCharsets.UTF_8));Files.createSymbolicLink(ledger().toPath(),target.toPath());
        try{assertThrows(IOException.class,policy::capture);assertThrows(IOException.class,()->policy.excludeSource("message",1,"a".repeat(64),"retrieval_exclude",""));assertEquals("outside bytes",new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));}finally{Files.deleteIfExists(target.toPath());}
    }
    @Test public void missingEstablishedLedgerFailsClosedEvenAfterRestartWhileUnusedPolicyIsEmpty()throws Exception{
        assertEquals(0,policy.version());assertFalse(ledger().exists());assertFalse(anchor().exists());assertNotNull(store.getPrivacyLedger());
        policy.excludeSource("message",1,"a".repeat(64),"retrieval_exclude","");assertTrue(anchor().isFile());Files.delete(ledger().toPath());
        SourcePrivacyLedger restarted=new SourcePrivacyLedger(context);assertThrows(IOException.class,restarted::capture);assertThrows(IOException.class,()->restarted.allowedSource("message",1,"a".repeat(64)));assertTrue(restarted.diagnostics().getBoolean("retrievalBlocked"));
    }
    @Test public void missingFirstAnchorBlocksRetrievalAndExplicitControlRetryCanFinishPublication()throws Exception{
        policy.excludeSource("message",3,"a".repeat(64),"retrieval_exclude","");Files.delete(anchor().toPath());assertThrows(IOException.class,policy::capture);
        JSONObject retry=policy.excludeSource("message",3,"a".repeat(64),"retrieval_exclude","");assertFalse(retry.getBoolean("changed"));assertTrue(anchor().isFile());assertFalse(policy.allowedSource("message",3,"a".repeat(64)));
    }
    @Test public void replacingPolicyWithOlderValidGenerationCannotUndoNewExclusion()throws Exception{
        policy.excludeSource("message",1,"a".repeat(64),"retrieval_exclude","");byte[]older=Files.readAllBytes(ledger().toPath());
        policy.excludeSource("device_event",2,"b".repeat(64),"retrieval_exclude","");Files.write(ledger().toPath(),older);
        assertThrows(IOException.class,policy::capture);assertThrows(IOException.class,()->new SourcePrivacyLedger(context).allowedSource("device_event",2,"b".repeat(64)));
    }
    @Test public void reusedSqliteIdWithNewDistinctSourceHashIsNotIncorrectlyExcluded()throws Exception{
        JSONObject old=store.addMessage("user","old excluded owner source",null);long id=old.getLong("id");String oldHash=old.getString("content_hash");policy.excludeSource("message",id,oldHash,"retrieval_exclude","");
        // A different database generation can reuse an AUTOINCREMENT id; policy keys retain the hash.
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);JSONObject replacement=store.addMessage("user","different source in another generation",null);
        assertEquals(id,replacement.getLong("id"));assertNotEquals(oldHash,replacement.getString("content_hash"));
        assertTrue(store.getPrivacyLedger().allowedSource("message",id,replacement.getString("content_hash")));assertFalse(store.getPrivacyLedger().allowedSource("message",id,oldHash));
    }
    @Test public void replayedCanonicalDeviceObservationRemainsExcludedAtNewSqliteId()throws Exception{
        JSONObject payload=new JSONObject().put("text","synthetic observed notification");JSONObject old=store.appendDeviceEvent("notification","synthetic.app",payload,1700000000000L,null);
        policy.excludeSource("device_event",old.getLong("id"),old.getString("content_hash"),"retrieval_exclude","");
        JSONObject replay=store.appendDeviceEvent("notification","synthetic.app",payload,1700000000000L,null),newObservation=store.appendDeviceEvent("notification","synthetic.app",payload,1700000000001L,null);
        assertNotEquals(old.getLong("id"),replay.getLong("id"));assertEquals(old.getString("content_hash"),replay.getString("content_hash"));assertFalse(policy.allowedSource("device_event",replay.getLong("id"),replay.getString("content_hash")));
        assertTrue(policy.allowedSource("device_event",old.getLong("id"),newObservation.getString("content_hash")));assertTrue(policy.allowedSource("device_event",newObservation.getLong("id"),newObservation.getString("content_hash")));
        assertTrue(policy.allowedSource("message",replay.getLong("id"),replay.getString("content_hash")));
    }
    @Test public void legacyMessageIdFallbackIsImmutableTypedAndDoesNotChangeExactHashRules()throws Exception{
        SourcePrivacyLedger.Snapshot before=policy.capture();policy.excludeSource("message",41,"a".repeat(64),"retrieval_exclude","");policy.excludeSource("device_event",42,"b".repeat(64),"retrieval_exclude","");policy.excludeSource("message",Long.MAX_VALUE,"c".repeat(64),"retrieval_exclude","");
        SourcePrivacyLedger.Snapshot frozen=policy.capture();String fingerprint=frozen.fingerprint,serialized=frozen.toJson().toString();assertTrue(frozen.hasExcludedMessageId(41));assertTrue(frozen.hasExcludedMessageId(Long.MAX_VALUE));assertFalse(frozen.hasExcludedMessageId(42));assertFalse(frozen.hasExcludedMessageId(43));assertFalse(frozen.hasExcludedMessageId(0));assertFalse(frozen.hasExcludedMessageId(-1));assertFalse(before.hasExcludedMessageId(41));
        assertFalse(frozen.allowedSource("message",41,"a".repeat(64)));assertTrue(frozen.allowedSource("message",41,"d".repeat(64)));assertTrue(frozen.allowedSource("device_event",41,"a".repeat(64)));assertEquals(fingerprint,frozen.fingerprint);assertEquals(serialized,frozen.toJson().toString());
        SourcePrivacyLedger.Snapshot roundtrip=SourcePrivacyLedger.fromSnapshot(frozen.toJson());assertTrue(roundtrip.hasExcludedMessageId(41));assertEquals(fingerprint,roundtrip.fingerprint);policy.excludeSource("message",43,"e".repeat(64),"retrieval_exclude","");assertFalse(frozen.hasExcludedMessageId(43));assertTrue(policy.capture().hasExcludedMessageId(43));
    }
    private File ledger(){return new File(context.getFilesDir(),"privacy-ledger.json");}
    private File anchor(){return new File(context.getFilesDir(),"privacy-ledger.anchor.json");}
    private static JSONObject ref(String type,long id,String hash)throws Exception{return new JSONObject().put("type",type).put("id",id).put("hash",hash);}
    private static void clear(File file)throws IOException{if(Files.isSymbolicLink(file.toPath())){Files.deleteIfExists(file.toPath());return;}if(!file.exists())return;if(file.isDirectory()){File[]children=file.listFiles();if(children==null)throw new IOException("Cannot inspect fixtures");for(File child:children)clear(child);}Files.delete(file.toPath());}
}
