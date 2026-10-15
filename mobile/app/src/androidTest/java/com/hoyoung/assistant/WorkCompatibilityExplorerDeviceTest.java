package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual Android WebView, real timers and origin-bound native messages on an offline AVD.
 * The page is synthetic and every resource is intercepted; this does not claim live Work compatibility. */
@RunWith(AndroidJUnit4.class)
public final class WorkCompatibilityExplorerDeviceTest {
    private static final String PREFS = "{requiredModel:'gpt-6.1-sol',requiredEffort:'medium',requiredSpeed:'fast'}";
    private final List<JSONObject> events = new CopyOnWriteArrayList<>();
    private final List<String> console = new CopyOnWriteArrayList<>();
    private final AtomicInteger mainRequests = new AtomicInteger();
    private Context context;
    private ActivityScenario<MainActivity> scenario;
    private WebView web;

    @Before public void requireRealIsolatedEmulator() {
        assertTrue("Only disposable real Android emulators may run this explorer", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        assertEquals(35, Build.VERSION.SDK_INT);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNull("The emulator must be offline before launching the app", context.getSystemService(ConnectivityManager.class).getActiveNetwork());
        assertTrue("The actual WebView must implement the production native message boundary", WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER));
    }

    @After public void closeFixture() {
        if (scenario != null) {
            scenario.onActivity(activity -> {
                if (web != null) {
                    if (web.getParent() instanceof ViewGroup parent) parent.removeView(web);
                    web.destroy(); web = null;
                }
            });
            scenario.close(); scenario = null;
        }
    }

    @Test public void rapidRequestsFirstRouteAndCollapsedCodeMirrorReplyAreCollectedExactlyOnce() throws Exception {
        open("");
        JSONObject first = send("matrix-first", "첫 작업 원문\n두 번째 줄 🐈");
        assertEquals("dispatch_pending", first.getString("code"));
        eval("for(let i=0;i<25;i++){HoyoungWorkBridge.send({requestId:'matrix-first',text:'첫 작업 원문\\n두 번째 줄 🐈'});HoyoungWorkBridge.send({requestId:'matrix-second',text:'대기한 두 번째 작업'});}true");
        awaitEvent("accepted", "matrix-first", 30_000);
        assertEquals(1, intValue("clicks"));
        assertEquals(1, count("accepted", "matrix-first"));
        assertEquals("owned-matrix-123", event("accepted", "matrix-first").getString("conversationId"));
        assertTrue(event("accepted", "matrix-first").getBoolean("freshConversation"));
        assertEquals("queued", object("HoyoungWorkBridge.inspect()").getJSONObject("state").getString("pendingInputPolicy"));
        assertEquals("busy", send("matrix-second", "대기한 두 번째 작업").getString("code"));
        assertEquals(1, intValue("clicks"));

        String protocol = new JSONObject().put("protocol", "hoyoung.v1").put("requestId", "matrix-first")
                .put("message", "완료한 원문 🐈").put("actions", new JSONArray()).toString(2);
        String captured;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("work-explorer/captured-codemirror-reply.html")) {
            captured = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        eval("document.querySelector('#work').remove();document.querySelector('#turns').setAttribute('aria-hidden','true');answerPlain('');"
                + "const holder=document.createElement('div');holder.innerHTML=" + JSONObject.quote(captured) + ";"
                + "const body=holder.querySelector('[data-markdown-text-style=\"assistant-message\"]');body.querySelector('h1').remove();body.querySelector('ul').remove();"
                + "const code=body.querySelector('.cm-content');code.dataset.language='hoyoung';code.textContent='';"
                + "for(const text of " + JSONObject.quote(protocol) + ".split('\\n')){const line=document.createElement('div');line.className='cm-line';line.textContent=text;code.append(line);}"
                + "body.querySelector('[data-markdown-copy=\"exclude\"] .truncate').textContent='hoyoung';window.lastGroup.querySelector('.markdown').replaceWith(body);true");
        awaitEvent("completed", "matrix-first", 30_000);
        JSONObject completed = event("completed", "matrix-first");
        assertEquals(protocol, completed.getString("structuredEnvelope"));
        assertTrue(completed.getBoolean("captureComplete"));
        assertEquals(1, count("completed", "matrix-first"));
        assertTrue(object("HoyoungWorkBridge.inspect()").getJSONObject("state").getBoolean("conversationOwned"));
        assertEquals("duplicate_request", send("matrix-first", "첫 작업 원문\n두 번째 줄 🐈").getString("code"));
        assertEquals(1, intValue("clicks"));

        assertEquals("dispatch_pending", send("matrix-second", "대기한 두 번째 작업").getString("code"));
        awaitEvent("accepted", "matrix-second", 30_000);
        eval("answerPlain('두 번째 작업도 완료');true");
        awaitEvent("completed", "matrix-second", 30_000);
        assertEquals(2, intValue("clicks"));
        assertEquals(1, count("completed", "matrix-second"));
        proof("first-route; 50-rapid-requests; 2-total-submit-clicks; collapsed-controls; modal-aria-hidden; captured-CodeMirror-markup-exact-envelope; 2-unique-completions");
    }

