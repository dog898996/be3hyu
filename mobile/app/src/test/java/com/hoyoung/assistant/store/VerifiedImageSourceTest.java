package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class VerifiedImageSourceTest {
    private Context context;private InternalFiles files;
    @Before public void setup()throws Exception{context=ApplicationProvider.getApplicationContext();clear(context.getFilesDir());files=new InternalFiles(context);}
    @After public void cleanup()throws Exception{clear(context.getFilesDir());}

    @Test public void allPassesUsePinnedInodeRatherThanReopeningChangedPath()throws Exception{
        byte[] original={1,2,3,4};JSONObject metadata=saved(original);
        File path=new File(context.getFilesDir(),"projects/"+metadata.getString("id"));
        InternalFiles.VerifiedImageSource source=files.openVerifiedImage(metadata);
        try{
            assertTrue(source.descriptor().valid());assertEquals(original.length,source.size);assertEquals(1,source.stream().read());
            source.rewind();assertEquals(1,source.stream().read());
            File replacement=new File(path.getParentFile(),"synthetic-replacement.bin");Files.write(replacement.toPath(),new byte[]{9,8,7,6});
            Files.move(replacement.toPath(),path.toPath(),StandardCopyOption.REPLACE_EXISTING);
            source.verifyUnchanged();assertEquals(1,source.stream().read());
            assertEquals("integrity_failed",assertThrows(InternalFiles.ImageSourceFailure.class,()->files.openVerifiedImage(metadata)).code);
            assertArrayEquals(new byte[]{9,8,7,6},Files.readAllBytes(path.toPath()));
        }finally{source.close();}
        assertThrows(IOException.class,source::stream);assertThrows(IOException.class,source::rewind);
    }

    @Test public void recheckingSameOpenInodeDetectsEqualLengthMutationWithoutRepairingOrDeletingOriginal()throws Exception{
        JSONObject metadata=saved(new byte[]{1,2,3,4});File path=new File(context.getFilesDir(),"projects/"+metadata.getString("id"));
        try(InternalFiles.VerifiedImageSource source=files.openVerifiedImage(metadata)){
            long modified=path.lastModified();try(RandomAccessFile file=new RandomAccessFile(path,"rw")){file.seek(0);file.write(7);}assertTrue(path.setLastModified(modified));
            assertEquals("integrity_failed",assertThrows(InternalFiles.ImageSourceFailure.class,source::verifyUnchanged).code);
            assertArrayEquals(new byte[]{7,2,3,4},Files.readAllBytes(path.toPath()));assertEquals(4,path.length());
        }
    }

    @Test public void immutableImageIdentitySizeAndDeclaredMimeAreRequiredBeforeReturningHandle()throws Exception{
        JSONObject metadata=saved(new byte[]{1,2,3,4});
        JSONObject contradictory=new JSONObject(metadata.toString()).put("id","working.png");
        assertEquals("original_missing",assertThrows(InternalFiles.ImageSourceFailure.class,()->files.openVerifiedImage(contradictory)).code);
        JSONObject size=new JSONObject(metadata.toString()).put("size",5);
        assertEquals("integrity_failed",assertThrows(InternalFiles.ImageSourceFailure.class,()->files.openVerifiedImage(size)).code);
        JSONObject fractional=new JSONObject(metadata.toString()).put("size",4.5);
        assertEquals("invalid_identity",assertThrows(InternalFiles.ImageSourceFailure.class,()->files.openVerifiedImage(fractional)).code);
        JSONObject notImage=new JSONObject(metadata.toString()).put("mime","application/json");
        assertEquals("not_image",assertThrows(InternalFiles.ImageSourceFailure.class,()->files.openVerifiedImage(notImage)).code);
    }
    private JSONObject saved(byte[] bytes)throws Exception{return files.freezeAttachments(new JSONArray().put(files.writeFile("synthetic.png",new ByteArrayInputStream(bytes)))).getJSONObject(0);}
    private static void clear(File file)throws IOException{if(Files.isSymbolicLink(file.toPath())){Files.deleteIfExists(file.toPath());return;}if(!file.exists())return;if(file.isDirectory()){File[]children=file.listFiles();if(children==null)throw new IOException("Cannot enumerate synthetic storage");for(File child:children)clear(child);}Files.delete(file.toPath());}
}
