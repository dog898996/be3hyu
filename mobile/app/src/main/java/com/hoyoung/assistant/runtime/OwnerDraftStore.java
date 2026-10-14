package com.hoyoung.assistant.runtime;

import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Durable owner input cohorts. All fields move together in the caller's SQLite transaction.
 * There is no cached mutable draft and no lifecycle/onPause write-back operation. */
public final class OwnerDraftStore {
    public static final String STATE_KEY="ui.draftState";
    private static final String PART_PREFIX=STATE_KEY+".part.";
    private static final String PROFILE="utf16be-code-units-v1";
    private static final int MAX_FIELD_CHARS=500_000,PART_CHARS=240_000,MAX_PARTS=12,INLINE_CHARS=480_000;
    private final HoyoungStore store;

    public static final class Snapshot {
        public final String text,attachmentsJson,clientInputId,inputActor;
        public final long revision;
        public final boolean recoveryRequired;
        public final String recoveryKey,recoveryReason;
        private Snapshot(String text,String attachmentsJson,String clientInputId,long revision,boolean recoveryRequired,String recoveryKey,String recoveryReason,String inputActor){
            this.text=text;this.attachmentsJson=attachmentsJson;this.clientInputId=clientInputId;this.revision=revision;
            this.recoveryRequired=recoveryRequired;this.recoveryKey=recoveryKey;this.recoveryReason=recoveryReason;
            this.inputActor=UiAutomationProvenance.normalize(inputActor);
        }
        /** Every call returns a private copy; callers cannot alter this input cohort. */
        public JSONArray attachments(){try{return new JSONArray(attachmentsJson);}catch(Exception impossible){throw new IllegalStateException("Draft attachment state is invalid",impossible);}}
    }

    public OwnerDraftStore(HoyoungStore store){if(store==null)throw new IllegalArgumentException("Draft storage is missing");this.store=store;}

    /** Migrates a legacy tuple once. Recreating an Activity/process keeps its input id. */
    public Snapshot read(){return store.withTransaction(this::readInside);}

    /** Only call for a real owner text/attachment edit, never to write back an old Activity. */
    public Snapshot edit(String text,JSONArray attachments){
        return edit(text,attachments,UiAutomationProvenance.OWNER);
    }

    /** Agent input is allowed, but its authorship survives process recreation and a later
     * human Send tap. Clearing the whole input starts a new owner-authored cohort. */
    public Snapshot edit(String text,JSONArray attachments,String inputActor){
        if(text==null)text="";
        final String editingActor=UiAutomationProvenance.normalize(inputActor);
        String attachmentText=attachments==null?"[]":attachments.toString();
        validateLength(text);validateLength(attachmentText);validateAttachments(attachmentText);
        final String editedText=text,editedAttachments=attachmentText;
        return store.withTransaction(db->{
            Snapshot current=readInside(db);
            boolean empty=editedText.isEmpty()&&"[]".equals(editedAttachments);
            String actor=empty?UiAutomationProvenance.OWNER:editingActor;
            if(!empty&&!UiAutomationProvenance.OWNER.equals(current.inputActor)&&UiAutomationProvenance.OWNER.equals(editingActor))actor=UiAutomationProvenance.MIXED;
            if(!current.recoveryRequired&&current.text.equals(editedText)&&current.attachmentsJson.equals(editedAttachments)&&current.inputActor.equals(actor))return current;
            Snapshot updated=fresh(editedText,editedAttachments,nextRevision(current.revision),false,"","",actor);
            write(db,updated,true);return updated;
        });
    }

    /** Apply a live editor's changed fields without writing back an unseen import/send result. */
    public Snapshot editFrom(Snapshot before,String text,JSONArray attachments,String inputActor){
        if(before==null)return edit(text,attachments,inputActor);
        final String editedText=text==null?"":text;
        final String editedAttachments=attachments==null?"[]":attachments.toString();
        return store.withTransaction(db->{
            Snapshot current=readInside(db);
            String mergedText=editedText.equals(before.text)?current.text:editedText;
            JSONArray mergedAttachments=editedAttachments.equals(before.attachmentsJson)?current.attachments()
                    :mergeAttachmentChanges(before.attachments(),attachments==null?new JSONArray():attachments,current.attachments());
            return edit(mergedText,mergedAttachments,inputActor);
        });
    }

