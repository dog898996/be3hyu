package com.hoyoung.assistant;

import static org.junit.Assert.*;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.web.WorkWebController;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Production runtime + controller + journal + actual WebView; only the provider page is synthetic. */
@RunWith(AndroidJUnit4.class)
public final class WorkNativeDispatchDeviceTest {
    private HoyoungApplication app;
    private WebView web;
    @Test public void reviewedCreationReconnectsAndNewRequestReachesNativeChatExactlyOnce() throws Exception {
        assertTrue(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"));assertEquals(35,Build.VERSION.SDK_INT);
        app=(HoyoungApplication)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        assertNull(app.getSystemService(ConnectivityManager.class).getActiveNetwork());
        String oldId=UUID.randomUUID().toString();
        byte[] page=WorkCompatibilityExplorerDeviceTest.PAGE.replace("/* SETUP */", "").getBytes(StandardCharsets.UTF_8);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            await(()->app.getRuntime().state().optBoolean("receiptIndexReady"));
            scenario.onActivity(activity->{
                try {
                    app.getStore().putSetting("memoryReviewEnabled","false");app.getStore().putSetting("phoneContextEnabled","false");
                    JSONObject previous=app.getStore().enqueueRequest("이미 사용자가 확인한 이전 작업","user",oldId);
                    app.getStore().markRequest(previous.getLong("id"),"uncertain",null);
                    WorkWebController controller=app.getWebController();
                    Field field=WorkWebController.class.getDeclaredField("conversations");field.setAccessible(true);Object journal=field.get(controller);
                    Method begin=journal.getClass().getDeclaredMethod("begin",String.class);begin.setAccessible(true);begin.invoke(journal,oldId);
                    app.getRuntime().resolveUncertain(previous.getLong("id"));
                    web=controller.getView();WebViewClient production=web.getWebViewClient();
                    web.setWebViewClient(new WebViewClient(){
                        @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                            return new WebResourceResponse(request.isForMainFrame()?"text/html":"text/plain","UTF-8",new ByteArrayInputStream(request.isForMainFrame()?page:new byte[0]));
                        }
                        @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){production.onPageStarted(view,url,icon);}
                        @Override public void onPageCommitVisible(WebView view,String url){production.onPageCommitVisible(view,url);}
                        @Override public void onPageFinished(WebView view,String url){production.onPageFinished(view,url);}
                        @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){production.onReceivedError(view,request,error);}
                        @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){production.onReceivedHttpError(view,request,response);}
                    });
                    if(web.getParent() instanceof ViewGroup parent)parent.removeView(web);
                    activity.addContentView(web,new FrameLayout.LayoutParams(-1,-1));web.loadUrl(WorkWebController.HOME_URL);
                }catch(Exception failure){throw new AssertionError(failure);}
            });
            // loadUrl posts navigation: a prior document's readiness may still
            // be visible before onPageStarted. Require this reservation's receipt.
            await(()->app.getRuntime().state().optBoolean("ready")
                    &&!app.getStore().getSetting("workCreationRetired:"+oldId,"").isEmpty());
            JSONObject retirement=new JSONObject(app.getStore().getSetting("workCreationRetired:"+oldId,""));
            assertFalse(retirement.getBoolean("providerDeletionVerified"));
            JSONObject[] request={null};scenario.onActivity(activity->request[0]=app.getRuntime().receive("새 인사에 답해 줘",new JSONArray()));
            String id=request[0].getString("external_id");
            await(()->"accepted".equals(app.getStore().getRequestByExternalId(id).optString("status")));
            assertEquals(1,((Number)eval("window.clicks")).intValue());
            eval("answerPlain('실제 native 대화로 돌아온 답변');true");
            await(()->"completed".equals(app.getStore().getRequestByExternalId(id).optString("status")));
            int answers=0;JSONArray messages=app.getStore().listMessages(30);
            for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);
                if("assistant".equals(message.optString("role"))&&id.equals(message.optString("request_id"))){
                    assertTrue(message.getString("text").contains("실제 native 대화로 돌아온 답변"));answers++;}}
            assertEquals(1,answers);assertEquals(1,((Number)eval("window.clicks")).intValue());
            assertEquals("completed",app.getStore().getRequestByExternalId(oldId).getString("status"));
            Bundle proof=new Bundle();proof.putString("stream","HOYOUNG_NATIVE_DISPATCH production-runtime-controller-journal; reviewed-orphan-retired; final-native-authorization; one-submit; one-native-chat-reply; no-old-replay; actual-Android35-WebView; offline-synthetic-provider\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0,proof);
        }
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+30_000;
        do{if(condition.getAsBoolean())return;SystemClock.sleep(150);}while(SystemClock.elapsedRealtime()<deadline);
        fail("Native condition timed out: "+app.getRuntime().state()+"; requests="+app.getStore().listRequests(8));
    }
    private Object eval(String source) throws Exception {
        String[] response={null};CountDownLatch done=new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->web.evaluateJavascript(source,raw->{response[0]=raw;done.countDown();}));
        assertTrue(done.await(10,TimeUnit.SECONDS));return new JSONTokener(response[0]).nextValue();
    }
}
