package com.hoyoung.assistant.memory;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
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
    private final boolean useGateway="1".equals(System.getenv("HOYOUNG_FIVE_YEAR_GATEWAY")), usePrivacyControls="1".equals(System.getenv("HOYOUNG_FIVE_YEAR_PRIVACY_CONTROLS"));
    private Object ledger,gateway,payloadDeletion;private Context applicationContext;private final boolean usePhysicalDeletion="1".equals(System.getenv("HOYOUNG_FIVE_YEAR_PHYSICAL_DELETE"));private final List<JSONObject> pendingPhysicalDeletes=new ArrayList<>();private final Set<String> physicallyDeletedSources=new HashSet<>();private InternalFiles internalFiles;private final Map<String,Object> yearOneCachedPrepared=new HashMap<>();private File yearOneBackup;private final List<JSONObject> controlOperations=new ArrayList<>();
    private JSONObject matrix;private File output;private Method messageHash;
    private MessageDigest corpusDigest;private final List<JSONObject> rows=new ArrayList<>(),readerPackets=new ArrayList<>();
    private final Map<String,JSONObject> sourceDefinitions=new HashMap<>();
    private final Map<String,List<JSONObject>> notesByDay=new HashMap<>();private final List<JSONObject> rejectedNotes=new ArrayList<>();private int insertedNotes;private String notesSha="";

    @Test public void accumulateSixCalendarYearsAndEmitGroundedEvidence()throws Exception {
        String matrixPath=System.getenv("HOYOUNG_FIVE_YEAR_MATRIX"), outPath=System.getenv("HOYOUNG_FIVE_YEAR_OUTPUT");
        assertNotNull("Explicit matrix path required",matrixPath);assertNotNull("Explicit output path required",outPath);
        matrix=new JSONObject(Files.readString(new File(matrixPath).toPath()));output=new File(outPath);assertTrue(output.isDirectory()||output.mkdirs());
        assertEquals(144,matrix.getJSONArray("cases").length());assertEquals(408,matrix.getJSONArray("sources").length());
        Context context=ApplicationProvider.getApplicationContext();applicationContext=context;context.deleteDatabase(HoyoungStore.DATABASE_NAME);internalFiles=new InternalFiles(context);store=new HoyoungStore(context);
        configureProduct();
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
                executePendingPhysicalDeletes();
                distractors+=DAILY_DISTRACTORS;
                if(day%365==0)System.out.println("FIVE_YEAR_PROGRESS days="+day+" records="+store.messageCount());
                if(isCheckpoint(date.toString())){
                    // Genuine production close/reopen, not a new synthetic DB/retrieval implementation.
                    store.close();store=new HoyoungStore(context);configureProduct();
                    if(date.equals(end)) {verifyRawIntegrity();exportCanonicalReaderCorpus();}
                    checkpoints.put(evaluate(date));
                    if(useGateway&&"2021-12-31".equals(date.toString())){
                        yearOneBackup=new File(output,"year-one-original-backup.zip");try(java.io.OutputStream destination=new java.io.FileOutputStream(yearOneBackup)){internalFiles.exportBackup(destination,store);}
                    }
                    writeArtifacts(checkpoints,days,distractors,(System.nanoTime()-started)/1000000,false);
                }
            }
            assertEquals(2191,days);assertEquals(184044,distractors);assertEquals(408,sourceMap.size());verifyRawIntegrity();
            exportCanonicalReaderCorpus();
            writeArtifacts(checkpoints,days,distractors,(System.nanoTime()-started)/1000000,true);
            if(useGateway&&usePrivacyControls)verifyPrivacyCacheAndRestoreReplay(end);
        } finally {store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    }

    private void configureProduct()throws Exception {
        retriever=new MemoryRetriever(store);retriever.setDeviceSourceVisibility(event->!hiddenDeviceSources.contains(event.optLong("id")));
        if(usePrivacyControls||useGateway)ledger=store.getClass().getMethod("getPrivacyLedger").invoke(store);
        if(usePhysicalDeletion){Class<?> type=Class.forName("com.hoyoung.assistant.store.SourcePayloadDeletion");payloadDeletion=type.getConstructor(Context.class,HoyoungStore.class).newInstance(applicationContext,store);}
        if(useGateway){Class<?> type=Class.forName("com.hoyoung.assistant.store.MemoryGateway");gateway=type.getConstructor(HoyoungStore.class,ledger.getClass()).newInstance(store,ledger);gateway.getClass().getMethod("setDeviceSourceVisibility",java.util.function.Predicate.class).invoke(gateway,(java.util.function.Predicate<JSONObject>)event->!hiddenDeviceSources.contains(event.optLong("id")));}
    }
    /** Source-only replay state for independent reader tools. No matrix/scoring/oracle is included. */
    private void exportCanonicalReaderCorpus()throws Exception {
        try(Cursor checkpointResult=store.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(FULL)",null)){while(checkpointResult.moveToNext()){}}
        Files.copy(applicationContext.getDatabasePath(HoyoungStore.DATABASE_NAME).toPath(),new File(output,"canonical-corpus.sqlite").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);exportNativeReaderState();
    }
    private void exportNativeReaderState()throws Exception {
        File directory=new File(output,"native-reader-files");assertTrue(directory.isDirectory()||directory.mkdirs());
        for(String name:new String[]{"privacy-ledger.json","privacy-ledger.anchor.json","originals"}){
            File live=new File(applicationContext.getFilesDir(),name);if(!live.exists())continue;
            try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(live.toPath())){
                for(java.nio.file.Path path:(Iterable<java.nio.file.Path>)paths::iterator){java.nio.file.Path destination=directory.toPath().resolve(applicationContext.getFilesDir().toPath().relativize(path));if(Files.isDirectory(path))Files.createDirectories(destination);else {Files.createDirectories(destination.getParent());Files.copy(path,destination,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}}
            }
        }
        JSONObject controls=json("format","native-reader-replay-v1","date","2026-12-31","gatewayEnabled",useGateway,"excludedDeviceIds",new JSONArray(hiddenDeviceSources),"privacyFingerprint",ledger==null?"":ledger.getClass().getMethod("fingerprint").invoke(ledger),"scope","Exact synthetic native corpus/policy/predicate replay; no real phone or provider access");
        Files.writeString(new File(output,"native-reader-control-snapshot.json").toPath(),controls.toString(2));
    }
    private void applyExplicitOwnerControl(JSONObject source)throws Exception {
        if(!usePrivacyControls||!("forget".equals(source.optString("event"))||"permission_revoke".equals(source.optString("event"))))return;
        JSONArray targets=source.getJSONArray("targetSources");
        for(int i=0;i<targets.length();i++){
            JSONObject mapped=sourceMap.get(targets.getString(i));assertNotNull(mapped);
            Object receipt=ledger.getClass().getMethod("excludeSource",String.class,long.class,String.class,String.class,String.class).invoke(ledger,mapped.getString("type"),mapped.getLong("id"),mapped.getString("recordHash"),"retrieval_exclude","Explicit synthetic owner control: "+source.getString("event"));
            boolean physicalDeleteRejected=false;
            if("forget".equals(source.optString("event"))&&!usePhysicalDeletion)try{ledger.getClass().getMethod("excludeSource",String.class,long.class,String.class,String.class,String.class).invoke(ledger,mapped.getString("type"),mapped.getLong("id"),mapped.getString("recordHash"),"delete_payload","Requested physical payload deletion");}
            catch(java.lang.reflect.InvocationTargetException unavailable){if(unavailable.getCause() instanceof IllegalArgumentException)physicalDeleteRejected=true;else throw unavailable;}
            JSONObject operation=json("controlSourceId",source.getString("sourceId"),"targetRawSourceId",targets.getString(i),"target",mapped,"receipt",receipt,"physicalDeleteRequested","forget".equals(source.optString("event")),"ledgerDeleteModeRejected",physicalDeleteRejected,"physicalDeleteUnsupported",physicalDeleteRejected,"physicalDeletionCoordinatorExercised",usePhysicalDeletion&&"forget".equals(source.optString("event")),"controlParsing","fixture-supplied explicit owner event; no LM command parsing measured");controlOperations.add(operation);
            if(usePhysicalDeletion&&"forget".equals(source.optString("event")))pendingPhysicalDeletes.add(operation);
        }
    }

    private void executePendingPhysicalDeletes()throws Exception {
        for(JSONObject operation:pendingPhysicalDeletes){JSONObject source=operation.getJSONObject("target");JSONObject receipt=(JSONObject)payloadDeletion.getClass().getMethod("deleteSource",String.class,long.class,String.class,String.class).invoke(payloadDeletion,source.getString("type"),source.getLong("id"),source.getString("recordHash"),"Explicit synthetic owner request to delete original and derived memory");operation.put("payloadDeletionReceipt",receipt);if(receipt.optBoolean("managedLocalDeletionVerified"))physicallyDeletedSources.add(operation.getString("targetRawSourceId"));}
        pendingPhysicalDeletes.clear();
    }

    private void prepareNotes()throws Exception {
        String path=System.getenv("HOYOUNG_FIVE_YEAR_NOTES");if(path==null||path.isEmpty())return;
        String raw=Files.readString(new File(path).toPath());notesSha=sha(raw);JSONArray notes;
        if(raw.stripLeading().startsWith("["))notes=new JSONArray(raw);else {JSONObject wrapper=new JSONObject(raw);notes=wrapper.optJSONArray("notes");if(notes==null)notes=wrapper.getJSONArray("memories");}
        Set<String> rawOnlySources=new HashSet<>();JSONArray cases=matrix.getJSONArray("cases");for(int i=0;i<cases.length();i++){JSONObject c=cases.getJSONObject(i);if("raw_only".equals(c.getString("stratum")))for(int j=0;j<c.getJSONArray("supportSources").length();j++)rawOnlySources.add(c.getJSONArray("supportSources").getString(j));}
        for(int i=0;i<notes.length();i++){
            JSONObject note=notes.getJSONObject(i);JSONArray refs=note.optJSONArray("rawSourceIds");if(refs==null&&note.optJSONArray("sourceRefs")!=null){refs=new JSONArray();JSONArray originals=note.getJSONArray("sourceRefs");for(int j=0;j<originals.length();j++)refs.put(originals.getJSONObject(j).getString("rawSourceId"));note.put("rawSourceIds",refs);}if(!note.has("sourceReferences")&&note.optJSONArray("sourceRefs")!=null)note.put("sourceReferences",note.getJSONArray("sourceRefs"));String reason="";LocalDate latest=LocalDate.MIN;
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
        applyExplicitOwnerControl(s);
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
        JSONArray cases=matrix.getJSONArray("cases");int allDelivered=0,totalRequired=0,deliveredRequired=0,rawCases=0,rawDelivered=0,leakCases=0,blockedCases=0;List<Long> latencies=new ArrayList<>();
        for(int i=0;i<cases.length();i++) {
            JSONObject c=cases.getJSONObject(i);JSONObject args=json("query",c.getString("question"),"asOf",at(checkpoint.plusDays(1)),"toExclusive",at(checkpoint.plusDays(1)),"limit",LIMIT,"maxChars",CONTEXT_CHARS);
            long t=System.nanoTime();JSONObject result=json("originalEvidence",new JSONArray(),"diagnostics",new JSONObject());String context="";JSONArray gatewayReads=new JSONArray(),gatewayDependencies=new JSONArray();JSONObject gatewayDiagnostics=new JSONObject();boolean freshOutputChecked=false;String failureCode="";
            try {result=retriever.search(args);context=result.getString("context");
            if(useGateway){
                String opaqueDispatch=sha(checkpoint+"\u0000"+c.getString("question")).substring(0,20);
                Method prepare=gateway.getClass().getMethod("prepare",String.class,String.class,String.class,long.class,String.class,JSONObject.class,int.class,long.class);
                Object prepared=prepare.invoke(gateway,"bench-"+opaqueDispatch,"benchmark-owner","normal",0L,c.getString("question"),result,CONTEXT_CHARS,at(checkpoint.plusDays(1)));
                context=(String)prepared.getClass().getField("context").get(prepared);
                if("2021-12-31".equals(checkpoint.toString())&&"forget_revoke".equals(c.getString("stratum")))yearOneCachedPrepared.put(c.getString("caseId"),prepared);
                gatewayReads=(JSONArray)prepared.getClass().getMethod("receipts").invoke(prepared);gatewayDependencies=(JSONArray)prepared.getClass().getMethod("dependencies").invoke(prepared);gatewayDiagnostics=(JSONObject)prepared.getClass().getMethod("diagnostics").invoke(prepared);
                if(gatewayReads.length()!=5)throw new IllegalStateException("missing_five_actual_layer_receipts");
                gateway.getClass().getMethod("assertFresh",prepared.getClass(),long.class,boolean.class).invoke(gateway,prepared,at(checkpoint.plusDays(1)),false);freshOutputChecked=true;
            }
            }catch(Exception unavailable){
                Throwable cause=unavailable instanceof java.lang.reflect.InvocationTargetException?((java.lang.reflect.InvocationTargetException)unavailable).getCause():unavailable;
                failureCode=cause.getClass().getSimpleName();try{Object reason=cause.getClass().getField("reason").get(cause);if(reason instanceof String&&((String)reason).matches("[a-zA-Z0-9_]{1,96}"))failureCode=(String)reason;}catch(ReflectiveOperationException ignored){}
                context="[Native memory preparation/output was blocked; no admitted source evidence was delivered.]";blockedCases++;
            }
            long elapsed=(System.nanoTime()-t)/1000000;latencies.add(elapsed);assertTrue(context.length()<=CONTEXT_CHARS);
            JSONArray required=new JSONArray(),ranked=new JSONArray(),delivered=new JSONArray();int hit=0,needs=0;Set<String> known=new HashSet<>();
            JSONArray evidence=result.getJSONArray("originalEvidence");for(int j=0;j<evidence.length();j++){JSONObject e=evidence.getJSONObject(j);String nativeKey=e.getString("type")+":"+e.getLong("id");ranked.put(nativeKey);known.add(nativeKey);}
            if(useGateway)for(int j=0;j<gatewayDependencies.length();j++){JSONObject dependency=gatewayDependencies.getJSONObject(j);String type=dependency.optString("type");if("message".equals(type)||"device_event".equals(type))known.add(type+":"+dependency.getLong("id"));}
            JSONArray support=c.getJSONArray("supportSources");JSONObject expected=c.getJSONObject("expected").optJSONObject(checkpoint.toString());if(expected==null)expected=c.getJSONObject("expected").getJSONObject("all");
            boolean protectedAfterControl=expected.has("forbiddenValues");
            for(int j=0;j<support.length();j++) {
                String sid=support.getString(j);JSONObject s=sourceDefinitions.get(sid),mapped=sourceMap.get(sid);
                if(mapped==null||LocalDate.parse(s.getString("recordedAt")).isAfter(checkpoint))continue;
                if(protectedAfterControl&&s.optString("event").isEmpty())continue;
                needs++;required.put(mapped);String key=mapped.getString("type")+":"+mapped.getLong("id");
                boolean sourceLocator=useGateway?context.contains(mapped.getString("recordHash")):context.contains("ORIGINAL "+key+" ");boolean present=known.contains(key)&&sourceLocator&&context.contains(s.getString("text"));
                if(present){hit++;delivered.put(mapped);}
            }
            totalRequired+=needs;deliveredRequired+=hit;boolean complete=hit==needs;if(complete)allDelivered++;
            if("raw_only".equals(c.getString("stratum"))){rawCases++;if(complete)rawDelivered++;}
            JSONArray leaks=new JSONArray();JSONArray forbidden=expected.optJSONArray("forbiddenValues");if(forbidden!=null)for(int j=0;j<forbidden.length();j++){String secret=forbidden.getString(j);if(context.contains(secret)||result.toString().contains(secret))leaks.put(json("protectedValueSha256",sha(secret),"inContext",context.contains(secret),"inCandidates",result.toString().contains(secret)));}
            if(leaks.length()>0)leakCases++;
            String opaque=sha(checkpoint+"\u0000"+c.getString("question")).substring(0,20);
            rows.add(json("opaqueId",opaque,"caseId",c.getString("caseId"),"stratum",c.getString("stratum"),"checkpoint",checkpoint.toString(),"requiredSources",required,"rankedNativeSourceIds",ranked,"deliveredSources",delivered,"requiredCount",needs,"deliveredCount",hit,"allRequiredDelivered",complete,"protectedValueLeaks",leaks,"elapsedMillis",elapsed,"contextChars",context.length(),"contextSha256",sha(context),"diagnostics",result.getJSONObject("diagnostics"),"gatewayReceipts",gatewayReads,"gatewayDependencies",gatewayDependencies,"gatewayDiagnostics",gatewayDiagnostics,"freshOutputCheckPassed",freshOutputChecked,"blocked",!failureCode.isEmpty(),"failureCode",failureCode,"groundedAnswerScore","not_measured"));
            readerPackets.add(json("id",opaque,"date",checkpoint.toString(),"question",c.getString("question"),"evidence",context,"evidencePolicy","Answer using supplied evidence only. Source relevance does not establish truth or completeness. Distinguish dated/current facts, request/confirmation, owner reports, quotations, and missing image pixels. Do not invent. Historical purchase/notification text is not present authorization.","actualSourceFollowups",0,"maximumSourceFollowups",3,"modelAnswerMeasured",false));
            Files.writeString(new File(output,"checkpoint-"+checkpoint+"-scoring.partial.jsonl").toPath(),rows.get(rows.size()-1).toString()+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
            Files.writeString(new File(output,"checkpoint-"+checkpoint+"-reader-inputs.partial.jsonl").toPath(),readerPackets.get(readerPackets.size()-1).toString()+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
        }
        Collections.sort(latencies);return json("checkpoint",checkpoint.toString(),"cases",cases.length(),"allRequiredSourcesDelivered",allDelivered,"requiredSourceCount",totalRequired,"requiredSourcesDelivered",deliveredRequired,"rawOnlyCases",rawCases,"rawOnlyCompleteDelivery",rawDelivered,"protectedValueLeakCases",leakCases,"blockedCases",blockedCases,"p50Millis",latencies.get(latencies.size()/2),"p95Millis",latencies.get((int)Math.ceil(latencies.size()*.95)-1),"groundedAnswerAccuracy","not_measured","completedUsefulAnswers","not_measured");
    }
    private void verifyPrivacyCacheAndRestoreReplay(LocalDate finalDate)throws Exception {
        assertNotNull("Actual year-one backup required",yearOneBackup);String policyBefore=(String)ledger.getClass().getMethod("fingerprint").invoke(ledger);
        int entries=0;try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new java.io.FileInputStream(yearOneBackup))){java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null){assertFalse("Owner privacy policy must stay outside restored archive",entry.getName().contains("privacy-ledger"));entries++;}}
        JSONArray cachedChecks=new JSONArray();JSONArray cases=matrix.getJSONArray("cases");
        for(int i=0;i<cases.length();i++){
            JSONObject c=cases.getJSONObject(i);if(!"forget_revoke".equals(c.getString("stratum")))continue;Object cached=yearOneCachedPrepared.get(c.getString("caseId"));
            if(cached==null){cachedChecks.put(json("caseId",c.getString("caseId"),"cachedScopeAvailable",false,"passed",false));continue;}
            boolean blocked=false;String reason="";
            try{gateway.getClass().getMethod("assertFresh",cached.getClass(),long.class,boolean.class).invoke(gateway,cached,at(finalDate.plusDays(1)),false);}
            catch(java.lang.reflect.InvocationTargetException stale){blocked=true;reason=nativeFailureCode(stale);}
            JSONObject expected=c.getJSONObject("expected").getJSONObject(finalDate.toString());String cachedContext=(String)cached.getClass().getField("context").get(cached);boolean onceContainedProtectedValue=false;JSONArray secrets=expected.getJSONArray("forbiddenValues");for(int j=0;j<secrets.length();j++)onceContainedProtectedValue|=cachedContext.contains(secrets.getString(j));
            cachedChecks.put(json("caseId",c.getString("caseId"),"cachedScopeAvailable",true,"cachedContextSha256",sha(cachedContext),"previousPacketContainedProtectedValue",onceContainedProtectedValue,"freshOutputRejected",blocked,"reason",reason,"passed",blocked&&"memory_privacy_changed".equals(reason)));
        }
        try(java.io.InputStream archive=new java.io.FileInputStream(yearOneBackup)){internalFiles.importBackup(archive,store);}
        configureProduct();JSONObject reconciled=new JSONObject();if(usePhysicalDeletion)reconciled=(JSONObject)payloadDeletion.getClass().getMethod("reconcileDeletionRequests").invoke(payloadDeletion);retriever.setDeviceSourceVisibility(event->true);gateway.getClass().getMethod("setDeviceSourceVisibility",java.util.function.Predicate.class).invoke(gateway,(java.util.function.Predicate<JSONObject>)event->true); // Isolate durable ledger from fixture predicate.
        String policyAfter=(String)ledger.getClass().getMethod("fingerprint").invoke(ledger);assertEquals("Old restore cannot roll back current exclusions",policyBefore,policyAfter);
        JSONArray restoredChecks=new JSONArray();int exposed=0,completed=0,blocked=0;
        for(int i=0;i<cases.length();i++){
            JSONObject c=cases.getJSONObject(i);if(!"forget_revoke".equals(c.getString("stratum")))continue;JSONObject expected=c.getJSONObject("expected").getJSONObject(finalDate.toString());String context="",candidateText="",failure="";
            try{JSONObject candidates=retriever.search(json("query",c.getString("question"),"asOf",at(finalDate.plusDays(1)),"toExclusive",at(finalDate.plusDays(1)),"limit",LIMIT,"maxChars",CONTEXT_CHARS));candidateText=candidates.toString();String id="replay-"+sha(c.getString("question")).substring(0,20);Object prepared=gateway.getClass().getMethod("prepare",String.class,String.class,String.class,long.class,String.class,JSONObject.class,int.class,long.class).invoke(gateway,id,"benchmark-owner","normal",0L,c.getString("question"),candidates,CONTEXT_CHARS,at(finalDate.plusDays(1)));context=(String)prepared.getClass().getField("context").get(prepared);gateway.getClass().getMethod("assertFresh",prepared.getClass(),long.class,boolean.class).invoke(gateway,prepared,at(finalDate.plusDays(1)),false);completed++;}
            catch(Exception unavailable){failure=nativeFailureCode(unavailable);blocked++;context="";}
            boolean leaked=false;JSONArray secrets=expected.getJSONArray("forbiddenValues");for(int j=0;j<secrets.length();j++)leaked|=context.contains(secrets.getString(j))||candidateText.contains(secrets.getString(j));if(leaked)exposed++;
            restoredChecks.put(json("caseId",c.getString("caseId"),"protectedValueExposed",leaked,"contextSha256",sha(context),"contextChars",context.length(),"candidateSha256",sha(candidateText),"blocked",!failure.isEmpty(),"failure",failure));
        }
        JSONObject report=json("actualYearOneStreamingBackup",true,"backupBytes",yearOneBackup.length(),"backupEntries",entries,"privacyFileIncluded",false,"policyFingerprintBeforeRestore",policyBefore,"policyFingerprintAfterRestore",policyAfter,"cachedYearOneScopes",cachedChecks,"restoredOldCorpusChecks",restoredChecks,"restoredQueryCompletions",completed,"restoredQueryBlocks",blocked,"protectedExposureCases",exposed,"physicalPayloadDeletionMeasured",usePhysicalDeletion,"deletionReconcileAfterRestore",reconciled,"scope",usePhysicalDeletion?"Tracked managed-local owner deletion replay; provider archives, untracked copies and already delivered bytes are outside verified scope.":"Current ledger blocks newly served cached scopes and restored source retrieval; old payload remains locally retained. Prior delivered bytes are not erased.");
        int rejectedFreshScopes=0;for(int i=0;i<cachedChecks.length();i++)if(cachedChecks.getJSONObject(i).optBoolean("passed"))rejectedFreshScopes++;
        boolean replayPass=rejectedFreshScopes==12&&completed==12&&blocked==0&&exposed==0;report.put("cachedScopesRejectedForPrivacyChange",rejectedFreshScopes);report.put("retrievalExclusionReplayPassed",replayPass);
        Files.writeString(new File(output,"privacy-replay-report.json").toPath(),report.toString(2));
        File mainReport=new File(output,"report.json");JSONObject main=new JSONObject(Files.readString(mainReport.toPath()));main.put("privacyReplayComplete",true);main.put("retrievalExclusionReplayPassed",replayPass);Files.writeString(mainReport.toPath(),main.toString(2));
    }
    private static String nativeFailureCode(Throwable unavailable){Throwable cause=unavailable instanceof java.lang.reflect.InvocationTargetException?((java.lang.reflect.InvocationTargetException)unavailable).getCause():unavailable;String code=cause.getClass().getSimpleName();try{Object reason=cause.getClass().getField("reason").get(cause);if(reason instanceof String&&((String)reason).matches("[a-zA-Z0-9_]{1,96}"))code=(String)reason;}catch(ReflectiveOperationException ignored){}return code;}

    private void verifyRawIntegrity()throws Exception {
        for(Map.Entry<String,JSONObject> pair:sourceMap.entrySet()){JSONObject m=pair.getValue(),s=sourceDefinitions.get(pair.getKey());JSONObject raw="message".equals(m.getString("type"))?store.getMessage(m.getLong("id")):store.getDeviceEvent(m.getLong("id"));if(physicallyDeletedSources.contains(pair.getKey())){assertNull("Explicit managed-local deleted original must stay absent",raw);continue;}assertNotNull("Unexpected missing original",raw);assertEquals(m.getString("recordHash"),raw.getString("content_hash"));String text="message".equals(m.getString("type"))?raw.getString("text"):raw.getJSONObject("payload").getString("text");assertEquals(s.getString("textSha256"),sha(text));}
    }
    private void writeArtifacts(JSONArray checkpoints,int days,int distractors,long millis,boolean finished)throws Exception {
        JSONObject report=json("benchmark","six-calendar-year-production-source-delivery-v1","finished",finished,"matrixSha256",System.getenv("HOYOUNG_FIVE_YEAR_MATRIX_SHA"),"productionCommit",System.getenv("HOYOUNG_FIVE_YEAR_SOURCE_REF"),"arm",notesSha.isEmpty()?"raw chronological storage; no model extraction or hand-seeded derived notes":"writer-derived notes replayed at latest referenced date; writer execution separate","calendarDays",days,"distractors",distractors,"sourceRecords",sourceMap.size(),"messages",store.messageCount(),"memoryNotes",store.memoryCount(),"writerNotesSha256",notesSha,"writerNotesInserted",insertedNotes,"writerNotesRejected",new JSONArray(rejectedNotes),"gatewayEnabled",useGateway,"physicalDeletionCoordinatorEnabled",usePhysicalDeletion,"physicallyDeletedOriginalSources",new JSONArray(physicallyDeletedSources),"privacyReplayRequested",useGateway&&usePrivacyControls,"privacyReplayComplete",false,"ownerPrivacyControlsApplied",new JSONArray(controlOperations),"checkpoints",checkpoints,"elapsedMillis",millis,"corpusDigest",hex((byte[])((MessageDigest)corpusDigest.clone()).digest()),"runtime","Robolectric SDK28 native SQLite; JDK17 host","semanticEncoder","absent; lexical production fallback only","contextChars",CONTEXT_CHARS,"sourceLimit",LIMIT,"oracleNeverInReaderPacket",true,"actualModelExtractionMeasured",false,"writerDerivedNotesLoaded",!notesSha.isEmpty(),"writerModelRuntimeVerifiedByHarness",false,"chronologicalIncrementalWriterVerified",false,"actualGroundedAnswerQualityMeasured",false,"actualFiveYearsElapsed",false,"actualWorkCalls",0,"physicalPhoneMeasured",false,"paidApiCalls",0,"finalAcceptancePassed",false,"scope",usePhysicalDeletion?"Explicit managed-local owner deletion coordinator exercised; receipts distinguish verified local completion from pending/outside-scope archives. No model command parsing, real provider erasure or answer accuracy is established.":usePrivacyControls?"Explicit owner controls applied through production retrieval-exclusion ledger; physical payload deletion not exercised. No model command parsing, answer quality, managed cache or restore replay is established by this run.":"Delivery/integrity baseline only. Forget messages are not executed by a model. Permission revocation uses production device visibility predicate. Neither successful answers nor product forget enforcement are established.");
        Files.writeString(new File(output,"report.json").toPath(),report.toString(2));Files.writeString(new File(output,"scoring-rows.json").toPath(),new JSONArray(rows).toString(2));Files.writeString(new File(output,"source-map.json").toPath(),new JSONObject(sourceMap).toString(2));
        StringBuilder packets=new StringBuilder();for(JSONObject p:readerPackets)packets.append(p).append('\n');Files.writeString(new File(output,"reader-inputs.jsonl").toPath(),packets.toString());
        System.out.println("FIVE_YEAR_CHECKPOINT "+checkpoints);
    }
    private static long at(LocalDate date){return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();}
    private static JSONObject json(Object...pairs){JSONObject o=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)o.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}return o;}
    private static String sha(String text)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
}
