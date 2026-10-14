package com.hoyoung.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import com.hoyoung.assistant.phone.PermissionTestGrants;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.runtime.OwnerDraftStore;
import com.hoyoung.assistant.runtime.UiAutomationProvenance;
import com.hoyoung.assistant.store.AccountStore;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/** Small synthetic lifecycle/conflict regressions; no provider calls or long-running workload. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=HoyoungApplication.class,qualifiers="ko-rKR-w412dp-h915dp-xhdpi")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MainActivityFinalAuditTest {
    private HoyoungApplication app;
    private MainActivity activity;
    private ActivityController<MainActivity> controller;
    private OwnerDraftStore drafts;

    @Before public void setup()throws Exception{
        app=(HoyoungApplication)RuntimeEnvironment.getApplication();
        app.getStore().putSetting("autonomy.enabled","false");app.getStore().putSetting("memoryReviewEnabled","false");
        PermissionTestGrants.allowAll(app);
        app.getSharedPreferences("hoyoung_ui_actor_v1",0).edit().clear().commit();
        drafts=new OwnerDraftStore(app.getStore());
        controller=Robolectric.buildActivity(MainActivity.class).setup().visible();activity=controller.get();drain();
    }
    @After public void cleanup(){if(controller!=null)controller.pause().stop().destroy();}

    @Test public void rotationDuringImportKeepsOriginalAndAddsItToTheCurrentDurableDraft()throws Exception{
        composer().setText("before rotation");ExecutorService oldIo=executor("io");
        CountDownLatch release=block(oldIo);
        try{
            importFixture();
            Configuration configuration=new Configuration(activity.getResources().getConfiguration());
            configuration.orientation=Configuration.ORIENTATION_LANDSCAPE;
            controller.configurationChange(configuration);activity=controller.get();
            composer().setText("new text in recreated activity");
        }finally{release.countDown();}
        assertTrue(oldIo.awaitTermination(10,TimeUnit.SECONDS));
        invoke("refreshStatus");drain();
        OwnerDraftStore.Snapshot current=drafts.read();
        assertEquals("new text in recreated activity",current.text);assertEquals(1,current.attachments().length());
        assertEquals(current.attachmentsJson,((JSONArray)get("attachments")).toString());
        assertEquals(current.text,composer().getText().toString());
        assertNotNull(app.getFiles().describeStoredFile(current.attachments().getString(0)));
    }

    @Test public void typingBeforeImportCallbackPreservesTheAlreadyDurableAttachment()throws Exception{
        composer().setText("before");importFixture();
        executor("io").submit(()->{}).get(10,TimeUnit.SECONDS);
        // The import is committed but its main-thread callback has not run yet.
        composer().setText("typed before callback");
        assertEquals("typed before callback",drafts.read().text);assertEquals(1,drafts.read().attachments().length());
        drain();assertEquals(1,((JSONArray)get("attachments")).length());
    }

    @Test public void queuedImportCannotPublishIntoAReplacedStorageGeneration()throws Exception{
        composer().setText("original draft");ExecutorService worker=executor("io");CountDownLatch release=block(worker);
        try{importFixture();app.getStore().putSetting("internal.files.restoreToken","synthetic-restored-generation");}
        finally{release.countDown();}
        worker.submit(()->{}).get(10,TimeUnit.SECONDS);drain();
        assertEquals("original draft",drafts.read().text);assertEquals(0,drafts.read().attachments().length());
    }

    @Test public void concurrentImportLimitIsAtomicAndDoesNotReplaceLatestText()throws Exception{
        JSONArray existing=new JSONArray();for(int i=0;i<7;i++)existing.put("fixture-"+i+".txt");
        drafts.edit("latest text",existing);
        try{drafts.appendAttachments(new JSONArray().put("eighth.txt").put("ninth.txt"),UiAutomationProvenance.OWNER);fail("Expected attachment limit");}
        catch(IllegalArgumentException expected){}
        assertEquals("latest text",drafts.read().text);assertEquals(existing.toString(),drafts.read().attachmentsJson);
    }

    @Test public void attachmentEditsPreserveUnseenImportsAndNeverResurrectConsumedSelections()throws Exception{
        OwnerDraftStore.Snapshot before=drafts.edit("first draft",new JSONArray().put("a.txt"));
        drafts.appendAttachments(new JSONArray().put("b.txt"),UiAutomationProvenance.OWNER);
        OwnerDraftStore.Snapshot removed=drafts.editFrom(before,before.text,new JSONArray(),UiAutomationProvenance.OWNER);
        assertEquals(new JSONArray().put("b.txt").toString(),removed.attachmentsJson);

        before=drafts.edit("submitted draft",new JSONArray().put("a.txt"));
        drafts.consume(before.clientInputId,before.revision);
        OwnerDraftStore.Snapshot next=drafts.editFrom(before,"next draft",new JSONArray().put("a.txt").put("c.txt"),UiAutomationProvenance.OWNER);
        assertEquals("next draft",next.text);assertEquals(new JSONArray().put("c.txt").toString(),next.attachmentsJson);
    }

    @Test public void executionLimitSaveMergesOnlyEditedFieldsAndRejectsSameFieldConflict()throws Exception{
        MainActivityUiTestSupport.navigate(activity,"권한");
        EditText rounds=(EditText)MainActivityUiTestSupport.description(root(),"작업당 도구 실행 턴 · 0은 제한 없음");
        rounds.setText("3");app.getRuntime().updatePersonalSettings(new JSONObject().put("toolActionsPerTurnLimit",5),null);
        MainActivityUiTestSupport.text(root(),"실행 한도 저장").performClick();
        assertEquals("3",app.getStore().getSetting("toolRoundLimit",""));assertEquals("5",app.getStore().getSetting("toolActionsPerTurnLimit",""));
        dismissNotice();rounds.setText("4");app.getRuntime().updatePersonalSettings(new JSONObject().put("toolRoundLimit",7),null);
        MainActivityUiTestSupport.text(root(),"실행 한도 저장").performClick();assertEquals("7",app.getStore().getSetting("toolRoundLimit",""));
    }

    @Test public void delayedFilesRefreshCannotReplaceSettingsPage()throws Exception{
        MainActivityUiTestSupport.navigate(activity,"파일");
        new Handler(Looper.getMainLooper()).post(()->{try{invoke("showFiles");}catch(Exception failed){throw new AssertionError(failed);}});
        MainActivityUiTestSupport.navigate(activity,"설정");drain();
        assertEquals("설정",get("tab"));assertNotNull(MainActivityUiTestSupport.description(root(),"사용자 지침 편집"));
        assertNull(MainActivityUiTestSupport.text(root(),"작업 파일"));
    }

    @Test public void queuedRuntimeCallbackAfterDestroyCannotSubmitToClosedUiWorkers()throws Exception{
        Runnable pending=(Runnable)get("runtimeRefreshListener");ExecutorService closedDisplay=executor("display");
        controller.pause().stop().destroy();controller=null;
        assertTrue(closedDisplay.isShutdown());pending.run();Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void staleAccountEditorCannotOverwriteANewerPasswordOrMfa()throws Exception{
        AccountStore accounts=new AccountStore(app,app.getStore());
        JSONObject original=accounts.saveFromUi(new JSONObject().put("service","fixture.invalid").put("username","fixture-owner")
                .put("password","synthetic-old-password").put("extra",new JSONObject().put("totp",new JSONObject().put("secret","JBSWY3DPEHPK3PXP"))));
        String id=original.getString("accountId");JSONObject opening=accounts.get(id);
        MainActivityUiTestSupport.navigate(activity,"계정");invoke("editAccount",new Class<?>[]{JSONObject.class},opening);
        // The onShow callback installs the actual save listener on Android's main queue.
        drain();
        AlertDialog editor=ShadowAlertDialog.getLatestAlertDialog();
        ((EditText)MainActivityUiTestSupport.description(editor.getWindow().getDecorView(),"메모")).setText("a stale note edit");
        JSONObject corrected=new JSONObject(opening.toString()).put("password","synthetic-new-password")
                .put("extra",new JSONObject().put("totp",new JSONObject().put("secret","GEZDGNBVGY3TQOJQ")));
        accounts.saveFromUi(corrected);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();
        JSONObject current=accounts.get(id);assertEquals("synthetic-new-password",current.getString("password"));
        assertEquals("GEZDGNBVGY3TQOJQ",current.getJSONObject("extra").getJSONObject("totp").getString("secret"));
        assertNotEquals("a stale note edit",current.optString("notes"));assertTrue(editor.isShowing());
        AlertDialog conflict=ShadowAlertDialog.getLatestAlertDialog();assertNotSame(editor,conflict);
        assertNotNull(MainActivityUiTestSupport.text(conflict.getWindow().getDecorView(),"다른 화면이나 대화에서 계정이 바뀌었어. 최신 계정을 다시 열고 수정해 줘. 이전 값으로 덮어쓰지 않았어."));
    }

    private void importFixture()throws Exception{
        File fixture=File.createTempFile("audit-attachment-",".txt",app.getCacheDir());
        Files.write(fixture.toPath(),"synthetic attachment bytes".getBytes(StandardCharsets.UTF_8));
        activity.onActivityResult(201,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(fixture)));
    }
    private CountDownLatch block(ExecutorService worker)throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        worker.submit(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));return release;
    }
    private EditText composer(){return (EditText)MainActivityUiTestSupport.description(root(),"메시지 입력");}
    private View root(){return activity.findViewById(android.R.id.content);}
    private Object get(String name)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}
    private ExecutorService executor(String name)throws Exception{return (ExecutorService)get(name);}
    private void invoke(String name)throws Exception{invoke(name,new Class<?>[0]);}
    private void invoke(String name,Class<?>[] types,Object...arguments)throws Exception{Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(activity,arguments);}
    private void drain()throws Exception{for(int i=0;i<3;i++){executor("io").submit(()->{}).get(10,TimeUnit.SECONDS);executor("display").submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    private void dismissNotice(){AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();if(dialog!=null)dialog.dismiss();}
}
