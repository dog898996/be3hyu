package com.hoyoung.assistant.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Native callback evidence for an earlier asynchronous request in the same root.
 * Does not rewrite the original accepted receipt or certify external/model outcomes. */
final class NativeAsyncCompletion {
    private final Set<String> confirmed = new HashSet<>();
    void observe(JSONObject receipt) {
        if (!"completed".equals(receipt.optString("status"))) return;
        JSONObject result=receipt.optJSONObject("result");
        if(result==null||!Boolean.TRUE.equals(result.opt("ok")))return;
        String name=receipt.optString("name");JSONObject request=result.optJSONObject("request");
        if("phone_alarm_list".equals(name)) {
            JSONArray alarms=result.optJSONArray("alarms");
            for(int i=0;alarms!=null&&i<alarms.length();i++) {
                JSONObject alarm=alarms.optJSONObject(i);
                if(alarm!=null&&alarm.optBoolean("receiverCallbackObserved")&&alarm.optBoolean("completionConfirmed")&&alarm.optLong("firedAt")>0)
                    add("phone_alarm_schedule",alarm.optString("alarmId"));
            }
            return;
        }
        if(request==null||!Boolean.TRUE.equals(request.opt("completionConfirmed")))return;
        String id=request.optString("requestId"),state=request.optString("state");
        switch(name) {
            case "phone_screen_capture_read":
                if(result.optBoolean("completionConfirmed")&&result.optBoolean("uploadRequested")&&"observed".equals(state))add("phone_screen_capture",id);break;
            case "phone_voice_record_read":
                if(request.optBoolean("fileValid")&&!request.optString("fileId").isEmpty()) {add("phone_voice_record",id);add("phone_voice_record_stop",id);}break;
            case "phone_sms_send_read":
                if(result.optBoolean("completionConfirmed")&&request.optBoolean("deliveryConfirmed"))add("phone_sms_send",id);break;
            case "phone_location_refresh_read":
                if("observed".equals(state))add("phone_location_refresh",id);break;
            case "phone_bluetooth_scan_read":
                if(request.optBoolean("scanEndedVerified")){add("phone_bluetooth_scan",id);add("phone_bluetooth_scan_cancel",id);}break;
            case "phone_wifi_hotspot_read":
                if("active".equals(state)&&request.optLong("startedCallbackAt")>0)add("phone_wifi_hotspot_start",id);
                if("stopped".equals(state)) {add("phone_wifi_hotspot_stop",id);if(request.optLong("startedCallbackAt")>0)add("phone_wifi_hotspot_start",id);}break;
            default: break;
        }
    }
    boolean confirms(JSONObject receipt) {
        JSONObject result=receipt.optJSONObject("result");if(result==null||!Boolean.TRUE.equals(result.opt("ok")))return false;
        String name=receipt.optString("name"),id=result.optString("requestId");
        // A terminal callback cannot turn a later no-op/foreign-process stop into
        // a mutation performed by this action. Keep actual request acceptance.
        if("phone_voice_record_stop".equals(name)&&!"stop_requested".equals(result.optString("code")))return false;
        if("phone_bluetooth_scan_cancel".equals(name)&&!"bluetooth_cancel_requested".equals(result.optString("code")))return false;
        if("phone_wifi_hotspot_stop".equals(name)&&(!"hotspot_stop_requested".equals(result.optString("code"))||!Boolean.TRUE.equals(result.opt("stopRequested"))))return false;
        if("phone_alarm_schedule".equals(name)){JSONObject alarm=result.optJSONObject("alarm");id=alarm==null?"":alarm.optString("alarmId");}
        return !id.isEmpty()&&confirmed.contains(name+":"+id);
    }
    private void add(String name,String id){if(id.matches("[a-f0-9-]{36}"))confirmed.add(name+":"+id);}
}
