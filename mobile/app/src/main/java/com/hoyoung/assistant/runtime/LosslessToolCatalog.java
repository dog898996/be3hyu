package com.hoyoung.assistant.runtime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Reversible wire representation of the complete tool catalog. No descriptions, constraints,
 * argument names or tools are selected away. Character savings are not a token or quality claim.
 */
public final class LosslessToolCatalog {
    public static final String HEADER = "Lossless tool catalog. [0,name,args]={name,arguments:args}; "
            + "[1,name,desc,props,required]={name,description:desc,inputSchema:{type:\"object\","
            + "properties:props,required,additionalProperties:false}}; [2,def]=def unchanged. "
            + "Each props value is a JSON type string ({type:value}), [type,description] "
            + "({type,description}), or the exact schema object. Names, argument strings, "
            + "descriptions, required entries and nested constraints are literal; use named arguments.\n";

    private static String cachedOriginal;
    private static Result cachedResult;

    private LosslessToolCatalog() {}

    /** Returns either the verified compact representation or the exact full JSON serialization. */
    public static String format(JSONArray catalog) {
        return formatResult(catalog).text;
    }

    /** Native diagnostics only: these are UTF-16 character counts, never account token estimates. */
    public static synchronized Result formatResult(JSONArray catalog) {
        if (catalog == null) throw new IllegalArgumentException("A complete tool catalog is required");
        String original = catalog.toString();
        if (original == null) throw new IllegalArgumentException("The tool catalog is not serializable");
        // Exact serialization is the cache key; there is no mutable-array identity or hash collision.
        if (original.equals(cachedOriginal)) return cachedResult;
        String packed = null;
        boolean verified = false;
        try {
            // Snapshot the actual wire input before packing; callers retain ownership of their array.
            JSONArray snapshot = new JSONArray(original);
            JSONArray encoded = encode(snapshot);
            String encodedText = encoded.toString();
            if (encodedText != null) {
                packed = HEADER + encodedText;
                JSONArray restored = decode(new JSONArray(encodedText));
                verified = equalJson(snapshot, restored);
            }
        } catch (JSONException | IllegalArgumentException invalid) {
            // An unfamiliar definition or failed verification cannot remove a tool from the prompt.
        }
        boolean usePacked = verified && packed != null && packed.length() < original.length();
        Result result = new Result(usePacked ? packed : original, original.length(),
                packed == null ? original.length() : packed.length(), usePacked, verified);
        cachedOriginal = original;
        cachedResult = result;
        return result;
    }

