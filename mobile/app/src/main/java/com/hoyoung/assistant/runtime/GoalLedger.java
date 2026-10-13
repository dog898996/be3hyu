package com.hoyoung.assistant.runtime;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Durable unfinished goals. Provider-turn completion is not evidence a goal finished. */
public final class GoalLedger {
    public static final int MAX_ACTIVE_GOALS = 256;
    private static final String PREFIX = "activeGoal:", RANGE_END = "activeGoal;";
    private final HoyoungStore store;
    public GoalLedger(HoyoungStore store) { this.store = store; }

    public JSONObject update(String rootId, String stage, long sourceId, String lastRequestId,
                             String progress, long nextCheckAt) {
        return update(rootId, stage, sourceId, lastRequestId, progress, nextCheckAt, new JSONObject());
    }

    /** Evidence is supplied by trusted runtime receipts, never passed straight from model JSON. */
    public JSONObject update(String rootId, String reportedStage, long sourceId, String lastRequestId,
                             String progress, long nextCheckAt, JSONObject evidence) {
        validRoot(rootId); validRoot(lastRequestId);
        if (!pending(reportedStage) && !"completed".equals(reportedStage) && !"abandoned".equals(reportedStage))
            throw new IllegalArgumentException("Unsupported goal stage");
        if (sourceId < 0 || nextCheckAt < 0 || progress == null || progress.length() > 2_000)
            throw new IllegalArgumentException("Invalid goal source, progress, or check time");
        JSONObject proof = evidence == null ? new JSONObject() : copy(evidence);
        if (proof.has("completionVerified") && !(proof.opt("completionVerified") instanceof Boolean))
            throw new IllegalArgumentException("completionVerified must be boolean");
        boolean verified = Boolean.TRUE.equals(proof.opt("completionVerified"));
        if (verified && proof.optString("verificationSource").trim().isEmpty())
            throw new IllegalArgumentException("Verified completion needs an actual receipt source");
        return store.withTransaction(db -> {
            JSONObject previous = get(rootId), rootRequest = requestByExternalId(db, rootId);
            if (rootRequest == null) throw new IllegalArgumentException("Unknown root request");
            JSONObject lastRequest = requestByExternalId(db, lastRequestId);
            if (lastRequest == null || (!lastRequestId.equals(rootId)
                    && !rootId.equals(store.getSetting("requestRoot:" + lastRequestId, ""))))
                throw new IllegalArgumentException("Last request is not part of this root goal");
            long effectiveSource = sourceId;
            if (previous != null) {
                long originalSource = previous.optLong("sourceMessageId");
                if (sourceId == 0 && originalSource > 0) effectiveSource = originalSource;
                else if (sourceId != originalSource) throw new IllegalArgumentException("A goal source cannot be replaced");
            }
            validateSource(rootId, effectiveSource, rootRequest);
            boolean finalClaim = "completed".equals(reportedStage) || "abandoned".equals(reportedStage);
            if (previous == null && finalClaim) {
                // Ordinary finished chat replies do not consume unfinished-goal slots.
                return object("ok", true, "tracked", false, "closed", false, "code", "no_pending_goal",
                        "reportedStage", reportedStage, "completionVerified", verified);
            }
            if (previous == null && activeCount(db) >= MAX_ACTIVE_GOALS) {
                store.recordEvent("goal_ledger_capacity_reached", object("rootRequestId", rootId, "limit", MAX_ACTIVE_GOALS));
                return object("ok", false, "code", "goal_capacity_reached", "limit", MAX_ACTIVE_GOALS,
                        "message", "미완료 목표 256개 한도에 도달했어. 기존 목표를 확인·정리해야 새 목표를 추적할 수 있어.");
            }
            String stage = "completed".equals(reportedStage) && !verified ? "awaiting_result"
                    : "abandoned".equals(reportedStage) ? "blocked" : reportedStage;
            long now = System.currentTimeMillis();
            JSONObject record = object("rootRequestId", rootId, "sourceMessageId", effectiveSource,
                    "lastRequestId", lastRequestId, "stage", stage, "reportedStage", reportedStage,
                    "progress", progress, "createdAt", previous == null ? now : previous.optLong("createdAt", now),
                    "updatedAt", Math.max(now, previous == null ? 0 : previous.optLong("updatedAt")),
                    "nextCheckAt", nextCheckAt, "completionVerified", verified,
                    "completionClaimUnverified", "completed".equals(reportedStage) && !verified,
                    "abandonmentNeedsOwnerDecision", "abandoned".equals(reportedStage),
                    "requiresOwnerReconciliation", "blocked".equals(stage) || "uncertain".equals(stage),
                    "mayAutoRepeat", false, "mayScheduleReadOnlyCheck", checkAllowed(stage, nextCheckAt),
                    "requestExcerpt", safePrefix(rootRequest.optString("text"), 500),
                    "requestExcerptIncomplete", rootRequest.optString("text").length() > 500);
            if (!proof.optString("verificationSource").isEmpty()) put(record, "verificationSource", safePrefix(proof.optString("verificationSource"), 512));
            boolean closed = "completed".equals(stage) || "abandoned".equals(stage);
            if (closed) db.delete("settings", "setting_key = ?", new String[]{PREFIX + rootId});
            else store.putSetting(PREFIX + rootId, record.toString());
            bumpRevision();
            store.recordEvent(closed ? "goal_closed" : "goal_stage_recorded", object("goal", record,
                    "modelReportIsVerification", false, "rawRequestsAndOriginalsRetained", true));
            return object("ok", true, "tracked", !closed, "closed", closed, "goal", record);
        });
    }

