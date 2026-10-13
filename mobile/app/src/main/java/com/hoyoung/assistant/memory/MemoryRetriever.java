package com.hoyoung.assistant.memory;

import android.database.Cursor;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evidence-first hybrid retrieval, run on the app's I/O executor rather than the UI thread. */
public final class MemoryRetriever {
    private final HoyoungStore store;
    private final SemanticEncoder encoder;
    private final SemanticIndex semantic;
    private volatile boolean semanticEnabled=true;
    private volatile SemanticCandidateProvider candidateProvider;
    private volatile java.util.function.Predicate<JSONObject> deviceVisibility=event->true;
    public MemoryRetriever(HoyoungStore store) { this(store,null); }
    public MemoryRetriever(HoyoungStore store,SemanticEncoder encoder) { this.store=store;this.encoder=encoder;semantic=new SemanticIndex(store,encoder); }
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
        Map<String,Candidate> memories=new LinkedHashMap<>();Map<String,JSONObject> originals=new LinkedHashMap<>();
        JSONObject diagnostics=diagnostics();JSONArray missingSources=new JSONArray();boolean candidateCap=false,historyCap=false;
        JSONArray exact=store.retrieveMemoryCandidates(q.query,500),expanded=store.retrieveMemoryCandidates(q.expanded,500);
        candidateCap=exact.length()>=500||expanded.length()>=500;
        collect(memories,exact,1.0,false);collect(memories,expanded,0.8,false);collect(memories,store.retrievePinnedMemories(1000),0,true);
        JSONArray raw=store.rankedOriginalCandidates(q.query,q.from,q.to,500);
        candidateCap|=raw.length()>=500;addOriginals(originals,raw,q);
        JSONArray rawExpanded=store.rankedOriginalCandidates(q.expanded,q.from,q.to,500);
        candidateCap|=rawExpanded.length()>=500;addOriginals(originals,rawExpanded,q);
        JSONArray devices=store.rankedDeviceCandidates(q.query,q.from,q.to,500);
        candidateCap|=devices.length()>=500;addDeviceOriginals(originals,devices,q);
        if(!q.expanded.equals(q.query)){JSONArray deviceExpanded=store.rankedDeviceCandidates(q.expanded,q.from,q.to,500);candidateCap|=deviceExpanded.length()>=500;addDeviceOriginals(originals,deviceExpanded,q);}
        String semanticError="";JSONObject annDiagnostic=null;
        semantic.resetScanDiagnostics();
        if(semanticEnabled&&semantic.ready()&&!q.query.isEmpty()) try {
            float[] vector=VectorCodec.normalize(encoder.encode(q.query),encoder.dimensions());
            collect(memories,semantic.memories(vector,Math.max(50,q.limit*3)),1.0,false);
            JSONArray chunks;SemanticCandidateProvider provider=candidateProvider;
            if(provider!=null&&provider.isReady(encoder.modelId())){
                try{chunks=provider.chunks(vector,q,Math.max(30,q.limit*2),deviceVisibility);annDiagnostic=providerDiagnostic(provider);MemoryPolicy.put(annDiagnostic,"usedForThisSearch",true);}
                catch(Exception failed){chunks=semantic.chunks(vector,q,Math.max(30,q.limit*2));annDiagnostic=providerDiagnostic(provider);MemoryPolicy.put(annDiagnostic,"usedForThisSearch",false);MemoryPolicy.put(annDiagnostic,"fallback","paged_exact_cosine");MemoryPolicy.put(annDiagnostic,"error",failed.getClass().getSimpleName());}
            }else{chunks=semantic.chunks(vector,q,Math.max(30,q.limit*2));if(provider!=null){annDiagnostic=providerDiagnostic(provider);MemoryPolicy.put(annDiagnostic,"usedForThisSearch",false);MemoryPolicy.put(annDiagnostic,"fallback","paged_exact_cosine");MemoryPolicy.put(annDiagnostic,"fallbackReason","ann_cache_not_current_or_ready");}}
            for(int i=0;i<chunks.length();i++) {
                JSONObject chunk=chunks.optJSONObject(i);JSONObject source=source(chunk.optString("type"),chunk.optLong("id"));
                if(chunk.has("modelId")&&!encoder.modelId().equals(chunk.optString("modelId")))continue;
                if(source==null||!chunk.optString("hash").equals(source.optString("content_hash"))||!q.recordedInRange(source.optLong("message".equals(chunk.optString("type"))?"created_at":"observed_at")))continue;
                JSONObject evidence=evidence(source,chunk.optString("type"));
                JSONObject range=semantic.sourceRange(chunk.optString("type"),chunk.optLong("id"),chunk.optLong("offsetChars"),chunk.optInt("lengthChars"));
                if(!chunk.optString("hash").equals(range.optString("recordHash")))continue;
                if(!entitiesMatch(q.entities,range.optString("text")))continue;
                MemoryPolicy.put(evidence,"excerpt",range.optString("text"));MemoryPolicy.put(evidence,"offsetChars",range.optLong("offsetChars"));MemoryPolicy.put(evidence,"lengthChars",range.optString("text").length());MemoryPolicy.put(evidence,"exactOriginalRange",true);MemoryPolicy.put(evidence,"semanticSimilarity",chunk.optDouble("semanticSimilarity"));
                MemoryPolicy.put(evidence,"retrievalScore",chunk.optDouble("semanticSimilarity")*2);MemoryPolicy.put(evidence,"relevantToQuery",chunk.optDouble("semanticSimilarity")>=0.25);mergeSemantic(originals,evidence,i+1);
            }
        } catch(Exception unavailable) {semanticError=unavailable.getClass().getSimpleName();}
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
        for(Candidate c:ranked)if(selected.size()<q.limit+12&&!selected.contains(c.row)&&c.relevant)selected.add(c.row);
        List<JSONObject> history=new ArrayList<>();
        for(JSONObject row:selected) {
            JSONObject meta=MemoryPolicy.metadata(row);
            if(q.includeHistory||meta.has("factKey")||meta.has("validFrom")||meta.has("validTo")) {
                JSONObject historySlice=new MemoryHistory(store).select(MemoryPolicy.key(row),row.optInt("revision"),q,q.includeHistory?20:2);
                JSONArray revisions=historySlice.optJSONArray("rows");historyCap|=historySlice.optBoolean("limitReached");
                int included=0;
                for(int i=0;i<revisions.length();i++) {
                    JSONObject revision=revisions.optJSONObject(i);if(revision.optInt("revision")==row.optInt("revision")||!semantic.visibleMemory(MemoryPolicy.key(revision),revision.optInt("revision")))continue;
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
                String type=ref.optString("type");long id=ref.optLong("id");JSONObject source=source(type,id);
                if(source==null||!ref.optString("hash").equals(source.optString("content_hash"))) {missingSources.put(ref);invalidSource=true;continue;}
                String key=type+":"+id;if(!originals.containsKey(key)) {
                    String originalExcerpt=excerpt(source,type,q.query);if(!entitiesMatch(q.entities,originalExcerpt)||!q.recordedInRange(source.optLong("message".equals(type)?"created_at":"observed_at")))continue;
                    JSONObject evidence=evidence(source,type);MemoryPolicy.put(evidence,"linkedMemory",MemoryPolicy.key(row));MemoryPolicy.put(evidence,"excerpt",originalExcerpt);MemoryPolicy.put(evidence,"retrievalScore",row.optDouble("retrievalScore")+0.5);MemoryPolicy.put(evidence,"relevantToQuery",row.optBoolean("relevantToQuery"));originals.put(key,evidence);
                }
                read++;
            }
            if(invalidSource){MemoryPolicy.put(row,"epistemicStatus","source_integrity_unknown_do_not_treat_as_verified");MemoryPolicy.put(row,"sourceIntegrity","missing_or_changed");}
        }
        List<JSONObject> all=new ArrayList<>(selected);all.addAll(history);JSONArray conflicts=MemoryPolicy.conflicts(all);
        List<JSONObject> evidence=fuseOriginalRanks(originals,q.query);
        if(evidence.size()>q.limit*2)evidence=new ArrayList<>(evidence.subList(0,q.limit*2));
        boolean answerEvidence=false;for(JSONObject row:all)answerEvidence|=row.optBoolean("relevantToQuery")&&!"unsourced_unverified".equals(row.optString("epistemicStatus"));
        for(JSONObject row:evidence)answerEvidence|=row.optBoolean("relevantToQuery");
        MemoryPolicy.put(diagnostics,"candidateLimitReached",candidateCap);MemoryPolicy.put(diagnostics,"historyLimitReached",historyCap);MemoryPolicy.put(diagnostics,"missingOrChangedSourceRefs",missingSources);MemoryPolicy.put(diagnostics,"semanticError",semanticError);MemoryPolicy.put(diagnostics,"semanticScan",semantic.scanDiagnostics());if(annDiagnostic!=null)MemoryPolicy.put(diagnostics,"semanticAnn",annDiagnostic);MemoryPolicy.put(diagnostics,"sourceRanking","reciprocal-rank-fusion-k60-plus-exact-identifier");MemoryPolicy.put(diagnostics,"noEvidenceIsNotProofOfAbsence",true);MemoryPolicy.put(diagnostics,"relevanceIsNotTruthConfidence",true);
        JSONObject result=new JSONObject();MemoryPolicy.put(result,"memories",new JSONArray(selected));MemoryPolicy.put(result,"history",new JSONArray(history));MemoryPolicy.put(result,"originalEvidence",new JSONArray(evidence));MemoryPolicy.put(result,"conflicts",conflicts);MemoryPolicy.put(result,"hasRelevantEvidence",answerEvidence);MemoryPolicy.put(result,"diagnostics",diagnostics);
        MemoryPolicy.put(result,"context",format(all,evidence,conflicts,diagnostics,q.maxChars,q.query));return result;
    }
    private static JSONObject providerDiagnostic(SemanticCandidateProvider provider){try{return new JSONObject(provider.diagnostics().toString());}catch(Exception failure){JSONObject state=new JSONObject();MemoryPolicy.put(state,"status","diagnostics_unavailable");MemoryPolicy.put(state,"error",failure.getClass().getSimpleName());return state;}}
    private void collect(Map<String,Candidate> result,JSONArray rows,double weight,boolean pinned) {
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;String key=MemoryPolicy.key(row);if(!semantic.visibleMemory(key,row.optInt("revision")))continue;Candidate c=result.get(key);
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
    private void addOriginals(Map<String,JSONObject> out,JSONArray rows,MemoryQuery query) {
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);JSONObject evidence=evidence(row,"message");String excerpt=excerpt(row,"message",query.query);if(!entitiesMatch(query.entities,excerpt))continue;
            double coverage=MemoryText.coverage(query.query,excerpt);MemoryPolicy.put(evidence,"excerpt",excerpt);double score=coverage*3+row.optDouble("score")/1000;MemoryPolicy.put(evidence,"retrievalScore",score);MemoryPolicy.put(evidence,"lexicalScore",score);MemoryPolicy.put(evidence,"relevantToQuery",coverage>0||(!query.query.isEmpty()&&row.optDouble("score")>0));out.putIfAbsent("message:"+row.optLong("id"),evidence);
        }
    }
    private void addDeviceOriginals(Map<String,JSONObject> out,JSONArray rows,MemoryQuery query){
        for(int i=0;i<rows.length();i++){JSONObject candidate=rows.optJSONObject(i);long id=candidate.optLong("id");String key="device_event:"+id;if(out.containsKey(key))continue;JSONObject original=source("device_event",id);if(original==null||!candidate.optString("content_hash").equals(original.optString("content_hash")))continue;String excerpt=excerpt(original,"device_event",query.query);if(!entitiesMatch(query.entities,excerpt))continue;JSONObject evidence=evidence(original,"device_event");double coverage=MemoryText.coverage(query.query,excerpt);MemoryPolicy.put(evidence,"excerpt",excerpt);MemoryPolicy.put(evidence,"originalChars",candidate.optLong("payloadChars"));double score=coverage*3+candidate.optDouble("score")/1000;MemoryPolicy.put(evidence,"retrievalScore",score);MemoryPolicy.put(evidence,"lexicalScore",score);MemoryPolicy.put(evidence,"relevantToQuery",coverage>0||(!query.query.isEmpty()&&candidate.optDouble("score")>0));out.put(key,evidence);}
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
        for(JSONObject row:rows){double score=0;if(row.has("lexicalRank"))score+=1.0/(60+row.optInt("lexicalRank"));if(row.has("semanticRank"))score+=1.0/(60+row.optInt("semanticRank"));if(row.has("linkedMemory"))score+=1.0/61;
            if(identifier&&(MemoryText.normalize(row.optString("excerpt")).contains(exact)||MemoryText.normalize(row.optString("lexicalExcerpt")).contains(exact)))score+=0.04;
            MemoryPolicy.put(row,"retrievalScore",score);MemoryPolicy.put(row,"rankingMethod","RRF(k=60);scores_are_not_truth_confidence");
        }
        rows.sort((a,b)->Double.compare(b.optDouble("retrievalScore"),a.optDouble("retrievalScore")));return rows;
    }
    private JSONObject source(String type,long id) {return "message".equals(type)?store.getMessage(id):"device_event".equals(type)&&semantic.visibleDevice(id)?store.getDeviceEvent(id):null;}
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
    private static String format(List<JSONObject> rows,List<JSONObject> evidence,JSONArray conflicts,JSONObject diagnostics,int budget,String query) {
        StringBuilder out=new StringBuilder("[Persistent memory evidence: relevance does not prove truth. Owner statements/device observations/generated summaries are separate. Recorded time does not establish a fact's validity. If evidence is insufficient, retrieve source.read/memory.search; do not invent.]\n");
        int omitted=0;int memoryBudget=Math.max(600,budget*60/100),pinnedUsed=0;
        List<JSONObject> ordered=new ArrayList<>(rows);ordered.sort((a,b)->{int relevant=Boolean.compare(b.optBoolean("relevantToQuery"),a.optBoolean("relevantToQuery"));return relevant!=0?relevant:Double.compare(b.optDouble("retrievalScore"),a.optDouble("retrievalScore"));});
        for(JSONObject row:ordered){JSONArray refs=row.optJSONArray("sourceRefs"),few=new JSONArray();if(refs!=null)for(int i=0;i<Math.min(5,refs.length());i++)few.put(refs.opt(i));String line="MEMORY "+row.optString("key")+" revision="+row.optInt("revision")+" "+row.optString("temporalStatus")+" "+row.optString("epistemicStatus")+(row.optBoolean("pinned")?" PINNED":"")+" sources="+few+" sourceCount="+(refs==null?0:refs.length())+" metadata="+MemoryText.window(MemoryPolicy.metadata(row).toString(),query,800)+"\n"+MemoryText.window(row.optString("content"),query,1200)+"\n";boolean profile=row.optBoolean("pinned")&&!row.optBoolean("relevantToQuery");if(out.length()+line.length()>memoryBudget||(profile&&pinnedUsed+line.length()>budget/4)){omitted++;continue;}out.append(line);if(profile)pinnedUsed+=line.length();}
        String conflictLine="CONFLICTS "+conflicts+"\n";if(out.length()+conflictLine.length()<budget-500)out.append(conflictLine);else if(conflicts.length()>0)out.append("CONFLICTS exist; memory.search returns both versions and sources.\n");
        for(JSONObject row:evidence){String extra=row.optString("lexicalExcerpt");String line="ORIGINAL "+row.optString("type")+":"+row.optLong("id")+" hash="+row.optString("hash")+" role="+row.optString("role")+" recordedAt="+row.optLong("recordedAt")+(row.optBoolean("exactOriginalRange")?" range="+row.optLong("offsetChars")+"+"+row.optInt("lengthChars"):" excerpt_with_approximate_match_markers")+"\n"+row.optString("excerpt")+(extra.isEmpty()||extra.equals(row.optString("excerpt"))?"":"\nlexical_excerpt: "+MemoryText.window(extra,query,800))+"\n";if(out.length()+line.length()>budget-450){omitted++;continue;}out.append(line);}
        JSONObject scan=diagnostics.optJSONObject("semanticScan"),ann=diagnostics.optJSONObject("semanticAnn");String footer="RETRIEVAL LIMITS omittedItems="+omitted+" candidateLimitReached="+diagnostics.optBoolean("candidateLimitReached")+" historyLimitReached="+diagnostics.optBoolean("historyLimitReached")+" semanticMode="+diagnostics.optString("semanticMode")+" sourceCoverage="+diagnostics.optDouble("sourceCoverage")+" semanticSearchLimitReached="+(scan!=null&&scan.optBoolean("limitReached"))+" approximateCandidateSelection="+(ann!=null&&ann.optBoolean("usedForThisSearch")&&ann.optBoolean("approximateNearestNeighbor"))+" semanticConstraintLimitReached="+(ann!=null&&ann.optBoolean("usedForThisSearch")&&ann.optBoolean("constraintCandidateLimitReached"))+". Unreturned evidence may still exist; original sources remain stored.\n";
        if(out.length()+footer.length()>budget)out.setLength(Math.max(0,budget-footer.length()));out.append(footer);return out.toString();
    }
    private static final class Candidate {JSONObject row;double score;boolean pinned,relevant;Candidate(JSONObject row){this.row=row;}}
}
