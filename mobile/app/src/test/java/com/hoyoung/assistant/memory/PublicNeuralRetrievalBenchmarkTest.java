package com.hoyoung.assistant.memory;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.io.File;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Paired real host-neural/lexical app retrieval on public annotated data; no QA or extraction score. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class PublicNeuralRetrievalBenchmarkTest {
    @Test public void compareActualOnnxRawSourceVectorsWithLexicalRetrieval()throws Exception{
        String path=System.getenv("HOYOUNG_MEMORY_DATASET"),neural=System.getenv("HOYOUNG_MEMORY_SEMANTIC");assumeTrue("Both actual public fixtures must be prepared separately",path!=null&&neural!=null);
        byte[] raw=Files.readAllBytes(Paths.get(path));JSONObject fixture=new JSONObject(new String(raw,StandardCharsets.UTF_8));
        Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);HoyoungStore store=new HoyoungStore(context);Map<String,Long> ids=new HashMap<>();
        try(PrecomputedOnnxFixture encoder=new PrecomputedOnnxFixture(neural)){
            assertEquals(PrecomputedOnnxFixture.sha(raw),encoder.manifest.getString("fixtureSha256"));JSONArray messages=fixture.getJSONArray("messages");
            store.withTransaction(db->{try{for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);ids.put(message.getString("key"),store.addMessage(message.getString("role"),message.getString("text"),null).getLong("id"));}}catch(Exception failure){throw new IllegalStateException(failure);}return null;});
            MemoryRetriever retriever=new MemoryRetriever(store,encoder);int chunks=encoder.populate(store,ids);assertEquals(1.0,retriever.diagnostics().getDouble("sourceCoverage"),0.00001);
            boolean useAnn="1".equals(System.getenv("HOYOUNG_MEMORY_ANN"));SemanticAnnCache ann=null;
            if(useAnn){assertTrue("Explicit ANN benchmark requires the real host JNI library",NativeAnnIndex.isAvailable());ann=new SemanticAnnCache(store,encoder,new File(context.getCacheDir(),"public-neural-ann"));for(int batch=0;batch<100&&!ann.isReady(encoder.modelId());batch++)ann.maintain(4096,5000);assertTrue("The whole canonical graph must be ready before measuring recall",ann.isReady(encoder.modelId()));}
            JSONArray questions=fixture.getJSONArray("questions"),metrics=new JSONArray();double lexicalSum=0,hybridSum=0,annSum=0;int improved=0,worsened=0,annImproved=0,annWorsened=0;
            try{
            for(int i=0;i<questions.length();i++){
                JSONObject question=questions.getJSONObject(i),args=new JSONObject();args.put("query",question.getString("query"));args.put("limit",10);args.put("maxChars",12000);
                retriever.setSemanticCandidateProvider(null);retriever.setSemanticEnabled(false);long begin=System.nanoTime();JSONObject lexical=retriever.search(args);long lexicalMillis=(System.nanoTime()-begin)/1_000_000;
                retriever.setSemanticEnabled(true);begin=System.nanoTime();JSONObject hybrid=retriever.search(args);long hybridMillis=(System.nanoTime()-begin)/1_000_000;
                double l=recall(lexical,question.getJSONArray("gold"),ids),h=recall(hybrid,question.getJSONArray("gold"),ids);lexicalSum+=l;hybridSum+=h;if(h>l)improved++;if(h<l)worsened++;
                JSONObject row=new JSONObject();row.put("questionId",question.getString("id"));row.put("type",question.getString("type"));row.put("lexicalRecallAt20",l);row.put("hybridRecallAt20",h);row.put("lexicalMillis",lexicalMillis);row.put("hybridMillis",hybridMillis);row.put("semanticScan",hybrid.getJSONObject("diagnostics").getJSONObject("semanticScan"));
                if(useAnn){retriever.setSemanticCandidateProvider(ann);begin=System.nanoTime();JSONObject approximate=retriever.search(args);long annMillis=(System.nanoTime()-begin)/1_000_000;double a=recall(approximate,question.getJSONArray("gold"),ids);annSum+=a;if(a>h)annImproved++;if(a<h)annWorsened++;JSONObject diagnostic=approximate.getJSONObject("diagnostics").getJSONObject("semanticAnn");assertFalse("ANN measurement must not silently use exact fallback",diagnostic.has("fallback"));assertTrue(diagnostic.getBoolean("usedForThisSearch"));assertEquals("native_hnsw_int8_cosine_then_exact_f32_shortlist_rerank",diagnostic.getString("method"));row.put("annHybridRecallAt20",a);row.put("annHybridMillis",annMillis);row.put("semanticAnn",diagnostic);}
                metrics.put(row);
            }
            JSONObject report=new JSONObject();report.put("dataset",fixture.getString("dataset"));report.put("standardBenchmark",false);report.put("questionCount",questions.length());report.put("sourceCount",store.messageCount());report.put("rawSourceChunks",chunks);report.put("modelId",encoder.modelId());report.put("hostEmbeddingElapsedSeconds",encoder.manifest.getDouble("elapsedSeconds"));report.put("lexicalMacroSourceRecallAt20",lexicalSum/questions.length());report.put("hybridMacroSourceRecallAt20",hybridSum/questions.length());report.put("improvedQuestions",improved);report.put("worsenedQuestions",worsened);report.put("goldNotesSeeded",false);report.put("actualLmMeasured",false);report.put("extractionMeasured",false);report.put("androidTokenizationParityMeasured",false);report.put("realDeviceLatencyMeasured",false);report.put("scope","actual-app-retriever/real-host-ONNX-vectors/public-source-recall-only");if(useAnn){report.put("annHybridMacroSourceRecallAt20",annSum/questions.length());report.put("annImprovedOverExactQuestions",annImproved);report.put("annWorsenedOverExactQuestions",annWorsened);report.put("annMethod",ann.diagnostics());}report.put("metrics",metrics);System.out.println("HOYOUNG_NEURAL_PUBLIC_MEMORY_BENCHMARK "+report);
            }finally{if(ann!=null)ann.close();}
        }finally{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    }
    private static double recall(JSONObject result,JSONArray gold,Map<String,Long> ids)throws Exception{Set<Long> retrieved=new HashSet<>();JSONArray evidence=result.getJSONArray("originalEvidence");for(int i=0;i<evidence.length();i++)if("message".equals(evidence.getJSONObject(i).getString("type")))retrieved.add(evidence.getJSONObject(i).getLong("id"));int found=0;for(int i=0;i<gold.length();i++)if(retrieved.contains(ids.get(gold.getString(i))))found++;return (double)found/gold.length();}
}