    /** Remove only visible selections the owner removed; add only newly selected IDs.
     * In particular, retaining an old selection does not restore it after a send consumed it. */
    private static JSONArray mergeAttachmentChanges(JSONArray before,JSONArray edited,JSONArray current){
        java.util.Set<String> previousIds=new java.util.HashSet<>(),editedIds=new java.util.HashSet<>(),mergedIds=new java.util.HashSet<>();
        for(int i=0;i<before.length();i++)previousIds.add(attachmentKey(before.opt(i)));
        for(int i=0;i<edited.length();i++)editedIds.add(attachmentKey(edited.opt(i)));
        JSONArray merged=new JSONArray();
        for(int i=0;i<current.length();i++){
            Object value=current.opt(i);String key=attachmentKey(value);
            if(previousIds.contains(key)&&!editedIds.contains(key))continue;
            merged.put(value);mergedIds.add(key);
        }
        for(int i=0;i<edited.length();i++){
            Object value=edited.opt(i);String key=attachmentKey(value);
            if(!previousIds.contains(key)&&mergedIds.add(key))merged.put(value);
        }
        if(merged.length()>8)throw new IllegalArgumentException("A message supports at most eight attachments");
        return merged;
    }
    private static String attachmentKey(Object value){
        if(value instanceof String)return "id:"+value;
        if(value instanceof JSONObject object){
            String id=object.optString("originalAssetId",object.optString("id",object.optString("fileId","")));
            if(!id.isEmpty())return "id:"+id;
        }
        return "value:"+String.valueOf(value);
    }

    /** Import completion belongs to durable storage, independent of the originating Activity. */
    public Snapshot appendAttachments(JSONArray imported,String inputActor){
        final JSONArray additions;
        try{additions=new JSONArray(imported==null?"[]":imported.toString());}
        catch(Exception invalid){throw new IllegalArgumentException("Invalid imported attachments",invalid);}
        validateAttachments(additions.toString());
        return store.withTransaction(db->{
            Snapshot current=readInside(db);
            if(current.recoveryRequired)throw new IllegalStateException(current.recoveryReason);
            JSONArray merged=current.attachments();
            if(merged.length()+additions.length()>8)throw new IllegalArgumentException("A message supports at most eight attachments");
            for(int i=0;i<additions.length();i++)merged.put(additions.opt(i));
            return edit(current.text,merged,inputActor);
        });
    }

    /** CAS the entire tuple, including attachments. Call inside the successful enqueue transaction.
     * A stale callback/duplicate send/recovery draft never clears any field. */
    public Snapshot consume(String clientId,long revision){
        return store.withTransaction(db->{
            Snapshot current=readInside(db);
            if(current.recoveryRequired||revision!=current.revision||!current.clientInputId.equals(clientId))return current;
            Snapshot empty=fresh("","[]",nextRevision(current.revision),false,"","");write(db,empty,true);return empty;
        });
    }

    private Snapshot readInside(SQLiteDatabase db){
        String raw=store.getSetting(STATE_KEY,null);
        if(raw==null){
            String legacyText=store.getSetting("ui.draft",""),legacyAttachments=store.getSetting("ui.attachments","[]");
            try{validateLength(legacyText);validateLength(legacyAttachments);validateAttachments(legacyAttachments);Snapshot migrated=fresh(legacyText,legacyAttachments,0,false,"","");write(db,migrated,true);return migrated;}
            catch(IllegalArgumentException malformed){return recover(db,null,legacyText,legacyAttachments,"legacy_draft_invalid");}
        }
        try{return parse(readPayload(raw));}
        catch(RuntimeException malformed){return recover(db,raw,store.getSetting("ui.draft",""),store.getSetting("ui.attachments","[]"),"draft_state_invalid_or_integrity_failed");}
    }

