package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Tiny fixtures exercise real streaming, SQLite rollback and ZIP64 without allocating GB. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BackupCapacityTest {
    private static final long MIB=1024L*1024,RESERVE=64L*1024;
    private Context context; private HoyoungStore store; private InternalFiles files;
    private final BackupPolicy roomy=new BackupPolicy(4*MIB,RESERVE,directory->1L<<40);
    @Before public void setUp()throws Exception{
        context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir());assertTrue(context.getFilesDir().mkdirs()||context.getFilesDir().isDirectory());
        store=new HoyoungStore(context);files=new InternalFiles(context);
    }
    @After public void tearDown()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());}

    @Test public void productionPolicyDefaultsTo64GiBAndStrictOwnerSettingDoesNotAllocateStorage()throws Exception{
        assertEquals(64*BackupPolicy.GIB,BackupPolicy.fromStore(store).maxArchiveBytes);
        assertEquals(256*MIB,BackupPolicy.defaults().reserveBytes);
        store.putSetting("backupMaxGiB","1");assertEquals(BackupPolicy.GIB,BackupPolicy.fromStore(store).maxArchiveBytes);
        for(String bad:new String[]{"0","65","1.5","-1","01","NaN"," 64"}){
            store.putSetting("backupMaxGiB",bad);
            assertEquals("backup_configuration",assertThrows(BackupFailure.class,()->BackupPolicy.fromStore(store)).code);
        }
    }

    @Test public void preflightReportsActualPrivateProbeAndLabelsSizeEstimateAndProviderCapacity()throws Exception{
        files.writeFile("ordinary.txt","synthetic bytes");store.addMessage("user","original",null);
        BackupPolicy policy=new BackupPolicy(2*MIB,RESERVE,directory->7*MIB);
        JSONObject report=files.backupPreflight(store,policy);
        assertEquals(7*MIB,report.getLong("privateAvailableBytes"));assertEquals(2*MIB,report.getLong("archiveLimitBytes"));
        assertEquals(512*MIB,report.getLong("workingFileLimitBytes"));assertEquals(64*MIB,report.getLong("attachmentLimitBytes"));
        assertFalse(report.getBoolean("expandedEstimateExact"));assertFalse(report.getBoolean("providerCapacityKnown"));
        assertTrue(report.getLong("expandedBytesEstimate")>=report.getLong("sourceFileBytes"));assertFalse(files.restoreRecoveryPending());
    }

    @Test public void configuredSmallExpandedBudgetRejectsCompressibleArchiveAndPreservesCurrentGeneration()throws Exception{
        byte[] backup=seedBackup(repeat('x',180_000));long active=activateGeneration();
        assertTrue("The fixture must be small compressed but large expanded",backup.length<32*1024);
        BackupPolicy small=new BackupPolicy(64L*1024,RESERVE,directory->1L<<40);
        BackupFailure failure=assertThrows(BackupFailure.class,()->files.importBackup(new ByteArrayInputStream(backup),store,small));
        assertEquals("backup_expanded_limit",failure.code);assertEquals("restore_extract",failure.phase);
        assertActive(active);assertNoStaging();
    }

    @Test public void lowPrivateSpaceBeforeRestoreDoesNotReadOrReplaceAnything()throws Exception{
        byte[] backup=seedBackup("backup original");long active=activateGeneration();
        InputStream unread=new ByteArrayInputStream(backup){@Override public int read(byte[] b,int o,int l){fail("Preflight must run before input consumption");return-1;}};
        BackupPolicy low=new BackupPolicy(4*MIB,RESERVE,directory->RESERVE-1);
        BackupFailure failure=assertThrows(BackupFailure.class,()->files.importBackup(unread,store,low));
        assertEquals("backup_private_space",failure.code);assertEquals("restore_preflight",failure.phase);
        assertActive(active);assertNoStaging();
    }

    @Test public void privateSpaceLossDuringExtractionLeavesActiveRawFilesAndSqliteUntouched()throws Exception{
        byte[] backup=seedBackup(repeat('z',220_000));long active=activateGeneration();
        java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        BackupPolicy low=new BackupPolicy(4*MIB,RESERVE,directory->checks.incrementAndGet()>4?RESERVE-1:1L<<40);
        BackupFailure failure=assertThrows(BackupFailure.class,()->files.importBackup(new ByteArrayInputStream(backup),store,low));
        assertEquals("backup_private_space",failure.code);assertEquals("restore_extract",failure.phase);
        assertActive(active);assertNoStaging();
    }

    @Test public void lowSpaceAfterSqliteHasPartiallyCopiedSnapshotRollsBackDatabaseAndEveryNamespace()throws Exception{
        byte[] backup=seedBackup("backup original");long active=activateGeneration();
        BackupPolicy low=new BackupPolicy(4*MIB,RESERVE,directory->{
            boolean swapped=new File(context.getFilesDir(),".restore-transaction.json").isFile();
            return swapped&&"backup".equals(store.getSetting("synthetic.generation",""))?RESERVE-1:1L<<40;
        });
        BackupFailure failure=assertThrows(BackupFailure.class,()->files.importBackup(new ByteArrayInputStream(backup),store,low));
        assertEquals("backup_private_space",failure.code);assertEquals("restore_index_rebuild",failure.phase);
        assertFalse(failure.diagnostics().getBoolean("restoreCommitted"));
        assertActive(active);assertNoStaging();
    }

    @Test public void smallIncomingBackupAccountsForLargeExistingDatabaseBeforeSwappingNamespaces()throws Exception{
        byte[] backup=seedBackup("small older snapshot");long active=activateGeneration();
        String payload=repeat('l',4096);
        store.withTransaction(db->{for(int i=0;i<512;i++)store.addMessage("user","Synthetic old history "+i+payload,null);return null;});
        long beforeCount=store.messageCount();final boolean[] validated={false};final long[] incomingBytes={0};
        files.setBackupParticipant(new InternalFiles.BackupParticipant(){
            @Override public void capture(File destination){}
            @Override public void validate(File stagedJournal){validated[0]=true;incomingBytes[0]=new File(stagedJournal.getParentFile(),"staged-store.db").length();}
        });
        BackupPolicy low=new BackupPolicy(4*MIB,RESERVE,directory->validated[0]?RESERVE+2*incomingBytes[0]+MIB:1L<<40);
        BackupFailure failure=assertThrows(BackupFailure.class,()->files.importBackup(new ByteArrayInputStream(backup),store,low));
        assertEquals("backup_private_space",failure.code);assertEquals("restore_precommit",failure.phase);
        assertEquals(beforeCount,store.messageCount());assertActive(active);assertNoStaging();
    }

    @Test public void errorAfterVerifiedDatabaseCommitReportsCommittedAndRetainsPendingRecovery()throws Exception{
        byte[] backup=seedBackup("backup original");activateGeneration();
        File journal=new File(context.getFilesDir(),".restore-transaction.json"), saved=new File(context.getCacheDir(),"synthetic-restore-journal-copy.json");
        final boolean[] altered={false};
        BackupPolicy injected=new BackupPolicy(4*MIB,RESERVE,directory->{
            if(!altered[0]&&journal.isFile()){
                try{
                    JSONObject record=new JSONObject(new String(Files.readAllBytes(journal.toPath()),StandardCharsets.UTF_8));
                    if(record.getString("token").equals(store.getSetting(HoyoungStore.FILE_RESTORE_TOKEN_KEY,""))){
                        Files.move(journal.toPath(),saved.toPath());assertTrue(journal.mkdir());altered[0]=true;
                    }
                }catch(org.json.JSONException invalid){throw new IOException(invalid);}
            }
            return 1L<<40;
        });
        try{
            BackupFailure failure=assertThrows(BackupFailure.class,()->files.importBackup(new ByteArrayInputStream(backup),store,injected));
            assertTrue(altered[0]);assertTrue(failure.diagnostics().getBoolean("restoreCommitted"));assertTrue(files.restoreRecoveryPending());
            assertEquals("backup",store.getSetting("synthetic.generation",""));assertEquals("backup tree",files.readText("keep.txt",100));
        }finally{
            if(altered[0]){Files.delete(journal.toPath());Files.move(saved.toPath(),journal.toPath());files=new InternalFiles(context);}
        }
        assertFalse(files.restoreRecoveryPending());assertTrue(store.verifyIntegrity().getBoolean("ok"));
    }

    @Test public void destinationWriteAndFlushFailuresReportIncompleteWithoutChangingLocalSources()throws Exception{
        store.addMessage("user","local source",null);files.writeFile("local.txt","local bytes");
        OutputStream broken=new OutputStream(){int written;@Override public void write(int value)throws IOException{if(written++>=512)throw new IOException("synthetic provider size limit");}};
        BackupFailure failure=assertThrows(BackupFailure.class,()->files.exportBackup(broken,store,roomy));
        assertEquals("backup_destination_io",failure.code);assertFalse(failure.diagnostics().getBoolean("complete"));
        assertFalse(failure.diagnostics().getBoolean("providerCapacityKnown"));
        OutputStream badFlush=new ByteArrayOutputStream(){@Override public void flush()throws IOException{throw new IOException("synthetic provider flush failure");}};
        assertEquals("backup_destination_io",assertThrows(BackupFailure.class,()->files.exportBackup(badFlush,store,roomy)).code);
        assertEquals("local bytes",files.readText("local.txt",100));assertEquals("local source",store.getMessage(1).getString("text"));assertNoStaging();
    }

    @Test public void configuredExportBudgetFailsExplicitlyWithoutTrimmingSavedOriginal()throws Exception{
        String original=repeat('p',210_000)+" ORIGINAL-TAIL";store.addMessage("assistant",original,null);
        BackupPolicy small=new BackupPolicy(64L*1024,RESERVE,directory->1L<<40);
        assertEquals("backup_expanded_limit",assertThrows(BackupFailure.class,()->files.exportBackup(new ByteArrayOutputStream(),store,small)).code);
        assertEquals(" ORIGINAL-TAIL",store.readOriginalText(1,210_000,100).getString("text"));assertNoStaging();
    }

    @Test public void sparseOversizedWorkingFileFailsBeforeCopyAndRemainsUntouched()throws Exception{
        store.addMessage("user","retained original",null);
        File source=new File(context.getFilesDir(),"projects/oversized.bin");long size=InternalFiles.MAX_FILE_BYTES+1;
        try(RandomAccessFile file=new RandomAccessFile(source,"rw")){file.setLength(size);file.seek(size-1);file.write(77);}
        BackupFailure failure=assertThrows(BackupFailure.class,()->files.exportBackup(new ByteArrayOutputStream(),store,roomy));
        assertEquals("backup_file_limit",failure.code);assertEquals("export_files",failure.phase);assertEquals("projects/oversized.bin",failure.relativePath);
        assertEquals(size,source.length());try(RandomAccessFile file=new RandomAccessFile(source,"r")){file.seek(size-1);assertEquals(77,file.read());}
        assertEquals("retained original",store.getMessage(1).getString("text"));assertNoStaging();
    }

    @Test public void forcedZip64TinyFixtureRoundTripsExactPayloadAndSourceHashes()throws Exception{
        byte[] ordinary=seedBackup("source for ZIP64");String hash=store.getMessage(1).getString("content_hash");
        byte[] zip64=zip64Fixture(ordinary);assertTrue(zip64.length<32*1024);
        files.writeFile("keep.txt","changed after backup");
        files.importBackup(new ByteArrayInputStream(zip64),store,roomy);
        assertEquals("backup tree",files.readText("keep.txt",100));
        assertEquals(hash,store.getMessage(1).getString("content_hash"));assertTrue(store.verifyIntegrity().getBoolean("ok"));
    }

    @Test public void slowMediaDestinationDoesNotKeepDatabaseSnapshotPinned()throws Exception{
        store.addMessage("user","table cut",null);byte[] bytes=new byte[96*1024];new Random(9).nextBytes(bytes);
        Files.write(new File(context.getFilesDir(),"projects/random.bin").toPath(),bytes);
        BackupPolicy.Session session=roomy.session(context.getFilesDir());
        CountDownLatch blocked=new CountDownLatch(1),resume=new CountDownLatch(1);ByteArrayOutputStream captured=new ByteArrayOutputStream();
        OutputStream slow=new OutputStream(){boolean waited;
            @Override public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
            @Override public void write(byte[] body,int offset,int count)throws IOException{
                if(!waited&&session.phase().equals("export_files")){waited=true;blocked.countDown();try{if(!resume.await(5,TimeUnit.SECONDS))throw new IOException("synthetic backup timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}}
                captured.write(body,offset,count);
            }
        };
        ExecutorService worker=Executors.newSingleThreadExecutor();Future<?> export=worker.submit(()->{try{StreamingBackup.exportArchive(slow,store,context.getFilesDir(),null,roomy,session);}catch(IOException failure){throw new RuntimeException(failure);}});
        try{
            assertTrue(blocked.await(5,TimeUnit.SECONDS));store.putSetting("written.after.table.cut","new generation");
            try(Cursor checkpoint=store.getReadableDatabase().rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){
                assertTrue(checkpoint.moveToFirst());assertEquals("Media export must not pin the old read transaction",0,checkpoint.getInt(0));
            }
        }finally{resume.countDown();}
        export.get(10,TimeUnit.SECONDS);worker.shutdownNow();
        files.importBackup(new ByteArrayInputStream(captured.toByteArray()),store,roomy);
        assertEquals("missing",store.getSetting("written.after.table.cut","missing"));
    }

    private byte[] seedBackup(String original)throws Exception{
        store.addMessage("user",original,null);store.putSetting("synthetic.generation","backup");
        files.writeFile("keep.txt","backup tree");ByteArrayOutputStream output=new ByteArrayOutputStream();files.exportBackup(output,store,roomy);return output.toByteArray();
    }
    private long activateGeneration()throws Exception{
        store.putSetting("synthetic.generation","active");store.putSetting("autonomy.enabled","true");
        store.putSetting(HoyoungStore.FILE_RESTORE_TOKEN_KEY,"active-token");files.writeFile("keep.txt","active tree");
        File journal=new File(context.getFilesDir(),"device-journal/active.json");assertTrue(journal.getParentFile().mkdirs()||journal.getParentFile().isDirectory());Files.write(journal.toPath(),"active journal".getBytes(StandardCharsets.UTF_8));
        return store.addMessage("assistant",repeat('a',210_000)+" active exact tail",null).getLong("id");
    }
    private void assertActive(long active)throws Exception{
        assertEquals("active tree",files.readText("keep.txt",100));assertEquals("active",store.getSetting("synthetic.generation",""));
        assertEquals("true",store.getSetting("autonomy.enabled",""));assertEquals("active-token",store.getSetting(HoyoungStore.FILE_RESTORE_TOKEN_KEY,""));
        assertEquals(" active exact tail",store.readOriginalText(active,210_000,100).getString("text"));
        assertEquals("active journal",new String(Files.readAllBytes(new File(context.getFilesDir(),"device-journal/active.json").toPath()),StandardCharsets.UTF_8));assertTrue(store.verifyIntegrity().getBoolean("ok"));
    }
    private void assertNoStaging(){File[] entries=context.getFilesDir().listFiles();assertNotNull(entries);for(File entry:entries)assertFalse(entry.getName(),entry.getName().startsWith(".restore-")||entry.getName().startsWith(".previous-")||entry.getName().startsWith(".backup-"));}
    private static String repeat(char value,int count){char[] chars=new char[count];Arrays.fill(chars,value);return new String(chars);}
    private static void clear(File file)throws IOException{if(Files.isSymbolicLink(file.toPath())){Files.deleteIfExists(file.toPath());return;}if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("Cannot list synthetic directory");for(File child:children)clear(child);}Files.delete(file.toPath());}

    /** Every local/central entry uses ZIP64 sizes, plus ZIP64 end+locator, with tiny STORED bodies. */
    private static byte[] zip64Fixture(byte[] ordinary)throws IOException{
        LinkedHashMap<String,byte[]> entries=new LinkedHashMap<>();
        try(ZipInputStream input=new ZipInputStream(new ByteArrayInputStream(ordinary))){ZipEntry entry;while((entry=input.getNextEntry())!=null){ByteArrayOutputStream body=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)body.write(buffer,0,count);entries.put(entry.getName(),body.toByteArray());}}
        ByteArrayOutputStream output=new ByteArrayOutputStream(),central=new ByteArrayOutputStream();
        for(Map.Entry<String,byte[]> entry:entries.entrySet()){
            byte[] name=entry.getKey().getBytes(StandardCharsets.UTF_8),body=entry.getValue();CRC32 crc=new CRC32();crc.update(body);long offset=output.size();
            le(output,0x04034b50,4);le(output,45,2);le(output,0x800,2);le(output,0,2);le(output,0,4);le(output,crc.getValue(),4);le(output,0xffffffffL,4);le(output,0xffffffffL,4);le(output,name.length,2);le(output,20,2);output.write(name);le(output,1,2);le(output,16,2);le(output,body.length,8);le(output,body.length,8);output.write(body);
            le(central,0x02014b50,4);le(central,45,2);le(central,45,2);le(central,0x800,2);le(central,0,2);le(central,0,4);le(central,crc.getValue(),4);le(central,0xffffffffL,4);le(central,0xffffffffL,4);le(central,name.length,2);le(central,28,2);le(central,0,2);le(central,0,2);le(central,0,2);le(central,0,4);le(central,0xffffffffL,4);central.write(name);le(central,1,2);le(central,24,2);le(central,body.length,8);le(central,body.length,8);le(central,offset,8);
        }
        long centralOffset=output.size();central.writeTo(output);long endOffset=output.size();
        le(output,0x06064b50,4);le(output,44,8);le(output,45,2);le(output,45,2);le(output,0,4);le(output,0,4);le(output,entries.size(),8);le(output,entries.size(),8);le(output,central.size(),8);le(output,centralOffset,8);
        le(output,0x07064b50,4);le(output,0,4);le(output,endOffset,8);le(output,1,4);
        le(output,0x06054b50,4);le(output,0,2);le(output,0,2);le(output,0xffff,2);le(output,0xffff,2);le(output,0xffffffffL,4);le(output,0xffffffffL,4);le(output,0,2);return output.toByteArray();
    }
    private static void le(OutputStream output,long value,int bytes)throws IOException{for(int i=0;i<bytes;i++)output.write((int)(value>>>(i*8))&255);}
}
