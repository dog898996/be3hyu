package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SourceRecordVisibilityTest {
    private Context context;private HoyoungStore store;
    @Before public void before() throws Exception {context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clearPolicy();store=new HoyoungStore(context);}
    @After public void after() throws Exception {if(store!=null)store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clearPolicy();}
    @Test public void excludedOwnerMasksKnownAssistantAndNestedNativeCopiesButNotUnrelatedSources() throws Exception {
        JSONObject owner=store.addMessage("user","owner private value", "root");long parent=owner.getLong("id");store.putSetting("requestSource:root",String.valueOf(parent));
        JSONObject assistant=store.addMessage("assistant","copied private value","root");
        JSONObject system=store.addMessage("system",new JSONObject().put("goal",new JSONObject().put("sourceMessageId",parent)).toString(),null);
        JSONObject nativeCopy=store.addMessage("system",new JSONObject().put("nativeOutcomeSource",new JSONObject().put("messageId",parent)).toString(),null);
        JSONObject unrelated=store.addMessage("assistant","unrelated public text",null),newOwner=store.addMessage("user","new distinct owner statement","root");
        assertTrue(allowed(assistant));assertTrue(allowed(system));exclude("message",owner);
        assertFalse(allowed(owner));assertFalse(allowed(assistant));assertFalse(allowed(system));assertFalse(allowed(nativeCopy));assertTrue(allowed(unrelated));assertTrue(allowed(newOwner));
        // Exclusion masks known lineage, and does not claim original payload deletion or semantic duplicate detection.
        assertEquals("owner private value",store.readOriginalText(parent,0,100).getString("text"));
    }
    @Test public void invalidNativeParentAndAmbiguousSpilledCopyFailClosedWhileUnlinkedJsonRemainsVisible() throws Exception {
        JSONObject owner=store.addMessage("user","hidden seed",null);exclude("message",owner);
        JSONObject nonexistent=store.addMessage("system",new JSONObject().put("assetMessageId",999999).toString(),null);
        JSONObject malformed=store.addMessage("system",new JSONObject().put("sourceMessageId","not-an-id").toString(),null);
        JSONObject spilled=store.addMessage("system","{\"padding\":\""+"x".repeat(210000)+"\",\"sourceMessageId\":"+owner.getLong("id")+"}",null);
        JSONObject independent=store.addMessage("system","{\"note\":\"unrelated native record\"}",null);
        store.putSetting("requestSource:broken","not-an-id");JSONObject badSetting=store.addMessage("assistant","known parent cannot be parsed","broken");
        assertFalse(allowed(nonexistent));assertFalse(allowed(malformed));assertFalse(allowed(spilled));assertFalse(allowed(badSetting));assertTrue(allowed(independent));
    }
    @Test public void canonicalDeviceReplayAtNewSqliteIdRemainsExcludedButNewObservationIsVisible() throws Exception {
        JSONObject payload=new JSONObject().put("text","private notification observation");long at=1_700_000_000_000L;
        JSONObject old=store.appendDeviceEvent("notification","fixture.app",payload,at,null);exclude("device_event",old);
        JSONObject replay=store.appendDeviceEvent("notification","fixture.app",payload,at,null),different=store.appendDeviceEvent("notification","fixture.app",payload,at+1,null);
        assertNotEquals(old.getLong("id"),replay.getLong("id"));assertEquals(old.getString("content_hash"),replay.getString("content_hash"));
        SourcePrivacyLedger.Snapshot policy=store.getPrivacyLedger().capture();
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()) {assertFalse(SourceRecordVisibility.allowed(snapshot.database,policy,"device_event",replay));assertTrue(SourceRecordVisibility.allowed(snapshot.database,policy,"device_event",different));}
        JSONObject result=new MemorySourceEnumerator(store,row->true).enumerate(new JSONObject().put("type","device_event").put("limit",10));
        assertEquals(3,result.getLong("canonicalMatchingRows"));assertEquals(2,result.getInt("excludedRowsThisPage"));assertEquals(1,result.getJSONArray("sources").length());assertEquals(different.getLong("id"),result.getJSONArray("sources").getJSONObject(0).getLong("id"));assertTrue(result.getBoolean("completeToCutoff"));
    }
    private boolean allowed(JSONObject row) throws Exception {SourcePrivacyLedger.Snapshot policy=store.getPrivacyLedger().capture();try(HoyoungStore.ReadSnapshot read=store.openReadSnapshot()){return SourceRecordVisibility.allowed(read.database,policy,"message",row);}}
    private void exclude(String type,JSONObject s) throws Exception {store.getPrivacyLedger().excludeSource(type,s.getLong("id"),s.getString("content_hash"),"retrieval_exclude","explicit owner control fixture");}
    private void clearPolicy() throws Exception {for(String f:new String[]{"privacy-ledger.json","privacy-ledger.anchor.json",".privacy-ledger.lock"})Files.deleteIfExists(new java.io.File(context.getFilesDir(),f).toPath());}
}
