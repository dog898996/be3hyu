package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Explicit short voice capture while the app is foreground. No ambient recording or upload. */
final class PhoneVoiceRecorder {
    private final Context context; private final PhoneEventJournal journal; private final Handler main = new Handler(Looper.getMainLooper());
    private MediaRecorder recorder; private String activeId; private File partial; private File finished; private long deadline;
    PhoneVoiceRecorder(Context context, PhoneEventJournal journal) { this.context = context.getApplicationContext(); this.journal = journal; }
    synchronized JSONObject start(JSONObject args) throws Exception {
        if (!new PermissionCatalog(context).has(Manifest.permission.RECORD_AUDIO)) return PhoneEventJournal.object("ok", false, "code", "permission_missing", "permissionId", "microphone");
        if (!foreground()) return PhoneEventJournal.object("ok", false, "code", "foreground_required");
        if (recorder != null) return PhoneEventJournal.object("ok", false, "code", "recording_busy");
        int duration = args.optInt("durationMs", 10000); if (duration < 2000 || duration > 30000) throw new IllegalArgumentException();
        String id = UUID.randomUUID().toString(); File directory = new File(context.getFilesDir(), "projects/captures/voice");
        if (!directory.mkdirs() && !directory.isDirectory()) return PhoneEventJournal.object("ok", false, "code", "operation_failed");
        partial = new File(directory, ".partial-" + id); finished = new File(directory, id + ".m4a");
        MediaRecorder capture = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        try {
            capture.setAudioSource(MediaRecorder.AudioSource.MIC); capture.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); capture.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            capture.setAudioEncodingBitRate(64000); capture.setAudioSamplingRate(16000); capture.setOutputFile(partial.getPath()); capture.setMaxDuration(duration); capture.prepare(); capture.start();
            recorder = capture; activeId = id; deadline = System.currentTimeMillis() + duration;
            capture.setOnInfoListener((value, what, extra) -> { if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish("duration_completed"); });
            journal.put("voice.request." + id, PhoneEventJournal.object("requestId", id, "state", "recording", "startedAt", System.currentTimeMillis(), "deadlineAt", deadline, "binaryUploaded", false).toString());
            main.postDelayed(this::pulse, 500);
            return PhoneEventJournal.object("ok", true, "code", "recording_started", "requestId", id, "completionConfirmed", false, "deadlineAt", deadline, "localOnly", true);
        } catch (Exception failure) { capture.release(); if (partial.exists()) partial.delete(); partial = null; finished = null; throw failure; }
    }
    private void pulse() { synchronized (this) { if (recorder == null) return; if (!foreground()) { finish("stopped_when_app_left_foreground"); return; } if (!new PermissionCatalog(context).has(Manifest.permission.RECORD_AUDIO) || !journal.toolAllowed("phone_voice_record")) { finish("permission_or_tool_revoked"); return; } if (System.currentTimeMillis() >= deadline) { finish("duration_completed"); return; } } main.postDelayed(this::pulse, 500); }
    synchronized void finish(String reason) {
        if (recorder == null) return; MediaRecorder capture = recorder; String id = activeId; recorder = null; activeId = null;
        JSONObject result;
        try {
            capture.stop(); capture.release();
            Files.move(partial.toPath(), finished.toPath(), StandardCopyOption.ATOMIC_MOVE);
            result = PhoneEventJournal.object("requestId", id, "state", reason, "fileId", "captures/voice/" + finished.getName(), "mime", "audio/mp4", "size", finished.length(), "completedAt", System.currentTimeMillis(), "binaryUploaded", false, "source", "android.media_recorder");
            journal.append("capture_result", "android.media_recorder", result, System.currentTimeMillis(), "voice-request:" + id);
        } catch (Exception failed) { try { capture.release(); } catch (RuntimeException ignored) { } result = PhoneEventJournal.object("requestId", id, "state", "capture_failed", "completionConfirmed", false, "binaryUploaded", false); if (partial != null && partial.exists()) partial.delete(); }
        journal.put("voice.request." + id, result.toString()); partial = null; finished = null;
    }
    JSONObject read(JSONObject args) throws JSONException {
        if (!new PermissionCatalog(context).has(Manifest.permission.RECORD_AUDIO)) return PhoneEventJournal.object("ok", false, "code", "permission_missing", "permissionId", "microphone");
        String id = args.getString("requestId"); if (!id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException();
        String value = journal.get("voice.request." + id, ""); if (value.isEmpty()) return PhoneEventJournal.object("ok", false, "code", "target_not_found");
        JSONObject result = new JSONObject(value); synchronized (this) { if ("recording".equals(result.optString("state")) && !id.equals(activeId)) result.put("state", "outcome_unknown_after_process_restart"); }
        return PhoneEventJournal.object("ok", true, "code", "ok", "request", result);
    }
    private static boolean foreground() { ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo(); ActivityManager.getMyMemoryState(process); return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND; }
}