    private String readPayload(String raw){
        try{
            JSONObject top=object(raw);
            if(!"chunked-json-v1".equals(top.optString("storage")))return raw;
            int count=strictInt(top,"parts",1,MAX_PARTS);StringBuilder payload=new StringBuilder();
            for(int i=0;i<count;i++){String part=store.getSetting(PART_PREFIX+i,null);if(part==null||part.length()>PART_CHARS)throw new IllegalArgumentException("Draft payload part is missing");payload.append(part);}
            if(!hash(payload.toString()).equals(top.getString("payloadSha256")))throw new IllegalArgumentException("Draft payload integrity failed");return payload.toString();
        }catch(RuntimeException failed){throw failed;}catch(Exception failed){throw new IllegalArgumentException("Invalid draft payload",failed);}
    }
    private Snapshot parse(String raw){
        try{
            JSONObject state=object(raw);int schema=strictInt(state,"schemaVersion",1,2);if(!PROFILE.equals(state.getString("encoding")))throw new IllegalArgumentException("Unsupported draft schema");
            String text=decoded(state.getString("textUtf16")),attachments=decoded(state.getString("attachmentsUtf16")),id=state.getString("clientInputId");
            validateLength(text);validateLength(attachments);validateAttachments(attachments);validateId(id);
            Object rawRevision=state.get("revision");if(!(rawRevision instanceof Long||rawRevision instanceof Integer))throw new IllegalArgumentException("Invalid draft revision");long revision=((Number)rawRevision).longValue();if(revision<0)throw new IllegalArgumentException("Negative draft revision");
            Object recovery=state.get("recoveryRequired");if(!(recovery instanceof Boolean))throw new IllegalArgumentException("Invalid recovery state");
            Snapshot snapshot=new Snapshot(text,attachments,id,revision,(Boolean)recovery,state.getString("recoveryKey"),state.getString("recoveryReason"),schema==1?UiAutomationProvenance.OWNER:state.getString("inputActor"));
            if(snapshot.recoveryRequired&&(snapshot.recoveryKey.isEmpty()||snapshot.recoveryReason.isEmpty()))throw new IllegalArgumentException("Missing recovery evidence");
            String tuple=schema==1?legacyIdentity(snapshot):identity(snapshot);
            if(!hash(tuple).equals(state.getString("tupleSha256")))throw new IllegalArgumentException("Draft tuple integrity failed");return snapshot;
        }catch(RuntimeException failed){throw failed;}catch(Exception failed){throw new IllegalArgumentException("Invalid draft state",failed);}
    }

    private Snapshot recover(SQLiteDatabase db,String rawState,String legacyText,String legacyAttachments,String reason){
        LinkedHashMap<String,String> sources=new LinkedHashMap<>();if(rawState!=null)sources.put(STATE_KEY,rawState);sources.put("ui.draft",legacyText);sources.put("ui.attachments",legacyAttachments);
        // A broken chunked manifest must not overwrite the only remaining raw chunks.
        for(int i=0;i<MAX_PARTS;i++){String part=store.getSetting(PART_PREFIX+i,null);if(part!=null)sources.put(PART_PREFIX+i,part);}
        String key="ui.draftRecovery."+UUID.randomUUID();JSONObject manifest=new JSONObject();put(manifest,"schemaVersion",1);put(manifest,"encoding",PROFILE);put(manifest,"reason",reason);put(manifest,"createdAtEpochMillis",System.currentTimeMillis());
        JSONArray records=new JSONArray();long now=System.currentTimeMillis();
        for(Map.Entry<String,String> source:sources.entrySet()){
            String encoded=encoded(source.getValue()),digest=hash(source.getValue());int count=Math.max(1,(encoded.length()+PART_CHARS-1)/PART_CHARS);JSONObject record=new JSONObject();
            put(record,"sourceSetting",source.getKey());put(record,"utf16Chars",source.getValue().length());put(record,"rawSha256",digest);put(record,"partCount",count);JSONArray sourceIds=new JSONArray();
            for(int i=0;i<count;i++){
                String part=encoded.substring(Math.min(encoded.length(),i*PART_CHARS),Math.min(encoded.length(),(i+1)*PART_CHARS));JSONObject original=new JSONObject();
                put(original,"actor","local_app");put(original,"recoveryKey",key);put(original,"sourceSetting",source.getKey());put(original,"encoding",PROFILE);put(original,"rawSha256",digest);put(original,"utf16Chars",source.getValue().length());put(original,"part",i);put(original,"partCount",count);put(original,"rawUtf16Base64",part);put(original,"reason",reason);put(original,"authorizationClaim",false);
                JSONObject observed=store.appendDeviceEvent("system","app.owner_draft.recovery",original,now,null);sourceIds.put(observed.optLong("id"));
            }
            put(record,"systemSourceIds",sourceIds);records.put(record);
        }
        put(manifest,"sources",records);checkedPut(db,key,manifest.toString());
        String recoveredText=legacyText;String recoveredAttachments;
        try{validateAttachments(legacyAttachments);recoveredAttachments=legacyAttachments;}catch(RuntimeException invalid){recoveredAttachments="[]";}
        Snapshot recovered=fresh(recoveredText,recoveredAttachments,0,true,key,"임시 입력 저장에 문제가 있어 원본을 따로 보존했어. 내용을 확인하고 글이나 첨부를 수정한 뒤 다시 보내 줘.");
        // Preserve original legacy bytes. The new usable tuple is explicitly marked for owner review.
        write(db,recovered,false);return recovered;
    }

