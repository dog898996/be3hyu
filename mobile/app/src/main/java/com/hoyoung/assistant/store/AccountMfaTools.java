package com.hoyoung.assistant.store;

import android.content.Context;
import com.hoyoung.assistant.phone.PhoneTools;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.LongSupplier;

/** Actual native MFA computation/reservation; no invented SMS/email/FIDO result or login proof. */
public final class AccountMfaTools {
    interface EntryClient { JSONObject execute(String name, JSONObject args); }
    private final Context context;
    private final HoyoungStore store;
    private final AccountStore accounts;
    private final RecoveryCodeLedger recovery;
    private final LongSupplier clock;
    private final ExternalOwnerPolicyFolder portable;
    private EntryClient entry;
    public AccountMfaTools(Context context, HoyoungStore store, PhoneTools phoneTools) {
        this(context, store, System::currentTimeMillis, phoneTools == null ? null : phoneTools::execute);
    }
    AccountMfaTools(Context context, HoyoungStore store, LongSupplier clock, EntryClient entry) {
        this(context, store, clock, entry, new ExternalOwnerPolicyFolder(context));
    }
    AccountMfaTools(Context context, HoyoungStore store, LongSupplier clock, EntryClient entry, ExternalOwnerPolicyFolder portable) {
        this.context = context.getApplicationContext(); this.store = store; this.accounts = new AccountStore(context, store);
        this.recovery = new RecoveryCodeLedger(context); this.clock = clock; this.entry = entry; this.portable = portable;
    }
    public JSONArray catalog() {
        JSONArray tools = new JSONArray();
        tools.put(tool("accounts.totp.get", "Generate RFC6238 code from the current saved extra.totp seed. Returns exact expiry and native source refs; device-clock and successful authentication remain unverified.", properties("accountId", "string"), "accountId"));
        tools.put(tool("accounts.totp.type", "Generate fresh TOTP immediately before native phone_ui_type on a fresh nodeId. Both tool grants apply. Near-expiry windows defer without typing; Android acceptance is not successful authentication.", properties("accountId", "string", "nodeId", "string"), "accountId", "nodeId"));
        tools.put(tool("accounts.recovery.reserve", "Durably reserve one saved recovery code before revealing it. Native action identity supplies idempotence. Reserved is not server-consumed; fingerprints survive older backup restore. Optional zero-based index.", properties("accountId", "string", "index", "integer"), "accountId"));
        tools.put(tool("accounts.recovery.state", "Read recovery-code available/reserved counts for a current account without exposing code values. Local reservation does not prove server consumption.", properties("accountId", "string"), "accountId"));
        return tools;
    }
    public boolean supports(String name) { return "accounts.totp.get".equals(name) || "accounts.totp.type".equals(name) || "accounts.recovery.reserve".equals(name) || "accounts.recovery.state".equals(name); }
    public JSONObject execute(String name, JSONObject args, String nativeActionIdentity) throws IOException {
        try {
            if (!supports(name)) return result(false, "unknown_tool");
            if (!allowed(name)) return result(false, "permission_denied").put("tool", name);
            String accountId = args.getString("accountId"); JSONObject account = accounts.get(accountId);
            if (account == null) return result(false, "account_not_found");
            JSONArray refs = references(account); String sourceScope = store.getPrivacyLedger().fingerprint();
            if (name.equals("accounts.totp.get") || name.equals("accounts.totp.type")) {
                Totp.Value value = totp(account);
                JSONObject response = validity(value).put("sourceRefs", refs).put("accountId", accountId).put("accountRevision", account.optInt("revision"));
                if (name.equals("accounts.totp.get")) return !fresh(account, sourceScope) ? result(false, "account_source_changed") : response.put("otp", value.code).put("ephemeral", true);
                if (!allowed("phone_ui_type")) return result(false, "permission_denied").put("tool", "phone_ui_type");
                if (value.remainingMillis() < 5000L) return response.put("ok", false).put("code", "totp_window_ending").put("nextEligibleAt", value.expiresAt).put("inputRequested", false);
                if (!fresh(account, sourceScope)) return result(false, "account_source_changed");
                if (entry == null) entry = new PhoneTools(context, store)::execute;
                JSONObject typed = entry.execute("phone_ui_type", new JSONObject().put("nodeId", args.getString("nodeId")).put("text", value.code));
                response.put("ok", typed.optBoolean("ok")).put("code", typed.optString("code", "input_unverified"))
                        .put("inputRequested", typed.optBoolean("accepted")).put("nativeInput", typed).put("otpExposed", false);
                // Completion refers only to matching field contents, never account authentication.
                response.put("completionConfirmed", typed.optBoolean("completionConfirmed")); return response;
            }
            JSONArray codes = recoveryCodes(account); int total = codes.length(), reserved = 0;
            if (name.equals("accounts.recovery.state")) {
                java.util.Set<String> unique = new java.util.HashSet<>();
                for (int i = 0; i < total; i++) if (unique.add(codes.getString(i)) && (recovery.isReserved(RecoveryCodeLedger.fingerprint(accountId, codes.getString(i)))
                        || recovery.isReserved(RecoveryCodeLedger.stableFingerprint(account.getString("service"), account.getString("username"), codes.getString(i))))) reserved++;
                if (!fresh(account, sourceScope)) return result(false, "account_source_changed");
                return result(true, "ok").put("accountId", accountId).put("total", unique.size()).put("storedEntries", total).put("duplicateEntries", total - unique.size()).put("reserved", reserved).put("available", unique.size() - reserved)
                        .put("serverConsumptionVerified", false).put("sourceRefs", refs).put("metadataOnly", true);
            }
            if (nativeActionIdentity == null || nativeActionIdentity.isEmpty()) return result(false, "native_action_context_required");
            int selected = args.has("index") ? exactInt(args.opt("index"), "index") : -1;
            if (selected < -1 || selected >= total) throw new IllegalArgumentException("Recovery index is outside the stored list");
            int from = selected >= 0 ? selected : 0, until = selected >= 0 ? selected + 1 : total;
            String nativeRequest = "mfa:" + hash(nativeActionIdentity);
            for (int i = from; i < until; i++) {
                String code = codes.getString(i), fingerprint = RecoveryCodeLedger.fingerprint(accountId, code);
                JSONObject origin = new JSONObject().put("stableCodeFingerprint", RecoveryCodeLedger.stableFingerprint(account.getString("service"), account.getString("username"), code))
                        .put("accountRecordHash", hash(account.toString())).put("accountRevision", account.optInt("revision"))
                        .put("inputActor", account.optString("inputActor", "agent_authored")).put("sourceRefs", refs);
                if (!fresh(account, sourceScope)) return result(false, "account_source_changed");
                JSONObject reservation = recovery.reserve(accountId, fingerprint, nativeRequest, origin);
                if (!reservation.optBoolean("reserved")) continue;
                JSONObject persistence = publishReservation();
                if (!persistence.optBoolean("ready")) return result(false, "recovery_portable_persistence_pending").put("reservationPersisted", true)
                        .put("portablePersistencePending", true).put("persistence", persistence).put("sourceRefs", refs);
                if (!fresh(account, sourceScope)) return result(false, "account_source_changed").put("reservationPersisted", true);
                return result(true, "recovery_code_reserved").put("accountId", accountId).put("index", i).put("recoveryCode", code)
                        .put("reservation", reservation).put("reservedBeforeReveal", true).put("serverConsumptionVerified", false)
                        .put("automaticReuseAllowed", false).put("sourceRefs", refs).put("persistence", persistence)
                        .put("completionConfirmed", true).put("confirmationScope", "local_reservation_before_reveal");
            }
            return result(false, "no_unreserved_recovery_code").put("serverConsumptionVerified", false).put("sourceRefs", refs);
        } catch (JSONException malformed) { throw new IllegalArgumentException("MFA configuration or arguments are malformed", malformed); }
    }
    private JSONObject publishReservation() throws JSONException {
        if (!portable.connected()) return new JSONObject().put("ready", true).put("portablePersistence", "installation_only").put("portableReadbackVerified", false).put("reinstallPersistenceAvailable", false);
        try {
            long generation = portable.requestedGeneration(); JSONObject snapshot = OwnerPolicyBackup.capture(context, store);
            portable.sync(snapshot, generation);
            return new JSONObject().put("ready", true).put("portablePersistence", "connected_provider_verified").put("portableReadbackVerified", true).put("reinstallPersistenceAvailable", true)
                    .put("providerRemoteDurabilityVerified", false).put("fullMemoryBackupPerformed", false);
        } catch (IOException | RuntimeException unavailable) {
            portable.failure(unavailable);
            return new JSONObject().put("ready", false).put("portablePersistence", "connected_provider_pending").put("portableReadbackVerified", false).put("reinstallPersistenceAvailable", false)
                    .put("failureType", unavailable.getClass().getSimpleName());
        }
    }
    private Totp.Value totp(JSONObject account) throws JSONException {
        JSONObject extra = account.optJSONObject("extra"), config = extra == null ? null : extra.optJSONObject("totp");
        if (config == null || !(config.opt("secret") instanceof String)) throw new IllegalArgumentException("No saved TOTP seed is configured");
        String algorithm = config.has("algorithm") ? config.getString("algorithm") : "SHA1";
        int digits = config.has("digits") ? exactInt(config.opt("digits"), "digits") : 6;
        long period = config.has("period") ? exactLong(config.opt("period"), "period") : 30L;
        return Totp.generate(config.getString("secret"), algorithm, digits, period, clock.getAsLong());
    }
    private JSONArray recoveryCodes(JSONObject account) throws JSONException {
        JSONObject extra = account.optJSONObject("extra"); if (extra == null || !extra.has("recoveryCodes")) return new JSONArray();
        JSONArray codes = extra.getJSONArray("recoveryCodes");
        for (int i = 0; i < codes.length(); i++) if (!(codes.opt(i) instanceof String) || codes.getString(i).isEmpty()) throw new IllegalArgumentException("Recovery codes must be exact nonempty strings");
        return codes;
    }
    private boolean fresh(JSONObject original, String scope) throws IOException {
        if (!scope.equals(store.getPrivacyLedger().fingerprint())) return false;
        JSONObject current = accounts.get(original.optString("accountId"));
        return current != null && original.optInt("revision") == current.optInt("revision") && hash(original.toString()).equals(hash(current.toString()));
    }
    private boolean allowed(String name) { try { JSONObject flags = new JSONObject(store.getSetting("toolPermissions", "{}")); return !flags.has(name) || Boolean.TRUE.equals(flags.opt(name)); } catch (JSONException invalid) { return false; } }
    private static JSONObject validity(Totp.Value value) throws JSONException {
        return result(true, "totp_generated").put("generatedAt", value.generatedAt).put("validFrom", value.validFrom).put("expiresAt", value.expiresAt)
                .put("remainingMillis", value.remainingMillis()).put("algorithm", value.algorithm).put("digits", value.digits).put("period", value.period)
                .put("clockSource", "android_system_clock").put("clockSynchronizationVerified", false).put("authenticationVerified", false).put("completionConfirmed", false);
    }
    private static JSONArray references(JSONObject account) throws JSONException {
        JSONArray originals = account.getJSONArray("sourceRefs"), refs = new JSONArray();
        for (int i = 0; i < originals.length(); i++) { JSONObject ref = originals.getJSONObject(i); refs.put(new JSONObject().put("type", ref.getString("type")).put("id", ref.getLong("id")).put("hash", ref.getString("hash"))); }
        return refs;
    }
    private static long exactLong(Object value, String field) { if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException(field + " must be an integer"); return ((Number) value).longValue(); }
    private static int exactInt(Object value, String field) { long parsed = exactLong(value, field); if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) throw new IllegalArgumentException(field + " is out of range"); return (int) parsed; }
    private static String hash(String value) { try { StringBuilder hex = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) hex.append(String.format(Locale.ROOT, "%02x", b & 255)); return hex.toString(); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static JSONObject result(boolean ok, String code) { try { return new JSONObject().put("ok", ok).put("code", code).put("authenticationVerified", false); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
    private static JSONObject properties(String... pairs) { JSONObject result = new JSONObject(); try { for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], new JSONObject().put("type", pairs[i + 1])); return result; } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
    private static JSONObject tool(String name, String description, JSONObject properties, String... required) { try { return new JSONObject().put("name", name).put("description", description).put("inputSchema", new JSONObject().put("type", "object").put("properties", properties).put("required", new JSONArray(java.util.Arrays.asList(required))).put("additionalProperties", false)); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
}
