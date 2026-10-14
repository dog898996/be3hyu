package com.hoyoung.assistant.store;
import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class SnapshotOriginalTextTest {
    private Context context;private HoyoungStore store;
    @Before public void setup()throws Exception{context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());new InternalFiles(context);store=new HoyoungStore(context);}
    @After public void cleanup()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());}
    @Test public void snapshotHydrationRetainsInlineSourceFromSameViewAfterLiveDeletion()throws Exception{
        JSONObject old=store.addMessage("user","frozen owner 👋 original",null);long id=old.getLong("id");
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()){
            store.getWritableDatabase().delete("messages","id=?",new String[]{String.valueOf(id)});JSONObject newer=store.addMessage("user","new source outside snapshot",null);
            assertThrows(IllegalArgumentException.class,()->store.readOriginalText(id,0,100));
            JSONObject frozen=store.readOriginalText(snapshot,id,0,100);assertEquals(old.getString("text"),frozen.getString("text"));assertEquals(old.getString("raw_content_hash"),frozen.getString("contentHash"));assertEquals(old.getString("content_hash"),frozen.getString("recordHash"));assertTrue(frozen.getBoolean("complete"));
            assertThrows(IllegalArgumentException.class,()->store.readOriginalText(snapshot,newer.getLong("id"),0,100));
        }
    }
    @Test public void spilledSourceRangeUsesSnapshotMetadataAndEnforcesSnapshotOwner()throws Exception{
        String text="large frozen 원문 😀 ".repeat(16000);JSONObject old=store.addMessage("user",text,null);long id=old.getLong("id");assertFalse(old.isNull("raw_file_id"));
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()){
            store.getWritableDatabase().delete("messages","id=?",new String[]{String.valueOf(id)});
            JSONObject frozen=store.readOriginalText(snapshot,id,0,24);assertEquals(text.substring(0,24),frozen.getString("text"));assertEquals(text.length(),frozen.getLong("totalChars"));assertEquals(old.getString("raw_content_hash"),frozen.getString("contentHash"));
            ExecutorService worker=Executors.newSingleThreadExecutor();try{ExecutionException failure=assertThrows(ExecutionException.class,()->worker.submit(()->store.readOriginalText(snapshot,id,0,100)).get(10,TimeUnit.SECONDS));assertTrue(failure.getCause() instanceof IllegalStateException);}finally{worker.shutdownNow();}
        }
    }
    private static void clear(File file)throws IOException{if(Files.isSymbolicLink(file.toPath())){Files.deleteIfExists(file.toPath());return;}if(!file.exists())return;if(file.isDirectory()){File[]children=file.listFiles();if(children==null)throw new IOException("Cannot inspect fixtures");for(File child:children)clear(child);}Files.delete(file.toPath());}
}
