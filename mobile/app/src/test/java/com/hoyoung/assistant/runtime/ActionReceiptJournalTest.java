package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class ActionReceiptJournalTest {
    private Context context;private HoyoungStore store;private ActionReceiptJournal journal;
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);journal=new ActionReceiptJournal(store);}
    @After public void after(){store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}

    @Test public void unfinishedIndexFailsClosedAndEmptyCompletedMigrationIsReusable()throws Exception{
        assertTrue(journal.hasUnresolved());JSONObject first=journal.ensureIndex();assertTrue(first.getBoolean("ready"));assertFalse(journal.hasUnresolved());
        JSONObject second=journal.ensureIndex();assertFalse(second.getBoolean("migrated"));assertEquals(0,second.getLong("scanned"));
    }
    @Test public void pendingIntentAndFinalIndexRemovalAreAtomicAcrossRestart()throws Exception{
        journal.ensureIndex();String key="actionReceipt:root:send";journal.put(key,receipt("dispatching"));assertTrue(journal.hasUnresolved());
        store.close();store=new HoyoungStore(context);journal=new ActionReceiptJournal(store);assertFalse(journal.ensureIndex().getBoolean("migrated"));assertTrue(journal.hasUnresolved());
        journal.put(key,receipt("completed"));assertFalse(journal.hasUnresolved());assertEquals("completed",journal.get(key).getString("status"));assertEquals("1",store.getSetting("indexedReceiptCount",""));
    }
    @Test public void failedPendingIndexWriteRollsBackIntentBeforeAnyEffectCanStart()throws Exception{
        journal.ensureIndex();store.getWritableDatabase().execSQL("CREATE TRIGGER fail_pending BEFORE INSERT ON settings WHEN NEW.setting_key >= 'pendingReceipt:' AND NEW.setting_key < 'pendingReceipt;' BEGIN SELECT RAISE(ABORT,'test pending disk failure'); END");
        assertThrows(RuntimeException.class,()->journal.put("actionReceipt:root:effect",receipt("dispatching")));
        assertNull(journal.get("actionReceipt:root:effect"));assertEquals("0",store.getSetting("indexedReceiptCount",""));assertFalse(journal.hasUnresolved());
    }
    @Test public void fiveThousandArchivedReceiptsDoNotNeedAnUnboundedHistoryArray()throws Exception{
        store.withTransaction(db->{for(int i=0;i<5000;i++){String key=String.format(java.util.Locale.ROOT,"actionReceipt:history:a-%05d",i);JSONObject value=receipt("completed");if(i<32)try{value.put("result","大".repeat(100_000));}catch(Exception invalid){throw new IllegalStateException(invalid);}store.putSetting(key,value.toString());}store.putSetting("actionReceipt:live:pending",receipt("uncertain").toString());return null;});
        JSONObject result=journal.ensureIndex();assertEquals(5001,result.getLong("scanned"));assertEquals(5001,result.getLong("indexedReceiptCount"));assertTrue(journal.hasUnresolved());
        assertEquals(1,settingsCount("pendingReceipt:","pendingReceipt;"));assertFalse(journal.ensureIndex().getBoolean("migrated"));
    }
    @Test public void malformedEmptyAndUnknownStatusRecordsBecomePendingCorruption()throws Exception{
        store.putSetting("actionReceipt:root:empty","");store.putSetting("actionReceipt:root:bad","not-json");store.putSetting("actionReceipt:root:unknown","{\"status\":\"made_up\"}");
        JSONObject result=journal.ensureIndex();assertEquals(3,result.getLong("corruptReceipts"));assertTrue(journal.hasUnresolved());
        assertThrows(IllegalStateException.class,()->journal.get("actionReceipt:root:empty"));assertThrows(IllegalStateException.class,()->journal.get("actionReceipt:root:unknown"));assertNull(journal.get("actionReceipt:missing:none"));
    }
    @Test public void directLegacyNewRecordInvalidatesReadyIndexCountOnNextStartup()throws Exception{
        journal.ensureIndex();store.putSetting("actionReceipt:legacy:action",receipt("dispatching").toString());
        store.close();store=new HoyoungStore(context);journal=new ActionReceiptJournal(store);
        JSONObject result=journal.ensureIndex();assertTrue(result.getBoolean("migrated"));assertEquals(1,result.getLong("indexedReceiptCount"));assertTrue(journal.hasUnresolved());
    }
    @Test public void sameCountLegacyOverwriteIsRebuiltExplicitlyAfterRestore()throws Exception{
        journal.ensureIndex();journal.put("actionReceipt:root:action",receipt("completed"));store.putSetting("actionReceipt:root:action",receipt("uncertain").toString());
        assertFalse(journal.hasUnresolved());journal.ensureIndex(true);assertTrue(journal.hasUnresolved());
    }
    @Test public void rootOwnerResolutionDoesNotTouchOtherRootsOrArchivePayloads()throws Exception{
        journal.ensureIndex();journal.put("actionReceipt:one:a",receipt("uncertain"));journal.put("actionReceipt:one:b",receipt("completed"));journal.put("actionReceipt:two:a",receipt("dispatching"));
        String archived=store.getSetting("actionReceipt:one:b","");JSONObject result=journal.resolveFor("one",true);
        assertEquals(1,result.getInt("resolvedCount"));assertEquals("abandoned",journal.get("actionReceipt:one:a").getString("status"));assertTrue(journal.get("actionReceipt:one:a").getBoolean("ownerAbandoned"));
        assertEquals(archived,store.getSetting("actionReceipt:one:b",""));assertEquals("dispatching",journal.get("actionReceipt:two:a").getString("status"));assertTrue(journal.hasUnresolved());
    }
    @Test public void reconciliationPreservesFingerprintAndExactPreviousUnknownOutcome()throws Exception{
        journal.ensureIndex();JSONObject previous=receipt("uncertain").put("fingerprint","f".repeat(64)).put("result",new JSONObject().put("ok",false).put("providerTaskId","external-task-42"));String raw=previous.toString();
        journal.put("actionReceipt:root:action",previous);journal.resolveFor("root",false);JSONObject closed=journal.get("actionReceipt:root:action");
        assertEquals("f".repeat(64),closed.getString("fingerprint"));assertFalse(closed.getBoolean("providerOutcomeVerified"));assertFalse(closed.getBoolean("replayed"));
        JSONObject preserved=new JSONObject(store.readOriginalText(closed.getLong("previousReceiptSourceMessageId"),0,2000).getString("text"));assertEquals(raw,preserved.getString("rawReceipt"));assertFalse(journal.hasUnresolved());
    }
    @Test public void ownerCanExcludeCorruptRecordWithoutErasingItsRawEvidence()throws Exception{
        String raw="not-json original receipt";store.putSetting("actionReceipt:root:broken",raw);journal.ensureIndex();JSONObject result=journal.resolveFor("root",true);
        assertEquals(1,result.getInt("corruptReceiptCount"));JSONObject closed=journal.get("actionReceipt:root:broken");assertTrue(closed.getBoolean("previousReceiptUnreadable"));
        JSONObject original=new JSONObject(store.readOriginalText(closed.getLong("previousReceiptSourceMessageId"),0,2000).getString("text"));assertEquals(raw,original.getString("rawReceipt"));assertFalse(journal.hasUnresolved());
    }
    @Test public void snapshotRestoreKeepsPendingAndFinalReceiptsAndSupportsExplicitRebuild()throws Exception{
        journal.ensureIndex();journal.put("actionReceipt:root:pending",receipt("uncertain"));journal.put("actionReceipt:root:final",receipt("completed"));JSONObject snapshot=store.exportSnapshot();
        journal.resolveFor("root",true);assertFalse(journal.hasUnresolved());store.importSnapshot(snapshot);journal.ensureIndex(true);
        assertTrue(journal.hasUnresolved());assertEquals("completed",journal.get("actionReceipt:root:final").getString("status"));assertEquals("uncertain",journal.get("actionReceipt:root:pending").getString("status"));
    }
    @Test public void longLegacyReceiptKeyCanStillBeIndexedAndResolved()throws Exception{
        String root="r".repeat(397),key="actionReceipt:"+root+":"+"a".repeat(100);assertEquals(512,key.length());
        store.putSetting(key,receipt("uncertain").toString());journal.ensureIndex();assertTrue(journal.hasUnresolved());
        assertEquals(1,journal.resolveFor(root,true).getInt("resolvedCount"));assertFalse(journal.hasUnresolved());assertEquals("abandoned",journal.get(key).getString("status"));
    }
    @Test public void deeplyNestedLegacyReceiptIsFailClosedBeforeRecursiveJsonParsing()throws Exception {
        String raw="{\"status\":\"dispatching\",\"payload\":"+"[".repeat(1000)+"0"+"]".repeat(1000)+"}";
        store.putSetting("actionReceipt:root:deep",raw);assertEquals(1,journal.ensureIndex().getLong("corruptReceipts"));assertTrue(journal.hasUnresolved());
        assertThrows(IllegalStateException.class,()->journal.get("actionReceipt:root:deep"));assertEquals(raw,store.getSetting("actionReceipt:root:deep",""));
    }
    @Test public void completedNativeReceiptStillNeedsActualMutationConfirmation()throws Exception{
        journal.put("actionReceipt:root:write",nativeReceipt("files.write",true,false));JSONObject before=summary("root");assertFalse(before.getBoolean("ready"));assertEquals(1,before.getLong("unconfirmedMutationCount"));
        journal.put("actionReceipt:root:write",nativeReceipt("files.write",true,true));JSONObject after=summary("root");assertTrue(after.getBoolean("ready"));assertEquals(1,after.getLong("confirmedMutationCount"));assertNotEquals(before.getString("snapshotHash"),after.getString("snapshotHash"));assertEquals(journal.snapshotHash("root"),after.getString("snapshotHash"));assertFalse(after.getBoolean("overallOutcomeVerified"));
    }
    @Test public void readOnlyFailureCanRemainDiagnosticButFailedMutationBlocksArchive()throws Exception{
        journal.put("actionReceipt:root:read",nativeReceipt("memory.search",false,false));JSONObject read=summary("root");assertTrue(read.getBoolean("ready"));assertEquals(1,read.getLong("failedReadOnlyCount"));assertEquals(0,read.getLong("failedMutationCount"));
        journal.put("actionReceipt:root:write",nativeReceipt("files.write",false,false));JSONObject mixed=summary("root");assertFalse(mixed.getBoolean("ready"));assertEquals(1,mixed.getLong("failedMutationCount"));assertEquals(2,mixed.getLong("failedCount"));
        journal.put("actionReceipt:root:retry",nativeReceipt("files.write",true,true));assertFalse(summary("root").getBoolean("ready"));
    }
    @Test public void unfinishedMissingResultCorruptAndClassifierErrorsFailSummaryConservative()throws Exception{
        journal.put("actionReceipt:root:a",receipt("dispatching"));assertFalse(summary("root").getBoolean("ready"));assertEquals(1,summary("root").getLong("unfinishedCount"));
        journal.put("actionReceipt:root:a",receipt("completed"));assertEquals(1,summary("root").getLong("corruptCount"));store.putSetting("actionReceipt:root:a","broken");assertFalse(summary("root").getBoolean("ready"));assertEquals("broken",store.getSetting("actionReceipt:root:a",null));
        journal.put("actionReceipt:root:a",nativeReceipt("files.write",true,true));JSONObject errors=journal.completionSummary("root",name->{throw new IllegalStateException("unknown classifier");},row->true);assertFalse(errors.getBoolean("ready"));assertEquals(1,errors.getLong("corruptCount"));
    }
    @Test public void rootSummaryDoesNotLoadOtherArchiveRootsOrPrefixCollidingRoot()throws Exception{
        store.withTransaction(db->{for(int i=0;i<2500;i++)store.putSetting("actionReceipt:other:a-"+i,nativeReceiptUnchecked("files.write").toString());return null;});
        journal.put("actionReceipt:root:actual",nativeReceipt("memory.search",true,false));journal.put("actionReceipt:root:foreign:action",nativeReceipt("files.write",true,false));
        JSONObject own=summary("root");assertTrue(own.getBoolean("ready"));assertEquals(1,own.getLong("receiptCount"));assertEquals(1,summary("root:foreign").getLong("receiptCount"));assertFalse(summary("root:foreign").getBoolean("ready"));
    }
    @Test public void orderedSnapshotIsStableAcrossRestartAndChangesForActualSameStatusPayload()throws Exception{
        journal.put("actionReceipt:root:z",nativeReceipt("files.write",true,true));journal.put("actionReceipt:root:a",nativeReceipt("memory.search",true,false));String snapshot=journal.snapshotHash("root");store.close();store=new HoyoungStore(context);journal=new ActionReceiptJournal(store);assertEquals(snapshot,journal.snapshotHash("root"));
        journal.put("actionReceipt:unrelated:a",nativeReceipt("files.write",false,false));assertEquals(snapshot,journal.snapshotHash("root"));journal.put("actionReceipt:root:a",nativeReceipt("memory.search",true,false).put("observedValue","changed"));assertNotEquals(snapshot,journal.snapshotHash("root"));
    }
    private JSONObject summary(String root){return journal.completionSummary(root,name->!"memory.search".equals(name),row->row.optJSONObject("result")!=null&&row.optJSONObject("result").optBoolean("completionConfirmed"));}
    private static JSONObject nativeReceipt(String name,boolean ok,boolean confirmed)throws Exception{return receipt("completed").put("name",name).put("result",new JSONObject().put("ok",ok).put("completionConfirmed",confirmed));}
    private static JSONObject nativeReceiptUnchecked(String name){try{return nativeReceipt(name,true,true);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private long settingsCount(String begin,String end){try(android.database.Cursor cursor=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM settings WHERE setting_key >= ? AND setting_key < ?",new String[]{begin,end})){cursor.moveToFirst();return cursor.getLong(0);}}
    private static JSONObject receipt(String status){try{return new JSONObject().put("status",status).put("name","phone_app_launch").put("fingerprint","f".repeat(64)).put("startedAt",100);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
}
