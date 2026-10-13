package com.hoyoung.assistant.phone;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.UserManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class PhoneUsageTools {
    private final Context context;
    PhoneUsageTools(Context context) { this.context = context.getApplicationContext(); }
    JSONObject usage(JSONObject args) throws JSONException {
        if (!new PermissionCatalog(context).usageAccess()) return missing();
        if (!unlocked()) return PhoneEventJournal.object("ok", false, "code", "user_locked", "source", "android.usage_stats");
        long to = args.optLong("toMs", System.currentTimeMillis()), from = args.optLong("fromMs", to - 86400000);
        int limit = args.optInt("limit", 50); String packageName = args.optString("package", "");
        if (from < 0 || to <= from || to - from > 7L * 86400000 || limit < 1 || limit > 100 || packageName.length() > 200) throw new IllegalArgumentException();
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return PhoneEventJournal.object("ok", false, "code", "unsupported");
        List<UsageStats> stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, from, to);
        if (stats == null) return PhoneEventJournal.object("ok", false, "code", "usage_unavailable");
        List<UsageStats> sorted = new ArrayList<>(stats); sorted.sort(Comparator.comparingLong(UsageStats::getLastTimeUsed).reversed());
        JSONArray rows = new JSONArray(); boolean truncated = false;
        for (UsageStats item : sorted) {
            if (!packageName.isEmpty() && !packageName.equals(item.getPackageName())) continue;
            if (rows.length() >= limit) { truncated = true; break; }
            JSONObject row = PhoneEventJournal.object("package", item.getPackageName(), "foregroundTimeMsInReturnedBucket", item.getTotalTimeInForeground(), "lastUsedAt", item.getLastTimeUsed(),
                "bucketStart", item.getFirstTimeStamp(), "bucketEnd", item.getLastTimeStamp());
            if (Build.VERSION.SDK_INT >= 29) row.put("visibleTimeMsInReturnedBucket", item.getTotalTimeVisible());
            rows.put(row);
        }
        return PhoneEventJournal.object("ok", true, "code", "ok", "usage", rows, "fromMs", from, "toMs", to, "source", "android.usage_stats", "truncated", truncated,
            "coverage", "Android aggregate buckets can extend beyond the requested range; these are not exact requested-window totals.");
    }
    JSONObject events(JSONObject args) {
        if (!new PermissionCatalog(context).usageAccess()) return missing();
        if (!unlocked()) return PhoneEventJournal.object("ok", false, "code", "user_locked", "source", "android.usage_events");
        long to = args.optLong("toMs", System.currentTimeMillis()), from = args.optLong("fromMs", to - 3600000);
        int limit = args.optInt("limit", 100); String packageName = args.optString("package", "");
        if (from < 0 || to <= from || to - from > 86400000 || limit < 1 || limit > 200 || packageName.length() > 200) throw new IllegalArgumentException();
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return PhoneEventJournal.object("ok", false, "code", "unsupported");
        UsageEvents events = manager.queryEvents(from, to);
        if (events == null) return PhoneEventJournal.object("ok", false, "code", "usage_unavailable");
        JSONArray output = new JSONArray(); UsageEvents.Event event = new UsageEvents.Event(); boolean truncated = false;
        long scannedThrough = from;
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) break;
            scannedThrough = Math.max(scannedThrough, event.getTimeStamp());
            if (!relevant(event.getEventType()) || (!packageName.isEmpty() && !packageName.equals(event.getPackageName()))) continue;
            if (output.length() >= limit) { truncated = true; break; }
            output.put(PhoneEventJournal.object("package", event.getPackageName(), "eventType", event.getEventType(), "event", eventName(event.getEventType()), "time", event.getTimeStamp()));
        }
        return PhoneEventJournal.object("ok", true, "code", "ok", "events", output, "fromMs", from, "toMs", to, "scannedThroughMs", scannedThrough, "truncated", truncated,
            "source", "android.usage_events", "currentAppVerified", false, "coverage", "Observed app/screen transitions only; Android does not guarantee a complete activity history.");
    }
    JSONObject appState(JSONObject args) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        ActivityManager.RunningAppProcessInfo self = new ActivityManager.RunningAppProcessInfo(); ActivityManager.getMyMemoryState(self);
        JSONObject output = PhoneEventJournal.object("ok", true, "code", "ok", "interactive", power != null && power.isInteractive(), "keyguardLocked", keyguard != null && keyguard.isKeyguardLocked(),
            "userUnlocked", unlocked(), "selfProcessImportance", self.importance, "source", "android.device_state", "observedAt", System.currentTimeMillis(), "otherProcessesInspectable", false);
        String packageName = args.optString("package", "");
        if (!packageName.isEmpty()) {
            if (!new PermissionCatalog(context).usageAccess()) return missing();
            if (!packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException();
            UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            try { output.put("package", packageName).put("inactive", manager == null ? JSONObject.NULL : manager.isAppInactive(packageName)); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        return output;
    }
    private boolean unlocked() { UserManager user = (UserManager) context.getSystemService(Context.USER_SERVICE); return user == null || user.isUserUnlocked(); }
    private JSONObject missing() { return PhoneEventJournal.object("ok", false, "code", "permission_missing", "permissionId", "usage_access"); }
    private static boolean relevant(int type) { return type == UsageEvents.Event.MOVE_TO_FOREGROUND || type == UsageEvents.Event.MOVE_TO_BACKGROUND || type == 15 || type == 16 || type == 17 || type == 18; }
    private static String eventName(int type) { switch (type) { case 1: return "activity_resumed"; case 2: return "activity_paused"; case 15: return "screen_interactive"; case 16: return "screen_non_interactive"; case 17: return "keyguard_shown"; case 18: return "keyguard_hidden"; default: return "unknown"; } }
}
