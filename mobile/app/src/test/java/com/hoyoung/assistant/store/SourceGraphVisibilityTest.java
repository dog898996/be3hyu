package com.hoyoung.assistant.store;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.Assert.*;

/** Canonical native-SQLite ancestry, long graph scopes and structured legacy result copies. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SourceGraphVisibilityTest {
    private Context context;private HoyoungStore store;
    @Before public void before()throws Exception {context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());context.getFilesDir().mkdirs();store=new HoyoungStore(context);}
    @After public void after()throws Exception {store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());}

    @Test public void thousandNodeGraphUsesOneMemoAcrossManySelectionsAndSharedForks()throws Exception {
        List<JSONObject> chain=chain(1000);List<JSONObject> forks=new ArrayList<>();
        store.withTransaction(db->{for(int i=0;i<64;i++){JSONObject next=message("assistant","shared-fork-"+i);bind(next,chain.get(1000));forks.add(next);}return null;});
        SourcePrivacyLedger.Snapshot policy=store.getPrivacyLedger().capture();long independentQueries=0,independentStart=System.nanoTime();
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()) {
            for(JSONObject fork:forks){SourceRecordVisibility.ReadContext independent=new SourceRecordVisibility.ReadContext(snapshot,policy);assertTrue(independent.allowed("message",fork));independentQueries+=queries(independent.diagnostics());}
            long independentNanos=System.nanoTime()-independentStart,sharedStart=System.nanoTime();
            SourceRecordVisibility.ReadContext shared=new SourceRecordVisibility.ReadContext(snapshot,policy);
            for(JSONObject fork:forks)assertTrue(shared.allowed("message",fork));for(JSONObject source:chain)assertTrue(shared.allowed("message",source));
            JSONObject stats=shared.diagnostics();assertEquals(1065,stats.getLong("sourceQueries"));assertEquals(1065,stats.getLong("settingQueries"));
            assertTrue("Shared ancestors must avoid repeated actual SQL",independentQueries>queries(stats)*50);
            System.out.println("SOURCE_GRAPH_1000 "+new JSONObject().put("independentQueries",independentQueries).put("sharedQueries",queries(stats)).put("independentNanos",independentNanos).put("sharedNanos",System.nanoTime()-sharedStart).put("sharedStats",stats));
        }
    }
    @Test public void tenThousandNodeHistoryRemainsReadableAndExcludedLeafBlocksEveryDescendantOnly()throws Exception {
        List<JSONObject> chain=chain(10000);JSONObject unrelated=message("user","unrelated owner remains readable");
        SourcePrivacyLedger.Snapshot before=store.getPrivacyLedger().capture();long start=System.nanoTime();
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()) {
            SourceRecordVisibility.ReadContext scope=new SourceRecordVisibility.ReadContext(snapshot,before);assertTrue(scope.allowed("message",chain.get(10000)));
            for(JSONObject source:chain)assertTrue(scope.allowed("message",source));
            assertEquals(10001,scope.diagnostics().getLong("sourceQueries"));assertEquals(10001,scope.diagnostics().getLong("settingQueries"));
            System.out.println("SOURCE_GRAPH_10000_ALLOWED "+scope.diagnostics().put("elapsedNanos",System.nanoTime()-start));
        }
        exclude("message",chain.get(0));start=System.nanoTime();
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()) {
            SourceRecordVisibility.ReadContext scope=new SourceRecordVisibility.ReadContext(snapshot,store.getPrivacyLedger().capture());
            assertFalse(scope.allowed("message",chain.get(10000)));for(JSONObject source:chain)assertFalse(scope.allowed("message",source));assertTrue(scope.allowed("message",unrelated));
            assertEquals(10002,scope.diagnostics().getLong("sourceQueries"));assertEquals(1,scope.diagnostics().getLong("privacyVersion"));
            System.out.println("SOURCE_GRAPH_10000_EXCLUDED "+scope.diagnostics().put("elapsedNanos",System.nanoTime()-start));
        }
    }
    @Test public void cycleOrFailedChildDoesNotPoisonIndependentAllowedNodes()throws Exception {
        JSONObject first=message("assistant","cycle first"),second=message("assistant","cycle second"),healthy=message("user","healthy owner"),root=message("assistant","mixed result");
        bind(first,second);bind(second,first);bind(root,healthy,first);
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()) {
            SourceRecordVisibility.ReadContext scope=new SourceRecordVisibility.ReadContext(snapshot,store.getPrivacyLedger().capture());
            assertFalse(scope.allowed("message",root));assertFalse(scope.allowed("message",first));assertFalse(scope.allowed("message",second));assertTrue(scope.allowed("message",healthy));
            long count=queries(scope.diagnostics());assertFalse(scope.allowed("message",root));assertEquals(count,queries(scope.diagnostics()));
        }
    }
    @Test public void missingReferencedChildIsDeniedWhileUnrelatedOwnerRemainsAvailable()throws Exception {
        JSONObject bad=message("assistant","missing source"),healthy=message("user","healthy source");
        store.putSetting("answerSources:"+bad.getLong("id"),new JSONObject().put("version",1).put("sources",new JSONArray().put(new JSONObject().put("type","message").put("id",999999).put("hash","a".repeat(64)))).toString());
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()) {SourceRecordVisibility.ReadContext scope=new SourceRecordVisibility.ReadContext(snapshot,store.getPrivacyLedger().capture());assertFalse(scope.allowed("message",bad));assertTrue(scope.allowed("message",healthy));assertFalse(scope.allowed("message",bad));}
    }
    @Test public void memoCannotEscapeItsReadSnapshotOrOpeningThread()throws Exception {
        JSONObject source=message("user","thread-bound source");HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot();SourceRecordVisibility.ReadContext scope=new SourceRecordVisibility.ReadContext(snapshot,store.getPrivacyLedger().capture());assertTrue(scope.allowed("message",source));
        ExecutorService worker=Executors.newSingleThreadExecutor();try{Future<Boolean> wrongThread=worker.submit(()->scope.allowed("message",source));try{wrongThread.get();fail("A graph memo must not escape its opening thread");}catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause() instanceof IllegalStateException);}}finally{worker.shutdownNow();snapshot.close();}
        assertThrows(IllegalStateException.class,()->scope.allowed("message",source));assertThrows(IllegalStateException.class,scope::diagnostics);
    }
    @Test public void restoredIdAndDifferentHashCannotInheritPreviousGenerationVerdict()throws Exception {
        JSONObject old=message("user","old generation");HoyoungStore.ReadSnapshot previous=store.openReadSnapshot();SourceRecordVisibility.ReadContext oldScope=new SourceRecordVisibility.ReadContext(previous,store.getPrivacyLedger().capture());assertTrue(oldScope.allowed("message",old));previous.close();exclude("message",old);
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);JSONObject replacement=message("user","new explicit independent generation");assertEquals(old.getLong("id"),replacement.getLong("id"));assertNotEquals(old.getString("content_hash"),replacement.getString("content_hash"));
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()){SourceRecordVisibility.ReadContext fresh=new SourceRecordVisibility.ReadContext(snapshot,store.getPrivacyLedger().capture());assertFalse(fresh.allowed("message",old));assertTrue(fresh.allowed("message",replacement));}
        assertThrows(IllegalStateException.class,()->oldScope.allowed("message",replacement));
    }
    @Test public void newPrivacyScopeDoesNotReuseEarlierAllowedAncestry()throws Exception {
        JSONObject owner=message("user","later excluded original"),reply=message("assistant","known copied original");bind(reply,owner);
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()){assertTrue(new SourceRecordVisibility.ReadContext(snapshot,store.getPrivacyLedger().capture()).allowed("message",reply));}
        exclude("message",owner);
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()){assertFalse(new SourceRecordVisibility.ReadContext(snapshot,store.getPrivacyLedger().capture()).allowed("message",reply));}
    }
    @Test public void actualPermissionPredicateAppliesToTransitiveDeviceCopiesOnFreshScopes()throws Exception {
        JSONObject device=store.appendDeviceEvent("location","fixture.location",new JSONObject().put("latitude",37.123),100,null),first=message("assistant","location copy"),second=message("assistant","transitive location copy"),owner=message("user","unrelated owner");
        bindTyped(first,"device_event",device);bind(second,first);Application application=(Application)context;
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()) {SourceRecordVisibility.ReadContext scope=new SourceRecordVisibility.ReadContext(snapshot,store.getPrivacyLedger().capture(),row->context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED);assertTrue(scope.allowed("message",second));}
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()) {SourceRecordVisibility.ReadContext scope=new SourceRecordVisibility.ReadContext(snapshot,store.getPrivacyLedger().capture(),row->context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED);assertFalse(scope.allowed("message",second));assertTrue(scope.allowed("message",owner));
            assertTrue("Owner-storage audit default does not grant implicit model access",SourceRecordVisibility.allowed(snapshot.database,store.getPrivacyLedger().capture(),"message",second));}
    }
    @Test public void legacyForeignOriginalResultIsBlockedDespiteAllowedQuestionParent()throws Exception {
        JSONObject question=requestOwner("allowed-root"),foreign=message("user","FOREIGN-STRUCTURED-5721");
        JSONObject archive=receipt("allowed-root",new JSONObject().put("result",new JSONObject().put("original",store.readOriginalText(foreign.getLong("id"),0,1000))));
        assertTrue(allowed(archive));exclude("message",foreign);assertFalse(allowed(archive));assertTrue(allowed(question));
    }
    @Test public void nestedTypedEvidenceSnakeCaseMemoryRefsAndPartialReviewRangesAllFollowOriginals()throws Exception {
        requestOwner("typed-root");JSONObject original=message("user","FOREIGN-NESTED-9832");JSONArray arrays=new JSONArray();
        arrays.put(new JSONObject().put("originalEvidence",new JSONArray().put(ref("message",original))));
        arrays.put(new JSONObject().put("memories",new JSONArray().put(new JSONObject().put("sourceRefs",new JSONArray().put(new JSONObject().put("source_type","message").put("source_id",original.getLong("id")).put("source_hash",original.getString("content_hash")))))));
        arrays.put(new JSONObject().put("sourceRanges",new JSONArray().put(store.readOriginalText(original.getLong("id"),0,3))));
        JSONObject archive=receipt("typed-root",new JSONObject().put("result",new JSONObject().put("nested",arrays)));
        assertTrue(allowed(archive));exclude("message",original);assertFalse(allowed(archive));
    }
    @Test public void deeplyNestedStructuredResultsRemainTrackedWithoutParsingQuotedText()throws Exception {
        requestOwner("deep-root");JSONObject original=message("user","FOREIGN-DEEP-2234"),nested=new JSONObject().put("original",store.readOriginalText(original.getLong("id"),0,100));
        for(int i=0;i<20;i++)nested=new JSONObject().put("container",nested);JSONObject archive=receipt("deep-root",new JSONObject().put("result",nested));
        JSONObject quoted=message("system",new JSONObject().put("text",new JSONObject().put("original",store.readOriginalText(original.getLong("id"),0,100)).toString()).toString());
        assertTrue(allowed(archive));exclude("message",original);assertFalse(allowed(archive));assertTrue("Quoted text has no native structured provenance",allowed(quoted));
        JSONObject ownerLiteral=message("user",new JSONObject().put("sourceMessageId",original.getLong("id")).toString());assertTrue(allowed(ownerLiteral));
    }
    @Test public void nativeResultProvenanceAppliesToDeviceEvidenceInLegacyArchive()throws Exception {
        requestOwner("device-root");JSONObject device=store.appendDeviceEvent("notification","fixture.app",new JSONObject().put("text","FOREIGN-DEVICE-8224"),100,null);
        JSONObject archive=receipt("device-root",new JSONObject().put("nativeResultSources",new JSONObject().put("version",1).put("sources",new JSONArray().put(ref("device_event",device)))).put("result",new JSONObject().put("text","FOREIGN-DEVICE-8224")));
        assertTrue(allowed(archive));exclude("device_event",device);assertFalse(allowed(archive));
    }
    @Test public void malformedTypedReferencesAndConflictingHashAliasesFailClosed()throws Exception {
        requestOwner("bad-root");JSONObject source=message("user","source with known hash");
        JSONObject fractional=ref("message",source).put("id",1.5),blank=ref("message",source).put("hash",""),conflict=ref("message",source).put("source_hash","f".repeat(64));
        for(JSONObject bad:new JSONObject[]{fractional,blank,conflict})assertFalse(allowed(receipt("bad-root",new JSONObject().put("result",new JSONObject().put("sourceRefs",new JSONArray().put(bad))))));
        assertFalse(allowed(receipt("bad-root",new JSONObject().put("result",new JSONObject().put("sourceRanges",new JSONArray().put(new JSONObject().put("messageId",source.getLong("id")).put("text","unbound range")))))));
    }
    @Test public void oldOriginalRecordHashCannotAttachItsCopyToReplacementAtSameId()throws Exception {
        JSONObject original=message("user","old raw generation"),oldRange=store.readOriginalText(original.getLong("id"),0,100);
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);JSONObject replacement=message("user","new raw generation");assertEquals(original.getLong("id"),replacement.getLong("id"));
        requestOwner("replacement-root");JSONObject archive=receipt("replacement-root",new JSONObject().put("result",new JSONObject().put("original",oldRange)));
        assertFalse(allowed(archive));assertTrue(allowed(replacement));
    }
    @Test public void partialMemoryCitationsResolveAllCanonicalDependenciesIncludingOmittedLastSource()throws Exception {
        requestOwner("partial-root");JSONArray ids=new JSONArray();List<JSONObject> originals=new ArrayList<>();
        store.withTransaction(db->{for(int i=0;i<140;i++){JSONObject source=message("user","partial note source "+i);originals.add(source);ids.put(source.optLong("id"));}return null;});
        store.upsertMemory("partial-source-memory","known full canonical dependency note","fact",ids);JSONObject full=store.getMemory("partial-source-memory"),partial=new JSONObject(full.toString());partial.put("sourceRefs",new JSONArray().put(ref("message",originals.get(0)))).put("sourceCount",140).put("sourceRefsTruncated",true);
        JSONObject archive=receipt("partial-root",new JSONObject().put("result",new JSONObject().put("memories",new JSONArray().put(partial))));assertTrue(allowed(archive));exclude("message",originals.get(139));assertFalse(allowed(archive));assertTrue(allowed(originals.get(0)));
    }
    @Test public void partialMemoryCannotBorrowAnotherRevisionHashAndEvidenceMetadataIsTracked()throws Exception {
        requestOwner("metadata-root");JSONObject original=message("user","metadata original"),quoted=ref("message",original);quoted.remove("hash");quoted.put("recordHash",original.getString("content_hash"));
        JSONObject archive=receipt("metadata-root",new JSONObject().put("result",new JSONObject().put("metadata",new JSONObject().put("evidenceQuotes",new JSONArray().put(quoted)).put("assetRefs",new JSONArray().put(new JSONObject().put("messageId",original.getLong("id")))))));
        assertTrue(allowed(archive));exclude("message",original);assertFalse(allowed(archive));
        JSONObject unknownPartial=new JSONObject().put("key","missing-note").put("revision",1).put("content_hash","f".repeat(64)).put("sourceRefs",new JSONArray()).put("sourceCount",3).put("sourceRefsTruncated",true);
        assertFalse(allowed(receipt("metadata-root",new JSONObject().put("result",unknownPartial))));
    }
    @Test public void authenticatedHistoricalPartialMemoryAlsoFollowsHiddenOldMetadataEvidence()throws Exception {
        requestOwner("historical-root");JSONObject source=message("user","main memory origin"),foreign=message("user","foreign old metadata origin");
        store.upsertMemory("historical-partial","old remembered note","fact",new JSONArray().put(source.getLong("id")));
        JSONObject evidence=ref("message",foreign);evidence.remove("hash");evidence.put("recordHash",foreign.getString("content_hash"));
        store.putMemoryMetadata("historical-partial",new JSONObject().put("evidenceQuotes",new JSONArray().put(evidence)).put("profile","old version"));
        JSONObject old=new JSONObject(store.getMemory("historical-partial").toString()).put("sourceRefs",new JSONArray()).put("sourceCount",1).put("sourceRefsTruncated",true).put("metadata",new JSONObject()).put("metadataIsCandidateView",true);
        JSONObject archive=receipt("historical-root",new JSONObject().put("result",new JSONObject().put("memories",new JSONArray().put(old))));assertTrue(allowed(archive));
        store.upsertMemory("historical-partial","new corrected note","fact",new JSONArray().put(source.getLong("id")));store.putMemoryMetadata("historical-partial",new JSONObject().put("profile","new version"));
        assertTrue("Historical dependency identity is checked independently of the current note",allowed(archive));
        exclude("message",foreign);assertFalse(allowed(archive));assertTrue(allowed(source));
    }
    @Test public void failedAncestorPredicateLeavesDeniedFramesAndDoesNotPoisonUnrelatedSources()throws Exception {
        JSONObject device=store.appendDeviceEvent("notification","fixture.app",new JSONObject().put("text","notification"),100,null),response=message("assistant","copied notification"),owner=message("user","healthy owner");bindTyped(response,"device_event",device);
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()) {
            SourceRecordVisibility.ReadContext failed=new SourceRecordVisibility.ReadContext(snapshot,store.getPrivacyLedger().capture(),row->{throw new IllegalStateException("unavailable native permission check");});
            assertThrows(IllegalStateException.class,()->failed.allowed("message",response));assertFalse(failed.allowed("message",response));assertTrue(failed.allowed("message",owner));
            assertTrue(new SourceRecordVisibility.ReadContext(snapshot,store.getPrivacyLedger().capture(),row->true).allowed("message",response));
        }
    }
    @Test public void malformedDeepAndTrailingNativeEnvelopesCannotHideSourceLocators()throws Exception {
        JSONObject owner=requestOwner("malformed-root"),foreign=message("user","foreign private original");
        JSONObject deep=new JSONObject().put("original",store.readOriginalText(foreign.getLong("id"),0,100));for(int i=0;i<70;i++)deep=new JSONObject().put("nested",deep);
        JSONObject malformedReceipt=receipt("malformed-root",new JSONObject());
        String broken=new JSONObject().put("receiptKey","actionReceipt:malformed-root:read").put("rawReceipt","not-json").toString();
        JSONObject valid=new JSONObject().put("nativeOutcomeSource",new JSONObject().put("messageId",foreign.getLong("id")).put("recordHash",foreign.getString("content_hash")));
        for(String text:new String[]{"{malformed native envelope",deep.toString(),valid+" trailing",broken})assertFalse(allowed(message("system",text)));
        assertTrue(allowed(owner));assertTrue("Empty but well-formed native receipt remains allowed under its exact root",allowed(malformedReceipt));
        assertTrue("Owner's own literal text remains readable",allowed(message("user",deep.toString())));
        assertTrue(allowed(message("system",new JSONObject().put("note","unrelated structured native note").toString())));
    }
    private List<JSONObject> chain(int descendants) {
        List<JSONObject> sources=new ArrayList<>();store.withTransaction(db->{JSONObject previous=message("user","long-history owner origin");sources.add(previous);
            for(int i=0;i<descendants;i++){JSONObject next=message("assistant","long-history reply "+i);bind(next,previous);sources.add(next);previous=next;}return null;});return sources;
    }
    private JSONObject message(String role,String text){return store.addMessage(role,text,null);}
    private JSONObject requestOwner(String root)throws Exception {JSONObject owner=store.addMessage("user","Allowed question for "+root,root);store.putSetting("requestSource:"+root,String.valueOf(owner.getLong("id")));store.putSetting("requestSourceHash:"+root,owner.getString("content_hash"));return owner;}
    private JSONObject receipt(String root,JSONObject receipt)throws Exception{return message("system",new JSONObject().put("receiptKey","actionReceipt:"+root+":read").put("rawReceipt",receipt.toString()).toString());}
    private static JSONObject ref(String type,JSONObject source)throws Exception{return new JSONObject().put("type",type).put("id",source.getLong("id")).put("hash",source.getString("content_hash"));}
    private void bind(JSONObject response,JSONObject...sources){JSONArray refs=new JSONArray();try{for(JSONObject source:sources)refs.put(ref("message",source));store.putSetting("answerSources:"+response.getLong("id"),new JSONObject().put("version",1).put("sources",refs).toString());}catch(Exception invalid){throw new IllegalStateException(invalid);}}
    private void bindTyped(JSONObject response,String type,JSONObject source)throws Exception{store.putSetting("answerSources:"+response.getLong("id"),new JSONObject().put("version",1).put("sources",new JSONArray().put(ref(type,source))).toString());}
    private void exclude(String type,JSONObject source)throws Exception{store.getPrivacyLedger().excludeSource(type,source.getLong("id"),source.getString("content_hash"),"retrieval_exclude","explicit owner exclusion fixture");}
    private boolean allowed(JSONObject source)throws Exception{try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()){return new SourceRecordVisibility.ReadContext(snapshot,store.getPrivacyLedger().capture()).allowed("message",source);}}
    private static long queries(JSONObject stats){return stats.optLong("sourceQueries")+stats.optLong("settingQueries")+stats.optLong("derivedSourceQueries");}
    private static void delete(File file)throws Exception {if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Cannot inspect test files");for(File child:children)delete(child);}if(!file.delete())throw new java.io.IOException("Cannot remove test file");}
}
