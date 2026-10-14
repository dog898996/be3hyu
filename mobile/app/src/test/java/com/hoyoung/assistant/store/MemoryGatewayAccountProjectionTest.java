package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
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
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryGatewayAccountProjectionTest {
    private Context context; private HoyoungStore store; private AccountStore accounts; private MemoryGateway gateway;
    @Before public void setup(){context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());store=new HoyoungStore(context);accounts=new AccountStore(context,store);gateway=new MemoryGateway(store);}
    @After public void cleanup(){store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());}
    private static void clear(java.io.File file){if(file.isDirectory()){java.io.File[] children=file.listFiles();if(children!=null)for(java.io.File child:children)clear(child);}file.delete();}
    private JSONObject save()throws Exception{return accounts.saveFromUi(new JSONObject().put("service","source_fixture_service").put("username","fixture_user").put("password","fixture-exact-secret-9442").put("notes","private-fixture-note-4832"));}
    private MemoryGateway.Prepared prepare(String text){return gateway.prepare("account-projection-dispatch","account-projection-root","user",0,text,null,80_000,1_000_000);}
    private long source(JSONObject saved)throws Exception{return saved.getJSONArray("sourceRefs").getJSONObject(0).getLong("id");}
    @Test public void additionalRecentProjectionContainsMetadataWhileExactSourceAndCredentialsStayUntouched()throws Exception{
        JSONObject saved=save();long id=source(saved);String original=store.getMessage(id).getString("text");
        MemoryGateway.Prepared prepared=prepare("unrelated_daily_thought_8765");
        assertTrue(prepared.context.contains("account_metadata"));assertTrue(prepared.context.contains(saved.getString("accountId")));
        assertFalse(prepared.context.contains("fixture-exact-secret-9442"));assertFalse(prepared.context.contains("private-fixture-note-4832"));
        assertEquals(original,store.readOriginalText(id,0,24_000).getString("text"));
        assertEquals("fixture-exact-secret-9442",accounts.get(saved.getString("accountId")).getString("password"));
        assertEquals(5,prepared.receipts().length());gateway.assertFresh(prepared,1_000_001);
    }
    @Test public void originalOwnerChatIsNeverChangedByAccountProjection()throws Exception{
        store.addMessage("user","owner-native-chat-secret-7794 stays exact",null);save();
        assertTrue(prepare("unrelated_daily_thought_8765").context.contains("owner-native-chat-secret-7794"));
    }
    @Test public void targetedAccountOriginalSearchStillContainsTheExactRelevantSource()throws Exception{
        save();MemoryGateway.Prepared prepared=prepare("source_fixture_service");
        assertTrue(prepared.context.contains("fixture-exact-secret-9442"));assertTrue(prepared.context.contains("native_account_input"));
    }
    @Test public void sourceHashMismatchDoesNotUseAFalseMetadataProjection()throws Exception{
        JSONObject saved=save();long id=source(saved);String key="nativeAccountInput:"+id;
        JSONObject marker=new JSONObject(store.getSetting(key,"{}"));marker.put("sourceMessageHash","0".repeat(64));store.putSetting(key,marker.toString());
        MemoryGateway.Prepared prepared=prepare("unrelated_daily_thought_8765");
        assertFalse(prepared.context.contains("account_metadata"));assertTrue(prepared.context.contains("fixture-exact-secret-9442"));
    }
    @Test public void changedNativeProjectionInvalidatesPreparedContextAndOwnerSourceExclusionStillApplies()throws Exception{
        JSONObject saved=save();long id=source(saved);MemoryGateway.Prepared prepared=prepare("unrelated_daily_thought_8765");
        String key="nativeAccountInput:"+id;JSONObject marker=new JSONObject(store.getSetting(key,"{}"));marker.getJSONObject("account").put("label","changed-fixture-label");store.putSetting(key,marker.toString());
        MemoryGateway.Blocked blocked=assertThrows(MemoryGateway.Blocked.class,()->gateway.assertFresh(prepared,1_000_001));assertEquals("memory_control_changed",blocked.reason);
        JSONObject ref=saved.getJSONArray("sourceRefs").getJSONObject(0);store.getPrivacyLedger().excludeSource("message",id,ref.getString("hash"),"retrieval_exclude","owner choice");
        MemoryGateway.Prepared excluded=prepare("unrelated_daily_thought_8765");assertFalse(excluded.context.contains(saved.getString("accountId")));assertFalse(excluded.context.contains("fixture-exact-secret-9442"));
    }
}
