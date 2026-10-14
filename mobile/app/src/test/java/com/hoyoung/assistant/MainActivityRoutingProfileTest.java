package com.hoyoung.assistant;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.hoyoung.assistant.phone.PermissionTestGrants;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=HoyoungApplication.class,qualifiers="ko-rKR-w412dp-h915dp-xhdpi")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class MainActivityRoutingProfileTest {
    private HoyoungApplication app;private MainActivity activity;private ActivityController<MainActivity> controller;
    @Before public void setup()throws Exception{
        app=(HoyoungApplication)RuntimeEnvironment.getApplication();app.getStore().putSetting("autonomy.enabled","false");app.getStore().putSetting("memoryReviewEnabled","false");
        PermissionTestGrants.allowAll(app);controller=Robolectric.buildActivity(MainActivity.class).setup().visible();activity=controller.get();drain();
    }
    @After public void cleanup()throws Exception{controller.pause().stop().destroy();}
    @Test public void composerPickerKeepsMultipleMessagesAndTabsButHomeResetsOnlyUiSelection()throws Exception{
        TextView selector=(TextView)get("modelSelector");assertNotNull(selector);assertSame(selector.getParent(),((View)get("sendButton")).getParent());
        chooseXhigh();assertEquals("xhigh",app.getRuntime().foregroundRoutingState().optString("effort"));
        MainActivityUiTestSupport.navigate(activity,"설정");MainActivityUiTestSupport.navigate(activity,"대화");assertEquals("xhigh",app.getRuntime().foregroundRoutingState().optString("effort"));
        JSONObject request=app.getRuntime().receive("나중에도 이 선택 유지",new org.json.JSONArray());
        app.getRuntime().onWebEvent(new JSONObject().put("type","state").put("code","ready").put("state",new JSONObject().put("authenticated",true).put("workSelected",true).put("temporary",true).put("model","gpt-6.1-sol").put("effort","xhigh").put("speed","fast").put("busy",false).put("quota",false)));drain();
        controller.pause().stop();assertFalse(app.getRuntime().foregroundRoutingState().optBoolean("selected"));
        assertEquals("xhigh",new JSONObject(app.getStore().getSetting("ownerRouteOverride:"+request.optString("external_id"),"{}")).optString("effort"));
        controller.start().resume().visible();drain();assertTrue(((TextView)get("modelSelector")).getText().toString().contains("Medium"));
        assertEquals("xhigh",new JSONObject(app.getStore().getSetting("ownerRouteOverride:"+request.optString("external_id"),"{}")).optString("effort"));
    }
    @Test public void screenOffClearsSelectionAndDialogOpenedBeforeExitCannotRestoreIt()throws Exception{
        chooseXhigh();((TextView)get("modelSelector")).performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(app.getRuntime().foregroundRoutingState().optBoolean("selected"));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(app.getRuntime().foregroundRoutingState().optBoolean("selected"));
    }
    @Test public void uneditedPermanentFieldsNeverOverwriteLaterChatChangesAndEditedConflictsAreRejected()throws Exception{
        MainActivityUiTestSupport.navigate(activity,"설정");EditText goals=findHint(root(),"계속 맡길 일과 목표");goals.setText("화면에서 편집한 목표");
        app.getRuntime().updatePersonalSettings(new JSONObject().put("requiredEffort","high"),null);
        MainActivityUiTestSupport.text(root(),"설정 저장").performClick();assertEquals("high",app.getStore().getSetting("requiredEffort",""));assertEquals("화면에서 편집한 목표",app.getStore().getSetting("standingGoals",""));
        goals.setText("뒤늦은 화면 편집");app.getRuntime().updatePersonalSettings(new JSONObject().put("standingGoals","더 최근 채팅 설정"),null);
        MainActivityUiTestSupport.text(root(),"설정 저장").performClick();assertEquals("더 최근 채팅 설정",app.getStore().getSetting("standingGoals",""));
    }
    @Test public void nameChangesRefreshHeaderAndAvatarAndStaleNameFormCannotUndoChatRename()throws Exception{
        MainActivityUiTestSupport.navigate(activity,"설정");EditText name=(EditText)MainActivityUiTestSupport.description(root(),"비서 이름 편집");name.setText("하루");
        MainActivityUiTestSupport.text(root(),"이름 저장").performClick();drain();assertEquals("하루",app.getRuntime().assistantIdentity().optString("name"));
        MainActivityUiTestSupport.navigate(activity,"대화");assertEquals("하루",((TextView)get("screenTitle")).getText().toString());assertEquals("하루 프로필 사진",((View)get("headerAvatar")).getContentDescription());
        MainActivityUiTestSupport.navigate(activity,"설정");name=(EditText)MainActivityUiTestSupport.description(root(),"비서 이름 편집");name.setText("옛 편집");
        String revision=app.getRuntime().assistantIdentity().optString("revision");app.getRuntime().updateAssistantIdentity("모모",null,false,revision);drain();
        MainActivityUiTestSupport.text(root(),"기본 고양이 사진으로").performClick();MainActivityUiTestSupport.text(root(),"이름 저장").performClick();assertEquals("모모",app.getRuntime().assistantIdentity().optString("name"));
    }
    @Test public void rotationKeepsForegroundChoiceWithoutTreatingRecreationAsLeaving()throws Exception{
        chooseXhigh();String session=app.getRuntime().foregroundRoutingState().optString("sessionId");
        android.content.res.Configuration config=new android.content.res.Configuration(activity.getResources().getConfiguration());config.orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        controller.configurationChange(config);activity=controller.get();drain();
        assertEquals(session,app.getRuntime().foregroundRoutingState().optString("sessionId"));assertEquals("xhigh",app.getRuntime().foregroundRoutingState().optString("effort"));
    }
    private void chooseXhigh()throws Exception{
        ((TextView)get("modelSelector")).performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        Spinner effort=(Spinner)MainActivityUiTestSupport.description(dialog.getWindow().getDecorView(),"이번 화면 추론 강도");assertNotNull(effort);effort.setSelection(3);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();
    }
    private Object get(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private View root(){return activity.findViewById(android.R.id.content);}
    private void drain()throws Exception{for(int i=0;i<3;i++){((ExecutorService)get("display")).submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    private static EditText findHint(View view,String hint){if(view instanceof EditText edit&&hint.contentEquals(edit.getHint()))return edit;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){EditText found=findHint(group.getChildAt(i),hint);if(found!=null)return found;}return null;}
}
