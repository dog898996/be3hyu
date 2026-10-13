package com.hoyoung.assistant.web;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.hoyoung.assistant.runtime.AssistantRuntime;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.InternalFiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;

/** Only DOM events cross the web boundary. No privileged native API is exposed to a page. */
public final class WorkWebController implements AssistantRuntime.Transport {
    public static final String TEMPORARY_URL = "https://chatgpt.com/?temporary-chat=true";
    private final Context context;
    private final AssistantRuntime runtime;
    private final InternalFiles files;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView view;
    private Activity activity;
    private boolean loaded;
    private boolean started;
    private String script;
    private ValueCallback<Uri[]> fileCallback;
    private String currentOrigin = "";
    private final java.util.concurrent.ExecutorService downloads = java.util.concurrent.Executors.newSingleThreadExecutor();

    public WorkWebController(Context context, AssistantRuntime runtime, InternalFiles files) {
        this.context = context.getApplicationContext();
        this.runtime = runtime;
        this.files = files;
        try (var input = this.context.getAssets().open("work-bridge.js")) {
            script = readUtf8(input);
        } catch (Exception e) { script = null; }
    }

    public WebView getView() {
        if (view == null) createView();
        return view;
    }

    public void bindActivity(Activity activity) { this.activity = activity; }
    public void unbindActivity(Activity activity) { if (this.activity == activity) this.activity = null; }

    public void start() {
        main.post(() -> {
            getView();
            if (!started) { started = true; view.loadUrl(TEMPORARY_URL); }
        });
    }

