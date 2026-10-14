package com.hoyoung.assistant.runtime;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import com.hoyoung.assistant.phone.PhoneEventJournal;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Synthetic recorder tests verify control/receipt semantics; they do not claim real microphone audio. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public final class VoiceCaptureServiceTest {
    private Context context;
    private HoyoungStore store;
    private PhoneEventJournal journal;
    private ServiceController<VoiceCaptureService> controller;
    private AtomicInteger starts;
    private AtomicInteger stops;
    private AtomicLong backendDuration;
    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        store = new HoyoungStore(context); journal = PhoneEventJournal.bind(context, store);
        journal.refreshPolicyBlocking();
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.RECORD_AUDIO);
        VoiceCaptureService.resetForTests(); starts = new AtomicInteger(); stops = new AtomicInteger(); backendDuration = new AtomicLong();
        synthetic(false, false);
    }
    @After public void tearDown() throws Exception {
        assertTrue("Synthetic capture worker must release before closing storage", VoiceCaptureService.stopAndAwaitForRestore());
        if (controller != null) { controller.destroy(); controller = null; }
        VoiceCaptureService.resetForTests();
        for (String name : new String[]{"closeWorker", "awaitClosedForTests"}) {
            java.lang.reflect.Method method = PhoneEventJournal.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(journal);
        }
        store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
    }

    @Test public void acceptedRequestDoesNotClaimTheMicrophoneStartedOrAudioCompleted() throws Exception {
        JSONObject accepted = VoiceCaptureService.requestStart(context, journal, 0);
        assertTrue(accepted.getBoolean("ok")); assertFalse(accepted.getBoolean("recorderStarted")); assertFalse(accepted.getBoolean("completionConfirmed"));
        assertEquals(0, starts.get());
        JSONObject pending = receipt(accepted.getString("requestId"));
        assertEquals("service_start_requested", pending.getString("state")); assertFalse(pending.getBoolean("completionConfirmed"));
        assertEquals("recording_busy", VoiceCaptureService.requestStart(context, journal, 50_000).getString("code"));
    }
    @Test public void noPermissionOrUserToolDenialStartsNothing() throws Exception {
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.RECORD_AUDIO);
        assertEquals("permission_missing", VoiceCaptureService.requestStart(context, journal, 0).getString("code"));
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.RECORD_AUDIO);
        journal.applyPermissionPolicyFromOwner("{\"phone_voice_record\":false}", true);
        assertEquals("permission_denied", VoiceCaptureService.requestStart(context, journal, 0).getString("code"));
        assertEquals(0, starts.get());
    }
    @Test public void actualAndroidStartFailureIsPersistedWithoutAnApplicationForegroundBan() throws Exception {
        Context denied = new android.content.ContextWrapper(context) {
            @Override public android.content.ComponentName startForegroundService(Intent command) {
                throw new SecurityException("synthetic_android_while_in_use_denial");
            }
        };
        JSONObject response = VoiceCaptureService.requestStart(denied, journal, 0);
        assertEquals("android_service_start_failed", response.getString("code"));
        JSONObject failure = receipt(response.getString("requestId"));
        assertEquals("android_service_start_failed", failure.getString("state")); assertFalse(failure.getBoolean("recorderStarted"));
        assertFalse(failure.getBoolean("completionConfirmed")); assertEquals("SecurityException", failure.getString("failureClass"));
        assertTrue(VoiceCaptureService.requestStart(context, journal, 0).getBoolean("ok"));
    }
    @Test public void positiveDurationBeyondThirtySecondsIsActuallyPassedToCapture() throws Exception {
        String id = begin(3_600_000);
        assertEquals(3_600_000, backendDuration.get()); assertEquals(1, starts.get());
        JSONObject recording = receipt(id);
        assertTrue(recording.getBoolean("recorderStarted")); assertFalse(recording.getBoolean("completionConfirmed"));
        assertEquals(3_600_000, recording.getLong("deadlineAt") - recording.getLong("startedAt"));
        finish(id);
    }
    @Test public void manualRecordingKeepsRunningAfterTheActivityLeavesForeground() throws Exception {
        String id = begin(0);
        android.app.ActivityManager.RunningAppProcessInfo process = new android.app.ActivityManager.RunningAppProcessInfo();
        process.pid = android.os.Process.myPid(); process.uid = android.os.Process.myUid();
        process.importance = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
        Shadows.shadowOf((android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).setProcesses(java.util.Collections.singletonList(process));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(45));
        assertEquals("recording", receipt(id).getString("state")); assertEquals(0, stops.get());
        assertTrue(receipt(id).isNull("deadlineAt")); finish(id);
    }
    @Test public void completedReceiptRequiresFinalFileAndVerifiedEncodedAudio() throws Exception {
        String id = begin(0); finish(id);
        JSONObject result = receipt(id);
        assertEquals("owner_stopped", result.getString("state")); assertTrue(result.getBoolean("completionConfirmed")); assertTrue(result.getBoolean("fileValid"));
        assertFalse(result.getBoolean("binaryUploaded")); assertFalse(result.getBoolean("audioAudibilityVerified"));
        assertFalse(result.getBoolean("requestedDurationCompleted")); assertTrue(result.getString("contentHash").matches("[a-f0-9]{64}"));
        File file = new File(context.getFilesDir(), "projects/" + result.getString("fileId"));
        assertTrue(file.isFile()); assertEquals(file.length(), result.getLong("size")); assertEquals(1, stops.get());
        assertEquals("recording_not_active", VoiceCaptureService.requestStop(journal, id).getString("code"));
    }
    @Test public void recorderStopFailureCannotBeReportedAsCompletedAudio() throws Exception {
        synthetic(true, false); String id = begin(0); finish(id);
        JSONObject result = receipt(id);
        assertEquals("capture_failed", result.getString("state")); assertFalse(result.getBoolean("completionConfirmed")); assertFalse(result.getBoolean("fileValid")); assertFalse(result.has("fileId"));
    }
    @Test public void nonemptyBytesWithoutAnAudioTrackCannotPassValidation() throws Exception {
        synthetic(false, true); String id = begin(0); finish(id);
        JSONObject result = receipt(id);
        assertEquals("capture_failed", result.getString("state")); assertFalse(result.getBoolean("completionConfirmed")); assertFalse(result.has("fileId")); assertTrue(result.getBoolean("partialFileRetained"));
    }
    @Test public void stopBeforeServiceStartsNeverSwitchesOnTheMicrophone() throws Exception {
        JSONObject accepted = VoiceCaptureService.requestStart(context, journal, 0); String id = accepted.getString("requestId");
        assertEquals("stop_requested", VoiceCaptureService.requestStop(journal, id).getString("code"));
        startPending(); awaitTerminal(id);
        assertEquals("cancelled_before_recording", receipt(id).getString("state")); assertEquals(0, starts.get());
    }
    @Test public void processLossMarksPendingCommandUnknownAndNeverRepeatsIt() throws Exception {
        JSONObject accepted = VoiceCaptureService.requestStart(context, journal, 0); String id = accepted.getString("requestId");
        VoiceCaptureService.resetForTests();
        JSONObject result = receipt(id);
        assertEquals("outcome_unknown_after_process_restart", result.getString("state")); assertFalse(result.getBoolean("completionConfirmed")); assertFalse(result.getBoolean("automaticReplay"));
        assertEquals("recording_not_active", VoiceCaptureService.requestStop(journal, id).getString("code")); assertEquals(0, starts.get());
    }
    @Test public void killedServiceIsNotStickyAndCanOnlyFinalizeAnActuallyStoppedCapture() throws Exception {
        String id = begin(0); controller.destroy(); controller = null; awaitTerminal(id);
        assertEquals("service_stopped", receipt(id).getString("state")); assertFalse(receipt(id).getBoolean("requestedDurationCompleted"));
        assertTrue(receipt(id).getBoolean("completionConfirmed"));
    }
    @Test public void revokingTheOwnersRecordingSwitchActuallyStopsCurrentCapture() throws Exception {
        String id = begin(0); journal.applyPermissionPolicyFromOwner("{\"phone_voice_record\":false}", true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); awaitTerminal(id);
        assertEquals("permission_or_tool_revoked", receipt(id).getString("state")); assertEquals(1, stops.get());
    }
    @Test public void repeatedUnknownReadRetainsTheSameInterruptionRecord() throws Exception {
        String id = "a1111111-a111-a111-a111-a11111111111";
        journal.put("voice.request." + id, "{\"requestId\":\"" + id + "\",\"state\":\"recording\",\"recorderStarted\":true,\"completionConfirmed\":false}");
        JSONObject first = receipt(id); JSONObject second = receipt(id);
        assertEquals(first.toString(), second.toString()); assertEquals("recording", first.getString("previousState"));
    }
    @Test public void backupRestoreWaitsForActualRecorderReleaseAndSavedTerminalReceipt() throws Exception {
        String id = begin(0);
        assertTrue(VoiceCaptureService.stopAndAwaitForRestore());
        JSONObject stopped = receipt(id);
        assertEquals("backup_restore", stopped.getString("state")); assertTrue(stopped.getBoolean("completionConfirmed")); assertEquals(1, stops.get());
    }

    private void synthetic(boolean stopFailure, boolean validationFailure) {
        VoiceCaptureService.testBackend((app, file, duration, callback) -> {
            backendDuration.set(duration);
            return new VoiceCaptureService.Capture() {
                public void start() throws Exception { starts.incrementAndGet(); Files.write(file.toPath(), "synthetic-control-test-only".getBytes(StandardCharsets.UTF_8)); }
                public void stop() throws Exception { stops.incrementAndGet(); if (stopFailure) throw new java.io.IOException("synthetic_stop_failure"); }
                public void release() { }
            };
        }, file -> {
            if (validationFailure) throw new java.io.IOException("synthetic_no_audio_track");
            return new JSONObject().put("syntheticControlFixture", true).put("encodedAudioTrackVerified", true);
        });
    }
    private String begin(long duration) throws Exception {
        JSONObject accepted = VoiceCaptureService.requestStart(context, journal, duration);
        assertTrue(accepted.toString(), accepted.getBoolean("ok")); String id = accepted.getString("requestId"); startPending();
        awaitState(id, "recording"); return id;
    }
    private void startPending() {
        Intent command = Shadows.shadowOf((Application) context).getNextStartedService(); assertNotNull(command);
        controller = Robolectric.buildService(VoiceCaptureService.class).create();
        assertEquals(android.app.Service.START_NOT_STICKY, controller.get().onStartCommand(command, 0, 1));
    }
    private void finish(String id) throws Exception {
        assertEquals("stop_requested", VoiceCaptureService.requestStop(journal, id).getString("code")); awaitTerminal(id);
    }
    private JSONObject receipt(String id) throws Exception { return VoiceCaptureService.readReceipt(journal, id).getJSONObject("request"); }
    private void awaitState(String id, String desired) throws Exception {
        long until = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < until) { if (desired.equals(receipt(id).optString("state"))) return; Thread.sleep(10); }
        fail("Expected " + desired + ": " + receipt(id));
    }
    private void awaitTerminal(String id) throws Exception {
        long until = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < until) {
            String state = receipt(id).optString("state");
            if (!"recording".equals(state) && !"service_start_requested".equals(state)) { Shadows.shadowOf(Looper.getMainLooper()).idle(); return; }
            Thread.sleep(10);
        }
        fail("Capture did not finish: " + receipt(id));
    }
}
