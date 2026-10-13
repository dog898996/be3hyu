package com.hoyoung.assistant.phone;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Notification content stays in this app's private storage; this class has no network path. */
public final class HoyoungNotificationListener extends NotificationListenerService {
    private static final Object LOCK = new Object();
    private static final String STORE = "hoyoung_notification_history";
    private static final int MAX_EVENTS = 250;
    private static volatile boolean connected;

    @Override public void onListenerConnected() { connected = true; }
    @Override public void onListenerDisconnected() { connected = false; }
    @Override public void onDestroy() { connected = false; super.onDestroy(); }
    @Override public void onNotificationPosted(StatusBarNotification notification) { retain(notification, "posted", 0); }
    @Override public void onNotificationRemoved(StatusBarNotification notification) { retain(notification, "removed", 0); }
    @Override public void onNotificationRemoved(StatusBarNotification notification, RankingMap ranking, int reason) { retain(notification, "removed", reason); }
    public static boolean isConnected() { return connected; }

    public static JSONArray notificationSnapshot(Context context) {
        synchronized (LOCK) {
            String source = context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE).getString("events", "[]");
            try { return new JSONArray(source); } catch (JSONException corrupt) { return new JSONArray(); }
        }
    }

    private void retain(StatusBarNotification sbn, String event, int reason) {
        if (sbn == null) return;
        synchronized (LOCK) {
            try {
                Bundle extras = sbn.getNotification() == null ? Bundle.EMPTY : sbn.getNotification().extras;
                JSONObject entry = new JSONObject()
                    .put("package", sbn.getPackageName()).put("key", limit(sbn.getKey(), 512))
                    .put("title", limit(extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE), 512))
                    .put("text", limit(extras == null ? null : extras.getCharSequence(Notification.EXTRA_TEXT), 2048))
                    .put("time", System.currentTimeMillis()).put("postedAt", sbn.getPostTime())
                    .put("event", event).put("removalReason", reason);
                JSONArray previous = notificationSnapshot(this);
                JSONArray bounded = new JSONArray();
                for (int i = Math.max(0, previous.length() - MAX_EVENTS + 1); i < previous.length(); i++) bounded.put(previous.opt(i));
                bounded.put(entry);
                SharedPreferences preferences = getSharedPreferences(STORE, Context.MODE_PRIVATE);
                preferences.edit().putString("events", bounded.toString()).apply();
            } catch (RuntimeException | JSONException ignored) { /* Fail closed; never forward a failed record. */ }
        }
    }

    private static String limit(CharSequence value, int maximum) { if (value == null) return ""; String text = value.toString(); return text.length() > maximum ? text.substring(0, maximum) : text; }
}
