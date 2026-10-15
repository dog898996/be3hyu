package com.hoyoung.assistant.store;

import android.content.Context;
import android.database.Cursor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Exact account records, backed by the same durable originals, revisions and ZIP as memory.
 * Ordinary memory prompts do not pin this kind. Only an explicit account lookup returns its
 * credentials. Source visibility here follows the owner's existing exclusion/deletion controls.
 * This class does not perform a login and never claims that a supplied password was verified. */
public final class AccountStore {
    public static final String KIND = "account_record";
    public static final String KEY_PREFIX = "account:";
    public static final String OWNER_SUPPLIED = "owner_supplied";
    public static final String AGENT_GENERATED = "agent_generated";
    private static final Object ACCOUNT_MUTATION_LOCK = new Object();
    private final Context context;
    private final HoyoungStore store;

    public AccountStore(Context context, HoyoungStore store) {
        if (context == null || store == null) throw new IllegalArgumentException("An account store needs context and memory storage");
        this.context = context.getApplicationContext(); this.store = store;
    }

    /** Metadata only; query searches labels/service/usernames/URLs, never fuzzy-selects a login.
     * Pass the returned stable accountId to get() to choose between accounts on one service. */
    public JSONArray list(String query, int limit) throws IOException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Account list limit must be 1..1000");
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        synchronized (store) {
            SourcePrivacyLedger.Snapshot policy = store.getPrivacyLedger().capture();
            JSONArray result = new JSONArray();
            try (Cursor rows = store.getReadableDatabase().rawQuery(
                    "SELECT memory_key FROM memories WHERE kind=? ORDER BY updated_at DESC,memory_key", new String[]{KIND})) {
                while (rows.moveToNext() && result.length() < limit) {
                    JSONObject memory = store.getMemory(rows.getString(0));
                    if (memory == null || !sourcesAllowed(memory, policy)) continue;
                    JSONObject record = decode(memory);
                    if (needle.isEmpty() || searchable(record).contains(needle)) result.put(metadata(record));
                }
            }
            requireSamePolicy(policy); return result;
        }
    }

    /** Complete authoritative latest revision, or null. An unavailable latest source never
     * causes a fallback to an older password. No semantic search or password normalization. */
    public JSONObject get(String accountId) throws IOException {
        String key = key(accountId);
        synchronized (store) {
            SourcePrivacyLedger.Snapshot policy = store.getPrivacyLedger().capture();
            JSONObject memory = store.getMemory(key);
            JSONObject record = memory == null || !KIND.equals(memory.optString("kind"))
                    || !sourcesAllowed(memory, policy) ? null : decode(memory);
            requireSamePolicy(policy); return record;
        }
    }

    public JSONObject saveFromUi(JSONObject supplied) throws IOException { return saveFromUi(supplied, "owner"); }

    /** inputActor is captured at the real form submission. Keep native form input out of chat
     * bubbles: a structured system original records it, without fabricating a user utterance. */
    public JSONObject saveFromUi(JSONObject supplied, String inputActor) throws IOException {
        return saveNative(supplied, inputActor, "owner".equals(inputActor) ? OWNER_SUPPLIED : AGENT_GENERATED);
    }

    /** The unmodified record shown by an existing editor is its compare-and-save token.
     * Source identities also distinguish a recreated/restored record whose revision repeats. */
    public JSONObject saveFromUi(JSONObject supplied, String inputActor, JSONObject expectedRecord) throws IOException {
        return saveNative(supplied, inputActor, "owner".equals(inputActor) ? OWNER_SUPPLIED : AGENT_GENERATED, expectedRecord);
    }

    public static final class AccountChangedException extends IOException {
        public AccountChangedException() { super("The account changed or became unavailable; reopen its current record before saving"); }
    }

    public JSONObject saveFromAgent(JSONObject supplied) throws IOException {
        return saveNative(supplied, "agent_authored", AGENT_GENERATED);
    }

    /** Also supports a newly generated password for account creation. Its origin remains
     * explicit and loginVerified stays false until an actual external login is established. */
    public JSONObject saveNative(JSONObject supplied, String inputActor, String origin) throws IOException {
        return saveNative(supplied, inputActor, origin, null);
    }

    private JSONObject saveNative(JSONObject supplied, String inputActor, String origin, JSONObject expectedRecord) throws IOException {
        synchronized (ACCOUNT_MUTATION_LOCK) {
            actor(inputActor); origin(origin);
            JSONObject record = normalize(supplied, inputActor, origin);
            synchronized (store) {
                if (expectedRecord != null) {
                    JSONObject current = get(record.optString("accountId"));
                    if (current == null || !current.toString().equals(expectedRecord.toString())) throw new AccountChangedException();
                }
                if ("agent_authored".equals(inputActor)) requireNotRemovedForGeneratedInput(record, store.getPrivacyLedger().capture());
                return store.withTransaction(db -> writeNativeCopy(record, new JSONArray(), null));
            }
        }
    }

    /** Save an exact credential extracted from an actual owner original. A corrected value
     * must appear literally (or as the exact JSON-escaped string) in that original. Unchanged
     * username/password fields may retain their previous source refs. AI proposals use the
     * separate agent-generated native path, and cannot masquerade as a verified owner value. */
    public JSONObject saveFromSource(JSONObject supplied, long ownerMessageId, String ownerMessageHash) throws IOException {
        synchronized (ACCOUNT_MUTATION_LOCK) {
            synchronized (store) {
                JSONObject source = store.getMessage(ownerMessageId);
                if (source == null || !"user".equals(source.optString("role"))
                        || !source.optString("content_hash").equals(ownerMessageHash)
                        || !SourceRecordVisibility.canonicalRecordMatches("message", source)) {
                    throw new IllegalArgumentException("The supplied account original does not match an actual owner message");
                }
                String sourceActor = source.isNull("request_id") ? "owner" : store.getSetting("inputActor:" + source.optString("request_id"), "owner");
                actor(sourceActor);
                JSONObject record = normalize(supplied, sourceActor, OWNER_SUPPLIED);
                SourcePrivacyLedger.Snapshot policy = store.getPrivacyLedger().capture();
                if (!SourceRecordVisibility.allowed(store.getReadableDatabase(), policy, "message", source)) {
                    throw new IllegalArgumentException("The owner excluded the selected original account source");
                }
                requireNotRemovedFromThisOriginal(record, source, policy);
                String text = fullOriginalText(source);
                JSONObject previous = get(record.optString("accountId"));
                JSONArray references = new JSONArray().put(reference(source));
                boolean inherited = false;
                for (String field : new String[]{"username", "password"}) {
                    String value = record.optString(field);
                    if (!literal(text, value)) {
                        if (previous == null || !value.equals(previous.optString(field))) {
                            throw new IllegalArgumentException("The " + field + " differs from the exact owner original; use the agent-generated path for a proposal");
                        }
                        inherited = true;
                    }
                }
                inherited |= verifyMfaOriginal(record, previous, text);
                if (inherited) {
                    JSONArray old = previous.optJSONArray("sourceRefs");
                    for (int index = 0; old != null && index < old.length(); index++) references.put(old.optJSONObject(index));
                }
                requireSamePolicy(policy);
                final JSONArray ancestry = references;
                return store.withTransaction(db -> writeNativeCopy(record, ancestry, reference(source)));
            }
        }
    }

    /** A model may extract a seed/code from an owner original, or retain an unchanged
     * source-backed value. An invented factor uses saveFromAgent and its truthful origin. */
    private static boolean verifyMfaOriginal(JSONObject record, JSONObject previous, String text) {
        JSONObject extra = record.optJSONObject("extra"), prior = previous == null ? null : previous.optJSONObject("extra");
        if (extra == null) return false;
        boolean inherited = false;
        JSONObject totp = extra.optJSONObject("totp"), oldTotp = prior == null ? null : prior.optJSONObject("totp");
        if (totp != null && totp.has("secret")) {
            Object supplied = totp.opt("secret");
            if (!(supplied instanceof String)) throw new IllegalArgumentException("The TOTP seed must be an exact string");
            String seed = (String) supplied;
            if (!literal(text, seed)) {
                if (oldTotp == null || !(oldTotp.opt("secret") instanceof String) || !seed.equals(oldTotp.opt("secret")))
                    throw new IllegalArgumentException("The TOTP seed differs from the exact owner original; use the agent-generated path for a proposal");
                inherited = true;
            }
        }
        if (extra.has("recoveryCodes")) {
            JSONArray codes = extra.optJSONArray("recoveryCodes"), oldCodes = prior == null ? null : prior.optJSONArray("recoveryCodes");
            if (codes == null) throw new IllegalArgumentException("Recovery codes must be an exact string array");
            for (int i = 0; i < codes.length(); i++) {
                Object supplied = codes.opt(i); if (!(supplied instanceof String)) throw new IllegalArgumentException("Recovery codes must be exact strings");
                String code = (String) supplied; if (literal(text, code)) continue;
                boolean previousCode = false;
                for (int old = 0; oldCodes != null && old < oldCodes.length(); old++) if (code.equals(oldCodes.opt(old))) { previousCode = true; break; }
                if (!previousCode) throw new IllegalArgumentException("A recovery code differs from the exact owner original; use the agent-generated path for a proposal");
                inherited = true;
            }
        }
        return inherited;
    }

    /** Explicit history includes old values labeled current=false. It is never used as a
     * fallback by get(). The same owner source exclusions apply to historical revisions. */
    public JSONArray history(String accountId, int limit) throws IOException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Account history limit must be 1..1000");
        String key = key(accountId);
        synchronized (store) {
            SourcePrivacyLedger.Snapshot policy = store.getPrivacyLedger().capture();
            JSONObject current = store.getMemory(key); JSONArray result = new JSONArray();
            JSONArray revisions = store.memoryRevisions(key, limit);
            for (int index = 0; index < revisions.length(); index++) {
                JSONObject memory = revisions.optJSONObject(index);
                if (!KIND.equals(memory.optString("kind")) || !sourcesAllowed(memory, policy)) continue;
                JSONObject record = decode(memory);
                put(record, "current", current != null && current.optInt("revision") == memory.optInt("revision"));
                result.put(record);
            }
            requireSamePolicy(policy); return result;
        }
    }

    /** Remove dedicated account copies and their revisions. Chat originals and unrelated
     * facts remain intact. Portable source identities also prevent an older ZIP from reviving
     * these copies; portable account tombstones prevent re-extracting the deleted old input. */
    public JSONObject delete(String accountId, String inputActor) throws IOException {
        synchronized (ACCOUNT_MUTATION_LOCK) {
            actor(inputActor); String key = key(accountId);
            List<JSONObject> originals = new ArrayList<>();
            synchronized (store) {
                Map<String, JSONObject> unique = new LinkedHashMap<>();
                try (Cursor rows = store.getReadableDatabase().rawQuery(
                        "SELECT source_type,source_id,source_hash FROM memory_sources WHERE memory_key=? ORDER BY revision DESC,source_id",
                        new String[]{key})) {
                    while (rows.moveToNext()) {
                        JSONObject ref = object("type", rows.getString(0), "id", rows.getLong(1), "hash", rows.getString(2));
                        unique.put(rows.getString(0) + ":" + rows.getLong(1) + ":" + rows.getString(2), ref);
                    }
                }
                originals.addAll(unique.values());
            }
            // SourcePayloadDeletion owns its own lock order; do not hold the store monitor while
            // entering it. The immutable identities were captured before any revision was purged.
            JSONArray receipts = new JSONArray(); int pending = 0;
            SourcePayloadDeletion deletion = new SourcePayloadDeletion(context, store);
            Set<String> removalControls = new HashSet<>();
            JSONArray exclusions = store.getPrivacyLedger().snapshot().optJSONArray("exclusions");
            for (int index = 0; index < exclusions.length(); index++) {
                String reason = exclusions.optJSONObject(index).optString("reason");
                try { if (new JSONObject(reason).optInt("controlVersion") == 2) removalControls.add(reason); }
                catch (JSONException ordinaryReason) { /* Ordinary retrieval exclusions are not account removal controls. */ }
            }
            Throwable primaryFailure = null;
            try {
                for (JSONObject source : originals) {
                    JSONObject original = store.getMessage(source.optLong("id"));
                    if (original != null) {
                        JSONObject nativeCopy = nativeCopy(original);
                        // Only our dedicated system records are owned by this removal. Legacy or
                        // externally authored memory refs must not widen removal to a chat message.
                        if (nativeCopy == null) throw new IOException("Account deletion source is not a dedicated account copy");
                        JSONObject tombstone = object("type", "account_copy_removed", "controlVersion", 2, "accountId", accountId,
                                "credentialFingerprint", credentialFingerprint(parseRecord(nativeCopy.optString("recordJson"))));
                        JSONObject ownerOriginal = nativeCopy.optJSONObject("ownerOriginal");
                        if (ownerOriginal != null) put(tombstone, "ownerOriginal", ownerOriginal);
                        // An existing retrieval exclusion keeps its original append-only reason.
                        // Give removal its own real source identity so union with an older policy
                        // cannot discard the tombstone on a duplicate account-copy identity.
                        String control = tombstone.toString();
                        if (removalControls.add(control)) {
                            JSONObject marker = store.addMessage("system", object("type", "native_account_removal", "version", 1, "control", tombstone).toString(), null);
                            store.getPrivacyLedger().excludeSource("message", marker.optLong("id"), marker.optString("content_hash"),
                                    SourcePrivacyLedger.RETRIEVAL_EXCLUDE, control);
                        }
                        store.getPrivacyLedger().excludeSource("message", source.optLong("id"), source.optString("hash"),
                                SourcePrivacyLedger.RETRIEVAL_EXCLUDE, control);
                    }
                    JSONObject receipt = deletion.deleteSource(source.optString("type"), source.optLong("id"), source.optString("hash"),
                            "account removed by " + inputActor);
                    receipts.put(receipt); if (receipt.optBoolean("pendingDeletion")) pending++;
                }
            } catch (IOException | RuntimeException | Error failure) {
                primaryFailure = failure; throw failure;
            } finally {
                // Outside the store monitor: form-state I/O never acquires account/database locks.
                IOException cleanupFailure=null;
                try{com.hoyoung.assistant.ui.PrivateFormState.deleteAccountSnapshots(context,accountId);}
                catch(IOException failure){cleanupFailure=failure;}
                // Account UI/tool deletion bypasses the ordinary source-control UI wrapper.
                // Queue this union too, including a partly completed deletion, for its mirror.
                if (!originals.isEmpty()) try { new ExternalOwnerPolicyFolder(context).markPendingDurably(); }
                catch (IOException failure) { if(cleanupFailure==null)cleanupFailure=failure;else cleanupFailure.addSuppressed(failure); }
                if(cleanupFailure!=null){if(primaryFailure!=null)primaryFailure.addSuppressed(cleanupFailure);else throw cleanupFailure;}
            }
            JSONObject result = object("accountId", accountId, "inputActor", inputActor,
                    "affectedSourceCount", originals.size(), "sourceScope", "dedicated_account_copies_and_tracked_derivatives",
                    "pendingDeletion", pending > 0, "receipts", receipts,
                    "status", pending > 0 ? "pending" : originals.isEmpty() ? "not_found" : "completed_managed_local");
            store.recordEvent("account_removed", object("accountId", accountId, "inputActor", inputActor,
                    "affectedSourceCount", originals.size(), "pendingDeletion", pending > 0));
            return result;
        }
    }

    private JSONObject write(JSONObject record, JSONArray references) {
        JSONObject memory = store.upsertMemoryWithSources(key(record.optString("accountId")), record.toString(), KIND, references);
        return decode(memory);
    }

    private JSONObject writeNativeCopy(JSONObject record, JSONArray ancestry, JSONObject ownerOriginal) {
        JSONObject original = object("type", "native_account_input", "version", 1,
                "inputActor", record.optString("inputActor"), "origin", record.optString("origin"), "recordJson", record.toString());
        if (ownerOriginal != null) put(original, "ownerOriginal", ownerOriginal);
        JSONObject source = store.addMessage("system", original.toString(), null);
        if (ancestry.length() > 0) store.putSetting("answerSources:" + source.optLong("id"),
                object("version", 1, "sources", ancestry).toString());
        JSONObject saved = write(record, new JSONArray().put(reference(source)));
        store.putSetting("nativeAccountInput:" + source.optLong("id"), object("version", 1, "type", "native_account_input",
                "sourceMessageId", source.optLong("id"), "sourceMessageHash", source.optString("content_hash"),
                "account", metadata(saved)).toString());
        return saved;
    }

    private void requireNotRemovedFromThisOriginal(JSONObject record, JSONObject source, SourcePrivacyLedger.Snapshot policy) {
        JSONArray exclusions = policy.toJson().optJSONArray("exclusions");
        for (int index = 0; exclusions != null && index < exclusions.length(); index++) {
            JSONObject entry = exclusions.optJSONObject(index); if (entry == null) continue;
            JSONObject removed;
            try { removed = new JSONObject(entry.optString("reason")); } catch (JSONException ordinaryReason) { continue; }
            if (!"account_copy_removed".equals(removed.optString("type"))) continue;
            JSONObject original = removed.optJSONObject("ownerOriginal");
            if (original != null && source.optLong("id") == original.optLong("id")
                    && source.optString("content_hash").equals(original.optString("hash"))
                    && (record.optString("accountId").equals(removed.optString("accountId"))
                    || credentialFingerprint(record).equals(removed.optString("credentialFingerprint")))) {
                throw new IllegalArgumentException("This old account input was removed from the account store; a new owner input can recreate it");
            }
        }
    }

    private void requireNotRemovedForGeneratedInput(JSONObject record, SourcePrivacyLedger.Snapshot policy) {
        JSONArray exclusions = policy.toJson().optJSONArray("exclusions");
        for (int index = 0; exclusions != null && index < exclusions.length(); index++) {
            JSONObject entry = exclusions.optJSONObject(index); if (entry == null) continue;
            JSONObject removed;
            try { removed = new JSONObject(entry.optString("reason")); } catch (JSONException ordinaryReason) { continue; }
            if ("account_copy_removed".equals(removed.optString("type"))
                    && (record.optString("accountId").equals(removed.optString("accountId"))
                    || credentialFingerprint(record).equals(removed.optString("credentialFingerprint")))) {
                throw new IllegalArgumentException("The owner removed this account copy; a fresh owner input can recreate it");
            }
        }
    }

    private JSONObject nativeCopy(JSONObject source) throws IOException {
        if (!"system".equals(source.optString("role"))) return null;
        // System originals larger than the SQL excerpt remain exact in the normal original
        // spool. Hydrate them for this explicit account lookup, rather than silently failing
        // or truncating credentials/notes. This uses the existing verified original reader.
        String text = fullOriginalText(source);
        try {
            JSONObject value = new JSONObject(text);
            return "native_account_input".equals(value.optString("type")) && value.optInt("version") == 1 ? value : null;
        } catch (JSONException malformed) { return null; }
    }
    private String fullOriginalText(JSONObject source) throws IOException {
        String text = source.optString("text");
        if (source.optLong("raw_char_count", text.length()) <= text.length()) return text;
        StringBuilder full = new StringBuilder(); char[] chunk = new char[16_384]; int count;
        try (Reader reader = store.openOriginalReader(source.optLong("id"))) {
            while ((count = reader.read(chunk)) != -1) full.append(chunk, 0, count);
        }
        return full.toString();
    }
    private static JSONObject parseRecord(String text) {
        try { return new JSONObject(text); } catch (JSONException malformed) { throw new IllegalStateException("Native account copy is malformed", malformed); }
    }
    private static String credentialFingerprint(JSONObject record) {
        String text = JSONObject.quote(record.optString("service")) + "\n" + JSONObject.quote(record.optString("username")) + "\n" + JSONObject.quote(record.optString("password"));
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64); for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255)); return result.toString();
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private boolean sourcesAllowed(JSONObject memory, SourcePrivacyLedger.Snapshot policy) throws IOException {
        JSONArray references = memory.optJSONArray("sourceRefs");
        if (references == null || references.length() == 0) return false;
        for (int index = 0; index < references.length(); index++) {
            JSONObject ref = references.optJSONObject(index);
            if (ref == null || !"message".equals(ref.optString("type"))) return false;
            JSONObject source = store.getMessage(ref.optLong("id"));
            if (!sourceMatches(ref, source, policy)) return false;
            JSONObject copy = nativeCopy(source);
            // Authenticate the exact copied fields against the authoritative memory row,
            // not merely a nearby user source containing a guessed credential.
            if (copy == null || !memory.optString("content").equals(copy.optString("recordJson"))
                    || !accountSourceAllowed(source, copy, policy)) return false;
        }
        return true;
    }

    /** Account copies may form a long revision DAG. Walk without Java recursion so years of
     * corrections do not overflow the stack. Non-account ancestors use the existing native
     * visibility checks. Large dedicated originals are fully hydrated only on this lookup. */
    private boolean accountSourceAllowed(JSONObject start, JSONObject startCopy, SourcePrivacyLedger.Snapshot policy) throws IOException {
        Map<String, Byte> states = new HashMap<>(); ArrayDeque<AccountFrame> stack = new ArrayDeque<>();
        AccountFrame first = accountFrame(start, startCopy, policy); if (first == null) return false;
        stack.push(first); states.put(first.identity, (byte) 1);
        while (!stack.isEmpty()) {
            AccountFrame current = stack.peek();
            if (current.next == current.parents.length()) { states.put(current.identity, (byte) 2); stack.pop(); continue; }
            JSONObject ref = current.parents.optJSONObject(current.next++);
            if (ref == null || !"message".equals(ref.optString("type"))) return false;
            JSONObject parent = store.getMessage(ref.optLong("id")); if (!sourceMatches(ref, parent, policy)) return false;
            String identity = parent.optLong("id") + ":" + parent.optString("content_hash");
            Byte known = states.get(identity); if (known != null) { if (known != 2) return false; continue; }
            JSONObject account = nativeCopy(parent);
            if (account == null) {
                if (!SourceRecordVisibility.allowed(store.getReadableDatabase(), policy, "message", parent)) return false;
                states.put(identity, (byte) 2); continue;
            }
            AccountFrame child = accountFrame(parent, account, policy); if (child == null) return false;
            states.put(identity, (byte) 1); stack.push(child);
        }
        return true;
    }
    private AccountFrame accountFrame(JSONObject source, JSONObject copy, SourcePrivacyLedger.Snapshot policy) {
        if (source == null || !SourceRecordVisibility.canonicalRecordMatches("message", source)
                || !policy.allowedSource("message", source.optLong("id"), source.optString("content_hash"))) return null;
        if (copy == null) return null;
        JSONArray parents = new JSONArray();
        if (copy.has("ownerOriginal")) { if (!(copy.opt("ownerOriginal") instanceof JSONObject)) return null; parents.put(copy.optJSONObject("ownerOriginal")); }
        String ancestry = store.getSetting("answerSources:" + source.optLong("id"), null);
        if (ancestry != null) {
            JSONObject provenance;
            try { provenance = new JSONObject(ancestry); } catch (JSONException malformed) { return null; }
            if (provenance.optInt("version") != 1 || provenance.optJSONArray("sources") == null) return null;
            JSONArray refs = provenance.optJSONArray("sources"); for (int index = 0; index < refs.length(); index++) parents.put(refs.opt(index));
        }
        return new AccountFrame(source.optLong("id") + ":" + source.optString("content_hash"), parents);
    }
    private static boolean sourceMatches(JSONObject ref, JSONObject source, SourcePrivacyLedger.Snapshot policy) {
        Object id = ref == null ? null : ref.opt("id");
        return id instanceof Number && ((Number) id).doubleValue() == ref.optLong("id") && ref.optLong("id") > 0
                && source != null && "message".equals(ref.optString("type"))
                && source.optLong("id") == ref.optLong("id") && source.optString("content_hash").equals(ref.optString("hash"))
                && SourceRecordVisibility.canonicalRecordMatches("message", source)
                && policy.allowedSource("message", source.optLong("id"), source.optString("content_hash"));
    }
    private static final class AccountFrame {
        final String identity; final JSONArray parents; int next;
        AccountFrame(String identity, JSONArray parents) { this.identity = identity; this.parents = parents; }
    }

    private void requireSamePolicy(SourcePrivacyLedger.Snapshot policy) throws IOException {
        if (!policy.fingerprint.equals(store.getPrivacyLedger().fingerprint())) {
            throw new IOException("Account source controls changed during lookup; retry the current lookup");
        }
    }

    private static JSONObject normalize(JSONObject supplied, String inputActor, String origin) throws IOException {
        if (supplied == null) throw new IllegalArgumentException("An account record is required");
        JSONObject record = new JSONObject();
        String accountId = supplied.has("accountId") ? string(supplied, "accountId", false) : UUID.randomUUID().toString();
        key(accountId); put(record, "format", "hoyoung-account-v1"); put(record, "accountId", accountId);
        put(record, "service", string(supplied, "service", false));
        put(record, "username", string(supplied, "username", true));
        put(record, "password", string(supplied, "password", true));
        for (String field : new String[]{"label", "loginUrl", "notes"}) put(record, field, supplied.has(field) ? string(supplied, field, true) : "");
        if (supplied.has("extra")) {
            if (!(supplied.opt("extra") instanceof JSONObject)) throw new IllegalArgumentException("extra must be a JSON object");
            put(record, "extra", copy(supplied.optJSONObject("extra")));
        } else put(record, "extra", new JSONObject());
        put(record, "origin", origin); put(record, "inputActor", inputActor); put(record, "loginVerified", false);
        if (record.toString().length() > 500_000) throw new IllegalArgumentException("Account exceeds the memory record capacity");
        HoyoungStore.checkJsonDepth(record.toString());
        return record;
    }

    private static JSONObject decode(JSONObject memory) {
        JSONObject record;
        try { record = new JSONObject(memory.getString("content")); }
        catch (JSONException malformed) { throw new IllegalStateException("Stored account content is malformed", malformed); }
        if (!"hoyoung-account-v1".equals(record.optString("format")) || !KIND.equals(memory.optString("kind"))
                || !key(record.optString("accountId")).equals(memory.optString("key"))) {
            throw new IllegalStateException("Stored account identity differs from its memory key");
        }
        for (String field : new String[]{"service", "username", "password", "label", "loginUrl", "notes"}) string(record, field, true);
        put(record, "revision", memory.optInt("revision"));
        put(record, "createdAt", memory.optLong("created_at"));
        put(record, "updatedAt", memory.optLong("updated_at", memory.optLong("created_at")));
        put(record, "sourceRefs", copyArray(memory.optJSONArray("sourceRefs")));
        return record;
    }

    private static JSONObject metadata(JSONObject record) {
        JSONObject result = new JSONObject();
        for (String field : new String[]{"accountId", "service", "username", "label", "loginUrl", "origin", "inputActor", "revision", "createdAt", "updatedAt", "loginVerified"}) {
            put(result, field, record.opt(field));
        }
        put(result, "hasPassword", !record.optString("password").isEmpty());
        put(result, "extraFieldCount", record.optJSONObject("extra") == null ? 0 : record.optJSONObject("extra").length());
        return result;
    }

    private static String searchable(JSONObject record) {
        return (record.optString("service") + "\n" + record.optString("username") + "\n"
                + record.optString("label") + "\n" + record.optString("loginUrl")).toLowerCase(Locale.ROOT);
    }
    private static JSONObject reference(JSONObject source) {
        return object("type", "message", "id", source.optLong("id"), "hash", source.optString("content_hash"));
    }
    private static boolean literal(String text, String value) {
        // Empty is an explicitly supplied empty string, not an invented secret. JSON quoting
        // supports exact passwords containing newlines, quotes and backslashes in JSON input.
        return value.isEmpty() || text.contains(value) || text.contains(JSONObject.quote(value));
    }
    private static String key(String accountId) {
        if (accountId == null || !accountId.matches("[A-Za-z0-9_.-]{1,480}")) throw new IllegalArgumentException("Invalid stable accountId");
        return KEY_PREFIX + accountId;
    }
    private static String string(JSONObject record, String field, boolean allowEmpty) {
        Object value = record.opt(field);
        if (!(value instanceof String) || !allowEmpty && ((String) value).isEmpty()) throw new IllegalArgumentException(field + " must be an exact " + (allowEmpty ? "" : "nonempty ") + "string");
        return (String) value;
    }
    private static void actor(String value) {
        if (!("owner".equals(value) || "agent_authored".equals(value) || "owner_with_agent_input".equals(value))) throw new IllegalArgumentException("Unknown input actor");
    }
    private static void origin(String value) {
        if (!(OWNER_SUPPLIED.equals(value) || AGENT_GENERATED.equals(value))) throw new IllegalArgumentException("Unknown account origin");
    }
    private static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); } catch (JSONException impossible) { throw new IllegalArgumentException("Invalid JSON account fields", impossible); }
    }
    private static JSONArray copyArray(JSONArray value) {
        if (value == null) return new JSONArray();
        try { return new JSONArray(value.toString()); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JSONObject object(Object... values) {
        JSONObject result = new JSONObject(); for (int index = 0; index < values.length; index += 2) put(result, (String) values[index], values[index + 1]); return result;
    }
    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value == null ? JSONObject.NULL : value); } catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
    }
}
