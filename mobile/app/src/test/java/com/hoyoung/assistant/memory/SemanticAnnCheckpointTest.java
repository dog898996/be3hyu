package com.hoyoung.assistant.memory;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real JNI/cache files and SQLite; fake clocks advance cadence without sleeping for hours. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class SemanticAnnCheckpointTest {
    @Test public void nativeRestartReplaysOnlyPureAppendTailFromOlderCheckpoint()throws Exception{
        assumeTrue(NativeAnnIndex.isAvailable());Fixture f=new Fixture();
        try{
            for(int i=0;i<10;i++)f.insert("base "+i,new float[]{1,0,0,0});
            try(SemanticAnnCache first=f.cache()){first.maintain(100,1000);assertTrue(first.isReady(f.encoder.modelId()));assertEquals(1,first.diagnostics().getLong("checkpointWritesThisProcess"));}
            JSONObject before=NativeAnnIndex.readManifest(f.directory);String file=before.getString("file");assertEquals(10,before.getJSONObject("checkpoint").getLong("canonicalCount"));
            for(int i=0;i<4;i++)f.insert("new "+i,new float[]{0,1,0,0});long newest=f.insert("latest unique",new float[]{0,0,0,1});f.advanceMinutes(1);
            try(SemanticAnnCache restarted=f.cache()){
                JSONObject partial=restarted.maintain(1,1000);assertFalse(restarted.isReady(f.encoder.modelId()));assertEquals("loaded_checkpoint_and_replay_appends",partial.getString("checkpointLoadDecision"));assertEquals(1,partial.getLong("rowsAddedThisBatch"));assertEquals(11,partial.getLong("indexedChunkCount"));assertEquals(1,partial.getLong("checkpointLoadsThisProcess"));
                restarted.maintain(100,1000);assertTrue(restarted.isReady(f.encoder.modelId()));assertEquals(15,restarted.diagnostics().getLong("indexedChunkCount"));assertEquals(0,restarted.diagnostics().getLong("checkpointWritesThisProcess"));assertEquals(file,NativeAnnIndex.readManifest(f.directory).getString("file"));assertEquals(newest,restarted.chunks(new float[]{0,0,0,1},f.query(),1,e->false).getJSONObject(0).getLong("id"));
            }
            assertEquals(15,f.store.messageCount());
        }finally{f.close();}
    }
    @Test public void nativeRestartAfterUpdateRejectsCheckpointAndRebuildsCurrentVector()throws Exception{
        assumeTrue(NativeAnnIndex.isAvailable());Fixture f=new Fixture();
        try{long a=f.insert("kept original A",new float[]{1,0,0,0});f.insert("kept original B",new float[]{0,1,0,0});f.firstCheckpoint();f.update(a,new float[]{0,0,1,0});
            try(SemanticAnnCache cache=f.cache()){cache.maintain(100,1000);assertEquals("rejected_non_append_epoch_or_count",cache.diagnostics().getString("checkpointLoadDecision"));assertEquals(0,cache.diagnostics().getLong("checkpointLoadsThisProcess"));assertTrue(cache.isReady(f.encoder.modelId()));assertEquals(a,cache.chunks(new float[]{0,0,1,0},f.query(),1,e->false).getJSONObject(0).getLong("id"));assertEquals("kept original A",f.store.getMessage(a).getString("text"));}
        }finally{f.close();}
    }
    @Test public void nativeRestartAfterDeleteRejectsCheckpointWithoutDeletingOriginals()throws Exception{
        assumeTrue(NativeAnnIndex.isAvailable());Fixture f=new Fixture();
        try{long a=f.insert("original remains after derived deletion",new float[]{1,0,0,0});f.insert("other original",new float[]{0,1,0,0});f.firstCheckpoint();f.store.withTransaction(db->{db.delete("semantic_chunks","source_id=?",new String[]{String.valueOf(a)});return null;});
            try(SemanticAnnCache cache=f.cache()){cache.maintain(100,1000);assertEquals("rejected_non_append_epoch_or_count",cache.diagnostics().getString("checkpointLoadDecision"));assertEquals(1,cache.diagnostics().getLong("indexedChunkCount"));assertEquals(2,f.store.messageCount());assertEquals("original remains after derived deletion",f.store.getMessage(a).getString("text"));}
        }finally{f.close();}
    }
    @Test public void nativeCheckpointFullF32PrefixRejectsVectorChangesEvenWithRolledBackEpoch()throws Exception{
        assumeTrue(NativeAnnIndex.isAvailable());Fixture f=new Fixture();
        try{long a=f.insert("original with changed derived vector",new float[]{1,0,0,0});f.firstCheckpoint();long epoch=NativeAnnIndex.readManifest(f.directory).getJSONObject("checkpoint").getLong("canonicalGeneration");f.update(a,new float[]{0,0,1,0});
            f.store.withTransaction(db->{db.execSQL("UPDATE semantic_ann_generation SET generation=? WHERE id=1",new Object[]{epoch});return null;});
            try(SemanticAnnCache cache=f.cache()){cache.maintain(100,1000);assertEquals("rejected_checkpoint_prefix_hash_or_range",cache.diagnostics().getString("checkpointLoadDecision"));assertEquals(0,cache.diagnostics().getLong("checkpointLoadsThisProcess"));assertEquals(a,cache.chunks(new float[]{0,0,1,0},f.query(),1,e->false).getJSONObject(0).getLong("id"));}
        }finally{f.close();}
    }
    @Test public void nativeCorruptGraphIsRebuiltAndOriginalArchiveSurvives()throws Exception{
        assumeTrue(NativeAnnIndex.isAvailable());Fixture f=new Fixture();
        try{long a=f.insert("durable original despite graph corruption",new float[]{1,0,0,0});f.firstCheckpoint();File graph=new File(f.directory,NativeAnnIndex.readManifest(f.directory).getString("file"));try(RandomAccessFile corrupt=new RandomAccessFile(graph,"rw")){corrupt.seek(64);int value=corrupt.read();corrupt.seek(64);corrupt.write(value^1);}
            try(SemanticAnnCache cache=f.cache()){cache.maintain(100,1000);assertEquals("invalid_or_corrupt_checkpoint",cache.diagnostics().getString("checkpointLoadDecision"));assertEquals(0,cache.diagnostics().getLong("checkpointLoadsThisProcess"));assertTrue(cache.isReady(f.encoder.modelId()));assertEquals(a,cache.chunks(new float[]{1,0,0,0},f.query(),1,e->false).getJSONObject(0).getLong("id"));assertEquals("durable original despite graph corruption",f.store.getMessage(a).getString("text"));}
        }finally{f.close();}
    }
    @Test public void nativeDifferentEncoderIdentityDoesNotReuseOldCheckpoint()throws Exception{
        assumeTrue(NativeAnnIndex.isAvailable());Fixture f=new Fixture();
        try{long id=f.insert("original survives encoder replacement",new float[]{1,0,0,0});f.firstCheckpoint();
            SemanticEncoder other=new SemanticEncoder(){public String modelId(){return "checkpoint-new-model-4d-v2";}public int dimensions(){return 4;}public boolean isReady(){return true;}public boolean isNeural(){return true;}public float[] encode(String text){return new float[]{0,1,0,0};}};
            try(SemanticAnnCache cache=new SemanticAnnCache(f.store,other,f.directory,false,f.elapsed::get,f.wall::get)){
                cache.maintain(100,1000);assertEquals("incompatible_checkpoint_identity",cache.diagnostics().getString("checkpointLoadDecision"));assertEquals(0,cache.diagnostics().getLong("checkpointLoadsThisProcess"));assertEquals(0,cache.diagnostics().getLong("indexedChunkCount"));assertEquals("original survives encoder replacement",f.store.getMessage(id).getString("text"));
            }
        }finally{f.close();}
    }
    @Test public void nativeDayOfMinuteAppendsUsesFiveCheckpointsAndStableTicksDoNotWrite()throws Exception{
        assumeTrue(NativeAnnIndex.isAvailable());Fixture f=new Fixture();
        try{f.insert("first persistent original",new float[]{1,0,0,0});
            try(SemanticAnnCache cache=f.cache()){cache.maintain(100,1000);assertEquals(1,cache.diagnostics().getLong("checkpointWritesThisProcess"));
                for(int minute=1;minute<=24*60;minute++){f.advanceMinutes(1);final int current=minute;f.store.withTransaction(db->{for(int i=0;i<8;i++)f.insert("minute "+current+" item "+i,new float[]{1,0,0,0});return null;});cache.maintain(32,1000);assertTrue(cache.isReady(f.encoder.modelId()));if(minute<6*60)assertEquals(1,cache.diagnostics().getLong("checkpointWritesThisProcess"));}
                assertEquals(5,cache.diagnostics().getLong("checkpointWritesThisProcess"));assertEquals(11_521,f.store.messageCount());long writes=cache.diagnostics().getLong("checkpointWritesThisProcess");f.advanceMinutes(24*60);for(int i=0;i<20;i++)cache.maintain(32,1000);assertEquals(writes,cache.diagnostics().getLong("checkpointWritesThisProcess"));assertTrue(cache.diagnostics().getLong("checkpointFileBytesWrittenThisProcess")>0);
            }
        }finally{f.close();}
    }
    @Test public void nativeBurstCannotWriteBeforeFloorAndFirstPartialBuildDoesNotCheckpoint()throws Exception{
        assumeTrue(NativeAnnIndex.isAvailable());Fixture f=new Fixture();
        try{for(int i=0;i<16;i++)f.insert("initial "+i,new float[]{1,0,0,0});
            try(SemanticAnnCache cache=f.cache()){cache.maintain(1,1000);assertFalse(cache.isReady(f.encoder.modelId()));assertEquals(0,cache.diagnostics().getLong("checkpointWritesThisProcess"));assertNull(NativeAnnIndex.readManifest(f.directory));cache.maintain(100,1000);assertEquals(1,cache.diagnostics().getLong("checkpointWritesThisProcess"));
                f.store.withTransaction(db->{for(int i=0;i<4100;i++)f.insert("burst "+i,new float[]{1,0,0,0});return null;});f.advanceMinutes(1);cache.maintain(4096,1000);cache.maintain(100,1000);assertTrue(cache.isReady(f.encoder.modelId()));assertEquals(1,cache.diagnostics().getLong("checkpointWritesThisProcess"));f.advanceMinutes(14);cache.maintain(1,1000);assertEquals(2,cache.diagnostics().getLong("checkpointWritesThisProcess"));
            }
        }finally{f.close();}
    }
    private static final class Fixture implements AutoCloseable{
        final Context context=ApplicationProvider.getApplicationContext();final Encoder encoder=new Encoder();final HoyoungStore store;final File directory;final AtomicLong elapsed=new AtomicLong(),wall=new AtomicLong(1_700_000_000_000L);
        Fixture(){context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);new SemanticIndex(store,encoder);directory=new File(context.getCacheDir(),"ann-checkpoint-"+System.nanoTime());}
        SemanticAnnCache cache(){return new SemanticAnnCache(store,encoder,directory,false,elapsed::get,wall::get);}
        void advanceMinutes(long minutes){elapsed.addAndGet(minutes*60_000);wall.addAndGet(minutes*60_000);}
        long insert(String text,float[] vector){JSONObject source=store.addMessage("user",text,null);store.withTransaction(db->{ContentValues row=new ContentValues();row.put("source_type","message");row.put("source_id",source.optLong("id"));row.put("source_hash",source.optString("content_hash"));row.put("chunk_offset",0);row.put("chunk_length",text.length());row.put("model_id",encoder.modelId());row.put("dimension",4);row.put("encoding",VectorCodec.ENCODING);row.put("vector",VectorCodec.encode(vector,4));row.put("updated_at",wall.get());db.insertOrThrow("semantic_chunks",null,row);return null;});return source.optLong("id");}
        void update(long id,float[] vector){store.withTransaction(db->{ContentValues row=new ContentValues();row.put("vector",VectorCodec.encode(vector,4));db.update("semantic_chunks",row,"source_id=?",new String[]{String.valueOf(id)});return null;});}
        void firstCheckpoint(){try(SemanticAnnCache cache=cache()){cache.maintain(100,1000);assertTrue(cache.isReady(encoder.modelId()));assertEquals(1,cache.diagnostics().optLong("checkpointWritesThisProcess"));}}
        MemoryQuery query()throws Exception{return new MemoryQuery(new JSONObject().put("query","checkpoint proof"));}
        public void close(){store.close();remove(directory);context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    }
    private static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
    private static final class Encoder implements SemanticEncoder{public String modelId(){return "checkpoint-native-regression-4d-v1";}public int dimensions(){return 4;}public boolean isReady(){return true;}public boolean isNeural(){return true;}public float[] encode(String text){return new float[]{1,0,0,0};}}
}
