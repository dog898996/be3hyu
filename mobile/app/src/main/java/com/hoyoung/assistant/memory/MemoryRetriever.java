package com.hoyoung.assistant.memory;

import android.database.Cursor;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.SourcePrivacyLedger;
import com.hoyoung.assistant.store.SourceRecordVisibility;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/** Evidence-first hybrid retrieval, run on the app's I/O executor rather than the UI thread. */
public final class MemoryRetriever {
    private final HoyoungStore store;
    private final SemanticEncoder encoder;
    private final SemanticIndex semantic;
    private volatile boolean semanticEnabled=true;
    private volatile SemanticCandidateProvider candidateProvider;
    private volatile SourcePrivacyLedger privacyLedger;
    private volatile java.util.function.Predicate<JSONObject> deviceVisibility=event->true;
    public MemoryRetriever(HoyoungStore store) { this(store,null); }
    public MemoryRetriever(HoyoungStore store,SemanticEncoder encoder) { this(store,encoder,null); }
    public MemoryRetriever(HoyoungStore store,SemanticEncoder encoder,SourcePrivacyLedger ledger) { this.store=store;this.encoder=encoder;semantic=new SemanticIndex(store,encoder);privacyLedger=ledger==null?store.getPrivacyLedger():ledger; }
    public void setSourcePrivacyLedger(SourcePrivacyLedger ledger){privacyLedger=ledger==null?store.getPrivacyLedger():ledger;}
    /** Reuse for native source.read/enumeration; a direct record and its supported native dependencies share privacy scope. */
    public boolean allowsSourceRecord(String type,JSONObject row,SourcePrivacyLedger.Snapshot snapshot){return row!=null&&new SearchPrivacy(privacyLedger,snapshot).allowedRecord(type,row);}
    public void setSemanticEnabled(boolean enabled) {semanticEnabled=enabled;}
    public void setDeviceSourceVisibility(java.util.function.Predicate<JSONObject> visibility){deviceVisibility=visibility==null?event->true:visibility;semantic.setDeviceSourceVisibility(deviceVisibility);}
    public void setSemanticCandidateProvider(SemanticCandidateProvider provider){candidateProvider=provider;}
    public JSONObject indexPending(int maxSources,long timeBudgetMs) {if(!semanticEnabled){JSONObject state=diagnostics();MemoryPolicy.put(state,"status","owner_disabled_semantics");return state;}JSONObject state=semantic.indexPending(maxSources,timeBudgetMs);SemanticCandidateProvider provider=candidateProvider;if(provider!=null)try{MemoryPolicy.put(state,"annMaintenance",provider.maintain(512,Math.max(100,Math.min(1000,timeBudgetMs))));}catch(RuntimeException failure){MemoryPolicy.put(state,"annMaintenanceError",failure.getClass().getSimpleName());}return state;}
    public JSONObject diagnostics() {JSONObject state=semantic.diagnostics();MemoryPolicy.put(state,"semanticEnabled",semanticEnabled);if(!semanticEnabled)MemoryPolicy.put(state,"semanticMode","owner-disabled");SemanticCandidateProvider provider=candidateProvider;if(provider!=null)MemoryPolicy.put(state,"semanticAnn",providerDiagnostic(provider));return state;}
    public String context(String query,int maxChars) {
        JSONObject args=new JSONObject();MemoryPolicy.put(args,"query",query);MemoryPolicy.put(args,"maxChars",maxChars);return search(args).optString("context");
    }
    public JSONObject search(JSONObject arguments) {
        MemoryQuery q=new MemoryQuery(arguments==null?new JSONObject():arguments);
        SearchPrivacy privacy=new SearchPrivacy(privacyLedger);
        Map<String,Candidate> memories=new LinkedHashMap<>();Map<String,JSONObject> originals=new LinkedHashMap<>();
        JSONObject diagnostics=diagnostics();JSONArray missingSources=new JSONArray();boolean candidateCap=false,historyCap=false;
        if(privacy.unavailable)return privacyBlocked(q,diagnostics,privacy,"privacy_policy_unavailable");
        JSONArray exact=store.retrieveMemoryCandidates(q.query,500),expanded=store.retrieveMemoryCandidates(q.expanded,500);
        candidateCap=exact.length()>=500||expanded.length()>=500;
        JSONArray pins=store.retrievePinnedMemories(1000);long pinnedPopulation; synchronized(store){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM memory_metadata x JOIN memories m ON m.memory_key=x.memory_key WHERE x.pinned=1",null)){pinnedPopulation=c.moveToFirst()?c.getLong(0):0;}}
        collect(memories,exact,1.0,false,privacy);collect(memories,expanded,0.8,false,privacy);collect(memories,pins,0,true,privacy);
        MemoryPolicy.put(diagnostics,"pinnedCandidateCount",pins.length());MemoryPolicy.put(diagnostics,"pinnedPopulationCount",pinnedPopulation);MemoryPolicy.put(diagnostics,"pinnedCandidateLimitReached",pinnedPopulation>pins.length());MemoryPolicy.put(diagnostics,"pinnedReservationLimit",12);
        JSONArray raw=store.rankedOriginalCandidates(q.query,q.from,q.to,500);
        candidateCap|=raw.length()>=500;addOriginals(originals,raw,q,privacy);
        JSONArray rawExpanded=store.rankedOriginalCandidates(q.expanded,q.from,q.to,500);
        candidateCap|=rawExpanded.length()>=500;addOriginals(originals,rawExpanded,q,privacy);
        JSONArray devices=store.rankedDeviceCandidates(q.query,q.from,q.to,500);
        candidateCap|=devices.length()>=500;addDeviceOriginals(originals,devices,q,privacy);
        if(!q.expanded.equals(q.query)){JSONArray deviceExpanded=store.rankedDeviceCandidates(q.expanded,q.from,q.to,500);candidateCap|=deviceExpanded.length()>=500;addDeviceOriginals(originals,deviceExpanded,q,privacy);}
        String semanticError="";JSONObject annDiagnostic=null,structuredDiagnostic=null;
        semantic.resetScanDiagnostics();
        if(semanticEnabled&&semantic.ready()&&!q.query.isEmpty()) try {
            float[] vector=VectorCodec.normalize(encoder.encode(q.query),encoder.dimensions());
            long exactMemoryLimit=Math.max(128,Math.min(4096,8L*1024*1024/(Math.max(1,encoder.dimensions())*4L)));boolean boundedMemories=diagnostics.optLong("semanticIndexedCount")>exactMemoryLimit;
            if(!boundedMemories){collect(memories,semantic.memories(vector,Math.max(50,q.limit*3)),1.0,false,privacy);structuredDiagnostic=new JSONObject();MemoryPolicy.put(structuredDiagnostic,"method","paged-exact-current-memory-vectors");MemoryPolicy.put(structuredDiagnostic,"fullMemoryVectorScanSkipped",false);MemoryPolicy.put(structuredDiagnostic,"exactScanRowThreshold",exactMemoryLimit);MemoryPolicy.put(structuredDiagnostic,"indexedCorpusCount",diagnostics.optLong("semanticIndexedCount"));MemoryPolicy.put(structuredDiagnostic,"wholeCorpusMemoryNeuralSearch",diagnostics.optLong("semanticIndexedCount")>=diagnostics.optLong("memoryTotals")&&!semantic.scanDiagnostics().optBoolean("limitReached"));}
            JSONArray chunks;SemanticCandidateProvider provider=candidateProvider;
            if(provider!=null&&provider.isReady(encoder.modelId())){
                try{chunks=provider.chunks(vector,q,Math.max(30,q.limit*2),deviceVisibility);annDiagnostic=providerDiagnostic(provider);MemoryPolicy.put(annDiagnostic,"usedForThisSearch",true);}
                catch(Exception failed){chunks=semantic.chunks(vector,q,Math.max(30,q.limit*2));annDiagnostic=providerDiagnostic(provider);MemoryPolicy.put(annDiagnostic,"usedForThisSearch",false);MemoryPolicy.put(annDiagnostic,"fallback","paged_exact_cosine");MemoryPolicy.put(annDiagnostic,"error",failed.getClass().getSimpleName());}
            }else{chunks=semantic.chunks(vector,q,Math.max(30,q.limit*2));if(provider!=null){annDiagnostic=providerDiagnostic(provider);MemoryPolicy.put(annDiagnostic,"usedForThisSearch",false);MemoryPolicy.put(annDiagnostic,"fallback","paged_exact_cosine");MemoryPolicy.put(annDiagnostic,"fallbackReason","ann_cache_not_current_or_ready");}}
            JSONArray verifiedChunks=new JSONArray();for(int i=0;i<chunks.length();i++) {
                JSONObject chunk=chunks.optJSONObject(i);JSONObject source=source(chunk.optString("type"),chunk.optLong("id"),privacy);
                if(chunk.has("modelId")&&!encoder.modelId().equals(chunk.optString("modelId")))continue;
                if(source==null||!chunk.optString("hash").equals(source.optString("content_hash"))||!q.recordedInRange(source.optLong("message".equals(chunk.optString("type"))?"created_at":"observed_at")))continue;
                JSONObject evidence=evidence(source,chunk.optString("type"));
                JSONObject range=semantic.sourceRange(chunk.optString("type"),chunk.optLong("id"),chunk.optLong("offsetChars"),chunk.optInt("lengthChars"));
                if(!chunk.optString("hash").equals(range.optString("recordHash")))continue;
                if(!entitiesMatch(q.entities,range.optString("text")))continue;
                verifiedChunks.put(chunk);
                MemoryPolicy.put(evidence,"excerpt",range.optString("text"));MemoryPolicy.put(evidence,"offsetChars",range.optLong("offsetChars"));MemoryPolicy.put(evidence,"lengthChars",range.optString("text").length());MemoryPolicy.put(evidence,"exactOriginalRange",true);MemoryPolicy.put(evidence,"semanticSimilarity",chunk.optDouble("semanticSimilarity"));
                MemoryPolicy.put(evidence,"retrievalScore",chunk.optDouble("semanticSimilarity")*2);MemoryPolicy.put(evidence,"relevantToQuery",chunk.optDouble("semanticSimilarity")>=0.25);mergeSemantic(originals,evidence,i+1);
            }
            if(boundedMemories){JSONObject selected=new StructuredMemorySearch(store,encoder,semantic).retrieve(vector,Math.max(50,q.limit*3),memories.keySet(),verifiedChunks);collect(memories,selected.optJSONArray("rows"),1.0,false,privacy);structuredDiagnostic=selected.optJSONObject("diagnostics");MemoryPolicy.put(structuredDiagnostic,"indexedCorpusCount",diagnostics.optLong("semanticIndexedCount"));MemoryPolicy.put(structuredDiagnostic,"exactScanRowThreshold",exactMemoryLimit);}
        } catch(Exception unavailable) {semanticError=unavailable.getClass().getSimpleName();}
        MemoryPolicy.put(diagnostics,"adaptiveRecall",adaptiveRecall(originals,q,privacy));
        List<Candidate> ranked=new ArrayList<>(memories.values());
        for(Candidate candidate:ranked) {
            JSONObject row=candidate.row;String doc=MemoryPolicy.key(row)+" "+SemanticIndex.document(row);
            double coverage=MemoryText.coverage(q.query,doc),expandedCoverage=MemoryText.coverage(q.expanded,doc);
            candidate.score+=coverage*4+expandedCoverage*0.4;
            candidate.relevant=!q.query.isEmpty()&&(coverage>0 || row.optDouble("score",0)>0 || row.optDouble("semanticSimilarity",-1)>=0.25);
            if(MemoryText.normalize(MemoryPolicy.key(row)).equals(MemoryText.normalize(q.query))&&!q.query.isEmpty())candidate.score+=10;
            if(!entitiesMatch(q.entities,doc)){candidate.score-=8;candidate.relevant=false;}
            if(!memoryRecordedInRange(row,q))candidate.relevant=false;
            String temporal=MemoryPolicy.temporal(row,q.asOf);if("future".equals(temporal)||"past".equals(temporal))candidate.score-=1;
            candidate.row=decorate(row,q,candidate.score,candidate.relevant,candidate.pinned);
        }
        ranked.sort((a,b)->Double.compare(b.score,a.score));
        List<JSONObject> selected=new ArrayList<>();int pinnedCount=0;
        // Profile constraints get a separate reservation, so busy/newer conversations cannot evict them.
        for(Candidate c:ranked)if(c.pinned&&pinnedCount++<12)selected.add(c.row);
        MemoryPolicy.put(diagnostics,"pinnedVisibleCandidates",pinnedCount);MemoryPolicy.put(diagnostics,"selectedPinnedCount",Math.min(12,pinnedCount));MemoryPolicy.put(diagnostics,"pinnedReservationSaturated",pinnedCount>12);
        for(Candidate c:ranked)if(selected.size()<q.limit+12&&!selected.contains(c.row)&&c.relevant)selected.add(c.row);
        List<JSONObject> history=new ArrayList<>();
        for(JSONObject row:selected) {
            JSONObject meta=MemoryPolicy.metadata(row);
            if(q.includeHistory||meta.has("factKey")||meta.has("validFrom")||meta.has("validTo")) {
                JSONObject historySlice=new MemoryHistory(store).select(MemoryPolicy.key(row),row.optInt("revision"),q,q.includeHistory?20:2);
                JSONArray revisions=historySlice.optJSONArray("rows");historyCap|=historySlice.optBoolean("limitReached");
                int included=0;
                for(int i=0;i<revisions.length();i++) {
                    JSONObject revision=revisions.optJSONObject(i);if(revision.optInt("revision")==row.optInt("revision")||!semantic.visibleMemory(MemoryPolicy.key(revision),revision.optInt("revision"))||!privacy.allowedMemory(revision))continue;
                    JSONObject old=decorate(revision,q,row.optDouble("retrievalScore"),row.optBoolean("relevantToQuery"),false);
                    MemoryPolicy.put(old,"historicalRevision",true);
                    String state=old.optString("temporalStatus");
                    if(history.size()>=q.limit*2) {historyCap=true;break;}
                    if(q.includeHistory||"valid_at_requested_time".equals(state)||included<2) {history.add(old);included++;}
                    if(included>=20) {historyCap|=i+1<revisions.length();break;}
                }
            }
            JSONArray refs=row.optJSONArray("sourceRefs");if(refs==null)continue;
            int read=0;boolean invalidSource=false;
            for(int i=0;i<refs.length()&&read<3;i++) {
                JSONObject ref=refs.optJSONObject(i);if(ref==null)continue;
                String type=ref.optString("type");long id=ref.optLong("id");JSONObject source=source(type,id,privacy);
                if(source==null||!ref.optString("hash").equals(source.optString("content_hash"))) {missingSources.put(ref);invalidSource=true;continue;}
                String key=type+":"+id;if(!originals.containsKey(key)) {
                    String originalExcerpt=excerpt(source,type,q.query);if(!entitiesMatch(q.entities,originalExcerpt)||!q.recordedInRange(source.optLong("message".equals(type)?"created_at":"observed_at")))continue;
                    JSONObject evidence=evidence(source,type);MemoryPolicy.put(evidence,"linkedMemory",MemoryPolicy.key(row));MemoryPolicy.put(evidence,"excerpt",originalExcerpt);MemoryPolicy.put(evidence,"retrievalScore",row.optDouble("retrievalScore")+0.5);MemoryPolicy.put(evidence,"relevantToQuery",row.optBoolean("relevantToQuery"));originals.put(key,evidence);
                }
                read++;
            }
            if(invalidSource){MemoryPolicy.put(row,"epistemicStatus","source_integrity_unknown_do_not_treat_as_verified");MemoryPolicy.put(row,"sourceIntegrity","missing_or_changed");}
        }
        selected.removeIf(row->!privacy.allowedMemory(row)||!semantic.visibleMemory(MemoryPolicy.key(row),row.optInt("revision")));
        history.removeIf(row->!privacy.allowedMemory(row)||!semantic.visibleMemory(MemoryPolicy.key(row),row.optInt("revision")));
        originals.entrySet().removeIf(entry->{JSONObject value=entry.getValue(),current=source(value.optString("type"),value.optLong("id"),privacy);return current==null||!value.optString("hash").equals(current.optString("content_hash"));});
        if(!privacy.current())return privacyBlocked(q,diagnostics,privacy,"privacy_policy_changed_during_search");
        List<JSONObject> all=new ArrayList<>(selected);all.addAll(history);JSONArray conflicts=MemoryPolicy.conflicts(all);
        List<JSONObject> evidence=fuseOriginalRanks(originals,q.query);
        if(evidence.size()>q.limit*2)evidence=new ArrayList<>(evidence.subList(0,q.limit*2));
        boolean answerEvidence=false;for(JSONObject row:all)answerEvidence|=row.optBoolean("relevantToQuery")&&!"unsourced_unverified".equals(row.optString("epistemicStatus"));
        for(JSONObject row:evidence)answerEvidence|=row.optBoolean("relevantToQuery");
        MemoryPolicy.put(diagnostics,"candidateLimitReached",candidateCap);MemoryPolicy.put(diagnostics,"historyLimitReached",historyCap);MemoryPolicy.put(diagnostics,"missingOrChangedSourceRefs",missingSources);MemoryPolicy.put(diagnostics,"semanticError",semanticError);MemoryPolicy.put(diagnostics,"semanticScan",semantic.scanDiagnostics());if(annDiagnostic!=null)MemoryPolicy.put(diagnostics,"semanticAnn",annDiagnostic);if(structuredDiagnostic!=null)MemoryPolicy.put(diagnostics,"structuredMemorySemantic",structuredDiagnostic);MemoryPolicy.put(diagnostics,"sourceRanking","reciprocal-rank-fusion-k60-plus-exact-identifier-and-source-backed-followup");MemoryPolicy.put(diagnostics,"noEvidenceIsNotProofOfAbsence",true);MemoryPolicy.put(diagnostics,"relevanceIsNotTruthConfidence",true);MemoryPolicy.put(diagnostics,"sourcePrivacy",privacy.diagnostics());MemoryPolicy.put(diagnostics,"rankedSampleOnly",true);MemoryPolicy.put(diagnostics,"completeRangeEnumerated",false);MemoryPolicy.put(diagnostics,"rangeEnumerationRequired",q.requiresExhaustiveCoverage);
        JSONObject result=new JSONObject();MemoryPolicy.put(result,"memories",new JSONArray(selected));MemoryPolicy.put(result,"history",new JSONArray(history));MemoryPolicy.put(result,"originalEvidence",new JSONArray(evidence));MemoryPolicy.put(result,"conflicts",conflicts);MemoryPolicy.put(result,"hasRelevantEvidence",answerEvidence);MemoryPolicy.put(result,"diagnostics",diagnostics);
        if(q.requiresExhaustiveCoverage)MemoryPolicy.put(result,"aggregationCoverage",aggregationCoverage());
        MemoryPolicy.put(result,"context",format(all,evidence,conflicts,diagnostics,q.maxChars,q.query));return result;
    }
    private static JSONObject providerDiagnostic(SemanticCandidateProvider provider){try{return new JSONObject(provider.diagnostics().toString());}catch(Exception failure){JSONObject state=new JSONObject();MemoryPolicy.put(state,"status","diagnostics_unavailable");MemoryPolicy.put(state,"error",failure.getClass().getSimpleName());return state;}}
    private static JSONObject aggregationCoverage(){JSONObject value=new JSONObject();MemoryPolicy.put(value,"complete",false);MemoryPolicy.put(value,"method","ranked_source_sample");MemoryPolicy.put(value,"requiredPath","paged_source_enumeration");MemoryPolicy.put(value,"code","complete_range_enumeration_required_before_aggregate");return value;}
    private JSONObject privacyBlocked(MemoryQuery q,JSONObject diagnostics,SearchPrivacy privacy,String code){
        diagnostics.remove("adaptiveRecall");diagnostics.remove("missingOrChangedSourceRefs");MemoryPolicy.put(diagnostics,"sourcePrivacy",privacy.diagnostics());MemoryPolicy.put(diagnostics,"retrievalBlocked",true);MemoryPolicy.put(diagnostics,"code",code);MemoryPolicy.put(diagnostics,"completeRangeEnumerated",false);
        JSONObject result=new JSONObject();for(String key:new String[]{"memories","history","originalEvidence","conflicts"})MemoryPolicy.put(result,key,new JSONArray());MemoryPolicy.put(result,"hasRelevantEvidence",false);MemoryPolicy.put(result,"diagnostics",diagnostics);MemoryPolicy.put(result,"context","[Memory retrieval is blocked because the current owner source-privacy policy could not be verified. No source content was delivered.]");return result;
    }
    private void collect(Map<String,Candidate> result,JSONArray rows,double weight,boolean pinned,SearchPrivacy privacy) {
        if(rows==null)return;
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;String key=MemoryPolicy.key(row);if(!semantic.visibleMemory(key,row.optInt("revision"))||!privacy.allowedMemory(row))continue;Candidate c=result.get(key);
            if(c==null){c=new Candidate(row);result.put(key,c);}c.score+=weight/(60+i+1);c.pinned|=pinned;
            if(row.has("semanticSimilarity"))MemoryPolicy.put(c.row,"semanticSimilarity",row.optDouble("semanticSimilarity"));
        }
    }
    private static boolean entitiesMatch(JSONArray entities,String text) {String normalized=MemoryText.normalize(text);for(int i=0;i<entities.length();i++)if(!normalized.contains(MemoryText.normalize(entities.optString(i))))return false;return true;}
    private static boolean memoryRecordedInRange(JSONObject row,MemoryQuery q){if(q.from==0&&q.to==0)return true;JSONArray refs=row.optJSONArray("sourceRefs");if(refs!=null)for(int i=0;i<refs.length();i++){JSONObject ref=refs.optJSONObject(i);if(ref!=null&&q.recordedInRange(ref.optLong("observedAt")))return true;}return (refs==null||refs.length()==0)&&q.recordedInRange(row.optLong("created_at"));}
    private static JSONObject decorate(JSONObject row,MemoryQuery q,double score,boolean relevant,boolean pinned) {
        JSONObject copy;try{copy=new JSONObject(row.toString());}catch(org.json.JSONException bad){throw new IllegalArgumentException(bad);}
        String content=row.optString("content");MemoryPolicy.put(copy,"contentChars",row.optInt("contentChars",content.length()));
        if(content.length()>4000){MemoryPolicy.put(copy,"content",MemoryText.window(content,q.query,4000));MemoryPolicy.put(copy,"contentExcerpt",true);}
        MemoryPolicy.put(copy,"key",MemoryPolicy.key(row));MemoryPolicy.put(copy,"temporalStatus",MemoryPolicy.temporal(row,q.asOf));MemoryPolicy.put(copy,"epistemicStatus",MemoryPolicy.epistemic(row));MemoryPolicy.put(copy,"retrievalScore",score);MemoryPolicy.put(copy,"relevantToQuery",relevant);MemoryPolicy.put(copy,"pinned",pinned);return copy;
    }
    private void addOriginals(Map<String,JSONObject> out,JSONArray rows,MemoryQuery query,SearchPrivacy privacy) {
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);if(row==null||!privacy.allowedRecord("message",row))continue;JSONObject evidence=evidence(row,"message");String excerpt=excerpt(row,"message",query.expanded);if(!entitiesMatch(query.entities,excerpt))continue;
            double coverage=MemoryText.coverage(query.query,excerpt);MemoryPolicy.put(evidence,"excerpt",excerpt);double score=coverage*3+row.optDouble("score")/1000;MemoryPolicy.put(evidence,"retrievalScore",score);MemoryPolicy.put(evidence,"lexicalScore",score);MemoryPolicy.put(evidence,"relevantToQuery",coverage>0||(!query.query.isEmpty()&&row.optDouble("score")>0));out.putIfAbsent("message:"+row.optLong("id"),evidence);
        }
    }
    private void addDeviceOriginals(Map<String,JSONObject> out,JSONArray rows,MemoryQuery query,SearchPrivacy privacy){
        for(int i=0;i<rows.length();i++){JSONObject candidate=rows.optJSONObject(i);long id=candidate.optLong("id");String key="device_event:"+id;if(out.containsKey(key))continue;JSONObject original=source("device_event",id,privacy);if(original==null||!candidate.optString("content_hash").equals(original.optString("content_hash")))continue;String excerpt=excerpt(original,"device_event",query.query);if(!entitiesMatch(query.entities,excerpt))continue;JSONObject evidence=evidence(original,"device_event");double coverage=MemoryText.coverage(query.query,excerpt);MemoryPolicy.put(evidence,"excerpt",excerpt);MemoryPolicy.put(evidence,"originalChars",candidate.optLong("payloadChars"));double score=coverage*3+candidate.optDouble("score")/1000;MemoryPolicy.put(evidence,"retrievalScore",score);MemoryPolicy.put(evidence,"lexicalScore",score);MemoryPolicy.put(evidence,"relevantToQuery",coverage>0||(!query.query.isEmpty()&&candidate.optDouble("score")>0));out.put(key,evidence);}
    }
    private static void mergeSemantic(Map<String,JSONObject> rows,JSONObject semantic,int rank){
        String key=semantic.optString("type")+":"+semantic.optLong("id");JSONObject old=rows.get(key);
        if(old!=null&&old.has("semanticRank")){if(semantic.optDouble("semanticSimilarity")>old.optDouble("semanticSimilarity")){MemoryPolicy.put(old,"semanticSimilarity",semantic.optDouble("semanticSimilarity"));}return;}
        MemoryPolicy.put(semantic,"semanticRank",rank);
        if(old!=null){MemoryPolicy.put(semantic,"lexicalScore",old.optDouble("lexicalScore",old.optDouble("retrievalScore")));MemoryPolicy.put(semantic,"lexicalExcerpt",old.optString("excerpt"));MemoryPolicy.put(semantic,"relevantToQuery",old.optBoolean("relevantToQuery")||semantic.optBoolean("relevantToQuery"));}
        rows.put(key,semantic);
    }
    private static List<JSONObject> fuseOriginalRanks(Map<String,JSONObject> originals,String query){
        List<JSONObject> rows=new ArrayList<>(originals.values()),lexical=new ArrayList<>();for(JSONObject row:rows)if(row.has("lexicalScore"))lexical.add(row);
        lexical.sort((a,b)->Double.compare(b.optDouble("lexicalScore"),a.optDouble("lexicalScore")));for(int i=0;i<lexical.size();i++)MemoryPolicy.put(lexical.get(i),"lexicalRank",i+1);
        String exact=MemoryText.normalize(query);boolean identifier=exact.length()>=3&&!exact.contains(" ")&&exact.matches(".*[0-9_.@/-].*");
        boolean generatedOutput=exact.matches("(?s).*(?:코드|산출물|보고서|답변|결과|\\bcode\\b|\\bartifact\\b|\\breport\\b|\\boutput\\b|\\bresult\\b|\\bdraft\\b).*");
        Map<String,Integer> frequency=new HashMap<>();for(String term:MemoryText.terms(query)){int count=0;for(JSONObject row:rows)if(MemoryText.normalize(row.optString("excerpt")).contains(term))count++;frequency.put(term,count);}
        for(JSONObject row:rows){double score=0;if(row.has("lexicalRank"))score+=1.0/(60+row.optInt("lexicalRank"));if(row.has("semanticRank"))score+=1.0/(60+row.optInt("semanticRank"));if(row.has("linkedMemory"))score+=1.0/61;
            if(row.has("adaptiveRank"))score+=1.0/(60+row.optInt("adaptiveRank"));
            if(!generatedOutput&&("user".equals(row.optString("role"))||"device_observation".equals(row.optString("role"))))score+=0.02;
            score+=0.03*anchorScore(row,frequency);
            if(identifier&&(MemoryText.normalize(row.optString("excerpt")).contains(exact)||MemoryText.normalize(row.optString("lexicalExcerpt")).contains(exact)))score+=0.04;
            MemoryPolicy.put(row,"retrievalScore",score);MemoryPolicy.put(row,"rankingMethod","RRF(k=60);scores_are_not_truth_confidence");
        }
        rows.sort((a,b)->Double.compare(b.optDouble("retrievalScore"),a.optDouble("retrievalScore")));return rows;
    }
    private JSONObject source(String type,long id,SearchPrivacy privacy) {JSONObject row="message".equals(type)?store.getMessage(id):"device_event".equals(type)&&semantic.visibleDevice(id)?store.getDeviceEvent(id):null;return row!=null&&privacy.allowedRecord(type,row)?row:null;}
    private static JSONObject evidence(JSONObject row,String type) {
        JSONObject result=new JSONObject();MemoryPolicy.put(result,"type",type);MemoryPolicy.put(result,"id",row.optLong("id"));MemoryPolicy.put(result,"hash",row.optString("content_hash"));MemoryPolicy.put(result,"role","message".equals(type)?row.optString("role"):"device_observation");MemoryPolicy.put(result,"recordedAt",row.optLong("message".equals(type)?"created_at":"observed_at"));MemoryPolicy.put(result,"exactOriginalRange",false);MemoryPolicy.put(result,"originalChars",row.optLong("raw_char_count",row.optString("text").length()));MemoryPolicy.put(result,"originalStored",true);return result;
    }
    private String excerpt(JSONObject row,String type,String query) {
        if(!"message".equals(type))return MemoryText.window(row.optJSONObject("payload")==null?row.optString("payload"):row.optJSONObject("payload").toString(),query,1600);
        String body=row.optString("text");
        if(row.optLong("raw_char_count",body.length())>body.length()&&!MemoryText.terms(query).isEmpty()) {
            StringBuilder match=new StringBuilder();
            for(String term:MemoryText.terms(query))for(String token:term.split("[^\\p{L}\\p{N}_]+"))if(!token.isEmpty()){if(match.length()>0)match.append(" OR ");match.append('"').append(token.replace("\"","\"\"")).append("*\"");}
            if(match.length()>0)synchronized(store){
                try(Cursor c=store.getReadableDatabase().rawQuery("SELECT snippet(message_search,'⟦','⟧',' … ',0,48) FROM message_search WHERE docid=? AND message_search MATCH ?",new String[]{String.valueOf(row.optLong("id")),match.toString()})){if(c.moveToFirst())return c.getString(0);}
                catch(RuntimeException unavailable){/* Exact source remains accessible through source.read. */}
            }
        }
        return MemoryText.window(body,query,1600);
    }
    /** Bounded owner-original relationship traversal. It never executes or adds instructions from source text. */
    private JSONObject adaptiveRecall(Map<String,JSONObject> originals,MemoryQuery q,SearchPrivacy privacy){
        JSONObject report=new JSONObject();MemoryPolicy.put(report,"method","source_backed_explicit_relationship_followup");MemoryPolicy.put(report,"maxQueries",3);MemoryPolicy.put(report,"maxHydratedSources",20);MemoryPolicy.put(report,"completeRelationshipGraph",false);MemoryPolicy.put(report,"instructionsChanged",false);
        double best=0;for(JSONObject row:originals.values())best=Math.max(best,MemoryText.coverage(q.query,row.optString("excerpt")));
        boolean relationRequest=MemoryText.normalize(q.query).matches("(?s).*(?:담당|파트너|동료|팀원|출장|누구|어디|관련|\\bwho\\b|\\bwhere\\b|\\bpartner\\b|\\blocation\\b).*");
        if(q.query.isEmpty()||(!relationRequest&&best>=0.85)){MemoryPolicy.put(report,"attempted",false);MemoryPolicy.put(report,"reason","initial_lexical_coverage_sufficient");return report;}
        Set<String> known=new LinkedHashSet<>();for(String term:MemoryText.terms(q.query))known.add(MemoryText.normalize(term));
        List<Followup> pending=new ArrayList<>();Set<String> queued=new LinkedHashSet<>(),readIds=new LinkedHashSet<>();Map<String,ReadCue> cues=new LinkedHashMap<>();int hydrated=0,queries=0,added=0;boolean limited=false;long deadline=System.nanoTime()+350_000_000L;
        List<String> terms=MemoryText.terms(q.query);Map<String,Integer> frequency=new HashMap<>();for(String term:terms){int count=0;for(JSONObject row:originals.values())if(MemoryText.normalize(row.optString("excerpt")).contains(term))count++;frequency.put(term,count);}
        List<JSONObject> initial=new ArrayList<>(originals.values());initial.sort((a,b)->{int order=Double.compare(anchorScore(b,frequency),anchorScore(a,frequency));return order!=0?order:Double.compare(b.optDouble("lexicalScore",b.optDouble("retrievalScore")),a.optDouble("lexicalScore",a.optDouble("retrievalScore")));});
        // Initial cue inspection leaves most of the 20-source allowance for newly discovered edges.
        for(JSONObject seed:initial){if(hydrated>=6||System.nanoTime()>deadline){limited=true;break;}String key=seed.optString("type")+":"+seed.optLong("id");if(!"message".equals(seed.optString("type"))||!"user".equals(seed.optString("role"))||!readIds.add(key))continue;
            JSONObject canonical=source("message",seed.optLong("id"),privacy);if(canonical==null)continue;
            hydrated++;try{JSONObject range=store.readOriginalText(seed.optLong("id"),seed.optBoolean("exactOriginalRange")?seed.optLong("offsetChars"):0,6000);if(!seed.optString("hash").equals(range.optString("recordHash")))continue;cues.put(key,new ReadCue(range.optString("text"),seed));queueHints(pending,queued,known,range.optString("text"),seed);}
            catch(java.io.IOException|IllegalArgumentException unavailable){MemoryPolicy.put(report,"sourceReadFailed",true);}
        }
        JSONArray paths=new JSONArray();
        for(int next=0;next<pending.size()&&queries<3&&hydrated<20;next++){
            if(System.nanoTime()>deadline){limited=true;break;}
            Followup followup=pending.get(next);known.add(MemoryText.normalize(followup.term));queries++;
            // Earlier hydrated originals may become connected only after this new alias is known.
            for(ReadCue cue:cues.values())queueHints(pending,queued,known,cue.text,cue.evidence);
            JSONArray rows=store.rankedOriginalCandidates(followup.term,q.from,q.to,Math.min(20,20-hydrated));limited|=rows.length()>=Math.min(20,20-hydrated);
            for(int i=0;i<rows.length()&&hydrated<20;i++){
                if(System.nanoTime()>deadline){limited=true;break;}
                JSONObject candidate=rows.optJSONObject(i);if(candidate==null)continue;long id=candidate.optLong("id");String key="message:"+id;
                if(!"user".equals(candidate.optString("role"))||!privacy.allowedSource("message",id,candidate.optString("content_hash")))continue;
                ReadCue cached=cues.get(key);if(cached!=null){if(id!=followup.source.optLong("id")&&matchesFollowup(cached.text,followup.term)){MemoryPolicy.put(cached.evidence,"adaptiveRank",paths.length()+1);MemoryPolicy.put(cached.evidence,"relevantToQuery",true);MemoryPolicy.put(cached.evidence,"followupQueryTerm",followup.term);paths.put(followupPath(followup,id,candidate.optString("content_hash")));}continue;}
                if(!readIds.add(key))continue;
                hydrated++;try{
                    JSONObject range=store.readOriginalText(id,0,6000);if(!candidate.optString("content_hash").equals(range.optString("recordHash")))continue;
                    String text=range.optString("text");if(!matchesFollowup(text,followup.term))continue;
                    JSONObject value=evidence(candidate,"message");MemoryPolicy.put(value,"excerpt",MemoryText.window(text,q.query+" "+followup.term,1600));MemoryPolicy.put(value,"offsetChars",range.optLong("offsetChars"));MemoryPolicy.put(value,"exactOriginalRange",text.length()<=1600);MemoryPolicy.put(value,"lengthChars",text.length());
                    MemoryPolicy.put(value,"lexicalScore",MemoryText.coverage(q.query,text)*3+candidate.optDouble("score")/1000);MemoryPolicy.put(value,"relevantToQuery",true);MemoryPolicy.put(value,"adaptiveRank",added+1);
                    MemoryPolicy.put(value,"retrievalRelation","explicit_owner_relationship_search_hint_not_verified_equivalence");
                    MemoryPolicy.put(value,"followupQueryTerm",followup.term);
                    JSONObject old=originals.get(key);if(old==null){originals.put(key,value);added++;}else{MemoryPolicy.put(old,"adaptiveRank",added+1);MemoryPolicy.put(old,"relevantToQuery",true);}
                    cues.put(key,new ReadCue(text,old==null?value:old));paths.put(followupPath(followup,id,candidate.optString("content_hash")));
                    queueHints(pending,queued,known,text,value);
                }catch(java.io.IOException|IllegalArgumentException unavailable){MemoryPolicy.put(report,"sourceReadFailed",true);}
            }
        }
        MemoryPolicy.put(report,"attempted",true);MemoryPolicy.put(report,"queriesExecuted",queries);MemoryPolicy.put(report,"hydratedSources",hydrated);MemoryPolicy.put(report,"addedSourceCandidates",added);MemoryPolicy.put(report,"paths",paths);MemoryPolicy.put(report,"limitReached",limited||pending.size()>queries||hydrated>=20);MemoryPolicy.put(report,"operationStartBudgetMillis",350);MemoryPolicy.put(report,"singleOperationMayExceedBudget",true);MemoryPolicy.put(report,"integrityVerificationMayReadWholeOriginal",true);MemoryPolicy.put(report,"originalsRetained",true);return report;
    }
    private static void queueHints(List<Followup> queue,Set<String> queued,Set<String> known,String text,JSONObject source){for(String term:MemoryText.relatedQuotedTerms(text,known)){String normal=MemoryText.normalize(term);if(queued.add(normal)&&queue.size()<24)queue.add(new Followup(term,source));}}
    private static double anchorScore(JSONObject row,Map<String,Integer> frequency){String text=MemoryText.normalize(row.optString("excerpt"));double score=0;for(Map.Entry<String,Integer> term:frequency.entrySet())if(text.contains(term.getKey())){double population=1+term.getValue();score+=1.0/(population*population);}return score;}
    private static boolean matchesFollowup(String text,String term){return MemoryText.normalize(text).contains(MemoryText.normalize(term))||MemoryText.terms(term).size()>1&&MemoryText.coverage(term,text)>=0.5;}
    private static JSONObject followupPath(Followup followup,long id,String hash){JSONObject path=new JSONObject();MemoryPolicy.put(path,"cueSourceId",followup.source.optLong("id"));MemoryPolicy.put(path,"cueSourceHash",followup.source.optString("hash"));MemoryPolicy.put(path,"targetSourceId",id);MemoryPolicy.put(path,"targetSourceHash",hash);MemoryPolicy.put(path,"queryTerm",followup.term);MemoryPolicy.put(path,"relationshipIsSearchHint",true);return path;}
    private static final class ReadCue{final String text;final JSONObject evidence;ReadCue(String text,JSONObject evidence){this.text=text;this.evidence=evidence;}}
    private static final class Followup{final String term;final JSONObject source;Followup(String term,JSONObject source){this.term=term;this.source=source;}}

    /** One immutable privacy scope; inspect all canonical dependencies, not the first three displayed citations. */
    private final class SearchPrivacy {
        final SourcePrivacyLedger ledger;final SourcePrivacyLedger.Snapshot snapshot;final Map<String,Boolean> memories=new HashMap<>();final Set<String> rejected=new LinkedHashSet<>();boolean unavailable;String error="";
        SearchPrivacy(SourcePrivacyLedger ledger){this.ledger=ledger;SourcePrivacyLedger.Snapshot captured=null;try{if(ledger!=null)captured=ledger.capture();}catch(java.io.IOException|RuntimeException failure){unavailable=true;error=failure.getClass().getSimpleName();}snapshot=captured;}
        SearchPrivacy(SourcePrivacyLedger ledger,SourcePrivacyLedger.Snapshot snapshot){this.ledger=ledger;this.snapshot=snapshot;unavailable=snapshot==null;}
        boolean allowedSource(String type,long id,String hash){boolean allowed=!unavailable&&(snapshot==null||snapshot.allowedSource(type,id,hash));if(!allowed)rejected.add(type+":"+id);return allowed;}
        boolean allowedRecord(String type,JSONObject row){
            if(!allowedSource(type,row.optLong("id"),row.optString("content_hash")))return false;
            if(snapshot==null)return false;
            JSONObject full=row.optBoolean("candidateExcerpt")?store.getMessage(row.optLong("id")):row;if(full==null)return false;
            // A spilled native JSON linkage cannot be treated as ordinary text solely because its display prefix is incomplete.
            if("system".equals(full.optString("role"))&&full.optString("text").trim().startsWith("{")&&full.optLong("raw_char_count")>full.optString("text").length())return false;
            synchronized(store){return SourceRecordVisibility.allowed(store.getReadableDatabase(),snapshot,type,full,deviceVisibility);}
        }
        boolean allowedMemory(JSONObject row){
            if(unavailable||snapshot==null)return false;
            String key=MemoryPolicy.key(row)+"@"+row.optInt("revision");Boolean known=memories.get(key);if(known!=null)return known;
            boolean allowed=true;int count=0;
            synchronized(store){try(Cursor refs=store.getReadableDatabase().rawQuery("SELECT source_type,source_id,source_hash FROM memory_sources WHERE memory_key=? AND revision=?",new String[]{MemoryPolicy.key(row),String.valueOf(row.optInt("revision"))})){
                while(refs.moveToNext()){String type=refs.getString(0);long id=refs.getLong(1);String hash=refs.getString(2);if(++count>10_000||!allowedSource(type,id,hash)){allowed=false;break;}JSONObject original="message".equals(type)?store.getMessage(id):"device_event".equals(type)?store.getDeviceEvent(id):null;if(original==null||!allowedRecord(type,original)){allowed=false;break;}}
            }catch(RuntimeException invalid){allowed=false;error="source_dependency_unavailable";}}
            if(allowed&&count==0){JSONArray fallback=row.optJSONArray("sourceRefs");
                if(fallback!=null)for(int i=0;i<fallback.length();i++){JSONObject ref=fallback.optJSONObject(i);if(ref==null||!metadataSourceAllowed(ref.optString("type"),ref.optLong("id"),ref.optString("hash"))){allowed=false;break;}}}
            if(allowed&&row.optBoolean("sourceRefsTruncated")&&count<row.optInt("sourceCount"))allowed=false;
            if(allowed)allowed=allowedMetadata(MemoryPolicy.metadata(row));memories.put(key,allowed);return allowed;
        }
        boolean allowedMetadata(JSONObject meta){
            JSONArray quotes=meta.optJSONArray("evidenceQuotes");for(int i=0;quotes!=null&&i<quotes.length();i++){JSONObject ref=quotes.optJSONObject(i);if(ref==null||!metadataSourceAllowed(ref.optString("type"),ref.optLong("id"),ref.optString("recordHash",ref.optString("hash"))))return false;}
            JSONArray assets=meta.optJSONArray("assetRefs");for(int i=0;assets!=null&&i<assets.length();i++){JSONObject ref=assets.optJSONObject(i);if(ref==null||!metadataSourceAllowed("message",ref.optLong("messageId"),""))return false;}
            return !meta.has("analysisSourceMessageId")||metadataSourceAllowed("message",meta.optLong("analysisSourceMessageId"),"");
        }
        boolean metadataSourceAllowed(String type,long id,String hash){JSONObject original="message".equals(type)?store.getMessage(id):"device_event".equals(type)?store.getDeviceEvent(id):null;if(original==null)return false;if(hash.isEmpty())hash=original.optString("content_hash");return allowedSource(type,id,hash)&&hash.equals(original.optString("content_hash"))&&allowedRecord(type,original);}
        boolean current(){if(unavailable)return false;if(ledger==null)return true;try{return privacyLedger==ledger&&snapshot.fingerprint.equals(ledger.fingerprint());}catch(java.io.IOException|RuntimeException failure){unavailable=true;error=failure.getClass().getSimpleName();return false;}}
        JSONObject diagnostics(){JSONObject result=new JSONObject();MemoryPolicy.put(result,"configured",ledger!=null);MemoryPolicy.put(result,"available",!unavailable);MemoryPolicy.put(result,"version",snapshot==null?0:snapshot.version);MemoryPolicy.put(result,"fingerprint",snapshot==null?"":snapshot.fingerprint);MemoryPolicy.put(result,"excludedCandidateSources",rejected.size());MemoryPolicy.put(result,"allCanonicalDependenciesChecked",true);MemoryPolicy.put(result,"error",error);MemoryPolicy.put(result,"payloadDeleted",false);return result;}
    }
    private static String format(List<JSONObject> rows,List<JSONObject> evidence,JSONArray conflicts,JSONObject diagnostics,int budget,String query) {
        StringBuilder out=new StringBuilder("[Persistent memory evidence: relevance is not truth. Original owner reports, device observations and generated notes are distinct; recorded time does not establish validity. These are bounded ranked excerpts.]\n");
        JSONObject scan=diagnostics.optJSONObject("semanticScan"),ann=diagnostics.optJSONObject("semanticAnn"),structured=diagnostics.optJSONObject("structuredMemorySemantic");
        String footer="RETRIEVAL LIMITS candidateLimitReached="+diagnostics.optBoolean("candidateLimitReached")+" historyLimitReached="+diagnostics.optBoolean("historyLimitReached")+" semanticMode="+diagnostics.optString("semanticMode")+" sourceCoverage="+diagnostics.optDouble("sourceCoverage")+" semanticSearchLimitReached="+(scan!=null&&scan.optBoolean("limitReached"))+" approximateCandidateSelection="+(ann!=null&&ann.optBoolean("usedForThisSearch")&&ann.optBoolean("approximateNearestNeighbor"))+" semanticConstraintLimitReached="+(ann!=null&&ann.optBoolean("usedForThisSearch")&&ann.optBoolean("constraintCandidateLimitReached"))+".\n";
        footer+="PROFILE LIMITS pinnedCandidatesLimited="+diagnostics.optBoolean("pinnedCandidateLimitReached")+" pinnedReservationSaturated="+diagnostics.optBoolean("pinnedReservationSaturated")+". SUMMARY NEURAL LIMITS wholeCorpusSearched="+(structured!=null&&structured.optBoolean("wholeCorpusMemoryNeuralSearch"))+" shortlistLimited="+(structured!=null&&structured.optBoolean("candidateLimitReached"))+". Ranked sample only; completeRangeEnumerated=false; rangeEnumerationRequired="+diagnostics.optBoolean("rangeEnumerationRequired")+". Complete-range sum/count/average needs paged source enumeration. Unreturned evidence may still exist.\n";
        int contentEnd=Math.max(out.length(),budget-footer.length()-60),rawCount=Math.min(20,evidence.size()),omitted=evidence.size()-rawCount;
        boolean relevantRaw=false;for(JSONObject row:evidence)relevantRaw|=row.optBoolean("relevantToQuery");
        int available=Math.max(0,contentEnd-out.length()),rawBudget=rawCount==0?0:available*(relevantRaw?72:30)/100;
        int rawEnd=out.length()+rawBudget,textLimit=rawCount==0?0:Math.max(80,Math.min(1200,(rawBudget-rawCount*170)/rawCount));
        String expanded=MemoryText.expandedQuery(query);
        // Original evidence is packed first with a per-source allowance rather than letting generic notes evict it.
        for(int i=0;i<rawCount;i++){
            JSONObject row=evidence.get(i);String body=row.optString("excerpt"),lexical=row.optString("lexicalExcerpt"),guide=expanded+" "+row.optString("followupQueryTerm");
            if(!lexical.isEmpty()&&MemoryText.coverage(guide,lexical)>MemoryText.coverage(guide,body))body=lexical;
            String snippet=MemoryText.window(body,guide,textLimit);
            String line="ORIGINAL "+row.optString("type")+":"+row.optLong("id")+" hash="+row.optString("hash")+" role="+row.optString("role")+" recordedAt="+row.optLong("recordedAt")+" displayedExcerptOnly="+(!snippet.equals(body)||!row.optBoolean("exactOriginalRange"))+"\n"+snippet+"\n";
            if(out.length()+line.length()>rawEnd){omitted++;continue;}out.append(line);
        }
        List<JSONObject> ordered=new ArrayList<>(rows);ordered.sort((a,b)->{int value=Boolean.compare(b.optBoolean("relevantToQuery"),a.optBoolean("relevantToQuery"));if(value!=0)return value;value=Integer.compare(temporalRank(b),temporalRank(a));return value!=0?value:Double.compare(b.optDouble("retrievalScore"),a.optDouble("retrievalScore"));});
        for(JSONObject row:ordered){
            JSONArray refs=row.optJSONArray("sourceRefs"),few=new JSONArray();if(refs!=null)for(int i=0;i<Math.min(2,refs.length());i++)few.put(refs.opt(i));
            String line="MEMORY "+row.optString("key")+" revision="+row.optInt("revision")+" "+row.optString("temporalStatus")+" "+row.optString("epistemicStatus")+(row.optBoolean("pinned")?" PINNED":"")+" sources="+few+" sourceCount="+row.optInt("sourceCount",refs==null?0:refs.length())+" metadata="+MemoryText.window(MemoryPolicy.metadata(row).toString(),expanded,350)+"\n"+MemoryText.window(row.optString("content"),expanded,500)+"\n";
            if(out.length()+line.length()>contentEnd){omitted++;continue;}out.append(line);
        }
        String conflict="CONFLICTS "+conflicts+"\n";if(out.length()+conflict.length()<=contentEnd)out.append(conflict);else if(conflicts.length()>0&&out.length()+60<=contentEnd)out.append("CONFLICTS exist; memory.search returns versions and sources.\n");
        footer="omittedItems="+omitted+"\n"+footer;
        if(out.length()+footer.length()>budget){int bound=Math.max(0,budget-footer.length());if(bound>0&&Character.isHighSurrogate(out.charAt(bound-1)))bound--;out.setLength(Math.min(out.length(),bound));}
        out.append(footer);return out.length()<=budget?out.toString():out.substring(0,budget);
    }
    private static int temporalRank(JSONObject row){String state=row.optString("temporalStatus");return "valid_at_requested_time".equals(state)?2:"validity_unknown".equals(state)?1:0;}
    private static final class Candidate {JSONObject row;double score;boolean pinned,relevant;Candidate(JSONObject row){this.row=row;}}
}
