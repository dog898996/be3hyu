package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemorySourceEnumeratorTest {
    private Context context; private HoyoungStore store; private MemorySourceEnumerator enumerate;
    @Before public void before() throws Exception {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clearPolicy();
        store=new HoyoungStore(context);enumerate=new MemorySourceEnumerator(store,row->true);
    }
    @After public void after() throws Exception {if(store!=null)store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clearPolicy();}
    @Test public void fourteenSourcesRequireFourStablePagesAndLaterAppendCannotExpandFrozenCutoff() throws Exception {
        List<Long> expected=new ArrayList<>();for(int i=1;i<=14;i++)expected.add(store.addMessage("user","독서회 자료정리 단계"+i,null).getLong("id"));
        JSONObject page=enumerate.enumerate(object("containsLiteral","독서회 자료정리","limit",4));
        long cutoff=page.getJSONObject("scope").getLong("cutoffId");String fingerprint=page.getString("scopeFingerprint");
        JSONObject future=store.addMessage("user","독서회 자료정리 나중에 추가",null);assertTrue(future.getLong("id")>cutoff);
        List<Long> actual=new ArrayList<>();int pages=0;
        while(true){pages++;assertEquals(14,page.getLong("canonicalMatchingRows"));assertEquals(cutoff,page.getJSONObject("scope").getLong("cutoffId"));assertEquals(fingerprint,page.getString("scopeFingerprint"));append(actual,page.getJSONArray("sources"));
            if(!page.getBoolean("hasMore")){assertTrue(page.getBoolean("completeToCutoff"));break;}
            assertFalse(page.getBoolean("completeToCutoff"));page=enumerate.enumerate(object("containsLiteral","독서회 자료정리","limit",4,"afterId",page.getLong("nextAfterId"),"cutoffId",cutoff,"expectedScopeFingerprint",fingerprint));
        }
        assertEquals(4,pages);assertEquals(expected,actual);assertFalse(actual.contains(future.getLong("id")));
        assertFalse(page.getBoolean("modelReadVerified"));assertFalse(page.getBoolean("semanticRelevanceCompletenessGuaranteed"));
    }
    @Test public void exactCanonicalLiteralDateCountAndExcludedRowsAdvanceCursorWithoutLeak() throws Exception {
        long t=1_700_000_000_000L;
        store.appendDeviceEvent("notification","fixture",object("text","Needle before"),t-1,null);
        JSONObject first=store.appendDeviceEvent("notification","fixture",object("text","Needle hidden"),t,null);
        JSONObject second=store.appendDeviceEvent("notification","fixture",object("text","NEEDLE allowed"),t+1,null);
        JSONObject third=store.appendDeviceEvent("notification","fixture",object("text","Needle hidden again"),t+2,null);
        JSONObject fourth=store.appendDeviceEvent("notification","fixture",object("text","needle last"),t+3,null);
        store.appendDeviceEvent("notification","fixture",object("text","irrelevant"),t+4,null);
        store.appendDeviceEvent("notification","fixture",object("text","Needle after"),t+10,null);
        exclude("device_event",first);exclude("device_event",third);
        JSONObject p=enumerate.enumerate(object("type","device_event","containsLiteral","needle","fromInclusive",t,"toExclusive",t+10,"limit",2));
        assertEquals(4,p.getLong("canonicalMatchingRows"));assertEquals(2,p.getInt("scannedRowsThisPage"));assertEquals(1,p.getInt("excludedRowsThisPage"));
        assertEquals(second.getLong("id"),p.getLong("nextAfterId"));assertTrue(p.getBoolean("hasMore"));assertEquals(1,p.getJSONArray("sources").length());assertEquals(second.getLong("id"),p.getJSONArray("sources").getJSONObject(0).getLong("id"));
        JSONObject q=enumerate.enumerate(object("type","device_event","containsLiteral","needle","fromInclusive",t,"toExclusive",t+10,"limit",2,"afterId",p.getLong("nextAfterId"),"cutoffId",p.getJSONObject("scope").getLong("cutoffId"),"expectedScopeFingerprint",p.getString("scopeFingerprint")));
        assertEquals(4,q.getLong("canonicalMatchingRows"));assertEquals(1,q.getInt("excludedRowsThisPage"));assertEquals(fourth.getLong("id"),q.getLong("nextAfterId"));assertFalse(q.getBoolean("hasMore"));assertTrue(q.getBoolean("completeToCutoff"));assertEquals(fourth.getLong("id"),q.getJSONArray("sources").getJSONObject(0).getLong("id"));
    }
    @Test public void privacyChangeBetweenPagesRejectsFrozenScopeRatherThanClaimingCompleteness() throws Exception {
        JSONObject first=store.addMessage("user","공방 자료 첫째",null);store.addMessage("user","공방 자료 둘째",null);
        JSONObject page=enumerate.enumerate(object("containsLiteral","공방 자료","limit",1));assertTrue(page.getBoolean("hasMore"));exclude("message",first);
        IOException blocked=assertThrows(IOException.class,()->enumerate.enumerate(object("containsLiteral","공방 자료","limit",1,"afterId",page.getLong("nextAfterId"),"cutoffId",page.getJSONObject("scope").getLong("cutoffId"),"expectedScopeFingerprint",page.getString("scopeFingerprint"))));
        assertTrue(blocked.getMessage().contains("scope changed"));
    }
    @Test public void spilledOriginalLiteralCoverageAndPerSourceTextCompletenessRemainSeparate() throws Exception {
        String tail="RARE-ONLY-IN-SPILLED-TAIL";String raw="앞부분 "+"가".repeat(210_000)+tail;
        JSONObject original=store.addMessage("user",raw,null);assertFalse(original.isNull("raw_file_id"));
        JSONObject literal=enumerate.enumerate(object("containsLiteral",tail,"limit",5));assertEquals(0,literal.getLong("canonicalMatchingRows"));assertEquals(0,literal.getJSONArray("sources").length());
        assertEquals(1,literal.getLong("longOriginalsInDateScope"));assertTrue(literal.getBoolean("literalFullTextCoverageUnverified"));assertFalse(literal.getBoolean("completeToCutoff"));assertFalse(literal.getBoolean("hasMore"));
        JSONObject all=enumerate.enumerate(object("limit",5,"maxExcerptChars",80));assertTrue(all.getBoolean("completeToCutoff"));assertFalse(all.getBoolean("fullSourceTextsRead"));assertFalse(all.getBoolean("literalFullTextCoverageUnverified"));
        JSONObject source=all.getJSONArray("sources").getJSONObject(0);assertFalse(source.getBoolean("complete"));assertEquals(raw.length(),source.getLong("totalChars"));assertEquals(raw.substring(0,80),source.getString("text"));assertEquals(original.getString("raw_content_hash"),source.getString("contentHash"));
    }
    @Test public void characterBudgetDefersUnconsumedRowAndAllIdsSurvivePagination() throws Exception {
        List<Long> expected=new ArrayList<>();for(int i=0;i<5;i++)expected.add(store.addMessage("user","budget-row-"+i+":"+"x".repeat(840),null).getLong("id"));
        JSONObject page=enumerate.enumerate(object("limit",100,"maxExcerptChars",1000,"maxChars",4096));List<Long> actual=new ArrayList<>();int pages=0;long previous=0;
        while(true){pages++;assertTrue(page.toString().length()<=4096);JSONArray sources=page.getJSONArray("sources");assertTrue(sources.length()>0);append(actual,sources);assertTrue(page.getLong("nextAfterId")>previous);previous=page.getLong("nextAfterId");
            if(!page.getBoolean("hasMore"))break;page=enumerate.enumerate(object("limit",100,"maxExcerptChars",1000,"maxChars",4096,"afterId",previous,"cutoffId",page.getJSONObject("scope").getLong("cutoffId"),"expectedScopeFingerprint",page.getString("scopeFingerprint")));
        }
        assertTrue(pages>1);assertEquals(expected,actual);assertTrue(page.getBoolean("completeToCutoff"));
    }
    private void exclude(String type,JSONObject s) throws Exception {store.getPrivacyLedger().excludeSource(type,s.getLong("id"),s.getString("content_hash"),"retrieval_exclude","explicit synthetic owner control");}
    private void clearPolicy() throws IOException {for(String f:new String[]{"privacy-ledger.json","privacy-ledger.anchor.json",".privacy-ledger.lock"})Files.deleteIfExists(new java.io.File(context.getFilesDir(),f).toPath());}
    private static void append(List<Long> target,JSONArray sources) throws Exception {for(int i=0;i<sources.length();i++)target.add(sources.getJSONObject(i).getLong("id"));}
    private static JSONObject object(Object...p) throws Exception {JSONObject o=new JSONObject();for(int i=0;i<p.length;i+=2)o.put(String.valueOf(p[i]),p[i+1]);return o;}
}
