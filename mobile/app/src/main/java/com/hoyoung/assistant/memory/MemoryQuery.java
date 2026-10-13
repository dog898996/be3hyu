package com.hoyoung.assistant.memory;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Explicit query constraints. Recorded dates are not silently treated as a fact's valid dates. */
public final class MemoryQuery {
    public final String query, expanded;
    public final JSONArray entities;
    public final long from, to, asOf;
    public final int limit, maxChars;
    public final boolean includeHistory;

    public MemoryQuery(JSONObject args) {
        query = args.optString("query", "").trim();
        if (query.length() > 20_000) throw new IllegalArgumentException("Memory query exceeds 20000 characters");
        entities = args.optJSONArray("entities") == null ? new JSONArray() : args.optJSONArray("entities");
        if (entities.length() > 20) throw new IllegalArgumentException("Too many entity constraints");
        StringBuilder all = new StringBuilder(MemoryText.expandedQuery(query));
        for (int i = 0; i < entities.length(); i++) {
            Object entity = entities.opt(i);
            if (!(entity instanceof String) || ((String) entity).length() > 200) throw new IllegalArgumentException("Entities must be short strings");
            all.append(' ').append(entity);
        }
        expanded = all.toString().trim();
        from = date(args.opt("fromInclusive")); to = date(args.opt("toExclusive")); asOf = date(args.opt("asOf"));
        if (from > 0 && to > 0 && from >= to) throw new IllegalArgumentException("Invalid date range");
        limit = Math.max(1, Math.min(50, args.optInt("limit", 20)));
        maxChars = Math.max(1000, Math.min(50_000, args.optInt("maxChars", 12_000)));
        includeHistory = args.optBoolean("includeHistory", false) || asOf > 0;
    }

    public boolean recordedInRange(long recordedAt) { return (from == 0 || recordedAt >= from) && (to == 0 || recordedAt < to); }

    public static long date(Object value) {
        if (value == null || value == JSONObject.NULL || "".equals(value)) return 0;
        if (value instanceof Number) {
            long result = ((Number) value).longValue();
            if (result < 0 || ((Number) value).doubleValue() != result) throw new IllegalArgumentException("Dates must be nonnegative epoch milliseconds");
            return result;
        }
        if (!(value instanceof String)) throw new IllegalArgumentException("Dates must be epoch milliseconds or ISO-8601");
        String input = (String) value;
        try { return Instant.parse(input).toEpochMilli(); }
        catch (RuntimeException ignored) {
            try { return LocalDate.parse(input).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid ISO date", invalid); }
        }
    }
}