    public JSONObject get(String rootId) {
        validRoot(rootId);
        String serialized = store.getSetting(PREFIX + rootId, "");
        if (serialized.isEmpty()) return null;
        try {
            JSONObject value = new JSONObject(serialized);
            if (!rootId.equals(value.optString("rootRequestId")) || !pending(value.optString("stage")))
                throw new IllegalStateException("Malformed active goal record");
            return value;
        } catch (org.json.JSONException invalid) { throw new IllegalStateException("Unreadable active goal record", invalid); }
    }

    /** Keyset pagination advances across scanned rows even if stage/time filters select none. */
    public JSONObject list(JSONObject arguments) {
        JSONObject args = arguments == null ? new JSONObject() : arguments;
        rejectUnknown(args, "afterRootRequestId", "limit", "stage", "dueBefore");
        String after = args.optString("afterRootRequestId", ""); if (!after.isEmpty()) validRoot(after);
        int limit = intArgument(args, "limit", 1, 100, 25);
        String stage = args.optString("stage", ""); if (!stage.isEmpty() && !pending(stage)) throw new IllegalArgumentException("Invalid stage filter");
        long dueBefore = longArgument(args, "dueBefore", 0, Long.MAX_VALUE, Long.MAX_VALUE);
        synchronized (store) {
            SQLiteDatabase db = store.getReadableDatabase();
            JSONArray goals = new JSONArray(), invalid = new JSONArray();
            int scanned = 0; String last = after; boolean more = false;
            try (Cursor cursor = db.rawQuery("SELECT setting_key,value FROM settings WHERE setting_key >= ? AND setting_key < ? AND setting_key > ? ORDER BY setting_key ASC LIMIT ?",
                    new String[]{PREFIX, RANGE_END, PREFIX + after, String.valueOf(limit + 1)})) {
                while (cursor.moveToNext()) {
                    if (scanned == limit) { more = true; break; }
                    String key = cursor.getString(0); last = key.substring(PREFIX.length()); scanned++;
                    try {
                        JSONObject goal = new JSONObject(cursor.getString(1));
                        if (!last.equals(goal.optString("rootRequestId")) || !pending(goal.optString("stage"))) throw new IllegalStateException();
                        if ((stage.isEmpty() || stage.equals(goal.optString("stage")))
                                && (!args.has("dueBefore") || (goal.optLong("nextCheckAt") > 0 && goal.optLong("nextCheckAt") <= dueBefore))) goals.put(goal);
                    } catch (Exception unreadable) { invalid.put(last); }
                }
            }
            return object("ok", true, "goals", goals, "totalPending", activeCount(db), "limit", MAX_ACTIVE_GOALS,
                    "scannedCount", scanned, "returnedCount", goals.length(), "truncated", more,
                    "nextAfterRootRequestId", more ? last : JSONObject.NULL, "invalidRecordCount", invalid.length(), "invalidRootRequestIds", invalid);
        }
    }

