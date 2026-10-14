package com.hoyoung.assistant.runtime;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.SourcePrivacyLedger;
import com.hoyoung.assistant.store.SourceRecordVisibility;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.BiConsumer;

/** Durable action intent and a pending-only index; never loads the archived receipt history into an array. */
public final class ActionReceiptJournal {
    private static final String RECEIPTS = "actionReceipt:", RECEIPTS_END = "actionReceipt;";
    private static final String PENDING = "pendingReceipt:", PENDING_END = "pendingReceipt;";
    private static final String VERSION = "receiptIndexVersion", COUNT = "indexedReceiptCount", CURSOR = "receiptIndexCursor";
    private static final int PAGE_SIZE = 128;
    private final HoyoungStore store;
    private final Object migrationLock = new Object();
    public ActionReceiptJournal(HoyoungStore store) { this.store = store; }

    /** Save dispatching before the external effect. Source and pending index succeed or roll back together. */
    public void put(String key, JSONObject receipt) {
        validateKey(key); JSONObject copy = strict(receipt == null ? null : receipt.toString());
        store.withTransaction(db -> {
            boolean existed = value(db, key) != null;
            store.putSetting(key, copy.toString()); index(db, key, copy.optString("status"));
            if (!existed && "1".equals(store.getSetting(VERSION, "0")))
                store.putSetting(COUNT, String.valueOf(number(store.getSetting(COUNT, "0")) + 1));
            return null;
        });
    }

    /** Null means no receipt. Empty, malformed, or unknown-status records are errors, never new actions. */
    public JSONObject get(String key) {
        validateKey(key);
        synchronized (store) {
            String raw = value(store.getReadableDatabase(), key);
            if (raw == null) return null;
            try { return strict(raw); }
            catch (IllegalArgumentException corrupt) { throw new IllegalStateException("Stored action receipt is unreadable; automatic replay is forbidden", corrupt); }
        }
    }

    /** I/O-only model-facing fence. Internal get() retains raw owner reconciliation data. */
    public boolean readAllowed(String rootId,String actionId,Predicate<JSONObject> deviceVisibility)throws IOException {
        validateKey(RECEIPTS+rootId+":"+actionId);
        SourcePrivacyLedger.Snapshot privacy=store.getPrivacyLedger().capture();
        boolean allowed=store.withTransaction(db->{String raw=value(db,RECEIPTS+rootId+":"+actionId);if(raw==null)return false;
            try{return new ReceiptRead(db,privacy,deviceVisibility).allowed(rootId,strict(raw));}catch(IllegalArgumentException unreadable){return false;}});
        return allowed&&privacy.fingerprint.equals(store.getPrivacyLedger().fingerprint());
    }
    /** Also applies to an already-loaded cached result before any continuation consumes it. */
    public boolean receiptAllowed(String rootId,JSONObject receipt,Predicate<JSONObject> deviceVisibility)throws IOException {
        if(receipt==null)return false;SourcePrivacyLedger.Snapshot privacy=store.getPrivacyLedger().capture();
        boolean allowed=store.withTransaction(db->new ReceiptRead(db,privacy,deviceVisibility).allowed(rootId,receipt));
        return allowed&&privacy.fingerprint.equals(store.getPrivacyLedger().fingerprint());
    }

    /** Only canonical refs found in a native result/receipt; never a model's source claim. */
    public JSONArray nativeSourcesForReceipt(String rootId,JSONObject receipt,Predicate<JSONObject> deviceVisibility)throws IOException {
        if(receipt==null)throw new IllegalArgumentException("A native receipt is required");
        SourcePrivacyLedger.Snapshot privacy=store.getPrivacyLedger().capture();
        JSONArray refs=store.withTransaction(db->{ReceiptRead read=new ReceiptRead(db,privacy,deviceVisibility);
            if(!read.allowed(rootId,receipt))throw new IllegalStateException("Native receipt sources are unavailable");
            return new JSONArray(read.verifiedRefs.values());});
        if(!privacy.fingerprint.equals(store.getPrivacyLedger().fingerprint()))throw new IOException("Receipt source policy changed during read");
        return refs;
    }

