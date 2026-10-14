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
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AccountToolsTest {
    private Context context; private HoyoungStore store; private AccountTools tools;
    @Before public void setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); clear(context.getFilesDir());clear(context.getNoBackupFilesDir());store = new HoyoungStore(context); tools = new AccountTools(context, store); }
    @After public void cleanup() { store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir()); }
    private static void clear(java.io.File file){if(file.isDirectory()){java.io.File[] children=file.listFiles();if(children!=null)for(java.io.File child:children)clear(child);}file.delete();}
    private JSONObject input() throws Exception { return new JSONObject().put("service", "fixture service").put("username", "fixture_user").put("password", "  fixture\n🙂\\value  "); }
    private JSONObject selector(String id) throws Exception { return new JSONObject().put("accountId", id); }

    @Test public void generatedAccountSaveDoesNotFabricateOwnerAuthorshipAndReadIsExact() throws Exception {
        JSONObject supplied = input(); JSONObject saved = tools.execute("accounts.save", supplied);
        assertEquals("agent_generated", saved.getJSONObject("account").getString("origin")); assertFalse(saved.toString().contains(supplied.getString("password")));
        String id = saved.getJSONObject("account").getString("accountId"); JSONObject get = tools.execute("accounts.get", selector(id));
        assertEquals(supplied.getString("password"), get.getJSONObject("account").getString("password")); assertFalse(get.getBoolean("loginPerformed")); assertFalse(get.getJSONObject("account").getBoolean("loginVerified"));
        JSONObject listed = tools.execute("accounts.list", new JSONObject().put("query", "fixture_user"));
        assertEquals(1, listed.getJSONArray("accounts").length()); assertFalse(listed.toString().contains(supplied.getString("password")));
    }
    @Test public void realSourceBoundOwnerInputIsAcceptedButInventedSourceBoundPasswordIsRejected() throws Exception {
        JSONObject supplied = input(); JSONObject owner = store.addMessage("user", supplied.toString(), null);
        supplied.put("sourceMessageId", owner.getLong("id")).put("sourceMessageHash", owner.getString("content_hash"));
        JSONObject saved = tools.execute("accounts.save", supplied);
        assertEquals("owner_supplied", saved.getJSONObject("account").getString("origin"));
        supplied.put("password", "synthetic-invented-value"); assertThrows(IllegalArgumentException.class, () -> tools.execute("accounts.save", supplied));
    }
    @Test public void sourceIdentityPairsAndExactTypesAreEnforcedBeforeAnyAccountIsWritten() throws Exception {
        JSONObject idOnly = input().put("sourceMessageId", 1);
        assertThrows(IllegalArgumentException.class, () -> tools.execute("accounts.save", idOnly));
        assertThrows(IllegalArgumentException.class, () -> tools.execute("accounts.save", input().put("password", 42)));
        assertThrows(IllegalArgumentException.class, () -> tools.execute("accounts.save", input().put("sourceMessageId", "1").put("sourceMessageHash", "a".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> tools.execute("accounts.list", new JSONObject().put("limit", 1.5)));
        assertEquals(0, tools.execute("accounts.list", new JSONObject()).getJSONArray("accounts").length());
    }
    @Test public void ownerToolSwitchPreventsAccountLookupAndWritesWithDefaultAllowed() throws Exception {
        store.putSetting("toolPermissions", "{\"accounts.save\":false}");
        assertEquals("permission_denied", tools.execute("accounts.save", input()).getString("code"));
        store.putSetting("toolPermissions", "{}"); JSONObject saved = tools.execute("accounts.save", input());
        store.putSetting("toolPermissions", "{\"accounts.get\":false}");
        assertEquals("permission_denied", tools.execute("accounts.get", selector(saved.getJSONObject("account").getString("accountId"))).getString("code"));
    }
    @Test public void correctionsRetainHistoryWhileGetReturnsOnlyLatest() throws Exception {
        JSONObject supplied = input(); JSONObject saved = tools.execute("accounts.save", supplied); String id = saved.getJSONObject("account").getString("accountId");
        supplied.put("accountId", id).put("password", "corrected-synthetic-value"); tools.execute("accounts.save", supplied);
        assertEquals("corrected-synthetic-value", tools.execute("accounts.get", selector(id)).getJSONObject("account").getString("password"));
        JSONArray history = tools.execute("accounts.history", selector(id)).getJSONArray("revisions"); assertEquals(2, history.length());
        assertTrue(history.getJSONObject(0).getBoolean("current")); assertFalse(history.getJSONObject(1).getBoolean("current"));
    }
    @Test public void deleteHasActualReceiptAndCannotReturnAnOldCredential() throws Exception {
        JSONObject saved = tools.execute("accounts.save", input()); String id = saved.getJSONObject("account").getString("accountId");
        JSONObject deleted = tools.execute("accounts.delete", selector(id)); assertTrue(deleted.toString(), deleted.getBoolean("completionConfirmed"));
        assertEquals("agent_authored", deleted.getJSONObject("deletion").getString("inputActor"));
        assertEquals("account_not_found", tools.execute("accounts.get", selector(id)).getString("code"));
        assertFalse(tools.execute("accounts.get", selector(id)).getBoolean("olderRevisionFallback"));
    }
    @Test public void unknownOrForgedControlFieldsAreNotSilentlyAccepted() throws Exception {
        assertEquals("unknown_tool", tools.execute("accounts.login", new JSONObject()).getString("code"));
        assertThrows(IllegalArgumentException.class, () -> tools.execute("accounts.save", input().put("inputActor", "owner")));
        assertThrows(IllegalArgumentException.class, () -> tools.execute("accounts.save", input().put("loginVerified", true)));
        assertThrows(IllegalArgumentException.class, () -> tools.execute("accounts.get", new JSONObject().put("accountId", JSONObject.NULL)));
    }
    @Test public void everyAccountToolUsesTheSameTypedInputSchemaAsNativePhoneTools() throws Exception {
        JSONArray definitions = tools.catalog();assertEquals(9,definitions.length());
        for(int i=0;i<definitions.length();i++){
            JSONObject tool=definitions.getJSONObject(i);assertFalse(tool.has("arguments"));
            JSONObject schema=tool.getJSONObject("inputSchema");assertEquals("object",schema.getString("type"));
            assertFalse(schema.getBoolean("additionalProperties"));assertNotNull(schema.getJSONObject("properties"));assertNotNull(schema.getJSONArray("required"));
        }
    }
}
