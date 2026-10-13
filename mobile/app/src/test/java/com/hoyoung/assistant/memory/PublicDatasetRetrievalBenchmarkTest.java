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
import java.util.Set;
import java.util.HashSet;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Independent public annotated source-ID recall. Opt-in: HOYOUNG_MEMORY_DATASET=/tmp/fixture.json.
 * Non-standard oracle-selected sessions plus pooled distractors; no QA/extraction score. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class PublicDatasetRetrievalBenchmarkTest {
    @Test public void reportIndependentPublicRawEvidenceRecall()throws Exception{
        String path=System.getenv("HOYOUNG_MEMORY_DATASET");assumeTrue("A separately prepared public fixture is required",path!=null);
        JSONObject fixture=new JSONObject(new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8));assertFalse(fixture.getBoolean("standardBenchmark"));assertEquals("821a2034d219ab45846873dd14c14f12cfe7776e73527a483f9dac095d38620c",fixture.getString("sourceSha256"));
        Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);HoyoungStore store=new HoyoungStore(context);Map<String,Long> originals=new HashMap<>();
        try{
            JSONArray messages=fixture.getJSONArray("messages");store.withTransaction(db->{try{for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);originals.put(m.getString("key"),store.addMessage(m.getString("role"),m.getString("text"),null).getLong("id"));}}catch(Exception error){throw new IllegalStateException(error);}return null;});
            MemoryRetriever retriever=new MemoryRetriever(store);JSONArray questions=fixture.getJSONArray("questions"),metrics=new JSONArray();double sum=0;int complete=0;long totalMillis=0;
            for(int i=0;i<questions.length();i++){
                JSONObject q=questions.getJSONObject(i);long start=System.nanoTime();JSONObject request=new JSONObject();request.put("query",q.getString("query"));request.put("limit",10);request.put("maxChars",12000);JSONObject result=retriever.search(request);long millis=(System.nanoTime()-start)/1_000_000;totalMillis+=millis;
                Set<Long> retrieved=new HashSet<>();JSONArray evidence=result.getJSONArray("originalEvidence");for(int j=0;j<evidence.length();j++)retrieved.add(evidence.getJSONObject(j).getLong("id"));int hit=0;JSONArray gold=q.getJSONArray("gold");for(int j=0;j<gold.length();j++){Long id=originals.get(gold.getString(j));assertNotNull("Gold source must actually be stored",id);if(retrieved.contains(id))hit++;}
                double recall=(double)hit/gold.length();sum+=recall;if(hit==gold.length())complete++;JSONObject score=new JSONObject();score.put("questionId",q.getString("id"));score.put("type",q.getString("type"));score.put("sourceRecallAt20",recall);score.put("allGoldFound",hit==gold.length());score.put("elapsedMillis",millis);score.put("candidateLimitReached",result.getJSONObject("diagnostics").getBoolean("candidateLimitReached"));metrics.put(score);
            }
            JSONObject report=new JSONObject();report.put("dataset",fixture.getString("dataset"));report.put("sourceSha256",fixture.getString("sourceSha256"));report.put("standardBenchmark",false);report.put("oracleSessionSelection",true);report.put("pooledDistractorsAcrossScenarios",true);report.put("sampling",fixture.getString("sampling"));report.put("questionTypeCounts",fixture.getJSONObject("questionTypeCounts"));report.put("abstentionIncluded",false);report.put("mode","actual-app-lexical-retriever-no-neural-encoder");report.put("questionCount",questions.length());report.put("originalCount",store.messageCount());report.put("macroSourceRecallAt20",sum/questions.length());report.put("completeEvidenceQuestions",complete);report.put("meanElapsedMillis",totalMillis/questions.length());report.put("goldNotesSeeded",false);report.put("extractionMeasured",false);report.put("actualLmMeasured",false);report.put("realDeviceLatencyMeasured",false);report.put("metrics",metrics);
            System.out.println("HOYOUNG_PUBLIC_MEMORY_BENCHMARK "+report);
        }finally{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    }
}
