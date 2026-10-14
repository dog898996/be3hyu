package com.hoyoung.assistant.memory;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.SourcePrivacyLedger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

/** Unseen owner-original alias graphs and actual privacy ledger/SQLite; no extractor/model call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AdaptiveMemoryRecallTest {
    private Context context;private HoyoungStore store;private SourcePrivacyLedger ledger;
    @Before public void before()throws Exception{context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());store=new HoyoungStore(context);ledger=store.getPrivacyLedger();}
    @After public void after()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());}

    @Test public void unseenQuotedAliasCrossesTwoOwnerEdgesAndDeliversExactSourcesWithoutDerivedNotes()throws Exception{
        String alias=token("Alias"),project=token("Project"),person=token("Person"),destination=token("Destination");
        long bridge=owner("“"+alias+"”는 “"+project+"” 프로젝트의 별칭이야.");
        long assigned=owner("“"+project+"”의 담당자는 “"+person+"”야.");
        long finalSource=owner("“"+person+"” destination is “"+destination+"”.");
        for(int i=0;i<200;i++)owner("별도 기록 세션 "+i+" 장비를 점검했다.");
        String query="“"+alias+"” 관련 기록";JSONArray base=store.rankedOriginalCandidates(MemoryText.expandedQuery(query),0,0,500);boolean initiallyPresent=false;for(int i=0;i<base.length();i++)initiallyPresent|=base.getJSONObject(i).getLong("id")==finalSource;assertFalse("Last hop must not be a baseline keyword hit",initiallyPresent);
        JSONObject result=search(query,12000);JSONObject adaptive=result.getJSONObject("diagnostics").getJSONObject("adaptiveRecall");
        assertTrue(hasSource(result,bridge));assertTrue(hasSource(result,assigned));assertTrue(hasSource(result,finalSource));
        assertTrue(result.getString("context").contains(destination));assertEquals(0,store.memoryCount());
        assertTrue(adaptive.getInt("queriesExecuted")>=2);assertTrue(adaptive.getInt("queriesExecuted")<=3);assertTrue(adaptive.getInt("hydratedSources")<=20);assertFalse(adaptive.getBoolean("instructionsChanged"));
        for(int i=0;i<result.getJSONArray("originalEvidence").length();i++){JSONObject evidence=result.getJSONArray("originalEvidence").getJSONObject(i);assertEquals(store.getMessage(evidence.getLong("id")).getString("content_hash"),evidence.getString("hash"));}
    }

    @Test public void negativeOrCorrectedAliasCueNeverExpandsTheRejectedName()throws Exception{
        String wrong=token("Rejected"),project=token("Canonical"),actual=token("Actual"),privateValue=token("DoNotInfer");
        owner("“"+project+"”의 별칭은 “"+wrong+"”가 아니라 “"+actual+"”야. 잘못 부르지 마.");
        long target=owner("“"+project+"” assigned reference is “"+privateValue+"”.");
        JSONObject result=search("“"+wrong+"” 관련 세부정보",12000);
        assertEquals(0,result.getJSONObject("diagnostics").getJSONObject("adaptiveRecall").getInt("queriesExecuted"));
        assertFalse(hasSource(result,target));assertFalse(result.getString("context").contains(privateValue));
    }

    @Test public void unquotedOwnerAliasSurvivesUnrelatedNegativeSentenceAndFindsOlderAssignment()throws Exception{
        String project=token("Exhibit")+" 준비팀",alias=token("Nickname"),person=token("Assigned");
        long target=owner(project+" 담당자는 "+person+"이야.");
        long cue=owner(project+"을 "+alias+"라고도 부를게. 다른 팀 이름은 아니야.");
        for(int i=0;i<80;i++)owner("별도 관리 기록 "+i+" 일반 준비 공지를 정리했다.");
        String query=alias+" 담당 누구였지?";
        // The assignment can match the generic role word. The source-backed followup must still run and preserve its concrete alias cue.
        JSONObject result=search(query,10000),adaptive=result.getJSONObject("diagnostics").getJSONObject("adaptiveRecall");
        assertTrue(result.toString(),hasSource(result,cue));assertTrue(result.toString(),hasSource(result,target));assertTrue(result.toString(),result.getString("context").contains(person));assertTrue(adaptive.toString(),adaptive.getInt("queriesExecuted")>=1);
        boolean explicitPath=false;JSONArray paths=adaptive.getJSONArray("paths");for(int i=0;i<paths.length();i++){JSONObject path=paths.getJSONObject(i);explicitPath|=path.getLong("cueSourceId")==cue&&path.getLong("targetSourceId")==target&&project.equals(path.getString("queryTerm"));}
        assertTrue("The relation must come from the ordinary unquoted owner cue",explicitPath);assertEquals(0,store.memoryCount());
    }

    @Test public void assistantInventedAliasCannotDriveOwnerRelationshipTraversal()throws Exception{
        String alias=token("GeneratedAlias"),project=token("Project"),value=token("UnlinkedValue");
        store.addMessage("assistant","“"+alias+"”는 “"+project+"”의 별명이야.",null);long target=owner("“"+project+"” assigned reference is “"+value+"”.");
        JSONObject result=search("“"+alias+"” 관련 정보",8000);assertFalse(hasSource(result,target));assertFalse(result.getString("context").contains(value));
        assertEquals(0,result.getJSONObject("diagnostics").getJSONObject("adaptiveRecall").getInt("queriesExecuted"));
    }

    @Test public void joinedKoreanGroupNicknameAndParticleReachTwoExplicitOwnerEdges()throws Exception{
        String alias=koreanNickname(),project=token("Exhibition")+" 기획팀",person=token("Coordinator"),value=token("Route");
        long last=owner("“"+person+"” destination is “"+value+"”.");
        long middle=owner("“"+project+"”의 담당자는 “"+person+"”야.");
        long cue=owner(project+"을 "+alias+"라고 부를게.");
        for(int i=0;i<120;i++)owner("별도 모임의 담당 기록 "+i+" 일반 일정 공지.");
        for(String joined:new String[]{alias+"모임의",alias+"팀에서"}) {
            String query=joined+" 담당 누구였지?";
            assertFalse("Final edge must need an actual owner relation, not the original lexical query",containsId(store.rankedOriginalCandidates(MemoryText.expandedQuery(query),0,0,500),last));
            JSONObject result=search(query,12000),adaptive=result.getJSONObject("diagnostics").getJSONObject("adaptiveRecall");
            assertTrue(result.toString(),hasSource(result,cue));assertTrue(result.toString(),hasSource(result,middle));assertTrue(result.toString(),hasSource(result,last));assertTrue(result.getString("context"),result.getString("context").contains(value));
            assertTrue(adaptive.toString(),adaptive.getInt("queriesExecuted")>=2);assertTrue(adaptive.getInt("queriesExecuted")<=3);assertTrue(adaptive.getInt("hydratedSources")<=20);assertFalse(adaptive.getBoolean("instructionsChanged"));
            assertTrue("Compound syntax must preserve the exact source-backed alias edge",hasPath(adaptive,cue,middle,project));
        }
        assertEquals(0,store.memoryCount());
    }

    @Test public void joinedNicknameAliasCueStillHonorsOwnerExclusion()throws Exception{
        String alias=koreanNickname(),project=token("Studio"),value=token("PrivateRoute");
        long target=owner("“"+project+"” destination is “"+value+"”.");
        long cue=owner(project+"을 "+alias+"라고 부를게.");exclude("message",cue);
        JSONObject result=search(alias+"모임에서 어디로 가기로 했지?",10000);
        assertFalse(result.toString(),hasSource(result,cue));assertFalse(result.toString(),hasSource(result,target));assertFalse(result.toString(),result.toString().contains(value));
        assertEquals(0,result.getJSONObject("diagnostics").getJSONObject("adaptiveRecall").getInt("queriesExecuted"));assertNotNull(store.getMessage(cue));
    }

    @Test public void joinedNicknameExpansionCannotTurnAssistantAliasIntoAnOwnerFact()throws Exception{
        String alias=koreanNickname(),project=token("Unverified"),value=token("DoNotFollow");
        store.addMessage("assistant",project+"을 "+alias+"라고 부를게.",null);
        long target=owner("“"+project+"” destination is “"+value+"”.");
        JSONObject result=search(alias+"팀의 관련 기록",10000);
        assertFalse(result.toString(),hasSource(result,target));assertFalse(result.toString(),result.toString().contains(value));
        assertEquals(0,result.getJSONObject("diagnostics").getJSONObject("adaptiveRecall").getInt("queriesExecuted"));assertEquals(0,store.memoryCount());
    }

    @Test public void groupDescriptorStemsAreSearchHintsWithMinimumLengthAndWordBoundaries()throws Exception{
        String alias=koreanNickname();
        assertTrue(MemoryText.terms(alias+"모임의").toString(),MemoryText.terms(alias+"모임의").contains(alias));
        assertTrue(MemoryText.terms(alias+"팀에서").toString(),MemoryText.terms(alias+"팀에서").contains(alias));
        assertFalse("A generic descriptor is not an entity",MemoryText.terms("모임에서 팀의").contains("모"));
        assertFalse("Do not split a descriptor occurring inside an unrelated word",MemoryText.terms(alias+"팀워크").contains(alias));
        assertTrue("The original joined name must remain available for ambiguity",MemoryText.terms(alias+"모임의").contains(alias+"모임"));
    }

    @Test public void relationshipSearchAndTinyContextExposeTheirCapsInsteadOfClaimingWholeGraph()throws Exception{
        String alias=token("Hub");StringBuilder source=new StringBuilder("“"+alias+"” 프로젝트의 별칭들 ");
        for(int i=0;i<30;i++)source.append('“').append("branch-"+i).append('”').append(' ');owner(source.toString());
        for(int i=0;i<30;i++)owner("“branch-"+i+"” 프로젝트의 담당자는 “worker-"+i+"”야.");
        JSONObject result=search("“"+alias+"” 담당 정보",1000),d=result.getJSONObject("diagnostics").getJSONObject("adaptiveRecall");
        assertTrue(d.getInt("queriesExecuted")<=3);assertTrue(d.getInt("hydratedSources")<=20);assertTrue(d.getBoolean("limitReached"));
        assertFalse(d.getBoolean("completeRelationshipGraph"));assertTrue(result.getString("context").length()<=1000);
    }

    @Test public void registrationReferenceSynonymsAndDenseWindowRecoverRawIdentifierPastGenericPrefix()throws Exception{
        String identifier=token("REF");owner("전화번호 일반 안내 ".repeat(200)+" 분실물 보관 등록코드 "+identifier+" 끝.");
        JSONObject result=search("분실물 접수번호",8000);assertTrue(result.getString("context"),result.getString("context").contains(identifier));
        assertEquals(0,store.memoryCount());
    }

    @Test public void rawOwnerSourcesKeepContextSpaceWhenManyGeneratedNotesShareTheirPrefix()throws Exception{
        String topic=token("Archive");long[] owners=new long[20];
        for(int i=0;i<owners.length;i++)owners[i]=owner(topic+" original field "+i+" = VALUE-"+i+".");
        long generated=store.addMessage("assistant",topic+" generic generated description",null).getLong("id");
        for(int i=0;i<35;i++)store.upsertMemory("generated."+i,topic+" generic reused summary "+"fluff ".repeat(200),"generated_summary",new JSONArray().put(generated));
        JSONObject result=search(topic,12000);String packed=result.getString("context");int delivered=0;
        for(long id:owners)if(packed.contains("ORIGINAL message:"+id+" hash="))delivered++;
        assertEquals(packed,20,delivered);assertTrue(packed.length()<=12000);assertTrue(result.getJSONObject("diagnostics").getBoolean("rankedSampleOnly"));
    }

    @Test public void exclusionMasksRawPinnedMixedNoteAndOldHistoryButRetainsEveryOriginal()throws Exception{
        long hidden=owner("private-vault secret phrase"),publicSource=owner("public-vault normal phrase");
        store.upsertMemory("vault.history","private-vault secret phrase","fact",new JSONArray().put(hidden));
        store.upsertMemory("vault.history","public-vault normal phrase","fact",new JSONArray().put(publicSource));
        store.upsertMemory("vault.mixed","private-vault secret phrase","summary",new JSONArray().put(hidden).put(publicSource));store.putMemoryMetadata("vault.mixed",object("pinned",true));
        exclude("message",hidden);JSONObject result=new MemoryRetriever(store).search(object("query","vault","includeHistory",true,"maxChars",12000));
        assertFalse(result.toString().contains("private-vault secret phrase"));assertFalse(hasMemory(result,"vault.mixed"));
        assertFalse(hasSource(result,hidden));assertTrue(hasSource(result,publicSource));assertNotNull(store.getMessage(hidden));assertEquals(2,store.memoryCount());assertEquals(2,store.memoryRevisions("vault.history",100).length());
    }

    @Test public void canonicalDependenciesBeyondDisplayedSourceRefsAreAllChecked()throws Exception{
        JSONArray ids=new JSONArray();long last=0;for(int i=0;i<140;i++){last=owner("fanout source "+i);ids.put(last);}
        store.upsertMemory("fanout.private","fanout hidden meaning","summary",ids);exclude("message",last);
        JSONObject result=search("fanout hidden meaning",8000);assertFalse(hasMemory(result,"fanout.private"));assertFalse(result.getString("context").contains("fanout hidden meaning"));
        assertEquals(140,store.getMemory("fanout.private").getJSONArray("sourceRefs").length());
    }

    @Test public void semanticDeviceCandidatesAndDerivedNotesCannotBypassLedger()throws Exception{
        JSONObject event=store.appendDeviceEvent("notification","fixture",object("text","SEMANTIC-PRIVATE-ONLY"),System.currentTimeMillis(),"semantic-private");long id=event.getLong("id");
        store.upsertMemoryWithSources("device.private","SEMANTIC-PRIVATE-ONLY","observation",new JSONArray().put(object("type","device_event","id",id)));exclude("device_event",id);
        FakeEncoder encoder=new FakeEncoder();MemoryRetriever retriever=new MemoryRetriever(store,encoder);retriever.indexPending(8,2000);
        retriever.setSemanticCandidateProvider(provider(object("type","device_event","id",id,"hash",event.getString("content_hash"),"modelId",encoder.modelId(),"offsetChars",0,"lengthChars",event.getJSONObject("payload").toString().length(),"semanticSimilarity",1),null));
        JSONObject result=retriever.search(object("query","opaque-random-semantic-request","maxChars",8000));assertFalse(result.toString().contains("SEMANTIC-PRIVATE-ONLY"));assertEquals(0,result.getJSONArray("originalEvidence").length());assertEquals(0,result.getJSONArray("memories").length());assertNotNull(store.getDeviceEvent(id));
    }

    @Test public void policyChangeDuringSemanticSearchRemovesSourceDataAndAdaptivePathsFromResult()throws Exception{
        long id=owner("policy-sensitive secret original");FakeEncoder encoder=new FakeEncoder();MemoryRetriever retriever=new MemoryRetriever(store,encoder);retriever.indexPending(8,2000);
        JSONObject hit=object("type","message","id",id,"hash",store.getMessage(id).getString("content_hash"),"modelId",encoder.modelId(),"offsetChars",0,"lengthChars",31,"semanticSimilarity",1);
        retriever.setSemanticCandidateProvider(provider(hit,()->{try{exclude("message",id);}catch(Exception failure){throw new IllegalStateException(failure);}}));
        JSONObject result=retriever.search(object("query","policy-sensitive","maxChars",8000));assertTrue(result.getJSONObject("diagnostics").getBoolean("retrievalBlocked"));assertFalse(result.toString().contains("secret original"));assertFalse(result.getJSONObject("diagnostics").has("adaptiveRecall"));assertEquals(0,result.getJSONArray("originalEvidence").length());
    }

    @Test public void malformedPrivacyFileFailsClosedBeforeAnySourceIsReturned()throws Exception{
        owner("PRIVATE-CORRUPT-POLICY");Files.write(new File(context.getFilesDir(),"privacy-ledger.json").toPath(),"{malformed".getBytes(StandardCharsets.UTF_8));
        JSONObject result=search("PRIVATE-CORRUPT-POLICY",8000);assertTrue(result.getJSONObject("diagnostics").getBoolean("retrievalBlocked"));assertFalse(result.toString().contains("PRIVATE-CORRUPT-POLICY"));assertEquals(0,result.getJSONArray("originalEvidence").length());
    }

    @Test public void ownerRequestDependencyAlsoMasksAssistantCopyAndItsGeneratedNote()throws Exception{
        long owner=store.addMessage("user","dependency-private original","request-parent").getLong("id");store.putSetting("requestSource:request-parent",String.valueOf(owner));
        long assistant=store.addMessage("assistant","dependency-private copied secret","request-parent").getLong("id");store.upsertMemory("dependency.copy","dependency-private copied secret","generated_summary",new JSONArray().put(assistant));
        exclude("message",owner);JSONObject result=search("dependency-private",8000);assertFalse(result.toString().contains("copied secret"));assertFalse(hasSource(result,assistant));assertFalse(hasMemory(result,"dependency.copy"));assertNotNull(store.getMessage(assistant));
    }

    @Test public void rangedAggregateRequestExplicitlyRequiresEnumerationInsteadOfTreatingTopKAsAllRecords()throws Exception{
        for(int i=0;i<30;i++)owner("지출 합계 계산 기록 "+i+" 100원");
        JSONObject result=new MemoryRetriever(store).search(object("query","지출 전체 합계","limit",3,"fromInclusive",1,"maxChars",5000));
        assertFalse(result.getJSONObject("aggregationCoverage").getBoolean("complete"));assertEquals("paged_source_enumeration",result.getJSONObject("aggregationCoverage").getString("requiredPath"));
        assertFalse(result.getJSONObject("diagnostics").getBoolean("completeRangeEnumerated"));assertTrue(result.getString("context").contains("rangeEnumerationRequired=true"));
    }

    private JSONObject search(String query,int budget)throws Exception{return new MemoryRetriever(store).search(object("query",query,"maxChars",budget,"limit",20));}
    private long owner(String text)throws Exception{return store.addMessage("user",text,null).getLong("id");}
    private void exclude(String type,long id)throws Exception{JSONObject source="message".equals(type)?store.getMessage(id):store.getDeviceEvent(id);ledger.excludeSource(type,id,source.getString("content_hash"),SourcePrivacyLedger.RETRIEVAL_EXCLUDE,"fixture owner exclusion");}
    private static boolean hasSource(JSONObject result,long id)throws Exception{JSONArray rows=result.getJSONArray("originalEvidence");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getLong("id")==id)return true;return false;}
    private static boolean hasMemory(JSONObject result,String key)throws Exception{JSONArray rows=result.getJSONArray("memories");for(int i=0;i<rows.length();i++)if(key.equals(rows.getJSONObject(i).optString("key")))return true;return false;}
    private static boolean containsId(JSONArray rows,long id)throws Exception{for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getLong("id")==id)return true;return false;}
    private static boolean hasPath(JSONObject adaptive,long cue,long target,String term)throws Exception{JSONArray paths=adaptive.getJSONArray("paths");for(int i=0;i<paths.length();i++){JSONObject path=paths.getJSONObject(i);if(path.getLong("cueSourceId")==cue&&path.getLong("targetSourceId")==target&&term.equals(path.getString("queryTerm")))return true;}return false;}
    private static String koreanNickname(){String syllables="다라바사자차카타파하";int value=UUID.randomUUID().hashCode();return ""+syllables.charAt(Math.floorMod(value,syllables.length()))+syllables.charAt(Math.floorMod(value/31,syllables.length()));}
    private static JSONObject object(Object...pairs)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static String token(String prefix){return prefix+"-"+UUID.randomUUID().toString().substring(0,8);}
    private static SemanticCandidateProvider provider(JSONObject row,Runnable before){return new SemanticCandidateProvider(){public boolean isReady(String model){return true;}public JSONArray chunks(float[] vector,MemoryQuery q,int limit,java.util.function.Predicate<JSONObject> visibility){if(before!=null)before.run();return new JSONArray().put(row);}public JSONObject diagnostics(){return new JSONObject();}public JSONObject maintain(int rows,long budget){return new JSONObject();}};}
    /** Marks ready-neural only to exercise the production branch with deterministic vectors; this is not a trained model. */
    private static final class FakeEncoder implements SemanticEncoder{public String modelId(){return "adaptive-policy-test-3d";}public int dimensions(){return 3;}public boolean isReady(){return true;}public boolean isNeural(){return true;}public float[]encode(String text){return new float[]{1,0,0};}}
    private static void delete(File file)throws Exception{if(Files.isSymbolicLink(file.toPath())){Files.deleteIfExists(file.toPath());return;}if(!file.exists())return;if(file.isDirectory()){File[]children=file.listFiles();if(children!=null)for(File child:children)delete(child);}Files.delete(file.toPath());}
}
