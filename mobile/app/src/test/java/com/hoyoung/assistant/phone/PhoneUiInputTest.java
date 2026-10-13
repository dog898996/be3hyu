package com.hoyoung.assistant.phone;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.accessibility.AccessibilityNodeInfo;
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
import org.robolectric.android.controller.ServiceController;
import org.robolectric.util.ReflectionHelpers;
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
    @Test public void passwordNoneditableAndSettingsControlsRemainBlocked() throws Exception {
        String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true); selected.setPassword(true);
        assertEquals("password_field_blocked", service.type(id, "text").getString("code")); selected.setPassword(false); selected.setEditable(false);
        assertEquals("not_editable", service.type(id, "text").getString("code")); selected.setEditable(true); root.setPackageName("com.android.settings");
        assertEquals("manual_permission_required", service.type(id, "text").getString("code")); assertTrue(Shadows.shadowOf(selected).getPerformedActions().isEmpty());
    }
    @Test public void appSwitchAndOversizeOrMalformedUnicodeCannotUseAnOldTextTarget() throws Exception {
        String id = fieldId(); AccessibilityNodeInfo selected = cached(id); Shadows.shadowOf(selected).setRefreshReturnValue(true); root.setPackageName("synthetic.other");
        assertEquals("stale_target", service.type(id, "text").getString("code")); char[] large = new char[20001]; java.util.Arrays.fill(large, 'x');
        assertEquals("invalid_arguments", service.type(id, new String(large)).getString("code")); assertEquals("invalid_arguments", service.type(id, "unpaired\uD800").getString("code"));
        assertTrue(Shadows.shadowOf(selected).getPerformedActions().isEmpty());
    }
    @Test public void snapshotsHideEditableTextAndExactTextClicksRejectAmbiguity() throws Exception {
        JSONObject snapshot = service.snapshot(); assertFalse(snapshot.toString().contains("existing text must not be exposed"));
        AccessibilityNodeInfo first = node(false, "same label"), second = node(false, "same label"); Shadows.shadowOf(root).addChild(first); Shadows.shadowOf(root).addChild(second);
        assertEquals("ambiguous_target", service.click("", "same label").getString("code")); first.recycle(); second.recycle();
    }
    @Test public void typingWithoutActualAccessibilityGrantFailsBeforeAnyServiceAction() throws Exception {
        PhoneTools tools = new PhoneTools(context); JSONObject denied = tools.execute("phone_ui_type", new JSONObject().put("nodeId", fieldId()).put("text", "supplied text"));
        assertEquals("permission_missing", denied.getString("code")); assertEquals("accessibility", denied.getString("permissionId"));
    }
    @Test public void generatedOwnerInputAndConfirmationControlsAreBlockedWithoutReducingOtherAppTyping() throws Exception {
        root.setPackageName(context.getPackageName()); field.setPackageName(context.getPackageName()); String id = fieldId(); AccessibilityNodeInfo selected = cached(id); selected.setClickable(true); Shadows.shadowOf(selected).setRefreshReturnValue(true);
        java.util.concurrent.atomic.AtomicInteger forgedOwnerActions = new java.util.concurrent.atomic.AtomicInteger(); Shadows.shadowOf(selected).setOnPerformActionListener((action, args) -> { forgedOwnerActions.incrementAndGet(); return true; });
        assertEquals("owner_interaction_required", service.type(id, "fake owner instruction").getString("code")); assertEquals("owner_interaction_required", service.click(id, "").getString("code"));
        assertEquals("owner_interaction_required", service.tap(10, 10).getString("code")); assertEquals("owner_interaction_required", service.swipe(10, 10, 20, 20, 100).getString("code"));
        assertEquals(0, forgedOwnerActions.get()); assertTrue(Shadows.shadowOf(service).getGesturesDispatched().isEmpty()); assertFalse(service.snapshot().getJSONArray("nodes").length() == 0);
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