    @Test public void failedAccountDiscoveryCanBeExplicitlyRetriedWithoutRepeatedOpening() throws Exception {
        open(SIDEBAR + "window.omitAccount=true;");
        assertEquals("authentication_unverified", object("HoyoungWorkBridge.inspect()").getString("code"));
        eval("for(let i=0;i<30;i++)HoyoungWorkBridge.connect(" + PREFS + ");true");
        awaitJs("HoyoungWorkBridge.inspect().state.connectionStatus==='account_unverified'", 30_000);
        assertEquals(1, intValue("sidebarOpens"));
        assertEquals(0, intValue("clicks"));
        eval("document.querySelector('#sidebar-close').click();window.omitAccount=false;HoyoungWorkBridge.connect(" + PREFS + ");true");
        awaitJs("HoyoungWorkBridge.inspect().code==='ready'", 30_000);
        assertEquals(2, intValue("sidebarOpens"));
        assertEquals(2, intValue("sidebarCloses"));
        eval("for(let i=0;i<30;i++)HoyoungWorkBridge.connect(" + PREFS + ");true");
        assertEquals(2, intValue("sidebarOpens"));
        assertEquals(0, intValue("clicks"));
        proof("30-repeated-connects; one-failed-sidebar-opening; explicit-retry-recovers; subsequent-30-connects-no-extra-opening; no-submit");
    }

    @Test public void ownerOpenedPickerClosesBeforeSendAndRepeatedConnectNeverTogglesFast() throws Exception {
        open(MODEL_MENU);
        eval("HoyoungWorkBridge.connect(" + PREFS + ");true");
        awaitJs("HoyoungWorkBridge.inspect().code==='ready' && !document.querySelector('#picker-menu')", 30_000);
        assertEquals(1, intValue("menuOpens"));
        assertEquals(0, intValue("fastChanges"));
        eval("document.querySelector('#model').click();true");
        assertTrue(bool("!!document.querySelector('#picker-menu')"));
        eval("for(let i=0;i<30;i++)HoyoungWorkBridge.connect(" + PREFS + ");true");
        awaitJs("HoyoungWorkBridge.inspect().code==='ready' && !document.querySelector('#picker-menu')", 30_000);
        assertEquals(2, intValue("menuOpens"));
        assertEquals(0, intValue("fastChanges"));
        assertEquals("dispatch_pending", send("picker-request", "메뉴 닫은 뒤 보낼 작업").getString("code"));
        awaitEvent("accepted", "picker-request", 30_000);
        assertEquals(1, intValue("clicks"));
        eval("answerPlain('메뉴 이후 완료');true");
        awaitEvent("completed", "picker-request", 30_000);
        proof("owner-opened-modal; 30-reconnects; focus-trap-released; Fast-unchanged; one-send-one-completion");
    }

    @Test public void navigationAwayAndBackRecoversRenderedResultWithoutReplayingRequest() throws Exception {
        open("");
        assertEquals("dispatch_pending", send("back-request", "뒤로 갔다 돌아올 작업").getString("code"));
        awaitEvent("accepted", "back-request", 30_000);
        eval("history.pushState({},'', '/c/another-thread-123');true");
        awaitJs("HoyoungWorkBridge.inspect().code==='conversation_changed'", 30_000);
        eval("for(let i=0;i<20;i++){HoyoungWorkBridge.connect(" + PREFS + ");HoyoungWorkBridge.send({requestId:'back-request',text:'뒤로 갔다 돌아올 작업'});}true");
        assertEquals(1, intValue("clicks"));
        assertEquals(1, count("error", "back-request"));
        eval("history.back();true");
        awaitJs("location.pathname==='/c/owned-matrix-123'", 30_000);
        String protocol = new JSONObject().put("protocol", "hoyoung.v1").put("requestId", "back-request")
                .put("message", "복구할 응답").put("actions", new JSONArray()).toString();
        eval("answerCode(" + JSONObject.quote(protocol) + ");true");
        awaitJs("!HoyoungWorkBridge.inspect().state.busy", 30_000);
        assertEquals("reply_recovery_settling", object("HoyoungWorkBridge.recoverResponse(['back-request'])").getString("code"));
        awaitJs("HoyoungWorkBridge.recoverResponse(['back-request']).ok", 30_000);
        JSONObject recovered = object("HoyoungWorkBridge.recoverResponse(['back-request'])");
        assertEquals(protocol, recovered.getString("envelope"));
        assertTrue(bool("HoyoungWorkBridge.adoptRecoveredResponse('back-request','owned-matrix-123')"));
        assertEquals("ready", object("HoyoungWorkBridge.inspect()").getString("code"));
        assertEquals(1, intValue("clicks"));
        assertEquals(0, count("completed", "back-request"));
        proof("real-WebView-history-back; 20-reconnects-plus-duplicate-sends; one-original-submit; exact-read-only-recovery; no-replay");
    }

