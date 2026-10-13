package com.hoyoung.assistant.memory;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.Predicate;

/** Optional derived ANN candidates. Every returned range is rechecked against authoritative sources.
 * Providers must expose approximate/partial coverage and respect current source visibility. */
public interface SemanticCandidateProvider {
    boolean isReady(String modelId);
    JSONArray chunks(float[] query,MemoryQuery constraints,int limit,Predicate<JSONObject> deviceVisibility) throws Exception;
    JSONObject diagnostics();
    /** Called on a maintenance worker after canonical SQLite vectors are updated. */
    JSONObject maintain(int maximumRows,long timeBudgetMillis);
}
