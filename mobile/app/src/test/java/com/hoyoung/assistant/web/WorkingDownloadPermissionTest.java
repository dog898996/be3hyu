package com.hoyoung.assistant.web;

import android.app.Application;
import android.content.Context;
import android.webkit.CookieManager;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Synthetic HTTP connections exercise real redirects/cookie headers/atomic writes; no network or real login. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public final class WorkingDownloadPermissionTest {
    private Context context;
    private InternalFiles files;
    @Before public void before() throws Exception {
        context=RuntimeEnvironment.getApplication();deleteTree(new File(context.getFilesDir(),"projects"));
        files=new InternalFiles(context);CookieManager.getInstance().removeAllCookies(null);
    }
    @After public void after() throws Exception {
        CookieManager.getInstance().removeAllCookies(null);deleteTree(new File(context.getFilesDir(),"projects"));
    }

    @Test public void crossHostRedirectUsesOnlyDestinationCookieJarAndSavesExactFile() throws Exception {
        String initial="https://chatgpt.com/download",target="https://other.example:8443/export.txt";
        CookieManager cookies=CookieManager.getInstance();cookies.setCookie(initial,"synthetic_chat=first; Path=/");
        cookies.setCookie(target,"synthetic_other=second; Path=/");
        FakeConnection first=redirect(initial,target),second=file(target,"안녕 🌙");
        List<String> opened=new ArrayList<>(),lookedUp=new ArrayList<>();
        JSONObject saved=WorkingDownload.save(initial,null,"text/plain",files,"synthetic-browser",
                url->{opened.add(url.toString());return opened.size()==1?first:second;},
                url->{lookedUp.add(url);return cookies.getCookie(url);});
        assertEquals(java.util.Arrays.asList(initial,target),opened);assertEquals(opened,lookedUp);
        assertTrue(first.getRequestProperty("Cookie").contains("synthetic_chat=first"));
        assertFalse(first.getRequestProperty("Cookie").contains("synthetic_other"));
        assertTrue(second.getRequestProperty("Cookie").contains("synthetic_other=second"));
        assertFalse(second.getRequestProperty("Cookie").contains("synthetic_chat"));
        assertEquals("synthetic-browser",second.getRequestProperty("User-Agent"));
        assertEquals("안녕 🌙",files.readText(saved.getString("id"),1000));assertTrue(first.disconnected);assertTrue(second.disconnected);
    }

    @Test public void redirectToUnrelatedHostWithoutCookiesDoesNotCarryPriorAuthentication() throws Exception {
        String initial="https://chatgpt.com/download",target="http://plain.example:8080/export.txt";
        FakeConnection first=redirect(initial,target),second=file(target,"ordinary http file");
        List<String> opened=new ArrayList<>();
        JSONObject saved=WorkingDownload.save(initial,null,null,files,null,
                url->{opened.add(url.toString());return opened.size()==1?first:second;},
                url->initial.equals(url)?"synthetic_chat=one":null);
        assertEquals("synthetic_chat=one",first.getRequestProperty("Cookie"));assertNull(second.getRequestProperty("Cookie"));
        assertEquals("ordinary http file",files.readText(saved.getString("id"),1000));
        // A fake connection proves protocol support; actual Android cleartext policy is independent.
        assertEquals(target,opened.get(1));
    }

    @Test public void relativeRedirectRechecksPathScopedCookiesRatherThanReusingHeader() throws Exception {
        String initial="https://example.test/one/file",target="https://example.test/two/file";
        FakeConnection first=redirect(initial,"/two/file"),second=file(target,"path result");
        List<String> opened=new ArrayList<>();
        WorkingDownload.save(initial,null,null,files,null,url->{opened.add(url.toString());return opened.size()==1?first:second;},
                url->initial.equals(url)?"path_one=first":"path_two=second");
        assertEquals("path_one=first",first.getRequestProperty("Cookie"));assertEquals("path_two=second",second.getRequestProperty("Cookie"));
        assertEquals(target,opened.get(1));
    }

    @Test public void unsupportedRedirectNeverOpensAFileOrPrivilegedUrl() throws Exception {
        String initial="https://example.test/file";FakeConnection first=redirect(initial,"file:///tmp/private");
        List<String> opened=new ArrayList<>();
        assertThrows(IllegalArgumentException.class,()->WorkingDownload.save(initial,null,null,files,null,
                url->{opened.add(url.toString());return first;},url->null));
        assertEquals(1,opened.size());assertTrue(first.disconnected);assertNoSavedDownloads();
    }

    @Test public void shortResponseAndLoginHtmlDoNotPublishCompletedFiles() throws Exception {
        String url="https://example.test/file";FakeConnection shortFile=file(url,"short");shortFile.length=100;
        assertThrows(IOException.class,()->WorkingDownload.save(url,null,null,files,null,address->shortFile,address->null));
        assertTrue(shortFile.disconnected);assertNoSavedDownloads();
        FakeConnection html=file(url,"<html>login required</html>");html.mime="text/html";html.disposition=null;
        assertThrows(IOException.class,()->WorkingDownload.save(url,null,null,files,null,address->html,address->null));
        assertTrue(html.disconnected);assertNoSavedDownloads();
    }

    private void assertNoSavedDownloads(){File folder=new File(context.getFilesDir(),"projects/downloads");assertTrue(!folder.exists()||folder.listFiles().length==0);}
    private static FakeConnection redirect(String url,String destination)throws Exception{FakeConnection result=new FakeConnection(url);result.status=302;result.location=destination;return result;}
    private static FakeConnection file(String url,String body)throws Exception{FakeConnection result=new FakeConnection(url);result.bytes=body.getBytes(StandardCharsets.UTF_8);result.length=result.bytes.length;result.disposition="attachment; filename=export.txt";return result;}
    private static void deleteTree(File root)throws Exception{if(root.isDirectory()){File[] children=root.listFiles();if(children!=null)for(File child:children)deleteTree(child);}if(root.exists()&&!root.delete())throw new IOException("Synthetic fixture cleanup failed");}
    private static final class FakeConnection extends HttpURLConnection {
        int status=200;String location,mime="text/plain",disposition;byte[] bytes=new byte[0];long length;boolean disconnected;
        FakeConnection(String address)throws Exception{super(new URL(address));}
        @Override public int getResponseCode(){return status;}
        @Override public String getHeaderField(String name){return "Location".equalsIgnoreCase(name)?location:"Content-Disposition".equalsIgnoreCase(name)?disposition:null;}
        @Override public String getContentType(){return mime;}
        @Override public long getContentLengthLong(){return length;}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        @Override public void connect(){connected=true;}
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return false;}
    }
}
