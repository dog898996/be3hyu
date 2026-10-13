package com.hoyoung.assistant.memory;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Derived, rebuildable local vectors. The original database/files remain authoritative.
 * Full-corpus scans use bounded top-k memory; local encoding happens outside the database lock. */
public final class SemanticIndex {
    private final HoyoungStore store;
    private final SemanticEncoder encoder;
    private volatile java.util.function.Predicate<JSONObject> deviceVisibility=event->true;
    public void setDeviceSourceVisibility(java.util.function.Predicate<JSONObject> visibility){deviceVisibility=visibility==null?event->true:visibility;}
    public boolean visibleDevice(long id){JSONObject event=store.getDeviceEvent(id);return event!=null&&deviceVisibility.test(event);}
    public boolean visibleMemory(String key,int revision){synchronized(store){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT source_id FROM memory_sources WHERE memory_key=? AND revision=? AND source_type='device_event'",new String[]{key,String.valueOf(revision)})){while(c.moveToNext())if(!visibleDevice(c.getLong(0)))return false;}}return true;}
    private long scannedVectors,scannedBytes,scanMillis;
    private boolean scanLimitReached;
    public synchronized void resetScanDiagnostics(){scannedVectors=0;scannedBytes=0;scanMillis=0;scanLimitReached=false;}
    public synchronized JSONObject scanDiagnostics(){JSONObject r=new JSONObject();MemoryPolicy.put(r,"method","paged_exact_cosine_with_1500ms_per_index_budget");MemoryPolicy.put(r,"scannedVectors",scannedVectors);MemoryPolicy.put(r,"scannedVectorBytes",scannedBytes);MemoryPolicy.put(r,"elapsedMillis",scanMillis);MemoryPolicy.put(r,"limitReached",scanLimitReached);MemoryPolicy.put(r,"approximateNearestNeighbor",false);return r;}
    public SemanticIndex(HoyoungStore store, SemanticEncoder encoder) { this.store = store; this.encoder = encoder; ensureSchema(); }

    private void ensureSchema() {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.execSQL("CREATE TABLE IF NOT EXISTS semantic_chunks (source_type TEXT NOT NULL, source_id INTEGER NOT NULL, source_hash TEXT NOT NULL, "
                    + "chunk_offset INTEGER NOT NULL, chunk_length INTEGER NOT NULL, model_id TEXT NOT NULL, dimension INTEGER NOT NULL, encoding TEXT NOT NULL, "
                    + "vector BLOB NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(source_type,source_id,source_hash,chunk_offset,model_id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS semantic_chunks_model ON semantic_chunks(model_id,source_type,source_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS semantic_chunks_scan ON semantic_chunks(model_id,source_type,source_id,chunk_offset)");
            db.execSQL("CREATE TABLE IF NOT EXISTS semantic_index_progress (source_type TEXT NOT NULL, source_id INTEGER NOT NULL, source_hash TEXT NOT NULL, "
                    + "model_id TEXT NOT NULL, next_offset INTEGER NOT NULL, complete INTEGER NOT NULL, updated_at INTEGER NOT NULL, "
                    + "PRIMARY KEY(source_type,source_id,model_id))");
        }
    }
    public synchronized JSONObject indexPending(int maximumSources, long timeBudgetMillis) {
        JSONObject report = diagnostics();
        if (!ready()) { MemoryPolicy.put(report, "status", "encoder_not_ready"); return report; }
        int max = Math.max(1, Math.min(64, maximumSources));
        long deadline = monotonicMillis() + Math.max(10, Math.min(30_000, timeBudgetMillis));
        int memories = 0, sources = 0, chunks = 0; String error = "";
        try {
            List<JSONObject> pending = pendingMemories(max);
            for (JSONObject memory : pending) {
                if (monotonicMillis() >= deadline) break;
                String key = MemoryPolicy.key(memory), hash = memory.optString("content_hash");
                if(!visibleMemory(key,memory.optInt("revision")))continue;
                float[] vector = VectorCodec.normalize(encoder.encode(document(memory)), encoder.dimensions());
                synchronized (store) {
                    JSONObject current = store.getMemory(key);
                    if (current != null && hash.equals(current.optString("content_hash"))) {
                        ContentValues value = vectorValues(vector); value.put("memory_key", key); value.put("revision", memory.optInt("revision")); value.put("source_hash", hash);
                        store.getWritableDatabase().insertWithOnConflict("semantic_vectors", null, value, SQLiteDatabase.CONFLICT_REPLACE); memories++;
                    }
                }
            }
            for (JSONObject source : pendingSources(max)) {
                if (monotonicMillis() >= deadline) break;
                sources++;
                String type = source.optString("type"), hash = source.optString("hash"); long id = source.optLong("id"), offset = source.optLong("offset");
                if("device_event".equals(type)&&!visibleDevice(id)){saveProgress(type,id,hash,offset,false,null,0);continue;}
                try(SourceWindow window=openWindow(type,id,offset)){
                for (int part = 0; part < 32 && monotonicMillis() < deadline; part++) {
                    String text = window.text(); long total = window.total;
                    if (text.isEmpty()) { saveProgress(type, id, hash, offset, true, null, 0); break; }
                    if(text.trim().isEmpty()){long next=offset+text.length();boolean complete=next>=total;if(!saveProgress(type,id,hash,next,complete,null,0))break;window.advance((int)(next-offset));offset=next;if(complete)break;continue;}
                    int fitting = encoder.fittingPrefixLength(text);
                    if (fitting <= 0 || fitting > text.length()) throw new IllegalStateException("Encoder has no valid source token budget");
                    if (fitting < text.length() && Character.isHighSurrogate(text.charAt(fitting - 1))) fitting--;
                    if (fitting <= 0) throw new IllegalStateException("Encoder chunk would split a surrogate pair");
                    String exact = text.substring(0, fitting);
                    float[] vector = VectorCodec.normalize(encoder.encode(exact), encoder.dimensions());
                    long end = offset + fitting; boolean complete = end >= total;
                    int overlap = complete ? 0 : Math.min(40, Math.max(0, fitting / 5));
                    if (overlap > 0 && Character.isLowSurrogate(exact.charAt(fitting - overlap))) overlap--;
                    long next = complete ? end : end - overlap;
                    if (!saveProgress(type, id, hash, next, complete, vector, fitting, offset)) break;
                    chunks++;window.advance((int)(next-offset)); offset = next; if (complete) break;
                }
                }
            }
        } catch (Exception unavailable) { error = unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage(); }
        report = diagnostics(); MemoryPolicy.put(report, "status", error.isEmpty() ? "indexed_batch" : "index_error");
        MemoryPolicy.put(report, "indexedMemoriesThisBatch", memories); MemoryPolicy.put(report, "sourcesVisitedThisBatch", sources);
        MemoryPolicy.put(report, "chunksAddedThisBatch", chunks); if (!error.isEmpty()) MemoryPolicy.put(report, "error", error);
        return report;
    }

    private List<JSONObject> pendingMemories(int limit) {
        List<JSONObject> rows = new ArrayList<>(); List<String> keys = new ArrayList<>();
        synchronized (store) {
            try (Cursor c = store.getReadableDatabase().rawQuery("SELECT m.memory_key FROM memories m LEFT JOIN semantic_vectors v "
                    + "ON v.memory_key=m.memory_key AND v.model_id=? AND v.source_hash=m.content_hash AND v.revision=m.revision "
                    + "WHERE v.memory_key IS NULL ORDER BY m.updated_at DESC LIMIT ?", new String[]{encoder.modelId(), String.valueOf(limit)})) {
                while (c.moveToNext()) keys.add(c.getString(0));
            }
            for (String key : keys) { JSONObject row = store.getMemory(key); if (row != null) rows.add(row); }
        }
        return rows;
    }
    private List<JSONObject> pendingSources(int limit) {
        List<JSONObject> rows = new ArrayList<>();
        synchronized (store) {
            String union = "SELECT 'message' AS type,id,content_hash AS hash FROM messages UNION ALL SELECT 'device_event',id,content_hash FROM device_events";
            try (Cursor c = store.getReadableDatabase().rawQuery("SELECT s.type,s.id,s.hash,CASE WHEN p.source_hash=s.hash THEN p.next_offset ELSE 0 END "
                    + "FROM (" + union + ") s LEFT JOIN semantic_index_progress p ON p.source_type=s.type AND p.source_id=s.id AND p.model_id=? "
                    + "WHERE p.source_id IS NULL OR p.source_hash!=s.hash OR p.complete=0 ORDER BY COALESCE(p.updated_at,0) ASC,s.id ASC LIMIT ?",
                    new String[]{encoder.modelId(), String.valueOf(limit)})) {
                while (c.moveToNext()) { JSONObject row = new JSONObject(); MemoryPolicy.put(row,"type",c.getString(0)); MemoryPolicy.put(row,"id",c.getLong(1)); MemoryPolicy.put(row,"hash",c.getString(2)); MemoryPolicy.put(row,"offset",c.getLong(3)); rows.add(row); }
            }
        }
        return rows;
    }
    private boolean saveProgress(String type,long id,String hash,long next,boolean complete,float[] vector,int length) { return saveProgress(type,id,hash,next,complete,vector,length,next); }
    private boolean saveProgress(String type,long id,String hash,long next,boolean complete,float[] vector,int length,long offset) {
        return store.withTransaction(db -> {
            JSONObject current = "message".equals(type) ? store.getMessage(id) : store.getDeviceEvent(id);
            if (current == null || !hash.equals(current.optString("content_hash"))) return false;
            if (vector != null) {
                ContentValues chunk = vectorValues(vector); chunk.put("source_type",type); chunk.put("source_id",id); chunk.put("source_hash",hash); chunk.put("chunk_offset",offset); chunk.put("chunk_length",length);
                db.insertWithOnConflict("semantic_chunks",null,chunk,SQLiteDatabase.CONFLICT_REPLACE);
            }
            ContentValues p = new ContentValues(); p.put("source_type",type); p.put("source_id",id); p.put("source_hash",hash); p.put("model_id",encoder.modelId()); p.put("next_offset",next); p.put("complete",complete ? 1 : 0); p.put("updated_at",System.currentTimeMillis());
            db.insertWithOnConflict("semantic_index_progress",null,p,SQLiteDatabase.CONFLICT_REPLACE); return true;
        });
    }
    private ContentValues vectorValues(float[] vector) {
        ContentValues value = new ContentValues(); value.put("model_id",encoder.modelId()); value.put("dimension",encoder.dimensions()); value.put("encoding",VectorCodec.ENCODING); value.put("vector",VectorCodec.encode(vector,encoder.dimensions())); value.put("updated_at",System.currentTimeMillis()); return value;
    }
    static String document(JSONObject memory) { return memory.optString("content") + "\n" + MemoryPolicy.metadata(memory).optString("retrievalText",MemoryPolicy.metadata(memory).toString()); }
    public boolean ready() { return encoder != null && encoder.isReady() && encoder.isNeural(); }

    public JSONArray memories(float[] query,int limit) {
        if (!ready()) return new JSONArray();
        long started=monotonicMillis();PriorityQueue<Hit> top=heap();String after="";
        while(true){
            List<VectorRow> page=new ArrayList<>();
            synchronized(store){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT v.memory_key,v.source_hash,v.dimension,v.encoding,v.vector,v.revision FROM semantic_vectors v JOIN memories m ON m.memory_key=v.memory_key AND m.revision=v.revision AND m.content_hash=v.source_hash WHERE v.model_id=? AND v.memory_key>? ORDER BY v.memory_key LIMIT 128",new String[]{encoder.modelId(),after})){
                while(c.moveToNext()){VectorRow row=new VectorRow();row.key=c.getString(0);row.hash=c.getString(1);row.dimension=c.getInt(2);row.encoding=c.getString(3);row.bytes=c.getBlob(4);row.revision=c.getInt(5);page.add(row);}
            }}
            for(VectorRow row:page){after=row.key;if(!visibleMemory(row.key,row.revision))continue;double score=score(query,row);if(score>0){Hit hit=new Hit(row.key,score);hit.hash=row.hash;offer(top,hit,limit);}}
            if(page.size()<128)break;if(monotonicMillis()-started>=1500){scanLimitReached=true;break;}
        }
        JSONArray result=new JSONArray();for(Hit hit:sorted(top)){JSONObject memory=store.getMemory(hit.key);if(memory!=null&&hit.hash.equals(memory.optString("content_hash"))&&visibleMemory(hit.key,memory.optInt("revision"))){String content=memory.optString("content");if(content.length()>8192){MemoryPolicy.put(memory,"contentChars",content.length());MemoryPolicy.put(memory,"content",content.substring(0,8192));MemoryPolicy.put(memory,"contentExcerpt",true);}MemoryPolicy.put(memory,"semanticSimilarity",hit.score);result.put(memory);}}
        scanMillis+=monotonicMillis()-started;return result;
    }
    public JSONArray chunks(float[] query,MemoryQuery constraints,int limit) {
        if(!ready())return new JSONArray();PriorityQueue<Hit> top=heap();long started=monotonicMillis();String afterType="",checkedType="";long afterId=0,afterOffset=-1,checkedId=-1;boolean permitted=true;
        while(true){
            List<VectorRow> page=new ArrayList<>();
            synchronized(store){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT v.source_type,v.source_id,v.chunk_offset,v.chunk_length,v.source_hash,v.dimension,v.encoding,v.vector FROM semantic_chunks v LEFT JOIN messages m ON v.source_type='message' AND m.id=v.source_id LEFT JOIN device_events e ON v.source_type='device_event' AND e.id=v.source_id WHERE v.source_hash=COALESCE(m.content_hash,e.content_hash) AND v.model_id=? AND (CAST(? AS INTEGER)=0 OR COALESCE(m.created_at,e.observed_at)>=CAST(? AS INTEGER)) AND (CAST(? AS INTEGER)=0 OR COALESCE(m.created_at,e.observed_at)<CAST(? AS INTEGER)) AND (v.source_type,v.source_id,v.chunk_offset)>(?,CAST(? AS INTEGER),CAST(? AS INTEGER)) ORDER BY v.source_type,v.source_id,v.chunk_offset LIMIT 128",new String[]{encoder.modelId(),String.valueOf(constraints.from),String.valueOf(constraints.from),String.valueOf(constraints.to),String.valueOf(constraints.to),afterType,String.valueOf(afterId),String.valueOf(afterOffset)})){
                while(c.moveToNext()){VectorRow row=new VectorRow();row.type=c.getString(0);row.id=c.getLong(1);row.offset=c.getLong(2);row.length=c.getInt(3);row.hash=c.getString(4);row.dimension=c.getInt(5);row.encoding=c.getString(6);row.bytes=c.getBlob(7);page.add(row);}
            }}
            for(VectorRow value:page){afterType=value.type;afterId=value.id;afterOffset=value.offset;if(!checkedType.equals(value.type)||checkedId!=value.id){checkedType=value.type;checkedId=value.id;permitted=!"device_event".equals(value.type)||visibleDevice(value.id);}if(!permitted)continue;double score=score(query,value);if(score<=0)continue;JSONObject row=new JSONObject();MemoryPolicy.put(row,"type",value.type);MemoryPolicy.put(row,"id",value.id);MemoryPolicy.put(row,"offsetChars",value.offset);MemoryPolicy.put(row,"lengthChars",value.length);MemoryPolicy.put(row,"hash",value.hash);MemoryPolicy.put(row,"semanticSimilarity",score);Hit hit=new Hit(value.type+":"+value.id+":"+value.offset,score);hit.row=row;offerSource(top,hit,Math.max(1,limit));}
            if(page.size()<128)break;if(monotonicMillis()-started>=1500){scanLimitReached=true;break;}
        }
        JSONArray result=new JSONArray();for(Hit hit:sorted(top))result.put(hit.row);
        scanMillis+=monotonicMillis()-started;return result;
    }
    private double score(float[] query,VectorRow row) {
        if(row.dimension!=encoder.dimensions()||!VectorCodec.ENCODING.equals(row.encoding))return -1;
        try {scannedVectors++;scannedBytes+=row.bytes.length;return VectorCodec.cosine(query,VectorCodec.decode(row.bytes,encoder.dimensions()));}catch(RuntimeException corrupt){return -1;}
    }
    public JSONObject sourceRange(String type,long id,long offset,int length) throws java.io.IOException {
        if ("message".equals(type)) return store.readOriginalText(id,offset,length);
        JSONObject source=store.getDeviceEvent(id); if (source==null) throw new IllegalArgumentException("Unknown device source");
        String text=source.optJSONObject("payload")==null ? source.optString("payload") : source.optJSONObject("payload").toString();
        int start=(int)Math.min(text.length(),Math.max(0,offset)); if (start>0 && start<text.length() && Character.isLowSurrogate(text.charAt(start))) start--;
        int end=Math.min(text.length(),start+Math.max(0,length)); if (end<text.length() && end>start && Character.isHighSurrogate(text.charAt(end-1))) end--;
        JSONObject result=new JSONObject(); MemoryPolicy.put(result,"text",text.substring(start,end)); MemoryPolicy.put(result,"offsetChars",start); MemoryPolicy.put(result,"totalChars",text.length()); MemoryPolicy.put(result,"complete",end>=text.length()); MemoryPolicy.put(result,"contentHash",source.optString("content_hash"));MemoryPolicy.put(result,"recordHash",source.optString("content_hash"));return result;
    }
    public JSONObject diagnostics() {
        JSONObject result=new JSONObject(); MemoryPolicy.put(result,"semanticMode",ready()?"neural-local":encoder==null?"concept-fallback":"unavailable");
        MemoryPolicy.put(result,"encoderModel",encoder==null?JSONObject.NULL:encoder.modelId()); MemoryPolicy.put(result,"actualLmMeasured",false);
        synchronized (store) {
            SQLiteDatabase db=store.getReadableDatabase(); String model=encoder==null?"":encoder.modelId();
            MemoryPolicy.put(result,"semanticIndexedCount",scalar(db,"SELECT COUNT(*) FROM semantic_vectors v JOIN memories m ON v.memory_key=m.memory_key AND v.revision=m.revision AND v.source_hash=m.content_hash WHERE v.model_id=?",model));
            long total=store.messageCount()+store.deviceEventStats().optLong("count"); MemoryPolicy.put(result,"sourceTotals",total); MemoryPolicy.put(result,"memoryTotals",store.memoryCount());
            String sources="SELECT 'message' AS type,id,content_hash AS hash,raw_char_count AS chars FROM messages UNION ALL SELECT 'device_event',id,content_hash,length(payload) FROM device_events";
            long done=scalar(db,"SELECT COUNT(*) FROM semantic_index_progress p JOIN ("+sources+") s ON p.source_type=s.type AND p.source_id=s.id AND p.source_hash=s.hash WHERE p.model_id=? AND p.complete=1",model);
            long covered=scalar(db,"SELECT COALESCE(SUM(MIN(p.next_offset,s.chars)),0) FROM semantic_index_progress p JOIN ("+sources+") s ON p.source_type=s.type AND p.source_id=s.id AND p.source_hash=s.hash WHERE p.model_id=?",model);
            long totalChars=scalar(db,"SELECT COALESCE(SUM(chars),0) FROM ("+sources+")",null);
            MemoryPolicy.put(result,"semanticCompletedSources",done); MemoryPolicy.put(result,"sourceCoverage",total==0?1.0:(double)done/total); MemoryPolicy.put(result,"semanticCoveredChars",covered); MemoryPolicy.put(result,"sourceChars",totalChars); MemoryPolicy.put(result,"characterCoverage",totalChars==0?1.0:(double)covered/totalChars);
            MemoryPolicy.put(result,"semanticChunkCount",scalar(db,"SELECT COUNT(*) FROM semantic_chunks WHERE model_id=?",model)); MemoryPolicy.put(result,"vectorEncoding",VectorCodec.ENCODING); MemoryPolicy.put(result,"derivedCacheRebuildable",true);
        }
        return result;
    }
    private static long monotonicMillis(){return System.nanoTime()/1_000_000;}
    private SourceWindow openWindow(String type,long id,long offset)throws java.io.IOException{
        if("message".equals(type)){JSONObject source=store.getMessage(id);if(source==null)throw new IllegalArgumentException("Unknown message source");return new SourceWindow(store.openOriginalReader(id),offset,source.optLong("raw_char_count",source.optString("text").length()));}
        JSONObject event=store.getDeviceEvent(id);if(event==null)throw new IllegalArgumentException("Unknown device source");String text=event.optJSONObject("payload")==null?event.optString("payload"):event.optJSONObject("payload").toString();return new SourceWindow(new java.io.StringReader(text),offset,text.length());
    }
    private static final class SourceWindow implements AutoCloseable{
        final java.io.Reader reader;final long total;final StringBuilder buffer=new StringBuilder(8192);final char[] chars=new char[8192];boolean eof;
        SourceWindow(java.io.Reader reader,long offset,long total)throws java.io.IOException{this.reader=reader;this.total=total;try{long left=offset;while(left>0){int n=reader.read(chars,0,(int)Math.min(left,chars.length));if(n<0)throw new java.io.IOException("Original ended before saved semantic offset");left-=n;}fill();}catch(java.io.IOException|RuntimeException e){reader.close();throw e;}}
        void fill()throws java.io.IOException{while(buffer.length()<8192&&!eof){int n=reader.read(chars,0,8192-buffer.length());if(n<0)eof=true;else if(n>0)buffer.append(chars,0,n);}}
        String text(){int end=buffer.length();if(end>0&&Character.isHighSurrogate(buffer.charAt(end-1)))end--;return buffer.substring(0,end);}
        void advance(int count)throws java.io.IOException{if(count<0||count>buffer.length())throw new java.io.IOException("Invalid source window advance");buffer.delete(0,count);fill();}
        public void close()throws java.io.IOException{reader.close();}
    }
    private static long scalar(SQLiteDatabase db,String sql,String value) { try(Cursor c=db.rawQuery(sql,value==null?null:new String[]{value})) {return c.moveToFirst()?c.getLong(0):0;} }
    private static PriorityQueue<Hit> heap() {return new PriorityQueue<>(Comparator.comparingDouble(h->h.score));}
    private static void offer(PriorityQueue<Hit> top,Hit hit,int limit) {if(top.size()<limit) top.add(hit); else if(hit.score>top.peek().score) {top.poll();top.add(hit);}}
    private static List<Hit> sorted(PriorityQueue<Hit> heap) {List<Hit> result=new ArrayList<>(heap);result.sort((a,b)->Double.compare(b.score,a.score));return result;}
    private static void offerSource(PriorityQueue<Hit> top,Hit hit,int limit){Hit worst=null;int count=0;String type=hit.row.optString("type");long id=hit.row.optLong("id");for(Hit current:top)if(type.equals(current.row.optString("type"))&&id==current.row.optLong("id")){count++;if(worst==null||current.score<worst.score)worst=current;}if(count>=2){if(hit.score>worst.score){top.remove(worst);top.add(hit);}return;}offer(top,hit,limit);}
    private static class VectorRow{String key,hash,type,encoding;long id,offset;int length,dimension,revision;byte[] bytes;}
    private static class Hit {final String key;final double score;String hash;JSONObject row;Hit(String key,double score){this.key=key;this.score=score;}}
}
