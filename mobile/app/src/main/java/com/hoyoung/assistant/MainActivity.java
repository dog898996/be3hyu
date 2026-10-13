package com.hoyoung.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ImageView;
import com.hoyoung.assistant.ui.AttachmentPreview;

import com.hoyoung.assistant.phone.PermissionCatalog;
import com.hoyoung.assistant.runtime.AutonomyService;
import com.hoyoung.assistant.runtime.HoyoungApplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BLUE = Color.rgb(36, 95, 232);
    private static final int INK = Color.rgb(24, 30, 44);
    private static final int SUBTLE = Color.rgb(103, 112, 132);
    private static final int BG = Color.rgb(247, 249, 253);
    private static final int PICK_FILES = 201, EXPORT_BACKUP = 202, IMPORT_BACKUP = 203, WORK_FILE_PICKER = 204, EXPORT_FILE = 205;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private HoyoungApplication app;
    private LinearLayout root, content;
    private TextView connection;
    private String tab = "대화", exportFileId;
    private JSONArray attachments = new JSONArray();
    private EditText composer;
    private TextView attachmentLabel;
    private long displayedMessageId = -1;
    private boolean sending;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            if ("대화".equals(tab)) refreshMessages();
            main.postDelayed(this, 1500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (HoyoungApplication) getApplication();
        try { attachments = app.getRuntime().isRestoringBackup() ? new JSONArray() : new JSONArray(app.getStore().getSetting("ui.attachments", "[]")); } catch (Exception ignored) { attachments = new JSONArray(); }
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        root = column(); root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(12), dp(12), dp(8));
        TextView name = label("호영", 25, INK); name.setTypeface(null, Typeface.BOLD); root.addView(name);
        connection = label("Work 연결을 준비하고 있어", 12, SUBTLE);
        connection.setPadding(0, dp(6), 0, dp(10)); root.addView(connection);
        LinearLayout nav = row();
        for (String nameTab : new String[]{"대화", "Work", "기억", "권한", "파일", "설정"}) {
            Button button = button(nameTab, () -> showTab(nameTab));
            button.setTextSize(12); button.setMinWidth(0); button.setMinimumWidth(0);
            nav.addView(button, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        root.addView(nav);
        content = column(); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        app.getWebController().bindActivity(this);
        app.getWebController().start();
        app.getRuntime().setListener(() -> main.post(this::refreshStatus));
        showTab("대화");
        if (!app.getRuntime().isRestoringBackup() && "true".equals(app.getStore().getSetting("autonomy.enabled", "false"))) startAutonomy();
    }

    @Override protected void onResume() {
        super.onResume();
        app.getWebController().bindActivity(this);
        main.removeCallbacks(refresh); main.post(refresh);
        if (!app.getRuntime().isRestoringBackup()) { app.getPhoneContext().reconcilePermissions(); app.maintainMemory(); }
        if ("권한".equals(tab)) showPermissions();
    }

    @Override protected void onPause() { persistDraft(); main.removeCallbacks(refresh); super.onPause(); }

    @Override protected void onDestroy() {
        main.removeCallbacks(refresh);
        app.getWebController().unbindActivity(this);
        detachWebView();
        io.shutdown();
        super.onDestroy();
    }

    private void showTab(String name) {
        if (app.getRuntime().isRestoringBackup()) {
            content.removeAllViews(); content.addView(label("백업을 검증·복원하고 있어. 원문과 파일을 일관된 상태로 되살린 뒤 다시 사용할 수 있어.", 16, INK));
            return;
        }
        persistDraft();
        detachWebView(); content.removeAllViews(); composer = null; tab = name;
        switch (name) {
            case "Work": showWork(); break;
            case "권한": showPermissions(); break;
            case "파일": showFiles(); break;
            case "기억": showMemory(); break;
            case "설정": showSettings(); break;
            default: showChat();
        }
    }

    private void detachWebView() {
        WebView view = app.getWebController().getView();
        if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
    }

    private LinearLayout messageList;
    private ScrollView messageScroll;
    private void showChat() {
        messageScroll = new ScrollView(this); messageScroll.setFillViewport(true);
        messageList = column(); messageScroll.addView(messageList);
        content.addView(messageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        attachmentLabel = label(attachmentSummary(), 12, SUBTLE); content.addView(attachmentLabel);
        LinearLayout entry = row();
        entry.addView(button("＋", () -> pickDocuments(PICK_FILES, "*/*")), new LinearLayout.LayoutParams(dp(48), dp(52)));
        composer = new EditText(this); composer.setTextSize(15); composer.setHint("호영에게 메시지…");
        composer.setMaxLines(5); composer.setText(app.getStore().getSetting("ui.draft", ""));
        composer.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { if (!app.getRuntime().isRestoringBackup()) app.getStore().putSetting("ui.draft", s.toString()); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        entry.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        entry.addView(button("보내기", this::send), new LinearLayout.LayoutParams(dp(76), dp(52)));
        content.addView(entry); displayedMessageId = -1; refreshMessages();
    }

    private void send() {
        if (sending) return;
        String draft = composer.getText().toString();
        if (draft.trim().isEmpty() && attachments.length() == 0) return;
        final String text = draft.trim().isEmpty() ? "첨부한 파일을 확인해줘." : draft;
        final String attached = attachments.toString();
        sending = true;
        io.execute(() -> {
            try {
                app.getRuntime().receive(text, new JSONArray(attached));
                main.post(() -> {
                    sending = false;
                    if (isFinishing() || isDestroyed()) return;
                    // Do not erase text or files the owner added while the immutable copy was being saved.
                    if (composer != null && composer.getText().toString().equals(draft)) composer.setText("");
                    if (attachments.toString().equals(attached)) { attachments = new JSONArray(); if (attachmentLabel != null) attachmentLabel.setText(""); }
                    persistDraft(); refreshMessages(); refreshStatus();
                });
            } catch (Exception error) { main.post(() -> { sending = false; notice("메시지나 첨부 원본을 저장하지 못했어. 입력과 선택은 그대로 남겨 뒀어."); }); }
        });
    }

    private void refreshMessages() {
        if (app.getRuntime().isRestoringBackup() || messageList == null || !"대화".equals(tab)) return;
        long newest = app.getStore().lastMessageId();
        if (newest == displayedMessageId) return;
        JSONArray messages = app.getStore().listMessages(80);
        displayedMessageId = newest; messageList.removeAllViews();
        if (messages.length() == 0) {
            TextView hint = label("대화와 기억은 이 폰에 저장돼.\nWork 탭에서 ChatGPT를 연결하면 일을 시작할 수 있어.", 15, SUBTLE);
            hint.setPadding(dp(8), dp(24), dp(8), dp(24)); messageList.addView(hint);
        }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i); if (message == null) continue;
            boolean owner = "user".equals(message.optString("role"));
            LinearLayout line = row(); line.setGravity(owner ? Gravity.END : Gravity.START);
            LinearLayout bubble = column(); bubble.setPadding(dp(12), dp(9), dp(12), dp(10));
            bubble.setBackground(round(owner ? BLUE : Color.WHITE));
            if (!owner) bubble.addView(label("호영", 11, SUBTLE));
            String original = message.optString("text");
            TextView body = label(shorten(original, 8000), 15, owner ? Color.WHITE : INK);
            body.setTextIsSelectable(true); bubble.addView(body);
            if (original.length() > 8000) bubble.addView(button("원문 보기", () -> new AlertDialog.Builder(this).setMessage(original).setPositiveButton("닫기", null).show()));
            if (message.optLong("raw_char_count") > original.length()) bubble.addView(button("보관된 전체 원문 읽기", () -> showOriginal(message.optLong("id"), 0)));
            if (owner) addAttachmentPreviews(bubble, message.optString("request_id"));
            LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(-2, -2);
            bubbleParams.setMargins(owner ? dp(30) : 0, dp(5), owner ? 0 : dp(30), dp(5));
            line.addView(bubble, bubbleParams); messageList.addView(line);
        }
        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void addAttachmentPreviews(LinearLayout bubble, String requestId) {
        JSONArray assets = parseArray(app.getStore().getSetting("attachmentMetadata:" + requestId, "[]"));
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i); if (asset == null) continue;
            String name = asset.optString("sourceName", asset.optString("name", "첨부"));
            bubble.addView(label(name, 12, Color.WHITE));
            if (!asset.optString("mime", asset.optString("mimeType")).startsWith("image/")) continue;
            ImageView image = new ImageView(this); image.setContentDescription(name); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            bubble.addView(image, new LinearLayout.LayoutParams(dp(220), dp(180)));
            io.execute(() -> {
                AttachmentPreview.Result preview = AttachmentPreview.loadResult(this, app.getFiles(), asset, 512);
                main.post(() -> { if (!isFinishing() && !isDestroyed() && preview.ready() && image.getParent() != null) image.setImageBitmap(preview.bitmap); });
            });
            image.setOnClickListener(view -> io.execute(() -> {
                AttachmentPreview.Result preview = AttachmentPreview.loadResult(this, app.getFiles(), asset, 1536);
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!preview.ready()) { notice("사진을 표시하지 못했어. 보관된 원본을 파일 페이지에서 확인해 줘."); return; }
                    ImageView full = new ImageView(this); full.setImageBitmap(preview.bitmap); full.setAdjustViewBounds(true); full.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    new AlertDialog.Builder(this).setTitle(name).setView(full).setPositiveButton("닫기", null).show();
                });
            }));
        }
    }

    private void showGoals(String after) {
        io.execute(() -> {
            try {
                JSONObject page = app.getRuntime().getGoalLedger().list(new JSONObject().put("limit", 30).put("afterRootRequestId", after));
                JSONArray goals = page.optJSONArray("goals"); String[] labels = new String[goals.length() + (page.optBoolean("truncated") ? 1 : 0)];
                for (int i = 0; i < goals.length(); i++) { JSONObject goal = goals.optJSONObject(i); labels[i] = goal.optString("stage") + " · " + shorten(goal.optString("progress", goal.optString("requestExcerpt")), 90); }
                if (page.optBoolean("truncated")) labels[labels.length - 1] = "다음 목표 보기";
                main.post(() -> { if (isFinishing() || isDestroyed()) return;
                    if (labels.length == 0) { notice("따로 추적 중인 미완료 목표는 없어."); return; }
                    new AlertDialog.Builder(this).setTitle("추적 중인 목표 " + page.optLong("totalPending") + "개").setItems(labels, (dialog, which) -> {
                        if (which >= goals.length()) { showGoals(page.optString("nextAfterRootRequestId")); return; }
                        JSONObject goal = goals.optJSONObject(which);
                        new AlertDialog.Builder(this).setTitle("목표 상태 정리").setMessage(goal.optString("requestExcerpt") + "\n" + goal.optString("progress") + "\n모델 보고와 실제 확인은 구분해 보관해. 아래 선택은 네가 직접 추적 상태를 정리하는 거야.")
                            .setPositiveButton("완료로 정리", (d, w) -> resolveGoal(goal.optString("rootRequestId"), "completed"))
                            .setNeutralButton("추적 그만", (d, w) -> resolveGoal(goal.optString("rootRequestId"), "abandoned")).setNegativeButton("유지", null).show();
                    }).setNegativeButton("닫기", null).show();
                });
            } catch (Exception failure) { main.post(() -> notice("목표 장부를 읽지 못했어. 저장된 작업은 그대로 두었어.")); }
        });
    }
    private void resolveGoal(String rootId, String stage) { io.execute(() -> { app.getRuntime().getGoalLedger().resolve(rootId, stage, "사용자가 앱 목표 목록에서 직접 정리"); main.post(() -> { notice("추적 상태를 정리했어. 원문과 영수증은 보관돼."); showMemory(); }); }); }

    private void showWork() {
        TextView help = label("여기서 본인 ChatGPT에 로그인하고 Work · 임시 채팅 · GPT-6.1 Sol · Medium · Fast를 선택해. 선택 상태가 확인되기 전에는 메시지를 보내지 않아.", 13, SUBTLE);
        help.setPadding(0, dp(10), 0, dp(8)); content.addView(help);
        LinearLayout controls = row();
        controls.addView(button("선택 확인", () -> app.getWebController().inspect()), new LinearLayout.LayoutParams(0, dp(46), 1));
        controls.addView(button("Work 선택", () -> app.getWebController().selectWork()), new LinearLayout.LayoutParams(0, dp(46), 1));
        controls.addView(button("검증 요청", () -> {
            app.getRuntime().receive("연결 검증만 수행해. Work의 실제 클라우드 코드 실행 도구가 있다면 Python으로 2+2를 계산하고 임시 작업 파일 hoyoung-connection-check.txt에 그 결과를 기록해. 실제 실행 결과와 사용한 도구를 보고하고, 클라우드 도구가 없으면 없다고 정확히 말해. 폰 연결은 phone_device_info 도구로 최소 상태만 확인해. 로그인 계정 변경·외부 메시지·결제·개인 연락처나 알림 읽기는 하지 마. 실제 도구 결과 없이 검증 통과라고 만들지 마.", new JSONArray());
            showTab("대화");
        }), new LinearLayout.LayoutParams(0, dp(46), 1));
        controls.addView(button("새 임시 대화", () -> {
            JSONObject state = app.getRuntime().state();
            if (!state.isNull("activeRequestId") && !state.optString("activeRequestId").isEmpty()) {
                notice("진행 중인 작업의 결과를 먼저 확인해. 새 대화를 열면 기존 임시 대화를 잃을 수 있어."); return;
            }
            try { app.getRuntime().openTemporary(); }
            catch (Exception e) { notice("기존 작업의 결과를 먼저 확인하거나 설정에서 해당 작업을 제외해 줘."); }
        }), new LinearLayout.LayoutParams(0, dp(46), 1));
        content.addView(controls);
        WebView web = app.getWebController().getView();
        content.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void showMemory() {
        content.removeAllViews(); LinearLayout panel = scrollPanel();
        panel.addView(label("기억과 개인화", 20, INK));
        panel.addView(label("원문과 정정 이력은 폰에 보관해. 관련 기억을 찾고 근거 원문을 다시 확인할 수 있어. 검토·의미 색인이 덜 된 부분은 상태에 표시해.", 14, SUBTLE));
        TextView status = label("기억 상태 확인 중", 13, SUBTLE); panel.addView(status);
        io.execute(() -> {
            String state;
            try {
                JSONObject review = app.getRuntime().getMemoryReview().status();
                JSONObject model = app.getSemanticEncoder().state();
                JSONObject indexed = app.getMemoryRetriever().diagnostics();
                state = "보관 원문 " + app.getStore().messageCount() + "건\n기억 검토: "
                        + (review.optBoolean("hasPending") ? "대기 원문 있음" : "현재 검토 대기 없음")
                        + "\n의미 검색 모델: " + semanticPhase(model.optString("phase"))
                        + "\n의미 검색 준비: " + Math.round(indexed.optDouble("sourceCoverage", 0) * 100) + "% ("
                        + indexed.optLong("semanticCompletedSources") + "/" + indexed.optLong("sourceTotals") + "건)";
            } catch (Exception error) { state = "기억 상태를 읽지 못했어. 원문을 삭제하거나 초기화하지 않았어."; }
            final String shown = state; main.post(() -> { if (!isFinishing() && "기억".equals(tab)) status.setText(shown); });
        });
        panel.addView(button("진행 중 목표와 결과 확인", () -> showGoals("")));
        panel.addView(button("지금 기억 검토 요청", () -> { app.getRuntime().requestMemoryReview(); notice("검토를 대기 목록에 넣었어. Work 연결과 한도가 준비돼야 실제 검토할 수 있어."); }));
        panel.addView(button("의미 검색 준비 상태 확인", () -> { app.maintainMemory(); showMemory(); }));
        panel.addView(label("폰 내부 의미 검색 모델은 약 138MB이며 사용량에 따른 API 요금이 없어. 검증한 공개 모델 파일을 비종량 네트워크에서 내려받고, 기억의 임베딩은 폰에서 계산해.", 12, SUBTLE));
        EditText query = new EditText(this); query.setHint("기억 검색: 사람, 일, 장소, 날짜…"); panel.addView(query);
        LinearLayout results = column();
        panel.addView(button("근거와 함께 검색", () -> {
            String text = query.getText().toString().trim();
            if (text.isEmpty() || text.length() > 4000) { notice("검색어는 1~4,000자로 입력해."); return; }
            results.removeAllViews(); results.addView(label("관련 원문과 기억을 찾는 중", 13, SUBTLE));
            io.execute(() -> {
                try {
                    JSONObject found = app.getMemoryRetriever().search(new JSONObject().put("query", text).put("limit", 12).put("includeHistory", true).put("maxChars", 16_000));
                    main.post(() -> {
                        if (isFinishing() || !"기억".equals(tab)) return;
                        results.removeAllViews(); TextView evidence = label(found.optString("context"), 13, INK); evidence.setTextIsSelectable(true); results.addView(evidence);
                        JSONArray originals = found.optJSONArray("originalEvidence");
                        for (int i = 0; originals != null && i < originals.length(); i++) {
                            JSONObject original = originals.optJSONObject(i); if (original == null) continue;
                            String type = original.optString("type", original.optString("sourceType"));
                            long id = original.optLong("id", original.optLong("sourceId"));
                            if ("message".equals(type) && id > 0) results.addView(button("원문 " + id + " 읽기", () -> showOriginal(id, original.optLong("offsetChars", 0))));
                        }
                    });
                } catch (Exception error) { main.post(() -> { results.removeAllViews(); results.addView(label("검색을 완료하지 못했어. 원문은 보존돼 있어.", 13, SUBTLE)); }); }
            });
        }));
        panel.addView(results);
    }

    private void showOriginal(long id, long offset) {
        io.execute(() -> {
            try {
                JSONObject chunk = app.getStore().readOriginalText(id, offset, 12_000);
                main.post(() -> {
                    if (isFinishing()) return;
                    AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("원문 " + id + " · " + chunk.optLong("offsetChars") + "/" + chunk.optLong("totalChars"))
                            .setMessage(chunk.optString("text")).setNegativeButton("닫기", null);
                    if (!chunk.optBoolean("complete")) dialog.setPositiveButton("다음 부분", (view, which) -> showOriginal(id, chunk.optLong("nextOffsetChars")));
                    dialog.show();
                });
            } catch (Exception error) { main.post(() -> notice("해당 원문을 읽지 못했어. 보관 상태를 확인해 줘.")); }
        });
    }

    private void applyPhonePolicy() { app.getPhoneTools().applyPermissionPolicyFromOwner(app.getStore().getSetting("toolPermissions", "{}"), "true".equals(app.getStore().getSetting("phoneContextEnabled", "true"))); }

    private void addSettingToggle(LinearLayout panel, String title, String key, boolean defaultValue) {
        CheckBox check = new CheckBox(this); check.setText(title);
        check.setChecked("true".equals(app.getStore().getSetting(key, String.valueOf(defaultValue))));
        check.setOnCheckedChangeListener((view, enabled) -> {
            app.getStore().putSetting(key, String.valueOf(enabled));
            applyPhonePolicy();
            app.getPhoneContext().reconcilePermissions();
            app.maintainMemory();
        }); panel.addView(check);
    }

    private static String semanticPhase(String phase) {
        return switch (phase) {
            case "ready" -> "사용 가능"; case "waiting_for_unmetered_network" -> "비종량 네트워크 연결 대기";
            case "downloading" -> "내려받는 중"; case "loading" -> "준비 중"; case "verifying" -> "파일 확인 중";
            case "failed" -> "준비 실패 — 원문·기본 검색은 유지"; default -> "준비 전";
        };
    }

    private void showPermissions() {
        content.removeAllViews();
        LinearLayout panel = scrollPanel();
        panel.addView(label("휴대폰 권한", 20, INK));
        panel.addView(label("기본 권한은 한 번에 요청할 수 있어. 특수권한은 각 Android 설정 화면에서 직접 켜야 해. 허용된 기능만 실제로 사용할 수 있어.", 14, SUBTLE));
        panel.addView(button("기본 권한 요청", this::requestBasicPermissions));
        PermissionCatalog catalog = new PermissionCatalog(this);
        JSONArray permissions = catalog.snapshot();
        for (int i = 0; i < permissions.length(); i++) {
            JSONObject permission = permissions.optJSONObject(i); if (permission == null) continue;
            String id = permission.optString("id");
            String kind = permission.optString("kind");
            boolean unsupported = "unsupported".equals(permission.optString("action")) || "restricted".equals(kind);
            String status = permission.optBoolean("granted") ? "허용됨" : unsupported ? "제한 있음" : "설정 필요";
            panel.addView(button(permission.optString("label") + " · " + status, () -> {
                if ("runtime".equals(kind) && !"background_location".equals(id)) {
                    JSONArray names = permission.optJSONArray("runtimePermissions");
                    java.util.ArrayList<String> missing = new java.util.ArrayList<>();
                    for (int j = 0; names != null && j < names.length(); j++) if (checkSelfPermission(names.optString(j)) != PackageManager.PERMISSION_GRANTED) missing.add(names.optString(j));
                    if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 300); else catalog.openSettings(this, id);
                } else if ("unsupported".equals(permission.optString("action"))) {
                    notice("일반 앱 설치로는 root나 기기 소유자 권한을 얻을 수 없어.");
                } else catalog.openSettings(this, id);
            }));
            String note = permission.optString("notes");
            if (!note.isEmpty()) panel.addView(label(note, 11, SUBTLE));
        }
        panel.addView(label("호영이가 사용할 기능", 20, INK));
        panel.addView(label("Android 권한을 유지하면서 호영이의 특정 기능만 끌 수 있어. 대화에서도 이 설정을 바꿀 수 있어.", 13, SUBTLE));
        JSONArray tools = app.getPhoneTools().catalog();
        JSONArray fileTools = app.getFiles().catalog();
        for (int i = 0; i < fileTools.length(); i++) tools.put(fileTools.optJSONObject(i));
        for (String name : new String[]{"memory.search", "memory.save", "memory.source.read", "memory.history", "memory.review", "tasks.list", "tasks.update", "actions.receipt.read", "memory.asset.analyze", "app.settings.get", "app.settings.update"}) {
            try { tools.put(new JSONObject().put("name", name)); } catch (Exception ignored) { }
        }
        JSONObject allowed = parseObject(app.getStore().getSetting("toolPermissions", "{}"));
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i); if (tool == null) continue;
            String name = tool.optString("name");
            CheckBox check = new CheckBox(this); check.setText(toolLabel(name)); check.setChecked(allowed.optBoolean(name, true));
            check.setOnCheckedChangeListener((button, enabled) -> {
                try { app.getStore().withTransaction(db -> { JSONObject current = parseObject(app.getStore().getSetting("toolPermissions", "{}")); try { current.put(name, enabled); } catch (Exception invalid) { throw new IllegalStateException(invalid); } app.getStore().putSetting("toolPermissions", current.toString()); return null; }); applyPhonePolicy(); app.getPhoneContext().reconcilePermissions(); }
                catch (Exception ignored) { }
            }); panel.addView(check);
        }
    }

    private void requestBasicPermissions() {
        String[] missing = new PermissionCatalog(this).missingRuntimePermissions();
        if (missing.length == 0) notice("요청 가능한 기본 권한은 이미 허용됐어."); else requestPermissions(missing, 300);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] names, int[] results) {
        super.onRequestPermissionsResult(requestCode, names, results);
        if ("권한".equals(tab)) showPermissions();
    }

    private void showFiles() {
        content.removeAllViews(); LinearLayout panel = scrollPanel();
        panel.addView(label("작업 파일", 20, INK));
        panel.addView(label("파일은 폰의 앱 내부 저장소에 보관해. 일반 캐시 삭제에는 남아. 앱 삭제·데이터 삭제 전에 백업을 내보내야 해.", 14, SUBTLE));
        panel.addView(button("사진·파일 가져오기", () -> pickDocuments(PICK_FILES, "*/*")));
        panel.addView(button("대화·기억·파일 백업 내보내기", () -> createDocument(EXPORT_BACKUP, "application/zip", "hoyoung-backup.zip")));
        panel.addView(button("백업 복원", () -> {
            if (isActive()) { notice("진행 중 작업이 있어. 완료를 확인한 뒤 복원해."); return; }
            pickDocuments(IMPORT_BACKUP, "application/zip");
        }));
        try {
            JSONArray files = app.getFiles().listFiles();
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i); if (file == null) continue;
                String id = file.optString("id"), name = file.optString("displayName", id);
                panel.addView(button(name + " · " + (file.optLong("size") / 1024) + " KB", () -> new AlertDialog.Builder(this)
                    .setTitle(name).setItems(new String[]{"대화에 첨부", "외부에 저장", "텍스트 보기"}, (dialog, which) -> {
                        if (which == 0) { if (attachments.length() < 8) attachments.put(id); persistDraft(); showTab("대화"); }
                        else if (which == 1) { exportFileId = id; createDocument(EXPORT_FILE, "application/octet-stream", name); }
                        else io.execute(() -> {
                            try { String text = app.getFiles().readText(id, 20000); main.post(() -> new AlertDialog.Builder(this).setTitle(name).setMessage(text).setPositiveButton("닫기", null).show()); }
                            catch (Exception e) { main.post(() -> notice("이 파일은 텍스트로 열 수 없어.")); }
                        });
                    }).show()));
            }
        } catch (Exception e) { panel.addView(label("파일 목록을 읽지 못했어.", 14, SUBTLE)); }
    }

    private void showSettings() {
        content.removeAllViews(); LinearLayout panel = scrollPanel();
        panel.addView(label("자율 모드", 20, INK));
        panel.addView(label("폰이 켜져 있고 인터넷과 Work 연결이 준비된 동안 주기적으로 판단해. 화면이 꺼져도 호출을 이어가려면 배터리 최적화 예외를 켜야 해. 자율 모드는 배터리 사용을 늘릴 수 있어.", 14, SUBTLE));
        panel.addView(button("자율 모드 켜기", this::startAutonomy));
        panel.addView(button("자율 모드 끄기", () -> { app.getRuntime().stopAutonomy(); stopService(new Intent(this, AutonomyService.class)); refreshStatus(); }));
        EditText interval = new EditText(this); interval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); interval.setHint("판단 간격(초)");
        interval.setText(app.getStore().getSetting("heartbeatSeconds", "300")); panel.addView(interval);
        EditText goals = new EditText(this); goals.setHint("계속 맡길 일과 목표"); goals.setMinLines(3); goals.setText(app.getStore().getSetting("standingGoals", "")); panel.addView(goals);
        panel.addView(label("맞춤 지침", 20, INK));
        panel.addView(button("호영의 기본 운영 지침 보기", () -> {
            try (InputStream input = getAssets().open("core-identity.txt")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                new AlertDialog.Builder(this).setTitle("호영의 기본 운영 지침").setMessage(output.toString(StandardCharsets.UTF_8.name())).setPositiveButton("닫기", null).show();
            } catch (Exception failure) { notice("기본 지침을 불러오지 못했어."); }
        }));
        panel.addView(label("실행 방식", 17, INK));
        panel.addView(label("계속 실행은 화면이 꺼져도 CPU를 유지해서 배터리를 더 써. 작업 중 실행은 쉬는 동안 절전하므로 Android가 판단 시간을 늦출 수 있어. 충전 중 실행은 충전 중에는 유지하고 배터리 사용 중에는 작업에 맞춰 깨어 있어.", 12, SUBTLE));
        android.widget.RadioGroup wakeGroup = new android.widget.RadioGroup(this);
        String[] wakeModes = {"continuous", "while_working", "charging"}, wakeLabels = {"계속 실행 · 자율 판단 우선", "작업 중 실행 · 배터리 우선", "충전 중 계속 실행"};
        String currentWake = app.getStore().getSetting("wakeMode", "continuous");
        for (int i = 0; i < wakeModes.length; i++) { android.widget.RadioButton option = new android.widget.RadioButton(this); option.setId(6000 + i); option.setText(wakeLabels[i]); wakeGroup.addView(option); if (wakeModes[i].equals(currentWake)) option.setChecked(true); }
        wakeGroup.setOnCheckedChangeListener((group, id) -> { int choice = id - 6000; if (choice >= 0 && choice < wakeModes.length) { app.getStore().putSetting("wakeMode", wakeModes[choice]); AutonomyService.reevaluateActiveService(); } }); panel.addView(wakeGroup);
        addSettingToggle(panel, "자동 기억 검토", "memoryReviewEnabled", true);
        addSettingToggle(panel, "허용한 휴대폰 정보 활용", "phoneContextEnabled", true);
        addSettingToggle(panel, "폰 내부 의미 검색", "semanticSearchEnabled", true);
        EditText instructions = new EditText(this); instructions.setMinLines(8); instructions.setGravity(Gravity.TOP);
        instructions.setText(app.getStore().getSetting("customInstructions", bundledInstructions())); panel.addView(instructions);
        panel.addView(button("설정 저장", () -> {
            try {
                int seconds = Integer.parseInt(interval.getText().toString()); if (seconds < 60 || seconds > 86400) throw new IllegalArgumentException();
                String goalText = goals.getText().toString(), instructionText = instructions.getText().toString();
                if (goalText.length() > 50_000 || instructionText.length() > 50_000) throw new IllegalArgumentException();
                app.getStore().withTransaction(db -> {
                    app.getStore().putSetting("heartbeatSeconds", String.valueOf(seconds));
                    app.getStore().putSetting("standingGoals", goalText);
                    app.getStore().putSetting("customInstructions", instructionText); return null;
                });
                notice("지침과 판단 간격을 저장했어.");
            } catch (Exception e) { notice("판단 간격은 60~86,400초, 지침과 목표는 각각 50,000자 이내로 입력해."); }
        }));
        panel.addView(button("한도·연결 상태 다시 확인", () -> { app.getWebController().manualRetryCheck(); refreshStatus(); }));
        panel.addView(button("연결 진단 복사", () -> {
            JSONObject diagnosis = app.getRuntime().state();
            // Progress can contain a streamed answer; diagnostics are state flags only.
            diagnosis.remove("progress");
            try { diagnosis.put("androidSdk", android.os.Build.VERSION.SDK_INT).put("appVersion", getPackageManager().getPackageInfo(getPackageName(), 0).versionName).put("permissions", new PermissionCatalog(this).snapshot()); }
            catch (Exception ignored) { }
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("호영이 연결 진단", diagnosis.toString())); notice("연결·권한 상태만 복사했어. 비밀번호·대화·알림 본문은 포함하지 않아."); }
        }));
        panel.addView(label("확인되지 않은 작업", 20, INK));
        JSONArray requests = app.getStore().listRequests(30);
        for (int i = 0; i < requests.length(); i++) {
            JSONObject request = requests.optJSONObject(i); if (request == null) continue;
            String status = request.optString("status");
            if (!"uncertain".equals(status) && !"blocked".equals(status) && !"quota".equals(status)) continue;
            panel.addView(label(status + " · " + shorten(request.optString("text"), 120), 13, SUBTLE));
            if (!"uncertain".equals(status)) panel.addView(button("선택 확인 후 다시 대기", () -> {
                try { app.getRuntime().retryRequest(request.optLong("id")); showSettings(); }
                catch (Exception e) { notice("이 작업은 지금 다시 시작할 수 없어."); }
            }));
            else {
                panel.addView(button("기존 Work 결과를 확인했음", () -> {
                    try { app.getRuntime().resolveUncertain(request.optLong("id")); showSettings(); }
                    catch (Exception e) { notice("이 작업을 정리하지 못했어."); }
                }));
                panel.addView(button("이 작업을 더 이어가지 않고 제외", () -> new AlertDialog.Builder(this)
                    .setMessage("폰의 대기 목록에서 제외해. 기존 클라우드 작업이 중지됐다는 뜻은 아니며, 같은 행동은 자동으로 다시 실행하지 않아.")
                    .setPositiveButton("제외", (dialog, which) -> {
                        try { app.getRuntime().abandonUncertain(request.optLong("id")); showSettings(); }
                        catch (Exception e) { notice("이 작업을 제외하지 못했어."); }
                    }).setNegativeButton("돌아가기", null).show()));
            }
        }
        panel.addView(button("오픈소스 라이선스", () -> {
            try (InputStream input = getAssets().open("THIRD_PARTY_NOTICES.txt")) {
                new AlertDialog.Builder(this).setTitle("라이선스").setMessage(readUtf8(input)).setPositiveButton("닫기", null).show();
            } catch (Exception ignored) { }
        }));
    }

    private void startAutonomy() {
        app.getRuntime().startAutonomy();
        refreshStatus();
    }

    private boolean isActive() {
        JSONObject state = app.getRuntime().state();
        return !state.isNull("activeRequestId") && !state.optString("activeRequestId").isEmpty();
    }

    private void refreshStatus() {
        if (connection == null) return;
        JSONObject state = app.getRuntime().state();
        JSONObject web = state.optJSONObject("webState");
        if (web == null) web = state.optJSONObject("web");
        String code = state.optString("lastCode", state.optString("status", ""));
        if (state.optBoolean("restoringBackup")) connection.setText("백업 복원 중 · 자동 실행을 멈췄어");
        else if (state.optBoolean("quotaBlocked")) connection.setText("사용 한도 대기 · 메시지와 작업은 폰에 저장돼");
        else if (state.optBoolean("uncertaintyBlocked")) connection.setText("이전 작업 결과 확인 필요 · 자동 재전송을 멈췄어");
        else if (isActive()) connection.setText("작업 중 · 새 메시지는 저장해서 이어서 전달해");
        else if ("prompt_too_large".equals(code)) connection.setText("문맥이 너무 길어 전송을 멈췄어 · 원문은 폰에 남아 있어");
        else if ("ATTACHMENT_NOT_READY".equals(code) || code.startsWith("attachment_")) connection.setText("첨부를 확인하지 못해 전송을 멈췄어");
        else if (state.optBoolean("ready")) connection.setText("Work 연결 확인 · GPT-6.1 Sol · Medium · Fast");
        else if (web != null && web.optBoolean("loading")) connection.setText("Work 페이지 로딩 중");
        else connection.setText(switch (code) {
            case "work_unverified" -> "Work 선택을 확인해 줘";
            case "temporary_unverified" -> "임시 채팅 선택을 확인해 줘";
            case "model_unverified" -> "GPT-6.1 Sol 선택을 확인해 줘";
            case "effort_unverified" -> "Medium 선택을 확인해 줘";
            case "speed_unverified" -> "Fast 선택을 확인해 줘";
            case "composer_unverified" -> "Work 입력창을 확인하지 못했어";
            default -> "연결 확인 필요 · Work 탭에서 로그인과 선택 상태를 확인해";
        });
    }

    private void pickDocuments(int code, String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, code == PICK_FILES);
        startActivityForResult(intent, code);
    }

    private void createDocument(int code, String mime, String name) {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name), code);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == WORK_FILE_PICKER) {
            java.util.ArrayList<Uri> items = selectedUris(result, data);
            app.getWebController().receiveFileChooser(items.isEmpty() ? null : items.toArray(new Uri[0])); return;
        }
        if (result != RESULT_OK || data == null) return;
        if (code == PICK_FILES) {
            java.util.ArrayList<Uri> uris = selectedUris(result, data);
            io.execute(() -> {
                try {
                    JSONArray saved = new JSONArray();
                    for (Uri uri : uris) {
                        if (saved.length() + attachments.length() >= 8) break;
                        JSONObject file = app.getFiles().saveFromUri(uri, null);
                        app.getStore().putSetting("fileMeta:" + file.getString("id"), file.toString()); saved.put(file.getString("id"));
                    }
                    main.post(() -> {
                        for (int i = 0; i < saved.length(); i++) attachments.put(saved.optString(i));
                        persistDraft();
                        if ("파일".equals(tab)) showFiles(); else if (attachmentLabel != null) attachmentLabel.setText(attachmentSummary());
                    });
                } catch (Exception e) { main.post(() -> notice("파일을 저장하지 못했어. 파일 크기와 접근 권한을 확인해.")); }
            }); return;
        }
        Uri uri = data.getData(); if (uri == null) return;
        io.execute(() -> {
            try {
                if (code == EXPORT_BACKUP) try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) { if (output == null) throw new java.io.IOException(); app.getFiles().exportBackup(output, app.getStore()); }
                else if (code == IMPORT_BACKUP) {
                    if (isActive()) throw new java.io.IOException("Active job");
                    try (InputStream input = getContentResolver().openInputStream(uri)) { if (input == null) throw new java.io.IOException(); app.getRuntime().restoreBackup(input); }
                } else if (code == EXPORT_FILE) try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) { if (output == null) throw new java.io.IOException(); app.getFiles().exportFile(exportFileId, output); }
                main.post(() -> { notice(code == IMPORT_BACKUP ? "백업을 복원했어. 불명확한 작업은 자동으로 반복하지 않아." : "선택한 위치에 저장했어."); if ("파일".equals(tab)) showFiles(); });
            } catch (Exception e) { main.post(() -> notice("저장·복원을 완료하지 못했어. 기존 폰 데이터를 확인해.")); }
        });
    }

    private java.util.ArrayList<Uri> selectedUris(int result, Intent data) {
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        if (result != RESULT_OK || data == null) return uris;
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        return uris;
    }

    private LinearLayout scrollPanel() {
        ScrollView scroll = new ScrollView(this); LinearLayout panel = column(); panel.setPadding(0, dp(12), 0, dp(12)); scroll.addView(panel);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); return panel;
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private TextView label(String text, int size, int color) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); return view; }
    private Button button(String text, Runnable action) { Button button = new Button(this); button.setText(text); button.setTextColor(BLUE); button.setAllCaps(false); button.setOnClickListener(v -> action.run()); return button; }
    private GradientDrawable round(int color) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(18)); return drawable; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void notice(String text) { new AlertDialog.Builder(this).setMessage(text).setPositiveButton("확인", null).show(); }
    private String attachmentSummary() { return attachments.length() == 0 ? "" : "첨부 " + attachments.length() + "개 · 폰 내부에 저장됨"; }
    private String shorten(String text, int max) { return text.length() <= max ? text : text.substring(0, max) + "…"; }
    private JSONArray parseArray(String value) { try { return new JSONArray(value); } catch (Exception invalid) { return new JSONArray(); } }
    private JSONObject parseObject(String value) { try { return new JSONObject(value); } catch (Exception e) { return new JSONObject(); } }
    private String bundledInstructions() { try (InputStream input = getAssets().open("personalized-instructions.txt")) { return readUtf8(input); } catch (Exception e) { return ""; } }
    private void persistDraft() {
        if (app == null || app.getRuntime().isRestoringBackup()) return;
        if (composer != null) app.getStore().putSetting("ui.draft", composer.getText().toString());
        app.getStore().putSetting("ui.attachments", attachments.toString());
    }
    private String readUtf8(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) { if (output.size() + count > 1_000_000) throw new IllegalArgumentException("Asset too large"); output.write(buffer, 0, count); }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
    private String toolLabel(String name) {
        return switch (name) {
            case "phone_device_info" -> "휴대폰 상태 확인"; case "phone_apps_list" -> "설치 앱 목록"; case "phone_app_launch" -> "앱 열기";
            case "phone_contacts_read" -> "주소록 읽기"; case "phone_contacts_create" -> "주소록 추가"; case "phone_calendar_read" -> "일정 읽기"; case "phone_calendar_create" -> "일정 추가";
            case "phone_notifications_read" -> "알림 읽기"; case "phone_location_last" -> "최근 위치 확인"; case "phone_volume_set" -> "음량 변경"; case "phone_flashlight_set" -> "손전등 제어";
            case "phone_clipboard_read" -> "클립보드 읽기"; case "phone_ui_snapshot" -> "화면 구성 확인"; case "phone_ui_click" -> "화면 버튼 누르기"; case "phone_ui_global" -> "홈·뒤로·알림 이동";
            case "phone_ui_tap" -> "화면 터치"; case "phone_ui_swipe" -> "화면 밀기"; case "phone_brightness_set" -> "화면 밝기 변경"; case "phone_dial_open" -> "전화번호 입력 화면 열기";
            case "phone_files_list" -> "휴대폰 공유 파일 목록"; case "phone_files_read" -> "휴대폰 공유 파일 읽기"; case "phone_files_write" -> "휴대폰 공유 파일 쓰기";
            case "files.list" -> "작업 파일 목록"; case "files.read" -> "작업 파일 읽기"; case "files.write" -> "작업 파일 쓰기";
            case "memory.search" -> "저장된 기억 검색"; case "memory.save" -> "기억 추가·수정"; case "app.settings.get" -> "앱 설정 확인"; case "app.settings.update" -> "대화에서 앱 설정 변경";
            default -> name;
        };
    }
}