    private void createView() {
        view = new WebView(context);
        view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, "HoyoungHost", Collections.singleton("https://chatgpt.com"),
                (webView, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!isMainFrame || !isChatGptOrigin(sourceOrigin) || !isChatGptOrigin(Uri.parse(currentOrigin))) return;
                    String payload = message.getData();
                    if (payload == null || payload.length() > 1_000_000) return;
                    try {
                        JSONObject event = new JSONObject(payload);
                        String type = event.optString("type");
                        if (!Arrays.asList("state", "accepted", "progress", "completed", "quota", "error").contains(type)) return;
                        runtime.onWebEvent(event);
                    } catch (Exception ignored) { emitError("BRIDGE_EVENT_INVALID", null); }
                });
        } else emitError("WEBVIEW_MESSAGE_LISTENER_UNAVAILABLE", null);

        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (isAllowedBrowserOrigin(uri)) return false;
                if (activity != null && "https".equals(uri.getScheme())) {
                    try { activity.startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                }
                return true;
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
                loaded = false;
                currentOrigin = url;
                JSONObject state = new JSONObject();
                try { state.put("loading", true).put("workSelected", false).put("temporary", false).put("authenticated", JSONObject.NULL); } catch (Exception ignored) { }
                emitState(state, "page_loading");
            }
            @Override public void onPageFinished(WebView v, String url) {
                currentOrigin = url;
                loaded = isChatGptOrigin(Uri.parse(url));
                if (loaded && script != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    v.evaluateJavascript(script, ignored -> inspect());
                } else if (loaded) emitError("BRIDGE_SCRIPT_UNAVAILABLE", null);
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                loaded = false;
                started = false;
                emitError("WEBVIEW_RENDERER_LOST", null);
                if (v.getParent() instanceof ViewGroup parent) parent.removeView(v);
                v.destroy();
                view = null;
                return true;
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                if (activity == null) { callback.onReceiveValue(null); fileCallback = null; return true; }
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { activity.startActivityForResult(intent, 204); }
                catch (Exception e) { callback.onReceiveValue(null); fileCallback = null; }
                return true;
            }
        });
        view.setDownloadListener((url, userAgent, disposition, mime, contentLength) -> {
            if (!WorkingDownload.allowed(Uri.parse(url))) {
                runtime.localNotice("download_origin_unverified", "이 다운로드 경로를 확인하지 못했어. 파일을 직접 저장해 앱으로 가져와 줘."); return;
            }
            downloads.execute(() -> {
                try {
                    JSONObject saved = WorkingDownload.save(url, disposition, mime, files);
                    runtime.localNotice("working_file_saved", "작업 파일을 폰 내부에 저장했어: " + saved.optString("name"));
                } catch (Exception e) { runtime.localNotice("working_file_download_failed", "작업 파일을 저장하지 못했어. Work에서 파일을 확인해 줘."); }
            });
        });
    }

    public void receiveFileChooser(Uri[] uris) {
        if (fileCallback != null) { fileCallback.onReceiveValue(uris); fileCallback = null; }
    }

    @Override public void inspect() {
        main.post(() -> {
            if (view == null || !loaded) return;
            view.evaluateJavascript("window.HoyoungWorkBridge ? JSON.stringify(window.HoyoungWorkBridge.inspectAndEmit()) : null", raw -> {
                try {
                    JSONObject receipt = decodeResult(raw);
                    JSONObject state = receipt.optJSONObject("state");
                    // Explicit polling is fresh evidence even when DOM observers deduplicate an unchanged state.
                    if (state != null) emitState(state, receipt.optString("code", "selection_unverified"));
                } catch (Exception ignored) { emitError("BRIDGE_INSPECTION_INVALID", null); }
            });
        });
    }

    public void selectWork() {
        main.post(() -> {
            if (view == null || !loaded) { emitError("WEBVIEW_NOT_READY", null); return; }
            view.evaluateJavascript("window.HoyoungWorkBridge ? JSON.stringify(window.HoyoungWorkBridge.selectWork()) : null", ignored -> inspect());
        });
    }

    @Override public void send(String requestId, String prompt) {
        main.post(() -> {
            if (view == null || !loaded) { emitPreflight("WEBVIEW_NOT_READY", requestId); return; }
            try {
                JSONObject request = new JSONObject().put("requestId", requestId).put("text", prompt)
                    .put("requiredModel", "gpt-6.1-sol").put("requiredEffort", "medium").put("requiredSpeed", "fast");
                JSONArray ids = new JSONArray(((HoyoungApplication) context).getStore().getSetting("attachments:" + requestId, "[]"));
                if (ids.length() > 0) request.put("attachments", attachmentPayload(ids));
                String call = "window.HoyoungWorkBridge ? JSON.stringify(window.HoyoungWorkBridge.send(" + request + ")) : null";
                view.evaluateJavascript(call, raw -> {
                    try {
                        if (raw == null || "null".equals(raw)) { emitError("BRIDGE_NOT_READY", requestId); return; }
                        JSONObject receipt = decodeResult(raw);
                        if (!receipt.optBoolean("ok")) {
                            if (receipt.optBoolean("knownNotSent")) emitPreflight(receipt.optString("code", "BRIDGE_BLOCKED"), requestId);
                            else emitError(receipt.optString("code", "BRIDGE_BLOCKED"), requestId);
                        } else if ("duplicate_request".equals(receipt.optString("code"))) {
                            if ("completed".equals(receipt.optString("phase")) && receipt.has("response"))
                                runtime.onWebEvent(new JSONObject().put("type", "completed").put("requestId", requestId).put("text", receipt.optString("response")));
                            else if ("accepted".equals(receipt.optString("phase")))
                                runtime.onWebEvent(new JSONObject().put("type", "accepted").put("requestId", requestId));
                        }
                    } catch (Exception e) { emitError("DISPATCH_ACK_UNCERTAIN", requestId); }
                });
            } catch (Exception e) { emitPreflight("ATTACHMENT_NOT_READY", requestId); }
        });
    }

    private JSONArray attachmentPayload(JSONArray ids) throws Exception {
        JSONArray all = files.listFiles();
        JSONArray result = new JSONArray();
        long total = 0;
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.getString(i);
            JSONObject metadata = null;
            for (int j = 0; j < all.length(); j++) {
                JSONObject row = all.getJSONObject(j);
                if (id.equals(row.optString("id")) || id.equals(row.optString("fileId"))) { metadata = row; break; }
            }
            if (metadata == null) throw new IllegalArgumentException("Unknown attachment");
            if (metadata.optLong("size") > 10 * 1024 * 1024 || ids.length() > 8) throw new IllegalArgumentException("Attachment exceeds bridge limit");
            String savedMeta = ((HoyoungApplication) context).getStore().getSetting("fileMeta:" + id, "");
            if (!savedMeta.isEmpty()) {
                JSONObject saved = new JSONObject(savedMeta);
                metadata.put("displayName", saved.optString("displayName", metadata.optString("name", id)));
                metadata.put("mimeType", saved.optString("mimeType", "application/octet-stream"));
            }
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            files.exportFile(id, output);
            byte[] bytes = output.toByteArray();
            total += bytes.length;
            if (bytes.length > 10 * 1024 * 1024 || total > 20 * 1024 * 1024 || ids.length() > 8) throw new IllegalArgumentException("Attachment exceeds bridge limit");
            String name = metadata.optString("displayName", metadata.optString("name", id));
            String mime = metadata.optString("mime", metadata.optString("mimeType", "application/octet-stream"));
            if ("application/octet-stream".equals(mime)) {
                String guessed = java.net.URLConnection.guessContentTypeFromName(name);
                if (guessed != null) mime = guessed;
            }
            result.put(new JSONObject().put("id", id).put("name", name).put("mime", mime).put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)));
        }
        return result;
    }

    @Override public void openTemporary() {
        main.post(() -> {
            getView();
            started = true;
            loaded = false;
            view.loadUrl(TEMPORARY_URL);
        });
    }

    @Override public void cancel() {
        main.post(() -> {
            if (view != null && loaded) view.evaluateJavascript("window.HoyoungWorkBridge && window.HoyoungWorkBridge.cancel()", ignored -> { });
        });
    }

    public void manualRetryCheck() {
        main.post(() -> {
            if (view == null || !loaded) { runtime.localNotice("retry_connection_missing", "Work 연결을 먼저 확인해 줘."); return; }
            view.evaluateJavascript("window.HoyoungWorkBridge && window.HoyoungWorkBridge.prepareOwnerRetry ? JSON.stringify(window.HoyoungWorkBridge.prepareOwnerRetry()) : null", raw -> {
                try {
                    JSONObject receipt = decodeResult(raw);
                    if (receipt.optBoolean("ok")) { runtime.confirmQuotaRecovery(); inspect(); }
                    else runtime.localNotice("retry_requires_review", "한도 안내나 기존 작업이 아직 확인되지 않았어. Work 탭에서 먼저 확인해 줘.");
                } catch (Exception e) { runtime.localNotice("retry_connection_unverified", "연결 상태를 확인하지 못했어. Work 탭에서 확인해 줘."); }
            });
        });
    }

    public static boolean isChatGptOrigin(Uri uri) {
        return uri != null && "https".equals(uri.getScheme()) && "chatgpt.com".equals(uri.getHost())
            && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static boolean isAllowedBrowserOrigin(Uri uri) {
        return uri != null && "https".equals(uri.getScheme()) && Arrays.asList("chatgpt.com", "auth.openai.com", "auth0.openai.com", "accounts.google.com", "appleid.apple.com", "login.live.com").contains(uri.getHost())
            && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private void emitState(JSONObject state, String code) {
        try { runtime.onWebEvent(new JSONObject().put("type", "state").put("code", code).put("state", state)); } catch (Exception ignored) { }
    }

    private void emitError(String code, String requestId) {
        try {
            if (requestId == null && "WEBVIEW_RENDERER_LOST".equals(code)) requestId = runtime.state().optString("activeRequestId", "");
            runtime.onWebEvent(new JSONObject().put("type", "error").put("code", code).put("requestId", requestId == null ? JSONObject.NULL : requestId));
        } catch (Exception ignored) { }
    }

    private void emitPreflight(String code, String requestId) {
        try { runtime.onWebEvent(new JSONObject().put("type", "error").put("code", code).put("knownNotSent", true).put("requestId", requestId)); } catch (Exception ignored) { }
    }

    private static JSONObject decodeResult(String raw) throws Exception {
        if (raw == null || "null".equals(raw)) throw new IllegalArgumentException("No bridge receipt");
        Object decoded = new org.json.JSONTokener(raw).nextValue();
        return decoded instanceof String ? new JSONObject((String) decoded) : (JSONObject) decoded;
    }

    private static String readUtf8(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) { if (output.size() + count > 1_000_000) throw new IllegalArgumentException("Asset too large"); output.write(buffer, 0, count); }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
