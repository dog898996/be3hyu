package com.hoyoung.assistant;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;

/** Synthetic document bytes live in the test APK's private namespace, outside target app data.
 * This proves Android SAF IO/authority, not Samsung's picker or real user account storage. */
public final class OwnerPolicyFixtureDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY="com.hoyoung.assistant.test.ownerpolicy";
    private File root;
    @Override public boolean onCreate(){root=new File(Objects.requireNonNull(getContext()).getFilesDir(),"owner-policy-shared-fixture");return root.isDirectory()||root.mkdirs();}
    @Override public Cursor queryRoots(String[] projection){
        String[] columns=projection==null?new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_FLAGS}:projection;
        MatrixCursor cursor=new MatrixCursor(columns);MatrixCursor.RowBuilder row=cursor.newRow();
        for(String column:columns)row.add(column,switch(column){case DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID->"root";case DocumentsContract.Root.COLUMN_TITLE->"Hoyoung synthetic owner controls";case DocumentsContract.Root.COLUMN_FLAGS->DocumentsContract.Root.FLAG_SUPPORTS_CREATE|DocumentsContract.Root.FLAG_LOCAL_ONLY;default->null;});return cursor;
    }
    @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException {MatrixCursor cursor=cursor(projection);add(cursor,file(id));return cursor;}
    @Override public Cursor queryChildDocuments(String id,String[] projection,String order)throws FileNotFoundException {MatrixCursor cursor=cursor(projection);File[] children=file(id).listFiles();if(children==null)throw new FileNotFoundException("Synthetic folder unavailable");for(File child:children)add(cursor,child);return cursor;}
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode));}
    @Override public String createDocument(String parent,String mime,String name)throws FileNotFoundException {
        safeName(name);File target=new File(file(parent),name);
        try{boolean created=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)?target.mkdir():target.createNewFile();if(!created)throw new FileNotFoundException("Synthetic document already exists");return id(target);}catch(IOException failure){throw new FileNotFoundException(failure.getMessage());}
    }
    @Override public String renameDocument(String document,String name)throws FileNotFoundException {safeName(name);File previous=file(document),next=new File(previous.getParentFile(),name);if(previous.equals(root)||!previous.renameTo(next))throw new FileNotFoundException("Synthetic rename failed");return id(next);}
    @Override public void deleteDocument(String document)throws FileNotFoundException {File target=file(document);if(target.equals(root)||!target.delete())throw new FileNotFoundException("Synthetic deletion failed");}
    @Override public boolean isChildDocument(String parent,String document){try{return !file(parent).equals(file(document))&&file(document).getCanonicalPath().startsWith(file(parent).getCanonicalPath()+File.separator);}catch(IOException invalid){return false;}}
    private File file(String document)throws FileNotFoundException {if(!document.equals("root")&&!document.startsWith("root/"))throw new FileNotFoundException("Invalid fixture root");File file=document.equals("root")?root:new File(root,document.substring(5));try{if(!file.equals(root)&&!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new FileNotFoundException("Fixture escape");return file;}catch(IOException invalid){throw new FileNotFoundException(invalid.getMessage());}}
    private String id(File file){return file.equals(root)?"root":"root/"+root.toPath().relativize(file.toPath()).toString();}
    private static void safeName(String name)throws FileNotFoundException {if(name==null||name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\"))throw new FileNotFoundException("Unsafe fixture name");}
    private static MatrixCursor cursor(String[] projection){return new MatrixCursor(projection==null?new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE}:projection);}
    private void add(MatrixCursor cursor,File file){MatrixCursor.RowBuilder row=cursor.newRow();for(String column:cursor.getColumnNames())row.add(column,switch(column){case DocumentsContract.Document.COLUMN_DOCUMENT_ID->id(file);case DocumentsContract.Document.COLUMN_DISPLAY_NAME->file.getName();case DocumentsContract.Document.COLUMN_MIME_TYPE->file.isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:file.getName().endsWith(".zip")?"application/zip":"application/json";case DocumentsContract.Document.COLUMN_FLAGS->DocumentsContract.Document.FLAG_SUPPORTS_DELETE|DocumentsContract.Document.FLAG_SUPPORTS_RENAME|DocumentsContract.Document.FLAG_SUPPORTS_WRITE|(file.isDirectory()?DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:0);case DocumentsContract.Document.COLUMN_SIZE->file.length();default->null;});}
}
