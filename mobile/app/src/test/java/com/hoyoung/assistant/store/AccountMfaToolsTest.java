package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AccountMfaToolsTest {
    private static final String SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private Context context; private HoyoungStore store; private AccountStore accounts; private AccountMfaTools tools;
    private AtomicLong now; private AtomicInteger entries; private AtomicReference<String> entered;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); clear(context.getFilesDir()); clear(context.getNoBackupFilesDir());
        context.getSharedPreferences("owner-policy-folder", 0).edit().clear().commit();
        store = new HoyoungStore(context); accounts = new AccountStore(context, store); now = new AtomicLong(59_000); entries = new AtomicInteger(); entered = new AtomicReference<>();
        tools = new AccountMfaTools(context, store, now::get, (name, args) -> { assertEquals("phone_ui_type", name); entries.incrementAndGet(); entered.set(args.optString("text"));
            try { return new JSONObject().put("ok", true).put("code", "text_set").put("accepted", true).put("completionConfirmed", true).put("authenticationVerified", false); } catch (Exception invalid) { throw new IllegalStateException(invalid); } });
    }
    @After public void cleanup() { store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); clear(context.getFilesDir()); clear(context.getNoBackupFilesDir()); context.getSharedPreferences("owner-policy-folder", 0).edit().clear().commit(); }
    private JSONObject input() throws Exception { return new JSONObject().put("service", "synthetic-MFA-site").put("username", "synthetic-mfa-user").put("password", "synthetic-password")
            .put("extra", new JSONObject().put("totp", new JSONObject().put("secret", SEED).put("algorithm", "SHA1").put("digits", 8).put("period", 30))
                    .put("recoveryCodes", new JSONArray().put(" synthetic-one-time-A ").put("synthetic-one-time-B-🙂"))); }
    private JSONObject saved() throws Exception { return accounts.saveFromUi(input()); }
    private JSONObject select(JSONObject record) throws Exception { return new JSONObject().put("accountId", record.getString("accountId")); }
    @Test public void currentSeedGeneratesPublishedRfcValueWithExactExpiryAndNoSeedEcho() throws Exception {
        JSONObject record = saved(), output = tools.execute("accounts.totp.get", select(record), null);
        assertEquals("94287082", output.getString("otp")); assertEquals("totp_generated", output.getString("code")); assertEquals(60_000, output.getLong("expiresAt")); assertEquals(1000, output.getLong("remainingMillis"));
        assertFalse(output.getBoolean("authenticationVerified")); assertFalse(output.getBoolean("clockSynchronizationVerified")); assertTrue(output.getBoolean("ephemeral")); assertTrue(output.getJSONArray("sourceRefs").length() > 0); assertFalse(output.toString().contains(SEED));
    }
    @Test public void freshNativeFillComputesImmediatelyAndDoesNotEchoOtpOrClaimLogin() throws Exception {
        now.set(1_234_567_890_000L); JSONObject record = saved(); JSONObject output = tools.execute("accounts.totp.type", select(record).put("nodeId", "3:4"), null);
        assertEquals(1, entries.get()); assertEquals("89005924", entered.get()); assertTrue(output.getBoolean("inputRequested")); assertTrue(output.getBoolean("completionConfirmed"));
        assertFalse(output.getBoolean("authenticationVerified")); assertFalse(output.getBoolean("otpExposed")); assertFalse(output.toString().contains("89005924")); assertFalse(output.toString().contains(SEED));
    }
    @Test public void endingClockWindowDefersWithoutTypingSleepingOrClaimingSuccess() throws Exception {
        JSONObject record = saved(), output = tools.execute("accounts.totp.type", select(record).put("nodeId", "3:4"), null);
        assertEquals("totp_window_ending", output.getString("code")); assertFalse(output.getBoolean("ok")); assertEquals(60_000, output.getLong("nextEligibleAt")); assertEquals(0, entries.get()); assertFalse(output.has("otp"));
    }
    @Test public void bothTheMfaToolAndPhoneTypingSwitchMustAllowNativeFill() throws Exception {
        now.set(60_000); JSONObject record = saved();
        store.putSetting("toolPermissions", "{\"phone_ui_type\":false}"); assertEquals("permission_denied", tools.execute("accounts.totp.type", select(record).put("nodeId", "3:4"), null).getString("code"));
        store.putSetting("toolPermissions", "{\"accounts.totp.type\":false}"); assertEquals("permission_denied", tools.execute("accounts.totp.type", select(record).put("nodeId", "3:4"), null).getString("code")); assertEquals(0, entries.get());
    }
    @Test public void latestSeedCorrectionIsUsedAndExcludedOriginalCannotProduceOtp() throws Exception {
        JSONObject record = saved(), corrected = input().put("accountId", record.getString("accountId")); corrected.getJSONObject("extra").getJSONObject("totp").put("secret", "MY");
        JSONObject current = accounts.saveFromUi(corrected), output = tools.execute("accounts.totp.get", select(current), null);
        assertEquals(Totp.generate("MY", "SHA1", 8, 30, now.get()).code, output.getString("otp"));
        JSONObject source = current.getJSONArray("sourceRefs").getJSONObject(0); store.getPrivacyLedger().excludeSource("message", source.getLong("id"), source.getString("hash"), "retrieval_exclude", "synthetic owner exclusion");
        assertEquals("account_not_found", tools.execute("accounts.totp.get", select(current), null).getString("code")); assertEquals(0, entries.get());
    }
    @Test public void reservationIsDurableBeforeRevealIdempotentPerNativeActionAndNotServerUsed() throws Exception {
        JSONObject record = saved(), args = select(record); JSONObject first = tools.execute("accounts.recovery.reserve", args, "root:reserve-one");
        assertEquals(" synthetic-one-time-A ", first.getString("recoveryCode")); assertTrue(first.getBoolean("reservedBeforeReveal")); assertFalse(first.getBoolean("serverConsumptionVerified"));
        assertEquals("installation_only", first.getJSONObject("persistence").getString("portablePersistence")); assertFalse(first.getJSONObject("persistence").getBoolean("reinstallPersistenceAvailable"));
        JSONObject registry = new RecoveryCodeLedger(context).snapshot(); assertEquals(1, registry.getJSONArray("reservations").length()); assertFalse(registry.toString().contains("synthetic-one-time-A"));
        JSONObject retry = tools.execute("accounts.recovery.reserve", args, "root:reserve-one"); assertEquals("recovery_publication_unavailable",retry.getString("code"));assertFalse(retry.has("recoveryCode"));
        JSONObject second = tools.execute("accounts.recovery.reserve", args, "root:reserve-two"); assertEquals("synthetic-one-time-B-🙂", second.getString("recoveryCode"));
        JSONObject state = tools.execute("accounts.recovery.state", args, null); assertEquals(2, state.getInt("reserved")); assertEquals(0, state.getInt("available")); assertFalse(state.toString().contains("synthetic-one-time"));
        assertEquals("no_unreserved_recovery_code", tools.execute("accounts.recovery.reserve", args, "root:reserve-three").getString("code"));
    }
    @Test public void modelCannotReserveOrReplayACodeWithoutANativeActionContext() throws Exception {
        JSONObject record = saved(); assertEquals("native_action_context_required", tools.execute("accounts.recovery.reserve", select(record), null).getString("code")); assertEquals(0, new RecoveryCodeLedger(context).snapshot().getJSONArray("reservations").length());
        AccountTools facade = new AccountTools(context, store); assertThrows(IllegalArgumentException.class, () -> facade.execute("accounts.recovery.reserve", select(record).put("requestId", "forged-old-action"), "actual-native-action"));
    }
    @Test public void malformedMfaCannotProduceCodeOrTouchTheTarget() throws Exception {
        JSONObject supplied = input(); supplied.getJSONObject("extra").getJSONObject("totp").put("secret", "not-base32-!"); JSONObject record = accounts.saveFromUi(supplied);
        assertThrows(IllegalArgumentException.class, () -> tools.execute("accounts.totp.get", select(record), null)); assertEquals(0, entries.get());
    }
    @Test public void oldFullBackupCannotMakeAReservedRecoveryCodeAvailableAgain() throws Exception {
        JSONObject record = saved(); InternalFiles files = new InternalFiles(context); ByteArrayOutputStream old = new ByteArrayOutputStream(); files.exportBackup(old, store);
        assertEquals(" synthetic-one-time-A ", tools.execute("accounts.recovery.reserve", select(record), "native-before-restore").getString("recoveryCode"));
        files.importBackup(new ByteArrayInputStream(old.toByteArray()), store); OwnerPolicyBackup.reconcile(context, store);
        assertEquals("synthetic-one-time-B-🙂", tools.execute("accounts.recovery.reserve", select(record), "native-after-restore").getString("recoveryCode"));
    }
    @Test public void duplicateStoredCodesAreNotCountedAsSeparateAvailableServerCodes() throws Exception {
        JSONObject supplied = input(); supplied.getJSONObject("extra").put("recoveryCodes", new JSONArray().put("same-synthetic-code").put("same-synthetic-code")); JSONObject record = accounts.saveFromUi(supplied);
        JSONObject state = tools.execute("accounts.recovery.state", select(record), null); assertEquals(1, state.getInt("total")); assertEquals(2, state.getInt("storedEntries")); assertEquals(1, state.getInt("duplicateEntries")); assertEquals(1, state.getInt("available"));
    }
    @Test public void linkedFolderFailureWithholdsCodeAndSameNativeRetryPublishesBeforeReveal() throws Exception {
        JSONObject record = saved(); context.getSharedPreferences("owner-policy-folder", 0).edit().putString("tree", "content://synthetic.documents/tree/owner").commit();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true); MemoryTree tree = new MemoryTree();
        ExternalOwnerPolicyFolder folder = new ExternalOwnerPolicyFolder(context, uri -> { if (fail.get()) throw new IOException("synthetic provider unavailable"); return tree; });
        AccountMfaTools linked = new AccountMfaTools(context, store, now::get, null, folder);
        JSONObject withheld = linked.execute("accounts.recovery.reserve", select(record), "native-linked-reserve"); assertEquals("recovery_portable_persistence_pending", withheld.getString("code")); assertFalse(withheld.has("recoveryCode")); assertTrue(withheld.getBoolean("reservationPersisted"));
        fail.set(false); JSONObject revealed = linked.execute("accounts.recovery.reserve", select(record), "native-linked-reserve"); assertEquals(" synthetic-one-time-A ", revealed.getString("recoveryCode")); assertEquals("connected_provider_verified", revealed.getJSONObject("persistence").getString("portablePersistence"));
        JSONObject external = folder.readConnectedFolder(); assertEquals(1, external.getJSONObject("recoveryReservations").getJSONArray("reservations").length()); assertFalse(external.toString().contains("synthetic-one-time-A"));
    }
    @Test public void failureAfterTerminalSealDoesNotRevealOrAutomaticallyRepublishCode()throws Exception {
        JSONObject record=saved();context.getSharedPreferences("owner-policy-folder",0).edit().putString("tree","content://synthetic.documents/tree/owner").commit();
        MemoryTree tree=new MemoryTree();RecoveryCodeLedger ledger=new RecoveryCodeLedger(context);
        ExternalOwnerPolicyFolder folder=new ExternalOwnerPolicyFolder(context,uri->{
            JSONArray publications=RecoveryCodeLedger.publicationClaims(ledger.snapshot());
            for(int i=0;i<publications.length();i++)if(publications.optJSONObject(i).optBoolean("sealed"))throw new IOException("synthetic provider failed after seal");
            return tree;
        });
        AccountMfaTools linked=new AccountMfaTools(context,store,now::get,null,folder);
        JSONObject withheld=linked.execute("accounts.recovery.reserve",select(record),"native-sealed-reserve");
        assertEquals("recovery_publication_uncertain",withheld.getString("code"));assertTrue(withheld.getBoolean("publicationSealed"));assertFalse(withheld.has("recoveryCode"));assertFalse(withheld.getBoolean("automaticResumeAllowed"));
        JSONObject retry=linked.execute("accounts.recovery.reserve",select(record),"native-sealed-reserve");assertEquals("recovery_publication_unavailable",retry.getString("code"));assertFalse(retry.has("recoveryCode"));
    }
    private static final class MemoryTree implements ExternalOwnerPolicyFolder.TreeAccess {
        private final Map<Uri, String> names = new LinkedHashMap<>(); private final Map<Uri, byte[]> bytes = new LinkedHashMap<>(); private int sequence;
        public List<ExternalOwnerPolicyFolder.Document> list() { List<ExternalOwnerPolicyFolder.Document> result = new ArrayList<>(); for (Map.Entry<Uri, String> entry : names.entrySet()) result.add(new ExternalOwnerPolicyFolder.Document(entry.getKey(), entry.getValue())); return result; }
        public Uri create(String mime, String name) { Uri uri = Uri.parse("content://synthetic.documents/document/" + (++sequence)); names.put(uri, name); bytes.put(uri, new byte[0]); return uri; }
        public InputStream read(Uri uri) throws IOException { if (!bytes.containsKey(uri)) throw new IOException("Synthetic document missing"); return new ByteArrayInputStream(bytes.get(uri)); }
        public OutputStream write(Uri uri) { return new ByteArrayOutputStream() { public void close() { bytes.put(uri, toByteArray()); } }; }
        public Uri rename(Uri uri, String name) { names.put(uri, name); return uri; }
        public void delete(Uri uri) { names.remove(uri); bytes.remove(uri); }
    }
    private static void clear(File file) { if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) clear(child); } file.delete(); }
}
