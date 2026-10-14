package com.hoyoung.assistant.store;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;

/** Owner-controlled retrieval exclusions. This file is deliberately outside restored namespaces.
 * Excluding a source never claims to erase its original payload or already delivered content. */
public final class SourcePrivacyLedger {
    public static final String FORMAT="hoyoung-source-privacy-v1";
    public static final String RETRIEVAL_EXCLUDE="retrieval_exclude";
    private static final long MAX_BYTES=16L*1024*1024;
    private static final int MAX_EXCLUSIONS=100_000;
    private static final Object CONTROL_LOCK=new Object();
    private final File root,ledger,anchor,lock;

    public SourcePrivacyLedger(Context context){
        if(context==null)throw new IllegalArgumentException("An application context is required");
        root=context.getApplicationContext().getFilesDir();ledger=new File(root,"privacy-ledger.json");anchor=new File(root,"privacy-ledger.anchor.json");lock=new File(root,".privacy-ledger.lock");
    }

    /** Immutable policy view for one retrieval scope; mutations never alter an existing Snapshot. */
    public static final class Snapshot {
        public final long version;
        public final String fingerprint;
        public final int exclusionCount;
        private final String serialized;
        private final Set<String> excluded;
        private final Set<String> excludedDeviceHashes;
        private final Set<Long> excludedMessageIds;
        private Snapshot(JSONObject object,long version,String fingerprint,Set<String> excluded){
            serialized=object.toString();this.version=version;this.fingerprint=fingerprint;this.excluded=Collections.unmodifiableSet(new HashSet<>(excluded));exclusionCount=excluded.size();
            Set<String> deviceHashes=new HashSet<>();Set<Long> messageIds=new HashSet<>();
            for(String key:excluded){
                if(key.startsWith("device_event:"))deviceHashes.add(key.substring(key.lastIndexOf(':')+1));
                else if(key.startsWith("message:"))messageIds.add(Long.valueOf(key.substring("message:".length(),key.lastIndexOf(':'))));
            }
            excludedDeviceHashes=Collections.unmodifiableSet(deviceHashes);excludedMessageIds=Collections.unmodifiableSet(messageIds);
        }
        public JSONObject toJson(){try{return new JSONObject(serialized);}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
        public boolean allowedSource(String type,long id,String hash){
            try{return !excluded.contains(identity(type,id,hash))&&!("device_event".equals(type)&&excludedDeviceHashes.contains(hash));}catch(IllegalArgumentException invalid){return false;}
        }
        /** Conservative fallback for legacy native ancestry that recorded only a message ID.
         * Exact-hash source bindings must use allowedSource instead so a distinct new source
         * reusing an ID is not incorrectly excluded. This does not perform a live policy read. */
        public boolean hasExcludedMessageId(long id){return excludedMessageIds.contains(id);}
        /** A derived note/cache is unavailable when even one of its source dependencies is excluded. */
        public boolean allowedSourceRefs(JSONArray references){
            if(references==null)return false;
            for(int index=0;index<references.length();index++){
                JSONObject source=references.optJSONObject(index);if(source==null)return false;
                try{if(!allowedSource(source.optString("type"),positiveInteger(source.opt("id")),source.optString("hash")))return false;}
                catch(IllegalArgumentException invalid){return false;}
            }
            return true;
        }
    }

    public Snapshot capture()throws IOException{synchronized(CONTROL_LOCK){return read();}}
    public JSONObject snapshot()throws IOException{return capture().toJson();}
    public long version()throws IOException{return capture().version;}
    public String fingerprint()throws IOException{return capture().fingerprint;}
    /** Convenience live read. A multi-step retrieval should capture once and compare its fingerprint later. */
    public boolean allowedSource(String type,long id,String hash)throws IOException{return capture().allowedSource(type,id,hash);}
    public static Snapshot fromSnapshot(JSONObject frozen)throws IOException{return parse(frozen);}
    public static boolean allowedSource(JSONObject frozen,String type,long id,String hash){
        try{return parse(frozen).allowedSource(type,id,hash);}catch(IOException invalid){return false;}
    }
    public JSONObject diagnostics(){
        JSONObject result=new JSONObject();put(result,"scope",RETRIEVAL_EXCLUDE);put(result,"payloadDeletionSupported",false);put(result,"includedInBackup",false);
        try{Snapshot current=capture();put(result,"available",true);put(result,"version",current.version);put(result,"fingerprint",current.fingerprint);put(result,"exclusionCount",current.exclusionCount);}
        catch(IOException unavailable){put(result,"available",false);put(result,"retrievalBlocked",true);put(result,"failure",unavailable.getMessage());}
        return result;
    }

