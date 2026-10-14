package com.hoyoung.assistant.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.hoyoung.assistant.runtime.HoyoungApplication;

/** Exact-alarm callback only: no Work request, autonomy start or reminder replay. */
public final class PhoneAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !PhoneAlarmTools.ACTION.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null || !"hoyoung-alarm".equals(data.getScheme()) || !"reminder".equals(data.getHost())) return;
        String id = data.getLastPathSegment(); Context application = context.getApplicationContext();
        PendingResult pending = goAsync();
        Thread worker = new Thread(() -> {
            try {
                long deadline = android.os.SystemClock.elapsedRealtime() + 8000;
                while (application instanceof HoyoungApplication && !((HoyoungApplication) application).isStartupReady()
                        && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
                if (application instanceof HoyoungApplication && !((HoyoungApplication) application).isStartupReady()) return;
                new PhoneAlarmTools(application).delivered(id);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { if (pending != null) pending.finish(); }
        }, "hoyoung-native-reminder");
        worker.start();
    }
}
