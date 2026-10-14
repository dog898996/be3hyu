package com.hoyoung.assistant.phone;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import java.util.Map;
import static org.junit.Assert.*;

/** Uses fake Android accessibility nodes and a synthetic web handler only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class PhoneUiInputTest {
    private Context context; private ServiceController<HoyoungAccessibilityService> controller; private HoyoungAccessibilityService service; private AccessibilityNodeInfo root; private AccessibilityNodeInfo field;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication(); controller = Robolectric.buildService(HoyoungAccessibilityService.class).create(); service = controller.get();
        root = node(false, ""); field = node(true, "existing text must not be exposed"); Shadows.shadowOf(root).addChild(field); service.setSyntheticRootForTests(root);
    }
    @After public void cleanup() { controller.destroy(); root.recycle(); field.recycle(); }
    private static AccessibilityNodeInfo node(boolean editable, String text) { AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(); node.setPackageName("synthetic.app"); node.setEditable(editable); node.setText(text); node.setEnabled(true); node.setVisibleToUser(true); return node; }
    private String fieldId() throws Exception { return service.snapshot().getJSONArray("nodes").getJSONObject(1).getString("id"); }
    private AccessibilityNodeInfo cached(String id) { Map<String, AccessibilityNodeInfo> nodes = ReflectionHelpers.getField(service, "nodes"); return nodes.get(id); }
    @Test public void explicitTypeUsesAndroidSetTextAndOnlyConfirmsAnActuallyMatchingRefreshedField() throws Exception {
        String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        Shadows.shadowOf(selected).setOnPerformActionListener((action, arguments) -> { if (action != AccessibilityNodeInfo.ACTION_SET_TEXT) return false; selected.setText(arguments.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)); return true; });
        JSONObject result = service.type(id, "supplied user text"); assertTrue(result.toString(), result.getBoolean("ok")); assertTrue(result.getBoolean("completionConfirmed")); assertEquals("text_set", result.getString("code"));
        assertFalse(result.toString().contains("supplied user text")); assertFalse(result.toString().contains("existing text")); assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, (int) Shadows.shadowOf(selected).getPerformedActions().get(0));
        assertEquals("supplied user text", Shadows.shadowOf(selected).getPerformedActionsWithArgs().get(0).second.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString());
    }
    @Test public void anAcceptedActionWithoutMatchingPostStateIsReportedAsUnconfirmed() throws Exception {
        String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true); Shadows.shadowOf(selected).setOnPerformActionListener((action, args) -> true);
        JSONObject result = service.type(id, "new text"); assertTrue(result.getBoolean("accepted")); assertFalse(result.getBoolean("completionConfirmed")); assertEquals("text_requested", result.getString("code"));
    }
    @Test public void staleGenerationAndUnrefreshableNodeNeverPerformATextAction() throws Exception {
        String old = fieldId(); service.snapshot(); assertEquals("stale_target", service.type(old, "text").getString("code"));
        String fresh = fieldId(); AccessibilityNodeInfo selected = cached(fresh); Shadows.shadowOf(selected).setRefreshReturnValue(false);
        assertEquals("stale_target", service.type(fresh, "text").getString("code")); assertTrue(Shadows.shadowOf(selected).getPerformedActions().isEmpty());
    }
    @Test public void noneditableControlsRemainRejectedByActualEditability() throws Exception {
        String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true); selected.setEditable(false);
        assertEquals("not_editable", service.type(id, "text").getString("code")); assertTrue(Shadows.shadowOf(selected).getPerformedActions().isEmpty());
    }
    @Test public void requestedSnapshotIncludesAndroidExposedPasswordTargetContentsAndDescription() throws Exception {
        field.setPassword(true); field.setContentDescription("synthetic old credential description");
        JSONObject snapshot = service.snapshot(), target = snapshot.getJSONArray("nodes").getJSONObject(1);
        assertEquals(2, snapshot.getJSONArray("nodes").length()); assertTrue(target.getBoolean("password")); assertTrue(target.getBoolean("editable"));
        assertEquals("existing text must not be exposed", target.getString("text")); assertEquals("synthetic old credential description", target.getString("description")); assertTrue(target.getString("id").matches("[0-9]+:[0-9]+"));
        field.setEditable(false); JSONObject noneditable = service.snapshot().getJSONArray("nodes").getJSONObject(1);
        assertTrue(noneditable.getBoolean("password")); assertEquals("existing text must not be exposed", noneditable.getString("text")); assertEquals("synthetic old credential description", noneditable.getString("description"));
        field.setText("••••"); assertEquals("••••", service.snapshot().getJSONArray("nodes").getJSONObject(1).getString("text"));
    }
    @Test public void suppliedPasswordActuallyReachesSetTextWithoutEchoingOrClaimingAuthentication() throws Exception {
        field.setPassword(true); String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        String supplied = "synthetic-password-🧪";
        Shadows.shadowOf(selected).setOnPerformActionListener((action, arguments) -> { if (action != AccessibilityNodeInfo.ACTION_SET_TEXT) return false; selected.setText(arguments.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)); return true; });
        JSONObject result = service.type(id, supplied); assertTrue(result.getBoolean("ok")); assertTrue(result.getBoolean("accepted")); assertTrue(result.getBoolean("passwordField"));
        assertFalse(result.getBoolean("completionConfirmed")); assertFalse(result.getBoolean("authenticationVerified")); assertEquals("text_requested", result.getString("code"));
        assertEquals(supplied, Shadows.shadowOf(selected).getPerformedActionsWithArgs().get(0).second.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString());
        assertFalse(result.toString().contains(supplied)); assertFalse(result.toString().contains("existing text"));
    }
    @Test public void rejectedPasswordInputDoesNotClaimAcceptanceOrCompletion() throws Exception {
        field.setPassword(true); String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        Shadows.shadowOf(selected).setOnPerformActionListener((action, arguments) -> false);
        JSONObject result = service.type(id, "synthetic-rejected-password"); assertFalse(result.getBoolean("ok")); assertFalse(result.getBoolean("accepted"));
        assertFalse(result.getBoolean("completionConfirmed")); assertFalse(result.getBoolean("authenticationVerified")); assertEquals("text_rejected", result.getString("code"));
    }
    @Test public void nodeIdClickCanFocusAPasswordTargetWithoutUsingItsHiddenText() throws Exception {
        field.setPassword(true); field.setClickable(true); String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        Shadows.shadowOf(selected).setOnPerformActionListener((action, arguments) -> action == AccessibilityNodeInfo.ACTION_CLICK);
        JSONObject result = service.click(id, ""); assertTrue(result.getBoolean("ok")); assertEquals("click_requested", result.getString("code"));
        assertEquals(AccessibilityNodeInfo.ACTION_CLICK, (int) Shadows.shadowOf(selected).getPerformedActions().get(0)); assertFalse(result.toString().contains("existing text"));
        assertFalse(result.toString().contains("existing text must not be exposed"));
    }
    @Test public void unchangedPasswordTargetRemainsUsableAfterLongCloudAndAuthenticationDelay() throws Exception {
        field.setPassword(true); field.setClickable(true); String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        Shadows.shadowOf(selected).setOnPerformActionListener((action, args) -> action == AccessibilityNodeInfo.ACTION_SET_TEXT || action == AccessibilityNodeInfo.ACTION_CLICK);
        long capturedAt = android.os.SystemClock.elapsedRealtime(); ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
        assertTrue(android.os.SystemClock.elapsedRealtime() - capturedAt > 30000);
        assertTrue(service.type(id, "synthetic-password").getBoolean("accepted"));
        assertEquals("click_requested", service.click(id, "").getString("code"));
        assertEquals(2, Shadows.shadowOf(selected).getPerformedActions().size());
    }

    @Test public void repeatedTypingWithChangedTextUsesSameUnchangedSemanticTargetAcrossLongDelay() throws Exception {
        field.setClassName("android.widget.EditText"); field.setViewIdResourceName("synthetic.app:id/name");
        field.setContentDescription("Account name"); String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        Shadows.shadowOf(selected).setOnPerformActionListener((action, args) -> {
            if (action != AccessibilityNodeInfo.ACTION_SET_TEXT) return false;
            selected.setText(args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)); return true;
        });
        assertTrue(service.type(id, "first value").getBoolean("completionConfirmed"));
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3));
        assertTrue(service.type(id, "corrected value").getBoolean("completionConfirmed"));
        assertEquals("corrected value", selected.getText().toString()); assertEquals(2, Shadows.shadowOf(selected).getPerformedActions().size());
        service.snapshot(); assertEquals("stale_target", service.type(id, "old generation").getString("code"));
    }

    @Test public void classViewDescriptionAndPasswordSemanticChangesRejectOldTargetEvenWhenRefreshSucceeds() throws Exception {
        for (String changed : new String[]{"class", "viewId", "description", "password"}) {
            String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true);
            switch (changed) {
                case "class": selected.setClassName("android.widget.SearchView"); break;
                case "viewId": selected.setViewIdResourceName("synthetic.app:id/different_account"); break;
                case "description": selected.setContentDescription("Different account target"); break;
                default: selected.setPassword(true); break;
            }
            assertEquals(changed, "stale_target", service.type(id, "must not enter").getString("code"));
            assertEquals(changed, "stale_target", service.click(id, "").getString("code"));
            assertTrue(changed, Shadows.shadowOf(selected).getPerformedActions().isEmpty());
        }
    }

    @Test @Config(shadows = RefreshChangingNode.class)
    public void semanticChangeDuringTheActualRefreshIsRejectedBeforePerformAction() throws Exception {
        String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        RefreshChangingNode shadow = Shadow.extract(selected);
        shadow.onRefresh = () -> selected.setContentDescription("Node reused during live refresh");
        assertEquals("stale_target", service.type(id, "must not enter").getString("code"));
        assertTrue(Shadows.shadowOf(selected).getPerformedActions().isEmpty());
    }

    @Test public void changedClickLabelCannotUseTheOldSnapshotMeaning() throws Exception {
        field.setClickable(true); field.setText("Save draft"); String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        selected.setText("Publish draft");
        assertEquals("stale_target", service.click(id, "").getString("code"));
        assertTrue(Shadows.shadowOf(selected).getPerformedActions().isEmpty());
    }

    @Test public void matchingTextAfterActionCannotConfirmAChangedSemanticTarget() throws Exception {
        String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        Shadows.shadowOf(selected).setOnPerformActionListener((action, args) -> {
            selected.setText(args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE));
            selected.setViewIdResourceName("synthetic.app:id/replaced_after_action"); return true;
        });
        JSONObject result = service.type(id, "same-looking value");
        assertTrue(result.getBoolean("accepted")); assertFalse(result.getBoolean("completionConfirmed"));
    }

    @Test public void elapsedTimeDoesNotOverrideDisabledOrHiddenLiveTargetChecks() throws Exception {
        String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2)); selected.setEnabled(false);
        assertEquals("stale_target", service.type(id, "must not enter").getString("code"));
        selected.setEnabled(true); selected.setVisibleToUser(false);
        assertEquals("stale_target", service.click(id, "").getString("code"));
        assertTrue(Shadows.shadowOf(selected).getPerformedActions().isEmpty());
    }

    @Implements(AccessibilityNodeInfo.class)
    public static class RefreshChangingNode extends ShadowAccessibilityNodeInfo {
        Runnable onRefresh;
        @Implementation @Override protected boolean refresh() {
            Runnable change = onRefresh; onRefresh = null; if (change != null) change.run();
            return super.refresh();
        }
    }
    @Test public void systemPackageNamesDoNotCreateExtraAppTypingOrClickProhibitions() throws Exception {
        field.setPassword(true); field.setClickable(true);
        for (String packageName : new String[]{"com.android.settings", "com.samsung.android.settings", "com.android.permissioncontroller", "com.android.packageinstaller"}) {
            root.setPackageName(packageName); field.setPackageName(packageName); String next = fieldId(); AccessibilityNodeInfo selected = cached(next); Shadows.shadowOf(selected).setRefreshReturnValue(true);
            Shadows.shadowOf(selected).setOnPerformActionListener((action, arguments) -> action == AccessibilityNodeInfo.ACTION_SET_TEXT || action == AccessibilityNodeInfo.ACTION_CLICK);
            assertTrue(packageName, service.type(next, "synthetic-password").getBoolean("accepted")); assertEquals(packageName, "click_requested", service.click(next, "").getString("code"));
        }
    }
    @Test public void changedPasswordWindowAndPackageCannotReceiveClicksOrInputFromAnOldSnapshot() throws Exception {
        field.setPassword(true); field.setClickable(true); String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        AccessibilityWindowInfo selectedWindow = AccessibilityWindowInfo.obtain(), activeWindow = AccessibilityWindowInfo.obtain();
        try {
            Shadows.shadowOf(selectedWindow).setId(11); Shadows.shadowOf(activeWindow).setId(12); Shadows.shadowOf(selected).setAccessibilityWindowInfo(selectedWindow); Shadows.shadowOf(root).setAccessibilityWindowInfo(activeWindow);
            assertEquals("stale_target", service.type(id, "synthetic-password").getString("code")); assertEquals("stale_target", service.click(id, "").getString("code")); assertTrue(Shadows.shadowOf(selected).getPerformedActions().isEmpty());
            root.setPackageName("synthetic.other"); assertEquals("stale_target", service.type(id, "synthetic-password").getString("code")); assertEquals("stale_target", service.click(id, "").getString("code"));
        } finally { selectedWindow.recycle(); activeWindow.recycle(); }
    }
    @Test public void appSwitchAndOversizeOrMalformedUnicodeCannotUseAnOldTextTarget() throws Exception {
        String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true); root.setPackageName("synthetic.other");
        assertEquals("stale_target", service.type(id, "text").getString("code")); char[] large = new char[20001]; java.util.Arrays.fill(large, 'x');
        assertEquals("invalid_arguments", service.type(id, new String(large)).getString("code")); assertEquals("invalid_arguments", service.type(id, "unpaired\uD800").getString("code"));
        assertTrue(Shadows.shadowOf(selected).getPerformedActions().isEmpty());
    }
    @Test public void requestedSnapshotsExposeEditableTextAndExactTextClicksRejectAmbiguity() throws Exception {
        JSONObject snapshot = service.snapshot(); assertTrue(snapshot.toString().contains("existing text must not be exposed"));
        AccessibilityNodeInfo first = node(false, "same label"), second = node(false, "same label"); Shadows.shadowOf(root).addChild(first); Shadows.shadowOf(root).addChild(second);
        assertEquals("ambiguous_target", service.click("", "same label").getString("code")); first.recycle(); second.recycle();
    }
    @Test public void typingWithoutActualAccessibilityGrantFailsBeforeAnyServiceAction() throws Exception {
        PhoneTools tools = new PhoneTools(context); JSONObject denied = tools.execute("phone_ui_type", new JSONObject().put("nodeId", fieldId()).put("text", "supplied text"));
        assertEquals("permission_missing", denied.getString("code")); assertEquals("accessibility", denied.getString("permissionId"));
    }
    @Test public void ownAppTypingAndClickAreAllowedByTheSameActualUiRulesAsOtherApps() throws Exception {
        root.setPackageName(context.getPackageName()); field.setPackageName(context.getPackageName()); String id = fieldId(); AccessibilityNodeInfo selected = cached(id); selected.setClickable(true); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        java.util.concurrent.atomic.AtomicInteger requestedActions = new java.util.concurrent.atomic.AtomicInteger(); Shadows.shadowOf(selected).setOnPerformActionListener((action, args) -> { requestedActions.incrementAndGet(); return true; });
        assertTrue(service.type(id, "agent-authored synthetic message").getBoolean("accepted")); assertEquals("click_requested", service.click(id, "").getString("code"));
        service.tap(10, 10); service.swipe(10, 10, 20, 20, 100); assertEquals(2, requestedActions.get()); assertEquals(2, Shadows.shadowOf(service).getGesturesDispatched().size());
        assertFalse(service.snapshot().getJSONArray("nodes").length() == 0);
        root.setPackageName("synthetic.app"); field.setPackageName("synthetic.app"); String external = fieldId(); AccessibilityNodeInfo other = cached(external); Shadows.shadowOf(other).setRefreshReturnValue(true); Shadows.shadowOf(other).setOnPerformActionListener((action, args) -> { other.setText(args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)); return true; });
        assertTrue(service.type(external, "normal other-app text").getBoolean("completionConfirmed"));
    }
    @Test public void urlToolRejectsNonWebSchemesAndMalformedUrlsWithoutLaunchingAnything() throws Exception {
        PhoneTools tools = new PhoneTools(context);
        for (String url : new String[]{"javascript:alert(1)", "file:///private/file", "intent://settings", "https:///missing-host", "https://example.com/contains space", "https://example.com:70000"}) assertEquals(url, "invalid_arguments", tools.execute("phone_url_open", new JSONObject().put("url", url)).getString("code"));
        assertNull(Shadows.shadowOf((Application) context).getNextStartedActivity());
    }
    @Test public void validWebUrlOnlyRequestsAndroidLaunchAndNeverClaimsLoadedOrAuthenticated() throws Exception {
        String url = "https://example.org/synthetic"; Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo resolver = new ResolveInfo(); resolver.activityInfo = new ActivityInfo(); resolver.activityInfo.packageName = "synthetic.browser"; resolver.activityInfo.name = "synthetic.browser.MainActivity"; resolver.activityInfo.applicationInfo = new ApplicationInfo(); resolver.activityInfo.applicationInfo.packageName = "synthetic.browser";
        Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(intent, resolver);
        JSONObject result = new PhoneTools(context).execute("phone_url_open", new JSONObject().put("url", url)); assertTrue(result.toString(), result.getBoolean("ok")); assertTrue(result.getBoolean("requested")); assertFalse(result.getBoolean("completionConfirmed")); assertFalse(result.getBoolean("browserLoaded")); assertFalse(result.getBoolean("authenticationVerified"));
        Intent started = Shadows.shadowOf((Application) context).getNextStartedActivity(); assertNotNull(started); assertEquals(Intent.ACTION_VIEW, started.getAction()); assertEquals(url, started.getDataString());
    }
}
