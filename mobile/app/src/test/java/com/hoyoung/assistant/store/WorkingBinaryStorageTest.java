package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class WorkingBinaryStorageTest {
    private Context context;private HoyoungStore store;private InternalFiles files;private File sparse;
    @Before public void setup()throws Exception{
        context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());assertTrue(context.getFilesDir().mkdirs()||context.getFilesDir().isDirectory());
        store=new HoyoungStore(context);files=new InternalFiles(context);sparse=new File(context.getCacheDir(),"synthetic-sparse-release.apk");
    }
    @After public void cleanup()throws Exception{if(store!=null)store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());if(sparse!=null)Files.deleteIfExists(sparse.toPath());}

    @Test public void binaryAbove64MiBStreamsAndBacksUpExactlyWhileOwnerAttachmentCapStays64MiB()throws Exception{
        long size=64L*1024*1024+17;byte[] tail="APK-EXACT-TAIL".getBytes(StandardCharsets.UTF_8);
        try(RandomAccessFile file=new RandomAccessFile(sparse,"rw")){file.setLength(size);file.seek(size-tail.length);file.write(tail);}
        assertEquals(512L*1024*1024,InternalFiles.MAX_FILE_BYTES);assertEquals(64L*1024*1024,InternalFiles.MAX_ATTACHMENT_BYTES);
        String expected=hash(sparse);JSONObject saved;
        try(InputStream input=new FileInputStream(sparse)){saved=files.writeFile("build/release.apk",input);}
        assertEquals(size,saved.getLong("size"));
        MessageDigest exported=OriginalTextFiles.digest();final long[] count={0};
        OutputStream sink=new OutputStream(){@Override public void write(int value){exported.update((byte)value);count[0]++;}@Override public void write(byte[]bytes,int offset,int length){exported.update(bytes,offset,length);count[0]+=length;}};
        files.exportFile("build/release.apk",sink);assertEquals(size,count[0]);assertEquals(expected,OriginalTextFiles.hex(exported.digest()));
        BackupPolicy policy=new BackupPolicy(128L*1024*1024,0,directory->1L<<40);
        ByteArrayOutputStream archive=new ByteArrayOutputStream();files.exportBackup(archive,store,policy);
        assertTrue("Only a small compressed fixture should live in heap",archive.size()<256*1024);
        files.writeFile("build/release.apk","after backup");files.importBackup(new ByteArrayInputStream(archive.toByteArray()),store,policy);
        File restored=new File(context.getFilesDir(),"projects/build/release.apk");assertEquals(size,restored.length());assertEquals(expected,hash(restored));
        try(InputStream input=new FileInputStream(restored)){
            IOException tooLarge=assertThrows(IOException.class,()->new AttachmentOriginals(new File(context.getFilesDir(),"projects")).save(input,new JSONObject().put("name","release.apk")));
            assertTrue(tooLarge.getMessage().contains("64 MiB"));
        }
        assertEquals(expected,hash(restored));
        assertEquals(0,new File(context.getFilesDir(),"projects/.attachment-originals").listFiles().length);
    }

    @Test public void stringWriterEncodesInSmallWindowsWithSurrogateAtWindowBoundary()throws Exception{
        String body=repeat('x',8191)+"😀한글\u0000"+repeat('y',8200);
        files.writeFile("large-window.txt",body);
        assertEquals(body,files.readTextRange("large-window.txt",0,body.length()+1).getString("text"));
    }
    private static String hash(File file)throws IOException{MessageDigest digest=OriginalTextFiles.digest();try(InputStream input=new FileInputStream(file)){byte[]buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}return OriginalTextFiles.hex(digest.digest());}
    private static String repeat(char value,int count){char[]chars=new char[count];java.util.Arrays.fill(chars,value);return new String(chars);}
    private static void clear(File file)throws IOException{if(Files.isSymbolicLink(file.toPath())){Files.deleteIfExists(file.toPath());return;}if(!file.exists())return;if(file.isDirectory()){File[]children=file.listFiles();if(children==null)throw new IOException("Cannot enumerate synthetic tree");for(File child:children)clear(child);}Files.delete(file.toPath());}
}
