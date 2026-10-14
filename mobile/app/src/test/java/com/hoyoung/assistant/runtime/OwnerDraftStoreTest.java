package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteException;
import android.util.Base64;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real SQLite transactions and competing input cohorts, without remote dispatch or phone effects. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class OwnerDraftStoreTest {
    private Context context;private HoyoungStore store;private OwnerDraftStore drafts;
    @Before public void setUp(){context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);drafts=new OwnerDraftStore(store);}
    @After public void tearDown(){store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}

    @Test public void legacyRawTupleMigratesOnceAndIdentitySurvivesDatabaseReopen()throws Exception{
        String text="  정확한 원문\n\"따옴표\" 🙂\t ";JSONArray attached=attachments("one");store.putSetting("ui.draft",text);store.putSetting("ui.attachments",attached.toString());
        OwnerDraftStore.Snapshot first=drafts.read();assertEquals(text,first.text);assertEquals(attached.toString(),first.attachmentsJson);assertFalse(first.recoveryRequired);assertEquals(0,first.revision);
        store.close();store=new HoyoungStore(context);drafts=new OwnerDraftStore(store);OwnerDraftStore.Snapshot recreated=drafts.read();same(first,recreated);assertEquals(text,store.getSetting("ui.draft","missing"));
    }

    @Test public void concurrentActivityReadsUseOneDurableMigratedIdentity()throws Exception{
        store.putSetting("ui.draft","회전 중인 입력");List<OwnerDraftStore.Snapshot> snapshots=concurrent(6,()->new OwnerDraftStore(store).read());
        for(OwnerDraftStore.Snapshot snapshot:snapshots)same(snapshots.get(0),snapshot);assertEquals(0,snapshots.get(0).revision);
    }

    @Test public void actorIsPersistedWithDraftAndHumanEditDoesNotEraseAgentContribution()throws Exception{
        OwnerDraftStore.Snapshot agent=drafts.edit("agent original",new JSONArray(),UiAutomationProvenance.AGENT);assertEquals(UiAutomationProvenance.AGENT,agent.inputActor);
        store.close();store=new HoyoungStore(context);drafts=new OwnerDraftStore(store);assertEquals(agent.clientInputId,drafts.read().clientInputId);assertEquals(UiAutomationProvenance.AGENT,drafts.read().inputActor);
        OwnerDraftStore.Snapshot mixed=drafts.edit("agent original + owner edit",new JSONArray(),UiAutomationProvenance.OWNER);assertEquals(UiAutomationProvenance.MIXED,mixed.inputActor);
        assertEquals(UiAutomationProvenance.OWNER,drafts.edit("",new JSONArray(),UiAutomationProvenance.OWNER).inputActor);
        assertEquals(UiAutomationProvenance.OWNER,drafts.edit("fresh actual owner input",new JSONArray(),UiAutomationProvenance.OWNER).inputActor);
    }

    @Test public void actorIsPartOfIntegrityTupleAndTamperingCannotTurnAgentDraftIntoOwner()throws Exception{
        OwnerDraftStore.Snapshot agent=drafts.edit("preserved generated input",new JSONArray(),UiAutomationProvenance.AGENT);JSONObject state=new JSONObject(store.getSetting(OwnerDraftStore.STATE_KEY,""));state.put("inputActor",UiAutomationProvenance.OWNER);store.putSetting(OwnerDraftStore.STATE_KEY,state.toString());
        assertTrue(drafts.read().recoveryRequired);assertEquals(agent.text,drafts.read().text);
    }

    @Test public void oldSchemaOneRetainsExactInputIdRevisionAndOwnerOrigin()throws Exception{
        OwnerDraftStore.Snapshot original=drafts.edit("old schema exact input",attachments("old"));JSONObject state=new JSONObject(store.getSetting(OwnerDraftStore.STATE_KEY,""));state.put("schemaVersion",1);state.remove("inputActor");
        String identity=field(original.text)+field(original.attachmentsJson)+field(original.clientInputId)+field(String.valueOf(original.revision))+field(String.valueOf(original.recoveryRequired))+field(original.recoveryKey)+field(original.recoveryReason);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");String payload="utf16be-code-units-v1\u0000"+identity;byte[] bytes=new byte[payload.length()*2];for(int i=0;i<payload.length();i++){bytes[2*i]=(byte)(payload.charAt(i)>>>8);bytes[2*i+1]=(byte)payload.charAt(i);}StringBuilder hash=new StringBuilder();for(byte b:digest.digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));state.put("tupleSha256",hash.toString());store.putSetting(OwnerDraftStore.STATE_KEY,state.toString());
        OwnerDraftStore.Snapshot restored=drafts.read();same(original,restored);assertFalse(restored.recoveryRequired);assertEquals(UiAutomationProvenance.OWNER,restored.inputActor);
    }

    private static String field(String value){return value.length()+":"+value;}

    @Test public void sameTupleIsStableButAToBToAIsANewInputCohort()throws Exception{
        OwnerDraftStore.Snapshot a=drafts.edit("A",attachments("one")),sameA=drafts.edit("A",attachments("one"));same(a,sameA);
        OwnerDraftStore.Snapshot b=drafts.edit("B",attachments("one")),againA=drafts.edit("A",attachments("one"));
        assertNotEquals(a.clientInputId,b.clientInputId);assertNotEquals(a.clientInputId,againA.clientInputId);assertEquals(a.revision+2,againA.revision);assertEquals("A",againA.text);
        same(againA,drafts.consume(a.clientInputId,a.revision));
    }

    @Test public void snapshotsAndAttachmentCopiesCannotBeMutatedByCaller()throws Exception{
        JSONArray original=attachments("one");OwnerDraftStore.Snapshot snapshot=drafts.edit("input",original);original.getJSONObject(0).put("id","mutated");snapshot.attachments().getJSONObject(0).put("id","other mutation");
        assertEquals("one",snapshot.attachments().getJSONObject(0).getString("id"));assertEquals("one",drafts.read().attachments().getJSONObject(0).getString("id"));same(snapshot,drafts.read());
    }

    @Test public void consumeClearsFullTupleAndDuplicateCallbackCannotClearNextCohort()throws Exception{
        OwnerDraftStore.Snapshot captured=drafts.edit("send",attachments("one")),empty=drafts.consume(captured.clientInputId,captured.revision);
        assertEquals("",empty.text);assertEquals("[]",empty.attachmentsJson);assertEquals(captured.revision+1,empty.revision);assertNotEquals(captured.clientInputId,empty.clientInputId);assertEquals("",store.getSetting("ui.draft","missing"));assertEquals("[]",store.getSetting("ui.attachments","missing"));
        same(empty,drafts.consume(captured.clientInputId,captured.revision));OwnerDraftStore.Snapshot later=drafts.edit("next",attachments("two"));same(later,drafts.consume(captured.clientInputId,captured.revision));
    }

    @Test public void textEditDuringAttachmentPreparationKeepsBothCurrentFields()throws Exception{
        OwnerDraftStore.Snapshot captured=drafts.edit("old",attachments("one")),changed=drafts.edit("new",attachments("one"));same(changed,drafts.consume(captured.clientInputId,captured.revision));assertEquals("new",drafts.read().text);assertEquals(1,drafts.read().attachments().length());
    }

    @Test public void fileEditDuringSendKeepsTextAndNewAttachmentTogether()throws Exception{
        OwnerDraftStore.Snapshot captured=drafts.edit("same text",attachments("one")),changed=drafts.edit("same text",attachments("one","two"));same(changed,drafts.consume(captured.clientInputId,captured.revision));assertEquals("same text",drafts.read().text);assertEquals(2,drafts.read().attachments().length());
    }

    @Test public void twoCompetingConsumesAdvanceRevisionOnlyOnce()throws Exception{
        OwnerDraftStore.Snapshot captured=drafts.edit("double click",attachments("one"));List<OwnerDraftStore.Snapshot> outcomes=concurrent(2,()->new OwnerDraftStore(store).consume(captured.clientInputId,captured.revision));
        same(outcomes.get(0),outcomes.get(1));same(outcomes.get(0),drafts.read());assertEquals(captured.revision+1,drafts.read().revision);
    }

    @Test public void enqueueTransactionFailureRollsBackConsumedDraftAndOwnerRecord()throws Exception{
        OwnerDraftStore.Snapshot captured=drafts.edit("transactional input",attachments("one"));
        try{store.withTransaction(db->{store.addMessage("user",captured.text,captured.clientInputId);drafts.consume(captured.clientInputId,captured.revision);throw new IllegalStateException("fixture rollback after consume");});fail();}catch(IllegalStateException expected){assertEquals("fixture rollback after consume",expected.getMessage());}
        same(captured,drafts.read());assertEquals(0,store.listMessages(10).length());assertEquals(captured.text,store.getSetting("ui.draft","missing"));assertEquals(captured.attachmentsJson,store.getSetting("ui.attachments","missing"));
    }

    @Test public void committedSendSurvivesRecreationBeforeUiCallback()throws Exception{
        OwnerDraftStore.Snapshot captured=drafts.edit("committed before rotation",attachments("one"));
        OwnerDraftStore.Snapshot committed=store.withTransaction(db->{store.addMessage("user",captured.text,captured.clientInputId);return drafts.consume(captured.clientInputId,captured.revision);});
        store.close();store=new HoyoungStore(context);drafts=new OwnerDraftStore(store);same(committed,drafts.read());assertEquals(1,store.listMessages(10).length());
        OwnerDraftStore.Snapshot newlyTyped=drafts.edit("typed after recreation",attachments("two"));same(newlyTyped,drafts.consume(captured.clientInputId,captured.revision));
    }

    @Test public void failureOnSecondLegacyMirrorRollsBackAllDraftFields()throws Exception{
        OwnerDraftStore.Snapshot original=drafts.edit("original",attachments("one"));String rawState=store.getSetting(OwnerDraftStore.STATE_KEY,"");
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_draft_mirror BEFORE INSERT ON settings WHEN NEW.setting_key='ui.attachments' BEGIN SELECT RAISE(ABORT,'fixture write failure'); END");
        try{try{drafts.edit("replacement",attachments("two"));fail("Partial draft commit must fail");}catch(SQLiteException expected){assertTrue(expected.toString().contains("fixture write failure"));}}
        finally{store.getWritableDatabase().execSQL("DROP TRIGGER fail_draft_mirror");}
        same(original,drafts.read());assertEquals(rawState,store.getSetting(OwnerDraftStore.STATE_KEY,""));assertEquals(original.text,store.getSetting("ui.draft",""));assertEquals(original.attachmentsJson,store.getSetting("ui.attachments",""));
    }

    @Test public void malformedLegacyAttachmentsArePreservedAsExactSystemSourcesAndCannotBeConsumed()throws Exception{
        store.putSetting("ui.draft","  preserve text\n");store.putSetting("ui.attachments","[{broken raw attachment");String bad=store.getSetting("ui.attachments","");
        OwnerDraftStore.Snapshot recovered=drafts.read();assertTrue(recovered.recoveryRequired);assertEquals("  preserve text\n",recovered.text);assertEquals("[]",recovered.attachmentsJson);assertEquals(bad,store.getSetting("ui.attachments",""));assertFalse(recovered.recoveryReason.isEmpty());
        same(recovered,drafts.read());same(recovered,drafts.consume(recovered.clientInputId,recovered.revision));assertEquals(bad,recoveryOriginal(recovered,"ui.attachments"));
        OwnerDraftStore.Snapshot edited=drafts.edit(recovered.text,new JSONArray());assertFalse(edited.recoveryRequired);assertNotEquals(recovered.clientInputId,edited.clientInputId);assertEquals("[]",store.getSetting("ui.attachments",""));assertEquals(bad,recoveryOriginal(recovered,"ui.attachments"));
    }

    @Test public void malformedWholeStateIsArchivedBeforeUsingLegacyTuple()throws Exception{
        store.putSetting("ui.draft","fallback exact");store.putSetting("ui.attachments",attachments("one").toString());store.putSetting(OwnerDraftStore.STATE_KEY,"{damaged whole tuple");
        OwnerDraftStore.Snapshot recovered=drafts.read();assertTrue(recovered.recoveryRequired);assertEquals("fallback exact",recovered.text);assertEquals(1,recovered.attachments().length());assertEquals("{damaged whole tuple",recoveryOriginal(recovered,OwnerDraftStore.STATE_KEY));
    }

    @Test public void changedEncodedTextWithoutMatchingHashRequiresRecovery()throws Exception{
        OwnerDraftStore.Snapshot original=drafts.edit("original",attachments("one"));JSONObject tampered=new JSONObject(store.getSetting(OwnerDraftStore.STATE_KEY,""));tampered.put("textUtf16",encode("changed secretly"));String damaged=tampered.toString();store.putSetting(OwnerDraftStore.STATE_KEY,damaged);
        OwnerDraftStore.Snapshot recovered=drafts.read();assertTrue(recovered.recoveryRequired);assertEquals(original.text,recovered.text);assertEquals(damaged,recoveryOriginal(recovered,OwnerDraftStore.STATE_KEY));
    }

    @Test public void largeLegacyDraftUsesBoundedPartsAndSurvivesReopen()throws Exception{
        String large="🙂\n".repeat(70_000);store.putSetting("ui.draft",large);OwnerDraftStore.Snapshot before=drafts.read();assertEquals("chunked-json-v1",new JSONObject(store.getSetting(OwnerDraftStore.STATE_KEY,"")).getString("storage"));
        store.close();store=new HoyoungStore(context);drafts=new OwnerDraftStore(store);same(before,drafts.read());assertEquals(large,drafts.read().text);
        drafts.consume(before.clientInputId,before.revision);assertNull(store.getSetting(OwnerDraftStore.STATE_KEY+".part.0",null));
    }

    @Test public void lostLargeDraftPartIsArchivedAndHeldForReview()throws Exception{
        String large="large exact text\n".repeat(20_000);OwnerDraftStore.Snapshot original=drafts.edit(large,attachments("one"));String manifest=store.getSetting(OwnerDraftStore.STATE_KEY,"");String surviving=store.getSetting(OwnerDraftStore.STATE_KEY+".part.0","");
        store.getWritableDatabase().delete("settings","setting_key=?",new String[]{OwnerDraftStore.STATE_KEY+".part.1"});OwnerDraftStore.Snapshot recovered=drafts.read();assertTrue(recovered.recoveryRequired);assertEquals(original.text,recovered.text);assertEquals(manifest,recoveryOriginal(recovered,OwnerDraftStore.STATE_KEY));assertEquals(surviving,recoveryOriginal(recovered,OwnerDraftStore.STATE_KEY+".part.0"));
    }

    @Test public void rawUtf16IncludingUnpairedSurrogatesHasNoEncodingReplacement()throws Exception{
        String exact="left\ud800x\udfff right\u0000";OwnerDraftStore.Snapshot original=drafts.edit(exact,new JSONArray());assertEquals(exact,drafts.read().text);store.close();store=new HoyoungStore(context);drafts=new OwnerDraftStore(store);same(original,drafts.read());assertEquals(exact,drafts.read().text);
    }

    @Test public void malformedAttachmentElementsDoNotReplaceExistingDraft()throws Exception{
        OwnerDraftStore.Snapshot original=drafts.edit("valid",attachments("one"));try{drafts.edit("bad",new JSONArray().put(17));fail();}catch(IllegalArgumentException expected){}same(original,drafts.read());
    }

    @Test public void trailingGarbageIsPreservedRatherThanSilentlyIgnoredByJsonParser()throws Exception{
        store.putSetting("ui.draft","keep");store.putSetting("ui.attachments","[] trailing damaged bytes");OwnerDraftStore.Snapshot legacy=drafts.read();assertTrue(legacy.recoveryRequired);assertEquals("[] trailing damaged bytes",recoveryOriginal(legacy,"ui.attachments"));
        OwnerDraftStore.Snapshot valid=drafts.edit("keep",new JSONArray());String damaged=store.getSetting(OwnerDraftStore.STATE_KEY,"")+" trailing damaged bytes";store.putSetting(OwnerDraftStore.STATE_KEY,damaged);
        OwnerDraftStore.Snapshot recovered=drafts.read();assertTrue(recovered.recoveryRequired);assertNotEquals(valid.clientInputId,recovered.clientInputId);assertEquals(damaged,recoveryOriginal(recovered,OwnerDraftStore.STATE_KEY));
    }

    @Test public void recoveryEvidenceFailureLeavesAllBrokenOriginalStateUntouched()throws Exception{
        store.putSetting("ui.draft","preserve");store.putSetting("ui.attachments","broken attachments");store.putSetting(OwnerDraftStore.STATE_KEY,"broken state");
        store.getWritableDatabase().execSQL("CREATE TRIGGER fail_draft_recovery BEFORE INSERT ON device_events WHEN NEW.source='app.owner_draft.recovery' BEGIN SELECT RAISE(ABORT,'fixture archive failure'); END");
        try{try{drafts.read();fail("Recovery must not overwrite raw state if evidence cannot be committed");}catch(SQLiteException expected){assertTrue(expected.toString().contains("fixture archive failure"));}}
        finally{store.getWritableDatabase().execSQL("DROP TRIGGER fail_draft_recovery");}
        assertEquals("preserve",store.getSetting("ui.draft",""));assertEquals("broken attachments",store.getSetting("ui.attachments",""));assertEquals("broken state",store.getSetting(OwnerDraftStore.STATE_KEY,""));assertEquals(0,store.listDeviceEvents(20).length());
        assertTrue(drafts.read().recoveryRequired);
    }

    @Test public void consumeRequiresBothCorrectClientAndRevision()throws Exception{
        OwnerDraftStore.Snapshot current=drafts.edit("still being edited",attachments("one"));same(current,drafts.consume(current.clientInputId,current.revision+1));same(current,drafts.consume(java.util.UUID.randomUUID().toString(),current.revision));same(current,drafts.consume(null,current.revision));
    }

    @Test public void mainStringIdAttachmentsMigrateEditReopenAndConsumeAsOneTuple()throws Exception{
        String immutable=".attachment-originals/"+"a".repeat(64)+".jpg";JSONArray ids=new JSONArray().put("photos/갤럭시 원본.png").put(immutable);store.putSetting("ui.draft","string IDs from Main");store.putSetting("ui.attachments",ids.toString());
        OwnerDraftStore.Snapshot migrated=drafts.read();assertFalse(migrated.recoveryRequired);assertEquals(ids.toString(),migrated.attachmentsJson);same(migrated,drafts.edit(migrated.text,new JSONArray(ids.toString())));
        store.close();store=new HoyoungStore(context);drafts=new OwnerDraftStore(store);same(migrated,drafts.read());JSONArray changed=new JSONArray(ids.toString()).put("project/working.py");OwnerDraftStore.Snapshot updated=drafts.edit(migrated.text,changed);
        assertEquals(3,updated.attachments().length());same(updated,drafts.consume(migrated.clientInputId,migrated.revision));OwnerDraftStore.Snapshot consumed=drafts.consume(updated.clientInputId,updated.revision);assertEquals("",consumed.text);assertEquals("[]",consumed.attachmentsJson);
    }

    @Test public void mixedStringIdsAndMetadataKeepOrderAndCopies()throws Exception{
        JSONArray mixed=attachments("original").put("project/current.txt");OwnerDraftStore.Snapshot snapshot=drafts.edit("mixed",mixed);mixed.put("later.txt");assertEquals(2,snapshot.attachments().length());assertTrue(snapshot.attachments().get(0)instanceof JSONObject);assertEquals("project/current.txt",snapshot.attachments().getString(1));same(snapshot,drafts.read());
    }

    @Test public void malformedStringIdCannotReplaceValidInputAndLegacyIsRecoverable()throws Exception{
        OwnerDraftStore.Snapshot before=drafts.edit("valid",new JSONArray().put("photos/one.png"));
        for(String invalid:new String[]{"","/host/path","../escape","dir/../escape","dir//file","file\\other","https:remote","photo\nname"}){try{drafts.edit("changed",new JSONArray().put(invalid));fail("Accepted invalid file id "+invalid);}catch(IllegalArgumentException expected){}same(before,drafts.read());}
        store.getWritableDatabase().delete("settings","setting_key=?",new String[]{OwnerDraftStore.STATE_KEY});store.putSetting("ui.attachments",new JSONArray().put("../escape").toString());OwnerDraftStore.Snapshot recovered=drafts.read();assertTrue(recovered.recoveryRequired);assertEquals(new JSONArray().put("../escape").toString(),recoveryOriginal(recovered,"ui.attachments"));
    }

    private String recoveryOriginal(OwnerDraftStore.Snapshot recovered,String key)throws Exception{
        JSONObject manifest=new JSONObject(store.getSetting(recovered.recoveryKey,""));JSONArray sources=manifest.getJSONArray("sources");
        for(int i=0;i<sources.length();i++){JSONObject source=sources.getJSONObject(i);if(!key.equals(source.getString("sourceSetting")))continue;StringBuilder encoded=new StringBuilder();JSONArray ids=source.getJSONArray("systemSourceIds");
            for(int j=0;j<ids.length();j++){JSONObject record=store.getDeviceEvent(ids.getLong(j));assertEquals("system",record.getString("category"));assertEquals("app.owner_draft.recovery",record.getString("source"));JSONObject payload=record.getJSONObject("payload");assertFalse(payload.getBoolean("authorizationClaim"));assertEquals(j,payload.getInt("part"));encoded.append(payload.getString("rawUtf16Base64"));}
            String raw=decode(encoded.toString());assertEquals(source.getInt("utf16Chars"),raw.length());assertEquals(hash(raw),source.getString("rawSha256"));return raw;
        }throw new AssertionError("Missing archived draft source "+key);
    }
    private static <T>List<T> concurrent(int count,Callable<T> action)throws Exception{ExecutorService workers=Executors.newFixedThreadPool(count);CyclicBarrier barrier=new CyclicBarrier(count);try{List<Future<T>> futures=new ArrayList<>();for(int i=0;i<count;i++)futures.add(workers.submit(()->{barrier.await(10,TimeUnit.SECONDS);return action.call();}));List<T> results=new ArrayList<>();for(Future<T> future:futures)results.add(future.get(20,TimeUnit.SECONDS));return results;}finally{workers.shutdownNow();}}
    private static JSONArray attachments(String...ids)throws Exception{JSONArray result=new JSONArray();for(String id:ids)result.put(new JSONObject().put("id",id).put("sourceName",id+".png").put("mime","image/png").put("size",13));return result;}
    private static void same(OwnerDraftStore.Snapshot a,OwnerDraftStore.Snapshot b){assertEquals(a.text,b.text);assertEquals(a.attachmentsJson,b.attachmentsJson);assertEquals(a.clientInputId,b.clientInputId);assertEquals(a.revision,b.revision);assertEquals(a.recoveryRequired,b.recoveryRequired);assertEquals(a.recoveryKey,b.recoveryKey);assertEquals(a.recoveryReason,b.recoveryReason);}
    private static byte[] bytes(String text){byte[] raw=new byte[text.length()*2];for(int i=0;i<text.length();i++){raw[i*2]=(byte)(text.charAt(i)>>>8);raw[i*2+1]=(byte)text.charAt(i);}return raw;}
    private static String encode(String text){return Base64.encodeToString(bytes(text),Base64.NO_WRAP);}
    private static String decode(String text){byte[] raw=Base64.decode(text,Base64.NO_WRAP);char[] chars=new char[raw.length/2];for(int i=0;i<chars.length;i++)chars[i]=(char)(((raw[i*2]&255)<<8)|(raw[i*2+1]&255));return new String(chars);}
    private static String hash(String text)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes("utf16be-code-units-v1\u0000"+text));StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return hex.toString();}
}
