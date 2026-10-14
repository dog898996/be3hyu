package com.hoyoung.assistant.memory;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SemanticIndexPendingVisibilityTest {
    private Context context;
    private HoyoungStore store;
    @Before public void before()throws Exception {
        context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());
        assertTrue(context.getFilesDir().mkdirs()||context.getFilesDir().isDirectory());store=new HoyoungStore(context);
    }
    @After public void after()throws Exception {store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());}

    @Test public void deniedNewestPageDoesNotStarveOlderOwnerNoteAndIsRevisitedWhenAllowed()throws Exception {
        JSONObject owner=store.addMessage("user","allowed owner preference",null);
        store.upsertMemory("owner.preference","allowed owner preference","fact",new JSONArray().put(owner.getLong("id")));
        store.getWritableDatabase().execSQL("UPDATE memories SET updated_at=10 WHERE memory_key='owner.preference'");
        for(int i=1;i<=2;i++){
            JSONObject device=store.appendDeviceEvent("notification","visibility-fixture",new JSONObject().put("text","PRIVATE-DEVICE-"+i),100+i,null);
            store.upsertMemoryWithSources("device."+i,"PRIVATE-DEVICE-NOTE-"+i,"observation",
                    new JSONArray().put(new JSONObject().put("type","device_event").put("id",device.getLong("id"))));
            store.getWritableDatabase().execSQL("UPDATE memories SET updated_at=? WHERE memory_key=?",new Object[]{10+i,"device."+i});
        }
        FakeEncoder encoder=new FakeEncoder();SemanticIndex index=new SemanticIndex(store,encoder);index.setDeviceSourceVisibility(row->false);
        assertEquals(0,index.indexPending(2,2000).getInt("indexedMemoriesThisBatch"));
        assertEquals(1,index.indexPending(2,2000).getInt("indexedMemoriesThisBatch"));
        assertEquals(1,index.diagnostics().getLong("semanticIndexedCount"));
        for(String input:encoder.inputs)assertFalse(input,input.contains("PRIVATE-DEVICE"));

        index.setDeviceSourceVisibility(row->true);
        assertEquals(2,index.indexPending(2,2000).getInt("indexedMemoriesThisBatch"));
        assertEquals(3,index.diagnostics().getLong("semanticIndexedCount"));
        assertTrue(encoder.inputs.stream().anyMatch(input->input.contains("PRIVATE-DEVICE-NOTE-1")));
        assertTrue(encoder.inputs.stream().anyMatch(input->input.contains("PRIVATE-DEVICE-NOTE-2")));

        // A changed note behind the cursor must be reached again after the pass wraps.
        store.upsertMemory("owner.preference","allowed owner preference with updated preference alias","fact",new JSONArray().put(owner.getLong("id")));
        assertEquals(1,index.indexPending(2,2000).getInt("indexedMemoriesThisBatch"));
        assertEquals(3,index.diagnostics().getLong("semanticIndexedCount"));
        assertTrue(encoder.inputs.stream().anyMatch(input->input.contains("updated preference alias")));
    }

    private static final class FakeEncoder implements SemanticEncoder {
        final List<String> inputs=new ArrayList<>();
        public String modelId(){return "pending-memory-visibility-fixture";}
        public int dimensions(){return 2;}
        public boolean isReady(){return true;}
        public boolean isNeural(){return true;}
        public float[] encode(String text){inputs.add(text);return new float[]{1,0};}
    }
    private static void delete(File file)throws Exception {
        if(!file.exists())return;
        if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Unreadable fixture");for(File child:children)delete(child);}
        if(!file.delete())throw new java.io.IOException("Fixture cleanup failed");
    }
}
