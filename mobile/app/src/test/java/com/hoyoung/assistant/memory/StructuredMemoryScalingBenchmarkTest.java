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
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real Store/reranker/cache cost with synthetic vectors and source-provider seam, not neural QA. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class StructuredMemoryScalingBenchmarkTest {
    @Test public void fiftyThousandCurrentMemoryVectorsUseVerifiedSourceLinkedShortlist()throws Exception{
        String setting=System.getenv("HOYOUNG_STRUCTURED_MEMORY_SCALE");assumeTrue("Large current-summary-vector probe requires explicit opt-in",setting!=null);int count=Integer.parseInt(setting);assertTrue(count>=5000&&count<=100000);
        Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);Encoder encoder=new Encoder();long start=System.nanoTime();
        try(HoyoungStore store=new HoyoungStore(context)){
            JSONObject goldSource=store.addMessage("user","preferred infusion is oolong",null),otherSource=store.addMessage("user","ordinary ledger activities",null);SemanticIndex index=new SemanticIndex(store,encoder);float[] otherVector=new float[512];otherVector[1]=1;byte[] distractor=VectorCodec.encode(otherVector,512),gold=VectorCodec.encode(encoder.encode("query"),512);
            for(int base=0;base<count;base+=500){final int from=base,to=Math.min(count,base+500);store.withTransaction(db->{try{for(int i=from;i<to;i++){boolean wanted=i==count-1;String key=wanted?"z.last.old-gold":"a.ledger."+i;JSONObject source=wanted?goldSource:otherSource;JSONObject memory=store.upsertMemory(key,wanted?"preferred infusion is oolong":"ordinary ledger summary "+i,"fact",new JSONArray().put(source.getLong("id")));ContentValues v=new ContentValues();v.put("memory_key",key);v.put("revision",memory.getInt("revision"));v.put("model_id",encoder.modelId());v.put("source_hash",memory.getString("content_hash"));v.put("dimension",512);v.put("encoding",VectorCodec.ENCODING);v.put("vector",wanted?gold:distractor);v.put("updated_at",System.currentTimeMillis());db.insertOrThrow("semantic_vectors",null,v);}}catch(Exception e){throw new IllegalStateException(e);}return null;});}
            long populatedMillis=elapsed(start);index.resetScanDiagnostics();long begin=System.nanoTime();index.memories(encoder.encode("unseen-bouquet-retrieval"),50);long baselineMillis=elapsed(begin);JSONObject baseline=index.scanDiagnostics();
            MemoryRetriever retriever=new MemoryRetriever(store,encoder);retriever.setSemanticCandidateProvider(new SemanticCandidateProvider(){public boolean isReady(String model){return true;}public JSONArray chunks(float[] query,MemoryQuery q,int limit,java.util.function.Predicate<JSONObject> visibility)throws Exception{return new JSONArray().put(new JSONObject().put("type","message").put("id",goldSource.getLong("id")).put("hash",goldSource.getString("content_hash")).put("modelId",encoder.modelId()).put("offsetChars",0).put("lengthChars",goldSource.getString("text").length()).put("semanticSimilarity",1));}public JSONObject diagnostics(){return new JSONObject();}public JSONObject maintain(int rows,long budget){return new JSONObject();}});
            JSONObject args=new JSONObject().put("query","unseen-bouquet-retrieval").put("limit",5);begin=System.nanoTime();JSONObject result=retriever.search(args);long queryMillis=elapsed(begin);JSONObject selective=result.getJSONObject("diagnostics").getJSONObject("structuredMemorySemantic");assertTrue(result.getJSONArray("memories").toString().contains("z.last.old-gold"));assertTrue(selective.getBoolean("fullMemoryVectorScanSkipped"));assertFalse(selective.getBoolean("wholeCorpusMemoryNeuralSearch"));assertTrue(selective.getLong("scannedVectors")<=256);assertTrue(selective.getLong("scannedVectorBytes")<=256L*512*4);assertTrue(result.getString("context").contains("wholeCorpusSearched=false"));
            JSONObject report=new JSONObject().put("benchmark","actual-store-structured-memory-verified-source-shortlist").put("storedCurrentMemoryVectors",count).put("dimensions",512).put("modelVectorsSynthetic",true).put("sourceCandidateProviderSynthetic",true).put("actualLanguageQualityMeasured",false).put("realDeviceLatencyMeasured",false).put("populateMillis",populatedMillis).put("baselineMemoryScanWallMillis",baselineMillis).put("baselineMemoryScan",baseline).put("boundedFullRetrieverWallMillis",queryMillis).put("boundedStructuredMemorySearch",selective).put("lateKeyGoldRecovered",true).put("databaseAndWalBytes",context.getDatabasePath(HoyoungStore.DATABASE_NAME).length()+new java.io.File(context.getDatabasePath(HoyoungStore.DATABASE_NAME).getPath()+"-wal").length());System.out.println("HOYOUNG_STRUCTURED_MEMORY_SCALE "+report);
        }finally{context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    }
    private static long elapsed(long start){return (System.nanoTime()-start)/1_000_000;}
    private static final class Encoder implements SemanticEncoder{public String modelId(){return "structured-cache-source-shortlist-protocol-seam-v1";}public int dimensions(){return 512;}public boolean isReady(){return true;}public boolean isNeural(){return true;}public float[] encode(String text){float[] result=new float[512];result[0]=1;return result;}}
}
