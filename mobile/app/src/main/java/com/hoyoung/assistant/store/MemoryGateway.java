package com.hoyoung.assistant.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.hoyoung.assistant.memory.MemoryText;
import com.hoyoung.assistant.memory.MemoryQuery;
import com.hoyoung.assistant.runtime.GoalLedger;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Native five-layer reads and source-bound context. No model or indexing work runs here. */
public final class MemoryGateway {
    public static final String[] LAYERS = {"verbatim", "semantic", "episodic", "procedural", "active"};
    private static final long MAX_AGE_MS = 600_000L;
    private static final int MAX_LAYER_CANDIDATES = 16, MAX_HYBRID_IDS = 32, MAX_SOURCE_LINKS = 128;
    private static final String[] POLICY_KEYS = {"customInstructions", "personalizedInstructions", "standingGoals", "toolPermissions",
            "phoneContextEnabled", "semanticSearchEnabled", "goalLedgerRevision",
            "ownerInputRevision", "latestOwnerRequestId", "memory.privacyEpoch", "memory.correctionEpoch", "memory.permissionEpoch", "memory.controlEpoch"};
    private final HoyoungStore store;
    private final SourcePrivacyLedger privacy;
    private volatile java.util.function.Predicate<JSONObject> deviceVisibility=source->false;

    public MemoryGateway(HoyoungStore store) { this(store, store==null?null:store.getPrivacyLedger()); }
    public MemoryGateway(HoyoungStore store, SourcePrivacyLedger privacy) {
        if (store == null) throw new IllegalArgumentException("A canonical store is required");
        this.store = store; this.privacy = privacy==null?store.getPrivacyLedger():privacy;
    }
    public MemoryGateway(HoyoungStore store,SourcePrivacyLedger privacy,java.util.function.Predicate<JSONObject> visibility){this(store,privacy);setDeviceSourceVisibility(visibility);}
    public void setDeviceSourceVisibility(java.util.function.Predicate<JSONObject> visibility){deviceVisibility=visibility==null?source->false:visibility;}

    public static final class Blocked extends IllegalStateException {
        public final String reason;
        public Blocked(String reason) { super(reason); this.reason = reason; }
        public Blocked(String reason, Throwable cause) { super(reason, cause); this.reason = reason; }
    }

    /** All JSON is serialized privately; callers receive copies and cannot alter an admitted read. */
    public static final class Prepared {
        public final String context, snapshotId, controlFingerprint, evidenceFingerprint, dispatchId, rootId, purpose;
        public final long inputId, latestOwnerInputId, preparedAt, validUntil, temporalValidUntil;
        private final String reads, dependencies, control, privacyFingerprint, diagnostics;
        private Prepared(String context, String snapshotId, String controlFingerprint, String dispatchId, String rootId,
                         String purpose, long inputId, long latestOwnerInputId, long now, long validUntil,long temporalValidUntil,
                         JSONArray reads, JSONArray dependencies, JSONObject control, String privacyFingerprint, JSONObject diagnostics) {
            this.context=context; this.snapshotId=snapshotId; this.controlFingerprint=controlFingerprint;
            this.dispatchId=dispatchId; this.rootId=rootId; this.purpose=purpose; this.inputId=inputId;
            this.latestOwnerInputId=latestOwnerInputId; this.preparedAt=now; this.validUntil=validUntil;this.temporalValidUntil=temporalValidUntil;
            this.reads=reads.toString(); this.dependencies=dependencies.toString(); this.control=control.toString();
            this.privacyFingerprint=privacyFingerprint; this.diagnostics=diagnostics.toString();
            this.evidenceFingerprint=digest(canonical(object("context",context,"receipts",reads,"dependencies",dependencies,
                    "control",control,"privacyFingerprint",privacyFingerprint,"validUntil",validUntil)));
        }
        public JSONArray receipts() { return array(reads); }
        public JSONArray dependencies() { return array(dependencies); }
        public JSONObject diagnostics() { return json(diagnostics); }
    }

    public Prepared prepare(String dispatchId,String rootId,String purpose,long inputId,String currentInputText,
                            JSONObject hybridCandidates,int maxChars) {
        return prepare(dispatchId,rootId,purpose,inputId,currentInputText,hybridCandidates,maxChars,System.currentTimeMillis());
    }

    public Prepared prepare(String dispatchId,String rootId,String purpose,long inputId,String currentInputText,
                            JSONObject hybridCandidates,int maxChars,long now) {
        return prepare(dispatchId,rootId,purpose,inputId,currentInputText,hybridCandidates,maxChars,now,null);
    }

