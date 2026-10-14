package com.hoyoung.assistant.runtime;

import org.json.JSONObject;
import java.util.UUID;

/** Visible-screen selection only. Accepted work keeps its own durable copy. */
public final class ForegroundRoutingSession {
    private String id = "", model = "", effort = "", speed = "";
    private long revision;

    public synchronized void enter() {
        if (id.isEmpty()) { id = UUID.randomUUID().toString(); revision++; }
    }
    public synchronized void leave() { id = ""; model = ""; effort = ""; speed = ""; revision++; }
    public synchronized void resetSelection(){if(!id.isEmpty())id=UUID.randomUUID().toString();model="";effort="";speed="";revision++;}
    public synchronized JSONObject snapshot() {
        return json("sessionId", id, "revision", revision, "visible", !id.isEmpty(),
                "selected", !effort.isEmpty(), "model", model, "effort", effort, "speed", speed);
    }
    public synchronized JSONObject select(String expectedId, long expectedRevision, String model, String effort, boolean reset) {
        return select(expectedId, expectedRevision, model, effort, speed.isEmpty() ? "fast" : speed, reset);
    }
    public synchronized JSONObject select(String expectedId, long expectedRevision, String model, String effort, String speed, boolean reset) {
        if (id.isEmpty() || !id.equals(expectedId) || revision != expectedRevision)
            return json("ok", false, "code", "screen_selection_changed", "message", "화면을 떠났거나 더 최근 선택이 있어. 현재 선택을 다시 확인해 줘.");
        if (!reset) AssistantRuntime.validateModelPreferences(model, effort, speed);
        this.model = reset ? "" : model; this.effort = reset ? "" : effort; this.speed = reset ? "" : speed; revision++;
        JSONObject result = snapshot(); put(result, "ok", true); return result;
    }
    static JSONObject json(Object... fields) {
        JSONObject value = new JSONObject(); for (int i=0;i<fields.length;i+=2) put(value,(String)fields[i],fields[i+1]); return value;
    }
    static void put(JSONObject value,String key,Object item) {
        try { value.put(key,item); } catch (org.json.JSONException failure) { throw new IllegalStateException(failure); }
    }
}
