package com.hoyoung.assistant.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** User-visible, best-effort phone scheduler; not a promise of uninterrupted cloud hosting. */
public final class AutonomyService extends Service {
    public static final String ACTION_STOP = "com.hoyoung.assistant.STOP_AUTONOMY";
    private static final String CHANNEL = "hoyoung_autonomy";
    private static final int NOTIFICATION_ID = 2410;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AssistantRuntime runtime;
    private PowerManager.WakeLock wakeLock;
    private boolean running;
    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            if (!running || runtime == null || !runtime.autonomyEnabled()) { stopSelf(); return; }
            try {
                // A Handler cannot wake a sleeping CPU. Keep a bounded lease across the
                // interval while the user-visible mode runs; renewals stop with the service.
                if (wakeLock != null) wakeLock.acquire(120_000L);
                runtime.tick();
            } finally { if (running) handler.postDelayed(this, 60_000L); }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        runtime = ((HoyoungApplication) getApplication()).getRuntime();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) { wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hoyoung:autonomy-pulse"); wakeLock.setReferenceCounted(false); }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "호영이 자율 실행", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { runtime.stopAutonomy(); stopSelf(); return START_NOT_STICKY; }
        if (!runtime.autonomyEnabled()) { stopSelf(); return START_NOT_STICKY; }
        try {
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID, notification);
            if (!running) { running = true; runtime.setServiceRunning(true); handler.post(pulse); }
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
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("호영이 자율 실행 중")
                .setContentText("폰에서 주기적으로 확인 · 네트워크와 Android 실행 제한의 영향을 받음")
                .setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "자동 실행 중지", stop).build());
        if (open != null) builder.setContentIntent(open);
        return builder.build();
    }

    @Override public void onDestroy() {
        running = false; handler.removeCallbacks(pulse);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (runtime != null) runtime.setServiceRunning(false);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
