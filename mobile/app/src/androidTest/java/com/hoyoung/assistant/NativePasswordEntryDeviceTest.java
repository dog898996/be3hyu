package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.Configurator;
import com.hoyoung.assistant.phone.HoyoungAccessibilityService;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.AccountStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real native editable fields and system accessibility Binder, using synthetic text only. */
@RunWith(AndroidJUnit4.class)
public final class NativePasswordEntryDeviceTest {
    private static final String PASSWORD_FIELD = "native synthetic password field";
    private static final String ORDINARY_FIELD = "native synthetic ordinary field";
    private static final String LOCAL_SUBMIT = "native synthetic login submit";

    @Test public void actualAccessibilitySetsPasswordAndOrdinaryTextWithoutClaimingAuthentication() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        DevicePermissionFixture.assertReady(instrumentation.getTargetContext());
        Configurator configuration = Configurator.getInstance();
        int priorFlags = configuration.getUiAutomationFlags();
        int fixtureFlags = priorFlags | UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES;
        // Default UiAutomation suppresses enabled services. Preserve the real OS
        // connection rather than replacing it with a synthetic AccessibilityNodeInfo.
        configuration.setUiAutomationFlags(fixtureFlags);
        instrumentation.getUiAutomation(fixtureFlags);
        EditText[] password = {null}, ordinary = {null}; TextView[] localState = {null};
        boolean[] localAuthenticated = {false};
        int[] localSubmissions = {0};
        String syntheticPassword = "Fixture_Only_7!한글", syntheticUsername = "local_fixture_owner@example.invalid";
        AccountStore accounts = new AccountStore(instrumentation.getTargetContext(),
                ((HoyoungApplication) instrumentation.getTargetContext().getApplicationContext()).getStore());
        String accountId = null;
        LinearLayout[] fixture = {null};
        ActivityScenario<MainActivity> scenario = null;
        try {
            long connectionDeadline = SystemClock.elapsedRealtime() + 15_000;
            while (HoyoungAccessibilityService.getInstance() == null && SystemClock.elapsedRealtime() < connectionDeadline) SystemClock.sleep(50);
            assertNotNull("The actual user-enabled accessibility service must be connected", HoyoungAccessibilityService.getInstance());
            JSONObject supplied = new JSONObject().put("service", "native offline login fixture").put("username", syntheticUsername)
                    .put("password", "Older_Fixture_Only_7!");
            JSONObject first = accounts.saveFromUi(supplied, "owner"); accountId = first.getString("accountId");
            accounts.saveFromUi(new JSONObject(supplied.toString()).put("accountId", accountId).put("password", syntheticPassword), "owner");
            JSONObject latest = accounts.get(accountId); assertEquals(2, latest.getInt("revision"));
            assertEquals(syntheticPassword, latest.getString("password"));
            scenario = ActivityScenario.launch(MainActivity.class);
            scenario.onActivity(activity -> {
                fixture[0] = new LinearLayout(activity); fixture[0].setOrientation(LinearLayout.VERTICAL);
                fixture[0].setPadding(24, 24, 24, 24); fixture[0].setBackgroundColor(Color.WHITE);
                ordinary[0] = new EditText(activity); ordinary[0].setSingleLine(true);
                ordinary[0].setInputType(InputType.TYPE_CLASS_TEXT); ordinary[0].setContentDescription(ORDINARY_FIELD);
                password[0] = new EditText(activity); password[0].setSingleLine(true);
                password[0].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                password[0].setContentDescription(PASSWORD_FIELD);
                fixture[0].addView(ordinary[0], new LinearLayout.LayoutParams(-1, 120));
                fixture[0].addView(password[0], new LinearLayout.LayoutParams(-1, 120));
                Button localLogin = new Button(activity); localLogin.setText("Offline fixture sign in"); localLogin.setContentDescription(LOCAL_SUBMIT);
                localState[0] = new TextView(activity); localState[0].setText("local fixture unauthenticated");
                localLogin.setOnClickListener(view -> {
                    localSubmissions[0]++;
                    localAuthenticated[0] = syntheticUsername.contentEquals(ordinary[0].getText()) && syntheticPassword.contentEquals(password[0].getText());
                    localState[0].setText(localAuthenticated[0] ? "local fixture authenticated" : "local fixture unauthenticated");
                });
                fixture[0].addView(localLogin, new LinearLayout.LayoutParams(-1, 100));
                fixture[0].addView(localState[0], new LinearLayout.LayoutParams(-1, 80));
                FrameLayout shell = (FrameLayout) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
                shell.addView(fixture[0], new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            });
            PhoneTools tools = new PhoneTools(instrumentation.getTargetContext());
            JSONObject snapshot = awaitSnapshot(tools);
            JSONObject passwordNode = node(snapshot, PASSWORD_FIELD), ordinaryNode = node(snapshot, ORDINARY_FIELD);
            assertNotNull(passwordNode); assertNotNull(ordinaryNode);
            assertTrue("The real framework must classify the native password editor", passwordNode.getBoolean("password"));
            assertTrue(passwordNode.getBoolean("editable")); assertTrue(passwordNode.getBoolean("visible"));
            assertFalse(ordinaryNode.getBoolean("password"));
            assertEquals("The native fixture intentionally exercises owner-permitted app UI", "com.hoyoung.assistant", passwordNode.getString("package"));

            JSONObject passwordResult = tools.execute("phone_ui_type", new JSONObject().put("nodeId", passwordNode.getString("id")).put("text", syntheticPassword));
            assertTrue("Actual Android ACTION_SET_TEXT must accept the password node: " + passwordResult, passwordResult.getBoolean("ok"));
            assertTrue(passwordResult.getBoolean("accepted")); assertTrue(passwordResult.getBoolean("passwordField"));
            assertEquals("text_requested", passwordResult.getString("code"));
            assertFalse("Password acceptance alone is not content confirmation", passwordResult.getBoolean("completionConfirmed"));
            assertFalse("A native input fixture cannot prove website login", passwordResult.getBoolean("authenticationVerified"));
            assertFalse("The action receipt must not echo the supplied password", passwordResult.toString().contains(syntheticPassword));
            awaitValue(scenario, password[0], syntheticPassword);

            String syntheticOrdinary = "native ordinary input 한글";
            JSONObject ordinaryResult = tools.execute("phone_ui_type", new JSONObject().put("nodeId", ordinaryNode.getString("id")).put("text", syntheticOrdinary));
            assertTrue(ordinaryResult.getBoolean("ok")); assertFalse(ordinaryResult.getBoolean("passwordField"));
            assertTrue("Ordinary editable text can be checked after the real action", ordinaryResult.getBoolean("completionConfirmed"));
            assertEquals("text_set", ordinaryResult.getString("code")); awaitValue(scenario, ordinary[0], syntheticOrdinary);

            JSONObject fresh = tools.execute("phone_ui_snapshot", new JSONObject()); assertTrue(fresh.getBoolean("ok"));
            JSONObject stale = tools.execute("phone_ui_type", new JSONObject().put("nodeId", passwordNode.getString("id")).put("text", "stale fixture must not write"));
            assertFalse(stale.getBoolean("ok")); assertEquals("stale_target", stale.getString("code"));
            awaitValue(scenario, password[0], syntheticPassword);
            JSONObject refreshedPassword = node(fresh, PASSWORD_FIELD); assertNotNull(refreshedPassword);
            JSONObject cleared = tools.execute("phone_ui_type", new JSONObject().put("nodeId", refreshedPassword.getString("id")).put("text", ""));
            assertTrue(cleared.getBoolean("ok")); assertTrue(cleared.getBoolean("passwordField")); awaitValue(scenario, password[0], "");

            // The native mock rejects the superseded value and accepts only the
            // latest exact account record. It has no network or real site identity.
            JSONObject loginSnapshot = awaitSnapshot(tools);
            JSONObject loginPassword = node(loginSnapshot, PASSWORD_FIELD), loginUsername = node(loginSnapshot, ORDINARY_FIELD), loginButton = node(loginSnapshot, LOCAL_SUBMIT);
            assertNotNull(loginPassword); assertNotNull(loginUsername); assertNotNull(loginButton);
            assertTrue(tools.execute("phone_ui_type", new JSONObject().put("nodeId", loginUsername.getString("id")).put("text", latest.getString("username"))).getBoolean("ok"));
            assertTrue(tools.execute("phone_ui_type", new JSONObject().put("nodeId", loginPassword.getString("id")).put("text", "Older_Fixture_Only_7!")).getBoolean("ok"));
            awaitValue(scenario, ordinary[0], latest.getString("username")); awaitValue(scenario, password[0], "Older_Fixture_Only_7!");
            assertTrue(tools.execute("phone_ui_click", new JSONObject().put("nodeId", loginButton.getString("id"))).getBoolean("ok"));
            awaitSubmission(scenario, localSubmissions, 1);
            awaitLocalState(scenario, localState[0], "local fixture unauthenticated"); assertFalse(localAuthenticated[0]);
            JSONObject latestEntry = tools.execute("phone_ui_type", new JSONObject().put("nodeId", loginPassword.getString("id")).put("text", latest.getString("password")));
            assertTrue(latestEntry.getBoolean("ok")); assertFalse(latestEntry.getBoolean("authenticationVerified"));
            awaitValue(scenario, password[0], latest.getString("password"));
            assertTrue(tools.execute("phone_ui_click", new JSONObject().put("nodeId", loginButton.getString("id"))).getBoolean("ok"));
            awaitSubmission(scenario, localSubmissions, 2);
            awaitLocalState(scenario, localState[0], "local fixture authenticated"); assertTrue(localAuthenticated[0]);
            assertFalse("Local mock success must not mark the external account logged in", accounts.get(accountId).getBoolean("loginVerified"));
            String marker = "HOYOUNG_DEVICE_PASSWORD_ENTRY actual-native-password-EditText; actual-enabled-service-active-window; Binder-ACTION_SET_TEXT-and-CLICK; ordinary-confirmed; password-accepted-not-external-authentication; stale-node-rejected; latest-account-correction-replayed; old-password-mock-rejected; local-synthetic-state-verified; no-real-account-or-credential";
            android.util.Log.i("HoyoungDeviceProof", marker);
            Bundle proof = new Bundle(); proof.putString("stream", marker + "\n"); instrumentation.sendStatus(0, proof);
        } finally {
            try {
                if (scenario != null) scenario.close();
                if (accountId != null) accounts.delete(accountId, "owner");
            } finally {
                configuration.setUiAutomationFlags(priorFlags);
                instrumentation.getUiAutomation(priorFlags);
            }
        }
    }

