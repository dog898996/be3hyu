package com.hoyoung.assistant.store;
import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ExclusiveStorageMaintenanceTest {
    private Context context;private InternalFiles files;private HoyoungStore store;private ExecutorService workers;
    @Before public void setup()throws Exception{context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());files=new InternalFiles(context);store=new HoyoungStore(context);workers=Executors.newFixedThreadPool(3);files.writeFile("keep.txt","original bytes");}
    @After public void cleanup()throws Exception{workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());}
    @Test public void maintenanceBlocksFileMutationsAndBackupUntilCallbackReturns()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),writeAttempt=new CountDownLatch(1),exportAttempt=new CountDownLatch(1);
        Future<String> owner=workers.submit(()->files.withExclusiveStorageMaintenance(()->{entered.countDown();await(release);return "maintenance complete";}));assertTrue(entered.await(10,TimeUnit.SECONDS));
        Future<?> write=workers.submit(()->{writeAttempt.countDown();files.writeFile("keep.txt","after maintenance");return null;}),export=workers.submit(()->{exportAttempt.countDown();files.exportBackup(new ByteArrayOutputStream(),store);return null;});
        try{assertTrue(writeAttempt.await(10,TimeUnit.SECONDS));assertTrue(exportAttempt.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->write.get(100,TimeUnit.MILLISECONDS));assertThrows(TimeoutException.class,()->export.get(100,TimeUnit.MILLISECONDS));}finally{release.countDown();}
        assertEquals("maintenance complete",owner.get(10,TimeUnit.SECONDS));write.get(10,TimeUnit.SECONDS);export.get(10,TimeUnit.SECONDS);assertEquals("after maintenance",files.readText("keep.txt",100));
    }
    @Test public void activeStreamingExportFinishesBeforeMaintenanceCallbackCanMutateNamespace()throws Exception{
        CountDownLatch destinationStarted=new CountDownLatch(1),releaseDestination=new CountDownLatch(1),maintenanceAttempt=new CountDownLatch(1);AtomicBoolean callbackEntered=new AtomicBoolean();
        OutputStream destination=new OutputStream(){boolean first=true;@Override public void write(int value)throws IOException{if(first){first=false;destinationStarted.countDown();await(releaseDestination);}}};
        Future<?> export=workers.submit(()->{files.exportBackup(destination,store);return null;});assertTrue(destinationStarted.await(10,TimeUnit.SECONDS));
        Future<?> maintenance=workers.submit(()->{maintenanceAttempt.countDown();return files.withExclusiveStorageMaintenance(()->{callbackEntered.set(true);files.writeFile("keep.txt","maintenance after export");return null;});});
        try{assertTrue(maintenanceAttempt.await(10,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->maintenance.get(100,TimeUnit.MILLISECONDS));assertFalse(callbackEntered.get());}finally{releaseDestination.countDown();}
        export.get(10,TimeUnit.SECONDS);maintenance.get(10,TimeUnit.SECONDS);assertTrue(callbackEntered.get());assertEquals("maintenance after export",files.readText("keep.txt",100));
    }
    @Test public void callbackFailureReleasesBothLocksAndPreservesOrdinaryOperation()throws Exception{
        IOException failure=assertThrows(IOException.class,()->files.withExclusiveStorageMaintenance(()->{throw new IOException("synthetic maintenance failure");}));assertEquals("synthetic maintenance failure",failure.getMessage());
        Future<?> next=workers.submit(()->{files.writeFile("keep.txt","after failure");files.exportBackup(new ByteArrayOutputStream(),store);return null;});next.get(10,TimeUnit.SECONDS);assertEquals("after failure",files.readText("keep.txt",100));
    }
    @Test public void constructorBlockedObjectRejectsMaintenanceBeforeInvokingCallback()throws Exception{
        byte[]journal="{truncated restore evidence".getBytes(StandardCharsets.UTF_8);File marker=new File(context.getFilesDir(),".restore-transaction.json");Files.write(marker.toPath(),journal);InternalFiles blocked=new InternalFiles(context);assertTrue(blocked.startupBlocked());AtomicBoolean called=new AtomicBoolean();assertThrows(IOException.class,()->blocked.withExclusiveStorageMaintenance(()->{called.set(true);return null;}));assertFalse(called.get());assertArrayEquals(journal,Files.readAllBytes(marker.toPath()));assertEquals("original bytes",files.readText("keep.txt",100));
    }
    private static void await(CountDownLatch latch)throws IOException{try{if(!latch.await(10,TimeUnit.SECONDS))throw new IOException("Synthetic concurrency fixture timed out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException("Synthetic concurrency fixture interrupted",failure);}}
    private static void clear(File file)throws IOException{if(Files.isSymbolicLink(file.toPath())){Files.deleteIfExists(file.toPath());return;}if(!file.exists())return;if(file.isDirectory()){File[]children=file.listFiles();if(children==null)throw new IOException("Cannot inspect synthetic fixtures");for(File child:children)clear(child);}Files.delete(file.toPath());}
}
