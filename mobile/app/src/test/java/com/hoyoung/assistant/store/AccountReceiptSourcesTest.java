package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.phone.PhoneEventJournal;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.runtime.ActionReceiptJournal;
import com.hoyoung.assistant.runtime.AssistantRuntime;
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
import java.io.File;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Actual account tools and runtime receipt-read fence, using synthetic credentials only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AccountReceiptSourcesTest {
    private Context context; private HoyoungStore store; private AccountTools accounts;
    private ActionReceiptJournal receipts; private AssistantRuntime runtime;
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir()); clear(context.getNoBackupFilesDir());
        store = new HoyoungStore(context); accounts = new AccountTools(context, store); receipts = new ActionReceiptJournal(store);
        runtime = new AssistantRuntime(context, store, new InternalFiles(context), new PhoneTools(context, store), Runnable::run);
        JSONObject owner = store.addMessage("user", "inspect my synthetic account", "account-query");
        store.putSetting("requestSource:account-query", String.valueOf(owner.getLong("id")));
        store.putSetting("requestSourceHash:account-query", owner.getString("content_hash"));
    }
    @After public void cleanup() throws Exception {
        PhoneEventJournal journal = PhoneEventJournal.forContext(context);
        for (String name : new String[]{"closeWorker", "awaitClosedForTests"}) { Method method = PhoneEventJournal.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(journal); }
        store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); clear(context.getFilesDir()); clear(context.getNoBackupFilesDir());
    }
    private JSONObject input() throws Exception { return new JSONObject().put("service", "synthetic-login-site").put("username", "synthetic-owner-user").put("password", "synthetic-private-password-🙂"); }
    private JSONObject selector(String id) throws Exception { return new JSONObject().put("accountId", id); }
    private JSONObject executeReceipt(String id) throws Exception {
        Method method = AssistantRuntime.class.getDeclaredMethod("executeTool", String.class, JSONObject.class); method.setAccessible(true);
        return (JSONObject) method.invoke(runtime, "actions.receipt.read", new JSONObject().put("rootRequestId", "account-query").put("actionId", id));
    }
    private Map<String, JSONObject> cacheReads(String accountId) throws Exception {
        Map<String, JSONObject> cached = new LinkedHashMap<>();
        for (String name : new String[]{"accounts.get", "accounts.history", "accounts.list"}) {
            JSONObject result = accounts.execute(name, "accounts.list".equals(name) ? new JSONObject() : selector(accountId));
            JSONArray sources = result.getJSONArray("sourceRefs"); assertTrue(name, sources.length() > 0);
            for (int i = 0; i < sources.length(); i++) {
                JSONObject ref = sources.getJSONObject(i); assertEquals("message", ref.getString("type")); assertTrue(ref.getLong("id") > 0); assertTrue(ref.getString("hash").matches("[a-f0-9]{64}")); assertEquals(3, ref.length());
            }
            if ("accounts.list".equals(name)) assertFalse(result.toString().contains("synthetic-private-password"));
            JSONObject receipt = new JSONObject().put("status", "completed").put("name", name).put("result", result);
            String action = name.substring("accounts.".length()); receipts.put("actionReceipt:account-query:" + action, receipt);
            assertTrue(name, executeReceipt(action).getBoolean("ok")); cached.put(action, receipt);
        }
        return cached;
    }
    private void assertUnreadable(Map<String, JSONObject> cached) throws Exception {
        for (Map.Entry<String, JSONObject> entry : cached.entrySet()) {
            JSONObject read = executeReceipt(entry.getKey()); assertFalse(entry.getKey(), read.getBoolean("ok")); assertFalse(read.has("original"));
            assertFalse(read.toString().contains("synthetic-private-password")); assertFalse(read.toString().contains("synthetic-owner-user"));
            assertFalse(receipts.receiptAllowed("account-query", entry.getValue(), row -> false));
        }
    }
    @Test public void deletingAccountInvalidatesGetHistoryAndMetadataReceiptsButFreshOwnerRecreationWorks() throws Exception {
        JSONObject supplied = input(), saved = accounts.execute("accounts.save", supplied); String id = saved.getJSONObject("account").getString("accountId");
        Map<String, JSONObject> cached = cacheReads(id); assertTrue(accounts.execute("accounts.delete", selector(id)).getBoolean("completionConfirmed")); assertUnreadable(cached);
        supplied.put("accountId", id); JSONObject freshOwner = store.addMessage("user", supplied.toString(), null);
        supplied.put("sourceMessageId", freshOwner.getLong("id")).put("sourceMessageHash", freshOwner.getString("content_hash"));
        assertTrue(accounts.execute("accounts.save", supplied).getBoolean("completionConfirmed"));
        assertEquals(input().getString("password"), accounts.execute("accounts.get", selector(id)).getJSONObject("account").getString("password"));
        assertUnreadable(cached);
        JSONObject fresh = new JSONObject().put("status", "completed").put("name", "accounts.get").put("result", accounts.execute("accounts.get", selector(id)));
        receipts.put("actionReceipt:account-query:fresh", fresh); assertTrue(executeReceipt("fresh").getBoolean("ok"));
    }
    @Test public void excludingAccountSourceHidesEveryCachedLookupIncludingPasswordFreeListMetadata() throws Exception {
        JSONObject saved = accounts.execute("accounts.save", input()); String id = saved.getJSONObject("account").getString("accountId"); Map<String, JSONObject> cached = cacheReads(id);
        JSONObject source = saved.getJSONArray("sourceRefs").getJSONObject(0);
        store.getPrivacyLedger().excludeSource("message", source.getLong("id"), source.getString("hash"), "retrieval_exclude", "synthetic owner source exclusion");
        assertUnreadable(cached); assertEquals("account_not_found", accounts.execute("accounts.get", selector(id)).getString("code"));
        assertEquals(0, accounts.execute("accounts.list", new JSONObject()).getJSONArray("accounts").length());
    }
    @Test public void legacyNestedCredentialReferencesAreAlsoCheckedByTheReceiptReader() throws Exception {
        JSONObject saved = accounts.execute("accounts.save", input()); String id = saved.getJSONObject("account").getString("accountId");
        JSONObject get = accounts.execute("accounts.get", selector(id)); get.remove("sourceRefs");
        JSONObject nested = new JSONObject().put("status", "completed").put("name", "accounts.get").put("result", get);
        receipts.put("actionReceipt:account-query:legacy", nested); assertTrue(executeReceipt("legacy").getBoolean("ok"));
        JSONObject source = saved.getJSONArray("sourceRefs").getJSONObject(0); store.getPrivacyLedger().excludeSource("message", source.getLong("id"), source.getString("hash"), "retrieval_exclude", "synthetic exclusion");
        assertFalse(executeReceipt("legacy").getBoolean("ok")); assertFalse(receipts.receiptAllowed("account-query", nested, row -> false));
    }
    private static void clear(File file) { if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) clear(child); } file.delete(); }
}