    /** Call only for an explicit owner control. The caller verifies the real source hash before this method. */
    public JSONObject excludeSource(String type,long id,String hash,String mode,String reason)throws IOException{
        String key=identity(type,id,hash);
        if(!RETRIEVAL_EXCLUDE.equals(mode))throw new IllegalArgumentException("Only retrieval_exclude is implemented; original payload deletion is not supported");
        if(reason==null)reason="";if(reason.length()>4096)throw new IllegalArgumentException("Exclusion reason exceeds 4096 characters");
        synchronized(CONTROL_LOCK){
            ensureSafeRoot();
            if(Files.isSymbolicLink(lock.toPath()))throw new IOException("Privacy control lock is unsafe");
            try(FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock control=channel.lock()){
                Snapshot previous=read(true);
                if(previous.excluded.contains(key)){writeAnchor(previous);return result(previous,false);}
                if(previous.exclusionCount>=MAX_EXCLUSIONS||previous.version==Long.MAX_VALUE)throw new IOException("Privacy ledger capacity is exhausted; previous policy is retained");
                JSONObject next=previous.toJson();next.remove("fingerprint");JSONArray exclusions=next.optJSONArray("exclusions");
                JSONObject entry=new JSONObject();put(entry,"type",type);put(entry,"id",id);put(entry,"hash",hash);put(entry,"mode",mode);put(entry,"reason",reason);put(entry,"excludedAt",System.currentTimeMillis());
                exclusions.put(entry);put(next,"version",previous.version+1);put(next,"fingerprint",fingerprintFor(next));
                Snapshot validated=parse(next);write(validated);return result(validated,true);
            }
        }
    }

