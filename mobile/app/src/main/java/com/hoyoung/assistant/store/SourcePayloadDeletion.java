package com.hoyoung.assistant.store;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Explicit owner control for tracked local persistence. Never exposed as an autonomous model tool.
 * Original provider/external documents and untracked working copies are outside this verified scope. */
public final class SourcePayloadDeletion {
    private static final Object LOCK=new Object();
    private static final long MAX_JOB_BYTES=2L*1024*1024;
    private final Context context;private final HoyoungStore store;private final SourcePrivacyLedger privacy;
    private final File root,jobs;
    private final File authenticationKey,intentAnchor;
    private final FaultBoundary fault;
    interface FaultBoundary { void checkpoint(String phase)throws IOException; }
    public SourcePayloadDeletion(Context context,HoyoungStore store){this(context,store,phase->{});}
    SourcePayloadDeletion(Context context,HoyoungStore store,FaultBoundary fault){
        if(context==null||store==null||fault==null)throw new IllegalArgumentException("Explicit source deletion needs private context and store");
        this.context=context.getApplicationContext();this.store=store;this.privacy=store.getPrivacyLedger();this.fault=fault;
        root=this.context.getFilesDir();jobs=new File(root,"source-deletions");
        authenticationKey=new File(root,"source-deletions.key");intentAnchor=new File(root,"source-deletions.anchor.json");
    }

