package com.hoyoung.assistant.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only normal completed boot/update broadcasts; no force-stop or locked-storage bypass. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) return;
        HoyoungApplication application = (HoyoungApplication) context.getApplicationContext();
        if (!application.isStartupReady()) return;
        AssistantRuntime runtime = application.getRuntime();
        if (runtime.autonomyEnabled()) runtime.startAutonomy();
    }
}
