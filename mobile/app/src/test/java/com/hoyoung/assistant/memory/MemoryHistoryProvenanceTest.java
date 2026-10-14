package com.hoyoung.assistant.memory;

import android.app.Application;
import android.content.Context;
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
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MemoryHistoryProvenanceTest {
    private Context context;
    private HoyoungStore store;
    @Before public void before()throws Exception {
        context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());
        assertTrue(context.getFilesDir().mkdirs()||context.getFilesDir().isDirectory());store=new HoyoungStore(context);
    }
    @After public void after()throws Exception {store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());}

    @Test public void historicalOwnerNoteRetainsItsOwnCanonicalCitationAndProvenance()throws Exception {
        JSONObject owner=store.addMessage("user","My home used to be Busan.",null);
        JSONObject assistant=store.addMessage("assistant","An unverified later suggestion: Seoul.",null);
        store.upsertMemory("owner.home","Busan","fact",new JSONArray().put(owner.getLong("id")));
        store.putMemoryMetadata("owner.home",new JSONObject().put("factKey","owner.home").put("validFrom",100).put("validTo",200));
        store.upsertMemory("owner.home","Seoul","generated_summary",new JSONArray().put(assistant.getLong("id")));
        store.putMemoryMetadata("owner.home",new JSONObject().put("factKey","owner.home").put("validFrom",200));

        JSONObject result=new MemoryRetriever(store).search(new JSONObject().put("query","owner.home").put("asOf",150));
        assertEquals(1,result.getJSONArray("history").length());
        JSONObject history=result.getJSONArray("history").getJSONObject(0);
        assertEquals(1,history.getInt("revision"));
        assertEquals("valid_at_requested_time",history.getString("temporalStatus"));
        assertEquals("derived_note_with_owner_sources_not_independently_verified",history.getString("epistemicStatus"));
        assertEquals(1,history.getInt("sourceCount"));
        JSONObject ref=history.getJSONArray("sourceRefs").getJSONObject(0);
        assertEquals("message",ref.getString("type"));assertEquals(owner.getLong("id"),ref.getLong("id"));
        assertEquals("user",ref.getString("role"));assertEquals(owner.getString("content_hash"),ref.getString("hash"));
        assertEquals(owner.getLong("created_at"),ref.getLong("observedAt"));
        assertEquals(owner.getLong("id"),history.getJSONArray("sourceMessageIds").getLong(0));
        assertEquals("assistant_generated_not_verified_fact",result.getJSONArray("memories").getJSONObject(0).getString("epistemicStatus"));

        store.getPrivacyLedger().excludeSource("message",owner.getLong("id"),owner.getString("content_hash"),"retrieval_exclude","");
        assertEquals(0,new MemoryRetriever(store).search(new JSONObject().put("query","owner.home").put("asOf",150)).getJSONArray("history").length());
    }

    private static void delete(File file)throws Exception {
        if(!file.exists())return;
        if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Unreadable fixture");for(File child:children)delete(child);}
        if(!file.delete())throw new java.io.IOException("Fixture cleanup failed");
    }
}
