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
import java.io.File;
import static org.junit.Assert.*;

/** Same read-snapshot FTS candidates are followed by canonical source verification. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryGatewayIndexedSourceTest {
    private Context context;private HoyoungStore store;private MemoryGateway gateway;
    @Before public void setup()throws Exception {context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());store=new HoyoungStore(context);gateway=new MemoryGateway(store);}
    @After public void cleanup()throws Exception {store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());}
    @Test public void uncachedOldRawTailIsFoundAmongTwentyThousandGenericRowsWithoutHybridHelp()throws Exception {
        String original="ordinary archive discussion ".repeat(5000)+" cobalt dossier rare decision ORIGINAL-TAIL-8062";
        JSONObject target=store.addMessage("user",original,null);
        store.withTransaction(db->{for(int i=0;i<20000;i++)store.addMessage("user","ordinary archive dossier discussion generic row "+i,null);return null;});
        long started=System.nanoTime();MemoryGateway.Prepared prepared=gateway.prepare("indexed-raw","root-indexed","user",0,"cobalt dossier decision",null,7000);
        long elapsed=(System.nanoTime()-started)/1_000_000;
        assertTrue(prepared.context.contains("ORIGINAL-TAIL-8062"));assertTrue(prepared.context.contains("\"recordedAt\":"+target.getLong("created_at")));assertTrue(prepared.context.contains("\"complete\":true"));
        JSONObject scope=verbatim(prepared).getJSONObject("candidateScope");
        assertEquals("snapshot_fts_ids_plus_canonical_hydration",scope.getString("backend"));
        assertTrue(scope.getBoolean("candidateLimitReached"));assertTrue(scope.getInt("candidateIds")<=544);
        assertFalse(scope.getBoolean("completeHistoryEnumerated"));
        boolean exactOldRange=false;JSONArray deps=prepared.dependencies();for(int i=0;i<deps.length();i++){JSONObject ref=deps.getJSONObject(i);if("message".equals(ref.optString("type"))&&ref.optLong("id")==target.getLong("id")&&ref.optLong("offsetChars")>0)exactOldRange=true;}
        assertTrue(exactOldRange);gateway.assertFresh(prepared);
        System.out.println("Indexed canonical raw-tail fixture: messages=20001 candidateIds="+scope.getInt("candidateIds")+" elapsedMs="+elapsed);
    }
    @Test public void specificOwnerEntityBeatsCanonicallyValidButCrossEntityHybridNoise()throws Exception {
        JSONObject pending=store.addMessage("user","cobalt reservation was pending OWNER-PENDING-8342",null);
        JSONObject confirmed=store.appendDeviceEvent("booking","phone",new JSONObject().put("text","cobalt reservation was confirmed DEVICE-CONFIRMED-9476").put("seats",2),100,null);
        JSONArray hybrids=new JSONArray();for(int i=0;i<26;i++){JSONObject unrelated=store.addMessage("user","differententity"+i+" reservation pending OTHER-ENTITY-"+i+" "+"generic text ".repeat(50),null);hybrids.put(ref("message",unrelated));}
        hybrids.put(ref("device_event",confirmed));hybrids.put(ref("message",pending));gateway.setDeviceSourceVisibility(row->true);
        MemoryGateway.Prepared prepared=gateway.prepare("indexed-mixed","root-indexed","user",0,"cobalt reservation status",new JSONObject().put("originalEvidence",hybrids),5000);
        assertTrue(prepared.context.contains("OWNER-PENDING-8342"));assertTrue(prepared.context.contains("DEVICE-CONFIRMED-9476"));
        assertTrue(prepared.context.indexOf("OWNER-PENDING-8342")<prepared.context.indexOf("OTHER-ENTITY-0")||!prepared.context.contains("OTHER-ENTITY-0"));gateway.assertFresh(prepared);
    }
    @Test public void completeSmallEntityRangeFitsCompactSourcesDespiteUnrelatedHybridRanks()throws Exception {
        JSONArray wanted=new JSONArray(),noise=new JSONArray();for(int i=0;i<14;i++)wanted.put(store.addMessage("user","cobalt period expense on day "+i+" was AMOUNT-"+(7000+i),null));
        for(int i=0;i<20;i++){JSONObject other=store.addMessage("user","unrelatedentity"+i+" period expense AMOUNT-NOISE-"+i,null);noise.put(ref("message",other));}
        for(int i=0;i<14;i++)noise.put(ref("message",wanted.getJSONObject(i)));
        MemoryGateway.Prepared prepared=gateway.prepare("indexed-range","root-indexed","user",0,"cobalt period expense",new JSONObject().put("originalEvidence",noise),12000);
        for(int i=0;i<14;i++)assertTrue("Missing day "+i,prepared.context.contains("AMOUNT-"+(7000+i)));
        assertFalse(verbatim(prepared).getJSONObject("candidateScope").getBoolean("completeHistoryEnumerated"));gateway.assertFresh(prepared);
    }
    @Test public void literalSubstringFallbackAndOwnerExclusionStayCanonical()throws Exception {
        JSONObject source=store.addMessage("user","prequartzpost literal fact LITERAL-9753",null);
        MemoryGateway.Prepared prepared=gateway.prepare("indexed-literal","root-indexed","user",0,"quartz",null,7000);
        assertTrue(prepared.context.contains("LITERAL-9753"));gateway.assertFresh(prepared);
        store.getPrivacyLedger().excludeSource("message",source.getLong("id"),source.getString("content_hash"),"retrieval_exclude","owner excluded selected original");
        prepared=gateway.prepare("indexed-literal-next","root-indexed","user",0,"quartz",null,7000);
        assertFalse(prepared.context.contains("LITERAL-9753"));assertEquals(5,prepared.receipts().length());gateway.assertFresh(prepared);
    }
    @Test public void transitiveDeviceCopyNeedsCurrentGrantAndFreshReadScope()throws Exception {
        JSONObject question=store.addMessage("user","allowed user question","device-question");store.putSetting("requestSource:device-question",String.valueOf(question.getLong("id")));store.putSetting("requestSourceHash:device-question",question.getString("content_hash"));
        JSONObject event=store.appendDeviceEvent("notification","phone",new JSONObject().put("text","DEVICE-ANCESTOR-8264"),100,null);
        JSONObject answer=store.addMessage("assistant","DEVICE-ANCESTOR-8264 known native response copy","device-question");
        store.putSetting("answerSources:"+answer.getLong("id"),new JSONObject().put("version",1).put("sources",new JSONArray().put(ref("device_event",event))).toString());
        java.util.concurrent.atomic.AtomicBoolean grant=new java.util.concurrent.atomic.AtomicBoolean(true);gateway.setDeviceSourceVisibility(row->grant.get());
        MemoryGateway.Prepared first=gateway.prepare("device-granted","device-granted","user",0,"hello",null,10000);assertTrue(first.context.contains("known native response copy"));
        assertTrue(first.diagnostics().getJSONObject("sourceGraphRead").getBoolean("immutableReadSnapshot"));assertFalse(first.diagnostics().getJSONObject("sourceGraphRead").getBoolean("persistentCache"));
        grant.set(false);assertThrows(MemoryGateway.Blocked.class,()->gateway.assertFreshOutput(first));
        MemoryGateway.Prepared revoked=gateway.prepare("device-revoked","device-revoked","user",0,"hello",null,10000);
        assertFalse(revoked.context.contains("known native response copy"));assertTrue(revoked.context.contains("allowed user question"));gateway.assertFresh(revoked);
    }
    @Test public void disabledImplicitPhoneScopeAlsoExcludesDeviceDerivedMemoriesAndCopies()throws Exception {
        gateway.setDeviceSourceVisibility(row->true);JSONObject event=store.appendDeviceEvent("notification","phone",new JSONObject().put("text","PHONE-SCOPE-PRIVATE-8452"),100,null);
        JSONObject copy=store.addMessage("assistant","PHONE-SCOPE-PRIVATE-8452 copied observation",null);
        store.putSetting("answerSources:"+copy.getLong("id"),new JSONObject().put("version",1).put("sources",new JSONArray().put(ref("device_event",event))).toString());
        store.upsertMemoryWithSources("phone-private","PHONE-SCOPE-PRIVATE-8452 structured copied observation","observation",new JSONArray().put(ref("device_event",event)));
        store.addMessage("user","ordinary independent owner message",null);store.putSetting("toolPermissions",new JSONObject().put("phone_context_read",false).toString());
        MemoryGateway.Prepared prepared=gateway.prepare("phone-scope-disabled","phone-scope-disabled","user",0,"hello",null,10000);
        assertFalse(prepared.context.contains("PHONE-SCOPE-PRIVATE-8452"));assertTrue(prepared.context.contains("ordinary independent owner message"));
        assertEquals(5,prepared.receipts().length());gateway.assertFresh(prepared);
    }
    private static JSONObject ref(String type,JSONObject source)throws Exception{return new JSONObject().put("type",type).put("id",source.getLong("id")).put("hash",source.getString("content_hash"));}
    private static JSONObject verbatim(MemoryGateway.Prepared prepared)throws Exception{JSONArray reads=prepared.receipts();for(int i=0;i<reads.length();i++)if("verbatim".equals(reads.getJSONObject(i).getString("layer")))return reads.getJSONObject(i);throw new AssertionError("Missing actual read receipt");}
    private static void clear(File file)throws Exception {if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Cannot inspect fixture");for(File child:children)clear(child);}if(!file.delete())throw new java.io.IOException("Cannot remove fixture");}
}
