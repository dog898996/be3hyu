package com.hoyoung.assistant.memory;

import android.database.Cursor;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Search the entire revision timeline for an as-of date before limiting returned history. */
final class MemoryHistory {
    private final HoyoungStore store;
    MemoryHistory(HoyoungStore store){this.store=store;}
    JSONObject select(String key,int currentRevision,MemoryQuery query,int limit){
        List<Integer> relevant=new ArrayList<>(),fallback=new ArrayList<>();int scanned=0,eligible=0;
        synchronized(store){
            String sql="SELECT r.revision,(SELECT x.metadata_json FROM memory_metadata_revisions x WHERE x.memory_key=r.memory_key "
                    +"AND x.memory_revision=r.revision ORDER BY x.metadata_revision DESC LIMIT 1) FROM memory_revisions r WHERE r.memory_key=? ORDER BY r.revision DESC";
            try(Cursor c=store.getReadableDatabase().rawQuery(sql,new String[]{key})){
                while(c.moveToNext()){
                    int revision=c.getInt(0);if(revision==currentRevision)continue;scanned++;
                    JSONObject metadata=parse(c.isNull(1)?null:c.getString(1));JSONObject probe=new JSONObject();MemoryPolicy.put(probe,"metadata",metadata==null?JSONObject.NULL:metadata);
                    String temporal=MemoryPolicy.temporal(probe,query.asOf);
                    if(query.asOf>0){
                        if("valid_at_requested_time".equals(temporal)){eligible++;if(relevant.size()<limit)relevant.add(revision);}
                        else if(fallback.size()<2)fallback.add(revision);
                    }else{eligible++;if(relevant.size()<limit)relevant.add(revision);}
                }
            }
            if(query.asOf>0)for(Integer revision:fallback)if(relevant.size()<limit)relevant.add(revision);
            JSONArray rows=new JSONArray();for(int revision:relevant){JSONObject row=read(key,revision);if(row!=null)rows.put(row);}
            JSONObject result=new JSONObject();MemoryPolicy.put(result,"rows",rows);MemoryPolicy.put(result,"scannedRevisions",scanned);MemoryPolicy.put(result,"limitReached",eligible>limit);return result;
        }
    }
    private JSONObject read(String key,int revision){
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT memory_key,revision,substr(content,1,8192),length(content),kind,created_at,source_ids,source_refs "
                +"FROM memory_revisions WHERE memory_key=? AND revision=?",new String[]{key,String.valueOf(revision)})){
            if(!c.moveToFirst())return null;JSONObject row=new JSONObject();MemoryPolicy.put(row,"key",c.getString(0));MemoryPolicy.put(row,"revision",c.getInt(1));MemoryPolicy.put(row,"content",c.getString(2));MemoryPolicy.put(row,"contentChars",c.getInt(3));MemoryPolicy.put(row,"contentExcerpt",c.getInt(3)>8192);MemoryPolicy.put(row,"kind",c.getString(4));MemoryPolicy.put(row,"created_at",c.getLong(5));
            try{MemoryPolicy.put(row,"sourceMessageIds",new JSONArray(c.getString(6)));MemoryPolicy.put(row,"sourceRefs",new JSONArray(c.getString(7)));}catch(org.json.JSONException corrupt){throw new IllegalStateException("Corrupt revision provenance",corrupt);}
            try(Cursor metadata=store.getReadableDatabase().rawQuery("SELECT metadata_json FROM memory_metadata_revisions WHERE memory_key=? AND memory_revision=? ORDER BY metadata_revision DESC LIMIT 1",new String[]{key,String.valueOf(revision)})){MemoryPolicy.put(row,"metadata",metadata.moveToFirst()?parse(metadata.getString(0)):JSONObject.NULL);}
            return row;
        }
    }
    private static JSONObject parse(String json){if(json==null)return null;try{return new JSONObject(json);}catch(org.json.JSONException invalid){throw new IllegalStateException("Corrupt memory metadata",invalid);}}
}
