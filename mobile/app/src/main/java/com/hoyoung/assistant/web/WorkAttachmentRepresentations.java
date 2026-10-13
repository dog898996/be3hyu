package com.hoyoung.assistant.web;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Looper;
import android.util.Base64;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Local delivery copies. Originals and their canonical identity never become JPEG derivatives. */
public final class WorkAttachmentRepresentations {
    public static final long MAX_WIRE_BYTES=10L*1024*1024;
    public static final long MAX_CACHE_BYTES=256L*1024*1024;
    private static final int MAX_CACHE_ENTRIES=2048;
    private static final long MAX_SOURCE_PIXELS=256_000_000L,MAX_OUTPUT_PIXELS=2_000_000L;
    private static final String VERSION="work-delivery-v1";
    private static final Object CACHE_LOCK=new Object();
    private final InternalFiles files;private final File cache,authDirectory;private final JpegEncoder encoder;private byte[] authenticationKey;
    /** Injectable native encoder boundary permits real resource/write failure regression tests. */
    interface JpegEncoder { boolean encode(Bitmap bitmap,int quality,OutputStream output); }

    public WorkAttachmentRepresentations(Context context,InternalFiles files){
        this(context,files,(bitmap,quality,output)->bitmap.compress(Bitmap.CompressFormat.JPEG,quality,output));
    }
    WorkAttachmentRepresentations(Context context,InternalFiles files,JpegEncoder encoder){
        if(context==null||files==null)throw new IllegalArgumentException("Context and private files are required");
        if(encoder==null)throw new IllegalArgumentException("Image encoder is required");
        this.files=files;this.encoder=encoder;File privateRoot=context.getApplicationContext().getNoBackupFilesDir();
        cache=new File(privateRoot,"work-attachment-representations");authDirectory=new File(privateRoot,"work-attachment-representation-auth");
    }

    public static final class RepresentationFailure extends IOException {
        private static final long serialVersionUID=1L;public final String code;
        RepresentationFailure(String code,String message,Throwable cause){super(message,cause);this.code=code;}
    }

