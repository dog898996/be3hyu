package com.hoyoung.assistant.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.hoyoung.assistant.runtime.UiAutomationProvenance;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** User-enabled, on-demand UI tools. Android grants and app tool switches define access. */
public final class HoyoungAccessibilityService extends AccessibilityService {
    private static volatile HoyoungAccessibilityService instance;
    private final Map<String, AccessibilityNodeInfo> nodes = new LinkedHashMap<>();
    private final Map<String, NodeIdentity> identities = new LinkedHashMap<>();
    private long generation;
    private AccessibilityNodeInfo syntheticRootForTests;

    public static HoyoungAccessibilityService getInstance() { return instance; }
    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* Do not retain input or event text. */ }
    @Override public void onInterrupt() { clearNodes(); PhoneScreenCapture.onServiceUnavailable(this, "accessibility_service_interrupted"); }
    @Override public void onDestroy() { if (instance == this) instance = null; clearNodes(); PhoneScreenCapture.onServiceUnavailable(this, "accessibility_service_disconnected"); super.onDestroy(); }

    /** The caller owns the copied software bitmap. Hardware buffers/wrappers are
     * always released here, including late callbacks after cancellation. */
    void requestScreenCapture(int displayId, Executor worker, PhoneScreenCapture.FrameCallback callback) {
        if (Build.VERSION.SDK_INT < 30) throw new UnsupportedOperationException("Android screenshot capture needs API30");
        takeScreenshot(displayId, worker, new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult screenshot) {
                if (screenshot == null) { callback.localFailure("capture_empty_platform_result"); return; }
                HardwareBuffer buffer = screenshot.getHardwareBuffer(); Bitmap wrapper = null, copy = null;
                try {
                    if (buffer == null || (long) buffer.getWidth() * buffer.getHeight() > PhoneScreenCapture.MAX_PIXELS) {
                        callback.localFailure(buffer == null ? "capture_empty_hardware_buffer" : "capture_dimensions_unsupported"); return;
                    }
                    wrapper = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                    if (wrapper != null) copy = wrapper.copy(Bitmap.Config.ARGB_8888, false);
                } catch (RuntimeException invalid) { /* Report Android capture failure below without manufacturing a black image. */ }
                finally { if (wrapper != null && !wrapper.isRecycled()) wrapper.recycle(); if (buffer != null) buffer.close(); }
                if (copy == null) callback.localFailure("capture_bitmap_conversion_failed");
                else callback.success(copy, screenshot.getTimestamp());
            }
            @Override public void onFailure(int errorCode) { callback.failure(errorCode); }
        });
    }

    public synchronized JSONObject snapshot() {
        clearNodes(); generation++;
        JSONArray list = new JSONArray();
        AccessibilityNodeInfo root = activeRoot();
        if (root == null) return result(false, "no_active_window", "The device may be locked or the current app may protect its UI.");
        try {
            walk(root, list, 0);
            return result(true, "ok", "").put("generation", generation).put("nodes", list).put("truncated", nodes.size() >= 300);
        } catch (JSONException impossible) { return result(false, "internal_error", "Unable to build UI snapshot."); }
        finally { root.recycle(); }
    }

    private void walk(AccessibilityNodeInfo node, JSONArray list, int depth) throws JSONException {
        if (node == null || depth > 16 || nodes.size() >= 300) return;
        String id = generation + ":" + nodes.size();
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        boolean editable = node.isEditable(), password = node.isPassword();
        JSONObject item = new JSONObject().put("id", id).put("class", string(node.getClassName(), 160))
            .put("package", string(node.getPackageName(), 160)).put("viewId", node.getViewIdResourceName())
            .put("text", string(node.getText(), 1000))
            .put("description", string(node.getContentDescription(), 500))
            .put("editable", editable).put("password", password).put("clickable", node.isClickable()).put("enabled", node.isEnabled())
            .put("visible", node.isVisibleToUser()).put("bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom));
        nodes.put(id, AccessibilityNodeInfo.obtain(node)); identities.put(id, new NodeIdentity(node)); list.put(item);
        for (int i = 0; i < node.getChildCount() && nodes.size() < 300; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { try { walk(child, list, depth + 1); } finally { child.recycle(); } }
        }
    }

    /** Node IDs refer to the latest snapshot. Exact text selection rejects ambiguous matches. */
    public synchronized JSONObject click(String nodeId, String exactText) {
        AccessibilityNodeInfo selected = null;
        if (nodeId != null && !nodeId.isEmpty()) {
            if (!currentNodeId(nodeId)) return result(false, "stale_target", "Take a fresh UI snapshot before selecting a node.");
            selected = nodes.get(nodeId);
        }
        else if (exactText != null && !exactText.isEmpty()) {
            JSONObject snapshot = snapshot();
            if (!snapshot.optBoolean("ok")) return snapshot;
            for (Map.Entry<String, AccessibilityNodeInfo> entry : nodes.entrySet()) {
                AccessibilityNodeInfo candidate = entry.getValue();
                if (exactText.equals(string(candidate.getText(), 1000)) || exactText.equals(string(candidate.getContentDescription(), 500))) {
                    if (selected != null) return result(false, "ambiguous_target", "Use a nodeId from a fresh UI snapshot.");
                    selected = candidate; nodeId = entry.getKey();
                }
            }
        }
        if (selected == null) return result(false, "target_not_found", "Take a new snapshot and select an existing node.");
        AccessibilityNodeInfo active = activeRoot();
        if (active == null) return result(false, "no_active_window", "The current app may protect its UI.");
        AccessibilityNodeInfo target = null;
        try {
            NodeIdentity expected = identities.get(nodeId), beforeRefresh = new NodeIdentity(selected);
            if (!selected.refresh() || !selected.isVisibleToUser() || !selected.isEnabled() || !sameActiveWindow(selected, active)
                    || expected == null || !expected.matches(selected, true) || !beforeRefresh.matches(selected, true))
                return result(false, "stale_target", "The active UI or target meaning changed. Take a new snapshot.");
            target = AccessibilityNodeInfo.obtain(selected);
            for (int depth = 0; depth < 5 && target != null; depth++) {
                if (depth > 0) {
                    NodeIdentity parentBefore = new NodeIdentity(target);
                    if (!target.refresh() || !parentBefore.matches(target, true) || !sameActiveWindow(target, active))
                        return result(false, "stale_target", "The clickable parent changed. Take a new snapshot.");
                }
                if (target.isClickable() && target.isEnabled() && target.isVisibleToUser()) {
                    UiAutomationProvenance.record(this, AccessibilityNodeInfo.ACTION_CLICK, ownAppPackage(target.getPackageName()), null);
                    boolean accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return result(accepted, accepted ? "click_requested" : "click_rejected", "Android reports whether the click action was accepted.");
                }
                AccessibilityNodeInfo parent = target.getParent(); target.recycle(); target = parent;
            }
            return result(false, "not_clickable", "No clickable target or nearby parent was found.");
        } finally { if (target != null) target.recycle(); active.recycle(); }
    }

    /** Explicit supplied text is sent only to a fresh target. Results do not echo inputs. */
    public synchronized JSONObject type(String nodeId, String text) {
        if (nodeId == null || nodeId.length() > 200 || !nodeId.matches("[0-9]+:[0-9]+") || text == null || text.length() > 20000 || !validUtf16(text)) return result(false, "invalid_arguments", "Use a fresh nodeId and at most20000 UTF-16 units of supplied text.");
        if (!currentNodeId(nodeId)) return result(false, "stale_target", "Take a fresh UI snapshot before entering text.");
        AccessibilityNodeInfo selected = nodes.get(nodeId); if (selected == null) return result(false, "target_not_found", "Select an editable node from the latest snapshot.");
        AccessibilityNodeInfo active = activeRoot(); if (active == null) return result(false, "no_active_window", "The current app may protect its UI.");
        try {
            NodeIdentity expected = identities.get(nodeId), beforeRefresh = new NodeIdentity(selected);
            if (!selected.refresh() || !selected.isVisibleToUser() || !selected.isEnabled()) return result(false, "stale_target", "The UI changed. Take a new snapshot.");
            if (!selected.isEditable()) return result(false, "not_editable", "This node does not accept editable text.");
            if (!sameActiveWindow(selected, active) || expected == null || !expected.matches(selected, false) || !beforeRefresh.matches(selected, false))
                return result(false, "stale_target", "The active app, window or editable target meaning differs from the snapshot.");
            boolean passwordField = selected.isPassword();
            Bundle arguments = new Bundle(); arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            UiAutomationProvenance.record(this, AccessibilityNodeInfo.ACTION_SET_TEXT, ownAppPackage(selected.getPackageName()), text);
            boolean accepted = selected.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            // Password nodes expose masked or implementation-specific values. An accepted
            // ACTION_SET_TEXT neither proves their contents nor a successful site login.
            boolean confirmed = accepted && !passwordField && confirmsTypedState(selected, expected, text);
            try { return result(accepted, accepted ? confirmed ? "text_set" : "text_requested" : "text_rejected", "Input contents are not returned. Accepted password entry is not verified input or login completion.").put("accepted", accepted).put("completionConfirmed", confirmed).put("passwordField", passwordField).put("authenticationVerified", false).put("suppliedUtf16Units", text.length()); }
            catch (JSONException impossible) { return result(false, "internal_error", "The text action result could not be represented."); }
        } finally { active.recycle(); }
    }

    public JSONObject global(String action) {
        int code;
        switch (action == null ? "" : action) {
            case "home": code = GLOBAL_ACTION_HOME; break;
            case "back": code = GLOBAL_ACTION_BACK; break;
            case "recents": code = GLOBAL_ACTION_RECENTS; break;
            case "notifications": code = GLOBAL_ACTION_NOTIFICATIONS; break;
            default: return result(false, "invalid_arguments", "Supported navigation: home, back, recents, notifications.");
        }
        boolean accepted = performGlobalAction(code);
        return result(accepted, accepted ? "navigation_requested" : "navigation_rejected", "Android reports whether navigation was accepted.");
    }

    public JSONObject tap(float x, float y) { return gesture(x, y, x, y, 70); }
    public JSONObject swipe(float x1, float y1, float x2, float y2, long durationMs) { return gesture(x1, y1, x2, y2, durationMs); }
    private JSONObject gesture(float x1, float y1, float x2, float y2, long duration) {
        AccessibilityNodeInfo active = activeRoot();
        if (active == null) return result(false, "no_active_window", "No active UI is available for a gesture.");
        boolean ownApp = ownAppPackage(active.getPackageName());
        active.recycle();
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (!finite(x1) || !finite(y1) || !finite(x2) || !finite(y2) || x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0 || x1 >= width || x2 >= width || y1 >= height || y2 >= height || duration < 40 || duration > 1500) return result(false, "invalid_arguments", "Coordinates must be on-screen; gesture duration must be 40–1500 ms.");
        Path path = new Path(); path.moveTo(x1, y1); if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        UiAutomationProvenance.record(this, 0, ownApp, null);
        boolean accepted = dispatchGesture(gesture, null, new Handler(Looper.getMainLooper()));
        return result(accepted, accepted ? "gesture_accepted" : "gesture_rejected", "Accepted means scheduled by Android; completion is not yet confirmed.");
    }

    private synchronized void clearNodes() { for (AccessibilityNodeInfo node : nodes.values()) node.recycle(); nodes.clear(); identities.clear(); }
    private boolean currentNodeId(String nodeId) {
        // Model round trips and owner authentication may take minutes. Time alone
        // does not invalidate a target that still passes current live checks.
        return nodeId != null && nodeId.length() <= 200 && nodeId.matches("[0-9]+:[0-9]+") && nodeId.startsWith(generation + ":");
    }
    private boolean confirmsTypedState(AccessibilityNodeInfo selected, NodeIdentity expected, String text) {
        if (!selected.refresh() || !selected.isVisibleToUser() || !selected.isEnabled() || !selected.isEditable()
                || selected.isPassword() || !expected.matches(selected, false)) return false;
        AccessibilityNodeInfo current = activeRoot(); if (current == null) return false;
        try { return sameActiveWindow(selected, current) && text.contentEquals(selected.getText() == null ? "" : selected.getText()); }
        finally { current.recycle(); }
    }
    /** Immutable snapshot identity. Editable text can change through legitimate
     * repeated typing; click labels must still mean what the snapshot selected. */
    private static final class NodeIdentity {
        final String packageName, className, viewId, description, text;
        final boolean password, editable;
        final int windowId;
        NodeIdentity(AccessibilityNodeInfo node) {
            packageName = exact(node.getPackageName()); className = exact(node.getClassName());
            viewId = exact(node.getViewIdResourceName()); description = exact(node.getContentDescription()); text = exact(node.getText());
            password = node.isPassword(); editable = node.isEditable(); windowId = node.getWindowId();
        }
        boolean matches(AccessibilityNodeInfo node, boolean compareText) {
            return packageName.equals(exact(node.getPackageName())) && className.equals(exact(node.getClassName()))
                    && viewId.equals(exact(node.getViewIdResourceName())) && description.equals(exact(node.getContentDescription()))
                    && password == node.isPassword() && editable == node.isEditable()
                    && (windowId < 0 || node.getWindowId() < 0 || windowId == node.getWindowId())
                    && (!compareText || text.equals(exact(node.getText())));
        }
        private static String exact(CharSequence value) { return value == null ? "" : value.toString(); }
    }
    private static boolean sameActiveWindow(AccessibilityNodeInfo selected, AccessibilityNodeInfo active) {
        String selectedPackage = selected.getPackageName() == null ? "" : selected.getPackageName().toString();
        String activePackage = active.getPackageName() == null ? "" : active.getPackageName().toString();
        return !selectedPackage.isEmpty() && selectedPackage.equals(activePackage)
                && (selected.getWindowId() < 0 || active.getWindowId() < 0 || selected.getWindowId() == active.getWindowId());
    }
    private AccessibilityNodeInfo activeRoot() { return syntheticRootForTests == null ? getRootInActiveWindow() : AccessibilityNodeInfo.obtain(syntheticRootForTests); }
    /** Package-private synthetic Android node seam; never reachable from an application tool. */
    void setSyntheticRootForTests(AccessibilityNodeInfo root) { syntheticRootForTests = root; }
    private boolean ownAppPackage(CharSequence name) { return name != null && getPackageName().contentEquals(name); }
    private static boolean validUtf16(String value) { for (int i = 0; i < value.length(); i++) { char character = value.charAt(i); if (Character.isHighSurrogate(character)) { if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false; } else if (Character.isLowSurrogate(character)) return false; } return true; }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private static String string(CharSequence text, int max) { if (text == null) return ""; String value = text.toString(); return value.length() > max ? value.substring(0, max) : value; }
    private static JSONObject result(boolean ok, String code, String message) { try { return new JSONObject().put("ok", ok).put("code", code).put("message", message); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
}
