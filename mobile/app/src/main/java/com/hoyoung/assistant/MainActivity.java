package com.hoyoung.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Rect;
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
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import com.hoyoung.assistant.ui.ChatPalette;
import com.hoyoung.assistant.ui.LineIconDrawable;
import com.hoyoung.assistant.ui.HoyoungAvatarView;
import com.hoyoung.assistant.ui.TypingIndicatorView;
import com.hoyoung.assistant.phone.PermissionOnboarding;
import com.hoyoung.assistant.store.EmojiReactionReply;
import com.hoyoung.assistant.store.AccountStore;
import com.hoyoung.assistant.store.AssistantIdentity;
import com.hoyoung.assistant.ui.AttachmentPreview;
import com.hoyoung.assistant.ui.ChatAttachmentPreviews;
import com.hoyoung.assistant.runtime.AssistantReplyNotifications;
import com.hoyoung.assistant.runtime.OwnerDraftStore;
import com.hoyoung.assistant.runtime.UiAutomationProvenance;
import com.hoyoung.assistant.store.BackupFailure;
import com.hoyoung.assistant.store.SourceRecordVisibility;

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
    private static final int BLUE = Color.rgb(104, 74, 232);
    private static final int INK = Color.rgb(31, 30, 40);
    private static final int SUBTLE = Color.rgb(125, 122, 139);
    private static final int BG = Color.rgb(250, 249, 252);
    private static final int PICK_FILES = 201, EXPORT_BACKUP = 202, IMPORT_BACKUP = 203, WORK_FILE_PICKER = 204, EXPORT_FILE = 205, OWNER_FOLDER = 206, PICK_PROFILE = 207;
    private TextView ownerFolderState;
    private TextView usageBudgetState;
    private TextView workConnectionDetails;
    private ImageButton headerOptions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final ExecutorService display = Executors.newSingleThreadExecutor();
    private final ExecutorService fileListing = Executors.newSingleThreadExecutor();
    private final Runnable runtimeRefreshListener = () -> main.post(() -> { if(isFinishing()||isDestroyed())return;refreshStatus(); if("대화".equals(MainActivity.this.tab))refreshMessages(); });
    private ChatAttachmentPreviews photoPreviews;
    private final java.util.IdentityHashMap<AlertDialog, Bitmap> expandedPhotos = new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<TextView, JSONObject> replyViews = new java.util.IdentityHashMap<>();
    private boolean resumed;
    private OwnerDraftStore draftStore;
    private OwnerDraftStore.Snapshot draftState;
    private boolean applyingDraft, messagesLoading, draftSaveFailed, sourceControlBusy;
    private long chatBeforeId, nextBeforeId, chatGeneration;
    private LinearLayout chatNavigation;
    private HoyoungApplication app;
    private LinearLayout root, content;
    private TextView connection, screenTitle, typingLabel;
    private FrameLayout shell, drawerOverlay;
    private LinearLayout drawer, typingRow;
    private View headerAvatar, statusDot;
    private ImageButton sendButton;
    private TypingIndicatorView typingDots;
    private PermissionOnboarding permissionOnboarding;
    private boolean permissionBlocked, connectedUiStarted;
    private final java.util.LinkedHashMap<Long, View> messageRows = new java.util.LinkedHashMap<>();
    private final java.util.HashMap<Long, String> messageSignatures = new java.util.HashMap<>();
    private String tab = "대화", exportFileId;
    private JSONArray attachments = new JSONArray();
    private EditText composer;
    private TextView attachmentLabel, modelSelector;
    private String renderedIdentity = "", pendingProfileRevision;
    private boolean screenReceiverRegistered;
    private final android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context,Intent intent) {
            if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && app.getRuntime()!=null)app.getRuntime().leaveForegroundRouting();
        }
    };
    private long displayedMessageId = -1;
    private boolean sending;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            if ("대화".equals(tab)) refreshMessages();
            main.postDelayed(this, 1500);
        }
    };

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){UiAutomationProvenance.observeTouch(this,event);return super.dispatchTouchEvent(event);}
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event){UiAutomationProvenance.observeKey(this,event);return super.dispatchKeyEvent(event);}

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (HoyoungApplication) getApplication();
        if(state!=null)pendingProfileRevision=state.getString("pendingProfileRevision");
        android.content.IntentFilter screenFilter=new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(screenReceiver,screenFilter,android.content.Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver,screenFilter);
        screenReceiverRegistered=true;
        if (!app.isStartupReady()) { showStartupRecovery(); return; }
        initializeReadyUi();
    }

    private boolean uiReady;
    private final Runnable startupRefresh=new Runnable(){
        @Override public void run(){
            if(isDestroyed()||isFinishing()||uiReady)return;
            if(app.isStartupReady()){initializeReadyUi();if(resumed)resumeReadyUi();}
            else if("source_deletion_reconciling".equals(app.startupState().optString("status")))main.postDelayed(this,500);
            else showStartupRecovery();
        }
    };
    private void initializeReadyUi() {
        if (uiReady || !app.isStartupReady()) return;
        uiReady = true;
        draftStore = new OwnerDraftStore(app.getStore());
        photoPreviews = new ChatAttachmentPreviews(this, app.getFiles());
        if (!app.getRuntime().isRestoringBackup()) { draftState = draftStore.read(); attachments = draftState.attachments(); }
        if(android.os.Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        shell = new FrameLayout(this); shell.setBackgroundColor(BG);
        root = column(); root.setBackgroundColor(BG);
        shell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        buildHeader();
        content = column(); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(shell); root.requestApplyInsets();
        permissionOnboarding = new PermissionOnboarding(this);
        app.getRuntime().setListener(runtimeRefreshListener);
        if (!checkPermissionGate()) activateConnectedUi();
    }

    private void activateConnectedUi() {
        if (!connectedUiStarted) {
            connectedUiStarted = true;
            app.getWebController().bindActivity(this);
            app.getWebController().start();
            if (!app.getRuntime().isRestoringBackup() && "true".equals(app.getStore().getSetting("autonomy.enabled", "false"))) startAutonomy();
        }
        showTab("대화"); openReplyIntent(getIntent());
    }

    private void buildHeader() {
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(12), dp(8), dp(12), dp(8));
        header.setBackgroundColor(BG); header.setMinimumHeight(dp(76));
        header.addView(icon(LineIconDrawable.Icon.MENU, "메뉴 열기", this::openDrawer), new LinearLayout.LayoutParams(dp(44), dp(48)));
        headerAvatar = new HoyoungAvatarView(this);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(42), dp(42)); avatarParams.setMargins(dp(6),0,dp(12),0); header.addView(headerAvatar,avatarParams);
        LinearLayout titles=column(); screenTitle=identityLabel("",19,INK);screenTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium",Typeface.NORMAL));titles.addView(screenTitle);
        LinearLayout status=row();status.setGravity(Gravity.CENTER_VERTICAL);
        statusDot=new View(this);statusDot.setBackground(round(Color.rgb(177,171,191)));LinearLayout.LayoutParams dot=new LinearLayout.LayoutParams(dp(5),dp(5));dot.setMargins(0,0,dp(5),0);status.addView(statusDot,dot);
        connection=label("연결 준비 중",11,SUBTLE);connection.setSingleLine(true);connection.setEllipsize(android.text.TextUtils.TruncateAt.END);status.addView(connection,new LinearLayout.LayoutParams(-1,-2));titles.addView(status);
        header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));titles.setOnClickListener(v->showConnectionDetails());
        headerOptions=icon(LineIconDrawable.Icon.MORE,"대화 옵션",this::showConversationOptions);
        header.addView(headerOptions,new LinearLayout.LayoutParams(dp(44),dp(48)));
        root.addView(header);View divider=new View(this);divider.setBackgroundColor(Color.rgb(237,234,243));root.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
    }

    private ImageButton icon(LineIconDrawable.Icon name,String description,Runnable action) {
        ImageButton result=ChatPalette.iconButton(this,name,description,INK);result.setOnClickListener(v->action.run());return result;
    }

    private void openDrawer() {
        if (permissionBlocked) { showPermissionOnboarding(); return; }
        if(drawerOverlay!=null)return;
        drawerOverlay=new FrameLayout(this);drawerOverlay.setBackgroundColor(Color.argb(70,20,17,37));drawerOverlay.setContentDescription("메뉴");
        drawerOverlay.setOnClickListener(v->closeDrawer());
        drawer=column();drawer.setClickable(true);drawer.setBackgroundColor(BG);drawer.setPadding(dp(22),dp(40),dp(18),dp(24));
        int width=Math.min(dp(328),getResources().getDisplayMetrics().widthPixels-dp(40));
        FrameLayout.LayoutParams panelParams=new FrameLayout.LayoutParams(width,-1,Gravity.START);android.widget.ScrollView drawerScroll=new android.widget.ScrollView(this);drawerScroll.setFillViewport(true);drawerScroll.setVerticalScrollBarEnabled(false);drawerScroll.setBackgroundColor(BG);drawerScroll.addView(drawer,new android.widget.ScrollView.LayoutParams(-1,-1));drawerOverlay.addView(drawerScroll,panelParams);
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);top.addView(new HoyoungAvatarView(this),new LinearLayout.LayoutParams(dp(52),dp(52)));
        LinearLayout identity=column();identity.setPadding(dp(14),0,0,0);TextView name=identityLabel("",24,INK);name.setTypeface(null,Typeface.BOLD);identity.addView(name);identity.addView(label("우리의 대화, 이어지는 기억",12,SUBTLE));top.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(icon(LineIconDrawable.Icon.CLOSE,"메뉴 닫기",this::closeDrawer),new LinearLayout.LayoutParams(dp(40),dp(48)));drawer.addView(top);
        TextView section=label("MY SPACE",10,SUBTLE);section.setLetterSpacing(.12f);section.setPadding(dp(12),dp(32),0,dp(12));drawer.addView(section);
        String[] names={"대화","Work","기억","계정","파일","권한","설정"};
        String[] hints={"오늘도 편하게 이야기해","ChatGPT 연결과 작업 공간","쌓일수록 더 나다운 기억","로그인 정보와 수정 이력","사진, 문서와 백업","휴대폰 접근과 기능 허용","사용자 지침과 모델"};
        LineIconDrawable.Icon[] icons={LineIconDrawable.Icon.CHAT,LineIconDrawable.Icon.WORK,LineIconDrawable.Icon.MEMORY,LineIconDrawable.Icon.EDIT,LineIconDrawable.Icon.FOLDER,LineIconDrawable.Icon.SHIELD,LineIconDrawable.Icon.SETTINGS};
        for(int i=0;i<names.length;i++) {
            String destination=names[i];boolean selected=destination.equals(tab);LinearLayout item=row();item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(14),dp(13),dp(12),dp(13));item.setMinimumHeight(dp(72));item.setBackground(ChatPalette.ripple(this,selected?Color.rgb(238,233,255):BG,20));
            ImageView symbol=new ImageView(this);symbol.setImageDrawable(new LineIconDrawable(icons[i],selected?BLUE:SUBTLE,getResources().getDisplayMetrics().density));item.addView(symbol,new LinearLayout.LayoutParams(dp(24),dp(24)));
            LinearLayout copy=column();copy.setPadding(dp(15),0,0,0);TextView title=label(destination,16,selected?BLUE:INK);title.setTypeface(null,Typeface.BOLD);copy.addView(title);copy.addView(label(hints[i],11,SUBTLE));item.addView(copy,new LinearLayout.LayoutParams(0,-2,1));item.setContentDescription(destination+" · "+hints[i]);item.setOnClickListener(v->{closeDrawer();showTab(destination);});drawer.addView(item);
        }
        drawer.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        TextView footer=label("이 폰에 보관되는 대화와 기억",12,SUBTLE);footer.setPadding(dp(12),dp(20),0,0);drawer.addView(footer);
        shell.addView(drawerOverlay,new FrameLayout.LayoutParams(-1,-1));
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if(app.getReplyNotifications()!=null)app.getReplyNotifications().setConversationVisible(false);
        drawer.setTranslationX(-width);drawer.animate().translationX(0).setDuration(android.animation.ValueAnimator.areAnimatorsEnabled()?240:0).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private void closeDrawer() {
        if(drawerOverlay==null)return;FrameLayout old=drawerOverlay;drawerOverlay=null;drawer=null;shell.removeView(old);root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if(app.getReplyNotifications()!=null)app.getReplyNotifications().setConversationVisible(resumed&&!permissionBlocked&&"대화".equals(tab));markVisibleReplies();
    }

    @Override public void onBackPressed() {
        if(drawerOverlay!=null){closeDrawer();return;}
        if(!permissionBlocked&&!"대화".equals(tab)){showTab("대화");return;}
        super.onBackPressed();
    }

    private void showConversationOptions() {
        if(permissionBlocked){showPermissionOnboarding();return;}
        if("Work".equals(tab)){showWorkOptions();return;}
        new ObservedDialogBuilder(this).setTitle("대화 옵션").setItems(new String[]{"모델·응답 설정","Work 연결 상태","현재 작업 중지"},(dialog,which)->{
            if(which==0)showTab("설정");else if(which==1)showTab("Work");else commands.execute(()->{app.getRuntime().pauseCurrentWork("사용자가 대화 옵션에서 현재 작업 중지 선택");main.post(this::refreshStatus);});
        }).setNegativeButton("닫기",null).show();
    }

    private void showConnectionDetails() {
        if(permissionBlocked){showPermissionOnboarding();return;}
        JSONObject state=app.getRuntime().state();
        if("Work".equals(tab)) {
            JSONObject web=state.optJSONObject("webState");if(web==null)web=state.optJSONObject("web");
            String code=state.optBoolean("ready")?"ready":state.optString("readinessCode",state.optString("lastCode"));
            new ObservedDialogBuilder(this).setTitle("Work 연결 상세")
                .setMessage(com.hoyoung.assistant.ui.WorkConnectionStatus.describe(code,web))
                .setPositiveButton("진단 복사",(d,w)->copyConnectionDiagnosis()).setNegativeButton("닫기",null).show();
            return;
        }
        new ObservedDialogBuilder(this).setTitle(assistantName()+"의 현재 상태").setMessage(connection.getContentDescription()+"\n\n모델 "+state.optString("model")+"\n추론 "+state.optString("effort")+" · 응답 "+state.optString("speed"))
                .setPositiveButton("Work 열기",(d,w)->showTab("Work")).setNegativeButton("닫기",null).show();
    }

    private boolean checkPermissionGate() {
        if(!uiReady||permissionOnboarding==null)return false;
        boolean wasBlocked=permissionBlocked;
        permissionBlocked=!permissionOnboarding.snapshot().ready();
        if(permissionBlocked){
            connectedUiStarted=false;
            if(typingDots!=null)typingDots.setActive(false);
            if(app.getReplyNotifications()!=null)app.getReplyNotifications().setConversationVisible(false);
            closeDrawer();showPermissionOnboarding();return true;
        }
        if(wasBlocked)activateConnectedUi();
        return false;
    }

    private void showPermissionOnboarding() {
        if(!uiReady||permissionOnboarding==null)return;
        PermissionOnboarding.Snapshot state=permissionOnboarding.snapshot();
        permissionBlocked=!state.ready();
        tab="권한 설정";screenTitle.setText("시작할 준비");headerAvatar.setVisibility(View.GONE);
        connection.setText("권한 "+state.grantedCount+" / "+state.requiredCount);connection.setContentDescription("필수 권한을 모두 허용하면 대화를 시작할 수 있어");
        chatGeneration++;if(photoPreviews!=null)photoPreviews.clear();replyViews.clear();messageRows.clear();messageSignatures.clear();
        if(typingDots!=null)typingDots.setActive(false);typingDots=null;typingRow=null;composer=null;sendButton=null;
        detachWebView();content.removeAllViews();LinearLayout panel=scrollPanel();
        HoyoungAvatarView avatar=new HoyoungAvatarView(this);LinearLayout.LayoutParams av=new LinearLayout.LayoutParams(dp(64),dp(64));av.setMargins(0,dp(12),0,dp(22));panel.addView(avatar,av);
        TextView title=label("먼저, 내 폰과\n연결해 볼까",29,INK);title.setTypeface(null,Typeface.BOLD);panel.addView(title);
        panel.addView(label("대화를 시작하기 전에 아래 권한을 모두 허용해 줘. 각 권한으로 무엇을 하는지 확인하고 Android 설정에서 직접 켤 수 있어.",14,SUBTLE));
        TextView progress=label(state.grantedCount+" / "+state.requiredCount+" 준비 완료",13,BLUE);progress.setPadding(0,dp(18),0,dp(8));panel.addView(progress);
        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(Math.max(1,state.requiredCount));bar.setProgress(state.grantedCount);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));panel.addView(bar,new LinearLayout.LayoutParams(-1,dp(6)));
        if(!state.ready())panel.addView(primaryButton("다음 권한 허용",()->requestOnboardingPermission(null)));
        for(PermissionOnboarding.Item permission:state.items){
            if(!permission.required||permission.granted)continue;
            LinearLayout card=column();card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(ChatPalette.outlined(this,Color.WHITE,Color.rgb(235,231,241),20));
            TextView name=label(permission.title,16,INK);name.setTypeface(null,Typeface.BOLD);card.addView(name);TextView detail=label(permission.detail,12,SUBTLE);detail.setPadding(0,dp(5),0,dp(10));card.addView(detail);
            card.addView(button("허용하기",()->requestOnboardingPermission(permission.id)));panel.addView(card);
        }
        LinearLayout completed=column();for(PermissionOnboarding.Item permission:state.items)if(permission.required&&permission.granted)completed.addView(label("✓  "+permission.title,14,INK));
        if(completed.getChildCount()>0)panel.addView(disclosure("허용된 권한",completed,false));
        LinearLayout unavailable=column();for(PermissionOnboarding.Item permission:state.items)if(!permission.required){unavailable.addView(label(permission.title,14,INK));unavailable.addView(label(permission.detail,12,SUBTLE));}
        if(unavailable.getChildCount()>0)panel.addView(disclosure("기기 제한·지원 여부 확인",unavailable,false));
        Button proceed=primaryButton("호영과 대화 시작",()->{if(!checkPermissionGate())activateConnectedUi();});proceed.setEnabled(state.ready());proceed.setAlpha(state.ready()?1f:.4f);panel.addView(proceed);
        panel.addView(button("권한 상태 다시 확인",()->{if(!checkPermissionGate())activateConnectedUi();}));
        panel.addView(label("권한을 허용한 뒤에도 사이드바의 권한 메뉴에서 호영이 사용할 기능을 개별적으로 바꿀 수 있어.",12,SUBTLE));
    }

    private void requestOnboardingPermission(String id){
        PermissionOnboarding.RequestResult result=id==null?permissionOnboarding.requestNext(this):permissionOnboarding.request(this,id);
        if(!result.launched&&!result.message.isEmpty())notice(result.message);
    }

    private void showStartupRecovery() {
        main.removeCallbacks(startupRefresh);
        LinearLayout panel = column(); panel.setBackgroundColor(BG); panel.setPadding(dp(20), dp(32), dp(20), dp(20));
        JSONObject diagnosis = app.startupState();
        if("source_deletion_reconciling".equals(diagnosis.optString("status"))) {
            panel.addView(label("호영 · 기억을 준비하고 있어",23,INK));
            panel.addView(label("이전에 삭제한 기록이 다시 사용되지 않도록 확인 중이야. 완료하면 대화를 열게.",16,INK));
            setContentView(panel);main.postDelayed(startupRefresh,500);return;
        }
        panel.addView(label("호영 · 원문 복구 확인 필요", 23, INK));
        panel.addView(label(diagnosis.optBoolean("recoveryPending")
                ? "원문 복구를 완료하지 못했어. 이전 파일과 복원 기록을 보존하고 새 저장소 생성과 자동 작업을 멈췄어."
                : "폰 저장소를 열지 못해서 자동 작업을 멈췄어. 저장 공간과 저장소 접근을 확인해 줘.", 16, INK));
        panel.addView(label("앱 데이터를 지우지 말고, 저장 공간을 확보한 뒤 복구를 다시 확인해 줘.", 14, SUBTLE));
        Button retry = button("원문 복구 다시 확인", () -> { });
        retry.setOnClickListener(view -> {
            retry.setEnabled(false);
            panel.addView(label("원문 복구 상태를 확인하고 있어…", 14, SUBTLE));
            io.execute(() -> {
                boolean failed = false;
                try { app.retryStartupRecovery(); }
                catch (RuntimeException unavailable) { failed = true; }
                final boolean retryFailed = failed;
                main.post(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    if (app.isStartupReady()) { initializeReadyUi(); if (resumed) resumeReadyUi(); }
                    else { showStartupRecovery(); if (retryFailed) notice("복구 준비를 완료하지 못했어. 원문을 초기화하지 않았어."); }
                });
            });
        });
        panel.addView(retry); setContentView(panel);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!uiReady) return;
        if (checkPermissionGate()) return;
        if (!connectedUiStarted) activateConnectedUi();
        resumeReadyUi();
    }

    private void resumeReadyUi() {
        if(permissionBlocked)return;
        app.getRuntime().enterForegroundRouting();
        if (app.getReplyNotifications() != null) app.getReplyNotifications().setConversationVisible(drawerOverlay==null && "대화".equals(tab) && !app.getRuntime().isRestoringBackup());
        app.getWebController().bindActivity(this);
        main.removeCallbacks(refresh); main.post(refresh);
        if (!app.getRuntime().isRestoringBackup()) { app.getPhoneContext().reconcilePermissions(); app.maintainMemory(); }
        if ("권한".equals(tab)) showPermissions();
        if(app.getRuntime().autonomyEnabled()&&!app.getRuntime().state().optBoolean("serviceRunning")&&!app.getRuntime().isRestoringBackup())startAutonomy();
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); openReplyIntent(intent); }

    private void openReplyIntent(Intent intent) {
        if (!uiReady) return;
        if (intent == null || !intent.getBooleanExtra(AssistantReplyNotifications.EXTRA_OPEN_CONVERSATION, false)) return;
        long id = intent.getLongExtra(AssistantReplyNotifications.EXTRA_SOURCE_ID, 0);
        String hash = intent.getStringExtra(AssistantReplyNotifications.EXTRA_SOURCE_HASH);
        intent.removeExtra(AssistantReplyNotifications.EXTRA_OPEN_CONVERSATION);
        if (id <= 0 || id == Long.MAX_VALUE || hash == null || !hash.matches("[0-9a-f]{64}")) return;
        display.execute(() -> {
            try {
                JSONObject page = app.getStore().boundedChatMessagePage(id + 1, 1, 1);
                JSONObject source = page.optJSONArray("messages").optJSONObject(0);
                if (source == null || source.optLong("id") != id || !hash.equals(source.optString("content_hash")) || "user".equals(source.optString("role"))) return;
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || app.getRuntime().isRestoringBackup()) return;
                    showTab("대화"); chatBeforeId = id + 1; displayedMessageId = -1; chatGeneration++; refreshMessages();
                });
            } catch (RuntimeException unavailable) { /* A stale notification does not certify read or restored-source identity. */ }
        });
    }

    @Override protected void onPause() {
        resumed = false;
        if(typingDots!=null)typingDots.setActive(false);
        if (app.getReplyNotifications() != null) app.getReplyNotifications().setConversationVisible(false);
        if (photoPreviews != null) photoPreviews.clear();
        displayedMessageId = -1;
        main.removeCallbacks(refresh); super.onPause();
    }

    @Override protected void onStop() {
        // Configuration recreation is still the same visible-app session. Home, another app,
        // the document picker and screen-off end only the selection for FUTURE submissions.
        if(!isChangingConfigurations() && app.getRuntime()!=null)app.getRuntime().leaveForegroundRouting();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);state.putString("pendingProfileRevision",pendingProfileRevision);
    }

    @Override protected void onDestroy() {
        if(screenReceiverRegistered){unregisterReceiver(screenReceiver);screenReceiverRegistered=false;}
        main.removeCallbacks(refresh);
        if (app.getRuntime() != null) app.getRuntime().clearListener(runtimeRefreshListener);
        if (photoPreviews != null) photoPreviews.close();
        for (AlertDialog dialog : new java.util.ArrayList<>(expandedPhotos.keySet())) dialog.dismiss();
        replyViews.clear();
        if (app.getWebController() != null) { app.getWebController().unbindActivity(this); detachWebView(); }
        io.shutdown();
        commands.shutdown(); display.shutdownNow(); fileListing.shutdownNow();
        super.onDestroy();
    }

    private void showTab(String name) {
        if (!uiReady) { showStartupRecovery(); return; }
        if (permissionBlocked) { showPermissionOnboarding(); return; }
        if (app.getRuntime().isRestoringBackup()) {
            if (photoPreviews != null) photoPreviews.clear();
            if (app.getReplyNotifications() != null) app.getReplyNotifications().setConversationVisible(false);
            boolean recoveryBlocked = app.getRuntime().state().optBoolean("storageRecoveryBlocked");
            content.removeAllViews(); content.addView(label(recoveryBlocked ? "복원 파일 확인이 남아 있어. 원문과 복원 기록은 보존했으니 앱 데이터를 지우지 말고 앱을 다시 열어 복구해 줘." : "백업을 검증·복원하고 있어. 원문과 파일을 일관된 상태로 되살린 뒤 다시 사용할 수 있어.", 16, INK));
            return;
        }
        chatGeneration++;
        if (photoPreviews != null) photoPreviews.clear();
        replyViews.clear(); messageRows.clear(); messageSignatures.clear();
        if(typingDots!=null)typingDots.setActive(false);typingDots=null;typingRow=null;
        detachWebView(); content.removeAllViews(); composer = null; modelSelector=null; sendButton=null;attachmentLabel=null;tab = name;
        screenTitle.setText("대화".equals(name)?assistantName():name);
        headerAvatar.setVisibility("대화".equals(name)?View.VISIBLE:View.GONE);
        headerOptions.setContentDescription("Work".equals(name)?"연결 옵션":"대화 옵션");
        if (app.getReplyNotifications() != null) app.getReplyNotifications().setConversationVisible(resumed && "대화".equals(name));
        switch (name) {
            case "Work": showWork(); break;
            case "권한": showPermissions(); break;
            case "파일": showFiles(); break;
            case "기억": showMemory(); break;
            case "계정": showAccounts(); break;
            case "설정": showSettings(); break;
            default: showChat();
        }
        refreshStatus();
    }

    private void detachWebView() {
        WebView view = app.getWebController().getExistingView();
        if (view == null) return;
        if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
    }

    private LinearLayout messageList;
    private ScrollView messageScroll;
    private void showChat() {
        draftState=draftStore.read();attachments=draftState.attachments();chatBeforeId=0;nextBeforeId=0;
        chatNavigation=row();chatNavigation.setPadding(dp(12),0,dp(12),0);content.addView(chatNavigation);
        messageScroll=new ScrollView(this);messageScroll.setFillViewport(true);messageScroll.setVerticalScrollBarEnabled(false);messageScroll.setClipToPadding(false);
        messageScroll.getViewTreeObserver().addOnScrollChangedListener(()->{photoPreviews.updateViewport();markVisibleReplies();});
        messageList=column();messageList.setPadding(dp(16),dp(12),dp(16),dp(14));messageScroll.addView(messageList);
        content.addView(messageScroll,new LinearLayout.LayoutParams(-1,0,1));
        typingRow=row();typingRow.setGravity(Gravity.CENTER_VERTICAL);typingRow.setPadding(dp(20),0,dp(16),dp(8));
        HoyoungAvatarView typingAvatar=new HoyoungAvatarView(this);typingRow.addView(typingAvatar,new LinearLayout.LayoutParams(dp(24),dp(24)));
        typingDots=new TypingIndicatorView(this);LinearLayout.LayoutParams dots=new LinearLayout.LayoutParams(dp(56),dp(32));dots.setMargins(dp(8),0,dp(6),0);typingDots.setBackground(round(Color.rgb(239,236,244)));typingRow.addView(typingDots,dots);
        typingLabel=label("답변을 준비하고 있어",11,SUBTLE);typingRow.addView(typingLabel);typingRow.setVisibility(View.GONE);content.addView(typingRow);
        LinearLayout composerArea=column();composerArea.setBackgroundColor(Color.WHITE);composerArea.setPadding(dp(12),dp(10),dp(12),dp(12));
        attachmentLabel=label(attachmentSummary(),12,BLUE);attachmentLabel.setPadding(dp(12),dp(8),dp(12),dp(8));attachmentLabel.setBackground(round(Color.rgb(241,237,255)));composerArea.addView(attachmentLabel);
        attachmentLabel.setOnClickListener(v->{if(attachments.length()==0)return;new ObservedDialogBuilder(this).setMessage("선택한 첨부를 입력창에서 지울까? 보관된 원본은 남아 있어.").setPositiveButton("첨부 선택 지우기",(d,w)->{attachments=new JSONArray();persistDraft();updateComposerState();}).setNegativeButton("유지",null).show();});
        LinearLayout entry=column();entry.setPadding(dp(10),dp(4),dp(6),dp(5));entry.setBackground(ChatPalette.rounded(this,Color.rgb(245,243,249),28));
        composer=new EditText(this);composer.setTextSize(15);composer.setTextColor(INK);composer.setHintTextColor(SUBTLE);composer.setHint("메시지 보내기…");composer.setBackgroundColor(Color.TRANSPARENT);composer.setPadding(dp(8),dp(10),dp(8),dp(6));
        composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setMaxLines(5);composer.setText(draftState.text);composer.setContentDescription("메시지 입력");
        composer.addTextChangedListener(new android.text.TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){if(!applyingDraft)persistDraft();updateComposerState();}
            @Override public void afterTextChanged(android.text.Editable s){}
        });
        entry.addView(composer,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout composerControls=row();composerControls.setGravity(Gravity.CENTER_VERTICAL);
        composerControls.addView(icon(LineIconDrawable.Icon.ATTACHMENT,"사진 또는 파일 첨부",this::showAttachmentChoices),new LinearLayout.LayoutParams(dp(44),dp(48)));
        composerControls.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        modelSelector=label("",12,INK);modelSelector.setSingleLine(true);modelSelector.setEllipsize(android.text.TextUtils.TruncateAt.END);modelSelector.setMaxWidth(dp(222));
        modelSelector.setGravity(Gravity.CENTER);modelSelector.setPadding(dp(10),dp(12),dp(10),dp(12));modelSelector.setMinimumHeight(dp(48));
        modelSelector.setBackground(ChatPalette.ripple(this,Color.TRANSPARENT,16));modelSelector.setOnClickListener(v->showForegroundModelPicker());
        composerControls.addView(modelSelector,new LinearLayout.LayoutParams(-2,dp(48)));
        sendButton=ChatPalette.iconButton(this,LineIconDrawable.Icon.SEND,"메시지 보내기",Color.WHITE);sendButton.setBackground(ChatPalette.outgoing(this));sendButton.setOnClickListener(v->send());
        LinearLayout.LayoutParams sendParams=new LinearLayout.LayoutParams(dp(48),dp(48));sendParams.setMargins(dp(6),dp(3),dp(3),dp(3));composerControls.addView(sendButton,sendParams);entry.addView(composerControls);composerArea.addView(entry);refreshModelSelector();
        content.addView(composerArea);displayedMessageId=-1;refreshMessages();updateComposerState();
        if(draftState.recoveryRequired)composerArea.addView(label(draftState.recoveryReason,12,SUBTLE));
    }

    private void showAttachmentChoices(){
        new ObservedDialogBuilder(this).setTitle("대화에 첨부").setItems(new String[]{"사진·동영상","모든 파일"},(d,which)->{
            if(which==0){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"}).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(intent,PICK_FILES);}
            else pickDocuments(PICK_FILES,"*/*");
        }).setNegativeButton("닫기",null).show();
    }

    private void updateComposerState(){
        if(attachmentLabel!=null){attachmentLabel.setText(attachmentSummary());attachmentLabel.setVisibility(attachments.length()==0?View.GONE:View.VISIBLE);}
        if(sendButton!=null){boolean canSend=!sending&&!permissionBlocked&&composer!=null&&(!composer.getText().toString().trim().isEmpty()||attachments.length()>0);sendButton.setEnabled(canSend);sendButton.setAlpha(canSend?1f:.35f);sendButton.setContentDescription(sending?"메시지 저장 중":"메시지 보내기");}
    }

    private void send() {
        if (checkPermissionGate() || sending || composer == null || draftState == null) return;
        if (draftState.recoveryRequired) { notice(draftState.recoveryReason); return; }
        if (draftSaveFailed || !draftState.text.equals(composer.getText().toString()) || !draftState.attachmentsJson.equals(attachments.toString())) { notice("입력을 아직 영구 저장하지 못했어. 내용을 수정하거나 첨부 선택을 다시 확인한 뒤 보내 줘."); return; }
        String draft = composer.getText().toString();
        if (draft.trim().isEmpty() && attachments.length() == 0) return;
        final String text = draft;
        final String attached = attachments.toString();
        final String clientId = draftState.clientInputId;
        final long revision = draftState.revision;
        final String inputActor=UiAutomationProvenance.submittedActor(draftState.inputActor,UiAutomationProvenance.currentActor(this));
        final JSONObject submittedSelection=app.getRuntime().foregroundRoutingState();
        sending = true; updateComposerState();
        commands.execute(() -> {
            try {
                app.getRuntime().receive(text, new JSONArray(attached), clientId, revision,inputActor,submittedSelection);
                OwnerDraftStore.Snapshot current = draftStore.read();
                main.post(() -> {
                    sending = false; updateComposerState();
                    if (isFinishing() || isDestroyed()) return;
                    if (draftState != null && clientId.equals(draftState.clientInputId) && revision == draftState.revision) applyDraft(current);
                    refreshMessages(); refreshStatus();
                });
            } catch (Exception error) { main.post(() -> { sending = false; updateComposerState(); notice("메시지나 첨부 원본을 저장하지 못했어. 입력과 선택은 그대로 남겨 뒀어."); }); }
        });
    }

    /** At most forty bounded previews are read/rendered; old originals stay available by source id. */
    private void refreshMessages() {
        if (isFinishing() || isDestroyed() || app.getRuntime().isRestoringBackup() || messageList == null || !"대화".equals(tab) || messagesLoading) return;
        if (chatBeforeId > 0 && displayedMessageId >= 0) return;
        final long generation = chatGeneration, before = chatBeforeId, alreadyDisplayed = displayedMessageId;
        messagesLoading = true;
        display.execute(() -> {
            try {
                long newest = app.getStore().lastChatMessageId();
                if (newest == alreadyDisplayed) { main.post(() -> { messagesLoading = false; if (generation != chatGeneration && !isDestroyed() && "대화".equals(tab)) refreshMessages(); }); return; }
                JSONObject page = app.getStore().boundedChatMessagePage(before, 40, 8000);
                JSONArray messages = page.optJSONArray("messages");
                java.util.HashMap<Long, JSONArray> assets = new java.util.HashMap<>();
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject message = messages.optJSONObject(i);
                    if ("user".equals(message.optString("role"))) assets.put(message.optLong("id"), parseArray(app.getStore().getSetting("attachmentMetadata:" + message.optString("request_id"), "[]")));
                }
                main.post(() -> {
                    messagesLoading = false;
                    if (isFinishing() || isDestroyed() || generation != chatGeneration || before != chatBeforeId || !"대화".equals(tab)) { if (!isDestroyed() && "대화".equals(tab)) refreshMessages(); return; }
                    if (newest == displayedMessageId) return;
                    boolean follow = displayedMessageId < 0 || messageScroll.getChildAt(0).getHeight() - messageScroll.getHeight() - messageScroll.getScrollY() < dp(100);
                    int scrollY = messageScroll.getScrollY();
                    long previousNewest=displayedMessageId;
                    displayedMessageId = newest; nextBeforeId = page.optLong("nextBeforeId", 0);
                    renderChatPage(messages,assets,previousNewest);
                    chatNavigation.removeAllViews();
                    if(before>0)chatNavigation.addView(button("최신 대화",()->{chatBeforeId=0;displayedMessageId=-1;chatGeneration++;refreshMessages();}));
                    if(page.optBoolean("hasMore"))chatNavigation.addView(button("이전 대화 보기",()->{chatBeforeId=nextBeforeId;displayedMessageId=-1;chatGeneration++;refreshMessages();}));
                    chatNavigation.setVisibility(chatNavigation.getChildCount()==0?View.GONE:View.VISIBLE);
                    messageScroll.post(()->{if(follow){if(previousNewest<0)messageScroll.fullScroll(View.FOCUS_DOWN);else messageScroll.smoothScrollTo(0,messageList.getHeight());}else messageScroll.scrollTo(0,scrollY);photoPreviews.updateViewport();markVisibleReplies();});

                });
            } catch (RuntimeException failed) { main.post(() -> { messagesLoading = false; if (!isDestroyed()) connection.setText("대화 미리보기를 읽지 못했어. 원본은 그대로 보관돼."); }); }
        });
    }

    /** Reuse unchanged bubbles: polling must not restart selection, photo decoding, or entry motion. */
    private void renderChatPage(JSONArray messages, java.util.Map<Long,JSONArray> assets,long previousNewest) {
        java.util.HashMap<Long,java.util.ArrayList<JSONObject>> reactions=new java.util.HashMap<>();
        java.util.HashMap<Long,String> visibleOwners=new java.util.HashMap<>();
        for(int i=0;i<messages.length();i++){JSONObject message=messages.optJSONObject(i);if(message!=null&&"user".equals(message.optString("role")))visibleOwners.put(message.optLong("id"),message.optString("content_hash"));}
        for(int i=0;i<messages.length();i++) {
            JSONObject message=messages.optJSONObject(i);JSONObject reaction=EmojiReactionReply.fromStoredMessage(message);
            if(reaction!=null&&reaction.optString("targetRecordHash").equals(visibleOwners.get(reaction.optLong("targetMessageId")))) reactions.computeIfAbsent(reaction.optLong("targetMessageId"),key->new java.util.ArrayList<>()).add(message);
        }
        if(previousNewest<0){photoPreviews.clear();replyViews.clear();messageList.removeAllViews();messageRows.clear();messageSignatures.clear();}
        if(messages.length()==0){
            for(View old:messageRows.values())releaseChatRow(old);replyViews.clear();
            messageList.removeAllViews();messageRows.clear();messageSignatures.clear();LinearLayout welcome=column();welcome.setGravity(Gravity.CENTER);welcome.setPadding(dp(20),dp(74),dp(20),dp(36));
            welcome.addView(new HoyoungAvatarView(this),new LinearLayout.LayoutParams(dp(78),dp(78)));
            TextView title=identityLabel("과 나의 대화",23,INK);title.setTypeface(null,Typeface.BOLD);title.setPadding(0,dp(20),0,dp(8));welcome.addView(title);
            TextView subtitle=label("일상의 한마디부터, 맡기고 싶은 일까지.\n사진과 파일도 편하게 보내 줘.",14,SUBTLE);subtitle.setGravity(Gravity.CENTER);welcome.addView(subtitle);
            Button connect=button("Work 연결하기",()->showTab("Work"));LinearLayout.LayoutParams link=new LinearLayout.LayoutParams(-2,dp(48));link.setMargins(0,dp(22),0,0);welcome.addView(connect,link);messageList.addView(welcome);return;
        }
        java.util.LinkedHashMap<Long,View> next=new java.util.LinkedHashMap<>();int position=0;String lastDate="";
        for(int i=0;i<messages.length();i++) {
            JSONObject message=messages.optJSONObject(i);if(message==null)continue;long id=message.optLong("id");
            JSONObject reaction=EmojiReactionReply.fromStoredMessage(message);
            if(reaction!=null&&reaction.optString("targetRecordHash").equals(visibleOwners.get(reaction.optLong("targetMessageId"))))continue;
            String date=dayLabel(message.optLong("created_at"));String separator=date.equals(lastDate)?"":date;lastDate=date;
            java.util.ArrayList<JSONObject> badges=reactions.get(id);
            String signature=message.toString()+"|"+separator+"|"+String.valueOf(assets.get(id))+"|"+String.valueOf(badges);
            View row=messageRows.get(id);boolean inserted=row==null;
            if(row==null||!signature.equals(messageSignatures.get(id))) {
                if(row!=null){releaseChatRow(row);messageList.removeView(row);}
                row=chatMessageView(message,assets.get(id),badges,separator);messageSignatures.put(id,signature);
            }
            if(row.getParent()==null)messageList.addView(row,Math.min(position,messageList.getChildCount()));
            else if(messageList.indexOfChild(row)!=position){messageList.removeView(row);messageList.addView(row,Math.min(position,messageList.getChildCount()));}
            next.put(id,row);position++;
            if(inserted&&previousNewest>=0&&id>previousNewest&&chatBeforeId==0)ChatPalette.enter(row,0);
        }
        for(java.util.Map.Entry<Long,View> old:messageRows.entrySet())if(!next.containsKey(old.getKey())){releaseChatRow(old.getValue());messageList.removeView(old.getValue());messageSignatures.remove(old.getKey());}
        // The initial empty state is a placeholder, not a chat item.
        for(int i=messageList.getChildCount()-1;i>=0;i--)if(!next.containsValue(messageList.getChildAt(i)))messageList.removeViewAt(i);
        messageRows.clear();messageRows.putAll(next);
    }

    private View chatMessageView(JSONObject message,JSONArray assets,java.util.List<JSONObject> reactions,String date) {
        boolean owner="user".equals(message.optString("role"));boolean system="system".equals(message.optString("role"));
        LinearLayout item=column();item.setPadding(0,0,0,dp(10));
        if(!date.isEmpty()){TextView day=label(date,11,SUBTLE);day.setGravity(Gravity.CENTER);day.setPadding(0,dp(14),0,dp(18));item.addView(day);}
        LinearLayout line=row();line.setGravity((owner?Gravity.END:Gravity.START)|Gravity.BOTTOM);
        if(system){TextView notice=label(systemMessageText(message),12,SUBTLE);notice.setPadding(dp(14),dp(10),dp(14),dp(10));notice.setGravity(Gravity.CENTER);notice.setBackground(round(Color.rgb(239,237,244)));item.addView(notice);replyViews.put(notice,message);return item;}
        if(!owner){HoyoungAvatarView avatar=new HoyoungAvatarView(this);LinearLayout.LayoutParams avatarParams=new LinearLayout.LayoutParams(dp(27),dp(27));avatarParams.setMargins(0,0,dp(8),dp(24));line.addView(avatar,avatarParams);}
        LinearLayout group=column();group.setGravity(owner?Gravity.END:Gravity.START);
        LinearLayout bubble=column();bubble.setPadding(dp(15),dp(11),dp(15),dp(12));bubble.setBackground(owner?ChatPalette.outgoing(this):ChatPalette.rounded(this,Color.rgb(238,236,243),22));
        JSONObject reaction=EmojiReactionReply.fromStoredMessage(message);
        String text=reaction==null?message.optString("text"):"이전 메시지에 "+reaction.optString("emoji")+" 반응했어";
        TextView body=label(text,15,owner?Color.WHITE:INK);body.setTextIsSelectable(true);body.setMaxWidth(Math.max(dp(140),getResources().getDisplayMetrics().widthPixels-dp(126)));
        if(!text.isEmpty())bubble.addView(body);
        if(!owner)replyViews.put(body,message);
        if(message.optBoolean("textIsExcerpt"))bubble.addView(button("전체 원문 읽기",()->showOriginal(message.optLong("id"),0)));
        if(owner)addAttachmentPreviews(bubble,assets);
        group.addView(bubble,new LinearLayout.LayoutParams(-2,-2));
        if(reactions!=null&&!reactions.isEmpty()){
            LinearLayout badges=row();badges.setGravity(Gravity.END);badges.setPadding(dp(4),0,dp(4),0);
            for(JSONObject reply:reactions){JSONObject mark=EmojiReactionReply.fromStoredMessage(reply);if(mark==null)continue;TextView badge=label(mark.optString("emoji"),19,INK);badge.setGravity(Gravity.CENTER);badge.setMinWidth(dp(44));badge.setMinHeight(dp(36));badge.setPadding(dp(10),dp(2),dp(10),dp(2));badge.setBackground(ChatPalette.outlined(this,Color.WHITE,Color.rgb(229,225,238),18));badge.setTag("assistant-reaction:"+mark.optString("emoji"));badge.setContentDescription(assistantName()+"이 이 메시지에 "+mark.optString("emoji")+" 이모지로 응답함");badge.setOnClickListener(v->notice(assistantName()+"이 "+mark.optString("emoji")+" 이모지로 응답했어. 이 반응도 대화와 기억에 함께 보관돼."));badges.addView(badge);replyViews.put(badge,reply);}
            LinearLayout.LayoutParams badgeParams=new LinearLayout.LayoutParams(-2,-2);badgeParams.topMargin=-dp(3);group.addView(badges,badgeParams);
        }
        TextView time=label(clockLabel(message.optLong("created_at")),10,SUBTLE);time.setGravity(owner?Gravity.END:Gravity.START);time.setPadding(dp(4),dp(5),dp(4),0);group.addView(time);
        LinearLayout.LayoutParams groupParams=new LinearLayout.LayoutParams(-2,-2);groupParams.setMargins(owner?dp(48):0,0,owner?0:dp(28),0);line.addView(group,groupParams);item.addView(line);return item;
    }

    private void releaseChatRow(View view){
        view.animate().cancel();
        if(view instanceof TextView)replyViews.remove(view);
        if(view instanceof ImageView)photoPreviews.unregister((ImageView)view);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)releaseChatRow(group.getChildAt(i));}
    }
    private String dayLabel(long time){return time<=0?"":new java.text.SimpleDateFormat("M월 d일 E요일",java.util.Locale.KOREAN).format(new java.util.Date(time));}
    private String clockLabel(long time){return time<=0?"":new java.text.SimpleDateFormat("a h:mm",java.util.Locale.KOREAN).format(new java.util.Date(time));}

    private void markVisibleReplies() {
        if (!resumed || drawerOverlay!=null || !"대화".equals(tab) || app.getRuntime().isRestoringBackup() || app.getReplyNotifications() == null) return;
        Rect bounds = new Rect();
        for (java.util.Map.Entry<TextView, JSONObject> entry : replyViews.entrySet()) {
            TextView view = entry.getKey(); JSONObject source = entry.getValue();
            if (view.isAttachedToWindow() && view.isShown() && view.getGlobalVisibleRect(bounds) && !bounds.isEmpty())
                app.getReplyNotifications().markSeen(source.optLong("id"), source.optString("content_hash"));
        }
    }

    private void addAttachmentPreviews(LinearLayout bubble, JSONArray assets) {
        if (assets == null) return;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i); if (asset == null) continue;
            String name = asset.optString("sourceName", asset.optString("name", "첨부"));
            bubble.addView(label(name, 12, Color.WHITE));
            if (!asset.optString("mime", asset.optString("mimeType")).startsWith("image/")) continue;
            ImageView image = new ImageView(this); image.setBackground(ChatPalette.rounded(this,Color.rgb(239,236,244),14)); image.setClipToOutline(true); image.setContentDescription(name); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            bubble.addView(image, new LinearLayout.LayoutParams(Math.min(dp(220),getResources().getDisplayMetrics().widthPixels-dp(160)), dp(180)));
            photoPreviews.register(image, asset);
            image.setOnClickListener(view -> photoPreviews.loadExpanded(asset, preview -> {
                    if (isFinishing() || isDestroyed()) { if (preview.ready() && !preview.bitmap.isRecycled()) preview.bitmap.recycle(); return; }
                    if (!preview.ready()) { notice("사진을 표시하지 못했어. 보관된 원본을 파일 페이지에서 확인해 줘."); return; }
                    ImageView full = new ImageView(this); full.setImageBitmap(preview.bitmap); full.setAdjustViewBounds(true); full.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    AlertDialog dialog = new ObservedDialogBuilder(this).setTitle(name).setView(full).setPositiveButton("닫기", null).create();
                    expandedPhotos.put(dialog, preview.bitmap);
                    dialog.setOnDismissListener(ignored -> { Bitmap bitmap = expandedPhotos.remove(dialog); full.setImageDrawable(null); if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); });
                    dialog.show(); UiAutomationProvenance.observeWindow(this,dialog.getWindow());
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
                    new ObservedDialogBuilder(this).setTitle("추적 중인 목표 " + page.optLong("totalPending") + "개").setItems(labels, (dialog, which) -> {
                        if (which >= goals.length()) { showGoals(page.optString("nextAfterRootRequestId")); return; }
                        JSONObject goal = goals.optJSONObject(which);
                        new ObservedDialogBuilder(this).setTitle("목표 상태 정리").setMessage(goal.optString("requestExcerpt") + "\n" + goal.optString("progress") + "\n모델 보고와 실제 확인은 구분해 보관해. 아래 선택은 네가 직접 추적 상태를 정리하는 거야.")
                            .setPositiveButton("완료로 정리", (d, w) -> resolveGoal(goal.optString("rootRequestId"), "completed"))
                            .setNeutralButton("추적 그만", (d, w) -> resolveGoal(goal.optString("rootRequestId"), "abandoned")).setNegativeButton("유지", null).show();
                    }).setNegativeButton("닫기", null).show();
                });
            } catch (Exception failure) { main.post(() -> notice("목표 장부를 읽지 못했어. 저장된 작업은 그대로 두었어.")); }
        });
    }
    private void resolveGoal(String rootId, String stage) { io.execute(() -> { app.getRuntime().getGoalLedger().resolve(rootId, stage, "사용자가 앱 목표 목록에서 직접 정리"); main.post(() -> { notice("추적 상태를 정리했어. 원문과 영수증은 보관돼."); showMemory(); }); }); }

    private void copyConnectionDiagnosis() {
            JSONObject diagnosis = app.getRuntime().state();
            // Progress can contain a streamed answer; diagnostics are state flags only.
            diagnosis.remove("progress");
            JSONObject budget = diagnosis.optJSONObject("usageBudget");
            if (budget != null) {
                budget.remove("observation"); budget.remove("latestProviderObservation");
                budget.remove("accountScope"); budget.remove("poolId"); budget.remove("evidenceRef");
            }
            try { diagnosis.put("androidSdk", android.os.Build.VERSION.SDK_INT).put("appVersion", getPackageManager().getPackageInfo(getPackageName(), 0).versionName).put("permissions", new PermissionCatalog(this).snapshot()); }
            catch (Exception ignored) { }
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("호영이 연결 진단", diagnosis.toString())); notice("연결·권한 상태만 복사했어. 비밀번호·대화·알림 본문은 포함하지 않아."); }
    }

    private void showWorkOptions() {
        java.util.ArrayList<String> items=new java.util.ArrayList<>(java.util.List.of("새 Work 대화","연결 상세","연결 진단 복사","연결 동작 테스트"));
        if(!app.getWebController().pendingConversationDeletion().isEmpty() || !app.getWebController().unresolvedConversationCreation().isEmpty()) items.add("삭제 확인 복구");
        new ObservedDialogBuilder(this).setTitle("Work 연결").setItems(items.toArray(new String[0]),(dialog,which)->{
            switch(items.get(which)) {
                case "새 Work 대화" -> startNewWorkConversation();
                case "연결 상세" -> showConnectionDetails();
                case "연결 진단 복사" -> copyConnectionDiagnosis();
                case "연결 동작 테스트" -> new ObservedDialogBuilder(this).setTitle("연결 동작 테스트")
                    .setMessage("실제 Work 요청을 보내 사용량이 발생해. 평소 연결에는 이 테스트가 필요하지 않아.")
                    .setPositiveButton("테스트",(d,w)->verifyWorkConnection()).setNegativeButton("취소",null).show();
                case "삭제 확인 복구" -> recoverWorkConversationDeletion();
            }
        }).setNegativeButton("닫기",null).show();
    }

    private void verifyWorkConnection() {
        if(!app.getRuntime().state().optBoolean("ready")){app.getWebController().connect();notice("아직 연결 준비가 안 됐어. ⋯의 연결 상세를 확인해 줘.");return;}
        app.getRuntime().receive("연결 검증만 수행해. Work의 실제 클라우드 코드 실행 도구가 있다면 Python으로 2+2를 계산하고 임시 작업 파일 hoyoung-connection-check.txt에 그 결과를 기록해. 실제 실행 결과와 사용한 도구를 보고하고, 클라우드 도구가 없으면 없다고 정확히 말해. 폰 연결은 phone_device_info 도구로 최소 상태만 확인해. 로그인 계정 변경·외부 메시지·결제·개인 연락처나 알림 읽기는 하지 마. 실제 도구 결과 없이 검증 통과라고 만들지 마.", new JSONArray());
        showTab("대화");
    }

    private void startNewWorkConversation() {
        JSONObject state = app.getRuntime().state();
        if (!state.isNull("activeRequestId") && !state.optString("activeRequestId").isEmpty()) {
            notice("진행 중인 작업의 결과가 보관된 뒤 새 대화로 바꿀 수 있어."); return;
        }
        try { app.getRuntime().openTemporary(); }
        catch (Exception e) { notice("기존 작업의 결과를 먼저 확인하거나 설정에서 해당 작업을 제외해 줘."); }
    }

    private void recoverWorkConversationDeletion() {
        String id = app.getWebController().pendingConversationDeletion();
        String unresolved = app.getWebController().unresolvedConversationCreation();
        if (id.isEmpty() && !unresolved.isEmpty()) {
            new android.app.AlertDialog.Builder(this).setTitle("대화 생성 결과 확인")
                .setMessage("연결이 끊겨 앱이 만든 대화 주소를 확인하지 못했어. 기존 작업의 결과를 먼저 확인하고, 웹에서 해당 호영 대화를 직접 삭제한 뒤에만 확인해 줘. 확인되지 않은 진행 중 작업은 먼저 설정의 작업 복구에서 정리해야 해.")
                .setNegativeButton("돌아가기", null)
                .setPositiveButton("확인·삭제했음", (dialog, which) -> app.getWebController().confirmUnknownConversationDeletedByOwner(unresolved)).show();
            return;
        }
        if (id.isEmpty()) { notice("확인이 필요한 이전 대화 삭제가 없어."); return; }
        new android.app.AlertDialog.Builder(this).setTitle("이전 대화 삭제 확인")
            .setMessage("자동 삭제 확인이 실패했을 때만 사용해. 아래 Work 화면에서 이 대화를 직접 삭제한 것이 확실할 때 확인해 줘.\n\n대화 ID: " + id + "\n\n폰의 기억·설정은 삭제하지 않아. ChatGPT 계정에 별도로 저장된 메모리도 지우지 않아.")
            .setNegativeButton("돌아가기", null)
            .setPositiveButton("직접 삭제했음", (dialog, which) -> app.getWebController().confirmConversationDeletedByOwner(id)).show();
    }

    private void showWork() {
        LinearLayout bar=row();bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(16),dp(6),dp(12),dp(6));
        workConnectionDetails=label("연결 상태 확인 중…",13,SUBTLE);
        workConnectionDetails.setMaxLines(2);workConnectionDetails.setEllipsize(android.text.TextUtils.TruncateAt.END);
        workConnectionDetails.setPadding(0,dp(4),dp(12),dp(4));workConnectionDetails.setContentDescription("Work 연결 상세");
        workConnectionDetails.setOnClickListener(v->showConnectionDetails());
        bar.addView(workConnectionDetails,new LinearLayout.LayoutParams(0,-2,1));
        bar.addView(button("연결",()->app.getWebController().connect()),new LinearLayout.LayoutParams(dp(84),dp(48)));
        content.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        try {
            WebView web = app.getWebController().getView();
            content.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
            app.getWebController().start();
        } catch (RuntimeException | LinkageError unavailable) {
            content.addView(label("Work 화면을 아직 열지 못했어. 휴대폰의 웹 구성 요소와 저장된 작업 상태를 확인한 뒤 다시 준비할 수 있어.", 15, INK));
            content.addView(button("Work 화면 다시 준비", () -> { app.getWebController().start(); showTab("Work"); }));
        }
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
                JSONObject deviceSearch = app.getStore().deviceSearchIndexStatus();
                state = "보관 원문 " + app.getStore().messageCount() + "건\n기억 검토: "
                        + (review.optBoolean("hasPending") ? "대기 원문 있음" : "현재 검토 대기 없음")
                        + "\n의미 검색 모델: " + semanticPhase(model.optString("phase"))
                        + "\n의미 검색 준비: " + Math.round(indexed.optDouble("sourceCoverage", 0) * 100) + "% ("
                        + indexed.optLong("semanticCompletedSources") + "/" + indexed.optLong("sourceTotals") + "건)"
                        + "\n휴대폰 기록 검색: " + (deviceSearch.optBoolean("complete") ? "전체 색인 준비됨" : "이전 기록 색인 준비 중 · 원문 검색 유지")
                        + (deviceSearch.has("error") ? " · 상태 확인 필요" : "");
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
                            if (("message".equals(type) || "device_event".equals(type)) && id > 0) {
                                Button manage = button(("message".equals(type) ? "대화" : "기기") + " 원문 " + id + " 관리", () -> { });
                                manage.setOnClickListener(view -> inspectStoredSource(type, id, manage)); results.addView(manage);
                            }
                        }
                    });
                } catch (Exception error) { main.post(() -> { results.removeAllViews(); results.addView(label("검색을 완료하지 못했어. 원문은 보존돼 있어.", 13, SUBTLE)); }); }
            });
        }));
        panel.addView(results);
        panel.addView(label("저장 원문 관리", 18, INK));
        panel.addView(label("검색 근거의 관리 버튼을 쓰거나 원문 유형과 ID를 선택해. 원문을 확인한 뒤 네가 직접 AI 기억에서 제외하거나 앱의 원문·파생 기록 삭제를 요청할 수 있어.", 13, SUBTLE));
        android.widget.Spinner sourceType = new android.widget.Spinner(this);
        String[] sourceTypes = {"message", "device_event"};
        sourceType.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"대화 원문", "기기 기록 원문"})); panel.addView(sourceType);
        EditText sourceId = new EditText(this); sourceId.setSingleLine(true); sourceId.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); sourceId.setHint("원문 ID"); panel.addView(sourceId);
        Button inspect = button("원문 확인 후 관리", () -> { });
        inspect.setOnClickListener(view -> {
            try {
                long id = Long.parseLong(sourceId.getText().toString().trim());
                if (id <= 0) throw new IllegalArgumentException();
                inspectStoredSource(sourceTypes[sourceType.getSelectedItemPosition()], id, inspect);
            } catch (IllegalArgumentException invalid) { notice("원문 ID는 1 이상의 정수로 입력해."); }
        }); panel.addView(inspect);
    }

    /** Only a direct owner click reaches this control; model tools never receive its authority. */
    private void inspectStoredSource(String type, long id, Button inspect) {
        if (isFinishing() || isDestroyed()) return;
        if (sourceControlBusy) { notice("선택한 원문의 처리 상태를 확인하고 있어."); return; }
        if (app.getRuntime().isRestoringBackup()) { notice("백업 복구를 마친 뒤 원문을 관리해 줘."); return; }
        sourceControlBusy = true; inspect.setEnabled(false);
        io.execute(() -> {
            try {
                if (app.getRuntime().isRestoringBackup()) throw new IllegalStateException("Restore in progress");
                JSONObject source = "message".equals(type) ? app.getStore().getMessage(id) : app.getStore().getDeviceEvent(id);
                if (source == null || !SourceRecordVisibility.canonicalRecordMatches(type, source)) throw new IllegalArgumentException("Source unavailable or changed");
                final String hash = source.optString("content_hash");
                String body = source.optString("message".equals(type) ? "text" : "payload");
                int end = Math.min(body.length(), 600);
                if (end > 0 && end < body.length() && Character.isHighSurrogate(body.charAt(end - 1))) end--;
                final String excerpt = body.substring(0, end) + (end < body.length() ? "…" : "");
                main.post(() -> {
                    sourceControlBusy = false; inspect.setEnabled(true);
                    if (isFinishing() || isDestroyed() || !inspect.isAttachedToWindow() || app.getRuntime().isRestoringBackup()) return;
                    new ObservedDialogBuilder(this).setTitle(("message".equals(type) ? "대화" : "기기") + " 원문 " + id)
                            .setMessage(excerpt + "\n\nAI 기억에서 제외하면 원문은 보관돼. 삭제는 추적된 폰 원문과 파생 기록의 완료 상태를 확인하는 별도 요청이야.")
                            .setPositiveButton("AI 기억에서 제외", (dialog, which) -> controlStoredSource(type, id, hash, false))
                            .setNeutralButton("원문·파생 기록 삭제", (dialog, which) -> new ObservedDialogBuilder(this)
                                    .setTitle("앱에서 원문·파생 기록 삭제")
                                    .setMessage("선택한 원문 " + id + "와 연결된 기억·답장 등 추적된 폰 기록의 삭제를 요청할까? 진행 중 작업, 공유 원문, 작업 사본 때문에 대기할 수 있어. 외부 제공자·클라우드 사본과 추적되지 않은 작업 사본의 삭제는 확인하지 않아.")
                                    .setPositiveButton("삭제 요청", (confirmation, choice) -> controlStoredSource(type, id, hash, true))
                                    .setNegativeButton("유지", null).show())
                            .setNegativeButton("닫기", null).show();
                });
            } catch (Exception unavailable) {
                main.post(() -> { sourceControlBusy = false; inspect.setEnabled(true); if (inspect.isAttachedToWindow()) notice("선택한 원문의 내용과 식별 정보를 확인하지 못했어. 원문을 변경하거나 삭제하지 않았어."); });
            }
        });
    }

    private void controlStoredSource(String type, long id, String hash, boolean deletePayload) {
        if (isFinishing() || isDestroyed()) return;
        if (sourceControlBusy) { notice("선택한 원문의 처리 상태를 확인하고 있어."); return; }
        if (app.getRuntime().isRestoringBackup()) { notice("백업 복구를 마친 뒤 원문을 관리해 줘."); return; }
        sourceControlBusy = true;
        final String inputActor=UiAutomationProvenance.currentActor(this);
        io.execute(() -> {
            try {
                JSONObject receipt = app.controlStoredSource(type, id, hash, deletePayload,inputActor);
                if (receipt == null) throw new IllegalStateException("Missing owner control receipt");
                main.post(() -> {
                    sourceControlBusy = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (deletePayload) { displayedMessageId = -1; chatGeneration++; if ("대화".equals(tab)) refreshMessages(); }
                    showStoredSourceReceipt(type, id, hash, deletePayload, receipt);
                });
            } catch (Exception unavailable) {
                main.post(() -> {
                    sourceControlBusy = false;
                    if (isFinishing() || isDestroyed()) return;
                    new ObservedDialogBuilder(this).setTitle("원문 " + id + " 처리 확인 필요")
                            .setMessage("처리 완료를 확인하지 못했어. 저장된 요청이 있을 수 있으니 완료로 표시하지 않았어.")
                            .setPositiveButton("같은 원문 처리 다시 확인", (view, which) -> controlStoredSource(type, id, hash, deletePayload))
                            .setNegativeButton("닫기", null).show();
                });
            }
        });
    }

    private void showStoredSourceReceipt(String type, long id, String hash, boolean deletePayload, JSONObject receipt) {
        String text = receipt.optBoolean("retrievalExcluded") ? "AI 기억에서 제외됨" : "AI 기억 제외 완료 확인 필요";
        boolean pending = receipt.optBoolean("pendingDeletion", true);
        boolean verified = receipt.optBoolean("managedLocalDeletionVerified") && !pending;
        if (!deletePayload) text += "\n저장 원문은 보관돼. 이미 전달된 내용은 되돌리지 않아.";
        else {
            text += verified ? "\n추적된 폰 원문·파생 기록의 삭제를 확인했어." : pending
                    ? "\n삭제 요청은 대기 중이야. 원문·파생 기록의 물리 삭제 완료는 확인하지 못했어."
                    : "\n원문·파생 기록의 물리 삭제 완료를 확인하지 못했어.";
            if (!verified) text += "\n" + switch (receipt.optString("status")) {
                case "pending_inflight_request" -> "진행 중 작업 상태 확인 필요";
                case "pending_external_archive" -> "휴대폰 보관 기록 삭제 확인 필요";
                case "pending_shared_payload" -> "다른 기록과 공유하는 원문이 남아 있어";
                case "pending_working_copy_retained" -> "작업용 사본이 보존돼 있어";
                case "pending_sqlite_cleanup", "pending_database", "pending_files" -> "폰 저장소 정리 확인 필요";
                case "pending_attachment_manifest", "pending_source_manifest" -> "원문·첨부 식별 정보 확인 필요";
                case "pending_source_generation_changed", "pending_source_integrity_changed", "pending_descendant_generation_changed" -> "선택한 원문·파생 기록의 변경 상태 확인 필요";
                default -> "삭제 요청의 완료 상태 확인 필요";
            };
            text += "\n외부 제공자·클라우드 사본과 추적되지 않은 작업 사본의 삭제는 확인하지 않아. 삭제 요청의 식별 기록은 남아 있어.";
        }
        text+="\n"+ownerFolderDescription();
        AlertDialog.Builder dialog = new ObservedDialogBuilder(this).setTitle("원문 " + id + " 처리 상태").setMessage(text).setNegativeButton("닫기", null);
        if (deletePayload && !verified) dialog.setPositiveButton("같은 삭제 요청 다시 확인", (view, which) -> controlStoredSource(type, id, hash, true));
        dialog.show();
    }

    private void showOriginal(long id, long offset) {
        display.execute(() -> {
            try {
                JSONObject chunk = app.getStore().readOriginalText(id, offset, 12_000);
                main.post(() -> {
                    if (isFinishing()) return;
                    AlertDialog.Builder dialog = new ObservedDialogBuilder(this).setTitle("원문 " + id + " · " + chunk.optLong("offsetChars") + "/" + chunk.optLong("totalChars"))
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
            saveImmediateSetting(key,enabled);
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
        panel.addView(label("접근과 허용",25,INK));
        panel.addView(label("폰의 접근 권한과 비서가 실행할 기능을 따로 관리해.",14,SUBTLE));
        PermissionOnboarding.Snapshot permissionState=permissionOnboarding.snapshot();
        LinearLayout systemPermissions=new com.hoyoung.assistant.ui.FormColumn(this);
        for(PermissionOnboarding.Item permission:permissionState.items){
            systemPermissions.addView(button(permission.title+" · "+(permission.granted?"허용됨":permission.required?"설정 필요":"지원 범위 확인"),()->{
                PermissionOnboarding.RequestResult result=permissionOnboarding.request(this,permission.id);if(!result.launched)notice(result.message);
            }));
            systemPermissions.addView(label(permission.detail,12,SUBTLE));
        }
        panel.addView(disclosure("Android 권한 · "+permissionState.grantedCount+" / "+permissionState.requiredCount,systemPermissions,false));
        panel.addView(label("비서가 사용할 기능", 20, INK));
        panel.addView(label("Android 권한을 유지하면서 비서의 특정 기능만 끌 수 있어. 대화에서도 이 설정을 바꿀 수 있어.", 13, SUBTLE));
        JSONArray tools = app.getRuntime().toolCatalog();
        JSONObject allowed;
        final String rawPermissions = app.getStore().getSetting("toolPermissions", "{}");
        try {
            org.json.JSONTokener parser = new org.json.JSONTokener(rawPermissions); Object parsed = parser.nextValue();
            if (!(parsed instanceof JSONObject) || parser.nextClean() != 0) throw new IllegalArgumentException();
            allowed = (JSONObject)parsed; java.util.Iterator<String> keys = allowed.keys();
            while (keys.hasNext()) if (!(allowed.opt(keys.next()) instanceof Boolean)) throw new IllegalArgumentException();
        } catch (Exception invalid) {
            panel.addView(label("앱 기능 권한 설정을 읽지 못해 실행을 멈춘 상태야. 기존 설정을 원문으로 보관한 뒤 기본값으로 복구할 수 있어.", 14, INK));
            panel.addView(button("앱 기능을 모두 허용으로 복구", () -> {
                try {
                    app.getStore().withTransaction(db -> { app.getStore().addMessage("system", "앱 기능 권한 설정 복구 전 원문:\n" + rawPermissions, null); app.getStore().putSetting("toolPermissions", "{}"); app.getStore().putSetting("personalSettingsRevision",java.util.UUID.randomUUID().toString()); return null; });
                    applyPhonePolicy(); app.getPhoneContext().reconcilePermissions(); showPermissions();
                } catch (RuntimeException failed) { notice("이전 설정을 보관하지 못해 변경하지 않았어."); }
            }));
            return;
        }
        java.util.LinkedHashMap<String,LinearLayout> categories=new java.util.LinkedHashMap<>();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i); if (tool == null) continue;
            String name = tool.optString("name");
            CheckBox check = new CheckBox(this); check.setTag(name); check.setText(toolLabel(name)); check.setChecked(allowed.optBoolean(name, true));
            check.setOnCheckedChangeListener((button, enabled) -> {
                try { app.getRuntime().updatePersonalSettings(settingsObject("toolPermissions",settingsObject(name,enabled)),null); }
                catch (Exception ignored) { }
            });
            String category=toolCategory(name);
            categories.computeIfAbsent(category,key->new com.hoyoung.assistant.ui.FormColumn(this)).addView(check);
        }
        for(java.util.Map.Entry<String,LinearLayout> category:categories.entrySet())panel.addView(disclosure(category.getKey(),category.getValue(),false));
        LinearLayout behavior=new com.hoyoung.assistant.ui.FormColumn(this);
        addSettingToggle(behavior,"잠금 화면에 답장 내용 표시","replyLockscreenContentVisible",true);
        addSettingToggle(behavior,"Android 알림의 제안 동작 허용","replySystemContextualActionsEnabled",true);
        addSettingToggle(behavior,"Work에서 작업 파일 내려받기","workFileDownloadsEnabled",true);
        JSONObject limitBaseline=app.getRuntime().personalSettingsBaseline();
        EditText rounds=accountField(behavior,"작업당 도구 실행 턴 · 0은 제한 없음",app.getStore().getSetting("toolRoundLimit","0"),false);
        rounds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText actions=accountField(behavior,"한 턴의 도구 개수 · 0은 제한 없음",app.getStore().getSetting("toolActionsPerTurnLimit","0"),false);
        actions.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        JSONObject initialLimits=settingsObject("toolRoundLimit",rounds.getText().toString(),"toolActionsPerTurnLimit",actions.getText().toString());
        behavior.addView(button("실행 한도 저장",()->{try{
            int roundCount=Integer.parseInt(rounds.getText().toString());int actionCount=Integer.parseInt(actions.getText().toString());
            if(roundCount<0||actionCount<0)throw new IllegalArgumentException();
            JSONObject desired=settingsObject("toolRoundLimit",rounds.getText().toString(),"toolActionsPerTurnLimit",actions.getText().toString()),patch=new JSONObject();
            for(String key:new String[]{"toolRoundLimit","toolActionsPerTurnLimit"})if(!desired.optString(key).equals(initialLimits.optString(key)))patch.put(key,Integer.parseInt(desired.optString(key)));
            JSONObject result=app.getRuntime().updatePersonalSettings(patch,limitBaseline);
            if(!result.optBoolean("ok")){notice(result.optString("message"));return;}
            JSONObject saved=app.getRuntime().personalSettingsBaseline();
            for(java.util.Iterator<String> keys=patch.keys();keys.hasNext();){String key=keys.next();limitBaseline.put(key,saved.opt(key));initialLimits.put(key,desired.opt(key));}
            notice("네가 정한 실행 한도를 저장했어. 0은 제한 없이 실행해.");
        }catch(Exception invalid){notice("0 이상의 정수를 입력해.");}}));
        panel.addView(disclosure("알림·다운로드·실행 방식",behavior,false));
    }

    /** Dialogs have a separate Window, so real owner touches must reset input authorship here too. */
    private final class ObservedDialogBuilder extends AlertDialog.Builder {
        ObservedDialogBuilder(android.content.Context context){super(context);}
        @Override public AlertDialog show(){AlertDialog dialog=super.show();UiAutomationProvenance.observeWindow(MainActivity.this,dialog.getWindow());return dialog;}
    }

    private String prettyJson(JSONObject value){try{return value.toString(2);}catch(Exception malformed){return value.toString();}}

    private String systemMessageText(JSONObject message){
        JSONObject body=parseObject(message.optString("text"));
        if("agent_authored_input".equals(body.optString("type")))return "호영이 작성·전송한 메시지\n"+body.optString("text");
        if("native_input_origin".equals(body.optString("type")))return "호영이 작성한 내용을 네가 전송했어 · 원문 "+body.optLong("sourceMessageId");
        if("native_account_input".equals(body.optString("type")))return "계정 보관함을 업데이트했어 · 로그인 정보는 계정 탭에서 확인해";
        return message.optString("text");
    }

    private AccountStore accounts(){return new AccountStore(this,app.getStore());}
    private void showAccounts(){
        if(isDestroyed()||isFinishing()||!"계정".equals(tab))return;
        content.removeAllViews();LinearLayout panel=scrollPanel();
        panel.addView(label("계정 보관함",25,INK));
        panel.addView(label("로그인 정보의 최신 값과 수정 이력을 정확히 보관해. 전체 기억 백업에 함께 포함돼.",14,SUBTLE));
        panel.addView(button("계정 추가",()->editAccount(new JSONObject())));
        EditText search=accountField(panel,"서비스·아이디 검색","",false);search.setSingleLine(true);
        LinearLayout rows=column();panel.addView(button("검색",()->loadAccounts(rows,search.getText().toString())));panel.addView(rows);
        loadAccounts(rows,"");
    }
    private void loadAccounts(LinearLayout rows,String query){
        rows.removeAllViews();rows.addView(label("계정을 불러오는 중…",13,SUBTLE));
        io.execute(()->{try{JSONArray found=accounts().list(query,1000);main.post(()->{
            if(isDestroyed()||isFinishing()||!"계정".equals(tab)||rows.getParent()==null)return;
            rows.removeAllViews();if(found.length()==0)rows.addView(label("저장한 계정이 없어. 대화로 알려주거나 직접 추가해.",14,SUBTLE));
            for(int i=0;i<found.length();i++){JSONObject record=found.optJSONObject(i);if(record==null)continue;
                LinearLayout card=column();card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(ChatPalette.outlined(this,Color.WHITE,Color.rgb(231,226,241),20));
                card.addView(label(record.optString("label",record.optString("service")),17,INK));
                card.addView(label(record.optString("service")+" · "+record.optString("username"),13,SUBTLE));
                card.addView(label(record.optBoolean("loginVerified")?"실제 로그인 확인됨":"로그인 정보 저장됨 · 실제 로그인 미검증",11,SUBTLE));
                card.setContentDescription("계정 열기 · "+record.optString("service")+" · "+record.optString("username"));
                card.setOnClickListener(v->openAccount(record.optString("accountId")));
                LinearLayout.LayoutParams margins=new LinearLayout.LayoutParams(-1,-2);margins.setMargins(0,dp(10),0,0);rows.addView(card,margins);
            }
        });}catch(Exception failure){main.post(()->{rows.removeAllViews();rows.addView(label("계정을 읽지 못했어. 원문과 데이터는 유지했어.",14,INK));});}});
    }
    private void openAccount(String id){io.execute(()->{try{JSONObject record=accounts().get(id);main.post(()->{if(isDestroyed()||isFinishing()||!"계정".equals(tab))return;if(record==null)notice("현재 계정 기록이 삭제·제외됐어. 이전 비밀번호로 되돌리지 않았어.");else editAccount(record);});}catch(Exception failure){main.post(()->notice("현재 계정 원문을 읽지 못했어."));}});}
    private EditText accountField(LinearLayout form,String hint,String value,boolean multiline){
        TextView caption=label(hint,12,SUBTLE);caption.setPadding(dp(2),dp(12),0,dp(4));form.addView(caption);
        EditText input=new EditText(this);input.setText(value);input.setHint(hint);input.setContentDescription(hint);input.setTextSize(15);input.setSingleLine(!multiline);if(multiline){input.setMinLines(2);input.setMaxLines(5);}
        input.setPadding(dp(12),dp(11),dp(12),dp(11));input.setBackground(ChatPalette.outlined(this,Color.WHITE,Color.rgb(225,220,236),12));form.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;
    }
    private void editAccount(JSONObject record){
        LinearLayout form=column();form.setPadding(dp(18),dp(6),dp(18),dp(18));ScrollView scroll=new ScrollView(this);scroll.addView(form);
        EditText service=accountField(form,"서비스",record.optString("service"),false);
        EditText labelInput=accountField(form,"표시 이름",record.optString("label"),false);
        EditText username=accountField(form,"아이디·이메일",record.optString("username"),false);
        EditText password=accountField(form,"비밀번호",record.optString("password"),false);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);password.setTypeface(Typeface.DEFAULT);
        EditText url=accountField(form,"로그인 주소",record.optString("loginUrl"),false);
        EditText notes=accountField(form,"메모",record.optString("notes"),true);
        JSONObject initialExtra=record.optJSONObject("extra")==null?new JSONObject():record.optJSONObject("extra");
        AccountMfaFields authentication=new AccountMfaFields(form,initialExtra);
        LinearLayout advanced=column();EditText extra=accountField(advanced,"추가 필드 · JSON",initialExtra.toString(),true);
        form.addView(disclosure("고급 추가 정보",advanced,false));
        boolean existing=!record.optString("accountId").isEmpty();final AlertDialog[] editor=new AlertDialog[1];
        if(existing){form.addView(button("수정 이력 보기",()->showAccountHistory(record.optString("accountId"))));form.addView(button("계정 보관함에서 삭제",()->{
            String actor=UiAutomationProvenance.currentActor(this);
            io.execute(()->{try{app.getFiles().withExclusiveStorageMaintenance(()->accounts().delete(record.optString("accountId"),actor));main.post(()->{if(editor[0]!=null)editor[0].dismiss();notice("계정 보관함의 복사본과 이전 값을 삭제했어. 일반 대화 원문은 그대로야.");showAccounts();});}catch(Exception failure){main.post(()->notice("계정 삭제를 완료하지 못했어."));}});
        }));}
        AlertDialog dialog=new ObservedDialogBuilder(this).setTitle(existing?"계정 수정":"새 계정").setView(scroll).setPositiveButton("저장",null).setNegativeButton("닫기",null).create();
        editor[0]=dialog;
        dialog.setOnShowListener(unused->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{JSONObject supplied=new JSONObject().put("service",service.getText().toString()).put("username",username.getText().toString()).put("password",password.getText().toString())
                    .put("label",labelInput.getText().toString()).put("loginUrl",url.getText().toString()).put("notes",notes.getText().toString()).put("extra",authentication.apply(new JSONObject(extra.getText().toString())));
                if(existing)supplied.put("accountId",record.optString("accountId"));String actor=UiAutomationProvenance.currentActor(this);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                io.execute(()->{try{accounts().saveFromUi(supplied,actor,existing?record:null);main.post(()->{dialog.dismiss();showAccounts();notice("계정의 최신 값을 저장했어. 실제 로그인 성공은 별도 확인해.");});}catch(AccountStore.AccountChangedException conflict){main.post(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);notice("다른 화면이나 대화에서 계정이 바뀌었어. 최신 계정을 다시 열고 수정해 줘. 이전 값으로 덮어쓰지 않았어.");});}catch(Exception failure){main.post(()->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);notice("계정 저장을 완료하지 못했어. 서비스·아이디와 추가 필드 형식을 확인해.");});}});
            }catch(Exception invalid){notice("추가 필드의 JSON과 2단계 인증의 숫자 설정을 확인해 줘.");}
        }));dialog.show();UiAutomationProvenance.observeWindow(this,dialog.getWindow());
    }
    /** Friendly MFA fields overlay only explicit edits; unrelated advanced values stay exact. */
    private final class AccountMfaFields {
        final EditText secret,algorithm,digits,period,issuer,accountName,recoveryCodes,mfaNotes;
        final CheckBox removeTotp,removeRecovery;
        final String[] before;
        AccountMfaFields(LinearLayout form,JSONObject extra){
            JSONObject totp=extra.optJSONObject("totp"),mfa=extra.optJSONObject("mfa");
            form.addView(label("2단계 인증 · 선택",17,INK));
            form.addView(label("인증앱 비밀키가 있으면 1회용 코드를 계산할 수 있어. SMS·이메일·알림 인증은 도착한 코드를 확인하고, 패스키·보안키는 Android가 요구하는 본인 확인이 필요할 수 있어. 저장만으로 로그인 완료가 되지는 않아.",12,SUBTLE));
            secret=accountField(form,"인증앱 비밀키 · Base32",value(totp,"secret",""),false);
            secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);secret.setTypeface(Typeface.DEFAULT);
            recoveryCodes=accountField(form,"복구 코드 · 한 줄에 하나",recoveryText(extra.opt("recoveryCodes")),true);
            mfaNotes=accountField(form,"인증 방식 메모 · 선택",value(mfa,"notes",""),true);
            LinearLayout options=column();
            algorithm=accountField(options,"인증 알고리즘 · SHA1, SHA256, SHA512",value(totp,"algorithm","SHA1"),false);
            digits=accountField(options,"인증 코드 자릿수 · 6 또는 8",value(totp,"digits","6"),false);digits.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            period=accountField(options,"인증 코드 갱신 간격 · 초",value(totp,"period","30"),false);period.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            issuer=accountField(options,"인증앱 서비스 이름 · 선택",value(totp,"issuer",""),false);
            accountName=accountField(options,"인증앱 계정 이름 · 선택",value(totp,"accountName",""),false);
            removeTotp=new CheckBox(MainActivity.this);removeTotp.setText("저장한 인증앱 설정 삭제");options.addView(removeTotp);
            removeRecovery=new CheckBox(MainActivity.this);removeRecovery.setText("저장한 복구 코드 목록 삭제");options.addView(removeRecovery);
            form.addView(disclosure("인증앱 세부 설정",options,false));
            before=new String[]{text(secret),text(algorithm),text(digits),text(period),text(issuer),text(accountName),text(recoveryCodes),text(mfaNotes)};
        }
        JSONObject apply(JSONObject extra)throws org.json.JSONException {
            boolean introducing=!text(secret).isEmpty()&&changed(secret,0)&&!(extra.opt("totp") instanceof JSONObject);
            if(removeTotp.isChecked())extra.remove("totp");
            else if(introducing||changed(secret,0)||changed(algorithm,1)||changed(digits,2)||changed(period,3)||changed(issuer,4)||changed(accountName,5)){
                JSONObject totp=extra.optJSONObject("totp");if(totp==null)totp=new JSONObject();
                if(changed(secret,0)){if(text(secret).isEmpty())totp.remove("secret");else totp.put("secret",text(secret));}
                if(introducing||changed(algorithm,1)){
                    String selected=text(algorithm).toUpperCase(java.util.Locale.ROOT);
                    if(!java.util.Arrays.asList("SHA1","SHA256","SHA512").contains(selected))throw new IllegalArgumentException("Invalid TOTP algorithm");
                    totp.put("algorithm",selected);
                }
                if(introducing||changed(digits,2)){int selected=Integer.parseInt(text(digits));if(selected!=6&&selected!=8)throw new IllegalArgumentException("Invalid TOTP digits");totp.put("digits",selected);}
                if(introducing||changed(period,3)){int selected=Integer.parseInt(text(period));if(selected<=0)throw new IllegalArgumentException("Invalid TOTP period");totp.put("period",selected);}
                if(changed(issuer,4)){if(text(issuer).isEmpty())totp.remove("issuer");else totp.put("issuer",text(issuer));}
                if(changed(accountName,5)){if(text(accountName).isEmpty())totp.remove("accountName");else totp.put("accountName",text(accountName));}
                if(totp.length()==0)extra.remove("totp");else extra.put("totp",totp);
            }
            if(removeRecovery.isChecked()||(changed(recoveryCodes,6)&&text(recoveryCodes).isEmpty()))extra.remove("recoveryCodes");
            else if(changed(recoveryCodes,6)){JSONArray codes=new JSONArray();for(String code:text(recoveryCodes).split("\n",-1))codes.put(code);extra.put("recoveryCodes",codes);}
            if(changed(mfaNotes,7)){JSONObject mfa=extra.optJSONObject("mfa");if(mfa==null)mfa=new JSONObject();if(text(mfaNotes).isEmpty())mfa.remove("notes");else mfa.put("notes",text(mfaNotes));if(mfa.length()==0)extra.remove("mfa");else extra.put("mfa",mfa);}
            return extra;
        }
        private boolean changed(EditText field,int index){return !before[index].equals(text(field));}
        private String text(EditText field){return field.getText().toString();}
        private String value(JSONObject object,String key,String fallback){return object==null||!object.has(key)||object.isNull(key)?fallback:String.valueOf(object.opt(key));}
        private String recoveryText(Object value){if(!(value instanceof JSONArray))return "";JSONArray codes=(JSONArray)value;StringBuilder lines=new StringBuilder();for(int i=0;i<codes.length();i++){if(!(codes.opt(i) instanceof String))return "";if(i>0)lines.append('\n');lines.append(codes.optString(i));}return lines.toString();}
    }
    private void showAccountHistory(String id){io.execute(()->{try{JSONArray history=accounts().history(id,1000);main.post(()->{
        if(isDestroyed()||isFinishing()||!"계정".equals(tab))return;
        LinearLayout form=column();form.setPadding(dp(18),dp(8),dp(18),dp(16));for(int i=0;i<history.length();i++){JSONObject version=history.optJSONObject(i);if(version==null)continue;
            LinearLayout entry=column();entry.addView(label((version.optBoolean("current")?"현재":"이전")+" · 수정 "+version.optInt("revision"),16,INK));
            TextView details=label(prettyJson(version),13,SUBTLE);details.setTextIsSelectable(true);entry.addView(details);form.addView(disclosure(version.optBoolean("current")?"현재 값":"이전 값 "+(i+1),entry,false));
        }ScrollView scroll=new ScrollView(this);scroll.addView(form);new ObservedDialogBuilder(this).setTitle("계정 수정 이력").setView(scroll).setPositiveButton("닫기",null).show();
    });}catch(Exception failure){main.post(()->notice("계정 수정 이력을 읽지 못했어."));}});}

    private void requestBasicPermissions() {
        String[] missing = new PermissionCatalog(this).missingRuntimePermissions();
        if (missing.length == 0) notice("요청 가능한 기본 권한은 이미 허용됐어."); else requestPermissions(missing, 300);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] names, int[] results) {
        super.onRequestPermissionsResult(requestCode, names, results);
        if(permissionOnboarding!=null)permissionOnboarding.recordRequestResult(this,names,results);
        if(checkPermissionGate())return;
        if ("권한".equals(tab)) showPermissions();
    }

    private void showFiles() {
        if(isDestroyed()||isFinishing()||!"파일".equals(tab)||app.getRuntime().isRestoringBackup())return;
        content.removeAllViews(); LinearLayout panel = scrollPanel();
        panel.addView(label("작업 파일", 20, INK));
        panel.addView(label("파일은 폰의 앱 내부 저장소에 보관해. 일반 캐시 삭제에는 남아. 앱 삭제·데이터 삭제 전에 백업을 내보내야 해.", 14, SUBTLE));
        panel.addView(button("사진·파일 가져오기", () -> pickDocuments(PICK_FILES, "*/*")));
        panel.addView(button("대화·기억·파일 백업 내보내기", () -> createDocument(EXPORT_BACKUP, "application/zip", "hoyoung-backup.zip")));
        panel.addView(label("재설치 보존 폴더",18,INK));
        panel.addView(label("Documents/호영 같은 일반 폴더를 선택해. 삭제·제외 기록은 변경할 때 자동 보존하고, 전체 대화·파일은 백업 저장 버튼으로 보관해. 재설치 뒤에는 같은 폴더를 먼저 다시 연결한 다음 ZIP을 복원해.",14,SUBTLE));
        ownerFolderState=label(ownerFolderDescription(),14,SUBTLE);panel.addView(ownerFolderState);
        panel.addView(button("보존 폴더 선택·다시 연결",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),OWNER_FOLDER)));
        panel.addView(button("보존 폴더에 대화·기억·파일 백업 저장",()->{
            if(!app.ownerPolicyFolderStatus().optBoolean("connected")){notice("먼저 재설치 보존 폴더를 선택해 줘.");return;}
            notice("선택한 폴더에 백업을 저장하고 실제 파일을 확인하고 있어.");
            io.execute(()->{
                try{String name=app.exportBackupToOwnerFolder();main.post(()->{if(!isDestroyed()){notice("보존 폴더에 백업을 저장했어: "+name);showFiles();}});}
                catch(Exception failed){main.post(()->{if(!isDestroyed())notice("보존 폴더 백업을 완료하지 못했어. 폴더 접근과 여유 공간을 확인해 줘. 미완성 파일은 복원하지 마.");});}
            });
        }));
        panel.addView(button("백업 용량 확인", () -> io.execute(() -> {
            try {
                JSONObject capacity = app.getFiles().backupPreflight(app.getStore());
                main.post(() -> notice("폰 여유 공간: " + (capacity.optLong("privateAvailableBytes") / (1024 * 1024)) + "MiB\n백업 한도: " + app.getStore().getSetting("backupMaxGiB", "64") + "GiB\n외부 저장 위치의 여유 공간은 Android 제공자가 알려 주지 않을 수 있어. 내보내기 중 오류가 나면 완료로 표시하지 않아."));
            } catch (Exception failed) { main.post(() -> notice("백업 여유 공간을 확인하지 못했어. 폰 저장 공간을 확인해 줘.")); }
        })));
        panel.addView(button("백업 복원", () -> {
            if (isActive()) { notice("진행 중 작업이 있어. 완료를 확인한 뒤 복원해."); return; }
            pickDocuments(IMPORT_BACKUP, "application/zip");
        }));
        EditText query = new EditText(this); query.setSingleLine(true); query.setHint("파일 이름이나 경로 검색"); panel.addView(query);
        LinearLayout items = column();
        panel.addView(button("파일 검색", () -> loadFileListing(items, query.getText().toString()))); panel.addView(items);
        loadFileListing(items, "");
    }
    private void loadFileListing(LinearLayout items, String query) {
        items.removeAllViews(); items.addView(label("파일 목록을 준비하고 있어…", 13, SUBTLE));
        final long generation = chatGeneration; final Object queryToken = new Object(); items.setTag(queryToken);
        fileListing.execute(() -> {
            try {
                JSONObject page = app.getFiles().listFilesPage(100, query);
                main.post(() -> {
                    if (isDestroyed() || isFinishing() || generation != chatGeneration || items.getTag() != queryToken || !"파일".equals(tab) || !items.isAttachedToWindow()) return;
                    items.removeAllViews();
                    items.addView(label("찾은 파일 " + page.optLong("matchedCount") + "개 · 최근 " + page.optJSONArray("files").length() + "개 표시", 12, SUBTLE));
                    if (page.optBoolean("truncated")) items.addView(label("이름이나 경로로 검색하면 이전 파일도 찾을 수 있어.", 12, SUBTLE));
                    JSONArray files = page.optJSONArray("files");
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject file = files.optJSONObject(i); if (file == null) continue;
                        String id = file.optString("id"), name = file.optString("displayName", id);
                        items.addView(button(name + " · " + (file.optLong("size") / 1024) + " KB", () -> new ObservedDialogBuilder(this)
                            .setTitle(name).setItems(new String[]{"대화에 첨부", "외부에 저장", "텍스트 보기"}, (dialog, which) -> {
                                if (which == 0) { if (attachments.length() < 8) attachments.put(id); persistDraft(); showTab("대화"); }
                                else if (which == 1) { exportFileId = id; createDocument(EXPORT_FILE, "application/octet-stream", name); }
                                else display.execute(() -> {
                                    try { String text = app.getFiles().readText(id, 20000); main.post(() -> { if (!isDestroyed() && !isFinishing()) new ObservedDialogBuilder(this).setTitle(name).setMessage(text).setPositiveButton("닫기", null).show(); }); }
                                    catch (Exception e) { main.post(() -> notice("이 파일은 텍스트로 열 수 없어.")); }
                                });
                            }).show()));
                    }
                });
            } catch (Exception failed) { main.post(() -> { if (!isDestroyed() && items.isAttachedToWindow()) { items.removeAllViews(); items.addView(label("파일 목록을 읽지 못했어. 원본을 삭제하지 않았어.", 14, SUBTLE)); } }); }
        });
    }

    private void showSettings() {
        content.removeAllViews(); LinearLayout panel = scrollPanel();
        JSONObject baseline=app.getRuntime().personalSettingsBaseline();
        addIdentitySettings(panel);
        panel.addView(label("자율 모드", 20, INK));
        panel.addView(label("폰이 켜져 있고 인터넷과 Work 연결이 준비된 동안 주기적으로 판단해. 화면이 꺼져도 호출을 이어가려면 배터리 최적화 예외를 켜야 해. 자율 모드는 배터리 사용을 늘릴 수 있어.", 14, SUBTLE));
        panel.addView(button("자율 모드 켜기", this::startAutonomy));
        panel.addView(button("자율 모드 끄기", () -> { app.getRuntime().stopAutonomy(); stopService(new Intent(this, AutonomyService.class)); refreshStatus(); }));
        EditText interval = new EditText(this); interval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); interval.setHint("판단 간격(초)");
        interval.setText(app.getStore().getSetting("heartbeatSeconds", "300")); panel.addView(interval);
        EditText goals = new EditText(this); goals.setHint("계속 맡길 일과 목표"); goals.setMinLines(3); goals.setText(app.getStore().getSetting("standingGoals", "")); panel.addView(goals);
        panel.addView(label("맞춤 지침", 20, INK));
        panel.addView(button("비서의 기본 운영 지침 보기", () -> {
            try (InputStream input = getAssets().open("core-identity.txt")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                new ObservedDialogBuilder(this).setTitle(assistantName()+"의 기본 운영 지침").setMessage(output.toString(StandardCharsets.UTF_8.name())).setPositiveButton("닫기", null).show();
            } catch (Exception failure) { notice("기본 지침을 불러오지 못했어."); }
        }));
        panel.addView(label("실행 방식", 17, INK));
        panel.addView(label("계속 실행은 화면이 꺼져도 CPU를 유지해서 배터리를 더 써. 작업 중 실행은 쉬는 동안 절전하므로 Android가 판단 시간을 늦출 수 있어. 충전 중 실행은 충전 중에는 유지하고 배터리 사용 중에는 작업에 맞춰 깨어 있어.", 12, SUBTLE));
        android.widget.RadioGroup wakeGroup = new android.widget.RadioGroup(this);
        String[] wakeModes = {"continuous", "while_working", "charging"}, wakeLabels = {"계속 실행 · 자율 판단 우선", "작업 중 실행 · 배터리 우선", "충전 중 계속 실행"};
        String currentWake = app.getStore().getSetting("wakeMode", "continuous");
        for (int i = 0; i < wakeModes.length; i++) { android.widget.RadioButton option = new android.widget.RadioButton(this); option.setId(6000 + i); option.setText(wakeLabels[i]); wakeGroup.addView(option); if (wakeModes[i].equals(currentWake)) option.setChecked(true); }
        wakeGroup.setOnCheckedChangeListener((group, id) -> { int choice = id - 6000; if (choice >= 0 && choice < wakeModes.length) { saveImmediateSetting("wakeMode",wakeModes[choice]); AutonomyService.reevaluateActiveService(); } }); panel.addView(wakeGroup);
        panel.addView(label("기억과 백업",17,INK));
        panel.addView(label("전체 백업에 대화·기억·계정뿐 아니라 이름·프사 원본·영구 라우팅·사용자 지침·개인 설정을 함께 담아. 현재 화면의 임시 선택은 복원하지 않아.",12,SUBTLE));
        panel.addView(button("백업과 복원 열기",()->showTab("파일")));
        addSettingToggle(panel, "자동 기억 검토", "memoryReviewEnabled", true);
        addSettingToggle(panel, "허용한 휴대폰 정보 활용", "phoneContextEnabled", true);
        addSettingToggle(panel, "폰 내부 의미 검색", "semanticSearchEnabled", true);
        addSettingToggle(panel, "비서의 답장·선톡 알림", "replyNotificationsEnabled", true);
        EditText backupLimit = new EditText(this); backupLimit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); backupLimit.setHint("백업 용량 한도(GiB, 1~64)");
        backupLimit.setText(app.getStore().getSetting("backupMaxGiB", "64")); panel.addView(backupLimit);
        panel.addView(label("사용량 절약 · 하루 10% 목표", 17, INK));
        CheckBox optimize = new CheckBox(this); optimize.setText("판단·기억 처리에 맞춰 실행 옵션 분리");
        optimize.setChecked("true".equals(app.getStore().getSetting("usageOptimizationEnabled", "true"))); panel.addView(optimize);
        panel.addView(label("대화 응답은 아래 Fast 스위치를 따라. 정기 판단은 기본 Medium·Standard, 기억 검토는 최소 Medium으로 분리해. 판단 간격과 기억 보관량은 줄이지 않아.", 12, SUBTLE));
        usageBudgetState = label(usageBudgetDescription(app.getRuntime().state().optJSONObject("usageBudget")), 13, INK);
        panel.addView(usageBudgetState);
        panel.addView(label("공개된 고정 하루 토큰 한도는 없어. 아래 목표는 사용량 화면의 한도 비율을 기준으로 해. 실제 사용량을 확인하지 못하면 10% 달성으로 표시하지 않아. 다른 ChatGPT·Codex 작업도 같은 계정 한도를 사용할 수 있어.", 12, SUBTLE));
        panel.addView(button("열린 Work 사용량 화면 읽기", this::showProviderUsageObservation));
        panel.addView(label("Work 탭에서 실제 사용량 화면을 먼저 열고 이 버튼을 눌러. 화면을 이동하거나 AI를 호출하지 않고 현재 보이는 Work·Codex 한도 비율과 리셋 시각만 읽어. 화면 형식이 지원되지 않거나 값이 불분명하면 관찰 완료로 처리하지 않아.", 12, SUBTLE));
        panel.addView(button("사용량 화면에서 확인한 수치 입력", this::showUsageObservation));
        EditText budgetTarget = new EditText(this); budgetTarget.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        budgetTarget.setHint("일평균 절약 목표(%, 1~100)"); budgetTarget.setText(app.getStore().getSetting("budgetTargetPercent", "10")); panel.addView(budgetTarget);
        android.widget.Spinner budgetBasisChoice = new android.widget.Spinner(this);
        String[] budgetBases = {"pool_per_day", "daily_share"};
        budgetBasisChoice.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"사용량 화면의 전체 한도 중 하루 목표 비율", "고급 · 전체 한도를 7일로 나눈 하루 몫 중 목표 비율"}));
        budgetBasisChoice.setSelection("daily_share".equals(app.getStore().getSetting("budgetBasis", "pool_per_day")) ? 1 : 0); panel.addView(budgetBasisChoice);
        panel.addView(label("10%는 평균 사용량을 줄이는 목표야. 목표에 도달해도 대화·자율 판단·기억 처리를 중단하지 않아. 두 번째 기준은 전체 한도를 7로 나누므로 같은 10%여도 7배 엄격해.", 12, SUBTLE));
        panel.addView(label("정기 판단과 기억 모델",17,INK));
        android.widget.Spinner autonomyEffortChoice = new android.widget.Spinner(this), memoryEffortChoice = new android.widget.Spinner(this), backgroundSpeedChoice = new android.widget.Spinner(this);
        String[] autonomyEfforts = {"low", "medium", "high", "xhigh", "max", "ultra"}, memoryEfforts = {"medium", "high", "xhigh", "max", "ultra"}, backgroundSpeeds = {"standard", "fast"};
        panel.addView(label("정기 판단 추론 강도 · Low는 Work의 Light", 12, SUBTLE));
        autonomyEffortChoice.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, autonomyEfforts));
        autonomyEffortChoice.setSelection(Math.max(0, java.util.Arrays.asList(autonomyEfforts).indexOf(app.getStore().getSetting("autonomousEffort", "medium")))); panel.addView(autonomyEffortChoice);
        panel.addView(label("기억 검토 최소 추론 강도", 12, SUBTLE));
        memoryEffortChoice.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, memoryEfforts));
        memoryEffortChoice.setSelection(Math.max(0, java.util.Arrays.asList(memoryEfforts).indexOf(app.getStore().getSetting("memoryMinimumEffort", "medium")))); panel.addView(memoryEffortChoice);
        panel.addView(label("백그라운드 작업 속도 · Standard 권장", 12, SUBTLE));
        backgroundSpeedChoice.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, backgroundSpeeds));
        backgroundSpeedChoice.setSelection(Math.max(0, java.util.Arrays.asList(backgroundSpeeds).indexOf(app.getStore().getSetting("backgroundSpeed", "standard")))); panel.addView(backgroundSpeedChoice);
        panel.addView(label("기본 모델과 응답 속도", 17, INK));
        panel.addView(label("여기서 저장한 값은 영구 설정이야. 채팅에서도 ‘앞으로 계속’이라고 바꿀 수 있어. 이번 화면에서만 바꿀 때는 보내기 옆 모델 버튼을 사용해.",12,SUBTLE));
        panel.addView(label("기본값은 6.1 Sol · Medium · Fast야. Fast를 끄면 Standard로 실행해. 사용량 절약을 켜도 네 속도 선택은 유지하고, 정기 판단은 별도 설정을 따라. 실제 Work에서 선택을 확인한 뒤 전송해.", 12, SUBTLE));
        android.widget.Spinner modelChoice = modelChoice(app.getStore().getSetting("requiredModel", "gpt-6.1-sol"), "기본 모델"); panel.addView(modelChoice);
        android.widget.Spinner effortChoice = new android.widget.Spinner(this); effortChoice.setContentDescription("기본 추론 강도");
        android.widget.Switch fastChoice = fastChoice(app.getStore().getSetting("requiredSpeed", "fast"), "기본 Fast");
        String[] efforts = {"low", "medium", "high", "xhigh", "max", "ultra"};
        effortChoice.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, efforts));
        effortChoice.setSelection(Math.max(0, java.util.Arrays.asList(efforts).indexOf(app.getStore().getSetting("requiredEffort", "medium"))));
        panel.addView(effortChoice); panel.addView(fastChoice);
        EditText instructions = new EditText(this); instructions.setMinLines(8); instructions.setGravity(Gravity.TOP);
        instructions.setText(app.getStore().getSetting("customInstructions", bundledInstructions())); panel.addView(instructions);
        java.util.function.Supplier<JSONObject> formValues=()->settingsObject(
            "heartbeatSeconds",interval.getText().toString(),"backupMaxGiB",backupLimit.getText().toString(),"budgetTargetPercent",budgetTarget.getText().toString(),
            "standingGoals",goals.getText().toString(),"customInstructions",instructions.getText().toString(),
            "requiredModel",com.hoyoung.assistant.ui.ModelChoices.id(modelChoice.getSelectedItemPosition()),"requiredEffort",efforts[effortChoice.getSelectedItemPosition()],"requiredSpeed",fastChoice.isChecked()?"fast":"standard",
            "usageOptimizationEnabled",optimize.isChecked(),"autonomousEffort",autonomyEfforts[autonomyEffortChoice.getSelectedItemPosition()],
            "memoryMinimumEffort",memoryEfforts[memoryEffortChoice.getSelectedItemPosition()],"backgroundSpeed",backgroundSpeeds[backgroundSpeedChoice.getSelectedItemPosition()],"budgetBasis",budgetBases[budgetBasisChoice.getSelectedItemPosition()]);
        JSONObject initialForm=formValues.get();
        panel.addView(button("설정 저장", () -> {
            try {
                int seconds = Integer.parseInt(interval.getText().toString()); if (seconds < 60 || seconds > 86400) throw new IllegalArgumentException();
                int backupGiB = Integer.parseInt(backupLimit.getText().toString()); if (backupGiB < 1 || backupGiB > 64) throw new IllegalArgumentException();
                double targetPercent = Double.parseDouble(budgetTarget.getText().toString()); if (!Double.isFinite(targetPercent) || targetPercent < 1 || targetPercent > 100) throw new IllegalArgumentException();
                String chosenModel = com.hoyoung.assistant.ui.ModelChoices.id(modelChoice.getSelectedItemPosition()), chosenEffort = efforts[effortChoice.getSelectedItemPosition()], chosenSpeed = fastChoice.isChecked() ? "fast" : "standard";
                com.hoyoung.assistant.runtime.AssistantRuntime.validateModelPreferences(chosenModel, chosenEffort, chosenSpeed);
                String goalText = goals.getText().toString(), instructionText = instructions.getText().toString();
                if (goalText.length() > 50_000 || instructionText.length() > 50_000) throw new IllegalArgumentException();
                JSONObject desired=formValues.get(),patch=new JSONObject();
                for(java.util.Iterator<String> keys=desired.keys();keys.hasNext();){String key=keys.next();if(!java.util.Objects.equals(desired.opt(key),initialForm.opt(key)))patch.put(key,desired.opt(key));}
                for(String key:new String[]{"heartbeatSeconds","backupMaxGiB"})if(patch.has(key))patch.put(key,Integer.parseInt(patch.optString(key)));
                if(patch.has("budgetTargetPercent"))patch.put("budgetTargetPercent",targetPercent);
                JSONObject result=app.getRuntime().updatePersonalSettings(patch,baseline);
                if(!result.optBoolean("ok")){notice(result.optString("message"));return;}
                JSONObject saved=app.getRuntime().personalSettingsBaseline();
                for(java.util.Iterator<String> keys=patch.keys();keys.hasNext();){String key=keys.next();baseline.put(key,saved.opt(key));initialForm.put(key,desired.opt(key));}
                AutonomyService.reevaluateActiveService();refreshModelSelector();
                notice("변경한 지침과 영구 설정을 저장했어.");
            } catch (Exception e) { notice("판단 간격은 60~86,400초, 백업 한도는 1~64GiB, 사용량 목표는 1~100%, 지침과 목표는 각각 50,000자 이내로 입력해."); }
        }));
        panel.addView(button("한도·연결 상태 다시 확인", () -> { app.getWebController().manualRetryCheck(); refreshStatus(); }));
        panel.addView(button("연결 진단 복사", this::copyConnectionDiagnosis));
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
                panel.addView(button("이 작업을 더 이어가지 않고 제외", () -> new ObservedDialogBuilder(this)
                    .setMessage("폰의 대기 목록에서 제외해. 기존 클라우드 작업이 중지됐다는 뜻은 아니며, 같은 행동은 자동으로 다시 실행하지 않아.")
                    .setPositiveButton("제외", (dialog, which) -> {
                        try { app.getRuntime().abandonUncertain(request.optLong("id")); showSettings(); }
                        catch (Exception e) { notice("이 작업을 제외하지 못했어."); }
                    }).setNegativeButton("돌아가기", null).show()));
            }
        }
        panel.addView(button("오픈소스 라이선스", () -> {
            try (InputStream input = getAssets().open("THIRD_PARTY_NOTICES.txt")) {
                new ObservedDialogBuilder(this).setTitle("라이선스").setMessage(readUtf8(input)).setPositiveButton("닫기", null).show();
            } catch (Exception ignored) { }
        }));
        organizeSettings(panel,instructions);
    }

    private void startAutonomy() {
        if(permissionOnboarding!=null&&!permissionOnboarding.snapshot().ready()){checkPermissionGate();return;}
        app.getRuntime().startAutonomy();
        refreshStatus();
    }

    /** The owner identifies the account; the bridge reads only the already open usage view. */
    private void showProviderUsageObservation() {
        LinearLayout form = column(); form.setPadding(dp(18), dp(8), dp(18), dp(8));
        form.addView(label("Work 탭에서 네 계정의 실제 사용량 화면을 먼저 열어. 현재 열린 화면만 읽고, 다른 페이지 이동·계정 변경·AI 호출은 하지 않아. 같은 계정의 관찰에 같은 구분 이름을 써. 다른 계정이나 한도 관찰은 합치지 않아.", 13, SUBTLE));
        EditText scope = new EditText(this); scope.setSingleLine(true); scope.setHint("계정 구분 이름 · 비밀번호 입력 금지"); form.addView(scope);
        AlertDialog dialog = new ObservedDialogBuilder(this).setTitle("열린 사용량 화면 관찰").setView(form)
                .setPositiveButton("보이는 사용량 읽기", null).setNegativeButton("취소", null).create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String account = scope.getText().toString().trim();
            if (account.isEmpty() || account.length() > 80 || account.contains("\n")) {
                notice("같은 계정의 관찰에 사용할 구분 이름을 80자 이내로 입력해. 비밀번호나 인증정보는 입력하지 마."); return;
            }
            if (app.getWebController().getExistingView() == null) {
                notice("Work 탭에서 실제 사용량 화면을 먼저 열어 줘. 새로운 페이지를 자동으로 열지 않았어."); return;
            }
            try {
                app.getWebController().captureUsageObservation(account);
                dialog.dismiss();
                notice("열린 화면의 사용량 관찰을 요청했어. 실제 수치와 리셋 시각을 확인한 경우에만 기록해. 결과는 사용량 상태에서 확인해.");
            } catch (RuntimeException unavailable) {
                notice("열린 사용량 화면을 읽지 못했어. 사용량 관찰이나 10% 달성으로 기록하지 않았어.");
            }
        }));
        dialog.show(); UiAutomationProvenance.observeWindow(this,dialog.getWindow());
    }

    /** Owner-entered figures remain reports rather than provider-observed telemetry. */
    private void showUsageObservation() {
        LinearLayout form = column(); form.setPadding(dp(18), dp(8), dp(18), dp(8));
        form.addView(label("Work·Codex 사용량 화면의 현재 '사용한 비율'을 입력해. 남은 비율이나 오늘 토큰 수가 아니야. 같은 계정·한도·리셋 시각의 관찰 두 개가 있어야 변화량을 계산할 수 있어. 수동 입력은 서버 확인이나 10% 달성 증거로 취급하지 않아.", 13, SUBTLE));
        EditText scope = new EditText(this); scope.setSingleLine(true); scope.setHint("계정 구분 이름 · 비밀번호 입력 금지"); form.addView(scope);
        EditText percent = new EditText(this); percent.setSingleLine(true); percent.setHint("현재 사용한 비율(%, 0~100)");
        percent.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); form.addView(percent);
        EditText reset = new EditText(this); reset.setSingleLine(true); reset.setHint("화면의 다음 리셋 시각 · 2026-10-18T15:00+09:00"); form.addView(reset);
        form.addView(label("리셋 날짜·시각과 시간대는 화면에서 확인한 값으로 입력해. 확인할 수 없으면 입력을 취소해. 정수 비율 표시의 오차는 1%p로 보수적으로 반영해.", 12, SUBTLE));
        AlertDialog dialog = new ObservedDialogBuilder(this).setTitle("사용량 수동 관찰 기록").setView(form)
                .setPositiveButton("관찰 기록", null).setNegativeButton("취소", null).create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                String account = scope.getText().toString().trim();
                if (account.isEmpty() || account.length() > 80 || account.contains("\n")) throw new IllegalArgumentException();
                double used = Double.parseDouble(percent.getText().toString().trim());
                if (!Double.isFinite(used) || used < 0 || used > 100) throw new IllegalArgumentException();
                long resetAt = java.time.OffsetDateTime.parse(reset.getText().toString().trim()).toInstant().toEpochMilli();
                if (resetAt <= System.currentTimeMillis()) throw new IllegalArgumentException();
                JSONObject observation = new JSONObject().put("accountScope", account).put("poolId", "work-codex:" + resetAt)
                        .put("resetAt", resetAt).put("usedPercent", used).put("precisionPercent", 1)
                        .put("origin", "owner_reported").put("evidenceRef", "owner_settings_manual");
                app.getRuntime().recordUsageObservation(observation);
                dialog.dismiss();
                if (usageBudgetState != null) usageBudgetState.setText(usageBudgetDescription(app.getRuntime().state().optJSONObject("usageBudget")));
                notice("수동 관찰을 저장했어. 서버 검증이나 10% 달성 확인으로 취급하지 않아.");
            } catch (Exception invalid) {
                notice("계정 구분 이름, 0~100%의 사용 비율, 시간대가 포함된 미래 리셋 시각을 입력해. 수치를 확인하지 못했으면 취소해.");
            }
        }));
        dialog.show(); UiAutomationProvenance.observeWindow(this,dialog.getWindow());
    }

    static String usageBudgetDescription(JSONObject budget) {
        if (budget == null) return "실제 한도 사용량 미확인 · 10% 달성 미검증";
        StringBuilder text = new StringBuilder("일평균 절약 목표 ").append(budget.optDouble("targetPercent", 10)).append("%");
        if ("daily_share".equals(budget.optString("basis"))) text.append(" · 전체 한도의 하루 몫 기준");
        else text.append(" · 전체 한도 기준");
        JSONObject observation = budget.optJSONObject("observation");
        boolean ownerReported = observation != null && "owner_reported".equals(observation.optString("origin"));
        boolean verified = budget.optBoolean("verifiedObservationFresh", false);
        String budgetState = budget.optString("budgetState", "unknown");
        if (verified && budget.has("measuredLowerPercent") && budget.has("measuredUpperPercent")) {
            text.append("\n관찰 구간 사용량 ").append(budget.optDouble("measuredLowerPercent")).append("~")
                    .append(budget.optDouble("measuredUpperPercent")).append("%p · 공유 계정 전체");
        } else if (ownerReported) {
            text.append("\n사용자 수동 관찰 있음 · 실제 한도·10% 달성 미검증");
        } else text.append("\n실제 한도 사용량 미확인 · 10% 달성 미검증");
        if (budget.optBoolean("partialDayCoverage", true)) text.append("\n하루 전체 관찰이 아니므로 하루 사용량 달성을 확인할 수 없어.");
        if ("target_exceeded".equals(budgetState)) text.append("\n관찰 구간의 사용량이 목표보다 높아. 작업은 계속 진행해.");
        JSONObject statistics = budget.optJSONObject("requestStatistics");
        if (statistics != null) {
            text.append("\n앱에서 접수 확인된 호출 ").append(statistics.optLong("acceptedRequests")).append("회");
            text.append(" · 전달 문맥 ").append(statistics.optLong("promptUtf16Chars")).append("자(UTF-16)");
        }
        text.append("\n호출 수·문자 수는 실제 토큰이나 한도 차감량이 아니야.");
        text.append("\n절약 목표에 따른 호출 차단은 없어. 실제 서비스 한도 소진은 별도로 처리해.");
        return text.toString();
    }

    private boolean isActive() {
        JSONObject state = app.getRuntime().state();
        return !state.isNull("activeRequestId") && !state.optString("activeRequestId").isEmpty();
    }

    private String assistantName(){return AssistantIdentity.name(app.getStore());}
    private TextView identityLabel(String suffix,int size,int color){TextView view=label(assistantName()+suffix,size,color);view.setTag("assistant-name:"+suffix);return view;}
    private void refreshIdentityViews(View view,String name){
        if(view instanceof HoyoungAvatarView)((HoyoungAvatarView)view).refreshIdentity();
        if(view instanceof TextView&&view.getTag() instanceof String&&((String)view.getTag()).startsWith("assistant-name:"))
            ((TextView)view).setText(name+((String)view.getTag()).substring("assistant-name:".length()));
        if(view.getTag() instanceof String tag&&tag.startsWith("assistant-reaction:"))view.setContentDescription(name+"이 이 메시지에 "+tag.substring("assistant-reaction:".length())+" 이모지로 응답함");
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)refreshIdentityViews(group.getChildAt(i),name);
    }
    private void refreshModelSelector(){
        if(modelSelector==null)return;
        JSONObject current=app.getRuntime().foregroundRoutingState();boolean selected=current.optBoolean("selected");
        String model=selected?current.optString("model"):app.getStore().getSetting("requiredModel","gpt-6.1-sol");
        String effort=selected?current.optString("effort"):app.getStore().getSetting("requiredEffort","medium");
        String speed=selected?current.optString("speed","fast"):app.getStore().getSetting("requiredSpeed","fast");
        modelSelector.setText(com.hoyoung.assistant.ui.ModelChoices.label(model)+" · "+effortLabel(effort)+" · "+("fast".equals(speed)?"Fast":"Standard")+"⌄");
        modelSelector.setTextColor(selected?BLUE:INK);
        modelSelector.setContentDescription("이번 화면의 모델 선택 · "+model+" · "+effort+" · "+speed+(selected?" · 임시 선택":" · 기본 설정"));
    }
    private static String effortLabel(String effort){return switch(effort){case "low"->"Light";case "xhigh"->"XHigh";default->effort.isEmpty()?"Medium":Character.toUpperCase(effort.charAt(0))+effort.substring(1);};}
    private android.widget.Spinner modelChoice(String value,String description){
        android.widget.Spinner choice=new android.widget.Spinner(this);choice.setContentDescription(description);
        choice.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,com.hoyoung.assistant.ui.ModelChoices.labels()));
        choice.setSelection(com.hoyoung.assistant.ui.ModelChoices.index(value));return choice;
    }
    private android.widget.Switch fastChoice(String value,String description){
        android.widget.Switch choice=new android.widget.Switch(this);choice.setText("Fast");choice.setContentDescription(description);choice.setChecked("fast".equals(value));
        choice.setTextOn("켜짐");choice.setTextOff("꺼짐 · Standard");choice.setShowText(true);choice.setMinHeight(dp(48));return choice;
    }
    private void showForegroundModelPicker(){
        JSONObject opening=app.getRuntime().foregroundRoutingState();if(!opening.optBoolean("visible"))return;
        LinearLayout panel=new com.hoyoung.assistant.ui.FormColumn(this);panel.setPadding(dp(20),dp(8),dp(20),dp(8));
        panel.addView(label("바꾸기 전까지 다음 메시지와 작업에 계속 적용해. 화면을 나가면 새 메시지는 영구 설정으로 돌아가고, 이미 맡긴 작업은 선택을 유지해. 정기 판단은 별도 설정을 따라.",13,SUBTLE));
        android.widget.Spinner model=modelChoice(opening.optBoolean("selected")?opening.optString("model"):app.getStore().getSetting("requiredModel","gpt-6.1-sol"),"이번 화면 모델");panel.addView(model);
        String[] efforts={"low","medium","high","xhigh","max","ultra"};
        android.widget.Spinner effort=new android.widget.Spinner(this);effort.setContentDescription("이번 화면 추론 강도");effort.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Light","Medium","High","XHigh","Max","Ultra"}));
        effort.setSelection(Math.max(0,java.util.Arrays.asList(efforts).indexOf(opening.optBoolean("selected")?opening.optString("effort"):app.getStore().getSetting("requiredEffort","medium"))));panel.addView(effort);
        android.widget.Switch fast=fastChoice(opening.optBoolean("selected")?opening.optString("speed","fast"):app.getStore().getSetting("requiredSpeed","fast"),"이번 화면 Fast");panel.addView(fast);
        panel.addView(label("Fast를 끄면 Standard · UltraFast는 사용하지 않아",12,SUBTLE));
        AlertDialog dialog=new ObservedDialogBuilder(this).setTitle("이번 화면의 모델").setView(panel).setPositiveButton("적용",null).setNeutralButton("기본 설정으로",null).setNegativeButton("닫기",null).create();
        dialog.show();UiAutomationProvenance.observeWindow(this,dialog.getWindow());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->applyForegroundChoice(dialog,opening,com.hoyoung.assistant.ui.ModelChoices.id(model.getSelectedItemPosition()),efforts[effort.getSelectedItemPosition()],fast.isChecked()?"fast":"standard",false));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->applyForegroundChoice(dialog,opening,"","","",true));
    }
    private void applyForegroundChoice(AlertDialog dialog,JSONObject opening,String model,String effort,String speed,boolean reset){
        try{JSONObject result=app.getRuntime().selectForegroundRouting(opening.optString("sessionId"),opening.optLong("revision"),model,effort,speed,reset);
            if(!result.optBoolean("ok")){dialog.dismiss();notice(result.optString("message"));return;}dialog.dismiss();refreshModelSelector();
        }catch(RuntimeException invalid){notice("모델과 추론 강도·속도를 다시 선택해 줘.");}
    }

    private void refreshStatus() {
        if(isFinishing()||isDestroyed()||app==null||app.getRuntime()==null)return;
        refreshDraftFromStore();
        refreshModelSelector();
        String currentIdentity=app.getRuntime().assistantIdentity().toString();
        if(!currentIdentity.equals(renderedIdentity)){
            renderedIdentity=currentIdentity;if(shell!=null)refreshIdentityViews(shell,assistantName());
            if(screenTitle!=null)screenTitle.setText("대화".equals(tab)?assistantName():tab);
        }
        if("파일".equals(tab)&&ownerFolderState!=null)ownerFolderState.setText(ownerFolderDescription());
        if (connection == null || permissionBlocked) return;
        JSONObject state = app.getRuntime().state();
        if ("설정".equals(tab) && usageBudgetState != null) usageBudgetState.setText(usageBudgetDescription(state.optJSONObject("usageBudget")));
        JSONObject web = state.optJSONObject("webState");
        if (web == null) web = state.optJSONObject("web");
        String code = state.optString("lastCode", state.optString("status", ""));
        String readiness = state.optString("readinessCode", "");
        if (!state.optBoolean("ready") && !readiness.isEmpty() && !"ready".equals(readiness)) code = readiness;
        if ("Work".equals(tab) && resumed) {
            WebView existing = app.getWebController().getExistingView();
            if (existing != null && existing.getParent() == null) { showTab("Work"); return; }
        }
        if (state.optBoolean("storageRecoveryBlocked")) connection.setText("저장소 복구 필요 · 앱을 다시 열어 복구해 줘");
        else if (state.optBoolean("restoringBackup")) connection.setText("백업 복원 중 · 자동 실행을 멈췄어");
        else if (state.optBoolean("quotaBlocked")) connection.setText("사용 한도 대기 · 메시지와 작업은 폰에 저장돼");
        else if (state.optBoolean("uncertaintyBlocked")) connection.setText("이전 작업 결과 확인 필요 · 자동 재전송을 멈췄어");
        else if (isActive()) connection.setText(("preparing".equals(state.optString("activePhase")) ? "관련 기억·요청 준비 중" : "Work 전송·응답 대기") + " · " + (state.optLong("activeWaitingMillis") / 1000) + "초 · " + (state.optLong("queuedOwnerInputs") > 0 ? "추가 지시 " + state.optLong("queuedOwnerInputs") + "개 저장 · 후속 동작에 반영" : "추가 메시지를 보내도 돼"));
        else if ("prompt_too_large".equals(code)) connection.setText("문맥이 너무 길어 전송을 멈췄어 · 원문은 폰에 남아 있어");
        else if ("ATTACHMENT_NOT_READY".equals(code) || code.startsWith("attachment_")) connection.setText("첨부를 확인하지 못해 전송을 멈췄어");
        else if (state.optBoolean("ready")) {
            JSONObject route = state.optJSONObject("requestModelPreferences");
            if (route == null) route = web == null ? new JSONObject() : web;
            connection.setText("Work 연결 확인 · 현재 요청 " + route.optString("model", "미확인")
                    + " · " + route.optString("effort", "미확인") + " · " + route.optString("speed", "미확인"));
        }
        else if (web != null && web.optBoolean("loading")) connection.setText("Work 페이지 로딩 중");
        else connection.setText(switch (code) {
            case "authentication_required" -> "ChatGPT 로그인 필요";
            case "authentication_unverified" -> "계정 표시 확인 필요";
            case "connection_preparing" -> "연결 확인 중";
            case "page_load_failed" -> "Work 페이지 로드 실패";
            case "work_control_unverified", "work_unverified" -> "Work 선택을 확인해 줘";
            case "temporary_control_unverified", "temporary_unverified" -> "임시 채팅 선택을 확인해 줘";
            case "model_unverified" -> "설정에 지정한 모델 선택을 확인해 줘";
            case "effort_unverified" -> "설정에 지정한 추론 강도 선택을 확인해 줘";
            case "speed_unverified" -> "설정에 지정한 속도 선택을 확인해 줘";
            case "preference_selection_pending" -> "요청에 맞는 모델·속도를 선택하는 중";
            case "preference_selection_uncertain", "preference_option_unavailable", "preference_option_ambiguous", "preference_selection_limit" -> "자동 선택 확인 중단";
            case "composer_has_draft" -> "웹 입력창의 초안 확인 필요";
            case "composer_has_attachments" -> "웹 입력창의 첨부 확인 필요";
            case "composer_unverified" -> "Work 입력창을 확인하지 못했어";
            case "startup_failed" -> "Work 시작 재시도 중 · " + Math.max(1, (web == null ? 0 : web.optLong("startupRetryAfterMs")) / 1000) + "초 후 다시 확인";
            case "work_recovery_review_required", "renderer_lost" -> "이전 Work 화면 확인 필요 · Work 탭에서 복구 상태를 확인해";
            case "bridge_script_unavailable" -> "Work 연결 도구를 준비하지 못했어 · 대화와 파일은 폰에 남아 있어";
            default -> "연결 확인 필요 · Work 탭에서 로그인과 선택 상태를 확인해";
        });
        String detail=connection.getText().toString();connection.setContentDescription(detail);
        boolean active=!state.isNull("activeRequestId")&&!state.optString("activeRequestId").isEmpty();
        boolean blocked=state.optBoolean("quotaBlocked")||state.optBoolean("uncertaintyBlocked")||state.optBoolean("storageRecoveryBlocked")||state.optBoolean("restoringBackup");
        if(blocked)connection.setText(state.optBoolean("quotaBlocked")?"사용 한도 대기 중":"작업 상태 확인 필요");
        else if(active)connection.setText("preparing".equals(state.optString("activePhase"))?"답변을 준비하고 있어":"응답을 기다리고 있어");
        else if(state.optBoolean("ready"))connection.setText("연결됨 · "+("true".equals(app.getStore().getSetting("autonomy.enabled","false"))?"자율 모드":"대화 준비 완료"));
        else connection.setText("Work".equals(tab)?detail:(web!=null&&web.optBoolean("loading")?"Work 연결 중":"Work 연결 확인 필요"));
        if("Work".equals(tab)&&workConnectionDetails!=null)workConnectionDetails.setText(com.hoyoung.assistant.ui.WorkConnectionStatus.compact(code,web));
        statusDot.setBackground(round(blocked?Color.rgb(203,146,81):state.optBoolean("ready")?Color.rgb(80,161,128):Color.rgb(177,171,191)));
        if(typingDots!=null&&typingRow!=null){boolean show=active&&state.optBoolean("activeOwnerReply")&&!blocked&&"대화".equals(tab);typingRow.setVisibility(show?View.VISIBLE:View.GONE);typingDots.setActive(show&&resumed);typingLabel.setText("preparing".equals(state.optString("activePhase"))?"기억을 살펴보고 있어":"답변을 기다리고 있어");}
    }

    private void pickDocuments(int code, String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, code == PICK_FILES);
        startActivityForResult(intent, code);
    }

    private void createDocument(int code, String mime, String name) {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, name), code);
    }

    private String ownerFolderDescription(){
        JSONObject status=app.ownerPolicyFolderStatus();
        if(!status.optBoolean("connected"))return "보존 폴더 미연결 · 앱 삭제 전에 외부 백업을 저장해 줘.";
        if(!status.optString("lastError").isEmpty())return "삭제·제외 기록 외부 보존 대기 · 폴더 접근을 확인하고 다시 연결해 줘.";
        if(status.optBoolean("pending"))return "삭제·제외 기록을 외부 폴더에 보존 중 · 완료 전에 앱 데이터를 지우지 마.";
        return "삭제·제외 기록 외부 보존 확인됨 · 전체 대화·파일 백업은 별도로 저장해 줘.";
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == WORK_FILE_PICKER) {
            java.util.ArrayList<Uri> items = selectedUris(result, data);
            app.getWebController().receiveFileChooser(items.isEmpty() ? null : items.toArray(new Uri[0])); return;
        }
        if (result != RESULT_OK || data == null) return;
        if(code==OWNER_FOLDER){
            Uri tree=data.getData();if(tree==null)return;int flags=data.getFlags();
            notice("보존 폴더의 삭제·제외 기록을 확인하고 있어.");
            io.execute(()->{
                try{app.connectOwnerPolicyFolder(tree,flags);main.post(()->{if(!isDestroyed()){notice("보존 폴더를 연결했어. 외부 보존 완료 표시를 확인해 줘.");showFiles();}});}
                catch(Exception failed){main.post(()->{if(!isDestroyed())notice("보존 폴더를 연결하지 못했어. 진행 중 작업과 폴더 권한을 확인해 줘. 삭제·제외 기록이 잘못된 폴더는 복원에 사용하지 않아.");});}
            });return;
        }
        if(code==PICK_PROFILE){
            Uri uri=data.getData();if(uri==null)return;
            final String expected=pendingProfileRevision;pendingProfileRevision=null;
            if(expected==null){notice("프로필 선택을 다시 열어 줘.");return;}
            io.execute(()->{
                try{JSONObject file=app.getFiles().saveFromUri(uri,null);JSONObject update=app.getRuntime().updateAssistantIdentity(null,file.optString("id"),false,expected);
                    main.post(()->{if(!isDestroyed()){refreshStatus();notice(update.optBoolean("ok")?"프로필 사진을 바꿨어. 새 사진도 전체 백업에 포함돼.":update.optString("message"));}});
                }catch(Exception failure){main.post(()->notice("사진을 읽지 못했어. 열 수 있는 이미지 파일을 다시 선택해 줘."));}
            });return;
        }
        if (code == PICK_FILES) {
            java.util.ArrayList<Uri> uris = selectedUris(result, data);
            if (uris.size() > 8 || attachments.length() + uris.size() > 8) {
                notice("대화 첨부는 한 번에 최대 8개야. 선택을 나누거나 기존 첨부 선택을 지워 줘. 이번 파일은 일부만 추가하지 않았어.");
                return;
            }
            final String importActor=UiAutomationProvenance.currentActor(this);
            final String storageGeneration=app.getStore().getSetting("internal.files.restoreToken","");
            io.execute(() -> {
                try {
                    app.getFiles().withExclusiveStorageMaintenance(()->{
                        if(app.getRuntime().isRestoringBackup()||!storageGeneration.equals(app.getStore().getSetting("internal.files.restoreToken","")))throw new java.io.IOException("The attachment import belongs to a previous storage generation");
                        JSONArray saved = new JSONArray();
                        for (Uri uri : uris) {
                            JSONObject file = app.getFiles().saveFromUri(uri, null);
                            String id=file.optString("id");app.getStore().putSetting("fileMeta:" + id, file.toString());saved.put(id);
                        }
                        draftStore.appendAttachments(saved,importActor);
                        return null;
                    });
                    main.post(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        refreshDraftFromStore();
                        if ("파일".equals(tab)) showFiles(); else updateComposerState();
                    });
                } catch (IllegalArgumentException full) { main.post(() -> notice("파일 원본은 보관했지만 첨부 선택이 바뀌어 이번 파일을 대화에 추가하지 않았어. 파일 탭에서 나눠 선택해 줘.")); }
                catch (Exception e) { main.post(() -> notice("파일을 저장하지 못했어. 파일 크기와 접근 권한을 확인해.")); }
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
                main.post(() -> { if (isDestroyed()) return; notice(code == IMPORT_BACKUP ? "백업을 복원했어. 불명확한 작업은 자동으로 반복하지 않아." : "선택한 위치에 저장했어."); if (code == IMPORT_BACKUP) { draftState = draftStore.read(); attachments = draftState.attachments(); showTab(tab); } else if ("파일".equals(tab)) showFiles(); });
            } catch (Exception e) {
                String description = "저장·복원을 완료하지 못했어. 기존 폰 데이터를 확인해.";
                if (e instanceof BackupFailure) {
                    BackupFailure failed = (BackupFailure)e;
                    JSONObject detail = failed.diagnostics();
                    description = "백업 작업이 멈췄어: " + failed.code + " · " + failed.phase;
                    if (failed.availableBytes >= 0 && failed.requiredBytes >= 0) description += "\n폰 여유 공간 " + (failed.availableBytes / (1024 * 1024)) + "MiB / 필요한 공간 " + (failed.requiredBytes / (1024 * 1024)) + "MiB";
                    if (detail.optBoolean("restoreCommitted")) description += "\n데이터 복원은 반영됐지만 파일 정리가 남아 있어. 앱을 다시 열어 복구를 완료해 줘.";
                    if (code == EXPORT_BACKUP) description += "\n선택한 저장 위치에 남은 불완전한 백업은 사용하지 마.";
                }
                final String report = description; main.post(() -> notice(report));
            }
        });
    }

    private java.util.ArrayList<Uri> selectedUris(int result, Intent data) {
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        if (result != RESULT_OK || data == null) return uris;
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        return uris;
    }

    private LinearLayout disclosure(String title,LinearLayout body,boolean expanded){
        LinearLayout card=column();card.setBackground(ChatPalette.outlined(this,Color.WHITE,Color.rgb(235,231,241),20));
        LinearLayout heading=row();heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(dp(16),dp(18),dp(12),dp(18));heading.setMinimumHeight(dp(60));heading.setBackground(ChatPalette.ripple(this,Color.TRANSPARENT,20));
        TextView text=label(title,15,INK);text.setTypeface(null,Typeface.BOLD);heading.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        ImageView chevron=new ImageView(this);chevron.setImageDrawable(new LineIconDrawable(LineIconDrawable.Icon.CHEVRON,SUBTLE,getResources().getDisplayMetrics().density));heading.addView(chevron,new LinearLayout.LayoutParams(dp(22),dp(22)));chevron.setRotation(expanded?90:0);
        body.setPadding(dp(16),0,dp(16),dp(12));body.setVisibility(expanded?View.VISIBLE:View.GONE);card.addView(heading);card.addView(body);
        heading.setContentDescription(title+(expanded?" 접기":" 펼치기"));heading.setOnClickListener(v->{boolean open=body.getVisibility()!=View.VISIBLE;body.setVisibility(open?View.VISIBLE:View.GONE);chevron.animate().rotation(open?90:0).setDuration(ChatPalette.animationsEnabled()?160:0).start();heading.setContentDescription(title+(open?" 접기":" 펼치기"));});return card;
    }

    private static JSONObject settingsObject(Object...pairs){JSONObject value=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);}catch(org.json.JSONException bad){throw new IllegalStateException(bad);}return value;}
    private void saveImmediateSetting(String key,Object value){
        try{JSONObject result=app.getRuntime().updatePersonalSettings(settingsObject(key,value),null);if(!result.optBoolean("ok"))notice(result.optString("message"));}
        catch(Exception failure){notice("설정을 저장하지 못했어. 현재 값을 다시 확인해 줘.");}
    }
    private void addIdentitySettings(LinearLayout panel){
        panel.addView(label("프로필과 이름",20,INK));
        JSONObject opening=app.getRuntime().assistantIdentity();
        LinearLayout profile=row();profile.setGravity(Gravity.CENTER_VERTICAL);profile.addView(new HoyoungAvatarView(this),new LinearLayout.LayoutParams(dp(64),dp(64)));
        TextView current=identityLabel("",20,INK);current.setPadding(dp(16),0,0,0);profile.addView(current);panel.addView(profile);
        panel.addView(label("현재 이름과 프사가 기억 속 예전 이름보다 우선해. 사진 원본은 폰에 저장하고 비서도 읽을 수 있어.",12,SUBTLE));
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("비서 이름");name.setContentDescription("비서 이름 편집");name.setText(opening.optString("name"));panel.addView(name);
        panel.addView(button("이름 저장",()->{
            try{JSONObject latest=app.getRuntime().assistantIdentity();
                String expected=opening.optString("name").equals(latest.optString("name"))?latest.optString("revision"):opening.optString("revision");
                JSONObject result=app.getRuntime().updateAssistantIdentity(name.getText().toString(),null,false,expected);
                if(result.optBoolean("ok")){opening.put("revision",result.optJSONObject("identity").optString("revision"));opening.put("name",result.optJSONObject("identity").optString("name"));}
                refreshStatus();notice(result.optBoolean("ok")?"이름을 바꿨어. 새 이름으로 대화를 이어 갈게.":result.optString("message"));
            }catch(Exception invalid){notice("이름은 한 줄, 1~80자로 입력해 줘.");}
        }));
        panel.addView(button("프로필 사진 변경",()->{pendingProfileRevision=app.getRuntime().assistantIdentity().optString("revision");startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),PICK_PROFILE);}));
        panel.addView(button("기본 고양이 사진으로",()->{
            try{JSONObject currentIdentity=app.getRuntime().assistantIdentity();JSONObject result=app.getRuntime().updateAssistantIdentity(null,null,true,currentIdentity.optString("revision"));
                refreshStatus();notice(result.optBoolean("ok")?"기본 고양이 사진으로 바꿨어.":result.optString("message"));
            }catch(Exception failure){notice("프로필을 저장하지 못했어.");}
        }));
    }

    private void organizeSettings(LinearLayout panel,EditText instructions){
        java.util.LinkedHashMap<String,LinearLayout> sections=new java.util.LinkedHashMap<>();
        String current="자율 모드";Button save=null;java.util.ArrayList<View> children=new java.util.ArrayList<>();
        for(int i=0;i<panel.getChildCount();i++)children.add(panel.getChildAt(i));panel.removeAllViews();
        for(View view:children){
            if(view==instructions)continue;
            if(view instanceof Button&&"설정 저장".contentEquals(((Button)view).getText())){save=(Button)view;continue;}
            if(view instanceof TextView&&!(view instanceof EditText)&&!(view instanceof Button)&&((TextView)view).getTextSize()/getResources().getDisplayMetrics().scaledDensity>=17){current=((TextView)view).getText().toString();continue;}
            if(view instanceof Button&&"한도·연결 상태 다시 확인".contentEquals(((Button)view).getText()))current="연결과 진단";
            sections.computeIfAbsent(current,key->new com.hoyoung.assistant.ui.FormColumn(this)).addView(view);
        }
        panel.addView(label("내 비서 설정",25,INK));panel.addView(label("말투부터 모델까지, 원하는 방식으로.",13,SUBTLE));
        LinearLayout custom=sections.remove("맞춤 지침");if(custom==null)custom=new com.hoyoung.assistant.ui.FormColumn(this);
        instructions.setHint("비서에게 알려 둘 말투와 사용자 지침");instructions.setMinLines(6);instructions.setMaxLines(9);instructions.setVerticalScrollBarEnabled(true);instructions.setContentDescription("사용자 지침 편집");
        custom.addView(instructions,0);sections.put("사용자 지침",custom);
        String[] order={"프로필과 이름","기본 모델과 응답 속도","정기 판단과 기억 모델","사용자 지침","자율 모드","실행 방식","기억과 백업","사용량 절약 · 하루 10% 목표","연결과 진단","확인되지 않은 작업"};
        for(String title:order){LinearLayout body=sections.remove(title);if(body!=null)panel.addView(disclosure(title,body,"프로필과 이름".equals(title)));}
        for(java.util.Map.Entry<String,LinearLayout> section:sections.entrySet())panel.addView(disclosure(section.getKey(),section.getValue(),false));
        if(save!=null){save.setTextColor(Color.WHITE);save.setBackground(ChatPalette.outgoing(this));panel.addView(save);}
    }

    private Button primaryButton(String title,Runnable action){Button result=button(title,action);result.setTextColor(Color.WHITE);result.setBackground(ChatPalette.outgoing(this));return result;}
    private LinearLayout scrollPanel() {
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); LinearLayout panel = new com.hoyoung.assistant.ui.FormColumn(this); panel.setPadding(dp(20), dp(22), dp(20), dp(24)); scroll.addView(panel);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); return panel;
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private TextView label(String text, int size, int color) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setFontFeatureSettings("kern"); view.setLineSpacing(dp(2),1.08f); if(size>=17)view.setTypeface(android.graphics.Typeface.create("sans-serif-medium",Typeface.NORMAL)); return view; }
    private Button button(String text, Runnable action) { Button button = new Button(this); button.setText(text); button.setTextSize(14); button.setTextColor(BLUE); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setPadding(dp(16),dp(10),dp(16),dp(10)); button.setElevation(0); button.setStateListAnimator(null); button.setBackground(ChatPalette.ripple(this,Color.rgb(238,233,250),15)); button.setOnClickListener(v -> action.run()); return button; }
    private GradientDrawable round(int color) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(18)); return drawable; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void notice(String text) { if (isFinishing() || isDestroyed()) return; new ObservedDialogBuilder(this).setMessage(text).setPositiveButton("확인", null).show(); }
    private String attachmentSummary() { return attachments.length() == 0 ? "" : "첨부 " + attachments.length() + "개 · 폰 내부에 저장됨"; }
    private String shorten(String text, int max) { return text.length() <= max ? text : text.substring(0, max) + "…"; }
    private JSONArray parseArray(String value) { try { return new JSONArray(value); } catch (Exception invalid) { return new JSONArray(); } }
    private JSONObject parseObject(String value) { try { return new JSONObject(value); } catch (Exception e) { return new JSONObject(); } }
    private String bundledInstructions() { try (InputStream input = getAssets().open("personalized-instructions.txt")) { return readUtf8(input); } catch (Exception e) { return ""; } }
    private void persistDraft() {
        if (app == null || app.getRuntime().isRestoringBackup()) return;
        String text = composer != null ? composer.getText().toString() : draftState == null ? "" : draftState.text;
        try { applyDraft(draftStore.editFrom(draftState,text,attachments,UiAutomationProvenance.currentActor(this))); }
        catch (RuntimeException failure) { draftSaveFailed = true; connection.setText("임시 입력을 저장하지 못했어. 입력을 그대로 두고 다시 확인해 줘."); }
    }
    private void refreshDraftFromStore(){
        if(draftStore==null||draftSaveFailed||app.getRuntime().isRestoringBackup())return;
        if(composer!=null&&draftState!=null&&(!draftState.text.equals(composer.getText().toString())||!draftState.attachmentsJson.equals(attachments.toString())))return;
        OwnerDraftStore.Snapshot current=draftStore.read();
        if(draftState==null||current.revision!=draftState.revision||!current.clientInputId.equals(draftState.clientInputId))applyDraft(current);
    }
    private void applyDraft(OwnerDraftStore.Snapshot snapshot) {
        draftState = snapshot; attachments = snapshot.attachments();
        draftSaveFailed = false;
        applyingDraft = true;
        try { if (composer != null&&!snapshot.text.contentEquals(composer.getText())) composer.setText(snapshot.text); }
        finally { applyingDraft = false; }
        updateComposerState();
    }
    private String readUtf8(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) { if (output.size() + count > 1_000_000) throw new IllegalArgumentException("Asset too large"); output.write(buffer, 0, count); }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
    private String toolCategory(String name){
        if(name.startsWith("accounts."))return "계정 보관함";
        if(name.startsWith("memory."))return "기억과 원문";
        if(name.startsWith("files.")||name.startsWith("phone_files")||name.startsWith("phone_media"))return "사진·미디어·파일";
        if(name.startsWith("phone_ui")||name.startsWith("phone_screen")||name.startsWith("phone_overlay")||name.startsWith("phone_clipboard")||name.equals("phone_device_admin_state"))return "화면 읽기·입력·조작";
        if(name.startsWith("phone_notification")||name.startsWith("phone_dnd")||name.startsWith("phone_alarm"))return "알림·예약·방해 금지";
        if(name.startsWith("phone_bluetooth")||name.startsWith("phone_wifi")||name.startsWith("phone_network"))return "블루투스·와이파이";
        if(name.startsWith("phone_contact")||name.startsWith("phone_calendar"))return "주소록·일정";
        if(name.startsWith("phone_call")||name.startsWith("phone_dial")||name.startsWith("phone_number")||name.startsWith("phone_sms"))return "전화·문자";
        if(name.startsWith("phone_voice")||name.startsWith("phone_camera")||name.startsWith("phone_volume")||name.startsWith("phone_flashlight")||name.equals("phone_vibrate"))return "카메라·소리·녹음";
        if(name.startsWith("phone_"))return "기기 상태·위치·활동";
        return "작업과 앱 설정";
    }

    private String toolLabel(String name) {
        return switch (name) {
            case "phone_device_info" -> "휴대폰 상태 확인"; case "phone_apps_list" -> "설치 앱 목록"; case "phone_app_launch" -> "앱 열기";
            case "phone_contacts_read" -> "주소록 읽기"; case "phone_contacts_create" -> "주소록 추가"; case "phone_calendar_read" -> "일정 읽기"; case "phone_calendar_create" -> "일정 추가";
            case "phone_notifications_read" -> "알림 읽기"; case "phone_location_last" -> "최근 위치 확인"; case "phone_volume_set" -> "음량 변경"; case "phone_flashlight_set" -> "손전등 제어";
            case "phone_clipboard_read" -> "클립보드 읽기"; case "phone_clipboard_write" -> "클립보드 쓰기"; case "phone_ui_type" -> "화면 입력칸에 글·비밀번호 입력"; case "phone_screen_capture" -> "현재 화면 캡처"; case "phone_screen_capture_read" -> "화면 캡처 결과 읽기"; case "phone_screen_capture_cancel" -> "화면 캡처 취소"; case "phone_screen_lock" -> "화면 잠금"; case "phone_call_place" -> "전화 걸기"; case "phone_voice_record_stop" -> "음성 녹음 중지"; case "phone_ui_snapshot" -> "화면 구성 확인"; case "phone_ui_click" -> "화면 버튼 누르기"; case "phone_ui_global" -> "홈·뒤로·알림 이동";
            case "phone_ui_tap" -> "화면 터치"; case "phone_ui_swipe" -> "화면 밀기"; case "phone_brightness_set" -> "화면 밝기 변경"; case "phone_dial_open" -> "전화번호 입력 화면 열기";
            case "phone_files_list" -> "휴대폰 공유 파일 목록"; case "phone_files_read" -> "휴대폰 공유 파일 읽기"; case "phone_files_write" -> "휴대폰 공유 파일 쓰기";
            case "accounts.list" -> "계정 목록 확인"; case "accounts.get" -> "최신 로그인 정보 읽기"; case "accounts.save" -> "계정 추가·정정"; case "accounts.history" -> "계정 수정 이력 읽기"; case "accounts.delete" -> "계정 보관함 복사본 삭제";
            case "app.routing.session.get" -> "이번 화면 모델 선택 읽기"; case "app.routing.session.set" -> "이번 화면 모델 선택 변경";
            case "app.identity.get" -> "현재 비서 이름·프사 확인"; case "app.identity.update" -> "비서 이름·프사 변경"; case "app.identity.avatar" -> "현재 비서 프사 직접 보기";
            case "files.list" -> "작업 파일 목록"; case "files.read" -> "작업 파일 읽기"; case "files.write" -> "작업 파일 쓰기";
            case "memory.search" -> "저장된 기억 검색"; case "memory.save" -> "기억 추가·수정"; case "app.settings.get" -> "앱 설정 확인"; case "app.settings.update" -> "대화에서 앱 설정 변경";
            case "memory.source.enumerate" -> "조건에 맞는 저장 원문 목록 확인";
            case "app.work.pause" -> "현재 작업의 새 폰·앱 실행 중지"; case "app.tools.describe" -> "도구 사용법 확인";
            case "phone_contact_get" -> "연락처 상세 정보";
            case "phone_contact_update" -> "연락처 수정";
            case "phone_contacts_delete" -> "연락처 삭제";
            case "phone_calendar_update" -> "일정 수정";
            case "phone_calendar_delete" -> "일정 삭제";
            case "phone_notifications_active" -> "현재 알림과 실행 버튼 확인";
            case "phone_notification_dismiss" -> "알림 지우기";
            case "phone_notification_open" -> "알림의 앱 열기";
            case "phone_notification_action" -> "알림 버튼 실행";
            case "phone_notification_reply" -> "알림에서 답장 보내기";
            case "phone_dnd_read" -> "방해 금지 상태 확인";
            case "phone_dnd_set" -> "방해 금지 변경";
            case "phone_vibrate" -> "진동 실행";
            case "phone_device_admin_state" -> "기기 관리자와 잠금 상태 확인";
            case "phone_numbers_read" -> "휴대폰 번호 확인";
            case "phone_bluetooth_bonded" -> "연결된 블루투스 기기";
            case "phone_bluetooth_scan" -> "주변 블루투스 검색";
            case "phone_bluetooth_scan_read" -> "블루투스 검색 결과";
            case "phone_bluetooth_scan_cancel" -> "블루투스 검색 중지";
            case "phone_wifi_scan" -> "주변 와이파이 검색";
            case "phone_wifi_scan_read" -> "와이파이 검색 결과";
            case "phone_wifi_hotspot_start" -> "로컬 핫스팟 시작";
            case "phone_wifi_hotspot_read" -> "로컬 핫스팟 상태";
            case "phone_wifi_hotspot_stop" -> "로컬 핫스팟 중지";
            case "phone_overlay_show" -> "화면 위 호영 카드 표시";
            case "phone_overlay_update" -> "화면 위 카드 수정";
            case "phone_overlay_hide" -> "화면 위 카드 닫기";
            case "phone_alarm_schedule" -> "시각을 정해 알림 예약";
            case "phone_alarm_list" -> "예약 알림 확인";
            case "phone_alarm_cancel" -> "예약 알림 취소";
            case "phone_voice_record" -> "마이크 녹음 시작";
            case "phone_voice_record_read" -> "녹음 파일·진행 상태 확인";
            case "phone_camera_open" -> "카메라 열기";
            case "phone_location_refresh" -> "새 위치 요청";
            case "phone_location_refresh_read" -> "위치 요청 결과";
            case "phone_network_state" -> "네트워크 상태";
            case "phone_call_state" -> "전화 상태";
            case "phone_url_open" -> "웹사이트 열기";
            case "phone_activity_read" -> "걸음·신체 활동 확인";
            case "phone_usage_read" -> "앱 사용량 확인";
            case "phone_usage_events" -> "앱·화면 사용 이력";
            case "phone_app_state" -> "앱·화면 상태 확인";
            case "phone_context_search" -> "휴대폰 관찰 기록 검색";
            case "phone_context_read" -> "휴대폰 관찰 원문 읽기";
            case "phone_media_list" -> "사진·영상·오디오 목록";
            case "memory.source.read" -> "기억의 전체 원문 읽기";
            case "memory.history" -> "기억의 수정 이력";
            case "memory.review" -> "기억 검토";
            case "memory.asset.analyze" -> "저장된 첨부 분석";
            case "tasks.list" -> "진행 중인 일 확인";
            case "tasks.update" -> "진행 상황 저장";
            case "tasks.report_completed" -> "확인된 완료 보고 보관";
            case "actions.receipt.read" -> "실행 결과 원문 확인";
            case "accounts.totp.get" -> "현재 인증앱 코드 확인";
            case "accounts.totp.type" -> "인증앱 코드 즉시 입력";
            case "accounts.recovery.reserve" -> "일회용 복구 코드 선택·기록";
            case "accounts.recovery.state" -> "복구 코드 사용 이력 확인";
            case "phone_files_attach" -> "작업 파일을 AI에 첨부";
            case "phone_files_transfer_read" -> "파일 전송 결과 확인";
            case "phone_sms_get" -> "문자 메시지 원문 읽기";
            case "phone_sms_read" -> "문자 메시지 읽기";
            case "phone_sms_send" -> "문자 메시지 보내기";
            case "phone_sms_send_read" -> "문자 전송 결과 확인";
            case "phone_call_log_read" -> "통화 기록 읽기";
            case "phone_call_log_delete" -> "통화 기록 삭제";
            case "phone_files_import" -> "휴대폰 파일 가져오기";
            case "phone_files_export" -> "작업 파일을 휴대폰에 저장";
            case "phone_files_copy" -> "휴대폰 파일 복사";
            case "phone_files_move" -> "휴대폰 파일 이동";
            case "phone_files_delete" -> "휴대폰 파일 삭제";
            case "phone_files_mkdir" -> "휴대폰 폴더 만들기";
            case "phone_media_import" -> "사진·미디어 가져오기";
            default -> name;
        };
    }
}
