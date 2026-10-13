package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class FullHistorySearchTest {
    private Context context; private HoyoungStore store;
    @Before public void setUp(){context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);}
    @After public void tearDown(){store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}

    @Test public void compoundIdentifierDoesNotExpandIntoEveryDateDigitAndWhitespaceOrEvidenceSurvives() throws Exception {
        long rare=store.addMessage("user","RARE-OLD-FACT-0-K831 exact source",null).getLong("id");
        long city=store.addMessage("user","Project-Zeta-0 location Osaka",null).getLong("id");
        long partner=store.addMessage("user","Mira is the partner",null).getLong("id");
        store.withTransaction(db->{for(int i=0;i<10_000;i++)store.addMessage("user","2025-01-01 baseline 0 routine report index"+i,null);return null;});
        JSONArray unique=store.rankedOriginalCandidates("RARE-OLD-FACT-0-K831",0,0,10);
        assertEquals(1,unique.length()); assertEquals(rare,unique.getJSONObject(0).getLong("id"));
        JSONArray distributed=store.rankedOriginalCandidates("Project-Zeta-0 Mira",0,0,10);
        assertTrue(contains(distributed,city)); assertTrue(contains(distributed,partner));
        assertEquals(rare,store.queryOriginals("RARE-OLD-FACT-0-K831",0,0,0,10).getJSONObject(0).getLong("id"));
    }

    @Test public void tenThousandDeviceEventsKeepOldRareEvidenceAndDateFiltersWithoutPayloadTableScan() throws Exception {
        long old=store.appendDeviceEvent("notification","synthetic.app",new JSONObject().put("text","DEVICE-RARE-OLD-0-X924"),10,"rare-old").getLong("id");
        store.withTransaction(db->{for(int i=0;i<10_000;i++)store.appendDeviceEvent("activity_sample","synthetic.sensor",object("text","2025-01-01 routine 0 sample"+i),20+i,"sample-"+i);return null;});
        long start=System.nanoTime();JSONArray found=store.rankedDeviceCandidates("DEVICE-RARE-OLD-0-X924",0,0,10);long elapsed=(System.nanoTime()-start)/1_000_000;
        assertEquals(1,found.length());assertEquals(old,found.getJSONObject(0).getLong("id"));
        assertEquals(0,store.rankedDeviceCandidates("DEVICE-RARE-OLD-0-X924",11,0,10).length());
        assertEquals(old,store.rankedDeviceCandidates("DEVICE-RARE-OLD-0-X924",1,11,10).getJSONObject(0).getLong("id"));
        assertTrue(store.deviceSearchIndexStatus().getBoolean("complete"));
        try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM device_event_search",null)){assertTrue(cursor.moveToFirst());assertEquals(10_001,cursor.getLong(0));}
        System.out.println("HOYOUNG_DEVICE_FTS_PROBE events=10001 rareResult=1 elapsedMillis="+elapsed+" realDeviceLatencyMeasured=false");
        JSONObject snapshot=store.exportSnapshot();store.importSnapshot(snapshot);
        assertEquals(old,store.rankedDeviceCandidates("DEVICE-RARE-OLD-0-X924",0,0,10).getJSONObject(0).getLong("id"));
        assertTrue(store.verifyIntegrity().getBoolean("ok"));
    }

    @Test public void incompleteDerivedIndexNeverHidesUnindexedHistoricalEvidenceAndRebuildIsResumable() throws Exception {
        long old=store.appendDeviceEvent("notification","synthetic.app",object("text","OLD-UNINDEXED-SOURCE-712"),10,"old").optLong("id");
        long newer=store.appendDeviceEvent("notification","synthetic.app",object("text","newer indexed event"),20,"newer").optLong("id");
        store.withTransaction(db->{db.delete("device_event_search",null,null);db.execSQL("UPDATE device_search_state SET indexed_through=0,target_id=?,complete=0",new Object[]{newer});return null;});
        assertFalse(store.deviceSearchIndexStatus().getBoolean("complete"));
        assertEquals(old,store.rankedDeviceCandidates("OLD-UNINDEXED-SOURCE-712",0,0,10).getJSONObject(0).getLong("id"));
        assertFalse(store.rebuildDeviceEventSearchBatch(1).getBoolean("complete"));
        assertEquals(old,store.rankedDeviceCandidates("OLD-UNINDEXED-SOURCE-712",0,0,10).getJSONObject(0).getLong("id"));
        assertTrue(store.rebuildDeviceEventSearchBatch(1).getBoolean("complete"));
        assertEquals(old,store.rankedDeviceCandidates("OLD-UNINDEXED-SOURCE-712",0,0,10).getJSONObject(0).getLong("id"));
        assertEquals("OLD-UNINDEXED-SOURCE-712",store.getDeviceEvent(old).getJSONObject("payload").getString("text"));
    }

    @Test public void oldVersionTwoWithoutDeviceFtsReopensAdditivelyAndPreservesSourceHash() throws Exception {
        JSONObject source=store.appendDeviceEvent("notification","synthetic.app",object("text","V2-LEGACY-EVIDENCE-832"),10,"legacy");
        SQLiteDatabase db=store.getWritableDatabase();
        for(String trigger:new String[]{"device_fts_before_delete","device_fts_before_update","device_fts_after_insert","device_fts_after_update"})db.execSQL("DROP TRIGGER "+trigger);
        db.execSQL("DROP TABLE device_event_search");db.execSQL("DROP TABLE device_search_state");store.close();
        store=new HoyoungStore(context);
        assertEquals(2,store.getReadableDatabase().getVersion());
        assertEquals(source.getString("content_hash"),store.getDeviceEvent(source.getLong("id")).getString("content_hash"));
        assertEquals(source.getLong("id"),store.rankedDeviceCandidates("V2-LEGACY-EVIDENCE-832",0,0,10).getJSONObject(0).getLong("id"));
        store.rebuildSearchIndexes();assertTrue(store.deviceSearchIndexStatus().getBoolean("complete"));
    }
    private static boolean contains(JSONArray rows,long id){for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i).optLong("id")==id)return true;return false;}
    private static JSONObject object(String key,String text){JSONObject out=new JSONObject();try{out.put(key,text);}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}return out;}
}
