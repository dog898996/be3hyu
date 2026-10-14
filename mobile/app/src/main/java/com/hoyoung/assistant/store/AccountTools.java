package com.hoyoung.assistant.store;

import android.content.Context;
import com.hoyoung.assistant.phone.PhoneTools;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Iterator;

/** Agent account tools: exact latest lookup, durable corrections/history, and owner-controlled removal. */
public final class AccountTools {
    private final HoyoungStore store;
    private final AccountStore accounts;
    private final AccountMfaTools mfa;
    public AccountTools(Context context, HoyoungStore store) { this(context, store, null); }
    public AccountTools(Context context, HoyoungStore store, PhoneTools phoneTools) { this.store = store; accounts = new AccountStore(context, store); mfa = new AccountMfaTools(context, store, phoneTools); }

    public JSONArray catalog() {
        JSONArray tools = new JSONArray();
        tools.put(tool("accounts.list", "Find saved account metadata by service, label, username or URL. Choose accountId explicitly; passwords are obtained with accounts.get.", props("query", "string", "limit", "integer")));
        tools.put(tool("accounts.get", "Read the complete exact latest account and credentials by stable accountId. Stored credentials are not proof of a successful external login; no old-password fallback.", props("accountId", "string"), "accountId"));
        JSONObject save = props("accountId", "string", "service", "string", "username", "string", "password", "string", "label", "string", "loginUrl", "string", "notes", "string", "extra", "object", "sourceMessageId", "integer", "sourceMessageHash", "string");
        tools.put(tool("accounts.save", "Save or correct exact account strings. Provide sourceMessageId and sourceMessageHash together to bind actual owner input; otherwise the value is truthfully agent-generated. Corrections retain revision history and standard memory ZIP backup. The result is metadata; accounts.get explicitly reads credentials.", save, "service", "username", "password"));
        tools.put(tool("accounts.history", "Read explicitly labeled current and historical credential revisions for one accountId. History is not an automatic fallback for login.", props("accountId", "string", "limit", "integer"), "accountId"));
        tools.put(tool("accounts.delete", "Remove dedicated account copies and derivatives, with deletion receipts and portable tombstones. The original owner chat and unrelated memories remain preserved.", props("accountId", "string"), "accountId"));
        JSONArray factors = mfa.catalog(); for (int i = 0; i < factors.length(); i++) tools.put(factors.optJSONObject(i));
        return tools;
    }