    public static final class Representation {
        public final JSONObject originalSource;
        public final String originalAssetId,originalSha256,name,mime,wireSha256;
        public final long originalSize,size;
        public final boolean lossy,resized,alphaFlattened,orientationChanged;
        public final int sourceWidth,sourceHeight,wireWidth,wireHeight,jpegQuality;
        public final String representationAssetId,orientationStatus,representationKind;
        private final String originalJson;private final File body;
        private Representation(JSONObject source,File body,String name,String mime,long size,String sha,boolean lossy,
                               boolean resized,boolean alpha,boolean orientation,int sourceWidth,int sourceHeight,
                               int width,int height,int quality,String orientationStatus){
            originalJson=source.toString();originalSource=json(originalJson);this.body=body;
            originalAssetId=source.optString("originalAssetId",source.optString("id"));originalSha256=source.optString("sha256");originalSize=source.optLong("size");
            this.name=name;this.mime=mime;this.size=size;wireSha256=sha;this.lossy=lossy;this.resized=resized;
            alphaFlattened=alpha;orientationChanged=orientation;this.sourceWidth=sourceWidth;this.sourceHeight=sourceHeight;
            wireWidth=width;wireHeight=height;jpegQuality=quality;this.orientationStatus=orientationStatus;
            representationKind=lossy?"sampled_jpeg":"exact_original";
            representationAssetId=lossy?"derived-sha256:"+sha:originalAssetId;
        }
        /** Flat plan identity remains the original; wire identity is a separate, required group. */
        public JSONObject metadata(){
            JSONObject original=json(originalJson),result=json(originalJson);result.remove("path");
            put(result,"originalSource",original);put(result,"id",originalAssetId);put(result,"sha256",originalSha256);put(result,"size",originalSize);
            put(result,"originalAssetId",originalAssetId);put(result,"originalSha256",originalSha256);put(result,"originalSize",originalSize);
            put(result,"wireName",name);put(result,"wireMime",mime);put(result,"wireBytes",size);put(result,"wireSize",size);
            put(result,"providerRepresentationSha256",wireSha256);put(result,"wireSha256",wireSha256);put(result,"representationAssetId",representationAssetId);
            put(result,"representationKind",representationKind);put(result,"analysisScope",lossy?"delivered_representation":"original_bytes");
            put(result,"originalBytesIncludedInWire",!lossy&&size==originalSize&&wireSha256.equals(originalSha256));
            put(result,"originalBytesFullyUploaded",false);put(result,"serverBytesIndependentlyVerified",false);put(result,"originalContentFullyInspected",false);
            put(result,"lossy",lossy);put(result,"resized",resized);put(result,"alphaFlattened",alphaFlattened);put(result,"orientationChanged",orientationChanged);
            put(result,"sourceWidth",sourceWidth);put(result,"sourceHeight",sourceHeight);put(result,"wireWidth",wireWidth);put(result,"wireHeight",wireHeight);
            put(result,"dimensionsKnown",sourceWidth>0&&sourceHeight>0);put(result,"jpegQuality",jpegQuality);put(result,"orientationStatus",orientationStatus);
            put(result,"singleFrameRepresentation",lossy);put(result,"version",VERSION);put(result,"stage","wire_prepared");return result;
        }
        /** One pinned inode is verified before opening and as its complete contents are consumed. */
        public InputStream openStream()throws IOException{
            FileInputStream input;
            synchronized(CACHE_LOCK){safeCacheFile(body);input=new FileInputStream(body);body.setLastModified(System.currentTimeMillis());}
            try{verify(input,size,wireSha256);input.getChannel().position(0);return new VerifiedWireInput(input,size,wireSha256);}
            catch(IOException|RuntimeException|Error failure){try{input.close();}catch(IOException close){failure.addSuppressed(close);}throw failure;}
        }
        /** The only whole-body buffer is the bounded wire copy (at most 10 MiB), never a 64 MiB source. */
        public String payloadBase64()throws IOException{
            try(InputStream input=openStream();ByteArrayOutputStream output=new ByteArrayOutputStream((int)size)){
                byte[]buffer=new byte[16_384];long count=0;int read;
                while((read=input.read(buffer))!=-1){count+=read;if(count>size||count>MAX_WIRE_BYTES)throw fail("integrity_failed","Wire representation size changed",null);output.write(buffer,0,read);}
                if(count!=size)throw fail("integrity_failed","Wire representation was truncated",null);
                return Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP);
            }catch(OutOfMemoryError exhausted){throw fail("memory_unavailable","Memory is insufficient for the bounded wire payload",exhausted);}
        }
    }

    /** Background work only. The budget is supplied by the request-wide 20 MiB/8-file allocator. */
    public Representation prepare(JSONObject supplied,long budget)throws RepresentationFailure{
        if(Looper.myLooper()==Looper.getMainLooper())throw fail("background_required","Attachment preparation requires an IO worker",null);
        if(budget<1||budget>MAX_WIRE_BYTES)throw fail("wire_budget","Wire byte budget must be from 1 through 10 MiB",null);
        validateSource(supplied);
        try{
            JSONObject source=files.freezeAttachments(new JSONArray().put(supplied)).getJSONObject(0);
            if(supplied.has("sourceMessageId"))put(source,"sourceMessageId",supplied.opt("sourceMessageId"));
            String originalSha=source.optString("sha256"),id=source.optString("id");long originalSize=source.optLong("size");
            if(originalSize>InternalFiles.MAX_ATTACHMENT_BYTES)throw fail("source_too_large","Original attachment exceeds 64 MiB",null);
            if(originalSize>budget&&!source.optString("mime",source.optString("mimeType","")).toLowerCase(Locale.ROOT).startsWith("image/"))
                throw fail("non_image_too_large","A non-image original exceeds the available Work wire budget",null);
            synchronized(CACHE_LOCK){
                ensureCache();ensureAuthenticationKey();prune(null);
                String key=sha((VERSION+"\n"+Build.VERSION.SDK_INT+"\n"+id+"\n"+originalSha+"\n"+budget).getBytes(StandardCharsets.UTF_8));
                File plan=new File(cache,"plan-"+key+".json");
                Representation cached=readPlan(plan,source,budget);
                if(cached!=null){cached.body.setLastModified(System.currentTimeMillis());plan.setLastModified(System.currentTimeMillis());return cached;}
                Representation result=originalSize<=budget?exact(source,budget):image(source,budget);
                writePlan(plan,result);prune(result.body);return result;
            }
        }catch(RepresentationFailure failure){throw failure;}
        catch(InternalFiles.ImageSourceFailure invalid){throw fail(invalid.code,"Original image could not be verified",invalid);}
        catch(IOException unreadable){throw fail("source_unavailable","Original or private delivery cache is unavailable",unreadable);}
        catch(JSONException invalid){throw fail("invalid_source","Original attachment metadata is invalid",invalid);}
        catch(OutOfMemoryError exhausted){throw fail("memory_unavailable","Memory is insufficient for a sampled delivery image",exhausted);}
        catch(RuntimeException corrupt){throw fail("corrupt_image","The image could not be decoded or encoded",corrupt);}
    }

    private Representation exact(JSONObject source,long budget)throws IOException{
        File partial=partial();BoundedFileOutput output=null;
        try{
            output=new BoundedFileOutput(partial,budget);
            if(source.optString("mime",source.optString("mimeType","")).toLowerCase(Locale.ROOT).startsWith("image/")){
                try(InternalFiles.VerifiedImageSource input=files.openVerifiedImage(source)){copy(input.stream(),output);input.verifyUnchanged();}
            }else files.exportFile(source.optString("id"),output);
            output.finish();String wireHash=output.hash();long bytes=output.count;
            if(bytes!=source.optLong("size")||!wireHash.equals(source.optString("sha256")))throw fail("integrity_failed","Exact wire bytes differ from the original identity",null);
            File published=publish(partial,bytes,wireHash);
            return new Representation(source,published,wireName(source,false),source.optString("mime",source.optString("mimeType","application/octet-stream")),bytes,wireHash,false,false,false,false,0,0,0,0,0,"not_transformed");
        }finally{if(output!=null)output.close();deletePartial(partial);}
    }

    private Representation image(JSONObject source,long budget)throws IOException{
        Bitmap bitmap=null;boolean alpha=false,orientationChanged=false,resized=false;String orientationStatus="unknown";
        try(InternalFiles.VerifiedImageSource input=files.openVerifiedImage(source)){
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeStream(input.stream(),null,bounds);input.rewind();
            int sourceWidth=bounds.outWidth,sourceHeight=bounds.outHeight;
            if(sourceWidth<=0||sourceHeight<=0)throw fail("corrupt_image","Image bounds could not be decoded",null);
            if(sourceWidth>32768||sourceHeight>32768||(long)sourceWidth*sourceHeight>MAX_SOURCE_PIXELS)throw fail("image_dimensions","Image source exceeds 256 MP or dimension limits",null);
            int sample=1;while((sourceWidth+(long)sample-1)/sample>2048||(sourceHeight+(long)sample-1)/sample>2048
                    ||((sourceWidth+(long)sample-1)/sample)*((sourceHeight+(long)sample-1)/sample)>MAX_OUTPUT_PIXELS)sample*=2;
            BitmapFactory.Options decode=new BitmapFactory.Options();decode.inSampleSize=sample;decode.inScaled=false;decode.inPreferredConfig=Bitmap.Config.ARGB_8888;
            bitmap=BitmapFactory.decodeStream(input.stream(),null,decode);input.rewind();
            if(bitmap==null)throw fail("corrupt_image","Image pixels could not be decoded",null);
            checkBitmap(bitmap);resized=bitmap.getWidth()!=sourceWidth||bitmap.getHeight()!=sourceHeight;
            int orientation=ExifInterface.ORIENTATION_NORMAL;
            try{orientation=new ExifInterface(input.stream()).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);orientationStatus="known";}
            catch(IOException|RuntimeException unsupported){orientationStatus="unknown";}
            Matrix transform=orientationMatrix(orientation);orientationChanged=!transform.isIdentity();
            if(orientationChanged){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),transform,true);if(rotated!=bitmap){bitmap.recycle();bitmap=rotated;}}
            alpha=bitmap.hasAlpha();
            if(alpha){Bitmap flattened=Bitmap.createBitmap(bitmap.getWidth(),bitmap.getHeight(),Bitmap.Config.ARGB_8888);try{Canvas canvas=new Canvas(flattened);canvas.drawColor(Color.WHITE);canvas.drawBitmap(bitmap,0,0,null);}catch(RuntimeException|Error failure){flattened.recycle();throw failure;}bitmap.recycle();bitmap=flattened;}
            int[]qualities={90,80,65,50,35};
            for(int edge:new int[]{2048,1024,512}){
                if(bitmap.getWidth()>edge||bitmap.getHeight()>edge){double ratio=Math.min(edge/(double)bitmap.getWidth(),edge/(double)bitmap.getHeight());
                    Bitmap smaller=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)Math.floor(bitmap.getWidth()*ratio)),Math.max(1,(int)Math.floor(bitmap.getHeight()*ratio)),true);
                    if(smaller!=bitmap){bitmap.recycle();bitmap=smaller;}resized=true;
                }
                checkBitmap(bitmap);
                for(int quality:qualities){
                    File partial=partial();BoundedFileOutput output=null;
                    try{
                        output=new BoundedFileOutput(partial,budget);boolean encoded=encoder.encode(bitmap,quality,output);
                        if(output.limitExceeded)continue;
                        if(output.failure!=null)throw fail("cache_io","Private JPEG delivery write failed",output.failure);
                        if(!encoded)throw fail("corrupt_image","Image JPEG encoding failed",null);
                        output.finish();long bytes=output.count;String hash=output.hash();
                        input.verifyUnchanged();File published=publish(partial,bytes,hash);
                        return new Representation(source,published,wireName(source,true),"image/jpeg",bytes,hash,true,resized,alpha,orientationChanged,sourceWidth,sourceHeight,bitmap.getWidth(),bitmap.getHeight(),quality,orientationStatus);
                    }finally{if(output!=null)output.close();deletePartial(partial);}
                }
            }
            throw fail("wire_budget","A sampled JPEG could not fit the available wire budget",null);
        }finally{if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
    }

    private Representation readPlan(File plan,JSONObject source,long budget)throws IOException{
        if(!plan.exists())return null;
        try{
            safeCacheFile(plan);if(plan.length()>128*1024)throw new IOException("Oversized delivery plan");
            JSONObject envelope=json(new String(Files.readAllBytes(plan.toPath()),StandardCharsets.UTF_8));
            Object rawPayload=envelope.opt("payload");String signature=envelope.optString("authentication");
            if(!(rawPayload instanceof String)||!VERSION.equals(envelope.optString("version"))||!signature.matches("[a-f0-9]{64}"))throw new IOException("Unauthenticated delivery plan");
            String payload=(String)rawPayload;
            if(!MessageDigest.isEqual(authenticate(payload).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)))throw new IOException("Delivery source-to-wire binding changed");
            JSONObject saved=json(payload);
            if(!VERSION.equals(saved.optString("version"))||!source.optString("id").equals(saved.optString("id"))||!source.optString("sha256").equals(saved.optString("sha256")))throw new IOException("Stale delivery plan");
            long bytes=saved.optLong("wireBytes",-1);String hash=saved.optString("providerRepresentationSha256");
            if(bytes<0||bytes>budget||!hash.matches("[a-f0-9]{64}"))throw new IOException("Invalid delivery plan wire identity");
            for(String flag:new String[]{"lossy","resized","alphaFlattened","orientationChanged"})if(!(saved.opt(flag) instanceof Boolean))throw new IOException("Invalid delivery plan transformation flag");
            boolean lossy=saved.optBoolean("lossy");
            if(!lossy&&(bytes!=source.optLong("size")||!hash.equals(source.optString("sha256"))||!saved.optString("wireMime").equals(source.optString("mime",source.optString("mimeType","application/octet-stream")))))throw new IOException("Exact delivery plan does not contain original bytes");
            if(lossy&&(!saved.optString("wireMime").equals("image/jpeg")||saved.optInt("sourceWidth")<1||saved.optInt("sourceHeight")<1
                    ||saved.optInt("sourceWidth")>32768||saved.optInt("sourceHeight")>32768||(long)saved.optInt("sourceWidth")*saved.optInt("sourceHeight")>MAX_SOURCE_PIXELS
                    ||saved.optInt("jpegQuality")<1||saved.optInt("jpegQuality")>100
                    ||saved.optInt("wireWidth")<1||saved.optInt("wireHeight")<1||saved.optInt("wireWidth")>2048||saved.optInt("wireHeight")>2048
                    ||(long)saved.optInt("wireWidth")*saved.optInt("wireHeight")>MAX_OUTPUT_PIXELS))throw new IOException("Invalid sampled JPEG plan");
            if(!saved.optString("wireName").equals(wireName(source,lossy)))throw new IOException("Delivery plan name differs from its canonical source");
            File body=new File(cache,"body-"+hash+".bin");safeCacheFile(body);try(FileInputStream input=new FileInputStream(body)){verify(input,bytes,hash);}
            return new Representation(source,body,saved.optString("wireName"),saved.optString("wireMime"),bytes,hash,saved.optBoolean("lossy"),saved.optBoolean("resized"),saved.optBoolean("alphaFlattened"),saved.optBoolean("orientationChanged"),saved.optInt("sourceWidth"),saved.optInt("sourceHeight"),saved.optInt("wireWidth"),saved.optInt("wireHeight"),saved.optInt("jpegQuality"),saved.optString("orientationStatus","unknown"));
        }catch(IOException|RuntimeException stale){if(!plan.delete()&&plan.exists())throw fail("cache_io","Stale delivery plan could not be removed",stale);return null;}
    }
    private void writePlan(File plan,Representation representation)throws IOException{
        File partial=partial();try{
            String payload=representation.metadata().toString();JSONObject envelope=new JSONObject();put(envelope,"version",VERSION);put(envelope,"payload",payload);put(envelope,"authentication",authenticate(payload));
            try(FileOutputStream output=new FileOutputStream(partial)){output.write(envelope.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
            Files.move(partial.toPath(),plan.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory(cache);
        }finally{deletePartial(partial);}
    }
    private File publish(File partial,long bytes,String hash)throws IOException{
        try(FileInputStream input=new FileInputStream(partial)){verify(input,bytes,hash);}
        File target=new File(cache,"body-"+hash+".bin");
        if(target.exists()){
            boolean correct=false;try{safeCacheFile(target);try(FileInputStream input=new FileInputStream(target)){verify(input,bytes,hash);correct=true;}}catch(IOException invalid){/* Only a disposable delivery body is replaced. */}
            if(correct){deletePartial(partial);return target;}
        }
        Files.move(partial.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory(cache);return target;
    }
    private File partial(){return new File(cache,".partial-"+UUID.randomUUID());}
    private void ensureCache()throws IOException{
        if(Files.isSymbolicLink(cache.toPath())||(!cache.mkdirs()&&!cache.isDirectory()))throw fail("cache_io","Private delivery cache is unavailable",null);
    }
    /** Independent private key authenticates the persisted original-to-wire binding.
     * It is disposable along with this cache; a missing key makes old plans regenerate.
     * This protects damaged/rebound manifests, not a compromised Android app sandbox. */
    private void ensureAuthenticationKey()throws IOException{
        if(authenticationKey!=null)return;
        if(Files.isSymbolicLink(authDirectory.toPath())||(!authDirectory.mkdirs()&&!authDirectory.isDirectory()))throw fail("cache_io","Delivery authentication directory is unavailable",null);
        File key=new File(authDirectory,"key-v1.bin");
        if(!key.exists()){
            byte[]generated=new byte[32];new SecureRandom().nextBytes(generated);File partial=new File(authDirectory,".partial-"+UUID.randomUUID());
            try{try(FileOutputStream output=new FileOutputStream(partial)){output.write(generated);output.getFD().sync();}
                Files.move(partial.toPath(),key.toPath(),StandardCopyOption.ATOMIC_MOVE);syncDirectory(authDirectory);
            }finally{deletePartial(partial);}
        }
        safeCacheFile(key);if(key.length()!=32)throw fail("cache_io","Delivery authentication key is invalid",null);
        authenticationKey=Files.readAllBytes(key.toPath());if(authenticationKey.length!=32)throw fail("cache_io","Delivery authentication key changed while reading",null);
    }
    private String authenticate(String payload)throws IOException{
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(authenticationKey,"HmacSHA256"));return hex(mac.doFinal((VERSION+"\n"+payload).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.GeneralSecurityException invalid){throw fail("cache_io","Delivery authentication is unavailable",invalid);}
    }
    private void prune(File pinned)throws IOException{
        File[] entries=cache.listFiles();if(entries==null)throw fail("cache_io","Delivery cache could not be listed",null);
        long total=0;List<File> candidates=new ArrayList<>();
        for(File entry:entries){safeCacheFile(entry);total+=entry.length();candidates.add(entry);}
        candidates.sort(Comparator.comparingLong(File::lastModified));
        int count=candidates.size();
        for(File entry:candidates){if(total<=MAX_CACHE_BYTES&&count<=MAX_CACHE_ENTRIES)break;if(entry.equals(pinned))continue;long bytes=entry.length();if(!entry.delete()&&entry.exists())throw fail("cache_io","Old delivery cache entry could not be removed",null);total-=bytes;count--;}
    }
    private static void safeCacheFile(File file)throws IOException{if(Files.isSymbolicLink(file.toPath())||!file.isFile())throw fail("cache_io","Delivery cache file is unavailable or unsafe",null);}
    private static void deletePartial(File file)throws IOException{if(file.exists()&&!file.delete())throw fail("cache_io","Temporary delivery bytes could not be removed",null);}
    private static void syncDirectory(File directory)throws IOException{
        try(FileChannel channel=FileChannel.open(directory.toPath(),StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
        catch(IOException|UnsupportedOperationException nioUnavailable){java.io.FileDescriptor descriptor=null;
            try{descriptor=android.system.Os.open(directory.getPath(),android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_NOFOLLOW,0);
                if(!android.system.OsConstants.S_ISDIR(android.system.Os.fstat(descriptor).st_mode))throw new IOException("Delivery cache fsync target is not a directory");android.system.Os.fsync(descriptor);
            }catch(android.system.ErrnoException unavailable){throw new IOException("Delivery cache directory fsync failed",unavailable);}
            finally{if(descriptor!=null)try{android.system.Os.close(descriptor);}catch(android.system.ErrnoException ignored){}}
        }
    }
    private static void validateSource(JSONObject source)throws RepresentationFailure{
        if(source==null)throw fail("invalid_source","Original attachment metadata is required",null);
        String id=source.optString("originalAssetId",source.optString("id","")),hash=source.optString("sha256");Object rawSize=source.opt("size");
        if(!(rawSize instanceof Number)||!Double.isFinite(((Number)rawSize).doubleValue())||((Number)rawSize).doubleValue()!=(double)((Number)rawSize).longValue())throw fail("invalid_source","Original byte size must be an integer",null);
        long size=((Number)rawSize).longValue();
        if(!id.matches("\\.attachment-originals/[a-f0-9]{64}\\.[a-z0-9]{1,12}")||!hash.matches("[a-f0-9]{64}")||size<0)throw fail("invalid_source","Immutable source identity is required",null);
        if(!id.substring(".attachment-originals/".length(),".attachment-originals/".length()+64).equals(hash))throw fail("integrity_failed","Original ID and source SHA-256 disagree",null);
        if(size>InternalFiles.MAX_ATTACHMENT_BYTES)throw fail("source_too_large","Original attachment exceeds 64 MiB",null);
    }
    private static void checkBitmap(Bitmap bitmap)throws RepresentationFailure{if(bitmap.getWidth()<=0||bitmap.getHeight()<=0||bitmap.getWidth()>2048||bitmap.getHeight()>2048||(long)bitmap.getWidth()*bitmap.getHeight()>MAX_OUTPUT_PIXELS)throw fail("image_dimensions","Image decoder exceeded the sampled pixel budget",null);}
    private static String wireName(JSONObject source,boolean jpeg){
        String raw=source.optString("sourceName",source.optString("name","attachment"));String name=raw.replaceAll("[\\\\/\\p{Cntrl}]","_");
        String hash=source.optString("sha256").substring(0,12);int dot=name.lastIndexOf('.');String base=dot>0?name.substring(0,dot):name,extension=jpeg?".jpg":dot>0?name.substring(dot):"";
        if(base.length()>160){int end=160;if(Character.isHighSurrogate(base.charAt(end-1))&&Character.isLowSurrogate(base.charAt(end)))end--;base=base.substring(0,end);}if(extension.length()>16)extension="";
        return base+"_"+hash+(jpeg?"_work":"")+extension;
    }
    private static Matrix orientationMatrix(int orientation){Matrix matrix=new Matrix();switch(orientation){
        case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;
        case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;
        case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(270);matrix.postScale(-1,1);break;
        case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(270);break;default:break;}return matrix;}
    private static void copy(InputStream input,OutputStream output)throws IOException{byte[]buffer=new byte[16_384];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);}
    private static void verify(FileInputStream input,long size,String hash)throws IOException{
        if(size<0||size>MAX_WIRE_BYTES||input.getChannel().size()!=size)throw fail("integrity_failed","Delivery representation size changed",null);
        MessageDigest digest=digest();byte[]buffer=new byte[16_384];long bytes=0;int count;
        while((count=input.read(buffer))!=-1){bytes+=count;if(bytes>size)throw fail("integrity_failed","Delivery representation grew during verification",null);digest.update(buffer,0,count);}
        if(bytes!=size||!hex(digest.digest()).equals(hash))throw fail("integrity_failed","Delivery representation SHA-256 changed",null);
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String sha(byte[]bytes){return hex(digest().digest(bytes));}
    private static String hex(byte[]bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
    private static JSONObject json(String value){try{return new JSONObject(value);}catch(JSONException invalid){throw new IllegalStateException(invalid);}}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
    private static RepresentationFailure fail(String code,String message,Throwable cause){return new RepresentationFailure(code,message,cause);}
    private static final class VerifiedWireInput extends FilterInputStream {
        final long size;final String hash;final MessageDigest digest=digest();long count;boolean verified;
        VerifiedWireInput(InputStream input,long size,String hash){super(input);this.size=size;this.hash=hash;}
        private void done()throws IOException{if(!verified){verified=true;if(count!=size||!hex(digest.digest()).equals(hash))throw fail("integrity_failed","Consumed delivery bytes differ from their wire identity",null);}}
        @Override public int read()throws IOException{int value=in.read();if(value<0)done();else{digest.update((byte)value);if(++count>size)throw fail("integrity_failed","Wire delivery grew while reading",null);}return value;}
        @Override public int read(byte[]bytes,int offset,int length)throws IOException{int read=in.read(bytes,offset,length);if(read<0)done();else if(read>0){digest.update(bytes,offset,read);count+=read;if(count>size)throw fail("integrity_failed","Wire delivery grew while reading",null);}return read;}
        @Override public long skip(long bytes)throws IOException{if(bytes<=0)return 0;byte[]buffer=new byte[(int)Math.min(16384,bytes)];long consumed=0;while(consumed<bytes){int read=read(buffer,0,(int)Math.min(buffer.length,bytes-consumed));if(read<0)break;consumed+=read;}return consumed;}
    }
    private static final class BoundedFileOutput extends OutputStream {
        final FileOutputStream output;final long budget;final MessageDigest digest=digest();long count;boolean limitExceeded;IOException failure;
        BoundedFileOutput(File file,long budget)throws IOException{output=new FileOutputStream(file);this.budget=budget;}
        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
        @Override public void write(byte[]bytes,int offset,int length)throws IOException{
            // A bounded discard sink lets the native encoder finish without treating the
            // expected quality/size retry as an IO exception. No overflow byte reaches disk.
            if(limitExceeded)return;
            if(length>budget-count){limitExceeded=true;return;}
            try{output.write(bytes,offset,length);digest.update(bytes,offset,length);count+=length;}catch(IOException broken){failure=broken;throw broken;}
        }
        void finish()throws IOException{output.flush();output.getFD().sync();}
        String hash(){return hex(digest.digest());}
        @Override public void flush()throws IOException{output.flush();}
        @Override public void close()throws IOException{output.close();}
    }
}