    /**
     * The optional proof names only effective setting text already rendered, in full, in the
     * same final prompt. Matching is byte-for-byte against this native read snapshot; it does
     * not authorize truncation, replace any source, or change the native read receipts.
     */
    public Prepared prepare(String dispatchId,String rootId,String purpose,long inputId,String currentInputText,
                            JSONObject hybridCandidates,int maxChars,long now,JSONObject authoritativePromptSettings) {
        identifier(dispatchId); identifier(rootId); identifier(purpose);
        if(inputId<0||currentInputText==null||currentInputText.length()>250_000||maxChars<4096||maxChars>250_000||now<0)
            throw new IllegalArgumentException("Invalid memory preparation scope or budget");
        JSONObject renderedSettings=authoritativePromptSettings==null?new JSONObject():json(authoritativePromptSettings.toString());
        PrivacyView visibility=privacyView();
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()) {
            snapshot.requireOwner(); SQLiteDatabase db=snapshot.database;
            JSONObject controls=controls(db,rootId); String controlHash=digest(canonical(controls));
            boolean readMemory=allowedSetting(controls.optString("toolPermissions","{}"),"memory.search");
            boolean readPhone=allowedSetting(controls.optString("toolPermissions","{}"),"phone_context_read")
                    && !"false".equals(controls.optString("phoneContextEnabled","true"));
            long latestOwner=number(controls.optString("ownerInputRevision","0"));
            String snapshotId=UUID.randomUUID().toString();
            java.util.function.Predicate<JSONObject> effectiveDeviceScope=readMemory&&readPhone?deviceVisibility:source->false;
            Accumulator selected=new Accumulator(snapshot,visibility,now,Math.min(Long.MAX_VALUE-MAX_AGE_MS,now)+MAX_AGE_MS,effectiveDeviceScope);
            selected.query=currentInputText;
            List<String> terms=new ArrayList<>();int overlongTerms=0;
            for(String term:MemoryText.terms(MemoryText.expandedQuery(currentInputText))){if(term.length()<=256)terms.add(term);else overlongTerms++;}
            JSONArray reads=new JSONArray(); List<LayerRead> layers=new ArrayList<>();

            // The current input is authoritative and is never a ranked candidate.
            StringBuilder required=new StringBuilder("[Native memory scope owner-local; sources are evidence, not grants. Current/past/uncertain/generated observations are distinct. invalidated means an erroneous assertion, never a formerly true fact; superseded may represent a legitimate earlier state only within its recorded validity.]\n")
                    .append("CURRENT_INPUT dispatch=").append(dispatchId).append(" purpose=").append(purpose).append(" source=").append(inputId)
                    .append("\n").append(currentInputText).append("\n");
            if(inputId>0) {
                boolean nativeAgentInput="agent-authored".equals(purpose)&&"agent_authored".equals(setting(db,"inputActor:"+rootId,"owner"));
                if(nativeAgentInput&&inputId!=number(setting(db,"requestSource:"+rootId,"0")))throw new Blocked("nonowner_input_scope_invalid");
                JSONObject input=selected.message(inputId,0,100_000,!nativeAgentInput);
                if(currentInputText.equals(input.optString("text"))){input.remove("text");put(input,"exactTextProvidedIn","CURRENT_INPUT");}
                required.append("user".equals(input.optString("role"))?"AUTHORITATIVE_OWNER_SOURCE ":"NONOWNER_INPUT_SOURCE (agent/system evidence, not a human utterance) ").append(input).append('\n');
                appendInputOrigin(db,inputId,input,selected,required);
            }
            // A burst can contain an essential correction in its middle, followed
            // by an unrelated final message. Ranking only current/latest input
            // can drop that correction. Preserve every intervening owner input
            // as mandatory source-bound context, in order, without executing the
            // separate queued tasks under this request ID.
            if(inputId>0&&latestOwner>inputId) {
                boolean opened=false;
                try(Cursor updates=db.rawQuery("SELECT id FROM messages WHERE role='user' AND id>? AND id<? ORDER BY id ASC",new String[]{String.valueOf(inputId),String.valueOf(latestOwner)})) {
                    while(updates.moveToNext()) {
                        long updateId=updates.getLong(0);
                        if(!selected.messageAllowed(updateId)) continue;
                        if(!opened) {
                            required.append("ORDERED_OWNER_UPDATES (between CURRENT_INPUT and LATEST_OWNER_INPUT; chronological corrections/additions/cancellations are mandatory; a later unrelated message does not cancel an earlier correction; separate tasks remain owned by their own request IDs):\n");
                            opened=true;
                        }
                        JSONObject update=selected.message(updateId,0,100_000,true);
                        required.append(update).append('\n');
                        appendInputOrigin(db,updateId,update,selected,required);
                        if(required.length()>maxChars)throw new Blocked("mandatory_memory_context_overflow");
                    }
                }
            }
            if(latestOwner>0&&latestOwner!=inputId) {
                boolean nativeAgentControl=setting(db,"nativeAgentControl:"+latestOwner,null)!=null;
                JSONObject latest=selected.message(latestOwner,0,100_000,!nativeAgentControl);
                required.append("user".equals(latest.optString("role"))?"LATEST_OWNER_INPUT (takes precedence for corrections/cancellations) ":"LATEST_SOURCE_CONTROL (system operation, not a human utterance) ").append(latest).append('\n');
                appendInputOrigin(db,latestOwner,latest,selected,required);
            }
            LayerRead verbatim=verbatim(db,terms,readMemory,readPhone,visibility,hybridCandidates,selected);
            layers.add(verbatim);
            layers.add(memories(db,"semantic",terms,readMemory,visibility,hybridCandidates,selected));
            layers.add(memories(db,"episodic",terms,readMemory,visibility,hybridCandidates,selected));
            layers.add(procedural(db,terms,readMemory,visibility,hybridCandidates,selected,controls));
            LayerRead active=active(db,rootId,visibility,selected);
            layers.add(active); required.append("MANDATORY_ACTIVE_CONSTRAINTS ").append(active.payload).append('\n');
            if(required.length()>maxChars)throw new Blocked("mandatory_memory_context_overflow");

            StringBuilder context=required; int omitted=0;List<OptionalLine> optional=new ArrayList<>();
            JSONArray duplicateSettingProjections=new JSONArray();
            for(LayerRead layer:layers) {
                if(!"active".equals(layer.layer)) for(int i=0;i<layer.payload.length();i++) {
                    JSONObject modelItem=json(layer.payload.optJSONObject(i).toString());
                    if("verbatim".equals(layer.layer)&&"message".equals(modelItem.optString("type"))&&modelItem.has("text"))modelItem=compactOriginal(modelItem);
                    if(!"verbatim".equals(layer.layer)&&modelItem.has("hydratedSourceExamples")){modelItem.remove("hydratedSourceExamples");put(modelItem,"sourcesHydratedAndValidatedByNative",true);put(modelItem,"rawSourceTextAccess","VERBATIM_or_native_source_read");}
                    String line=layer.layer.toUpperCase(java.util.Locale.ROOT)+" "+modelItem+"\n";
                    if("procedural".equals(layer.layer)) {
                        String providedIn=duplicateSettingLocation(modelItem,active.payload,controls,renderedSettings);
                        if(!providedIn.isEmpty()) {
                            duplicateSettingProjections.put(object("settingKey",modelItem.optString("setting_key"),
                                    "exactTextProvidedIn",providedIn,"projectionCharsAvoided",line.length()));
                            continue;
                        }
                    }
                    optional.add(new OptionalLine(line,i,"verbatim".equals(layer.layer)?0:"semantic".equals(layer.layer)?1:"episodic".equals(layer.layer)?2:3));
                }
                reads.put(receipt(layer,dispatchId,rootId,purpose,inputId,latestOwner,snapshotId,controls,visibility,now,selected.validUntil));
            }
            optional.sort((a,b)->a.rank!=b.rank?Integer.compare(a.rank,b.rank):Integer.compare(a.lane,b.lane));
            JSONObject diagnostics=object("fiveLayerQueries",layers.size(),"snapshotBackend",android.os.Build.VERSION.SDK_INT>=35?"android_readonly_transaction":"readonly_connection_savepoint",
                    "omittedOptionalItems",optional.size(),"projectionState",projectionState(db),"coverageMeaning","canonical_query_scope_at_snapshot_not_all_history_read_by_model",
                    "overlongRetrievalTermsExcluded",overlongTerms,"authoritativeInputPreserved",true,"sourceGraphRead",selected.sourceVisibility.diagnostics(),
                    "hybridCandidatesAreUntrustedUntilHydrated",true,"modelInternalUseVerified",false);
            int footerReserve=("MEMORY_LIMITS "+diagnostics+"\n").length();
            int sourceAllowance=Math.max(0,(maxChars-context.length()-footerReserve)*2/3),sourceUsed=0;
            java.util.Set<OptionalLine> packedSources=new java.util.HashSet<>();
            for(OptionalLine line:optional)if(line.lane==0&&sourceUsed+line.text.length()<=sourceAllowance&&context.length()+line.text.length()+footerReserve<=maxChars){context.append(line.text);sourceUsed+=line.text.length();packedSources.add(line);}
            optional.removeIf(packedSources::contains);
            for(OptionalLine line:optional){if(context.length()+line.text.length()+footerReserve>maxChars){omitted++;continue;}context.append(line.text);}
            put(diagnostics,"omittedOptionalItems",omitted);
            String footer="MEMORY_LIMITS "+diagnostics+"\n";
            if(context.length()+footer.length()>maxChars)throw new Blocked("memory_context_diagnostics_overflow");
            context.append(footer);
            // Savings receipts are native audit data, not another repeated model payload.
            if(duplicateSettingProjections.length()>0)put(diagnostics,"duplicateSettingProjections",duplicateSettingProjections);
            if(!visibility.fingerprint.equals(privacyView().fingerprint))throw new Blocked("privacy_changed_during_prepare");
            return new Prepared(context.toString(),snapshotId,controlHash,dispatchId,rootId,purpose,inputId,latestOwner,now,selected.validUntil,selected.temporalValidUntil,
                    reads,selected.dependencies(),controls,visibility.fingerprint,diagnostics);
        }catch(Blocked failure){throw failure;}catch(RuntimeException|IOException unavailable){throw new Blocked("memory_query_unavailable",unavailable);}
    }

    /** Intended for the native I/O executor, before the already-prepared final wire/output/action is released. */
    public void assertFresh(Prepared prepared) { assertFresh(prepared,System.currentTimeMillis()); }
    public void assertFresh(Prepared prepared,long now) {
        assertFresh(prepared,now,true);
    }
    public void assertFreshOutput(Prepared prepared){assertFresh(prepared,System.currentTimeMillis(),false);}
    public void assertFresh(Prepared prepared,long now,boolean requireAdmissionTtl) {
        assertFresh(prepared,now,requireAdmissionTtl,false);
    }
    /** Only permits retaining old output for a fresh steering turn. This does not
     * authorize releasing its reply, promoting memories, or executing its actions. */
    public void assertFreshForOwnerRebase(Prepared prepared,long now) {
        assertFresh(prepared,now,false,true);
    }
    private void assertFresh(Prepared prepared,long now,boolean requireAdmissionTtl,boolean allowNewOwnerInput) {
        if(prepared==null||now<prepared.preparedAt||(requireAdmissionTtl&&now>=prepared.validUntil))throw new Blocked("memory_context_expired");
        if(now>=prepared.temporalValidUntil)throw new Blocked("memory_temporal_boundary_changed");
        PrivacyView visibility=privacyView();
        if(!prepared.privacyFingerprint.equals(visibility.fingerprint))throw new Blocked("memory_privacy_changed");
        try(HoyoungStore.ReadSnapshot snapshot=store.openReadSnapshot()) {
            SQLiteDatabase db=snapshot.database;
            JSONObject currentControls=controls(db,prepared.rootId),previousControls=json(prepared.control);
            if(allowNewOwnerInput)for(String key:new String[]{"ownerInputRevision","latestOwnerRequestId"}) {
                currentControls.remove(key);previousControls.remove(key);
            }
            if(!canonical(previousControls).equals(canonical(currentControls)))throw new Blocked("memory_control_changed");
            JSONArray dependencies=prepared.dependencies();
            Accumulator checker=new Accumulator(snapshot,visibility,now,prepared.validUntil,deviceVisibility);
            for(int i=0;i<dependencies.length();i++) {
                JSONObject dependency=dependencies.optJSONObject(i);
                String type=dependency.optString("type");
                if("message".equals(type)) {
                    JSONObject current=checker.message(dependency.optLong("id"),dependency.optLong("offsetChars"),dependency.optInt("lengthChars"),false);
                    if(!dependency.optString("hash").equals(current.optString("recordHash"))||!dependency.optString("rangeHash").equals(digest(current.optString("text"))))throw new Blocked("memory_source_changed");
                }else if("device_event".equals(type)) {
                    JSONObject current=checker.device(dependency.optLong("id"));
                    if(!dependency.optString("hash").equals(current.optString("hash")))throw new Blocked("memory_source_changed");
                }else if("memory".equals(type)) {
                    JSONObject current=memoryRow(db,dependency.optString("key"));
                    if(current==null||dependency.optLong("revision")!=current.optLong("revision")||!dependency.optString("hash").equals(current.optString("content_hash")))throw new Blocked("memory_revision_changed");
                    checker.validateMemory(current,false);
                }else if("memory_revision".equals(type)) {
                    JSONObject current=checker.historical(dependency.optString("key"),dependency.optLong("revision"),false);
                    if(current==null||!dependency.optString("hash").equals(current.optString("snapshotEvidenceHash")))throw new Blocked("memory_revision_changed");
                }else if("goal_source_setting".equals(type)||"native_account_projection".equals(type)) {
                    JSONObject current=one(db,"SELECT value FROM settings WHERE setting_key=?",new String[]{dependency.optString("key")});
                    if(current==null||!dependency.optString("hash").equals(digest(current.optString("value"))))throw new Blocked("memory_control_changed");
                }else throw new Blocked("memory_dependency_invalid");
            }
            if(!visibility.fingerprint.equals(privacyView().fingerprint))throw new Blocked("memory_privacy_changed");
        }catch(Blocked failure){throw failure;}catch(RuntimeException|IOException unavailable){throw new Blocked("memory_freshness_unavailable",unavailable);}
    }

    private LayerRead verbatim(SQLiteDatabase db,List<String> terms,boolean enabled,boolean phone,PrivacyView privacy,
                               JSONObject hybrid,Accumulator selected)throws IOException {
        LayerRead result=new LayerRead("verbatim");
        Map<String,JSONObject> candidates=new LinkedHashMap<>();
        Map<String,Long> weights=new LinkedHashMap<>();
        List<TermLane> termLanes=new ArrayList<>();boolean capped=false;
        // FTS is a disposable candidate index. Every selected row is still hydrated and
        // verified against the canonical source in this same SQLite read view.
        for(String term:terms){
            String match=ftsMatch(term);if(match.isEmpty())continue;
            JSONArray messageHits=query(db,"SELECT docid AS id FROM message_search WHERE "+(enabled?"message_search MATCH ?":"0")+" LIMIT 65",
                    enabled?java.util.Arrays.asList(match):Collections.emptyList(),result);
            JSONArray deviceHits=query(db,"SELECT docid AS id FROM device_event_search WHERE "+(enabled&&phone?"device_event_search MATCH ?":"0")+" LIMIT 65",
                    enabled&&phone?java.util.Arrays.asList(match):Collections.emptyList(),result);
            int observed=messageHits.length()+deviceHits.length();boolean laneCapped=messageHits.length()>64||deviceHits.length()>64;
            long weight=laneCapped?1:Math.max(2,1024/(1+observed));weights.put(term,weight);
            termLanes.add(new TermLane(messageHits,deviceHits,weight));capped|=laneCapped;
        }
        // Rare owner terms reserve candidates before common query words consume the cap.
        termLanes.sort((a,b)->Long.compare(b.weight,a.weight));
        for(TermLane lane:termLanes){
            for(int i=0;i<Math.min(64,lane.messages.length());i++)addCandidate(candidates,"message",lane.messages.optJSONObject(i).optLong("id"),0,0,512);
            for(int i=0;i<Math.min(64,lane.devices.length());i++)addCandidate(candidates,"device_event",lane.devices.optJSONObject(i).optLong("id"),0,0,512);
        }
        if(enabled&&hybrid!=null){JSONArray nativeCandidates=hybrid.optJSONArray("originalEvidence");
            for(int i=0;nativeCandidates!=null&&i<Math.min(MAX_HYBRID_IDS,nativeCandidates.length());i++){
                JSONObject candidate=nativeCandidates.optJSONObject(i);if(candidate==null)continue;String type=candidate.optString("type");long id=candidate.optLong("id");
                if(!"message".equals(type)&&!(phone&&"device_event".equals(type)))continue;
                JSONObject source=one(db,"SELECT id,content_hash"+("message".equals(type)?",raw_char_count":"")+" FROM "+("message".equals(type)?"messages":"device_events")+" WHERE id=?",new String[]{String.valueOf(id)});
                if(source!=null&&candidate.optString("hash").equals(source.optString("content_hash"))&&privacy.allowed(type,id,source.optString("content_hash"))){
                    long offset=0;Object candidateOffset=candidate.opt("offsetChars");if(candidate.optBoolean("exactOriginalRange")&&candidateOffset instanceof Number&&((Number)candidateOffset).doubleValue()==((Number)candidateOffset).longValue()&&((Number)candidateOffset).longValue()>=0&&((Number)candidateOffset).longValue()<source.optLong("raw_char_count"))offset=((Number)candidateOffset).longValue();
                    addCandidate(candidates,type,id,offset,MAX_HYBRID_IDS-i,544);
                }
            }
        }
        // Bootstrap device rows have an honest canonical lane until their FTS index catches up.
        long[] deviceState=DeviceEventSearch.state(db);
        if(enabled&&phone&&deviceState[2]==0){List<String> args=new ArrayList<>();String predicate=matching("payload",terms,args);
            args.add(0,String.valueOf(deviceState[1]));args.add(0,String.valueOf(deviceState[0]));
            JSONArray pending=query(db,"SELECT id FROM device_events WHERE id>? AND id<=? AND "+privacy.sql("device_event","id","content_hash")+" AND "+predicate+" ORDER BY id LIMIT 64",args,result);
            for(int i=0;i<pending.length();i++)addCandidate(candidates,"device_event",pending.optJSONObject(i).optLong("id"),0,0,608);capped|=pending.length()==64;
        }
        // Substring-only names may not be token prefixes. An empty index result is not
        // proof of absence; preserve the actual canonical literal fallback query.
        if(enabled&&candidates.isEmpty()&&!terms.isEmpty()){
            List<String> args=new ArrayList<>();String predicate=matching("text",terms,args);
            JSONArray literal=query(db,"SELECT id FROM messages WHERE "+privacy.sql("message","id","content_hash")+" AND "+predicate+" ORDER BY id DESC LIMIT 24",args,result);
            for(int i=0;i<literal.length();i++)addCandidate(candidates,"message",literal.optJSONObject(i).optLong("id"),0,0,608);
        }
        Map<String,Long> scores=new LinkedHashMap<>();
        for(String type:new String[]{"message","device_event"}){
            List<String> ids=new ArrayList<>();for(JSONObject candidate:candidates.values())if(type.equals(candidate.optString("type")))ids.add(String.valueOf(candidate.optLong("id")));
            List<String> args=new ArrayList<>();String expression="message".equals(type)?"COALESCE(search_doc.text,m.text)":"m.payload";
            String score=weightedRanking(expression,weights,args);StringBuilder placeholders=new StringBuilder();for(String id:ids){if(placeholders.length()>0)placeholders.append(',');placeholders.append('?');args.add(id);}
            String from="message".equals(type)?"messages m LEFT JOIN message_search search_doc ON search_doc.docid=m.id":"device_events m";
            JSONArray rows=query(db,"SELECT m.id,("+score+") AS relevance FROM "+from+" WHERE "+privacy.sql(type,"m.id","m.content_hash")+" AND "+(ids.isEmpty()?"0":"m.id IN ("+placeholders+")"),args,result);
            for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);scores.put(type+":"+row.optLong("id"),row.optLong("relevance"));}
        }
        List<JSONObject> ranked=new ArrayList<>(candidates.values());
        ranked.sort((a,b)->{int relevance=Long.compare(scores.getOrDefault(b.optString("type")+":"+b.optLong("id"),0L),scores.getOrDefault(a.optString("type")+":"+a.optLong("id"),0L));if(relevance!=0)return relevance;int hybridOrder=Integer.compare(b.optInt("hybridPriority"),a.optInt("hybridPriority"));return hybridOrder!=0?hybridOrder:Long.compare(b.optLong("id"),a.optLong("id"));});
        Set<String> emitted=new LinkedHashSet<>();
        for(JSONObject source:ranked){long id=source.optLong("id");String type=source.optString("type"),key=type+":"+id;
            if(!scores.containsKey(key))continue;
            if("message".equals(type)){if(!selected.authoritativeIds.contains(id)&&selected.messageAllowed(id)){
                long offset=source.optLong("offsetChars");if(offset==0)offset=sourceWindowOffset(db,id,weights);
                result.payload.put(selected.message(id,offset,1200,false));emitted.add(key);
            }}else if(selected.deviceAllowed(id)){result.payload.put(selected.device(id));emitted.add(key);}
            if(emitted.size()>=64){capped|=ranked.size()>emitted.size();break;}
        }
        JSONArray recent=query(db,"SELECT id FROM messages WHERE "+privacy.sql("message","id","content_hash")+" AND "+(enabled?"1":"0")+" ORDER BY id DESC LIMIT 8",Collections.emptyList(),result);
        for(int i=0;i<recent.length();i++){long id=recent.optJSONObject(i).optLong("id");if(!emitted.contains("message:"+id)&&!selected.authoritativeIds.contains(id)&&selected.messageAllowed(id))result.payload.put(selected.recentMessage(id));}
        result.candidateScope=object("backend","snapshot_fts_ids_plus_canonical_hydration","perTermPerTypeLimit",64,"candidateIds",candidates.size(),"candidateLimitReached",capped,
                "ranking","capped_term_rarity_plus_canonical_literal_matches_then_hybrid_rank","deviceBootstrapComplete",deviceState[2]!=0,"completeHistoryEnumerated",false);
        for(long id:selected.authoritativeIds){
            JSONArray authoritative=query(db,"SELECT id,content_hash FROM messages WHERE id=?",java.util.Arrays.asList(String.valueOf(id)),result);
            if(authoritative.length()!=1)throw new Blocked("authoritative_input_source_invalid");
            result.payload.put(object("type","message","id",id,"hash",authoritative.optJSONObject(0).optString("content_hash"),"exactTextProvidedIn","CURRENT_INPUT_OR_LATEST_OWNER_INPUT","mandatory",true));
        }
        result.exclusion=enabled?phone?"none":"phone_context_excluded_by_owner":"memory_search_excluded_by_owner";
        return result;
    }

    private LayerRead memories(SQLiteDatabase db,String layer,List<String> terms,boolean enabled,PrivacyView privacy,
                               JSONObject hybrid,Accumulator selected)throws IOException {
        LayerRead result=new LayerRead(layer); List<String> args=new ArrayList<>();
        String kinds="semantic".equals(layer)?"('fact','preference','uncertain','inference','opinion')":"('summary','generated_summary','project','task','observation','episodic','event')";
        String document="m.memory_key||' '||m.content||' '||COALESCE(x.retrieval_text,'')";String predicate=matching(document,terms,args);List<String> scoreArgs=new ArrayList<>();String score=ranking(document,terms,scoreArgs);
        if(!enabled){args.clear();scoreArgs.clear();score="0";}scoreArgs.addAll(args);
        String sql="SELECT m.memory_key,("+score+") AS relevance FROM memories m LEFT JOIN memory_metadata x ON x.memory_key=m.memory_key WHERE m.kind IN "+kinds
                +" AND "+privacy.memorySql()+" AND "+(enabled?predicate:"0")+" ORDER BY relevance DESC,COALESCE(x.pinned,0) DESC,m.updated_at DESC,m.memory_key LIMIT "+MAX_LAYER_CANDIDATES;
        JSONArray rows=query(db,sql,scoreArgs,result); Set<String> keys=new LinkedHashSet<>();
        if(enabled&&hybrid!=null){JSONArray candidates=hybrid.optJSONArray("memories");
            for(int i=0;candidates!=null&&i<Math.min(MAX_HYBRID_IDS,candidates.length());i++){
                JSONObject candidate=candidates.optJSONObject(i);if(candidate==null)continue;
                String key=candidate.optString("key",candidate.optString("memory_key"));JSONObject row=memoryRow(db,key);
                if(row==null||row.optLong("revision")!=candidate.optLong("revision")||!row.optString("content_hash").equals(candidate.optString("content_hash")))continue;
                if(candidate.optBoolean("relevantToQuery",true)&&layerKinds(layer,row.optString("kind"))&&selected.memoryAllowed(row))keys.add(key);
            }
        }
        for(int i=0;i<rows.length();i++){String key=rows.optJSONObject(i).optString("memory_key");JSONObject memory=memoryRow(db,key);if(memory!=null&&selected.memoryAllowed(memory))keys.add(key);}
        for(String key:keys){JSONObject row=memoryRow(db,key);if(row==null)throw new Blocked("memory_candidate_disappeared");
            result.payload.put(selected.validateMemory(row,true));
            // History is independently queried at the same snapshot, not silently treated as current.
            JSONArray history=query(db,"SELECT revision FROM memory_revisions WHERE memory_key=? AND revision<>? ORDER BY revision DESC LIMIT 4",
                    java.util.Arrays.asList(key,String.valueOf(row.optLong("revision"))),result);
            Set<Long> historicalRevisions=new LinkedHashSet<>();for(int i=0;i<history.length();i++)historicalRevisions.add(history.optJSONObject(i).optLong("revision"));
            JSONArray hybridHistory=hybrid==null?null:hybrid.optJSONArray("history");
            for(int i=0;enabled&&hybridHistory!=null&&i<Math.min(MAX_HYBRID_IDS,hybridHistory.length());i++){
                JSONObject candidate=hybridHistory.optJSONObject(i);if(candidate!=null&&key.equals(candidate.optString("key"))&&candidate.optLong("revision")>0&&candidate.optLong("revision")!=row.optLong("revision"))historicalRevisions.add(candidate.optLong("revision"));
            }
            for(long revision:historicalRevisions){JSONObject past=selected.historical(key,revision,true);if(past!=null)result.payload.put(past);}
        }
        result.exclusion=enabled?"none":"memory_search_excluded_by_owner";return result;
    }

    /** A human may adopt generated wording. Its immutable native origin receipt is mandatory
     * alongside that owner instruction so later recall distinguishes authorship and approval. */
    private static void appendInputOrigin(SQLiteDatabase db,long inputId,JSONObject input,Accumulator selected,StringBuilder required)throws IOException {
        String encoded=setting(db,"inputOrigin:"+inputId,null);
        if(encoded==null)return;
        JSONObject link=json(encoded);long originId=link.optLong("sourceMessageId",0);String hash=link.optString("sourceHash");
        if(originId<=0||!hash.matches("[a-f0-9]{64}"))throw new Blocked("input_origin_binding_invalid");
        JSONObject source=selected.message(originId,0,12000,false);
        if(!hash.equals(source.optString("recordHash"))||!"system".equals(source.optString("role")))throw new Blocked("input_origin_source_invalid");
        JSONObject evidence=json(source.optString("text"));
        if(!"native_input_origin".equals(evidence.optString("type"))||evidence.optLong("sourceMessageId")!=inputId
                ||!input.optString("recordHash").equals(evidence.optString("sourceHash"))||!evidence.optBoolean("humanOwnerAuthorized"))throw new Blocked("input_origin_scope_invalid");
        required.append("NATIVE_INPUT_ORIGIN (authorship and human submission are distinct) ").append(source).append('\n');
    }

    private LayerRead procedural(SQLiteDatabase db,List<String> terms,boolean enabled,PrivacyView privacy,
                                 JSONObject hybrid,Accumulator selected,JSONObject controls)throws IOException {
        LayerRead result=new LayerRead("procedural");
        JSONArray settings=query(db,"SELECT setting_key,value FROM settings WHERE setting_key IN ('customInstructions','personalizedInstructions','standingGoals') ORDER BY setting_key",Collections.emptyList(),result);
        for(int i=0;i<settings.length();i++){JSONObject row=settings.optJSONObject(i);put(row,"sourceKind","effective_owner_setting");put(row,"isActionGrant",false);result.payload.put(row);}
        JSONArray rows=query(db,"SELECT m.memory_key FROM memories m LEFT JOIN memory_metadata x ON x.memory_key=m.memory_key WHERE "+privacy.memorySql()
                +" AND "+(enabled?"(m.kind='procedural' OR (m.kind='preference' AND (m.memory_key LIKE 'style%' OR m.memory_key LIKE 'workflow%' OR x.metadata_json LIKE '%\"scope\":\"procedural\"%' OR x.metadata_json LIKE '%\"layer\":\"procedural\"%')))":"0")
                +" ORDER BY m.updated_at DESC,m.memory_key LIMIT "+MAX_LAYER_CANDIDATES,Collections.emptyList(),result);
        for(int i=0;i<rows.length();i++){JSONObject row=memoryRow(db,rows.optJSONObject(i).optString("memory_key"));if(row!=null&&selected.memoryAllowed(row))result.payload.put(selected.validateMemory(row,true));}
        result.exclusion=enabled?"none":"historical_procedural_memory_excluded_by_owner_effective_settings_retained";return result;
    }

    private LayerRead active(SQLiteDatabase db,String rootId,PrivacyView privacy,Accumulator selected)throws IOException {
        LayerRead result=new LayerRead("active");
        JSONArray rows=query(db,"SELECT setting_key,value FROM settings WHERE setting_key IN ("+activeControlKeys(false)+")"
                +" OR setting_key=? OR setting_key IN ('toolPermissions','standingGoals','runtime.uncertaintyBlocked','runtime.quotaBlocked','autonomy.enabled') ORDER BY setting_key",java.util.Arrays.asList("pausedRoot:"+rootId),result);
        if(rows.length()>1024)throw new Blocked("active_constraints_population_overflow");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);String key=row.optString("setting_key"),raw=row.optString("value");
            if(key.startsWith("activeGoal:")){
                JSONObject goal=json(raw);long source=goal.optLong("sourceMessageId");
                if(!GoalLedger.sourcesAllowed(db,privacy.policy,goal,selected.deviceScope)){result.payload.put(GoalLedger.opaque(goal));continue;}
                if(source>0){JSONObject sourceRow=one(db,"SELECT id,content_hash FROM messages WHERE id=?",new String[]{String.valueOf(source)});
                    if(sourceRow==null){result.payload.put(GoalLedger.opaque(goal));continue;}
                    if(!selected.messageAllowed(source)){result.payload.put(object("rootRequestId",key.substring("activeGoal:".length()),"stage","blocked_source_excluded","mayAutoRepeat",false));continue;}
                    selected.message(source,0,1200,false);
                }
                List<String> sourceKeys=new ArrayList<>();sourceKeys.add("goalSources:"+goal.optString("rootRequestId"));if(!goal.optString("progressSourcesKey").isEmpty())sourceKeys.add(goal.optString("progressSourcesKey"));
                for(String sourceKey:sourceKeys){JSONObject provenance=one(db,"SELECT value FROM settings WHERE setting_key=?",new String[]{sourceKey});if(provenance==null)continue;
                    selected.dependencies.put("goal_source_setting:"+sourceKey,object("type","goal_source_setting","key",sourceKey,"hash",digest(provenance.optString("value"))));
                    JSONArray refs=json(provenance.optString("value")).optJSONArray("sources");for(int n=0;refs!=null&&n<refs.length();n++){JSONObject ref=refs.optJSONObject(n);if("message".equals(ref.optString("type")))selected.message(ref.optLong("id"),0,1200,false);else selected.device(ref.optLong("id"));}}
                for(String actorKey:new String[]{"lastRequestId","actorRequestId"}){String actor=goal.optString(actorKey);if(actor.isEmpty())continue;JSONObject answer=one(db,"SELECT id FROM messages WHERE role='assistant' AND request_id=? ORDER BY id DESC LIMIT 1",new String[]{actor});if(answer!=null)selected.message(answer.optLong("id"),0,1200,false);}
                put(goal,"mayAutoRepeat",false);result.payload.put(goal);
                long next=goal.optLong("nextCheckAt");if(next>selected.now){selected.validUntil=Math.min(selected.validUntil,next);selected.temporalValidUntil=Math.min(selected.temporalValidUntil,next);}
            }else result.payload.put(row);
        }
        JSONArray uncertain=query(db,unresolvedRequestSql(),java.util.Arrays.asList(rootId),result);
        for(int i=0;i<uncertain.length();i++){JSONObject constraint=uncertain.optJSONObject(i);put(constraint,"mayAutoRepeat",false);result.payload.put(constraint);}
        JSONArray unresolvedCounts=query(db,UNRESOLVED_COUNTS,Collections.emptyList(),result);
        if(unresolvedCounts.length()>0)result.payload.put(object("unresolvedRequestCounts",unresolvedCounts,"detailCoverage","newest_32_plus_current_root",
                "allHistoricalRowsRetained",true,"mayAutoRepeat",false,"instruction","blocked/quota/uncertain 과거 요청은 일부만 표시돼도 모두 명시적 새 요청 없이 재실행하지 않는다."));
        result.exclusion="none_mandatory_native_controls";return result;
    }

    private final class Accumulator {
        final HoyoungStore.ReadSnapshot snapshot;final PrivacyView privacy;final SourceRecordVisibility.ReadContext sourceVisibility;final long now;final java.util.function.Predicate<JSONObject> deviceScope;long validUntil;long temporalValidUntil=Long.MAX_VALUE;String query="";
        final Map<String,JSONObject> dependencies=new LinkedHashMap<>();
        final Set<Long> authoritativeIds=new LinkedHashSet<>();
        Accumulator(HoyoungStore.ReadSnapshot snapshot,PrivacyView privacy,long now,long validUntil,java.util.function.Predicate<JSONObject> deviceScope){this.snapshot=snapshot;this.privacy=privacy;this.now=now;this.validUntil=validUntil;this.deviceScope=deviceScope;this.sourceVisibility=new SourceRecordVisibility.ReadContext(snapshot,privacy.policy,deviceScope);}
        JSONObject message(long id,long offset,int maximum,boolean ownerRequired)throws IOException {
            JSONObject row=one(snapshot.database,"SELECT * FROM messages WHERE id=?",new String[]{String.valueOf(id)});
            if(row==null||!sourceVisibility.allowed("message",row))throw new Blocked("memory_source_excluded_or_missing");
            if(ownerRequired&&!"user".equals(row.optString("role")))throw new Blocked("authoritative_input_source_invalid");
            if(ownerRequired)authoritativeIds.add(id);
            JSONObject hashed=new JSONObject();for(String key:new String[]{"id","role","text","request_id","created_at","raw_file_id","raw_char_count","raw_byte_count","raw_content_hash"})put(hashed,key,row.has(key)?row.opt(key):JSONObject.NULL);
            if(!digest(canonical(hashed)).equals(row.optString("content_hash")))throw new Blocked("memory_source_integrity_failed");
            JSONObject original=store.readOriginalText(snapshot,id,offset,maximum);
            put(original,"type","message");put(original,"role",row.optString("role"));put(original,"recordedAt",row.optLong("created_at"));
            JSONObject dependency=object("type","message","id",id,"hash",original.optString("recordHash"),"offsetChars",original.optLong("offsetChars"),"lengthChars",original.optString("text").length(),"rangeHash",digest(original.optString("text")));
            dependencies.put("message:"+id+":"+offset+":"+maximum,dependency);return original;
        }
        /** Only the additional last-eight recent projection uses native account metadata.
         * Search matches, owner originals, source.read and account lookup keep their full text. */
        JSONObject recentMessage(long id)throws IOException {
            JSONObject original=message(id,0,1200,false);
            if(!"system".equals(original.optString("role")))return original;
            String key="nativeAccountInput:"+id;
            JSONObject index=one(snapshot.database,"SELECT value FROM settings WHERE setting_key=?",new String[]{key});
            if(index==null)return original;
            JSONObject marker;
            try {marker=new JSONObject(index.optString("value"));}catch(org.json.JSONException malformed){return original;}
            JSONObject account=marker.optJSONObject("account");
            if(marker.optInt("version")!=1||!"native_account_input".equals(marker.optString("type"))
                    ||marker.optLong("sourceMessageId")!=id||!original.optString("recordHash").equals(marker.optString("sourceMessageHash"))
                    ||account==null||!(account.opt("accountId") instanceof String))return original;
            JSONObject metadata=new JSONObject();
            for(String field:new String[]{"accountId","service","username","label","loginUrl","origin","inputActor","revision","createdAt","updatedAt","loginVerified","hasPassword","extraFieldCount"})
                if(account.has(field))put(metadata,field,account.opt(field));
            dependencies.put("native_account_projection:"+id,object("type","native_account_projection","key",key,"hash",digest(index.optString("value"))));
            return object("type","account_metadata","sourceType","message","id",id,"role","system","recordedAt",original.optLong("recordedAt"),
                    "recordHash",original.optString("recordHash"),"projectionOnly",true,"historicalInputMetadata",true,"exactCredentialsPreservedInOriginal",true,
                    "account",metadata,"exactAccountReadTool","accounts.get","exactOriginalReadTool","memory.source.read");
        }
        JSONObject device(long id) {
            JSONObject row=one(snapshot.database,"SELECT * FROM device_events WHERE id=?",new String[]{String.valueOf(id)});
            if(row==null)throw new Blocked("memory_source_excluded_or_missing");
            JSONObject body=json(row.optString("payload"));String hash=digest(canonical(object("category",row.optString("category"),"source",row.optString("source"),"payload",body,"observed_at",row.optLong("observed_at"))));
            JSONObject visible=json(row.toString());put(visible,"payload",body);if(!deviceScope.test(visible))throw new Blocked("memory_source_permission_changed");
            if(!sourceVisibility.allowed("device_event",row))throw new Blocked("memory_source_excluded_or_missing");
            if(!hash.equals(row.optString("content_hash")))throw new Blocked("memory_source_integrity_failed");
            dependencies.put("device_event:"+id,object("type","device_event","id",id,"hash",hash));
            return object("type","device_event","id",id,"hash",hash,"category",row.optString("category"),"source",row.optString("source"),"observedAt",row.optLong("observed_at"),"payloadExcerpt",MemoryText.window(body.toString(),query,1800),"excerptMayOmitDetails",body.toString().length()>1800);
        }
        boolean memoryAllowed(JSONObject memory) {
            JSONArray refs=query(snapshot.database,"SELECT source_type,source_id,source_hash FROM memory_sources WHERE memory_key=? AND revision=? ORDER BY source_type,source_id",new String[]{memory.optString("key"),String.valueOf(memory.optLong("revision"))});
            for(int i=0;i<refs.length();i++){JSONObject ref=refs.optJSONObject(i);String type=ref.optString("source_type");long id=ref.optLong("source_id");if(!privacy.allowed(type,id,ref.optString("source_hash"))||("device_event".equals(type)&&!deviceAllowed(id))||("message".equals(type)&&!messageAllowed(id)))return false;}return true;
        }
        boolean messageAllowed(long id){JSONObject row=one(snapshot.database,"SELECT * FROM messages WHERE id=?",new String[]{String.valueOf(id)});return row!=null&&sourceVisibility.allowed("message",row);}
        boolean deviceAllowed(long id){JSONObject row=one(snapshot.database,"SELECT * FROM device_events WHERE id=?",new String[]{String.valueOf(id)});if(row==null||!sourceVisibility.allowed("device_event",row))return false;JSONObject visible=json(row.toString());put(visible,"payload",json(row.optString("payload")));return deviceScope.test(visible);}
        JSONObject historical(String key,long revision,boolean add)throws IOException {
            JSONObject past=one(snapshot.database,"SELECT r.*,(SELECT x.metadata_json FROM memory_metadata_revisions x WHERE x.memory_key=r.memory_key AND x.memory_revision=r.revision ORDER BY x.metadata_revision DESC LIMIT 1) AS metadata_json FROM memory_revisions r WHERE r.memory_key=? AND r.revision=?",new String[]{key,String.valueOf(revision)});
            if(past==null)return null;
            JSONArray links=query(snapshot.database,"SELECT source_type,source_id,source_hash,source_role,observed_at FROM memory_sources WHERE memory_key=? AND revision=? ORDER BY source_type,source_id",new String[]{key,String.valueOf(revision)});
            if(links.length()==0||links.length()>MAX_SOURCE_LINKS)throw new Blocked("memory_history_source_coverage_unverified");
            for(int i=0;i<links.length();i++){JSONObject link=links.optJSONObject(i);String type=link.optString("source_type");long id=link.optLong("source_id");if(!privacy.allowed(type,id,link.optString("source_hash"))||("device_event".equals(type)&&!deviceAllowed(id))||("message".equals(type)&&!messageAllowed(id)))return null;}
            JSONArray evidence=new JSONArray();for(int i=0;i<links.length();i++){
                JSONObject link=links.optJSONObject(i);String type=link.optString("source_type");long id=link.optLong("source_id");
                JSONObject source="message".equals(type)?message(id,0,1200,false):"device_event".equals(type)?device(id):null;
                if(source==null||!link.optString("source_hash").equals(source.optString("recordHash",source.optString("hash"))))throw new Blocked("memory_history_source_changed");
                if(i<3)evidence.put(source);
            }
            String fingerprint=digest(canonical(object("revisionRow",past,"sourceLinks",links)));
            if(add)dependencies.put("memory_revision:"+key+":"+revision,object("type","memory_revision","key",key,"revision",revision,"hash",fingerprint));
            JSONObject metadata=past.isNull("metadata_json")?new JSONObject():json(past.optString("metadata_json"));
            recordTemporalBoundaries(metadata);
            String state=memoryState(metadata);
            return object("key",key,"revision",revision,"kind",past.optString("kind"),"content","invalidated".equals(state)||"forgotten".equals(state)?"":MemoryText.window(past.optString("content"),query,2500),"contentIsExcerpt",past.optString("content").length()>2500,
                    "memoryStatus",state,"invalidatedIsNotFormerlyTrue","invalidated".equals(state),"historicalRevision",true,"currentTruthVerified",false,"metadata",metadata,"hydratedSourceExamples","invalidated".equals(state)||"forgotten".equals(state)?new JSONArray():evidence,"snapshotEvidenceHash",fingerprint);
        }
        JSONObject validateMemory(JSONObject memory,boolean add)throws IOException {
            if(memory==null||!memoryAllowed(memory))throw new Blocked("memory_source_excluded_or_missing");
            JSONArray links=query(snapshot.database,"SELECT source_type,source_id,source_hash,source_role,observed_at FROM memory_sources WHERE memory_key=? AND revision=? ORDER BY source_type,source_id",new String[]{memory.optString("key"),String.valueOf(memory.optLong("revision"))});
            if(links.length()==0||links.length()>MAX_SOURCE_LINKS)throw new Blocked("memory_source_coverage_unverified");
            JSONArray refs=new JSONArray(),evidence=new JSONArray();
            for(int i=0;i<links.length();i++){
                JSONObject link=links.optJSONObject(i);String type=link.optString("source_type");long id=link.optLong("source_id");
                JSONObject ref=object("type",type,"id",id,"hash",link.optString("source_hash"),"role",link.isNull("source_role")?JSONObject.NULL:link.opt("source_role"),"observedAt",link.optLong("observed_at"));refs.put(ref);
                JSONObject source="message".equals(type)?message(id,0,1200,false):"device_event".equals(type)?device(id):null;
                if(source==null||!link.optString("source_hash").equals(source.optString("recordHash",source.optString("hash"))))throw new Blocked("memory_source_changed");
                if(i<3)evidence.put(source);
            }
            JSONObject metadata=memory.optJSONObject("metadata");
            JSONObject document=object("key",memory.optString("key"),"content",memory.optString("content"),"kind",memory.optString("kind"),"sourceRefs",refs,"metadata",metadata);
            if(!digest(canonical(document)).equals(memory.optString("content_hash")))throw new Blocked("memory_revision_integrity_failed");
            String state=memoryState(metadata);
            if(add)dependencies.put("memory:"+memory.optString("key"),object("type","memory","key",memory.optString("key"),"revision",memory.optLong("revision"),"hash",memory.optString("content_hash")));
            recordTemporalBoundaries(metadata);
            JSONArray quotes=metadata==null?null:metadata.optJSONArray("evidenceQuotes");
            for(int i=0;quotes!=null&&i<quotes.length();i++){JSONObject quote=quotes.optJSONObject(i);if(quote==null||!"message".equals(quote.optString("type")))continue;
                String text=quote.optString("quote");if(text.length()>8000)throw new Blocked("memory_quote_overflow");
                JSONObject original=message(quote.optLong("id"),quote.optLong("offsetChars"),text.length(),false);
                if(!text.equals(original.optString("text"))||!quote.optString("recordHash").equals(original.optString("recordHash")))throw new Blocked("memory_quote_changed");
            }
            return object("key",memory.optString("key"),"revision",memory.optLong("revision"),"hash",memory.optString("content_hash"),"kind",memory.optString("kind"),
                    "content","invalidated".equals(state)||"forgotten".equals(state)?"":MemoryText.window(memory.optString("content"),query,2500),"contentIsExcerpt",memory.optString("content").length()>2500,
                    "memoryStatus",state,"invalidatedIsNotFormerlyTrue","invalidated".equals(state),"metadata",metadata,"sourceRefs",refs,"hydratedSourceExamples","invalidated".equals(state)||"forgotten".equals(state)?new JSONArray():evidence,"meaningIndependentlyVerified",false);
        }
        void recordTemporalBoundaries(JSONObject metadata){
            for(String boundary:new String[]{"validFrom","validTo"}){
                final long value;
                try{value=MemoryQuery.date(metadata==null?null:metadata.opt(boundary));}
                catch(IllegalArgumentException invalid){throw new Blocked("memory_validity_invalid",invalid);}
                if(value>now){validUntil=Math.min(validUntil,value);temporalValidUntil=Math.min(temporalValidUntil,value);}
            }
        }
        JSONArray dependencies(){return new JSONArray(dependencies.values());}
    }

    private static final class TermLane {final JSONArray messages,devices;final long weight;TermLane(JSONArray messages,JSONArray devices,long weight){this.messages=messages;this.devices=devices;this.weight=weight;}}
    private static void addCandidate(Map<String,JSONObject> values,String type,long id,long offset,int hybridPriority,int limit){if(id<=0)return;String key=type+":"+id;JSONObject old=values.get(key);if(old!=null){if(hybridPriority>old.optInt("hybridPriority")){put(old,"hybridPriority",hybridPriority);put(old,"offsetChars",offset);}return;}if(values.size()<limit)values.put(key,object("type",type,"id",id,"offsetChars",offset,"hybridPriority",hybridPriority));}
    private static String ftsMatch(String term){List<String> parts=new ArrayList<>();for(String part:term.split("[^\\p{L}\\p{N}_]+"))if(!part.isEmpty())parts.add("\""+part.replace("\"","\"\"")+"*\"");return String.join(" ",parts);}
    private static String weightedRanking(String expression,Map<String,Long> terms,List<String> args){StringBuilder score=new StringBuilder("0");for(Map.Entry<String,Long> term:terms.entrySet()){score.append(" + CASE WHEN ").append(expression).append(" LIKE ? ESCAPE '\\' THEN ").append(term.getValue()).append(" ELSE 0 END");args.add("%"+term.getKey().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");}return score.toString();}
    private static long sourceWindowOffset(SQLiteDatabase db,long id,Map<String,Long> weights){JSONObject row=one(db,"SELECT text FROM message_search WHERE docid=?",new String[]{String.valueOf(id)});if(row==null)return 0;String text=row.optString("text");if(text.length()<=1200)return 0;int best=-1;long weight=-1;for(Map.Entry<String,Long> term:weights.entrySet()){java.util.regex.Matcher hit=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(term.getKey()),java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.UNICODE_CASE).matcher(text);if(hit.find()&&term.getValue()>weight){best=hit.start();weight=term.getValue();}}long offset=Math.max(0,best-400);if(offset>0&&offset<text.length()&&Character.isLowSurrogate(text.charAt((int)offset))&&Character.isHighSurrogate(text.charAt((int)offset-1)))offset--;return offset;}
    private static JSONObject compactOriginal(JSONObject source){JSONObject model=object("type","message","id",source.optLong("messageId"),"role",source.optString("role"),"recordedAt",source.optLong("recordedAt"),"recordHash",source.optString("recordHash"),"offsetChars",source.optLong("offsetChars"),"text",source.optString("text"),"exactOriginalRange",true,"complete",source.optBoolean("complete"));if(!source.optBoolean("complete")){put(model,"nextOffsetChars",source.optLong("nextOffsetChars"));put(model,"totalChars",source.optLong("totalChars"));put(model,"complete",false);}return model;}

    /** Omit only a repeated projection; the authoritative setting and its query stay intact. */
    private static String duplicateSettingLocation(JSONObject row,JSONArray active,JSONObject controls,JSONObject rendered) {
        String key=row.optString("setting_key");Object value=row.opt("value");
        if(!(value instanceof String))return "";
        if("standingGoals".equals(key)) {
            for(int i=0;i<active.length();i++) {
                JSONObject mandatory=active.optJSONObject(i);
                if(mandatory!=null&&key.equals(mandatory.optString("setting_key"))&&value.equals(mandatory.opt("value")))
                    return "MANDATORY_ACTIVE_CONSTRAINTS";
            }
        } else if(("customInstructions".equals(key)||"personalizedInstructions".equals(key))
                &&value.equals(controls.opt(key))&&value.equals(rendered.opt(key))) {
            return "AUTHORITATIVE_PROMPT_SETTINGS";
        }
        return "";
    }

    private static final class LayerRead {
        final String layer;final JSONArray payload=new JSONArray(),queries=new JSONArray();String exclusion="none";JSONObject candidateScope=new JSONObject();
        LayerRead(String layer){this.layer=layer;}
    }
    private static final class OptionalLine{final String text;final int rank,lane;OptionalLine(String text,int rank,int lane){this.text=text;this.rank=rank;this.lane=lane;}}
    private static JSONObject receipt(LayerRead layer,String dispatch,String root,String purpose,long input,long latest,String snapshot,
                                      JSONObject control,PrivacyView privacy,long now,long until) {
        if(layer.queries.length()==0)throw new Blocked("missing_actual_layer_query");
        return object("layer",layer.layer,"status",layer.payload.length()==0?"empty":"success","queryId",digest(canonical(layer.queries)),
                "queryFingerprint",digest(canonical(layer.queries)),"queries",layer.queries,"ownerScope","owner-local","conversationScope","single-native-conversation",
                "dispatchId",dispatch,"runId",root,"purpose",purpose,"inputId",input,"inputCutoffId",latest,"snapshotId",snapshot,
                "controlFingerprint",digest(canonical(control)),"privacyFingerprint",privacy.fingerprint,"backend","canonical_sql_plus_validated_hybrid_ids",
                "candidateScope",layer.candidateScope,"resultCount",layer.payload.length(),"exclusion",layer.exclusion,"coverage","canonical_query_scope_at_snapshot",
                "readAt",now,"validUntil",until,"modelInternalUseVerified",false);
    }
    private static JSONArray query(SQLiteDatabase db,String sql,List<String> args,LayerRead read) {
        JSONArray rows=query(db,sql,args.toArray(new String[0]));
        read.queries.put(object("sqlFingerprint",digest(sql),"boundArgumentsFingerprint",digest(canonical(new JSONArray(args))),"returnedRows",rows.length()));return rows;
    }
    private static JSONArray query(SQLiteDatabase db,String sql,String[] args) {
        JSONArray result=new JSONArray();try(Cursor c=db.rawQuery(sql,args)){while(c.moveToNext()){JSONObject row=new JSONObject();
            for(int i=0;i<c.getColumnCount();i++)put(row,c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getType(i)==Cursor.FIELD_TYPE_INTEGER?c.getLong(i):c.getType(i)==Cursor.FIELD_TYPE_FLOAT?c.getDouble(i):c.getString(i));result.put(row);}}return result;
    }
    private static JSONObject one(SQLiteDatabase db,String sql,String[] args){JSONArray rows=query(db,sql,args);return rows.length()==0?null:rows.optJSONObject(0);}
    private static JSONObject memoryRow(SQLiteDatabase db,String key){
        JSONObject row=one(db,"SELECT m.*,x.metadata_json FROM memories m LEFT JOIN memory_metadata x ON x.memory_key=m.memory_key WHERE m.memory_key=?",new String[]{key});
        if(row==null)return null;put(row,"key",row.optString("memory_key"));put(row,"metadata",row.isNull("metadata_json")?new JSONObject():json(row.optString("metadata_json")));return row;
    }
    private static boolean layerKinds(String layer,String kind){return ("semantic".equals(layer)?"|fact|preference|uncertain|inference|opinion|":"|summary|generated_summary|project|task|observation|episodic|event|").contains("|"+kind+"|");}
    private static String memoryState(JSONObject metadata){if(metadata==null)return "active";String state=metadata.optString("status",metadata.optString("correctionState","active"));if(!"|active|candidate|conflicted|superseded|invalidated|forgotten|".contains("|"+state+"|"))throw new Blocked("memory_status_invalid");return state;}
    private static String matching(String expression,List<String> terms,List<String> args){if(terms.isEmpty())return "0";StringBuilder predicate=new StringBuilder("(");for(int i=0;i<Math.min(24,terms.size());i++){if(i>0)predicate.append(" OR ");predicate.append(expression).append(" LIKE ? ESCAPE '\\'");args.add("%"+terms.get(i).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");}return predicate.append(')').toString();}
    private static String ranking(String expression,List<String> terms,List<String> args){StringBuilder score=new StringBuilder("0");for(int i=0;i<Math.min(24,terms.size());i++){score.append(" + CASE WHEN ").append(expression).append(" LIKE ? ESCAPE '\\' THEN ").append(10+Math.min(24,terms.get(i).length())).append(" ELSE 0 END");args.add("%"+terms.get(i).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");}return score.toString();}
    private static JSONObject controls(SQLiteDatabase db,String root){JSONObject result=new JSONObject();for(String key:POLICY_KEYS){JSONObject row=one(db,"SELECT value FROM settings WHERE setting_key=?",new String[]{key});put(result,key,row==null?"<unset>":row.optString("value"));}
        put(result,"pausedRoot",setting(db,"pausedRoot:"+root,"false"));put(result,"runGeneration",setting(db,"runGeneration:"+root,"0"));
        JSONArray active=query(db,"SELECT setting_key,value FROM settings WHERE setting_key IN ("+activeControlKeys(true)+") OR setting_key IN (?,?,'runtime.uncertaintyBlocked','runtime.quotaBlocked') ORDER BY setting_key",new String[]{"pausedRoot:"+root,"goalSources:"+root});
        JSONArray uncertain=query(db,unresolvedRequestSql(),new String[]{root});
        put(result,"activeConstraintsFingerprint",digest(canonical(object("settings",active,"unresolvedRequests",uncertain,"unresolvedRequestCounts",query(db,UNRESOLVED_COUNTS,new String[0])))));return result;}
    /** Indexed active-root lookups: closed-goal provenance stays stored, outside the live prompt. */
    private static String activeControlKeys(boolean sources){
        String roots=" FROM settings WHERE setting_key>='activeGoal:' AND setting_key<'activeGoal;'";
        return "SELECT setting_key"+roots+" UNION SELECT 'pausedRoot:'||substr(setting_key,12)"+roots
                +(sources?" UNION SELECT 'goalSources:'||substr(setting_key,12)"+roots:"");
    }
    private static final String UNRESOLVED_COUNTS="SELECT status,COUNT(*) AS count,MAX(id) AS newestId,MAX(updated_at) AS latestChangeAt FROM requests WHERE status IN ('uncertain','blocked','quota') GROUP BY status ORDER BY status";
    private static String unresolvedRequestSql(){
        return "SELECT id,external_id,kind,status FROM requests WHERE id IN (SELECT id FROM requests WHERE status IN ('uncertain','blocked','quota') ORDER BY id DESC LIMIT 32) "
                +"OR (external_id=? AND status IN ('uncertain','blocked','quota')) ORDER BY id DESC";
    }
    private static String setting(SQLiteDatabase db,String key,String fallback){JSONObject row=one(db,"SELECT value FROM settings WHERE setting_key=?",new String[]{key});return row==null?fallback:row.optString("value");}
    private static boolean allowedSetting(String raw,String name){if("<unset>".equals(raw))return true;JSONObject permissions=json(raw);if(permissions.has(name)&&!(permissions.opt(name) instanceof Boolean))throw new Blocked("memory_permission_configuration_invalid");return !permissions.has(name)||Boolean.TRUE.equals(permissions.opt(name));}
    private static JSONObject projectionState(SQLiteDatabase db){long reviewed=number(setting(db,"watermark:memory.review.owner","0"));JSONObject pending=one(db,"SELECT COUNT(*) AS count,COALESCE(MAX(id),0) AS latest FROM messages WHERE role='user' AND id>?",new String[]{String.valueOf(reviewed)});return object("state",pending.optLong("count")>0?"pending":"ready_source_ranges_only","structuredExtraction","source_ranges_acknowledged_not_semantic_completeness", "ownerReviewedThroughId",reviewed,"latestOwnerId",Math.max(reviewed,pending.optLong("latest")),"unreviewedOwnerCount",pending.optLong("count"),"semanticIndexRequiredForAdmission",false,"rawFallback","canonical_sql");}
    private PrivacyView privacyView(){try{return privacy==null?new PrivacyView(new JSONObject()):new PrivacyView(privacy.snapshot());}catch(IOException unavailable){throw new Blocked("memory_privacy_ledger_unavailable",unavailable);}}
    private static final class PrivacyView {
        final JSONObject state;final String fingerprint;final JSONArray exclusions;final SourcePrivacyLedger.Snapshot policy;
        final String messageExclusionKeys,deviceExclusionHashes;
        PrivacyView(JSONObject state){
            this.state=json(state.toString());this.exclusions=this.state.optJSONArray("exclusions");
            try{policy=SourcePrivacyLedger.fromSnapshot(this.state);}catch(IOException invalid){throw new Blocked("memory_privacy_ledger_invalid",invalid);}
            this.fingerprint=policy.fingerprint;
            StringBuilder messages=new StringBuilder(),devices=new StringBuilder();Set<String> deviceHashes=new LinkedHashSet<>();
            // Snapshot validation restricts every literal to a positive integer and a SHA-256.
            for(int i=0;i<exclusions.length();i++){
                JSONObject row=exclusions.optJSONObject(i);String sha=row.optString("hash");
                if("message".equals(row.optString("type"))){
                    if(messages.length()>0)messages.append(',');messages.append('\'').append(row.optLong("id")).append(':').append(sha).append('\'');
                }else if(deviceHashes.add(sha)){
                    if(devices.length()>0)devices.append(',');devices.append('\'').append(sha).append('\'');
                }
            }
            messageExclusionKeys=messages.toString();deviceExclusionHashes=devices.toString();
        }
        boolean allowed(String type,long id,String hash){return policy.allowedSource(type,id,hash);}
        String sql(String type,String id,String hash){
            String values,identity;
            if("message".equals(type)){values=messageExclusionKeys;identity="("+id+" || ':' || "+hash+")";}
            else if("device_event".equals(type)){values=deviceExclusionHashes;identity=hash;}
            else return "1=1";
            // Scalar membership avoids both SQLite's 1,000-deep expression limit and a
            // row-value NOT IN scan for each absent source. Message generations stay distinct;
            // replayed device observations remain excluded even under another SQLite ID.
            return values.isEmpty()?"1=1":identity+" NOT IN ("+values+")";
        }
        String memorySql(){return "NOT EXISTS(SELECT 1 FROM memory_sources privacy_ref WHERE privacy_ref.memory_key=m.memory_key AND privacy_ref.revision=m.revision AND ((privacy_ref.source_type='message' AND NOT ("+sql("message","privacy_ref.source_id","privacy_ref.source_hash")+")) OR (privacy_ref.source_type='device_event' AND NOT ("+sql("device_event","privacy_ref.source_id","privacy_ref.source_hash")+"))))";}
    }
    private static void identifier(String value){if(value==null||!value.matches("[A-Za-z0-9_.:-]{1,450}"))throw new IllegalArgumentException("Invalid native memory identifier");}
    private static long number(String value){if("<unset>".equals(value))return 0;try{long result=Long.parseLong(value);if(result<0)throw new NumberFormatException();return result;}catch(NumberFormatException invalid){throw new Blocked("memory_control_sequence_invalid",invalid);}}
    private static JSONObject json(String raw){try{return new JSONObject(raw);}catch(Exception invalid){throw new Blocked("memory_json_invalid",invalid);}}
    private static JSONArray array(String raw){try{return new JSONArray(raw);}catch(Exception invalid){throw new Blocked("memory_json_invalid",invalid);}}
    private static JSONObject object(Object... fields){JSONObject result=new JSONObject();for(int i=0;i<fields.length;i+=2)put(result,String.valueOf(fields[i]),fields[i+1]);return result;}
    private static void put(JSONObject target,String key,Object value){try{target.put(key,value);}catch(Exception invalid){throw new Blocked("memory_payload_invalid",invalid);}}
    private static String digest(String text){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder(64);for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static String canonical(Object value){if(value==null||value==JSONObject.NULL)return "null";if(value instanceof JSONObject){JSONObject json=(JSONObject)value;List<String> keys=new ArrayList<>();java.util.Iterator<String> i=json.keys();while(i.hasNext())keys.add(i.next());Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(json.opt(key)));}return out.append('}').toString();}if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.opt(i)));}return out.append(']').toString();}return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);}
}
