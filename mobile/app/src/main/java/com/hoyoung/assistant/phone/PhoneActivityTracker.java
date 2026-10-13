package com.hoyoung.assistant.phone;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONException;
import org.json.JSONObject;

/** Step counter preferred, detector fallback. It does not infer activity classes from step counts. */
public final class PhoneActivityTracker implements SensorEventListener {
    private final Context context;
    private final PhoneEventJournal journal;
    private final SensorManager manager;
    private boolean running;
    private boolean registered;
    private Sensor sensor;
    private long lastRaw = -1;
    private long delta;
    private long detectorCount;
    private long lastObservedAt;
    private long lastSampleAt;
    private int bootCount = -1;
    private boolean gapSinceLastSample;
    private String status = "stopped";

    public PhoneActivityTracker(Context context, PhoneEventJournal journal) { this.context = context.getApplicationContext(); this.journal = journal; this.manager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE); load(); }
    public synchronized void start() {
        running = true;
        if (!hasPermission()) { suspend("activity_permission_missing"); return; }
        if (registered) return;
        if (manager == null) { status = "sensor_service_unavailable"; return; }
        sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (sensor == null) sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        if (sensor == null) { status = "step_sensor_unavailable"; return; }
        int currentBoot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (bootCount != -1 && currentBoot != -1 && currentBoot != bootCount) { lastRaw = -1; delta = 0; detectorCount = 0; gapSinceLastSample = true; journal.gap("android.step_sensor", lastObservedAt, System.currentTimeMillis(), "device_reboot_counter_reset"); }
        bootCount = currentBoot;
        try { registered = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000); }
        catch (SecurityException denied) { registered = false; }
        status = registered ? "awaiting_sensor_event" : "sensor_registration_denied";
        if (registered) { gapSinceLastSample = true; journal.append("collector_state", "android.step_sensor", PhoneEventJournal.object("state", "started", "sensorType", sensor.getType(), "sensorName", sensor.getName(), "gapBeforeStart", true), System.currentTimeMillis(), null); }
        persist();
    }
    public synchronized void reconcilePermissions() { if (!running) return; if (!hasPermission()) suspend("activity_permission_revoked"); else if (!registered) start(); }
    public synchronized void stop(String reason) { running = false; suspend(reason == null ? "collector_stopped" : reason); }
    private void suspend(String reason) {
        flush();
        if (registered && manager != null) manager.unregisterListener(this);
        if (registered || !reason.equals(status)) journal.gap("android.step_sensor", lastObservedAt > 0 ? lastObservedAt : System.currentTimeMillis(), System.currentTimeMillis(), reason);
        registered = false; gapSinceLastSample = true; status = reason; persist();
    }
    public synchronized JSONObject snapshot() {
        return PhoneEventJournal.object("ok", hasPermission(), "code", !hasPermission() ? "permission_missing" : registered ? "ok" : status,
            "permissionId", "activity_recognition", "running", running, "registered", registered, "sensorType", sensor == null ? JSONObject.NULL : sensor.getType(),
            "counterStepsSinceBootWhileSensorActive", lastRaw >= 0 ? lastRaw : JSONObject.NULL, "detectorStepsObservedThisProcess", detectorCount,
            "pendingCounterDelta", delta, "lastObservedAt", lastObservedAt > 0 ? lastObservedAt : JSONObject.NULL,
            "ageMs", lastObservedAt > 0 ? Math.max(0, System.currentTimeMillis() - lastObservedAt) : JSONObject.NULL,
            "bootCount", bootCount, "gapSinceLastSample", gapSinceLastSample, "source", "android.step_sensor", "observedOnly", true, "activityClassification", "not_inferred");
    }
    @Override public synchronized void onSensorChanged(SensorEvent event) {
        if (!running || !registered || !hasPermission() || !journal.toolAllowed("phone_activity_read") || !journal.collectionEnabled()) {
            if (running && registered) suspend(!hasPermission() ? "activity_permission_revoked" : "app_activity_collection_disabled"); return;
        }
        if (event == null || event.sensor == null || event.values.length == 0) return;
        observe(event.sensor.getType(), event.values[0], event.timestamp);
    }
    /** Package-private input seam used by synthetic sensor tests; not an app/network tool. */
    synchronized void observe(int type, float value, long elapsedNanos) {
        if (!Float.isFinite(value) || value < 0 || value != Math.floor(value) || (type != Sensor.TYPE_STEP_COUNTER && type != Sensor.TYPE_STEP_DETECTOR)) return;
        long ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedNanos;
        if (ageNanos < 0) return;
        long time = System.currentTimeMillis() - ageNanos / 1_000_000;
        if (time < lastObservedAt) return;
        if (type == Sensor.TYPE_STEP_COUNTER) {
            long counter = (long) value;
            if (lastRaw >= 0 && counter < lastRaw) { flush(); journal.gap("android.step_sensor", lastObservedAt, time, "counter_decreased_or_reset"); delta = 0; gapSinceLastSample = true; }
            else if (lastRaw >= 0) delta += counter - lastRaw;
            lastRaw = counter;
        } else if (value == 1.0f) { detectorCount++; delta++; }
        else return;
        lastObservedAt = time; status = "observed";
        if (lastSampleAt == 0 || delta >= 10 || time - lastSampleAt >= 300_000) flush();
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { /* Counter availability is reported; no invented body-health accuracy. */ }
    public synchronized void flush() {
        if (lastObservedAt == 0 || (lastSampleAt == lastObservedAt && delta == 0)) return;
        JSONObject sample = PhoneEventJournal.object("counterStepsSinceBootWhileSensorActive", lastRaw >= 0 ? lastRaw : JSONObject.NULL,
            "stepsDeltaSincePreviousReading", delta, "detectorStepsObservedThisProcess", detectorCount, "windowStart", lastSampleAt,
            "windowEnd", lastObservedAt, "bootCount", bootCount, "includesObservationGap", gapSinceLastSample, "observedOnly", true,
            "sensorType", sensor == null ? JSONObject.NULL : sensor.getType(), "classification", "steps_only");
        JSONObject stored = journal.append("activity_sample", "android.step_sensor", sample, lastObservedAt, null);
        if (stored.has("id") || stored.optBoolean("observationPersisted")) { delta = 0; lastSampleAt = lastObservedAt; gapSinceLastSample = false; persist(); }
    }
    private boolean hasPermission() { return Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED; }
    private void load() {
        try { JSONObject saved = new JSONObject(journal.get("activity.state", "{}")); lastRaw = saved.optLong("lastRaw", -1); lastObservedAt = saved.optLong("lastObservedAt", 0); lastSampleAt = saved.optLong("lastSampleAt", 0); bootCount = saved.optInt("bootCount", -1); gapSinceLastSample = true; }
        catch (JSONException corrupt) { status = "saved_sensor_state_invalid"; }
    }
    private void persist() { journal.put("activity.state", PhoneEventJournal.object("lastRaw", lastRaw, "lastObservedAt", lastObservedAt, "lastSampleAt", lastSampleAt, "bootCount", bootCount, "status", status).toString()); }
}
