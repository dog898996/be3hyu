package com.hoyoung.assistant.web;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.runtime.AssistantRuntime;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Provider faults/Android lifecycle with local fake runtime observations; no account or model call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WorkStartupRecoveryTest {
    private Context context;
    private HoyoungStore store;
    private InternalFiles files;
    private AssistantRuntime runtime;
    private final List<WorkWebController> controllers = new ArrayList<>();

    @Before public void before() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        store = new HoyoungStore(context); store.putSetting("memoryReviewEnabled", "false");
        files = new InternalFiles(context);
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context, store), Runnable::run);
        runtime.attachTransport(new AssistantRuntime.Transport() {
            @Override public void inspect() { }
            @Override public void send(String id, String prompt) { }
            @Override public void openTemporary() { }
            @Override public void cancel() { }
        });
    }

    @After public void after() throws Exception {
        for (WorkWebController controller : controllers) {
            ((Handler) field(WorkWebController.class, "main").get(controller)).removeCallbacksAndMessages(null);
        }
        store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
    }

    @Test public void existingGetterAndBackgroundInspectionNeverInitializeAProvider() {
        int[] calls = {0}; WorkWebController controller = controller(c -> { calls[0]++; throw new AssertionError("Unexpected provider constructor"); });
        for (int i = 0; i < 10; i++) { assertNull(controller.getExistingView()); controller.inspect(); controller.unbindActivity(null); }
        idle(); assertEquals(0, calls[0]);
    }
    @Test public void connectionDiagnosticsReadsTheCurrentDocumentOnceWithoutSelectingOrSending() throws Exception {
        List<RecordingView> views=new ArrayList<>();
        WorkWebController controller=controller(c->{RecordingView result=new RecordingView(c);views.add(result);return result;});
        controller.start();idle();markLoaded(controller);RecordingView view=views.get(0);
        List<JSONObject> results=new ArrayList<>();int before=view.scripts.size();
        controller.readConnectionDiagnostics(results::add);idle();
        assertEquals(before+1,view.scripts.size());
        assertTrue(view.scripts.get(before).contains("connectionDiagnostics()"));
        assertFalse(view.scripts.get(before).contains("ensurePreferences("));assertFalse(view.scripts.get(before).contains(".send("));
        ValueCallback<String> callback=view.callbacks.get(view.callbacks.size()-1);
        callback.onReceiveValue(JSONObject.quote(object("code","speed_unverified","state",object("model","gpt-6.1-sol")).toString()));
        advance(3_000);assertEquals(1,results.size());assertTrue(results.get(0).getBoolean("fresh"));
        assertEquals("gpt-6.1-sol",results.get(0).getJSONObject("state").getString("model"));
        results.clear();controller.readConnectionDiagnostics(results::add);idle();
        callback=view.callbacks.get(view.callbacks.size()-1);advance(2_001);
        assertEquals(1,results.size());assertEquals("diagnostic_timeout",results.get(0).getString("code"));
        callback.onReceiveValue(JSONObject.quote(object("state",object("model","gpt-6.1-sol")).toString()));
        assertEquals(1,results.size());
    }
    @Test public void workSelectionFailureKeepsTheActualStepInsteadOfDiscardingItsReceipt() throws Exception {
        List<RecordingView> views=new ArrayList<>();WorkWebController controller=controller(c->{RecordingView result=new RecordingView(c);views.add(result);return result;});
        controller.start();idle();markLoaded(controller);RecordingView view=views.get(0);
        controller.selectWork();idle();
        view.callbacks.get(view.callbacks.size()-1).onReceiveValue(JSONObject.quote(object("ok",false,"code","work_control_unverified","state",object("authenticated",true,"workSelected",false,"temporary",false)).toString()));
        assertEquals("work_control_unverified",runtime.state().optString("readinessCode"));assertFalse(runtime.state().optBoolean("ready"));
        assertTrue(store.listMessages(20).toString().contains("Work를 선택"));
    }
    @Test public void mainDocumentLoadFailureIsNotOverwrittenByPageFinishedOrRetriedAsNewWork() throws Exception {
        List<RecordingView> views=new ArrayList<>();WorkWebController controller=controller(c->{RecordingView result=new RecordingView(c);views.add(result);return result;});
        controller.start();idle();RecordingView view=views.get(0);WebViewClient client=shadowOf(view).getWebViewClient();
        client.onPageStarted(view,"https://chatgpt.com/",null);
        android.webkit.WebResourceRequest request=new android.webkit.WebResourceRequest(){
            public android.net.Uri getUrl(){return android.net.Uri.parse("https://chatgpt.com/");}public boolean isForMainFrame(){return true;}public boolean isRedirect(){return false;}public boolean hasGesture(){return false;}public String getMethod(){return "GET";}public java.util.Map<String,String> getRequestHeaders(){return java.util.Collections.emptyMap();}
        };
        client.onReceivedError(view,request,null);
        client.onPageFinished(view,"https://chatgpt.com/");
        assertEquals("page_load_failed",runtime.state().optString("readinessCode"));assertFalse(runtime.state().optBoolean("ready"));assertFalse(runtime.state().getJSONObject("web").optBoolean("loading"));
        advance(30_000);assertEquals(1,view.urlLoads);
    }

    @Test public void ownerReconnectAfterLoadFailureReloadsTheSamePageOnlyOnceDespiteRepeatedTaps() throws Exception {
        List<RecordingView> views=new ArrayList<>();WorkWebController controller=controller(c->{RecordingView v=new RecordingView(c);views.add(v);return v;});
        controller.start();idle();RecordingView view=views.get(0);WebViewClient client=view.getWebViewClient();
        client.onPageStarted(view,"https://chatgpt.com/c/retry-owned-123",null);
        field(WorkWebController.class,"pageLoadFailure").set(controller,"page_load_failed");
        for(int i=0;i<6;i++)controller.connect();idle();
        assertEquals(2,view.urlLoads);assertEquals("https://chatgpt.com/c/retry-owned-123",view.lastUrl);
        assertEquals("page_loading",runtime.state().optString("readinessCode"));
        assertEquals(1,views.size());assertTrue(view.scripts.stream().noneMatch(script->script.contains("HoyoungWorkBridge.send(")));
        field(WorkWebController.class,"pageLoadFailure").set(controller,"page_http_429");
        controller.manualRetryCheck();idle();assertEquals(3,view.urlLoads);
    }

    @Test public void hangingPageBecomesRetryableAndARecreatedDocumentDoesNotKeepTheOldRecoveryFlight() throws Exception {
        List<RecordingView> views=new ArrayList<>();WorkWebController controller=controller(c->{RecordingView v=new RecordingView(c);views.add(v);return v;});
        controller.start();idle();RecordingView view=views.get(0);advance(30_001);
        assertEquals("page_load_timeout",runtime.state().optString("readinessCode"));
        controller.connect();controller.connect();idle();assertEquals(2,view.urlLoads);
        field(WorkWebController.class,"recoveringReply").setBoolean(controller,true);
        view.getWebViewClient().onPageStarted(view,"https://chatgpt.com/",null);
        assertFalse(field(WorkWebController.class,"recoveringReply").getBoolean(controller));
        view.getWebViewClient().onPageFinished(view,"https://accounts.google.com/");
        advance(30_001);assertEquals("authentication_required",runtime.state().optString("readinessCode"));
    }

    @Test public void visibleDocumentStartsBeforeSlowResourcesFinishAndLateFinishDoesNotResetReadiness() throws Exception {
        List<RecordingView> views=new ArrayList<>();WorkWebController controller=controller(c->{RecordingView v=new RecordingView(c);views.add(v);return v;});
        controller.start();idle();RecordingView view=views.get(0);WebViewClient client=view.getWebViewClient();
        client.onPageStarted(view,WorkWebController.HOME_URL,null);
        client.onPageCommitVisible(view,WorkWebController.HOME_URL);idle();
        assertTrue(field(WorkWebController.class,"documentEverLoaded").getBoolean(controller));
        assertTrue(field(WorkWebController.class,"loaded").getBoolean(controller));
        assertFalse(field(WorkWebController.class,"pageAwaitingFinish").getBoolean(controller));
        ready();advance(30_001);
        assertEquals("ready",runtime.state().optString("readinessCode"));
        client.onPageFinished(view,WorkWebController.HOME_URL);idle();
        assertEquals("ready",runtime.state().optString("readinessCode"));assertEquals(1,view.urlLoads);
    }

    @Test public void staleOrFailedVisibleCommitCannotStartAutomationOnAnotherDocument() throws Exception {
        List<RecordingView> views=new ArrayList<>();WorkWebController controller=controller(c->{RecordingView v=new RecordingView(c);views.add(v);return v;});
        controller.start();idle();RecordingView view=views.get(0);WebViewClient client=view.getWebViewClient();
        client.onPageStarted(view,"https://auth.openai.com/",null);
        client.onPageCommitVisible(view,WorkWebController.HOME_URL);idle();
        assertFalse(field(WorkWebController.class,"loaded").getBoolean(controller));assertTrue(view.scripts.isEmpty());
        client.onPageStarted(view,WorkWebController.HOME_URL,null);
        field(WorkWebController.class,"pageLoadFailure").set(controller,"page_load_failed");
        client.onPageCommitVisible(view,WorkWebController.HOME_URL);idle();
        assertFalse(field(WorkWebController.class,"loaded").getBoolean(controller));assertTrue(view.scripts.isEmpty());
    }

    @Test public void repeatedStartsUseOneMonotonicOneFiveThirtySixtySecondBackoff() throws Exception {
        runtime.receive("대기 원문은 startup 실패해도 보존", new JSONArray());
        int[] calls = {0}; WorkWebController controller = controller(c -> { calls[0]++; throw new IllegalStateException("Injected missing provider"); });
        controller.start(); idle(); assertEquals(1, calls[0]); assertNull(controller.getExistingView());
        assertEquals("startup_failed", runtime.state().getString("readinessCode"));
        assertFalse(runtime.state().getBoolean("ready"));
        JSONObject web = runtime.state().getJSONObject("web"); assertTrue(web.isNull("authenticated")); assertTrue(web.getBoolean("busy"));
        assertEquals(1_000L, web.getLong("startupRetryAfterMs"));
        for (long delay : new long[]{1_000, 5_000, 30_000, 60_000, 60_000}) {
            int before = calls[0]; for (int i = 0; i < 10; i++) controller.start(); idle(); assertEquals(before, calls[0]);
            advance(delay - 1); assertEquals(before, calls[0]); advance(1); assertEquals(before + 1, calls[0]);
        }
        assertEquals(60_000L, runtime.state().getJSONObject("web").getLong("startupRetryAfterMs"));
        assertTrue(store.listMessages(10).toString().contains("대기 원문은 startup 실패해도 보존"));
    }

    @Test public void linkageAndPartialSettingsFailureAreContainedAndThePartialViewIsDestroyed() {
        WorkWebController linkage = controller(c -> { throw new UnsatisfiedLinkError("Injected ABI failure"); });
        linkage.start(); idle(); assertNull(linkage.getExistingView());
        assertEquals("UnsatisfiedLinkError", runtime.state().optJSONObject("web").optString("startupErrorType"));
        List<RecordingView> views = new ArrayList<>();
        WorkWebController setup = controller(c -> { RecordingView result = new RecordingView(c); result.settingsFailure = true; result.destroyFailure = true; views.add(result); return result; });
        setup.start(); idle(); assertNull(setup.getExistingView()); assertEquals(1, views.size());
        assertEquals(1, views.get(0).destroyCalls); assertEquals(0, views.get(0).urlLoads);
    }

    @Test public void aSuccessfulRetryStopsTheRetryTimerWithoutClaimingWorkIsConnected() {
        int[] calls = {0}; List<RecordingView> views = new ArrayList<>();
        WorkWebController controller = controller(c -> { calls[0]++; if (calls[0] <= 2) throw new IllegalStateException("Transient provider startup"); RecordingView result = new RecordingView(c); views.add(result); return result; });
        controller.start(); idle(); advance(1_000); advance(5_000);
        assertEquals(3, calls[0]); assertSame(views.get(0), controller.getExistingView()); assertEquals(1, views.get(0).urlLoads);
        assertFalse(runtime.state().optBoolean("ready")); assertEquals("page_loading", runtime.state().optString("readinessCode"));
        advance(120_000); controller.start(); idle(); assertEquals(3, calls[0]); assertEquals(1, views.get(0).urlLoads);
    }

    @Test public void finalPostedRecoveryGuardRejectsAnOperationThatBecameActiveAfterTheOwnerClicked() throws Exception {
        int[] calls = {0}; WorkWebController controller = controller(c -> { calls[0]++; return new RecordingView(c); });
        controller.openTemporary(); ready(); JSONObject original = runtime.receive("도중에 시작된 작업", new JSONArray());
        assertEquals("dispatching", runtime.state().optString("activePhase")); idle(); assertEquals(0, calls[0]); assertNull(controller.getExistingView());
        assertEquals(original.getString("external_id"), runtime.state().getString("activeRequestId"));
        JSONObject idleState = object("receiptIndexReady", true, "activePhase", "idle", "activeRequestId", JSONObject.NULL);
        assertTrue(WorkWebController.canAutomaticallyOpen(idleState));
        for (JSONObject blocked : new JSONObject[]{object("receiptIndexReady", false, "activePhase", "idle", "activeRequestId", JSONObject.NULL),
                object("receiptIndexReady", true, "activePhase", "saving_result", "activeRequestId", JSONObject.NULL),
                object("receiptIndexReady", true, "activePhase", "idle", "activeRequestId", JSONObject.NULL, "uncertaintyBlocked", true),
                object("receiptIndexReady", true, "activePhase", "idle", "activeRequestId", JSONObject.NULL, "restoringBackup", true),
                object("receiptIndexReady", true, "activePhase", "idle", "activeRequestId", JSONObject.NULL, "storageRecoveryBlocked", true)})
            assertFalse(WorkWebController.canAutomaticallyOpen(blocked));
    }

    @Test public void rendererLossAllowsReadOnlyRecoveryWithoutResubmittingTheAcceptedRequest() throws Exception {
        List<RecordingView> views = new ArrayList<>();
        WorkWebController controller = controller(c -> { RecordingView result = new RecordingView(c); views.add(result); return result; });
        controller.start(); idle(); RecordingView originalView = views.get(0); WebViewClient client = originalView.getWebViewClient();
        ready(); JSONObject original = runtime.receive("이미 접수된 원문", new JSONArray());
        runtime.onWebEvent(object("type", "accepted", "requestId", original.getString("external_id")));
        originalView.destroyFailure = true;
        field(WorkWebController.class, "fileCallback").set(controller, (ValueCallback<android.net.Uri[]>) values -> { throw new IllegalStateException("Injected chooser callback failure"); });
        assertTrue(client.onRenderProcessGone(originalView, detail()));
        assertNull(controller.getExistingView()); assertTrue(runtime.state().optBoolean("uncertaintyBlocked"));
        assertEquals("uncertain", store.getRequest(original.getLong("id")).getString("status"));
        assertEquals("이미 접수된 원문", store.getRequest(original.getLong("id")).getString("text"));
        controller.start(); idle(); advance(120_000); assertEquals(2, views.size());
        assertEquals("uncertain",store.getRequest(original.getLong("id")).optString("status"));
        assertTrue(views.get(1).scripts.stream().noneMatch(script->script.contains("HoyoungWorkBridge.send(")));
        runtime.abandonUncertain(original.getLong("id")); controller.start(); idle(); assertEquals(2, views.size());
        controller.openTemporary(); idle(); assertEquals(2, views.size()); assertEquals(2, views.get(1).urlLoads);
        assertTrue(client.onRenderProcessGone(originalView, detail())); assertSame(views.get(1), controller.getExistingView());
    }

    @Test public void reconnectReadsAnUncertainReplyAndImportsItWithoutSendingOrChangingModels() throws Exception {
        List<RecordingView> views=new ArrayList<>();
        WorkWebController controller=controller(c->{RecordingView v=new RecordingView(c);views.add(v);return v;});
        ready();JSONObject request=runtime.receive("이미 보낸 메시지",new JSONArray());String id=request.getString("external_id");
        ((WorkConversationJournal)field(WorkWebController.class,"conversations").get(controller)).begin(id);
        runtime.onWebEvent(object("type","error","code","navigation_uncertain","requestId",id,"mayHaveBeenAccepted",true));
        assertTrue(WorkWebController.canReadRecovery(runtime.state()));
        assertFalse(WorkWebController.canAutomaticallySelectPreferences(runtime.state()));
        JSONObject restoring=new JSONObject(runtime.state().toString()).put("restoringBackup",true);
        assertFalse(WorkWebController.canReadRecovery(restoring));
        controller.connect();idle();assertEquals(1,views.size());markLoaded(controller);
        RecordingView view=views.get(0);controller.connect();idle();
        assertTrue(view.scripts.get(view.scripts.size()-1).contains("recoverResponse("));
        view.callbacks.get(view.callbacks.size()-1).onReceiveValue(JSONObject.quote(object("ok",false,"code","reply_recovery_settling","state",readyState()).toString()));
        advance(2_101);
        String envelope=object("protocol","hoyoung.v1","requestId",id,"message","회수된 답변","status","completed").toString();
        JSONObject proof=object("ok",true,"code","reply_recovered","captureComplete",true,"requestId",id,"conversationId","owned-recovery-123","envelope",envelope,"state",readyState());
        view.callbacks.get(view.callbacks.size()-1).onReceiveValue(JSONObject.quote(proof.toString()));
        assertEquals("completed",store.getRequest(request.getLong("id")).getString("status"));
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
        assertTrue(store.listMessages(20).toString().contains("회수된 답변"));
        assertTrue(view.scripts.stream().anyMatch(script->script.contains("adoptRecoveredResponse(")));
        assertTrue(view.scripts.stream().noneMatch(script->script.contains("HoyoungWorkBridge.send(")||script.contains("ensurePreferences(")));
    }

    @Test public void anInitialNeverLoadedRendererLossRetriesWithoutDiscardingQueuedOwnerInput() throws Exception {
        JSONObject original = runtime.receive("부팅 중에도 잃으면 안 되는 입력", new JSONArray());
        List<RecordingView> views = new ArrayList<>();
        WorkWebController controller = controller(c -> { RecordingView result = new RecordingView(c); views.add(result); return result; });
        controller.start(); idle(); RecordingView initial = views.get(0);
        assertTrue(initial.getWebViewClient().onRenderProcessGone(initial, detail()));
        assertNull(controller.getExistingView()); assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
        assertEquals("renderer_lost_startup_retry", runtime.state().optString("readinessCode"));
        assertEquals("queued", store.getRequest(original.getLong("id")).getString("status"));
        advance(999); assertEquals(1, views.size()); advance(1);
        assertEquals(2, views.size()); assertEquals(1, views.get(1).urlLoads);
        assertEquals(original.getString("text"), store.getRequest(original.getLong("id")).getString("text"));
        assertFalse(runtime.state().optBoolean("ready"));
        assertTrue(views.get(1).getWebViewClient().onRenderProcessGone(views.get(1), detail()));
        assertEquals(5_000L, runtime.state().getJSONObject("web").getLong("startupRetryAfterMs"));
        advance(4_999); assertEquals(2, views.size()); advance(1); assertEquals(3, views.size());
        assertTrue(views.get(2).getWebViewClient().onRenderProcessGone(views.get(2), detail()));
        assertEquals(30_000L, runtime.state().getJSONObject("web").getLong("startupRetryAfterMs"));
    }

    @Test public void navigationDoesNotReclassifyAPreviouslyLoadedDocumentAsStartupOnly() throws Exception {
        List<RecordingView> views = new ArrayList<>();
        WorkWebController controller = controller(c -> { RecordingView result = new RecordingView(c); views.add(result); return result; });
        controller.start(); idle(); RecordingView current = views.get(0); WebViewClient client = current.getWebViewClient();
        client.onPageFinished(current, "https://auth.openai.com/");
        client.onPageStarted(current, WorkWebController.TEMPORARY_URL, null);
        assertTrue(client.onRenderProcessGone(current, detail()));
        advance(120_000); controller.start(); idle(); assertEquals(1, views.size());
        assertTrue(runtime.state().getJSONObject("web").getBoolean("recoveryReviewRequired"));
        controller.openTemporary(); idle(); assertEquals(2, views.size()); assertEquals(1, views.get(1).urlLoads);
    }

    @Test public void postedSelectionCancelAndRetryProviderFaultsNeverEscapeTheMainLoop() throws Exception {
        for (int operation = 0; operation < 3; operation++) {
            for (boolean linkage : new boolean[]{false, true}) {
                List<RecordingView> views = new ArrayList<>();
                WorkWebController controller = controller(c -> { RecordingView result = new RecordingView(c); views.add(result); return result; });
                controller.start(); idle(); RecordingView current = views.get(0);
                markLoaded(controller);
                if (linkage) current.javascriptLinkageFailure = new UnsatisfiedLinkError("Injected provider operation failure");
                else current.javascriptFailure = new IllegalStateException("Injected provider operation failure");
                if (operation == 0) controller.selectWork(); else if (operation == 1) controller.cancel(); else controller.manualRetryCheck();
                idle(); assertNull(controller.getExistingView());
                assertEquals(linkage ? "UnsatisfiedLinkError" : "IllegalStateException", runtime.state().getJSONObject("web").getString("startupErrorType"));
                assertTrue(runtime.state().getJSONObject("web").getBoolean("recoveryReviewRequired"));
                advance(120_000); assertEquals(1, views.size());
            }
        }
    }

    @Test public void aCancelProviderFaultPreservesAcceptedWorkAsUnknownWithoutClaimingItStopped() throws Exception {
        List<RecordingView> views = new ArrayList<>();
        WorkWebController controller = controller(c -> { RecordingView result = new RecordingView(c); views.add(result); return result; });
        controller.start(); idle(); markLoaded(controller); ready();
        JSONObject original = runtime.receive("취소 여부를 추측하면 안 되는 작업", new JSONArray());
        runtime.onWebEvent(object("type", "accepted", "requestId", original.getString("external_id")));
        views.get(0).javascriptFailure = new IllegalStateException("Injected cancel provider failure");
        controller.cancel(); idle();
        assertEquals("uncertain", store.getRequest(original.getLong("id")).getString("status"));
        assertTrue(runtime.state().getBoolean("uncertaintyBlocked"));
        advance(120_000); assertEquals(1, views.size());
        assertEquals(original.getString("text"), store.getRequest(original.getLong("id")).getString("text"));
    }

    @Test public void authorizationEvaluationFailureIsNotEvidenceOfAnUnsentRequest() throws Exception {
        List<RecordingView> views = new ArrayList<>();
        WorkWebController controller = controller(c -> { RecordingView result = new RecordingView(c); views.add(result); return result; });
        controller.start(); idle(); markLoaded(controller); ready();
        field(WorkWebController.class, "currentOrigin").set(controller, WorkWebController.TEMPORARY_URL);
        JSONObject original = runtime.receive("JS로 전달된 권한의 결과는 확인 필요", new JSONArray());
        String payloadDigest = runtime.registerPreparedPayload(original.getString("external_id"),
                object("requestId", original.getString("external_id"), "text", "native prepared test payload"));
        views.get(0).javascriptLinkageFailure = new UnsatisfiedLinkError("Injected authorization provider failure");
        java.lang.reflect.Method authorize = WorkWebController.class.getDeclaredMethod("authorizePreparedDispatch", WebView.class, JSONObject.class);
        authorize.setAccessible(true);
        authorize.invoke(controller, views.get(0), object("requestId", original.getString("external_id"), "nonce", "0123456789abcdef0123456789abcdef", "payloadDigest", payloadDigest));
        idle();
        // Native source validation now runs on the attachment worker; await that
        // actual boundary before draining the posted WebView authorization.
        ((java.util.concurrent.ExecutorService) field(WorkWebController.class, "attachmentPreparation").get(controller))
                .submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        idle(); assertEquals("uncertain", store.getRequest(original.getLong("id")).getString("status"));
        assertTrue(runtime.state().getBoolean("uncertaintyBlocked")); assertNull(controller.getExistingView());
        advance(120_000); assertEquals(1, views.size());
    }

    @Test public void anOldInspectionReceiptCannotMakeAReplacedDocumentReady() throws Exception {
        List<RecordingView> views = new ArrayList<>();
        WorkWebController controller = controller(c -> { RecordingView result = new RecordingView(c); views.add(result); return result; });
        controller.start(); idle(); RecordingView current = views.get(0);
        field(WorkWebController.class, "loaded").setBoolean(controller, true);
        controller.inspect(); idle(); assertEquals(1, current.callbacks.size()); ValueCallback<String> oldReceipt = current.callbacks.get(0);
        controller.openTemporary(); idle(); assertEquals(2, current.urlLoads);
        JSONObject receipt = object("code", "ready", "state", readyState());
        oldReceipt.onReceiveValue(JSONObject.quote(receipt.toString()));
        assertFalse(runtime.state().optBoolean("ready")); assertEquals("page_loading", runtime.state().optString("readinessCode"));
    }

    @Test public void connectionCanDiscoverAccountWhileIdleButLeavesDispatchedAndAcceptedWorkReadOnly() throws Exception {
        List<RecordingView> views = new ArrayList<>();
        WorkWebController controller = controller(c -> { RecordingView result = new RecordingView(c); views.add(result); return result; });
        controller.start(); idle(); markLoaded(controller);
        controller.connect(); idle();
        assertTrue(views.get(0).scripts.get(0).contains("HoyoungWorkBridge.connect("));
        ready(); JSONObject request=runtime.receive("작업 중 연결 버튼은 모델을 바꾸면 안 돼",new JSONArray());
        controller.connect(); idle();
        assertTrue(views.get(0).scripts.get(1).contains("HoyoungWorkBridge.configurePreferences("));
        runtime.onWebEvent(object("type","accepted","requestId",request.getString("external_id")));
        controller.connect(); idle();
        assertTrue(views.get(0).scripts.get(2).contains("HoyoungWorkBridge.configurePreferences("));
        assertEquals(request.getString("external_id"),runtime.state().getString("activeRequestId"));
    }

    @Test public void onlyAnIdleOrPreWirePreparingLedgerMayEnsureUiPreferencesAndDispatchedInspectionIsReadOnly() throws Exception {
        List<RecordingView> views = new ArrayList<>();
        WorkWebController controller = controller(c -> { RecordingView result = new RecordingView(c); views.add(result); return result; });
        controller.start(); idle(); markLoaded(controller);
        controller.inspect(); idle();
        assertTrue(views.get(0).scripts.get(0).contains("HoyoungWorkBridge.ensurePreferences("));
        ready(); JSONObject request = runtime.receive("주인 Fast 응답 중에는 설정 전환 금지", new JSONArray());
        assertEquals(request.getString("external_id"), runtime.state().getString("activeRequestId"));
        controller.inspect(); idle();
        assertTrue(views.get(0).scripts.get(1).contains("HoyoungWorkBridge.configurePreferences("));
        runtime.onWebEvent(object("type", "accepted", "requestId", request.getString("external_id")));
        controller.inspect(); idle();
        assertTrue(views.get(0).scripts.get(2).contains("HoyoungWorkBridge.configurePreferences("));
        assertTrue(WorkWebController.canAutomaticallySelectPreferences(object("receiptIndexReady", true,
                "activePhase", "preparing", "activeRequestId", "frozen-pre-wire-request")));
        assertFalse(WorkWebController.canAutomaticallySelectPreferences(object("receiptIndexReady", true,
                "activePhase", "preparing", "activeRequestId", JSONObject.NULL)));
        for (String phase : new String[]{"dispatching", "accepted", "uncertain", "saving_result"})
            assertFalse(WorkWebController.canAutomaticallySelectPreferences(object("receiptIndexReady", true,
                    "activePhase", phase, "activeRequestId", "request")));
        assertFalse(WorkWebController.canAutomaticallySelectPreferences(object("receiptIndexReady", true,
                "activePhase", "preparing", "activeRequestId", "request", "uncertaintyBlocked", true)));
    }

    @Test public void requestSpecificBridgePreferencesRetainTheDispatchSnapshotAfterNextSettingsChange() throws Exception {
        WorkWebController controller = controller(c -> new RecordingView(c));
        ready(); JSONObject request = runtime.receive("이 요청은 원래 Medium/Fast로 실행", new JSONArray());
        java.lang.reflect.Method preferences = WorkWebController.class.getDeclaredMethod("bridgePreferences", String.class);
        preferences.setAccessible(true);
        JSONObject before = (JSONObject) preferences.invoke(controller, request.getString("external_id"));
        store.putSetting("requiredModel", "gpt-6.2-sol"); store.putSetting("requiredEffort", "high"); store.putSetting("requiredSpeed", "standard");
        JSONObject after = (JSONObject) preferences.invoke(controller, request.getString("external_id"));
        assertEquals(before.toString(), after.toString());
        assertEquals("gpt-6.1-sol", after.getString("requiredModel"));
        assertEquals("medium", after.getString("requiredEffort")); assertEquals("fast", after.getString("requiredSpeed"));
    }

    @Test public void aUsageReceiptNeedsNativeScopeNonceFreshTimeAndHashesItsExactVisibleEvidence() throws Exception {
        long at = System.currentTimeMillis(); String nonce = "a".repeat(32);
        JSONObject receipt = usageReceipt("owner-account", nonce, at);
        JSONObject verified = WorkWebController.verifiedUsageObservation(receipt, "owner-account", nonce, at, at + 5);
        assertEquals("provider_ui_observed", verified.getString("origin")); assertFalse(verified.has("captureNonce"));
        assertEquals("owner_label", verified.getString("accountScopeSource")); assertFalse(verified.getBoolean("accountIdentityVerified"));
        assertEquals(17.5, verified.getDouble("usedPercent"), 0); assertEquals(.1, verified.getDouble("precisionPercent"), 0);
        assertTrue(verified.getString("evidenceRef").startsWith("work-dom-sha256:")); assertEquals(64, verified.getString("evidenceSha256").length());
        assertThrows(IllegalArgumentException.class, () -> WorkWebController.verifiedUsageObservation(receipt, "other-account", nonce, at, at + 5));
        assertThrows(IllegalArgumentException.class, () -> WorkWebController.verifiedUsageObservation(receipt, "owner-account", "b".repeat(32), at, at + 5));
        assertThrows(IllegalArgumentException.class, () -> WorkWebController.verifiedUsageObservation(receipt, "owner-account", nonce, at + 1, at + 5));
        assertThrows(IllegalArgumentException.class, () -> WorkWebController.verifiedUsageObservation(receipt, "owner-account", nonce, at, at + 15_001));
        JSONObject malformed = usageReceipt("owner-account", nonce, at); malformed.getJSONObject("observation").put("usedPercent", "17.5");
        assertThrows(IllegalArgumentException.class, () -> WorkWebController.verifiedUsageObservation(malformed, "owner-account", nonce, at, at + 5));
    }

    @Test public void anOldDocumentUsageCallbackCannotCreateAnObservedProviderReading() throws Exception {
        List<RecordingView> views = new ArrayList<>();
        WorkWebController controller = controller(c -> { RecordingView result = new RecordingView(c); views.add(result); return result; });
        controller.start(); idle(); markLoaded(controller);
        field(WorkWebController.class, "currentOrigin").set(controller, WorkWebController.TEMPORARY_URL);
        controller.captureUsageObservation("owner-account"); idle();
        RecordingView current = views.get(0); String script = current.scripts.get(0);
        int start = script.indexOf("captureUsageObservation(") + "captureUsageObservation(".length();
        JSONObject arguments = new JSONObject(script.substring(start, script.indexOf(")) : null", start)));
        ValueCallback<String> callback = current.callbacks.get(0);
        controller.openTemporary(); idle();
        callback.onReceiveValue(JSONObject.quote(usageReceipt("owner-account", arguments.getString("captureNonce"), System.currentTimeMillis()).toString()));
        assertTrue(runtime.state().getJSONObject("usageBudget").isNull("latestProviderObservation"));
    }

    private static JSONObject usageReceipt(String scope, String nonce, long observedAt) throws Exception {
        long resetAt = observedAt + 3L * 24 * 60 * 60 * 1000;
        return object("ok", true, "code", "usage_observed", "observation", object("schemaVersion", 1,
                "accountScope", scope, "captureNonce", nonce, "sourceOrigin", "https://chatgpt.com", "sourceKind", "visible-usage-panel", "period", "weekly",
                "poolId", "work-codex:weekly:" + resetAt, "observedAt", observedAt, "resetAt", resetAt, "usedPercent", 17.5, "precisionPercent", .1,
                "evidenceText", "Usage\nPro plan\nWork / Codex\nWeekly usage\n82.5% remaining\nReset: visible ISO date"));
    }

    private WorkWebController controller(WorkWebController.ViewFactory factory) { WorkWebController result = new WorkWebController(context, runtime, files, factory); controllers.add(result); return result; }
    private void ready() throws Exception { runtime.onWebEvent(object("type", "state", "code", "ready", "state", readyState())); }
    private static JSONObject readyState() throws Exception { return object("authenticated", true, "workSelected", true, "temporary", true, "busy", false, "quota", false, "model", "gpt-6.1-sol", "effort", "medium", "speed", "fast"); }
    private static JSONObject object(Object... pairs) throws Exception { JSONObject result = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static void advance(long milliseconds) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds)); }
    private static Field field(Class<?> type, String name) throws Exception { Field result = type.getDeclaredField(name); result.setAccessible(true); return result; }
    private static void markLoaded(WorkWebController controller) throws Exception {
        field(WorkWebController.class, "loaded").setBoolean(controller, true);
        field(WorkWebController.class, "documentEverLoaded").setBoolean(controller, true);
    }
    private static RenderProcessGoneDetail detail() { return new RenderProcessGoneDetail() { @Override public boolean didCrash() { return true; } @Override public int rendererPriorityAtExit() { return 0; } }; }

    private static final class RecordingView extends WebView {
        boolean settingsFailure, destroyFailure;
        RuntimeException javascriptFailure;
        LinkageError javascriptLinkageFailure;
        int destroyCalls, urlLoads;
        String lastUrl="";
        final List<ValueCallback<String>> callbacks = new ArrayList<>();
        final List<String> scripts = new ArrayList<>();
        RecordingView(Context context) { super(context); }
        @Override public WebSettings getSettings() { if (settingsFailure) throw new IllegalStateException("Injected settings failure"); return super.getSettings(); }
        @Override public void loadUrl(String url) { urlLoads++; lastUrl=url; /* Robolectric fixture does not navigate or call an account. */ }
        @Override public void destroy() { destroyCalls++; if (destroyFailure) throw new IllegalStateException("Injected teardown failure"); super.destroy(); }
        @Override public void evaluateJavascript(String script, ValueCallback<String> callback) {
            scripts.add(script);
            if (javascriptFailure != null) throw javascriptFailure;
            if (javascriptLinkageFailure != null) throw javascriptLinkageFailure;
            callbacks.add(callback);
        }
    }
}