    private static final class ReceiptRead {
        final SQLiteDatabase db;final SourcePrivacyLedger.Snapshot privacy;final Predicate<JSONObject> deviceVisibility;
        final java.util.Set<String> checked=new java.util.HashSet<>();final java.util.Map<String,String> boundHashes=new java.util.HashMap<>();final java.util.Map<String,JSONObject> verifiedRefs=new java.util.LinkedHashMap<>();boolean hasBoundSource;
        ReceiptRead(SQLiteDatabase db,SourcePrivacyLedger.Snapshot privacy,Predicate<JSONObject> visibility){this.db=db;this.privacy=privacy;this.deviceVisibility=visibility==null?row->false:visibility;}
        boolean allowed(String root,JSONObject receipt) {
            if(root==null||root.isEmpty())return false;
            boolean nativeWithoutOwner=false;
            JSONObject provenance=receipt.optJSONObject("nativeResultSources");if(receipt.has("nativeResultSources")) {
                if(provenance==null||provenance.optInt("version")!=1||provenance.optJSONArray("sources")==null||!refs(provenance.optJSONArray("sources")))return false;
            }
            String owner=value(db,"requestSource:"+root);if(owner!=null){try{long id=Long.parseLong(owner);if(id<0)return false;
                if(id==0){
                    if(provenance==null)return false;
                    try(Cursor request=db.rawQuery("SELECT kind FROM requests WHERE external_id=?",new String[]{root})) {
                        if(!request.moveToFirst()||!("autonomy".equals(request.getString(0))||"memory-review".equals(request.getString(0))))return false;
                        nativeWithoutOwner=true;
                    }
                }else{
                    String hash=value(db,"requestSourceHash:"+root);if(hash!=null&&!hash.matches("[a-f0-9]{64}"))return false;
                    try(Cursor row=db.rawQuery("SELECT role,request_id,text,content_hash FROM messages WHERE id=?",new String[]{String.valueOf(id)})){
                        if(!row.moveToFirst()||!root.equals(row.getString(1)))return false;
                        if(!"user".equals(row.getString(0))){
                            if(!"system".equals(row.getString(0))||hash==null||!hash.equals(row.getString(3))
                                    ||!UiAutomationProvenance.AGENT.equals(value(db,"inputActor:"+root)))return false;
                            JSONObject nativeInput;try{nativeInput=new JSONObject(row.getString(2));}catch(org.json.JSONException invalid){return false;}
                            if(!"agent_authored_input".equals(nativeInput.optString("type"))
                                    ||!UiAutomationProvenance.AGENT.equals(nativeInput.optString("submittedBy"))
                                    ||!UiAutomationProvenance.AGENT.equals(nativeInput.optString("inputActor"))
                                    ||!Boolean.FALSE.equals(nativeInput.opt("naturalLanguageOwnerUtterance"))
                                    ||!(nativeInput.opt("text") instanceof String))return false;
                            try(Cursor request=db.rawQuery("SELECT kind,text FROM requests WHERE external_id=?",new String[]{root})){
                                if(!request.moveToFirst()||!"agent-authored".equals(request.getString(0))
                                        ||!nativeInput.optString("text").equals(request.getString(1)))return false;
                            }
                        }
                    }
                    if(!source("message",id,hash==null?boundHashes.getOrDefault("message:"+id,""):hash))return false;
                }
            }catch(NumberFormatException invalid){return false;}}
            for(String field:new String[]{"nativeOutcomeSourceMessageId","previousReceiptSourceMessageId"})if(receipt.has(field)) {
                String expected=field.equals("nativeOutcomeSourceMessageId")?receipt.optString("nativeOutcomeSourceHash"):"";
                if(field.equals("nativeOutcomeSourceMessageId")&&!expected.matches("[a-f0-9]{64}"))return false;
                if(!integer(receipt.opt(field))||receipt.optLong(field)<=0||!source("message",receipt.optLong(field),expected))return false;
            }
            java.util.ArrayDeque<Object> pending=new java.util.ArrayDeque<>();JSONObject result=receipt.optJSONObject("result");if(result!=null)pending.add(result);
            java.util.Set<Object> visited=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            while(!pending.isEmpty()) {
                Object current=pending.removeFirst();if(!visited.add(current))continue;
                if(current instanceof JSONArray){JSONArray array=(JSONArray)current;for(int i=0;i<array.length();i++){Object item=array.opt(i);if(item instanceof JSONObject||item instanceof JSONArray)pending.add(item);}continue;}
                JSONObject object=(JSONObject)current;
                for(String key:new String[]{"sourceRefs","originalEvidence"})if(object.has(key)){JSONArray array=object.optJSONArray(key);if(array==null||!refs(array))return false;}
                if(object.has("messageId")&&object.has("recordHash")) {
                    if(!integer(object.opt("messageId"))||!object.optString("recordHash").matches("[a-f0-9]{64}")||!source("message",object.optLong("messageId"),object.optString("recordHash")))return false;
                }
                if(object.has("sourceMessageId")) {long id=object.optLong("sourceMessageId");Object encoded=object.opt("sourceMessageId");if(!(encoded instanceof Number)||((Number)encoded).doubleValue()!=id||id<0)return false;
                    if(id>0){String hash=object.optString("sourceHash",boundHashes.getOrDefault("message:"+id,""));if(hash.isEmpty()&&privacy.exclusionCount>0||!source("message",id,hash))return false;}}
                if("device_event".equals(object.optString("type"))&&object.has("id")&&object.has("hash")) {if(!typedRef(object))return false;}
                java.util.Iterator<String> keys=object.keys();while(keys.hasNext()){Object item=object.opt(keys.next());if(item instanceof JSONObject||item instanceof JSONArray)pending.add(item);}
            }
            // Legacy unbound bytes remain owner audit data. A tombstone elsewhere
            // cannot establish provenance for those bytes, so do not certify them.
            return hasBoundSource||nativeWithoutOwner||privacy.exclusionCount==0;
        }
        boolean refs(JSONArray refs){for(int i=0;i<refs.length();i++){JSONObject ref=refs.optJSONObject(i);if(ref==null||!typedRef(ref))return false;}return true;}
        boolean typedRef(JSONObject ref){String type=ref.optString("type",ref.optString("source_type"));Object id=ref.has("id")?ref.opt("id"):ref.opt("source_id");String hash=ref.optString("hash",ref.optString("source_hash"));if(!("message".equals(type)||"device_event".equals(type))||!integer(id)||!hash.matches("[a-f0-9]{64}"))return false;boolean allowed=source(type,((Number)id).longValue(),hash);if(allowed)boundHashes.put(type+":"+((Number)id).longValue(),hash);return allowed;}
        boolean source(String type,long id,String expected) {
            if(id<=0||!expected.isEmpty()&&!expected.matches("[a-f0-9]{64}"))return false;String identity=type+":"+id+":"+expected;if(checked.contains(identity))return true;
            if("message".equals(type)&&expected.isEmpty()&&privacy.hasExcludedMessageId(id))return false;
            String table="message".equals(type)?"messages":"device_events";JSONObject row=new JSONObject();
            try(Cursor cursor=db.rawQuery("SELECT * FROM "+table+" WHERE id=?",new String[]{String.valueOf(id)})) {
                if(!cursor.moveToFirst())return false;for(int i=0;i<cursor.getColumnCount();i++)put(row,cursor.getColumnName(i),cursor.isNull(i)?JSONObject.NULL:cursor.getType(i)==Cursor.FIELD_TYPE_INTEGER?cursor.getLong(i):cursor.getType(i)==Cursor.FIELD_TYPE_FLOAT?cursor.getDouble(i):cursor.getString(i));
            }
            if(!expected.isEmpty()&&!expected.equals(row.optString("content_hash"))||!SourceRecordVisibility.canonicalRecordMatches(type,row)||!SourceRecordVisibility.allowed(db,privacy,type,row,deviceVisibility))return false;
            if("device_event".equals(type)){try{put(row,"payload",new JSONObject(row.optString("payload")));}catch(org.json.JSONException malformed){return false;}if(!deviceVisibility.test(row))return false;}
            checked.add(identity);verifiedRefs.put(type+":"+id+":"+row.optString("content_hash"),object("type",type,"id",id,"hash",row.optString("content_hash")));hasBoundSource=true;return true;
        }
        static boolean integer(Object value){return value instanceof Number&&((Number)value).doubleValue()==((Number)value).longValue()&&((Number)value).longValue()>0;}
    }

