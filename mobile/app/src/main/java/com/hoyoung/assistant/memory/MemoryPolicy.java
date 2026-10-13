package com.hoyoung.assistant.memory;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Separates retrieval relevance, source provenance and the explicitly supplied validity period. */
public final class MemoryPolicy {
    private MemoryPolicy() { }
    public static JSONObject metadata(JSONObject row) { JSONObject m = row.optJSONObject("metadata"); return m == null ? new JSONObject() : m; }
    public static String key(JSONObject row) { return row.optString("key", row.optString("memory_key")); }
    public static String temporal(JSONObject row, long asOf) {
        JSONObject m = metadata(row);
        long from = safeDate(m.opt("validFrom")), to = safeDate(m.opt("validTo"));
        if (from == 0 && to == 0) return "validity_unknown";
        long time = asOf == 0 ? System.currentTimeMillis() : asOf;
        if (from > 0 && time < from) return "future";
        if (to > 0 && time >= to) return "past";
        return "valid_at_requested_time";
    }
    private static long safeDate(Object v) { try { return MemoryQuery.date(v); } catch (RuntimeException e) { return 0; } }
    public static String epistemic(JSONObject row) {
        JSONArray refs = row.optJSONArray("sourceRefs"); boolean owner = false, device = false;
        if (refs != null) for (int i = 0; i < refs.length(); i++) {
            JSONObject ref = refs.optJSONObject(i); if (ref == null) continue;
            owner |= "message".equals(ref.optString("type")) && "user".equals(ref.optString("role"));
            device |= "device_event".equals(ref.optString("type"));
        }
        if (owner) return "derived_note_with_owner_sources_not_independently_verified";
        if (device) return "device_observation_with_scope_limits";
        return refs != null && refs.length() > 0 ? "assistant_generated_not_verified_fact" : "unsourced_unverified";
    }
    public static boolean overlaps(JSONObject a, JSONObject b) {
        JSONObject x = metadata(a), y = metadata(b);
        long af = safeDate(x.opt("validFrom")), at = safeDate(x.opt("validTo"));
        long bf = safeDate(y.opt("validFrom")), bt = safeDate(y.opt("validTo"));
        return (at == 0 || bf == 0 || bf < at) && (bt == 0 || af == 0 || af < bt);
    }
    public static JSONArray conflicts(List<JSONObject> rows) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < rows.size(); i++) for (int j = i + 1; j < rows.size(); j++) {
            JSONObject a = rows.get(i), b = rows.get(j), x = metadata(a), y = metadata(b);
            String group = x.optString("factKey", x.optString("subject") + "|" + x.optString("predicate"));
            String other = y.optString("factKey", y.optString("subject") + "|" + y.optString("predicate"));
            if (group.equals("|") || group.isEmpty() || !group.equals(other) || !overlaps(a, b)) continue;
            String av = x.optString("value", a.optString("content")), bv = y.optString("value", b.optString("content"));
            if (MemoryText.normalize(av).equals(MemoryText.normalize(bv))) continue;
            JSONObject conflict = new JSONObject();
            put(conflict, "factKey", group); put(conflict, "left", key(a)); put(conflict, "leftRevision", a.optInt("revision"));
            put(conflict, "right", key(b)); put(conflict, "rightRevision", b.optInt("revision"));
            put(conflict, "resolution", "unresolved_keep_both_sources"); put(conflict, "validity", "overlap_or_unknown"); result.put(conflict);
        }
        return result;
    }
    static void put(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (org.json.JSONException e) { throw new IllegalArgumentException(e); } }
}
