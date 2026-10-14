package com.hoyoung.assistant.memory;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Disposable source interventions. Reads packets/source requests, never a gold matrix or rubric. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class FiveYearNativeCounterfactual {
    @Test public void mutateOnlyTaggedNativeCopiesAndEmitActualContexts()throws Exception {
        Path run=Path.of(required("HOYOUNG_COUNTERFACTUAL_RUN"));
        Path output=Path.of(required("HOYOUNG_COUNTERFACTUAL_OUTPUT"));
        JSONObject request=new JSONObject(Files.readString(Path.of(required("HOYOUNG_COUNTERFACTUAL_REQUEST"))));
        JSONObject primary=null;
        for(String line:Files.readAllLines(run.resolve("final-reader-inputs.jsonl"))){JSONObject p=new JSONObject(line);if(request.getString("primaryId").equals(p.getString("id")))primary=p;}
        assertNotNull("An actually emitted final primary packet is required",primary);
        JSONObject controls=new JSONObject(Files.readString(run.resolve("native-reader-control-snapshot.json")));
        assertEquals("2026-12-31",primary.getString("date"));
        Context context=ApplicationProvider.getApplicationContext();
        JSONArray variants=request.getJSONArray("variants"),receipts=new JSONArray();StringBuilder packets=new StringBuilder();
        for(int i=0;i<variants.length();i++){
            JSONObject variant=variants.getJSONObject(i);String tag=variant.getString("tag");assertTrue(tag.matches("[a-zA-Z0-9_-]{1,40}"));
            Path destination=output.resolve(tag);assertTrue(Files.isDirectory(destination));
            context.deleteDatabase(HoyoungStore.DATABASE_NAME);
            File database=context.getDatabasePath(HoyoungStore.DATABASE_NAME);database.getParentFile().mkdirs();
            Files.copy(run.resolve("canonical-corpus.sqlite"),database.toPath(),StandardCopyOption.REPLACE_EXISTING);
            copyTree(run.resolve("native-reader-files"),context.getFilesDir().toPath());
            new InternalFiles(context);HoyoungStore store=new HoyoungStore(context);
            try {
                assertEquals("Raw arm required; existing writer notes must not be repaired",0,store.memoryCount());
                long messagesBefore=store.messageCount(),devicesBefore=count(store,"device_events"),ftsBefore=count(store,"message_search");
                JSONObject changed=new JSONObject();JSONObject mutation=variant.optJSONObject("mutation");
                if(mutation!=null){
                    long id=mutation.getLong("sourceId");JSONObject before=store.getMessage(id);assertNotNull(before);
                    assertEquals(mutation.getString("expectedHash"),before.getString("content_hash"));
                    assertTrue("Only inline short originals are supported",before.isNull("raw_file_id")||before.optString("raw_file_id").isEmpty());
                    String old=before.getString("text"),span=mutation.getString("oldSpan"),replacement=mutation.getString("replacementSpan");
                    assertFalse(span.isEmpty());assertFalse(span.equals(replacement));assertEquals(old.indexOf(span),old.lastIndexOf(span));assertTrue(old.contains(span));
                    String text=old.replace(span,replacement);assertTrue(text.length()<2000);
                    JSONObject after=new JSONObject(before.toString());after.put("text",text).put("raw_char_count",text.length()).put("raw_byte_count",text.getBytes(StandardCharsets.UTF_8).length).put("raw_content_hash",sha(text));
                    Method hash=HoyoungStore.class.getDeclaredMethod("messageHash",JSONObject.class);hash.setAccessible(true);String recordHash=(String)hash.invoke(null,after);
                    ContentValues values=new ContentValues();values.put("text",text);values.put("raw_char_count",text.length());values.put("raw_byte_count",text.getBytes(StandardCharsets.UTF_8).length);values.put("raw_content_hash",sha(text));values.put("content_hash",recordHash);
                    assertEquals(1,(int)store.withTransaction(db->db.update("messages",values,"id=?",new String[]{String.valueOf(id)})));
                    JSONObject actual=store.getMessage(id);assertEquals(text,actual.getString("text"));assertEquals(recordHash,actual.getString("content_hash"));assertNotEquals(before.getString("content_hash"),recordHash);
                    assertEquals(text,store.readOriginalText(id,0,4000).getString("text"));
                    try(Cursor fts=store.getReadableDatabase().rawQuery("SELECT text FROM message_search WHERE docid=?",new String[]{String.valueOf(id)})){assertTrue(fts.moveToFirst());assertEquals(text,fts.getString(0));assertFalse(fts.moveToNext());}
                    changed=json("type","message","id",id,"role",before.getString("role"),"recordedAt",before.getLong("created_at"),"oldRecordHash",before.getString("content_hash"),"newRecordHash",recordHash,"oldText",old,"newText",text,"oldSpan",span,"replacementSpan",replacement,"actualProductionHashAndFtsVerified",true);
                }
                assertEquals(messagesBefore,store.messageCount());assertEquals(devicesBefore,count(store,"device_events"));assertEquals(ftsBefore,count(store,"message_search"));assertEquals(0,store.memoryCount());
                Set<Long> hidden=new HashSet<>();JSONArray hiddenIds=controls.getJSONArray("excludedDeviceIds");for(int j=0;j<hiddenIds.length();j++)hidden.add(hiddenIds.getLong(j));
                java.util.function.Predicate<JSONObject> visible=row->!hidden.contains(row.optLong("id"));
                MemoryRetriever retriever=new MemoryRetriever(store);retriever.setDeviceSourceVisibility(visible);
                long now=Instant.parse("2027-01-01T00:00:00Z").toEpochMilli();JSONObject args=json("query",primary.getString("question"),"asOf",now,"toExclusive",now,"limit",12,"maxChars",12000);
                JSONObject result=retriever.search(args);String evidence=result.getString("context");JSONArray dependencies=new JSONArray(),reads=new JSONArray();JSONObject diagnostics=new JSONObject();boolean fresh=false;
                if(controls.optBoolean("gatewayEnabled")){
                    Object ledger=store.getClass().getMethod("getPrivacyLedger").invoke(store);assertEquals(controls.getString("privacyFingerprint"),ledger.getClass().getMethod("fingerprint").invoke(ledger));
                    Class<?> type=Class.forName("com.hoyoung.assistant.store.MemoryGateway");Object gateway=type.getConstructor(HoyoungStore.class,ledger.getClass()).newInstance(store,ledger);type.getMethod("setDeviceSourceVisibility",java.util.function.Predicate.class).invoke(gateway,visible);
                    Object prepared=type.getMethod("prepare",String.class,String.class,String.class,long.class,String.class,JSONObject.class,int.class,long.class).invoke(gateway,"bench-"+primary.getString("id"),"benchmark-owner","normal",0L,primary.getString("question"),result,12000,now);
                    evidence=(String)prepared.getClass().getField("context").get(prepared);dependencies=(JSONArray)prepared.getClass().getMethod("dependencies").invoke(prepared);reads=(JSONArray)prepared.getClass().getMethod("receipts").invoke(prepared);diagnostics=(JSONObject)prepared.getClass().getMethod("diagnostics").invoke(prepared);
                    type.getMethod("assertFresh",prepared.getClass(),long.class,boolean.class).invoke(gateway,prepared,now,false);fresh=true;assertEquals(5,reads.length());
                }
                assertTrue(evidence.length()<=12000);
                String opaque=sha(primary.getString("id")+"\u0000"+tag).substring(0,20);JSONObject packet=new JSONObject(primary.toString()).put("id",opaque).put("evidence",evidence).put("modelAnswerMeasured",false).put("actualSourceFollowups",0);
                String line=packet.toString()+"\n";packets.append(line);Files.writeString(destination.resolve("reader-inputs.jsonl"),line);Files.writeString(destination.resolve("final-reader-inputs.jsonl"),line);
                try(Cursor checkpoint=store.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(FULL)",null)){while(checkpoint.moveToNext()){}}
                Files.copy(database.toPath(),destination.resolve("canonical-corpus.sqlite"),StandardCopyOption.REPLACE_EXISTING);copyTree(run.resolve("native-reader-files"),destination.resolve("native-reader-files"));Files.copy(run.resolve("native-reader-control-snapshot.json"),destination.resolve("native-reader-control-snapshot.json"));
                JSONObject receipt=json("tag",tag,"readerId",opaque,"originalPrimaryId",primary.getString("id"),"scorerOnlyRelation",variant.getString("scorerOnlyRelation"),"sourceChange",changed,"queryArgs",args,"originalEvidence",result.getJSONArray("originalEvidence"),"gatewayDependencies",dependencies,"gatewayReceipts",reads,"gatewayDiagnostics",diagnostics,"freshOutputCheckPassed",fresh,"contextSha256",sha(evidence),"contextChars",evidence.length(),"messageCount",store.messageCount(),"deviceEventCount",devicesBefore,"ftsCount",ftsBefore,"memoryNotes",0,"canonicalCopySha256",fileSha(destination.resolve("canonical-corpus.sqlite")),"emittedEvidenceContainsChangedText",mutation!=null&&evidence.contains(changed.getString("newText")),"originalPrimaryPacketContextSha256",sha(primary.getString("evidence")),"modelAnswerQuality","not_measured");
                Files.writeString(destination.resolve("scorer-only-intervention-receipt.json"),receipt.toString(2));receipts.put(receipt);
            }finally{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
        }
        Files.writeString(output.resolve("reader-inputs.jsonl"),packets.toString());Files.writeString(output.resolve("scorer-only-intervention-receipts.json"),receipts.toString(2));
    }
    private static long count(HoyoungStore store,String table){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM "+table,null)){assertTrue(c.moveToFirst());return c.getLong(0);}}
    private static void copyTree(Path source,Path destination)throws Exception {if(!Files.exists(source))return;try(java.util.stream.Stream<Path> paths=Files.walk(source)){for(Path path:(Iterable<Path>)paths::iterator){Path target=destination.resolve(source.relativize(path));if(Files.isDirectory(path))Files.createDirectories(target);else{Files.createDirectories(target.getParent());Files.copy(path,target,StandardCopyOption.REPLACE_EXISTING);}}}}
    private static JSONObject json(Object...pairs)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put(String.valueOf(pairs[i]),pairs[i+1]);return out;}
    private static String sha(String value)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static String fileSha(Path path)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(java.io.InputStream stream=Files.newInputStream(path)){byte[] bytes=new byte[65536];int n;while((n=stream.read(bytes))>=0)digest.update(bytes,0,n);}return hex(digest.digest());}
    private static String hex(byte[] value){StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException(key+" required");return value;}
}
