package com.hoyoung.assistant.runtime;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.Assert.*;

/** Standalone JVM tests use the same persisted settings state across policy instances. */
public final class AutonomyTickPolicyTest {
    private static final long START=1_000_000L, PERIOD=300_000L;
    private static final class Durable implements AutonomyTickPolicy.Backend {
        Map<String,String> settings=new HashMap<>(); final List<JSONObject> events=new ArrayList<>(); boolean failWrite;
        public synchronized <T> T atomic(Supplier<T> action) {
            Map<String,String> before=new HashMap<>(settings);int count=events.size();
            try{return action.get();}catch(RuntimeException e){settings=before;while(events.size()>count)events.remove(events.size()-1);throw e;}
        }
        public String read(String k,String fallback){return settings.getOrDefault(k,fallback);}
        public void write(String k,String v){if(failWrite)throw new IllegalStateException("synthetic disk failure");settings.put(k,v);}
        public void event(JSONObject e){events.add(e);}
    }
    private static AutonomyTickPolicy.Decision poll(AutonomyTickPolicy p,long now){return p.poll(now,true,false,false,false,true,false);}
    @Test public void defaultJudgesWithoutEventsAndCannotClaimNoActionBeforeDispatch() {
        Durable db=new Durable();AutonomyTickPolicy p=new AutonomyTickPolicy(db);
        AutonomyTickPolicy.Decision d=poll(p,START);assertTrue(d.shouldDispatch);assertEquals("reserved",d.outcome);
        assertFalse(db.events.get(0).optBoolean("actualModelDispatch"));
        assertThrows(IllegalStateException.class,()->p.complete(d.tickId,true,START+1));
        assertTrue(p.markDispatched(d.tickId,START+2));assertFalse(p.markDispatched(d.tickId,START+3));
        assertTrue(p.complete(d.tickId,true,START+4));assertFalse(p.complete(d.tickId,true,START+5));
        assertEquals("no_action",p.snapshot(START+5).optString("lastOutcome"));
        assertFalse(poll(p,START+PERIOD-1).shouldDispatch);assertTrue(poll(p,START+PERIOD).shouldDispatch);
    }
    @Test public void sharedActivityServicePoliciesHaveExactlyOneClaim() throws Exception {
        Durable db=new Durable();AtomicInteger claims=new AtomicInteger();CountDownLatch start=new CountDownLatch(1);List<Thread> threads=new ArrayList<>();
        for(int i=0;i<16;i++){Thread t=new Thread(()->{try{start.await();if(poll(new AutonomyTickPolicy(db),START).shouldDispatch)claims.incrementAndGet();}catch(InterruptedException e){throw new AssertionError(e);}});threads.add(t);t.start();}
        start.countDown();for(Thread t:threads)t.join();assertEquals(1,claims.get());assertEquals(1,db.events.size());
    }
    @Test public void sevenDaysWithoutEventsKeep2016JudgmentSlotsAcrossRestarts() {
        // Scheduling replay with simulated admission/completion receipts, not live AI inference.
        Durable db=new Durable();AutonomyTickPolicy policy=new AutonomyTickPolicy(db);
        java.util.Set<String> identities=new java.util.HashSet<>();
        for(int slot=0;slot<7*288;slot++) {
            if(slot%97==0)policy=new AutonomyTickPolicy(db);
            long now=START+slot*PERIOD;
            AutonomyTickPolicy.Decision due=poll(policy,now);
            assertTrue("Every no-change slot still requires a judgment: "+slot,due.shouldDispatch);
            assertTrue(identities.add(due.tickId));
            assertTrue(policy.markDispatched(due.tickId,now+1));
            assertTrue(policy.complete(due.tickId,true,now+2));
            assertFalse("A repeated local pulse cannot repeat the submitted task",poll(policy,now+3).shouldDispatch);
        }
        assertEquals(2016,identities.size());assertEquals(2016*3,db.events.size());
        assertEquals("no_action",policy.snapshot(START+2015*PERIOD+4).optString("lastOutcome"));
    }
    @Test public void ownerControlAndBusyCoalesceInsteadOfQueueingBehindOwner() {
        Durable db=new Durable();AutonomyTickPolicy p=new AutonomyTickPolicy(db);
        AutonomyTickPolicy.Decision owner=p.poll(START,true,true,false,true,false,false);
        assertFalse(owner.shouldDispatch);assertEquals("coalesced_busy",owner.outcome);assertEquals("owner_or_control_first",owner.reason);
        assertEquals("not_due",poll(p,START+1).outcome);
        assertEquals("coalesced_busy",p.poll(START+PERIOD,true,false,true,false,true,true).outcome);
        assertTrue(poll(p,START+2*PERIOD).shouldDispatch);
    }
    @Test public void quotaAndMemoryBlocksArePersistedAtEachDueSlotNotReplayed() {
        Durable db=new Durable();AutonomyTickPolicy p=new AutonomyTickPolicy(db);
        assertEquals("blocked_quota",p.poll(START,true,false,false,true,true,false).outcome);
        assertEquals("blocked_memory",p.poll(START+PERIOD,true,false,false,false,false,false).outcome);
        assertFalse(poll(p,START+PERIOD+1).shouldDispatch);assertTrue(poll(p,START+2*PERIOD).shouldDispatch);
    }
    @Test public void outageIsOneAggregateAndAtMostOneCurrentJudgment() {
        Durable db=new Durable();AutonomyTickPolicy p=new AutonomyTickPolicy(db);AutonomyTickPolicy.Decision initial=poll(p,START);
        p.markDispatched(initial.tickId,START+1);p.complete(initial.tickId,true,START+2);
        p=new AutonomyTickPolicy(db);AutonomyTickPolicy.Decision recovered=poll(p,START+100*PERIOD+123);
        assertEquals(99,recovered.missedOutageCount);assertEquals(START+100*PERIOD,recovered.dueAt);assertTrue(recovered.shouldDispatch);
        assertEquals(5,db.events.size()); // reservation, admission, completion, aggregate, current reservation.
        assertEquals(99,db.events.get(3).optLong("count"));assertEquals("missed_outage",db.events.get(3).optString("outcome"));
        assertFalse(poll(p,START+100*PERIOD+124).shouldDispatch);
    }
    @Test public void crashClaimSurvivesRestartAndNeedsExplicitReconciliation() {
        Durable db=new Durable();AutonomyTickPolicy p=new AutonomyTickPolicy(db);AutonomyTickPolicy.Decision d=poll(p,START);
        AutonomyTickPolicy reopened=new AutonomyTickPolicy(db);
        assertEquals(d.tickId,reopened.snapshot(START+1).optString("inFlightTickId"));
        assertEquals("coalesced_busy",poll(reopened,START+PERIOD).outcome);
        assertTrue(reopened.abandonAfterRestart(d.tickId,START+PERIOD+1));assertFalse(reopened.abandonAfterRestart(d.tickId,START+PERIOD+2));
        assertFalse(poll(reopened,START+PERIOD+3).shouldDispatch);assertTrue(poll(reopened,START+2*PERIOD).shouldDispatch);
    }
    @Test public void eventDrivenRequiresOwnerSelectionAndDoesNotFalselyRecordJudgment() {
        Durable db=new Durable();AutonomyTickPolicy p=new AutonomyTickPolicy(db);
        assertThrows(IllegalArgumentException.class,()->p.selectMode("event_driven",false));
        p.selectMode("event_driven",true);AutonomyTickPolicy.Decision d=poll(p,START);
        assertEquals("skipped_no_event",d.outcome);assertFalse(d.shouldDispatch);assertFalse(db.events.get(0).optBoolean("actualModelDispatch"));
        assertTrue(p.poll(START+PERIOD,true,false,false,false,true,true).shouldDispatch);
    }
    @Test public void ownerStopIsNotAnOutageAndReenableDoesNotReplayOldSlots() {
        Durable db=new Durable();AutonomyTickPolicy p=new AutonomyTickPolicy(db);
        assertEquals("disabled",p.poll(START,false,false,false,false,true,false).outcome);
        assertEquals("disabled",p.poll(START+100*PERIOD,false,false,false,false,true,false).outcome);
        AutonomyTickPolicy.Decision d=poll(p,START+100*PERIOD+1);assertTrue(d.shouldDispatch);assertEquals(0,d.missedOutageCount);
    }
    @Test public void storageFailureRollsBackClaimAndNeverReturnsDispatchPermit() {
        Durable db=new Durable();AutonomyTickPolicy p=new AutonomyTickPolicy(db);db.failWrite=true;
        assertThrows(IllegalStateException.class,()->poll(p,START));assertTrue(db.events.isEmpty());assertTrue(db.settings.isEmpty());
        db.failWrite=false;assertTrue(poll(p,START).shouldDispatch);
    }
    @Test public void sameClockStopStartCannotReusePreviouslyCompletedTickId() {
        Durable db=new Durable();AutonomyTickPolicy p=new AutonomyTickPolicy(db);AutonomyTickPolicy.Decision d=poll(p,START);
        p.markDispatched(d.tickId,START);p.complete(d.tickId,false,START);
        assertTrue(db.events.get(1).optBoolean("actualModelDispatch"));
        assertFalse(db.events.get(2).optBoolean("actualModelDispatch"));assertTrue(db.events.get(2).optBoolean("actualModelJudgmentCompleted"));
        p.poll(START,false,false,false,false,true,false);
        assertFalse(poll(p,START).shouldDispatch);
        assertNotEquals(d.tickId,poll(p,START+PERIOD).tickId);
    }
    @Test public void malformedDurableStateAndInvalidClockDoNotSilentlyReset() {
        Durable db=new Durable();AutonomyTickPolicy p=new AutonomyTickPolicy(db);
        assertThrows(IllegalArgumentException.class,()->poll(p,-1));db.settings.put(AutonomyTickPolicy.STATE_KEY,"{}");
        assertThrows(IllegalStateException.class,()->poll(p,START));assertEquals("{}",db.settings.get(AutonomyTickPolicy.STATE_KEY));
    }
    @Test public void backwardClockDoesNotCreateDuplicateAndBlockedReservationReleasesSlot() {
        Durable db=new Durable();AutonomyTickPolicy p=new AutonomyTickPolicy(db);AutonomyTickPolicy.Decision d=poll(p,START);
        assertTrue(p.block(d.tickId,"blocked_memory",START+1));assertFalse(poll(p,START-1).shouldDispatch);
        assertTrue(poll(p,START+PERIOD).shouldDispatch);
    }
}
