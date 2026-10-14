package com.hoyoung.assistant.memory;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Independent helper regression: source identity, forgotten-source exclusion, persistent budgets. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class FiveYearNativeReaderSmokeTest {
    @Test public void nativeReaderExcludesForgottenRawAndPersistsCapsAcrossCalls() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);new InternalFiles(context);HoyoungStore store=new HoyoungStore(context);
        JSONObject allowed=store.addMessage("user","창고 보관 키는 PINE-1742야.",null),forgotten=store.addMessage("user","잊어야 하는 예전 주소는 SYNTHETIC-PRIVATE-17이야.",null);
        for(int i=0;i<25;i++)store.addMessage("user","창고 공용 준비 기록 "+i+"야.",null);
        Object ledger=store.getClass().getMethod("getPrivacyLedger").invoke(store);ledger.getClass().getMethod("excludeSource",String.class,long.class,String.class,String.class,String.class).invoke(ledger,"message",forgotten.getLong("id"),forgotten.getString("content_hash"),"retrieval_exclude","Explicit synthetic smoke control");
        try(android.database.Cursor c=store.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(FULL)",null)){while(c.moveToNext()){}}
        Path run=Files.createTempDirectory("native-reader-independent-"),privateFiles=run.resolve("native-reader-files");Files.createDirectories(privateFiles);
        Files.copy(context.getDatabasePath(HoyoungStore.DATABASE_NAME).toPath(),run.resolve("canonical-corpus.sqlite"));
        for(String file:new String[]{"privacy-ledger.json","privacy-ledger.anchor.json"})Files.copy(context.getFilesDir().toPath().resolve(file),privateFiles.resolve(file));
        Files.writeString(run.resolve("native-reader-control-snapshot.json"),new JSONObject().put("gatewayEnabled",true).put("excludedDeviceIds",new JSONArray()).put("privacyFingerprint",ledger.getClass().getMethod("fingerprint").invoke(ledger)).toString());store.close();
        StringBuilder packets=new StringBuilder();for(String id:new String[]{"allowed","forgotten","enumeration","bounded","searchBounded","historical","largeSearch"})packets.append(new JSONObject().put("id",id).put("date","2026-12-31")).append('\n');Files.writeString(run.resolve("reader-inputs.jsonl"),packets);
        Path requests=run.resolve("requests.jsonl"),output=run.resolve("responses.jsonl"),budget=run.resolve("budget.json");StringBuilder input=new StringBuilder();
        input.append(request("allowed","read",new JSONObject().put("sourceId",allowed.getLong("id")))).append('\n');
        input.append(request("forgotten","read",new JSONObject().put("sourceId",forgotten.getLong("id")))).append('\n');
        input.append(request("enumeration","enumerate",new JSONObject().put("containsLiteral","잊어야").put("limit",20))).append('\n');
        for(int i=0;i<21;i++)input.append(request("bounded","read",new JSONObject().put("sourceId",allowed.getLong("id")))).append('\n');
        for(int i=0;i<4;i++)input.append(request("searchBounded","search",new JSONObject().put("query","창고 보관 키"))).append('\n');
        input.append(request("invented-packet","read",new JSONObject().put("sourceId",allowed.getLong("id")))).append('\n');
        input.append(request("historical","search",new JSONObject().put("query","창고 보관 키").put("asOf","2021-12-31").put("toExclusive","2022-01-01"))).append('\n');
        input.append(request("largeSearch","search",new JSONObject().put("query","창고").put("limit",12))).append('\n');
        Files.writeString(requests,input);FiveYearNativeReader helper=new FiveYearNativeReader();helper.replay(run,requests,output,budget);List<String> rows=Files.readAllLines(output);
        assertTrue(new JSONObject(rows.get(0)).getBoolean("ok"));assertTrue(new JSONObject(rows.get(0)).getJSONObject("result").getString("text").contains("PINE-1742"));
        assertFalse(new JSONObject(rows.get(1)).getBoolean("ok"));assertFalse(Files.readString(output).contains("SYNTHETIC-PRIVATE-17"));
        assertEquals(0,new JSONObject(rows.get(2)).getJSONObject("result").getJSONArray("sources").length());
        assertFalse(new JSONObject(rows.get(23)).getBoolean("ok"));assertEquals(20,new JSONObject(rows.get(23)).getInt("sourceRowsRead"));assertFalse(new JSONObject(rows.get(27)).getBoolean("ok"));
        assertFalse(new JSONObject(rows.get(28)).getBoolean("ok"));assertTrue(new JSONObject(rows.get(29)).getBoolean("ok"));assertFalse(new JSONObject(rows.get(29)).getJSONObject("result").toString().contains("PINE-1742"));
        assertTrue(new JSONObject(rows.get(30)).getBoolean("ok"));assertEquals(20,new JSONObject(rows.get(30)).getInt("sourceRowsRead"));
        Files.writeString(requests,request("bounded","read",new JSONObject().put("sourceId",allowed.getLong("id")))+'\n');Path second=run.resolve("second.jsonl");helper.replay(run,requests,second,budget);assertFalse(new JSONObject(Files.readString(second)).getBoolean("ok"));
    }
    private static String request(String id,String op,JSONObject args)throws Exception{return new JSONObject().put("id",id).put("op",op).put("args",args).toString();}
}
