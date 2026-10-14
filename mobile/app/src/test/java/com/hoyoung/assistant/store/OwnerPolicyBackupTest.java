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
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerPolicyBackupTest {
    private Context context;private HoyoungStore store;private InternalFiles files;
    @Before public void setup()throws Exception{context=ApplicationProvider.getApplicationContext();reset();}
    @After public void cleanup()throws Exception{if(store!=null)store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());context.getSharedPreferences("owner-policy-folder",0).edit().clear().commit();}
    private void reset()throws Exception{if(store!=null)store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());context.getSharedPreferences("owner-policy-folder",0).edit().clear().commit();files=new InternalFiles(context);store=new HoyoungStore(context);}
    private byte[] backup()throws Exception{ByteArrayOutputStream output=new ByteArrayOutputStream();files.exportBackup(output,store);return output.toByteArray();}
    private void exclude(JSONObject source)throws Exception{store.getPrivacyLedger().excludeSource("message",source.getLong("id"),source.getString("content_hash"),"retrieval_exclude","owner request");}
    private void delete(JSONObject source)throws Exception{assertEquals("completed_managed_local",new SourcePayloadDeletion(context,store).deleteSource("message",source.getLong("id"),source.getString("content_hash"),"owner request").getString("status"));}
    private boolean allowed(JSONObject source)throws Exception{return store.getPrivacyLedger().allowedSource("message",source.getLong("id"),source.getString("content_hash"));}

    @Test public void coldReinstallRestoresExclusionsCorrectedMemoryAndUntouchedOriginals()throws Exception{
        JSONObject hidden=store.addMessage("user","excluded owner address",null),kept=store.addMessage("user","allowed personal preference",null);
        store.upsertMemory("preference","old value","fact",new JSONArray().put(kept.getLong("id")));
        store.upsertMemory("preference","corrected value","fact",new JSONArray().put(kept.getLong("id")));
        exclude(hidden);byte[] archive=backup();String expected=store.getPrivacyLedger().fingerprint();
        reset();files.importBackup(new ByteArrayInputStream(archive),store);OwnerPolicyBackup.reconcile(context,store);
        assertEquals(expected,store.getPrivacyLedger().fingerprint());assertFalse(allowed(hidden));assertTrue(allowed(kept));
        assertEquals(hidden.getString("text"),store.getMessage(hidden.getLong("id")).getString("text"));
        assertEquals("corrected value",store.getMemory("preference").getString("content"));
    }
    @Test public void latestPortablePolicyThenOlderZipCannotResurrectDeletedRawOrDerivedMemory()throws Exception{
        JSONObject removed=store.addMessage("user","PRIVATE deleted long original ".repeat(14000),null);
        store.upsertMemory("private-note","derived secret","fact",new JSONArray().put(removed.getLong("id")));
        JSONObject kept=store.addMessage("user","independent surviving source",null);byte[] old=backup();delete(removed);
        JSONObject latest=OwnerPolicyBackup.capture(context,store);reset();OwnerPolicyBackup.merge(context,store,latest);
        assertEquals(0,OwnerPolicyBackup.reconcile(context,store).getInt("checked"));
        assertFalse(new File(context.getFilesDir(),"source-deletions").exists());
        files.importBackup(new ByteArrayInputStream(old),store);assertFalse(allowed(removed));
        assertEquals(1,OwnerPolicyBackup.reconcile(context,store).getInt("checked"));
        assertNull(store.getMessage(removed.getLong("id")));assertNull(store.getMemory("private-note"));
        assertFalse(new File(context.getFilesDir(),"originals/"+removed.getString("raw_file_id")).exists());
        assertEquals(kept.getString("text"),store.getMessage(kept.getLong("id")).getString("text"));assertTrue(allowed(kept));
        assertEquals(0,OwnerPolicyBackup.reconcile(context,store).getInt("checked"));
    }
    @Test public void newerLocalControlsAreUnionedWithOldBackupWithoutFingerprintRollback()throws Exception{
        JSONObject one=store.addMessage("user","first source",null),two=store.addMessage("user","second source",null);
        exclude(one);byte[] older=backup();exclude(two);String latest=store.getPrivacyLedger().fingerprint();
        files.importBackup(new ByteArrayInputStream(older),store);
        assertEquals(latest,store.getPrivacyLedger().fingerprint());assertFalse(allowed(one));assertFalse(allowed(two));assertEquals(2,store.getPrivacyLedger().capture().exclusionCount);
    }
    @Test public void reinstallSameIdWithDifferentCanonicalHashPreservesNewGeneration()throws Exception{
        JSONObject old=store.addMessage("user","old deleted generation",null);delete(old);JSONObject controls=OwnerPolicyBackup.capture(context,store);
        reset();JSONObject fresh=store.addMessage("user","new distinct generation",null);assertEquals(old.getLong("id"),fresh.getLong("id"));assertNotEquals(old.getString("content_hash"),fresh.getString("content_hash"));
        OwnerPolicyBackup.merge(context,store,controls);JSONObject result=OwnerPolicyBackup.reconcile(context,store);
        assertEquals(1,result.getInt("distinctGenerationsPreserved"));assertTrue(allowed(fresh));assertEquals(fresh.getString("text"),store.getMessage(fresh.getLong("id")).getString("text"));
    }
    @Test public void duplicateMergeDoesNotRewritePrivacyOrChangeRankingInputs()throws Exception{
        JSONObject source=store.addMessage("user","durable source",null);exclude(source);JSONObject controls=OwnerPolicyBackup.capture(context,store);
        OwnerPolicyBackup.merge(context,store,controls);byte[] before=Files.readAllBytes(new File(context.getFilesDir(),"privacy-ledger.json").toPath());String privacy=store.getPrivacyLedger().fingerprint();
        for(int i=0;i<5;i++)OwnerPolicyBackup.merge(context,store,controls);
        assertEquals(privacy,store.getPrivacyLedger().fingerprint());assertArrayEquals(before,Files.readAllBytes(new File(context.getFilesDir(),"privacy-ledger.json").toPath()));
        assertEquals(source.getString("content_hash"),store.getMessage(source.getLong("id")).getString("content_hash"));
    }
    @Test public void controlTamperingOrTrailingDataFailsBeforeMutatingLocalState()throws Exception{
        JSONObject source=store.addMessage("user","intact local source",null);JSONObject controls=OwnerPolicyBackup.capture(context,store);String before=store.getPrivacyLedger().fingerprint();
        controls.put("deletions",new JSONArray().put(new JSONObject().put("type","message").put("id",source.getLong("id")).put("hash",source.getString("content_hash"))));
        assertThrows(IOException.class,()->OwnerPolicyBackup.merge(context,store,controls));
        assertEquals(before,store.getPrivacyLedger().fingerprint());assertNotNull(store.getMessage(source.getLong("id")));
        JSONObject valid=OwnerPolicyBackup.capture(context,store);
        assertThrows(IOException.class,()->OwnerPolicyBackup.read(new ByteArrayInputStream((valid.toString()+" {} ").getBytes(StandardCharsets.UTF_8))));
    }
    @Test public void versionThreeArchiveCarriesChecksummedControlsButNoInstallationKeys()throws Exception{
        JSONObject source=store.addMessage("user","secret source body",null);delete(source);byte[] archive=backup();boolean controls=false;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
            assertFalse(entry.getName().contains("source-deletions.key"));assertFalse(entry.getName().contains("privacy-ledger.anchor"));
            if(entry.getName().equals(OwnerPolicyBackup.ARCHIVE_PATH)){JSONObject policy=OwnerPolicyBackup.read(zip);controls=true;assertEquals(1,policy.getJSONArray("deletions").length());assertFalse(policy.toString().contains("secret source body"));assertFalse(policy.toString().contains("originals/"));}
            if(entry.getName().equals("manifest.json")){JSONObject manifest=new JSONObject(new String(zip.readAllBytes(),StandardCharsets.UTF_8));assertEquals(3,manifest.getInt("version"));}
        }}assertTrue(controls);
    }
    @Test public void corruptedArchiveControlCannotReplaceExistingData()throws Exception{
        JSONObject archived=store.addMessage("user","archived state",null);exclude(archived);byte[] archive=backup();JSONObject latest=store.addMessage("user","new live data",null);String privacy=store.getPrivacyLedger().fingerprint();
        ByteArrayOutputStream changed=new ByteArrayOutputStream();
        try(ZipInputStream input=new ZipInputStream(new ByteArrayInputStream(archive));ZipOutputStream output=new ZipOutputStream(changed)){
            ZipEntry entry;while((entry=input.getNextEntry())!=null){byte[] bytes=input.readAllBytes();if(entry.getName().equals(OwnerPolicyBackup.ARCHIVE_PATH))bytes="{}".getBytes(StandardCharsets.UTF_8);output.putNextEntry(new ZipEntry(entry.getName()));output.write(bytes);output.closeEntry();}
        }
        assertThrows(IOException.class,()->files.importBackup(new ByteArrayInputStream(changed.toByteArray()),store));
        assertEquals(latest.getString("text"),store.getMessage(latest.getLong("id")).getString("text"));assertEquals(privacy,store.getPrivacyLedger().fingerprint());
    }
    @Test public void previousVersionTwoZipRemainsReadableAndLatestPortablePolicyStillWins()throws Exception{
        JSONObject source=store.addMessage("user","old v2 archive source",null);byte[] generated=backup();exclude(source);JSONObject latest=OwnerPolicyBackup.capture(context,store);
        java.util.Map<String,byte[]> parts=new java.util.LinkedHashMap<>();
        try(ZipInputStream input=new ZipInputStream(new ByteArrayInputStream(generated))){ZipEntry entry;while((entry=input.getNextEntry())!=null)parts.put(entry.getName(),input.readAllBytes());}
        parts.remove(OwnerPolicyBackup.ARCHIVE_PATH);StringBuilder index=new StringBuilder();
        for(String line:new String(parts.get("checksums.ndjson"),StandardCharsets.UTF_8).split("\n"))if(!line.isEmpty()&&!new JSONObject(line).getString("path").equals(OwnerPolicyBackup.ARCHIVE_PATH))index.append(line).append('\n');
        byte[] checks=index.toString().getBytes(StandardCharsets.UTF_8);parts.put("checksums.ndjson",checks);JSONObject manifest=new JSONObject(new String(parts.get("manifest.json"),StandardCharsets.UTF_8));
        manifest.put("version",2).put("entryCount",manifest.getInt("entryCount")-1);manifest.getJSONObject("checksumIndex").put("bytes",checks.length).put("sha256",OriginalTextFiles.hex(OriginalTextFiles.digest().digest(checks)));parts.put("manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream archived=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(archived)){for(java.util.Map.Entry<String,byte[]> part:parts.entrySet()){zip.putNextEntry(new ZipEntry(part.getKey()));zip.write(part.getValue());zip.closeEntry();}}
        reset();OwnerPolicyBackup.merge(context,store,latest);files.importBackup(new ByteArrayInputStream(archived.toByteArray()),store);
        assertNotNull(store.getMessage(source.getLong("id")));assertFalse(allowed(source));
    }
    @Test public void missingEstablishedPortableDeletionRegistryFailsClosed()throws Exception{
        JSONObject source=store.addMessage("user","removed policy owner",null);delete(source);JSONObject controls=OwnerPolicyBackup.capture(context,store);
        reset();OwnerPolicyBackup.merge(context,store,controls);Files.delete(new File(context.getFilesDir(),"owner-policy-portable.json").toPath());
        assertThrows(IOException.class,()->OwnerPolicyBackup.reconcile(context,store));assertFalse(allowed(source));
    }
    @Test public void largePolicyUnionIsIdempotentAndDoesNotTouchOriginals()throws Exception{
        Context other=new android.content.ContextWrapper(context){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){File path=new File(context.getFilesDir(),"other-policy");path.mkdirs();return path;}};
        SourcePrivacyLedger remote=new SourcePrivacyLedger(other);
        for(int i=1;i<=128;i++)remote.excludeSource("message",i,String.format("%064x",i),"retrieval_exclude","");
        JSONObject source=store.addMessage("user","unchanged source payload",null);SourcePrivacyLedger ledger=store.getPrivacyLedger();
        ledger.mergeSnapshot(remote.snapshot());String fingerprint=ledger.fingerprint();ledger.mergeSnapshot(remote.snapshot());
        assertEquals(128,ledger.capture().exclusionCount);assertEquals(fingerprint,ledger.fingerprint());assertEquals(source.getString("text"),store.getMessage(source.getLong("id")).getString("text"));assertTrue(allowed(source));
    }
    private static void clear(File directory)throws IOException{if(!directory.exists())return;File[] children=directory.listFiles();if(children!=null)for(File child:children){if(child.isDirectory())clear(child);if(!child.delete())throw new IOException("fixture cleanup failed");}}
}