    public JSONObject execute(String name, JSONObject args) throws IOException {
        return execute(name, args, null);
    }
    public JSONObject execute(String name, JSONObject args, String nativeActionIdentity) throws IOException {
        return execute(name, args, nativeActionIdentity, null);
    }
    /** Only the native runtime can supply a prior withheld recovery reservation; never model arguments. */
    public JSONObject execute(String name, JSONObject args, String nativeActionIdentity, JSONObject pendingRecovery) throws IOException {
        return execute(name,args,nativeActionIdentity,pendingRecovery,pendingRecovery==null?null:nativeActionIdentity);
    }
    public JSONObject execute(String name, JSONObject args, String nativeActionIdentity, JSONObject pendingRecovery, String pendingNativeIdentity) throws IOException {
        JSONObject definition = null;
        JSONArray tools = catalog();
        for (int i = 0; i < tools.length(); i++) if (name.equals(tools.optJSONObject(i).optString("name"))) definition = tools.optJSONObject(i);
        if (definition == null) return object("ok", false, "code", "unknown_tool");
        if (!allowed(name)) return object("ok", false, "code", "permission_denied", "tool", name);
        validate(args, definition.optJSONObject("inputSchema"));
        if (pendingRecovery != null && !"accounts.recovery.reserve".equals(name)) return object("ok", false, "code", "recovery_resume_mismatch");
        if (mfa.supports(name)) return mfa.execute(name, args, nativeActionIdentity, pendingRecovery, pendingNativeIdentity);
        try {
            switch (name) {
                case "accounts.list": {
                    synchronized (store) {
                        SourcePrivacyLedger.Snapshot scope = store.getPrivacyLedger().capture();
                        JSONArray listed = accounts.list(args.optString("query", ""), limit(args));
                        JSONArray visible = new JSONArray(), originals = new JSONArray();
                        for (int i = 0; i < listed.length(); i++) {
                            JSONObject row = listed.getJSONObject(i), current = accounts.get(row.getString("accountId"));
                            if (current == null) continue;
                            visible.put(row); originals.put(current);
                        }
                        if (!scope.fingerprint.equals(store.getPrivacyLedger().fingerprint())) throw new IOException("Account source policy changed during list read");
                        return object("ok", true, "code", "ok", "accounts", visible, "sourceRefs", references(originals), "metadataOnly", true, "loginPerformed", false);
                    }
                }
                case "accounts.get": {
                    JSONObject value = accounts.get(args.getString("accountId"));
                    return value == null ? missing() : object("ok", true, "code", "ok", "account", value, "sourceRefs", references(new JSONArray().put(value)), "exactLatestRevision", true, "loginPerformed", false);
                }
                case "accounts.save": {
                    boolean id = args.has("sourceMessageId"), hash = args.has("sourceMessageHash");
                    if (id != hash) throw new IllegalArgumentException("sourceMessageId and sourceMessageHash must be supplied together");
                    JSONObject value;
                    if (id) {
                        long sourceId = integer(args.opt("sourceMessageId"), "sourceMessageId");
                        String sourceHash = args.getString("sourceMessageHash");
                        if (sourceId < 1 || !sourceHash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("A canonical owner source identity is required");
                        value = accounts.saveFromSource(args, sourceId, sourceHash);
                    } else value = accounts.saveFromAgent(args);
                    return object("ok", true, "code", "saved", "account", metadata(value), "sourceRefs", value.optJSONArray("sourceRefs"),
                            "exactStringsPreserved", true, "completionConfirmed", true, "loginPerformed", false, "loginVerified", false);
                }
                case "accounts.history": {
                    JSONArray revisions = accounts.history(args.getString("accountId"), limit(args));
                    return object("ok", true, "code", "ok", "accountId", args.getString("accountId"), "revisions", revisions, "sourceRefs", references(revisions), "historicalValuesAreNotCurrent", true, "loginPerformed", false);
                }
                case "accounts.delete": {
                    JSONObject deleted = accounts.delete(args.getString("accountId"), "agent_authored");
                    return object("ok", true, "code", deleted.optString("status"), "deletion", deleted,
                            "completionConfirmed", "completed_managed_local".equals(deleted.optString("status")), "pendingDeletion", deleted.optBoolean("pendingDeletion"));
                }
                default: throw new IllegalStateException("Unimplemented catalog entry");
            }
        } catch (JSONException malformed) { throw new IllegalArgumentException("Malformed account arguments", malformed); }
    }

    /** Metadata-only native discovery fence; the model cannot provide a ledger claimant identity. */
    public boolean canResumeRecovery(JSONObject pending, String pendingNativeIdentity) throws IOException {
        return allowed("accounts.recovery.reserve") && mfa.canResume(pending,pendingNativeIdentity);
    }

    private boolean allowed(String name) {
        try { JSONObject permissions = new JSONObject(store.getSetting("toolPermissions", "{}")); return !permissions.has(name) || Boolean.TRUE.equals(permissions.opt(name)); }
        catch (JSONException malformed) { return false; }
    }
    private static JSONObject metadata(JSONObject value) {
        JSONObject result = new JSONObject();
        for (String field : new String[]{"accountId", "service", "username", "label", "loginUrl", "origin", "inputActor", "revision", "createdAt", "updatedAt", "loginVerified"}) put(result, field, value.opt(field));
        put(result, "hasPassword", !value.optString("password").isEmpty()); return result;
    }
    /** Top-level typed native identities bind cached metadata and credential results alike.
     * Quotes/passwords never enter this reference projection. Receipt reads recheck the
     * canonical identities and current deletion/exclusion policy recursively. */
    private static JSONArray references(JSONArray records) throws JSONException {
        java.util.LinkedHashMap<String, JSONObject> unique = new java.util.LinkedHashMap<>();
        for (int i = 0; i < records.length(); i++) {
            JSONArray refs = records.getJSONObject(i).optJSONArray("sourceRefs");
            if (refs == null) throw new IllegalStateException("Account result has no native source identities");
            for (int j = 0; j < refs.length(); j++) {
                JSONObject ref = refs.getJSONObject(j); String type = ref.optString("type");
                Object rawId = ref.opt("id"); String hash = ref.optString("hash");
                if (!("message".equals(type) || "device_event".equals(type)) || !(rawId instanceof Number)
                        || ((Number) rawId).doubleValue() != ((Number) rawId).longValue() || ((Number) rawId).longValue() <= 0 || !hash.matches("[a-f0-9]{64}"))
                    throw new IllegalStateException("Account source identity is malformed");
                long id = ((Number) rawId).longValue(); unique.put(type + ":" + id + ":" + hash, object("type", type, "id", id, "hash", hash));
            }
        }
        return new JSONArray(unique.values());
    }
    private static int limit(JSONObject args) {
        long value = args.has("limit") ? integer(args.opt("limit"), "limit") : 20;
        if (value < 1 || value > 1000) throw new IllegalArgumentException("limit must be 1..1000"); return (int) value;
    }
    private static long integer(Object value, String field) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException(field + " must be an integer");
        return ((Number) value).longValue();
    }
    private static void validate(JSONObject args, JSONObject schema) {
        if (args == null) throw new IllegalArgumentException("Account arguments are required");
        JSONObject properties = schema.optJSONObject("properties"); JSONArray required = schema.optJSONArray("required");
        for (int i = 0; i < required.length(); i++) if (!args.has(required.optString(i))) throw new IllegalArgumentException("Missing " + required.optString(i));
        for (Iterator<String> keys = args.keys(); keys.hasNext();) {
            String key = keys.next(); JSONObject property = properties.optJSONObject(key); Object value = args.opt(key);
            if (property == null) throw new IllegalArgumentException("Unknown account argument " + key);
            switch (property.optString("type")) {
                case "string": if (!(value instanceof String)) throw new IllegalArgumentException(key + " must be an exact string"); break;
                case "integer": integer(value, key); break;
                case "object": if (!(value instanceof JSONObject)) throw new IllegalArgumentException(key + " must be an object"); break;
                default: throw new IllegalStateException("Unsupported account schema");
            }
        }
    }
    private static JSONObject missing() { return object("ok", false, "code", "account_not_found", "olderRevisionFallback", false, "loginPerformed", false); }
    private static JSONObject tool(String name, String description, JSONObject properties, String... required) { return object("name", name, "description", description, "inputSchema", object("type", "object", "properties", properties, "required", new JSONArray(java.util.Arrays.asList(required)), "additionalProperties", false)); }
    private static JSONObject props(String... fields) { JSONObject result = new JSONObject(); for (int i = 0; i < fields.length; i += 2) put(result, fields[i], object("type", fields[i + 1])); return result; }
    private static JSONObject object(Object... fields) { JSONObject result = new JSONObject(); for (int i = 0; i < fields.length; i += 2) put(result, (String) fields[i], fields[i + 1]); return result; }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
}
