package com.hoyoung.assistant;

import android.Manifest;
import android.app.Application;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import com.hoyoung.assistant.phone.PermissionTestGrants;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.EmojiReactionReply;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.ui.TypingIndicatorView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
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
import static org.junit.Assert.*;

/** Native widgets and the real durable message/provenance store; no provider response is claimed here. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=HoyoungApplication.class,qualifiers="ko-rKR-w412dp-h915dp-xhdpi")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MainActivityChatUiTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private HoyoungApplication app;
    private HoyoungStore store;

    @Before public void before() {
        app=(HoyoungApplication)RuntimeEnvironment.getApplication(); store=app.getStore();
        store.putSetting("autonomy.enabled","false");store.putSetting("memoryReviewEnabled","false");
    }
    @After public void after() throws Exception {
        set(app.getRuntime(),"active",null);
        if(controller!=null)controller.pause().stop().destroy();
    }

    @Test public void missingPermissionsBlockDrawerComposerAndSendUntilActualGrantsRechecked() throws Exception {
        start(false);
        assertNotNull(MainActivityUiTestSupport.text(root(),"시작할 준비"));
        assertFalse(MainActivityUiTestSupport.text(root(),"호영과 대화 시작").isEnabled());
        assertNull(MainActivityUiTestSupport.description(root(),"메시지 입력"));
        assertTrue(MainActivityUiTestSupport.description(root(),"메뉴 열기").performClick());
        assertNull(get(activity,"drawerOverlay"));
        invoke("send"); assertEquals(0,store.lastChatMessageId());
        PermissionTestGrants.allowAll(app);
        MainActivityUiTestSupport.text(root(),"권한 상태 다시 확인").performClick();
        assertNotNull(MainActivityUiTestSupport.description(root(),"메시지 입력"));
        assertFalse((Boolean)get(activity,"permissionBlocked"));
    }

    @Test public void revokingPermissionBeforeSendKeepsDraftAndDoesNotCreateMessage() throws Exception {
        start(true);
        EditText composer=(EditText)MainActivityUiTestSupport.description(root(),"메시지 입력");
        composer.setText("권한 확인 도중에도 이 초안은 보존돼야 해");
        Shadows.shadowOf((Application)app).denyPermissions(Manifest.permission.READ_CONTACTS);
        MainActivityUiTestSupport.description(root(),"메시지 보내기").performClick();
        assertTrue((Boolean)get(activity,"permissionBlocked"));assertEquals(0,store.lastChatMessageId());
        assertEquals("권한 확인 도중에도 이 초안은 보존돼야 해",store.getSetting("ui.draft",""));
        PermissionTestGrants.allowAll(app);
        MainActivityUiTestSupport.text(root(),"권한 상태 다시 확인").performClick();
        assertEquals("권한 확인 도중에도 이 초안은 보존돼야 해",((EditText)MainActivityUiTestSupport.description(root(),"메시지 입력")).getText().toString());
    }

    @Test public void sidebarHasAllSevenDestinationsAndPreservesDraft() throws Exception {
        start(true);
        EditText composer=(EditText)MainActivityUiTestSupport.description(root(),"메시지 입력");
        composer.setText("돌아와도 그대로 남아 있는 초안");
        assertNull("Old top-level settings button is gone",MainActivityUiTestSupport.text(root(),"설정"));
        for(String destination:new String[]{"Work","기억","계정","파일","권한","설정","대화"}) {
            MainActivityUiTestSupport.navigate(activity,destination);
            assertEquals(destination,get(activity,"tab"));assertNull(get(activity,"drawerOverlay"));
            if("설정".equals(destination))assertNotNull("The actual writable instructions retain their accessible identity",MainActivityUiTestSupport.description(root(),"사용자 지침 편집"));
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,((View)get(activity,"root")).getImportantForAccessibility());
        }
        assertEquals("돌아와도 그대로 남아 있는 초안",((EditText)MainActivityUiTestSupport.description(root(),"메시지 입력")).getText().toString());
    }

    @Test public void attachmentMenuPreservesMultiSelectForPhotosVideosAndEveryFileType() throws Exception {
        start(true);
        for(int choice=0;choice<2;choice++) {
            MainActivityUiTestSupport.description(root(),"사진 또는 파일 첨부").performClick();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
            dialog.getListView().performItemClick(null,choice,choice);
            Intent launched=Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,launched.getAction());assertEquals("*/*",launched.getType());
            assertTrue(launched.hasCategory(Intent.CATEGORY_OPENABLE));assertTrue(launched.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));
            if(choice==0)assertArrayEquals(new String[]{"image/*","video/*"},launched.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
            else assertNull(launched.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
            dialog.dismiss();
        }
    }

    @Test public void nativeReactionBecomesBadgeAndReadTrackingUsesAssistantReplyIdentity() throws Exception {
        start(true);JSONObject owner=store.addMessage("user","나 운동갔다올게 이제",null);
        JSONObject answer=addReaction(owner,"💪",true);JSONArray page=page();render(page,-1);
        View badge=MainActivityUiTestSupport.description(root(),"호영이 이 메시지에 💪 이모지로 응답함");assertNotNull(badge);
        Map<?,?> rows=(Map<?,?>)get(activity,"messageRows");assertTrue(rows.containsKey(owner.optLong("id")));assertFalse(rows.containsKey(answer.optLong("id")));
        JSONObject tracked=(JSONObject)((Map<?,?>)get(activity,"replyViews")).get(badge);
        assertEquals(answer.optLong("id"),tracked.optLong("id"));assertEquals(answer.optString("content_hash"),tracked.optString("content_hash"));
        assertTrue(store.readOriginalText(answer.optLong("id"),0,2000).optString("text").contains("무응답이나 읽씹이 아니다"));
        assertEquals("💪",EmojiReactionReply.fromStoredMessage(page.optJSONObject(1)).optString("emoji"));
    }

    @Test public void outOfPageReactionAndMismatchedTargetHashRemainVisibleStandaloneReplies() throws Exception {
        start(true);JSONObject owner=store.addMessage("user","고마워",null),answer=addReaction(owner,"❤️",true);
        JSONObject reactionRow=page().optJSONObject(1);render(new JSONArray().put(reactionRow),-1);
        assertNotNull(MainActivityUiTestSupport.text(root(),"이전 메시지에 ❤️ 반응했어"));
        assertTrue(((Map<?,?>)get(activity,"messageRows")).containsKey(answer.optLong("id")));
        JSONObject mismatchedOwner=new JSONObject(page().optJSONObject(0).toString()).put("content_hash","0".repeat(64));
        render(new JSONArray().put(mismatchedOwner).put(reactionRow),-1);
        assertNull(MainActivityUiTestSupport.description(root(),"호영이 이 메시지에 ❤️ 이모지로 응답함"));
        assertEquals(2,((Map<?,?>)get(activity,"messageRows")).size());
        assertNotNull(MainActivityUiTestSupport.text(root(),"이전 메시지에 ❤️ 반응했어"));
    }

    @Test public void modelWrittenReactionTextWithoutNativeReceiptRemainsOrdinaryRecord() throws Exception {
        start(true);JSONObject owner=store.addMessage("user","고마워",null),answer=addReaction(owner,"👍",false);
        JSONArray page=page();assertNull(EmojiReactionReply.fromStoredMessage(page.optJSONObject(1)));render(page,-1);
        assertNull(MainActivityUiTestSupport.description(root(),"호영이 이 메시지에 👍 이모지로 응답함"));
        assertNotNull(MainActivityUiTestSupport.text(root(),answer.optString("text")));
        assertEquals(2,((Map<?,?>)get(activity,"messageRows")).size());
    }

    @Test public void unchangedBubblesAreReusedWithoutReplayingMotionWhenNewMessageArrives() throws Exception {
        start(true);JSONObject first=store.addMessage("user","첫 번째 대화",null);render(page(),-1);
        View old=(View)((Map<?,?>)get(activity,"messageRows")).get(first.optLong("id"));
        old.setAlpha(.47f);old.setTranslationY(7f);
        render(new JSONArray(page().toString()),first.optLong("id"));
        assertSame(old,((Map<?,?>)get(activity,"messageRows")).get(first.optLong("id")));assertEquals(.47f,old.getAlpha(),.001f);assertEquals(7f,old.getTranslationY(),.001f);
        JSONObject next=store.addMessage("assistant","두 번째 대화",null);render(page(),first.optLong("id"));
        assertSame(old,((Map<?,?>)get(activity,"messageRows")).get(first.optLong("id")));assertEquals(.47f,old.getAlpha(),.001f);
        assertEquals(2,((Map<?,?>)get(activity,"messageRows")).size());
        assertNotNull(((Map<?,?>)get(activity,"messageRows")).get(next.optLong("id")));
    }

    @Test public void typingFollowsActiveRequestAndStopsOnPauseNavigationAndCompletion() throws Exception {
        start(true);
        // Inject the presentation state only. Runtime dispatch/completion semantics have their own integration tests.
        set(app.getRuntime(),"active",new JSONObject().put("external_id","ui-active-request").put("kind","user").put("status","preparing"));
        invoke("refreshStatus");TypingIndicatorView dots=(TypingIndicatorView)get(activity,"typingDots");
        assertTrue(dots.isActive());assertEquals(View.VISIBLE,((View)get(activity,"typingRow")).getVisibility());
        controller.pause();assertFalse(dots.isActive());controller.resume();invoke("refreshStatus");assertTrue(dots.isActive());
        MainActivityUiTestSupport.navigate(activity,"설정");assertFalse(dots.isActive());
        MainActivityUiTestSupport.navigate(activity,"대화");dots=(TypingIndicatorView)get(activity,"typingDots");assertTrue(dots.isActive());
        set(app.getRuntime(),"active",null);invoke("refreshStatus");
        assertFalse(dots.isActive());assertEquals(View.GONE,((View)get(activity,"typingRow")).getVisibility());
    }

    @Test public void backgroundJudgmentDoesNotPretendToBeTypingAReply() throws Exception {
        start(true);
        set(app.getRuntime(),"active",new JSONObject().put("external_id","background-check").put("kind","heartbeat").put("status","preparing"));
        invoke("refreshStatus");assertFalse(((TypingIndicatorView)get(activity,"typingDots")).isActive());
        assertEquals(View.GONE,((View)get(activity,"typingRow")).getVisibility());
    }

    @Test public void coveredConversationDoesNotSuppressNotificationsOrKeepStaleReplyViews() throws Exception {
        start(true);store.addMessage("assistant","서랍에 가려진 응답",null);render(page(),-1);
        assertEquals(1,((Map<?,?>)get(activity,"replyViews")).size());
        assertTrue(app.getReplyNotifications().state().optBoolean("conversationVisible"));
        MainActivityUiTestSupport.description(root(),"메뉴 열기").performClick();
        assertFalse(app.getReplyNotifications().state().optBoolean("conversationVisible"));
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,((View)get(activity,"root")).getImportantForAccessibility());
        activity.onBackPressed();assertTrue(app.getReplyNotifications().state().optBoolean("conversationVisible"));
        render(new JSONArray(),store.lastChatMessageId());
        assertTrue(((Map<?,?>)get(activity,"replyViews")).isEmpty());
        assertTrue(((Map<?,?>)get(activity,"messageRows")).isEmpty());
    }

    @Test public void resumeRechecksRevokedSystemPermissionAndKeepsDraft() throws Exception {
        start(true);((EditText)MainActivityUiTestSupport.description(root(),"메시지 입력")).setText("설정에서 돌아올 때도 보관");
        controller.pause();Shadows.shadowOf((Application)app).denyPermissions(Manifest.permission.READ_CONTACTS);controller.resume();
        assertTrue((Boolean)get(activity,"permissionBlocked"));assertNull(MainActivityUiTestSupport.description(root(),"메시지 입력"));
        assertEquals("설정에서 돌아올 때도 보관",store.getSetting("ui.draft",""));
        PermissionTestGrants.allowAll(app);controller.pause().resume();
        assertFalse((Boolean)get(activity,"permissionBlocked"));
        assertEquals("설정에서 돌아올 때도 보관",((EditText)MainActivityUiTestSupport.description(root(),"메시지 입력")).getText().toString());
    }

    @Test public void workConnectionKeepsOneActionAndMovesDiagnosticsBehindOverflowWithoutModelCalls() throws Exception {
        start(true);MainActivityUiTestSupport.navigate(activity,"Work");
        assertNotNull(MainActivityUiTestSupport.text(root(),"연결"));
        for(String hidden:new String[]{"새 Work 대화","삭제 확인 복구","연결 진단 복사","검증 요청","Work 선택"})
            assertNull(hidden,MainActivityUiTestSupport.text(root(),hidden));
        android.widget.TextView status=(android.widget.TextView)MainActivityUiTestSupport.description(root(),"Work 연결 상세");
        assertEquals(2,status.getMaxLines());assertFalse(status.getText().toString().contains("상태:"));
        assertTrue(MainActivityUiTestSupport.description(root(),"연결 옵션").performClick());
        AlertDialog options=ShadowAlertDialog.getLatestAlertDialog();
        assertEquals(4,options.getListView().getAdapter().getCount());
        assertEquals("연결 상세",options.getListView().getAdapter().getItem(1));
        options.getListView().performItemClick(null,1,1);
        AlertDialog details=ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(((android.widget.TextView)details.findViewById(android.R.id.message)).getText().toString().contains("상태:"));
        assertEquals("진단 복사",details.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());details.dismiss();
        assertEquals(0,store.lastChatMessageId());
        MainActivityUiTestSupport.navigate(activity,"대화");
        assertNotNull(MainActivityUiTestSupport.description(root(),"대화 옵션"));
    }

    private void start(boolean grants) throws Exception {
        if(grants)PermissionTestGrants.allowAll(app);
        controller=Robolectric.buildActivity(MainActivity.class).setup().visible();activity=controller.get();
        drain();
    }
    private void drain() throws Exception {
        // Barrier the preview executor, then deliver posted render results; do not run recurring timers forever.
        for(int i=0;i<3;i++){((ExecutorService)get(activity,"display")).submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    private JSONObject addReaction(JSONObject owner,String emoji,boolean nativeReceipt) throws Exception {
        JSONObject descriptor=EmojiReactionReply.proposal(new JSONObject().put("status","completed").put("message","")
                .put("reaction",new JSONObject().put("emoji",emoji).put("targetMessageId",owner.optLong("id"))),owner);
        assertNotNull("The ordinary casual source can receive a native reaction",descriptor);
        JSONObject answer=store.addMessage("assistant",EmojiReactionReply.originalText(descriptor),null);
        if(nativeReceipt)store.putSetting("answerSources:"+answer.optLong("id"),new JSONObject().put("version",1).put("reaction",descriptor)
                .put("sources",new JSONArray().put(new JSONObject().put("type","message").put("id",owner.optLong("id")).put("hash",owner.optString("content_hash")))).toString());
        return answer;
    }
    private JSONArray page(){return store.boundedChatMessagePage(0,40,8000).optJSONArray("messages");}
    private void render(JSONArray messages,long previous) throws Exception {
        Method method=MainActivity.class.getDeclaredMethod("renderChatPage",JSONArray.class,Map.class,long.class);method.setAccessible(true);method.invoke(activity,messages,Collections.emptyMap(),previous);
    }
    private void invoke(String name) throws Exception {Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(activity);}
    private View root(){return activity.findViewById(android.R.id.content);}
    private static Object get(Object value,String name) throws Exception {Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    private static void set(Object value,String name,Object update) throws Exception {Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);field.set(value,update);}
}
