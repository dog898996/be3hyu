package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
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

/** Actual SQLite preparation, exact repeated settings, and unchanged native source barriers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryGatewayPromptDedupTest {
    private Context context;
    private HoyoungStore store;
    private MemoryGateway gateway;

    @Before public void setup()throws Exception {
        context=ApplicationProvider.getApplicationContext();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir());clear(context.getNoBackupFilesDir());
        store=new HoyoungStore(context);gateway=new MemoryGateway(store);
    }

    @After public void cleanup()throws Exception {
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir());clear(context.getNoBackupFilesDir());
    }

    private MemoryGateway.Prepared prepare(String text,JSONObject rendered)throws Exception {
        return gateway.prepare("dedup-dispatch","dedup-root","user",0,text,null,80_000,1_000_000,rendered);
    }

    @Test public void standingGoalsRemainMandatoryOnceWhileCanonicalProceduralReadIsUnchanged()throws Exception {
        String goals="GOAL-ONCE-6347 "+"정확한 장기 목표를 계속 기억한다. ".repeat(250);
        store.putSetting("standingGoals",goals);
        MemoryGateway.Prepared prepared=prepare("hello",null);
        assertEquals(1,occurrences(prepared.context,"GOAL-ONCE-6347"));
        assertTrue(prepared.context.contains("MANDATORY_ACTIVE_CONSTRAINTS"));
        assertFalse(prepared.context.contains("PROCEDURAL {\"setting_key\":\"standingGoals\""));
        JSONObject projection=projection(prepared,"standingGoals");
        assertEquals("MANDATORY_ACTIVE_CONSTRAINTS",projection.getString("exactTextProvidedIn"));
        assertEquals(proceduralLine("standingGoals",goals).length(),projection.getInt("projectionCharsAvoided"));
        JSONObject procedural=receipt(prepared,"procedural");
        assertEquals("success",procedural.getString("status"));
        assertEquals(1,procedural.getInt("resultCount"));
        assertEquals(1,procedural.getJSONArray("queries").getJSONObject(0).getInt("returnedRows"));
        assertEquals(5,prepared.receipts().length());
        gateway.assertFresh(prepared,1_000_001);
    }

    @Test public void fullExactRenderedInstructionAvoidsRepeatedCharactersWithoutDroppingDistinctSettingsOrSources()throws Exception {
        String custom="FULL-CUSTOM-4791 "+"사용자의 완전한 추가 지침을 보존한다. ".repeat(220);
        String fallback="DISTINCT-FALLBACK-1538";
        store.putSetting("customInstructions",custom);
        store.putSetting("personalizedInstructions",fallback);
        store.putSetting("standingGoals","LONG-TERM-GOAL-8172");
        long id=store.addMessage("user","CANONICAL-PROOF-6731 cobalt decision",null).getLong("id");
        store.upsertMemory("decision","CANONICAL-MEMORY-5219 cobalt decision","fact",new JSONArray().put(id));
        MemoryGateway.Prepared baseline=prepare("cobalt decision",null);
        MemoryGateway.Prepared optimized=prepare("cobalt decision",new JSONObject().put("customInstructions",custom));
        assertTrue(baseline.context.contains(custom));
        assertFalse(optimized.context.contains(custom));
        assertEquals(1,occurrences(custom+"\n"+optimized.context,"FULL-CUSTOM-4791"));
        assertTrue(optimized.context.contains(fallback));
        assertTrue(optimized.context.contains("CANONICAL-PROOF-6731"));
        assertTrue(optimized.context.contains("CANONICAL-MEMORY-5219"));
        assertEquals(baseline.controlFingerprint,optimized.controlFingerprint);
        assertEquals(baseline.dependencies().toString(),optimized.dependencies().toString());
        for(String layer:MemoryGateway.LAYERS) {
            JSONObject before=receipt(baseline,layer),after=receipt(optimized,layer);
            assertEquals(before.getString("queryFingerprint"),after.getString("queryFingerprint"));
            assertEquals(before.getInt("resultCount"),after.getInt("resultCount"));
        }
        JSONObject projection=projection(optimized,"customInstructions");
        assertEquals("AUTHORITATIVE_PROMPT_SETTINGS",projection.getString("exactTextProvidedIn"));
        assertEquals(proceduralLine("customInstructions",custom).length(),projection.getInt("projectionCharsAvoided"));
        // Native savings receipts are not added to the model wire just to report a saving.
        assertFalse(optimized.context.contains("duplicateSettingProjections"));
        int actualReduction=baseline.context.length()-optimized.context.length();
        assertEquals(projection.getInt("projectionCharsAvoided"),actualReduction);
        assertTrue("The actual serialized context must become smaller",actualReduction>4000);
        gateway.assertFresh(optimized,1_000_001);
    }

    @Test public void oldOverloadRetainsBothInstructionSettingsEvenIfTheirValuesAreIdentical()throws Exception {
        String instructions="IDENTICAL-DIFFERENT-KEYS-3864";
        store.putSetting("customInstructions",instructions);
        store.putSetting("personalizedInstructions",instructions);
        MemoryGateway.Prepared prepared=gateway.prepare("dedup-dispatch","dedup-root","user",0,"hello",null,80_000,1_000_000);
        assertEquals(2,occurrences(prepared.context,instructions));
        assertFalse(prepared.diagnostics().has("duplicateSettingProjections"));
        gateway.assertFresh(prepared,1_000_001);
    }

    @Test public void staleTruncatedWrongKeyAndNonStringRenderedProofCannotRemoveFullInstructions()throws Exception {
        String custom="OWNER-CUSTOM-9502 "+"must keep every clause ".repeat(90);
        store.putSetting("customInstructions",custom);
        JSONObject[] invalid={new JSONObject().put("customInstructions",custom.substring(0,30)),
                new JSONObject().put("customInstructions",custom+" different"),
                new JSONObject().put("personalizedInstructions",custom),
                new JSONObject().put("customInstructions",new JSONArray().put(custom))};
        for(JSONObject proof:invalid) {
            MemoryGateway.Prepared prepared=prepare("hello",proof);
            assertTrue(prepared.context.contains(custom));
            assertFalse(prepared.diagnostics().has("duplicateSettingProjections"));
            gateway.assertFresh(prepared,1_000_001);
        }
    }

    @Test public void onlyActuallyRenderedFallbackKeyIsDeduplicated()throws Exception {
        String fallback="EXACT-FALLBACK-6709";
        store.putSetting("personalizedInstructions",fallback);
        MemoryGateway.Prepared prepared=prepare("hello",new JSONObject().put("personalizedInstructions",fallback));
        assertFalse(prepared.context.contains(fallback));
        assertEquals("AUTHORITATIVE_PROMPT_SETTINGS",projection(prepared,"personalizedInstructions").getString("exactTextProvidedIn"));
        assertEquals(1,receipt(prepared,"procedural").getInt("resultCount"));
        gateway.assertFresh(prepared,1_000_001);
    }

    @Test public void instructionAndGoalChangesStillInvalidateDeduplicatedPreparedContext()throws Exception {
        store.putSetting("customInstructions","OWNER-INSTRUCTION-4783");
        store.putSetting("standingGoals","OWNER-GOAL-6403");
        MemoryGateway.Prepared prepared=prepare("hello",new JSONObject().put("customInstructions","OWNER-INSTRUCTION-4783"));
        store.putSetting("customInstructions","LATEST-OWNER-INSTRUCTION-7521");
        assertBlocked(prepared,"memory_control_changed");
        prepared=prepare("hello",new JSONObject().put("customInstructions","LATEST-OWNER-INSTRUCTION-7521"));
        store.putSetting("standingGoals","LATEST-OWNER-GOAL-8391");
        assertBlocked(prepared,"memory_control_changed");
    }

    @Test public void latestOwnerCorrectionAndInvalidatedAssertionKeepTheirExactSourceMeaning()throws Exception {
        store.putSetting("standingGoals","SINGLE-GOAL-9072");
        long old=store.addMessage("user","Historical erroneous assertion: height was 152cm.",null).getLong("id");
        store.upsertMemory("height","Historical erroneous assertion: height was 152cm.","fact",new JSONArray().put(old));
        store.putMemoryMetadata("height",new JSONObject().put("status","invalidated").put("correctionReason","the assertion was wrong"));
        String correction="LATEST-CORRECTION-5173: 152cm was an error, not a former height. Correct height is 180cm.";
        long latest=store.addMessage("user",correction,null).getLong("id");
        store.putSetting("ownerInputRevision",String.valueOf(latest));
        MemoryGateway.Prepared prepared=prepare("height",null);
        assertTrue(prepared.context.contains(correction));
        assertTrue(prepared.context.contains("LATEST_OWNER_INPUT (takes precedence for corrections/cancellations)"));
        assertTrue(prepared.context.contains("\"memoryStatus\":\"invalidated\""));
        assertTrue(prepared.context.contains("\"invalidatedIsNotFormerlyTrue\":true"));
        assertEquals(1,occurrences(prepared.context,"SINGLE-GOAL-9072"));
        gateway.assertFresh(prepared,1_000_001);
    }

    @Test public void excludingOriginalStillBlocksOldSnapshotAndHidesItsDerivedMemory()throws Exception {
        store.putSetting("standingGoals","RETAINED-GOAL-4302");
        JSONObject source=store.addMessage("user","PRIVATE-SOURCE-8017 cobalt",null);
        store.upsertMemory("cobalt","PRIVATE-SOURCE-8017 derived cobalt fact","fact",new JSONArray().put(source.getLong("id")));
        MemoryGateway.Prepared before=prepare("cobalt",null);
        assertTrue(before.context.contains("PRIVATE-SOURCE-8017"));
        store.getPrivacyLedger().excludeSource("message",source.getLong("id"),source.getString("content_hash"),"retrieval_exclude","owner excludes the source");
        assertBlocked(before,"memory_privacy_changed");
        MemoryGateway.Prepared after=prepare("cobalt",null);
        assertFalse(after.context.contains("PRIVATE-SOURCE-8017"));
        assertTrue(after.context.contains("RETAINED-GOAL-4302"));
        assertEquals(5,after.receipts().length());
        gateway.assertFresh(after,1_000_001);
    }

    @Test public void deletingOriginalAndRevisionHistoryCannotBeBypassedByDeduplication()throws Exception {
        store.putSetting("standingGoals","RETAINED-GOAL-9236");
        JSONObject source=store.addMessage("user","DELETED-SOURCE-3962 cobalt",null);
        store.upsertMemory("cobalt","DELETED-SOURCE-3962 old fact","fact",new JSONArray().put(source.getLong("id")));
        store.upsertMemory("cobalt","DELETED-SOURCE-3962 corrected fact","fact",new JSONArray().put(source.getLong("id")));
        MemoryGateway.Prepared before=prepare("cobalt",null);
        new SourcePayloadDeletion(context,store).deleteSource("message",source.getLong("id"),source.getString("content_hash"),"owner deletes original and derived records");
        assertNull(store.getMessage(source.getLong("id")));
        assertNull(store.getMemory("cobalt"));
        assertBlocked(before,"memory_privacy_changed");
        MemoryGateway.Prepared after=prepare("cobalt",null);
        assertFalse(after.context.contains("DELETED-SOURCE-3962"));
        assertTrue(after.context.contains("RETAINED-GOAL-9236"));
        gateway.assertFresh(after,1_000_001);
    }

    @Test public void mandatoryGoalOverflowStillBlocksRatherThanRemovingOrTruncatingTheGoal()throws Exception {
        store.putSetting("standingGoals","FULL-MANDATORY-9270 ".repeat(500));
        try {
            gateway.prepare("dedup-dispatch","dedup-root","user",0,"hello",null,4096,1_000_000,new JSONObject());
            fail("Mandatory constraints must never become an optional duplicate");
        } catch(MemoryGateway.Blocked expected) {
            assertEquals("mandatory_memory_context_overflow",expected.reason);
        }
    }

    private void assertBlocked(MemoryGateway.Prepared prepared,String reason)throws Exception {
        try {gateway.assertFresh(prepared,1_000_001);fail("The old native context must not be released");}
        catch(MemoryGateway.Blocked expected) {assertEquals(reason,expected.reason);}
    }

    private static JSONObject receipt(MemoryGateway.Prepared prepared,String layer)throws Exception {
        JSONArray receipts=prepared.receipts();
        for(int i=0;i<receipts.length();i++)if(layer.equals(receipts.getJSONObject(i).getString("layer")))return receipts.getJSONObject(i);
        throw new AssertionError("Missing canonical layer receipt: "+layer);
    }

    private static JSONObject projection(MemoryGateway.Prepared prepared,String key)throws Exception {
        JSONArray duplicates=prepared.diagnostics().getJSONArray("duplicateSettingProjections");
        for(int i=0;i<duplicates.length();i++)if(key.equals(duplicates.getJSONObject(i).getString("settingKey")))return duplicates.getJSONObject(i);
        throw new AssertionError("Missing actual duplicate-setting projection: "+key);
    }

    private static String proceduralLine(String key,String value)throws Exception {
        return "PROCEDURAL "+new JSONObject().put("setting_key",key).put("value",value)
                .put("sourceKind","effective_owner_setting").put("isActionGrant",false)+"\n";
    }

    private static int occurrences(String text,String value) {
        int count=0,offset=0;while((offset=text.indexOf(value,offset))>=0){count++;offset+=value.length();}return count;
    }

    private static void clear(File file)throws Exception {
        if(!file.exists())return;
        if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Unreadable fixture");for(File child:children)clear(child);}
        if(!file.delete())throw new java.io.IOException("Fixture cleanup failed");
    }
}
