package com.hoyoung.assistant.store;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Owner-authorized portable controls. This contains source identities, never signing keys
 * or file-erasure paths. It is merged outside restored namespaces before originals are exposed.
 * No code here calls a model or changes retrieval ranking. */
public final class OwnerPolicyBackup {
    public static final String FORMAT="hoyoung-owner-controls-v1";
    public static final String ARCHIVE_PATH="owner-policy/controls.json";
    public static final long MAX_BYTES=32L*1024*1024;
    private static final String SAVED="owner-policy-portable.json",ANCHOR="owner-policy-portable.anchor.json";
    private static final Object LOCK=new Object();
    private OwnerPolicyBackup(){}

    public static JSONObject capture(Context context,HoyoungStore store)throws IOException {
        synchronized(LOCK){
            JSONObject saved=readSaved(context),privacy=store.getPrivacyLedger().snapshot();JSONArray deletions=new JSONArray();
            if(saved!=null){privacy=SourcePrivacyLedger.unionSnapshots(privacy,saved.optJSONObject("privacy"));deletions=saved.optJSONArray("deletions");}
            deletions=unionDeletions(deletions,new SourcePayloadDeletion(context,store).portableDeletionControls());
            return create(privacy,deletions);
        }
    }

    /** Merge first, with a durable intent registry, then replay only against matching restored
     * source hashes. Local newer controls are never discarded by an older portable archive. */
    public static JSONObject merge(Context context,HoyoungStore store,JSONObject supplied)throws IOException {
        JSONObject incoming=validate(supplied);
        synchronized(LOCK){
            JSONObject combined=union(capture(context,store),incoming);
            File root=context.getApplicationContext().getFilesDir();
            JSONObject existing=readSaved(context);
            if(existing==null||!existing.optString("checksum").equals(combined.optString("checksum"))){
                atomic(root,new File(root,SAVED),combined.toString());
                JSONObject anchor=new JSONObject();put(anchor,"count",combined.optJSONArray("deletions").length());
                put(anchor,"prefixHash",hash(combined.optJSONArray("deletions").toString()));atomic(root,new File(root,ANCHOR),anchor.toString());
            }
            store.getPrivacyLedger().mergeSnapshot(combined.optJSONObject("privacy"));
            return combined;
        }
    }

    /** Startup/restore only, before WebView, collectors, indexes or originals become ready. */
    public static JSONObject reconcile(Context context,HoyoungStore store)throws IOException {
        synchronized(LOCK){
            JSONObject saved=readSaved(context);JSONObject result=new JSONObject();int checked=0,pending=0,different=0;
            if(saved!=null){
                store.getPrivacyLedger().mergeSnapshot(saved.optJSONObject("privacy"));
                JSONArray controls=saved.optJSONArray("deletions");SourcePayloadDeletion deletion=new SourcePayloadDeletion(context,store);
                for(int i=0;i<controls.length();i++){
                    JSONObject ref=controls.optJSONObject(i);String type=ref.optString("type"),expected=ref.optString("hash");long id=ref.optLong("id");
                    JSONObject source="message".equals(type)?store.getMessage(id):store.getDeviceEvent(id);
                    // No empty file manifest is fabricated for an absent source: the portable
                    // registry remains and can capture the real source if a later old ZIP brings it back.
                    if(source==null)continue;
                    if(!expected.equals(source.optString("content_hash"))){different++;continue;}
                    if(!SourceRecordVisibility.canonicalRecordMatches(type,source))throw new IOException("Portable deletion source integrity changed");
                    JSONObject receipt=deletion.deleteSource(type,id,expected,"owner-authorized portable policy restore");checked++;
                    if(receipt.optBoolean("pendingDeletion"))pending++;
                }
            }
            put(result,"checked",checked);put(result,"pending",pending);put(result,"distinctGenerationsPreserved",different);put(result,"modelCalls",0);return result;
        }
    }

    public static JSONObject union(JSONObject first,JSONObject second)throws IOException {
        JSONObject a=validate(first),b=validate(second);
        return create(SourcePrivacyLedger.unionSnapshots(a.optJSONObject("privacy"),b.optJSONObject("privacy")),
                unionDeletions(a.optJSONArray("deletions"),b.optJSONArray("deletions")));
    }

    public static JSONObject read(InputStream input)throws IOException {
        if(input==null)throw new IOException("Owner control document is unavailable");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int count;
        while((count=input.read(buffer))!=-1){if(bytes.size()>MAX_BYTES-count)throw new IOException("Portable owner controls exceed their byte limit");bytes.write(buffer,0,count);}
        String text=new String(bytes.toByteArray(),StandardCharsets.UTF_8);HoyoungStore.checkJsonDepth(text);
        try{JSONTokener tokener=new JSONTokener(text);Object value=tokener.nextValue();
            if(!(value instanceof JSONObject)||tokener.nextClean()!=0)throw new IOException("Owner control document has trailing or non-object data");
            return validate((JSONObject)value);
        }catch(JSONException malformed){throw new IOException("Portable owner controls are malformed",malformed);}
    }

