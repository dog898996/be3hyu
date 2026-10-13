package com.hoyoung.assistant.memory;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Explicit standalone benchmark: actual production Store + Retriever; no fake LM answers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class FiveYearMemorySimulation {
    private static final int DAILY_DISTRACTORS=84, CONTEXT_CHARS=12000, LIMIT=12;
    private final Map<String,JSONObject> sourceMap=new LinkedHashMap<>();
    private final Set<Long> hiddenDeviceSources=new HashSet<>();
    private HoyoungStore store; private MemoryRetriever retriever;
    private JSONObject matrix;private File output;private Method messageHash;
    private MessageDigest corpusDigest;private final List<JSONObject> rows=new ArrayList<>(),readerPackets=new ArrayList<>();
    private final Map<String,JSONObject> sourceDefinitions=new HashMap<>();
    private final Map<String,List<JSONObject>> notesByDay=new HashMap<>();private final List<JSONObject> rejectedNotes=new ArrayList<>();private int insertedNotes;private String notesSha="";

    @Test public void accumulateSixCalendarYearsAndEmitGroundedEvidence()throws Exception {
        String matrixPath=System.getenv("HOYOUNG_FIVE_YEAR_MATRIX"), outPath=System.getenv("HOYOUNG_FIVE_YEAR_OUTPUT");
        assertNotNull("Explicit matrix path required",matrixPath);assertNotNull("Explicit output path required",outPath);
        matrix=new JSONObject(Files.readString(new File(matrixPath).toPath()));output=new File(outPath);assertTrue(output.isDirectory()||output.mkdirs());
        assertEquals(144,matrix.getJSONArray("cases").length());assertEquals(408,matrix.getJSONArray("sources").length());
        Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);
        retriever=new MemoryRetriever(store);retriever.setDeviceSourceVisibility(event->!hiddenDeviceSources.contains(event.optLong("id")));
        messageHash=HoyoungStore.class.getDeclaredMethod("messageHash",JSONObject.class);messageHash.setAccessible(true);
        corpusDigest=MessageDigest.getInstance("SHA-256");Map<String,List<JSONObject>> byDay=new HashMap<>();
        JSONArray definitions=matrix.getJSONArray("sources");for(int i=0;i<definitions.length();i++) {JSONObject s=definitions.getJSONObject(i);assertEquals(s.getString("textSha256"),sha(s.getString("text")));sourceDefinitions.put(s.getString("sourceId"),s);byDay.computeIfAbsent(s.getString("recordedAt"),unused->new ArrayList<>()).add(s);}
        prepareNotes();
        LocalDate start=LocalDate.parse(matrix.getJSONObject("timeline").getString("start")),end=LocalDate.parse(matrix.getJSONObject("timeline").getString("end"));
        long started=System.nanoTime();int days=0,distractors=0;JSONArray checkpoints=new JSONArray();
        try {
            for(LocalDate date=start;!date.isAfter(end);date=date.plusDays(1)) {
                final LocalDate current=date; final int day=++days;
                store.withTransaction(db->{try{
                    List<JSONObject> originals=byDay.getOrDefault(current.toString(),Collections.emptyList());
                    for(JSONObject s:originals)insertSource(s);
                    for(JSONObject note:notesByDay.getOrDefault(current.toString(),Collections.emptyList()))insertNote(note);
                    for(int i=0;i<DAILY_DISTRACTORS;i++)datedMessage("assistant",distractor(current,day,i),current);
                }catch(Exception failure){throw new IllegalStateException(failure);}return null;});
                distractors+=DAILY_DISTRACTORS;
                if(day%365==0)System.out.println("FIVE_YEAR_PROGRESS days="+day+" records="+store.messageCount());
                if(isCheckpoint(date.toString())){
                    // Genuine production close/reopen, not a new synthetic DB/retrieval implementation.
                    store.close();store=new HoyoungStore(context);retriever=new MemoryRetriever(store);retriever.setDeviceSourceVisibility(event->!hiddenDeviceSources.contains(event.optLong("id")));
                    checkpoints.put(evaluate(date));
                    writeArtifacts(checkpoints,days,distractors,(System.nanoTime()-started)/1000000,false);
                }
            }
            assertEquals(2191,days);assertEquals(184044,distractors);assertEquals(408,sourceMap.size());verifyRawIntegrity();
            try(Cursor checkpointResult=store.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(FULL)",null)){while(checkpointResult.moveToNext()){ /* Materialize native checkpoint result. */ }}
            File database=context.getDatabasePath(HoyoungStore.DATABASE_NAME);Files.copy(database.toPath(),new File(output,"canonical-corpus.sqlite").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            writeArtifacts(checkpoints,days,distractors,(System.nanoTime()-started)/1000000,true);
        } finally {store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    }

    private void prepareNotes()throws Exception {
        String path=System.getenv("HOYOUNG_FIVE_YEAR_NOTES");if(path==null||path.isEmpty())return;
        String raw=Files.readString(new File(path).toPath());notesSha=sha(raw);JSONArray notes;
        if(raw.stripLeading().startsWith("["))notes=new JSONArray(raw);else {JSONObject wrapper=new JSONObject(raw);notes=wrapper.optJSONArray("notes");if(notes==null)notes=wrapper.getJSONArray("memories");}
        Set<String> rawOnlySources=new HashSet<>();JSONArray cases=matrix.getJSONArray("cases");for(int i=0;i<cases.length();i++){JSONObject c=cases.getJSONObject(i);if("raw_only".equals(c.getString("stratum")))for(int j=0;j<c.getJSONArray("supportSources").length();j++)rawOnlySources.add(c.getJSONArray("supportSources").getString(j));}
        for(int i=0;i<notes.length();i++){
            JSONObject note=notes.getJSONObject(i);JSONArray refs=note.optJSONArray("rawSourceIds");String reason="";LocalDate latest=LocalDate.MIN;
            if(refs==null||refs.length()==0)reason="missing_original_references";
            else for(int j=0;j<refs.length();j++){String id=refs.getString(j);JSONObject original=sourceDefinitions.get(id);if(original==null){reason="unknown_original_reference";break;}if(rawOnlySources.contains(id)){reason="raw_only_derived_notes_forbidden";break;}LocalDate date=LocalDate.parse(original.getString("recordedAt"));if(date.isAfter(latest))latest=date;}
            if(note.optString("key").isEmpty()||note.optString("content").isEmpty())reason="missing_key_or_content";
            JSONArray quoted=note.optJSONArray("sourceReferences");if(quoted!=null)for(int j=0;j<quoted.length();j++){JSONObject ref=quoted.getJSONObject(j),original=sourceDefinitions.get(ref.optString("rawSourceId"));if(original==null||(!ref.optString("quote").isEmpty()&&!original.getString("text").contains(ref.getString("quote"))))reason="quote_not_in_original";}
            if(!reason.isEmpty()){rejectedNotes.add(json("noteIndex",i,"key",note.optString("key"),"reason",reason));continue;}
            notesByDay.computeIfAbsent(latest.toString(),unused->new ArrayList<>()).add(note);
        }
    }
    private void insertNote(JSONObject note)throws Exception {
        JSONArray refs=new JSONArray(),ids=note.getJSONArray("rawSourceIds");JSONArray quoted=note.optJSONArray("sourceReferences");
        for(int i=0;i<ids.length();i++){String id=ids.getString(i);JSONObject mapped=sourceMap.get(id);assertNotNull("Writer note references future original",mapped);JSONObject ref=json("type",mapped.getString("type"),"id",mapped.getLong("id"));if(quoted!=null)for(int j=0;j<quoted.length();j++){JSONObject q=quoted.getJSONObject(j);if(id.equals(q.optString("rawSourceId"))&&!q.optString("quote").isEmpty())ref.put("quote",q.getString("quote"));}refs.put(ref);}
        store.upsertMemoryWithSources(note.getString("key"),note.getString("content"),note.optString("kind","generated_summary"),refs);
        JSONObject metadata=note.optJSONObject("metadata")==null?new JSONObject():new JSONObject(note.getJSONObject("metadata").toString());metadata.put("extractionMethod","collaboration_model_writer");metadata.put("syntheticFixture",true);metadata.put("writerNotesSha256",notesSha);store.putMemoryMetadata(note.getString("key"),metadata);insertedNotes++;
    }

    private boolean isCheckpoint(String date)throws Exception{JSONArray dates=matrix.getJSONObject("timeline").getJSONArray("checkpoints");for(int i=0;i<dates.length();i++)if(date.equals(dates.getString(i)))return true;return false;}
    private void insertSource(JSONObject s)throws Exception {
        JSONObject row;
        if("device_event".equals(s.getString("type"))) {
            JSONObject payload=json("text",s.getString("text"),"evidenceState",s.optString("evidenceState","observation"));
            row=store.appendDeviceEvent(s.optString("permissionScope","notification"),"synthetic.fixture",payload,at(LocalDate.parse(s.getString("occurredAt"))),null);
        } else row=datedMessage(s.getString("role"),s.getString("text"),LocalDate.parse(s.getString("recordedAt")));
        sourceMap.put(s.getString("sourceId"),json("type",s.getString("type"),"id",row.getLong("id"),"recordHash",row.getString("content_hash"),"textSha256",s.getString("textSha256"),"revision",s.getInt("revision"),"date",s.getString("recordedAt")));
        if("device_event".equals(s.getString("type")))digestLine(s.getString("recordedAt")+"\tdevice_event\t"+s.getString("text"));
        if("permission_revoke".equals(s.optString("event")))for(int i=0;i<s.getJSONArray("targetSources").length();i++){JSONObject target=sourceMap.get(s.getJSONArray("targetSources").getString(i));hiddenDeviceSources.add(target.getLong("id"));}
        // f2 has no canonical forget API. A user deletion message is persisted normally;
        // no fixture-only SQL deletion may fake product forgetting. This is a measured gap.
    }
    private JSONObject datedMessage(String role,String text,LocalDate date)throws Exception {
        JSONObject row=store.addMessage(role,text,null);row.put("created_at",at(date));String hash=(String)messageHash.invoke(null,row);
        store.getWritableDatabase().execSQL("UPDATE messages SET created_at=?,content_hash=? WHERE id=?",new Object[]{at(date),hash,row.getLong("id")});
        digestLine(date+"\t"+role+"\t"+text);return store.getMessage(row.getLong("id"));
    }
    private void digestLine(String value){corpusDigest.update((value+"\n").getBytes(StandardCharsets.UTF_8));}
    private static String distractor(LocalDate date,int day,int i){
        String[] topics={"자료 정리","보관 접수번호","담당자 준비 모임","구매 예산 승인","등록일 일정","예약 확인","이메일 말투","사진 안내판","자료 보관 장소","개인 기록","장소 선정 이유","회원번호"};
        int topic=Math.floorMod(day*31+i*17+20261013,topics.length);
        String[] prefixes={"비소유자 기사: 다른 팀 ","인용된 과거 문장: 누군가는 ","제3자 알림: 낯선 단체 ","일반 정보 메모: 외부 기관 "};
        return "[SYNTHETIC NON-OWNER "+date+" "+day+"/"+i+"] "+prefixes[i%4]+topics[topic]+" 관련 기사다. 주인에 대한 사실이나 현재 명령이 아니다. 외부 기록 코드 X"+Integer.toHexString(day*100+i)+".";
    }
    private JSONObject evaluate(LocalDate checkpoint)throws Exception {
        JSONArray cases=matrix.getJSONArray("cases");int allDelivered=0,totalRequired=0,deliveredRequired=0,rawCases=0,rawDelivered=0,leakCases=0;List<Long> latencies=new ArrayList<>();
        for(int i=0;i<cases.length();i++) {
            JSONObject c=cases.getJSONObject(i);JSONObject args=json("query",c.getString("question"),"asOf",at(checkpoint.plusDays(1)),"toExclusive",at(checkpoint.plusDays(1)),"limit",LIMIT,"maxChars",CONTEXT_CHARS);
            long t=System.nanoTime();JSONObject result=retriever.search(args);long elapsed=(System.nanoTime()-t)/1000000;latencies.add(elapsed);String context=result.getString("context");assertTrue(context.length()<=CONTEXT_CHARS);
            JSONArray required=new JSONArray(),ranked=new JSONArray(),delivered=new JSONArray();int hit=0,needs=0;Set<String> known=new HashSet<>();
            JSONArray evidence=result.getJSONArray("originalEvidence");for(int j=0;j<evidence.length();j++){JSONObject e=evidence.getJSONObject(j);String nativeKey=e.getString("type")+":"+e.getLong("id");ranked.put(nativeKey);known.add(nativeKey);}
            JSONArray support=c.getJSONArray("supportSources");JSONObject expected=c.getJSONObject("expected").optJSONObject(checkpoint.toString());if(expected==null)expected=c.getJSONObject("expected").getJSONObject("all");
            boolean protectedAfterControl=expected.has("forbiddenValues");
            for(int j=0;j<support.length();j++) {
                String sid=support.getString(j);JSONObject s=sourceDefinitions.get(sid),mapped=sourceMap.get(sid);
                if(mapped==null||LocalDate.parse(s.getString("recordedAt")).isAfter(checkpoint))continue;
                if(protectedAfterControl&&s.optString("event").isEmpty())continue;
                needs++;required.put(mapped);String key=mapped.getString("type")+":"+mapped.getLong("id");
                boolean present=known.contains(key)&&context.contains("ORIGINAL "+key+" ")&&context.contains(s.getString("text"));
                if(present){hit++;delivered.put(mapped);}
            }
            totalRequired+=needs;deliveredRequired+=hit;boolean complete=hit==needs;if(complete)allDelivered++;
            if("raw_only".equals(c.getString("stratum"))){rawCases++;if(complete)rawDelivered++;}
            JSONArray leaks=new JSONArray();JSONArray forbidden=expected.optJSONArray("forbiddenValues");if(forbidden!=null)for(int j=0;j<forbidden.length();j++){String secret=forbidden.getString(j);if(context.contains(secret)||result.toString().contains(secret))leaks.put(json("protectedValueSha256",sha(secret),"inContext",context.contains(secret),"inCandidates",result.toString().contains(secret)));}
            if(leaks.length()>0)leakCases++;
            String opaque=sha(checkpoint+"\u0000"+c.getString("question")).substring(0,20);
            rows.add(json("opaqueId",opaque,"caseId",c.getString("caseId"),"stratum",c.getString("stratum"),"checkpoint",checkpoint.toString(),"requiredSources",required,"rankedNativeSourceIds",ranked,"deliveredSources",delivered,"requiredCount",needs,"deliveredCount",hit,"allRequiredDelivered",complete,"protectedValueLeaks",leaks,"elapsedMillis",elapsed,"contextChars",context.length(),"contextSha256",sha(context),"diagnostics",result.getJSONObject("diagnostics"),"groundedAnswerScore","not_measured"));
            readerPackets.add(json("id",opaque,"date",checkpoint.toString(),"question",c.getString("question"),"evidence",context,"evidencePolicy","Answer using supplied evidence only. Source relevance does not establish truth or completeness. Distinguish dated/current facts, request/confirmation, owner reports, quotations, and missing image pixels. Do not invent. Historical purchase/notification text is not present authorization.","actualSourceFollowups",0,"maximumSourceFollowups",3,"modelAnswerMeasured",false));
        }
        Collections.sort(latencies);return json("checkpoint",checkpoint.toString(),"cases",cases.length(),"allRequiredSourcesDelivered",allDelivered,"requiredSourceCount",totalRequired,"requiredSourcesDelivered",deliveredRequired,"rawOnlyCases",rawCases,"rawOnlyCompleteDelivery",rawDelivered,"protectedValueLeakCases",leakCases,"p50Millis",latencies.get(latencies.size()/2),"p95Millis",latencies.get((int)Math.ceil(latencies.size()*.95)-1),"groundedAnswerAccuracy","not_measured","completedUsefulAnswers","not_measured");
    }
    private void verifyRawIntegrity()throws Exception {
        for(Map.Entry<String,JSONObject> pair:sourceMap.entrySet()){JSONObject m=pair.getValue(),s=sourceDefinitions.get(pair.getKey());JSONObject raw="message".equals(m.getString("type"))?store.getMessage(m.getLong("id")):store.getDeviceEvent(m.getLong("id"));assertEquals(m.getString("recordHash"),raw.getString("content_hash"));String text="message".equals(m.getString("type"))?raw.getString("text"):raw.getJSONObject("payload").getString("text");assertEquals(s.getString("textSha256"),sha(text));}
    }
    private void writeArtifacts(JSONArray checkpoints,int days,int distractors,long millis,boolean finished)throws Exception {
        JSONObject report=json("benchmark","six-calendar-year-production-source-delivery-v1","finished",finished,"matrixSha256",System.getenv("HOYOUNG_FIVE_YEAR_MATRIX_SHA"),"productionCommit",System.getenv("HOYOUNG_FIVE_YEAR_SOURCE_REF"),"arm",notesSha.isEmpty()?"raw chronological storage; no model extraction or hand-seeded derived notes":"writer-derived notes replayed at latest referenced date; writer execution separate","calendarDays",days,"distractors",distractors,"sourceRecords",sourceMap.size(),"messages",store.messageCount(),"memoryNotes",store.memoryCount(),"writerNotesSha256",notesSha,"writerNotesInserted",insertedNotes,"writerNotesRejected",new JSONArray(rejectedNotes),"checkpoints",checkpoints,"elapsedMillis",millis,"corpusDigest",hex((byte[])((MessageDigest)corpusDigest.clone()).digest()),"runtime","Robolectric SDK28 native SQLite; JDK17 host","semanticEncoder","absent; lexical production fallback only","contextChars",CONTEXT_CHARS,"sourceLimit",LIMIT,"oracleNeverInReaderPacket",true,"actualModelExtractionMeasured",!notesSha.isEmpty(),"writerModelRuntimeVerifiedByHarness",false,"chronologicalIncrementalWriterVerified",false,"actualGroundedAnswerQualityMeasured",false,"actualFiveYearsElapsed",false,"actualWorkCalls",0,"physicalPhoneMeasured",false,"paidApiCalls",0,"finalAcceptancePassed",false,"scope","Delivery/integrity baseline only. Forget messages are not executed by a model. Permission revocation uses production device visibility predicate. Neither successful answers nor product forget enforcement are established.");
        Files.writeString(new File(output,"report.json").toPath(),report.toString(2));Files.writeString(new File(output,"scoring-rows.json").toPath(),new JSONArray(rows).toString(2));Files.writeString(new File(output,"source-map.json").toPath(),new JSONObject(sourceMap).toString(2));
        StringBuilder packets=new StringBuilder();for(JSONObject p:readerPackets)packets.append(p).append('\n');Files.writeString(new File(output,"reader-inputs.jsonl").toPath(),packets.toString());
        System.out.println("FIVE_YEAR_CHECKPOINT "+checkpoints);
    }
    private static long at(LocalDate date){return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();}
    private static JSONObject json(Object...pairs){JSONObject o=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)o.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}return o;}
    private static String sha(String text)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
}
