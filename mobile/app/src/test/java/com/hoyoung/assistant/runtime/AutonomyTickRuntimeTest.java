package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.os.Looper;
import com.hoyoung.assistant.phone.PhoneTools;
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
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Actual native SQLite/runtime integration; fake transport performs no model or paid API call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AutonomyTickRuntimeTest {
    private Context context; private HoyoungStore store; private AssistantRuntime runtime; private Transport transport;
    @Before public void before() throws Exception {
        context=RuntimeEnvironment.getApplication();
        try(java.io.InputStream a=context.getAssets().open("core-identity.txt")){assertTrue(a.read()!=-1);}
        try(java.io.InputStream a=context.getAssets().open("personalized-instructions.txt")){assertTrue(a.read()!=-1);}
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        store=new HoyoungStore(context);store.putSetting("autonomy.enabled","false");
        store.putSetting("memoryReviewEnabled","false");store.putSetting("heartbeatSeconds","300");
        construct();ready();drain();
    }
    @After public void after() {drain();if(store!=null)store.close();if(context!=null)context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    private void construct() {
        runtime=new AssistantRuntime(context,store,new InternalFiles(context),new PhoneTools(context,store),Runnable::run);
        transport=new Transport();runtime.attachTransport(transport);
    }
    private void ready() {
        runtime.onWebEvent(object("type","state","code","ready","state",object("authenticated",true,"workSelected",true,"temporary",true,"model","gpt-6.1-sol","effort","medium","speed","fast","busy",false,"quota",false)));
    }
    private void enable() {store.putSetting("autonomy.enabled","true");runtime.tick();drain();}
    private JSONObject active() {return store.getRequest(runtime.state().optLong("activeStoreId"));}
    private JSONObject policy() {return runtime.getAutonomyTickPolicy().snapshot(System.currentTimeMillis());}
    private void accept(JSONObject request) {runtime.onWebEvent(object("type","accepted","requestId",request.optString("external_id")));drain();}
    private void finishIdle(JSONObject request) {
        JSONObject envelope=object("protocol","hoyoung.v1","requestId",request.optString("external_id"),"status","idle","message","지금 필요한 새 행동 없음","actions",new JSONArray(),"memories",new JSONArray());
        runtime.onWebEvent(object("type","completed","requestId",request.optString("external_id"),"text",envelope.toString(),"rawEnvelopeEligible",true));drain();
    }
    private static void drain() {Shadows.shadowOf(Looper.getMainLooper()).idle();}

    @Test public void defaultJudgesWithoutNewEventsAndAcceptedIdleIsRecordedAsNoAction() {
        assertEquals(0,store.messageCount());enable();assertEquals(diagnostics(),1,transport.ids.size());
        JSONObject request=active();assertNotNull(request);assertEquals("autonomy",request.optString("kind"));
        String tick=request.optString("external_id");assertTrue(tick.startsWith("inference-"));
        assertEquals(tick,policy().optString("inFlightTickId"));assertEquals("reserved",policy().optString("inFlightStatus"));
        assertEquals(300_000L,policy().optLong("nextDueAt")-policy().optLong("lastDueAt"));
        accept(request);assertEquals("executed",policy().optString("inFlightStatus"));
        finishIdle(request);assertEquals("no_action",policy().optString("lastOutcome"));assertEquals("",policy().optString("inFlightTickId"));
        assertEquals("no_action",runtime.state().optJSONObject("autonomyTick").optString("lastOutcome"));
        runtime.tick();runtime.tick();drain();assertEquals(1,transport.ids.size());
    }
    @Test public void secondInferenceWaitsFiveMinutesAndDoesNotRequireNewOwnerEvent() {
        enable();JSONObject first=active();assertNotNull(diagnostics(),first);accept(first);finishIdle(first);
        long due=policy().optLong("nextDueAt");
        assertEquals(300_000L,due-policy().optLong("lastDueAt"));
        assertTrue(System.currentTimeMillis()<due);
        ready();runtime.tick();runtime.tick();drain();assertEquals(1,transport.ids.size());
        // The standalone JVM uses host wall time; advance the durable scheduled boundary, not Android uptime.
        forceStoredDue();ready();runtime.tick();drain();assertEquals(diagnostics(),2,transport.ids.size());
        assertNotEquals(first.optString("external_id"),active().optString("external_id"));
    }
    @Test public void queuedOwnerRequestTakesDispatchBeforeDueInference() {
        runtime.detachTransport();JSONObject owner=runtime.receive("내 요청을 먼저 처리해",new JSONArray());
        store.putSetting("autonomy.enabled","true");runtime.attachTransport(transport);ready();drain();
        assertEquals(diagnostics(),1,transport.ids.size());assertEquals(owner.optString("external_id"),transport.ids.get(0));
        assertEquals("coalesced_busy",policy().optString("lastOutcome"));
        assertEquals("",policy().optString("inFlightTickId"));
    }
    @Test public void durableQuotaBlockConsumesDueSlotWithoutTransportOrPaidFallback() {
        store.putSetting("runtime.quotaBlocked","true");enable();assertEquals(0,transport.ids.size());
        assertTrue(runtime.state().optBoolean("quotaBlocked"));assertEquals("blocked_quota",policy().optString("lastOutcome"));
        long next=policy().optLong("nextDueAt");runtime.tick();ready();drain();assertEquals(0,transport.ids.size());assertEquals(next,policy().optLong("nextDueAt"));
        assertTrue(hasTickOutcome("blocked_quota"));
        // This fixture only supplies the subscription Work Transport; no paid API client is installed or called.
    }
    @Test public void nativeDatabaseRestartDoesNotReplayAnUnsentReservedTick() {
        AutonomyTickPolicy.Decision claimed=runtime.getAutonomyTickPolicy().poll(System.currentTimeMillis(),true,false,false,false,true,false);
        assertTrue(claimed.shouldDispatch);assertEquals("reserved",policy().optString("inFlightStatus"));
        store.putSetting("autonomy.enabled","true");drain();store.close();store=new HoyoungStore(context);construct();ready();runtime.tick();drain();
        assertEquals(0,transport.ids.size());assertNotEquals("executed",policy().optString("inFlightStatus"));
        assertFalse(runtime.getAutonomyTickPolicy().markDispatched("inference-invented",System.currentTimeMillis()));
    }
    @Test public void acceptedInferenceRemainsUncertainAcrossNativeDatabaseRestartWithoutReplay() {
        enable();JSONObject request=active();assertNotNull(diagnostics(),request);accept(request);String tick=request.optString("external_id");
        assertEquals("executed",policy().optString("inFlightStatus"));drain();store.close();store=new HoyoungStore(context);construct();ready();runtime.tick();drain();
        assertEquals(0,transport.ids.size());JSONObject stored=store.getRequest(request.optLong("id"));assertNotNull(stored);
        assertNotEquals("completed",stored.optString("status"));
        assertFalse("no_action".equals(policy().optString("lastOutcome")));
        assertTrue(store.getSetting(AutonomyTickPolicy.STATE_KEY,"").contains(tick));
    }
    @Test public void preparationFailureReleasesReservationAndNextSlotDoesNotReplayBlockedTick() throws Exception {
        runtime.detachTransport();
        java.lang.reflect.Constructor<AssetManager> constructor=AssetManager.class.getDeclaredConstructor();constructor.setAccessible(true);
        final AssetManager missingAssets=constructor.newInstance();
        Context missingContext=new ContextWrapper(context) {
            @Override public Context getApplicationContext(){return this;}
            @Override public AssetManager getAssets(){return missingAssets;}
        };
        runtime=new AssistantRuntime(missingContext,store,new InternalFiles(context),new PhoneTools(context,store),Runnable::run);
        transport=new Transport();runtime.attachTransport(transport);ready();enable();
        assertEquals(0,transport.ids.size());assertEquals("blocked_memory",policy().optString("lastOutcome"));
        assertEquals("",policy().optString("inFlightTickId"));
        JSONObject old=store.listRequests(1).optJSONObject(0);assertNotNull(old);assertEquals("blocked",old.optString("status"));
        forceStoredDue();ready();runtime.tick();drain();
        JSONObject next=store.listRequests(1).optJSONObject(0);assertNotEquals(old.optString("external_id"),next.optString("external_id"));
        assertEquals("blocked",store.getRequest(old.optLong("id")).optString("status"));
        assertEquals("blocked_memory",policy().optString("lastOutcome"));assertEquals("",policy().optString("inFlightTickId"));
    }
    private void forceStoredDue() {
        JSONObject s=policy();try{s.put("nextDueAt",System.currentTimeMillis()-1);}catch(Exception e){throw new AssertionError(e);}
        store.putSetting(AutonomyTickPolicy.STATE_KEY,s.toString());
    }
    private String diagnostics() {return "runtime="+runtime.state()+" requests="+store.listRequests(10)+" tick="+policy();}
    private boolean hasTickOutcome(String outcome) {
        JSONArray events=store.events(100);for(int i=0;i<events.length();i++){JSONObject e=events.optJSONObject(i);if(e!=null && "autonomy_tick".equals(e.optString("type"))){try{if(outcome.equals(new JSONObject(e.optString("payload")).optString("outcome")))return true;}catch(Exception malformed){throw new AssertionError(malformed);}}}return false;
    }
    private static JSONObject object(Object...pairs) {JSONObject o=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)o.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(Exception e){throw new IllegalStateException(e);}return o;}
    private static final class Transport implements AssistantRuntime.Transport {
        final List<String> ids=new ArrayList<>(),prompts=new ArrayList<>();
        public void inspect(){}public void send(String id,String prompt){ids.add(id);prompts.add(prompt);}public void openTemporary(){}public void cancel(){}
    }
}