    public JSONObject ensureIndex() { return ensureIndex(false); }

    /** Run in a background executor. A completed migration is reused unless receipt count or restore invalidates it. */
    public JSONObject ensureIndex(boolean forceRebuild) {
        synchronized (migrationLock) {
            boolean ready = store.withTransaction(db -> "1".equals(store.getSetting(VERSION, "0"))
                    && number(store.getSetting(COUNT, "0")) == receiptCount(db));
            if (ready && !forceRebuild) return object("ready", true, "migrated", false, "scanned", 0,
                    "indexedReceiptCount", number(store.getSetting(COUNT, "0")));
            if (forceRebuild || "1".equals(store.getSetting(VERSION, "0"))) {
                store.withTransaction(db -> {
                    db.delete("settings", "setting_key >= ? AND setting_key < ?", new String[]{PENDING, PENDING_END});
                    store.putSetting(VERSION, "0"); store.putSetting(COUNT, "0"); store.putSetting(CURSOR, "");
                    return null;
                });
            }
            String after = store.getSetting(CURSOR, ""); long scanned = 0, corrupt = 0;
            while (true) {
                List<String> page = keys(RECEIPTS, RECEIPTS_END, after, PAGE_SIZE);
                if (page.isEmpty()) break;
                for (String key : page) {
                    final String sourceKey = key;
                    boolean malformed = store.withTransaction(db -> {
                        String raw = value(db, sourceKey); boolean invalid = false;
                        if (raw != null) {
                            String status;
                            try { status = strict(raw).optString("status"); }
                            catch (IllegalArgumentException unreadable) { status = "corrupt"; invalid = true; }
                            index(db, sourceKey, status);
                        }
                        store.putSetting(CURSOR, sourceKey); store.putSetting(VERSION, "0");
                        return invalid;
                    });
                    scanned++; if (malformed) corrupt++; after = key;
                }
            }
            long count = store.withTransaction(db -> {
                long total = receiptCount(db); store.putSetting(COUNT, String.valueOf(total)); store.putSetting(VERSION, "1");
                store.recordEvent("action_receipt_index_ready", object("indexedReceiptCount", total, "pendingOnly", true,
                        "loadsEntireHistory", false, "forceRebuild", forceRebuild)); return total;
            });
            return object("ready", true, "migrated", true, "scanned", scanned, "corruptReceipts", corrupt,
                    "indexedReceiptCount", count);
        }
    }