    public String context(int maxChars) {
        if (maxChars < 256 || maxChars > 50_000) throw new IllegalArgumentException("Goal context budget must be 256..50,000");
        JSONObject page = list(object("limit", 100)); JSONArray goals = new JSONArray();
        java.util.List<JSONObject> ranked = new java.util.ArrayList<>(); int invalidCount = 0;
        int scanned = 0;
        while (true) {
            JSONArray items = page.optJSONArray("goals");
            for (int i = 0; i < items.length(); i++) ranked.add(items.optJSONObject(i));
            invalidCount += page.optInt("invalidRecordCount"); scanned += page.optInt("scannedCount");
            if (!page.optBoolean("truncated") || scanned >= MAX_ACTIVE_GOALS) break;
            page = list(object("limit", 100, "afterRootRequestId", page.optString("nextAfterRootRequestId")));
        }
        long now = System.currentTimeMillis();
        ranked.sort((left, right) -> {
            int order = Integer.compare(priority(left, now), priority(right, now));
            if (order != 0) return order;
            order = Long.compare(left.optLong("updatedAt"), right.optLong("updatedAt"));
            return order != 0 ? order : left.optString("rootRequestId").compareTo(right.optString("rootRequestId"));
        });
        for (JSONObject goal : ranked) goals.put(goal);
        JSONArray packed = new JSONArray(); boolean truncated = page.optBoolean("truncated");
        JSONObject result = object("totalPending", page.optLong("totalPending"), "goals", packed,
                "truncated", truncated, "invalidRecordCount", invalidCount,
                "executionRule", "blocked/uncertain 목표 자동재실행 금지. due는 상태 확인 시점이며 행동 재승인이 아님.");
        for (int i = 0; i < goals.length(); i++) {
            JSONObject goal = goals.optJSONObject(i);
            JSONObject compact = object("rootRequestId", goal.optString("rootRequestId"), "sourceMessageId", goal.optLong("sourceMessageId"),
                    "lastRequestId", goal.optString("lastRequestId"), "stage", goal.optString("stage"),
                    "reportedStage", goal.optString("reportedStage"), "progress", goal.optString("progress"),
                    "requestExcerpt", goal.optString("requestExcerpt"), "requestExcerptIncomplete", goal.optBoolean("requestExcerptIncomplete"),
                    "nextCheckAt", goal.optLong("nextCheckAt"), "completionVerified", goal.optBoolean("completionVerified"),
                    "mayAutoRepeat", false, "requiresOwnerReconciliation", goal.optBoolean("requiresOwnerReconciliation"));
            packed.put(compact);
            if (result.toString().length() > maxChars - 32) { packed.remove(packed.length() - 1); truncated = true; break; }
        }
        put(result, "truncated", truncated); put(result, "shownCount", packed.length());
        if (result.toString().length() > maxChars) result.remove("executionRule");
        return result.toString();
    }

    /** Call only for an explicit owner decision; this is not an independently verified outcome. */
    public JSONObject resolve(String rootId, String finalStage, String reason) {
        validRoot(rootId);
        if (!("completed".equals(finalStage) || "abandoned".equals(finalStage))
                || reason == null || reason.trim().isEmpty() || reason.length() > 2_000) throw new IllegalArgumentException("Explicit owner closure and reason required");
        return store.withTransaction(db -> {
            JSONObject previous = get(rootId);
            if (previous == null) return object("ok", false, "code", "goal_not_active");
            put(previous, "stage", finalStage); put(previous, "reportedStage", finalStage);
            put(previous, "ownerResolved", true); put(previous, "completionVerified", false);
            put(previous, "providerOutcomeVerified", false); put(previous, "resolutionReason", reason);
            put(previous, "mayScheduleReadOnlyCheck", false); put(previous, "requiresOwnerReconciliation", false);
            put(previous, "updatedAt", System.currentTimeMillis());
            db.delete("settings", "setting_key = ?", new String[]{PREFIX + rootId});
            bumpRevision();
            store.recordEvent("goal_owner_resolved", object("goal", previous, "automaticReplay", false, "rawRequestsAndOriginalsRetained", true));
            return object("ok", true, "closed", true, "goal", previous);
        });
    }
    public JSONObject remove(String rootId, String reason) { return resolve(rootId, "abandoned", reason); }

