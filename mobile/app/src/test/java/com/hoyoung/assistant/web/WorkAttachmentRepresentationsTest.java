package com.hoyoung.assistant.web;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import android.util.Base64;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Native sampled image delivery tests use synthetic private files, never accounts or user photos. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class WorkAttachmentRepresentationsTest {
    private static final long MIB=1024L*1024;
    private Context context;private InternalFiles files;private ExecutorService io;
    private WorkAttachmentRepresentations delivery;
    @Before public void setup()throws Exception{
        context=ApplicationProvider.getApplicationContext();clear(context.getFilesDir());clear(cache());clear(authDirectory());
        files=new InternalFiles(context);delivery=new WorkAttachmentRepresentations(context,files);io=Executors.newSingleThreadExecutor();
    }
    @After public void cleanup()throws Exception{io.shutdownNow();clear(context.getFilesDir());clear(cache());clear(authDirectory());}

    @Test public void elevenMiBActualPngBecomesBoundedJpegWithHonestOriginalAndWireIdentity()throws Exception{
        File fixture=fixture("11mib-blue.png");writeBluePng(fixture,1000,800,11*MIB);
        JSONObject source=saved(fixture,"11mib-blue.png").put("sourceMessageId",42);
        File original=original(source);String originalHash=sha256(original);long originalSize=original.length();
        assertTrue(originalSize>10*MIB);
        WorkAttachmentRepresentations.Representation result=worker(source,256*1024);
        assertTrue(result.lossy);assertEquals("image/jpeg",result.mime);assertTrue(result.size<=256*1024);
        assertNotEquals(originalHash,result.wireSha256);assertEquals(originalHash,result.originalSha256);
        byte[]wire=Base64.decode(result.payloadBase64(),Base64.DEFAULT);assertEquals(result.size,wire.length);assertEquals(result.wireSha256,sha256(wire));
        Bitmap decoded=BitmapFactory.decodeByteArray(wire,0,wire.length);
        try{assertNotNull(decoded);assertEquals(1000,decoded.getWidth());assertEquals(800,decoded.getHeight());assertBlue(decoded.getPixel(500,400));}
        finally{if(decoded!=null)decoded.recycle();}
        JSONObject plan=result.metadata();assertCanonical(plan,source,false);assertEquals(42,plan.getLong("sourceMessageId"));
        assertEquals("delivered_representation",plan.getString("analysisScope"));assertEquals("derived-sha256:"+result.wireSha256,plan.getString("representationAssetId"));
        assertEquals(result.size,plan.getLong("wireBytes"));assertEquals(result.wireSha256,plan.getString("providerRepresentationSha256"));
        assertEquals(originalHash,sha256(original));assertEquals(originalSize,original.length());assertEquals(originalHash,sha256(fixture));
    }

    @Test public void smallBinaryIsExactAndPreparedPlanNeverClaimsServerUploadOrInspection()throws Exception{
        byte[]bytes="{\"answer\":42,\"text\":\"원본\"}".getBytes(StandardCharsets.UTF_8);
        JSONObject source=saved(bytes,"source.json").put("sourceMessageId",9);
        WorkAttachmentRepresentations.Representation result=worker(source,bytes.length);
        assertFalse(result.lossy);assertEquals(source.getString("sha256"),result.wireSha256);assertArrayEquals(bytes,Base64.decode(result.payloadBase64(),Base64.DEFAULT));
        assertCanonical(result.metadata(),source,true);assertEquals("original_bytes",result.metadata().getString("analysisScope"));
        assertEquals(source.getString("id"),result.representationAssetId);
        assertTrue(cache().getCanonicalPath().startsWith(context.getNoBackupFilesDir().getCanonicalPath()+File.separator));
        assertFalse(cache().getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath()+File.separator));
        result.originalSource.put("sha256","mutated public view");assertEquals(source.getString("sha256"),result.metadata().getString("sha256"));
        assertEquals(source.getString("sha256"),sha256(original(source)));
    }

    @Test public void exactImageDoesNotDecodeOrChangeItsBytes()throws Exception{
        File fixture=fixture("exact.png");writeBluePng(fixture,128,96,0);JSONObject source=saved(fixture,"exact.png");
        WorkAttachmentRepresentations.Representation result=worker(source,source.getLong("size"));
        assertFalse(result.lossy);assertEquals(source.getString("sha256"),sha256(Base64.decode(result.payloadBase64(),Base64.DEFAULT)));
        assertCanonical(result.metadata(),source,true);assertFalse(result.orientationChanged);assertEquals("not_transformed",result.orientationStatus);
    }

    @Test public void true200MpPngUsesBoundedSampleRatherThanAllocatingOriginalPixels()throws Exception{
        File fixture=fixture("200mp.png");writeBluePng(fixture,16000,12500,0);JSONObject source=saved(fixture,"200mp.png");
        assertTrue(source.getLong("size")>64*1024);
        WorkAttachmentRepresentations.Representation result=worker(source,64*1024);
        assertEquals(16000,result.sourceWidth);assertEquals(12500,result.sourceHeight);assertTrue(result.resized);assertTrue(result.lossy);
        assertTrue(result.wireWidth<=2048&&result.wireHeight<=2048);assertTrue((long)result.wireWidth*result.wireHeight<=2_000_000L);
        byte[]wire=Base64.decode(result.payloadBase64(),Base64.DEFAULT);Bitmap image=BitmapFactory.decodeByteArray(wire,0,wire.length);
        try{assertNotNull(image);assertBlue(image.getPixel(image.getWidth()/2,image.getHeight()/2));}finally{if(image!=null)image.recycle();}
        assertEquals(source.getString("sha256"),sha256(original(source)));
    }

    @Test public void noisyPhotoAdaptsQualityOrDimensionsToActualWireBudget()throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(1024,768,Bitmap.Config.ARGB_8888);int[]row=new int[1024];Random random=new Random(931);
        File fixture=fixture("noise.png");
        try{for(int y=0;y<768;y++){for(int x=0;x<1024;x++)row[x]=0xff000000|random.nextInt(1<<24);bitmap.setPixels(row,0,1024,0,y,1024,1);}try(OutputStream output=new FileOutputStream(fixture)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}}
        finally{bitmap.recycle();}
        JSONObject source=saved(fixture,"noise.png");WorkAttachmentRepresentations.Representation result=worker(source,64*1024);
        assertTrue(result.size<=64*1024);assertTrue(result.jpegQuality<90||result.resized);assertTrue(result.lossy);
        assertEquals(result.wireSha256,sha256(Base64.decode(result.payloadBase64(),Base64.DEFAULT)));assertEquals(source.getString("sha256"),sha256(original(source)));
    }

    @Test public void jpegExifRotationIsAppliedOnlyToDerivativeAndOriginalSurvives()throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.BLUE);File fixture=fixture("rotate.jpg");
        try(OutputStream output=new FileOutputStream(fixture)){assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,output));}finally{bitmap.recycle();}
        ExifInterface exif=new ExifInterface(fixture.getPath());exif.setAttribute(ExifInterface.TAG_ORIENTATION,Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));exif.saveAttributes();
        try(OutputStream padding=new FileOutputStream(fixture,true)){padding.write(new byte[64*1024]);}
        JSONObject source=saved(fixture,"rotate.jpg");WorkAttachmentRepresentations.Representation result=worker(source,4096);
        assertTrue(result.lossy);assertTrue(result.orientationChanged);assertEquals("known",result.orientationStatus);
        assertEquals(80,result.sourceWidth);assertEquals(40,result.sourceHeight);assertEquals(40,result.wireWidth);assertEquals(80,result.wireHeight);
        assertEquals(source.getString("sha256"),sha256(original(source)));assertEquals(ExifInterface.ORIENTATION_ROTATE_90,new ExifInterface(original(source).getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,0));
    }

    @Test public void transparentPixelsAreFlattenedWithExplicitDerivativeFlag()throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.TRANSPARENT);File fixture=fixture("alpha.png");
        try(OutputStream output=new FileOutputStream(fixture)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}
        // Trailing padding is ignored by the native decoder while remaining part of immutable original bytes.
        try(OutputStream padding=new FileOutputStream(fixture,true)){padding.write(new byte[64*1024]);}
        JSONObject source=saved(fixture,"alpha.png");WorkAttachmentRepresentations.Representation result=worker(source,4096);assertTrue(result.alphaFlattened);assertTrue(result.lossy);
        byte[]wire=Base64.decode(result.payloadBase64(),Base64.DEFAULT);Bitmap decoded=BitmapFactory.decodeByteArray(wire,0,wire.length);
        try{assertNotNull(decoded);assertEquals(Color.WHITE,decoded.getPixel(30,20));}finally{if(decoded!=null)decoded.recycle();}
        assertEquals(source.getString("sha256"),sha256(original(source)));
    }

    @Test public void oversizeNonImageFailsWithoutConvertingOrDeletingOriginal()throws Exception{
        JSONObject source=saved(new byte[8192],"binary.dat");
        assertEquals("non_image_too_large",failure(source,4096).code);assertEquals(8192,original(source).length());assertEquals(source.getString("sha256"),sha256(original(source)));
    }

    @Test public void malformedLargeImageFailsExplicitlyAndPreservesEverySavedByte()throws Exception{
        JSONObject source=saved(new byte[8192],"malformed.png");assertEquals("corrupt_image",failure(source,4096).code);assertEquals(source.getString("sha256"),sha256(original(source)));
        assertNoPartials();
    }

    @Test public void exhaustedEncoderMemoryLeavesOriginalAndRemovesPartialWireBytes()throws Exception{
        File fixture=fixture("memory.png");writeBluePng(fixture,128,96,64*1024);JSONObject source=saved(fixture,"memory.png");
        WorkAttachmentRepresentations injected=new WorkAttachmentRepresentations(context,files,(bitmap,quality,output)->{throw new OutOfMemoryError("synthetic encoder pressure");});
        assertEquals("memory_unavailable",failure(injected,source,4096).code);assertEquals(source.getString("sha256"),sha256(original(source)));assertNoPartials();
        assertTrue(worker(source,4096).lossy); // The source remains usable after failure.
    }

    @Test public void impossibleWireBudgetDoesNotLeaveAStaleOrIncompletePlan()throws Exception{
        File fixture=fixture("tiny-budget.png");writeBluePng(fixture,128,96,64*1024);JSONObject source=saved(fixture,"tiny-budget.png");
        assertEquals("wire_budget",failure(source,1).code);assertEquals(source.getString("sha256"),sha256(original(source)));assertNoPartials();
        assertTrue(worker(source,4096).size<=4096);
    }

    @Test public void corruptedCacheIsRejectedThenRegeneratedFromStillVerifiedOriginal()throws Exception{
        JSONObject source=saved("durable original bytes".getBytes(StandardCharsets.UTF_8),"original.txt");WorkAttachmentRepresentations.Representation first=worker(source,4096);
        File body=wireBody(first);try(RandomAccessFile mutation=new RandomAccessFile(body,"rw")){mutation.seek(0);mutation.write(0);}
        assertEquals("integrity_failed",assertThrows(WorkAttachmentRepresentations.RepresentationFailure.class,first::payloadBase64).code);
        WorkAttachmentRepresentations.Representation fresh=worker(source,4096);assertEquals(source.getString("sha256"),sha256(Base64.decode(fresh.payloadBase64(),Base64.DEFAULT)));
        assertEquals(source.getString("sha256"),sha256(original(source)));
    }

    @Test public void alreadyOpenWireInodeSurvivesDisposableCacheEviction()throws Exception{
        byte[]bytes="pinned delivery bytes".getBytes(StandardCharsets.UTF_8);JSONObject source=saved(bytes,"pinned.txt");WorkAttachmentRepresentations.Representation result=worker(source,4096);
        try(InputStream input=result.openStream()){Files.delete(wireBody(result).toPath());assertArrayEquals(bytes,readAll(input));}
        assertEquals(source.getString("sha256"),sha256(original(source)));assertArrayEquals(bytes,Base64.decode(worker(source,4096).payloadBase64(),Base64.DEFAULT));
    }

    @Test public void sparseDisposableCacheBudgetPrunesOnlyDerivedFiles()throws Exception{
        JSONObject source=saved("original survives LRU".getBytes(StandardCharsets.UTF_8),"lru.txt");worker(source,4096);
        for(int index=0;index<3;index++){File sparse=new File(cache(),"synthetic-old-"+index+".bin");try(RandomAccessFile file=new RandomAccessFile(sparse,"rw")){file.setLength(100*MIB);}assertTrue(sparse.setLastModified(index+1));}
        WorkAttachmentRepresentations.Representation result=worker(source,4096);long bytes=0;for(File file:cache().listFiles())bytes+=file.length();
        assertTrue(bytes<=WorkAttachmentRepresentations.MAX_CACHE_BYTES);assertEquals(source.getString("sha256"),sha256(original(source)));assertEquals(result.wireSha256,sha256(Base64.decode(result.payloadBase64(),Base64.DEFAULT)));
    }

    @Test public void invalidBudgetIdentityAndCallingThreadFailBeforePreparingWire()throws Exception{
        JSONObject source=saved(new byte[]{1,2,3},"identity.bin");
        assertEquals("background_required",assertThrows(WorkAttachmentRepresentations.RepresentationFailure.class,()->delivery.prepare(source,4096)).code);
        assertEquals("wire_budget",failure(source,0).code);assertEquals("wire_budget",failure(source,WorkAttachmentRepresentations.MAX_WIRE_BYTES+1).code);
        JSONObject wrongHash=new JSONObject(source.toString()).put("sha256",repeat('0',64));assertEquals("integrity_failed",failure(wrongHash,4096).code);
        JSONObject fractional=new JSONObject(source.toString()).put("size",3.5);assertEquals("invalid_source",failure(fractional,4096).code);
        JSONObject tooLarge=new JSONObject(source.toString()).put("size",InternalFiles.MAX_ATTACHMENT_BYTES+1);assertEquals("source_too_large",failure(tooLarge,4096).code);
        assertEquals(source.getString("sha256"),sha256(original(source)));
    }

    @Test public void wireNameTruncationKeepsUnicodeScalarsAndCanonicalOriginalName()throws Exception{
        String name=repeat('a',159)+"😀.json";
        JSONObject working=files.writeFile("unicode.json",new ByteArrayInputStream(new byte[]{1,2,3}));working.put("sourceName",name);
        JSONObject source=files.freezeAttachments(new JSONArray().put(working)).getJSONObject(0);
        WorkAttachmentRepresentations.Representation result=worker(source,4096);
        for(int offset=0;offset<result.name.length();offset++){
            char value=result.name.charAt(offset);
            if(Character.isHighSurrogate(value)){assertTrue(offset+1<result.name.length());assertTrue(Character.isLowSurrogate(result.name.charAt(++offset)));}
            else assertFalse(Character.isLowSurrogate(value));
        }
        assertEquals(name,result.metadata().getString("sourceName"));assertEquals(source.getString("sha256"),sha256(original(source)));
    }

    @Test public void excessiveSourceDimensionIsRejectedBeforeFullPixelAllocation()throws Exception{
        File fixture=fixture("too-wide.png");writeBluePng(fixture,32769,1,8192);JSONObject source=saved(fixture,"too-wide.png");
        assertEquals("image_dimensions",failure(source,4096).code);assertEquals(source.getString("sha256"),sha256(original(source)));assertNoPartials();
    }

    @Test public void staleCachedTransformationCannotMasqueradeAsOriginalOrProviderProof()throws Exception{
        File fixture=fixture("cached-proof.png");writeBluePng(fixture,128,96,64*1024);JSONObject source=saved(fixture,"cached-proof.png");
        WorkAttachmentRepresentations.Representation initial=worker(source,4096);
        File plan=null;for(File entry:cache().listFiles())if(entry.getName().startsWith("plan-"))plan=entry;assertNotNull(plan);
        JSONObject envelope=new JSONObject(new String(Files.readAllBytes(plan.toPath()),StandardCharsets.UTF_8));JSONObject altered=new JSONObject(envelope.getString("payload"));
        altered.put("lossy",false).put("originalBytesFullyUploaded",true).put("originalContentFullyInspected",true).put("serverBytesIndependentlyVerified",true);
        envelope.put("payload",altered.toString());Files.write(plan.toPath(),envelope.toString().getBytes(StandardCharsets.UTF_8));
        WorkAttachmentRepresentations.Representation fresh=worker(source,4096);assertTrue(fresh.lossy);assertCanonical(fresh.metadata(),source,false);
        assertEquals(initial.wireSha256,fresh.wireSha256);assertEquals(source.getString("sha256"),sha256(original(source)));
    }

    @Test public void anotherValidJpegCannotBeReboundToThisOriginalByChangingCacheManifest()throws Exception{
        File blueFile=fixture("blue-binding.png");writeBluePng(blueFile,128,96,64*1024);JSONObject blueSource=saved(blueFile,"blue-binding.png");
        File redFile=fixture("red-binding.png");Bitmap red=Bitmap.createBitmap(128,96,Bitmap.Config.ARGB_8888);red.eraseColor(Color.RED);
        try(OutputStream output=new FileOutputStream(redFile)){assertTrue(red.compress(Bitmap.CompressFormat.PNG,100,output));}finally{red.recycle();}
        try(OutputStream padding=new FileOutputStream(redFile,true)){padding.write(new byte[64*1024]);}
        JSONObject redSource=saved(redFile,"red-binding.png");WorkAttachmentRepresentations.Representation blue=worker(blueSource,4096),other=worker(redSource,4096);
        assertEquals(blue.wireWidth,other.wireWidth);assertEquals(blue.wireHeight,other.wireHeight);assertNotEquals(blue.wireSha256,other.wireSha256);
        File bluePlan=null;
        for(File file:cache().listFiles())if(file.getName().startsWith("plan-")){
            JSONObject envelope=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));JSONObject payload=new JSONObject(envelope.getString("payload"));
            if(payload.getString("id").equals(blueSource.getString("id"))){payload.put("providerRepresentationSha256",other.wireSha256).put("wireSha256",other.wireSha256).put("wireBytes",other.size).put("wireSize",other.size);envelope.put("payload",payload.toString());Files.write(file.toPath(),envelope.toString().getBytes(StandardCharsets.UTF_8));bluePlan=file;break;}
        }
        assertNotNull(bluePlan);WorkAttachmentRepresentations.Representation regenerated=worker(blueSource,4096);
        assertEquals(blue.wireSha256,regenerated.wireSha256);assertNotEquals(other.wireSha256,regenerated.wireSha256);
        byte[]wire=Base64.decode(regenerated.payloadBase64(),Base64.DEFAULT);Bitmap decoded=BitmapFactory.decodeByteArray(wire,0,wire.length);
        try{assertNotNull(decoded);assertBlue(decoded.getPixel(64,48));}finally{if(decoded!=null)decoded.recycle();}
        assertEquals(blueSource.getString("sha256"),sha256(original(blueSource)));assertEquals(redSource.getString("sha256"),sha256(original(redSource)));
    }

    @Test public void skippedWireBytesAreStillHashedAndSourcePlanSurvivesProcessRecreation()throws Exception{
        byte[]bytes="verification covers skipped wire bytes".getBytes(StandardCharsets.UTF_8);JSONObject source=saved(bytes,"skip.txt");WorkAttachmentRepresentations.Representation first=worker(source,4096);
        try(InputStream input=first.openStream()){assertEquals(7,input.skip(7));assertArrayEquals(java.util.Arrays.copyOfRange(bytes,7,bytes.length),readAll(input));}
        WorkAttachmentRepresentations recreated=new WorkAttachmentRepresentations(context,files);
        WorkAttachmentRepresentations.Representation second=io.submit(()->recreated.prepare(source,4096)).get(90,TimeUnit.SECONDS);
        assertEquals(first.wireSha256,second.wireSha256);assertArrayEquals(bytes,Base64.decode(second.payloadBase64(),Base64.DEFAULT));assertCanonical(second.metadata(),source,true);
    }

    private WorkAttachmentRepresentations.Representation worker(JSONObject source,long budget)throws Exception{return io.submit(()->delivery.prepare(source,budget)).get(90,TimeUnit.SECONDS);}
    private WorkAttachmentRepresentations.RepresentationFailure failure(JSONObject source,long budget)throws Exception{return failure(delivery,source,budget);}
    private WorkAttachmentRepresentations.RepresentationFailure failure(WorkAttachmentRepresentations candidate,JSONObject source,long budget)throws Exception{
        ExecutionException failure=assertThrows(ExecutionException.class,()->io.submit(()->candidate.prepare(source,budget)).get(90,TimeUnit.SECONDS));
        assertTrue(failure.getCause().toString(),failure.getCause() instanceof WorkAttachmentRepresentations.RepresentationFailure);return (WorkAttachmentRepresentations.RepresentationFailure)failure.getCause();
    }
    private static void assertCanonical(JSONObject plan,JSONObject source,boolean included)throws Exception{
        assertEquals(source.getString("id"),plan.getString("id"));assertEquals(source.getString("sha256"),plan.getString("sha256"));assertEquals(source.getLong("size"),plan.getLong("size"));
        assertEquals(source.getString("sourceName"),plan.getString("sourceName"));assertEquals(source.getString("mime"),plan.getString("mime"));
        assertEquals(included,plan.getBoolean("originalBytesIncludedInWire"));
        for(String flag:new String[]{"originalBytesFullyUploaded","serverBytesIndependentlyVerified","originalContentFullyInspected"}){assertTrue(plan.get(flag) instanceof Boolean);assertFalse(plan.getBoolean(flag));}
        String name=plan.getString("wireName");assertTrue(name.length()<=240);assertFalse(name.contains("/"));assertFalse(name.contains("\\"));assertFalse(name.contains("\n"));
    }
    private JSONObject saved(byte[] bytes,String name)throws Exception{return files.freezeAttachments(new JSONArray().put(files.writeFile(name,new ByteArrayInputStream(bytes)))).getJSONObject(0);}
    private JSONObject saved(File fixture,String name)throws Exception{try(InputStream input=new FileInputStream(fixture)){return files.freezeAttachments(new JSONArray().put(files.writeFile(name,input))).getJSONObject(0);}}
    private File original(JSONObject metadata)throws Exception{return new File(context.getFilesDir(),"projects/"+metadata.getString("id"));}
    private File cache(){return new File(context.getNoBackupFilesDir(),"work-attachment-representations");}
    private File authDirectory(){return new File(context.getNoBackupFilesDir(),"work-attachment-representation-auth");}
    private File fixture(String name)throws IOException{File directory=new File(context.getFilesDir(),"representation-fixtures");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Fixture directory unavailable");return new File(directory,name);}
    private File wireBody(WorkAttachmentRepresentations.Representation representation){return new File(cache(),"body-"+representation.wireSha256+".bin");}
    private void assertNoPartials(){File[]children=cache().listFiles();if(children!=null)for(File child:children)assertFalse(child.getName(),child.getName().startsWith(".partial-"));}
    private static void assertBlue(int pixel){assertTrue(Color.blue(pixel)>240);assertTrue(Color.red(pixel)<15);assertTrue(Color.green(pixel)<15);}
    private static byte[]readAll(InputStream input)throws IOException{ByteArrayOutputStream output=new ByteArrayOutputStream();byte[]buffer=new byte[4096];int read;while((read=input.read(buffer))!=-1)output.write(buffer,0,read);return output.toByteArray();}
    private static String repeat(char character,int count){char[]value=new char[count];java.util.Arrays.fill(value,character);return new String(value);}
    /** RGB PNG, filter zero per scanline, fixed-size IDAT chunks, optional valid ancillary padding. */
    private static void writeBluePng(File destination, int width, int height, long paddingBytes) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(destination)))) {
            output.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            byte[] header = new byte[13];
            integer(header, 0, width); integer(header, 4, height);
            header[8] = 8; header[9] = 2; // 8-bit RGB, no alpha or interlace.
            chunk(output, "IHDR", header, header.length);
            byte[] scanline = new byte[1 + width * 3];
            for (int pixel = 0; pixel < width; pixel++) scanline[1 + pixel * 3 + 2] = (byte) 255;
            Deflater compressor = new Deflater(Deflater.BEST_SPEED);
            try (DeflaterOutputStream encoded = new DeflaterOutputStream(new IdatChunks(output), compressor, 16_384)) {
                for (int row = 0; row < height; row++) encoded.write(scanline);
            } finally { compressor.end(); }
            if (paddingBytes > 0) paddingChunk(output, paddingBytes);
            chunk(output, "IEND", new byte[0], 0);
        }
    }

    private static final class IdatChunks extends OutputStream {
        private final DataOutputStream output;
        private final byte[] buffer = new byte[64 * 1024];
        private int count;
        IdatChunks(DataOutputStream output) { this.output = output; }
        @Override public void write(int value) throws IOException {
            buffer[count++] = (byte) value; if (count == buffer.length) publish();
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
                int copied = Math.min(length, buffer.length - count);
                System.arraycopy(bytes, offset, buffer, count, copied);
                offset += copied; length -= copied; count += copied;
                if (count == buffer.length) publish();
            }
        }
        private void publish() throws IOException {
            if (count != 0) { chunk(output, "IDAT", buffer, count); count = 0; }
        }
        @Override public void close() throws IOException { publish(); }
    }

    private static void paddingChunk(DataOutputStream output, long bytes) throws IOException {
        if (bytes > Integer.MAX_VALUE) throw new IOException("Synthetic PNG padding is too large");
        byte[] type = "hoYg".getBytes(StandardCharsets.US_ASCII); // Unknown ancillary/private/safe-to-copy chunk.
        output.writeInt((int) bytes); output.write(type);
        CRC32 crc = new CRC32(); crc.update(type);
        byte[] zeros = new byte[16_384];
        while (bytes != 0) {
            int count = (int) Math.min(bytes, zeros.length);
            output.write(zeros, 0, count); crc.update(zeros, 0, count); bytes -= count;
        }
        output.writeInt((int) crc.getValue());
    }

    private static void chunk(DataOutputStream output, String name, byte[] bytes, int count) throws IOException {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(count); output.write(type); output.write(bytes, 0, count);
        CRC32 crc = new CRC32(); crc.update(type); crc.update(bytes, 0, count);
        output.writeInt((int) crc.getValue());
    }

    private static void integer(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24); bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8); bytes[offset + 3] = (byte) value;
    }

    private static String sha256(File file)throws Exception{try(InputStream input=new FileInputStream(file)){MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[]buffer=new byte[16384];int read;while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);return hex(digest.digest());}}
    private static String sha256(byte[]bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[]bytes){StringBuilder value=new StringBuilder();for(byte item:bytes)value.append(String.format(Locale.ROOT,"%02x",item&255));return value.toString();}
    private static void clear(File file)throws IOException{if(Files.isSymbolicLink(file.toPath())){Files.deleteIfExists(file.toPath());return;}if(!file.exists())return;if(file.isDirectory()){File[]children=file.listFiles();if(children==null)throw new IOException("Cannot inspect synthetic fixtures");for(File child:children)clear(child);}Files.delete(file.toPath());}
}
