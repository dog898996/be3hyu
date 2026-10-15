package com.hoyoung.assistant.runtime;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import com.hoyoung.assistant.phone.PermissionCatalog;
import com.hoyoung.assistant.phone.PhoneEventJournal;
import com.hoyoung.assistant.store.AssistantIdentity;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Requested microphone FGS. Android, rather than an application foreground-only rule, enforces start eligibility. */
public final class VoiceCaptureService extends Service {
    public static final String ACTION_START = "com.hoyoung.assistant.START_VOICE_CAPTURE";
    public static final String ACTION_STOP = "com.hoyoung.assistant.STOP_VOICE_CAPTURE";
    private static final String CHANNEL = "hoyoung_voice_capture";
    private static final int NOTIFICATION_ID = 2424;
    private static final Object CONTROL = new Object();
    private static VoiceCaptureService live;
    private static String pendingId;
    private static PhoneEventJournal pendingJournal;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "hoyoung-voice-capture"));
    private PhoneEventJournal journal;
    private volatile String activeId;
    private volatile boolean destroyed;
    private Capture recorder;
    private File partial;
    private File finished;
    private PowerManager.WakeLock wakeLock;
    private long durationMs;
    private long requestedWall;
    private long startedElapsed;
    private long startedWall;
    private int commandStartId;
    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            if (activeId == null || destroyed) return;
            if (!new PermissionCatalog(VoiceCaptureService.this).has(Manifest.permission.RECORD_AUDIO)
                    || !journal.toolAllowed("phone_voice_record")) enqueueFinish("permission_or_tool_revoked");
            else if (durationMs > 0 && startedWall > 0 && SystemClock.elapsedRealtime() - startedElapsed >= durationMs)
                enqueueFinish("duration_completed");
            else main.postDelayed(this, 1_000L);
        }
    };

    public static JSONObject requestStart(Context context, PhoneEventJournal journal, long durationMs) {
        if (durationMs < 0) throw new IllegalArgumentException("durationMs must be nonnegative");
        if (!new PermissionCatalog(context).has(Manifest.permission.RECORD_AUDIO)) return error("permission_missing", "permissionId", "microphone");
        if (!journal.toolAllowed("phone_voice_record")) return error("permission_denied", "tool", "phone_voice_record");
        String id = UUID.randomUUID().toString();
        synchronized (CONTROL) {
            if (pendingId != null || live != null && live.activeId != null) return error("recording_busy");
            Receipt receipt = base(id, "service_start_requested").putSafe("durationMs", durationMs);
            // Persist before microphone I/O. put() records failures separately; verify this particular state too.
            if (!persist(journal, id, receipt)) return error("journal_unavailable");
            pendingId = id; pendingJournal = journal;
            Intent command = new Intent(context, VoiceCaptureService.class).setAction(ACTION_START).putExtra("requestId", id);
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(command); else context.startService(command);
            } catch (RuntimeException denied) {
                pendingId = null; pendingJournal = null;
                Receipt failed = base(id, "android_service_start_failed").putSafe("failureClass", denied.getClass().getSimpleName())
                        .putSafe("androidStartEligibility", "foreground_service_and_while_in_use_microphone_rules")
                        .putSafe("completedAt", System.currentTimeMillis());
                persist(journal, id, failed); event(journal, id, failed);
                return error("android_service_start_failed", "requestId", id, "failureClass", denied.getClass().getSimpleName());
            }
        }
        return PhoneEventJournal.object("ok", true, "code", "recording_request_accepted", "requestId", id,
                "durationMs", durationMs, "manualStop", durationMs == 0, "recorderStarted", false,
                "completionConfirmed", false, "receiptMustBeRead", true, "localOnly", true);
    }

    public static JSONObject requestStop(PhoneEventJournal journal, String id) throws JSONException {
        JSONObject found = readReceipt(journal, id);
        if (!found.optBoolean("ok")) return found;
        JSONObject receipt = found.getJSONObject("request");
        synchronized (CONTROL) {
            if (live != null && id.equals(live.activeId)) {
                live.enqueueFinish("owner_stopped");
                return PhoneEventJournal.object("ok", true, "code", "stop_requested", "requestId", id, "completionConfirmed", false, "receiptMustBeRead", true);
            }
            if (id.equals(pendingId)) {
                cancelPending("owner_stopped");
                return PhoneEventJournal.object("ok", true, "code", "stop_requested", "requestId", id, "completionConfirmed", false, "receiptMustBeRead", true);
            }
        }
        return PhoneEventJournal.object("ok", true, "code", "recording_not_active", "requestId", id, "request", receipt, "automaticReplay", false);
    }

    /** Cancel an already active command, including before backup restore; never starts a service. */
    public static void stopActive(String reason) {
        synchronized (CONTROL) { cancelPending(reason); if (live != null && live.activeId != null) live.enqueueFinish(reason); }
    }

    /** Restore worker only: release/finalize microphone I/O before replacing projects or pausing the journal. */
    public static boolean stopAndAwaitForRestore() {
        Future<?> stopped;
        synchronized (CONTROL) {
            cancelPending("backup_restore");
            if (live == null || live.activeId == null) return true;
            VoiceCaptureService instance = live;
            try { stopped = instance.worker.submit(() -> instance.finish("backup_restore")); }
            catch (RejectedExecutionException unavailable) { return false; }
        }
        try { stopped.get(30, TimeUnit.SECONDS); return true; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (Exception failedOrTimedOut) { return false; }
    }

    private static void cancelPending(String reason) {
        if (pendingId == null || pendingJournal == null) return;
        String id = pendingId;
        JSONObject receipt;
        try { receipt = new JSONObject(pendingJournal.get(key(id), "{}")); }
        catch (JSONException invalid) { receipt = base(id, "cancelled_before_recording"); }
        try { receipt.put("state", "cancelled_before_recording").put("stopReason", reason).put("completedAt", System.currentTimeMillis())
                .put("recorderStarted", false).put("completionConfirmed", false).put("automaticReplay", false); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        persist(pendingJournal, id, receipt); event(pendingJournal, id, receipt);
        pendingId = null; pendingJournal = null;
    }

    public static JSONObject readReceipt(PhoneEventJournal journal, String id) throws JSONException {
        String value = journal.get(key(id), "");
        if (value.isEmpty()) return error("target_not_found");
        JSONObject receipt = new JSONObject(value);
        synchronized (CONTROL) {
            if (incomplete(receipt.optString("state")) && !id.equals(pendingId) && (live == null || !id.equals(live.activeId))) {
                receipt.put("previousState", receipt.optString("state")); receipt.put("state", "outcome_unknown_after_process_restart");
                receipt.put("completionConfirmed", false); receipt.put("automaticReplay", false);
                receipt.put("interruptionDetectedAt", System.currentTimeMillis()); persist(journal, id, receipt); event(journal, id, receipt);
            }
        }
        return PhoneEventJournal.object("ok", true, "code", "ok", "request", receipt);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { if (activeId == null) stopSelf(startId); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(intent.getAction())) {
            if (intent.getStringExtra("requestId") != null && intent.getStringExtra("requestId").equals(activeId)) enqueueFinish("owner_stopped");
            else if (activeId == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        String id = intent.getStringExtra("requestId");
        synchronized (CONTROL) {
            if (!ACTION_START.equals(intent.getAction()) || id == null || !id.equals(pendingId) || activeId != null) {
                if (activeId == null) stopSelf(startId); return START_NOT_STICKY;
            }
            activeId = id; live = this; pendingId = null; pendingJournal = null; commandStartId = startId;
        }
        try {
            journal = PhoneEventJournal.forContext(this);
            JSONObject requested = new JSONObject(journal.get(key(id), "{}"));
            durationMs = requested.getLong("durationMs"); requestedWall = requested.optLong("requestedAt");
            startedWall = 0; startedElapsed = 0;
            if (requested.optBoolean("stopRequested")) { finishWithoutAudio("cancelled_before_recording", ""); return START_NOT_STICKY; }
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "호영 마이크 녹음", NotificationManager.IMPORTANCE_LOW));
            Notification notification = notification(id);
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIFICATION_ID, notification);
            worker.execute(this::beginCapture);
        } catch (Exception denied) { finishWithoutAudio("android_microphone_service_start_failed", denied.getClass().getSimpleName()); }
        return START_NOT_STICKY; // Process death never silently restarts a microphone command.
    }

    private void beginCapture() {
        String id = activeId;
        if (id == null) return;
        if (destroyed) { finishWithoutAudio("service_stopped_before_recording", ""); return; }
        try {
            if (!new PermissionCatalog(this).has(Manifest.permission.RECORD_AUDIO)) { finishWithoutAudio("permission_missing", ""); return; }
            if (!journal.toolAllowed("phone_voice_record")) { finishWithoutAudio("permission_denied", ""); return; }
            File directory = new File(getFilesDir(), "projects/captures/voice");
            if (!directory.mkdirs() && !directory.isDirectory()) throw new java.io.IOException("Capture directory unavailable");
            partial = new File(directory, ".partial-" + id); finished = new File(directory, id + ".m4a");
            Capture capture = factory.create(this, partial, durationMs, reason -> enqueueFinish(reason));
            recorder = capture; capture.start();
            startedElapsed = SystemClock.elapsedRealtime(); startedWall = System.currentTimeMillis();
            try {
                PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                if (power != null) { wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hoyoung:voice-capture"); wakeLock.setReferenceCounted(false); wakeLock.acquire(); }
            } catch (RuntimeException denied) { /* Audio can still proceed; exact scheduling is not promised. */ }
            Receipt receipt = base(id, "recording").putSafe("requestedAt", requestedWall).putSafe("durationMs", durationMs).putSafe("manualStop", durationMs == 0)
                    .putSafe("startedAt", startedWall).putSafe("recorderStarted", true).putSafe("wakeLockHeld", wakeLock != null && wakeLock.isHeld())
                    .putSafe("deadlineAt", durationMs == 0 || Long.MAX_VALUE - startedWall < durationMs ? JSONObject.NULL : startedWall + durationMs);
            if (!persist(journal, id, receipt)) { finish("journal_unavailable"); return; }
            main.post(pulse);
        } catch (Exception failed) { finish("capture_start_failed", failed.getClass().getSimpleName()); }
    }

    private Notification notification(String id) {
        PendingIntent stop = PendingIntent.getService(this, 2424, new Intent(this, VoiceCaptureService.class).setAction(ACTION_STOP).putExtra("requestId", id), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        HoyoungApplication app = getApplication() instanceof HoyoungApplication ? (HoyoungApplication) getApplication() : null;
        String name = app != null && app.getStore() != null ? AssistantIdentity.name(app.getStore()) : AssistantIdentity.DEFAULT_NAME;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(name + " 녹음 중").setContentText("요청한 마이크 녹음을 저장하고 있어 · 눌러서 중지할 수 있어")
                .setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "녹음 중지", stop).build());
        if (launch != null) builder.setContentIntent(PendingIntent.getActivity(this, 2424, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return builder.build();
    }

    private void enqueueFinish(String reason) {
        try { worker.execute(() -> finish(reason)); } catch (RejectedExecutionException stopped) { /* Persisted request remains explicitly unconfirmed. */ }
    }
    private void finish(String reason) { finish(reason, ""); }
    private void finish(String reason, String failureClass) {
        String id = activeId;
        if (id == null) return;
        main.removeCallbacks(pulse);
        Capture capture = recorder; recorder = null;
        Receipt receipt = base(id, reason).putSafe("requestedAt", requestedWall).putSafe("durationMs", durationMs).putSafe("completedAt", System.currentTimeMillis())
                .putSafe("recorderStarted", startedWall > 0).putSafe("startedAt", startedWall > 0 ? startedWall : JSONObject.NULL)
                .putSafe("requestedDurationCompleted", "duration_completed".equals(reason));
        boolean stopped = false;
        try {
            if (capture == null || startedWall == 0) throw new java.io.IOException("Recorder did not start");
            capture.stop(); stopped = true; capture.release(); capture = null;
            if (partial == null || !partial.isFile() || partial.length() == 0) throw new java.io.IOException("No captured audio file");
            try (RandomAccessFile disk = new RandomAccessFile(partial, "rw")) { disk.getFD().sync(); }
            JSONObject verified = validator.verify(partial);
            String hash = fileHash(partial);
            Files.move(partial.toPath(), finished.toPath(), StandardCopyOption.ATOMIC_MOVE);
            receipt.putSafe("completionConfirmed", true).putSafe("fileValid", true).putSafe("fileId", "captures/voice/" + finished.getName())
                    .putSafe("mime", "audio/mp4").putSafe("size", finished.length()).putSafe("contentHash", hash)
                    .putSafe("audioVerification", verified).putSafe("audioAudibilityVerified", false);
        } catch (Exception failed) {
            receipt.putSafe("state", "capture_failed").putSafe("stopReason", reason).putSafe("recordingStopped", stopped)
                    .putSafe("failureClass", failureClass.isEmpty() ? failed.getClass().getSimpleName() : failureClass)
                    .putSafe("completionConfirmed", false).putSafe("fileValid", false)
                    .putSafe("partialFileRetained", partial != null && partial.exists());
        } finally {
            if (capture != null) try { capture.release(); } catch (RuntimeException ignored) { }
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); wakeLock = null;
        }
        persist(journal, id, receipt); event(journal, id, receipt); clearAndStop(id);
    }

    private void finishWithoutAudio(String state, String failureClass) {
        String id = activeId;
        if (id != null && journal != null) {
            Receipt receipt = base(id, state).putSafe("completedAt", System.currentTimeMillis()).putSafe("failureClass", failureClass);
            persist(journal, id, receipt); event(journal, id, receipt);
        }
        clearAndStop(id);
    }
    private void clearAndStop(String id) {
        int finishedStartId = commandStartId;
        synchronized (CONTROL) { if (id != null && id.equals(activeId)) activeId = null; if (live == this) live = null; }
        partial = null; finished = null;
        main.post(() -> { if (!destroyed && activeId == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(finishedStartId); } });
    }
    @Override public void onDestroy() {
        destroyed = true; main.removeCallbacks(pulse);
        if (activeId != null) enqueueFinish("service_stopped");
        else synchronized (CONTROL) { if (live == this) live = null; }
        worker.shutdown(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private static boolean incomplete(String state) { return "service_start_requested".equals(state) || "recording".equals(state) || "stop_requested".equals(state); }
    private static String key(String id) { return "voice.request." + id; }
    private static boolean persist(PhoneEventJournal journal, String id, JSONObject receipt) { String value = receipt.toString(); journal.put(key(id), value); return value.equals(journal.get(key(id), "")); }
    private static void event(PhoneEventJournal journal, String id, JSONObject receipt) { journal.append("capture_result", "android.media_recorder", receipt, System.currentTimeMillis(), "voice-request:" + id); }
    private static JSONObject error(String code, Object... extras) { JSONObject value = PhoneEventJournal.object("ok", false, "code", code, "recorderStarted", false, "completionConfirmed", false); for (int i = 0; i < extras.length; i += 2) try { value.put((String) extras[i], extras[i + 1]); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } return value; }
    private static Receipt base(String id, String state) { return new Receipt().putSafe("requestId", id).putSafe("state", state).putSafe("requestedAt", System.currentTimeMillis())
            .putSafe("completionConfirmed", false).putSafe("recorderStarted", false).putSafe("binaryUploaded", false).putSafe("automaticReplay", false).putSafe("source", "android.media_recorder"); }
    private static final class Receipt extends JSONObject { Receipt putSafe(String key, Object value) { try { put(key, value); return this; } catch (JSONException impossible) { throw new IllegalStateException(impossible); } } }
    private static String fileHash(File file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (FileInputStream in = new FileInputStream(file)) { byte[] buffer = new byte[65_536]; for (int count; (count = in.read(buffer)) >= 0;) if (count > 0) digest.update(buffer, 0, count); } StringBuilder text = new StringBuilder(); for (byte b : digest.digest()) text.append(String.format(Locale.ROOT, "%02x", b & 255)); return text.toString(); }

    interface Capture { void start() throws Exception; void stop() throws Exception; void release(); }
    interface Factory { Capture create(Context context, File output, long duration, java.util.function.Consumer<String> stopped) throws Exception; }
    interface Validator { JSONObject verify(File output) throws Exception; }
    private static final Factory ANDROID_FACTORY = (context, output, duration, stopped) -> {
        MediaRecorder recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC); recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64_000); recorder.setAudioSamplingRate(16_000); recorder.setOutputFile(output.getPath());
            if (duration > 0 && duration <= Integer.MAX_VALUE) recorder.setMaxDuration((int) duration);
            recorder.setOnInfoListener((capture, what, extra) -> { if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopped.accept("duration_completed"); });
            recorder.setOnErrorListener((capture, what, extra) -> stopped.accept("android_recorder_error_" + what + "_" + extra));
            recorder.prepare();
        } catch (Exception failure) { recorder.release(); throw failure; }
        return new Capture() { public void start() { recorder.start(); } public void stop() { recorder.stop(); } public void release() { recorder.release(); } };
    };
    private static final Validator ANDROID_VALIDATOR = output -> {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(output.getAbsolutePath());
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track); String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(track); int sample = extractor.readSampleData(ByteBuffer.allocate(65_536), 0);
                    if (sample <= 0) throw new java.io.IOException("Audio track has no encoded sample");
                    return PhoneEventJournal.object("encodedAudioTrackVerified", true, "encodedSampleVerified", true, "codecMime", mime,
                            "durationUs", format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : JSONObject.NULL);
                }
            }
            throw new java.io.IOException("No audio track in capture");
        } finally { extractor.release(); }
    };
    private static Factory factory = ANDROID_FACTORY;
    private static Validator validator = ANDROID_VALIDATOR;
    static void testBackend(Factory capture, Validator verify) { factory = capture; validator = verify; }
    static void resetForTests() { synchronized (CONTROL) { live = null; pendingId = null; pendingJournal = null; factory = ANDROID_FACTORY; validator = ANDROID_VALIDATOR; } }
}
