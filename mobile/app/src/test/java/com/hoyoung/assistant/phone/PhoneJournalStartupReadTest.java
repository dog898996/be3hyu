package com.hoyoung.assistant.phone;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.store.HoyoungStore;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** JVM concurrency/integrity regression; real startup ANR evidence is recorded separately. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public class PhoneJournalStartupReadTest {
    @Test public void stateReadDoesNotWaitForUnrelatedSpoolMonitor() throws Exception {
        PhoneObservationSpool spool=spool();spool.writeState("activity.state","saved-counter-123");
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try {
            synchronized(spool) {
                Future<String> read=reader.submit(()->spool.readState("activity.state","missing"));
                assertEquals("saved-counter-123",read.get(1,TimeUnit.SECONDS));
            }
        } finally {reader.shutdownNow();reader.awaitTermination(2,TimeUnit.SECONDS);}
    }

    @Test public void sha256FilenamesAndChecksumsRemainByteCompatible() throws Exception {
        for(String value:new String[]{"","abc","activity.state","health.archiveFailure","한글🙂\nraw observation", "x".repeat(8193)}) {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder legacy=new StringBuilder();
            for(byte part:digest)legacy.append(String.format(Locale.ROOT,"%02x",part&255));
            assertEquals(legacy.toString(),PhoneEventJournal.hash(value));
        }
    }

    @Test public void atomicStateReplacementRemainsCompleteAndCorruptionIsRejected() throws Exception {
        PhoneObservationSpool spool=spool();spool.writeState("state","v0");
        ExecutorService writer=Executors.newSingleThreadExecutor();
        try {
            Future<?> written=writer.submit(()->{try{for(int i=1;i<=40;i++)spool.writeState("state","v"+i);}catch(Exception failure){throw new RuntimeException(failure);}});
            for(int i=0;i<200;i++)assertTrue(spool.readState("state","invalid").matches("v[0-9]+"));
            written.get(3,TimeUnit.SECONDS);assertEquals("v40",spool.readState("state","invalid"));
            File file=new File(new File(spool.directory(),"state"),PhoneEventJournal.hash("state")+".json");
            Files.write(file.toPath(),new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8).replace("v40","bad").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("invalid",spool.readState("state","invalid"));
        } finally {writer.shutdownNow();writer.awaitTermination(2,TimeUnit.SECONDS);}
    }

    @Test public void lateReadCannotOverwriteANewerPersistedPut() throws Exception {checkLateRead(false);}
    @Test public void lateReadCannotRepopulateTheRestoredGenerationCache() throws Exception {checkLateRead(true);}

    private void checkLateRead(boolean restore) throws Exception {
        Context context=RuntimeEnvironment.getApplication();HoyoungStore store=new HoyoungStore(context);
        PhoneEventJournal journal=PhoneEventJournal.bind(context,store);journal.closeWorker();journal.awaitClosedForTests();
        journal.put("race.state","old");
        BlockingCache cache=new BlockingCache();Field field=PhoneEventJournal.class.getDeclaredField("localState");field.setAccessible(true);field.set(journal,cache);
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try {
            Future<String> read=reader.submit(()->journal.get("race.state","missing"));
            assertTrue(cache.readReachedPublish.await(2,TimeUnit.SECONDS));
            if(restore){journal.pauseForBackupRestore();new PhoneObservationSpool(journal.spoolDirectoryForTests()).writeState("race.state","new");journal.afterBackupRestore();}
            else journal.put("race.state","new");
            cache.resumeRead.countDown();read.get(2,TimeUnit.SECONDS);
            assertEquals("new",journal.get("race.state","missing"));
            assertEquals("new",new PhoneObservationSpool(journal.spoolDirectoryForTests()).readState("race.state","missing"));
        } finally {cache.resumeRead.countDown();reader.shutdownNow();reader.awaitTermination(2,TimeUnit.SECONDS);journal.closeWorker();journal.awaitClosedForTests();store.close();}
    }

    private static PhoneObservationSpool spool() throws Exception {
        return new PhoneObservationSpool(Files.createTempDirectory(RuntimeEnvironment.getApplication().getCacheDir().toPath(),"startup-spool-").toFile());
    }
    private static final class BlockingCache extends ConcurrentHashMap<String,String> {
        final CountDownLatch readReachedPublish=new CountDownLatch(1),resumeRead=new CountDownLatch(1);
        private void beforePublish(String value){if(!"old".equals(value))return;readReachedPublish.countDown();try{if(!resumeRead.await(4,TimeUnit.SECONDS))throw new AssertionError("Read was never released");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}
        @Override public String put(String key,String value){beforePublish(value);return super.put(key,value);}
        @Override public String putIfAbsent(String key,String value){beforePublish(value);return super.putIfAbsent(key,value);}
    }
}
