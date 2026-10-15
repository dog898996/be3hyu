package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.HoyoungStore;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Independent real-device exploration. No Work account, fake Activity, or paid model call. */
@RunWith(AndroidJUnit4.class)
public final class SettingsExplorerDeviceTest {
    private Context context;
    private HoyoungApplication app;
    private HoyoungStore store;
    private UiDevice device;
    private final Map<String, String> previous = new LinkedHashMap<>();

    @Before public void offlineOwnerFixture() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DevicePermissionFixture.assertReady(context);
        app = (HoyoungApplication) context.getApplicationContext();
        store = app.getStore();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        for (String key : new String[]{"autonomy.enabled", "memoryReviewEnabled", "customInstructions",
                "requiredModel", "requiredEffort", "requiredSpeed", "assistant.identity", "toolPermissions"}) {
            previous.put(key, store.getSetting(key, null));
        }
        store.putSetting("autonomy.enabled", "false");
        store.putSetting("memoryReviewEnabled", "false");
        store.putSetting("requiredModel", "gpt-6.1-sol");
        store.putSetting("requiredEffort", "medium");
        store.putSetting("requiredSpeed", "fast");
        store.putSetting("customInstructions", "독립 기기 탐색용 사용자 지침");
        proof("fixture fontScale=" + context.getResources().getConfiguration().fontScale
                + "; uiMode=" + context.getResources().getConfiguration().uiMode
                + "; pixels=" + device.getDisplayWidth() + "x" + device.getDisplayHeight());
    }

    @After public void restoreSyntheticPreferences() throws Exception {
        if (device != null) { device.setOrientationNatural(); device.unfreezeRotation(); }
        if (store != null) for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (entry.getValue() == null) store.getWritableDatabase().delete("settings", "setting_key=?", new String[]{entry.getKey()});
            else store.putSetting(entry.getKey(), entry.getValue());
        }
    }

    @Test public void multilineKoreanEditsSaveWithoutOverwritingConcurrentChatSettings() throws Exception {
        String instructions = "문장 중간의 줄바꿈과 이모지도 그대로 보존해 줘. 🐈\n"
                + "약속 시간과 출처를 먼저 적고, 한글 이름이 길어도 빠뜨리지 않아.\n".repeat(18);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            DeviceFixtureHealth.openPage(device, "설정");
            evidence("01-settings-overview");
            DeviceFixtureHealth.expandSection(device, "사용자 지침");
            DeviceFixtureHealth.revealDescription(device, "사용자 지침 편집");
            object(By.desc("사용자 지침 편집")).setText(instructions);
            hideKeyboard(scenario);
            evidence("02-long-korean-instructions");
            JSONObject external = app.getRuntime().updatePersonalSettings(new JSONObject().put("requiredEffort", "high"), null);
            assertTrue(external.toString(), external.optBoolean("ok"));
            clickText("설정 저장");
            acknowledge();
            assertEquals(instructions, store.getSetting("customInstructions", ""));
            assertEquals("An untouched permanent control must retain the later chat update", "high", store.getSetting("requiredEffort", ""));

            DeviceFixtureHealth.revealDescription(device, "사용자 지침 편집");
            object(By.desc("사용자 지침 편집")).setText("다른 화면을 열어 둔 동안 편집한 오래된 지침");
            hideKeyboard(scenario);
            String latest = "대화에서 더 최근에 저장한 지침";
            assertTrue(app.getRuntime().updatePersonalSettings(new JSONObject().put("customInstructions", latest), null).optBoolean("ok"));
            clickText("설정 저장");
            assertNotNull(object(By.textContains("다른 화면이나 대화에서 바뀌었어")));
            evidence("03-concurrent-setting-conflict");
            acknowledge();
            assertEquals(latest, store.getSetting("customInstructions", ""));
            DeviceFixtureHealth.openPage(device, "권한");
            DeviceFixtureHealth.openPage(device, "설정");
            DeviceFixtureHealth.expandSection(device, "사용자 지침");
            DeviceFixtureHealth.revealDescription(device, "사용자 지침 편집");
            assertEquals(latest, object(By.desc("사용자 지침 편집")).getText());
            proof("multiline-native-edit-save; concurrent-unedited-setting-retained; stale-edited-setting-rejected; reopened-latest");
        }
    }

    @Test public void modelPickerSurvivesRotationAndHomeExpiresOnlyFutureRouting() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            DeviceFixtureHealth.awaitForeground(device);
            object(By.descStartsWith("이번 화면의 모델 선택")).click();
            scenario.onActivity(activity->{
                boolean found=false;
                for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews()){
                    View model=find(window,view->"이번 화면 모델".equals(view.getContentDescription()));
                    if(model instanceof android.widget.Spinner){
                        android.widget.Spinner spinner=(android.widget.Spinner)model;found=true;
                        assertEquals(3,spinner.getCount());
                        assertEquals("6 Sol",spinner.getItemAtPosition(0));assertEquals("6.1 Sol",spinner.getItemAtPosition(1));assertEquals("Astra",spinner.getItemAtPosition(2));
                    }
                }
                assertTrue("The three-choice adapter belongs to an actual Android dialog window",found);
            });
            object(By.desc("이번 화면 모델")).click();
            assertNotNull(object(By.text("6 Sol")));
            assertNotNull(object(By.text("6.1 Sol")));
            assertNotNull(object(By.text("Astra")));
            object(By.text("Astra")).click();
            object(By.desc("이번 화면 추론 강도")).click();
            object(By.text("XHigh")).click();
            UiObject2 fast = object(By.desc("이번 화면 Fast"));
            assertTrue(fast.isChecked()); fast.click();
            evidence("04-temporary-route-dialog");
            object(By.text("적용")).click();
            assertRoute("gpt-6-astra", "xhigh", "standard");
            String session = app.getRuntime().foregroundRoutingState().optString("sessionId");

            device.setOrientationLeft();
            await(() -> context.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
            DeviceFixtureHealth.awaitForeground(device);
            assertEquals(session, app.getRuntime().foregroundRoutingState().optString("sessionId"));
            object(By.descStartsWith("이번 화면의 모델 선택")).click();
            evidence("05-landscape-large-font-model-dialog");
            // These native controls must remain reachable on a short landscape display.
            revealDialogDescription("이번 화면 모델");
            assertNotNull(object(By.desc("이번 화면 모델")));
            revealDialogDescription("이번 화면 Fast");
            assertNotNull(object(By.desc("이번 화면 Fast")));
            object(By.text("닫기")).click();
            device.setOrientationNatural();
            DeviceFixtureHealth.awaitForeground(device);

            DeviceFixtureHealth.openPage(device, "설정");
            DeviceFixtureHealth.openPage(device, "대화");
            assertRoute("gpt-6-astra", "xhigh", "standard");
            String text = "EXPLORER_OFFLINE_ROUTE_" + SystemClock.elapsedRealtime();
            object(By.desc("메시지 입력")).setText(text);
            hideKeyboard(scenario);
            object(By.desc("메시지 보내기")).click();
            await(() -> requestFor(text) != null);
            JSONObject request = requestFor(text);
            String key = "ownerRouteOverride:" + request.optString("external_id");
            String frozen = store.getSetting(key, "");
            assertFalse("A real native submission must persist its selected route", frozen.isEmpty());
            assertEquals("gpt-6-astra", new JSONObject(frozen).optString("model"));

            device.pressHome();
            await(() -> !app.getRuntime().foregroundRoutingState().optBoolean("selected"));
            assertEquals("Leaving must not rewrite the already submitted request", frozen, store.getSetting(key, ""));
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            DeviceFixtureHealth.awaitForeground(device);
            assertFalse(app.getRuntime().foregroundRoutingState().optBoolean("selected"));
            assertNotNull(object(By.descContains("gpt-6.1-sol · medium · fast · 기본 설정")));
            assertEquals(frozen, store.getSetting(key, ""));
            evidence("06-home-cleared-future-route");
            proof("three-native-model-options; Fast-off-standard; real-rotation-keeps-session; real-send-freezes-route; real-Home-clears-future-only; offline-provider-unexecuted");
        }
    }

    @Test public void longestLegalKoreanIdentityStillLeavesChatAndDrawerUsable() throws Exception {
        String longName = "가나다라마바사아자차카타파하가나".repeat(5);
        assertEquals(80, longName.length());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            DeviceFixtureHealth.openPage(device, "설정");
            DeviceFixtureHealth.revealDescription(device, "비서 이름 편집");
            object(By.desc("비서 이름 편집")).setText(longName);
            hideKeyboard(scenario);
            clickText("이름 저장"); acknowledge();
            assertEquals(longName, app.getRuntime().assistantIdentity().optString("name"));
            evidence("07-long-profile-name");
            // Android Back performs the real settings-to-chat transition, independent of the current name.
            device.pressBack(); DeviceFixtureHealth.awaitForeground(device);
            evidence("08-long-name-chat");
            scenario.onActivity(activity -> {
                View editor = find(activity.getWindow().getDecorView(), view -> "메시지 입력".equals(view.getContentDescription()));
                View send = find(activity.getWindow().getDecorView(), view -> "메시지 보내기".equals(view.getContentDescription()));
                assertNotNull(editor); assertNotNull(send);
                Rect visible = new Rect();
                assertTrue("The composer must remain on screen after a legal long rename", editor.getGlobalVisibleRect(visible));
                assertTrue("The entire Send target must remain reachable", send.getGlobalVisibleRect(visible));
                assertEquals(send.getHeight(), visible.height());
                View menu = find(activity.getWindow().getDecorView(), view -> "메뉴 열기".equals(view.getContentDescription()));
                assertNotNull(menu);
                assertTrue("The header must leave a usable conversation viewport after a legal rename",
                        ((View) menu.getParent()).getHeight() < activity.getWindow().getDecorView().getHeight() / 3);
            });
            object(By.desc("메뉴 열기")).click();
            evidence("09-long-name-drawer");
            device.pressBack();
            assertFalse(device.hasObject(By.desc("메뉴").pkg(context.getPackageName())));
            DeviceFixtureHealth.openPage(device, "설정");
            DeviceFixtureHealth.revealDescription(device, "비서 이름 편집");
            object(By.desc("비서 이름 편집")).setText("화면에 남은 예전 편집"); hideKeyboard(scenario);
            JSONObject current = app.getRuntime().assistantIdentity();
            assertTrue(app.getRuntime().updateAssistantIdentity("나중에 대화에서 바꾼 이름", null, false, current.optString("revision")).optBoolean("ok"));
            clickText("기본 고양이 사진으로"); acknowledge();
            clickText("이름 저장"); acknowledge();
            assertEquals("나중에 대화에서 바꾼 이름", app.getRuntime().assistantIdentity().optString("name"));
            proof("80-character-native-rename; composer-and-drawer-reachable; stale-identity-edit-rejected-after-avatar-reset");
        }
    }

    @Test public void wholeAppDetoursKeepToolPermissionsAndCancelExternalPickersCleanly() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            DeviceFixtureHealth.openPage(device, "권한");
            DeviceFixtureHealth.expandSection(device, "작업과 앱 설정");
            String toolLabel = "현재 비서 이름·프사 확인";
            clickText(toolLabel);
            assertFalse(new JSONObject(store.getSetting("toolPermissions", "{}")).optBoolean("app.identity.get", true));
            evidence("10-disabled-individual-tool");
            for (String destination : new String[]{"계정", "기억", "Work", "파일", "권한"})
                DeviceFixtureHealth.openPage(device, destination);
            DeviceFixtureHealth.expandSection(device, "작업과 앱 설정");
            DeviceFixtureHealth.revealText(device, toolLabel);
            assertFalse(object(By.text(toolLabel)).isChecked());
            clickText(toolLabel);
            assertTrue(new JSONObject(store.getSetting("toolPermissions", "{}")).optBoolean("app.identity.get", false));

            DeviceFixtureHealth.openPage(device, "계정");
            clickText("계정 추가");
            object(By.desc("서비스")).setText("아직 저장하지 않은 기기 탐색용 계정");
            hideKeyboard(scenario);
            evidence("11-account-cancel-detour");
            device.pressBack();
            assertTrue(device.wait(Until.gone(By.desc("서비스")), 10_000));
            DeviceFixtureHealth.openPage(device, "설정");
            DeviceFixtureHealth.expandSection(device, "기억과 백업");
            clickText("백업과 복원 열기");
            clickText("사진·파일 가져오기");
            assertTrue(device.wait(Until.gone(By.pkg(context.getPackageName()).desc("메뉴 열기")), 10_000));
            evidence("12-real-document-picker");
            device.pressBack(); DeviceFixtureHealth.awaitForeground(device);
            assertNotNull(object(By.text("작업 파일")));
            device.pressBack(); DeviceFixtureHealth.awaitForeground(device);
            assertNotNull(object(By.desc("메시지 입력")));
            DevicePermissionFixture.assertReady(context);
            proof("all-seven-native-destinations; immediate-tool-permission-retained; unsaved-account-dialog-back; real-SAF-picker-cancel; production-permission-gate-still-ready");
        }
    }

    private void clickText(String text) throws Exception {
        DeviceFixtureHealth.revealText(device, text);
        object(By.text(text)).click();
        device.waitForIdle(1_000);
    }

    private UiObject2 object(BySelector selector) {
        UiObject2 result = DeviceFixtureHealth.waitFor(device, Until.findObject(selector), 15_000);
        assertNotNull("The actual visible Android control must be reachable: " + selector, result);
        return result;
    }

    private void revealDialogDescription(String description) throws Exception {
        if(device.hasObject(By.desc(description)))return;
        androidx.test.uiautomator.UiScrollable scroll=new androidx.test.uiautomator.UiScrollable(new androidx.test.uiautomator.UiSelector().scrollable(true));
        assertTrue("A native swipe must reveal the dialog control "+description,
                scroll.scrollIntoView(new androidx.test.uiautomator.UiSelector().description(description)));
    }

    private void acknowledge() {
        object(By.text("확인")).click(); device.waitForIdle(1_000);
    }

    private void hideKeyboard(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
            for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                View focused=window.findFocus();
                if(focused!=null){
                    if(keyboard!=null)keyboard.hideSoftInputFromWindow(focused.getWindowToken(),0);
                    focused.clearFocus();
                }
            }
        });
        device.waitForIdle(1_000);
    }

    private void assertRoute(String model, String effort, String speed) {
        JSONObject route = app.getRuntime().foregroundRoutingState();
        assertTrue(route.toString(), route.optBoolean("selected"));
        assertEquals(model, route.optString("model"));
        assertEquals(effort, route.optString("effort"));
        assertEquals(speed, route.optString("speed"));
    }

    private JSONObject requestFor(String text) {
        JSONArray requests = store.listRequests(100);
        for (int i = 0; i < requests.length(); i++) {
            JSONObject request = requests.optJSONObject(i);
            if (request != null && text.equals(request.optString("text"))) return request;
        }
        return null;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        do { if (condition.getAsBoolean()) return; SystemClock.sleep(100); }
        while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("The real Android lifecycle or persisted operation must complete", condition.getAsBoolean());
    }

    private void evidence(String phase) throws Exception {
        File folder = new File(context.getExternalFilesDir(null), "explorer-settings");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        assertTrue(device.takeScreenshot(new File(folder, phase + ".png")));
        device.dumpWindowHierarchy(new File(folder, phase + ".xml"));
        proof("evidence=" + folder + "/" + phase);
    }

    private static View find(View view, Predicate<View> matches) {
        if (view.getVisibility() == View.GONE) return null;
        if (matches.test(view)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), matches); if (found != null) return found;
        }
        return null;
    }

    private static void proof(String text) {
        String message = "HOYOUNG_EXPLORER_SETTINGS " + text;
        android.util.Log.i("HoyoungDeviceProof", message);
        Bundle status = new Bundle(); status.putString("stream", message + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