    private void write(SQLiteDatabase db,Snapshot snapshot,boolean mirrorLegacy){
        JSONObject state=new JSONObject();put(state,"schemaVersion",2);put(state,"encoding",PROFILE);put(state,"textUtf16",encoded(snapshot.text));put(state,"attachmentsUtf16",encoded(snapshot.attachmentsJson));put(state,"clientInputId",snapshot.clientInputId);put(state,"revision",snapshot.revision);put(state,"recoveryRequired",snapshot.recoveryRequired);put(state,"recoveryKey",snapshot.recoveryKey);put(state,"recoveryReason",snapshot.recoveryReason);put(state,"inputActor",snapshot.inputActor);put(state,"tupleSha256",hash(identity(snapshot)));
        String payload=state.toString();int count=payload.length()<=INLINE_CHARS?0:(payload.length()+PART_CHARS-1)/PART_CHARS;
        if(count>MAX_PARTS)throw new IllegalArgumentException("Draft state is too large");
        if(count==0)checkedPut(db,STATE_KEY,payload);
        else{
            for(int i=0;i<count;i++)checkedPut(db,PART_PREFIX+i,payload.substring(i*PART_CHARS,Math.min(payload.length(),(i+1)*PART_CHARS)));
            JSONObject top=new JSONObject();put(top,"storage","chunked-json-v1");put(top,"parts",count);put(top,"payloadSha256",hash(payload));checkedPut(db,STATE_KEY,top.toString());
        }
        for(int i=count;i<MAX_PARTS;i++)db.delete("settings","setting_key=?",new String[]{PART_PREFIX+i});
        if(mirrorLegacy){checkedPut(db,"ui.draft",snapshot.text);checkedPut(db,"ui.attachments",snapshot.attachmentsJson);}
    }