    /** Run on an IO worker after explicit owner authorization; a pending result never claims erased bytes. */
    public JSONObject deleteSource(String type,long id,String hash,String reason)throws IOException{
        validate(type,id,hash);synchronized(LOCK){
            assertResolvedGeneration();verifyIntentInventory(true);File path=jobPath(type,id,hash);JSONObject job=path.exists()?readJob(path):newJob(type,id,hash);
            JSONObject source=source(type,id);
            if(source!=null&&!hash.equals(source.optString("content_hash")))throw new IllegalArgumentException("Source hash differs from the selected source generation");
            if(source!=null&&!SourceRecordVisibility.canonicalRecordMatches(type,source))throw new IllegalArgumentException("Selected source record integrity differs from its recorded hash");
            if(!job.optBoolean("manifestCaptured")){captureManifest(job,source);}
            // The immutable privacy policy and durable job precede every database/file erasure.
            privacy.excludeSource(type,id,hash,SourcePrivacyLedger.RETRIEVAL_EXCLUDE,"explicit owner payload deletion");
            put(job,"ownerDeletionRequested",true);put(job,"mode","delete_payload");put(job,"status","pending_database");writeJob(path,job);
            return execute(path,job);
        }
    }
    public JSONObject reconcileDeletionRequests()throws IOException{
        JSONObject summary=new JSONObject();JSONArray visible=new JSONArray();String after="";int processed=0,pending=0;
        while(true){JSONObject page=reconcileDeletionRequests(after,256);processed+=page.optInt("processed");pending+=page.optInt("pending");JSONArray rows=page.optJSONArray("receipts");for(int index=0;rows!=null&&index<rows.length()&&visible.length()<64;index++)visible.put(rows.opt(index));
            if(!page.optBoolean("hasMore"))break;String next=page.optString("nextAfterKey");if(next.compareTo(after)<=0)throw new IOException("Deletion reconcile cursor did not advance");after=next;
        }put(summary,"processed",processed);put(summary,"pending",pending);put(summary,"hasMore",false);put(summary,"receipts",visible);put(summary,"receiptsTruncated",processed>visible.length());return summary;
    }
    /** Portable owner intent only. Authenticate the durable identity inventory, but never
     * export the signing key, paths, captured file manifests or a claimed completion. */
    public JSONArray portableDeletionControls()throws IOException {
        synchronized(LOCK){
            verifyIntentInventory(false);JSONArray result=new JSONArray();List<String> names=intentKeys();
            if(names.size()>100_000)throw new IOException("Portable deletion controls exceed their capacity");
            // The authenticated inventory binds the exact immutable source identities.
            // Do not repeatedly read large descendant/file manifests just to mirror three fields.
            for(String name:names){String[] parts=name.substring(0,name.length()-5).split("-",3);JSONObject ref=new JSONObject();
                put(ref,"type",parts[0]);put(ref,"id",Long.parseLong(parts[1]));put(ref,"hash",parts[2]);result.put(ref);
            }
            return result;
        }
    }
    /** Recreate a missing installation-local intent, without executing it twice during restore.
     * Existing intents are authenticated and replayed by the single reconcile pass below. */
    void seedPortableControl(String type,long id,String hash)throws IOException {
        validate(type,id,hash);
        synchronized(LOCK){
            assertResolvedGeneration();verifyIntentInventory(true);File path=jobPath(type,id,hash);if(path.exists())return;
            JSONObject selected=source(type,id);
            if(selected==null||!hash.equals(selected.optString("content_hash"))||!SourceRecordVisibility.canonicalRecordMatches(type,selected))throw new IOException("Portable deletion source generation changed before intent capture");
            JSONObject job=newJob(type,id,hash);captureManifest(job,selected);
            privacy.excludeSource(type,id,hash,SourcePrivacyLedger.RETRIEVAL_EXCLUDE,"owner-authorized portable deletion restore");
            put(job,"ownerDeletionRequested",true);put(job,"mode","delete_payload");put(job,"status","pending_database");writeJob(path,job);
        }
    }
    /** Invoke after backup recovery and before exposing restored sources. Completed jobs are inspected again. */
    public JSONObject reconcileDeletionRequests(int limit)throws IOException{
        return reconcileDeletionRequests("",limit);
    }
    public JSONObject reconcileDeletionRequests(String afterKey,int limit)throws IOException{
        if(limit<1||limit>10000)throw new IllegalArgumentException("Deletion reconcile limit must be 1..10000");
        if(afterKey==null||!afterKey.isEmpty()&&!afterKey.matches("(?:message|device_event)-[1-9][0-9]*-[a-f0-9]{64}\\.json"))throw new IllegalArgumentException("Invalid deletion cursor");
        synchronized(LOCK){assertResolvedGeneration();verifyIntentInventory(false);JSONObject result=new JSONObject();JSONArray receipts=new JSONArray();
            if(!jobs.exists()){put(result,"processed",0);put(result,"pending",0);put(result,"hasMore",false);put(result,"receipts",receipts);return result;}
            safeDirectory(jobs);File[]entries=jobs.listFiles();if(entries==null)throw new IOException("Deletion intents cannot be enumerated");Arrays.sort(entries,Comparator.comparing(File::getName));
            int processed=0,pending=0;String last=afterKey;boolean more=false;for(File path:entries){if(path.getName().startsWith(".partial-")||path.getName().compareTo(afterKey)<=0)continue;if(processed>=limit){more=true;break;}
                JSONObject job=readJob(path);privacy.excludeSource(job.optString("type"),job.optLong("id"),job.optString("hash"),SourcePrivacyLedger.RETRIEVAL_EXCLUDE,"explicit owner payload deletion");
                JSONObject receipt=execute(path,job);receipts.put(receipt);processed++;last=path.getName();if(receipt.optBoolean("pendingDeletion"))pending++;
            }
            put(result,"processed",processed);put(result,"pending",pending);put(result,"hasMore",more);put(result,"nextAfterKey",last);put(result,"receipts",receipts);return result;
        }
    }
    private JSONObject execute(File path,JSONObject job)throws IOException{
        String type=job.optString("type"),hash=job.optString("hash");long id=job.optLong("id");
        try{
            JSONObject current=source(type,id);
            if(current!=null&&!hash.equals(current.optString("content_hash"))){put(job,"status","pending_source_generation_changed");writeJob(path,job);return receipt(job);}
            if(current!=null&&!SourceRecordVisibility.canonicalRecordMatches(type,current)){put(job,"status","pending_source_integrity_changed");writeJob(path,job);return receipt(job);}
            if(descendantGenerationChanged(job)){put(job,"status","pending_descendant_generation_changed");writeJob(path,job);return receipt(job);}
            if(!job.optBoolean("manifestCaptured")){put(job,"status","pending_source_manifest");writeJob(path,job);return receipt(job);}
            if(job.optBoolean("unknownAssetReferences")){put(job,"status","pending_attachment_manifest");writeJob(path,job);return receipt(job);}
            // In-flight cloud work is not cancelled by deleting a local row. Caller must
            // quiesce native delivery before physical erasure can finish.
            excludeLinkedMessages(job);
            captureAnswerDescendants(job);
            excludeKnownNotificationCopies(job);
            writeJob(path,job); // Newly discovered descendant originals are durable before DB removal.
            // Pending/spool HJA bytes could reinsert an event. Preserve its dedupe row until
            // the journal owner supplies a verified purge hook; descendants remain excluded.
            if(type.equals("device_event")){put(job,"status","pending_external_archive");put(job,"externalArchiveDeletionVerified",false);writeJob(path,job);return receipt(job);}
            if(hasInflightRequest(job)){put(job,"status","pending_inflight_request");writeJob(path,job);return receipt(job);}
            store.withTransaction(db->{purgeDatabase(db,job);return null;});
            put(job,"status","pending_files");put(job,"databasePurged",true);writeJob(path,job);fault.checkpoint("after_database");
            int shared=purgeOriginalFiles(job)+purgeAssets(job);put(job,"retainedSharedPayloads",shared);
            int working=retainedWorkingCopies(job);put(job,"retainedWorkingCopies",working);
            purgeDerivedCaches();
            if(!checkpointDatabase()){put(job,"status","pending_sqlite_cleanup");writeJob(path,job);return receipt(job);}
            boolean archivedCopies=job.optJSONArray("phoneArchiveCopies")!=null&&job.optJSONArray("phoneArchiveCopies").length()>0;
            put(job,"status",archivedCopies?"pending_external_archive":shared>0?"pending_shared_payload":working>0?"pending_working_copy_retained":"completed_managed_local");put(job,"completedAt",System.currentTimeMillis());writeJob(path,job);return receipt(job);
        }catch(IOException|RuntimeException failure){put(job,"status","pending_failure");put(job,"failure",failure.getClass().getSimpleName()+": "+failure.getMessage());
            try{writeJob(path,job);}catch(IOException persistence){failure.addSuppressed(persistence);throw new IOException("Deletion remains incomplete and its last durable intent is retained",failure);}return receipt(job);
        }
    }
    private void captureManifest(JSONObject job,JSONObject source)throws IOException{
        if(source==null)return;
        JSONArray originals=new JSONArray(),assets=new JSONArray(),requests=new JSONArray();String request=source.optString("request_id","");
        if(!source.isNull("raw_file_id")&&!source.optString("raw_file_id").isEmpty())originals.put(raw(source.optString("raw_file_id"),source.optString("raw_content_hash"),source.optLong("raw_byte_count")));
        put(job,"sourceRole",source.optString("role"));put(job,"sourceCategory",source.optString("category"));put(job,"sourceDedupeKey",source.optString("dedupe_key"));
        JSONArray parents=new JSONArray();if("message".equals(job.optString("type"))){JSONObject parent=new JSONObject();put(parent,"id",source.optLong("id"));put(parent,"hash",source.optString("content_hash"));put(parent,"createdAt",source.optLong("created_at"));put(parent,"bodyHash",source.optString("raw_content_hash"));parents.put(parent);}put(job,"parentMessages",parents);
        if(!request.isEmpty()){
            requests.put(request);if("user".equals(source.optString("role"))){
                try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT setting_key FROM settings WHERE setting_key >= 'requestRoot:' AND setting_key < 'requestRoot;' AND value=?",new String[]{request})){
                    while(rows.moveToNext()){String key=rows.getString(0);requests.put(key.substring("requestRoot:".length()));}
                }
            }
            for(int index=0;index<requests.length();index++){
                String external=requests.optString(index);
                try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT raw_result_file_id,raw_result_hash,raw_result_byte_count FROM requests WHERE external_id=?",new String[]{external})){
                    if(rows.moveToFirst()&&!rows.isNull(0))originals.put(raw(rows.getString(0),rows.getString(1),rows.getLong(2)));
                }
                if("user".equals(source.optString("role"))){
                    String encoded=store.getSetting("attachmentMetadata:"+external,"[]");
                    try{JSONArray metadata=new JSONArray(encoded);for(int a=0;a<metadata.length();a++){JSONObject asset=metadata.optJSONObject(a);if(asset==null)throw new JSONException("Invalid attachment");String assetId=asset.optString("originalAssetId",asset.optString("id"));String sha=asset.optString("sha256");
                        if(!assetId.matches("\\.attachment-originals/[a-f0-9]{64}\\.[a-z0-9]{1,12}")||!sha.matches("[a-f0-9]{64}")||!assetId.contains(sha))throw new JSONException("Invalid immutable attachment identity");
                        JSONObject known=new JSONObject();put(known,"id",assetId);put(known,"hash",sha);put(known,"size",asset.optLong("size"));assets.put(known);
                        if(!asset.optString("sourceFileId").isEmpty())appendRaw(array(job,"workingCopies"),raw(asset.optString("sourceFileId"),sha,asset.optLong("size")));}
                    }catch(JSONException invalid){put(job,"unknownAssetReferences",true);}
                }
            }
        }
        collectToolArchives(assets,requests);
        put(job,"originals",originals);put(job,"assets",assets);put(job,"requestIds",requests);put(job,"manifestCaptured",true);
    }
    private void collectToolArchives(JSONArray assets,JSONArray requests)throws IOException{
        Set<String> ids=new HashSet<>();for(int i=0;i<requests.length();i++)ids.add(requests.optString(i));if(ids.isEmpty())return;
        File directory=new File(root,"projects/.attachment-originals");if(!directory.exists())return;safeDirectory(directory);File[]entries=directory.listFiles();if(entries==null)throw new IOException("Archived tool results cannot be inspected");
        for(File sidecar:entries){if(!sidecar.getName().endsWith(".metadata.json"))continue;if(Files.isSymbolicLink(sidecar.toPath())||sidecar.length()>65536)throw new IOException("Archived tool metadata is unsafe");
            try{JSONObject metadata=new JSONObject(new String(Files.readAllBytes(sidecar.toPath()),StandardCharsets.UTF_8));if("tool_result".equals(metadata.optString("kind"))&&ids.contains(metadata.optString("rootRequestId"))){
                String id=metadata.optString("originalAssetId",metadata.optString("id")),hash=metadata.optString("sha256");if(!id.matches("\\.attachment-originals/[a-f0-9]{64}\\.[a-z0-9]{1,12}")||!id.contains(hash)||!hash.matches("[a-f0-9]{64}"))throw new IOException("Archived tool result identity changed");
                JSONObject asset=new JSONObject();put(asset,"id",id);put(asset,"hash",hash);put(asset,"size",metadata.optLong("size"));assets.put(asset);
            }}catch(JSONException invalid){throw new IOException("Archived tool metadata is malformed",invalid);}
        }
    }
    private void excludeLinkedMessages(JSONObject job)throws IOException{
        if(!"user".equals(job.optString("sourceRole")))return;
        JSONArray requests=job.optJSONArray("requestIds");
        for(int index=0;requests!=null&&index<requests.length();index++){
            try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT id,content_hash,created_at,request_id,raw_file_id,raw_content_hash,raw_byte_count FROM messages WHERE request_id=? AND role<>'user'",new String[]{requests.optString(index)})){
                while(rows.moveToNext())rememberDescendant(job,rows);
            }
        }
    }
    /** Native answerSources links preserve provenance across unrelated later owner questions.
     * Resolve the reverse transitive closure without erasing those later question payloads. */
    private void captureAnswerDescendants(JSONObject job)throws IOException{
        JSONArray descendants=array(job,"descendants"),responses=array(job,"responseRequestIds");
        boolean changed;do{changed=false;String after="";
            if(captureReviewDescendants(job))changed=true;
            if(captureGoalDescendants(job))changed=true;
            if(captureNativeReceiptArchives(job))changed=true;
            while(true){String through=after;
                try(Cursor links=store.getReadableDatabase().rawQuery("SELECT setting_key,value FROM settings WHERE setting_key>? AND setting_key>='answerSources:' AND setting_key<'answerSources;' ORDER BY setting_key LIMIT 128",new String[]{after})){
                    while(links.moveToNext()){
                        String key=links.getString(0);through=key;JSONObject provenance;
                        try{provenance=new JSONObject(links.getString(1));}catch(JSONException malformed){throw new IOException("Native answer provenance is malformed; source deletion remains pending",malformed);}
                        JSONArray refs=provenance.optJSONArray("sources");if(refs==null)throw new IOException("Native answer provenance has no source manifest");
                        if(!referencesDeletedSource(job,refs))continue;
                        long responseId;try{responseId=Long.parseLong(key.substring("answerSources:".length()));}catch(NumberFormatException invalid){throw new IOException("Native answer provenance identity is invalid",invalid);}
                        try(Cursor row=store.getReadableDatabase().rawQuery("SELECT id,content_hash,created_at,request_id,raw_file_id,raw_content_hash,raw_byte_count FROM messages WHERE id=? AND role<>'user'",new String[]{String.valueOf(responseId)})){
                            if(row.moveToFirst()&&rememberDescendant(job,row))changed=true;
                        }
                    }
                }
                if(through.equals(after))break;after=through;
            }
            for(int index=0;index<responses.length();index++){
                String external=responses.optString(index),requestRoot=store.getSetting("requestRoot:"+external,external);
                appendString(responses,requestRoot);
                try(Cursor siblings=store.getReadableDatabase().rawQuery("SELECT setting_key FROM settings WHERE setting_key>='requestRoot:' AND setting_key<'requestRoot;' AND value=?",new String[]{requestRoot})){
                    while(siblings.moveToNext())appendString(responses,siblings.getString(0).substring("requestRoot:".length()));
                }
                try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT id,content_hash,created_at,request_id,raw_file_id,raw_content_hash,raw_byte_count FROM messages WHERE request_id=? AND role<>'user'",new String[]{external})){
                    while(rows.moveToNext())if(rememberDescendant(job,rows))changed=true;
                }
            }
        }while(changed);
        for(int index=0;index<responses.length();index++){
            String request=responses.optString(index);
            try(Cursor row=store.getReadableDatabase().rawQuery("SELECT raw_result_file_id,raw_result_hash,raw_result_byte_count FROM requests WHERE external_id=?",new String[]{request})){
                if(row.moveToFirst()&&!row.isNull(0))appendRaw(array(job,"originals"),raw(row.getString(0),row.getString(1),row.getLong(2)));
            }
        }
        collectToolArchives(array(job,"assets"),allRequests(job));
    }
    private boolean captureNativeReceiptArchives(JSONObject job)throws IOException{
        boolean changed=false;JSONArray requests=allRequests(job);
        for(int index=0;index<requests.length();index++){
            String prefix="{\"receiptKey\":"+JSONObject.quote("actionReceipt:"+requests.optString(index)+":");prefix=prefix.substring(0,prefix.length()-1);
            String pattern=prefix.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            // The native writer publishes receiptKey first, before the escaped old raw
            // receipt. Its bounded SQL prefix remains complete even for spilled payloads.
            try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT id,content_hash,created_at,request_id,raw_file_id,raw_content_hash,raw_byte_count FROM messages WHERE role='system' AND text LIKE ? ESCAPE '\\'",new String[]{pattern})){
                while(rows.moveToNext())if(rememberDescendant(job,rows))changed=true;
            }
        }return changed;
    }
    private boolean captureGoalDescendants(JSONObject job)throws IOException{
        boolean changed=false;for(String prefix:new String[]{"goalSources:","goalSourceHistory:"}){
            String after="";while(true){String through=after;
                try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT setting_key,value FROM settings WHERE setting_key>? AND setting_key>=? AND setting_key<? ORDER BY setting_key LIMIT 128",new String[]{after,prefix,prefix+"\uffff"})){
                    while(rows.moveToNext()){String key=rows.getString(0);through=key;JSONObject provenance;
                        try{provenance=new JSONObject(rows.getString(1));}catch(JSONException malformed){throw new IOException("Native goal provenance is malformed",malformed);}
                        JSONArray refs=provenance.optJSONArray("sources");if(refs==null)throw new IOException("Native goal provenance has no sources");if(!referencesDeletedSource(job,refs))continue;
                        String request=key.substring(prefix.length());if("goalSourceHistory:".equals(prefix)){int separator=request.lastIndexOf(':');if(separator<1||!request.substring(separator+1).matches("[a-f0-9]{64}"))throw new IOException("Native goal history identity changed");request=request.substring(0,separator);}
                        if(request.isEmpty())throw new IOException("Native goal source has no root request");JSONArray responses=array(job,"responseRequestIds");int prior=responses.length();appendString(responses,request);if(prior!=responses.length())changed=true;
                    }
                }if(through.equals(after))break;after=through;
            }
        }return changed;
    }
    private boolean captureReviewDescendants(JSONObject job)throws IOException{
        String after="";boolean changed=false;
        while(true){String through=after;
            try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT setting_key,value FROM settings WHERE setting_key>? AND setting_key>='memoryReview:' AND setting_key<'memoryReview;' ORDER BY setting_key LIMIT 128",new String[]{after})){
                while(rows.moveToNext()){String key=rows.getString(0);through=key;JSONObject review;
                    try{review=new JSONObject(rows.getString(1));}catch(JSONException malformed){throw new IOException("Native memory review manifest is malformed",malformed);}
                    if(!reviewContainsDeletedSource(review,job,0))continue;
                    appendString(array(job,"reviewSettingKeys"),key);String request=key.substring("memoryReview:".length());appendString(array(job,"responseRequestIds"),request);appendString(array(job,"reviewRequestIds"),request);
                    try(Cursor response=store.getReadableDatabase().rawQuery("SELECT id,content_hash,created_at,request_id,raw_file_id,raw_content_hash,raw_byte_count FROM messages WHERE request_id=? AND role<>'user'",new String[]{request})){
                        while(response.moveToNext())if(rememberDescendant(job,response))changed=true;
                    }
                }
            }if(through.equals(after))break;after=through;
        }return changed;
    }
    private static boolean reviewContainsDeletedSource(Object value,JSONObject job,int depth){
        if(depth>64)throw new IllegalStateException("Native memory review nesting exceeds verified deletion bound");
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;
            if(object.opt("messageId") instanceof Number){JSONArray parents=job.optJSONArray("parentMessages");for(int index=0;parents!=null&&index<parents.length();index++){JSONObject parent=parents.optJSONObject(index);
                if(object.optLong("messageId")==parent.optLong("id")&&(parent.optString("hash").equals(object.optString("recordHash"))||!parent.optString("bodyHash").isEmpty()&&parent.optString("bodyHash").equals(object.optString("contentHash"))))return true;
            }}Iterator<String> keys=object.keys();while(keys.hasNext())if(reviewContainsDeletedSource(object.opt(keys.next()),job,depth+1))return true;
        }else if(value instanceof JSONArray){JSONArray rows=(JSONArray)value;for(int index=0;index<rows.length();index++)if(reviewContainsDeletedSource(rows.opt(index),job,depth+1))return true;}return false;
    }
    private boolean rememberDescendant(JSONObject job,Cursor row)throws IOException{
        long id=row.getLong(0);String hash=row.getString(1);JSONObject canonical=store.getMessage(id);
        if(canonical==null||!hash.equals(canonical.optString("content_hash"))||!SourceRecordVisibility.canonicalRecordMatches("message",canonical))throw new IOException("Derived source record integrity changed before deletion");
        JSONArray descendants=array(job,"descendants");
        for(int index=0;index<descendants.length();index++){JSONObject old=descendants.optJSONObject(index);if(old.optLong("id")==id&&hash.equals(old.optString("hash")))return false;}
        privacy.excludeSource("message",id,hash,SourcePrivacyLedger.RETRIEVAL_EXCLUDE,"answer derived from owner-deleted source");
        JSONObject descendant=new JSONObject();put(descendant,"id",id);put(descendant,"hash",hash);put(descendant,"createdAt",row.getLong(2));put(descendant,"bodyHash",row.getString(5));put(descendant,"requestId",row.isNull(3)?"":row.getString(3));descendants.put(descendant);
        appendIdentity(array(job,"parentMessages"),descendant);
        if(!row.isNull(4))appendRaw(array(job,"originals"),raw(row.getString(4),row.getString(5),row.getLong(6)));
        if(!row.isNull(3))appendString(array(job,"responseRequestIds"),row.getString(3));
        return true;
    }
    private static boolean referencesDeletedSource(JSONObject job,JSONArray refs){
        for(int index=0;index<refs.length();index++){JSONObject ref=refs.optJSONObject(index);if(ref==null)continue;String type=ref.optString("type"),hash=ref.optString("hash");long id=ref.optLong("id");
            if(type.equals(job.optString("type"))&&hash.equals(job.optString("hash"))&&("device_event".equals(type)||id==job.optLong("id")))return true;
            if("message".equals(type)){JSONArray descendants=job.optJSONArray("descendants");for(int d=0;descendants!=null&&d<descendants.length();d++){JSONObject source=descendants.optJSONObject(d);if(source.optLong("id")==id&&hash.equals(source.optString("hash")))return true;}}
        }return false;
    }
    private static JSONArray array(JSONObject object,String key){JSONArray rows=object.optJSONArray(key);if(rows==null){rows=new JSONArray();put(object,key,rows);}return rows;}
    private static void appendString(JSONArray rows,String value){for(int index=0;index<rows.length();index++)if(value.equals(rows.optString(index)))return;rows.put(value);}
    private static void appendIdentity(JSONArray rows,JSONObject identity){for(int index=0;index<rows.length();index++){JSONObject old=rows.optJSONObject(index);if(old.optLong("id")==identity.optLong("id")&&old.optString("hash").equals(identity.optString("hash")))return;}rows.put(identity);}
    private static JSONArray allRequests(JSONObject job){JSONArray result=new JSONArray();for(String key:new String[]{"requestIds","responseRequestIds"}){JSONArray rows=job.optJSONArray(key);for(int index=0;rows!=null&&index<rows.length();index++)appendString(result,rows.optString(index));}return result;}
    private boolean descendantGenerationChanged(JSONObject job){
        JSONArray descendants=job.optJSONArray("descendants");for(int index=0;descendants!=null&&index<descendants.length();index++){
            JSONObject expected=descendants.optJSONObject(index),current=store.getMessage(expected.optLong("id"));
            if(current!=null&&(!expected.optString("hash").equals(current.optString("content_hash"))||!SourceRecordVisibility.canonicalRecordMatches("message",current)))return true;
        }return false;
    }
    private void excludeKnownNotificationCopies(JSONObject job)throws IOException{
        JSONArray parents=job.optJSONArray("parentMessages"),copies=job.optJSONArray("phoneArchiveCopies");if(copies==null){copies=new JSONArray();put(job,"phoneArchiveCopies",copies);}
        for(int parent=0;parents!=null&&parent<parents.length();parent++){
            JSONObject expected=parents.optJSONObject(parent);long id=expected.optLong("id");JSONObject current=store.getMessage(id);if(current!=null&&!expected.optString("hash").equals(current.optString("content_hash")))continue;
            String needle="%\"assistantMessageId\":"+id+"%";
            try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT id,content_hash,payload FROM device_events WHERE category='notification' AND payload LIKE ?",new String[]{needle})){
                while(rows.moveToNext())try{JSONObject payload=new JSONObject(rows.getString(2));
                    if(!context.getPackageName().equals(payload.optString("package"))||!payload.optBoolean("assistantGenerated")||!payload.optBoolean("sourceReferencePresent")||payload.optLong("assistantMessageId")!=id||payload.optLong("postedAt")<expected.optLong("createdAt"))continue;
                    privacy.excludeSource("device_event",rows.getLong(0),rows.getString(1),SourcePrivacyLedger.RETRIEVAL_EXCLUDE,"notification derived from owner-deleted source");boolean present=false;for(int c=0;c<copies.length();c++)if(copies.optJSONObject(c).optString("hash").equals(rows.getString(1)))present=true;
                    if(!present){JSONObject copy=new JSONObject();put(copy,"id",rows.getLong(0));put(copy,"hash",rows.getString(1));copies.put(copy);}
                }catch(JSONException invalid){throw new IOException("Known notification source is malformed",invalid);}
            }
        }
    }
    private boolean hasInflightRequest(JSONObject job){
        JSONArray requests=allRequests(job);for(int index=0;requests!=null&&index<requests.length();index++)try(Cursor row=store.getReadableDatabase().rawQuery("SELECT status FROM requests WHERE external_id=?",new String[]{requests.optString(index)})){
            if(row.moveToFirst()&&Arrays.asList("dispatching","accepted","uncertain").contains(row.getString(0)))return true;
        }return false;
    }
    private void purgeDatabase(SQLiteDatabase db,JSONObject job){
        try(Cursor pragma=db.rawQuery("PRAGMA secure_delete=ON",null)){if(!pragma.moveToFirst()||pragma.getInt(0)!=1)throw new IllegalStateException("SQLite secure deletion could not be enabled");}String type=job.optString("type"),hash=job.optString("hash");long id=job.optLong("id");
        db.execSQL("CREATE TEMP TABLE IF NOT EXISTS deleting_memory_keys(memory_key TEXT PRIMARY KEY)");db.delete("deleting_memory_keys",null,null);
        db.execSQL("INSERT OR IGNORE INTO deleting_memory_keys SELECT memory_key FROM memory_sources WHERE source_type=? AND source_id=? AND source_hash=?",new Object[]{type,id,hash});
        JSONArray requests=job.optJSONArray("requestIds");
        if("user".equals(job.optString("sourceRole")))for(int index=0;requests!=null&&index<requests.length();index++){
            db.execSQL("INSERT OR IGNORE INTO deleting_memory_keys SELECT s.memory_key FROM memory_sources s JOIN messages m ON s.source_type='message' AND s.source_id=m.id AND s.source_hash=m.content_hash WHERE m.request_id=? AND m.role<>'user'",new Object[]{requests.optString(index)});
            for(String derived:new String[]{"semantic_chunks","semantic_index_progress"})if(tableExists(db,derived))db.delete(derived,"source_type='message' AND source_id IN(SELECT id FROM messages WHERE request_id=? AND role<>'user')",new String[]{requests.optString(index)});
        }
        JSONArray descendants=job.optJSONArray("descendants");
        for(int index=0;descendants!=null&&index<descendants.length();index++){JSONObject child=descendants.optJSONObject(index);
            db.execSQL("INSERT OR IGNORE INTO deleting_memory_keys SELECT memory_key FROM memory_sources WHERE source_type='message' AND source_id=? AND source_hash=?",new Object[]{child.optLong("id"),child.optString("hash")});
            for(String derived:new String[]{"semantic_chunks","semantic_index_progress"})if(tableExists(db,derived))db.delete(derived,"source_type='message' AND source_id=?",new String[]{String.valueOf(child.optLong("id"))});
        }
        db.execSQL("DELETE FROM memory_search WHERE docid IN(SELECT rowid FROM memories WHERE memory_key IN(SELECT memory_key FROM deleting_memory_keys))");
        for(String table:new String[]{"semantic_vectors","memory_metadata_revisions","memory_metadata","memory_revisions","memory_sources","memories"})db.delete(table,"memory_key IN(SELECT memory_key FROM deleting_memory_keys)",null);
        if(tableExists(db,"semantic_chunks"))db.delete("semantic_chunks","source_type=? AND source_id=?",new String[]{type,String.valueOf(id)});
        if(tableExists(db,"semantic_index_progress"))db.delete("semantic_index_progress","source_type=? AND source_id=?",new String[]{type,String.valueOf(id)});
        db.delete(type.equals("message")?"messages":"device_events","id=? AND content_hash=?",new String[]{String.valueOf(id),hash});
        for(int index=0;descendants!=null&&index<descendants.length();index++){JSONObject child=descendants.optJSONObject(index);if(db.delete("messages","id=? AND content_hash=?",new String[]{String.valueOf(child.optLong("id")),child.optString("hash")})>0)purgeMessageSettings(db,child.optLong("id"));}
        purgeMessageSettings(db,id);
        Set<String> ownedRequests=new HashSet<>();for(int index=0;requests!=null&&index<requests.length();index++)ownedRequests.add(requests.optString(index));
        JSONArray all=allRequests(job);
        for(int index=0;index<all.length();index++){
            String external=all.optString(index);ContentValues scrub=new ContentValues();scrub.put("result","");scrub.putNull("raw_result_file_id");scrub.put("raw_result_char_count",0);scrub.put("raw_result_byte_count",0);scrub.put("raw_result_hash",sha(new byte[0]));
            if("user".equals(job.optString("sourceRole"))&&ownedRequests.contains(external)){scrub.put("text","[Owner-deleted source payload]");scrub.put("status","abandoned");}
            try(Cursor request=db.rawQuery("SELECT kind FROM requests WHERE external_id=?",new String[]{external})){if(request.moveToFirst()&&!"user".equals(request.getString(0))){scrub.put("text","[Owner-deleted derived work payload]");scrub.put("status","abandoned");}}
            db.update("requests",scrub,"external_id=?",new String[]{external});
            for(String prefix:new String[]{"activeGoal:","goalReportedArchive:","goalSources:"})db.delete("settings","setting_key=?",new String[]{prefix+external});
            if("user".equals(job.optString("sourceRole"))&&ownedRequests.contains(external))for(String prefix:new String[]{"attachments:","attachmentMetadata:","workAttachmentRepresentations:","uploadedAssetsObserved:","inputFingerprint:"})db.delete("settings","setting_key=?",new String[]{prefix+external});
            for(String prefix:new String[]{"actionReceipt:"+external+":","pendingReceipt:actionReceipt:"+external+":","goalSourceHistory:"+external+":"})db.delete("settings","setting_key>=? AND setting_key<?",new String[]{prefix,prefix+"\uffff"});
        }
        db.delete("settings","setting_key=?",new String[]{"assetCoverage:"+id});db.delete("settings","setting_key>=? AND setting_key<?",new String[]{"assetAnalysisReceipt:"+id+":","assetAnalysisReceipt:"+id+":\uffff"});
        db.delete("settings","setting_key=?",new String[]{"pendingAssetReview:"+String.format(Locale.ROOT,"%019d",id)});
        JSONArray reviews=job.optJSONArray("reviewSettingKeys");for(int index=0;reviews!=null&&index<reviews.length();index++)db.delete("settings","setting_key=?",new String[]{reviews.optString(index)});
        purgeTrackedEvents(db,job);
        // Source-bound review/asset settings are removed by exact identifiers only.
        db.delete("deleting_memory_keys",null,null);
    }
    private static void purgeMessageSettings(SQLiteDatabase db,long id){
        for(String prefix:new String[]{"answerSources:","replyNotification:","assetCoverage:","nativeAccountInput:"})db.delete("settings","setting_key=?",new String[]{prefix+id});
        db.delete("settings","setting_key>=? AND setting_key<?",new String[]{"assetAnalysisReceipt:"+id+":","assetAnalysisReceipt:"+id+":\uffff"});
        db.delete("settings","setting_key=?",new String[]{"pendingAssetReview:"+String.format(Locale.ROOT,"%019d",id)});
    }
    private void purgeTrackedEvents(SQLiteDatabase db,JSONObject job){
        JSONArray requests=job.optJSONArray("requestIds");Set<String> ids=new HashSet<>();for(int index=0;requests!=null&&index<requests.length();index++)ids.add(requests.optString(index));
        Set<Long> messages=new HashSet<>();if("message".equals(job.optString("type")))messages.add(job.optLong("id"));JSONArray descendants=job.optJSONArray("descendants");for(int index=0;descendants!=null&&index<descendants.length();index++)messages.add(descendants.optJSONObject(index).optLong("id"));
        Set<String> responseIds=new HashSet<>();JSONArray all=allRequests(job);for(int index=0;index<all.length();index++)responseIds.add(all.optString(index));
        long after=0;while(true){List<Long> remove=new ArrayList<>();long through=after;try(Cursor rows=db.rawQuery("SELECT id,payload,type FROM events WHERE id>? ORDER BY id LIMIT 128",new String[]{String.valueOf(after)})){
            while(rows.moveToNext()){through=rows.getLong(0);try{JSONObject payload=new JSONObject(rows.getString(1));Set<String> requestScope=generatedOutputEvent(rows.getString(2),payload)?responseIds:ids;if(trackedEvent(payload,messages,requestScope,job,0))remove.add(through);}catch(JSONException invalid){/* Unbound malformed records are never guessed to belong to this source. */}}
        }if(through==after)break;for(long event:remove)db.delete("events","id=?",new String[]{String.valueOf(event)});after=through;}
    }
    private static boolean generatedOutputEvent(String type,JSONObject payload){
        if("message_attachments".equals(type)||type.startsWith("owner_")||type.startsWith("user_"))return false;
        if(Arrays.asList("phone_tool_receipts","work_response_observed","completion_processing_uncertain","tool_continuation_queued","asset_analysis_recorded","memory_saved","work_output_held").contains(type))return true;
        for(String field:new String[]{"results","result","response","output","receipt","raw_result"})if(payload.has(field))return true;return false;
    }
    private static boolean trackedEvent(Object value,Set<Long> messages,Set<String> requests,JSONObject job,int depth){
        if(depth>64)throw new IllegalStateException("Source-linked event nesting exceeds verified deletion bound");
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;
            if(object.has("type")&&object.has("id")&&object.has("hash")&&referencesDeletedSource(job,new JSONArray().put(object)))return true;
            for(String field:new String[]{"requestId","request_id","rootRequestId","rootId","external_id"})if(object.has(field)&&requests.contains(object.optString(field)))return true;
            for(String field:new String[]{"messageId","sourceMessageId"})if(object.opt(field) instanceof Number&&messages.contains(object.optLong(field)))return true;
            Iterator<String> keys=object.keys();while(keys.hasNext())if(trackedEvent(object.opt(keys.next()),messages,requests,job,depth+1))return true;
        }else if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int index=0;index<array.length();index++)if(trackedEvent(array.opt(index),messages,requests,job,depth+1))return true;}return false;
    }
    private int purgeOriginalFiles(JSONObject job)throws IOException{
        int shared=0;JSONArray originals=job.optJSONArray("originals");Set<String>seen=new HashSet<>();
        for(int index=0;originals!=null&&index<originals.length();index++){
            JSONObject entry=originals.optJSONObject(index);String id=entry.optString("id");if(!seen.add(id))continue;
            try(Cursor refs=store.getReadableDatabase().rawQuery("SELECT 1 FROM messages WHERE raw_file_id=? UNION ALL SELECT 1 FROM requests WHERE raw_result_file_id=? LIMIT 1",new String[]{id,id})){if(refs.moveToFirst()){shared++;continue;}}
            if(!id.matches("(?:[a-fA-F0-9-]{36}|[a-f0-9]{64})\\.txt"))throw new IOException("Unsafe original deletion manifest");
            File file=safeFile(new File(root,"originals"),id);if(file.exists()){verify(file,entry.optString("hash"),entry.optLong("size"));if(!file.delete())throw new IOException("Original payload could not be removed");syncDirectory(file.getParentFile());}
        }return shared;
    }
    private int purgeAssets(JSONObject job)throws IOException{
        int shared=0;JSONArray assets=job.optJSONArray("assets");Set<String>seen=new HashSet<>();
        for(int index=0;assets!=null&&index<assets.length();index++){
            JSONObject entry=assets.optJSONObject(index);String id=entry.optString("id");if(!seen.add(id))continue;
            if(assetReferenced(id)){shared++;continue;}
            if(!id.matches("\\.attachment-originals/[a-f0-9]{64}\\.[a-z0-9]{1,12}"))throw new IOException("Unsafe attachment deletion manifest");
            File originalDirectory=new File(root,"projects/.attachment-originals");if(originalDirectory.exists()||Files.isSymbolicLink(originalDirectory.toPath()))safeDirectory(originalDirectory);
            File file=safeFile(new File(root,"projects"),id);if(file.exists()){verify(file,entry.optString("hash"),entry.optLong("size"));if(!file.delete())throw new IOException("Attachment payload could not be removed");}
            store.getWritableDatabase().delete("settings","setting_key=?",new String[]{"fileMeta:"+id});
            File sidecar=new File(file.getPath()+".metadata.json");if(Files.isSymbolicLink(sidecar.toPath()))throw new IOException("Unsafe attachment sidecar");if(sidecar.exists()&&!sidecar.delete())throw new IOException("Attachment metadata could not be removed");if(file.getParentFile().isDirectory())syncDirectory(file.getParentFile());
        }return shared;
    }
    /** General project working files are not erased merely because they once supplied an attachment.
     * A remaining known byte-identical copy prevents a full managed-local completion claim. */
    private int retainedWorkingCopies(JSONObject job)throws IOException{
        int retained=0,changed=0;JSONArray copies=job.optJSONArray("workingCopies");for(int index=0;copies!=null&&index<copies.length();index++){
            JSONObject entry=copies.optJSONObject(index);String id=entry.optString("id");if(id.isEmpty()||id.startsWith(".attachment-originals/")||id.startsWith("/")||id.contains("\\")||Arrays.asList(id.split("/",-1)).contains(".."))throw new IOException("Known working copy path is unsafe");
            File file=safeFile(new File(root,"projects"),id);if(!file.exists())continue;
            if(file.length()!=entry.optLong("size")){changed++;continue;}try{verify(file,entry.optString("hash"),entry.optLong("size"));retained++;}catch(IOException modified){changed++;}
        }put(job,"knownWorkingCopyCount",copies==null?0:copies.length());put(job,"changedWorkingCopiesPreserved",changed);return retained;
    }
    private boolean assetReferenced(String id)throws IOException{
        String hash=id.substring(id.lastIndexOf('/')+1,id.lastIndexOf('.'));
        // Android JSON may escape '/', so a raw LIKE on the full path silently misses
        // a genuine retained attachment. The hash narrows rows; parsed IDs establish ownership.
        try(Cursor refs=store.getReadableDatabase().rawQuery("SELECT value FROM settings WHERE setting_key<>? AND value LIKE ?",new String[]{"fileMeta:"+id,"%"+hash+"%"})){
            while(refs.moveToNext()){String value=refs.getString(0);if(value.contains(id))return true;
                try{Object decoded=new JSONTokener(value).nextValue();if(referencesAsset(decoded,id,0))return true;}catch(JSONException invalid){return true;}
            }return false;
        }
    }
    private static boolean referencesAsset(Object value,String id,int depth)throws IOException{
        if(depth>64)throw new IOException("Retained asset references exceed verified traversal bound");
        if(value instanceof String)return ((String)value).contains(id);
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;Iterator<String> keys=object.keys();while(keys.hasNext())if(referencesAsset(object.opt(keys.next()),id,depth+1))return true;}
        else if(value instanceof JSONArray){JSONArray rows=(JSONArray)value;for(int index=0;index<rows.length();index++)if(referencesAsset(rows.opt(index),id,depth+1))return true;}return false;
    }
    private boolean checkpointDatabase(){
        SQLiteDatabase db=store.getWritableDatabase();
        for(String search:new String[]{"message_search","memory_search","device_event_search"})db.execSQL("INSERT INTO "+search+"("+search+") VALUES('optimize')");
        try(Cursor row=db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){return row.moveToFirst()&&row.getInt(0)==0;}
    }
    /** These namespaces contain only reproducible caches, never originals or generic model weights. */
    private void purgeDerivedCaches()throws IOException{
        for(String name:new String[]{"derived-indexes","work-attachment-representations"}){
            File directory=new File(context.getNoBackupFilesDir(),name);deleteCacheTree(directory,0);
        }
    }
    private static void deleteCacheTree(File file,int depth)throws IOException{
        if(depth>32||Files.isSymbolicLink(file.toPath()))throw new IOException("Derived cache cleanup path is unsafe");if(!file.exists())return;
        if(file.isDirectory()){File[]entries=file.listFiles();if(entries==null)throw new IOException("Derived cache cleanup cannot enumerate files");for(File entry:entries)deleteCacheTree(entry,depth+1);}
        if(!file.delete())throw new IOException("Derived cache cleanup could not remove stale data");
    }
    private JSONObject source(String type,long id){return type.equals("message")?store.getMessage(id):store.getDeviceEvent(id);}
    private JSONObject newJob(String type,long id,String hash){JSONObject job=new JSONObject();put(job,"format","hoyoung-source-deletion-v1");put(job,"type",type);put(job,"id",id);put(job,"hash",hash);put(job,"createdAt",System.currentTimeMillis());put(job,"mode","delete_payload");put(job,"manifestCaptured",false);put(job,"status","pending_manifest");return job;}
    private static JSONObject receipt(JSONObject job){JSONObject result=new JSONObject();boolean complete="completed_managed_local".equals(job.optString("status"));put(result,"type",job.optString("type"));put(result,"id",job.optLong("id"));put(result,"hash",job.optString("hash"));put(result,"status",job.optString("status"));put(result,"requestedMode","delete_payload");put(result,"pendingDeletion",!complete);put(result,"managedLocalDeletionVerified",complete);put(result,"scope","tracked_local_source_and_derivations");put(result,"providerDeletionVerified",false);put(result,"untrackedWorkingCopiesSearched",false);put(result,"mutableWorkingCopiesDeleted",false);put(result,"knownWorkingCopyCount",job.optInt("knownWorkingCopyCount"));put(result,"retainedWorkingCopies",job.optInt("retainedWorkingCopies"));put(result,"changedWorkingCopiesPreserved",job.optInt("changedWorkingCopiesPreserved"));put(result,"tombstoneMetadataRetained",true);put(result,"retainedSharedPayloads",job.optInt("retainedSharedPayloads"));if(job.has("failure"))put(result,"failure",job.optString("failure"));return result;}
    private static JSONObject raw(String id,String hash,long size){JSONObject result=new JSONObject();put(result,"id",id);put(result,"hash",hash);put(result,"size",size);return result;}
    private static void appendRaw(JSONArray rows,JSONObject entry){for(int index=0;index<rows.length();index++){JSONObject old=rows.optJSONObject(index);if(old.optString("id").equals(entry.optString("id"))&&old.optString("hash").equals(entry.optString("hash")))return;}rows.put(entry);}
    private void assertResolvedGeneration()throws IOException{if(InternalFiles.restoreJournalPresent(root))throw new IOException("Restore generation must be resolved before source deletion");}
    private File jobPath(String type,long id,String hash)throws IOException{if(!jobs.exists()&&!jobs.mkdirs())throw new IOException("Deletion intent storage is unavailable");safeDirectory(jobs);return new File(jobs,type+"-"+id+"-"+hash+".json");}
    private JSONObject readJob(File path)throws IOException{if(Files.isSymbolicLink(path.toPath())||!path.isFile()||path.length()>MAX_JOB_BYTES)throw new IOException("Deletion intent is unsafe or oversized");try{JSONObject envelope=new JSONObject(new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8));String record=envelope.getString("record"),authentication=envelope.optString("authentication");if(!authentication.matches("[a-f0-9]{64}")||!MessageDigest.isEqual(authentication.getBytes(StandardCharsets.US_ASCII),authenticate(record,false).getBytes(StandardCharsets.US_ASCII)))throw new IOException("Deletion intent source-to-file binding changed");JSONObject job=new JSONObject(record);validate(job.optString("type"),job.optLong("id"),job.optString("hash"));if(!"hoyoung-source-deletion-v1".equals(job.optString("format"))||!path.getName().equals(job.optString("type")+"-"+job.optLong("id")+"-"+job.optString("hash")+".json"))throw new IOException("Deletion intent identity changed");return job;}catch(JSONException|IllegalArgumentException invalid){throw new IOException("Deletion intent is malformed",invalid);}}
    private void writeJob(File path,JSONObject job)throws IOException{safeDirectory(jobs);boolean newIntent=!path.exists();String record=job.toString();JSONObject envelope=new JSONObject();put(envelope,"record",record);put(envelope,"authentication",authenticate(record,true));byte[]bytes=envelope.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_JOB_BYTES)throw new IOException("Deletion manifest is too large");File temporary=new File(jobs,".partial-"+UUID.randomUUID());try{try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}Files.move(temporary.toPath(),path.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory(jobs);if(newIntent||!intentAnchor.exists())publishIntentInventory();}finally{if(temporary.exists()&&!temporary.delete())throw new IOException("Incomplete deletion intent cannot be removed");}}
    /** A second durable namespace prevents missing established intents from silently
     * turning a restored deletion payload into an apparently untracked source. */
    private void verifyIntentInventory(boolean explicitControlRecovery)throws IOException{
        List<String> actual=intentKeys();
        if(!intentAnchor.exists()){
            if(actual.isEmpty()){if(authenticationKey.exists()||Files.isSymbolicLink(authenticationKey.toPath()))throw new IOException("Established deletion intent storage is missing; originals remain blocked");return;}
            if(!explicitControlRecovery)throw new IOException("Deletion intent anchor is missing; restored sources remain blocked");
            // Initial publication may stop after the signed job but before its anchor.
            // Only an explicit owner control retry may complete that publication.
            for(String name:actual)readJob(new File(jobs,name));publishIntentInventory();return;
        }
        if(Files.isSymbolicLink(intentAnchor.toPath())||!intentAnchor.isFile()||intentAnchor.length()>MAX_JOB_BYTES)throw new IOException("Deletion intent anchor is unsafe");
        try{JSONObject envelope=new JSONObject(new String(Files.readAllBytes(intentAnchor.toPath()),StandardCharsets.UTF_8));String record=envelope.getString("record"),signature=envelope.getString("authentication");
            if(!signature.matches("[a-f0-9]{64}")||!MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII),authenticate(record,false).getBytes(StandardCharsets.US_ASCII)))throw new IOException("Deletion intent anchor integrity changed");
            JSONObject index=new JSONObject(record);JSONArray expected=index.optJSONArray("keys");if(!"hoyoung-source-deletion-anchor-v1".equals(index.optString("format"))||expected==null||expected.length()!=actual.size())throw new IOException("Established deletion intents are missing or changed");
            for(int i=0;i<actual.size();i++)if(!actual.get(i).equals(expected.optString(i)))throw new IOException("Established deletion intent identity changed");
        }catch(JSONException malformed){throw new IOException("Deletion intent anchor is malformed",malformed);}
    }
    private List<String> intentKeys()throws IOException{
        List<String> names=new ArrayList<>();if(!jobs.exists())return names;safeDirectory(jobs);File[]entries=jobs.listFiles();if(entries==null)throw new IOException("Deletion intents cannot be enumerated");
        for(File file:entries){if(file.getName().startsWith(".partial-"))continue;if(!file.getName().matches("(?:message|device_event)-[1-9][0-9]*-[a-f0-9]{64}\\.json")||Files.isSymbolicLink(file.toPath())||!file.isFile())throw new IOException("Deletion intent namespace changed");names.add(file.getName());}Collections.sort(names);return names;
    }
    private void publishIntentInventory()throws IOException{
        List<String> names=intentKeys();JSONObject index=new JSONObject();put(index,"format","hoyoung-source-deletion-anchor-v1");JSONArray keys=new JSONArray();for(String name:names)keys.put(name);put(index,"keys",keys);String record=index.toString();JSONObject envelope=new JSONObject();put(envelope,"record",record);put(envelope,"authentication",authenticate(record,true));byte[]bytes=envelope.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_JOB_BYTES)throw new IOException("Deletion intent inventory is oversized");
        File partial=new File(root,".source-deletions-anchor-"+UUID.randomUUID());try{try(FileOutputStream out=new FileOutputStream(partial)){out.write(bytes);out.getFD().sync();}Files.move(partial.toPath(),intentAnchor.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory(root);}finally{if(partial.exists()&&!partial.delete())throw new IOException("Incomplete deletion intent anchor cannot be removed");}
    }
    private String authenticate(String record,boolean create)throws IOException{
        if(Files.isSymbolicLink(authenticationKey.toPath()))throw new IOException("Deletion authentication key is unsafe");
        if(!authenticationKey.exists()){
            if(!create)throw new IOException("Deletion authentication key is missing; intent is retained");byte[]key=new byte[32];new SecureRandom().nextBytes(key);File partial=new File(root,".source-deletion-key-"+UUID.randomUUID());
            try{try(FileOutputStream output=new FileOutputStream(partial)){output.write(key);output.getFD().sync();}Files.move(partial.toPath(),authenticationKey.toPath(),StandardCopyOption.ATOMIC_MOVE);syncDirectory(root);}finally{if(partial.exists()&&!partial.delete())throw new IOException("Incomplete deletion key could not be removed");}
        }
        if(!authenticationKey.isFile()||authenticationKey.length()!=32)throw new IOException("Deletion authentication key is invalid");
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(Files.readAllBytes(authenticationKey.toPath()),"HmacSHA256"));return OriginalTextFiles.hex(mac.doFinal(record.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException unavailable){throw new IOException("Deletion intent authentication is unavailable",unavailable);}
    }
    private static File safeFile(File directory,String relative)throws IOException{safeDirectory(directory);File path=new File(directory,relative);if(Files.isSymbolicLink(path.toPath())||!path.getCanonicalPath().startsWith(directory.getCanonicalPath()+File.separator))throw new IOException("Deletion path escapes managed original storage");return path;}
    private static void safeDirectory(File file)throws IOException{if(Files.isSymbolicLink(file.toPath())||!file.isDirectory())throw new IOException("Managed deletion directory is unavailable or unsafe");}
    private static void verify(File file,String hash,long size)throws IOException{if(!file.isFile()||file.length()!=size||!hash.matches("[a-f0-9]{64}"))throw new IOException("Deletion payload identity changed");MessageDigest digest=OriginalTextFiles.digest();try(InputStream input=new FileInputStream(file)){byte[]buffer=new byte[16384];int read;while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);}if(!hash.equals(OriginalTextFiles.hex(digest.digest())))throw new IOException("Deletion payload SHA-256 differs from its manifest");}
    private static boolean tableExists(SQLiteDatabase db,String table){try(Cursor row=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",new String[]{table})){return row.moveToFirst();}}
    private static String sha(byte[]bytes){return OriginalTextFiles.hex(OriginalTextFiles.digest().digest(bytes));}
    private static void validate(String type,long id,String hash){if(!("message".equals(type)||"device_event".equals(type))||id<=0||hash==null||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Deletion needs an exact typed source id and SHA-256");}
    private static void syncDirectory(File directory)throws IOException{try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}catch(IOException|UnsupportedOperationException unavailable){java.io.FileDescriptor descriptor=null;try{descriptor=android.system.Os.open(directory.getPath(),android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_NOFOLLOW,0);android.system.Os.fsync(descriptor);}catch(android.system.ErrnoException failure){throw new IOException("Deletion metadata directory fsync failed",failure);}finally{if(descriptor!=null)try{android.system.Os.close(descriptor);}catch(android.system.ErrnoException ignored){}}}}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
}
