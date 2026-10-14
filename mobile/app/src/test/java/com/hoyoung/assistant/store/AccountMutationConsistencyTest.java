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
public final class AccountMutationConsistencyTest {
    private Context context;
    private HoyoungStore store;
    private AccountStore accounts;
    private InternalFiles files;

    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        reset();
    }

    @After public void cleanup() throws Exception {
        if (store != null) store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir());
        context.getSharedPreferences("owner-policy-folder", 0).edit().clear().commit();
    }

    private void reset() throws Exception {
        if (store != null) store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir());
        context.getSharedPreferences("owner-policy-folder", 0).edit().clear().commit();
        files = new InternalFiles(context);
        store = new HoyoungStore(context);
        accounts = new AccountStore(context, store);
    }

    @Test public void deletingAnAlreadyExcludedCopyStillBlocksReextractingItsOldOwnerInput() throws Exception {
        JSONObject input = input(), owner = owner(input);
        JSONObject saved = accounts.saveFromSource(input, owner.getLong("id"), owner.getString("content_hash"));
        exclude(saved);
        assertEquals("completed_managed_local", accounts.delete(saved.getString("accountId"), "owner").getString("status"));
        assertNotNull(store.getMessage(owner.getLong("id")));
        assertNull(accounts.get(saved.getString("accountId")));
        assertThrows(IllegalArgumentException.class, () -> accounts.saveFromSource(input, owner.getLong("id"), owner.getString("content_hash")));
        assertThrows(IllegalArgumentException.class, () -> accounts.saveFromAgent(input));
    }

    @Test public void eitherPortableUnionOrderRetainsRemovalAfterAnOlderExclusionAndColdRestore() throws Exception {
        JSONObject input = input(), owner = owner(input);
        JSONObject saved = accounts.saveFromSource(input, owner.getLong("id"), owner.getString("content_hash"));
        byte[] archive = backup();
        exclude(saved);
        JSONObject olderPolicy = OwnerPolicyBackup.capture(context, store);
        accounts.delete(saved.getString("accountId"), "owner");
        JSONObject removedPolicy = OwnerPolicyBackup.capture(context, store);
        JSONObject[] unions = { OwnerPolicyBackup.union(olderPolicy, removedPolicy), OwnerPolicyBackup.union(removedPolicy, olderPolicy) };
        for (JSONObject policy : unions) {
            reset();
            OwnerPolicyBackup.merge(context, store, policy);
            files.importBackup(new ByteArrayInputStream(archive), store);
            OwnerPolicyBackup.reconcile(context, store);
            assertNull(accounts.get(saved.getString("accountId")));
            assertNotNull(store.getMessage(owner.getLong("id")));
            assertThrows(IllegalArgumentException.class, () -> accounts.saveFromSource(input, owner.getLong("id"), owner.getString("content_hash")));
        }
    }

    @Test public void deletionDurablyQueuesThePreviouslySyncedPolicyFolder() throws Exception {
        JSONObject saved = accounts.saveFromUi(input());
        context.getSharedPreferences("owner-policy-folder", 0).edit().putString("tree", "content://fixture/tree/owner")
                .putBoolean("pending", false).putLong("generation", 7).commit();
        accounts.delete(saved.getString("accountId"), "owner");
        ExternalOwnerPolicyFolder folder = new ExternalOwnerPolicyFolder(context);
        assertTrue(folder.pending());
        assertTrue(folder.requestedGeneration() > 7);
    }

    @Test public void staleEditorCannotOverwriteANewerPasswordAndMfaCorrection() throws Exception {
        JSONObject initial = accounts.saveFromUi(input().put("extra", new JSONObject().put("recoveryCodes", new JSONArray().put("fixture-old-code"))));
        JSONObject corrected = accounts.saveFromAgent(new JSONObject(initial.toString()).put("password", "fixture-corrected-password")
                .put("extra", new JSONObject().put("recoveryCodes", new JSONArray().put("fixture-new-code"))));
        long originalCount = store.messageCount();
        JSONObject stale = new JSONObject(initial.toString()).put("notes", "Only the old editor's notes changed");
        assertThrows(AccountStore.AccountChangedException.class, () -> accounts.saveFromUi(stale, "owner", initial));
        assertEquals(corrected.toString(), accounts.get(initial.getString("accountId")).toString());
        assertEquals(originalCount, store.messageCount());
        assertEquals(2, accounts.history(initial.getString("accountId"), 10).length());
    }

    @Test public void excludedOrDeletedRecordCannotBeRecreatedByItsStaleEditor() throws Exception {
        JSONObject initial = accounts.saveFromUi(input());
        exclude(initial);
        assertThrows(AccountStore.AccountChangedException.class, () -> accounts.saveFromUi(initial, "owner", initial));
        accounts.delete(initial.getString("accountId"), "owner");
        assertThrows(AccountStore.AccountChangedException.class, () -> accounts.saveFromUi(initial, "owner", initial));
        assertNull(accounts.get(initial.getString("accountId")));
    }

    @Test public void sameRevisionAfterDeleteAndRecreateStillRejectsThePreviousEditor() throws Exception {
        JSONObject initial = accounts.saveFromUi(input());
        accounts.delete(initial.getString("accountId"), "owner");
        JSONObject recreated = accounts.saveFromUi(new JSONObject(initial.toString()).put("password", "fixture-recreated-password"));
        assertEquals(initial.getInt("revision"), recreated.getInt("revision"));
        assertThrows(AccountStore.AccountChangedException.class, () -> accounts.saveFromUi(initial, "owner", initial));
        assertEquals("fixture-recreated-password", accounts.get(initial.getString("accountId")).getString("password"));
    }

    @Test public void unchangedEditorTokenAllowsAnExplicitCorrection() throws Exception {
        JSONObject initial = accounts.saveFromUi(input());
        JSONObject saved = accounts.saveFromUi(new JSONObject(initial.toString()).put("notes", "new owner note"), "owner", initial);
        assertEquals(2, saved.getInt("revision"));
        assertEquals("new owner note", accounts.get(initial.getString("accountId")).getString("notes"));
    }

    private JSONObject input() throws Exception {
        return new JSONObject().put("service", "fixture").put("username", "fixture_user").put("password", "fixture-password");
    }

    private JSONObject owner(JSONObject input) {
        return store.addMessage("user", "Keep unrelated owner text. " + input, null);
    }

    private void exclude(JSONObject saved) throws Exception {
        JSONObject source = saved.getJSONArray("sourceRefs").getJSONObject(0);
        store.getPrivacyLedger().excludeSource("message", source.getLong("id"), source.getString("hash"),
                SourcePrivacyLedger.RETRIEVAL_EXCLUDE, "ordinary owner retrieval exclusion");
    }

    private byte[] backup() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        files.exportBackup(output, store);
        return output.toByteArray();
    }

    private static void clear(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) clear(child);
        }
        if (!file.delete()) throw new IOException("Fixture cleanup failed");
    }
}
