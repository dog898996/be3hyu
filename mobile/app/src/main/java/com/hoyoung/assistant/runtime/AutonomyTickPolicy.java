package com.hoyoung.assistant.runtime;

import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.function.Supplier;

/** Durable, single-slot inference cadence. This reserves work; it never invokes a model. */
public final class AutonomyTickPolicy {
    public static final long DEFAULT_INTERVAL_MS = 300_000L;
    public static final String STATE_KEY = "autonomy.tick.state.v1";
    public static final String MODE_KEY = "autonomy.tick.mode";
    private final Backend backend;

    /** The transaction serializes callers sharing the database, including Activity/FGS pulses. */
    public AutonomyTickPolicy(HoyoungStore store) {
        if (store == null) throw new IllegalArgumentException("Store required");
        backend = new Backend() {
            public <T> T atomic(Supplier<T> action) { return store.withTransaction(db -> action.get()); }
            public String read(String key, String fallback) { return store.getSetting(key, fallback); }
            public void write(String key, String value) { store.putSetting(key, value); }
            public void event(JSONObject event) { store.recordEvent("autonomy_tick", event); }
        };
    }
    // In-memory backend tests verify policy independently of Android. Production uses the constructor above.
    AutonomyTickPolicy(Backend backend) {
        if (backend == null) throw new IllegalArgumentException("Backend required");
        this.backend = backend;
    }
    interface Backend {
        <T> T atomic(Supplier<T> action);
        String read(String key, String fallback);
        void write(String key, String value);
        void event(JSONObject event);
    }

    public static final class Decision {
        public final String tickId, outcome, reason;
        public final long dueAt, nextDueAt, missedOutageCount;
        public final boolean shouldDispatch;
        private Decision(String id, long due, long next, boolean dispatch, String outcome, long missed, String reason) {
            tickId=id; dueAt=due; nextDueAt=next; shouldDispatch=dispatch;
            this.outcome=outcome; missedOutageCount=missed; this.reason=reason;
        }
    }

    /** Quiet notification hours are deliberately not an input: they do not stop judgment. */
    public Decision poll(long nowMillis, boolean enabled, boolean ownerOrControlPending,
                         boolean busy, boolean quotaBlocked, boolean memoryReady, boolean hasNewEvents) {
        clock(nowMillis);
        return backend.atomic(() -> {
            JSONObject state = state(nowMillis);
            long interval = interval();
            long next = number(state, "nextDueAt");
            if (!enabled) {
                // Owner stop is not an outage, and never releases a previous in-flight claim.
                put(state,"enabled",false); put(state,"nextDueAt",Math.max(sum(nowMillis,interval), afterLastDue(state,interval,0))); save(state);
                return decision("",0,number(state,"nextDueAt"),false,"disabled",0,"owner_disabled");
            }
            if (!state.optBoolean("enabled",true)) {
                put(state,"enabled",true); next=Math.max(nowMillis,afterLastDue(state,interval,0)); put(state,"nextDueAt",next);
            }
            if (nowMillis < next) {
                save(state);
                return decision("",0,next,false,"not_due",0,"cadence");
            }
            long missed = (nowMillis-next)/interval;
            long due = next + missed*interval; // Product is bounded by now-next.
            long following = sum(due,interval);
            String id = "inference-"+due;
            if (missed>0) {
                emit("inference-"+next,"missed_outage",nowMillis,"no_catchup_replay",missed,next,due-interval);
                put(state,"missedOutageTotal",sum(number(state,"missedOutageTotal"),missed));
            }
            String outcome, reason; boolean dispatch=false;
            if (ownerOrControlPending) { outcome="coalesced_busy"; reason="owner_or_control_first"; }
            else if (busy || !state.optString("inFlightTickId","").isEmpty()) { outcome="coalesced_busy"; reason="work_in_flight"; }
            else if (quotaBlocked) { outcome="blocked_quota"; reason="quota_exhausted"; }
            else if (!memoryReady) { outcome="blocked_memory"; reason="memory_not_ready"; }
            else if ("event_driven".equals(mode()) && !hasNewEvents) { outcome="skipped_no_event"; reason="owner_selected_event_driven"; }
            else { outcome="reserved"; reason="inference_due"; dispatch=true; put(state,"inFlightTickId",id); put(state,"inFlightStatus","reserved"); }
            put(state,"lastTickId",id); put(state,"lastDueAt",due); put(state,"nextDueAt",following);
            put(state,"lastOutcome",outcome); put(state,"lastObservedAt",nowMillis);
            emit(id,outcome,nowMillis,reason,1,due,due); save(state);
            return decision(id,due,following,dispatch,outcome,missed,reason);
        });
    }

