package com.hoyoung.assistant.memory;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.SourcePrivacyLedger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.function.LongSupplier;
import static org.junit.Assert.*;

/** A real bounded SQLite result is not discarded when its SQL operation exceeds the start budget. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AdaptiveCompletedQueryTest {
    private Context context;private HoyoungStore store;
    @Before public void setup()throws Exception{context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());store=new HoyoungStore(context);}
    @After public void cleanup()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());}

    @Test public void completedFirstQueryIsConsumedButLateSecondQueryNeverStarts()throws Exception{
        String alias=token("Alias"),project=token("Studio"),person=token("Person"),destination=token("Destination");
        long last=owner("“"+person+"” destination is “"+destination+"”.");
        long target=owner("“"+project+"”의 담당자는 “"+person+"”야.");
        long cue=owner("“"+alias+"”는 “"+project+"”의 별칭이야.");
        // Four readings: establish deadline, hydrate the only initial cue, admit
        // followup preparation, and admit its actual SQL operation. Thereafter
        // the clock is beyond the deadline; the SQL and raw reads remain real.
        JSONObject result=search(alias,new ExpiringClock(4)),adaptive=result.getJSONObject("diagnostics").getJSONObject("adaptiveRecall");
        assertTrue(result.toString(),hasSource(result,cue));assertTrue(result.toString(),hasSource(result,target));
        assertFalse("The next hop requires a second SQL query after the deadline",hasSource(result,last));
        assertEquals(1,adaptive.getInt("queriesExecuted"));assertTrue(adaptive.getBoolean("limitReached"));
        assertTrue(adaptive.getInt("hydratedSources")<=20);assertTrue(adaptive.getInt("queriesExecuted")<=3);
        assertEquals(store.getMessage(target).getString("content_hash"),source(result,target).getString("hash"));
        assertFalse(adaptive.getBoolean("completeRelationshipGraph"));assertFalse(adaptive.getBoolean("instructionsChanged"));
    }

    @Test public void deadlineExpiredDuringHintPreparationPreventsEvenTheFirstSqlQuery()throws Exception{
        String alias=token("Alias"),project=token("Project"),value=token("Private");
        long target=owner("“"+project+"” assigned reference is “"+value+"”.");
        owner("“"+alias+"”는 “"+project+"”의 별칭이야.");
        JSONObject result=search(alias,new ExpiringClock(3)),adaptive=result.getJSONObject("diagnostics").getJSONObject("adaptiveRecall");
        assertEquals(0,adaptive.getInt("queriesExecuted"));assertFalse(hasSource(result,target));assertFalse(result.toString().contains(value));assertTrue(adaptive.getBoolean("limitReached"));
    }

    @Test public void completedLargeBatchStaysBoundedAfterTheDeadline()throws Exception{
        String alias=token("Alias"),project=token("Project");long latest=0;
        for(int i=0;i<45;i++)latest=owner("“"+project+"” assigned reference is “"+token("Reference")+"”.");
        owner("“"+alias+"”는 “"+project+"”의 별칭이야.");
        JSONObject result=search(alias,new ExpiringClock(4)),adaptive=result.getJSONObject("diagnostics").getJSONObject("adaptiveRecall");
        assertTrue(result.toString(),hasSource(result,latest));assertEquals(1,adaptive.getInt("queriesExecuted"));
        assertTrue(adaptive.toString(),adaptive.getInt("hydratedSources")<=20);assertTrue(adaptive.getBoolean("limitReached"));assertFalse(adaptive.getBoolean("completeRelationshipGraph"));
        assertTrue(result.getJSONArray("originalEvidence").length()<=40);assertEquals(0,store.memoryCount());
    }

    @Test public void excludedParentCueCannotDriveACompletedRelationshipQuery()throws Exception{
        String alias=token("Alias"),project=token("Project"),value=token("Hidden");
        JSONObject parent=store.addMessage("user",token("ExcludedParent"),null);
        long target=owner("“"+project+"” assigned reference is “"+value+"”.");
        long cue=owner("“"+alias+"”는 “"+project+"”의 별칭이야.");
        store.putSetting("answerSources:"+cue,object("version",1,"sources",new JSONArray().put(object("type","message","id",parent.getLong("id"),"hash",parent.getString("content_hash")))).toString());
        store.getPrivacyLedger().excludeSource("message",parent.getLong("id"),parent.getString("content_hash"),SourcePrivacyLedger.RETRIEVAL_EXCLUDE,"explicit test owner exclusion");
        JSONObject result=search(alias,new ExpiringClock(4));assertFalse(result.toString(),hasSource(result,cue));assertFalse(hasSource(result,target));assertFalse(result.toString().contains(value));
        assertEquals(0,result.getJSONObject("diagnostics").getJSONObject("adaptiveRecall").getInt("queriesExecuted"));assertNotNull(store.getMessage(cue));
    }

    private JSONObject search(String alias,LongSupplier clock)throws Exception{MemoryRetriever retriever=new MemoryRetriever(store,null,store.getPrivacyLedger(),clock);retriever.setSemanticEnabled(false);return retriever.search(object("query","“"+alias+"” 관련 기록","limit",20,"maxChars",12000));}
    private long owner(String text)throws Exception{return store.addMessage("user",text,null).getLong("id");}
    private static JSONObject source(JSONObject result,long id)throws Exception{JSONArray rows=result.getJSONArray("originalEvidence");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getLong("id")==id)return rows.getJSONObject(i);return null;}
    private static boolean hasSource(JSONObject result,long id)throws Exception{return source(result,id)!=null;}
    private static String token(String prefix){return prefix+"-"+UUID.randomUUID().toString().substring(0,8);}
    private static JSONObject object(Object...pairs)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static void clear(File file)throws Exception{if(Files.isSymbolicLink(file.toPath())){Files.deleteIfExists(file.toPath());return;}if(!file.exists())return;if(file.isDirectory()){File[]children=file.listFiles();if(children==null)throw new java.io.IOException("Cannot inspect fixture");for(File child:children)clear(child);}Files.delete(file.toPath());}
    private static final class ExpiringClock implements LongSupplier{private final int allowedReadings;private int readings;ExpiringClock(int allowedReadings){this.allowedReadings=allowedReadings;}public long getAsLong(){return ++readings<=allowedReadings?0:351_000_000L;}}
}
