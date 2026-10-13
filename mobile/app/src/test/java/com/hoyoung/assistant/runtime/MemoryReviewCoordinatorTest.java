package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
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
public class MemoryReviewCoordinatorTest {
    private Context context; private HoyoungStore store; private MemoryReviewCoordinator coordinator;
    @Before public void before() { context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);coordinator=new MemoryReviewCoordinator(store); }
    @After public void after() { store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME); }
    @Test public void missingOrFabricatedAcknowledgementNeverClaimsSourceWasReviewed() throws Exception {
        store.addMessage("user","나는 부산에 살고 고양이 이름은 나비야",null);
        JSONObject batch=coordinator.prepareBatch();assertFalse(coordinator.acknowledge(batch,null,true,1));
        JSONObject ack=ack(batch,false);ack.put("batchId","invented");
        assertFalse(coordinator.acknowledge(batch,ack,true,1));assertTrue(coordinator.hasPending());assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
    }
    @Test public void partialSourcesCannotAdvanceAsIfAllOriginalsWereRead() throws Exception {
        store.addMessage("user","첫 번째 사실",null);store.addMessage("user","다른 사실",null);
        JSONObject batch=coordinator.prepareBatch(),review=ack(batch,false);
        review.put("sourceRanges",new JSONArray().put(review.getJSONArray("sourceRanges").getJSONObject(0)));
        assertFalse(coordinator.acknowledge(batch,review,true,1));assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
    }
    @Test public void durableMemoryFailurePreservesTheReviewBacklog() throws Exception {
        store.addMessage("user","짧고 정확하게 답하는걸 좋아해",null);JSONObject batch=coordinator.prepareBatch();
        assertFalse(coordinator.acknowledge(batch,ack(batch,false),false,1));assertTrue(coordinator.hasPending());
        assertTrue(coordinator.acknowledge(batch,ack(batch,false),true,1));assertFalse(coordinator.hasPending());
    }
    @Test public void noMemoryNeededRequiresExplicitDecisionAndReason() throws Exception {
        store.addMessage("user","ㅇㅇ",null);JSONObject batch=coordinator.prepareBatch(),review=ack(batch,true);
        review.put("reason","");assertFalse(coordinator.acknowledge(batch,review,true,0));
        review.put("reason","현재 대화의 단순 확인이며 새로운 개인 사실이 없다");
        assertTrue(coordinator.acknowledge(batch,review,true,0));assertFalse(coordinator.hasPending());
    }
    @Test public void largeOriginalIsReviewedInEveryExactChunkIncludingTheTail() throws Exception {
        String text="상황을 기억해줘🐱 ".repeat(2500)+" 오래된 원문 맨끝의 희귀 사실";
        long id=store.addMessage("user",text,null).getLong("id");StringBuilder observed=new StringBuilder();int batches=0;
        while(coordinator.hasPending()) {
            JSONObject batch=coordinator.prepareBatch();JSONArray ranges=batch.getJSONArray("sourceRanges");
            assertEquals(1,ranges.length());observed.append(ranges.getJSONObject(0).getString("text"));
            assertTrue(coordinator.acknowledge(batch,ack(batch,false),true,1));assertTrue(++batches<20);
        }
        assertEquals(text,observed.toString());assertEquals(id,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertTrue(batches>1);
    }
    @Test public void staleAcknowledgementCannotOverwriteNewerProgress() throws Exception {
        store.addMessage("user","첫 범위",null);JSONObject old=coordinator.prepareBatch();
        assertTrue(coordinator.acknowledge(old,ack(old,false),true,1));store.addMessage("user","새 범위",null);
        assertFalse(coordinator.acknowledge(old,ack(old,false),true,1));assertTrue(coordinator.hasPending());
    }
    @Test public void hashMismatchAndInconsistentDecisionsRemainPending() throws Exception {
        store.addMessage("user","사실 원문",null);JSONObject batch=coordinator.prepareBatch(),review=ack(batch,false);
        review.getJSONArray("sourceRanges").getJSONObject(0).put("contentHash","wrong");
        assertFalse(coordinator.acknowledge(batch,review,true,1));assertFalse(coordinator.acknowledge(batch,ack(batch,true),true,1));
        assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
    }
    @Test public void assistantStatementsAreNotOwnerReviewEvidence() throws Exception {
        store.addMessage("assistant","사용자는 유명한 의사다라는 생성 주장",null);assertFalse(coordinator.hasPending());
        long id=store.addMessage("user","난 의사가 아니라 학생이야",null).getLong("id");JSONObject batch=coordinator.prepareBatch();
        assertEquals(id,batch.getJSONArray("sourceRanges").getJSONObject(0).getLong("messageId"));
    }
    @Test public void unavailableOriginalBlocksPreparationWithoutSkippingIt() throws Exception {
        JSONObject original=store.addMessage("user","원문".repeat(110_000),null);
        File file=new File(new File(context.getFilesDir(),"originals"),original.getString("raw_file_id"));
        byte[] saved=Files.readAllBytes(file.toPath());assertTrue(file.delete());
        JSONObject blocked=coordinator.prepareBatch();assertEquals(0,blocked.getJSONArray("sourceRanges").length());
        assertEquals(original.getLong("id"),blocked.getJSONArray("blockedSources").getJSONObject(0).getLong("messageId"));
        assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertTrue(coordinator.hasPending());
        Files.write(file.toPath(),saved);assertEquals(1,coordinator.prepareBatch().getJSONArray("sourceRanges").length());
    }
    @Test public void originalBecomingUnavailableAfterPreparationNeverAdvancesCursor() throws Exception {
        JSONObject original=store.addMessage("user","보존할 대화 ".repeat(35_000),null);JSONObject batch=coordinator.prepareBatch();
        File file=new File(new File(context.getFilesDir(),"originals"),original.getString("raw_file_id"));assertTrue(file.delete());
        assertFalse(coordinator.acknowledge(batch,ack(batch,false),true,1));
        assertEquals("source_unavailable",coordinator.status().getString("lastReviewCode"));assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
    }
    @Test public void changedInlineBytesCannotBorrowOldStoredHashesForReview() throws Exception {
        long id=store.addMessage("user","정확히 이 원문",null).getLong("id");JSONObject batch=coordinator.prepareBatch();
        store.getWritableDatabase().execSQL("UPDATE messages SET text=? WHERE id=?",new Object[]{"전혀 다른 원문",id});
        assertFalse(coordinator.acknowledge(batch,ack(batch,false),true,1));assertEquals("source_changed",coordinator.status().getString("lastReviewCode"));
        assertTrue(coordinator.hasPending());
    }
    @Test public void shortConfirmationCarriesConversationContextWithoutAcknowledgingAssistantClaims() throws Exception {
        long assistantId=store.addMessage("assistant","앞으로 답변은 짧게 할까?",null).getLong("id");
        long ownerId=store.addMessage("user","ㅇㅇ",null).getLong("id");JSONObject batch=coordinator.prepareBatch();
        JSONObject range=batch.getJSONArray("sourceRanges").getJSONObject(0);
        assertEquals(ownerId,range.getLong("messageId"));JSONObject context=range.getJSONArray("contextMessages").getJSONObject(0);
        assertEquals(assistantId,context.getLong("messageId"));assertEquals("assistant",context.getString("role"));
        assertEquals("context_only_not_acknowledged",context.getString("reviewScope"));
        assertEquals(1,ack(batch,true).getJSONArray("sourceRanges").length());
    }
    @Test public void boundaryOverlapRetainsNegationButNeverWidensAcknowledgedRange() throws Exception {
        String text="a".repeat(7_998)+"알레르기는 없고 앞으로 약물 복용은 안 해"+"b".repeat(3_000);
        store.addMessage("user",text,null);JSONObject batch=coordinator.prepareBatch(),range=batch.getJSONArray("sourceRanges").getJSONObject(0);
        assertEquals(8_000,range.getLong("nextOffsetChars"));assertEquals(text.substring(8_000,8_512),range.getJSONObject("contextAfter").getString("text"));
        assertTrue(range.getBoolean("noMemoryDecisionNeedsSecondPass"));assertTrue(coordinator.acknowledge(batch,ack(batch,false),true,1));
        JSONObject next=coordinator.prepareBatch().getJSONArray("sourceRanges").getJSONObject(0);
        assertEquals(8_000,next.getLong("offsetChars"));assertEquals(text.substring(7_488,8_000),next.getJSONObject("contextBefore").getString("text"));
    }
    @Test public void durableFactCannotBeDismissedWithoutASecondExplicitPass() throws Exception {
        store.addMessage("user","나는 땅콩 알레르기가 있어 꼭 기억해",null);JSONObject first=coordinator.prepareBatch();
        assertFalse(coordinator.acknowledge(first,ack(first,true),true,0));assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        store.addMessage("assistant","이전 검토 응답이 추가돼도 범위는 같다",null);
        JSONObject second=coordinator.prepareBatch();assertEquals(first.getString("batchId"),second.getString("batchId"));assertTrue(second.getBoolean("secondPassRequired"));
        assertFalse(coordinator.acknowledge(second,ack(second,true),true,0));
        JSONObject checked=ack(second,true).put("secondPass",new JSONObject().put("reviewed",true).put("reason","중요한 사실의 기존 저장 여부를 다시 확인했다"));
        assertTrue(coordinator.acknowledge(second,checked,true,0));
    }
    @Test public void revisitPassRetainsIncrementalProgressAndReReadsOldSources() throws Exception {
        long firstId=store.addMessage("user","서울에 살았음",null).getLong("id");
        long lastId=store.addMessage("user","현재 부산에 살고 있음",null).getLong("id");JSONObject first=coordinator.prepareBatch();
        assertTrue(coordinator.acknowledge(first,ack(first,false),true,1));assertEquals(lastId,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        assertTrue(coordinator.requestRevisit(firstId,lastId,"거주지 정정 관계 재검토"));assertFalse(coordinator.requestRevisit(firstId,lastId,"진행 중 중복 요청"));
        assertTrue(coordinator.hasPending());JSONObject repeat=coordinator.prepareBatch();assertEquals("revisit",repeat.getString("reviewMode"));
        assertEquals(firstId,repeat.getJSONArray("sourceRanges").getJSONObject(0).getLong("messageId"));
        assertTrue(coordinator.acknowledge(repeat,ack(repeat,false),true,1));assertFalse(coordinator.hasPending());
        assertEquals(lastId,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        assertFalse(coordinator.acknowledge(repeat,ack(repeat,false),true,1));
    }
    @Test public void sourceUnavailableAfterEarlierReadableSourcesDoesNotSkipBlockedTail() throws Exception {
        long readable=store.addMessage("user","앞부분 원문",null).getLong("id");
        JSONObject missing=store.addMessage("user","저장 원문 ".repeat(45_000),null);assertTrue(new File(new File(context.getFilesDir(),"originals"),missing.getString("raw_file_id")).delete());
        JSONObject batch=coordinator.prepareBatch();assertEquals(1,batch.getJSONArray("sourceRanges").length());assertEquals(1,batch.getJSONArray("blockedSources").length());
        assertTrue(coordinator.acknowledge(batch,ack(batch,false),true,1));assertEquals(readable,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertTrue(coordinator.hasPending());
    }
    @Test public void revisitEndingAtAnAssistantIdDoesNotLeaveAnUnreachableCursor() throws Exception {
        long owner=store.addMessage("user","원문",null).getLong("id");
        long assistant=store.addMessage("assistant","결과",null).getLong("id");
        JSONObject first=coordinator.prepareBatch();assertTrue(coordinator.acknowledge(first,ack(first,false),true,1));
        assertTrue(coordinator.requestRevisit(owner,assistant,"범위 끝이 사용자 아닌 경우"));
        JSONObject repeated=coordinator.prepareBatch();assertEquals(owner,repeated.getLong("revisitThroughMessageId"));
        assertTrue(coordinator.acknowledge(repeated,ack(repeated,false),true,1));assertFalse(coordinator.hasPending());
    }
    @Test public void priorLongQuestionUsesTailContextForShortConfirmation() throws Exception {
        String question="내용 ".repeat(2_000)+"그러면 답변은 짧게 하고 중요한 근거만 남길까?";
        store.addMessage("assistant",question,null);store.addMessage("user","ㅇㅇ",null);
        JSONObject neighbor=coordinator.prepareBatch().getJSONArray("sourceRanges").getJSONObject(0).getJSONArray("contextMessages").getJSONObject(0);
        assertTrue(neighbor.getString("text").endsWith("그러면 답변은 짧게 하고 중요한 근거만 남길까?"));assertEquals("tail",neighbor.getString("excerptPosition"));
    }
    @Test public void serializedBudgetCountsEscapingAndDoesNotSkipAnyDroppedTail() throws Exception {
        String text="control:"+"\u0000\u0001\u0002\n\t".repeat(800);long id=store.addMessage("user",text,null).getLong("id");
        StringBuilder observed=new StringBuilder();int calls=0;
        while(coordinator.hasPending()) {
            JSONObject batch=coordinator.prepareBatch(4_096);assertTrue(batch.toString().length()<=4_096);
            assertEquals(1,batch.getJSONArray("sourceRanges").length());JSONObject range=batch.getJSONArray("sourceRanges").getJSONObject(0);
            assertTrue(range.getLong("nextOffsetChars")>range.getLong("offsetChars"));observed.append(range.getString("text"));
            assertTrue(coordinator.acknowledge(batch,ack(batch,false),true,1));assertTrue(++calls<100);
        }
        assertEquals(text,observed.toString());assertEquals(id,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertTrue(calls>1);
    }
    @Test public void tightBudgetMayDropLaterSourcesWithoutAdvancingPastTheirRanges() throws Exception {
        long first=store.addMessage("user","원문:"+"\u0000".repeat(1_000),null).getLong("id");
        long tail=store.addMessage("user","두 번째 원문 ".repeat(200),null).getLong("id");JSONObject batch=coordinator.prepareBatch(4_096);
        assertTrue(batch.toString().length()<=4_096);assertEquals(1,batch.getJSONArray("sourceRanges").length());
        assertEquals(first,batch.getJSONArray("sourceRanges").getJSONObject(0).getLong("messageId"));
        assertTrue(coordinator.acknowledge(batch,ack(batch,false),true,1));assertTrue(store.getWatermark(MemoryReviewCoordinator.WATERMARK)<tail);assertTrue(coordinator.hasPending());
    }
    @Test public void serializedBudgetIsSurrogateSafeAndIncludesSecondPassMetadata() throws Exception {
        String text="🐱\u0000".repeat(500)+"알레르기 기억해";store.addMessage("user",text,null);
        JSONObject batch=coordinator.prepareBatch(4_096);String part=batch.getJSONArray("sourceRanges").getJSONObject(0).getString("text");
        assertFalse(Character.isHighSurrogate(part.charAt(part.length()-1)));assertTrue(batch.toString().length()<=4_096);
        // Use the full source so the risk cue appears in the first no-memory decision.
        JSONObject full=coordinator.prepareBatch();JSONObject first=ack(full,true).put("reason","검토 이유:"+"\u0000".repeat(5_000));
        assertFalse(coordinator.acknowledge(full,first,true,0));JSONObject repeated=coordinator.prepareBatch();
        assertTrue(repeated.toString().length()<=60_000);assertTrue(repeated.getBoolean("secondPassRequired"));
    }
    @Test public void captionTextAcknowledgementCannotClaimItsAssetsWereAnalyzed() throws Exception {
        JSONObject fixture=assetOwner(2);long id=fixture.getLong("messageId");JSONObject batch=coordinator.prepareBatch();
        assertEquals(2,batch.getJSONArray("sourceRanges").getJSONObject(0).getJSONArray("assetRefs").length());
        assertTrue(coordinator.acknowledge(batch,ack(batch,true),true,0));assertEquals(id,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        assertTrue(coordinator.hasPending());assertEquals(2,coordinator.status().getLong("assetsPending"));assertEquals(1,coordinator.status().getLong("assetSourcesPending"));
        JSONObject assets=coordinator.prepareBatch(4096);assertEquals("asset",assets.getString("reviewMode"));assertTrue(assets.toString().length()<=4096);
        assertEquals(id,assets.getLong("cursorStart"));assertEquals(id,assets.getLong("cursorEnd"));
        assertFalse(coordinator.acknowledge(assets,ack(assets,true),true,0));assertEquals(2,coordinator.status().getLong("assetsPending"));
    }
    @Test public void assetAnalysisNeedsObservedBoundUploadAndActualResponseHash() throws Exception {
        JSONObject fixture=assetOwner(1),asset=fixture.getJSONArray("assets").getJSONObject(0);long id=fixture.getLong("messageId");
        String assetId=asset.getString("id"),sha=asset.getString("sha256"),analysis="제공자가 분석한 내용";
        assertFalse(coordinator.acceptAssetAnalysis(id,assetId,sha,analysis).getBoolean("ok"));
        JSONObject valid=providerReceipt(id,asset,"provider",analysis);
        assertFalse(coordinator.acceptAssetAnalysis(id,assetId,"0".repeat(64),analysis,valid).getBoolean("ok"));
        assertFalse(coordinator.acceptAssetAnalysis(id,assetId,sha,analysis,new JSONObject(valid.toString()).put("uploadObserved",false)).getBoolean("ok"));
        assertFalse(coordinator.acceptAssetAnalysis(id,assetId,sha,analysis,new JSONObject(valid.toString()).put("providerResponseHash","forged")).getBoolean("ok"));
        store.putSetting("attachmentMetadata:provider","[]");
        assertFalse(coordinator.acceptAssetAnalysis(id,assetId,sha,analysis,valid).getBoolean("ok"));
        assertEquals(1,coordinator.status().getLong("assetsPending"));assertEquals(0,store.memoryCount());
    }
    @Test public void providerAnalysisIsPreservedAsUnverifiedObservationWithExactAssetIdentity() throws Exception {
        JSONObject fixture=assetOwner(1),asset=fixture.getJSONArray("assets").getJSONObject(0);long id=fixture.getLong("messageId");
        JSONObject textBatch=coordinator.prepareBatch();assertTrue(coordinator.acknowledge(textBatch,ack(textBatch,true),true,0));
        String analysis="사진에 고양이로 보이는 물체가 있어. 사용자의 실제 반려동물인지 불명확해.\n원문은 그대로 보존됨.";
        JSONObject receipt=providerReceipt(id,asset,"provider",analysis);JSONObject result=coordinator.acceptAssetAnalysis(id,asset.getString("id"),asset.getString("sha256"),analysis,receipt);
        assertTrue(result.getBoolean("ok"));assertFalse(result.getBoolean("analysisVerified"));assertFalse(coordinator.hasPending());
        long observedSource=result.getLong("analysisSourceMessageId");assertEquals(analysis,store.readOriginalText(observedSource,0,1000).getString("text"));
        JSONObject memory=store.getMemory(result.getString("memoryKey"));assertEquals("observation",memory.getString("kind"));
        assertEquals("provider_asset_analysis",memory.getJSONObject("metadata").getString("provenance"));assertFalse(memory.getJSONObject("metadata").getBoolean("analysisVerified"));
        assertEquals(asset.getString("sha256"),memory.getJSONObject("metadata").getJSONArray("assetRefs").getJSONObject(0).getString("sha256"));
        long before=store.messageCount();assertTrue(coordinator.acceptAssetAnalysis(id,asset.getString("id"),asset.getString("sha256"),analysis,receipt).getBoolean("cached"));assertEquals(before,store.messageCount());
    }
    @Test public void oneAnalysisDoesNotFinishTheOtherAssetAndReanalysisRestoresBacklog() throws Exception {
        JSONObject fixture=assetOwner(2);long id=fixture.getLong("messageId");JSONArray assets=fixture.getJSONArray("assets");
        JSONObject first=assets.getJSONObject(0),second=assets.getJSONObject(1);
        String firstText="첫 번째 첨부 분석",secondText="두 번째 첨부 분석";
        assertTrue(coordinator.acceptAssetAnalysis(id,first.getString("id"),first.getString("sha256"),firstText,providerReceipt(id,first,"first-provider",firstText)).getBoolean("ok"));
        assertEquals(1,coordinator.status().getLong("assetsPending"));assertEquals("partially_analyzed",coordinator.assetCoverage(id).getString("stage"));
        assertTrue(coordinator.acceptAssetAnalysis(id,second.getString("id"),second.getString("sha256"),secondText,providerReceipt(id,second,"second-provider",secondText)).getBoolean("ok"));
        assertEquals(0,coordinator.status().getLong("assetsPending"));
        assertTrue(coordinator.markAssetNeedsReanalysis(id,first.getString("id"),"새 관점에서 재검토"));assertEquals(1,coordinator.status().getLong("assetsPending"));
        assertEquals("needs_reanalysis",coordinator.assetCoverage(id).getString("stage"));
    }
    @Test public void ownerConfirmationIsLaterRealSourceAndStillNotIndependentImageProof() throws Exception {
        JSONObject fixture=assetOwner(1),asset=fixture.getJSONArray("assets").getJSONObject(0);long id=fixture.getLong("messageId");String analysis="첨부에 대한 분석";
        coordinator.acceptAssetAnalysis(id,asset.getString("id"),asset.getString("sha256"),analysis,providerReceipt(id,asset,"provider",analysis));
        assertFalse(coordinator.confirmAssetAnalysis(id,asset.getString("id"),asset.getString("sha256"),id).getBoolean("ok"));
        long assistant=store.addMessage("assistant","맞다는 생성 주장",null).getLong("id");assertThrows(IllegalArgumentException.class,()->coordinator.confirmAssetAnalysis(id,asset.getString("id"),asset.getString("sha256"),assistant));
        long confirmation=store.addMessage("user","그 이미지 분석은 내가 확인했어",null).getLong("id");
        JSONObject result=coordinator.confirmAssetAnalysis(id,asset.getString("id"),asset.getString("sha256"),confirmation);
        assertEquals("user_confirmed",result.getString("stage"));assertFalse(result.getBoolean("analysisVerified"));assertEquals(confirmation,result.getLong("confirmationMessageId"));
    }
    @Test public void discoveryFindsOlderAssetAfterItsTextWasAlreadyReviewed() throws Exception {
        long id=store.addMessage("user","예전 첨부 요청","old-owner").getLong("id");JSONObject batch=coordinator.prepareBatch();assertTrue(coordinator.acknowledge(batch,ack(batch,true),true,0));
        InternalFiles files=new InternalFiles(context);files.writeFile("legacy-fixture.txt","파일 원본");JSONArray assets=files.freezeAttachments(new JSONArray().put("legacy-fixture.txt"));
        store.putSetting("attachmentMetadata:old-owner",assets.toString());assertTrue(coordinator.registerAssetSource(id));
        assertTrue(coordinator.hasPending());assertEquals("asset",coordinator.prepareBatch().getString("reviewMode"));assertEquals(id,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        assertEquals("needs_reanalysis",coordinator.assetCoverage(id).getString("stage"));
    }
    @Test public void pendingAssetsAndTheirAnalysisSurviveProcessRestartAndBackup() throws Exception {
        JSONObject fixture=assetOwner(1);long id=fixture.getLong("messageId");JSONObject batch=coordinator.prepareBatch();assertTrue(coordinator.acknowledge(batch,ack(batch,true),true,0));
        JSONObject backup=store.exportSnapshot();store.close();store=new HoyoungStore(context);coordinator=new MemoryReviewCoordinator(store);
        assertTrue(coordinator.hasPending());assertEquals(1,coordinator.status().getLong("assetsPending"));
        store.importSnapshot(backup);assertEquals("asset",coordinator.prepareBatch().getString("reviewMode"));assertEquals(id,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
    }
    @Test public void corruptCoverageAfterTextReviewStaysPendingAcrossRestartAndPreservesEvidence() throws Exception {
        JSONObject fixture=assetOwner(2);long id=fixture.getLong("messageId");JSONObject batch=coordinator.prepareBatch();assertTrue(coordinator.acknowledge(batch,ack(batch,true),true,0));
        String corrupt="{ malformed provider state";store.putSetting("assetCoverage:"+id,corrupt);
        store.close();store=new HoyoungStore(context);coordinator=new MemoryReviewCoordinator(store);
        assertTrue(coordinator.hasPending());assertEquals(2,coordinator.status().getLong("assetsPending"));assertEquals(id,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        JSONObject repaired=coordinator.assetCoverage(id);assertEquals("raw_saved",repaired.getString("stage"));assertEquals("coverage_json_unreadable",repaired.getString("sourceIntegrity"));assertTrue(repaired.getBoolean("originalIdentityMetadataVerified"));
        for(int i=0;i<2;i++)assertEquals("raw_saved",repaired.getJSONArray("assets").getJSONObject(i).getString("coverageStage"));
        JSONObject original=new JSONObject(store.readOriginalText(repaired.getLong("previousCoverageSourceMessageId"),0,2000).getString("text"));assertEquals(corrupt,original.getString("rawCoverage"));
        JSONObject next=coordinator.prepareBatch();assertEquals("asset",next.getString("reviewMode"));assertEquals("coverage_json_unreadable",next.getJSONArray("sourceRanges").getJSONObject(0).getString("assetSourceIntegrity"));
    }
    @Test public void forgedAnalyzedStageWithoutProviderProofDoesNotClearBacklog() throws Exception {
        JSONObject fixture=assetOwner(1);long id=fixture.getLong("messageId");JSONObject coverage=coordinator.assetCoverage(id);
        coverage.put("stage","provider_analyzed");coverage.getJSONArray("assets").getJSONObject(0).put("coverageStage","provider_analyzed").put("analysisSourceMessageId",999999).put("analysisHash","a".repeat(64));
        store.putSetting("assetCoverage:"+id,coverage.toString());assertTrue(coordinator.hasPending());assertEquals(1,coordinator.status().getLong("assetsPending"));
        JSONObject repaired=coordinator.assetCoverage(id);assertEquals("provider_analysis_proof_missing",repaired.getString("sourceIntegrity"));assertEquals("raw_saved",repaired.getString("stage"));
    }
    @Test public void unreadableOriginalMetadataKeepsUnknownAssetsRatherThanClaimingNoWork() throws Exception {
        JSONObject fixture=assetOwner(1);long id=fixture.getLong("messageId");JSONObject text=coordinator.prepareBatch();assertTrue(coordinator.acknowledge(text,ack(text,true),true,0));
        store.putSetting("assetCoverage:"+id,"invalid coverage");store.putSetting("attachmentMetadata:asset-owner","invalid original metadata");
        assertTrue(coordinator.hasPending());JSONObject repaired=coordinator.assetCoverage(id);assertEquals(1,repaired.getInt("pendingUnknownAssets"));assertFalse(repaired.getBoolean("originalIdentityMetadataVerified"));
        assertEquals(1,coordinator.status().getLong("assetsPending"));JSONObject batch=coordinator.prepareBatch();assertTrue(batch.getJSONArray("sourceRanges").getJSONObject(0).getBoolean("assetsNeedAnalysis"));
        assertFalse(coordinator.acknowledge(batch,ack(batch,true),true,0));assertEquals(1,coordinator.status().getLong("assetsPending"));
    }
    @Test public void emptyAndDeeplyNestedCoverageCannotPretendPreviouslyAttachedAssetsDisappeared() throws Exception {
        JSONObject fixture=assetOwner(1);long id=fixture.getLong("messageId");store.putSetting("assetCoverage:"+id,"{\"stage\":\"provider_analyzed\",\"assets\":[]}");
        assertTrue(coordinator.hasPending());assertEquals("coverage_assets_missing",coordinator.assetCoverage(id).getString("sourceIntegrity"));
        store.putSetting("assetCoverage:"+id,"{\"assets\":"+"[".repeat(1000)+"0"+"]".repeat(1000)+"}");
        assertTrue(coordinator.hasPending());assertEquals("coverage_json_unreadable",coordinator.assetCoverage(id).getString("sourceIntegrity"));assertEquals(1,coordinator.status().getLong("assetsPending"));
    }
    @Test public void boundedIntegrityAuditDetectsCorruptPreviouslyCompletedCoverageAfterRestart() throws Exception {
        JSONObject fixture=assetOwner(1),asset=fixture.getJSONArray("assets").getJSONObject(0);long id=fixture.getLong("messageId");
        JSONObject text=coordinator.prepareBatch();assertTrue(coordinator.acknowledge(text,ack(text,true),true,0));String analysis="이미 제공자가 분석한 원문";
        coordinator.acceptAssetAnalysis(id,asset.getString("id"),asset.getString("sha256"),analysis,providerReceipt(id,asset,"provider",analysis));assertFalse(coordinator.hasPending());
        store.putSetting("assetCoverage:"+id,"corrupt completed coverage");store.close();store=new HoyoungStore(context);coordinator=new MemoryReviewCoordinator(store);
        assertTrue(coordinator.hasPending());assertEquals(1,coordinator.status().getLong("assetsPending"));assertEquals(id,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        assertEquals("raw_saved",coordinator.assetCoverage(id).getString("stage"));assertEquals(1,store.memoryCount());
    }
    @Test public void assetAcknowledgementSucceedsOnlyAfterAllStoredObservedReceiptsAndNeverMovesTextCursor() throws Exception {
        JSONObject fixture=assetOwner(2);long id=fixture.getLong("messageId");JSONArray assets=fixture.getJSONArray("assets");
        JSONObject text=coordinator.prepareBatch();assertTrue(coordinator.acknowledge(text,ack(text,true),true,0));JSONObject assetBatch=coordinator.prepareBatch();
        assertFalse(coordinator.acknowledge(assetBatch,ack(assetBatch,true),true,0));assertEquals(2,coordinator.status().getLong("assetsPending"));
        JSONObject first=assets.getJSONObject(0),second=assets.getJSONObject(1);String a="첫 분석",b="둘째 분석";
        coordinator.acceptAssetAnalysis(id,first.getString("id"),first.getString("sha256"),a,providerReceipt(id,first,"provider-a",a));
        assertFalse(coordinator.acknowledge(assetBatch,ack(assetBatch,true),true,0));assertEquals(1,coordinator.status().getLong("assetsPending"));
        coordinator.acceptAssetAnalysis(id,second.getString("id"),second.getString("sha256"),b,providerReceipt(id,second,"provider-b",b));
        assertTrue(coordinator.acknowledge(assetBatch,null,false,0));assertEquals("asset_receipts_verified",coordinator.status().getString("lastReviewCode"));
        assertEquals(id,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertEquals(0,coordinator.status().getLong("assetsPending"));
        JSONObject altered=new JSONObject(assetBatch.toString());altered.getJSONArray("sourceRanges").getJSONObject(0).getJSONArray("assetRefs").getJSONObject(0).put("sha256","0".repeat(64));
        assertFalse(coordinator.acknowledge(altered,ack(altered,true),true,0));
        store.putSetting(coordinator.assetCoverage(id).getJSONArray("assets").getJSONObject(0).getString("analysisReceiptKey"),"broken receipt");
        assertFalse(coordinator.acknowledge(assetBatch,ack(assetBatch,true),true,0));assertTrue(coordinator.status().getLong("assetsPending")>0);
    }
    @Test public void sameBinaryHashWithDifferentAssetIdsDoesNotOverwriteAnalysisProof() throws Exception {
        InternalFiles files=new InternalFiles(context);files.writeFile("same-body.txt","같은 원본 바이트");files.writeFile("same-body.md","같은 원본 바이트");
        JSONArray assets=files.freezeAttachments(new JSONArray().put("same-body.txt").put("same-body.md"));JSONObject first=assets.getJSONObject(0),second=assets.getJSONObject(1);
        assertEquals(first.getString("sha256"),second.getString("sha256"));assertNotEquals(first.getString("id"),second.getString("id"));
        store.enqueueRequest("첨부 파일 확인해 줘","user","same-owner");long id=store.addMessage("user","첨부 파일 확인해 줘","same-owner").getLong("id");
        store.putSetting("attachmentMetadata:same-owner",assets.toString());store.putSetting("assetCoverage:"+id,new JSONObject().put("stage","raw_saved").put("assets",assets).put("analysisVerified",false).toString());coordinator.registerAssetSource(id);
        JSONObject text=coordinator.prepareBatch();assertTrue(coordinator.acknowledge(text,ack(text,true),true,0));JSONObject expected=coordinator.prepareBatch();
        String a="텍스트 파일 분석",b="마크다운 파일 분석";
        assertTrue(coordinator.acceptAssetAnalysis(id,first.getString("id"),first.getString("sha256"),a,providerReceipt(id,first,"same-provider-a",a)).getBoolean("ok"));
        assertTrue(coordinator.acceptAssetAnalysis(id,second.getString("id"),second.getString("sha256"),b,providerReceipt(id,second,"same-provider-b",b)).getBoolean("ok"));
        assertFalse(coordinator.hasPending());assertTrue(coordinator.acknowledge(expected,null,false,0));
        JSONArray covered=coordinator.assetCoverage(id).getJSONArray("assets");assertNotEquals(covered.getJSONObject(0).getString("analysisReceiptKey"),covered.getJSONObject(1).getString("analysisReceiptKey"));
    }
    private JSONObject assetOwner(int count)throws Exception {
        InternalFiles files=new InternalFiles(context);JSONArray ids=new JSONArray();
        for(int i=0;i<count;i++){String id="asset-fixture-"+i+".txt";files.writeFile(id,"테스트 첨부 원문 "+i);ids.put(id);}
        JSONArray assets=files.freezeAttachments(ids);store.enqueueRequest("첨부 파일 확인해 줘","user","asset-owner");
        long messageId=store.addMessage("user","첨부 파일 확인해 줘","asset-owner").getLong("id");
        store.putSetting("attachmentMetadata:asset-owner",assets.toString());store.putSetting("assetCoverage:"+messageId,new JSONObject().put("stage","raw_saved").put("assets",assets).put("analysisVerified",false).toString());
        coordinator.registerAssetSource(messageId);return new JSONObject().put("messageId",messageId).put("assets",assets);
    }
    private JSONObject providerReceipt(long sourceId,JSONObject asset,String requestId,String analysis)throws Exception {
        JSONObject request=store.enqueueRequest("첨부 분석","memory-review",requestId);
        store.putSetting("attachmentMetadata:"+requestId,new JSONArray().put(asset).toString());
        String raw=new JSONObject().put("protocol","hoyoung.v1").put("requestId",requestId).put("assetAnalysis",analysis).toString();store.markRequest(request.getLong("id"),"accepted",raw);
        return new JSONObject().put("providerRequestId",requestId).put("sourceMessageId",sourceId).put("assetId",asset.getString("id")).put("sha256",asset.getString("sha256"))
                .put("uploadObserved",true).put("responseObserved",true).put("analysisHash",hash(analysis)).put("providerResponseHash",store.getRequest(request.getLong("id")).getString("raw_result_hash"));
    }
    private static String hash(String text)throws Exception {
        StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private static JSONObject ack(JSONObject batch,boolean none)throws Exception {
        JSONArray ranges=new JSONArray();JSONArray source=batch.getJSONArray("sourceRanges");
        for(int i=0;i<source.length();i++){JSONObject original=source.getJSONObject(i),range=new JSONObject();
            for(String key:new String[]{"messageId","offsetChars","nextOffsetChars","contentHash"})range.put(key,original.get(key));ranges.put(range);}
        return new JSONObject().put("batchId",batch.getString("batchId")).put("sourceRanges",ranges).put("noMemoryNeeded",none).put("reason",none?"기억할 새 내용 없음":"출처 기반 기억 제안");
    }
}
