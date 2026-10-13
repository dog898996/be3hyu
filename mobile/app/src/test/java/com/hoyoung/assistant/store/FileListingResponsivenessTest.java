package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Actual private files; no account, network, or external device data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class FileListingResponsivenessTest {
    private Context context; private InternalFiles files; private File projects;
    @Before public void before() throws Exception { context=RuntimeEnvironment.getApplication(); projects=new File(context.getFilesDir(),"projects"); delete(projects); files=new InternalFiles(context); }
    @After public void after() throws Exception { delete(projects); }
    @Test public void hundredsOfFilesStayBoundedAndAnOldFilenameIsSearchable() throws Exception {
        for(int i=0;i<350;i++){File f=new File(projects,String.format(java.util.Locale.ROOT,"history-%04d.txt",i));Files.write(f.toPath(),("private body "+i).getBytes(java.nio.charset.StandardCharsets.UTF_8));assertTrue(f.setLastModified(1_000_000L+i));}
        JSONObject page=files.listFilesPage(100,"");assertEquals(350,page.getLong("matchedCount"));assertEquals(100,page.getJSONArray("files").length());assertTrue(page.getBoolean("truncated"));assertFalse(page.getBoolean("fileContentsRead"));
        JSONObject first=page.getJSONArray("files").getJSONObject(0);assertEquals("history-0349.txt",first.getString("id"));assertFalse(first.has("path"));assertFalse(first.getBoolean("contentBytesVerifiedByListing"));
        JSONObject old=files.listFilesPage(100,"history-0001");assertEquals(1,old.getJSONArray("files").length());assertEquals("history-0001.txt",old.getJSONArray("files").getJSONObject(0).getString("id"));
    }
    @Test public void listingDoesNotWaitForTheAttachmentMonitor() throws Exception {
        files.writeFile("visible.txt","synthetic content");FutureTask<JSONObject> query=new FutureTask<>(()->files.listFilesPage(10,""));Thread worker=new Thread(query,"listing-independent-monitor");
        synchronized(files){worker.start();assertEquals(1,query.get(5,TimeUnit.SECONDS).getJSONArray("files").length());}worker.join(1000);assertFalse(worker.isAlive());
    }
    @Test public void unrelatedBrokenOriginalAndSymlinkCannotInvalidateDirectAttachmentIdentity() throws Exception {
        JSONObject working=files.writeFile("chosen.txt","stable original");JSONArray frozen=files.freezeAttachments(new JSONArray().put(working.getString("id")));JSONObject original=frozen.getJSONObject(0);
        File broken=new File(projects,".attachment-originals/"+"a".repeat(64)+".png");Files.write(broken.toPath(),new byte[]{1,2,3});Files.write(new File(broken.getPath()+".metadata.json").toPath(),new byte[]{123});File link=new File(projects,"outside-link");Files.createSymbolicLink(link.toPath(),new File(context.getCacheDir(),"absent-outside").toPath());
        JSONObject direct=files.describeStoredFile(original.getString("id"));assertEquals(original.getString("sha256"),direct.getString("sha256"));assertEquals(original.getLong("size"),direct.getLong("size"));
        JSONObject page=files.listFilesPage(10,"chosen");assertTrue(page.getLong("skippedUnsupportedOrUnavailableEntries")>=2);boolean found=false;for(int i=0;i<page.getJSONArray("files").length();i++)found|=original.getString("id").equals(page.getJSONArray("files").getJSONObject(i).getString("id"));assertTrue(found);
    }
    private static void delete(File file)throws Exception{if(!file.exists()&&!Files.isSymbolicLink(file.toPath()))return;if(file.isDirectory()&&!Files.isSymbolicLink(file.toPath())){File[]children=file.listFiles();if(children!=null)for(File child:children)delete(child);}Files.delete(file.toPath());}
}
