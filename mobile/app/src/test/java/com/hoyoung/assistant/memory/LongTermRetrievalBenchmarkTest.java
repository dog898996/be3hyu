package com.hoyoung.assistant.memory;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Synthetic source-recall regressions, not a real-user 2-year or language-model QA score.
 * Fake encoder exercises cache/range protocols only; it does not measure neural language quality. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class LongTermRetrievalBenchmarkTest {
    private Context context;private HoyoungStore store;
    @Before public void setup(){context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);}
    @After public void cleanup(){store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    private long source(String text)throws Exception{return store.addMessage("user",text,null).getLong("id");}
    private JSONObject note(String key,String content,long id)throws Exception{return store.upsertMemory(key,content,"fact",new JSONArray().put(id));}
    private static JSONObject object(Object...pairs)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static boolean contains(JSONArray rows,String key)throws Exception{for(int i=0;i<rows.length();i++)if(key.equals(rows.getJSONObject(i).optString("key")))return true;return false;}

    @Test public void twoYearLabeledHistoryRecoversOldRareEvidenceBeyondRecentTwoHundred()throws Exception{
        long original=source("2024-10-13 온새미 프로젝트 장비 PIN K9R4-731.");note("project.onsemi.pin","온새미 프로젝트 PIN K9R4-731",original);
        LocalDate start=LocalDate.of(2024,10,14);
        store.withTransaction(db->{try{for(int day=0;day<730;day++)for(int turn=0;turn<3;turn++){
            long id=source(start.plusDays(day)+" 프로젝트 장비 일반 점검 기록 "+day+" "+turn);
            if(turn==0)note("maintenance."+day,"프로젝트 장비 점검, PIN 값은 언급하지 않음 "+day,id);
        }}catch(Exception error){throw new IllegalStateException(error);}return null;});
        long started=System.nanoTime();JSONObject result=new MemoryRetriever(store).search(object("query","온새미 PIN","limit",5,"maxChars",8000));
        assertTrue(contains(result.getJSONArray("memories"),"project.onsemi.pin"));assertTrue(result.getString("context").contains("K9R4-731"));
        assertTrue(result.getJSONArray("originalEvidence").toString().contains("\"id\":"+original));assertEquals(2191,store.messageCount());
        assertFalse(result.getJSONObject("diagnostics").getBoolean("actualLmMeasured"));
        System.out.println("SYNTHETIC_RETRIEVAL {messages:2191,labeledDays:730,goldRecallAt5:1,actualLmMeasured:false,elapsedMillis:"+(System.nanoTime()-started)/1_000_000+"}");
    }

    @Test public void pinnedConstraintsAndIndexedAliasesSurviveUnrelatedCurrentConversation()throws Exception{
        long id=source("호두 먹으면 알레르기가 있어. 프로젝트 별칭은 새싹이야.");note("owner.health.walnut","호두 알레르기",id);store.putMemoryMetadata("owner.health.walnut",object("pinned",true,"subject","owner","predicate","allergy","value","walnut"));
        note("project.jade","Jade 배포 위치는 Osaka",id);store.putMemoryMetadata("project.jade",object("retrievalText","새싹 Jade jade-project","entities",new JSONArray().put("Jade").put("새싹")));
        JSONObject related=new MemoryRetriever(store).search(object("query","새싹 배포"));assertTrue(contains(related.getJSONArray("memories"),"project.jade"));
        JSONObject unrelated=new MemoryRetriever(store).search(object("query","오늘 영화 추천"));assertTrue(contains(unrelated.getJSONArray("memories"),"owner.health.walnut"));assertFalse(unrelated.getBoolean("hasRelevantEvidence"));assertTrue(unrelated.getString("context").contains("PINNED"));
    }

    @Test public void currentPastAndOverlappingContradictionsRemainDistinct()throws Exception{
        long old=source("2024년에는 부산에 살았어"),now=source("2025년부터 서울에 살아");
        note("owner.home","부산",old);store.putMemoryMetadata("owner.home",object("factKey","owner.home","value","부산","validFrom","2024-01-01","validTo","2025-01-01"));
        note("owner.home","서울",now);store.putMemoryMetadata("owner.home",object("factKey","owner.home","value","서울","validFrom","2025-01-01"));
        note("owner.home-conflicting","인천",now);store.putMemoryMetadata("owner.home-conflicting",object("factKey","owner.home","value","인천","validFrom","2025-01-01"));
        JSONObject result=new MemoryRetriever(store).search(object("query","owner.home","asOf","2024-06-01","includeHistory",true));
        boolean oldValid=false;JSONArray history=result.getJSONArray("history");for(int i=0;i<history.length();i++){JSONObject row=history.getJSONObject(i);if("부산".equals(row.getString("content")))oldValid="valid_at_requested_time".equals(row.getString("temporalStatus"));}
        assertTrue(oldValid);assertTrue(result.getJSONArray("conflicts").length()>0);assertTrue(result.getJSONArray("conflicts").toString().contains("unresolved_keep_both_sources"));
        assertTrue(store.getMessage(old).getString("text").contains("부산"));
    }

    @Test public void assistantGeneratedClaimsNeverBecomeVerifiedOwnerFacts()throws Exception{
        long id=store.addMessage("assistant","사용자는 우주선을 소유한다는 추측",null).getLong("id");note("generated.spaceship","우주선 소유",id);
        JSONObject result=new MemoryRetriever(store).search(object("query","우주선"));assertEquals("assistant_generated_not_verified_fact",result.getJSONArray("memories").getJSONObject(0).getString("epistemicStatus"));assertTrue(result.getString("context").contains("not_verified_fact"));
    }

    @Test public void oldAsOfRevisionSurvivesMoreThanTwentySubsequentUpdates()throws Exception{
        long id=source("월별 교통 예산 변경 원문");LocalDate start=LocalDate.of(2020,1,1);
        for(int month=0;month<35;month++){note("owner.transport.budget","교통 예산 "+(month+1000),id);store.putMemoryMetadata("owner.transport.budget",object("factKey","transport.budget","value",month+1000,"validFrom",start.plusMonths(month).toString(),"validTo",start.plusMonths(month+1).toString()));}
        JSONObject result=new MemoryRetriever(store).search(object("query","owner.transport.budget","asOf","2020-01-15"));
        JSONArray old=result.getJSONArray("history");boolean matched=false;for(int i=0;i<old.length();i++)if(old.getJSONObject(i).getInt("revision")==1)matched="valid_at_requested_time".equals(old.getJSONObject(i).getString("temporalStatus"));assertTrue(matched);
    }

    @Test public void revokedDeviceSourcesCannotAppearAsRawOrMixedDerivedMemory()throws Exception{
        long user=source("owner message public topic");JSONObject device=store.appendDeviceEvent("notification","instagram",object("text","private-needle-672"),System.currentTimeMillis(),"privacy-test");
        store.upsertMemoryWithSources("private.mixed","private-needle-672", "fact",new JSONArray().put(object("type","message","id",user)).put(object("type","device_event","id",device.getLong("id"))));
        MemoryRetriever retriever=new MemoryRetriever(store,new FakeEncoder("fake-privacy"));retriever.indexPending(20,1000);retriever.setDeviceSourceVisibility(event->false);
        JSONObject result=retriever.search(object("query","private-needle-672"));assertFalse(contains(result.getJSONArray("memories"),"private.mixed"));assertFalse(result.getJSONArray("originalEvidence").toString().contains("private-needle-672"));assertTrue(store.getDeviceEvent(device.getLong("id"))!=null);
    }

    @Test public void originalEvidenceHonorsEntityConstraints()throws Exception{
        source("Project-Orion budget 1000");source("Project-Aster budget 2000");JSONObject result=new MemoryRetriever(store).search(object("query","budget","entities",new JSONArray().put("Project-Orion")));JSONArray evidence=result.getJSONArray("originalEvidence");assertEquals(1,evidence.length());assertTrue(evidence.getJSONObject(0).getString("excerpt").contains("Orion"));
    }

    @Test public void offlineLexicalSearchRetrievesUnindexedOldDeviceEvidenceWithObservedDateScope()throws Exception{
        long observed=MemoryQuery.date("2024-04-01");JSONObject device=store.appendDeviceEvent("notification","calendar",object("text","DEVICE-RARE-731 Project-Orion meeting address"),observed,"old-device");
        for(int i=0;i<250;i++)store.appendDeviceEvent("notification","calendar",object("text","routine meeting other project "+i),observed+i+1,"distractor-device"+i);
        MemoryRetriever retriever=new MemoryRetriever(store);JSONObject result=retriever.search(object("query","DEVICE-RARE-731","fromInclusive","2024-01-01","toExclusive","2025-01-01"));
        assertTrue(result.getJSONArray("originalEvidence").toString().contains("DEVICE-RARE-731"));assertTrue(result.getBoolean("hasRelevantEvidence"));assertEquals("concept-fallback",result.getJSONObject("diagnostics").getString("semanticMode"));assertNotNull(store.getDeviceEvent(device.getLong("id")));
        retriever.setDeviceSourceVisibility(event->false);assertFalse(retriever.search(object("query","DEVICE-RARE-731")).getBoolean("hasRelevantEvidence"));
    }

    @Test public void lexicalSearchFindsOriginalTailBeyondSqlExcerptWithoutLosingExactOriginal()throws Exception{
        StringBuilder huge=new StringBuilder();for(int i=0;i<13_000;i++)huge.append("ordinary filler ");huge.append("TAIL-EVIDENCE-492 cloud region Osaka.");String original=huge.toString();long id=source(original);
        assertTrue(original.length()>200_000);String indexProof="";try(Cursor c=store.getReadableDatabase().rawQuery("SELECT length(text),substr(text,-80) FROM message_search WHERE docid=?",new String[]{String.valueOf(id)})){if(c.moveToFirst())indexProof=c.getLong(0)+":"+c.getString(1);}assertTrue("Full original FTS candidate must exist: "+indexProof,store.rankedOriginalCandidates("TAIL-EVIDENCE-492",0,0,3).length()>0);JSONObject result=new MemoryRetriever(store).search(object("query","TAIL-EVIDENCE-492","limit",3));
        assertTrue(result.getJSONArray("originalEvidence").toString(),result.getJSONArray("originalEvidence").toString().contains("TAIL"));assertTrue(result.getString("context").contains("492"));
        JSONObject range=store.readOriginalText(id,original.length()-60,60);assertEquals(original.substring(original.length()-60),range.getString("text"));assertTrue(range.getBoolean("originalStored"));
    }

    @Test public void fullSourceChunksCoverEveryCharacterAndSurrogatesDespiteSmallTokenBudget()throws Exception{
        String original="prefix "+"A😀한글! ".repeat(90)+" UNIQUE-END-TARGET";long id=source(original);FakeEncoder encoder=new FakeEncoder("fake-v1");MemoryRetriever retriever=new MemoryRetriever(store,encoder);
        for(int i=0;i<40&&retriever.diagnostics().getLong("semanticCompletedSources")==0;i++)retriever.indexPending(2,1000);
        assertEquals(1,retriever.diagnostics().getLong("semanticCompletedSources"));assertEquals(1.0,retriever.diagnostics().getDouble("characterCoverage"),0.00001);
        boolean[] covered=new boolean[original.length()];
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT chunk_offset,chunk_length FROM semantic_chunks WHERE source_type='message' AND source_id=? ORDER BY chunk_offset",new String[]{String.valueOf(id)})){
            while(c.moveToNext()){int start=c.getInt(0),length=c.getInt(1);String exact=original.substring(start,start+length);assertFalse(Character.isLowSurrogate(exact.charAt(0)));assertFalse(Character.isHighSurrogate(exact.charAt(exact.length()-1)));for(int j=start;j<start+length;j++)covered[j]=true;}
        }
        for(boolean character:covered)assertTrue(character);assertTrue(String.join("\n",encoder.inputs).contains("UNIQUE-END-TARGET"));
        JSONObject searched=retriever.search(object("query","UNIQUE-END-TARGET"));assertTrue(searched.getJSONObject("diagnostics").toString(),searched.getJSONObject("diagnostics").getJSONObject("semanticScan").getLong("scannedVectorBytes")>0);
    }

    @Test public void revisedFactsAndAliasesInvalidateDerivedVectorsAndOwnerTogglePreservesOriginals()throws Exception{
        long id=source("coffee budget 3000");note("owner.coffee","coffee budget 3000",id);MemoryRetriever retriever=new MemoryRetriever(store,new FakeEncoder("fake-v1"));retriever.indexPending(10,1000);assertEquals(1,retriever.diagnostics().getLong("semanticIndexedCount"));
        store.putMemoryMetadata("owner.coffee",object("retrievalText","카페 예산 커피"));assertEquals(0,retriever.diagnostics().getLong("semanticIndexedCount"));retriever.indexPending(10,1000);assertEquals(1,retriever.diagnostics().getLong("semanticIndexedCount"));
        note("owner.coffee","coffee budget 4000",id);assertEquals(0,retriever.diagnostics().getLong("semanticIndexedCount"));retriever.setSemanticEnabled(false);assertEquals("owner-disabled",retriever.diagnostics().getString("semanticMode"));assertTrue(retriever.context("coffee",5000).contains("4000"));assertEquals("coffee budget 3000",store.getMessage(id).getString("text"));
    }

    @Test public void noEncoderOrAbsentEvidenceDoesNotPretendNeuralSearchOrPerfectRecall()throws Exception{
        source("오늘은 날씨가 좋다");MemoryRetriever retriever=new MemoryRetriever(store);JSONObject result=retriever.search(object("query","unknown-needle-89713","maxChars",1000));
        assertFalse(result.getBoolean("hasRelevantEvidence"));assertEquals("concept-fallback",result.getJSONObject("diagnostics").getString("semanticMode"));assertTrue(result.getString("context").length()<=1000);assertTrue(result.getJSONObject("diagnostics").getBoolean("noEvidenceIsNotProofOfAbsence"));
        assertThrows(IllegalArgumentException.class,()->retriever.search(object("query","x","fromInclusive","2025-01-01","toExclusive","2024-01-01")));
    }

    @Test public void semanticOnlyEvidenceIsNotCrowdedOutByUncalibratedLexicalScores()throws Exception{
        long gold=source("preferred tea is oolong");for(int i=0;i<80;i++)source("aromatic drink generic recommendation "+i);
        SemanticEncoder seam=new SemanticEncoder(){public String modelId(){return "synthetic-ranking-seam-not-model-quality";}public int dimensions(){return 2;}public boolean isReady(){return true;}public boolean isNeural(){return true;}public float[] encode(String text){return text.equals("aromatic drink")||text.contains("preferred tea")?new float[]{1,0}:new float[]{0,1};}};
        MemoryRetriever retriever=new MemoryRetriever(store,seam);for(int i=0;i<10&&retriever.diagnostics().getLong("semanticCompletedSources")<81;i++)retriever.indexPending(16,1000);
        JSONObject result=retriever.search(object("query","aromatic drink","limit",1));boolean found=false;JSONArray evidence=result.getJSONArray("originalEvidence");for(int i=0;i<evidence.length();i++)found|=evidence.getJSONObject(i).getLong("id")==gold;assertTrue("Independent semantic candidate must survive source-rank fusion",found);assertTrue(evidence.length()<=2);
    }
    @Test public void derivedCandidateProviderCannotBypassOriginalHashModelDatesOrRevokedSources()throws Exception{
        long id=source("preferred tea is oolong");JSONObject original=store.getMessage(id);FakeEncoder encoder=new FakeEncoder("candidate-protocol-seam");MemoryRetriever retriever=new MemoryRetriever(store,encoder);
        JSONObject hit=object("type","message","id",id,"hash",original.getString("content_hash"),"modelId",encoder.modelId(),"offsetChars",0,"lengthChars",original.getString("text").length(),"semanticSimilarity",1);
        retriever.setSemanticCandidateProvider(candidateProvider(new JSONArray().put(hit),false));assertEquals(1,retriever.search(object("query","opaque-query-90214")).getJSONArray("originalEvidence").length());
        hit.put("hash","stale-record-hash");assertEquals(0,retriever.search(object("query","opaque-query-90214")).getJSONArray("originalEvidence").length());
        hit.put("hash",original.getString("content_hash")).put("modelId","incompatible-model");assertEquals(0,retriever.search(object("query","opaque-query-90214")).getJSONArray("originalEvidence").length());
        hit.put("modelId",encoder.modelId());assertEquals(0,retriever.search(object("query","opaque-query-90214","fromInclusive",original.getLong("created_at")+1)).getJSONArray("originalEvidence").length());
        JSONObject event=store.appendDeviceEvent("notification","calendar",object("text","hidden notification payload"),System.currentTimeMillis(),"provider-source-test");JSONObject deviceHit=object("type","device_event","id",event.getLong("id"),"hash",event.getString("content_hash"),"modelId",encoder.modelId(),"offsetChars",0,"lengthChars",event.getJSONObject("payload").toString().length(),"semanticSimilarity",1);
        retriever.setSemanticCandidateProvider(candidateProvider(new JSONArray().put(deviceHit),false));retriever.setDeviceSourceVisibility(source->false);JSONObject denied=retriever.search(object("query","opaque-query-90214"));assertEquals(0,denied.getJSONArray("originalEvidence").length());assertFalse(denied.getString("context").contains("hidden notification payload"));
    }
    @Test public void failedApproximateProviderFallsBackToCurrentCanonicalSourceVectors()throws Exception{
        long gold=source("preferred tea is oolong");MemoryRetriever retriever=new MemoryRetriever(store,new FakeEncoder("fallback-protocol-seam"));retriever.indexPending(5,1000);retriever.setSemanticCandidateProvider(candidateProvider(null,true));
        JSONObject result=retriever.search(object("query","opaque-query-90214"));assertEquals(gold,result.getJSONArray("originalEvidence").getJSONObject(0).getLong("id"));assertEquals("paged_exact_cosine",result.getJSONObject("diagnostics").getJSONObject("semanticAnn").getString("fallback"));assertTrue(result.getJSONObject("diagnostics").getJSONObject("semanticScan").getLong("scannedVectors")>0);
    }
    @Test public void revokedOldDeviceSourcesDoNotStarveLaterPermittedOriginalIndexing()throws Exception{
        for(int i=0;i<6;i++)store.appendDeviceEvent("notification","calendar",object("text","private pending notification "+i),System.currentTimeMillis(),"pending-device-"+i);
        for(int i=0;i<6;i++)source("permitted original source "+i);MemoryRetriever retriever=new MemoryRetriever(store,new FakeEncoder("pending-policy-seam"));retriever.setDeviceSourceVisibility(event->false);
        for(int batch=0;batch<20&&retriever.diagnostics().getLong("semanticCompletedSources")<6;batch++)retriever.indexPending(2,1000);
        assertEquals("Denied low-ID sources must rotate without becoming falsely complete",6,retriever.diagnostics().getLong("semanticCompletedSources"));
        try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM semantic_chunks WHERE source_type='device_event'",null)){assertTrue(cursor.moveToFirst());assertEquals(0,cursor.getInt(0));}
        retriever.setDeviceSourceVisibility(event->true);for(int batch=0;batch<12&&retriever.diagnostics().getLong("semanticCompletedSources")<12;batch++)retriever.indexPending(2,1000);assertEquals("Permission restoration must revisit the preserved incomplete sources",12,retriever.diagnostics().getLong("semanticCompletedSources"));
    }
    @Test public void unreadyApproximateCacheReportsItsCoverageAndActualExactFallback()throws Exception{
        long id=source("allowed fallback body");MemoryRetriever retriever=new MemoryRetriever(store,new FakeEncoder("unready-provider-seam"));retriever.indexPending(2,1000);JSONObject sharedSnapshot=object("status","building_partial_cache","candidateCoverage",0.4,"approximateNearestNeighbor",true);
        retriever.setSemanticCandidateProvider(new SemanticCandidateProvider(){public boolean isReady(String model){return false;}public JSONArray chunks(float[] query,MemoryQuery constraints,int limit,java.util.function.Predicate<JSONObject> visibility){throw new AssertionError("Partial graph must never be used");}public JSONObject diagnostics(){return sharedSnapshot;}public JSONObject maintain(int maximum,long budget){return sharedSnapshot;}});
        assertEquals(0.4,retriever.diagnostics().getJSONObject("semanticAnn").getDouble("candidateCoverage"),0.00001);JSONObject result=retriever.search(object("query","opaque-query-90214"));assertEquals(id,result.getJSONArray("originalEvidence").getJSONObject(0).getLong("id"));JSONObject ann=result.getJSONObject("diagnostics").getJSONObject("semanticAnn");assertFalse(ann.getBoolean("usedForThisSearch"));assertEquals("ann_cache_not_current_or_ready",ann.getString("fallbackReason"));assertTrue(result.getString("context").contains("approximateCandidateSelection=false"));assertFalse("Borrowed immutable provider state must not be mutated",sharedSnapshot.has("usedForThisSearch"));
    }
    @Test public void pinnedPopulationAndReservationCapsAreExplicit()throws Exception{
        long id=source("owner supplied profile preferences");store.withTransaction(db->{try{for(int i=0;i<1001;i++){String key=String.format(java.util.Locale.ROOT,"profile.%04d",i);note(key,"profile preference "+i,id);store.putMemoryMetadata(key,object("pinned",true));}}catch(Exception fail){throw new IllegalStateException(fail);}return null;});
        JSONObject result=new MemoryRetriever(store).search(object("query","unrelated movie suggestion","maxChars",12000));JSONObject d=result.getJSONObject("diagnostics");assertEquals(1001,d.getLong("pinnedPopulationCount"));assertEquals(1000,d.getInt("pinnedCandidateCount"));assertTrue(d.getBoolean("pinnedCandidateLimitReached"));assertTrue(d.getBoolean("pinnedReservationSaturated"));assertEquals(12,d.getInt("selectedPinnedCount"));assertEquals(12,result.getJSONArray("memories").length());assertTrue(result.getString("context").contains("pinnedCandidatesLimited=true"));assertTrue(result.getString("context").contains("pinnedReservationSaturated=true"));
    }
    @Test public void truncatedDeviceSourceTailCannotLeakCurrentOrHistoricalDerivedClaims()throws Exception{
        JSONArray refs=new JSONArray();for(int i=0;i<128;i++){JSONObject allowed=store.appendDeviceEvent("calendar","calendar",object("text","public calendar support "+i),System.currentTimeMillis(),"allowed-source-tail-"+i);refs.put(object("type","device_event","id",allowed.getLong("id")));}JSONObject event=store.appendDeviceEvent("notification","instagram",object("text","private-tail-claim-418"),System.currentTimeMillis(),"truncated-ref-tail");refs.put(object("type","device_event","id",event.getLong("id")));refs.put(object("type","message","id",source("owner report support")));
        store.upsertMemoryWithSources("owner.source-tail","private-tail-claim-418","fact",refs);JSONObject candidate=store.getMemoryCandidate("owner.source-tail");assertEquals(130,candidate.getInt("sourceCount"));assertEquals(128,candidate.getJSONArray("sourceRefs").length());assertTrue(candidate.getBoolean("sourceRefsTruncated"));for(int i=0;i<128;i++)assertNotEquals("The revoked event must actually be outside the bounded view",event.getLong("id"),candidate.getJSONArray("sourceRefs").getJSONObject(i).getLong("id"));MemoryRetriever retriever=new MemoryRetriever(store);retriever.setDeviceSourceVisibility(e->!"notification".equals(e.optString("category")));assertFalse(retriever.search(object("query","owner.source-tail")).getString("context").contains("private-tail-claim-418"));
        note("owner.source-tail","current public source state",source("current public source state"));JSONObject current=retriever.search(object("query","owner.source-tail","includeHistory",true));assertEquals(0,current.getJSONArray("history").length());assertFalse(current.getString("context").contains("private-tail-claim-418"));retriever.setDeviceSourceVisibility(e->true);JSONObject permitted=retriever.search(object("query","owner.source-tail","includeHistory",true));assertTrue(permitted.getJSONArray("history").toString().contains("private-tail-claim-418"));
    }
    @Test public void manyNotesLinkedToOneOriginalExposeShortlistLimitsWithoutReadingAllVectors()throws Exception{
        long id=source("shared original laboratory record");FakeEncoder encoder=new FakeEncoder("source-fanout-protocol-seam");SemanticIndex index=new SemanticIndex(store,encoder);byte[] vector=VectorCodec.encode(new float[]{1,0,0},3);
        store.withTransaction(db->{try{for(int i=0;i<300;i++){JSONObject m=note("lab.note."+i,"derived laboratory entry "+i,id);android.content.ContentValues v=new android.content.ContentValues();v.put("memory_key",m.getString("key"));v.put("revision",m.getInt("revision"));v.put("model_id",encoder.modelId());v.put("source_hash",m.getString("content_hash"));v.put("dimension",3);v.put("encoding",VectorCodec.ENCODING);v.put("vector",vector);v.put("updated_at",System.currentTimeMillis());db.insertOrThrow("semantic_vectors",null,v);}}catch(Exception failure){throw new IllegalStateException(failure);}return null;});
        JSONObject chunk=object("type","message","id",id,"hash",store.getMessage(id).getString("content_hash"));JSONObject result=new StructuredMemorySearch(store,encoder,index).retrieve(new float[]{1,0,0},10,new java.util.LinkedHashSet<>(),new JSONArray().put(chunk));JSONObject d=result.getJSONObject("diagnostics");assertEquals(10,result.getJSONArray("rows").length());assertEquals(256,d.getInt("candidateCount"));assertEquals(256,d.getInt("scannedVectors"));assertTrue(d.getBoolean("candidateLimitReached"));assertFalse(d.getBoolean("wholeCorpusMemoryNeuralSearch"));assertTrue(d.getBoolean("unlinkedOrUnselectedMemoriesMayBeRelevant"));
    }
    private static SemanticCandidateProvider candidateProvider(JSONArray rows,boolean fail){return new SemanticCandidateProvider(){public boolean isReady(String model){return true;}public JSONArray chunks(float[] query,MemoryQuery constraints,int limit,java.util.function.Predicate<JSONObject> visibility)throws Exception{if(fail)throw new IllegalStateException("derived cache unavailable");return rows;}public JSONObject diagnostics(){return new JSONObject();}public JSONObject maintain(int rows,long budget){return new JSONObject();}};}
    private static final class FakeEncoder implements SemanticEncoder {
        final String model;final List<String> inputs=new ArrayList<>();FakeEncoder(String model){this.model=model;}
        public String modelId(){return model;}public int dimensions(){return 3;}public boolean isReady(){return true;}public boolean isNeural(){return true;}
        public int fittingPrefixLength(String input){int size=Math.min(70,input.length());if(size<input.length()&&Character.isHighSurrogate(input.charAt(size-1)))size--;return size;}
        public float[] encode(String text){inputs.add(text);return VectorCodec.normalize(new float[]{1,text.contains("UNIQUE")?5:1,text.contains("coffee")?5:1},3);}
    }
}
