package com.hoyoung.assistant.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import com.hoyoung.assistant.phone.PhoneEventJournal;
import com.hoyoung.assistant.phone.PermissionOnboarding;
import org.json.JSONObject;

/** User-visible, best-effort phone scheduler; not a promise of uninterrupted cloud hosting. */
public final class AutonomyService extends Service {
    public static final String ACTION_STOP = "com.hoyoung.assistant.STOP_AUTONOMY";
    public static final String ACTION_REEVALUATE_WAKE = "com.hoyoung.assistant.REEVALUATE_WAKE";
    private static final String CHANNEL = "hoyoung_autonomy";
    private static final int NOTIFICATION_ID = 2410;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AssistantRuntime runtime;
    private PowerManager.WakeLock wakeLock;
    private boolean running;
    private boolean powerReceiverRegistered;
    private long lastPulseWall;
    private long lastPulseElapsed;
    private long lastPulseDelay;
    private boolean elapsedReferenceVerified;
    private int bootCount = -1;
    private String configuredWakeMode = "continuous";
    private String effectiveWakeMode = "continuous";
    private boolean configuredWakeModeValid = true;
    private boolean plugged;
    private String wakeFailure = "";
    private static volatile JSONObject resources = PhoneEventJournal.object("serviceRunning", false, "configuredWakeMode", "continuous", "effectiveWakeMode", "continuous", "timing", "best_effort", "batteryCostMeasured", false);
    private static volatile AutonomyService liveService;
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() { @Override public void onReceive(Context context, Intent intent) { if (running) applyWakePolicy(false); } };
    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            if (!running || runtime == null || !runtime.autonomyEnabled()) { stopSelf(); return; }
            if (!new PermissionOnboarding(AutonomyService.this).snapshot().ready()) {
                // Preserve the owner's autonomy preference and queued tasks while setup is
                // incomplete or a grant was revoked. Opening the app can resume after grants.
                stopSelf(); return;
            }
            try {
                applyWakePolicy(true);
                measurePulseDelay();
                HoyoungApplication app = (HoyoungApplication) getApplication();
                app.getPhoneContext().reconcilePermissions();
                app.getPhoneContext().sampleNow();
                app.maintainMemory();
                runtime.tick();
            } finally {
                // Idle while_working mode releases immediately. Processing callbacks recheck
                // as asynchronous local work finishes; active Work keeps only a bounded lease.
                applyWakePolicy(false);
                if (running) handler.postDelayed(this, 60_000L);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        if (!((HoyoungApplication) getApplication()).isStartupReady()
                || !new PermissionOnboarding(this).snapshot().ready()) return;
        liveService = this;
        runtime = ((HoyoungApplication) getApplication()).getRuntime();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) { wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hoyoung:autonomy-pulse"); wakeLock.setReferenceCounted(false); }
        IntentFilter powerChanges = new IntentFilter(); powerChanges.addAction(Intent.ACTION_BATTERY_CHANGED); powerChanges.addAction(Intent.ACTION_POWER_CONNECTED); powerChanges.addAction(Intent.ACTION_POWER_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerReceiver, powerChanges, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(powerReceiver, powerChanges);
        powerReceiverRegistered = true;
        PhoneEventJournal journal = PhoneEventJournal.forContext(this);
        bootCount = android.provider.Settings.Global.getInt(getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        try { JSONObject saved = new JSONObject(journal.get("scheduler.lastPulse", "{}")); lastPulseWall = saved.optLong("wall"); lastPulseElapsed = saved.optLong("elapsed"); elapsedReferenceVerified = bootCount >= 0 && saved.optInt("bootCount", -1) == bootCount; } catch (org.json.JSONException invalid) { }
        ((HoyoungApplication) getApplication()).getPhoneContext().setProcessingListener(() -> handler.post(() -> { if (running) applyWakePolicy(false); }));
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "호영 자율 실행", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (runtime == null || !((HoyoungApplication) getApplication()).isStartupReady()) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { runtime.stopAutonomy(); stopSelf(); return START_NOT_STICKY; }
        if (!runtime.autonomyEnabled()) { stopSelf(); return START_NOT_STICKY; }
        if (!new PermissionOnboarding(this).snapshot().ready()) { stopSelf(); return START_NOT_STICKY; }
        try {
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID, notification);
            if (!running) { running = true; runtime.setServiceRunning(true); ((HoyoungApplication) getApplication()).getPhoneContext().start(); handler.post(pulse); }
            applyWakePolicy(false);
            return START_STICKY;
        } catch (RuntimeException failure) {
            runtime.setServiceRunning(false);
            runtime.localNotice("autonomy_foreground_denied", "Android가 자율 실행 서비스를 시작하지 못하게 했어. 앱을 열고 실행 권한을 확인해 줘.");
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private Notification notification() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent open = launch == null ? null : PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, AutonomyService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(com.hoyoung.assistant.store.AssistantIdentity.name(((HoyoungApplication)getApplication()).getStore())+" 자율 실행 중")
                .setContentText(effectiveWakeMode.equals("continuous") ? "지속 확인 · CPU를 깨어 있게 유지해 배터리를 더 사용함 · OS 제한 적용" : "작업 중 CPU 유지 · 대기·절전 중 주기 확인이 지연될 수 있음")
                .setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "자동 실행 중지", stop).build());
        if (open != null) builder.setContentIntent(open);
        return builder.build();
    }

