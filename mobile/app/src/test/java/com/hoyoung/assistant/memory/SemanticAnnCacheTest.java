package com.hoyoung.assistant.memory;

import android.app.Application;
import android.content.Context;
import android.content.ContentValues;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class SemanticAnnCacheTest {
    @Test public void unavailableNativeAbiRemainsHonestAndCanonicalRowsRemain()throws Exception{
        assumeTrue(!NativeAnnIndex.isAvailable());Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        try(HoyoungStore store=new HoyoungStore(context)){Encoder encoder=new Encoder();new SemanticIndex(store,encoder);JSONObject source=store.addMessage("user","permanent original",null);insert(store,encoder,source,new float[]{1,0,0,0});
            try(SemanticAnnCache cache=new SemanticAnnCache(store,encoder,new File(context.getCacheDir(),"ann-unavailable"))){JSONObject state=cache.maintain(100,100);assertFalse(cache.isReady(encoder.modelId()));assertEquals("native_library_unavailable",state.getString("status"));assertEquals("permanent original",store.getMessage(source.getLong("id")).getString("text"));assertEquals(1,new SemanticIndex(store,encoder).diagnostics().getLong("semanticChunkCount"));}
        }
    }
    @Test public void nativeProviderMaintainsAppendDuringPartialBuildAndInvalidatesReplacement()throws Exception{
        assumeTrue(NativeAnnIndex.isAvailable());Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        try(HoyoungStore store=new HoyoungStore(context)){Encoder encoder=new Encoder();new SemanticIndex(store,encoder);JSONObject a=store.addMessage("user","apple exact source",null),b=store.addMessage("user","banana source",null);insert(store,encoder,a,new float[]{1,0,0,0});insert(store,encoder,b,new float[]{0,1,0,0});
            File directory=new File(context.getCacheDir(),"ann-maintain-"+System.nanoTime());
            try(SemanticAnnCache cache=new SemanticAnnCache(store,encoder,directory)){cache.maintain(1,100);assertFalse(cache.isReady(encoder.modelId()));JSONObject c=store.addMessage("user","cherry source",null);insert(store,encoder,c,new float[]{0,0,1,0});cache.maintain(100,1000);assertTrue(cache.isReady(encoder.modelId()));assertEquals(3,cache.diagnostics().getLong("indexedChunkCount"));
                assertEquals(a.getLong("id"),cache.chunks(new float[]{1,0,0,0},new MemoryQuery(new JSONObject().put("query","apple")),1,event->true).getJSONObject(0).getLong("id"));
                store.withTransaction(db->{ContentValues changed=new ContentValues();changed.put("vector",VectorCodec.encode(new float[]{0,0,0,1},4));db.update("semantic_chunks",changed,"source_id=?",new String[]{String.valueOf(a.optLong("id"))});return null;});assertFalse(cache.isReady(encoder.modelId()));cache.maintain(100,1000);assertTrue(cache.isReady(encoder.modelId()));
                assertEquals(0,cache.chunks(new float[]{1,0,0,0},new MemoryQuery(new JSONObject().put("query","apple")),1,event->true).length());assertEquals("apple exact source",store.getMessage(a.getLong("id")).getString("text"));
            }
        }
    }
    @Test public void nativeProviderFiltersCurrentPermissionBeforeTopKAndSupportsDateAndEntityConstraints()throws Exception{
        assumeTrue(NativeAnnIndex.isAvailable());Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        try(HoyoungStore store=new HoyoungStore(context)){Encoder encoder=new Encoder();new SemanticIndex(store,encoder);JSONObject owner=store.addMessage("user","owner banana meeting",null);insert(store,encoder,owner,new float[]{.95f,.05f,0,0});
            JSONObject event=store.appendDeviceEvent("notification","android.notification_listener",new JSONObject().put("text","private notification"),System.currentTimeMillis(),"ann-test-notification");
            store.withTransaction(db->{ContentValues row=new ContentValues();row.put("source_type","device_event");row.put("source_id",event.optLong("id"));row.put("source_hash",event.optString("content_hash"));row.put("chunk_offset",0);row.put("chunk_length",10);row.put("model_id",encoder.modelId());row.put("dimension",4);row.put("encoding",VectorCodec.ENCODING);row.put("vector",VectorCodec.encode(new float[]{1,0,0,0},4));row.put("updated_at",System.currentTimeMillis());db.insertOrThrow("semantic_chunks",null,row);return null;});
            try(SemanticAnnCache cache=new SemanticAnnCache(store,encoder,new File(context.getCacheDir(),"ann-filter-"+System.nanoTime()))){cache.maintain(100,1000);assertTrue(cache.isReady(encoder.modelId()));MemoryQuery normal=new MemoryQuery(new JSONObject().put("query","meeting"));
                assertEquals("device_event",cache.chunks(new float[]{1,0,0,0},normal,1,e->true).getJSONObject(0).getString("type"));
                assertEquals("message",cache.chunks(new float[]{1,0,0,0},normal,1,e->false).getJSONObject(0).getString("type"));assertEquals(1,cache.diagnostics().getLong("excludedBeforeTopK"));
                MemoryQuery entities=new MemoryQuery(new JSONObject().put("query","meeting").put("entities",new org.json.JSONArray().put("banana")));assertEquals(owner.getLong("id"),cache.chunks(new float[]{1,0,0,0},entities,1,e->true).getJSONObject(0).getLong("id"));
                MemoryQuery future=new MemoryQuery(new JSONObject().put("query","meeting").put("fromInclusive",System.currentTimeMillis()+86_400_000));assertEquals(0,cache.chunks(new float[]{1,0,0,0},future,1,e->true).length());
            }
        }
    }
    static void insert(HoyoungStore store,Encoder encoder,JSONObject source,float[] vector){store.withTransaction(db->{ContentValues row=new ContentValues();row.put("source_type","message");row.put("source_id",source.optLong("id"));row.put("source_hash",source.optString("content_hash"));row.put("chunk_offset",0);row.put("chunk_length",source.optString("text").length());row.put("model_id",encoder.modelId());row.put("dimension",4);row.put("encoding",VectorCodec.ENCODING);row.put("vector",VectorCodec.encode(vector,4));row.put("updated_at",System.currentTimeMillis());db.insertOrThrow("semantic_chunks",null,row);return null;});}
    private static final class Encoder implements SemanticEncoder {public String modelId(){return "ann-provider-regression-4d-v1";}public int dimensions(){return 4;}public boolean isReady(){return true;}public boolean isNeural(){return true;}public float[] encode(String text){return new float[]{1,0,0,0};}}
}