    @Test public void reconnectPreservesOwnerDraftAndAttachmentAndDelayedAcknowledgement() throws Exception {
        open(MODEL_MENU);
        eval("document.querySelector('#prompt-textarea').textContent='사용자가 보존해야 하는 초안 🐈';for(let i=0;i<30;i++)HoyoungWorkBridge.connect(" + PREFS + ");true");
        assertEquals("사용자가 보존해야 하는 초안 🐈", string("document.querySelector('#prompt-textarea').textContent"));
        assertEquals(0, intValue("menuOpens"));
        assertEquals(0, intValue("clicks"));
        eval("document.querySelector('#prompt-textarea').textContent='';document.querySelector('#attachments').innerHTML='<div data-testid=\"attachment\" data-file-name=\"owner.txt\">owner.txt</div>';HoyoungWorkBridge.connect(" + PREFS + ");true");
        assertTrue(bool("!!document.querySelector('[data-file-name=\"owner.txt\"]')"));
        assertEquals(0, intValue("menuOpens"));
        eval("document.querySelector('#attachments').textContent='';HoyoungWorkBridge.connect(" + PREFS + ");true");
        awaitJs("HoyoungWorkBridge.inspect().code==='ready'", 30_000);
        eval("window.ackDelay=1500;true");
        assertEquals("dispatch_pending", send("delayed-request", "주소가 먼저 바뀌고 사용자 턴은 늦게 표시").getString("code"));
        awaitEvent("accepted", "delayed-request", 30_000);
        assertEquals(1, intValue("clicks"));
        assertEquals(0, count("error", "delayed-request"));
        eval("answerPlain('늦은 확인 이후 완료');true");
        awaitEvent("completed", "delayed-request", 30_000);
        proof("owner-draft-and-attachment-preserved; repeated-connects-no-menu-or-submit; route-before-delayed-user-turn; one-completion");
    }

    @Test public void activityRecreationRetainsSettingsScreenAndUnsavedInstructions() throws Exception {
        DevicePermissionFixture.assertReady(context);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        scenario = ActivityScenario.launch(MainActivity.class);
        DeviceFixtureHealth.openPage(device, "설정");
        DeviceFixtureHealth.expandSection(device, "사용자 지침");
        DeviceFixtureHealth.revealDescription(device, "사용자 지침 편집");
        UiObject2 editor = DeviceFixtureHealth.waitFor(device, Until.findObject(By.desc("사용자 지침 편집").clazz(EditText.class)), 20_000);
        assertNotNull("The owner's actual settings editor must be available", editor);
        String draft = "EXPLORER_UNSAVED_INSTRUCTIONS\n회전 후에도 이 입력을 보존해 줘. 🐈";
        editor.setText(draft);
        scenario.recreate();
        DeviceFixtureHealth.awaitForeground(device);
        assertTrue("Android Activity recreation must keep the settings destination", device.hasObject(By.text("설정").pkg(context.getPackageName())));
        DeviceFixtureHealth.expandSection(device, "사용자 지침");
        DeviceFixtureHealth.revealDescription(device, "사용자 지침 편집");
        UiObject2 restored = DeviceFixtureHealth.waitFor(device, Until.findObject(By.desc("사용자 지침 편집").clazz(EditText.class)), 20_000);
        assertNotNull("Android Activity recreation must keep the settings screen containing the unsaved owner input", restored);
        assertEquals("The owner must not lose unsaved instructions during Activity recreation", draft, restored.getText());
        proof("native-settings-editor; actual-Activity-recreation; original-screen-and-unsaved-multiline-instructions-restored");
    }

