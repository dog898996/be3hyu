package com.hoyoung.assistant.memory;

import android.database.Cursor;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A bounded current-vector rerank, not a whole-corpus neural search over summary memories.
 * Original source ANN/FTS and the complete lexical/pinned lanes remain independent. */
final class StructuredMemorySearch {
    static final int MAX_CANDIDATES=256, MAX_EXISTING=128;
    private final HoyoungStore store;private final SemanticEncoder encoder;private final SemanticIndex index;
    StructuredMemorySearch(HoyoungStore store,SemanticEncoder encoder,SemanticIndex index){this.store=store;this.encoder=encoder;this.index=index;}
    JSONObject retrieve(float[] query,int limit,Set<String> existing,JSONArray verifiedChunks){
        long begin=System.nanoTime();LinkedHashSet<String> keys=new LinkedHashSet<>();int existingCount=0;boolean limited=false;Set<String> sources=new HashSet<>();
        for(String key:existing){if(existingCount++>=MAX_EXISTING){limited=true;break;}keys.add(key);}
        for(int i=0;i<verifiedChunks.length()&&keys.size()<MAX_CANDIDATES;i++){
            JSONObject chunk=verifiedChunks.optJSONObject(i);if(chunk==null)continue;String type=chunk.optString("type"),hash=chunk.optString("hash");long id=chunk.optLong("id");if(!sources.add(type+":"+id+":"+hash))continue;
            int available=MAX_CANDIDATES-keys.size();
            synchronized(store){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT m.memory_key FROM memory_sources s INDEXED BY memory_sources_original CROSS JOIN memories m CROSS JOIN semantic_vectors v WHERE s.source_type=? AND s.source_id=? AND s.source_hash=? AND m.memory_key=s.memory_key AND m.revision=s.revision AND v.memory_key=m.memory_key AND v.revision=m.revision AND v.source_hash=m.content_hash AND v.model_id=? LIMIT ?",new String[]{type,String.valueOf(id),hash,encoder.modelId(),String.valueOf(available+1)})){
                int count=0;while(c.moveToNext()){if(count++>=available){limited=true;break;}keys.add(c.getString(0));}
            }}
        }
        if(keys.size()>=MAX_CANDIDATES&&verifiedChunks.length()>0)limited=true;
        List<JSONObject> ranked=new ArrayList<>();long scanned=0,bytes=0;int corrupt=0;
        for(String key:keys){String hash=null;int revision=0,dimension=0;byte[] vector=null;String encoding=null;
            synchronized(store){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT v.source_hash,v.revision,v.dimension,v.encoding,v.vector FROM semantic_vectors v JOIN memories m ON m.memory_key=v.memory_key AND m.revision=v.revision AND m.content_hash=v.source_hash WHERE v.memory_key=? AND v.model_id=?",new String[]{key,encoder.modelId()})){if(c.moveToFirst()){hash=c.getString(0);revision=c.getInt(1);dimension=c.getInt(2);encoding=c.getString(3);vector=c.getBlob(4);}}}
            if(vector==null||dimension!=encoder.dimensions()||!VectorCodec.ENCODING.equals(encoding)||!index.visibleMemory(key,revision))continue;
            scanned++;bytes+=vector.length;double score;try{score=VectorCodec.cosine(query,VectorCodec.decode(vector,dimension));}catch(RuntimeException bad){corrupt++;continue;}if(score<=0)continue;
            JSONObject memory=store.getMemoryCandidate(key);if(memory==null||revision!=memory.optInt("revision")||!hash.equals(memory.optString("content_hash"))||!index.visibleMemory(key,revision))continue;
            MemoryPolicy.put(memory,"semanticSimilarity",score);MemoryPolicy.put(memory,"semanticCandidateMethod","verified-source-links-plus-existing-lexical-shortlist");ranked.add(memory);
        }
        ranked.sort(Comparator.comparingDouble((JSONObject m)->m.optDouble("semanticSimilarity")).reversed());JSONArray rows=new JSONArray();for(int i=0;i<Math.min(limit,ranked.size());i++)rows.put(ranked.get(i));
        long elapsed=(System.nanoTime()-begin)/1_000_000;index.recordSelectiveScan(scanned,bytes,elapsed);JSONObject result=new JSONObject(),diagnostics=new JSONObject();
        MemoryPolicy.put(diagnostics,"method","source-linked-and-existing-lexical-shortlist-canonical-f32");MemoryPolicy.put(diagnostics,"fullMemoryVectorScanSkipped",true);MemoryPolicy.put(diagnostics,"wholeCorpusMemoryNeuralSearch",false);MemoryPolicy.put(diagnostics,"maxCandidates",MAX_CANDIDATES);MemoryPolicy.put(diagnostics,"candidateCount",keys.size());MemoryPolicy.put(diagnostics,"candidateLimitReached",limited);MemoryPolicy.put(diagnostics,"validatedSourceCount",sources.size());MemoryPolicy.put(diagnostics,"scannedVectors",scanned);MemoryPolicy.put(diagnostics,"scannedVectorBytes",bytes);MemoryPolicy.put(diagnostics,"elapsedMillis",elapsed);MemoryPolicy.put(diagnostics,"corruptVectorCount",corrupt);MemoryPolicy.put(diagnostics,"sourceLinkGranularity","whole-original-source-not-quote-range");MemoryPolicy.put(diagnostics,"sourceLinkOrder","bounded-storage-index-order-not-semantic-relevance");MemoryPolicy.put(diagnostics,"unlinkedOrUnselectedMemoriesMayBeRelevant",true);MemoryPolicy.put(result,"rows",rows);MemoryPolicy.put(result,"diagnostics",diagnostics);return result;
    }
}