    /** Packs ordered rows, retaining unsupported definitions in their complete original form. */
    public static JSONArray encode(JSONArray catalog) {
        if (catalog == null) throw new IllegalArgumentException("A complete tool catalog is required");
        JSONArray rows = new JSONArray();
        try {
            for (int i = 0; i < catalog.length(); i++) {
                Object value = catalog.get(i);
                JSONObject definition = value instanceof JSONObject ? (JSONObject) value : null;
                if (definition != null && exactKeys(definition, "name", "arguments")
                        && definition.opt("name") instanceof String
                        && definition.opt("arguments") instanceof String) {
                    rows.put(new JSONArray().put(0).put(definition.get("name"))
                            .put(definition.get("arguments")));
                } else if (nativeShape(definition)) {
                    JSONObject schema = definition.getJSONObject("inputSchema");
                    JSONObject properties = schema.getJSONObject("properties");
                    JSONObject packedProperties = new JSONObject();
                    Iterator<String> keys = properties.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        JSONObject property = properties.getJSONObject(key);
                        if (exactKeys(property, "type") && jsonType(property.opt("type"))) {
                            packedProperties.put(key, property.get("type"));
                        } else if (exactKeys(property, "type", "description")
                                && jsonType(property.opt("type")) && property.opt("description") instanceof String) {
                            packedProperties.put(key, new JSONArray().put(property.get("type")).put(property.get("description")));
                        } else {
                            packedProperties.put(key, copyJson(property));
                        }
                    }
                    rows.put(new JSONArray().put(1).put(definition.get("name"))
                            .put(definition.get("description")).put(packedProperties)
                            .put(copyJson(schema.getJSONArray("required"))));
                } else {
                    rows.put(new JSONArray().put(2).put(copyJson(value)));
                }
            }
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("The full tool catalog could not be encoded", invalid);
        }
        return rows;
    }

    /** Strict inverse of encode. Malformed rows fail rather than silently discard any definition. */
    public static JSONArray decode(JSONArray rows) {
        if (rows == null) throw new IllegalArgumentException("Encoded tool rows are required");
        JSONArray result = new JSONArray();
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONArray row = rows.getJSONArray(i);
                Object rawTag = row.get(0);
                if (!(rawTag instanceof Number)) throw malformed();
                double tagValue = ((Number) rawTag).doubleValue();
                int tag = ((Number) rawTag).intValue();
                if (tagValue != tag) throw malformed();
                if (tag == 0) {
                    if (row.length() != 3 || !(row.get(1) instanceof String)
                            || !(row.get(2) instanceof String)) throw malformed();
                    result.put(new JSONObject().put("name", row.get(1))
                            .put("arguments", row.get(2)));
                } else if (tag == 1) {
                    if (row.length() != 5 || !(row.get(1) instanceof String)
                            || !(row.get(2) instanceof String)
                            || !(row.get(3) instanceof JSONObject)
                            || !(row.get(4) instanceof JSONArray)) throw malformed();
                    JSONObject properties = row.getJSONObject(3);
                    JSONObject fullProperties = new JSONObject();
                    Iterator<String> keys = properties.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object property = properties.get(key);
                        if (jsonType(property)) {
                            fullProperties.put(key, new JSONObject().put("type", property));
                        } else if (property instanceof JSONArray) {
                            JSONArray described = (JSONArray) property;
                            if (described.length() != 2 || !jsonType(described.get(0))
                                    || !(described.get(1) instanceof String)) throw malformed();
                            fullProperties.put(key, new JSONObject().put("type", described.get(0))
                                    .put("description", described.get(1)));
                        } else if (property instanceof JSONObject) {
                            fullProperties.put(key, copyJson(property));
                        } else {
                            throw malformed();
                        }
                    }
                    JSONObject schema = new JSONObject().put("type", "object")
                            .put("properties", fullProperties).put("required", copyJson(row.get(4)))
                            .put("additionalProperties", false);
                    result.put(new JSONObject().put("name", row.get(1))
                            .put("description", row.get(2)).put("inputSchema", schema));
                } else if (tag == 2) {
                    if (row.length() != 2) throw malformed();
                    result.put(copyJson(row.get(1)));
                } else {
                    throw malformed();
                }
            }
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Malformed lossless tool catalog", invalid);
        }
        return result;
    }

    private static boolean nativeShape(JSONObject definition) throws JSONException {
        if (definition == null || !exactKeys(definition, "name", "description", "inputSchema")
                || !(definition.opt("name") instanceof String)
                || !(definition.opt("description") instanceof String)) return false;
        JSONObject schema = definition.optJSONObject("inputSchema");
        if (schema == null || !exactKeys(schema, "type", "properties", "required", "additionalProperties")
                || !"object".equals(schema.opt("type"))
                || !Boolean.FALSE.equals(schema.opt("additionalProperties"))
                || !(schema.opt("required") instanceof JSONArray)) return false;
        JSONObject properties = schema.optJSONObject("properties");
        if (properties == null) return false;
        Iterator<String> keys = properties.keys();
        while (keys.hasNext()) {
            // Boolean schemas, strings or null property values stay in their full fallback row.
            if (!(properties.get(keys.next()) instanceof JSONObject)) return false;
        }
        return true;
    }

    private static boolean exactKeys(JSONObject object, String... keys) {
        if (object.length() != keys.length) return false;
        for (String key : keys) if (!object.has(key)) return false;
        return true;
    }

    private static boolean jsonType(Object value) {
        if (!(value instanceof String)) return false;
        switch ((String) value) {
            case "string": case "integer": case "number": case "boolean":
            case "object": case "array": case "null": return true;
            default: return false;
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Malformed lossless tool catalog row");
    }

    private static Object copyJson(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject result = new JSONObject();
            Iterator<String> keys = ((JSONObject) value).keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.put(key, copyJson(((JSONObject) value).get(key)));
            }
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray result = new JSONArray();
            for (int i = 0; i < ((JSONArray) value).length(); i++) {
                result.put(copyJson(((JSONArray) value).get(i)));
            }
            return result;
        }
        return value == null ? JSONObject.NULL : value;
    }

    private static boolean equalJson(Object first, Object second) throws JSONException {
        if (first instanceof JSONObject && second instanceof JSONObject) {
            JSONObject left = (JSONObject) first, right = (JSONObject) second;
            if (left.length() != right.length()) return false;
            Iterator<String> keys = left.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!right.has(key) || !equalJson(left.get(key), right.get(key))) return false;
            }
            return true;
        }
        if (first instanceof JSONArray && second instanceof JSONArray) {
            JSONArray left = (JSONArray) first, right = (JSONArray) second;
            if (left.length() != right.length()) return false;
            for (int i = 0; i < left.length(); i++) {
                if (!equalJson(left.get(i), right.get(i))) return false;
            }
            return true;
        }
        return first == second || (first != null && first.equals(second));
    }

    public static final class Result {
        public final String text;
        public final int baselineCharacters;
        public final int packedCharacters;
        public final boolean usedCompact;
        public final boolean roundTripVerified;

        private Result(String text, int baselineCharacters, int packedCharacters,
                       boolean usedCompact, boolean roundTripVerified) {
            this.text = text;
            this.baselineCharacters = baselineCharacters;
            this.packedCharacters = packedCharacters;
            this.usedCompact = usedCompact;
            this.roundTripVerified = roundTripVerified;
        }
    }
}