    /** Invoke only after actual model dispatch acceptance, never merely after enqueueing. */
    public boolean markDispatched(String tickId, long nowMillis) {
        return transition(tickId,nowMillis,"executed",false,true,"model_dispatch_accepted");
    }
    /** noAction is meaningful only after an admitted model judgment actually completed. */
    public boolean complete(String tickId, boolean noAction, long nowMillis) {
        return transition(tickId,nowMillis,noAction?"no_action":"executed",true,false,"model_judgment_completed");
    }
    public boolean block(String tickId, String outcome, long nowMillis) {
        if (!"blocked_quota".equals(outcome) && !"blocked_memory".equals(outcome))
            throw new IllegalArgumentException("Only explicit quota/memory blocks are supported");
        return transition(tickId,nowMillis,outcome,true,true,"dispatch_blocked");
    }
    /** Call after runtime request reconciliation proves the old claim cannot resume; never retry it. */
    public boolean abandonAfterRestart(String tickId, long nowMillis) {
        return transition(tickId,nowMillis,"missed_outage",true,true,"restart_reconciled_no_replay");
    }
    /** Explicit owner-selection API; historical model/notification text must not call this. */
    public void selectMode(String mode, boolean ownerConfirmed) {
        if (!ownerConfirmed) throw new IllegalArgumentException("Explicit owner selection required");
        if (!"inference_tick".equals(mode) && !"event_driven".equals(mode)) throw new IllegalArgumentException("Invalid tick mode");
        backend.atomic(() -> { backend.write(MODE_KEY,mode); return null; });
    }
    public JSONObject snapshot(long nowMillis) {
        clock(nowMillis); return backend.atomic(() -> copy(state(nowMillis)));
    }
    private boolean transition(String id,long now,String outcome,boolean release,boolean allowReserved,String reason) {
        clock(now);
        if (id==null || id.isEmpty()) throw new IllegalArgumentException("Tick ID required");
        return backend.atomic(() -> {
            JSONObject s=state(now);
            if (!id.equals(s.optString("inFlightTickId",""))) return false;
            String previous=s.optString("inFlightStatus","");
            if ("executed".equals(outcome) && !release && "executed".equals(previous)) return false;
            if (!allowReserved && !"executed".equals(previous)) throw new IllegalStateException("No completed judgment without actual dispatch");
            if (release) { put(s,"inFlightTickId",""); put(s,"inFlightStatus",""); }
            else put(s,"inFlightStatus",outcome);
            put(s,"lastOutcome",outcome); put(s,"lastObservedAt",now);
            if ("missed_outage".equals(outcome)) put(s,"missedOutageTotal",sum(number(s,"missedOutageTotal"),1));
            emit(id,outcome,now,reason,1,0,0); save(s); return true;
        });
    }
    private String mode() {
        String raw=backend.read(MODE_KEY,"inference_tick");
        if (!"inference_tick".equals(raw) && !"event_driven".equals(raw)) throw new IllegalStateException("Malformed durable autonomy mode");
        return raw;
    }
    private long interval() {
        String raw=backend.read("heartbeatSeconds","300");
        try { long seconds=Long.parseLong(raw); if(seconds<60 || seconds>86400) throw new IllegalArgumentException("Tick interval outside owner bounds"); return seconds*1000; }
        catch(NumberFormatException invalid) { throw new IllegalStateException("Malformed heartbeat interval",invalid); }
    }
    private JSONObject state(long now) {
        try {
            String raw=backend.read(STATE_KEY,null);
            if(raw!=null) {
                JSONObject s=new JSONObject(raw);
                if(s.getInt("version")!=1) throw new IllegalStateException("Unsupported autonomy state version");
                number(s,"nextDueAt"); number(s,"missedOutageTotal");
                if(!s.has("inFlightTickId") || !s.has("inFlightStatus")) throw new IllegalStateException("Incomplete autonomy state");
                return s;
            }
            JSONObject s=new JSONObject(); put(s,"version",1); put(s,"nextDueAt",now); put(s,"missedOutageTotal",0L);
            put(s,"inFlightTickId","");put(s,"inFlightStatus","");put(s,"enabled",true); return s;
        } catch(JSONException invalid) { throw new IllegalStateException("Malformed durable autonomy state",invalid); }
    }
    private void emit(String id,String outcome,long now,String reason,long count,long first,long last) {
        JSONObject e=new JSONObject(); put(e,"tickId",id);put(e,"outcome",outcome);put(e,"observedAt",now);
        put(e,"reason",reason);put(e,"count",count);put(e,"firstDueAt",first);put(e,"lastDueAt",last);
        put(e,"actualModelDispatch", "model_dispatch_accepted".equals(reason));
        put(e,"actualModelJudgmentCompleted", "model_judgment_completed".equals(reason)); backend.event(e);
    }
    private void save(JSONObject s) { backend.write(STATE_KEY,s.toString()); }
    private static Decision decision(String id,long due,long next,boolean dispatch,String outcome,long missed,String reason) { return new Decision(id,due,next,dispatch,outcome,missed,reason); }
    private static JSONObject copy(JSONObject s) { try {return new JSONObject(s.toString());}catch(JSONException e){throw new IllegalStateException(e);} }
    private static void put(JSONObject s,String k,Object v) { try{s.put(k,v);}catch(JSONException e){throw new IllegalStateException(e);} }
    private static long afterLastDue(JSONObject s,long interval,long fallback) { return s.has("lastDueAt") ? sum(number(s,"lastDueAt"),interval) : fallback; }
    private static long number(JSONObject s,String k) { try{long n=s.getLong(k);clock(n);return n;}catch(JSONException e){throw new IllegalStateException("Malformed autonomy counter "+k,e);} }
    private static void clock(long n) { if(n<0)throw new IllegalArgumentException("Nonnegative clock/counter required"); }
    private static long sum(long a,long b) { try{return Math.addExact(a,b);}catch(ArithmeticException e){throw new IllegalArgumentException("Tick timestamp/counter overflow",e);} }
}