    private static Snapshot fresh(String text,String attachments,long revision,boolean recovery,String recoveryKey,String recoveryReason){return fresh(text,attachments,revision,recovery,recoveryKey,recoveryReason,UiAutomationProvenance.OWNER);}
    private static Snapshot fresh(String text,String attachments,long revision,boolean recovery,String recoveryKey,String recoveryReason,String actor){return new Snapshot(text,attachments,UUID.randomUUID().toString(),revision,recovery,recoveryKey,recoveryReason,actor);}
    private static long nextRevision(long revision){if(revision==Long.MAX_VALUE)throw new IllegalStateException("Draft revision is exhausted");return revision+1;}
    private static void validateLength(String value){if(value==null||value.length()>MAX_FIELD_CHARS)throw new IllegalArgumentException("Draft value is too large");}
    private static void validateAttachments(String raw){try{Object parsed=json(raw);if(!(parsed instanceof JSONArray))throw new IllegalArgumentException("Invalid draft attachment array");JSONArray values=(JSONArray)parsed;for(int i=0;i<values.length();i++){Object value=values.get(i);if(value instanceof String)validateFileId((String)value);else if(!(value instanceof JSONObject))throw new IllegalArgumentException("Invalid draft attachment");}}catch(RuntimeException invalid){throw invalid;}catch(Exception invalid){throw new IllegalArgumentException("Invalid draft attachments",invalid);}}
    private static void validateFileId(String id){
        if(id.isEmpty()||id.length()>2048||id.startsWith("/")||id.indexOf('\\')>=0||id.indexOf(':')>=0)throw new IllegalArgumentException("Invalid draft file id");
        String[] parts=id.split("/",-1);if(parts.length>33)throw new IllegalArgumentException("Draft file path is too deep");
        for(String part:parts)if(part.isEmpty()||part.equals(".")||part.equals("..")||part.startsWith(".partial-")||part.getBytes(StandardCharsets.UTF_8).length>255)throw new IllegalArgumentException("Invalid draft file path");
        for(int i=0;i<id.length();i++)if(Character.isISOControl(id.charAt(i)))throw new IllegalArgumentException("Control character in draft file id");
    }
    private static Object json(String raw)throws Exception{JSONTokener parser=new JSONTokener(raw);Object parsed=parser.nextValue();if(parser.nextClean()!=0)throw new IllegalArgumentException("Trailing draft JSON data");return parsed;}
    private static JSONObject object(String raw)throws Exception{Object parsed=json(raw);if(!(parsed instanceof JSONObject))throw new IllegalArgumentException("Invalid draft JSON object");return(JSONObject)parsed;}
    private static void validateId(String id){if(!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException("Invalid draft input id");}
    private static int strictInt(JSONObject object,String key,int min,int max)throws Exception{Object raw=object.get(key);if(!(raw instanceof Integer||raw instanceof Long))throw new IllegalArgumentException("Invalid draft integer");long value=((Number)raw).longValue();if(value<min||value>max)throw new IllegalArgumentException("Draft integer outside bounds");return(int)value;}
    private static void checkedPut(SQLiteDatabase db,String key,String value){validateLength(value);db.execSQL("INSERT OR REPLACE INTO settings(setting_key,value) VALUES (?,?)",new Object[]{key,value});}
    private static String legacyIdentity(Snapshot snapshot){return field(snapshot.text)+field(snapshot.attachmentsJson)+field(snapshot.clientInputId)+field(String.valueOf(snapshot.revision))+field(String.valueOf(snapshot.recoveryRequired))+field(snapshot.recoveryKey)+field(snapshot.recoveryReason);}
    private static String identity(Snapshot snapshot){return legacyIdentity(snapshot)+field(snapshot.inputActor);}
    private static String field(String value){return value.length()+":"+value;}
    private static String encoded(String raw){return Base64.encodeToString(bytes(raw),Base64.NO_WRAP);}
    private static String decoded(String encoded){byte[] value=Base64.decode(encoded,Base64.NO_WRAP);if(value.length%2!=0)throw new IllegalArgumentException("Invalid UTF-16 draft bytes");if(value.length>MAX_FIELD_CHARS*2)throw new IllegalArgumentException("Draft bytes are too large");char[] chars=new char[value.length/2];for(int i=0;i<chars.length;i++)chars[i]=(char)(((value[i*2]&255)<<8)|(value[i*2+1]&255));return new String(chars);}
    private static byte[] bytes(String raw){byte[] value=new byte[raw.length()*2];for(int i=0;i<raw.length();i++){char c=raw.charAt(i);value[i*2]=(byte)(c>>>8);value[i*2+1]=(byte)c;}return value;}
    private static String hash(String raw){try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes(PROFILE+"\u0000"+raw));StringBuilder hex=new StringBuilder(64);for(byte b:digest)hex.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return hex.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
