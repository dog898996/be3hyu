package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AccountStoreTest {
    private Context context; private HoyoungStore store; private AccountStore accounts; private InternalFiles files;
    @Before public void setup() throws Exception { context = ApplicationProvider.getApplicationContext(); reset(); }
    @After public void cleanup() throws Exception {
        if (store != null) store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); clear(context.getFilesDir());
        context.getSharedPreferences("owner-policy-folder", 0).edit().clear().commit();
    }
    private void reset() throws Exception {
        if (store != null) store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); clear(context.getFilesDir());
        context.getSharedPreferences("owner-policy-folder", 0).edit().clear().commit();
        store = new HoyoungStore(context); accounts = new AccountStore(context, store); files = new InternalFiles(context);
    }
    private static JSONObject input(String service, String username, String password) throws Exception {
        return new JSONObject().put("service", service).put("username", username).put("password", password)
                .put("label", "테스트 계정").put("loginUrl", "https://example.invalid/login").put("notes", "fixture only");
    }
    private byte[] backup() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); files.exportBackup(bytes, store); return bytes.toByteArray();
    }
    private JSONObject ref(JSONObject record) throws Exception { return record.getJSONArray("sourceRefs").getJSONObject(0); }

    @Test public void exactUnicodeWhitespaceAndArbitraryExtraValuesSurviveRestart() throws Exception {
        String password = "  가짜🙂암호\n\"quoted\"\\tail\u0000 ";
        JSONObject input = input("테스트 서비스", "한글_owner@example.invalid", password)
                .put("extra", new JSONObject().put("recoveryCodes", new JSONArray().put("fixture-only-가")));
        JSONObject saved = accounts.saveFromUi(input); String id = saved.getString("accountId");
        assertEquals(password, saved.getString("password")); assertEquals(1, saved.getInt("revision"));
        assertFalse(saved.getBoolean("loginVerified"));
        store.close(); store = new HoyoungStore(context); accounts = new AccountStore(context, store);
        JSONObject read = accounts.get(id);
        assertEquals(password, read.getString("password")); assertEquals(input.getString("username"), read.getString("username"));
        assertEquals(input.getJSONObject("extra").toString(), read.getJSONObject("extra").toString());
    }

    @Test public void multipleAccountsOnSameServiceNeedExplicitStableSelectorAndListIsMetadataOnly() throws Exception {
        JSONObject first = accounts.saveFromUi(input("Instagram fixture", "fixture_one", "secret-fixture-1"));
        JSONObject second = accounts.saveFromUi(input("Instagram fixture", "fixture_two", "secret-fixture-2"));
        JSONArray listed = accounts.list("instagram", 10); assertEquals(2, listed.length());
        assertFalse(listed.toString().contains("secret-fixture")); assertFalse(listed.getJSONObject(0).has("password"));
        assertEquals("secret-fixture-1", accounts.get(first.getString("accountId")).getString("password"));
        assertEquals("secret-fixture-2", accounts.get(second.getString("accountId")).getString("password"));
        assertEquals(0, accounts.list("secret-fixture", 10).length());
    }

    @Test public void correctionReturnsOnlyLatestAndRetainsExplicitLabeledHistory() throws Exception {
        JSONObject first = accounts.saveFromUi(input("fixture", "fixture_user", "old-fixture-value"));
        JSONObject changed = accounts.saveFromUi(new JSONObject(first.toString()).put("password", "corrected-fixture-value"));
        String id = first.getString("accountId");
        assertEquals(2, changed.getInt("revision")); assertEquals("corrected-fixture-value", accounts.get(id).getString("password"));
        JSONArray history = accounts.history(id, 20); assertEquals(2, history.length());
        assertTrue(history.getJSONObject(0).getBoolean("current")); assertFalse(history.getJSONObject(1).getBoolean("current"));
        assertEquals("old-fixture-value", history.getJSONObject(1).getString("password"));
    }

    @Test public void excludedLatestDoesNotFallBackToTheEarlierPassword() throws Exception {
        JSONObject first = accounts.saveFromUi(input("fixture", "fixture_user", "old-fixture-value"));
        JSONObject second = accounts.saveFromUi(new JSONObject(first.toString()).put("password", "latest-fixture-value"));
        JSONObject source = ref(second);
        store.getPrivacyLedger().excludeSource("message", source.getLong("id"), source.getString("hash"), "retrieval_exclude", "owner choice");
        assertNull(accounts.get(first.getString("accountId"))); assertEquals(0, accounts.list("", 20).length());
        JSONArray history = accounts.history(first.getString("accountId"), 20); assertEquals(1, history.length());
        assertFalse(history.getJSONObject(0).getBoolean("current"));
    }

    @Test public void sourceBoundSaveAcceptsExactEscapedValueButRejectsInventedOwnerPassword() throws Exception {
        JSONObject input = input("fixture", "fixture_user", "fi\n\"xt\\ure");
        JSONObject owner = store.addMessage("user", input.toString(), null);
        JSONObject saved = accounts.saveFromSource(input, owner.getLong("id"), owner.getString("content_hash"));
        assertEquals(input.getString("password"), saved.getString("password"));
        JSONObject wrong = new JSONObject(saved.toString()).put("password", "not-in-owner-original");
        assertThrows(IllegalArgumentException.class, () -> accounts.saveFromSource(wrong, owner.getLong("id"), owner.getString("content_hash")));
        assertEquals(1, accounts.get(saved.getString("accountId")).getInt("revision"));
    }

    @Test public void correctedPasswordInANewOwnerMessageKeepsExactlyThatLatestValue() throws Exception {
        JSONObject input = input("fixture", "fixture_user", "old-fixture-value");
        JSONObject owner = store.addMessage("user", "fixture_user password old-fixture-value", null);
        JSONObject first = accounts.saveFromSource(input, owner.getLong("id"), owner.getString("content_hash"));
        JSONObject correction = store.addMessage("user", "비밀번호 정정: corrected-fixture-value", null);
        JSONObject changed = new JSONObject(first.toString()).put("password", "corrected-fixture-value");
        accounts.saveFromSource(changed, correction.getLong("id"), correction.getString("content_hash"));
        assertEquals("corrected-fixture-value", accounts.get(first.getString("accountId")).getString("password"));
        assertEquals(2, accounts.history(first.getString("accountId"), 20).length());
    }

    @Test public void generatedPasswordsAreAllowedButTruthfullyLabeledUnverified() throws Exception {
        JSONObject saved = accounts.saveFromAgent(input("new account fixture", "generated_fixture_user", "generated-fixture-value"));
        assertEquals("agent_generated", saved.getString("origin")); assertEquals("agent_authored", saved.getString("inputActor"));
        assertFalse(saved.getBoolean("loginVerified"));
        JSONObject original = store.getMessage(ref(saved).getLong("id")); assertEquals("system", original.getString("role"));
        assertEquals("native_account_input", new JSONObject(original.getString("text")).getString("type"));
    }

    @Test public void nativeFormsDoNotFabricateUserMessagesAndPreserveAutomationActor() throws Exception {
        JSONObject saved = accounts.saveFromUi(input("fixture", "fixture_user", "fixture-value"), "owner_with_agent_input");
        assertEquals("owner_with_agent_input", saved.getString("inputActor"));
        assertEquals("agent_generated", saved.getString("origin"));
        assertEquals(0, store.boundedChatMessagePage(0, 40, 8000).getJSONArray("messages").length());
        assertEquals("system", store.getMessage(ref(saved).getLong("id")).getString("role"));
    }

    @Test public void realZipRestoresEveryAccountRevisionAndExactCredentialAfterColdReinstall() throws Exception {
        JSONObject first = accounts.saveFromUi(input("fixture", "fixture_user", "old-fixture-value"));
        JSONObject latest = accounts.saveFromUi(new JSONObject(first.toString()).put("password", "corrected-fixture-value"));
        byte[] archive = backup(); String id = first.getString("accountId"); reset();
        files.importBackup(new ByteArrayInputStream(archive), store); OwnerPolicyBackup.reconcile(context, store);
        assertEquals(latest.getString("password"), accounts.get(id).getString("password"));
        assertEquals(2, accounts.history(id, 20).length());
        assertEquals(1, accounts.list("fixture_user", 20).length());
    }

    @Test public void deletedAccountCannotReviveFromOldZipWithLatestPortableControlsAfterReinstall() throws Exception {
        JSONObject first = accounts.saveFromUi(input("fixture", "fixture_user", "old-fixture-value"));
        JSONObject latest = accounts.saveFromUi(new JSONObject(first.toString()).put("password", "corrected-fixture-value"));
        byte[] older = backup(); String id = first.getString("accountId");
        JSONObject removed = accounts.delete(id, "owner"); assertEquals("completed_managed_local", removed.getString("status"));
        assertEquals(2, removed.getInt("affectedSourceCount")); assertNull(accounts.get(id));
        JSONObject controls = OwnerPolicyBackup.capture(context, store); assertEquals(2, controls.getJSONArray("deletions").length());
        reset(); OwnerPolicyBackup.merge(context, store, controls);
        files.importBackup(new ByteArrayInputStream(older), store); OwnerPolicyBackup.reconcile(context, store);
        assertNull(accounts.get(id)); assertEquals(0, accounts.history(id, 20).length());
        assertNull(store.getMessage(ref(first).getLong("id"))); assertNull(store.getMessage(ref(latest).getLong("id")));
    }

    @Test public void sourcePhysicalDeletionCascadesIntoAccountAndEveryRevision() throws Exception {
        JSONObject account = accounts.saveFromUi(input("fixture", "fixture_user", "fixture-value")); JSONObject source = ref(account);
        new SourcePayloadDeletion(context, store).deleteSource("message", source.getLong("id"), source.getString("hash"), "owner choice");
        assertNull(accounts.get(account.getString("accountId"))); assertNull(store.getMemory("account:" + account.getString("accountId")));
        assertEquals(0, accounts.history(account.getString("accountId"), 20).length());
    }

    @Test public void latestSourcePolicySurvivesRestoringAnOlderArchiveInSameInstallation() throws Exception {
        JSONObject saved = accounts.saveFromUi(input("fixture", "fixture_user", "fixture-value")); byte[] older = backup();
        accounts.delete(saved.getString("accountId"), "owner"); files.importBackup(new ByteArrayInputStream(older), store);
        assertNull(accounts.get(saved.getString("accountId"))); OwnerPolicyBackup.reconcile(context, store);
        assertNull(store.getMemory("account:" + saved.getString("accountId")));
    }

    @Test public void accountRemovalKeepsOrdinaryOwnerOriginalAndUnrelatedMemoryButBlocksOldReextraction() throws Exception {
        JSONObject owner = store.addMessage("user", "나는 달리기를 좋아한다. fixture_user password fixture-value", null);
        store.upsertMemory("unrelated-preference", "달리기를 좋아함", "preference", new JSONArray().put(owner.getLong("id")));
        JSONObject supplied = input("fixture", "fixture_user", "fixture-value");
        JSONObject account = accounts.saveFromSource(supplied, owner.getLong("id"), owner.getString("content_hash"));
        JSONObject removed = accounts.delete(account.getString("accountId"), "owner");
        assertEquals("completed_managed_local", removed.getString("status"));
        assertEquals(owner.getString("text"), store.getMessage(owner.getLong("id")).getString("text"));
        assertEquals("달리기를 좋아함", store.getMemory("unrelated-preference").getString("content"));
        assertTrue(store.getPrivacyLedger().allowedSource("message", owner.getLong("id"), owner.getString("content_hash")));
        assertThrows(IllegalArgumentException.class, () -> accounts.saveFromSource(supplied, owner.getLong("id"), owner.getString("content_hash")));
        JSONObject newOwner = store.addMessage("user", "다시 계정 저장 fixture_user password fixture-value", null);
        JSONObject recreated = accounts.saveFromSource(supplied, newOwner.getLong("id"), newOwner.getString("content_hash"));
        assertNotNull(accounts.get(recreated.getString("accountId")));
    }

    @Test public void originalSourceExclusionAndPhysicalDeletionFollowDedicatedCopyAncestry() throws Exception {
        JSONObject owner = store.addMessage("user", "fixture_user password fixture-value", null);
        JSONObject account = accounts.saveFromSource(input("fixture", "fixture_user", "fixture-value"), owner.getLong("id"), owner.getString("content_hash"));
        long copyId = ref(account).getLong("id"); assertNotEquals(owner.getLong("id"), copyId);
        store.getPrivacyLedger().excludeSource("message", owner.getLong("id"), owner.getString("content_hash"), "retrieval_exclude", "owner request");
        assertNull(accounts.get(account.getString("accountId"))); assertEquals(0, accounts.list("", 20).length());
        new SourcePayloadDeletion(context, store).deleteSource("message", owner.getLong("id"), owner.getString("content_hash"), "owner request");
        assertNull(store.getMessage(copyId)); assertNull(store.getMemory("account:" + account.getString("accountId")));
    }

    @Test public void coldRestoreKeepsAccountDeleteTombstoneAndDoesNotDeleteTheSurvivingChat() throws Exception {
        JSONObject owner = store.addMessage("user", "other owner facts; fixture_user password fixture-value", null);
        JSONObject supplied = input("fixture", "fixture_user", "fixture-value");
        JSONObject account = accounts.saveFromSource(supplied, owner.getLong("id"), owner.getString("content_hash"));
        byte[] old = backup(); accounts.delete(account.getString("accountId"), "owner");
        JSONObject policy = OwnerPolicyBackup.capture(context, store); reset(); OwnerPolicyBackup.merge(context, store, policy);
        files.importBackup(new ByteArrayInputStream(old), store); OwnerPolicyBackup.reconcile(context, store);
        assertNull(accounts.get(account.getString("accountId"))); assertNotNull(store.getMessage(owner.getLong("id")));
        assertThrows(IllegalArgumentException.class, () -> accounts.saveFromSource(supplied, owner.getLong("id"), owner.getString("content_hash")));
    }

    @Test public void malformedCredentialCannotCoerceNumberOrMutateAnExistingRecord() throws Exception {
        JSONObject saved = accounts.saveFromUi(input("fixture", "fixture_user", "fixture-value"));
        JSONObject malformed = new JSONObject(saved.toString()).put("password", 1234);
        assertThrows(IllegalArgumentException.class, () -> accounts.saveFromUi(malformed));
        assertEquals("fixture-value", accounts.get(saved.getString("accountId")).getString("password"));
        assertEquals(1, accounts.history(saved.getString("accountId"), 10).length());
    }

    @Test public void longNativeAccountOriginalBeyondSqlExcerptRemainsExactAndPortable() throws Exception {
        String longNotes = "장기 계정 메모 fixture ".repeat(16_000);
        JSONObject saved = accounts.saveFromUi(input("fixture", "fixture_user", "fixture-value").put("notes", longNotes));
        JSONObject original = store.getMessage(ref(saved).getLong("id"));
        assertTrue(original.getLong("raw_char_count") > original.getString("text").length());
        assertEquals(longNotes, accounts.get(saved.getString("accountId")).getString("notes"));
        assertEquals(1, accounts.list("fixture_user", 20).length());
        byte[] archive = backup(); String id = saved.getString("accountId"); reset();
        files.importBackup(new ByteArrayInputStream(archive), store); OwnerPolicyBackup.reconcile(context, store);
        assertEquals(longNotes, accounts.get(id).getString("notes"));
        assertEquals("completed_managed_local", accounts.delete(id, "owner").getString("status"));
    }

    @Test public void actualOwnerSourceMaySupplyAnExactPasswordBeyondFirstMillionCharacters() throws Exception {
        JSONObject owner = store.addMessage("user", "large owner original ".repeat(55_000) + " fixture_user password fixture-value", null);
        JSONObject account = accounts.saveFromSource(input("fixture", "fixture_user", "fixture-value"), owner.getLong("id"), owner.getString("content_hash"));
        assertEquals("fixture-value", accounts.get(account.getString("accountId")).getString("password"));
    }

    @Test public void sourceBoundSavePreservesActualOwnerAdoptedDraftActor() throws Exception {
        JSONObject owner = store.addMessage("user", "fixture_user password fixture-value", "fixture-adopted-input");
        store.putSetting("inputActor:fixture-adopted-input", "owner_with_agent_input");
        JSONObject saved = accounts.saveFromSource(input("fixture", "fixture_user", "fixture-value"), owner.getLong("id"), owner.getString("content_hash"));
        assertEquals("owner_with_agent_input", saved.getString("inputActor"));
        assertEquals("owner_supplied", saved.getString("origin"));
    }

    @Test public void agentGeneratedLabelCannotSilentlyRecreateAnOwnerDeletedCredential() throws Exception {
        JSONObject supplied = input("fixture", "fixture_user", "fixture-value");
        JSONObject account = accounts.saveFromUi(supplied); accounts.delete(account.getString("accountId"), "owner");
        assertThrows(IllegalArgumentException.class, () -> accounts.saveFromAgent(supplied));
        assertThrows(IllegalArgumentException.class, () -> accounts.saveFromAgent(new JSONObject(account.toString()).put("password", "different-generated-value")));
        JSONObject recreated = accounts.saveFromUi(supplied, "owner");
        assertEquals("fixture-value", accounts.get(recreated.getString("accountId")).getString("password"));
    }

    @Test public void ownerBoundMfaSeedsAndRecoveryCodesMustActuallyAppearInTheOwnerOriginal() throws Exception {
        JSONObject supplied = input("fixture", "fixture_user", "fixture-value").put("extra", new JSONObject()
                .put("totp", new JSONObject().put("secret", "SYNTHETIC-OWNER-SEED"))
                .put("recoveryCodes", new JSONArray().put("synthetic-recovery-one")));
        JSONObject owner = store.addMessage("user", supplied.toString(), null);
        JSONObject saved = accounts.saveFromSource(supplied, owner.getLong("id"), owner.getString("content_hash"));
        assertEquals("owner_supplied", saved.getString("origin")); long originals = store.messageCount();
        JSONObject fabricatedSeed = new JSONObject(supplied.toString()).put("accountId", saved.getString("accountId"));
        fabricatedSeed.getJSONObject("extra").getJSONObject("totp").put("secret", "SYNTHETIC-INVENTED-SEED");
        assertThrows(IllegalArgumentException.class, () -> accounts.saveFromSource(fabricatedSeed, owner.getLong("id"), owner.getString("content_hash")));
        JSONObject fabricatedCode = new JSONObject(supplied.toString()).put("accountId", saved.getString("accountId"));
        fabricatedCode.getJSONObject("extra").put("recoveryCodes", new JSONArray().put("synthetic-invented-recovery"));
        assertThrows(IllegalArgumentException.class, () -> accounts.saveFromSource(fabricatedCode, owner.getLong("id"), owner.getString("content_hash")));
        assertEquals(originals, store.messageCount()); assertEquals("SYNTHETIC-OWNER-SEED", accounts.get(saved.getString("accountId")).getJSONObject("extra").getJSONObject("totp").getString("secret"));
        assertEquals("agent_generated", accounts.saveFromAgent(fabricatedSeed).getString("origin"));
    }

    @Test public void unchangedMfaCanInheritItsActualPreviousSourceWhenOnlyThePasswordChanges() throws Exception {
        JSONObject supplied = input("fixture", "fixture_user", "fixture-value").put("extra", new JSONObject()
                .put("totp", new JSONObject().put("secret", "SYNTHETIC-OWNER-SEED"))
                .put("recoveryCodes", new JSONArray().put("synthetic-recovery-one")));
        JSONObject owner = store.addMessage("user", supplied.toString(), null);
        JSONObject first = accounts.saveFromSource(supplied, owner.getLong("id"), owner.getString("content_hash"));
        JSONObject correctedOwner = store.addMessage("user", "fixture_user password corrected-synthetic-password", null);
        JSONObject corrected = new JSONObject(supplied.toString()).put("accountId", first.getString("accountId")).put("password", "corrected-synthetic-password");
        JSONObject latest = accounts.saveFromSource(corrected, correctedOwner.getLong("id"), correctedOwner.getString("content_hash"));
        assertEquals("SYNTHETIC-OWNER-SEED", latest.getJSONObject("extra").getJSONObject("totp").getString("secret"));
        assertEquals("synthetic-recovery-one", latest.getJSONObject("extra").getJSONArray("recoveryCodes").getString(0));
        store.getPrivacyLedger().excludeSource("message", owner.getLong("id"), owner.getString("content_hash"), "retrieval_exclude", "synthetic owner old-factor exclusion");
        assertNull("Inherited factors must remain bound to the source that actually supplied them", accounts.get(first.getString("accountId")));
    }

    private static void clear(File directory) throws IOException {
        if (!directory.exists()) return; File[] children = directory.listFiles();
        if (children != null) for (File child : children) { if (child.isDirectory()) clear(child); if (!child.delete()) throw new IOException("Fixture cleanup failed"); }
    }
}
