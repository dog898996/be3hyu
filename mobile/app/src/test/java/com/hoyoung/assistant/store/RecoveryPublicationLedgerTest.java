package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Synthetic metadata only; no real recovery code, account, provider or model call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RecoveryPublicationLedgerTest {
    private static final String ACCOUNT="synthetic-account", CODE="SYNTHETIC-ONE-USE-🌙", RESERVATION="synthetic-native-reservation";
    private Context context;private HoyoungStore store;private RecoveryCodeLedger ledger;private InternalFiles files;private String fingerprint;
    @Before public void before()throws Exception {context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());store=new HoyoungStore(context);ledger=new RecoveryCodeLedger(context);files=new InternalFiles(context);fingerprint=RecoveryCodeLedger.fingerprint(ACCOUNT,CODE);}
    @After public void after()throws Exception {store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());}
    private void reserve()throws Exception {assertTrue(ledger.reserve(ACCOUNT,fingerprint,RESERVATION,new JSONObject().put("stableCodeFingerprint",RecoveryCodeLedger.stableFingerprint("synthetic-service","synthetic-owner",CODE))).getBoolean("reserved"));}
    private JSONObject claim(String action)throws Exception {return ledger.claimPublication(ACCOUNT,fingerprint,RESERVATION,action);}
    private JSONObject transfer(String expected,String next)throws Exception {return ledger.transferPublicationClaim(ACCOUNT,fingerprint,RESERVATION,expected,next);}
    private JSONObject seal(String action)throws Exception {return ledger.sealPublication(ACCOUNT,fingerprint,RESERVATION,action);}
    private JSONObject state()throws Exception {return ledger.publicationState(ACCOUNT,fingerprint,RESERVATION);}

    @Test public void claimRequiresActualExactReservationBeforeAnyPublicationWrite()throws Exception {
        assertEquals("reservation_not_found",claim("publish-a").getString("code"));assertEquals(0,ledger.snapshot().getJSONArray("publications").length());reserve();
        assertEquals("reservation_not_found",ledger.claimPublication(ACCOUNT,fingerprint,"different-original-action","publish-a").getString("code"));
        assertEquals("reservation_not_found",ledger.claimPublication("different-account",fingerprint,RESERVATION,"publish-a").getString("code"));assertFalse(state().getBoolean("claimed"));
    }
    @Test public void initialPublicationIsDurableMetadataOnlyAndOnlySameActionCanRetryBeforeSeal()throws Exception {
        reserve();JSONObject first=claim("publish-a");assertTrue(first.getBoolean("claimed"));assertFalse(first.getBoolean("reusedClaim"));assertTrue(claim("publish-a").getBoolean("reusedClaim"));assertFalse(claim("publish-b").getBoolean("claimed"));
        JSONObject actual=new RecoveryCodeLedger(context).publicationState(ACCOUNT,fingerprint,RESERVATION);assertTrue(actual.getBoolean("claimed"));assertFalse(actual.getBoolean("sealed"));assertTrue(actual.getBoolean("metadataOnly"));assertEquals("publish-a",actual.getJSONObject("publication").getString("publicationRequestId"));
        assertEquals(1,ledger.snapshot().getJSONArray("publications").length());assertFalse(first.toString().contains(CODE));assertFalse(actual.toString().contains(CODE));assertFalse(OwnerPolicyBackup.capture(context,store).toString().contains(CODE));assertTrue(new ExternalOwnerPolicyFolder(context).pending());
    }
    @Test public void concurrentNativeCasTransfersHaveOneWinnerAndOldActionNeverRegainsClaim()throws Exception {
        reserve();claim("publish-a");ExecutorService pool=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);List<Future<JSONObject>> futures=new ArrayList<>();String winner=null;
        try{for(int i=0;i<8;i++){String next="publish-race-"+i;futures.add(pool.submit(()->{start.await();return new RecoveryCodeLedger(context).transferPublicationClaim(ACCOUNT,fingerprint,RESERVATION,"publish-a",next);}));}start.countDown();int winners=0;
            for(Future<JSONObject> future:futures){JSONObject result=future.get(10,TimeUnit.SECONDS);if(result.getBoolean("claimed")){winners++;winner=result.getJSONObject("publication").getString("publicationRequestId");}}assertEquals(1,winners);
        }finally{pool.shutdownNow();}
        assertFalse(claim("publish-a").getBoolean("claimed"));assertFalse(transfer("publish-a","another-new-action").getBoolean("claimed"));assertTrue(transfer("publish-a",winner).getBoolean("reusedClaim"));assertEquals(winner,state().getJSONObject("publication").getString("publicationRequestId"));assertEquals(2,ledger.snapshot().getJSONArray("publications").length());
    }
    @Test public void linearHandoffsRemainAppendOnlyAndStaleReferencesCannotBypassNewestClaim()throws Exception {
        reserve();claim("publish-a");assertTrue(transfer("publish-a","publish-b").getBoolean("claimed"));assertTrue(transfer("publish-b","publish-c").getBoolean("claimed"));
        assertFalse(transfer("publish-a","publish-d").getBoolean("claimed"));assertFalse(claim("publish-b").getBoolean("claimed"));assertFalse(transfer("publish-c","publish-a").getBoolean("claimed"));assertFalse(state().getBoolean("conflictingClaims"));
        assertEquals("publish-c",state().getJSONObject("publication").getString("publicationRequestId"));assertEquals(3,ledger.snapshot().getJSONArray("publications").length());
    }
    @Test public void terminalSealAllowsOneDisclosureBoundaryAndBlocksEveryLaterClaimOrTransfer()throws Exception {
        reserve();claim("publish-a");assertFalse(seal("wrong-action").getBoolean("sealed"));JSONObject first=seal("publish-a");assertTrue(first.getBoolean("sealed"));assertTrue(first.getBoolean("newlySealed"));assertFalse(seal("publish-a").getBoolean("sealed"));
        assertFalse(claim("publish-a").getBoolean("claimed"));assertFalse(claim("publish-b").getBoolean("claimed"));assertEquals("publication_already_sealed",transfer("publish-a","publish-b").getString("code"));assertTrue(state().getBoolean("sealed"));assertEquals(2,ledger.snapshot().getJSONArray("publications").length());
    }
    @Test public void oldZipAndOldPendingPortableRecordCannotUnsealLaterDisclosure()throws Exception {
        reserve();claim("publish-a");JSONObject pending=OwnerPolicyBackup.capture(context,store);byte[] old=backup();seal("publish-a");
        files.importBackup(new ByteArrayInputStream(old),store);OwnerPolicyBackup.merge(context,store,pending);OwnerPolicyBackup.reconcile(context,store);
        assertTrue(state().getBoolean("sealed"));assertFalse(transfer("publish-a","publish-new-root").getBoolean("claimed"));assertFalse(claim("publish-a").getBoolean("claimed"));assertEquals(2,OwnerPolicyBackup.capture(context,store).getJSONObject("recoveryReservations").getJSONArray("publications").length());
    }
    @Test public void freshReinstallationPortableMergeThenOlderZipRestoreRetainsTerminalSeal()throws Exception {
        reserve();claim("publish-a");byte[] old=backup();seal("publish-a");JSONObject controls=OwnerPolicyBackup.capture(context,store);
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());store=new HoyoungStore(context);ledger=new RecoveryCodeLedger(context);files=new InternalFiles(context);
        OwnerPolicyBackup.merge(context,store,controls);files.importBackup(new ByteArrayInputStream(old),store);OwnerPolicyBackup.reconcile(context,store);
        assertTrue(state().getBoolean("sealed"));assertFalse(transfer("publish-a","publish-after-reinstall").getBoolean("claimed"));assertEquals(2,ledger.snapshot().getJSONArray("publications").length());
    }
    @Test public void portableAndLocalPublicationAnchorsRejectRollbackOfKnownTerminalSeal()throws Exception {
        reserve();claim("publish-a");JSONObject oldLedger=ledger.snapshot(),oldControls=OwnerPolicyBackup.capture(context,store);seal("publish-a");OwnerPolicyBackup.merge(context,store,OwnerPolicyBackup.capture(context,store));
        Files.write(new File(context.getFilesDir(),"owner-policy-portable.json").toPath(),oldControls.toString().getBytes(StandardCharsets.UTF_8));assertThrows(IOException.class,()->OwnerPolicyBackup.capture(context,store));
        Files.write(new File(context.getFilesDir(),"recovery-code-reservations.json").toPath(),oldLedger.toString().getBytes(StandardCharsets.UTF_8));assertThrows(IOException.class,()->ledger.snapshot());
    }
    @Test public void completeSealTailSurvivesInterruptedAnchorAdvanceAndCannotRevealAgain()throws Exception {
        reserve();claim("publish-a");File anchor=new File(context.getFilesDir(),"recovery-code-reservations.anchor.json");byte[] before=Files.readAllBytes(anchor.toPath());seal("publish-a");Files.write(anchor.toPath(),before);
        assertTrue(state().getBoolean("sealed"));assertFalse(claim("publish-a").getBoolean("claimed"));assertTrue(ledger.reserve(ACCOUNT,fingerprint,RESERVATION,new JSONObject()).getBoolean("reusedReservation"));assertEquals(2,new JSONObject(new String(Files.readAllBytes(anchor.toPath()),StandardCharsets.UTF_8)).getInt("publicationCount"));
    }
    @Test public void legacySnapshotAndLegacyAnchorUpgradeWithoutLosingOriginalReservations()throws Exception {
        reserve();JSONObject legacy=ledger.snapshot();legacy.put("format",RecoveryCodeLedger.LEGACY_FORMAT);legacy.remove("publications");resign(legacy);JSONArray reservations=legacy.getJSONArray("reservations");
        Files.write(new File(context.getFilesDir(),"recovery-code-reservations.json").toPath(),legacy.toString().getBytes(StandardCharsets.UTF_8));JSONObject anchor=new JSONObject().put("format",RecoveryCodeLedger.LEGACY_FORMAT).put("count",reservations.length()).put("prefixHash",digest(canonical(reservations)));
        Files.write(new File(context.getFilesDir(),"recovery-code-reservations.anchor.json").toPath(),anchor.toString().getBytes(StandardCharsets.UTF_8));assertEquals(RecoveryCodeLedger.LEGACY_FORMAT,ledger.snapshot().getString("format"));
        assertEquals(0,RecoveryCodeLedger.publicationClaims(legacy).length());assertTrue(claim("publish-after-upgrade").getBoolean("claimed"));assertEquals(RecoveryCodeLedger.FORMAT,ledger.snapshot().getString("format"));assertEquals(1,ledger.snapshot().getJSONArray("reservations").length());assertEquals(1,ledger.snapshot().getJSONArray("publications").length());assertEquals("publish-after-upgrade",state().getJSONObject("publication").getString("publicationRequestId"));
    }
    @Test public void divergentPortableClaimsRemainConflictAndNeverBecomeAnAvailableCode()throws Exception {
        reserve();JSONObject baseline=ledger.snapshot();claim("publish-local");Context remote=new android.content.ContextWrapper(context){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){File root=new File(context.getFilesDir(),"synthetic-remote-publications");root.mkdirs();return root;}};
        RecoveryCodeLedger other=new RecoveryCodeLedger(remote);other.mergeSnapshot(baseline);assertTrue(other.claimPublication(ACCOUNT,fingerprint,RESERVATION,"publish-remote").getBoolean("claimed"));ledger.mergeSnapshot(other.snapshot());
        assertTrue(state().getBoolean("conflictingClaims"));assertFalse(claim("publish-local").getBoolean("claimed"));assertFalse(transfer("publish-remote","publish-new").getBoolean("claimed"));assertFalse(seal("publish-local").getBoolean("sealed"));assertEquals(2,ledger.snapshot().getJSONArray("publications").length());
    }
    @Test public void independentlyRecreatedAccountUuidCannotDiscloseSameStableCodeAfterPortableMergeAndRestore()throws Exception {
        reserve();JSONObject originalPending=ledger.snapshot();Context remote=new android.content.ContextWrapper(context){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){File root=new File(context.getFilesDir(),"synthetic-recreated-account-publications");root.mkdirs();return root;}};
        RecoveryCodeLedger other=new RecoveryCodeLedger(remote);String otherAccount="synthetic-recreated-account",otherFingerprint=RecoveryCodeLedger.fingerprint(otherAccount,CODE),otherReservation="synthetic-other-native-reservation";
        assertTrue(other.reserve(otherAccount,otherFingerprint,otherReservation,new JSONObject().put("stableCodeFingerprint",RecoveryCodeLedger.stableFingerprint("synthetic-service","synthetic-owner",CODE))).getBoolean("reserved"));
        assertTrue(other.claimPublication(otherAccount,otherFingerprint,otherReservation,"publish-recreated").getBoolean("claimed"));assertTrue(other.sealPublication(otherAccount,otherFingerprint,otherReservation,"publish-recreated").getBoolean("sealed"));
        ledger.mergeSnapshot(other.snapshot());assertTrue(state().getBoolean("sealed"));assertTrue(state().getBoolean("conflictingClaims"));assertFalse(claim("publish-original").getBoolean("claimed"));
        JSONObject controls=OwnerPolicyBackup.capture(context,store);store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());store=new HoyoungStore(context);ledger=new RecoveryCodeLedger(context);files=new InternalFiles(context);
        OwnerPolicyBackup.merge(context,store,controls);ledger.mergeSnapshot(originalPending);assertTrue(state().getBoolean("sealed"));assertFalse(claim("publish-after-old-merge").getBoolean("claimed"));assertEquals(2,ledger.snapshot().getJSONArray("reservations").length());assertFalse(OwnerPolicyBackup.capture(context,store).toString().contains(CODE));
    }
    @Test public void stableAliasPendingClaimsBlockCompetingDisclosureWithoutPretendingToBeSealed()throws Exception {
        reserve();Context remote=new android.content.ContextWrapper(context){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){File root=new File(context.getFilesDir(),"synthetic-pending-alias-publications");root.mkdirs();return root;}};
        RecoveryCodeLedger other=new RecoveryCodeLedger(remote);String otherFingerprint=RecoveryCodeLedger.fingerprint("synthetic-alias",CODE);other.reserve("synthetic-alias",otherFingerprint,"alias-reservation",new JSONObject().put("stableCodeFingerprint",RecoveryCodeLedger.stableFingerprint("synthetic-service","synthetic-owner",CODE)));other.claimPublication("synthetic-alias",otherFingerprint,"alias-reservation","alias-publication");ledger.mergeSnapshot(other.snapshot());
        assertTrue(state().getBoolean("claimed"));assertFalse(state().getBoolean("sealed"));assertTrue(state().getBoolean("conflictingClaims"));assertFalse(claim("publish-original").getBoolean("claimed"));assertFalse(transfer("alias-publication","publish-original").getBoolean("claimed"));
    }
    @Test public void publicationSchemaCannotSmuggleRawValuesOrInventAnUnreservedCandidate()throws Exception {
        reserve();claim("publish-a");JSONObject bad=ledger.snapshot();bad.getJSONArray("publications").getJSONObject(0).put("password",CODE);resign(bad);assertThrows(IOException.class,()->ledger.mergeSnapshot(bad));
        JSONObject unknown=ledger.snapshot();unknown.getJSONArray("publications").getJSONObject(0).put("codeFingerprint","b".repeat(64));resign(unknown);assertThrows(IOException.class,()->ledger.mergeSnapshot(unknown));assertEquals(1,ledger.snapshot().getJSONArray("publications").length());assertFalse(ledger.snapshot().toString().contains(CODE));
    }
    private byte[] backup()throws Exception {ByteArrayOutputStream output=new ByteArrayOutputStream();files.exportBackup(output,store);return output.toByteArray();}
    private static void resign(JSONObject value)throws Exception {value.remove("checksum");value.put("checksum",digest(canonical(value)));}
    private static String digest(String text){return OriginalTextFiles.hex(OriginalTextFiles.digest().digest(text.getBytes(StandardCharsets.UTF_8)));}
    private static String canonical(Object value){if(value instanceof JSONObject){JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);Collections.sort(keys);StringBuilder text=new StringBuilder("{");for(String key:keys){if(text.length()>1)text.append(',');text.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key)));}return text.append('}').toString();}if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder text=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)text.append(',');text.append(canonical(array.opt(i)));}return text.append(']').toString();}return value==null||value==JSONObject.NULL?"null":value instanceof String?JSONObject.quote((String)value):String.valueOf(value);}
    private static void clear(File file)throws Exception {if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("Synthetic fixture cleanup failed");for(File child:children)clear(child);}if(!file.delete())throw new IOException("Synthetic fixture cleanup failed");}
}
