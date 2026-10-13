package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded one-shot requests, returning a receipt immediately instead of blocking the UI thread. */
final class PhoneLocationRequests {
    private final Context context;
    private final PhoneEventJournal journal;
    private final LocationManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Pending> pending = new HashMap<>();
    PhoneLocationRequests(Context context, PhoneEventJournal journal) { this.context = context.getApplicationContext(); this.journal = journal; this.manager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE); }
    synchronized JSONObject request(JSONObject args) {
        PermissionCatalog grants = new PermissionCatalog(context);
        boolean fine = grants.has(Manifest.permission.ACCESS_FINE_LOCATION), coarse = grants.has(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!fine && !coarse) return missing("location");
        ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo(); ActivityManager.getMyMemoryState(process);
        if (Build.VERSION.SDK_INT >= 29 && process.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && !grants.has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return missing("background_location");
        int timeout = args.optInt("timeoutMs", 15000); if (timeout < 1000 || timeout > 30000) throw new IllegalArgumentException();
        long maximumAge = args.optLong("maxAgeMs", 60000); if (maximumAge < 0 || maximumAge > 900000) throw new IllegalArgumentException();
        if (manager == null) return PhoneEventJournal.object("ok", false, "code", "location_unavailable");
        String provider = fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null;
        if (provider == null) return PhoneEventJournal.object("ok", false, "code", "location_services_disabled");
        String id = UUID.randomUUID().toString(); Pending work = new Pending(id, System.currentTimeMillis(), provider, maximumAge); pending.put(id, work);
        journal.put("location.request." + id, PhoneEventJournal.object("requestId", id, "state", "pending", "provider", provider, "startedAt", work.startedAt, "deadlineAt", work.startedAt + timeout).toString());
        try {
            if (Build.VERSION.SDK_INT >= 30) manager.getCurrentLocation(provider, work.signal, command -> main.post(command), location -> finish(work, location, location == null ? "location_unavailable" : "observed"));
            else manager.requestSingleUpdate(provider, work.listener, Looper.getMainLooper());
            main.postDelayed(() -> finish(work, null, "timed_out"), timeout);
            return PhoneEventJournal.object("ok", true, "code", "location_request_accepted", "requestId", id, "completionConfirmed", false, "deadlineAt", work.startedAt + timeout, "source", "android.location_manager");
        } catch (SecurityException denied) { pending.remove(id); work.signal.cancel(); journal.put("location.request." + id, PhoneEventJournal.object("requestId", id, "state", "permission_missing", "completedAt", System.currentTimeMillis()).toString()); return missing("location"); }
        catch (RuntimeException unavailable) { pending.remove(id); return PhoneEventJournal.object("ok", false, "code", "location_request_failed", "completionConfirmed", false); }
    }
    synchronized JSONObject read(JSONObject args) throws JSONException {
        String id = args.getString("requestId"); if (!id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException();
        PermissionCatalog grants = new PermissionCatalog(context);
        if (!grants.has(Manifest.permission.ACCESS_FINE_LOCATION) && !grants.has(Manifest.permission.ACCESS_COARSE_LOCATION)) return missing("location");
        String text = journal.get("location.request." + id, ""); if (text.isEmpty()) return PhoneEventJournal.object("ok", false, "code", "target_not_found");
        JSONObject status = new JSONObject(text);
        if (status.has("spoolId")) { JSONObject receipt = journal.receipt(status.optString("spoolId")); if (receipt.has("deviceEventId")) status.put("deviceEventId", receipt.optLong("deviceEventId")).put("databaseIngested", true); }
        if ("pending".equals(status.optString("state")) && !pending.containsKey(id)) { status.put("state", "outcome_unknown_after_process_restart").put("completionConfirmed", false); }
        return PhoneEventJournal.object("ok", true, "code", "ok", "request", status, "source", "android.location_manager");
    }
    synchronized void cancelAll(String reason) { for (Pending work : pending.values().toArray(new Pending[0])) finish(work, null, reason == null ? "cancelled" : reason); }
    private synchronized void finish(Pending work, Location location, String state) {
        if (!pending.containsKey(work.id)) return;
        pending.remove(work.id); work.signal.cancel(); if (Build.VERSION.SDK_INT < 30) manager.removeUpdates(work.listener);
        PermissionCatalog grants = new PermissionCatalog(context);
        if (!grants.has(Manifest.permission.ACCESS_FINE_LOCATION) && !grants.has(Manifest.permission.ACCESS_COARSE_LOCATION)) { location = null; state = "permission_revoked"; }
        if (!journal.toolAllowed("phone_location_refresh") || !journal.collectionEnabled()) { location = null; state = "app_collection_disabled"; }
        JSONObject result = PhoneEventJournal.object("requestId", work.id, "state", state, "provider", work.provider, "startedAt", work.startedAt, "completedAt", System.currentTimeMillis(), "completionConfirmed", location != null);
        if (location != null) {
            long age = (android.os.SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1_000_000;
            if (age < 0 || !Double.isFinite(location.getLatitude()) || !Double.isFinite(location.getLongitude()) || Math.abs(location.getLatitude()) > 90 || Math.abs(location.getLongitude()) > 180
                    || (location.hasAccuracy() && (!Float.isFinite(location.getAccuracy()) || location.getAccuracy() < 0))) {
                state = "invalid_location_measurement"; result = PhoneEventJournal.object("requestId", work.id, "state", state, "completionConfirmed", false);
                journal.gap("android.location_manager", work.startedAt, System.currentTimeMillis(), state);
            }
            else if (age > work.maximumAge) {
                state = "location_stale"; result = PhoneEventJournal.object("requestId", work.id, "state", state, "ageMs", age, "maximumAgeMs", work.maximumAge, "completionConfirmed", false);
                journal.gap("android.location_manager", work.startedAt, System.currentTimeMillis(), state);
            }
            else {
                JSONObject measurement = PhoneEventJournal.object("latitude", location.getLatitude(), "longitude", location.getLongitude(), "accuracyMeters", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL,
                    "ageMs", age, "timeMs", location.getTime(), "provider", location.getProvider(), "mock", Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider(), "requestId", work.id,
                    "freshFixRequested", true, "fixObservedAfterRequest", location.getElapsedRealtimeNanos() >= work.startedElapsedNanos, "freshnessClock", "elapsed_realtime", "cachedFixPossible", true, "maximumAgeMs", work.maximumAge);
                try { result.put("measurement", measurement); } catch (JSONException impossible) { }
                JSONObject stored = journal.append("location_sample", "android.location_manager", measurement, Math.max(0, location.getTime()), "location-request:" + work.id);
                try { result.put("deviceEventId", stored.has("id") ? stored.optLong("id") : JSONObject.NULL).put("spoolId", stored.optString("spoolId")).put("observationPersisted", stored.has("id") || stored.optBoolean("observationPersisted")).put("databaseIngested", stored.has("id")); } catch (JSONException impossible) { }
            }
        } else journal.gap("android.location_manager", work.startedAt, System.currentTimeMillis(), state);
        journal.put("location.request." + work.id, result.toString());
    }
    private final class Pending {
        final String id; final long startedAt; final long startedElapsedNanos; final String provider; final long maximumAge; final CancellationSignal signal = new CancellationSignal();
        final LocationListener listener = new LocationListener() { @Override public void onLocationChanged(Location location) { finish(Pending.this, location, "observed"); } @Override public void onProviderDisabled(String provider) { finish(Pending.this, null, "provider_disabled"); } @Override public void onProviderEnabled(String provider) { } @Override public void onStatusChanged(String provider, int status, Bundle extras) { } };
        Pending(String id, long startedAt, String provider, long maximumAge) { this.id = id; this.startedAt = startedAt; this.startedElapsedNanos = android.os.SystemClock.elapsedRealtimeNanos(); this.provider = provider; this.maximumAge = maximumAge; }
    }
    private static JSONObject missing(String id) { return PhoneEventJournal.object("ok", false, "code", "permission_missing", "permissionId", id); }
}
