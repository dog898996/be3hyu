package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import static org.junit.Assert.*;

/** A small source corpus with a mature portable exclusion policy; no model or benchmark. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryGatewayPrivacyScaleTest {
    private Context context;
    private HoyoungStore store;

    @Before public void before()throws Exception {
        context=ApplicationProvider.getApplicationContext();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());
        assertTrue(context.getFilesDir().mkdirs()||context.getFilesDir().isDirectory());
        store=new HoyoungStore(context);
    }

    @After public void after()throws Exception {
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());
    }

    @Test public void thousandsOfExclusionsPreserveExactGenerationsAndDerivedSourceVisibility()throws Exception {
        JSONObject old=store.addMessage("user","old excluded generation",null);
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);
        JSONObject fresh=store.addMessage("user","cobalt FRESH-GENERATION-4638",null);
        assertEquals(old.getLong("id"),fresh.getLong("id"));
        assertNotEquals(old.getString("content_hash"),fresh.getString("content_hash"));
        JSONObject excluded=store.addMessage("user","cobalt EXCLUDED-MESSAGE-9247",null);
        store.upsertMemory("allowed-cobalt","cobalt ALLOWED-NOTE-5872","fact",new JSONArray().put(fresh.getLong("id")));
        store.upsertMemory("excluded-cobalt","cobalt EXCLUDED-NOTE-1275","fact",new JSONArray().put(excluded.getLong("id")));
        JSONObject payload=new JSONObject().put("text","cobalt EXCLUDED-DEVICE-7491");
        JSONObject device=store.appendDeviceEvent("notification","scale-fixture",payload,100,null);
        JSONObject replay=store.appendDeviceEvent("notification","scale-fixture",payload,100,null);
        assertNotEquals(device.getLong("id"),replay.getLong("id"));
        assertEquals(device.getString("content_hash"),replay.getString("content_hash"));
        JSONObject allowedDevice=store.appendDeviceEvent("notification","scale-fixture",new JSONObject().put("text","cobalt ALLOWED-DEVICE-2846"),101,null);
        store.upsertMemoryWithSources("excluded-device-cobalt","cobalt EXCLUDED-DEVICE-NOTE-8625","observation",
                new JSONArray().put(new JSONObject().put("type","device_event").put("id",replay.getLong("id"))));

        JSONArray exclusions=new JSONArray();
        exclusions.put(exclusion("message",old.getLong("id"),old.getString("content_hash")));
        exclusions.put(exclusion("message",excluded.getLong("id"),excluded.getString("content_hash")));
        exclusions.put(exclusion("device_event",device.getLong("id"),device.getString("content_hash")));
        // Portable policy may refer to sources absent from this restored database generation.
        for(int i=0;i<1100;i++){
            exclusions.put(exclusion("message",10_000+i,String.format(Locale.ROOT,"%064x",i+1)));
            exclusions.put(exclusion("device_event",10_000+i,String.format(Locale.ROOT,"%064x",i+1)));
        }
        JSONObject imported=new JSONObject().put("format",SourcePrivacyLedger.FORMAT).put("version",exclusions.length()).put("exclusions",exclusions);
        imported.put("fingerprint",OriginalTextFiles.hex(OriginalTextFiles.digest().digest(imported.toString().getBytes(StandardCharsets.UTF_8))));
        SourcePrivacyLedger policy=store.getPrivacyLedger();policy.mergeSnapshot(imported);
        String fingerprint=policy.fingerprint();

        MemoryGateway gateway=new MemoryGateway(store);gateway.setDeviceSourceVisibility(row->true);
        JSONArray evidence=new JSONArray().put(candidate("device_event",replay)).put(candidate("device_event",allowedDevice));
        MemoryGateway.Prepared prepared=gateway.prepare("privacy-scale","privacy-root","user",0,"cobalt",
                new JSONObject().put("originalEvidence",evidence),80_000,1_000_000);
        assertEquals(5,prepared.receipts().length());
        for(String marker:new String[]{"FRESH-GENERATION-4638","ALLOWED-NOTE-5872","ALLOWED-DEVICE-2846"})assertTrue(marker,prepared.context.contains(marker));
        for(String marker:new String[]{"EXCLUDED-MESSAGE-9247","EXCLUDED-NOTE-1275","EXCLUDED-DEVICE-7491","EXCLUDED-DEVICE-NOTE-8625"})assertFalse(marker,prepared.context.contains(marker));
        gateway.assertFresh(prepared,1_000_001);
        assertEquals(exclusions.length(),policy.capture().exclusionCount);
        assertEquals(fingerprint,policy.fingerprint());
        assertEquals(excluded.getString("text"),store.getMessage(excluded.getLong("id")).getString("text"));

        policy.excludeSource("message",fresh.getLong("id"),fresh.getString("content_hash"),SourcePrivacyLedger.RETRIEVAL_EXCLUDE,"new owner control");
        MemoryGateway.Blocked changed=assertThrows(MemoryGateway.Blocked.class,()->gateway.assertFresh(prepared,1_000_001));
        assertEquals("memory_privacy_changed",changed.reason);
    }

    private static JSONObject exclusion(String type,long id,String hash)throws Exception {
        return new JSONObject().put("type",type).put("id",id).put("hash",hash).put("mode",SourcePrivacyLedger.RETRIEVAL_EXCLUDE).put("reason","").put("excludedAt",0);
    }
    private static JSONObject candidate(String type,JSONObject source)throws Exception {
        return new JSONObject().put("type",type).put("id",source.getLong("id")).put("hash",source.getString("content_hash"));
    }
    private static void delete(File file)throws Exception {
        if(!file.exists())return;
        if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Unreadable fixture");for(File child:children)delete(child);}
        if(!file.delete())throw new java.io.IOException("Fixture cleanup failed");
    }
}
