package com.hoyoung.assistant.store;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Append-only one-use recovery-code reservations, outside namespaces replaced by old ZIPs.
 * Only hashes and native reference metadata are retained here; never raw recovery codes. */
public final class RecoveryCodeLedger {
    public static final String FORMAT="hoyoung-recovery-reservations-v1";
    private static final long MAX_BYTES=16L*1024*1024;
    private static final int MAX_ENTRIES=100_000;
    private static final Object LOCK=new Object();
    private final Context context;
    private final File root,ledger,anchor,lock;
    public RecoveryCodeLedger(Context supplied) {
        if(supplied==null)throw new IllegalArgumentException("An application context is required");
        context=supplied.getApplicationContext();root=context.getFilesDir();
        ledger=new File(root,"recovery-code-reservations.json");anchor=new File(root,"recovery-code-reservations.anchor.json");lock=new File(root,".recovery-code-reservations.lock");
    }
    public static String fingerprint(String accountId,String exactCode) {
        bounded(accountId,160);bounded(exactCode,20_000);return hash(new JSONArray().put("account").put(accountId).put(exactCode).toString());
    }
    public static String stableFingerprint(String exactService,String exactUsername,String exactCode) {
        bounded(exactService,1000);bounded(exactUsername,4000);bounded(exactCode,20_000);
        return hash(new JSONArray().put("service-user").put(exactService).put(exactUsername).put(exactCode).toString());
    }
    public JSONObject snapshot()throws IOException {synchronized(LOCK){return read();}}
    public boolean isReserved(String fingerprint)throws IOException {
        digest(fingerprint);JSONObject snapshot=snapshot();JSONArray entries=snapshot.optJSONArray("reservations");
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);if(fingerprint.equals(entry.optString("codeFingerprint"))||fingerprint.equals(stable(entry)))return true;}return false;
    }
    /** A reservation is durable before a caller can return its raw code. An uncertain attempt
     * remains reserved, rather than treating a crash as proof the code was never used. */
    public JSONObject reserve(String accountId,String codeFingerprint,String requestId,JSONObject origin)throws IOException {
        bounded(accountId,160);bounded(requestId,512);digest(codeFingerprint);JSONObject metadata=metadata(origin);
        synchronized(LOCK){
            safeRoot();if(Files.isSymbolicLink(lock.toPath()))throw new IOException("Recovery reservation lock is unavailable");
            try(FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=channel.lock()){
                JSONObject previous=read();JSONArray entries=previous.optJSONArray("reservations");String stable=metadata.optString("stableCodeFingerprint");
                for(int i=0;i<entries.length();i++){
                    JSONObject entry=entries.optJSONObject(i);
                    if(codeFingerprint.equals(entry.optString("codeFingerprint"))||!stable.isEmpty()&&stable.equals(stable(entry))){
                        if(accountId.equals(entry.optString("accountId"))&&codeFingerprint.equals(entry.optString("codeFingerprint"))&&requestId.equals(entry.optString("requestId"))){
                            write(previous);markPortablePending();return object("reserved",true,"reusedReservation",true,"code","reserved","reservation",copy(entry));
                        }
                        return object("reserved",false,"reusedReservation",false,"code","already_reserved");
                    }
                }
                JSONObject entry=object("accountId",accountId,"codeFingerprint",codeFingerprint,"requestId",requestId,"reservedAt",System.currentTimeMillis(),"origin",metadata);
                entries.put(entry);JSONObject next=create(entries);write(next);markPortablePending();
                return object("reserved",true,"reusedReservation",false,"code","reserved","reservation",copy(entry));
            }
        }
    }
    public JSONObject mergeSnapshot(JSONObject supplied)throws IOException {
        JSONObject incoming=validate(supplied);
        synchronized(LOCK){safeRoot();if(Files.isSymbolicLink(lock.toPath()))throw new IOException("Recovery reservation lock is unavailable");
            try(FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=channel.lock()){
                JSONObject previous=read(),next=unionSnapshots(previous,incoming);
                if(!previous.optString("checksum").equals(next.optString("checksum")))write(next);
                return copy(next);
            }
        }
    }
    public static JSONObject emptySnapshot()throws IOException{return create(new JSONArray());}
    public static JSONObject unionSnapshots(JSONObject first,JSONObject second)throws IOException {
        JSONObject a=validate(first),b=validate(second);Map<String,JSONObject> entries=new LinkedHashMap<>();
        for(JSONObject snapshot:new JSONObject[]{a,b}){JSONArray values=snapshot.optJSONArray("reservations");for(int i=0;i<values.length();i++){
            JSONObject entry=values.optJSONObject(i);entries.putIfAbsent(hash(canonical(entry)),entry);
        }}
        JSONArray merged=new JSONArray();for(JSONObject entry:entries.values())merged.put(entry);return create(merged);
    }
    public static JSONObject validate(JSONObject supplied)throws IOException {
        if(supplied==null)throw new IOException("Recovery reservation snapshot is missing");JSONObject snapshot=copy(supplied);
        if(!FORMAT.equals(snapshot.optString("format"))||snapshot.length()!=3||snapshot.optJSONArray("reservations")==null)throw new IOException("Invalid recovery reservation snapshot");
        String checksum=snapshot.optString("checksum");digest(checksum);snapshot.remove("checksum");
        if(!checksum.equals(hash(canonical(snapshot))))throw new IOException("Recovery reservation snapshot checksum changed");
        JSONArray entries=snapshot.optJSONArray("reservations");if(entries.length()>MAX_ENTRIES)throw new IOException("Recovery reservation history exceeds capacity");Set<String> exact=new HashSet<>();
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.optJSONObject(i);if(entry==null||entry.length()!=5)throw new IOException("Invalid recovery reservation entry");
            text(entry,"accountId",160);text(entry,"requestId",512);digest(entry.optString("codeFingerprint"));nonnegative(entry.opt("reservedAt"));metadata(entry.optJSONObject("origin"));
            if(!exact.add(hash(canonical(entry))))throw new IOException("Duplicate recovery reservation entry");
        }
        put(snapshot,"checksum",checksum);if(snapshot.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("Recovery reservation history exceeds its byte capacity");return snapshot;
    }
    private JSONObject read()throws IOException {
        safeRoot();if(Files.isSymbolicLink(ledger.toPath())||Files.isSymbolicLink(anchor.toPath()))throw new IOException("Recovery reservation storage is unavailable");
        JSONObject state=ledger.exists()?validate(readObject(ledger,MAX_BYTES)):emptySnapshot();
        if(anchor.exists()){
            JSONObject established=readObject(anchor,1024);if(!FORMAT.equals(established.optString("format"))||established.length()!=3)throw new IOException("Invalid recovery reservation anchor");
            long count=nonnegative(established.opt("count"));String expected=established.optString("prefixHash");digest(expected);JSONArray entries=state.optJSONArray("reservations"),prefix=new JSONArray();
            if(count>entries.length())throw new IOException("Recovery reservation history was rolled back");for(int i=0;i<count;i++)prefix.put(entries.opt(i));
            if(!hash(canonical(prefix)).equals(expected))throw new IOException("Recovery reservation history differs from its anchor");
        }else if(ledger.exists())throw new IOException("Established recovery reservation anchor is missing");
        return state;
    }
    private void write(JSONObject supplied)throws IOException {
        JSONObject snapshot=validate(supplied);safeRoot();
        // Establish the empty anchor first. If first publication is interrupted, a complete
        // new ledger can be read with this valid prefix; an established history cannot vanish.
        if(!anchor.exists())atomic(anchor,object("format",FORMAT,"count",0,"prefixHash",hash(canonical(new JSONArray()))).toString());
        atomic(ledger,snapshot.toString());JSONArray entries=snapshot.optJSONArray("reservations");
        atomic(anchor,object("format",FORMAT,"count",entries.length(),"prefixHash",hash(canonical(entries))).toString());
    }
    private void markPortablePending()throws IOException {
        // Durable queueing does not claim that a shared-folder provider has completed its copy.
        new ExternalOwnerPolicyFolder(context).markPendingDurably();
    }
    private void safeRoot()throws IOException {if(!root.isDirectory()||Files.isSymbolicLink(root.toPath()))throw new IOException("Recovery reservation directory is unavailable");}
    private void atomic(File target,String body)throws IOException {
        if(Files.isSymbolicLink(target.toPath()))throw new IOException("Recovery reservation publication path changed");File temporary=new File(root,".recovery-reservation-partial-"+UUID.randomUUID());
        try{try(FileOutputStream output=new FileOutputStream(temporary)){output.write(body.getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(FileChannel directory=FileChannel.open(root.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){directory.force(true);}
            catch(IOException|UnsupportedOperationException nio){java.io.FileDescriptor descriptor=null;
                try{descriptor=android.system.Os.open(root.getPath(),android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_NOFOLLOW,0);android.system.Os.fsync(descriptor);}
                catch(android.system.ErrnoException failure){throw new IOException("Recovery reservation directory sync failed",failure);}
                finally{if(descriptor!=null)try{android.system.Os.close(descriptor);}catch(android.system.ErrnoException ignored){}}
            }
        }finally{if(temporary.exists()&&!temporary.delete())throw new IOException("Incomplete recovery reservation could not be cleaned up");}
    }
    private static JSONObject metadata(JSONObject supplied)throws IOException {
        if(supplied==null)throw new IOException("Native recovery reservation origin is required");JSONObject origin=copy(supplied);
        Set<String> allowed=new HashSet<>(Arrays.asList("stableCodeFingerprint","accountRecordHash","accountRevision","inputActor","sourceMessageId","sourceMessageHash","sourceRefs"));
        Iterator<String> names=origin.keys();while(names.hasNext())if(!allowed.contains(names.next()))throw new IOException("Recovery origin accepts only reference metadata, not credentials");
        for(String key:new String[]{"stableCodeFingerprint","accountRecordHash","sourceMessageHash"})if(origin.has(key))digest(origin.optString(key));
        for(String key:new String[]{"accountRevision","sourceMessageId"})if(origin.has(key)&&nonnegative(origin.opt(key))==0)throw new IOException("Native recovery reference must be positive");
        if(origin.has("inputActor"))text(origin,"inputActor",80);
        if(origin.has("sourceRefs")){JSONArray refs=origin.optJSONArray("sourceRefs");if(refs==null||refs.length()>128)throw new IOException("Invalid native recovery sources");for(int i=0;i<refs.length();i++){
            JSONObject ref=refs.optJSONObject(i);if(ref==null||ref.length()!=3||!("message".equals(ref.optString("type"))||"device_event".equals(ref.optString("type")))||nonnegative(ref.opt("id"))==0)throw new IOException("Invalid native recovery reference");digest(ref.optString("hash"));
        }}
        return origin;
    }
    private static String stable(JSONObject entry){JSONObject origin=entry.optJSONObject("origin");return origin==null?"":origin.optString("stableCodeFingerprint");}
    private static JSONObject create(JSONArray entries)throws IOException {JSONObject result=object("format",FORMAT,"reservations",entries);put(result,"checksum",hash(canonical(result)));return validate(result);}
    private static JSONObject readObject(File file,long maximum)throws IOException {
        if(!file.isFile()||file.length()>maximum)throw new IOException("Recovery reservation file is unavailable or oversized");String text=new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);HoyoungStore.checkJsonDepth(text);
        try{JSONTokener parser=new JSONTokener(text);Object value=parser.nextValue();if(!(value instanceof JSONObject)||parser.nextClean()!=0)throw new IOException("Invalid recovery reservation document");return (JSONObject)value;}catch(JSONException invalid){throw new IOException("Invalid recovery reservation JSON",invalid);}
    }
    private static JSONObject copy(JSONObject object)throws IOException {try{return new JSONObject(object.toString());}catch(JSONException invalid){throw new IOException("Invalid recovery reservation metadata",invalid);}}
    private static long nonnegative(Object value)throws IOException {if(!(value instanceof Number))throw new IOException("Recovery reservation integer is required");Number n=(Number)value;long result=n.longValue();if(result<0||!Double.isFinite(n.doubleValue())||n.doubleValue()!=(double)result)throw new IOException("Invalid recovery reservation integer");return result;}
    private static void text(JSONObject object,String key,int max)throws IOException {Object value=object.opt(key);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>max)throw new IOException("Invalid recovery reference text");}
    private static void bounded(String value,int max){if(value==null||value.isEmpty()||value.length()>max)throw new IllegalArgumentException("Invalid recovery fingerprint scope");}
    private static void digest(String value)throws IOException {if(value==null||!value.matches("[a-f0-9]{64}"))throw new IOException("Invalid recovery reservation fingerprint");}
    private static String hash(String value){return OriginalTextFiles.hex(OriginalTextFiles.digest().digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static String canonical(Object value){
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);Collections.sort(keys);StringBuilder text=new StringBuilder("{");for(String key:keys){if(text.length()>1)text.append(',');text.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key)));}return text.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder text=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)text.append(',');text.append(canonical(array.opt(i)));}return text.append(']').toString();}
        return value==null||value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    private static JSONObject object(Object...pairs){JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)put(result,(String)pairs[i],pairs[i+1]);return result;}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
}