    @Test public void hiddenCodeMirrorDescendantCannotSupplyAnUnobservedProtocolField() throws Exception {
        open("");
        assertEquals("dispatch_pending", send("hidden-native", "표시된 원문만 수집해 줘").getString("code"));
        awaitEvent("accepted", "hidden-native", 30_000);
        String protocol = new JSONObject().put("protocol", "hoyoung.v1").put("requestId", "hidden-native").put("message", "표시된 답변")
                .put("actions", new JSONArray().put(new JSONObject().put("id", "hidden-inspection").put("name", "phone_device_info").put("arguments", new JSONObject())))
                .put("memories", new JSONArray()).toString(2);
        eval("answerCode(" + JSONObject.quote(protocol) + ");"
                + "const line=Array.from(document.querySelectorAll('.cm-line')).find(line=>line.textContent.includes('\"name\":'));"
                + "line.firstElementChild.id='concealed-native';line.firstElementChild.style.display='none';true");
        SystemClock.sleep(3_000);
        assertEquals("Hidden source text must not enter a completed visible-DOM capture", 0, count("completed", "hidden-native"));
        assertFalse("Recovery must not promote hidden protocol text either", object("HoyoungWorkBridge.recoverResponse(['hidden-native'])").getBoolean("ok"));
        eval("document.querySelector('#concealed-native').style.display='';"
                + "const line=Array.from(document.querySelectorAll('.cm-line')).find(line=>line.textContent.includes('\"message\":'));"
                + "const text=line.textContent,split=text.indexOf(' 답변'),space=document.createElement('span');"
                + "space.id='concealed-space';space.textContent=' ';space.hidden=true;"
                + "line.replaceChildren(document.createTextNode(text.slice(0,split)),space,document.createTextNode(text.slice(split+1)));true");
        SystemClock.sleep(3_000);
        assertEquals("Hidden whitespace changes JSON string values and must also block capture", 0, count("completed", "hidden-native"));
        assertFalse("Recovery must preserve whitespace inside structured JSON strings", object("HoyoungWorkBridge.recoverResponse(['hidden-native'])").getBoolean("ok"));
        eval("document.querySelector('#concealed-space').hidden=false;true");
        awaitEvent("completed", "hidden-native", 30_000);
        assertEquals(protocol, event("completed", "hidden-native").getString("structuredEnvelope"));
        assertEquals(1, count("completed", "hidden-native"));
        proof("hidden-CodeMirror-source-and-string-whitespace-blocked; no-completion-or-recovery-until-rendered; exact-protocol-on-reveal; one-completion");
    }

    @Test public void missingProfileDoesNotBlockOriginalReplyRecoveryAfterDocumentRestart() throws Exception {
        open("");String original="새로고침 뒤 일반 글도 가져와 줘";
        assertEquals("dispatch_pending",send("plain-recovery",original).getString("code"));
        awaitEvent("accepted","plain-recovery",30_000);
        eval("answerPlain('일반 글 답변과 표의 결과 4');HoyoungWorkBridge.__dispose('fixture_restart');delete window.HoyoungWorkBridge;document.querySelector('#profile').remove();true");
        eval(asset("work-bridge.js")+";true");
        String requests=new JSONArray().put(new JSONObject().put("requestId","plain-recovery")
                .put("userTextDigest",com.hoyoung.assistant.runtime.DispatchPayloadRegistry.replyTextDigest(original))).toString();
        assertTrue(object("HoyoungWorkBridge.inspect()").getJSONObject("state").isNull("authenticated"));
        awaitJs("HoyoungWorkBridge.recoverResponse("+requests+").ok",30_000);
        JSONObject proof=object("HoyoungWorkBridge.recoverResponse("+requests+")");
        assertEquals("일반 글 답변과 표의 결과 4",proof.getString("text"));assertEquals("user_text_sha256",proof.getString("requestBinding"));
        assertEquals(1,intValue("clicks"));
        assertTrue(bool("HoyoungWorkBridge.adoptRecoveredResponse('plain-recovery','owned-matrix-123')"));
        proof("Android-WebCrypto; bridge-restart; no-account-control; exact-original-prompt-digest; plain-reply-recovery; one-submit");
    }

    @Test public void assistantCardsAndEmbedWithoutMarkdownAreCollectedAsReplyContent() throws Exception {
        open("");assertEquals("dispatch_pending",send("card-response","표와 미리보기를 보여 줘").getString("code"));
        awaitEvent("accepted","card-response",30_000);
        eval("answerPlain('');const section=window.lastGroup.querySelector('[data-conversation-role=assistant]');"
            +"section.innerHTML='<p>일반 글</p><pre><code>2+2</code></pre><table><tr><td>결과</td><td>4</td></tr></table><iframe title=작업미리보기 src=https://preview.example/result></iframe>';true");
        awaitEvent("completed","card-response",30_000);JSONObject answer=event("completed","card-response");
        assertTrue(answer.getString("text").contains("일반 글"));assertTrue(answer.getString("text").contains("결과"));
        assertTrue(answer.getString("text").contains("[임베드: 작업미리보기] https://preview.example/result"));
        assertFalse(answer.has("structuredEnvelope"));assertEquals(1,intValue("clicks"));
        proof("actual-WebView; plain-code-table-and-embed; no-Markdown-wrapper; one-completion; no-protocol-execution");
    }

