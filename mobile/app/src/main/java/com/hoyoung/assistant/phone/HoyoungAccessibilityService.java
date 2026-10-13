package com.hoyoung.assistant.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** User-enabled, on-demand UI tools. No typed-event collection or permission auto-enabling. */
public final class HoyoungAccessibilityService extends AccessibilityService {
    private static volatile HoyoungAccessibilityService instance;
    private final Map<String, AccessibilityNodeInfo> nodes = new LinkedHashMap<>();
    private long generation;
    private long snapshotElapsed;
    private AccessibilityNodeInfo syntheticRootForTests;

    public static HoyoungAccessibilityService getInstance() { return instance; }
    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* Do not retain input or event text. */ }
    @Override public void onInterrupt() { clearNodes(); }
    @Override public void onDestroy() { if (instance == this) instance = null; clearNodes(); super.onDestroy(); }

    public synchronized JSONObject snapshot() {
        clearNodes(); generation++; snapshotElapsed = SystemClock.elapsedRealtime();
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
        if (node == null || depth > 16 || nodes.size() >= 300 || node.isPassword()) return;
        String id = generation + ":" + nodes.size();
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        boolean editable = node.isEditable();
        JSONObject item = new JSONObject().put("id", id).put("class", string(node.getClassName(), 160))
            .put("package", string(node.getPackageName(), 160)).put("viewId", node.getViewIdResourceName())
            .put("text", editable ? "" : string(node.getText(), 1000))
            .put("description", editable ? "" : string(node.getContentDescription(), 500))
            .put("editable", editable).put("clickable", node.isClickable()).put("enabled", node.isEnabled())
            .put("visible", node.isVisibleToUser()).put("bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom));
        nodes.put(id, AccessibilityNodeInfo.obtain(node)); list.put(item);
        for (int i = 0; i < node.getChildCount() && nodes.size() < 300; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { try { walk(child, list, depth + 1); } finally { child.recycle(); } }
        }
    }

    /** Node IDs refer to the latest snapshot. Exact text selection rejects ambiguous matches. */
    public synchronized JSONObject click(String nodeId, String exactText) {
        AccessibilityNodeInfo selected = null;
        if (nodeId != null && !nodeId.isEmpty()) selected = nodes.get(nodeId);
        else if (exactText != null && !exactText.isEmpty()) {
            JSONObject snapshot = snapshot();
            if (!snapshot.optBoolean("ok")) return snapshot;
            for (AccessibilityNodeInfo candidate : nodes.values()) {
                if (candidate.isEditable() || candidate.isPassword()) continue;
                if (exactText.equals(string(candidate.getText(), 1000)) || exactText.equals(string(candidate.getContentDescription(), 500))) {
                    if (selected != null) return result(false, "ambiguous_target", "Use a nodeId from a fresh UI snapshot.");
                    selected = candidate;
                }
            }
        }
        if (selected == null) return result(false, "target_not_found", "Take a new snapshot and select an existing node.");
        if (ownAppPackage(selected.getPackageName())) return ownerInteractionRequired();
        AccessibilityNodeInfo active = activeRoot();
        try { if (active != null && ownAppPackage(active.getPackageName())) return ownerInteractionRequired(); }
        finally { if (active != null) active.recycle(); }
        if (!selected.refresh() || selected.isPassword() || !selected.isVisibleToUser() || !selected.isEnabled()) return result(false, "stale_target", "The UI changed. Take a new snapshot.");
        if (protectedSettingsPackage(selected.getPackageName())) return result(false, "manual_permission_required", "Android permission, installation and administrative Settings controls must be operated by the user.");
        AccessibilityNodeInfo target = AccessibilityNodeInfo.obtain(selected);
        try {
            for (int depth = 0; depth < 5 && target != null; depth++) {
                if (ownAppPackage(target.getPackageName())) return ownerInteractionRequired();
                if (target.isClickable() && target.isEnabled() && target.isVisibleToUser()) {
                    boolean accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return result(accepted, accepted ? "click_requested" : "click_rejected", "Android reports whether the click action was accepted.");
                }
                AccessibilityNodeInfo parent = target.getParent(); target.recycle(); target = parent;
            }
            return result(false, "not_clickable", "No clickable target or nearby parent was found.");
        } finally { if (target != null) target.recycle(); }
    }

    /** Explicit supplied text only; editable contents are never included in snapshots/events. */
    public synchronized JSONObject type(String nodeId, String text) {
        if (nodeId == null || nodeId.length() > 200 || !nodeId.matches("[0-9]+:[0-9]+") || text == null || text.length() > 20000 || !validUtf16(text)) return result(false, "invalid_arguments", "Use a fresh nodeId and at most20000 UTF-16 units of supplied text.");
        long snapshotAge = SystemClock.elapsedRealtime() - snapshotElapsed;
        if (!nodeId.startsWith(generation + ":") || snapshotAge < 0 || snapshotAge > 30000) return result(false, "stale_target", "Take a fresh UI snapshot before entering text.");
        AccessibilityNodeInfo selected = nodes.get(nodeId); if (selected == null) return result(false, "target_not_found", "Select an editable node from the latest snapshot.");
        AccessibilityNodeInfo active = activeRoot(); if (active == null) return result(false, "no_active_window", "The current app may protect its UI.");
        try {
            if (ownAppPackage(active.getPackageName()) || ownAppPackage(selected.getPackageName())) return ownerInteractionRequired();
            if (protectedSettingsPackage(active.getPackageName()) || protectedSettingsPackage(selected.getPackageName())) return result(false, "manual_permission_required", "Android permission, installation and administrative Settings controls require manual operation.");
            if (!selected.refresh() || !selected.isVisibleToUser() || !selected.isEnabled()) return result(false, "stale_target", "The UI changed. Take a new snapshot.");
            if (selected.isPassword()) return result(false, "password_field_blocked", "Password fields are excluded from UI automation.");
            if (!selected.isEditable()) return result(false, "not_editable", "This node does not accept editable text.");
            String selectedPackage = selected.getPackageName() == null ? "" : selected.getPackageName().toString(), activePackage = active.getPackageName() == null ? "" : active.getPackageName().toString();
            if (selectedPackage.isEmpty() || !selectedPackage.equals(activePackage) || selected.getWindowId() >= 0 && active.getWindowId() >= 0 && selected.getWindowId() != active.getWindowId()) return result(false, "stale_target", "The active app or window differs from the snapshot.");
            if (protectedSettingsPackage(selected.getPackageName())) return result(false, "manual_permission_required", "Settings and permission controls require manual operation.");
            Bundle arguments = new Bundle(); arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean accepted = selected.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            boolean confirmed = accepted && selected.refresh() && !selected.isPassword() && text.contentEquals(selected.getText() == null ? "" : selected.getText());
            try { return result(accepted, accepted ? confirmed ? "text_set" : "text_requested" : "text_rejected", "Only accepted action and matching post-action state are reported; input contents are not returned.").put("accepted", accepted).put("completionConfirmed", confirmed).put("suppliedUtf16Units", text.length()); }
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
        try { if (ownAppPackage(active.getPackageName())) return ownerInteractionRequired(); if (protectedSettingsPackage(active.getPackageName())) return result(false, "manual_permission_required", "Android permission, installation and administrative Settings controls must be operated by the user."); }
        finally { active.recycle(); }
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (!finite(x1) || !finite(y1) || !finite(x2) || !finite(y2) || x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0 || x1 >= width || x2 >= width || y1 >= height || y2 >= height || duration < 40 || duration > 1500) return result(false, "invalid_arguments", "Coordinates must be on-screen; gesture duration must be 40–1500 ms.");
        Path path = new Path(); path.moveTo(x1, y1); if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        boolean accepted = dispatchGesture(gesture, null, new Handler(Looper.getMainLooper()));
        return result(accepted, accepted ? "gesture_accepted" : "gesture_rejected", "Accepted means scheduled by Android; completion is not yet confirmed.");
    }

    private synchronized void clearNodes() { for (AccessibilityNodeInfo node : nodes.values()) node.recycle(); nodes.clear(); }
    private AccessibilityNodeInfo activeRoot() { return syntheticRootForTests == null ? getRootInActiveWindow() : AccessibilityNodeInfo.obtain(syntheticRootForTests); }
    /** Package-private synthetic Android node seam; never reachable from an application tool. */
    void setSyntheticRootForTests(AccessibilityNodeInfo root) { syntheticRootForTests = root; }
    private boolean ownAppPackage(CharSequence name) { return name != null && getPackageName().contentEquals(name); }
    private static JSONObject ownerInteractionRequired() { return result(false, "owner_interaction_required", "The agent cannot operate its own chat or owner-confirmation UI. Use app.settings.update and native file APIs for agent actions; owner messages and confirmations come from the owner."); }
    private static boolean validUtf16(String value) { for (int i = 0; i < value.length(); i++) { char character = value.charAt(i); if (Character.isHighSurrogate(character)) { if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false; } else if (Character.isLowSurrogate(character)) return false; } return true; }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private static boolean protectedSettingsPackage(CharSequence packageName) {
        String name = packageName == null ? "" : packageName.toString();
        return name.equals("com.android.settings") || name.equals("com.samsung.android.settings") || name.endsWith(".permissioncontroller") || name.endsWith(".packageinstaller");
    }
    private static String string(CharSequence text, int max) { if (text == null) return ""; String value = text.toString(); return value.length() > max ? value.substring(0, max) : value; }
    private static JSONObject result(boolean ok, String code, String message) { try { return new JSONObject().put("ok", ok).put("code", code).put("message", message); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
}
