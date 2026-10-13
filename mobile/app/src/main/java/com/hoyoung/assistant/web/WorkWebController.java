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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
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
    private final java.util.concurrent.ExecutorService responseCaptures = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService attachmentPreparation = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Map<String, ResponseCaptureJournal> captures = new HashMap<>();
    private final Set<String> failedCaptures = new HashSet<>();

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
                    if (webView != view || !isMainFrame || !isChatGptOrigin(sourceOrigin) || !isChatGptOrigin(Uri.parse(currentOrigin))) return;
                    String payload = message.getData();
                    if (payload == null) return;
                    if (payload.length() > 1_000_000) { emitActiveTransportError("BRIDGE_PAYLOAD_OVERFLOW"); return; }
                    try {
                        JSONObject event = new JSONObject(payload);
                        String type = event.optString("type");
                        if (!Arrays.asList("state", "accepted", "progress", "completed", "quota", "error").contains(type)) return;
                        if ("progress".equals(type) && "dispatch_authorization_requested".equals(event.optString("code"))) {
                            authorizePreparedDispatch(webView, event);
                            return; // This handshake is not an ordinary progress message.
                        }
                        if ("response_capture_part".equals(event.optString("code")) || "completed".equals(type) && event.has("captureId")) {
                            String requestId = event.optString("requestId", "");
                            if (requestId.isEmpty() || !requestId.equals(runtime.state().optString("activeRequestId", ""))) return;
                            // Ordering is preserved on a dedicated worker. Already received
                            // parts may still be archived after the WebView disconnects.
                            responseCaptures.execute(() -> receiveCaptureEvent(event));
                            return;
                        }
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
                if (v != view) return;
                if (loaded) emitActiveTransportError("WORK_DOCUMENT_REPLACED");
                loaded = false;
                currentOrigin = url;
                JSONObject state = new JSONObject();
                try { state.put("loading", true).put("workSelected", false).put("temporary", false).put("authenticated", JSONObject.NULL); } catch (Exception ignored) { }
                emitState(state, "page_loading");
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (v != view) return;
                currentOrigin = url;
                loaded = isChatGptOrigin(Uri.parse(url));
                if (loaded && script != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    v.evaluateJavascript(script, ignored -> inspect());
                } else if (loaded) emitError("BRIDGE_SCRIPT_UNAVAILABLE", null);
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                if (v != view) return true;
                loaded = false;
                started = false;
                emitActiveTransportError("WEBVIEW_RENDERER_LOST");
                JSONObject state = new JSONObject();
                try { state.put("loading", false).put("authenticated", JSONObject.NULL).put("workSelected", false).put("temporary", false).put("busy", true).put("quota", false); } catch (Exception ignored) { }
                emitState(state, "renderer_lost");
                if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
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
                    JSONObject saved = WorkingDownload.save(url, disposition, mime, files, userAgent);
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

    // UTF-16 character limit shared with work-bridge.js and AssistantRuntime.
    // Do not shorten a prepared prompt to fit: its original remains in the queue.
    private static final int MAX_PROMPT_CHARS = 250_000;

    @Override public void send(String requestId, String prompt) {
        main.post(() -> {
            if (view == null || !loaded) { emitPreflight("WEBVIEW_NOT_READY", requestId); return; }
            if (prompt == null || prompt.isEmpty() || prompt.length() > MAX_PROMPT_CHARS) { emitPreflight("MESSAGE_TOO_LARGE_OR_EMPTY", requestId); return; }
            WebView destination = view;
            // File reads/base64 can be tens of MiB. Keep owner input and Android's
            // main thread responsive while the immutable dispatch is prepared.
            attachmentPreparation.execute(() -> {
              final JSONObject request;
              try {
                request = new JSONObject().put("requestId", requestId).put("text", prompt)
                    .put("requiredModel", "gpt-6.1-sol").put("requiredEffort", "medium").put("requiredSpeed", "fast")
                    .put("requiresNativeAuthorization", true);
                JSONArray ids = new JSONArray(((HoyoungApplication) context).getStore().getSetting("attachments:" + requestId, "[]"));
                if (ids.length() > 0) request.put("attachments", attachmentPayload(ids));
              } catch (Exception preparationFailure) { emitPreflight("ATTACHMENT_NOT_READY", requestId); return; }
              main.post(() -> {
                if (view != destination || !loaded) { emitPreflight("WEBVIEW_NOT_READY", requestId); return; }
                if (!requestId.equals(runtime.state().optString("activeRequestId", ""))) return;
                // Attachment preparation runs after core prompt preparation. The
                // owner can revoke context/settings or the OS can revoke grants
                // during that interval; recheck immediately before any JS wire
                // send, without reclassifying an already accepted cloud outcome.
                if (!runtime.preparedDispatchStillAllowed(requestId)) { emitPreflight("PREPARED_DISPATCH_POLICY_CHANGED", requestId); return; }
                String call = "window.HoyoungWorkBridge ? JSON.stringify(window.HoyoungWorkBridge.send(" + request + ")) : null";
                try { destination.evaluateJavascript(call, raw -> {
                    try {
                        if (raw == null || "null".equals(raw)) { emitError("BRIDGE_NOT_READY", requestId); return; }
                        JSONObject receipt = decodeResult(raw);
                        if (!receipt.optBoolean("ok")) {
                            if (receipt.optBoolean("knownNotSent")) emitPreflight(receipt.optString("code", "BRIDGE_BLOCKED"), requestId);
                            else emitError(receipt.optString("code", "BRIDGE_BLOCKED"), requestId);
                        } else if ("duplicate_request".equals(receipt.optString("code"))) {
                            if ("completed".equals(receipt.optString("phase")) && receipt.has("response")) {
                                JSONObject completed = new JSONObject().put("type", "completed").put("requestId", requestId).put("text", receipt.optString("response"));
                                // A cached receipt must retain the same raw/structured split
                                // as the original event; Runtime validates protocol/requestId.
                                for (String field : new String[]{"rawEnvelopeEligible", "structuredEnvelope", "structuredEnvelopeSource", "structuredEnvelopeUnavailable", "structuredEnvelopeObservedChars"})
                                    if (receipt.has(field)) completed.put(field, receipt.get(field));
                                runtime.onWebEvent(completed);
                            }
                            else if ("accepted".equals(receipt.optString("phase")))
                                runtime.onWebEvent(new JSONObject().put("type", "accepted").put("requestId", requestId));
                            else if ("completed".equals(receipt.optString("phase")) && receipt.has("capture"))
                                emitError("CAPTURE_RECEIPT_REQUIRES_REVIEW", requestId);
                        }
                    } catch (Exception e) { emitError("DISPATCH_ACK_UNCERTAIN", requestId); }
                }); } catch (RuntimeException dispatchFailure) { emitError("DISPATCH_ACK_UNCERTAIN", requestId); }
              });
            });
        });
    }

    private void authorizePreparedDispatch(WebView source, JSONObject event) {
        String requestId = event.optString("requestId", ""), nonce = event.optString("nonce", "");
        if (!requestId.matches("[A-Za-z0-9_.:-]{1,128}") || !nonce.matches("[a-f0-9]{32}")) return;
        main.post(() -> {
            if (source != view || !loaded || !isChatGptOrigin(Uri.parse(currentOrigin))) return;
            boolean allowed = false;
            try { allowed = runtime.preparedDispatchStillAllowed(requestId); }
            catch (RuntimeException policyUnavailable) { /* An unreadable policy never authorizes a click. */ }
            JSONArray arguments = new JSONArray().put(requestId).put(nonce).put(allowed);
            try {
                source.evaluateJavascript("window.HoyoungWorkBridge && window.HoyoungWorkBridge.authorizeDispatch && window.HoyoungWorkBridge.authorizeDispatch.apply(null," + arguments + ")", ignored -> { });
            } catch (RuntimeException replyLost) {
                // No click is authorized without an actual matching JS reply. JS
                // retains the draft and reports its bounded handshake timeout.
            }
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
            LimitedAttachmentBytes output = new LimitedAttachmentBytes(10 * 1024 * 1024);
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

    static final class LimitedAttachmentBytes extends java.io.OutputStream {
        private final int maximum;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        LimitedAttachmentBytes(int maximum) { this.maximum = maximum; }
        @Override public void write(int value) throws IOException {
            if (output.size() >= maximum) throw new IOException("Attachment exceeds bridge limit");
            output.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int count) throws IOException {
            if (count < 0 || output.size() + (long) count > maximum) throw new IOException("Attachment exceeds bridge limit");
            output.write(bytes, offset, count);
        }
        byte[] toByteArray() { return output.toByteArray(); }
    }

    private void receiveCaptureEvent(JSONObject event) {
        String captureId = event.optString("captureId", "");
        String requestId = event.optString("requestId", "");
        if (captureId.isEmpty() || captureId.length() > 1024 || !requestId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            emitError("RESPONSE_CAPTURE_METADATA_INVALID", requestId); return;
        }
        if (failedCaptures.contains(captureId)) return;
        ResponseCaptureJournal journal = captures.get(captureId);
        try {
            int totalParts = event.getInt("totalParts");
            if ("response_capture_part".equals(event.optString("code"))) {
                if (!"progress".equals(event.optString("type"))) throw new IOException("Unexpected capture event");
                if (journal == null) {
                    if (event.getInt("index") != 0 || captures.size() >= 2) throw new IOException("Unexpected first capture part");
                    journal = new ResponseCaptureJournal(new File(context.getFilesDir(), "work-response-captures"), requestId, captureId, totalParts);
                    captures.put(captureId, journal);
                }
                if (!journal.requestId.equals(requestId) || journal.totalParts != totalParts) throw new IOException("Capture identity changed");
                journal.append(event.getInt("index"), event.getString("text"));
                return;
            }
            if (!"completed".equals(event.optString("type")) || !Boolean.TRUE.equals(event.opt("captureComplete"))
                || !"utf-8".equals(event.optString("captureEncoding")) || journal == null
                || !journal.requestId.equals(requestId) || journal.totalParts != totalParts) throw new IOException("Capture completion is unverified");
            File original = journal.finish(event.getLong("observedChars"));
            JSONObject saved;
            try (InputStream input = new FileInputStream(original)) {
                saved = files.writeFile("work-replies/" + UUID.randomUUID() + "-response.txt", input);
            }
            // The verified source is durable before any task is considered complete.
            String text = new String(Files.readAllBytes(original.toPath()), StandardCharsets.UTF_8);
            if (text.length() != event.getLong("observedChars")) throw new IOException("Captured text length changed");
            JSONObject complete = new JSONObject(event.toString()).put("text", text)
                .put("rawFileId", saved.optString("id")).put("rawSha256", journal.sha256())
                .put("captureComplete", true);
            ((HoyoungApplication) context).getStore().recordEvent("work_reply_original_captured", new JSONObject()
                .put("requestId", requestId).put("captureId", captureId).put("rawFileId", saved.optString("id"))
                .put("sha256", journal.sha256()).put("characters", text.length()).put("captureComplete", true));
            journal.archived(saved.optString("id"));
            captures.remove(captureId);
            original.delete();
            if (requestId.equals(runtime.state().optString("activeRequestId", ""))) runtime.onWebEvent(complete);
            else runtime.localNotice("work_reply_archived_after_disconnect", "연결이 끊긴 작업의 답변 원문은 폰 작업 파일에 저장했어. 기존 작업을 직접 확인해 줘: " + saved.optString("name"));
        } catch (Exception failure) {
            failedCaptures.add(captureId);
            if (journal != null) journal.closePartial();
            captures.remove(captureId);
            emitError("RESPONSE_CAPTURE_INCOMPLETE", requestId);
        }
    }

    /** Ordered, bounded original capture. A partial file is never marked complete. */
    static final class ResponseCaptureJournal {
        static final long MAX_CHARACTERS = 10_000_000L;
        final String requestId;
        final String captureId;
        final int totalParts;
        private final File file;
        private final File manifest;
        private final FileOutputStream output;
        private final MessageDigest digest;
        private int nextIndex;
        private long characters;
        private boolean closed;
        private String hash;
        private String rawFileId;

        ResponseCaptureJournal(File directory, String requestId, String captureId, int totalParts) throws Exception {
            if (totalParts < 1 || totalParts > 100) throw new IOException("Capture part count exceeds limit");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Capture directory unavailable");
            this.requestId = requestId; this.captureId = captureId; this.totalParts = totalParts;
            file = File.createTempFile("response-", ".partial", directory);
            manifest = new File(file.getPath() + ".json");
            output = new FileOutputStream(file);
            digest = MessageDigest.getInstance("SHA-256");
            try { writeManifest(false); } catch (IOException failure) { output.close(); throw failure; }
        }

        void append(int index, String text) throws IOException {
            if (closed || index != nextIndex || nextIndex >= totalParts || text == null || text.isEmpty()
                || text.length() > 100_001 || characters + text.length() > MAX_CHARACTERS) throw new IOException("Capture part is missing, duplicated or too large");
            for (int offset = 0; offset < text.length(); offset++) {
                char character = text.charAt(offset);
                if (Character.isHighSurrogate(character)) {
                    if (++offset >= text.length() || !Character.isLowSurrogate(text.charAt(offset))) throw new IOException("Capture split a Unicode character");
                } else if (Character.isLowSurrogate(character)) throw new IOException("Capture contains an unpaired Unicode character");
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            output.write(bytes); digest.update(bytes);
            output.getFD().sync();
            characters += text.length(); nextIndex++;
            writeManifest(false);
        }

        File finish(long observedChars) throws IOException {
            if (closed || nextIndex != totalParts || observedChars != characters || observedChars < 1) throw new IOException("Capture does not match its completion manifest");
            output.getFD().sync(); output.close(); closed = true;
            byte[] value = digest.digest();
            StringBuilder encoded = new StringBuilder(64);
            for (byte item : value) encoded.append("0123456789abcdef".charAt((item & 255) >>> 4)).append("0123456789abcdef".charAt(item & 15));
            hash = encoded.toString();
            writeManifest(true);
            return file;
        }

        private void writeManifest(boolean complete) throws IOException {
            try {
                JSONObject metadata = new JSONObject().put("requestId", requestId).put("captureId", captureId)
                    .put("totalParts", totalParts).put("receivedParts", nextIndex).put("characters", characters)
                    .put("captureComplete", complete).put("sourceFormat", "visible-dom-text")
                    .put("rawFileId", rawFileId == null ? JSONObject.NULL : rawFileId)
                    .put("sha256", hash == null ? JSONObject.NULL : hash);
                File temporary = new File(manifest.getPath() + ".tmp");
                try (FileOutputStream marker = new FileOutputStream(temporary)) {
                    marker.write(metadata.toString().getBytes(StandardCharsets.UTF_8)); marker.getFD().sync();
                }
                Files.move(temporary.toPath(), manifest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception failure) { throw new IOException("Capture manifest could not be persisted", failure); }
        }

        String sha256() { return hash; }

        void archived(String fileId) throws IOException { rawFileId = fileId; writeManifest(true); }

        void closePartial() {
            if (!closed) try { output.getFD().sync(); output.close(); } catch (IOException ignored) { }
            closed = true; // Keep partial originals for inspection, without a completed receipt.
        }
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

    private void emitActiveTransportError(String code) {
        JSONObject state = runtime.state();
        String requestId = state.isNull("activeRequestId") ? "" : state.optString("activeRequestId", "");
        if (!requestId.isEmpty()) emitError(code, requestId);
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
