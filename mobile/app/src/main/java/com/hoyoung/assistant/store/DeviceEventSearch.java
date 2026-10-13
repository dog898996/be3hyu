package com.hoyoung.assistant.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.json.JSONException;

/** Disposable full-history index. Bootstrap progress never removes the historical query lane. */
final class DeviceEventSearch {
    private DeviceEventSearch() { }
    static void ensure(SQLiteDatabase db) {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS device_event_search USING fts4(payload,category,source,tokenize=unicode61)");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_search_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),indexed_through INTEGER NOT NULL,target_id INTEGER NOT NULL,complete INTEGER NOT NULL)");
        db.execSQL("INSERT OR IGNORE INTO device_search_state(singleton,indexed_through,target_id,complete) SELECT 1,0,COALESCE(MAX(id),0),CASE WHEN COUNT(*)=0 THEN 1 ELSE 0 END FROM device_events");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS device_fts_before_delete BEFORE DELETE ON device_events BEGIN DELETE FROM device_event_search WHERE docid=old.id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS device_fts_before_update BEFORE UPDATE OF payload,category,source ON device_events BEGIN DELETE FROM device_event_search WHERE docid=old.id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS device_fts_after_insert AFTER INSERT ON device_events BEGIN INSERT INTO device_event_search(docid,payload,category,source) VALUES(new.id,new.payload,new.category,new.source); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS device_fts_after_update AFTER UPDATE OF payload,category,source ON device_events BEGIN INSERT INTO device_event_search(docid,payload,category,source) VALUES(new.id,new.payload,new.category,new.source); END");
    }
    static long[] state(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT indexed_through,target_id,complete FROM device_search_state WHERE singleton=1", null)) {
            return cursor.moveToFirst() ? new long[]{cursor.getLong(0),cursor.getLong(1),cursor.getLong(2)} : new long[]{0,0,0};
        }
    }
    static void rebuild(SQLiteDatabase db) {
        db.delete("device_event_search", null, null);
        db.execSQL("INSERT INTO device_event_search(docid,payload,category,source) SELECT id,payload,category,source FROM device_events");
        db.execSQL("UPDATE device_search_state SET indexed_through=(SELECT COALESCE(MAX(id),0) FROM device_events),target_id=(SELECT COALESCE(MAX(id),0) FROM device_events),complete=1 WHERE singleton=1");
    }
    static JSONObject batch(SQLiteDatabase db, int limit) {
        long[] state = state(db);
        if (state[2] == 0) {
            long end = state[0];
            try (Cursor cursor = db.rawQuery("SELECT id FROM device_events WHERE id>? AND id<=? ORDER BY id LIMIT ?",new String[]{String.valueOf(state[0]),String.valueOf(state[1]),String.valueOf(Math.max(1,Math.min(limit,2048)))})) {
                while (cursor.moveToNext()) end = cursor.getLong(0);
            }
            if (end == state[0]) end = state[1];
            db.execSQL("INSERT OR REPLACE INTO device_event_search(docid,payload,category,source) SELECT id,payload,category,source FROM device_events WHERE id>? AND id<=?",new Object[]{state[0],end});
            db.execSQL("UPDATE device_search_state SET indexed_through=?,complete=? WHERE singleton=1",new Object[]{end,end>=state[1]?1:0});
        }
        return status(db, "");
    }
    static JSONObject status(SQLiteDatabase db, String error) {
        long[] state = state(db); JSONObject result = new JSONObject();
        long latest = 0;
        try(Cursor cursor=db.rawQuery("SELECT COALESCE(MAX(id),0) FROM device_events",null)){if(cursor.moveToFirst())latest=cursor.getLong(0);}
        put(result,"indexedThroughId",state[2]!=0?latest:state[0]);put(result,"bootstrapTargetId",state[1]);put(result,"latestEventId",latest);put(result,"complete",state[2]!=0);
        put(result,"historicalFallbackActive",state[2]==0);put(result,"derived",true);
        if (error != null && !error.isEmpty()) put(result,"error",error);
        return result;
    }
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
}
