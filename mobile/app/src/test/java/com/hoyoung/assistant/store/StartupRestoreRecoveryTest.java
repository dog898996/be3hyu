package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import static org.junit.Assert.*;

/** Actual SQLite commit tokens and exact private trees, with no application boot/collector side effects. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class StartupRestoreRecoveryTest {
    private Context context;private HoyoungStore store;private InternalFiles files;
    @Before public void setup()throws Exception{
        context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());
        files=new InternalFiles(context);store=new HoyoungStore(context);files.writeFile("keep.txt","current working bytes");
    }
    @After public void cleanup()throws Exception{if(store!=null)store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());}

    @Test public void truncatedRestoreJournalReturnsBlockedDiagnosticsWithoutRewritingAnyEvidence()throws Exception{
        byte[]raw="{\"token\":\"truncated".getBytes(StandardCharsets.UTF_8);Files.write(journal().toPath(),raw);closeStore();
        InternalFiles blocked=new InternalFiles(context);JSONObject state=blocked.startupDiagnostics();
        assertTrue(blocked.startupBlocked());assertTrue(blocked.restoreRecoveryPending());assertFalse(state.getBoolean("ready"));assertEquals("restore_recovery_blocked",state.getString("status"));
        assertEquals("Invalid restore journal",state.getString("failure"));assertFalse(state.getBoolean("automaticReplay"));
        assertArrayEquals(raw,Files.readAllBytes(journal().toPath()));assertEquals("current working bytes",text(new File(root(),"projects/keep.txt")));
        assertThrows(InternalFiles.RestoreRecoveryPendingException.class,()->new HoyoungStore(context));
    }

    @Test public void wrongConstructionOrderNeverCreatesAnEmptyMissingOriginalsOrOpensDatabase()throws Exception{
        JSONObject source=store.addMessage("user","exact original survives restart ".repeat(12000),null);
        String rawId=source.getString("raw_file_id"),token=UUID.randomUUID().toString();store.putSetting(HoyoungStore.FILE_RESTORE_TOKEN_KEY,token);
        File previous=previous("originals"),stage=staging();Files.move(new File(root(),"originals").toPath(),previous.toPath());
        byte[]original=Files.readAllBytes(new File(previous,rawId).toPath());writeJournal(token,stage,new JSONArray().put(move("originals",previous)));closeStore();
        File database=context.getDatabasePath(HoyoungStore.DATABASE_NAME);String databaseHash=sha256(database);byte[]journal=Files.readAllBytes(journal().toPath());
        assertThrows(InternalFiles.RestoreRecoveryPendingException.class,()->new HoyoungStore(context));
        assertThrows(InternalFiles.RestoreRecoveryPendingException.class,()->new OriginalTextFiles(root()));
        assertFalse(new File(root(),"originals").exists());assertEquals(databaseHash,sha256(database));assertArrayEquals(journal,Files.readAllBytes(journal().toPath()));
        assertArrayEquals(original,Files.readAllBytes(new File(previous,rawId).toPath()));
        InternalFiles blocked=new InternalFiles(context);assertTrue(blocked.startupBlocked());assertFalse(new File(root(),"originals").exists());assertTrue(stage.isDirectory());assertTrue(previous.isDirectory());
    }

    @Test public void committedGenerationPrevalidatesEveryNamespaceBeforeDeletingFirstPreviousTree()throws Exception{
        JSONObject source=store.addMessage("user","preserved original raw evidence ".repeat(12000),null);String rawId=source.getString("raw_file_id");
        String token=UUID.randomUUID().toString();store.putSetting(HoyoungStore.FILE_RESTORE_TOKEN_KEY,token);
        File oldProjects=previous("projects"),oldOriginals=previous("originals"),oldJournal=previous("device-journal"),stage=staging();
        assertTrue(oldProjects.mkdir());Files.write(new File(oldProjects,"old.txt").toPath(),"old projects evidence".getBytes(StandardCharsets.UTF_8));
        Files.move(new File(root(),"originals").toPath(),oldOriginals.toPath());byte[]raw=Files.readAllBytes(new File(oldOriginals,rawId).toPath());
        assertTrue(new File(root(),"device-journal").mkdir());assertTrue(oldJournal.mkdir());Files.write(new File(oldJournal,"raw.json").toPath(),new byte[]{4,5,6});
        writeJournal(token,stage,new JSONArray().put(move("projects",oldProjects)).put(move("originals",oldOriginals)).put(move("device-journal",oldJournal)));closeStore();
        byte[]journal=Files.readAllBytes(journal().toPath());InternalFiles blocked=new InternalFiles(context);
        assertTrue(blocked.startupBlocked());assertEquals("Committed restore is missing a namespace",blocked.startupDiagnostics().getString("failure"));
        assertEquals("old projects evidence",text(new File(oldProjects,"old.txt")));assertArrayEquals(raw,Files.readAllBytes(new File(oldOriginals,rawId).toPath()));
        assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(new File(oldJournal,"raw.json").toPath()));assertArrayEquals(journal,Files.readAllBytes(journal().toPath()));
        assertFalse(new File(root(),"originals").exists());assertTrue(stage.isDirectory());assertEquals("current working bytes",text(new File(root(),"projects/keep.txt")));
    }

    @Test public void rollbackPrevalidatesLaterMissingNamespaceBeforeReplacingEarlierCurrentTree()throws Exception{
        File oldProjects=previous("projects"),missingOriginals=previous("originals"),stage=staging();assertTrue(oldProjects.mkdir());Files.write(new File(oldProjects,"old.txt").toPath(),"rollback original".getBytes(StandardCharsets.UTF_8));
        clear(new File(root(),"originals"));writeJournal(UUID.randomUUID().toString(),stage,new JSONArray().put(move("projects",oldProjects)).put(move("originals",missingOriginals)));closeStore();
        byte[]journal=Files.readAllBytes(journal().toPath());InternalFiles blocked=new InternalFiles(context);
        assertTrue(blocked.startupBlocked());assertEquals("Original namespace is missing during rollback",blocked.startupDiagnostics().getString("failure"));
        assertEquals("current working bytes",text(new File(root(),"projects/keep.txt")));assertEquals("rollback original",text(new File(oldProjects,"old.txt")));
        assertArrayEquals(journal,Files.readAllBytes(journal().toPath()));assertFalse(new File(root(),"originals").exists());assertTrue(stage.isDirectory());
    }

    @Test public void orphanCleanupIoFailureIsDeferredWhileConsistentCurrentTreeAndStoreRemainUsable()throws Exception{
        File stage=staging();Files.write(new File(stage,"orphan.txt").toPath(),new byte[]{1,2,3});File inaccessible=new File(stage.getPath()){@Override public File[]listFiles(){return null;}};
        File rootView=new File(root().getPath()){@Override public File[]listFiles(){return new File[]{inaccessible};}};
        Context wrapper=new ContextWrapper(context){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return rootView;}};
        InternalFiles ready=new InternalFiles(wrapper);JSONObject state=ready.startupDiagnostics();assertFalse(ready.startupBlocked());assertFalse(ready.restoreRecoveryPending());
        assertTrue(state.getBoolean("ready"));assertEquals("ready_cleanup_deferred",state.getString("status"));assertEquals("Restore directory cannot be listed",state.getString("warning"));
        assertEquals("current working bytes",ready.readText("keep.txt",100));store.addMessage("user","still usable after cleanup warning",null);assertEquals(1,store.messageCount());assertTrue(new File(stage,"orphan.txt").isFile());
    }

    @Test public void correctedCommittedNamespaceCanRetryWithoutInventingOrDiscardingOriginalBytes()throws Exception{
        JSONObject source=store.addMessage("user","immutable restored source ".repeat(12000),null);long id=source.getLong("id");String rawId=source.getString("raw_file_id"),token=UUID.randomUUID().toString();store.putSetting(HoyoungStore.FILE_RESTORE_TOKEN_KEY,token);
        File previous=previous("originals"),stage=staging();Files.move(new File(root(),"originals").toPath(),previous.toPath());writeJournal(token,stage,new JSONArray().put(move("originals",previous)));closeStore();
        InternalFiles blocked=new InternalFiles(context);assertTrue(blocked.startupBlocked());assertTrue(journal().isFile());
        // Explicit fixture correction recreates the actual committed generation, not an empty folder.
        Files.move(previous.toPath(),new File(root(),"originals").toPath());InternalFiles retried=new InternalFiles(context);assertFalse(retried.startupBlocked());assertFalse(retried.restoreRecoveryPending());
        assertFalse(stage.exists());store=new HoyoungStore(context);assertEquals(source.getString("raw_content_hash"),store.readOriginalText(id,0,100).getString("contentHash"));
        assertTrue(new File(root(),"originals/"+rawId).isFile());assertEquals("immutable restored source ",store.readOriginalText(id,0,26).getString("text"));
    }

    @Test public void malformedLaterMovePreservesEarlierRecoverableTree()throws Exception{
        String token=UUID.randomUUID().toString();store.putSetting(HoyoungStore.FILE_RESTORE_TOKEN_KEY,token);File previous=previous("projects"),stage=staging();assertTrue(previous.mkdir());Files.write(new File(previous,"keep.txt").toPath(),"previous retained".getBytes(StandardCharsets.UTF_8));
        writeJournal(token,stage,new JSONArray().put(move("projects",previous)).put(new JSONObject().put("current","outside").put("previous","invalid")));closeStore();
        InternalFiles blocked=new InternalFiles(context);assertTrue(blocked.startupBlocked());assertEquals("Invalid restore namespace",blocked.startupDiagnostics().getString("failure"));assertEquals("previous retained",text(new File(previous,"keep.txt")));assertTrue(stage.isDirectory());assertTrue(journal().isFile());
    }

    @Test public void diagnosticOnlyObjectRejectsWorkingToolAndBackupWritesBeforeCreatingAnything()throws Exception{
        store.addMessage("user","retained before blocked writes",null);File previous=previous("projects");Files.move(new File(root(),"projects").toPath(),previous.toPath());
        byte[]raw="{\"token\":\"truncated".getBytes(StandardCharsets.UTF_8);Files.write(journal().toPath(),raw);InternalFiles blocked=new InternalFiles(context);assertTrue(blocked.startupBlocked());
        InputStream unreadable=new InputStream(){@Override public int read(){throw new AssertionError("Blocked source must never be read");}};
        ByteArrayOutputStream destination=new ByteArrayOutputStream();
        assertThrows(IOException.class,()->blocked.writeFile("new/path.txt","forbidden"));assertThrows(IOException.class,()->blocked.writeFile("new/path.txt",unreadable));
        assertThrows(IOException.class,()->blocked.writeFile("new/path.txt",unreadable,10));assertThrows(IOException.class,()->blocked.archiveToolResult("root","action","{}"));
        assertThrows(IOException.class,()->blocked.importBackup(unreadable,store));assertThrows(IOException.class,()->blocked.importBackup(unreadable,store,BackupPolicy.fromStore(store)));
        assertThrows(IOException.class,()->blocked.exportBackup(destination,store));assertThrows(IOException.class,()->blocked.exportBackup(destination,store,BackupPolicy.fromStore(store)));
        assertEquals(0,destination.size());assertFalse(new File(root(),"projects").exists());assertEquals("current working bytes",text(new File(previous,"keep.txt")));
        assertArrayEquals(raw,Files.readAllBytes(journal().toPath()));assertEquals(1,store.messageCount());
        for(File child:root().listFiles())assertFalse(child.getName(),child.getName().startsWith(".backup-")||child.getName().startsWith(".restore-" )&&!child.getName().equals(".restore-transaction.json"));
    }

    @Test public void diagnosticOnlyObjectRejectsUriAndMutableAttachmentCopiesBeforeArchivingBytes()throws Exception{
        JSONObject working=files.writeFile("candidate.bin","owner attachment original bytes");JSONObject owner=store.addMessage("user","retained attachment source ".repeat(12000),null);
        String rawId=owner.getString("raw_file_id"),token=UUID.randomUUID().toString();store.putSetting(HoyoungStore.FILE_RESTORE_TOKEN_KEY,token);
        File previous=previous("originals"),stage=staging();Files.move(new File(root(),"originals").toPath(),previous.toPath());byte[]original=Files.readAllBytes(new File(previous,rawId).toPath());
        writeJournal(token,stage,new JSONArray().put(move("originals",previous)));byte[]journal=Files.readAllBytes(journal().toPath());InternalFiles blocked=new InternalFiles(context);assertTrue(blocked.startupBlocked());
        File candidate=new File(root(),"projects/candidate.bin");String candidateHash=sha256(candidate);
        assertThrows(IOException.class,()->blocked.saveFromUri(Uri.fromFile(candidate),"candidate.bin"));
        assertThrows(IOException.class,()->blocked.freezeAttachments(new JSONArray().put(working)));assertThrows(IOException.class,()->blocked.freezeAttachments(new JSONArray()));
        assertFalse(new File(root(),"projects/.attachment-originals").exists());assertFalse(new File(root(),"originals").exists());
        assertEquals(candidateHash,sha256(candidate));assertArrayEquals(original,Files.readAllBytes(new File(previous,rawId).toPath()));assertArrayEquals(journal,Files.readAllBytes(journal().toPath()));assertTrue(stage.isDirectory());
    }

    private File root(){return context.getFilesDir();}private File journal(){return new File(root(),".restore-transaction.json");}
    private File previous(String namespace){return new File(root(),".previous-"+namespace+"-"+UUID.randomUUID());}
    private File staging()throws IOException{File path=new File(root(),".restore-"+UUID.randomUUID());if(!path.mkdir())throw new IOException("Cannot create restore fixture");return path;}
    private static JSONObject move(String namespace,File previous)throws Exception{return new JSONObject().put("current",namespace).put("previous",previous.getName());}
    private void writeJournal(String token,File stage,JSONArray moves)throws Exception{JSONObject record=new JSONObject().put("token",token).put("staging",stage.getName()).put("moves",moves);Files.write(journal().toPath(),record.toString().getBytes(StandardCharsets.UTF_8));}
    private void closeStore(){store.close();store=null;}
    private static String text(File path)throws IOException{return new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8);}
    private static String sha256(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[]buffer=new byte[16384];try(InputStream input=new FileInputStream(file)){int read;while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);}StringBuilder value=new StringBuilder();for(byte item:digest.digest())value.append(String.format(Locale.ROOT,"%02x",item&255));return value.toString();}
    private static void clear(File file)throws IOException{if(Files.isSymbolicLink(file.toPath())){Files.deleteIfExists(file.toPath());return;}if(!file.exists())return;if(file.isDirectory()){File[]children=file.listFiles();if(children==null)throw new IOException("Cannot inspect synthetic source");for(File child:children)clear(child);}Files.delete(file.toPath());}
}
