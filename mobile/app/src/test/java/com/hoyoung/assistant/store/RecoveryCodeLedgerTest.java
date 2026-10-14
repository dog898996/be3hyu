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

/** Synthetic recovery values only: no provider login, raw code logging, or real user data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RecoveryCodeLedgerTest {
    private Context context;private HoyoungStore store;private RecoveryCodeLedger ledger;private InternalFiles files;
    @Before public void before()throws Exception {context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());store=new HoyoungStore(context);ledger=new RecoveryCodeLedger(context);files=new InternalFiles(context);}
    @After public void after()throws Exception {store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());}
    private String fingerprint(String account,String code){return RecoveryCodeLedger.fingerprint(account,code);}
    private JSONObject origin(String code)throws Exception{return new JSONObject().put("stableCodeFingerprint",RecoveryCodeLedger.stableFingerprint("synthetic-service","synthetic-owner",code)).put("accountRecordHash","a".repeat(64)).put("accountRevision",1).put("inputActor","owner");}
    private JSONObject reserve(String account,String code,String request)throws Exception{return ledger.reserve(account,fingerprint(account,code),request,origin(code));}

    @Test public void reservationIsDurableAndRawValueNeverEntersLocalOrPortableLedger()throws Exception {
        String code="SYNTHETIC-RECOVERY-🌙-1234";JSONObject result=reserve("account-a",code,"request-a");assertTrue(result.getBoolean("reserved"));assertFalse(result.getBoolean("reusedReservation"));
        assertTrue(new File(context.getFilesDir(),"recovery-code-reservations.json").isFile());assertTrue(new File(context.getFilesDir(),"recovery-code-reservations.anchor.json").isFile());
        assertTrue(new RecoveryCodeLedger(context).isReserved(fingerprint("account-a",code)));assertTrue(new RecoveryCodeLedger(context).isReserved(RecoveryCodeLedger.stableFingerprint("synthetic-service","synthetic-owner",code)));
        assertFalse(result.toString().contains(code));assertFalse(ledger.snapshot().toString().contains(code));assertFalse(OwnerPolicyBackup.capture(context,store).toString().contains(code));
        assertTrue(new ExternalOwnerPolicyFolder(context).pending());
    }
    @Test public void sameRequestRetryIsIdempotentAndAnotherRequestCannotReissueCode()throws Exception {
        reserve("account-a","synthetic-code-a","request-a");JSONObject retry=reserve("account-a","synthetic-code-a","request-a");assertTrue(retry.getBoolean("reserved"));assertTrue(retry.getBoolean("reusedReservation"));
        JSONObject next=reserve("account-a","synthetic-code-a","request-b");assertFalse(next.getBoolean("reserved"));assertEquals("already_reserved",next.getString("code"));assertEquals(1,ledger.snapshot().getJSONArray("reservations").length());
    }
    @Test public void atomicReservationAllowsOnlyOneOfConcurrentNativeRequests()throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
        try{for(int i=0;i<8;i++){String request="concurrent-"+i;futures.add(pool.submit(()->{start.await();return new RecoveryCodeLedger(context).reserve("account-a",fingerprint("account-a","synthetic-race"),request,origin("synthetic-race")).getBoolean("reserved");}));}start.countDown();int winners=0;for(Future<Boolean> future:futures)if(future.get(10,TimeUnit.SECONDS))winners++;assertEquals(1,winners);assertEquals(1,ledger.snapshot().getJSONArray("reservations").length());}finally{pool.shutdownNow();}
    }
    @Test public void recreatedAccountAndCorrectedRecordDoNotResetSameRecoveryValue()throws Exception {
        reserve("account-a","synthetic-shared-code","first");assertFalse(reserve("new-account-id","synthetic-shared-code","second").getBoolean("reserved"));
        JSONObject revised=origin("synthetic-shared-code").put("accountRecordHash","b".repeat(64)).put("accountRevision",2);
        assertFalse(ledger.reserve("account-a",fingerprint("account-a","synthetic-shared-code"),"third",revised).getBoolean("reserved"));
        assertTrue(reserve("account-a","new-synthetic-recovery-code","fourth").getBoolean("reserved"));assertEquals(2,ledger.snapshot().getJSONArray("reservations").length());
    }
    @Test public void rawValuesAndAccountScopesAreExactWithoutNormalization()throws Exception {
        assertNotEquals(fingerprint("account-a","Code-1"),fingerprint("account-a","Code1"));assertNotEquals(fingerprint("account-a","Code-1"),fingerprint("account-a","Code-1 "));
        assertNotEquals(RecoveryCodeLedger.stableFingerprint("service","Owner","code"),RecoveryCodeLedger.stableFingerprint("service","owner","code"));
    }
    @Test public void restoringOlderZipRetainsNewerInstallationReservations()throws Exception {
        byte[] old=backup();reserve("account-a","synthetic-after-backup","request-after-backup");
        files.importBackup(new ByteArrayInputStream(old),store);OwnerPolicyBackup.reconcile(context,store);
        assertTrue(ledger.isReserved(fingerprint("account-a","synthetic-after-backup")));assertFalse(reserve("account-a","synthetic-after-backup","new-request").getBoolean("reserved"));
    }
    @Test public void portableUnionBeforeOldZipRestorePreservesReservationsAfterReinstallation()throws Exception {
        byte[] old=backup();reserve("account-a","synthetic-portable","request-portable");JSONObject controls=OwnerPolicyBackup.capture(context,store);
        store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());store=new HoyoungStore(context);ledger=new RecoveryCodeLedger(context);files=new InternalFiles(context);
        OwnerPolicyBackup.merge(context,store,controls);files.importBackup(new ByteArrayInputStream(old),store);OwnerPolicyBackup.reconcile(context,store);
        assertTrue(ledger.isReserved(fingerprint("account-a","synthetic-portable")));assertFalse(reserve("new-account-id","synthetic-portable","later-request").getBoolean("reserved"));
        assertEquals(1,OwnerPolicyBackup.capture(context,store).getJSONObject("recoveryReservations").getJSONArray("reservations").length());
    }
    @Test public void legacyPortableControlsMergeWithoutErasingRecoveryReservations()throws Exception {
        JSONObject legacy=OwnerPolicyBackup.capture(context,store);legacy.put("format",OwnerPolicyBackup.LEGACY_FORMAT);legacy.remove("recoveryReservations");legacy.remove("checksum");legacy.put("checksum",OriginalTextFiles.hex(OriginalTextFiles.digest().digest(legacy.toString().getBytes(StandardCharsets.UTF_8))));
        assertEquals(OwnerPolicyBackup.LEGACY_FORMAT,OwnerPolicyBackup.validate(legacy).getString("format"));reserve("account-a","synthetic-legacy-union","newer");OwnerPolicyBackup.merge(context,store,legacy);
        assertTrue(ledger.isReserved(fingerprint("account-a","synthetic-legacy-union")));assertEquals(OwnerPolicyBackup.FORMAT,OwnerPolicyBackup.capture(context,store).getString("format"));
    }
    @Test public void olderLedgerCannotPassEstablishedAnchorOrReissueOldCode()throws Exception {
        JSONObject old=ledger.snapshot();reserve("account-a","synthetic-anchor","request-anchor");File file=new File(context.getFilesDir(),"recovery-code-reservations.json");Files.write(file.toPath(),old.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,()->ledger.snapshot());assertThrows(IOException.class,()->reserve("account-a","synthetic-anchor","later"));
    }
    @Test public void missingEstablishedFilesAreNotTreatedAsUnusedCodes()throws Exception {
        reserve("account-a","synthetic-missing","request-missing");File anchor=new File(context.getFilesDir(),"recovery-code-reservations.anchor.json");byte[] saved=Files.readAllBytes(anchor.toPath());Files.delete(anchor.toPath());assertThrows(IOException.class,()->ledger.snapshot());
        Files.write(anchor.toPath(),saved);Files.delete(new File(context.getFilesDir(),"recovery-code-reservations.json").toPath());assertThrows(IOException.class,()->ledger.snapshot());
    }
    @Test public void completePublishedTailSurvivesInterruptedAnchorAdvance()throws Exception {
        reserve("account-a","synthetic-first","request-first");File anchor=new File(context.getFilesDir(),"recovery-code-reservations.anchor.json");byte[] firstAnchor=Files.readAllBytes(anchor.toPath());
        reserve("account-a","synthetic-second","request-second");Files.write(anchor.toPath(),firstAnchor);
        assertTrue(ledger.isReserved(fingerprint("account-a","synthetic-second")));assertTrue(reserve("account-a","synthetic-second","request-second").getBoolean("reusedReservation"));
        assertEquals(2,new JSONObject(new String(Files.readAllBytes(anchor.toPath()),StandardCharsets.UTF_8)).getInt("count"));
    }
    @Test public void credentialPayloadAndCorruptSnapshotFailWithoutMutatingReservationHistory()throws Exception {
        assertThrows(IOException.class,()->ledger.reserve("account-a",fingerprint("account-a","synthetic-secret"),"request",new JSONObject().put("password","synthetic-secret")));
        assertEquals(0,ledger.snapshot().getJSONArray("reservations").length());reserve("account-a","synthetic-intact","intact");JSONObject corrupt=ledger.snapshot();corrupt.getJSONArray("reservations").getJSONObject(0).put("codeFingerprint","b".repeat(64));
        assertThrows(IOException.class,()->ledger.mergeSnapshot(corrupt));assertTrue(ledger.isReserved(fingerprint("account-a","synthetic-intact")));
    }
    private byte[] backup()throws Exception {ByteArrayOutputStream output=new ByteArrayOutputStream();files.exportBackup(output,store);return output.toByteArray();}
    private static void clear(File file)throws Exception {if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("Synthetic fixture cleanup failed");for(File child:children)clear(child);}if(!file.delete())throw new IOException("Synthetic fixture cleanup failed");}
}
