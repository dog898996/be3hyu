package com.hoyoung.assistant.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;

/** Small owner-control mirror in the same user-selected shared folder as exported ZIPs.
 * SAF grants disappear on uninstall: the owner reconnects this folder before restoring.
 * Inference/retrieval never access the provider. App workers call this only on control changes,
 * explicit export or a pending retry. Completed old records are never overwritten in place. */
public final class ExternalOwnerPolicyFolder {
    private static final String PREFIX="hoyoung-owner-policy-",PENDING=".hoyoung-pending-";
    private static final Object LOCK=new Object();
    private static final Object STATE_LOCK=new Object();
    private final Context context;
    private final SharedPreferences preferences;
    private final AccessFactory access;
    interface AccessFactory { TreeAccess open(Uri tree)throws IOException; }
    interface TreeAccess {
        List<Document> list()throws IOException;
        Uri create(String mime,String name)throws IOException;
        InputStream read(Uri document)throws IOException;
        OutputStream write(Uri document)throws IOException;
        Uri rename(Uri document,String name)throws IOException;
        void delete(Uri document)throws IOException;
    }
    static final class Document {
        final Uri uri;final String name;
        Document(Uri uri,String name){this.uri=uri;this.name=name;}
    }
    public ExternalOwnerPolicyFolder(Context context){this(context,tree->new SafAccess(context.getApplicationContext(),tree));}
    ExternalOwnerPolicyFolder(Context context,AccessFactory access){
        this.context=context.getApplicationContext();this.access=access;
        preferences=this.context.getSharedPreferences("owner-policy-folder",Context.MODE_PRIVATE);
    }
    public boolean connected(){return !preferences.getString("tree","").isEmpty();}
    public boolean pending(){return preferences.getBoolean("pending",false);}
    public long requestedGeneration(){return preferences.getLong("generation",0);}
    public void markPending(){synchronized(STATE_LOCK){long generation=requestedGeneration();preferences.edit().putLong("generation",generation==Long.MAX_VALUE?generation:generation+1).putBoolean("pending",true).apply();}}
    public void failure(Exception failure){preferences.edit().putBoolean("pending",true).putString("error",failure.getClass().getSimpleName()).apply();}
    public JSONObject status(){JSONObject result=new JSONObject();put(result,"connected",connected());put(result,"pending",pending());
        put(result,"lastSyncedAt",preferences.getLong("syncedAt",0));put(result,"lastError",preferences.getString("error",""));put(result,"automaticFullMemoryBackup",false);return result;}