    /** Fail closed while migration is unfinished. Ready lookups inspect one indexed pending key only. */
    public boolean hasUnresolved() {
        synchronized (store) {
            if (!"1".equals(store.getSetting(VERSION, "0"))) return true;
            try (Cursor pending = store.getReadableDatabase().rawQuery("SELECT 1 FROM settings WHERE setting_key >= ? AND setting_key < ? LIMIT 1", new String[]{PENDING, PENDING_END})) {
                return pending.moveToFirst();
            }
        }
    }

    /** Actual stored native receipts only. A provider's overall goal claim is not verified here. */
    public JSONObject completionSummary(String rootId,Predicate<String> mutatingTool,Predicate<JSONObject> confirmedNativeReceipt) {
        return completionSummary(rootId,mutatingTool,confirmedNativeReceipt,null);
    }
    public JSONObject completionSummary(String rootId,Predicate<String> mutatingTool,Predicate<JSONObject> confirmedNativeReceipt,Predicate<JSONObject> deviceVisibility) {
        validateRoot(rootId);
        if(mutatingTool==null||confirmedNativeReceipt==null)throw new IllegalArgumentException("Trusted native receipt classifiers required");
        return store.withTransaction(db->{
            SourcePrivacyLedger.Snapshot privacy;
            try { privacy=store.getPrivacyLedger().capture(); } catch(IOException unavailable) { throw new IllegalStateException("Native completion source policy unavailable",unavailable); }
            NativeAsyncCompletion callbacks=new NativeAsyncCompletion();
            forRoot(db,rootId,(key,raw)->{try{JSONObject receipt=strict(raw);if(new ReceiptRead(db,privacy,deviceVisibility).allowed(rootId,receipt))callbacks.observe(receipt);}catch(RuntimeException invalid){/* Never certify malformed callback evidence. */}});
            long[] counts=new long[12];MessageDigest snapshot=newDigest();snapshot.update(("native-receipts-v1:"+rootId).getBytes(StandardCharsets.UTF_8));
            forRoot(db,rootId,(key,raw)->{
                counts[0]++;hashReceipt(snapshot,key,raw);
                try{
                    JSONObject receipt=strict(raw);String name=receipt.optString("name");
                    if(name.isEmpty())throw new IllegalArgumentException("Native receipt name missing");
                    boolean mutation=mutatingTool.test(name);
                    if(mutation)counts[4]++;else counts[8]++;
                    if(!new ReceiptRead(db,privacy,deviceVisibility).allowed(rootId,receipt)){counts[11]++;return;}
                    if(!"completed".equals(receipt.optString("status"))){counts[3]++;return;}
                    JSONObject result=receipt.optJSONObject("result");
                    if(result==null||!(result.opt("ok") instanceof Boolean)){counts[10]++;return;}
                    if(Boolean.TRUE.equals(result.opt("ok"))){
                        counts[1]++;
                        if(mutation){if(confirmedNativeReceipt.test(receipt)||callbacks.confirms(receipt))counts[5]++;else counts[6]++;}
                    }else{counts[2]++;if(mutation)counts[7]++;else counts[9]++;}
                }catch(RuntimeException corruptOrClassifierFailed){counts[10]++;}
            });
            boolean privacyCurrent;
            try { privacyCurrent=privacy.fingerprint.equals(store.getPrivacyLedger().fingerprint()); } catch(IOException unavailable) { privacyCurrent=false; }
            boolean ready=counts[3]==0&&counts[6]==0&&counts[7]==0&&counts[10]==0&&counts[11]==0&&privacyCurrent;
            return object("ready",ready,"rootRequestId",rootId,"receiptCount",counts[0],"successfulCount",counts[1],"failedCount",counts[2],
                    "unfinishedCount",counts[3],"mutationCount",counts[4],"confirmedMutationCount",counts[5],"unconfirmedMutationCount",counts[6],
                    "failedMutationCount",counts[7],"readOnlyCount",counts[8],"failedReadOnlyCount",counts[9],"corruptCount",counts[10],
                    "sourceUnavailableCount",counts[11],
                    "snapshotHash",hex(snapshot.digest()),"proofScope","native_receipts_only","overallOutcomeVerified",false,"automaticReplay",false);
        });
    }