    private static JSONObject awaitSnapshot(PhoneTools tools) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000; JSONObject snapshot;
        do {
            snapshot = tools.execute("phone_ui_snapshot", new JSONObject());
            if (snapshot.optBoolean("ok") && node(snapshot, PASSWORD_FIELD) != null && node(snapshot, ORDINARY_FIELD) != null) return snapshot;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("The actual active-window accessibility tree must contain both native fixture editors: " + snapshot.optString("code"));
    }
    private static JSONObject node(JSONObject snapshot, String description) {
        JSONArray nodes = snapshot.optJSONArray("nodes"); if (nodes == null) return null;
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject candidate = nodes.optJSONObject(index);
            if (candidate != null && description.equals(candidate.optString("description"))) return candidate;
        }
        return null;
    }
    private static void awaitValue(ActivityScenario<MainActivity> scenario, EditText editor, String expected) {
        long deadline = SystemClock.elapsedRealtime() + 5_000; boolean[] equal = {false};
        do {
            scenario.onActivity(activity -> equal[0] = expected.contentEquals(editor.getText()));
            if (equal[0]) return; SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("The actual native field must receive the synthetic action's exact text", equal[0]);
    }
    private static void awaitLocalState(ActivityScenario<MainActivity> scenario, TextView state, String expected) {
        long deadline = SystemClock.elapsedRealtime() + 5_000; boolean[] equal = {false};
        do {
            scenario.onActivity(activity -> equal[0] = expected.contentEquals(state.getText()));
            if (equal[0]) return; SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("The controlled native login state must match the actual click result", equal[0]);
    }
    private static void awaitSubmission(ActivityScenario<MainActivity> scenario, int[] submissions, int expected) {
        long deadline = SystemClock.elapsedRealtime() + 5_000; boolean[] equal = {false};
        do {
            scenario.onActivity(activity -> equal[0] = submissions[0] == expected);
            if (equal[0]) return; SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("Actual native OnClick delivery must occur once per accepted Binder click", equal[0]);
    }
}
