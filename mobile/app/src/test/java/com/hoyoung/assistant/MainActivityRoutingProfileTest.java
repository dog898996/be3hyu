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
    @Test public void onlyThreeModelsAndFastToggleAreAvailableInComposerAndPermanentSettings()throws Exception{
        ((TextView)get("modelSelector")).performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View window=dialog.getWindow().getDecorView();
        Spinner models=(Spinner)MainActivityUiTestSupport.description(window,"이번 화면 모델");
        assertEquals(3,models.getCount());assertEquals("6 Sol",models.getItemAtPosition(0));assertEquals("6.1 Sol",models.getItemAtPosition(1));assertEquals("Astra",models.getItemAtPosition(2));
        assertNull(findHint(window,"gpt-6.1-sol"));models.setSelection(2);
        Switch fast=(Switch)MainActivityUiTestSupport.description(window,"이번 화면 Fast");assertTrue(fast.isChecked());fast.setChecked(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();
        assertEquals("gpt-6-astra",app.getRuntime().foregroundRoutingState().optString("model"));assertEquals("standard",app.getRuntime().foregroundRoutingState().optString("speed"));
        assertTrue(((TextView)get("modelSelector")).getText().toString().contains("Standard"));
        MainActivityUiTestSupport.navigate(activity,"설정");
        models=(Spinner)MainActivityUiTestSupport.description(root(),"기본 모델");assertEquals(3,models.getCount());models.setSelection(0);
        fast=(Switch)MainActivityUiTestSupport.description(root(),"기본 Fast");assertTrue(fast.isEnabled());fast.setChecked(false);
        MainActivityUiTestSupport.text(root(),"설정 저장").performClick();
        assertEquals("true",app.getStore().getSetting("usageOptimizationEnabled","true"));assertEquals("standard",app.getStore().getSetting("requiredSpeed",""));assertEquals("gpt-6-sol",app.getStore().getSetting("requiredModel",""));
        assertEquals("gpt-6-astra",app.getRuntime().foregroundRoutingState().optString("model"));
    }
    @Test public void workTabExplainsAuthenticationBlockerAndDoesNotQueueVerificationBeforeReady()throws Exception{
        MainActivityUiTestSupport.navigate(activity,"Work");
        app.getRuntime().onWebEvent(new JSONObject().put("type","state").put("code","authentication_unverified").put("state",new JSONObject().put("workSelected",true).put("temporary",false)));
        Method refresh=MainActivity.class.getDeclaredMethod("refreshStatus");refresh.setAccessible(true);refresh.invoke(activity);
        TextView detail=(TextView)MainActivityUiTestSupport.description(root(),"Work 연결 상세");
        assertTrue(detail.getText().toString().contains("계정 표시 확인 필요"));
        detail.performClick();AlertDialog details=ShadowAlertDialog.getLatestAlertDialog();
        String detailText=Shadows.shadowOf(details).getMessage().toString();
        assertTrue(detailText.contains("authentication_unverified"));assertTrue(detailText.contains("웹 왼쪽 메뉴"));
        details.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        int count=app.getStore().listRequests(100).length();
        MainActivityUiTestSupport.text(root(),"연결").performClick();drain();
        assertEquals(count,app.getStore().listRequests(100).length());
    }

    @Test public void aReplacementWebViewIsMountedOnTheVisibleWorkTabWithoutRenavigation()throws Exception{
        MainActivityUiTestSupport.navigate(activity,"Work");
        android.webkit.WebView old=app.getWebController().getExistingView();
        android.webkit.WebView replacement=new android.webkit.WebView(activity);
        Field webField=app.getWebController().getClass().getDeclaredField("view");webField.setAccessible(true);webField.set(app.getWebController(),replacement);
        Method refresh=MainActivity.class.getDeclaredMethod("refreshStatus");refresh.setAccessible(true);refresh.invoke(activity);
        View host=MainActivityUiTestSupport.description(root(),"Work 웹 화면");assertSame(host,replacement.getParent());
        if(old!=null)assertNotSame(host,old.getParent());
        refresh.invoke(activity);assertSame(host,replacement.getParent());assertEquals(1,((ViewGroup)host).getChildCount());
    }
    @Test public void uneditedPermanentFieldsNeverOverwriteLaterChatChangesAndEditedConflictsAreRejected()throws Exception{
        app.getStore().putSetting("standingGoals","기존에 저장한 계속 맡길 일");
        MainActivityUiTestSupport.navigate(activity,"설정");
        assertNull(findHint(root(),"계속 맡길 일과 목표"));
        assertNull(MainActivityUiTestSupport.text(root(),"사용량 절약 · 하루 10% 목표"));
        assertNotNull(MainActivityUiTestSupport.text(root(),"백업 용량 한도 · GiB"));
        EditText instructions=(EditText)MainActivityUiTestSupport.description(root(),"사용자 지침 편집");
        instructions.setText("화면에서 편집한 지침");
        app.getRuntime().updatePersonalSettings(new JSONObject().put("requiredEffort","high"),null);
        MainActivityUiTestSupport.text(root(),"설정 저장").performClick();
        assertEquals("high",app.getStore().getSetting("requiredEffort",""));
        assertEquals("화면에서 편집한 지침",app.getStore().getSetting("customInstructions",""));
        assertEquals("기존에 저장한 계속 맡길 일",app.getStore().getSetting("standingGoals",""));
        assertEquals("true",app.getStore().getSetting("usageOptimizationEnabled","true"));
        assertEquals("10",app.getStore().getSetting("budgetTargetPercent","10"));
        instructions.setText("뒤늦은 화면 편집");app.getRuntime().updatePersonalSettings(new JSONObject().put("customInstructions","더 최근 채팅 설정"),null);
        MainActivityUiTestSupport.text(root(),"설정 저장").performClick();assertEquals("더 최근 채팅 설정",app.getStore().getSetting("customInstructions",""));
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
