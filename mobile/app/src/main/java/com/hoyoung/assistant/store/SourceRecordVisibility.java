package com.hoyoung.assistant.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.function.Predicate;

/** Snapshot-bound privacy of a canonical source and its known native parent links. */
public final class SourceRecordVisibility {
    private SourceRecordVisibility() { }
    public static boolean allowed(SQLiteDatabase db,SourcePrivacyLedger.Snapshot policy,String type,JSONObject row) {
        return allowed(db,policy,type,row,null);
    }
    /** Ephemeral compatibility check. No verdict is retained across a mutable database read. */
    public static boolean allowed(SQLiteDatabase db,SourcePrivacyLedger.Snapshot policy,String type,JSONObject row,Predicate<JSONObject> deviceScope) {
        return new ReadContext(db,null,policy,deviceScope).allowed(type,row);
    }
    /**
     * One source graph memo for one established, thread-owned immutable database view.
     * Close the supplied ReadSnapshot after the entire caller's read scope. Never reuse
     * this object for a new dispatch, restore, privacy generation or permission scope.
     * A mutable OS permission predicate must also be fenced by the caller before output.
     */
    public static final class ReadContext {
        private final SQLiteDatabase db;private final SourcePrivacyLedger.Snapshot policy;
        private final HoyoungStore.ReadSnapshot snapshot;private final Thread owner=Thread.currentThread();
        private final Predicate<JSONObject> deviceScope;
        private final java.util.Map<String,Byte> states=new java.util.HashMap<>();
        private final java.util.Map<String,JSONObject> sources=new java.util.HashMap<>();
        private final java.util.Map<String,String> settings=new java.util.HashMap<>();
        private long sourceQueries,settingQueries,derivedSourceQueries,verdictHits,examinedNodes;
        private static final byte VISITING=1,ALLOWED=2,DENIED=3;
        private static final class Link {final String type,hash;final long id;Link(String t,long i,String h){type=t;id=i;hash=h;}}
        private static final class Frame {
            final String key;final java.util.List<Link> links;int next;
            Frame(String key,java.util.List<Link> links){this.key=key;this.links=links;}
        }
        public ReadContext(HoyoungStore.ReadSnapshot snapshot,SourcePrivacyLedger.Snapshot policy){this(snapshot,policy,null);}
        public ReadContext(HoyoungStore.ReadSnapshot snapshot,SourcePrivacyLedger.Snapshot policy,Predicate<JSONObject> deviceScope) {
            this(database(snapshot),snapshot,policy,deviceScope);
        }
        private static SQLiteDatabase database(HoyoungStore.ReadSnapshot snapshot) {
            if(snapshot==null)throw new IllegalArgumentException("An established read snapshot is required");
            snapshot.requireOwner();if(!snapshot.database.isOpen())throw new IllegalStateException("The source read snapshot is closed");return snapshot.database;
        }
        private ReadContext(SQLiteDatabase db,HoyoungStore.ReadSnapshot snapshot,SourcePrivacyLedger.Snapshot policy,Predicate<JSONObject> deviceScope){
            if(db==null||policy==null)throw new IllegalArgumentException("A canonical database and frozen privacy policy are required");
            this.db=db;this.snapshot=snapshot;this.policy=policy;this.deviceScope=deviceScope;
        }
        private void requireScope() {
            if(Thread.currentThread()!=owner)throw new IllegalStateException("Source graph reads belong to their opening thread");
            if(snapshot!=null)snapshot.requireOwner();
            if(!db.isOpen())throw new IllegalStateException("The source graph database view is closed");
        }
        public JSONObject diagnostics() {
            requireScope();JSONObject result=new JSONObject();
            try{result.put("sourceQueries",sourceQueries).put("settingQueries",settingQueries).put("cachedVerdictHits",verdictHits)
                    .put("derivedSourceQueries",derivedSourceQueries)
                    .put("examinedNodes",examinedNodes).put("cachedSources",sources.size()).put("cachedVerdicts",states.size())
                    .put("privacyVersion",policy.version).put("immutableReadSnapshot",snapshot!=null).put("persistentCache",false);}
            catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}return result;
        }
        public boolean allowed(String type,JSONObject row) {
            requireScope();if(row==null||!sourceType(type)||integer(row.opt("id"),false)<0||!row.optString("content_hash").matches("[a-f0-9]{64}"))return false;
            String requested=identity(type,row);JSONObject original=read(type,row.optLong("id"));
            if(original==null||!row.optString("content_hash").equals(original.optString("content_hash"))){states.put(requested,DENIED);return false;}
            Byte prior=states.get(requested);if(prior!=null){verdictHits++;return prior==ALLOWED;}
            java.util.ArrayDeque<Frame> stack=new java.util.ArrayDeque<>();
            Frame first=frame(type,original);if(first==null){states.put(requested,DENIED);return false;}stack.push(first);states.put(first.key,VISITING);
            try{while(!stack.isEmpty()) {
                    Frame current=stack.peek();
                    if(current.next==current.links.size()){states.put(current.key,ALLOWED);stack.pop();continue;}
                    Link link=current.links.get(current.next++);JSONObject source=read(link.type,link.id);
                    if(source==null||!link.hash.isEmpty()&&!link.hash.equals(source.optString("content_hash")))return deny(stack,null);
                    String key=identity(link.type,source);Byte state=states.get(key);
                    if(state!=null){verdictHits++;if(state!=ALLOWED)return deny(stack,key);continue;}
                    Frame child=frame(link.type,source);if(child==null)return deny(stack,key);
                    states.put(child.key,VISITING);stack.push(child);
                }}catch(RuntimeException unavailable){deny(stack,null);throw unavailable;}
            return true;
        }
        private boolean deny(java.util.ArrayDeque<Frame> stack,String failed) {
            for(Frame active:stack)states.put(active.key,DENIED);
            if(failed!=null)states.put(failed,DENIED);return false;
        }
        private Frame frame(String type,JSONObject row) {
            examinedNodes++;
            if(row==null||!policy.allowedSource(type,row.optLong("id"),row.optString("content_hash")))return null;
            java.util.List<Link> links=new java.util.ArrayList<>();
            if("device_event".equals(type)) {
                JSONObject payload=row.optJSONObject("payload");
                if(payload==null)try{payload=new JSONObject(row.optString("payload"));}catch(org.json.JSONException invalid){return null;}
                if(deviceScope!=null&&!deviceScope.test(row))return null;
                if("notification".equals(row.optString("category"))&&payload!=null&&"com.hoyoung.assistant".equals(payload.optString("package"))
                        &&("assistant_reply".equals(payload.optString("sourceKind"))||payload.optBoolean("sourceReferencePresent"))) {
                    Object id=payload.opt("assistantMessageId");String hash=payload.optString("assistantSourceHash");
                    if(!payload.optBoolean("sourceReferencePresent")||!(id instanceof Number)||((Number)id).doubleValue()!=payload.optLong("assistantMessageId")
                            ||payload.optLong("assistantMessageId")<=0||!hash.matches("[a-f0-9]{64}"))return null;
                    links.add(new Link("message",payload.optLong("assistantMessageId"),hash));
                }
                return new Frame(identity(type,row),links);
            }
            if(!"message".equals(type))return null;
            String lineage=setting("answerSources:"+row.optLong("id"));
            if(lineage!=null){JSONObject recorded=wholeObject(lineage);if(recorded==null||integer(recorded.opt("version"),false)!=1||!refs(recorded.optJSONArray("sources"),links))return null;}
            if(!"user".equals(row.optString("role"))) {
                String request=row.isNull("request_id")?"":row.optString("request_id","");
                if(!request.isEmpty()) {
                    String locator=setting("requestSource:"+request);if(locator!=null){long parent=locator(locator);if(parent<0)return null;
                        if(parent>0){String expected=requestSourceHash(request,parent,links);if(expected==null)return null;links.add(new Link("message",parent,expected));}}
                }
                String text=row.optString("text").trim();
                if("system".equals(row.optString("role"))&&text.startsWith("{")) {
                    JSONObject nativeRecord=wholeObject(text);
                    // An incomplete/malformed native-looking record cannot prove that
                    // its source locators were absent beyond the readable prefix.
                    if(nativeRecord==null||!nativeLinks(nativeRecord,links))return null;
                }
            }
            return new Frame(identity(type,row),links);
        }
        private JSONObject read(String type,long id) {
            if(id<=0)return null;String table="message".equals(type)?"messages":"device_event".equals(type)?"device_events":null;if(table==null)return null;
            String locator=type+":"+id;if(sources.containsKey(locator))return sources.get(locator);
            // Ancestor text is needed only for native system linkage records. Do not
            // retain every owner's long raw text while walking an old reply DAG.
            String columns="message".equals(type)?"id,role,request_id,content_hash,raw_char_count,raw_content_hash,CASE WHEN role='system' THEN text ELSE '' END AS text":"*";
            sourceQueries++;
            try(Cursor row=db.rawQuery("SELECT "+columns+" FROM "+table+" WHERE id=?",new String[]{String.valueOf(id)})) {
                if(!row.moveToFirst()){sources.put(locator,null);return null;}JSONObject source=new JSONObject();
                for(int i=0;i<row.getColumnCount();i++)try{source.put(row.getColumnName(i),row.isNull(i)?JSONObject.NULL:row.getType(i)==Cursor.FIELD_TYPE_INTEGER?row.getLong(i):row.getType(i)==Cursor.FIELD_TYPE_FLOAT?row.getDouble(i):row.getString(i));}catch(org.json.JSONException invalid){return null;}
                if("device_event".equals(type))try{source.put("payload",new JSONObject(source.optString("payload")));}catch(org.json.JSONException invalid){return null;}
                sources.put(locator,source);return source;
            }
        }
        private static String identity(String type,JSONObject row){return type+":"+row.optLong("id")+":"+row.optString("content_hash");}
        private String requestSourceHash(String request,long id,java.util.List<Link> known) {
            String value=setting("requestSourceHash:"+request);if(value!=null)return value.matches("[a-f0-9]{64}")?value:null;
            return legacyParentHash(id,known);
        }
        private String legacyParentHash(long id,java.util.List<Link> known) {
            String exact="";
            for(Link link:known)if("message".equals(link.type)&&link.id==id&&!link.hash.isEmpty()) {
                if(!exact.isEmpty()&&!exact.equals(link.hash))return null;
                exact=link.hash;
            }
            // A numeric locator cannot prove the old parent's identity once that
            // ID has an excluded generation. New native bindings carry its hash.
            return exact.isEmpty()&&policy.hasExcludedMessageId(id)?null:exact;
        }
        private String setting(String key) {
            if(settings.containsKey(key))return settings.get(key);settingQueries++;
            try(Cursor row=db.rawQuery("SELECT value FROM settings WHERE setting_key=?",new String[]{key})) {
                String value=row.moveToFirst()?row.getString(0):null;settings.put(key,value);return value;
            }
        }
        private boolean refs(JSONArray references,java.util.List<Link> links) {
            if(references==null)return false;
            for(int i=0;i<references.length();i++)if(!typedRef(references.optJSONObject(i),links))return false;return true;
        }
        private boolean typedRef(JSONObject reference,java.util.List<Link> links) {
            if(reference==null)return false;
            String type=reference.optString("type",reference.optString("source_type"));
            long id=integer(reference.has("id")?reference.opt("id"):reference.opt("source_id"),false);
            String hash=reference.optString("hash",reference.optString("source_hash",reference.optString("recordHash")));
            if(!sourceType(type)||id<0||!hash.matches("[a-f0-9]{64}"))return false;
            if(reference.has("type")&&reference.has("source_type")&&!reference.optString("type").equals(reference.optString("source_type")))return false;
            if(reference.has("id")&&reference.has("source_id")&&integer(reference.opt("source_id"),false)!=id)return false;
            for(String field:new String[]{"hash","source_hash","recordHash"})if(reference.has(field)&&!hash.equals(reference.optString(field)))return false;
            links.add(new Link(type,id,hash));return true;
        }
        /** Follow structured native containers; quoted text is never parsed as instructions or links. */
        private boolean nativeLinks(JSONObject record,java.util.List<Link> links) {
            java.util.ArrayDeque<Object> pending=new java.util.ArrayDeque<>();pending.add(record);
            java.util.Set<Object> visited=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            while(!pending.isEmpty()) {
                Object current=pending.removeFirst();if(!visited.add(current))continue;
                if(current instanceof JSONArray){JSONArray array=(JSONArray)current;for(int i=0;i<array.length();i++){Object item=array.opt(i);if(item instanceof JSONObject||item instanceof JSONArray)pending.add(item);}continue;}
                JSONObject object=(JSONObject)current;
                for(String field:new String[]{"sourceRefs","originalEvidence","evidenceQuotes"})if(object.has(field)&&!refs(object.optJSONArray(field),links))return false;
                if(object.has("assetRefs")) {JSONArray assets=object.optJSONArray("assetRefs");if(assets==null)return false;
                    for(int i=0;i<assets.length();i++){JSONObject asset=assets.optJSONObject(i);if(asset==null)return false;long id=integer(asset.opt("messageId"),false);if(id<0)return false;
                        String expected=asset.optString("recordHash");if(expected.isEmpty())expected=legacyParentHash(id,links);
                        if(expected==null||!expected.isEmpty()&&!expected.matches("[a-f0-9]{64}"))return false;links.add(new Link("message",id,expected));}}
                if(object.has("nativeResultSources")) {
                    JSONObject provenance=object.optJSONObject("nativeResultSources");
                    if(provenance==null||integer(provenance.opt("version"),false)!=1||!refs(provenance.optJSONArray("sources"),links))return false;
                }
                if(object.has("nativeOutcomeSource")) {
                    JSONObject outcome=object.optJSONObject("nativeOutcomeSource");if(outcome==null)return false;long id=integer(outcome.opt("messageId"),true);if(id<0)return false;
                    if(id>0){String expected=outcome.optString("recordHash");if(expected.isEmpty())expected=legacyParentHash(id,links);
                        if(expected==null||!expected.isEmpty()&&!expected.matches("[a-f0-9]{64}"))return false;links.add(new Link("message",id,expected));}
                }
                String typed=object.optString("type",object.optString("source_type"));
                if(sourceType(typed)&&(object.has("id")||object.has("source_id"))&&(object.has("hash")||object.has("source_hash")||object.has("recordHash"))&&!typedRef(object,links))return false;
                for(String key:new String[]{"sourceMessageId","assetMessageId","assetSourceMessageId","analysisSourceMessageId"})if(object.has(key)) {
                    long id=integer(object.opt(key),true);if(id<0)return false;if(id>0){String hashField="sourceMessageId".equals(key)?"sourceHash":"analysisSourceMessageId".equals(key)?"analysisSourceHash":"assetSourceHash";
                        String expected=object.optString(hashField);if(expected.isEmpty()&&"sourceMessageId".equals(key))expected=object.optString("recordHash");
                        if(expected.isEmpty())expected=legacyParentHash(id,links);if(expected==null||!expected.isEmpty()&&!expected.matches("[a-f0-9]{64}"))return false;links.add(new Link("message",id,expected));}
                }
                if(object.has("messageId")&&(object.has("recordHash")||object.has("originalStored")||object.has("contentHash")||object.has("reviewScope"))) {
                    long id=integer(object.opt("messageId"),false);if(id<0)return false;
                    String expected=object.optString("recordHash");if(object.has("recordHash")&&!expected.matches("[a-f0-9]{64}"))return false;
                    if(expected.isEmpty())expected=legacyParentHash(id,links);if(expected==null)return false;
                    if(object.has("contentHash")){JSONObject original=read("message",id);String body=object.optString("contentHash");
                        if(original==null||!body.matches("[a-f0-9]{64}")||!body.equals(original.optString("raw_content_hash")))return false;}
                    links.add(new Link("message",id,expected));
                }
                for(String field:new String[]{"nativeOutcomeSourceMessageId","previousReceiptSourceMessageId"})if(object.has(field)) {
                    long id=integer(object.opt(field),true);if(id<0)return false;if(id>0){String expected="nativeOutcomeSourceMessageId".equals(field)?object.optString("nativeOutcomeSourceHash"):object.optString("previousReceiptSourceHash");
                        if(expected.isEmpty())expected=legacyParentHash(id,links);if(expected==null||!expected.isEmpty()&&!expected.matches("[a-f0-9]{64}"))return false;links.add(new Link("message",id,expected));}
                }
                if(object.has("sourceRanges")){JSONArray ranges=object.optJSONArray("sourceRanges");if(ranges==null)return false;
                    for(int i=0;i<ranges.length();i++){JSONObject range=ranges.optJSONObject(i);if(range==null||integer(range.opt("messageId"),false)<0||!range.optString("recordHash").matches("[a-f0-9]{64}"))return false;}}
                if(object.optBoolean("sourceRefsTruncated")||object.has("sourceCount")&&object.optJSONArray("sourceRefs")!=null&&object.optLong("sourceCount")>object.optJSONArray("sourceRefs").length())
                    if(!completeMemoryRefs(object,links,pending))return false;
                if(object.has("receiptKey")&&!receiptLinks(object,links,pending))return false;
                for(String field:new String[]{"rawReceipt","rawCoverage","reviewBatch","cachedReviewBatch"})if(object.has(field)&&!("rawReceipt".equals(field)&&object.has("receiptKey"))) {
                    Object encoded=object.opt(field);if(encoded instanceof String){JSONObject nested=wholeObject((String)encoded);if(nested==null)return false;pending.add(nested);}
                    else if(!(encoded instanceof JSONObject))return false;
                }
                java.util.Iterator<String> keys=object.keys();while(keys.hasNext()){
                    String key=keys.next();Object item=object.opt(key);
                    if(item instanceof JSONObject||item instanceof JSONArray)pending.add(item);
                }
            }
            return true;
        }
        private boolean completeMemoryRefs(JSONObject memory,java.util.List<Link> links,java.util.ArrayDeque<Object> pending) {
            String key=memory.optString("key",memory.optString("memory_key")),hash=memory.optString("content_hash");long revision=integer(memory.opt("revision"),false);
            if(key.isEmpty()||revision<0||!hash.matches("[a-f0-9]{64}"))return false;
            derivedSourceQueries++;long count=0;JSONArray canonicalRefs=new JSONArray();
            try(Cursor refs=db.rawQuery("SELECT source_type,source_id,source_hash,source_role,observed_at FROM memory_sources WHERE memory_key=? AND revision=? ORDER BY source_type,source_id",new String[]{key,String.valueOf(revision)})) {
                while(refs.moveToNext()){String type=refs.getString(0),sourceHash=refs.getString(2);long id=refs.getLong(1);
                    if(!sourceType(type)||id<=0||sourceHash==null||!sourceHash.matches("[a-f0-9]{64}"))return false;
                    links.add(new Link(type,id,sourceHash));count++;
                    try{canonicalRefs.put(new JSONObject().put("type",type).put("id",id).put("hash",sourceHash).put("role",refs.getString(3)).put("observedAt",refs.getLong(4)));}
                    catch(org.json.JSONException invalid){return false;}}
            }
            if(integer(memory.opt("sourceCount"),true)!=count)return false;
            // Historical note rows intentionally have no content_hash column. Authenticate
            // the exact old document with its canonical dependency rows and metadata history.
            // The displayed citation prefix is never treated as the complete dependency set.
            derivedSourceQueries++;JSONObject document=new JSONObject();
            try(Cursor row=db.rawQuery("SELECT content,kind FROM memory_revisions WHERE memory_key=? AND revision=?",new String[]{key,String.valueOf(revision)})) {
                if(!row.moveToFirst())return false;
                try{document.put("key",key).put("content",row.getString(0)).put("kind",row.getString(1)).put("sourceRefs",canonicalRefs).put("metadata",new JSONObject());}
                catch(org.json.JSONException invalid){return false;}
            }
            if(hash.equals(documentHash(document)))return true;
            derivedSourceQueries++;
            try(Cursor metadata=db.rawQuery("SELECT metadata_json FROM memory_metadata_revisions WHERE memory_key=? AND memory_revision=? ORDER BY metadata_revision DESC",new String[]{key,String.valueOf(revision)})) {
                while(metadata.moveToNext()){JSONObject candidate=wholeObject(metadata.getString(0));if(candidate==null)return false;
                    try{document.put("metadata",candidate);}catch(org.json.JSONException invalid){return false;}
                    if(hash.equals(documentHash(document))){pending.add(candidate);return true;}}
            }
            return false;
        }
        private static String documentHash(JSONObject document) {
            try{byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(canonical(document).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder hash=new StringBuilder(64);for(byte value:bytes)hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hash.toString();}
            catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}
        }
        private boolean receiptLinks(JSONObject object,java.util.List<Link> links,java.util.ArrayDeque<Object> pending) {
                String key=object.optString("receiptKey");int split=key.indexOf(':',"actionReceipt:".length());
                if(!key.startsWith("actionReceipt:")||split<0)return false;
                String root=key.substring("actionReceipt:".length(),split);
                if(!root.matches("[A-Za-z0-9_.-]{1,450}"))return false;
                boolean bound=false;
                String rootSource=setting("requestSource:"+root);
                if(rootSource!=null){long id=locator(rootSource);if(id<0)return false;
                        if(id>0){String expected=requestSourceHash(root,id,links);if(expected==null)return false;links.add(new Link("message",id,expected));bound=true;}}
                JSONObject receipt=object.optJSONObject("rawReceipt");
                if(receipt==null&&object.opt("rawReceipt") instanceof String)receipt=wholeObject(object.optString("rawReceipt"));
                if(receipt==null)return false;
                if(receipt!=null)for(String field:new String[]{"nativeOutcomeSourceMessageId","previousReceiptSourceMessageId"})if(receipt.has(field)) {
                    if(integer(receipt.opt(field),true)<0)return false;
                    if(receipt.optLong(field)>0){String expected="nativeOutcomeSourceMessageId".equals(field)?receipt.optString("nativeOutcomeSourceHash"):legacyParentHash(receipt.optLong(field),links);
                        if(expected==null||!expected.isEmpty()&&!expected.matches("[a-f0-9]{64}"))return false;links.add(new Link("message",receipt.optLong(field),expected));bound=true;}
                }
                // Legacy archives have no native answerSources. Their known root
                // and prior/result links remain authoritative; absent provenance
                // cannot certify privacy after the owner has excluded sources.
                if(!bound&&policy.exclusionCount>0)return false;
                if(receipt!=null)pending.add(receipt);
            return true;
        }
        private static boolean sourceType(String type){return "message".equals(type)||"device_event".equals(type);}
        private static long locator(String value){try{return value!=null&&value.matches("0|[1-9][0-9]{0,18}")?Long.parseLong(value):-1;}catch(NumberFormatException invalid){return -1;}}
        private static long integer(Object value,boolean zero) {
            if(!(value instanceof Number))return -1;Number number=(Number)value;
            if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long){long id=number.longValue();return id>0||zero&&id==0?id:-1;}
            double exact=number.doubleValue();if(Double.isNaN(exact)||Double.isInfinite(exact)||exact<0||exact>=0x1.0p63||Math.rint(exact)!=exact)return -1;
            long id=number.longValue();return (id>0||zero&&id==0)&&(double)id==exact?id:-1;
        }
        private static JSONObject wholeObject(String raw) {
            if(raw==null||raw.isEmpty())return null;int depth=0;boolean quoted=false,escaped=false;
            for(int i=0;i<raw.length();i++){char value=raw.charAt(i);if(quoted){if(escaped)escaped=false;else if(value=='\\')escaped=true;else if(value=='"')quoted=false;}
                else if(value=='"')quoted=true;else if(value=='{'||value=='['){if(++depth>64)return null;}else if(value=='}'||value==']'){if(--depth<0)return null;}}
            if(depth!=0||quoted)return null;
            try{JSONTokener tokenizer=new JSONTokener(raw);Object object=tokenizer.nextValue();return object instanceof JSONObject&&tokenizer.nextClean()==0?(JSONObject)object:null;}
            catch(org.json.JSONException malformed){return null;}
        }
    }
    /** Recompute canonical stored-record hash before quoting native SQL text under it. */
    public static boolean canonicalRecordMatches(String type,JSONObject row) {
        JSONObject original=new JSONObject();
        try {
            if("message".equals(type))for(String key:new String[]{"id","role","text","request_id","created_at","raw_file_id","raw_char_count","raw_byte_count","raw_content_hash"})original.put(key,row.has(key)?row.opt(key):JSONObject.NULL);
            else if("device_event".equals(type))for(String key:new String[]{"category","source","payload","observed_at"})original.put(key,"payload".equals(key)&&row.optJSONObject(key)==null?new JSONObject(row.optString(key)):row.opt(key));
            else return false;
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(canonical(original).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hash=new StringBuilder(64);for(byte b:digest)hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return hash.toString().equals(row.optString("content_hash"));
        }catch(org.json.JSONException|java.security.NoSuchAlgorithmException unavailable){return false;}
    }
    private static String canonical(Object value) {
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;java.util.ArrayList<String> keys=new java.util.ArrayList<>();object.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key)));}return out.append('}').toString();}
        if(value instanceof org.json.JSONArray){org.json.JSONArray array=(org.json.JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.opt(i)));}return out.append(']').toString();}
        return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
}
