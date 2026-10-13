package com.hoyoung.assistant.memory;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Derived native shortlist, then exact canonical-f32 reranking and original-source validation.
 * Maintenance and search run on background workers. FTS/pinned/current-fact retrieval stay separate.
 * A partially built graph is never advertised as covering the full canonical corpus. */
public final class SemanticAnnCache implements SemanticCandidateProvider, AutoCloseable {
    private final HoyoungStore store;
    private final SemanticEncoder encoder;
    private final File directory;
    private final boolean categoricalVisibility;
    private NativeAnnIndex graph;
    private long afterRowId, syncedGeneration=-1, targetGeneration=-1, targetCanonicalCount, canonicalCount, lastSavedAt, lastSavedCount;
    private volatile boolean ready;
    private String status="not_initialized",error="";
    private long lastSearchMillis,lastCandidateBytes,lastExcludedCount,lastCandidateCount;
    private boolean constraintLimitReached;
    private volatile String publishedDiagnostics;
    private boolean schemaReady;
    private boolean corpusCountKnown;
    public SemanticAnnCache(HoyoungStore store,SemanticEncoder encoder,File directory){this(store,encoder,directory,false);}
    /** categoricalVisibility may be true only for a policy whose decision depends on source/category
     * plus current OS/app grants, as PhoneTools.canReadStoredSource currently does. The default
     * evaluates each full device record, including predicates that depend on payload or ID. */
    public SemanticAnnCache(HoyoungStore store,SemanticEncoder encoder,File directory,boolean categoricalVisibility){
        this.store=store;this.encoder=encoder;this.directory=directory;this.categoricalVisibility=categoricalVisibility;
        publishDiagnostics();
    }
    /** Creating a covering index over a long-lived corpus belongs to the maintenance worker. */
    private void ensureSchema(){
        if(schemaReady)return;
        synchronized(store){SQLiteDatabase db=store.getWritableDatabase();
            db.execSQL("CREATE TABLE IF NOT EXISTS semantic_ann_generation (id INTEGER PRIMARY KEY CHECK(id=1), generation INTEGER NOT NULL)");
            db.execSQL("INSERT OR IGNORE INTO semantic_ann_generation(id,generation) VALUES(1,0)");
            for(String operation:new String[]{"INSERT","UPDATE","DELETE"})db.execSQL("CREATE TRIGGER IF NOT EXISTS semantic_ann_"+operation.toLowerCase(Locale.ROOT)+" AFTER "+operation+" ON semantic_chunks BEGIN UPDATE semantic_ann_generation SET generation=generation+1 WHERE id=1; END");
            db.execSQL("CREATE INDEX IF NOT EXISTS semantic_ann_metadata ON semantic_chunks(model_id,source_type,source_id,source_hash,chunk_offset,chunk_length,dimension,encoding)");
        }
        schemaReady=true;
    }
    public boolean isReady(String modelId){
        if(!ready||!NativeAnnIndex.isAvailable()||!encoder.isReady()||!encoder.modelId().equals(modelId))return false;
        return generation()==syncedGeneration;
    }
    public synchronized JSONObject maintain(int maximumRows,long budgetMillis){
        if(!NativeAnnIndex.isAvailable()){status="native_library_unavailable";ready=false;return publishDiagnostics();}
        if(!encoder.isReady()||!encoder.isNeural()){status="encoder_not_ready";ready=false;return publishDiagnostics();}
        long start=now(),deadline=start+Math.max(10,Math.min(5000,budgetMillis));int added=0;
        try{
            ensureSchema();
            long currentGeneration=generation(),count=countRows();canonicalCount=count;corpusCountKnown=true;
            if(graph==null){
                graph=new NativeAnnIndex(encoder.modelId(),encoder.dimensions(),capacityFor(count));
                String fingerprint=fingerprint(currentGeneration,count);
                try{if(graph.loadVerified(directory,fingerprint)){afterRowId=maxRowId();syncedGeneration=currentGeneration;targetGeneration=currentGeneration;targetCanonicalCount=count;lastSavedCount=count;lastSavedAt=now();ready=true;status="loaded_verified_cache";error="";return publishDiagnostics();}}
                catch(Exception cacheInvalid){error="cache_rebuild: "+cacheInvalid.getClass().getSimpleName();}
                targetGeneration=currentGeneration;targetCanonicalCount=count;
            }
            if(currentGeneration!=targetGeneration){
                long delta=currentGeneration-targetGeneration,countDelta=count-targetCanonicalCount;
                // A pure append can be inserted incrementally. Replacement/deletion/model mutation
                // rebuilds the derived graph, so stale row IDs cannot silently stand for new vectors.
                if(!(delta==countDelta&&countDelta>0&&maxRowId()>afterRowId)){
                    graph.close();graph=new NativeAnnIndex(encoder.modelId(),encoder.dimensions(),capacityFor(count));afterRowId=0;
                }
                targetGeneration=currentGeneration;targetCanonicalCount=count;ready=false;
            }
            int maximum=Math.max(1,Math.min(4096,maximumRows));
            while(added<maximum&&now()<deadline){
                List<IndexedRow> rows=vectorRows(afterRowId,Math.min(32,maximum-added));if(rows.isEmpty())break;
                long[] labels=new long[rows.size()];float[] vectors=new float[rows.size()*encoder.dimensions()];
                for(int i=0;i<rows.size();i++){IndexedRow row=rows.get(i);float[] decoded=VectorCodec.decode(row.vector,encoder.dimensions());labels[i]=row.rowId;System.arraycopy(decoded,0,vectors,i*encoder.dimensions(),decoded.length);}
                graph.addBatch(labels,vectors);afterRowId=rows.get(rows.size()-1).rowId;added+=rows.size();
            }
            long afterGeneration=generation();canonicalCount=countRows();
            ready=afterGeneration==targetGeneration&&graph.count()==canonicalCount;
            if(ready){syncedGeneration=targetGeneration;status="ready";if(graph.count()!=lastSavedCount&&(graph.count()-lastSavedCount>=1024||now()-lastSavedAt>30_000)){
                String fingerprint=fingerprint(targetGeneration,canonicalCount);
                if(generation()==targetGeneration){graph.saveVerified(directory,fingerprint);lastSavedCount=graph.count();lastSavedAt=now();}
            }}else status="building_partial_cache";
            error="";
        }catch(Exception failure){ready=false;status="maintenance_failed_rebuildable";error=failure.getClass().getSimpleName()+": "+failure.getMessage();}
        JSONObject result=publishDiagnostics();put(result,"rowsAddedThisBatch",added);put(result,"maintenanceMillis",now()-start);return result;
    }
    public synchronized JSONArray chunks(float[] query,MemoryQuery constraints,int limit,Predicate<JSONObject> deviceVisibility)throws Exception{
        if(!isReady(encoder.modelId()))throw new IllegalStateException("ANN cache not current; canonical fallback required");
        long started=now();lastCandidateBytes=0;lastCandidateCount=0;constraintLimitReached=false;
        Longs denied=new Longs();Map<String,Boolean> categorical=new HashMap<>();Map<Long,Boolean> devices=new HashMap<>();
        // A covering metadata index avoids scanning canonical float blobs to enforce date/grant
        // constraints before HNSW selects its top candidates. Grants are re-evaluated each query.
        boolean dateFilter=constraints.from>0||constraints.to>0;
        // With no date filter, immutable owner originals need only the bounded candidate hash
        // recheck below. Scan device metadata for current grants, rather than every owner's row.
        String filterSql="SELECT v.rowid,v.source_type,v.source_id,v.source_hash,COALESCE(m.content_hash,e.content_hash),COALESCE(m.created_at,e.observed_at),e.category,e.source FROM semantic_chunks v INDEXED BY semantic_ann_metadata LEFT JOIN messages m ON v.source_type='message' AND m.id=v.source_id LEFT JOIN device_events e ON v.source_type='device_event' AND e.id=v.source_id WHERE v.model_id=? AND v.dimension=? AND v.encoding=?"+(dateFilter?"":" AND v.source_type='device_event'");
        synchronized(store){try(Cursor c=store.getReadableDatabase().rawQuery(filterSql,parameters())){
            while(c.moveToNext()){
                long label=c.getLong(0),id=c.getLong(2);String type=c.getString(1);
                boolean allowed=c.getString(3).equals(c.getString(4))&&constraints.recordedInRange(c.getLong(5));
                if(allowed&&"device_event".equals(type)){
                    String group=c.getString(6)+"\u0000"+c.getString(7);Boolean permitted=categoricalVisibility?categorical.get(group):devices.get(id);
                    if(permitted==null){JSONObject event=store.getDeviceEvent(id);permitted=event!=null&&deviceVisibility!=null&&deviceVisibility.test(event);if(categoricalVisibility)categorical.put(group,permitted);else devices.put(id,permitted);}
                    allowed=permitted;
                }
                if(!allowed)denied.add(label);
            }
        }}
        graph.setExcludedLabels(denied.array());lastExcludedCount=denied.size;
        if(denied.size>=graph.count()){lastSearchMillis=now()-started;publishDiagnostics();return new JSONArray();}
        int wanted=Math.max(1,Math.min(100,limit)),shortlist=Math.min(4096,Math.max(200,wanted*8));
        Map<Long,IndexedRow> checked=new HashMap<>();List<IndexedRow> eligible=new ArrayList<>();
        while(true){
            NativeAnnIndex.SearchResult hits=graph.search(query,shortlist,Math.max(512,shortlist));eligible.clear();
            Map<Long,IndexedRow> rows=metadataRows(hits.labels);lastCandidateCount=Math.max(lastCandidateCount,hits.labels.length);
            for(long label:hits.labels){
                IndexedRow row=checked.get(label);
                if(row==null){row=rows.get(label);if(row==null)continue;checked.put(label,row);lastCandidateBytes+=row.vector.length;
                    JSONObject source="message".equals(row.type)?store.getMessage(row.id):store.getDeviceEvent(row.id);
                    row.allowed=source!=null&&row.hash.equals(source.optString("content_hash"))&&constraints.recordedInRange(source.optLong("message".equals(row.type)?"created_at":"observed_at"));
                    if(row.allowed&&"device_event".equals(row.type))row.allowed=deviceVisibility!=null&&deviceVisibility.test(source);
                    if(row.allowed&&constraints.entities.length()>0){String text;
                        if("message".equals(row.type))text=store.readOriginalText(row.id,row.offset,row.length).optString("text");
                        else {String payload=source.optJSONObject("payload")==null?source.optString("payload"):source.optJSONObject("payload").toString();int from=(int)Math.min(payload.length(),row.offset),to=Math.min(payload.length(),from+row.length);text=payload.substring(from,to);}
                        String normalized=MemoryText.normalize(text);for(int i=0;i<constraints.entities.length();i++)if(!normalized.contains(MemoryText.normalize(constraints.entities.optString(i)))){row.allowed=false;break;}
                    }
                    if(row.allowed)row.score=VectorCodec.cosine(query,VectorCodec.decode(row.vector,encoder.dimensions()));
                }
                if(row.allowed&&row.score>0)eligible.add(row);
            }
            eligible.sort(Comparator.comparingDouble((IndexedRow r)->r.score).reversed());
            int diversified=diverseCount(eligible,wanted);
            if(diversified>=wanted||hits.labels.length<shortlist||shortlist>=4096||now()-started>=1500){constraintLimitReached=diversified<wanted&&hits.labels.length>=shortlist;break;}
            shortlist=Math.min(4096,shortlist*2);
        }
        JSONArray output=new JSONArray();Map<String,Integer> perSource=new HashMap<>();
        for(IndexedRow row:eligible){String source=row.type+":"+row.id;int used=perSource.getOrDefault(source,0);if(used>=2)continue;perSource.put(source,used+1);JSONObject hit=new JSONObject();put(hit,"type",row.type);put(hit,"id",row.id);put(hit,"offsetChars",row.offset);put(hit,"lengthChars",row.length);put(hit,"hash",row.hash);put(hit,"modelId",encoder.modelId());put(hit,"semanticSimilarity",row.score);put(hit,"approximateCandidateSelection",true);put(hit,"scoreComputedFromCanonicalF32",true);output.put(hit);if(output.length()>=wanted)break;}
        lastSearchMillis=now()-started;publishDiagnostics();return output;
    }
    /** Reading the last completed snapshot cannot wait behind a native graph build on the UI. */
    public JSONObject diagnostics(){try{return new JSONObject(publishedDiagnostics);}catch(Exception absent){JSONObject d=new JSONObject();put(d,"status","not_initialized");put(d,"approximateNearestNeighbor",true);return d;}}
    private JSONObject publishDiagnostics(){
        JSONObject d=new JSONObject();put(d,"method","native_hnsw_int8_cosine_then_exact_f32_shortlist_rerank");put(d,"profile",NativeAnnIndex.PROFILE);put(d,"approximateNearestNeighbor",true);put(d,"originalsAuthoritative",true);put(d,"derivedCacheRebuildable",true);put(d,"status",status);put(d,"ready",ready);put(d,"readinessScope","last_completed_maintenance; current_generation_rechecked_before_query");put(d,"canonicalChunkCountKnown",corpusCountKnown);put(d,"canonicalChunkCount",corpusCountKnown?canonicalCount:JSONObject.NULL);put(d,"indexedChunkCount",graph==null?0:graph.count());put(d,"candidateCoverage",!corpusCountKnown?JSONObject.NULL:canonicalCount==0?1.0:(double)(graph==null?0:graph.count())/canonicalCount);put(d,"coverageScope","currently_generated_canonical_chunks; raw_source_encoding_coverage_is_separate");put(d,"fullCorpusGraphCoverage",ready);put(d,"topKRecallGuaranteed",false);put(d,"canonicalFingerprintScope","encoder_profile+mutation_generation+ordered_rowid_source_hash_range_dimension_encoding");put(d,"canonicalFloat32CacheRetained",true);put(d,"excludedBeforeTopK",lastExcludedCount);put(d,"constraintsFilterMode",categoricalVisibility?"query_local_current_source_category_policy_then_full_candidate_recheck":"full_device_record_predicate");put(d,"candidateCount",lastCandidateCount);put(d,"canonicalCandidateBytes",lastCandidateBytes);put(d,"lastSearchMillis",lastSearchMillis);put(d,"constraintCandidateLimitReached",constraintLimitReached);put(d,"realDeviceLatencyMeasured",false);if(!error.isEmpty())put(d,"error",error);publishedDiagnostics=d.toString();return d;
    }
    public synchronized void close(){ready=false;if(graph!=null){graph.close();graph=null;}status="closed";publishDiagnostics();}
    private long generation(){synchronized(store){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT generation FROM semantic_ann_generation WHERE id=1",null)){return c.moveToFirst()?c.getLong(0):-1;}}}
    private long countRows(){return scalar("SELECT COUNT(*) FROM semantic_chunks WHERE model_id=? AND dimension=? AND encoding=?");}
    private long maxRowId(){return scalar("SELECT COALESCE(MAX(rowid),0) FROM semantic_chunks WHERE model_id=? AND dimension=? AND encoding=?");}
    private long scalar(String sql){synchronized(store){try(Cursor c=store.getReadableDatabase().rawQuery(sql,parameters())){return c.moveToFirst()?c.getLong(0):0;}}}
    private String[] parameters(){return new String[]{encoder.modelId(),String.valueOf(encoder.dimensions()),VectorCodec.ENCODING};}
    private List<IndexedRow> vectorRows(long after,int limit){List<IndexedRow> rows=new ArrayList<>();synchronized(store){String[] p=Arrays.copyOf(parameters(),5);p[3]=String.valueOf(after);p[4]=String.valueOf(limit);try(Cursor c=store.getReadableDatabase().rawQuery("SELECT rowid,source_type,source_id,source_hash,chunk_offset,chunk_length,vector FROM semantic_chunks WHERE model_id=? AND dimension=? AND encoding=? AND rowid>CAST(? AS INTEGER) ORDER BY rowid LIMIT ?",p)){while(c.moveToNext())rows.add(row(c));}}return rows;}
    private Map<Long,IndexedRow> metadataRows(long[] ids){Map<Long,IndexedRow> rows=new HashMap<>();for(int base=0;base<ids.length;base+=256){int end=Math.min(ids.length,base+256);StringBuilder in=new StringBuilder();String[] args=new String[end-base+3];String[] p=parameters();System.arraycopy(p,0,args,0,3);for(int i=base;i<end;i++){if(i>base)in.append(',');in.append('?');args[i-base+3]=String.valueOf(ids[i]);}synchronized(store){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT rowid,source_type,source_id,source_hash,chunk_offset,chunk_length,vector FROM semantic_chunks WHERE model_id=? AND dimension=? AND encoding=? AND rowid IN("+in+")",args)){while(c.moveToNext()){IndexedRow r=row(c);rows.put(r.rowId,r);}}}}return rows;}
    private String fingerprint(long generation,long count)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");digest(hash,NativeAnnIndex.PROFILE);digest(hash,encoder.modelId());digest(hash,generation+":"+count);long after=0;
        while(true){int n=0;synchronized(store){String[] p=Arrays.copyOf(parameters(),4);p[3]=String.valueOf(after);try(Cursor c=store.getReadableDatabase().rawQuery("SELECT rowid,source_type,source_id,source_hash,chunk_offset,chunk_length,dimension,encoding FROM semantic_chunks WHERE model_id=? AND dimension=? AND encoding=? AND rowid>CAST(? AS INTEGER) ORDER BY rowid LIMIT 256",p)){while(c.moveToNext()){after=c.getLong(0);for(int i=0;i<8;i++)digest(hash,c.getString(i));n++;}}}if(n<256)break;}
        StringBuilder out=new StringBuilder(64);for(byte value:hash.digest())out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
    private static void digest(MessageDigest hash,String value){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);}
    private static IndexedRow row(Cursor c){IndexedRow r=new IndexedRow();r.rowId=c.getLong(0);r.type=c.getString(1);r.id=c.getLong(2);r.hash=c.getString(3);r.offset=c.getLong(4);r.length=c.getInt(5);r.vector=c.getBlob(6);return r;}
    private static int capacityFor(long count){if(count>NativeAnnIndex.MAX_ELEMENTS)throw new IllegalArgumentException("ANN corpus exceeds native capacity; shard/reconfigure the derived cache");return (int)Math.max(1024,Math.min(NativeAnnIndex.MAX_ELEMENTS,count+Math.max(256,count/8)));}
    private static int diverseCount(List<IndexedRow> rows,int maximum){Map<String,Integer> sources=new HashMap<>();int count=0;for(IndexedRow row:rows){String key=row.type+":"+row.id;int used=sources.getOrDefault(key,0);if(used<2){sources.put(key,used+1);if(++count>=maximum)break;}}return count;}
    private static void put(JSONObject object,String key,Object value){MemoryPolicy.put(object,key,value);}
    private static long now(){return System.nanoTime()/1_000_000;}
    private static final class IndexedRow {long rowId,id,offset;String type,hash;int length;byte[] vector;boolean allowed;double score;}
    private static final class Longs{long[] values=new long[64];int size;void add(long v){if(size==values.length)values=Arrays.copyOf(values,values.length*2);values[size++]=v;}long[] array(){return Arrays.copyOf(values,size);}}
}
