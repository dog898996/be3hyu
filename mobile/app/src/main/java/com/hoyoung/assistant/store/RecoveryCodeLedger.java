package com.hoyoung.assistant.store;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Append-only one-use recovery-code reservations and publication claims, outside namespaces replaced by old ZIPs.
 * Only hashes and native reference metadata are retained here; never raw recovery codes. */
public final class RecoveryCodeLedger {
    public static final String FORMAT="hoyoung-recovery-reservations-v2";
    public static final String LEGACY_FORMAT="hoyoung-recovery-reservations-v1";
    private static final long MAX_BYTES=16L*1024*1024;
    private static final int MAX_ENTRIES=100_000;
    private static final Object LOCK=new Object();
    private final Context context;
    private final File root,ledger,anchor,lock;
    public RecoveryCodeLedger(Context supplied) {
        if(supplied==null)throw new IllegalArgumentException("An application context is required");
        context=supplied.getApplicationContext();root=context.getFilesDir();
        ledger=new File(root,"recovery-code-reservations.json");anchor=new File(root,"recovery-code-reservations.anchor.json");lock=new File(root,".recovery-code-reservations.lock");
    }
    public static String fingerprint(String accountId,String exactCode) {
        bounded(accountId,160);bounded(exactCode,20_000);return hash(new JSONArray().put("account").put(accountId).put(exactCode).toString());
    }
    public static String stableFingerprint(String exactService,String exactUsername,String exactCode) {
        bounded(exactService,1000);bounded(exactUsername,4000);bounded(exactCode,20_000);
        return hash(new JSONArray().put("service-user").put(exactService).put(exactUsername).put(exactCode).toString());
    }
    public JSONObject snapshot()throws IOException {synchronized(LOCK){return read();}}
    public boolean isReserved(String fingerprint)throws IOException {
        digest(fingerprint);JSONObject snapshot=snapshot();JSONArray entries=snapshot.optJSONArray("reservations");
        for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);if(fingerprint.equals(entry.optString("codeFingerprint"))||fingerprint.equals(stable(entry)))return true;}return false;
    }
    /** A reservation is durable before a caller can return its raw code. An uncertain attempt
     * remains reserved, rather than treating a crash as proof the code was never used. */
    public JSONObject reserve(String accountId,String codeFingerprint,String requestId,JSONObject origin)throws IOException {
        bounded(accountId,160);bounded(requestId,512);digest(codeFingerprint);JSONObject metadata=metadata(origin);
        synchronized(LOCK){
            safeRoot();if(Files.isSymbolicLink(lock.toPath()))throw new IOException("Recovery reservation lock is unavailable");
            try(FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=channel.lock()){
                JSONObject previous=read();JSONArray entries=previous.optJSONArray("reservations");String stable=metadata.optString("stableCodeFingerprint");
                for(int i=0;i<entries.length();i++){
                    JSONObject entry=entries.optJSONObject(i);
                    if(codeFingerprint.equals(entry.optString("codeFingerprint"))||!stable.isEmpty()&&stable.equals(stable(entry))){
                        if(accountId.equals(entry.optString("accountId"))&&codeFingerprint.equals(entry.optString("codeFingerprint"))&&requestId.equals(entry.optString("requestId"))){
                            write(previous);markPortablePending();return object("reserved",true,"reusedReservation",true,"code","reserved","reservation",copy(entry));
                        }
                        return object("reserved",false,"reusedReservation",false,"code","already_reserved");
                    }
                }
                JSONObject entry=object("accountId",accountId,"codeFingerprint",codeFingerprint,"requestId",requestId,"reservedAt",System.currentTimeMillis(),"origin",metadata);
                entries.put(entry);JSONObject next=create(entries,publications(previous));write(next);markPortablePending();
                return object("reserved",true,"reusedReservation",false,"code","reserved","reservation",copy(entry));
            }
        }
    }
    /** A different native publication action cannot reveal the same reserved code again.
     * The caller must mirror this claim before reveal when an external policy folder is linked.
     * Same-action retries are idempotent, including retrying a failed external publication. */
    public JSONObject claimPublication(String accountId,String codeFingerprint,String originalReservationRequestId,String publicationRequestId)throws IOException {
        bounded(accountId,160);digest(codeFingerprint);bounded(originalReservationRequestId,512);bounded(publicationRequestId,512);
        synchronized(LOCK){safeRoot();if(Files.isSymbolicLink(lock.toPath()))throw new IOException("Recovery publication lock is unavailable");
            try(FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=channel.lock()){
                JSONObject previous=read();JSONArray entries=previous.optJSONArray("reservations"),claims=publications(previous);
                if(!reservationExists(entries,accountId,codeFingerprint,originalReservationRequestId))return object("claimed",false,"reusedClaim",false,"code","reservation_not_found");
                PublicationView view=publicationView(entries,claims,accountId,codeFingerprint,originalReservationRequestId);
                if(view.conflicting||view.sealed)return object("claimed",false,"reusedClaim",false,"code",view.sealed?"publication_already_sealed":"publication_claim_conflict");
                if(view.latest!=null){
                    if(!publicationRequestId.equals(view.latest.optString("publicationRequestId")))return object("claimed",false,"reusedClaim",false,"code","publication_already_claimed");
                    write(previous);markPortablePending();return object("claimed",true,"reusedClaim",true,"code","publication_claimed","publication",copy(view.latest));
                }
                JSONObject claim=object("accountId",accountId,"codeFingerprint",codeFingerprint,"reservationRequestId",originalReservationRequestId,"publicationRequestId",publicationRequestId,"claimedAt",System.currentTimeMillis());
                claims.put(claim);write(create(entries,claims));markPortablePending();
                return object("claimed",true,"reusedClaim",false,"code","publication_claimed","publication",copy(claim));
            }
        }
    }
    /** CAS handoff is only for a native-validated withheld result. A superseded action can
     * never claim again, even if an old ZIP brings back its previous pending receipt. */
    public JSONObject transferPublicationClaim(String accountId,String codeFingerprint,String originalReservationRequestId,String expectedPublicationRequestId,String newPublicationRequestId)throws IOException {
        bounded(accountId,160);digest(codeFingerprint);bounded(originalReservationRequestId,512);bounded(expectedPublicationRequestId,512);bounded(newPublicationRequestId,512);
        synchronized(LOCK){safeRoot();if(Files.isSymbolicLink(lock.toPath()))throw new IOException("Recovery publication lock is unavailable");
            try(FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=channel.lock()){
                JSONObject previous=read();JSONArray entries=previous.optJSONArray("reservations"),claims=publications(previous);
                if(!reservationExists(entries,accountId,codeFingerprint,originalReservationRequestId))return object("claimed",false,"reusedClaim",false,"code","reservation_not_found");
                PublicationView view=publicationView(entries,claims,accountId,codeFingerprint,originalReservationRequestId);
                if(view.conflicting||view.sealed)return object("claimed",false,"reusedClaim",false,"code",view.sealed?"publication_already_sealed":"publication_claim_conflict");
                if(view.latest==null)return object("claimed",false,"reusedClaim",false,"code","publication_claim_not_found");
                String latest=view.latest.optString("publicationRequestId");
                if(newPublicationRequestId.equals(latest)&&expectedPublicationRequestId.equals(view.latest.optString("supersedesPublicationRequestId"))){write(previous);markPortablePending();return object("claimed",true,"reusedClaim",true,"code","publication_claimed","publication",copy(view.latest));}
                if(!expectedPublicationRequestId.equals(latest)||expectedPublicationRequestId.equals(newPublicationRequestId)||view.ids.contains(newPublicationRequestId))return object("claimed",false,"reusedClaim",false,"code","publication_claim_stale");
                JSONObject claim=object("accountId",accountId,"codeFingerprint",codeFingerprint,"reservationRequestId",originalReservationRequestId,"publicationRequestId",newPublicationRequestId,"claimedAt",System.currentTimeMillis(),"supersedesPublicationRequestId",expectedPublicationRequestId);
                claims.put(claim);write(create(entries,claims));markPortablePending();return object("claimed",true,"reusedClaim",false,"code","publication_claimed","publication",copy(claim));
            }
        }
    }
    /** Terminal disclosure fence, durable before the raw return. A caller must subsequently
     * mirror this seal when linked. Crash or mirror failure after sealing is not retried as
     * a new disclosure; this does not assert successful authentication/server consumption. */
    public JSONObject sealPublication(String accountId,String codeFingerprint,String originalReservationRequestId,String publicationRequestId)throws IOException {
        bounded(accountId,160);digest(codeFingerprint);bounded(originalReservationRequestId,512);bounded(publicationRequestId,512);
        synchronized(LOCK){safeRoot();if(Files.isSymbolicLink(lock.toPath()))throw new IOException("Recovery publication lock is unavailable");
            try(FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=channel.lock()){
                JSONObject previous=read();JSONArray entries=previous.optJSONArray("reservations"),claims=publications(previous);
                PublicationView view=publicationView(entries,claims,accountId,codeFingerprint,originalReservationRequestId);
                if(view.conflicting||view.sealed)return object("sealed",false,"newlySealed",false,"code",view.sealed?"publication_already_sealed":"publication_claim_conflict");
                if(view.latest==null||!publicationRequestId.equals(view.latest.optString("publicationRequestId")))return object("sealed",false,"newlySealed",false,"code","publication_claim_stale");
                JSONObject seal=object("accountId",accountId,"codeFingerprint",codeFingerprint,"reservationRequestId",originalReservationRequestId,"publicationRequestId",publicationRequestId,"claimedAt",System.currentTimeMillis(),"sealed",true);
                claims.put(seal);write(create(entries,claims));markPortablePending();return object("sealed",true,"newlySealed",true,"code","publication_sealed","publication",copy(seal));
            }
        }
    }
    /** Metadata-only state; a claim does not prove that a provider consumed the code or that
     * the raw return reached its caller. Conflicting portable histories remain claimed. */
    public JSONObject publicationState(String accountId,String codeFingerprint,String originalReservationRequestId)throws IOException {
        bounded(accountId,160);digest(codeFingerprint);bounded(originalReservationRequestId,512);JSONObject state=snapshot();JSONArray claims=publications(state);
        PublicationView view=publicationView(state.optJSONArray("reservations"),claims,accountId,codeFingerprint,originalReservationRequestId);
        JSONObject result=object("reservationExists",reservationExists(state.optJSONArray("reservations"),accountId,codeFingerprint,originalReservationRequestId),"claimed",!view.ids.isEmpty(),"sealed",view.sealed,"conflictingClaims",view.conflicting,"metadataOnly",true);
        if(view.latest!=null&&!view.conflicting)put(result,"publication",copy(view.latest));return result;
    }
    /** Portable anchor access, preserving a strictly validated legacy snapshot's checksum. */
    public static JSONArray publicationClaims(JSONObject supplied)throws IOException {try{return new JSONArray(publications(validate(supplied)).toString());}catch(JSONException invalid){throw new IOException("Invalid recovery publication metadata",invalid);}}
    private static JSONArray publications(JSONObject snapshot){JSONArray claims=snapshot.optJSONArray("publications");return claims==null?new JSONArray():claims;}
    private static boolean reservationExists(JSONArray entries,String accountId,String fingerprint,String request){for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);if(accountId.equals(entry.optString("accountId"))&&fingerprint.equals(entry.optString("codeFingerprint"))&&request.equals(entry.optString("requestId")))return true;}return false;}
    private static boolean publicationMatches(JSONObject claim,String accountId,String fingerprint){return accountId.equals(claim.optString("accountId"))&&fingerprint.equals(claim.optString("codeFingerprint"));}
    private static final class PublicationView {JSONObject latest;boolean conflicting,sealed;final Set<String> ids=new HashSet<>();}
    private static PublicationView publicationView(JSONArray reservations,JSONArray events,String account,String fingerprint,String reservation){
        PublicationView view=new PublicationView();Map<String,JSONObject> nodes=new LinkedHashMap<>();Map<String,String> children=new HashMap<>();Set<String> seals=new HashSet<>();String first=null;
        // Two independently valid portable histories can reserve the same issuer/user/code
        // under different account UUIDs. Their native publication claims must still collide.
        Set<String> stableScopes=new HashSet<>(),aliases=new HashSet<>();
        for(int i=0;i<reservations.length();i++){JSONObject entry=reservations.optJSONObject(i);if(account.equals(entry.optString("accountId"))&&fingerprint.equals(entry.optString("codeFingerprint"))&&reservation.equals(entry.optString("requestId"))&&!stable(entry).isEmpty())stableScopes.add(stable(entry));}
        if(stableScopes.size()>1)view.conflicting=true;
        for(int i=0;i<reservations.length();i++){JSONObject entry=reservations.optJSONObject(i);if(stableScopes.contains(stable(entry)))aliases.add(new JSONArray().put(entry.optString("accountId")).put(entry.optString("codeFingerprint")).toString());}
        for(int i=0;i<events.length();i++){JSONObject event=events.optJSONObject(i);boolean exact=publicationMatches(event,account,fingerprint);if(!exact&&!aliases.contains(new JSONArray().put(event.optString("accountId")).put(event.optString("codeFingerprint")).toString()))continue;
            if(!exact){view.ids.add(event.optString("publicationRequestId"));view.conflicting=true;if(Boolean.TRUE.equals(event.opt("sealed")))view.sealed=true;continue;}
            if(!reservation.equals(event.optString("reservationRequestId"))){view.conflicting=true;continue;}
            String id=event.optString("publicationRequestId");view.ids.add(id);
            if(Boolean.TRUE.equals(event.opt("sealed"))){seals.add(id);continue;}
            JSONObject prior=nodes.putIfAbsent(id,event);String parent=event.optString("supersedesPublicationRequestId");
            if(prior!=null&&!parent.equals(prior.optString("supersedesPublicationRequestId")))view.conflicting=true;
            if(parent.isEmpty()){if(first!=null&&!first.equals(id))view.conflicting=true;first=id;}else{String child=children.putIfAbsent(parent,id);if(child!=null&&!child.equals(id))view.conflicting=true;}
        }
        if(nodes.isEmpty()){if(!seals.isEmpty())view.conflicting=true;return view;}
        if(first==null){view.conflicting=true;return view;}Set<String> visited=new HashSet<>();String cursor=first;
        while(cursor!=null){if(!visited.add(cursor)||!nodes.containsKey(cursor)){view.conflicting=true;break;}view.latest=nodes.get(cursor);cursor=children.get(cursor);}
        if(visited.size()!=nodes.size())view.conflicting=true;
        for(String seal:seals){if(!nodes.containsKey(seal)||view.latest==null||!seal.equals(view.latest.optString("publicationRequestId")))view.conflicting=true;view.sealed=true;}
        return view;
    }
    public JSONObject mergeSnapshot(JSONObject supplied)throws IOException {
        JSONObject incoming=validate(supplied);
        synchronized(LOCK){safeRoot();if(Files.isSymbolicLink(lock.toPath()))throw new IOException("Recovery reservation lock is unavailable");
            try(FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock held=channel.lock()){
                JSONObject previous=read(),next=unionSnapshots(previous,incoming);
                if(!previous.optString("checksum").equals(next.optString("checksum")))write(next);
                return copy(next);
            }
        }
    }
    public static JSONObject emptySnapshot()throws IOException{return create(new JSONArray(),new JSONArray());}
    public static JSONObject unionSnapshots(JSONObject first,JSONObject second)throws IOException {
        JSONObject a=validate(first),b=validate(second);Map<String,JSONObject> entries=new LinkedHashMap<>();
        for(JSONObject snapshot:new JSONObject[]{a,b}){JSONArray values=snapshot.optJSONArray("reservations");for(int i=0;i<values.length();i++){
            JSONObject entry=values.optJSONObject(i);entries.putIfAbsent(hash(canonical(entry)),entry);
        }}
        JSONArray merged=new JSONArray();for(JSONObject entry:entries.values())merged.put(entry);Map<String,JSONObject> claims=new LinkedHashMap<>();
        for(JSONObject snapshot:new JSONObject[]{a,b}){JSONArray values=publications(snapshot);for(int i=0;i<values.length();i++){JSONObject claim=values.optJSONObject(i);claims.putIfAbsent(hash(canonical(claim)),claim);}}
        JSONArray mergedClaims=new JSONArray();for(JSONObject claim:claims.values())mergedClaims.put(claim);return create(merged,mergedClaims);
    }
    public static JSONObject validate(JSONObject supplied)throws IOException {
        if(supplied==null)throw new IOException("Recovery reservation snapshot is missing");JSONObject snapshot=copy(supplied);
        boolean legacy=LEGACY_FORMAT.equals(snapshot.optString("format"));
        if(!(legacy||FORMAT.equals(snapshot.optString("format")))||snapshot.length()!=(legacy?3:4)||snapshot.optJSONArray("reservations")==null||!legacy&&snapshot.optJSONArray("publications")==null)throw new IOException("Invalid recovery reservation snapshot");
        String checksum=snapshot.optString("checksum");digest(checksum);snapshot.remove("checksum");
        if(!checksum.equals(hash(canonical(snapshot))))throw new IOException("Recovery reservation snapshot checksum changed");
        JSONArray entries=snapshot.optJSONArray("reservations");if(entries.length()>MAX_ENTRIES)throw new IOException("Recovery reservation history exceeds capacity");Set<String> exact=new HashSet<>(),reservationIdentities=new HashSet<>();
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.optJSONObject(i);if(entry==null||entry.length()!=5)throw new IOException("Invalid recovery reservation entry");
            text(entry,"accountId",160);text(entry,"requestId",512);digest(entry.optString("codeFingerprint"));nonnegative(entry.opt("reservedAt"));metadata(entry.optJSONObject("origin"));
            if(!exact.add(hash(canonical(entry))))throw new IOException("Duplicate recovery reservation entry");
            reservationIdentities.add(new JSONArray().put(entry.optString("accountId")).put(entry.optString("codeFingerprint")).put(entry.optString("requestId")).toString());
        }
        JSONArray claims=publications(snapshot);if(claims.length()>MAX_ENTRIES)throw new IOException("Recovery publication history exceeds capacity");Set<String> claimEvents=new HashSet<>();
        for(int i=0;i<claims.length();i++){
            JSONObject claim=claims.optJSONObject(i);boolean transfer=claim!=null&&claim.has("supersedesPublicationRequestId"),seal=claim!=null&&claim.has("sealed");
            if(claim==null||transfer&&seal||claim.length()!=((transfer||seal)?6:5))throw new IOException("Invalid recovery publication claim");
            text(claim,"accountId",160);digest(claim.optString("codeFingerprint"));text(claim,"reservationRequestId",512);text(claim,"publicationRequestId",512);nonnegative(claim.opt("claimedAt"));
            if(transfer){text(claim,"supersedesPublicationRequestId",512);if(claim.optString("publicationRequestId").equals(claim.optString("supersedesPublicationRequestId")))throw new IOException("Recovery publication cannot supersede itself");}
            if(seal&&!Boolean.TRUE.equals(claim.opt("sealed")))throw new IOException("Invalid recovery publication seal");
            if(!reservationIdentities.contains(new JSONArray().put(claim.optString("accountId")).put(claim.optString("codeFingerprint")).put(claim.optString("reservationRequestId")).toString()))throw new IOException("Recovery publication has no exact reservation");
            if(!claimEvents.add(hash(canonical(claim))))throw new IOException("Duplicate recovery publication claim");
        }
        put(snapshot,"checksum",checksum);if(snapshot.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("Recovery reservation history exceeds its byte capacity");return snapshot;
    }
    private JSONObject read()throws IOException {
        safeRoot();if(Files.isSymbolicLink(ledger.toPath())||Files.isSymbolicLink(anchor.toPath()))throw new IOException("Recovery reservation storage is unavailable");
        JSONObject state=ledger.exists()?validate(readObject(ledger,MAX_BYTES)):emptySnapshot();
        if(anchor.exists()){
            JSONObject established=readObject(anchor,1024);boolean legacy=LEGACY_FORMAT.equals(established.optString("format"));if(!(legacy||FORMAT.equals(established.optString("format")))||established.length()!=(legacy?3:5))throw new IOException("Invalid recovery reservation anchor");
            long count=nonnegative(established.opt("count"));String expected=established.optString("prefixHash");digest(expected);JSONArray entries=state.optJSONArray("reservations"),prefix=new JSONArray();
            if(count>entries.length())throw new IOException("Recovery reservation history was rolled back");for(int i=0;i<count;i++)prefix.put(entries.opt(i));
            if(!hash(canonical(prefix)).equals(expected))throw new IOException("Recovery reservation history differs from its anchor");
            if(!legacy){long publicationCount=nonnegative(established.opt("publicationCount"));String publicationHash=established.optString("publicationPrefixHash");digest(publicationHash);JSONArray claims=publications(state),publishedPrefix=new JSONArray();
                if(publicationCount>claims.length())throw new IOException("Recovery publication history was rolled back");for(int i=0;i<publicationCount;i++)publishedPrefix.put(claims.opt(i));
                if(!hash(canonical(publishedPrefix)).equals(publicationHash))throw new IOException("Recovery publication history differs from its anchor");
            }
        }else if(ledger.exists())throw new IOException("Established recovery reservation anchor is missing");
        return state;
    }
    private void write(JSONObject supplied)throws IOException {
        JSONObject checked=validate(supplied),snapshot=create(checked.optJSONArray("reservations"),publications(checked));safeRoot();
        // Establish the empty anchor first. If first publication is interrupted, a complete
        // new ledger can be read with this valid prefix; an established history cannot vanish.
        if(!anchor.exists())atomic(anchor,object("format",FORMAT,"count",0,"prefixHash",hash(canonical(new JSONArray())),"publicationCount",0,"publicationPrefixHash",hash(canonical(new JSONArray()))).toString());
        atomic(ledger,snapshot.toString());JSONArray entries=snapshot.optJSONArray("reservations");
        JSONArray claims=publications(snapshot);atomic(anchor,object("format",FORMAT,"count",entries.length(),"prefixHash",hash(canonical(entries)),"publicationCount",claims.length(),"publicationPrefixHash",hash(canonical(claims))).toString());
    }
    private void markPortablePending()throws IOException {
        // Durable queueing does not claim that a shared-folder provider has completed its copy.
        new ExternalOwnerPolicyFolder(context).markPendingDurably();
    }
    private void safeRoot()throws IOException {if(!root.isDirectory()||Files.isSymbolicLink(root.toPath()))throw new IOException("Recovery reservation directory is unavailable");}
    private void atomic(File target,String body)throws IOException {
        if(Files.isSymbolicLink(target.toPath()))throw new IOException("Recovery reservation publication path changed");File temporary=new File(root,".recovery-reservation-partial-"+UUID.randomUUID());
        try{try(FileOutputStream output=new FileOutputStream(temporary)){output.write(body.getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(FileChannel directory=FileChannel.open(root.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){directory.force(true);}
            catch(IOException|UnsupportedOperationException nio){java.io.FileDescriptor descriptor=null;
                try{descriptor=android.system.Os.open(root.getPath(),android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_NOFOLLOW,0);android.system.Os.fsync(descriptor);}
                catch(android.system.ErrnoException failure){throw new IOException("Recovery reservation directory sync failed",failure);}
                finally{if(descriptor!=null)try{android.system.Os.close(descriptor);}catch(android.system.ErrnoException ignored){}}
            }
        }finally{if(temporary.exists()&&!temporary.delete())throw new IOException("Incomplete recovery reservation could not be cleaned up");}
    }
    private static JSONObject metadata(JSONObject supplied)throws IOException {
        if(supplied==null)throw new IOException("Native recovery reservation origin is required");JSONObject origin=copy(supplied);
        Set<String> allowed=new HashSet<>(Arrays.asList("stableCodeFingerprint","accountRecordHash","accountRevision","inputActor","sourceMessageId","sourceMessageHash","sourceRefs"));
        Iterator<String> names=origin.keys();while(names.hasNext())if(!allowed.contains(names.next()))throw new IOException("Recovery origin accepts only reference metadata, not credentials");
        for(String key:new String[]{"stableCodeFingerprint","accountRecordHash","sourceMessageHash"})if(origin.has(key))digest(origin.optString(key));
        for(String key:new String[]{"accountRevision","sourceMessageId"})if(origin.has(key)&&nonnegative(origin.opt(key))==0)throw new IOException("Native recovery reference must be positive");
        if(origin.has("inputActor"))text(origin,"inputActor",80);
        if(origin.has("sourceRefs")){JSONArray refs=origin.optJSONArray("sourceRefs");if(refs==null||refs.length()>128)throw new IOException("Invalid native recovery sources");for(int i=0;i<refs.length();i++){
            JSONObject ref=refs.optJSONObject(i);if(ref==null||ref.length()!=3||!("message".equals(ref.optString("type"))||"device_event".equals(ref.optString("type")))||nonnegative(ref.opt("id"))==0)throw new IOException("Invalid native recovery reference");digest(ref.optString("hash"));
        }}
        return origin;
    }
    private static String stable(JSONObject entry){JSONObject origin=entry.optJSONObject("origin");return origin==null?"":origin.optString("stableCodeFingerprint");}
    private static JSONObject create(JSONArray entries,JSONArray claims)throws IOException {JSONObject result=object("format",FORMAT,"reservations",entries,"publications",claims);put(result,"checksum",hash(canonical(result)));return validate(result);}
    private static JSONObject readObject(File file,long maximum)throws IOException {
        if(!file.isFile()||file.length()>maximum)throw new IOException("Recovery reservation file is unavailable or oversized");String text=new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);HoyoungStore.checkJsonDepth(text);
        try{JSONTokener parser=new JSONTokener(text);Object value=parser.nextValue();if(!(value instanceof JSONObject)||parser.nextClean()!=0)throw new IOException("Invalid recovery reservation document");return (JSONObject)value;}catch(JSONException invalid){throw new IOException("Invalid recovery reservation JSON",invalid);}
    }
    private static JSONObject copy(JSONObject object)throws IOException {try{return new JSONObject(object.toString());}catch(JSONException invalid){throw new IOException("Invalid recovery reservation metadata",invalid);}}
    private static long nonnegative(Object value)throws IOException {if(!(value instanceof Number))throw new IOException("Recovery reservation integer is required");Number n=(Number)value;long result=n.longValue();if(result<0||!Double.isFinite(n.doubleValue())||n.doubleValue()!=(double)result)throw new IOException("Invalid recovery reservation integer");return result;}
    private static void text(JSONObject object,String key,int max)throws IOException {Object value=object.opt(key);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>max)throw new IOException("Invalid recovery reference text");}
    private static void bounded(String value,int max){if(value==null||value.isEmpty()||value.length()>max)throw new IllegalArgumentException("Invalid recovery fingerprint scope");}
    private static void digest(String value)throws IOException {if(value==null||!value.matches("[a-f0-9]{64}"))throw new IOException("Invalid recovery reservation fingerprint");}
    private static String hash(String value){return OriginalTextFiles.hex(OriginalTextFiles.digest().digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static String canonical(Object value){
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);Collections.sort(keys);StringBuilder text=new StringBuilder("{");for(String key:keys){if(text.length()>1)text.append(',');text.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key)));}return text.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder text=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)text.append(',');text.append(canonical(array.opt(i)));}return text.append(']').toString();}
        return value==null||value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    private static JSONObject object(Object...pairs){JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)put(result,(String)pairs[i],pairs[i+1]);return result;}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
}
