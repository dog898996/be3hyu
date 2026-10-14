package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ExternalOwnerPolicyFolderTest {
    private static final Uri TREE=Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FHoyoung");
    private Context context;private HoyoungStore store;private InternalFiles files;private FileTree external;private ExternalOwnerPolicyFolder folder;
    @Before public void setup()throws Exception{context=ApplicationProvider.getApplicationContext();resetPrivate();external=new FileTree(Files.createTempDirectory("hoyoung-shared-owner-").toFile());folder=new ExternalOwnerPolicyFolder(context,uri->external);selectFixture();}
    @After public void cleanup()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());context.getSharedPreferences("owner-policy-folder",0).edit().clear().commit();clear(external.root);external.root.delete();}
    private void resetPrivate()throws Exception{if(store!=null)store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());context.getSharedPreferences("owner-policy-folder",0).edit().clear().commit();files=new InternalFiles(context);store=new HoyoungStore(context);}
    private void selectFixture(){context.getSharedPreferences("owner-policy-folder",0).edit().putString("tree",TREE.toString()).commit();folder.markPending();}
    private JSONObject exclude(String text)throws Exception{JSONObject source=store.addMessage("user",text,null);store.getPrivacyLedger().excludeSource("message",source.getLong("id"),source.getString("content_hash"),"retrieval_exclude","owner UI");folder.markPending();return source;}
    private void sync()throws Exception{long generation=folder.requestedGeneration();folder.sync(OwnerPolicyBackup.capture(context,store),generation);}
    private long completedPolicies(){return Arrays.stream(Objects.requireNonNull(external.root.list())).filter(name->name.startsWith("hoyoung-owner-policy-")).count();}
    @Test public void memoryZipAndLatestPolicyShareAnExternalFolderAndSurvivePrivateDataClear()throws Exception{
        JSONObject source=exclude("excluded but retained source");sync();String name=folder.exportMemoryBackup(files,store);File zip=new File(external.root,name);assertTrue(zip.isFile());
        boolean policy=false;try(ZipInputStream input=new ZipInputStream(new FileInputStream(zip))){ZipEntry entry;while((entry=input.getNextEntry())!=null)if(entry.getName().equals(OwnerPolicyBackup.ARCHIVE_PATH)){OwnerPolicyBackup.read(input);policy=true;}}assertTrue(policy);
        resetPrivate();folder=new ExternalOwnerPolicyFolder(context,uri->external);assertFalse(folder.connected());assertTrue(zip.isFile());
        JSONObject portable=folder.readFromFolder(TREE);OwnerPolicyBackup.merge(context,store,portable);
        try(InputStream input=new FileInputStream(zip)){files.importBackup(input,store);}OwnerPolicyBackup.reconcile(context,store);
        assertFalse(store.getPrivacyLedger().allowedSource("message",source.getLong("id"),source.getString("content_hash")));assertNotNull(store.getMessage(source.getLong("id")));
    }
    @Test public void latestSidecarBeforeOldZipReplaysDeletionAfterReinstall()throws Exception{
        JSONObject removed=store.addMessage("user","old private source ".repeat(18000),null),kept=store.addMessage("user","independent kept source",null);sync();
        String oldZip=folder.exportMemoryBackup(files,store);
        assertEquals("completed_managed_local",new SourcePayloadDeletion(context,store).deleteSource("message",removed.getLong("id"),removed.getString("content_hash"),"owner UI").getString("status"));folder.markPending();sync();
        resetPrivate();folder=new ExternalOwnerPolicyFolder(context,uri->external);OwnerPolicyBackup.merge(context,store,folder.readFromFolder(TREE));
        try(InputStream input=new FileInputStream(new File(external.root,oldZip))){files.importBackup(input,store);}OwnerPolicyBackup.reconcile(context,store);
        assertNull(store.getMessage(removed.getLong("id")));assertEquals(kept.getString("text"),store.getMessage(kept.getLong("id")).getString("text"));
        assertFalse(store.getPrivacyLedger().allowedSource("message",removed.getLong("id"),removed.getString("content_hash")));
    }
    @Test public void repeatedControlChangesRetainAtMostTwoCompleteMetadataSnapshots()throws Exception{
        for(int i=0;i<6;i++){exclude("owner source "+i);sync();}
        assertEquals(2,completedPolicies());assertFalse(folder.pending());assertEquals(6,SourcePrivacyLedger.fromSnapshot(folder.readConnectedFolder().getJSONObject("privacy")).exclusionCount);
    }
    @Test public void unchangedPolicyRequiresNoNewDocumentWrite()throws Exception{
        exclude("only source");sync();int created=external.created,writes=external.writes;folder.markPending();sync();
        assertEquals(created,external.created);assertEquals(writes,external.writes);assertFalse(folder.pending());
    }
    @Test public void interruptedMetadataWriteLeavesPreviousSnapshotAndLatestLocalExclusionIntact()throws Exception{
        exclude("first policy");sync();JSONObject latest=exclude("second policy");external.failWrite=true;
        assertThrows(IOException.class,this::sync);assertTrue(folder.pending());assertEquals(1,completedPolicies());
        assertEquals(1,SourcePrivacyLedger.fromSnapshot(folder.readConnectedFolder().getJSONObject("privacy")).exclusionCount);
        assertFalse(store.getPrivacyLedger().allowedSource("message",latest.getLong("id"),latest.getString("content_hash")));
        external.failWrite=false;sync();assertEquals(2,SourcePrivacyLedger.fromSnapshot(folder.readConnectedFolder().getJSONObject("privacy")).exclusionCount);assertFalse(folder.pending());
    }
    @Test public void providerReadBackMismatchIsNeverMarkedAsPublished()throws Exception{
        exclude("first");sync();exclude("second");external.corruptWrite=true;
        assertThrows(IOException.class,this::sync);assertTrue(folder.pending());assertEquals(1,completedPolicies());
    }
    @Test public void completedCorruptionFailsClosedInsteadOfFallingBackToOlderPolicy()throws Exception{
        exclude("first");sync();exclude("second");sync();File[] records=external.root.listFiles(file->file.getName().startsWith("hoyoung-owner-policy-"));Arrays.sort(records,Comparator.comparing(File::getName));Files.write(records[records.length-1].toPath(),"broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,folder::readConnectedFolder);assertEquals(2,store.getPrivacyLedger().capture().exclusionCount);
    }
    @Test public void establishedEmptyFolderIsNotSilentlyReplacedWithAnEmptyPolicy()throws Exception{
        exclude("first");sync();for(File file:Objects.requireNonNull(external.root.listFiles()))file.delete();
        assertThrows(IOException.class,folder::readConnectedFolder);folder.markPending();assertThrows(IOException.class,this::sync);assertTrue(folder.pending());
    }
    @Test public void newControlDuringOldMirrorReadBackKeepsRetryPending()throws Exception{
        JSONObject first=exclude("first");long cut=folder.requestedGeneration();JSONObject captured=OwnerPolicyBackup.capture(context,store);final JSONObject[] second={null};
        external.onRename=()->{try{second[0]=exclude("second while older mirror is writing");}catch(Exception failure){throw new RuntimeException(failure);}};
        folder.sync(captured,cut);assertTrue(folder.pending());assertFalse(store.getPrivacyLedger().allowedSource("message",second[0].getLong("id"),second[0].getString("content_hash")));
        external.onRename=null;sync();assertFalse(folder.pending());assertEquals(2,SourcePrivacyLedger.fromSnapshot(folder.readConnectedFolder().getJSONObject("privacy")).exclusionCount);
        assertFalse(store.getPrivacyLedger().allowedSource("message",first.getLong("id"),first.getString("content_hash")));
    }
    @Test public void statusPollingAndNormalPendingChecksNeverTouchExternalProvider()throws Exception{
        int calls=external.calls;for(int i=0;i<100;i++){folder.status();folder.pending();folder.connected();folder.requestedGeneration();}
        assertEquals(calls,external.calls);
    }
    @Test public void appSpecificAndroidDataOrObbFolderIsRejected()throws Exception{
        int calls=external.calls;
        for(String path:new String[]{"primary%3AAndroid%2Fdata","primary%3AAndroid%2Fdata%2Fcom.hoyoung.assistant","primary%3AAndroid%2Fobb"})
            assertThrows(IOException.class,()->folder.readFromFolder(Uri.parse("content://com.android.externalstorage.documents/tree/"+path)));
        assertEquals(calls,external.calls);
    }
    @Test public void renameFailurePreservesOldPublishedSnapshotAndPendingState()throws Exception{
        exclude("first");sync();exclude("second");external.failRename=true;
        assertThrows(IOException.class,this::sync);assertEquals(1,completedPolicies());assertTrue(folder.pending());
    }
    @Test public void unavailableFolderReportsPendingWithoutChangingLocalAccuracyInputs()throws Exception{
        JSONObject source=exclude("source remains protected");String before=store.getPrivacyLedger().fingerprint();external.failList=true;
        IOException failure=assertThrows(IOException.class,this::sync);folder.failure(failure);
        assertTrue(folder.pending());assertFalse(folder.status().getString("lastError").isEmpty());assertEquals(before,store.getPrivacyLedger().fingerprint());assertEquals(source.getString("text"),store.getMessage(source.getLong("id")).getString("text"));
    }
    private static final class FileTree implements ExternalOwnerPolicyFolder.TreeAccess {
        final File root;int calls,created,writes;boolean failWrite,corruptWrite,failRename,failList;Runnable onRename;
        FileTree(File root){this.root=root;}
        public List<ExternalOwnerPolicyFolder.Document> list()throws IOException{calls++;if(failList)throw new IOException("synthetic revoked access");List<ExternalOwnerPolicyFolder.Document> rows=new ArrayList<>();for(File file:Objects.requireNonNull(root.listFiles()))rows.add(new ExternalOwnerPolicyFolder.Document(Uri.fromFile(file),file.getName()));return rows;}
        public Uri create(String mime,String name)throws IOException{calls++;created++;File file=new File(root,name);if(!file.createNewFile())throw new IOException("fixture duplicate");return Uri.fromFile(file);}
        public InputStream read(Uri uri)throws IOException{calls++;return new FileInputStream(new File(uri.getPath()));}
        public OutputStream write(Uri uri)throws IOException{calls++;writes++;FileOutputStream output=new FileOutputStream(new File(uri.getPath()));return new FilterOutputStream(output){@Override public void write(byte[] bytes,int offset,int length)throws IOException{if(failWrite){out.write(bytes,offset,Math.min(length,8));throw new IOException("synthetic mid-write failure");}if(corruptWrite){out.write("changed".getBytes(StandardCharsets.UTF_8));return;}out.write(bytes,offset,length);}};}
        public Uri rename(Uri uri,String name)throws IOException{calls++;if(failRename)return null;File dest=new File(root,name);if(!new File(uri.getPath()).renameTo(dest))throw new IOException("fixture rename failure");if(onRename!=null)onRename.run();return Uri.fromFile(dest);}
        public void delete(Uri uri)throws IOException{calls++;if(!new File(uri.getPath()).delete())throw new IOException("fixture cleanup failed");}
    }
    private static void clear(File directory)throws IOException{if(!directory.exists())return;for(File child:Objects.requireNonNull(directory.listFiles())){if(child.isDirectory())clear(child);if(!child.delete())throw new IOException("fixture cleanup failed");}}
}
