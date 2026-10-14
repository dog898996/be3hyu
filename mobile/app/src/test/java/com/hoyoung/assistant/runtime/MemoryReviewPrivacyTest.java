package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Real native SQLite review paths, with frozen policy and no account/model calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryReviewPrivacyTest {
    private Context context; private HoyoungStore store; private MemoryReviewCoordinator review;
    @Before public void before()throws Exception{context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());context.getFilesDir().mkdirs();store=new HoyoungStore(context);review=new MemoryReviewCoordinator(store);}
    @After public void after()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());}
    private long source(String text)throws Exception{return store.addMessage("user",text,null).getLong("id");}
    private void exclude(long id)throws Exception{store.getPrivacyLedger().excludeSource("message",id,store.getMessage(id).getString("content_hash"),"retrieval_exclude","explicit owner exclusion");}
    private static JSONObject ack(JSONObject batch)throws Exception{JSONArray ranges=new JSONArray();JSONArray wanted=batch.getJSONArray("sourceRanges");for(int i=0;i<wanted.length();i++){JSONObject r=wanted.getJSONObject(i);ranges.put(new JSONObject().put("messageId",r.getLong("messageId")).put("offsetChars",r.getLong("offsetChars")).put("nextOffsetChars",r.getLong("nextOffsetChars")).put("contentHash",r.getString("contentHash")));}return new JSONObject().put("batchId",batch.getString("batchId")).put("sourceRanges",ranges).put("noMemoryNeeded",false);}
    private static void forbidden(JSONObject batch,String...values){String text=batch.toString();for(String value:values)assertFalse(value,text.contains(value));}

    @Test public void excludedPrefixHasNativeNonReviewReceiptAndFutureAllowedReviewContinues()throws Exception{
        long old=source("FORGOTTEN-PREFIX-3178"),allowed=source("new allowed owner fact");String hash=store.getMessage(old).getString("content_hash");exclude(old);
        JSONObject batch=review.prepareBatch();assertEquals(old,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertEquals(allowed,batch.getJSONArray("sourceRanges").getJSONObject(0).getLong("messageId"));
        JSONObject policy=batch.getJSONObject("policyExcludedRange");assertEquals(1,policy.getInt("sourceCount"));assertFalse(policy.getBoolean("reviewed"));assertFalse(policy.getBoolean("payloadRead"));forbidden(batch,"FORGOTTEN-PREFIX-3178",hash);
        review.assertBatchAllowed(batch);assertTrue(review.acknowledge(batch,ack(batch),true,1));assertFalse(review.hasPending());
    }
    @Test public void excludedHoleCannotSkipEarlierAllowedUnreviewedSource()throws Exception{
        long first=source("first allowed owner fact"),old=source("FORGOTTEN-HOLE-9361"),last=source("last allowed owner fact");exclude(old);
        JSONObject batch=review.prepareBatch();assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertEquals(1,batch.getJSONArray("sourceRanges").length());assertEquals(first,batch.getLong("cursorEnd"));forbidden(batch,"FORGOTTEN-HOLE-9361");
        assertTrue(review.acknowledge(batch,ack(batch),true,1));batch=review.prepareBatch();assertEquals(old,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertEquals(last,batch.getJSONArray("sourceRanges").getJSONObject(0).getLong("messageId"));assertTrue(review.acknowledge(batch,ack(batch),true,1));assertFalse(review.hasPending());
    }
    @Test public void allExcludedHistoryDoesNotPermanentlyBlockOrdinaryFutureReview()throws Exception{
        long first=source("FORGOTTEN-ALL-1"),last=source("FORGOTTEN-ALL-2");exclude(first);exclude(last);assertFalse(review.hasPending());assertEquals(last,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        long future=source("future allowed owner fact");assertTrue(review.hasPending());JSONObject batch=review.prepareBatch();assertEquals(future,batch.getJSONArray("sourceRanges").getJSONObject(0).getLong("messageId"));assertTrue(review.acknowledge(batch,ack(batch),true,1));assertFalse(review.hasPending());
    }
    @Test public void excludedPartialSourceResetsOffsetWithoutClaimingRemainingBytesReviewed()throws Exception{
        long id=source("FORGOTTEN-PARTIAL-7754 ".repeat(1000));JSONObject first=review.prepareBatch();assertTrue(review.acknowledge(first,ack(first),true,1));assertTrue(review.status().getLong("partialOffsetChars")>0);exclude(id);
        assertFalse(review.hasPending());assertEquals(0,review.status().getLong("partialOffsetChars"));assertEquals(id,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
    }
    @Test public void revisitExclusionsKeepIncrementalWatermarkAndDoNotResurrectOldRanges()throws Exception{
        long old=source("FORGOTTEN-REVISIT-1312"),allowed=source("allowed current fact");JSONObject first=review.prepareBatch();assertTrue(review.acknowledge(first,ack(first),true,1));exclude(old);
        assertTrue(review.requestRevisit(old,allowed,"owner revisit"));JSONObject repeat=review.prepareBatch();assertEquals(allowed,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertEquals(allowed,repeat.getJSONArray("sourceRanges").getJSONObject(0).getLong("messageId"));forbidden(repeat,"FORGOTTEN-REVISIT-1312");assertTrue(review.acknowledge(repeat,ack(repeat),true,1));assertFalse(review.hasPending());
    }
    @Test public void parentBoundAssistantNeighborCannotCopyForgottenOwnerText()throws Exception{
        long old=source("FORGOTTEN-COPY-2813");store.putSetting("requestSource:copied-reply",String.valueOf(old));store.addMessage("assistant","FORGOTTEN-COPY-2813 repeated","copied-reply");source("yes");exclude(old);
        JSONObject batch=review.prepareBatch();forbidden(batch,"FORGOTTEN-COPY-2813");assertTrue(review.acknowledge(batch,ack(batch),true,1));
    }
    @Test public void cachedBatchIsRejectedBeforeResendingAndBeforeAcknowledgementAfterExclusion()throws Exception{
        long id=source("FORGOTTEN-CACHE-7178");JSONObject cached=review.prepareBatch();review.assertBatchAllowed(cached);exclude(id);
        try{review.assertBatchAllowed(cached);fail("Cached old context must not be resent");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("privacy"));}
        assertFalse(review.acknowledge(cached,ack(cached),true,1));assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));assertFalse(review.hasPending());
    }
    @Test public void legacyBatchWithoutFingerprintStillCannotSendAnExcludedSource()throws Exception{
        long id=source("FORGOTTEN-LEGACY-7122");JSONObject cached=review.prepareBatch();cached.remove("sourcePrivacyFingerprint");exclude(id);
        try{review.assertBatchAllowed(cached);fail("Legacy cache cannot bypass source policy");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("excluded"));}
    }
    @Test public void corruptedPrivacyPolicyBlocksReviewInsteadOfAssumingNoExclusions()throws Exception{
        long id=source("FORGOTTEN-CORRUPT-5543");exclude(id);Files.write(new File(context.getFilesDir(),"privacy-ledger.json").toPath(),"{bad".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try{review.prepareBatch();fail("Corrupt privacy must block");}catch(IllegalStateException expected){assertEquals("memory_review_privacy_unavailable",expected.getMessage());}
        assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
    }
    @Test public void excludedAssetSourceDoesNotExposeOldAssetIdentityOrAnalysisAndPendingClears()throws Exception{
        String sha="a".repeat(64),assetId=".attachment-originals/"+sha+".png";long id=store.addMessage("user","FORGOTTEN-ASSET-4266","asset-owner").getLong("id");
        store.putSetting("attachmentMetadata:asset-owner",new JSONArray().put(new JSONObject().put("id",assetId).put("sha256",sha).put("immutable",true).put("name","private.png")).toString());assertTrue(review.registerAssetSource(id));exclude(id);
        JSONObject coverage=review.assetCoverage(id);assertEquals("source_excluded",coverage.getString("stage"));assertEquals(0,coverage.getJSONArray("assets").length());forbidden(coverage,sha,assetId,"private.png");
        JSONObject result=review.acceptAssetAnalysis(id,assetId,sha,"FORGOTTEN-ANALYSIS-5544",new JSONObject());assertFalse(result.getBoolean("ok"));assertEquals(1,store.messageCount());
        assertFalse(review.hasPending());JSONObject batch=review.prepareBatch();forbidden(batch,"FORGOTTEN-ASSET-4266",sha,assetId);assertEquals(0,review.status().getLong("assetsPending"));
    }
    @Test public void cachedAllowedRangeWithLaterExcludedNeighborIsBlockedEvenWithoutFingerprint()throws Exception{
        long old=source("FORGOTTEN-NEIGHBOR-5018");source("yes");JSONObject batch=review.prepareBatch();batch.remove("sourcePrivacyFingerprint");
        JSONArray ranges=batch.getJSONArray("sourceRanges");ranges.remove(0);exclude(old);
        try{review.assertBatchAllowed(batch);fail("Neighbor source privacy must be checked");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("excluded"));}
    }
    @Test public void exclusionDuringNativeTransactionRejectsResultAndRollsBackProgress()throws Exception{
        long id=source("FORGOTTEN-INFLIGHT-7705");String hash=store.getMessage(id).getString("content_hash");
        java.util.concurrent.CountDownLatch hydrated=new java.util.concurrent.CountDownLatch(1),excluded=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        java.lang.reflect.Method guarded=MemoryReviewCoordinator.class.getDeclaredMethod("withPrivacyTransaction",HoyoungStore.TransactionOperation.class);guarded.setAccessible(true);
        try{
            java.util.concurrent.Future<Object> result=executor.submit(()->{
                try{return guarded.invoke(review,(HoyoungStore.TransactionOperation<JSONObject>)db->{
                    JSONObject original;
                    try{original=store.readOriginalText(id,0,1000);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}
                    store.putSetting("review-test-progress","must rollback");hydrated.countDown();
                    try{if(!excluded.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test barrier timeout");}catch(InterruptedException interrupted){throw new IllegalStateException(interrupted);}
                    return original;
                });}catch(java.lang.reflect.InvocationTargetException failure){throw new IllegalStateException(failure.getCause());}
            });
            assertTrue(hydrated.await(10,java.util.concurrent.TimeUnit.SECONDS));store.getPrivacyLedger().excludeSource("message",id,hash,"retrieval_exclude","owner in-flight exclusion");excluded.countDown();
            try{result.get(10,java.util.concurrent.TimeUnit.SECONDS);fail("In-flight old bytes must not be returned");}catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause().getCause().getMessage().contains("privacy_changed"));}
            assertEquals("",store.getSetting("review-test-progress",""));assertEquals(0,store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        }finally{excluded.countDown();executor.shutdownNow();}
    }
    @Test public void oldDatabaseRestoreCannotResurrectForgottenReviewPayload()throws Exception{
        long id=source("FORGOTTEN-RESTORE-4812");JSONObject backup=store.exportSnapshot();exclude(id);store.importSnapshot(backup);
        JSONObject batch=review.prepareBatch();forbidden(batch,"FORGOTTEN-RESTORE-4812");assertEquals(0,batch.getJSONArray("sourceRanges").length());assertFalse(review.hasPending());
        long later=source("allowed after restore");batch=review.prepareBatch();assertEquals(later,batch.getJSONArray("sourceRanges").getJSONObject(0).getLong("messageId"));assertTrue(review.acknowledge(batch,ack(batch),true,1));
    }
    @Test public void excludedDeletedOriginalPayloadIsSkippedWithoutTryingToHydrateIt()throws Exception{
        long id=source("FORGOTTEN-RAW-FILE-1274 ".repeat(12000));JSONObject row=store.getMessage(id);assertFalse(row.getString("raw_file_id").isEmpty());exclude(id);
        assertTrue(new File(new File(context.getFilesDir(),"originals"),row.getString("raw_file_id")).delete());JSONObject batch=review.prepareBatch();
        assertEquals(0,batch.getJSONArray("sourceRanges").length());assertEquals(0,batch.getJSONArray("blockedSources").length());assertFalse(review.hasPending());assertEquals(0,review.status().getLong("modelAcknowledgedSourceRangeCount"));
    }
    private static void delete(File file)throws Exception{if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Unreadable fixture");for(File child:children)delete(child);}if(!file.delete())throw new java.io.IOException("Fixture cleanup failed");}
}
