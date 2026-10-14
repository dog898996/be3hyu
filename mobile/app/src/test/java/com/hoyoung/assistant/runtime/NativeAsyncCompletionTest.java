package com.hoyoung.assistant.runtime;
import android.app.Application;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class)
public final class NativeAsyncCompletionTest {
    private static final String ID="11111111-1111-4111-8111-111111111111",OTHER="22222222-2222-4222-8222-222222222222";
    @Test public void realCallbackEvidenceClosesOnlyMatchingRequestAndPreservesAcceptedOriginal()throws Exception {
        NativeAsyncCompletion evidence=new NativeAsyncCompletion();JSONObject start=receipt("phone_screen_capture",o("ok",true,"requestId",ID,"completionConfirmed",false));String original=start.toString();
        evidence.observe(receipt("phone_screen_capture_read",o("ok",true,"completionConfirmed",true,"uploadRequested",true,"request",o("requestId",ID,"state","observed","completionConfirmed",true))));
        assertTrue(evidence.confirms(start));assertEquals(original,start.toString());assertFalse(evidence.confirms(receipt("phone_screen_capture",o("ok",true,"requestId",OTHER))));
        assertFalse(evidence.confirms(receipt("phone_voice_record",o("ok",true,"requestId",ID))));
    }
    @Test public void pendingFailedSecureOrUnindexedScreenshotCannotCertifyCompletion()throws Exception {
        JSONObject start=receipt("phone_screen_capture",o("ok",true,"requestId",ID));
        for(String state:new String[]{"pending","failed","outcome_unknown_after_process_restart"}){NativeAsyncCompletion proof=new NativeAsyncCompletion();proof.observe(receipt("phone_screen_capture_read",o("ok",true,"completionConfirmed",true,"uploadRequested",true,"request",o("requestId",ID,"state",state,"completionConfirmed",true))));assertFalse(proof.confirms(start));}
        NativeAsyncCompletion proof=new NativeAsyncCompletion();proof.observe(receipt("phone_screen_capture_read",o("ok",true,"completionConfirmed",true,"uploadRequested",false,"request",o("requestId",ID,"state","observed","completionConfirmed",true))));assertFalse(proof.confirms(start));
    }
    @Test public void voiceFileVerificationAndSpecificHotspotCallbacksAreRequired()throws Exception {
        NativeAsyncCompletion proof=new NativeAsyncCompletion();JSONObject voice=receipt("phone_voice_record",o("ok",true,"requestId",ID));
        proof.observe(receipt("phone_voice_record_read",o("ok",true,"request",o("requestId",ID,"completionConfirmed",true,"fileValid",false,"fileId","captures/invalid.m4a"))));assertFalse(proof.confirms(voice));
        proof.observe(receipt("phone_voice_record_read",o("ok",true,"request",o("requestId",ID,"completionConfirmed",true,"fileValid",true,"fileId","captures/voice/test.m4a"))));assertTrue(proof.confirms(voice));
        JSONObject stop=receipt("phone_wifi_hotspot_stop",o("ok",true,"code","hotspot_stop_requested","stopRequested",true,"requestId",ID));proof.observe(receipt("phone_wifi_hotspot_read",o("ok",true,"request",o("requestId",ID,"state","active","startedCallbackAt",100,"completionConfirmed",true))));assertFalse(proof.confirms(stop));
        proof.observe(receipt("phone_wifi_hotspot_read",o("ok",true,"request",o("requestId",ID,"state","stopped","startedCallbackAt",100,"completionConfirmed",true))));assertTrue(proof.confirms(stop));
    }
    @Test public void firedAlarmConfirmsDeliveryCallbackOnlyAndDoesNotCertifyCallsOrGenericModelClaims()throws Exception {
        NativeAsyncCompletion proof=new NativeAsyncCompletion();JSONObject alarm=receipt("phone_alarm_schedule",o("ok",true,"alarm",o("alarmId",ID)));
        proof.observe(receipt("phone_alarm_list",o("ok",true,"alarms",new JSONArray().put(o("alarmId",ID,"completionConfirmed",true,"receiverCallbackObserved",true,"firedAt",100)))));assertTrue(proof.confirms(alarm));
        assertFalse(proof.confirms(receipt("phone_call_place",o("ok",true,"requestId",ID))));
        NativeAsyncCompletion untrusted=new NativeAsyncCompletion();untrusted.observe(receipt("tasks.report_completed",o("ok",true,"request",o("requestId",ID,"completionConfirmed",true,"fileValid",true,"fileId","pretend"))));assertFalse(untrusted.confirms(receipt("phone_voice_record",o("ok",true,"requestId",ID))));
    }
    @Test public void smsSentAloneIsNotDeliveredAndCannotCloseTheSend()throws Exception {
        NativeAsyncCompletion proof=new NativeAsyncCompletion();JSONObject send=receipt("phone_sms_send",o("ok",true,"requestId",ID));
        proof.observe(receipt("phone_sms_send_read",o("ok",true,"completionConfirmed",false,"request",o("requestId",ID,"sentConfirmed",true,"deliveryConfirmed",false,"completionConfirmed",false))));assertFalse(proof.confirms(send));
        proof.observe(receipt("phone_sms_send_read",o("ok",true,"completionConfirmed",true,"request",o("requestId",ID,"sentConfirmed",true,"deliveryConfirmed",true,"completionConfirmed",true))));assertTrue(proof.confirms(send));
    }
    @Test public void terminalCallbacksCannotTurnUnrequestedStopsIntoCompletedActions()throws Exception {
        NativeAsyncCompletion proof=new NativeAsyncCompletion();
        proof.observe(receipt("phone_wifi_hotspot_read",o("ok",true,"request",o("requestId",ID,"state","stopped","completionConfirmed",true))));
        assertFalse(proof.confirms(receipt("phone_wifi_hotspot_stop",o("ok",true,"code","hotspot_not_owned_by_this_process","requestId",ID,"stopRequested",false))));
        proof.observe(receipt("phone_bluetooth_scan_read",o("ok",true,"request",o("requestId",ID,"completionConfirmed",true,"scanEndedVerified",true))));
        assertFalse(proof.confirms(receipt("phone_bluetooth_scan_cancel",o("ok",true,"code","scan_not_pending","requestId",ID,"cancelRequested",false))));
        assertTrue(proof.confirms(receipt("phone_bluetooth_scan_cancel",o("ok",true,"code","bluetooth_cancel_requested","requestId",ID))));
        proof.observe(receipt("phone_voice_record_read",o("ok",true,"request",o("requestId",ID,"completionConfirmed",true,"fileValid",true,"fileId","captures/voice/fixture.m4a"))));
        assertFalse(proof.confirms(receipt("phone_voice_record_stop",o("ok",true,"code","recording_not_active","requestId",ID))));
        assertTrue(proof.confirms(receipt("phone_voice_record_stop",o("ok",true,"code","stop_requested","requestId",ID))));
    }
    @Test public void journalReconcilesWithinSameRootWithoutReplayingOrRewritingRequest()throws Exception {
        try(HoyoungStore store=new HoyoungStore(RuntimeEnvironment.getApplication())) {
            ActionReceiptJournal journal=new ActionReceiptJournal(store);journal.ensureIndex();JSONObject start=receipt("phone_screen_capture",o("ok",true,"requestId",ID,"completionConfirmed",false));journal.put("actionReceipt:root:capture",start);
            JSONObject read=receipt("phone_screen_capture_read",o("ok",true,"completionConfirmed",true,"uploadRequested",true,"request",o("requestId",ID,"state","observed","completionConfirmed",true)));journal.put("actionReceipt:other:read",read);
            assertFalse(journal.completionSummary("root",name->name.equals("phone_screen_capture"),row->false).optBoolean("ready"));
            journal.put("actionReceipt:root:read",read);JSONObject summary=journal.completionSummary("root",name->name.equals("phone_screen_capture"),row->false);
            assertTrue(summary.toString(),summary.optBoolean("ready"));assertEquals(1,summary.optInt("confirmedMutationCount"));assertFalse(summary.optBoolean("overallOutcomeVerified"));assertFalse(summary.optBoolean("automaticReplay"));assertEquals(start.toString(),journal.get("actionReceipt:root:capture").toString());
        }
    }
    private static JSONObject receipt(String name,JSONObject result)throws Exception{return o("status","completed","name",name,"result",result);}
    private static JSONObject o(Object...pairs)throws Exception{JSONObject x=new JSONObject();for(int i=0;i<pairs.length;i+=2)x.put((String)pairs[i],pairs[i+1]);return x;}
}