    @Test public void connectionActionsAndHelpRemainReachableWithoutTechnicalRecoveryLabels() throws Exception {
        DevicePermissionFixture.assertReady(context);UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        scenario=ActivityScenario.launch(MainActivity.class);DeviceFixtureHealth.openPage(device,"Work");
        for(String label:new String[]{"연결","재연결","상세정보"})assertNotNull(label,device.wait(Until.findObject(By.text(label).pkg(context.getPackageName())),20_000));
        UiObject2 menu=device.findObject(By.desc("연결 옵션"));assertNotNull(menu);menu.click();
        device.waitForIdle();UiObject2 list=device.findObject(By.scrollable(true));
        if(!device.hasObject(By.text("도움말"))&&list!=null)list.scroll(androidx.test.uiautomator.Direction.DOWN,1f);
        UiObject2 help=device.wait(Until.findObject(By.text("도움말")),20_000);assertNotNull(help);help.click();
        assertTrue(device.wait(Until.hasObject(By.textContains("이미 보낸 요청은 다시 보내지 않고")),20_000));
        device.pressBack();DeviceFixtureHealth.openPage(device,"대화");
        device.findObject(By.desc("대화 옵션")).click();assertNotNull(device.wait(Until.findObject(By.text("도움말")),20_000));
        proof("native-Work-actions; connection-reconnection-details; Work-and-chat-help-accessible; offline-no-paid-request");
    }

    @Test public void finalAnswerExcludesProgressAndWaitsForCssVisibleCompletion() throws Exception {
        open("");assertEquals("dispatch_pending",send("benchmark-response","진행 설명과 최종 답을 구분해 줘").getString("code"));
        awaitEvent("accepted","benchmark-response",30_000);
        eval("const style=document.createElement('style');style.id='footer-style';style.textContent='.turn-action-controls{opacity:0}';document.head.append(style);answerPlain('');"
            +"window.lastGroup.querySelector('[data-conversation-role=assistant]').innerHTML='<div class=markdown>진행 설명</div><div data-streaming-response-status=complete><div class=markdown>작업 상태</div></div><div data-testid=cot-v5-panel><div class=markdown>과정 설명</div></div><div class=markdown>최종 답변</div>';true");
        SystemClock.sleep(2_700);assertEquals(0,count("completed","benchmark-response"));
        eval("document.querySelector('#footer-style').sheet.cssRules[0].style.opacity='1';true");
        awaitEvent("completed","benchmark-response",30_000);
        assertEquals("최종 답변",event("completed","benchmark-response").getString("text"));assertEquals(1,intValue("clicks"));
        proof("actual-WebView; progress-excluded; invisible-footer-not-complete; stylesheet-only-reveal; one-completion");
    }