    /** Recheck inside a goal closure transaction so a changed native receipt cannot use an old summary. */
    public String snapshotHash(String rootId) {
        validateRoot(rootId);
        return store.withTransaction(db->{MessageDigest hash=newDigest();hash.update(("native-receipts-v1:"+rootId).getBytes(StandardCharsets.UTF_8));forRoot(db,rootId,(key,raw)->hashReceipt(hash,key,raw));return hex(hash.digest());});
    }
    private void forRoot(SQLiteDatabase db,String rootId,BiConsumer<String,String> visitor) {
        String prefix=RECEIPTS+rootId+":",end=RECEIPTS+rootId+";",after="";
        while(true){List<String> page=keys(prefix,end,after,PAGE_SIZE);if(page.isEmpty())return;
            for(String key:page){after=key;String actionId=key.substring(prefix.length());
                // A colon in another root's suffix is not an action id belonging to this root.
                if(actionId.matches("[A-Za-z0-9_.-]{1,100}"))visitor.accept(key,value(db,key));
            }
        }
    }
    private static void validateRoot(String rootId){if(rootId==null||!rootId.matches("[A-Za-z0-9_.:-]{1,450}"))throw new IllegalArgumentException("Invalid root request id");}
    private static MessageDigest newDigest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception unavailable){throw new IllegalStateException(unavailable);}}
    private static void hashReceipt(MessageDigest hash,String key,String raw){hash.update((byte)0);hash.update(key.getBytes(StandardCharsets.UTF_8));hash.update((byte)0);hash.update(digest(raw==null?"<missing>":raw).getBytes(StandardCharsets.UTF_8));}
    private static String hex(byte[] value){StringBuilder result=new StringBuilder();for(byte item:value)result.append(String.format(Locale.ROOT,"%02x",item&255));return result.toString();}

    /** Explicit owner reconciliation for one root. A closure never replays an action or verifies its external outcome. */
    public JSONObject resolveFor(String rootRequestId, boolean abandoned) {
        if (rootRequestId == null || !rootRequestId.matches("[A-Za-z0-9_.-]{1,450}")) throw new IllegalArgumentException("Invalid root request id");
        if (!"1".equals(store.getSetting(VERSION, "0"))) throw new IllegalStateException("Action receipt index is still being prepared");
        String prefix = RECEIPTS + rootRequestId + ":", end = RECEIPTS + rootRequestId + ";", after = "";
        int resolved = 0, corrupt = 0;
        while (true) {
            List<String> page = keys(prefix, end, after, PAGE_SIZE);
            if (page.isEmpty()) break;
            for (String key : page) {
                final String sourceKey = key;
                int changed = store.withTransaction(db -> {
                    // Archived final receipts may be huge. Their payloads are not loaded during owner reconciliation.
                    if (value(db, pendingKey(sourceKey)) == null) return 0;
                    String raw = value(db, sourceKey); if (raw == null) throw new IllegalStateException("Pending receipt source is missing");
                    JSONObject oldReceipt; boolean invalid = false;
                    try { oldReceipt = strict(raw); }
                    catch (IllegalArgumentException unreadable) { invalid = true; oldReceipt = new JSONObject(); }
                    if (!invalid && finalStatus(oldReceipt.optString("status"))) { index(db, sourceKey, oldReceipt.optString("status")); return 0; }
                    JSONObject preserved = store.addMessage("system", object("receiptKey", sourceKey, "rawReceipt", raw).toString(), null);
                    bindArchivedReceipt(db,rootRequestId,preserved.optLong("id"),oldReceipt,invalid);
                    JSONObject receipt = object("previousReceiptSourceMessageId", preserved.optLong("id"), "previousReceiptHash", digest(raw));
                    if (invalid) put(receipt, "previousReceiptUnreadable", true);
                    // Keep the request identity in the hot record; the full previous outcome stays in its immutable original.
                    String fingerprint = oldReceipt.optString("fingerprint");
                    if (!fingerprint.isEmpty()) put(receipt, "fingerprint", fingerprint.length() <= 512 ? fingerprint : "invalid_legacy_fingerprint_archived");
                    String name = oldReceipt.optString("name"); if (!name.isEmpty()) put(receipt, "name", name.substring(0, Math.min(256, name.length())));
                    for (String time : new String[]{"startedAt", "completedAt"}) if (oldReceipt.opt(time) instanceof Number) put(receipt, time, oldReceipt.opt(time));
                    put(receipt, "status", abandoned ? "abandoned" : "completed"); put(receipt, "ownerResolved", true);
                    put(receipt, "providerOutcomeVerified", false); put(receipt, "replayed", false);
                    if (abandoned) put(receipt, "ownerAbandoned", true);
                    else put(receipt, "result", object("ok", false, "code", "owner_resolved_outcome", "replayed", false, "providerOutcomeVerified", false));
                    put(receipt, "resolvedAt", System.currentTimeMillis());
                    put(sourceKey, receipt); return invalid ? 2 : 1;
                });
                if (changed > 0) resolved++; if (changed == 2) corrupt++; after = key;
            }
        }
        final int totalResolved = resolved, totalCorrupt = corrupt;
        store.recordEvent("action_receipts_owner_resolved", object("rootRequestId", rootRequestId, "resolvedCount", totalResolved,
                "corruptReceiptCount", totalCorrupt, "abandoned", abandoned, "automaticReplay", false, "providerOutcomeVerified", false));
        return object("ok", true, "resolvedCount", resolved, "corruptReceiptCount", corrupt,
                "abandoned", abandoned, "replayed", false, "providerOutcomeVerified", false);
    }

    private List<String> keys(String start, String end, String after, int limit) {
        List<String> result = new ArrayList<>(limit);
        synchronized (store) {
            try (Cursor cursor = store.getReadableDatabase().rawQuery("SELECT setting_key FROM settings WHERE setting_key >= ? AND setting_key < ? AND setting_key > ? ORDER BY setting_key ASC LIMIT ?",
                    new String[]{start, end, after, String.valueOf(limit)})) {
                while (cursor.moveToNext()) result.add(cursor.getString(0));
            }
        }
        return result;
    }
    /** Exact native parents are bound before an immutable raw reconciliation source is published. */
    private void bindArchivedReceipt(SQLiteDatabase db,String rootId,long sourceId,JSONObject receipt,boolean unreadable){
        JSONArray refs=new JSONArray();String parent=value(db,"requestSource:"+rootId);
        if(parent!=null)try{long id=Long.parseLong(parent);if(id>0)addRef(db,refs,"message",id,"");}catch(NumberFormatException invalid){unreadable=true;}
        for(String field:new String[]{"nativeOutcomeSourceMessageId","previousReceiptSourceMessageId"})if(receipt.optLong(field)>0)addRef(db,refs,"message",receipt.optLong(field),receipt.optString(field.equals("nativeOutcomeSourceMessageId")?"nativeOutcomeSourceHash":""));
        if(receipt.has("nativeResultSources")){JSONObject provenance=receipt.optJSONObject("nativeResultSources");JSONArray sources=provenance==null?null:provenance.optJSONArray("sources");if(provenance==null||provenance.optInt("version")!=1||sources==null)unreadable=true;
            for(int i=0;sources!=null&&i<sources.length();i++){JSONObject ref=sources.optJSONObject(i);if(ref==null){unreadable=true;continue;}addRef(db,refs,ref.optString("type"),ref.optLong("id"),ref.optString("hash"));}}
        JSONObject result=receipt.optJSONObject("result");
        if(result!=null){JSONObject original=result.optJSONObject("original");if(original!=null&&original.optLong("messageId")>0)addRef(db,refs,"message",original.optLong("messageId"),original.optString("recordHash"));
            JSONArray originals=result.optJSONArray("originalEvidence");for(int i=0;originals!=null&&i<originals.length();i++){JSONObject ref=originals.optJSONObject(i);if(ref!=null)addRef(db,refs,ref.optString("type"),ref.optLong("id"),ref.optString("hash"));}}
        // A malformed previous body can have unknown dependencies; preserve it for
        // owner auditing but do not turn it into available model evidence.
        store.putSetting("answerSources:"+sourceId,object("version",unreadable?0:1,"sources",refs,"nativeProvenance","owner_receipt_reconciliation").toString());
    }
    private static void addRef(SQLiteDatabase db,JSONArray refs,String type,long id,String expectedHash){
        String table="message".equals(type)?"messages":"device_event".equals(type)?"device_events":null;
        if(table==null||id<=0){refs.put(object("type",type,"id",id,"hash",expectedHash));return;}
        try(Cursor row=db.rawQuery("SELECT content_hash FROM "+table+" WHERE id=?",new String[]{String.valueOf(id)})){
            String actual=row.moveToFirst()?row.getString(0):"";
            refs.put(object("type",type,"id",id,"hash",expectedHash.isEmpty()?actual:expectedHash));
        }
    }
    private void index(SQLiteDatabase db, String key, String status) {
        String pendingKey = pendingKey(key);
        if (finalStatus(status)) db.delete("settings", "setting_key = ?", new String[]{pendingKey});
        else store.putSetting(pendingKey, object("receiptKey", key, "status", status).toString());
    }
    private static String pendingKey(String key) {
        String ordinary = PENDING + key;
        return ordinary.length() <= 512 ? ordinary : PENDING + "hash:" + digest(key);
    }
    private static boolean finalStatus(String status) { return "completed".equals(status) || "abandoned".equals(status); }
    private static JSONObject strict(String raw) {
        if (raw == null || raw.length() > 500_000) throw new IllegalArgumentException("Invalid receipt");
        checkDepth(raw);
        try {
            JSONTokener tokener = new JSONTokener(raw); Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException("Receipt must be one whole object");
            JSONObject receipt = (JSONObject) value; Object status = receipt.opt("status");
            if (!(status instanceof String) || !("dispatching".equals(status) || "uncertain".equals(status)
                    || "completed".equals(status) || "abandoned".equals(status))) throw new IllegalArgumentException("Unknown receipt status");
            return receipt;
        } catch (org.json.JSONException malformed) { throw new IllegalArgumentException("Malformed receipt", malformed); }
    }
    private static void checkDepth(String raw) {
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char value = raw.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
            } else if (value == '"') quoted = true;
            else if (value == '{' || value == '[') { if (++depth > 64) throw new IllegalArgumentException("Receipt nesting is too deep"); }
            else if (value == '}' || value == ']') { if (--depth < 0) throw new IllegalArgumentException("Receipt delimiters are malformed"); }
        }
    }
    private static String value(SQLiteDatabase db, String key) {
        try (Cursor row = db.rawQuery("SELECT value FROM settings WHERE setting_key = ?", new String[]{key})) { return row.moveToFirst() ? row.getString(0) : null; }
    }
    private static long receiptCount(SQLiteDatabase db) {
        try (Cursor count = db.rawQuery("SELECT COUNT(*) FROM settings WHERE setting_key >= ? AND setting_key < ?", new String[]{RECEIPTS, RECEIPTS_END})) { return count.moveToFirst() ? count.getLong(0) : 0; }
    }
    private static void validateKey(String key) { if (key == null || !key.startsWith(RECEIPTS) || key.length() <= RECEIPTS.length() || key.length() > 512) throw new IllegalArgumentException("Invalid receipt key"); }
    private static long number(String value) { try { return Math.max(0, Long.parseLong(value)); } catch (NumberFormatException malformed) { return 0; } }
    private static String digest(String text) {
        try { StringBuilder result = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) result.append(String.format(Locale.ROOT,"%02x",b&255)); return result.toString(); }
        catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static JSONObject object(Object... pairs) { JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)put(result,String.valueOf(pairs[i]),pairs[i+1]);return result; }
    private static void put(JSONObject target,String key,Object value) { try { target.put(key,value); } catch(Exception invalid) {throw new IllegalStateException(invalid);} }
}
