package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Looper;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import com.hoyoung.assistant.phone.PermissionTestGrants;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
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

/** JVM lifecycle regression evidence; separate from real Android exploration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=HoyoungApplication.class,qualifiers="ko-rKR-w412dp-h915dp-xhdpi")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SettingsRecreationExplorerTest {
    private HoyoungApplication app;
    private MainActivity activity;
    private ActivityController<MainActivity> controller;

    @Before public void setup() throws Exception {
        app=(HoyoungApplication)RuntimeEnvironment.getApplication();
        app.getStore().putSetting("autonomy.enabled","false");app.getStore().putSetting("memoryReviewEnabled","false");
        app.getStore().putSetting("customInstructions","original instructions");
        PermissionTestGrants.allowAll(app);
        controller=Robolectric.buildActivity(MainActivity.class).setup().visible();activity=controller.get();drain();
    }
    @After public void cleanup(){
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();if(dialog!=null&&dialog.isShowing())dialog.dismiss();
        if(controller!=null)controller.pause().stop().destroy();
    }

    @Test public void rotationRestoresDirtySettingsAndKeepsUneditedConcurrentValues() throws Exception {
        MainActivityUiTestSupport.navigate(activity,"설정");
        MainActivityUiTestSupport.description(root(),"사용자 지침 펼치기").performClick();
        field("사용자 지침 편집").setText("미저장 한글 지침\n두 번째 줄 🐈");
        app.getRuntime().updatePersonalSettings(new JSONObject().put("requiredEffort","high"),null);
        rotate();
        assertNotNull(MainActivityUiTestSupport.text(root(),"내 비서 설정"));
        assertNotNull("Expansion is part of the restored working form",MainActivityUiTestSupport.description(root(),"사용자 지침 접기"));
        assertEquals("미저장 한글 지침\n두 번째 줄 🐈",field("사용자 지침 편집").getText().toString());
        MainActivityUiTestSupport.text(root(),"설정 저장").performClick();
        assertEquals("미저장 한글 지침\n두 번째 줄 🐈",app.getStore().getSetting("customInstructions",""));
        assertEquals("high",app.getStore().getSetting("requiredEffort",""));
    }

    @Test public void rotationCannotTurnStaleSettingsIntoAFreshOverwrite() throws Exception {
        MainActivityUiTestSupport.navigate(activity,"설정");
        field("사용자 지침 편집").setText("stale unsaved edit");
        app.getRuntime().updatePersonalSettings(new JSONObject().put("customInstructions","later chat edit"),null);
        rotate();
        assertEquals("stale unsaved edit",field("사용자 지침 편집").getText().toString());
        MainActivityUiTestSupport.text(root(),"설정 저장").performClick();
        assertEquals("later chat edit",app.getStore().getSetting("customInstructions",""));
        assertTrue(Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage().toString().contains("다른 화면이나 대화에서 바뀌었어"));
    }

    @Test public void rotationKeepsOriginalIdentityRevisionForAnUncommittedName() throws Exception {
        MainActivityUiTestSupport.navigate(activity,"설정");
        field("비서 이름 편집").setText("회전 전에 편집한 이름");
        JSONObject identity=app.getRuntime().assistantIdentity();
        app.getRuntime().updateAssistantIdentity("더 최근 대화 이름",null,false,identity.optString("revision"));
        rotate();
        assertEquals("회전 전에 편집한 이름",field("비서 이름 편집").getText().toString());
        MainActivityUiTestSupport.text(root(),"이름 저장").performClick();
        assertEquals("더 최근 대화 이름",app.getRuntime().assistantIdentity().optString("name"));
    }

    @Test public void immediateWakePolicyIsReloadedRatherThanReplayedFromOldViewState() throws Exception {
        app.getStore().putSetting("wakeMode","continuous");
        MainActivityUiTestSupport.navigate(activity,"설정");
        app.getRuntime().updatePersonalSettings(new JSONObject().put("wakeMode","charging"),null);
        rotate();
        assertEquals("charging",app.getStore().getSetting("wakeMode",""));
        assertTrue(((android.widget.RadioButton)MainActivityUiTestSupport.text(root(),"충전 중 계속 실행")).isChecked());
    }

    @Test public void missingExportSourceNeverTruncatesTheSelectedDestination() throws Exception {
        File destination=File.createTempFile("existing-export-destination-",".txt",app.getCacheDir());
        try{
            Files.write(destination.toPath(),"existing destination bytes".getBytes(StandardCharsets.UTF_8));
            Field pending=MainActivity.class.getDeclaredField("exportFileId");pending.setAccessible(true);
            for(String invalid:new String[]{null,"missing-source.txt"}){
                pending.set(activity,invalid);
                activity.onActivityResult(205,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(destination)));
                drain();
                assertEquals("Source validation must precede opening the provider in truncate mode","existing destination bytes",new String(Files.readAllBytes(destination.toPath()),StandardCharsets.UTF_8));
                AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();if(dialog!=null)dialog.dismiss();
            }
        }finally{assertTrue(destination.delete());}
    }

    @Test public void missingPrivateSnapshotShowsRecoveryNoticeAndLeavesStoredSettingsExact() throws Exception {
        MainActivityUiTestSupport.navigate(activity,"설정");
        field("사용자 지침 편집").setText("unsaved instructions that cannot be recovered");
        Bundle state=saveAndDestroy();
        String token=state.getString("privateFormStateToken");assertNotNull(token);
        com.hoyoung.assistant.ui.PrivateFormState.delete(app,token);
        recreate(state);
        assertEquals("original instructions",app.getStore().getSetting("customInstructions",""));
        assertEquals("original instructions",field("사용자 지침 편집").getText().toString());
        assertTrue(Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage().toString().contains("이미 저장한 계정과 설정은 그대로야"));
    }

    @Test public void committedBackupGenerationCannotResurrectAnOldUnsavedForm() throws Exception {
        MainActivityUiTestSupport.navigate(activity,"설정");
        field("사용자 지침 편집").setText("old generation unsaved instructions");
        Bundle state=saveAndDestroy();
        // This is the existing marker committed by InternalFiles.importBackup's transaction.
        app.getStore().putSetting("internal.files.restoreToken","new-backup-generation");
        app.getStore().putSetting("customInstructions","instructions from the restored backup");
        recreate(state);
        assertEquals("instructions from the restored backup",field("사용자 지침 편집").getText().toString());
        assertEquals("instructions from the restored backup",app.getStore().getSetting("customInstructions",""));
    }

    private Bundle saveAndDestroy(){
        Bundle state=new Bundle();controller.pause().saveInstanceState(state).stop().destroy();controller=null;return state;
    }
    private void recreate(Bundle state) throws Exception {
        controller=Robolectric.buildActivity(MainActivity.class).create(state).start().restoreInstanceState(state).resume().visible();activity=controller.get();drain();
    }

    private void rotate() throws Exception {
        Configuration configuration=new Configuration(activity.getResources().getConfiguration());
        configuration.orientation=configuration.orientation==Configuration.ORIENTATION_LANDSCAPE?Configuration.ORIENTATION_PORTRAIT:Configuration.ORIENTATION_LANDSCAPE;
        controller.configurationChange(configuration);activity=controller.get();drain();
    }
    private EditText field(String description){return (EditText)MainActivityUiTestSupport.description(root(),description);}
    private View root(){return activity.findViewById(android.R.id.content);}
    private void drain() throws Exception {
        for(int repeat=0;repeat<2;repeat++){
            for(String name:new String[]{"io","display"}){
                Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);
                ((ExecutorService)field.get(activity)).submit(()->{}).get(10,TimeUnit.SECONDS);
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
}
