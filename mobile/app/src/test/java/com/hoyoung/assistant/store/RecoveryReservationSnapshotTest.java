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

import java.io.File;
import java.io.IOException;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RecoveryReservationSnapshotTest {
    private Context context;
    private HoyoungStore store;
    private RecoveryCodeLedger ledger;

    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir());
        context.getSharedPreferences("owner-policy-folder", 0).edit().clear().commit();
        new InternalFiles(context);
        store = new HoyoungStore(context);
        ledger = new RecoveryCodeLedger(context);
    }

    @After public void cleanup() throws Exception {
        if (store != null) store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir());
        context.getSharedPreferences("owner-policy-folder", 0).edit().clear().commit();
    }

    @Test public void metadataSnapshotIncludesStableAliasesAndDoesNotAuthorizeASecondReservation() throws Exception {
        String oldFingerprint = RecoveryCodeLedger.fingerprint("old-account", "fixture-code");
        String stable = RecoveryCodeLedger.stableFingerprint("fixture-service", "fixture-user", "fixture-code");
        JSONObject origin = new JSONObject().put("stableCodeFingerprint", stable);
        assertTrue(ledger.reserve("old-account", oldFingerprint, "first-native-action", origin).getBoolean("reserved"));
        Set<String> snapshot = ledger.reservedFingerprints();
        assertTrue(snapshot.contains(oldFingerprint));
        assertTrue(snapshot.contains(stable));
        assertFalse(ledger.reserve("new-account", RecoveryCodeLedger.fingerprint("new-account", "fixture-code"), "second-native-action", origin).getBoolean("reserved"));

        String later = RecoveryCodeLedger.fingerprint("old-account", "fixture-later-code");
        assertTrue(ledger.reserve("old-account", later, "later-native-action", new JSONObject()).getBoolean("reserved"));
        assertFalse(snapshot.contains(later));
        assertTrue(ledger.reservedFingerprints().contains(later));
    }

    @Test public void stateCountsAccountAndStableReservationsOncePerDistinctCode() throws Exception {
        JSONObject account = new AccountStore(context, store).saveFromUi(new JSONObject()
                .put("service", "fixture-service").put("username", "fixture-user").put("password", "fixture-password")
                .put("extra", new JSONObject().put("recoveryCodes", new JSONArray()
                        .put("fixture-alias-code").put("fixture-local-code").put("fixture-free-code").put("fixture-alias-code"))));
        String accountId = account.getString("accountId");
        ledger.reserve("previous-account-id", RecoveryCodeLedger.fingerprint("previous-account-id", "fixture-alias-code"), "previous-action",
                new JSONObject().put("stableCodeFingerprint", RecoveryCodeLedger.stableFingerprint("fixture-service", "fixture-user", "fixture-alias-code")));
        ledger.reserve(accountId, RecoveryCodeLedger.fingerprint(accountId, "fixture-local-code"), "local-action", new JSONObject());
        JSONObject result = new AccountMfaTools(context, store, null).execute("accounts.recovery.state", new JSONObject().put("accountId", accountId), null);
        assertTrue(result.getBoolean("ok"));
        assertEquals(4, result.getInt("storedEntries"));
        assertEquals(3, result.getInt("total"));
        assertEquals(1, result.getInt("duplicateEntries"));
        assertEquals(2, result.getInt("reserved"));
        assertEquals(1, result.getInt("available"));
        assertFalse(result.toString().contains("fixture-alias-code"));
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
