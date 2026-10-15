package com.hoyoung.assistant;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import com.hoyoung.assistant.phone.PermissionTestGrants;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowAlertDialog;

/** JVM regression for the Activity export path; synthetic files only, not Android device evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=HoyoungApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class ExportCorruptOriginalReviewTest {
    @Test public void validOriginalExportsExactBytesAndClosesDestination() throws Exception {
        HoyoungApplication app=(HoyoungApplication)RuntimeEnvironment.getApplication();
        String content="정상 내보내기\nexact original bytes 🙂";
        JSONObject source=app.getFiles().writeFile("export-review/valid.txt",content);
        String id=app.getFiles().freezeAttachments(new JSONArray().put(source)).getJSONObject(0).getString("id");
        boolean[] closed={false};
        ByteArrayOutputStream destination=new ByteArrayOutputStream(){
            @Override public void close(){closed[0]=true;}
        };
        app.getFiles().exportFileToDestination(id,()->destination);
        assertEquals(content,destination.toString(StandardCharsets.UTF_8.name()));
        assertTrue("The destination opened by the factory must be closed",closed[0]);
    }

    @Test public void corruptOriginalMustNotTruncateExistingExportDestination() throws Exception {
        HoyoungApplication app=(HoyoungApplication)RuntimeEnvironment.getApplication();
        app.getStore().putSetting("autonomy.enabled","false");
        app.getStore().putSetting("memoryReviewEnabled","false");
        PermissionTestGrants.allowAll(app);
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible();
        MainActivity activity=controller.get();
        try {
            JSONObject source=app.getFiles().writeFile("export-review/original.txt","synthetic original bytes");
            JSONObject original=app.getFiles().freezeAttachments(new JSONArray().put(source)).getJSONObject(0);
            String id=original.getString("id");
            File payload=new File(app.getFilesDir(),"projects/"+id);
            byte[] corrupted=Files.readAllBytes(payload.toPath()); corrupted[0]^=1;
            Files.write(payload.toPath(),corrupted);
            JSONObject described=app.getFiles().describeStoredFile(id);
            System.out.println("describeBeforeDestinationOpen="+described.optString("integrity"));
            File destination=new File(app.getCacheDir(),"synthetic-existing-export.txt");
            String prior="existing destination bytes must survive";
            Files.write(destination.toPath(),prior.getBytes(StandardCharsets.UTF_8));
            Field pending=MainActivity.class.getDeclaredField("exportFileId");pending.setAccessible(true);pending.set(activity,id);
            activity.onActivityResult(205,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(destination)));
            for(int repeat=0;repeat<2;repeat++){
                for(String name:new String[]{"io","display"}){
                    Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);
                    ((ExecutorService)field.get(activity)).submit(()->{}).get(10,TimeUnit.SECONDS);
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            }
            String after=new String(Files.readAllBytes(destination.toPath()),StandardCharsets.UTF_8);
            AlertDialog notice=ShadowAlertDialog.getLatestAlertDialog();
            System.out.println("destinationBeforeBytes="+prior.length()+";destinationAfterBytes="+destination.length());
            System.out.println("notice="+(notice==null?"none":Shadows.shadowOf(notice).getMessage()));
            assertEquals("A SHA-256-invalid original must be rejected before truncating the chosen destination",prior,after);
        } finally {
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();if(dialog!=null&&dialog.isShowing())dialog.dismiss();
            controller.pause().stop().destroy();
        }
    }
}