    public static JSONObject validate(JSONObject supplied)throws IOException {
        if(supplied==null)throw new IOException("Portable owner controls are missing");
        JSONObject object;try{object=new JSONObject(supplied.toString());}catch(JSONException malformed){throw new IOException("Invalid owner controls",malformed);}
        if(!FORMAT.equals(object.optString("format"))||object.length()!=4||object.optJSONObject("privacy")==null||object.optJSONArray("deletions")==null)
            throw new IOException("Invalid portable owner control schema");
        String checksum=object.optString("checksum");if(!checksum.matches("[a-f0-9]{64}"))throw new IOException("Invalid owner control checksum");
        object.remove("checksum");
        if(!MessageDigest.isEqual(checksum.getBytes(StandardCharsets.US_ASCII),hash(object.toString()).getBytes(StandardCharsets.US_ASCII)))throw new IOException("Portable owner control integrity mismatch");
        SourcePrivacyLedger.Snapshot privacy=SourcePrivacyLedger.fromSnapshot(object.optJSONObject("privacy"));JSONArray refs=object.optJSONArray("deletions");
        Set<String> identities=new HashSet<>();if(refs.length()>100_000)throw new IOException("Too many portable deletion controls");
        for(int i=0;i<refs.length();i++){
            JSONObject ref=refs.optJSONObject(i);String key=identity(ref);
            if(!identities.add(key)||privacy.allowedSource(ref.optString("type"),ref.optLong("id"),ref.optString("hash")))throw new IOException("Unexcluded or duplicate portable deletion control");
        }
        put(object,"checksum",checksum);if(object.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("Portable owner controls exceed their byte limit");return object;
    }

    private static JSONObject create(JSONObject privacy,JSONArray deletions)throws IOException {
        JSONObject result=new JSONObject();put(result,"format",FORMAT);put(result,"privacy",privacy);put(result,"deletions",deletions);put(result,"checksum",hash(result.toString()));return validate(result);
    }
    private static JSONArray unionDeletions(JSONArray first,JSONArray second)throws IOException {
        Map<String,JSONObject> unique=new LinkedHashMap<>();
        for(JSONArray refs:new JSONArray[]{first,second})for(int i=0;i<refs.length();i++){JSONObject ref=refs.optJSONObject(i);unique.putIfAbsent(identity(ref),ref);}
        if(unique.size()>100_000)throw new IOException("Too many merged portable deletion controls");JSONArray result=new JSONArray();for(JSONObject ref:unique.values())result.put(ref);return result;
    }
    private static String identity(JSONObject ref)throws IOException {
        if(ref==null||ref.length()!=3||!(ref.opt("id") instanceof Number))throw new IOException("Invalid portable deletion identity");
        String type=ref.optString("type"),digest=ref.optString("hash");Number number=(Number)ref.opt("id");long id=number.longValue();
        if(!("message".equals(type)||"device_event".equals(type))||id<=0||!Double.isFinite(number.doubleValue())||number.doubleValue()!=(double)id||!digest.matches("[a-f0-9]{64}"))throw new IOException("Invalid portable deletion identity");
        return type+":"+id+":"+digest;
    }
    private static JSONObject readSaved(Context context)throws IOException {
        File root=context.getApplicationContext().getFilesDir(),file=new File(root,SAVED),anchor=new File(root,ANCHOR);
        if(Files.isSymbolicLink(file.toPath())||Files.isSymbolicLink(anchor.toPath()))throw new IOException("Portable owner policy storage is unsafe");
        if(!file.exists()){if(anchor.exists())throw new IOException("Established portable owner controls are missing");return null;}
        JSONObject saved;try(InputStream input=new FileInputStream(file)){saved=read(input);}
        if(anchor.exists()){
            if(!anchor.isFile()||anchor.length()>1024)throw new IOException("Portable policy anchor is invalid");
            try{
                JSONObject expected=new JSONObject(new String(Files.readAllBytes(anchor.toPath()),StandardCharsets.UTF_8));Object value=expected.opt("count");
                if(!(value instanceof Number)||((Number)value).doubleValue()!=((Number)value).longValue())throw new IOException("Portable policy anchor count is invalid");
                long count=((Number)value).longValue();JSONArray controls=saved.optJSONArray("deletions"),prefix=new JSONArray();
                if(count<0||count>controls.length())throw new IOException("Portable deletion controls were rolled back");
                for(int i=0;i<count;i++)prefix.put(controls.opt(i));
                if(!hash(prefix.toString()).equals(expected.optString("prefixHash")))throw new IOException("Portable deletion control history changed");
            }catch(JSONException invalid){throw new IOException("Portable policy anchor is malformed",invalid);}
        }
        return saved;
    }
    private static String hash(String value){return OriginalTextFiles.hex(OriginalTextFiles.digest().digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static void atomic(File root,File target,String body)throws IOException {
        if(!root.isDirectory()||Files.isSymbolicLink(root.toPath())||Files.isSymbolicLink(target.toPath()))throw new IOException("Unsafe portable owner policy storage");
        File temporary=new File(root,".owner-policy-partial-"+UUID.randomUUID());
        try{try(FileOutputStream output=new FileOutputStream(temporary)){output.write(body.getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(FileChannel directory=FileChannel.open(root.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){directory.force(true);}
            catch(IOException|UnsupportedOperationException nio){java.io.FileDescriptor descriptor=null;try{descriptor=android.system.Os.open(root.getPath(),android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_NOFOLLOW,0);android.system.Os.fsync(descriptor);}catch(android.system.ErrnoException failure){throw new IOException("Portable control directory sync failed",failure);}finally{if(descriptor!=null)try{android.system.Os.close(descriptor);}catch(android.system.ErrnoException ignored){}}}
        }finally{if(temporary.exists()&&!temporary.delete())throw new IOException("Incomplete portable owner policy could not be removed");}
    }
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
}