    private static JSONObject result(Snapshot snapshot,boolean changed){
        JSONObject result=new JSONObject();put(result,"changed",changed);put(result,"version",snapshot.version);put(result,"fingerprint",snapshot.fingerprint);
        put(result,"scope",RETRIEVAL_EXCLUDE);put(result,"retrievalExcluded",true);put(result,"originalPayloadDeleted",false);put(result,"originalPayloadRetained",true);return result;
    }
    private Snapshot read()throws IOException{return read(false);}
    private Snapshot read(boolean explicitControl)throws IOException{
        ensureSafeRoot();
        if(Files.isSymbolicLink(ledger.toPath()))throw new IOException("Privacy ledger is unsafe");
        if(!ledger.exists()){
            if(anchor.exists()||Files.isSymbolicLink(anchor.toPath()))throw new IOException("Established privacy ledger is missing; retrieval is blocked");
            return empty();
        }
        if(!ledger.isFile()||ledger.length()>MAX_BYTES)throw new IOException("Privacy ledger is unavailable or exceeds its byte bound");
        try(InputStream input=Files.newInputStream(ledger.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[]buffer=new byte[16384];long count=0;int read;
            while((read=input.read(buffer))!=-1){count+=read;if(count>MAX_BYTES)throw new IOException("Privacy ledger grew beyond its byte bound");bytes.write(buffer,0,read);}
            try{Snapshot current=parse(new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)));validateAnchor(current,explicitControl);return current;}
            catch(JSONException invalid){throw new IOException("Privacy ledger is malformed; retrieval policy is unavailable",invalid);}
        }
    }
    /** The small durable anchor distinguishes an unused policy from a lost/rolled-back one. */
    private void validateAnchor(Snapshot current,boolean explicitControl)throws IOException{
        if(Files.isSymbolicLink(anchor.toPath()))throw new IOException("Privacy policy anchor is unsafe");
        if(!anchor.exists()){
            // A crash after publishing the first ledger but before its anchor is ambiguous.
            // Only an explicit owner control retry can finish that publication.
            if(explicitControl)return;
            throw new IOException("Privacy policy anchor is missing; retrieval is blocked");
        }
        if(!anchor.isFile()||anchor.length()>1024)throw new IOException("Privacy policy anchor is unavailable");
        JSONObject established;
        try{established=new JSONObject(new String(Files.readAllBytes(anchor.toPath()),StandardCharsets.UTF_8));}
        catch(JSONException invalid){throw new IOException("Privacy policy anchor is malformed",invalid);}
        long version;
        try{version=positiveInteger(established.opt("version"));}catch(IllegalArgumentException invalid){throw new IOException("Privacy anchor version is invalid",invalid);}
        String fingerprint=established.optString("fingerprint");
        if(!FORMAT.equals(established.optString("format"))||!fingerprint.matches("[a-f0-9]{64}")||version>current.version)throw new IOException("Privacy policy was rolled back or its anchor is invalid");
        JSONObject prefix=new JSONObject();put(prefix,"format",FORMAT);put(prefix,"version",version);JSONArray entries=new JSONArray();JSONArray all=current.toJson().optJSONArray("exclusions");
        for(int index=0;index<version;index++)entries.put(all.opt(index));put(prefix,"exclusions",entries);
        if(!MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),fingerprintFor(prefix).getBytes(StandardCharsets.US_ASCII)))throw new IOException("Privacy exclusion history differs from its durable anchor");
    }
    private static Snapshot empty()throws IOException{
        JSONObject object=new JSONObject();put(object,"format",FORMAT);put(object,"version",0L);put(object,"exclusions",new JSONArray());put(object,"fingerprint",fingerprintFor(object));return parse(object);
    }
    private static Snapshot parse(JSONObject original)throws IOException{
        if(original==null)throw new IOException("Privacy snapshot is missing");
        JSONObject object;try{object=new JSONObject(original.toString());}catch(JSONException invalid){throw new IOException("Privacy snapshot is malformed",invalid);}
        if(!FORMAT.equals(object.optString("format")))throw new IOException("Unknown privacy ledger format");
        long version;try{version=nonnegativeInteger(object.opt("version"));}catch(IllegalArgumentException invalid){throw new IOException("Invalid privacy ledger version",invalid);}
        JSONArray exclusions=object.optJSONArray("exclusions");String supplied=object.optString("fingerprint");
        if(exclusions==null||exclusions.length()>MAX_EXCLUSIONS||!supplied.matches("[a-f0-9]{64}"))throw new IOException("Invalid privacy ledger policy");
        if(!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII),fingerprintFor(object).getBytes(StandardCharsets.US_ASCII)))throw new IOException("Privacy ledger fingerprint does not match its contents");
        Set<String> identities=new HashSet<>();
        for(int index=0;index<exclusions.length();index++){
            JSONObject entry=exclusions.optJSONObject(index);if(entry==null)throw new IOException("Invalid privacy source exclusion");
            try{
                if(!RETRIEVAL_EXCLUDE.equals(entry.optString("mode"))||!(entry.opt("reason") instanceof String)||entry.optString("reason").length()>4096)throw new IllegalArgumentException("Invalid exclusion scope or reason");
                nonnegativeInteger(entry.opt("excludedAt"));String key=identity(entry.optString("type"),positiveInteger(entry.opt("id")),entry.optString("hash"));
                if(!identities.add(key))throw new IllegalArgumentException("Duplicate source exclusion");
            }catch(IllegalArgumentException invalid){throw new IOException("Invalid privacy source identity",invalid);}
        }
        if(version!=identities.size())throw new IOException("Privacy ledger version does not match its append-only exclusions");
        return new Snapshot(object,version,supplied,identities);
    }
    private void write(Snapshot snapshot)throws IOException{
        writeAtomic(ledger,snapshot.serialized,MAX_BYTES);writeAnchor(snapshot);
    }
    private void writeAnchor(Snapshot snapshot)throws IOException{
        if(snapshot.version==0)return;
        JSONObject state=new JSONObject();put(state,"format",FORMAT);put(state,"version",snapshot.version);put(state,"fingerprint",snapshot.fingerprint);writeAtomic(anchor,state.toString(),1024);
    }
    private void writeAtomic(File destination,String text,long maxBytes)throws IOException{
        ensureSafeRoot();if(Files.isSymbolicLink(destination.toPath()))throw new IOException("Privacy policy destination is unsafe");
        long bytes=utf8Length(text);if(bytes>maxBytes)throw new IOException("Privacy ledger exceeds its byte capacity; previous policy is retained");
        File temporary=new File(root,".privacy-ledger-partial-"+UUID.randomUUID());IOException primary=null;
        try{
            try(FileOutputStream file=new FileOutputStream(temporary);Writer writer=new OutputStreamWriter(file,StandardCharsets.UTF_8)){
                for(int offset=0;offset<text.length();offset+=8192)writer.write(text,offset,Math.min(8192,text.length()-offset));writer.flush();file.getFD().sync();
            }
            Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory(root);
        }catch(IOException unavailable){primary=unavailable;throw unavailable;}
        finally{if(temporary.exists()&&!temporary.delete()){IOException cleanup=new IOException("Incomplete privacy policy write is retained for diagnostics");if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}}
    }
    private void ensureSafeRoot()throws IOException{if(!root.isDirectory()||Files.isSymbolicLink(root.toPath()))throw new IOException("Private privacy policy storage is unavailable");}
    private static String identity(String type,long id,String hash){
        if(!("message".equals(type)||"device_event".equals(type))||id<=0||hash==null||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Privacy exclusions need a typed source id and canonical record SHA-256");
        return type+":"+id+":"+hash;
    }
    private static long positiveInteger(Object value){long result=nonnegativeInteger(value);if(result==0)throw new IllegalArgumentException("Source id must be positive");return result;}
    private static long nonnegativeInteger(Object value){
        if(!(value instanceof Number))throw new IllegalArgumentException("Integer metadata is required");Number number=(Number)value;long result=number.longValue();
        if(result<0||!Double.isFinite(number.doubleValue())||number.doubleValue()!=(double)result)throw new IllegalArgumentException("Nonnegative integer metadata is required");return result;
    }
    private static String fingerprintFor(JSONObject snapshot)throws IOException{
        try{JSONObject body=new JSONObject(snapshot.toString());body.remove("fingerprint");MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(Writer writer=new OutputStreamWriter(new java.security.DigestOutputStream(new OutputStream(){@Override public void write(int value){}@Override public void write(byte[]bytes,int offset,int length){}},digest),StandardCharsets.UTF_8)){
                String text=body.toString();for(int offset=0;offset<text.length();offset+=8192)writer.write(text,offset,Math.min(8192,text.length()-offset));
            }
            StringBuilder result=new StringBuilder();for(byte item:digest.digest())result.append(String.format(Locale.ROOT,"%02x",item&255));return result.toString();
        }catch(JSONException|java.security.NoSuchAlgorithmException invalid){throw new IOException("Privacy fingerprint could not be computed",invalid);}
    }
    private static long utf8Length(String text){long size=0;for(int offset=0;offset<text.length();offset++){char value=text.charAt(offset);if(value<128)size++;else if(value<2048)size+=2;else if(Character.isHighSurrogate(value)&&offset+1<text.length()&&Character.isLowSurrogate(text.charAt(offset+1))){size+=4;offset++;}else if(Character.isSurrogate(value))size++;else size+=3;}return size;}
    private static void syncDirectory(File directory)throws IOException{
        try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
        catch(IOException|UnsupportedOperationException nioUnavailable){java.io.FileDescriptor descriptor=null;try{descriptor=android.system.Os.open(directory.getPath(),android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_NOFOLLOW,0);if(!android.system.OsConstants.S_ISDIR(android.system.Os.fstat(descriptor).st_mode))throw new IOException("Privacy fsync target is not a directory");android.system.Os.fsync(descriptor);}catch(android.system.ErrnoException unavailable){throw new IOException("Privacy policy directory fsync failed",unavailable);}finally{if(descriptor!=null)try{android.system.Os.close(descriptor);}catch(android.system.ErrnoException ignored){}}}
    }
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
}
