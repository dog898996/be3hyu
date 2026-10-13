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
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Actual SQLite storage/scan with synthetic 512d vectors. Not neural recall/model accuracy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class SemanticScanScalingBenchmarkTest {
    @Test public void reportHundredThousand512dVectorScanBudget()throws Exception{
        String opt=System.getenv("HOYOUNG_MEMORY_VECTOR_SCALE");assumeTrue("Explicit vector-performance opt-in is required",opt!=null);int count=Integer.parseInt(opt);assertTrue(count>=1000&&count<=1_000_000);
        Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);HoyoungStore store=new HoyoungStore(context);Synthetic512 encoder=new Synthetic512();SemanticIndex index=new SemanticIndex(store,encoder);byte[] vector=VectorCodec.encode(encoder.encode("synthetic query"),512);long started=System.nanoTime();
        try{
            for(int base=0;base<count;base+=1000){final int begin=base,end=Math.min(count,base+1000);store.withTransaction(db->{try{for(int i=begin;i<end;i++){
                String text="synthetic vector storage source "+i;JSONObject source=store.addMessage("user",text,null);ContentValues row=new ContentValues();row.put("source_type","message");row.put("source_id",source.getLong("id"));row.put("source_hash",source.getString("content_hash"));row.put("chunk_offset",0);row.put("chunk_length",text.length());row.put("model_id",encoder.modelId());row.put("dimension",512);row.put("encoding",VectorCodec.ENCODING);row.put("vector",vector);row.put("updated_at",System.currentTimeMillis());db.insertOrThrow("semantic_chunks",null,row);
            }}catch(Exception failure){throw new IllegalStateException(failure);}return null;});}
            long populatedMillis=(System.nanoTime()-started)/1_000_000;JSONObject args=new JSONObject();args.put("query","synthetic query");MemoryQuery q=new MemoryQuery(args);index.resetScanDiagnostics();long begin=System.nanoTime();assertTrue(index.chunks(encoder.encode("synthetic query"),q,20).length()>0);long searchMillis=(System.nanoTime()-begin)/1_000_000;JSONObject scan=index.scanDiagnostics();assertTrue(scan.getLong("scannedVectors")>0);assertTrue(scan.getLong("scannedVectors")<=count);
            long bytes=context.getDatabasePath(HoyoungStore.DATABASE_NAME).length()+new java.io.File(context.getDatabasePath(HoyoungStore.DATABASE_NAME).getPath()+"-wal").length();JSONObject report=new JSONObject();report.put("benchmark","actual-app-cache-paged-cosine-synthetic-512d-storage-scaling");report.put("storedChunks",count);report.put("dimensions",512);report.put("nominalVectorBytes",(long)count*512*4);report.put("databaseAndWalBytes",bytes);report.put("populateMillis",populatedMillis);report.put("searchWallMillis",searchMillis);report.put("scan",scan);report.put("neuralQualityMeasured",false);report.put("actualLmMeasured",false);report.put("realDeviceLatencyMeasured",false);report.put("modelVectorsSynthetic",true);System.out.println("HOYOUNG_VECTOR_SCAN_BENCHMARK "+report);
        }finally{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    }
    private static class Synthetic512 implements SemanticEncoder{
        public String modelId(){return "synthetic-512d-storage-performance-only-v1";}public int dimensions(){return 512;}public boolean isReady(){return true;}public boolean isNeural(){return true;}
        public float[] encode(String text){float[] result=new float[512];for(int i=0;i<result.length;i++)result[i]=(float)Math.sin(i*.17)+.3f;return VectorCodec.normalize(result,512);}
    }
}
