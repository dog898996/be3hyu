package com.hoyoung.assistant;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hoyoung.assistant.store.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class OwnerPolicyDeviceTest {
    private static final Uri TREE=DocumentsContract.buildTreeDocumentUri(OwnerPolicyFixtureDocumentsProvider.AUTHORITY,"root");
    private static final int GRANTS=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private FixtureContext context;private HoyoungStore store;private InternalFiles files;private ExternalOwnerPolicyFolder folder;
    @Before public void prepare()throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();context=new FixtureContext(target);
        target.startActivity(new Intent().setClassName("com.hoyoung.assistant.test",OwnerPolicyFixtureGrantActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long end=SystemClock.elapsedRealtime()+10000;boolean ready=false;
        while(SystemClock.elapsedRealtime()<end){try{Uri root=DocumentsContract.buildDocumentUriUsingTree(TREE,"root");try(Cursor row=context.getContentResolver().query(root,null,null,null,null)){ready=row!=null&&row.moveToFirst();}if(ready)break;}catch(SecurityException denied){ }SystemClock.sleep(100);}
        assertTrue("Real tree authority must be granted by the test-provider UID",ready);clearProvider();resetPrivate();
        folder=new ExternalOwnerPolicyFolder(context);folder.rememberFolder(TREE,GRANTS);folder.markPending();
        assertTrue(context.getContentResolver().getPersistedUriPermissions().stream().anyMatch(permission->permission.getUri().equals(TREE)&&permission.isReadPermission()&&permission.isWritePermission()));
    }
    @After public void cleanup()throws Exception {if(store!=null)store.close();clearProvider();context.getSharedPreferences("owner-policy-folder",0).edit().clear().commit();context.getContentResolver().releasePersistableUriPermission(TREE,GRANTS);remove(context.root);}
    @Test public void realSafPolicyAndMemoryZipRecoverLatestDeletionOverOlderSnapshot()throws Exception {
        JSONObject removed=store.addMessage("user","old private device source ".repeat(12000),null),kept=store.addMessage("user","unchanged native preference",null);
        folder.sync(OwnerPolicyBackup.capture(context,store));String older=folder.exportMemoryBackup(files,store);
        assertEquals("completed_managed_local",new SourcePayloadDeletion(context,store).deleteSource("message",removed.getLong("id"),removed.getString("content_hash"),"device owner fixture").getString("status"));folder.markPending();folder.sync(OwnerPolicyBackup.capture(context,store));assertFalse(folder.pending());
        resetPrivate();folder=new ExternalOwnerPolicyFolder(context);assertFalse(folder.connected());JSONObject controls=folder.readFromFolder(TREE);OwnerPolicyBackup.merge(context,store,controls);folder.rememberFolder(TREE,GRANTS);
        Uri archive=DocumentsContract.buildDocumentUriUsingTree(TREE,"root/"+older);
        try(InputStream input=context.getContentResolver().openInputStream(archive)){assertNotNull(input);files.importBackup(input,store);}OwnerPolicyBackup.reconcile(context,store);
        assertNull(store.getMessage(removed.getLong("id")));assertNotNull(store.getMessage(kept.getLong("id")));assertFalse(store.getPrivacyLedger().allowedSource("message",removed.getLong("id"),removed.getString("content_hash")));
        android.util.Log.i("HoyoungDeviceProof","HOYOUNG_DEVICE_PORTABLE_OWNER_POLICY actual-SAF-persistable-grant; verified-provider-IO-and-rename; streaming-V3-backup; isolated-target-private-reset; latest-policy-before-old-ZIP; deleted-source-absent; other-source-preserved; no-real-uninstall-or-Samsung-claim");
    }
    @Test public void realSafCorruptCompletedPolicyFailsClosed()throws Exception {
        JSONObject source=store.addMessage("user","synthetic excluded secret",null);store.getPrivacyLedger().excludeSource("message",source.getLong("id"),source.getString("content_hash"),"retrieval_exclude","device owner fixture");folder.sync(OwnerPolicyBackup.capture(context,store));
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(TREE,"root");String id=null;
        try(Cursor rows=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){assertNotNull(rows);while(rows.moveToNext())if(rows.getString(1).startsWith("hoyoung-owner-policy-"))id=rows.getString(0);}
        assertNotNull(id);Uri document=DocumentsContract.buildDocumentUriUsingTree(TREE,id);
        try(OutputStream output=context.getContentResolver().openOutputStream(document,"wt")){assertNotNull(output);output.write("invalid finished policy".getBytes(StandardCharsets.UTF_8));}
        assertThrows(IOException.class,folder::readConnectedFolder);assertFalse(store.getPrivacyLedger().allowedSource("message",source.getLong("id"),source.getString("content_hash")));
        android.util.Log.i("HoyoungDeviceProof","HOYOUNG_DEVICE_PORTABLE_OWNER_POLICY_CORRUPTION actual-SAF-completed-document-corruption; no-fallback-policy-loss; local-exclusion-retained");
    }
    private void resetPrivate()throws Exception {if(store!=null)store.close();remove(context.root);context.getSharedPreferences("owner-policy-folder",0).edit().clear().commit();files=new InternalFiles(context);store=new HoyoungStore(context);}
    private void clearProvider()throws Exception {Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(TREE,"root");List<String> ids=new ArrayList<>();try(Cursor rows=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)){assertNotNull(rows);while(rows.moveToNext())ids.add(rows.getString(0));}for(String id:ids)assertTrue(DocumentsContract.deleteDocument(context.getContentResolver(),DocumentsContract.buildDocumentUriUsingTree(TREE,id)));}
    private static void remove(File path)throws IOException {if(!path.exists())return;if(path.isDirectory())for(File child:Objects.requireNonNull(path.listFiles()))remove(child);if(!path.delete())throw new IOException("Device fixture cleanup failed");}
    private static final class FixtureContext extends ContextWrapper {
        final File root;final String prefix=UUID.randomUUID().toString();FixtureContext(Context base){super(base);root=new File(base.getFilesDir(),"device-fixtures/saf-"+prefix);}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getFilesDir(){return directory("files");}
        @Override public File getDatabasePath(String name){return new File(directory("databases"),name);}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return getBaseContext().getSharedPreferences("saf-fixture-"+prefix+"-"+name,mode);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(),factory,SQLiteDatabase.CREATE_IF_NECESSARY,handler);}
        private File directory(String name){File result=new File(root,name);if(!result.isDirectory())assertTrue(result.mkdirs());return result;}
    }
}
