package com.hoyoung.assistant.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.hoyoung.assistant.memory.MemoryQuery;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Actual keyset enumeration of a stated date/literal scope, separate from ranked top-k search. */
public final class MemorySourceEnumerator {
    private final HoyoungStore store;
    private final Predicate<JSONObject> deviceVisibility;
    public MemorySourceEnumerator(HoyoungStore store,Predicate<JSONObject> visibility) {
        this.store=store;deviceVisibility=visibility==null?row->false:visibility;
    }
    public JSONObject enumerate(JSONObject args)throws IOException {
        for(java.util.Iterator<String> it=args.keys();it.hasNext();) {
            String k=it.next();if(!java.util.Arrays.asList("type","fromInclusive","toExclusive","containsLiteral","afterId","cutoffId","expectedScopeFingerprint","limit","maxExcerptChars","maxChars").contains(k))throw new IllegalArgumentException("Unknown enumeration argument");
        }
        String type=args.optString("type","message");boolean message="message".equals(type);
        if(!message&&!"device_event".equals(type))throw new IllegalArgumentException("Unknown source type");
        long from=MemoryQuery.date(args.opt("fromInclusive")),to=MemoryQuery.date(args.opt("toExclusive"));
        if(from>0&&to>0&&from>=to)throw new IllegalArgumentException("Invalid source date range");
        long after=integer(args,"afterId",0,Long.MAX_VALUE,0),requestedCutoff=integer(args,"cutoffId",0,Long.MAX_VALUE,0);
        int limit=(int)integer(args,"limit",1,100,50),excerpt=(int)integer(args,"maxExcerptChars",1,4000,2000),budget=(int)integer(args,"maxChars",4096,100000,48000);
        String literal=args.optString("containsLiteral","");if(literal.length()>200)throw new IllegalArgumentException("Literal exceeds 200 characters");
        SourcePrivacyLedger.Snapshot privacy=store.getPrivacyLedger().capture();
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()) {
            SQLiteDatabase db=snapshot.database;String table=message?"messages":"device_events",date=message?"created_at":"observed_at",body=message?"text":"payload";
            long last=scalar(db,"SELECT COALESCE(MAX(id),0) FROM "+table,new String[0]);long cutoff=requestedCutoff==0?last:Math.min(last,requestedCutoff);
            StringBuilder where=new StringBuilder("id<=?");List<String> scopeArgs=new ArrayList<>();scopeArgs.add(String.valueOf(cutoff));
            if(from>0){where.append(" AND ").append(date).append(">=?");scopeArgs.add(String.valueOf(from));}
            if(to>0){where.append(" AND ").append(date).append("<?");scopeArgs.add(String.valueOf(to));}
            long longOriginals=message?scalar(db,"SELECT COUNT(*) FROM "+table+" WHERE "+where+" AND raw_file_id IS NOT NULL AND raw_file_id<>''",scopeArgs.toArray(new String[0])):0;
            if(!literal.isEmpty()){where.append(" AND instr(lower(").append(body).append("),lower(?))>0");scopeArgs.add(literal);}
            long canonicalMatches=scalar(db,"SELECT COUNT(*) FROM "+table+" WHERE "+where,scopeArgs.toArray(new String[0]));
            String cutoffHash="";try(Cursor c=db.rawQuery("SELECT content_hash FROM "+table+" WHERE id=?",new String[]{String.valueOf(cutoff)})){if(c.moveToFirst())cutoffHash=c.getString(0);}
            JSONObject scope=object("type",type,"fromInclusive",from,"toExclusive",to,"containsLiteral",literal,"cutoffId",cutoff);
            String scopeFingerprint=scopeHash(type+"|"+from+"|"+to+"|"+literal.length()+":"+literal+"|"+cutoff+"|"+privacy.fingerprint+"|"+canonicalMatches+"|"+cutoffHash);
            if(args.has("expectedScopeFingerprint")&&!scopeFingerprint.equals(args.optString("expectedScopeFingerprint")))throw new IOException("Enumeration scope changed between pages");
            List<String> pageArgs=new ArrayList<>(scopeArgs);pageArgs.add(String.valueOf(after));pageArgs.add(String.valueOf(limit+1));
            JSONArray sources=new JSONArray();long next=after;int scanned=0,excluded=0;boolean hasMore=false,partialSource=false;
            try(Cursor rows=db.rawQuery("SELECT * FROM "+table+" WHERE "+where+" AND id>? ORDER BY id LIMIT ?",pageArgs.toArray(new String[0]))) {
                while(rows.moveToNext()) {
                    if(scanned==limit){hasMore=true;break;}
                    JSONObject row=record(rows);long id=row.optLong("id");
                    if(!SourceRecordVisibility.allowed(db,privacy,type,row)||!message&&!deviceVisibility.test(row)){next=id;scanned++;excluded++;continue;}
                    if(!SourceRecordVisibility.canonicalRecordMatches(type,row))throw new IOException("Canonical source integrity mismatch");
                    JSONObject source;
                    if(message){source=store.readOriginalText(snapshot,id,0,excerpt);put(source,"role",row.optString("role"));put(source,"createdAt",row.optLong(date));put(source,"hash",row.optString("content_hash"));}
                    else {String raw=row.optString("_payloadText");int end=Math.min(excerpt,raw.length());if(end>0&&end<raw.length()&&Character.isHighSurrogate(raw.charAt(end-1))&&Character.isLowSurrogate(raw.charAt(end)))end--;source=object("id",id,"hash",row.optString("content_hash"),"observedAt",row.optLong(date),"category",row.optString("category"),"source",row.optString("source"),"text",raw.substring(0,end),"complete",raw.length()<=end,"totalChars",raw.length());}
                    put(source,"type",type);put(source,"id",id);put(source,"sourceId",type+":"+id);
                    if(sources.toString().length()+source.toString().length()+2500>budget) {
                        if(sources.length()==0)throw new IOException("Enumeration budget cannot hold one source");
                        hasMore=true;break;
                    }
                    sources.put(source);next=id;scanned++;partialSource|=!source.optBoolean("complete",false);
                }
            }
            if(!privacy.fingerprint.equals(store.getPrivacyLedger().capture().fingerprint))throw new IOException("Privacy controls changed during source enumeration");
            boolean spilledScopeUnknown=!literal.isEmpty()&&longOriginals>0;
            return object("ok",true,"sources",sources,"scope",scope,"scopeFingerprint",scopeFingerprint,
                    "canonicalMatchingRows",canonicalMatches,"scannedRowsThisPage",scanned,"excludedRowsThisPage",excluded,"privacyFingerprint",privacy.fingerprint,
                    "nextAfterId",next,"hasMore",hasMore,"completeToCutoff",!hasMore&&!spilledScopeUnknown,
                    "completionMeaning","Only when all pages from afterId=0 carry the same scopeFingerprint/cutoff and are read; each source's complete flag is separate",
                    "fullSourceTextsRead",!partialSource,"literalMatchingScope",message?"persisted_SQL_excerpt":"canonical_payload",
                    "longOriginalsInDateScope",longOriginals,"literalFullTextCoverageUnverified",spilledScopeUnknown,
                    "semanticRelevanceCompletenessGuaranteed",false,"modelReadVerified",false);
        }
    }
    private static JSONObject record(Cursor rows){JSONObject r=new JSONObject();for(int i=0;i<rows.getColumnCount();i++)put(r,rows.getColumnName(i),rows.isNull(i)?JSONObject.NULL:rows.getType(i)==Cursor.FIELD_TYPE_INTEGER?rows.getLong(i):rows.getType(i)==Cursor.FIELD_TYPE_FLOAT?rows.getDouble(i):rows.getString(i));if(r.has("payload"))try{put(r,"_payloadText",r.optString("payload"));put(r,"payload",new JSONObject(r.optString("payload")));}catch(org.json.JSONException bad){ }return r;}
    private static long scalar(SQLiteDatabase db,String sql,String[] args){try(Cursor c=db.rawQuery(sql,args)){return c.moveToFirst()?c.getLong(0):0;}}
    private static String scopeHash(String value){try{java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");for(int i=0;i<value.length();i++){char c=value.charAt(i);hash.update((byte)(c>>>8));hash.update((byte)c);}StringBuilder out=new StringBuilder(64);for(byte b:hash.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static long integer(JSONObject args,String key,long min,long max,long fallback){if(!args.has(key))return fallback;Object n=args.opt(key);if(!(n instanceof Number)||((Number)n).doubleValue()!=((Number)n).longValue())throw new IllegalArgumentException("Invalid "+key);long value=((Number)n).longValue();if(value<min||value>max)throw new IllegalArgumentException("Invalid "+key);return value;}
    private static JSONObject object(Object...kv){JSONObject r=new JSONObject();for(int i=0;i<kv.length;i+=2)put(r,(String)kv[i],kv[i+1]);return r;}
    private static void put(JSONObject r,String key,Object value){try{r.put(key,value);}catch(org.json.JSONException bad){throw new IllegalStateException(bad);}}
}
