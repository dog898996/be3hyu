package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import com.hoyoung.assistant.phone.PermissionTestGrants;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.runtime.UiAutomationProvenance;
import com.hoyoung.assistant.store.AccountStore;
import com.hoyoung.assistant.store.AccountMfaTools;
import com.hoyoung.assistant.store.HoyoungStore;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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

/** Drives actual account dialog controls against the durable original/revision store. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = HoyoungApplication.class, qualifiers = "ko-rKR-w412dp-h915dp-xhdpi")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MainActivityAccountsUiTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private HoyoungApplication app;
    private HoyoungStore store;
    private AccountStore accounts;

    @Before public void setup() throws Exception {
        app = (HoyoungApplication) RuntimeEnvironment.getApplication(); store = app.getStore();
        store.putSetting("autonomy.enabled", "false"); store.putSetting("memoryReviewEnabled", "false");
        PermissionTestGrants.allowAll(app);
        app.getSharedPreferences("hoyoung_ui_actor_v1", 0).edit().clear().commit();
        accounts = new AccountStore(app, store);
        controller = Robolectric.buildActivity(MainActivity.class).setup().visible(); activity = controller.get(); drain();
        navigate("계정");
    }
    @After public void cleanup() {
        if (controller != null) controller.pause().stop().destroy();
        app.getSharedPreferences("hoyoung_ui_actor_v1", 0).edit().clear().commit();
    }

    @Test public void visibleEditorStoresExactFieldsAsSystemOriginalWithoutForgingOwnerChat() throws Exception {
        JSONObject ordinary = store.addMessage("user", "일반 대화 원문 · 계정 UI와 별개", null);
        JSONObject saved = addThroughUi("  UI_Fixture🙂  ");
        assertEquals("  UI_Fixture🙂  ", saved.getString("password"));
        assertEquals("UI 전용 테스트 계정", saved.getString("label"));
        assertEquals("https://example.invalid/login", saved.getString("loginUrl"));
        assertEquals("첫 줄\n둘째 줄 🙂", saved.getString("notes"));
        assertEquals("추가 원문", saved.getJSONObject("extra").getString("hint"));
        assertEquals(AccountStore.OWNER_SUPPLIED, saved.getString("origin")); assertEquals("owner", saved.getString("inputActor"));
        assertFalse(saved.getBoolean("loginVerified"));
        JSONObject source = store.getMessage(saved.getJSONArray("sourceRefs").getJSONObject(0).getLong("id"));
        assertEquals("system", source.getString("role"));
        JSONObject original = new JSONObject(source.getString("text"));
        assertEquals("native_account_input", original.getString("type")); assertEquals("owner", original.getString("inputActor"));
        assertEquals(saved.getString("password"), new JSONObject(original.getString("recordJson")).getString("password"));
        assertEquals(ordinary.getString("text"), store.getMessage(ordinary.getLong("id")).getString("text"));
        assertFalse("Metadata cards must not silently expose password text", allText(root()).contains(saved.getString("password")));
    }

    @Test public void correctingThroughEditorReopensLatestAndShowsBothExplicitHistoryRevisions() throws Exception {
        JSONObject original = addThroughUi("old-ui-fixture-password");
        AlertDialog editor = openCard();
        field(editor, "비밀번호").setText("corrected-ui-fixture-password");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(); dismissNotice();
        JSONObject latest = accounts.get(original.getString("accountId"));
        assertEquals(2, latest.getInt("revision")); assertEquals("corrected-ui-fixture-password", latest.getString("password"));
        editor = openCard(); assertEquals(latest.getString("password"), field(editor, "비밀번호").getText().toString());
        MainActivityUiTestSupport.text(editor.getWindow().getDecorView(), "수정 이력 보기").performClick(); drain();
        AlertDialog history = latestDialog();
        View current = MainActivityUiTestSupport.description(history.getWindow().getDecorView(), "현재 값 펼치기");
        assertNotNull(current); current.performClick();
        View previous = MainActivityUiTestSupport.description(history.getWindow().getDecorView(), "이전 값 2 펼치기");
        assertNotNull(previous); previous.performClick();
        String rendered = allText(history.getWindow().getDecorView());
        assertTrue(rendered.contains("현재 · 수정 2")); assertTrue(rendered.contains("이전 · 수정 1"));
        assertTrue(rendered.contains("corrected-ui-fixture-password")); assertTrue(rendered.contains("old-ui-fixture-password"));
        assertTrue(rendered.contains("owner_supplied"));
    }

    @Test public void deletingFromEditorPurgesAccountCopiesAndHistoryPreservingOrdinaryOwnerOriginal() throws Exception {
        JSONObject ordinary = store.addMessage("user", "이 일반 대화는 삭제하면 안 돼 · original", null);
        JSONObject first = addThroughUi("old-ui-fixture-password");
        AlertDialog editor = openCard(); field(editor, "비밀번호").setText("latest-ui-fixture-password");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(); dismissNotice();
        JSONArray history = accounts.history(first.getString("accountId"), 10); assertEquals(2, history.length());
        editor = openCard(); MainActivityUiTestSupport.text(editor.getWindow().getDecorView(), "계정 보관함에서 삭제").performClick(); drain();
        assertNull(accounts.get(first.getString("accountId"))); assertEquals(0, accounts.history(first.getString("accountId"), 10).length());
        assertEquals(0, accounts.list("", 10).length());
        for (int index = 0; index < history.length(); index++) {
            JSONObject source = history.getJSONObject(index).getJSONArray("sourceRefs").getJSONObject(0);
            assertNull("Dedicated account original is physically removed", store.getMessage(source.getLong("id")));
            assertFalse("Old dedicated password cannot re-enter retrieval", store.getPrivacyLedger().allowedSource("message", source.getLong("id"), source.getString("hash")));
        }
        assertEquals(ordinary.getString("text"), store.getMessage(ordinary.getLong("id")).getString("text"));
        assertEquals(ordinary.getString("content_hash"), store.getMessage(ordinary.getLong("id")).getString("content_hash"));
    }

    @Test public void malformedExtraJsonKeepsUnsavedEditorAndAllowsCorrectedRetry() throws Exception {
        AlertDialog editor = newEditor(); fill(editor, "retry-ui-fixture-password");
        field(editor, "추가 필드 · JSON").setText("[\"an array is not an account object\"]");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain();
        assertTrue(editor.isShowing()); assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertEquals(0, accounts.list("", 10).length()); assertEquals("retry-ui-fixture-password", field(editor, "비밀번호").getText().toString());
        dismissNotice(); field(editor, "추가 필드 · JSON").setText("{\"hint\":\"추가 원문\"}");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(); dismissNotice();
        assertFalse(editor.isShowing()); assertEquals("retry-ui-fixture-password", current().getString("password"));
    }

    @Test public void realDialogWindowTracksAgentSubmissionAndPhysicalOwnerTouchRestoresOwnerActor() throws Exception {
        AlertDialog editor = newEditor(); fill(editor, "agent-ui-fixture-password");
        UiAutomationProvenance.record(app, AccessibilityNodeInfo.ACTION_SET_TEXT, true, "agent-ui-fixture-password");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(); dismissNotice();
        JSONObject generated = current(); assertEquals("agent_authored", generated.getString("inputActor"));
        assertEquals(AccountStore.AGENT_GENERATED, generated.getString("origin"));
        JSONObject source = store.getMessage(generated.getJSONArray("sourceRefs").getJSONObject(0).getLong("id"));
        assertEquals("system", source.getString("role")); assertEquals("agent_authored", new JSONObject(source.getString("text")).getString("inputActor"));
        editor = openCard(); field(editor, "비밀번호").setText("owner-approved-ui-fixture-password");
        MotionEvent ownerTouch = MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, 4, 4, 0);
        try { editor.getWindow().getCallback().dispatchTouchEvent(ownerTouch); }
        finally { ownerTouch.recycle(); }
        assertEquals("A real dialog Window callback must observe owner touch", "owner", UiAutomationProvenance.currentActor(app));
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(); dismissNotice();
        JSONObject adopted = current(); assertEquals("owner", adopted.getString("inputActor")); assertEquals(AccountStore.OWNER_SUPPLIED, adopted.getString("origin"));
        JSONArray history = accounts.history(adopted.getString("accountId"), 10);
        assertEquals("owner", history.getJSONObject(0).getString("inputActor")); assertEquals("agent_authored", history.getJSONObject(1).getString("inputActor"));
    }

    @Test public void fiveOwnerPermissionControlsPersistAndInvalidLimitsDoNotReplaceLastValues() throws Exception {
        navigate("권한");
        MainActivityUiTestSupport.description(root(), "알림·다운로드·실행 방식 펼치기").performClick();
        for (String label : new String[]{"잠금 화면에 답장 내용 표시", "Android 알림의 제안 동작 허용", "Work에서 작업 파일 내려받기"}) {
            CheckBox control = (CheckBox) MainActivityUiTestSupport.text(root(), label); assertNotNull(control); assertTrue(control.isChecked()); control.performClick(); assertFalse(control.isChecked());
        }
        String[] keys = {"replyLockscreenContentVisible", "replySystemContextualActionsEnabled", "workFileDownloadsEnabled"};
        for (String key : keys) assertEquals("false", store.getSetting(key, "missing"));
        EditText rounds = (EditText) MainActivityUiTestSupport.description(root(), "작업당 도구 실행 턴 · 0은 제한 없음");
        EditText actions = (EditText) MainActivityUiTestSupport.description(root(), "한 턴의 도구 개수 · 0은 제한 없음");
        rounds.setText("7"); actions.setText("3"); MainActivityUiTestSupport.text(root(), "실행 한도 저장").performClick(); dismissNotice();
        assertEquals("7", store.getSetting("toolRoundLimit", "")); assertEquals("3", store.getSetting("toolActionsPerTurnLimit", ""));
        rounds.setText("-1"); actions.setText("not-an-integer"); MainActivityUiTestSupport.text(root(), "실행 한도 저장").performClick(); dismissNotice();
        assertEquals("7", store.getSetting("toolRoundLimit", "")); assertEquals("3", store.getSetting("toolActionsPerTurnLimit", ""));
        navigate("계정"); navigate("권한");
        for (String label : new String[]{"잠금 화면에 답장 내용 표시", "Android 알림의 제안 동작 허용", "Work에서 작업 파일 내려받기"}) assertFalse(((CheckBox) MainActivityUiTestSupport.text(root(), label)).isChecked());
        assertEquals("7", ((EditText) MainActivityUiTestSupport.description(root(), "작업당 도구 실행 턴 · 0은 제한 없음")).getText().toString());
        assertEquals("3", ((EditText) MainActivityUiTestSupport.description(root(), "한 턴의 도구 개수 · 0은 제한 없음")).getText().toString());
    }

    @Test public void delayedSaveAfterDialogCloseCannotReplaceNewlySelectedSettingsPage() throws Exception {
        AlertDialog editor = newEditor(); fill(editor, "navigation-ui-fixture-password");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ((ExecutorService) get(activity, "io")).submit(() -> { entered.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); } });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            MainActivityUiTestSupport.navigate(activity, "설정"); assertNotNull(MainActivityUiTestSupport.description(root(), "사용자 지침 편집"));
        } finally { release.countDown(); }
        drain();
        assertEquals("설정", get(activity, "tab"));
        assertNotNull("Completed save preserves the page the owner selected meanwhile", MainActivityUiTestSupport.description(root(), "사용자 지침 편집"));
        assertNull(MainActivityUiTestSupport.text(root(), "계정 보관함"));
        assertEquals("The originally requested save still completes", "navigation-ui-fixture-password", current().getString("password"));
    }

    @Test public void friendlyMfaFieldsCreateCanonicalTotpAndExactRecoveryCodeLines() throws Exception {
        AlertDialog editor=newEditor();fill(editor,"mfa-ui-fixture-password");
        field(editor,"인증앱 비밀키 · Base32").setText("JBSWY3DPEHPK3PXP");
        field(editor,"인증 알고리즘 · SHA1, SHA256, SHA512").setText("SHA256");
        field(editor,"인증 코드 자릿수 · 6 또는 8").setText("8");
        field(editor,"인증 코드 갱신 간격 · 초").setText("60");
        field(editor,"인증앱 서비스 이름 · 선택").setText("예시 서비스");
        field(editor,"인증앱 계정 이름 · 선택").setText("owner_ui@example.invalid");
        field(editor,"복구 코드 · 한 줄에 하나").setText(" code-a \ncode-b\r\n");
        field(editor,"인증 방식 메모 · 선택").setText("휴대폰 알림도 확인할 수 있음");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();dismissNotice();
        JSONObject extra=current().getJSONObject("extra"),totp=extra.getJSONObject("totp");
        assertEquals("JBSWY3DPEHPK3PXP",totp.getString("secret"));assertEquals("SHA256",totp.getString("algorithm"));
        assertEquals(8,totp.getInt("digits"));assertEquals(60,totp.getInt("period"));
        assertEquals("예시 서비스",totp.getString("issuer"));assertEquals("owner_ui@example.invalid",totp.getString("accountName"));
        JSONArray codes=extra.getJSONArray("recoveryCodes");assertEquals(3,codes.length());
        assertEquals(" code-a ",codes.getString(0));assertEquals("code-b\r",codes.getString(1));assertEquals("",codes.getString(2));
        assertEquals("추가 원문",extra.getString("hint"));assertEquals("휴대폰 알림도 확인할 수 있음",extra.getJSONObject("mfa").getString("notes"));
        assertFalse(current().getBoolean("loginVerified"));
    }

    @Test public void untouchedMfaPreservesUnknownNestedFieldsAndEmbeddedRecoverySeparatorsExactly() throws Exception {
        JSONObject extra=complexMfaExtra();
        JSONObject saved=accounts.saveFromUi(new JSONObject().put("service","Fixture 서비스").put("username","owner_ui@example.invalid").put("password","original-ui-password").put("extra",extra));
        navigate("계정");AlertDialog editor=openCard();field(editor,"표시 이름").setText("이름만 변경");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();dismissNotice();
        assertEquals(extra.toString(),accounts.get(saved.getString("accountId")).getJSONObject("extra").toString());
        assertEquals("이름만 변경",current().getString("label"));
    }

    @Test public void explicitFriendlyMfaEditsOverlayAdvancedDataWithoutLosingUnrecognizedValues() throws Exception {
        JSONObject extra=complexMfaExtra();
        accounts.saveFromUi(new JSONObject().put("service","Fixture 서비스").put("username","owner_ui@example.invalid").put("password","original-ui-password").put("extra",extra));
        navigate("계정");AlertDialog editor=openCard();
        field(editor,"인증앱 비밀키 · Base32").setText(" NEW_EXACT_SECRET ");
        field(editor,"복구 코드 · 한 줄에 하나").setText("replacement one\n replacement two ");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();dismissNotice();
        JSONObject updated=current().getJSONObject("extra");
        assertEquals(" NEW_EXACT_SECRET ",updated.getJSONObject("totp").getString("secret"));
        assertEquals(extra.getJSONObject("totp").getJSONObject("vendor").toString(),updated.getJSONObject("totp").getJSONObject("vendor").toString());
        assertEquals("SHA512",updated.getJSONObject("totp").getString("algorithm"));assertEquals(8,updated.getJSONObject("totp").getInt("digits"));assertEquals(45,updated.getJSONObject("totp").getInt("period"));
        assertEquals(extra.getJSONObject("other").toString(),updated.getJSONObject("other").toString());
        assertEquals(extra.getJSONObject("mfa").toString(),updated.getJSONObject("mfa").toString());
        assertEquals("replacement one",updated.getJSONArray("recoveryCodes").getString(0));assertEquals(" replacement two ",updated.getJSONArray("recoveryCodes").getString(1));
    }

    @Test public void explicitMfaRemovalControlsClearOnlySelectedGroupsAndKeepOtherExtras() throws Exception {
        JSONObject extra=complexMfaExtra();
        accounts.saveFromUi(new JSONObject().put("service","Fixture 서비스").put("username","owner_ui@example.invalid").put("password","original-ui-password").put("extra",extra));
        navigate("계정");AlertDialog editor=openCard();
        ((CheckBox)MainActivityUiTestSupport.text(editor.getWindow().getDecorView(),"저장한 인증앱 설정 삭제")).performClick();
        ((CheckBox)MainActivityUiTestSupport.text(editor.getWindow().getDecorView(),"저장한 복구 코드 목록 삭제")).performClick();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();dismissNotice();
        JSONObject updated=current().getJSONObject("extra");assertFalse(updated.has("totp"));assertFalse(updated.has("recoveryCodes"));
        assertEquals(extra.getJSONObject("other").toString(),updated.getJSONObject("other").toString());
        assertEquals(extra.getJSONObject("mfa").toString(),updated.getJSONObject("mfa").toString());
    }

    @Test public void invalidFriendlyTotpNumbersKeepUnsavedFormForCorrection() throws Exception {
        AlertDialog editor=newEditor();fill(editor,"numeric-ui-fixture-password");
        field(editor,"인증앱 비밀키 · Base32").setText("JBSWY3DPEHPK3PXP");field(editor,"인증 코드 자릿수 · 6 또는 8").setText("7");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();
        assertTrue(editor.isShowing());assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());assertEquals(0,accounts.list("",10).length());
        dismissNotice();field(editor,"인증 코드 자릿수 · 6 또는 8").setText("6");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();drain();dismissNotice();
        assertEquals(6,current().getJSONObject("extra").getJSONObject("totp").getInt("digits"));
    }

    @Test public void permissionPageCoversEveryLiveRuntimeToolIncludingMfaWithoutHardcodedInventoryCount() throws Exception {
        navigate("권한");JSONArray catalog=app.getRuntime().toolCatalog();Set<String> expected=new HashSet<>();
        for(int i=0;i<catalog.length();i++)assertTrue("Runtime catalog names must remain unique",expected.add(catalog.getJSONObject(i).getString("name")));
        Map<String,CheckBox> displayed=new HashMap<>();collectTaggedControls(root(),displayed);
        assertEquals("Every executable catalog tool must have a named owner control",expected,displayed.keySet());
        for(String name:expected){CheckBox check=displayed.get(name);assertFalse("Owner sees a readable label",check.getText().toString().isEmpty());assertTrue("All catalog capabilities start allowed",check.isChecked());}
        JSONArray mfaCatalog=new AccountMfaTools(app,store,null).catalog();assertTrue("Real native MFA catalog must be present",mfaCatalog.length()>0);
        MainActivityUiTestSupport.description(root(),"계정 보관함 펼치기").performClick();
        for(int i=0;i<mfaCatalog.length();i++){
            String name=mfaCatalog.getJSONObject(i).getString("name");CheckBox check=displayed.get(name);
            assertNotNull("Actual MFA tool is present in the full owner control inventory: "+name,check);assertTrue("Expanded account category reveals its actual control",check.isShown());check.performClick();
            assertFalse("The actual MFA toggle persists to the live tool policy",new JSONObject(store.getSetting("toolPermissions","{}")).getBoolean(name));
            check.performClick();assertTrue(new JSONObject(store.getSetting("toolPermissions","{}")).getBoolean(name));
        }
    }

    private JSONObject complexMfaExtra()throws Exception {
        return new JSONObject().put("totp",new JSONObject().put("secret","OLD_EXACT_SECRET").put("algorithm","SHA512").put("digits",8).put("period",45)
                .put("vendor",new JSONObject().put("case",new JSONArray().put("exact\nvalue").put(42))))
                .put("recoveryCodes",new JSONArray().put("first\nembedded").put(" trailing space ").put(""))
                .put("mfa",new JSONObject().put("methods",new JSONArray().put("totp").put("security_key")).put("notes","기존 안내 그대로").put("vendorFlag",true))
                .put("other",new JSONObject().put("arbitrary",new JSONArray().put(JSONObject.NULL).put(new JSONObject().put("x","정확한 원문"))));
    }
    private void collectTaggedControls(View view,Map<String,CheckBox> found){
        if(view instanceof CheckBox&&view.getTag() instanceof String){String name=(String)view.getTag();assertNull("No duplicate owner control for "+name,found.put(name,(CheckBox)view));}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collectTaggedControls(((ViewGroup)view).getChildAt(i),found);
    }

    private JSONObject addThroughUi(String password) throws Exception {
        AlertDialog editor = newEditor(); fill(editor, password); editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(); dismissNotice(); return current();
    }
    private AlertDialog newEditor() throws Exception {
        MainActivityUiTestSupport.text(root(), "계정 추가").performClick();
        // Dialog.onShow is delivered through Android's main Handler. A real
        // touchscreen cannot press its Save button before that delivery/frame.
        drain(); return latestDialog();
    }
    private AlertDialog openCard() throws Exception {
        View card = MainActivityUiTestSupport.description(root(), "계정 열기 · Fixture 서비스 · owner_ui@example.invalid"); assertNotNull(card); card.performClick(); drain(); return latestDialog();
    }
    private void fill(AlertDialog dialog, String password) {
        field(dialog, "서비스").setText("Fixture 서비스"); field(dialog, "표시 이름").setText("UI 전용 테스트 계정");
        field(dialog, "아이디·이메일").setText("owner_ui@example.invalid"); field(dialog, "비밀번호").setText(password);
        field(dialog, "로그인 주소").setText("https://example.invalid/login"); field(dialog, "메모").setText("첫 줄\n둘째 줄 🙂");
        field(dialog, "추가 필드 · JSON").setText("{\"hint\":\"추가 원문\"}");
    }
    private EditText field(AlertDialog dialog, String description) {
        View found = MainActivityUiTestSupport.description(dialog.getWindow().getDecorView(), description); assertTrue(description, found instanceof EditText); return (EditText) found;
    }
    private JSONObject current() throws Exception { JSONArray listed = accounts.list("", 10); assertEquals(1, listed.length()); return accounts.get(listed.getJSONObject(0).getString("accountId")); }
    private void navigate(String tab) throws Exception { MainActivityUiTestSupport.navigate(activity, tab); drain(); }
    private void drain() throws Exception {
        for (int pass = 0; pass < 4; pass++) {
            ((ExecutorService) get(activity, "io")).submit(() -> {}).get(5, TimeUnit.SECONDS);
            ((ExecutorService) get(activity, "display")).submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private AlertDialog latestDialog() { AlertDialog value = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(value); assertTrue(value.isShowing()); return value; }
    private void dismissNotice() { AlertDialog notice = latestDialog(); notice.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); }
    private View root() { return activity.findViewById(android.R.id.content); }
    private static Object get(Object value, String name) throws Exception { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
    private static String allText(View view) {
        StringBuilder value = new StringBuilder(); if (view instanceof TextView) value.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) value.append(allText(((ViewGroup) view).getChildAt(index)));
        return value.toString();
    }
}