    @Override public void onDestroy() {
        running = false; handler.removeCallbacks(pulse);
        if (liveService == this) liveService = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (powerReceiverRegistered) { unregisterReceiver(powerReceiver); powerReceiverRegistered = false; }
        if (runtime != null) runtime.setServiceRunning(false);
        com.hoyoung.assistant.phone.PhoneContextCollector collector = ((HoyoungApplication) getApplication()).getPhoneContext();
        if (collector != null) { collector.setProcessingListener(null); collector.stop("autonomy_service_stopped"); }
        updateResources();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    /** Current cached service state only; this accessor never obtains a database lock. */
    public static JSONObject resourceState() { try { return new JSONObject(resources.toString()); } catch (org.json.JSONException invalid) { return new JSONObject(); } }
    /** Post after owner settings or runtime work-state changes; never creates a background service. */
    public static void reevaluateActiveService() { AutonomyService live = liveService; if (live != null) live.handler.post(() -> { if (live.running) live.applyWakePolicy(false); }); }
    public static boolean validWakeMode(String mode) { return "continuous".equals(mode) || "while_working".equals(mode) || "charging".equals(mode); }
    public static String effectiveWakeMode(String mode, boolean plugged) { return "continuous".equals(mode) || "charging".equals(mode) && plugged ? "continuous" : "while_working"; }
    public static boolean shouldKeepAwake(String effectiveMode, boolean activeWork, boolean localProcessing) { return "continuous".equals(effectiveMode) || activeWork || localProcessing; }

    private void applyWakePolicy(boolean pulseExecuting) {
        if (runtime == null || !running || !runtime.autonomyEnabled()) { releaseWakeLock(); updateResources(); return; }
        HoyoungApplication app = (HoyoungApplication) getApplication();
        String raw = app.getStore().getSetting("wakeMode", "continuous"); configuredWakeModeValid = validWakeMode(raw);
        String previous = effectiveWakeMode;
        configuredWakeMode = configuredWakeModeValid ? raw : "continuous";
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); plugged = battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        effectiveWakeMode = effectiveWakeMode(configuredWakeMode, plugged);
        boolean keep = shouldKeepAwake(effectiveWakeMode, runtime.hasActiveWorkForWakeLock(), app.getPhoneContext().isProcessing());
        try { if (wakeLock != null && (keep || pulseExecuting)) { wakeLock.acquire(keep ? 120_000L : 15_000L); wakeFailure = ""; } else releaseWakeLock(); }
        catch (SecurityException denied) { wakeFailure = "android_denied_wake_lock"; releaseWakeLock(); }
        updateResources();
        if (!previous.equals(effectiveWakeMode)) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE); if (manager != null) manager.notify(NOTIFICATION_ID, notification());
        }
    }
    private void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
    private void measurePulseDelay() {
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        boolean elapsedReset = lastPulseWall > 0 && (!elapsedReferenceVerified || elapsed < lastPulseElapsed);
        long interval = elapsedReferenceVerified && lastPulseElapsed > 0 && elapsed >= lastPulseElapsed ? elapsed - lastPulseElapsed : lastPulseWall > 0 ? Math.max(0, wall - lastPulseWall) : 0;
        lastPulseDelay = Math.max(0, interval - 60_000L);
        PhoneEventJournal journal = PhoneEventJournal.forContext(this);
        if (lastPulseWall > 0 && (interval > 65_000L || elapsedReset)) journal.gap("android.autonomy_scheduler", lastPulseWall, wall, "scheduler_delay_or_restart; configured=" + configuredWakeMode + "; effective=" + effectiveWakeMode + "; measuredIntervalMs=" + interval + "; elapsedReferenceResetOrUnverified=" + elapsedReset + "; observedOnly=true");
        lastPulseWall = wall; lastPulseElapsed = elapsed; elapsedReferenceVerified = true;
        journal.put("scheduler.lastPulse", PhoneEventJournal.object("wall", wall, "elapsed", elapsed, "bootCount", bootCount).toString());
        updateResources();
    }
    private void updateResources() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        resources = PhoneEventJournal.object("serviceRunning", running, "configuredWakeMode", configuredWakeMode, "effectiveWakeMode", effectiveWakeMode,
                "validConfiguredWakeMode", configuredWakeModeValid, "plugged", plugged, "partialWakeLockHeld", wakeLock != null && wakeLock.isHeld(),
                "lastPulseAt", lastPulseWall > 0 ? lastPulseWall : JSONObject.NULL, "lastPulseElapsedRealtime", lastPulseElapsed > 0 ? lastPulseElapsed : JSONObject.NULL, "lastPulseDelayMs", lastPulseDelay,
                "desiredLocalPulseSeconds", 60, "timing", "best_effort", "powerSaveMode", power != null && power.isPowerSaveMode(), "deviceIdle", power != null && power.isDeviceIdleMode(),
                "batteryOptimizationExempt", power != null && power.isIgnoringBatteryOptimizations(getPackageName()), "wakeFailure", wakeFailure, "batteryCostMeasured", false,
                "completeLocationRouteRecorded", false);
    }
}