    private void bumpRevision() {
        long previous = 0;
        try { previous = Math.max(0, Long.parseLong(store.getSetting("goalLedgerRevision", "0"))); }
        catch (NumberFormatException invalid) { /* Repair the derived invalidation counter without dropping goals. */ }
        store.putSetting("goalLedgerRevision", String.valueOf(previous == Long.MAX_VALUE ? 1 : previous + 1));
    }
    private void validateSource(String rootId, long sourceId, JSONObject rootRequest) {
        if (sourceId > 0) {
            JSONObject source = store.getMessage(sourceId);
            if (source == null || !"user".equals(source.optString("role")) || !rootId.equals(source.optString("request_id")))
                throw new IllegalArgumentException("Root goal requires its actual owner source message");
        } else if (!"autonomy".equals(rootRequest.optString("kind"))
                || !rootId.equals(store.getSetting("requestRoot:" + rootId, rootId)))
            throw new IllegalArgumentException("An owner request cannot become a source-free automatic goal");
    }
    private static JSONObject requestByExternalId(SQLiteDatabase db, String id) {
        try (Cursor cursor = db.rawQuery("SELECT id,external_id,text,kind,status FROM requests WHERE external_id = ?", new String[]{id})) {
            if (!cursor.moveToFirst()) return null;
            return object("id", cursor.getLong(0), "external_id", cursor.getString(1), "text", cursor.getString(2), "kind", cursor.getString(3), "status", cursor.getString(4));
        }
    }
    private static long activeCount(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM settings WHERE setting_key >= ? AND setting_key < ?", new String[]{PREFIX, RANGE_END})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }
    private static int priority(JSONObject goal, long now) {
        if (checkAllowed(goal.optString("stage"), goal.optLong("nextCheckAt")) && goal.optLong("nextCheckAt") <= now) return 0;
        return goal.optBoolean("requiresOwnerReconciliation") ? 1 : 2;
    }
    private static boolean pending(String stage) { return "queued".equals(stage) || "working".equals(stage) || "awaiting_result".equals(stage) || "blocked".equals(stage) || "uncertain".equals(stage); }
    private static boolean checkAllowed(String stage, long nextCheckAt) { return nextCheckAt > 0 && ("working".equals(stage) || "awaiting_result".equals(stage)); }
    private static void validRoot(String id) { if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,450}")) throw new IllegalArgumentException("Invalid goal root request id"); }
    private static int intArgument(JSONObject args, String key, int min, int max, int fallback) {
        return (int) longArgument(args, key, min, max, fallback);
    }
    private static long longArgument(JSONObject args, String key, long min, long max, long fallback) {
        if (!args.has(key)) return fallback; Object value = args.opt(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).longValue()) throw new IllegalArgumentException("Invalid " + key);
        long result = ((Number) value).longValue(); if (result < min || result > max) throw new IllegalArgumentException("Invalid " + key); return result;
    }
    private static void rejectUnknown(JSONObject args, String... allowed) {
        Set<String> keys = new HashSet<>(); for (String key : allowed) keys.add(key);
        JSONArray names = args.names(); if (names != null) for (int i = 0; i < names.length(); i++) if (!keys.contains(names.optString(i))) throw new IllegalArgumentException("Unknown paging argument");
    }
    private static String safePrefix(String value, int count) {
        int end = Math.min(value.length(), count);
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }
    private static JSONObject copy(JSONObject value) { try { return new JSONObject(value.toString()); } catch (Exception invalid) { throw new IllegalArgumentException("Invalid evidence", invalid); } }
    private static JSONObject object(Object... pairs) { JSONObject result = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) put(result, String.valueOf(pairs[i]), pairs[i + 1]); return result; }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception invalid) { throw new IllegalStateException(invalid); } }
}
