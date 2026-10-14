package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import static org.junit.Assert.*;

/** Known foreign original links in pre-native-lineage receipt archives stay authoritative. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class LegacyReceiptSourceVisibilityTest {
    private Context context;private HoyoungStore store;
    @Before public void setup()throws Exception{context=RuntimeEnvironment.getApplication();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());store=new HoyoungStore(context);}
    @After public void cleanup()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());clear(context.getNoBackupFilesDir());}
    @Test public void allowedQuestionCannotAuthorizeLegacyReceiptCopyOfExcludedForeignOriginal()throws Exception {
        JSONObject question=store.addMessage("user","allowed question remains available","question-root"),foreign=store.addMessage("user","LEGACY-FOREIGN-ORIGINAL-9264",null);
        store.putSetting("requestSource:question-root",String.valueOf(question.getLong("id")));store.putSetting("requestSourceHash:question-root",question.getString("content_hash"));
        JSONObject receipt=new JSONObject().put("status","uncertain").put("name","memory.source.read").put("result",new JSONObject().put("original",store.readOriginalText(foreign.getLong("id"),0,1000)));
        JSONObject legacy=store.addMessage("system",new JSONObject().put("receiptKey","actionReceipt:question-root:old-read").put("rawReceipt",receipt.toString()).toString(),null);
        MemoryGateway gateway=new MemoryGateway(store);assertTrue(gateway.prepare("legacy-before","legacy-before","user",0,"hello",null,80000).context.contains("LEGACY-FOREIGN-ORIGINAL-9264"));
        store.getPrivacyLedger().excludeSource("message",foreign.getLong("id"),foreign.getString("content_hash"),"retrieval_exclude","owner excluded foreign original");
        MemoryGateway.Prepared prepared=gateway.prepare("legacy-after","legacy-after","user",0,"hello",null,80000);
        assertFalse(prepared.context.contains("LEGACY-FOREIGN-ORIGINAL-9264"));assertTrue(prepared.context.contains("allowed question remains available"));
        assertNotNull(store.getMessage(legacy.getLong("id")));assertNotNull(store.getMessage(question.getLong("id")));gateway.assertFresh(prepared);
    }
    private static void clear(File file)throws Exception{if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new java.io.IOException("Cannot inspect fixture");for(File child:children)clear(child);}if(!file.delete())throw new java.io.IOException("Cannot remove fixture");}
}
