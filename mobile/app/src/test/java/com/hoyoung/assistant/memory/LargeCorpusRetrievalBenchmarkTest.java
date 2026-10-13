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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in actual Store+Retriever scaling benchmark. No paid API or private data.
 * HOYOUNG_MEMORY_SCALE=100000 (or1000000) enables this separately from normal regression tests.
 * Gold notes/aliases are manually seeded: this measures retrieval, not extraction or LM QA. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class LargeCorpusRetrievalBenchmarkTest {
    @Test public void reportDeterministicLargeCorpusRetrieval()throws Exception{
        String requested=System.getenv("HOYOUNG_MEMORY_SCALE");assumeTrue("Explicit slow benchmark opt-in is required",requested!=null);
        int total=Integer.parseInt(requested);assertTrue(total>=10_000&&total<=1_000_000);
        Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);HoyoungStore store=new HoyoungStore(context);
        List<Probe> probes=new ArrayList<>();long start=System.nanoTime();
        try{
            store.withTransaction(db->{try{
                for(int i=0;i<10;i++){
                    String key="old.rare."+i,needle="RARE-OLD-FACT-"+i+"-K831";long source=message(store,"2024-10-13 "+needle+" value TOKEN"+i);
                    note(store,key,needle+" value TOKEN"+i,source);probes.add(new Probe("old_exact",needle,key));
                    String aliasKey="owner.breakfast."+i;long alias=message(store,"주인의 아침밥 선호 "+i+"는 귀리죽");note(store,aliasKey,"아침밥 선호 "+i+" 귀리죽",alias);store.putMemoryMetadata(aliasKey,json("retrievalText","morning meal oatmeal 조식 아침 먹을거리 slot"+i));probes.add(new Probe("korean_alias","조식 slot"+i,aliasKey));
                    String entity="Project-Zeta-"+i;long place=message(store,entity+" 실행 장소는 Osaka"),partner=message(store,entity+" 공동 작업자는 Mira");note(store,entity+".place",entity+" 실행 장소 Osaka",place);note(store,entity+".partner",entity+" 공동 작업자 Mira",partner);probes.add(new Probe("entity_multihop",entity,entity+".place",entity+".partner"));
                }
                long dated=message(store,"2024년과2025년 주소 변경 source");note(store,"owner.temporal.home","예전 집 부산",dated);store.putMemoryMetadata("owner.temporal.home",json("factKey","owner.home","validFrom","2024-01-01","validTo","2025-01-01","value","부산"));note(store,"owner.temporal.home","현재 집 서울",dated);store.putMemoryMetadata("owner.temporal.home",json("factKey","owner.home","validFrom","2025-01-01","value","서울"));note(store,"owner.conflicting.home","다른 진술 인천",dated);store.putMemoryMetadata("owner.conflicting.home",json("factKey","owner.home","validFrom","2025-01-01","value","인천"));
                for(int i=0;i<1000;i++){long id=message(store,"작업 프로젝트 예산 보고 아침 장소 일반 기록 baseline "+i);note(store,"generic."+i,"작업 프로젝트 예산 보고 아침 장소 baseline "+i,id);}
            }catch(Exception e){throw new IllegalStateException(e);}return null;});
            MemoryRetriever retriever=new MemoryRetriever(store);JSONArray small=evaluate(retriever,probes);
            long generated=store.messageCount();
            for(long base=generated;base<total;base+=1000){final long begin=base,end=Math.min(total,base+1000);store.withTransaction(db->{try{for(long i=begin;i<end;i++){
                String date=LocalDate.of(2024,10,13).plusDays(i%730).toString();long id=message(store,date+" distractor workload status budget report meeting morning location routine record index"+i);
                if(i%20==0)note(store,"distractor."+i,"작업 프로젝트 budget 예산 report routine location 일정 morning index"+i,id);
            }}catch(Exception error){throw new IllegalStateException(error);}return null;});}
            JSONArray large=evaluate(retriever,probes);JSONObject datedResult=retriever.search(json("query","owner.temporal.home","asOf","2024-06-01","includeHistory",true));
            boolean dateRecall=datedResult.getJSONArray("history").toString().contains("부산");JSONObject conflict=retriever.search(json("query","owner.home owner.temporal.home owner.conflicting.home","includeHistory",true));JSONObject absent=retriever.search(json("query","ABSENT-NEEDLE-483932-XX"));
            assertTrue(dateRecall);assertTrue(conflict.getJSONArray("conflicts").length()>0);assertFalse(absent.getBoolean("hasRelevantEvidence"));
            long bytes=context.getDatabasePath(HoyoungStore.DATABASE_NAME).length();java.io.File wal=new java.io.File(context.getDatabasePath(HoyoungStore.DATABASE_NAME).getPath()+"-wal");bytes+=wal.length();
            JSONObject report=json("benchmark","actual-sqlite-store-hybrid-retriever-synthetic-scaling-v1","messages",store.messageCount(),"memories",store.memoryCount(),"smallCorpus",small,"largeCorpus",large,"dateStateRecall",dateRecall,"conflictDetected",true,"absentEvidenceHandled",true,"databaseAndWalBytes",bytes,"semantic",retriever.diagnostics(),"goldNotesManuallySeeded",true,"extractionMeasured",false,"actualLmMeasured",false,"realDeviceLatencyMeasured",false,"totalElapsedMillis",(System.nanoTime()-start)/1_000_000);
            System.out.println("HOYOUNG_LARGE_MEMORY_BENCHMARK "+report);
        }finally{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    }
    private static JSONArray evaluate(MemoryRetriever retriever,List<Probe> probes)throws Exception{JSONArray rows=new JSONArray();for(Probe p:probes){long start=System.nanoTime();JSONObject result=retriever.search(json("query",p.query,"limit",10,"maxChars",10000));JSONArray memories=result.getJSONArray("memories");int found=0;for(String gold:p.gold)for(int i=0;i<memories.length();i++)if(gold.equals(memories.getJSONObject(i).getString("key"))){found++;break;}JSONObject row=json("type",p.type,"query",p.query,"goldRecallAt10",(double)found/p.gold.length,"elapsedMillis",(System.nanoTime()-start)/1_000_000,"candidateLimitReached",result.getJSONObject("diagnostics").getBoolean("candidateLimitReached"));rows.put(row);assertEquals("Missing gold retrieval "+p.query,p.gold.length,found);}return rows;}
    private static long message(HoyoungStore store,String text)throws Exception{return store.addMessage("user",text,null).getLong("id");}
    private static void note(HoyoungStore store,String key,String content,long id){store.upsertMemory(key,content,"fact",new JSONArray().put(id));}
    private static JSONObject json(Object... pairs)throws Exception{JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private static class Probe{final String type,query;final String[]gold;Probe(String type,String query,String...gold){this.type=type;this.query=query;this.gold=gold;}}
}