    @Test public void productionControllerConnectsBeforeAnImageFinishesLoading() throws Exception {
        CountDownLatch releaseImage=new CountDownLatch(1),imageRequested=new CountDownLatch(1),finished=new CountDownLatch(1);
        byte[] page=PAGE.replace("/* SETUP */", "const image=document.createElement('img');image.src='/benchmark-slow.png';document.body.append(image);").getBytes(StandardCharsets.UTF_8);
        scenario=ActivityScenario.launch(MainActivity.class);
        try {
            scenario.onActivity(activity->{
                com.hoyoung.assistant.runtime.HoyoungApplication app=(com.hoyoung.assistant.runtime.HoyoungApplication)activity.getApplication();
                com.hoyoung.assistant.web.WorkWebController controller=new com.hoyoung.assistant.web.WorkWebController(context,app.getRuntime(),app.getFiles());
                web=controller.getView();WebViewClient production=web.getWebViewClient();
                web.setWebViewClient(new WebViewClient(){
                    @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                        if(request.isForMainFrame()){mainRequests.incrementAndGet();return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(page));}
                        if(request.getUrl().getPath().equals("/benchmark-slow.png"))return new WebResourceResponse("image/png",null,new InputStream(){
                            @Override public int read(){imageRequested.countDown();try{releaseImage.await(20,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}return -1;}
                        });
                        return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                    }
                    @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){production.onPageStarted(view,url,icon);}
                    @Override public void onPageCommitVisible(WebView view,String url){production.onPageCommitVisible(view,url);}
                    @Override public void onPageFinished(WebView view,String url){production.onPageFinished(view,url);finished.countDown();}
                    @Override public void onReceivedError(WebView view,WebResourceRequest request,android.webkit.WebResourceError error){production.onReceivedError(view,request,error);}
                    @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){production.onReceivedHttpError(view,request,response);}
                });
                activity.addContentView(web,new FrameLayout.LayoutParams(-1,-1));controller.start();
            });
            assertTrue(imageRequested.await(15,TimeUnit.SECONDS));
            awaitJs("!!window.HoyoungWorkBridge",15_000);
            assertEquals("The real image request is still pending",1,finished.getCount());
            assertTrue(bool("document.readyState!=='complete'"));
            eval("window.benchmarkBridge=window.HoyoungWorkBridge;HoyoungWorkBridge.connect("+PREFS+");true");
            awaitJs("HoyoungWorkBridge.inspect().code==='ready'",15_000);
            assertEquals(0,intValue("clicks"));releaseImage.countDown();
            assertTrue(finished.await(15,TimeUnit.SECONDS));
            assertTrue(bool("window.HoyoungWorkBridge===window.benchmarkBridge"));assertEquals(1,mainRequests.get());
            proof("production-controller; actual-WebView; pending-image; ready-before-load-completion; late-finish-no-reinstall; no-submit");
        } finally {releaseImage.countDown();}
    }

    @Test public void truncatedAndCodeFormattedUserTurnsAreConfirmedWithoutResending() throws Exception {
        open("window.expandClicks=0;document.querySelector('form').addEventListener('submit',()=>{"
            +"const content=window.lastGroup.querySelector('[data-search-result-target]');if(window.clicks===1){const authored=content.cloneNode(true);authored.querySelectorAll('button').forEach(node=>node.remove());const full=authored.textContent;content.textContent=full.slice(0,100);"
            +"const button=document.createElement('button');button.type='button';button.setAttribute('aria-expanded','false');button.setAttribute('data-thread-find-skip','true');button.setAttribute('data-markdown-copy','exclude');button.textContent='더 보기';content.parentElement.append(button);"
            +"button.onclick=()=>{window.expandClicks++;setTimeout(()=>{content.textContent=full;button.setAttribute('aria-expanded','true');},500);};}"
            +"else content.innerHTML='원문<pre data-markdown-copy=code-block><code data-markdown-copy-content=true data-language=json>{\"value\":\"a b\"}</code></pre>끝';});");
        assertEquals("dispatch_pending",send("truncated-original","긴 원문 ".repeat(200)+"END").getString("code"));
        awaitEvent("accepted","truncated-original",30_000);assertEquals(1,intValue("expandClicks"));
        eval("answerPlain('긴 원문 확인 완료');true");awaitEvent("completed","truncated-original",30_000);
        assertEquals("dispatch_pending",send("code-original","원문\n```json\n{\"value\":\"a b\"}\n```\n끝").getString("code"));
        awaitEvent("accepted","code-original",30_000);eval("answerPlain('코드 원문 확인 완료');true");awaitEvent("completed","code-original",30_000);
        assertEquals(2,intValue("clicks"));assertEquals(1,intValue("expandClicks"));
        proof("actual-WebView; DOM-truncated-user-expands-once; code-fence-reconstruction; two-unique-requests; no-resubmit");
    }

    private void open(String setup) throws Exception {
        String page = PAGE.replace("/* SETUP */", setup);
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        CountDownLatch loaded = new CountDownLatch(1);
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            web = new WebView(activity);
            web.setContentDescription("Offline Work compatibility explorer");
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false);
            WebViewCompat.addWebMessageListener(web, "HoyoungHost", Collections.singleton("https://chatgpt.com"),
                    (view, message, origin, isMainFrame, reply) -> {
                        assertTrue("Every native fixture event must come from the main frame", isMainFrame);
                        assertEquals("https://chatgpt.com", origin.toString());
                        try { events.add(new JSONObject(message.getData())); }
                        catch (Exception invalid) { throw new AssertionError(invalid); }
                    });
            web.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (request.isForMainFrame()) {
                        mainRequests.incrementAndGet();
                        return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(bytes));
                    }
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
                @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
            });
            web.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onConsoleMessage(ConsoleMessage message) {
                    console.add(message.messageLevel() + ": " + message.message()); return true;
                }
            });
            activity.addContentView(web, new FrameLayout.LayoutParams(-1, -1));
            web.loadUrl("https://chatgpt.com/");
        });
        assertTrue("The intercepted document must load in the actual Android renderer", loaded.await(60, TimeUnit.SECONDS));
        assertEquals(1, mainRequests.get());
        String source = "(function(){\n" + asset("vendor/chat-on-steroids/chatgpt-dom.js") + "\n" + asset("cos-dom-adapter.js") + "\n})();\n"
                + asset("work-session-cleanup.js") + "\n" + asset("work-bridge.js");
        eval(source + ";true");
        awaitJs("!!window.HoyoungWorkBridge", 30_000);
        scenario.onActivity(activity -> {
            assertTrue("Tests must use an attached Android WebView", web.isAttachedToWindow());
            assertTrue("The WebView must have real layout", web.getWidth() > 0 && web.getHeight() > 0);
        });
    }

    private JSONObject send(String id, String text) throws Exception {
        JSONObject payload = new JSONObject().put("requestId", id).put("text", text)
                .put("requiredModel", "gpt-6.1-sol").put("requiredEffort", "medium").put("requiredSpeed", "fast");
        return object("HoyoungWorkBridge.send(" + payload + ")");
    }
    private Object eval(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1); String[] result = {null};
        // Each evaluateJavascript call shares the window's global lexical scope.
        // A block keeps fixture-local const declarations independent while
        // preserving the final expression's completion value for native asserts.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> web.evaluateJavascript("{\n" + script + "\n}", raw -> { result[0] = raw; done.countDown(); }));
        assertTrue("Real WebView JavaScript callback timed out; console=" + console, done.await(30, TimeUnit.SECONDS));
        return result[0] == null ? JSONObject.NULL : new JSONTokener(result[0]).nextValue();
    }
    private JSONObject object(String script) throws Exception { return new JSONObject((String) eval("JSON.stringify(" + script + ")")); }
    private boolean bool(String script) throws Exception { return Boolean.TRUE.equals(eval(script)); }
    private int intValue(String script) throws Exception { return ((Number) eval(script)).intValue(); }
    private String string(String script) throws Exception { return (String) eval(script); }
    private void awaitJs(String condition, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do { if (bool(condition)) return; SystemClock.sleep(200); } while (SystemClock.elapsedRealtime() < deadline);
        fail("Unmet real WebView condition: " + condition + "; diagnostics=" + object("HoyoungWorkBridge.connectionDiagnostics()") + "; console=" + console);
    }
    private void awaitEvent(String type, String request, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do { if (count(type, request) > 0) return; SystemClock.sleep(100); } while (SystemClock.elapsedRealtime() < deadline);
        fail("Missing native event " + type + "/" + request + "; events=" + events + "; diagnostics=" + object("HoyoungWorkBridge.connectionDiagnostics()") + "; console=" + console);
    }
    private int count(String type, String request) {
        int found = 0; for (JSONObject event : events) if (type.equals(event.optString("type")) && request.equals(event.optString("requestId"))) found++; return found;
    }
    private JSONObject event(String type, String request) {
        for (JSONObject event : events) if (type.equals(event.optString("type")) && request.equals(event.optString("requestId"))) return event;
        throw new AssertionError("Missing " + type + "/" + request);
    }
    private String asset(String name) throws Exception { try (InputStream input = context.getAssets().open(name)) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); } }
    private void proof(String facts) {
        String text = "HOYOUNG_WORK_EXPLORER hardware=" + Build.HARDWARE + "; api=" + Build.VERSION.SDK_INT
                + "; webview=" + WebViewCompat.getCurrentWebViewPackage(context).versionName + "; " + facts + "; actual-Android-WebView; offline-synthetic-page\n";
        Bundle status = new Bundle(); status.putString("stream", text); InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
        android.util.Log.i("HoyoungDeviceProof", text);
    }

    private static final String SIDEBAR = """
        document.querySelector('#profile').remove();window.sidebarOpens=0;window.sidebarCloses=0;
        const open=document.createElement('button');open.textContent='사이드바 열기';open.setAttribute('aria-label','사이드바 열기');document.querySelector('header').append(open);
        open.onclick=()=>{window.sidebarOpens++;open.hidden=true;const side=document.createElement('nav');side.id='sidebar';side.setAttribute('role','dialog');
          side.innerHTML='<button id="sidebar-close" aria-label="사이드바 닫기">닫기</button>';
          if(!window.omitAccount)side.innerHTML+='<button data-testid="accounts-profile-button">Synthetic account</button>';
          document.body.append(side);document.querySelector('#sidebar-close').onclick=()=>{window.sidebarCloses++;side.remove();open.hidden=false;};};
        """;
    private static final String MODEL_MENU = """
        document.querySelector('#effort-group').remove();document.querySelector('#speed-group').remove();
        const model=document.querySelector('#model');model.textContent='GPT-6.1 Sol Medium';model.setAttribute('aria-haspopup','menu');model.setAttribute('aria-controls','picker-menu');model.setAttribute('aria-expanded','false');
        window.menuOpens=0;window.fastChanges=0;window.isFast=true;
        window.closePicker=()=>{document.querySelector('#picker-menu')?.remove();model.setAttribute('aria-expanded','false');document.querySelector('#content').removeAttribute('aria-hidden');};
        model.onclick=()=>{if(document.querySelector('#picker-menu')){closePicker();return;}window.menuOpens++;model.setAttribute('aria-expanded','true');
          const menu=document.createElement('div');menu.id='picker-menu';menu.setAttribute('role','menu');menu.setAttribute('aria-labelledby','model');
          menu.innerHTML='<button id="fast" role="menuitemcheckbox">Fast</button>';document.body.append(menu);document.querySelector('#content').setAttribute('aria-hidden','true');
          const fast=menu.querySelector('#fast');fast.setAttribute('aria-checked',String(window.isFast));fast.onclick=()=>{window.fastChanges++;window.isFast=!window.isFast;fast.setAttribute('aria-checked',String(window.isFast));};};
        document.addEventListener('keydown',event=>{if(event.key==='Escape')closePicker();});
        document.querySelector('#prompt-textarea').addEventListener('focus',()=>closePicker());
        """;
    private static final String PAGE = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{margin:0;font:16px sans-serif;color:#111;background:white}button{min-height:40px}header{padding:8px}#turns{padding:8px}form{padding:12px}#prompt-textarea{min-height:70px;white-space:pre-wrap;border:1px solid #888}#picker-menu,nav{position:fixed;left:10px;top:80px;background:white;border:2px solid #555;padding:14px;z-index:20}.cm-line{white-space:pre-wrap}</style></head><body>
        <div id="content"><header>
        <button id="profile" data-testid="user-menu-button" aria-label="프로필 메뉴 열기">계정</button>
        <button id="work" role="tab" aria-selected="true">Work</button>
        <button id="model" data-testid="model-switcher-dropdown-button">GPT-6.1 Sol</button>
        <div id="effort-group" role="group" aria-label="Reasoning effort"><button role="menuitemradio" aria-checked="true">Medium</button></div>
        <div id="speed-group" role="group" aria-label="Speed"><button role="menuitemradio" aria-checked="true">Fast</button></div></header>
        <main id="turns"></main><form data-chatgpt-composer><div id="prompt-textarea" role="textbox" contenteditable="true"></div>
        <button type="submit" data-testid="send-button">보내기</button><button type="button" data-testid="stop-button" hidden>중지</button><div id="attachments"></div></form></div>
        <script>
        window.clicks=0;window.ackDelay=0;const composer=document.querySelector('#prompt-textarea'),stop=document.querySelector('[data-testid="stop-button"]');
        document.querySelector('form').onsubmit=event=>{event.preventDefault();window.clicks++;const text=composer.innerText,number=window.clicks;
          if(location.pathname==='/')history.pushState({},'', '/c/owned-matrix-123');composer.textContent='';stop.hidden=false;
          const acknowledge=()=>{const group=document.createElement('article');group.dataset.turnKey='matrix-'+number;
            const user=document.createElement('div');user.setAttribute('data-user-message-bubble','');
            const authored=document.createElement('div');authored.setAttribute('data-search-result-target','');authored.style='max-height:20px;overflow:hidden;white-space:pre-wrap';authored.textContent=text;
            const expand=document.createElement('button');expand.type='button';expand.textContent='더 보기';authored.append(expand);user.append(authored);group.append(user);document.querySelector('#turns').append(group);window.lastGroup=group;};
          if(window.ackDelay)setTimeout(acknowledge,window.ackDelay);else acknowledge();};
        window.footer=()=>{const footer=document.createElement('div');footer.className='turn-action-controls';footer.innerHTML='<button type="button" data-testid="copy-turn-action-button">복사</button>';window.lastGroup.append(footer);stop.hidden=true;};
        window.answerPlain=text=>{const assistant=document.createElement('section');assistant.setAttribute('data-conversation-role','assistant');const answer=document.createElement('div');answer.className='markdown';answer.textContent=text;assistant.append(answer);window.lastGroup.append(assistant);footer();};
        window.answerCode=text=>{const assistant=document.createElement('section');assistant.setAttribute('data-conversation-role','assistant');const answer=document.createElement('div');answer.className='markdown';
          const surface=document.createElement('div');surface.setAttribute('data-markdown-copy','code-block');const editor=document.createElement('div');editor.className='cm-content';editor.dataset.language='hoyoung';
          for(const line of text.split('\\n')){const node=document.createElement('div');node.className='cm-line';const token=document.createElement('span');token.textContent=line;node.append(token);editor.append(node);}surface.append(editor);answer.append(surface);assistant.append(answer);window.lastGroup.append(assistant);footer();};
        /* SETUP */
        </script></body></html>
        """;
}