    /** Read before the caller pauses and applies controls; never starts a model call. */
    public JSONObject readFromFolder(Uri tree)throws IOException {
        validateTree(tree);synchronized(LOCK){return readCompleted(access.open(tree));}
    }
    public JSONObject readConnectedFolder()throws IOException {
        if(!connected())return null;
        synchronized(LOCK){JSONObject result=readFromFolder(Uri.parse(preferences.getString("tree","")));
            if(result==null&&!preferences.getString("checksum","").isEmpty())throw new IOException("Previously verified external owner controls are missing");return result;}
    }
    /** Only after local policy import succeeded. Persisted SAF authority itself is not backed up. */
    public void rememberFolder(Uri tree,int flags)throws IOException {
        validateTree(tree);int grants=flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if(grants!=(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))throw new IOException("The owner folder needs read and write access");
        try{context.getContentResolver().takePersistableUriPermission(tree,grants);}
        catch(RuntimeException unavailable){throw new IOException("Persistent owner-folder access was not granted",unavailable);}
        if(!preferences.edit().putString("tree",tree.toString()).putString("checksum","").putBoolean("pending",true).putString("error","").commit())throw new IOException("Owner-folder selection could not be saved");
    }
    /** One small metadata record, no large memory ZIP and no model inference. */
    public void sync(JSONObject local)throws IOException {
        sync(local,requestedGeneration());
    }
    public void sync(JSONObject local,long capturedGeneration)throws IOException {
        synchronized(LOCK){
            if(!connected())return;
            TreeAccess folder=access.open(Uri.parse(preferences.getString("tree","")));
            JSONObject previous=readConnectedFolder(),combined=previous==null?OwnerPolicyBackup.validate(local):OwnerPolicyBackup.union(previous,local);
            if(previous==null||!previous.optString("checksum").equals(combined.optString("checksum"))){
                String name=PREFIX+String.format(Locale.ROOT,"%019d",System.currentTimeMillis())+"-"+UUID.randomUUID()+".json";
                publish(folder,"application/json",name,combined.toString().getBytes(StandardCharsets.UTF_8));
                // Keep the new verified full union and one previous complete record. Cleanup
                // failure only leaves extra backups; it never discards the newly committed one.
                List<Document> records=policyDocuments(folder);List<Document> retained=new ArrayList<>();
                for(Document record:records)if(!record.name.equals(name))retained.add(record);
                for(int i=0;i<Math.max(0,retained.size()-1);i++)try{folder.delete(retained.get(i).uri);}catch(IOException ignored){ }
            }
            synchronized(STATE_LOCK){
                if(!preferences.edit().putString("checksum",combined.optString("checksum")).putBoolean("pending",requestedGeneration()!=capturedGeneration).putLong("syncedAt",System.currentTimeMillis()).putString("error","").commit())throw new IOException("External owner-control completion could not be recorded");
            }
        }
    }
    /** Explicit user command only. Full backups keep existing streaming/capacity protections. */
    public String exportMemoryBackup(InternalFiles files,HoyoungStore store)throws IOException {
        synchronized(LOCK){
            if(!connected())throw new IOException("Select the shared owner folder first");
            TreeAccess folder=access.open(Uri.parse(preferences.getString("tree","")));
            Uri pending=folder.create("application/zip",PENDING+UUID.randomUUID()+".zip");
            if(pending==null)throw new IOException("The backup document could not be created");
            MessageDigest digest=OriginalTextFiles.digest();
            try(OutputStream output=required(folder.write(pending));DigestOutputStream hashed=new DigestOutputStream(output,digest)){files.exportBackup(hashed,store);hashed.flush();}
            String expected=OriginalTextFiles.hex(digest.digest());verifyBytes(folder,pending,expected,BackupPolicy.MAX_ARCHIVE_BYTES);
            String name="hoyoung-backup-"+String.format(Locale.ROOT,"%019d",System.currentTimeMillis())+"-"+UUID.randomUUID()+".zip";
            Uri committed=folder.rename(pending,name);if(committed==null)throw new IOException("Completed backup could not be published");
            verifyBytes(folder,committed,expected,BackupPolicy.MAX_ARCHIVE_BYTES);return name;
        }
    }
    private static JSONObject readCompleted(TreeAccess folder)throws IOException {
        JSONObject merged=null;
        for(Document record:policyDocuments(folder)){
            JSONObject current;try(InputStream input=required(folder.read(record.uri))){current=OwnerPolicyBackup.read(input);}
            merged=merged==null?current:OwnerPolicyBackup.union(merged,current);
        }
        return merged;
    }
    private static List<Document> policyDocuments(TreeAccess folder)throws IOException {
        List<Document> matches=new ArrayList<>();
        for(Document row:folder.list())if(row.name.matches("hoyoung-owner-policy-[0-9]{19}-[a-f0-9-]{36}\\.json"))matches.add(row);
        if(matches.size()>1000)throw new IOException("Too many external owner policy records; select a dedicated folder");
        matches.sort(Comparator.comparing(row->row.name));return matches;
    }
    private static void publish(TreeAccess folder,String mime,String name,byte[] bytes)throws IOException {
        Uri pending=folder.create(mime,PENDING+UUID.randomUUID()+".json");if(pending==null)throw new IOException("Owner-control staging document was not created");
        try(OutputStream output=required(folder.write(pending))){output.write(bytes);output.flush();}
        String expected=OriginalTextFiles.hex(OriginalTextFiles.digest().digest(bytes));verifyBytes(folder,pending,expected,OwnerPolicyBackup.MAX_BYTES);
        Uri committed=folder.rename(pending,name);if(committed==null)throw new IOException("Owner-control publication did not finish");
        verifyBytes(folder,committed,expected,OwnerPolicyBackup.MAX_BYTES);
        boolean visible=false;for(Document row:policyDocuments(folder))if(row.name.equals(name)){visible=true;break;}
        if(!visible)throw new IOException("The completed owner-control record is not visible in the selected folder");
    }
    private static void verifyBytes(TreeAccess folder,Uri document,String expected,long limit)throws IOException {
        MessageDigest digest=OriginalTextFiles.digest();long total=0;byte[] buffer=new byte[16384];int count;
        try(InputStream input=required(folder.read(document))){while((count=input.read(buffer))!=-1){if(total>limit-count)throw new IOException("External document exceeds its byte limit");total+=count;digest.update(buffer,0,count);}}
        if(!expected.equals(OriginalTextFiles.hex(digest.digest())))throw new IOException("External document read-back differs from the completed write");
    }
    private static <T>T required(T stream)throws IOException {if(stream==null)throw new IOException("External document stream is unavailable");return stream;}
    private static void validateTree(Uri tree)throws IOException {
        if(tree==null||!DocumentsContract.isTreeUri(tree))throw new IOException("Choose a shared document folder");
        String id=DocumentsContract.getTreeDocumentId(tree).replace(':','/').toLowerCase(Locale.ROOT);
        if(id.matches(".*(?:^|/)android/(?:data|obb)(?:/.*)?"))throw new IOException("Android/data and Android/obb are removed with the app; choose Documents/Hoyoung instead");
    }
    private static final class SafAccess implements TreeAccess {
        private final Context context;private final Uri tree,parent;
        SafAccess(Context context,Uri tree)throws IOException {validateTree(tree);this.context=context;this.tree=tree;parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));}
        public List<Document> list()throws IOException {
            List<Document> rows=new ArrayList<>();Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));
            try(Cursor cursor=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
                if(cursor==null)throw new IOException("The owner folder cannot be listed");
                while(cursor.moveToNext()){if(rows.size()>=10000)throw new IOException("Use a dedicated owner backup folder");rows.add(new Document(DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0)),cursor.getString(1)));}
            }catch(RuntimeException unavailable){throw new IOException("Owner folder access is unavailable",unavailable);}return rows;
        }
        public Uri create(String mime,String name)throws IOException {return DocumentsContract.createDocument(context.getContentResolver(),parent,mime,name);}
        public InputStream read(Uri uri)throws IOException {return context.getContentResolver().openInputStream(uri);}
        public OutputStream write(Uri uri)throws IOException {return context.getContentResolver().openOutputStream(uri,"wt");}
        public Uri rename(Uri uri,String name)throws IOException {return DocumentsContract.renameDocument(context.getContentResolver(),uri,name);}
        public void delete(Uri uri)throws IOException {if(!DocumentsContract.deleteDocument(context.getContentResolver(),uri))throw new IOException("Old owner-policy record cleanup was deferred");}
    }
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(JSONException impossible){throw new IllegalStateException(impossible);}}
}
