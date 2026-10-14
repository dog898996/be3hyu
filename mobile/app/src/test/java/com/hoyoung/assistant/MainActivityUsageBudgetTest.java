package com.hoyoung.assistant;

import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import com.hoyoung.assistant.runtime.HoyoungApplication;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.robolectric.RuntimeEnvironment;
import com.hoyoung.assistant.phone.PermissionTestGrants;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = HoyoungApplication.class)
public class MainActivityUsageBudgetTest {
    @Before public void grantDevicePermissions() { PermissionTestGrants.allowAll(RuntimeEnvironment.getApplication()); }
    @Test public void missingTelemetryCannotBecomeAnAchievementOrTokenEstimate() {
        String absent = MainActivity.usageBudgetDescription(null);
        assertTrue(absent.contains("사용량 미확인"));
        assertTrue(absent.contains("달성 미검증"));
        JSONObject state = new JSONObject();
        try { state.put("requestStatistics", new JSONObject().put("acceptedRequests", 3).put("promptUtf16Chars", 700)); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
        String description = MainActivity.usageBudgetDescription(state);
        assertTrue(description.contains("호출 3회"));
        assertTrue(description.contains("700자(UTF-16)"));
        assertTrue(description.contains("실제 토큰이나 한도 차감량이 아니야"));
        assertFalse(description.contains("사용량 0.0"));
    }

    @Test public void ownerReportAndPartialDayDoNotProveTenPercent() throws Exception {
        JSONObject state = new JSONObject().put("targetPercent", 10).put("basis", "daily_share")
                .put("budgetState", "owner_reported_only").put("verifiedObservationFresh", false)
                .put("partialDayCoverage", true).put("measuredLowerPercent", JSONObject.NULL)
                .put("measuredUpperPercent", JSONObject.NULL)
                .put("observation", new JSONObject().put("origin", "owner_reported").put("usedPercent", 7));
        String description = MainActivity.usageBudgetDescription(state);
        assertTrue(description.contains("하루 몫 기준"));
        assertTrue(description.contains("사용자 수동 관찰"));
        assertTrue(description.contains("달성 미검증"));
        assertTrue(description.contains("하루 전체 관찰이 아니므로"));
        assertFalse(description.contains("관찰 구간 사용량"));
        assertFalse(description.contains("NaN"));
    }

    @Test public void verifiedBoundIsClearlySharedAccountAndPartialInterval() throws Exception {
        JSONObject state = new JSONObject().put("targetPercent", 10).put("basis", "pool_per_day")
                .put("budgetState", "target_exceeded").put("verifiedObservationFresh", true)
                .put("partialDayCoverage", true).put("measuredLowerPercent", 11).put("measuredUpperPercent", 13)
                .put("observation", new JSONObject().put("origin", "provider_ui_observed"));
        String description = MainActivity.usageBudgetDescription(state);
        assertTrue(description.contains("11.0~13.0%p"));
        assertTrue(description.contains("공유 계정 전체"));
        assertTrue(description.contains("목표보다 높아"));
        assertTrue(description.contains("작업은 계속 진행"));
        assertTrue(description.contains("호출 차단은 없어"));
        assertTrue(description.contains("하루 전체 관찰이 아니므로"));
        assertFalse(description.contains("목표 달성"));
    }

    @Test public void optimizationKeepsUserFastWithoutLoweringUserEffort() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            HoyoungApplication app = (HoyoungApplication) activity.getApplication();
            app.getStore().putSetting("requiredSpeed", "standard");
            app.getStore().putSetting("requiredEffort", "high");
            app.getStore().putSetting("usageOptimizationEnabled", "true");
            View root = activity.findViewById(android.R.id.content);
            MainActivityUiTestSupport.navigate(activity, "설정");
            Spinner speed = findUserSpeed(root);
            assertNotNull(speed);
            assertFalse(speed.isEnabled());
            assertEquals("fast", speed.getSelectedItem());
            requireText(root, "설정 저장").performClick();
            assertEquals("fast", app.getStore().getSetting("requiredSpeed", ""));
            assertEquals("high", app.getStore().getSetting("requiredEffort", ""));
            assertEquals("standard", app.getStore().getSetting("backgroundSpeed", ""));
            assertEquals("low", app.getStore().getSetting("autonomousEffort", ""));
            assertEquals("medium", app.getStore().getSetting("memoryMinimumEffort", ""));
            assertEquals("300", app.getStore().getSetting("heartbeatSeconds", ""));
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void disablingSplitExplicitlyRestoresManualSpeedChoice() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            HoyoungApplication app = (HoyoungApplication) activity.getApplication();
            View root = activity.findViewById(android.R.id.content);
            MainActivityUiTestSupport.navigate(activity, "설정");
            CheckBox optimize = (CheckBox) requireText(root, "판단·기억 처리에 맞춰 실행 옵션 분리");
            optimize.setChecked(false);
            Spinner speed = findUserSpeed(root);
            assertTrue(speed.isEnabled());
            speed.setSelection(1);
            requireText(root, "설정 저장").performClick();
            assertEquals("false", app.getStore().getSetting("usageOptimizationEnabled", ""));
            assertEquals("standard", app.getStore().getSetting("requiredSpeed", ""));
        } finally { controller.pause().stop().destroy(); }
    }

    private static TextView requireText(View view, String text) {
        TextView found = findText(view, text); assertNotNull("Missing UI text: " + text, found); return found;
    }
    private static TextView findText(View view, String text) {
        if (view instanceof TextView label && text.contentEquals(label.getText())) return label;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = findText(group.getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private static Spinner findUserSpeed(View view) {
        if (view instanceof Spinner spinner && spinner.getAdapter() != null && spinner.getAdapter().getCount() == 2
                && "fast".equals(spinner.getAdapter().getItem(0))) return spinner;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Spinner found = findUserSpeed(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
}
