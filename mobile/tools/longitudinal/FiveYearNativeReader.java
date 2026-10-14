package com.hoyoung.assistant.memory;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Independent source tools over an exact native benchmark snapshot. Never reads gold/scoring files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class FiveYearNativeReader {
    @Test public void replayBoundedModelRequestedTools() throws Exception {
        Path run=Path.of(required("HOYOUNG_READER_RUN")),requests=Path.of(required("HOYOUNG_READER_REQUESTS"));
        Path output=Path.of(required("HOYOUNG_READER_OUTPUT"));
        replay(run,requests,output,Path.of(required("HOYOUNG_READER_BUDGET")));
    }
    public void replay(Path run,Path requests,Path output,Path budgetPath) throws Exception {
        JSONObject controls=new JSONObject(Files.readString(run.resolve("native-reader-control-snapshot.json")));
        Set<String> primaryIds=new HashSet<>();for(String name:new String[]{"reader-inputs.jsonl","checkpoint-2026-12-31-reader-inputs.partial.jsonl"}){Path packets=run.resolve(name);if(!Files.exists(packets))continue;for(String packet:Files.readAllLines(packets)){if(!packet.isBlank()){JSONObject p=new JSONObject(packet);if("2026-12-31".equals(p.optString("date")))primaryIds.add(p.getString("id"));}}}
        Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        File database=context.getDatabasePath(HoyoungStore.DATABASE_NAME);database.getParentFile().mkdirs();
        Files.copy(run.resolve("canonical-corpus.sqlite"),database.toPath(),StandardCopyOption.REPLACE_EXISTING);
        Path privateFiles=run.resolve("native-reader-files");
        if(Files.exists(privateFiles))try(java.util.stream.Stream<Path> paths=Files.walk(privateFiles)){
            for(Path path:(Iterable<Path>)paths::iterator){Path destination=context.getFilesDir().toPath().resolve(privateFiles.relativize(path));if(Files.isDirectory(path))Files.createDirectories(destination);else{Files.createDirectories(destination.getParent());Files.copy(path,destination,StandardCopyOption.REPLACE_EXISTING);}}
        }
        new InternalFiles(context);HoyoungStore store=new HoyoungStore(context);
        Set<Long> hidden=new HashSet<>();JSONArray ids=controls.getJSONArray("excludedDeviceIds");for(int i=0;i<ids.length();i++)hidden.add(ids.getLong(i));
        java.util.function.Predicate<JSONObject> visibility=row->!hidden.contains(row.optLong("id"));
        MemoryRetriever retriever=new MemoryRetriever(store);retriever.setDeviceSourceVisibility(visibility);
        Object ledger=null;
        if(controls.optBoolean("gatewayEnabled")){
            ledger=store.getClass().getMethod("getPrivacyLedger").invoke(store);
            if(!controls.getString("privacyFingerprint").equals(ledger.getClass().getMethod("fingerprint").invoke(ledger)))throw new IllegalStateException("Reader privacy snapshot mismatch");
        }
        JSONObject prior=Files.exists(budgetPath)?new JSONObject(Files.readString(budgetPath)):new JSONObject();
        Map<String,Integer> searches=counts(prior.optJSONObject("searches")),sourceReads=counts(prior.optJSONObject("sourceRows"));StringBuilder answers=new StringBuilder();
        try {
            for(String line:Files.readAllLines(requests)){
                if(line.isBlank())continue;JSONObject request=new JSONObject(line);String id=request.getString("id"),op=request.getString("op");JSONObject args=request.getJSONObject("args");JSONObject response=new JSONObject();response.put("id",id);response.put("op",op);
                try {
                    if(!primaryIds.contains(id))throw new IllegalArgumentException("Unknown final primary packet ID");
                    JSONObject result;
                    if("search".equals(op)){
                        if(searches.merge(id,1,Integer::sum)>3)throw new IllegalArgumentException("Maximum three source searches per packet");
                        int remaining=20-sourceReads.getOrDefault(id,0);if(remaining<2)throw new IllegalArgumentException("Source search needs two remaining rows; use direct read for a final single row");
                        JSONObject query=new JSONObject(args.toString());long horizon=java.time.Instant.parse("2027-01-01T00:00:00Z").toEpochMilli();query.put("asOf",fencedDate(query.opt("asOf"),horizon));query.put("toExclusive",fencedDate(query.opt("toExclusive"),horizon));query.put("limit",Math.max(1,Math.min(remaining/2,query.optInt("limit",10))));query.put("maxChars",12000);
                        result=retriever.search(query);JSONArray nativeRows=result.optJSONArray("originalEvidence");consume(sourceReads,id,nativeRows==null?0:nativeRows.length());
                    }else if("read".equals(op)){
                        consume(sourceReads,id,1);String type=args.optString("type","message");long nativeId=args.getLong("sourceId");JSONObject row="message".equals(type)?store.getMessage(nativeId):"device_event".equals(type)?store.getDeviceEvent(nativeId):null;
                        if(row==null)throw new IllegalArgumentException("Unknown native source");
                        if(args.has("expectedHash")&&!args.getString("expectedHash").equals(row.optString("content_hash")))throw new IllegalArgumentException("Native source hash changed");
                        if("device_event".equals(type)&&!visibility.test(row))throw new IllegalArgumentException("Source unavailable under device scope");
                        if(ledger!=null){Object snapshot=ledger.getClass().getMethod("capture").invoke(ledger);boolean allowed=(Boolean)retriever.getClass().getMethod("allowsSourceRecord",String.class,JSONObject.class,snapshot.getClass()).invoke(retriever,type,row,snapshot);if(!allowed)throw new IllegalArgumentException("Source unavailable under privacy scope");}
                        result="message".equals(type)?store.readOriginalText(nativeId,args.optLong("offsetChars"),Math.min(12000,args.optInt("maxChars",4000))):new JSONObject().put("text",row.getJSONObject("payload").toString()).put("observedAt",row.optLong("observed_at")).put("complete",true);
                        result.put("type",type).put("id",nativeId).put("hash",row.optString("content_hash"));
                        if("message".equals(type))result.put("role",row.optString("role")).put("createdAt",row.optLong("created_at"));
                    }else if("enumerate".equals(op)){
                        if(searches.merge(id,1,Integer::sum)>3)throw new IllegalArgumentException("Maximum three source searches per packet");
                        int remaining=20-sourceReads.getOrDefault(id,0);if(remaining<1)throw new IllegalArgumentException("Maximum twenty source rows per packet");args.put("limit",Math.min(remaining,args.optInt("limit",remaining)));
                        Class<?> enumerator=Class.forName("com.hoyoung.assistant.store.MemorySourceEnumerator");Object tool=enumerator.getConstructor(HoyoungStore.class,java.util.function.Predicate.class).newInstance(store,visibility);result=(JSONObject)enumerator.getMethod("enumerate",JSONObject.class).invoke(tool,args);consume(sourceReads,id,result.getJSONArray("sources").length());
                    }else throw new IllegalArgumentException("Supported operations: search/read/enumerate");
                    if(ledger!=null&&!controls.getString("privacyFingerprint").equals(ledger.getClass().getMethod("fingerprint").invoke(ledger)))throw new IllegalStateException("Reader privacy snapshot changed");
                    response.put("ok",true).put("result",result);
                }catch(Exception failure){Throwable cause=failure instanceof java.lang.reflect.InvocationTargetException?((java.lang.reflect.InvocationTargetException)failure).getCause():failure;response.put("ok",false).put("errorClass",cause.getClass().getSimpleName()).put("message",cause.getMessage()==null?"Native source tool unavailable":cause.getMessage());}
                response.put("sourceSearchesUsed",searches.getOrDefault(id,0)).put("sourceRowsRead",sourceReads.getOrDefault(id,0));answers.append(response).append('\n');
            }
            Files.writeString(output,answers.toString());
            Files.writeString(budgetPath,new JSONObject().put("searches",new JSONObject(searches)).put("sourceRows",new JSONObject(sourceReads)).toString(2));
        }finally{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    }
    private static void consume(Map<String,Integer> counts,String id,int n){int previous=counts.getOrDefault(id,0);if(previous+n>20)throw new IllegalArgumentException("Maximum twenty source rows per packet");counts.put(id,previous+n);}
    private static Map<String,Integer> counts(JSONObject values){Map<String,Integer> out=new HashMap<>();if(values!=null)for(Iterator<String> keys=values.keys();keys.hasNext();){String key=keys.next();out.put(key,values.optInt(key));}return out;}
    private static long fencedDate(Object value,long horizon){if(value==null||value==JSONObject.NULL)return horizon;long requested;if(value instanceof Number)requested=((Number)value).longValue();else{String s=String.valueOf(value);requested=s.matches("\\d{4}-\\d{2}-\\d{2}")?java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli():java.time.Instant.parse(s).toEpochMilli();}return Math.min(horizon,requested);}
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException(key+" required");return value;}
}
