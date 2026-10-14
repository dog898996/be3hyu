package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import java.io.IOException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SeparateSourceEnumeratorIntegrityTest {
    private Context context;private HoyoungStore store;private MemorySourceEnumerator enumerator;
    @Before public void before()throws Exception{context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());context.getFilesDir().mkdirs();store=new HoyoungStore(context);enumerator=new MemorySourceEnumerator(store,row->true);}
    @After public void after()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());}
    @Test public void devicePayloadCannotBorrowAnOldCanonicalRecordHash()throws Exception{JSONObject event=store.appendDeviceEvent("notification","phone",new JSONObject().put("text","valid device"),100,null);store.getWritableDatabase().execSQL("UPDATE device_events SET payload=? WHERE id=?",new Object[]{"{\"text\":\"TAMPERED-DEVICE-5528\"}",event.getLong("id")});blocked("device_event");}
    @Test public void inlineMessageCannotBorrowAnOldCanonicalRecordHash()throws Exception{long id=store.addMessage("user","valid original",null).getLong("id");store.getWritableDatabase().execSQL("UPDATE messages SET text=? WHERE id=?",new Object[]{"TAMPERED-MESSAGE-3822",id});blocked("message");}
    private void blocked(String type)throws Exception{try{enumerator.enumerate(new JSONObject().put("type",type));fail("Tampered source must not be quoted under the old hash");}catch(IOException expected){assertTrue(expected.getMessage().toLowerCase().matches(".*(integrity|hash).*"));}}
    private static void delete(File f)throws Exception{if(!f.exists())return;if(f.isDirectory()){File[] children=f.listFiles();if(children==null)throw new IOException("Unreadable fixture");for(File child:children)delete(child);}if(!f.delete())throw new IOException("Fixture cleanup failed");}
}
