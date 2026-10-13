package com.hoyoung.assistant.memory;

import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Native, approximate HNSW over int8 cosine vectors. This cache never replaces originals.
 * Calls are serialized; construction and maintenance must run off Android's main thread. */
public final class NativeAnnIndex implements AutoCloseable {
    public static final String PROFILE = "hnswlib-0.8.0-int8-cosine-norm-v1-64bit";
    public static final int DEFAULT_M = 16, DEFAULT_EF_CONSTRUCTION = 200, DEFAULT_EF_SEARCH = 200;
    public static final int MAX_ELEMENTS = 2_000_000;
    private static final boolean AVAILABLE;
    static { boolean loaded; try { System.loadLibrary("hoyoung_ann"); loaded=true; } catch(UnsatisfiedLinkError unavailable){loaded=false;} AVAILABLE=loaded; }
    private long handle;
    private final int dimensions;
    private final String modelId;
    private int capacity;
    public static boolean isAvailable(){return AVAILABLE;}
    public NativeAnnIndex(String modelId,int dimensions,int capacity){this(modelId,dimensions,capacity,DEFAULT_M,DEFAULT_EF_CONSTRUCTION);}
    public NativeAnnIndex(String modelId,int dimensions,int capacity,int m,int constructionEf){
        if(!AVAILABLE)throw new IllegalStateException("Native ANN library unavailable on this ABI");
        if(modelId==null||modelId.isEmpty()||modelId.length()>4096)throw new IllegalArgumentException("Missing encoder identity");
        this.modelId=modelId;this.dimensions=dimensions;this.capacity=capacity;handle=nativeCreate(dimensions,capacity,m,constructionEf);
    }
    public synchronized int count(){checkOpen();return nativeCount(handle);}
    public synchronized void reserve(int desired){checkOpen();if(desired>capacity){nativeReserve(handle,desired);capacity=desired;}}
    public synchronized void addBatch(long[] labels,float[] vectors){
        checkOpen();if(labels==null||vectors==null||labels.length<1||labels.length>1024||vectors.length!=(long)labels.length*dimensions)throw new IllegalArgumentException("Invalid ANN batch dimensions");
        int required=Math.addExact(count(),labels.length);if(required>capacity)reserve(Math.min(MAX_ELEMENTS,Math.max(required,capacity+Math.max(256,capacity/4))));
        nativeAddBatch(handle,labels,vectors);
    }
    public synchronized void setExcludedLabels(long[] denied){checkOpen();nativeExclude(handle,denied);}
    public synchronized SearchResult search(float[] query,int k,int ef){checkOpen();return nativeSearch(handle,query,k,Math.max(k,ef));}
    public synchronized JSONObject saveVerified(File directory,String canonicalFingerprint)throws IOException{
        return saveVerified(directory,canonicalFingerprint,null);
    }
    /** Optional checkpoint metadata is bound to the verified canonical prefix fingerprint. */
    public synchronized JSONObject saveVerified(File directory,String canonicalFingerprint,JSONObject checkpoint)throws IOException{
        checkOpen();validateFingerprint(canonicalFingerprint);if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create ANN cache directory");
        JSONObject checkpointCopy=null;
        if(checkpoint!=null){
            if(!canonicalFingerprint.equals(checkpoint.optString("prefixSha256"))||checkpoint.optLong("canonicalCount",-1)!=count())throw new IOException("ANN checkpoint does not describe this graph");
            try{checkpointCopy=new JSONObject(checkpoint.toString());}catch(Exception invalid){throw new IOException("Invalid ANN checkpoint metadata",invalid);}
        }
        String name="graph-"+UUID.randomUUID()+".bin";File graph=new File(directory,name),pending=new File(directory,name+".pending");
        try{
            nativeSave(handle,pending.getAbsolutePath());try(FileOutputStream flush=new FileOutputStream(pending,true)){flush.getFD().sync();}
            Files.move(pending.toPath(),graph.toPath(),StandardCopyOption.ATOMIC_MOVE);
            JSONObject manifest=new JSONObject();put(manifest,"schema",checkpoint==null?1:2);put(manifest,"profile",PROFILE);put(manifest,"modelId",modelId);put(manifest,"dimensions",dimensions);put(manifest,"count",count());put(manifest,"capacity",capacity);put(manifest,"canonicalFingerprint",canonicalFingerprint);put(manifest,"file",name);put(manifest,"bytes",graph.length());put(manifest,"sha256",sha256(graph));put(manifest,"approximateNearestNeighbor",true);
            if(checkpointCopy!=null)put(manifest,"checkpoint",checkpointCopy);
            File metadataPending=new File(directory,"manifest.json.pending"),metadata=new File(directory,"manifest.json");
            try(FileOutputStream output=new FileOutputStream(metadataPending)){output.write(manifest.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
            Files.move(metadataPending.toPath(),metadata.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            // Only remove old graphs after the new manifest points to a fully flushed cache.
            File[] old=directory.listFiles();if(old!=null)for(File file:old)if(file.getName().startsWith("graph-")&&!file.getName().equals(name))file.delete();
            return manifest;
        }catch(RuntimeException nativeError){throw new IOException("ANN cache save failed",nativeError);}finally{pending.delete();}
    }
    public synchronized boolean loadVerified(File directory,String canonicalFingerprint)throws IOException{
        checkOpen();validateFingerprint(canonicalFingerprint);
        try{
            JSONObject manifest=readManifest(directory);if(manifest==null)return false;
            if((manifest.optInt("schema")!=1&&manifest.optInt("schema")!=2)||!PROFILE.equals(manifest.optString("profile"))||!modelId.equals(manifest.optString("modelId"))||dimensions!=manifest.optInt("dimensions")||!canonicalFingerprint.equals(manifest.optString("canonicalFingerprint")))return false;
            String name=manifest.optString("file");if(!name.matches("graph-[a-fA-F0-9-]{36}\\.bin"))throw new IOException("Invalid ANN cache filename");
            File graph=new File(directory,name);long bytes=manifest.optLong("bytes",-1);int count=manifest.optInt("count",-1),storedCapacity=manifest.optInt("capacity",-1);
            if(count<0||count>MAX_ELEMENTS||storedCapacity<count||storedCapacity>MAX_ELEMENTS||bytes<96||bytes>3L*1024*1024*1024||!graph.isFile()||graph.length()!=bytes||!sha256(graph).equals(manifest.optString("sha256")))throw new IOException("ANN cache integrity mismatch");
            nativeLoad(handle,graph.getAbsolutePath(),count);capacity=storedCapacity;return true;
        }catch(IOException e){throw e;}catch(Exception invalid){throw new IOException("ANN cache is incompatible or corrupt; rebuild from canonical vectors",invalid);}
    }
    /** This is untrusted metadata until loadVerified validates its graph hash and identity. */
    public static JSONObject readManifest(File directory)throws IOException{
        File metadata=new File(directory,"manifest.json");if(!metadata.isFile())return null;
        if(metadata.length()>16_384)throw new IOException("ANN cache manifest too large");
        try{return new JSONObject(new String(Files.readAllBytes(metadata.toPath()),StandardCharsets.UTF_8));}
        catch(Exception invalid){throw new IOException("Invalid ANN cache manifest",invalid);}
    }
    public synchronized void close(){if(handle!=0){nativeClose(handle);handle=0;}}
    private void checkOpen(){if(handle==0)throw new IllegalStateException("ANN index closed");}
    private static void validateFingerprint(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Canonical ANN fingerprint must be SHA-256");}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static String sha256(File file)throws IOException{try{MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[64*1024];try(FileInputStream in=new FileInputStream(file)){for(int n;(n=in.read(buffer))!=-1;)hash.update(buffer,0,n);}StringBuilder hex=new StringBuilder(64);for(byte b:hash.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    public static final class SearchResult { public final long[] labels;public final float[] similarities;private SearchResult(long[] labels,float[] similarities){this.labels=labels;this.similarities=similarities;} }
    private static native long nativeCreate(int dimensions,int capacity,int m,int constructionEf);
    private static native void nativeClose(long handle);
    private static native int nativeCount(long handle);
    private static native void nativeReserve(long handle,int capacity);
    private static native void nativeAddBatch(long handle,long[] labels,float[] vectors);
    private static native void nativeExclude(long handle,long[] labels);
    private static native SearchResult nativeSearch(long handle,float[] query,int k,int ef);
    private static native void nativeSave(long handle,String path);
    private static native void nativeLoad(long handle,String path,int count);
}
