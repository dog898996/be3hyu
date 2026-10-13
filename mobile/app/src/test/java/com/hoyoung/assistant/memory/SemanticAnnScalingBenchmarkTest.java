package com.hoyoung.assistant.memory;

import android.app.Application;
import android.content.Context;
import android.content.ContentValues;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Actual production SQLite/JNI path at scale. Synthetic vectors are not neural accuracy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class SemanticAnnScalingBenchmarkTest {
    @Test public void measureActualSqliteNativeShortlistRerankAtScale()throws Exception{
        String requested=System.getenv("HOYOUNG_ANN_SQLITE_SCALE");assumeTrue("Explicit storage/native benchmark opt-in",requested!=null);assumeTrue("Real host JNI is mandatory",NativeAnnIndex.isAvailable());int count=Integer.parseInt(requested);assertTrue(count>=1000&&count<=1_000_000);
        Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);File directory=new File(context.getCacheDir(),"ann-scale-"+System.nanoTime());
        Encoder encoder=new Encoder();int queryCount=20;float[][] queries=new float[queryCount][];long[] expected=new long[queryCount];Random random=new Random(857231);long started=System.nanoTime();
        try(HoyoungStore store=new HoyoungStore(context)){
            new SemanticIndex(store,encoder);
            for(int base=0;base<count;base+=1000){final int first=base,last=Math.min(count,base+1000);store.withTransaction(db->{for(int i=first;i<last;i++){
                float[] vector=new float[512];for(int d=0;d<512;d++)vector[d]=(float)random.nextGaussian();vector=VectorCodec.normalize(vector,512);String text="synthetic permanent original "+i;JSONObject message=store.addMessage("user",text,null);
                ContentValues row=new ContentValues();row.put("source_type","message");row.put("source_id",message.optLong("id"));row.put("source_hash",message.optString("content_hash"));row.put("chunk_offset",0);row.put("chunk_length",text.length());row.put("model_id",encoder.modelId());row.put("dimension",512);row.put("encoding",VectorCodec.ENCODING);row.put("vector",VectorCodec.encode(vector,512));row.put("updated_at",System.currentTimeMillis());db.insertOrThrow("semantic_chunks",null,row);
                for(int q=0;q<queryCount;q++)if(i==(int)((long)q*(count-1)/(queryCount-1))){queries[q]=vector;expected[q]=message.optLong("id");}
            }return null;});}
            long populatedMillis=elapsed(started);long begin=System.nanoTime();
            try(SemanticAnnCache cache=new SemanticAnnCache(store,encoder,directory)){
                JSONObject maintenance=null;for(int batch=0;batch<count/100+100;batch++){maintenance=cache.maintain(4096,5000);if(cache.isReady(encoder.modelId()))break;assertNotEquals("maintenance_failed_rebuildable",maintenance.optString("status"));}
                assertTrue("Full canonical graph must be current before measured queries",cache.isReady(encoder.modelId()));long builtMillis=elapsed(begin);List<Long> latencies=new ArrayList<>();long bytes=0,hits=0;
                for(int q=0;q<queryCount;q++){begin=System.nanoTime();JSONArray result=cache.chunks(queries[q],new MemoryQuery(new JSONObject().put("query","synthetic lookup")),20,event->false);latencies.add(elapsed(begin));if(result.length()>0&&result.getJSONObject(0).getLong("id")==expected[q])hits++;bytes+=cache.diagnostics().getLong("canonicalCandidateBytes");}
                latencies.sort(Long::compare);double recall=(double)hits/queryCount;assertTrue("Self-match probes spanning old/new sources must reach >=95%",recall>=.95);
                long databaseBytes=context.getDatabasePath(HoyoungStore.DATABASE_NAME).length()+new File(context.getDatabasePath(HoyoungStore.DATABASE_NAME).getPath()+"-wal").length();long graphBytes=0;File[] files=directory.listFiles();if(files!=null)for(File f:files)graphBytes+=f.length();JSONObject report=new JSONObject();report.put("benchmark","actual-app-SQLite-JNI-HNSW-exact-f32-shortlist-rerank-512d");report.put("vectors",count);report.put("dimensions",512);report.put("modelVectorsSynthetic",true);report.put("queryCount",queryCount);report.put("selfTop1RecallOldToNewest",recall);report.put("top20ExactNeighborRecallMeasured",false);report.put("populateMillis",populatedMillis);report.put("buildNativeCacheMillis",builtMillis);report.put("actualSourceSearchMedianMillis",latencies.get(queryCount/2));report.put("actualSourceSearchP95Millis",latencies.get((int)Math.ceil(queryCount*.95)-1));report.put("averageCanonicalF32BytesReadPerQuery",bytes/queryCount);report.put("databaseAndWalBytes",databaseBytes);report.put("nativeCacheDirectoryBytes",graphBytes);report.put("ann",cache.diagnostics());report.put("realDeviceLatencyMeasured",false);report.put("actualLmMeasured",false);System.out.println("HOYOUNG_NATIVE_ANN_SQLITE_SCALE "+report);
            }
        }finally{remove(directory);context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    }
    private static long elapsed(long since){return (System.nanoTime()-since)/1_000_000;}
    private static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
    private static final class Encoder implements SemanticEncoder {public String modelId(){return "ann-storage-synthetic512-v1";}public int dimensions(){return 512;}public boolean isReady(){return true;}public boolean isNeural(){return true;}public float[] encode(String text){float[] v=new float[512];v[0]=1;return v;}}
}
