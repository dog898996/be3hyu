package com.hoyoung.assistant.phone;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hoyoung.assistant.ui.HoyoungAvatarView;
import com.hoyoung.assistant.store.AssistantIdentity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real system overlay window; it supplies no screenshot, touch injection, or root capability. */
public final class PhoneOverlayTools {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager windows;
    private PhoneEventJournal journal;
    private volatile boolean restorePaused;
    private LinearLayout panel;
    private TextView title, body;
    private HoyoungAvatarView avatar;
    private WindowManager.LayoutParams layout;
    private boolean explicitTitle;

    public PhoneOverlayTools(Context context) { this.context = context.getApplicationContext(); windows = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE); }
    PhoneOverlayTools(Context context, PhoneEventJournal journal) { this(context); this.journal = journal; }
    private PhoneEventJournal journal() { if (journal == null) journal = PhoneEventJournal.forContext(context); return journal; }

    public void appendCatalog(JSONArray output) {
        JSONObject properties = PhoneEventJournal.object("title", property("string"), "text", property("string"), "xDp", property("integer"), "yDp", property("integer"), "widthDp", property("integer"));
        tool(output, "phone_overlay_show", "Show/update a real draggable assistant card above ordinary app windows after System Alert Window grant. Coordinates are dp. Has an owner close button; does not inject touches or read underlying screens. Android can hide it on sensitive screens.", properties, new JSONArray().put("text"));
        tool(output, "phone_overlay_update", "Update this process's existing assistant overlay title/text/position/width. Returns actual WindowManager API acceptance; it does not prove the owner saw the card.", properties, new JSONArray());
        tool(output, "phone_overlay_hide", "Remove this process's assistant overlay window. Does not control other apps' overlays or any underlying app.", new JSONObject(), new JSONArray());
    }
    public boolean supports(String name) { return "phone_overlay_show".equals(name) || "phone_overlay_update".equals(name) || "phone_overlay_hide".equals(name); }
    public boolean canReadArchivedResult(String name, JSONObject result) { return supports(name) && Settings.canDrawOverlays(context); }
    public JSONObject execute(String name, JSONObject supplied) {
        JSONObject args = supplied == null ? new JSONObject() : supplied;
        try {
            if (restorePaused) return failure("restore_in_progress");
            journal().refreshPolicyBlocking(); if (!journal().toolAllowed(name)) return failure("permission_disabled");
            if (!Settings.canDrawOverlays(context)) return failure("permission_missing").put("permissionId", "overlay");
            if (windows == null) return failure("window_service_unavailable");
            if ("phone_overlay_hide".equals(name)) { rejectUnknown(args); return onMain(this::hide); }
            if (!"phone_overlay_show".equals(name) && !"phone_overlay_update".equals(name)) return failure("unsupported");
            rejectUnknown(args, "title", "text", "xDp", "yDp", "widthDp");
            if ("phone_overlay_show".equals(name) && !args.has("text")) throw new IllegalArgumentException();
            if (args.has("title")) text(args, "title", 100);
            if (args.has("text")) text(args, "text", 4000);
            for (String key : new String[]{"xDp", "yDp"}) if (args.has(key)) integer(args, key, 0, -4096, 4096);
            if (args.has("widthDp")) integer(args, "widthDp", 280, 120, 4096);
            JSONObject immutable = new JSONObject(args.toString());
            return onMain(() -> showOrUpdate(name, immutable));
        } catch (SecurityException denied) { return failure("permission_missing"); }
        catch (IllegalArgumentException | JSONException invalid) { return failure("invalid_arguments"); }
        catch (Exception unavailable) { return failure("operation_failed"); }
    }

    private JSONObject showOrUpdate(String name, JSONObject args) throws JSONException {
        if (!Settings.canDrawOverlays(context)) return failure("permission_missing");
        if (restorePaused) return failure("restore_in_progress");
        if ("phone_overlay_update".equals(name) && panel == null) return failure("overlay_not_visible");
        boolean existed = panel != null;
        if (!existed) createPanel();
        if (args.has("title")) { title.setText(text(args, "title", 100)); explicitTitle = true; }
        else if (!explicitTitle) title.setText(AssistantIdentity.name(journal().canonicalStore()));
        if (args.has("text")) body.setText(text(args, "text", 4000));
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        if (args.has("widthDp")) layout.width = Math.max(dp(120), Math.min(screenWidth, dp(integer(args, "widthDp", 280, 120, 4096))));
        if (args.has("xDp")) layout.x = dp(integer(args, "xDp", 12, -4096, 4096));
        if (args.has("yDp")) layout.y = dp(integer(args, "yDp", 90, -4096, 4096));
        if (existed) windows.updateViewLayout(panel, layout);
        else {
            try { windows.addView(panel, layout); }
            catch (RuntimeException failure) { panel = null; title = null; body = null; avatar = null; layout = null; explicitTitle = false; throw failure; }
        }
        return PhoneEventJournal.object("ok", true, "code", existed ? "overlay_update_applied" : "overlay_window_added", "windowAdded", true,
                "attachedToWindowObserved", panel.isAttachedToWindow(), "completionConfirmed", true, "ownerSawCardVerified", false,
                "osMayHideOnSensitiveScreens", true, "screenCaptureGranted", false, "touchInjectionGranted", false,
                "xPx", layout.x, "yPx", layout.y, "widthPx", layout.width);
    }
    private void createPanel() {
        explicitTitle = false;
        panel = new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16), dp(12), dp(14), dp(16));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(31, 36, 51)); background.setCornerRadius(dp(22));
        panel.setBackground(background); panel.setElevation(dp(16)); panel.setContentDescription("호영 플로팅 카드");
        LinearLayout header = new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL);
        avatar = new HoyoungAvatarView(context); header.addView(avatar, new LinearLayout.LayoutParams(dp(32), dp(32)));
        title = new TextView(context); title.setTextSize(16); title.setTextColor(Color.WHITE); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setPadding(dp(10), 0, dp(4), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(context); close.setText("×"); close.setTextSize(26); close.setGravity(Gravity.CENTER); close.setTextColor(Color.rgb(193, 200, 217));
        close.setContentDescription("호영 플로팅 카드 닫기"); close.setOnClickListener(view -> { try { hide(); } catch (RuntimeException ignored) { } });
        header.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40))); panel.addView(header);
        body = new TextView(context); body.setTextSize(14); body.setTextColor(Color.rgb(226, 231, 244)); body.setPadding(0, dp(8), 0, 0); body.setLineSpacing(dp(3), 1);
        body.setMaxLines(12); body.setEllipsize(android.text.TextUtils.TruncateAt.END); panel.addView(body);
        layout = new WindowManager.LayoutParams(Math.min(context.getResources().getDisplayMetrics().widthPixels, dp(280)), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.START; layout.x = dp(12); layout.y = dp(90);
        header.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY; int oldX, oldY;
            public boolean onTouch(View view, MotionEvent event) {
                if (layout == null || panel == null) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { startX = event.getRawX(); startY = event.getRawY(); oldX = layout.x; oldY = layout.y; return true; }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    layout.x = oldX + Math.round(event.getRawX() - startX); layout.y = oldY + Math.round(event.getRawY() - startY);
                    try { windows.updateViewLayout(panel, layout); } catch (RuntimeException unavailable) { }
                    return true;
                }
                return event.getActionMasked() == MotionEvent.ACTION_UP;
            }
        });
    }
    private JSONObject hide() {
        if (panel == null) return PhoneEventJournal.object("ok", true, "code", "overlay_already_hidden", "windowRemoved", false, "completionConfirmed", true);
        LinearLayout old = panel; windows.removeViewImmediate(old); panel = null; title = null; body = null; avatar = null; layout = null; explicitTitle = false;
        return PhoneEventJournal.object("ok", true, "code", "overlay_window_removed", "windowRemoved", true, "completionConfirmed", true);
    }
    /** Best-effort redraw after saving identity; never waits for the UI while the runtime holds its lock. */
    public void refreshAssistantIdentity() {
        try {
            main.post(() -> {
                if (panel == null || restorePaused) return;
                try {
                    if (!explicitTitle) title.setText(AssistantIdentity.name(journal().canonicalStore()));
                    if (avatar != null) avatar.refreshIdentity();
                } catch (RuntimeException unavailable) { /* The identity is already saved. */ }
            });
        } catch (RuntimeException unavailable) { /* An unavailable UI must not turn a saved identity into a failed update. */ }
    }
    public void pauseForBackupRestore() {
        restorePaused = true;
        try { onMain(this::hide); } catch (Exception ignored) { }
    }
    public void afterBackupRestore() { restorePaused = false; }
    public void stopForRuntime() { try { onMain(this::hide); } catch (Exception ignored) { } }
    private JSONObject onMain(Callable<JSONObject> operation) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return operation.call();
        AtomicBoolean canceled = new AtomicBoolean(); AtomicReference<JSONObject> result = new AtomicReference<>(); AtomicReference<Exception> error = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
        boolean posted = main.post(() -> { try { if (!canceled.get()) result.set(operation.call()); } catch (Exception failure) { error.set(failure); } finally { done.countDown(); } });
        if (!posted) return failure("window_dispatch_unavailable");
        if (!done.await(3, TimeUnit.SECONDS)) { canceled.set(true); return failure("operation_failed").put("windowOperationOutcomeUncertain", true); }
        if (error.get() != null) throw error.get(); return result.get() == null ? failure("window_dispatch_cancelled") : result.get();
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static String text(JSONObject args, String key, int maximum) throws JSONException { Object raw = args.get(key); if (!(raw instanceof String) || ((String) raw).length() > maximum) throw new IllegalArgumentException(); return (String) raw; }
    private static int integer(JSONObject args, String key, int fallback, int min, int max) throws JSONException {
        if (!args.has(key)) return fallback; Object raw = args.get(key); if (!(raw instanceof Number)) throw new IllegalArgumentException(); double n = ((Number) raw).doubleValue();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < min || n > max) throw new IllegalArgumentException(); return (int) n;
    }
    private static void rejectUnknown(JSONObject args, String... allowed) { for (java.util.Iterator<String> keys = args.keys(); keys.hasNext();) { String key = keys.next(); boolean valid = false; for (String candidate : allowed) if (key.equals(candidate)) valid = true; if (!valid) throw new IllegalArgumentException(); } }
    private static JSONObject failure(String code) { return PhoneEventJournal.object("ok", false, "code", code, "completionConfirmed", false); }
    private static JSONObject property(String type) { return PhoneEventJournal.object("type", type); }
    private static void tool(JSONArray output, String name, String description, JSONObject properties, JSONArray required) { output.put(PhoneEventJournal.object("name", name, "description", description, "inputSchema", PhoneEventJournal.object("type", "object", "properties", properties, "required", required, "additionalProperties", false))); }
}
