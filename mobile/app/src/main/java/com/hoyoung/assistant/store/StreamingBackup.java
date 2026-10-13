package com.hoyoung.assistant.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Version-two backups stream each table and file; history size does not set heap usage. */
final class StreamingBackup {
    static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;
    static final int MAX_ENTRIES = 100_000;
    static final class PreparedBackup {
        final File databaseFile, projectsDirectory, originalsDirectory;
        final JSONObject manifest, legacySnapshot;
        PreparedBackup(File database, File projects, File originals, JSONObject manifest, JSONObject legacy) {
            this.databaseFile=database; this.projectsDirectory=projects; this.originalsDirectory=originals;
            this.manifest=manifest; this.legacySnapshot=legacy;
        }
    }

    static void exportArchive(OutputStream destination, HoyoungStore store, File filesRoot) throws IOException {
        exportArchive(destination,store,filesRoot,null);
    }

    static void exportArchive(OutputStream destination, HoyoungStore store, File filesRoot, File deviceJournalSnapshot) throws IOException {
        File index = new File(filesRoot, ".backup-index-" + UUID.randomUUID());
        long[] total = {0}; int[] entries = {0};
        JSONObject counts = new JSONObject();
        try (HoyoungStore.ReadSnapshot snapshot = store.openReadSnapshot();
             FileOutputStream indexFile = new FileOutputStream(index);
             BufferedOutputStream indexOutput = new BufferedOutputStream(indexFile);
             ZipOutputStream zip = new ZipOutputStream(new BoundedOutput(new NonClosingOutput(destination), MAX_BYTES))) {
            for (String table : HoyoungStore.tables()) {
                String path = "store/" + table + ".ndjson";
                zip.putNextEntry(new ZipEntry(path)); EntryOutput content = new EntryOutput(zip, total);
                snapshot.requireOwner();
                long rows = HoyoungStore.writeTable(snapshot.database,table,content); zip.closeEntry();
                put(counts, table, rows); checksum(indexOutput, path, content.bytes, content.hash()); entries[0]++;
            }
            for (String namespace : new String[]{"projects", "originals"}) {
                File root = new File(filesRoot, namespace);
                if (root.exists()) exportFiles(root, root, namespace, zip, indexOutput, total, entries, 0);
            }
            if(deviceJournalSnapshot!=null&&deviceJournalSnapshot.isDirectory())exportFiles(deviceJournalSnapshot,deviceJournalSnapshot,"device-journal",zip,indexOutput,total,entries,0);
            indexOutput.flush(); indexFile.getFD().sync();
            zip.putNextEntry(new ZipEntry("checksums.ndjson")); EntryOutput checksums = new EntryOutput(zip, total);
            try (InputStream input = new BufferedInputStream(new FileInputStream(index))) { copy(input, checksums, InternalFiles.MAX_FILE_BYTES); }
            zip.closeEntry();
            JSONObject manifest = new JSONObject(); put(manifest, "format", "hoyoung-private-backup"); put(manifest, "version", 2);
            put(manifest, "storeVersion", HoyoungStore.SNAPSHOT_VERSION); put(manifest, "createdAt", System.currentTimeMillis());
            put(manifest, "rowCounts", counts); put(manifest, "entryCount", entries[0]);
            JSONObject check = new JSONObject(); put(check, "path", "checksums.ndjson"); put(check, "bytes", checksums.bytes); put(check, "sha256", checksums.hash());
            put(manifest, "checksumIndex", check);
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); zip.finish();
        } finally { if (index.exists()) index.delete(); }
    }

    private static void exportFiles(File root, File directory, String namespace, ZipOutputStream zip, OutputStream index,
                                    long[] total, int[] entries, int depth) throws IOException {
        if (depth > 32) throw new IOException("Project nesting exceeds the backup bound");
        if (Files.isSymbolicLink(directory.toPath())) throw new IOException("Backup does not follow symbolic links");
        File[] children = directory.listFiles(); if (children == null) throw new IOException("Private files cannot be listed");
        for (File file : children) {
            if (file.getName().startsWith(".partial-")) continue;
            if (Files.isSymbolicLink(file.toPath())) throw new IOException("Backup does not follow symbolic links");
            if (file.isDirectory()) { exportFiles(root, file, namespace, zip, index, total, entries, depth+1); continue; }
            if (!file.isFile() || file.length()>InternalFiles.MAX_FILE_BYTES) throw new IOException("Unsupported or oversized working file");
            if (++entries[0] > MAX_ENTRIES-2) throw new IOException("Too many backup entries");
            String relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
            String path = namespace + "/" + relative; target(new File(root.getParentFile(), ".validation-only"), path);
            zip.putNextEntry(new ZipEntry(path)); EntryOutput content = new EntryOutput(zip, total);
            try (InputStream input = new BufferedInputStream(new FileInputStream(file))) { copy(input, content, InternalFiles.MAX_FILE_BYTES); }
            zip.closeEntry(); checksum(index, path, content.bytes, content.hash());
        }
    }

    static PreparedBackup prepare(InputStream source, HoyoungStore store, File stagingRoot) throws IOException {
        File projects = new File(stagingRoot,"projects"), originals = new File(stagingRoot,"originals");
        mkdir(projects); mkdir(originals);
        File databaseFile = new File(stagingRoot,"staged-store.db");
        JSONObject manifest = null; long total = 0; int entries = 0;
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile,null)) {
            database.execSQL("CREATE TABLE backup_entries(path TEXT PRIMARY KEY,bytes INTEGER NOT NULL,sha256 TEXT NOT NULL,checked INTEGER NOT NULL DEFAULT 0)");
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new BoundedInput(new NonClosingInput(source),MAX_BYTES)))) {
                ZipEntry entry;
                while ((entry=zip.getNextEntry())!=null) {
                    if (++entries>MAX_ENTRIES || entry.isDirectory()) throw new IOException("Invalid or excessive backup entries");
                    String path=entry.getName(); File output=target(stagingRoot,path);
                    if (!allowed(path)) throw new IOException("Unknown backup entry");
                    mkdir(output.getParentFile());
                    MessageDigest digest=OriginalTextFiles.digest(); long bytes;
                    long bound=path.startsWith("store/")?MAX_BYTES:InternalFiles.MAX_FILE_BYTES;
                    if (path.equals("manifest.json")) bound=64*1024;
                    if (path.equals("hoyoung/store.json")) bound=16L*1024*1024;
                    try (FileOutputStream file=new FileOutputStream(output);
                         DigestOutputStream hashed=new DigestOutputStream(new BufferedOutputStream(file),digest)) {
                        bytes=copy(zip,hashed,Math.min(bound,MAX_BYTES-total)); hashed.flush(); file.getFD().sync();
                    }
                    total+=bytes;
                    try { database.execSQL("INSERT INTO backup_entries(path,bytes,sha256) VALUES(?,?,?)",new Object[]{path,bytes,OriginalTextFiles.hex(digest.digest())}); }
                    catch (RuntimeException duplicate) { throw new IOException("Duplicate backup entry",duplicate); }
                    zip.closeEntry();
                    if (path.equals("manifest.json")) manifest=parseSmall(output,64*1024);
                }
            }
            if (manifest==null || !"hoyoung-private-backup".equals(manifest.optString("format"))) throw new IOException("Missing backup manifest");
            if (manifest.optInt("version")==1) {
                File legacy=new File(stagingRoot,"hoyoung/store.json");
                if (!legacy.isFile()) throw new IOException("Incomplete legacy backup");
                JSONObject snapshot=parseSmall(legacy,16L*1024*1024);
                try {
                    store.validateSnapshot(snapshot); HoyoungStore.createSchema(database);
                    database.beginTransaction();
                    try { HoyoungStore.populateSnapshotDatabase(database,snapshot); database.setTransactionSuccessful(); }
                    finally { database.endTransaction(); }
                } catch (RuntimeException invalid) { throw new IOException("Invalid legacy backup provenance",invalid); }
                return new PreparedBackup(databaseFile,projects,originals,manifest,snapshot);
            }
            if (manifest.optInt("version")!=2 || manifest.optInt("storeVersion")!=HoyoungStore.SNAPSHOT_VERSION) throw new IOException("Unsupported backup version");
            verifyChecksums(database,stagingRoot,manifest);
            HoyoungStore.createSchema(database);
            database.beginTransaction();
            try {
                JSONObject counts=manifest.optJSONObject("rowCounts"); if (counts==null) throw new IOException("Missing row counts");
                for (String table:HoyoungStore.tables()) {
                    File input=new File(stagingRoot,"store/"+table+".ndjson"); if (!input.isFile()) throw new IOException("Missing authoritative table");
                    long rows;
                    try (InputStream stream=new BufferedInputStream(new FileInputStream(input))) { rows=HoyoungStore.readTable(database,table,stream); }
                    if (!counts.has(table)||rows!=counts.optLong(table,-1)) throw new IOException("Authoritative row count mismatch");
                }
                HoyoungStore.validateDatabase(database);
                database.setTransactionSuccessful();
            } catch (RuntimeException invalid) { throw new IOException("Backup provenance or database validation failed",invalid); }
            finally { database.endTransaction(); }
        }
        return new PreparedBackup(databaseFile,projects,originals,manifest,null);
    }

    private static void verifyChecksums(SQLiteDatabase database, File root, JSONObject manifest) throws IOException {
        JSONObject check=manifest.optJSONObject("checksumIndex");
        if (check==null || !"checksums.ndjson".equals(check.optString("path"))) throw new IOException("Invalid checksum index");
        try (Cursor row=database.rawQuery("SELECT bytes,sha256 FROM backup_entries WHERE path='checksums.ndjson'",null)) {
            if (!row.moveToFirst()||row.getLong(0)!=check.optLong("bytes",-1)||!row.getString(1).equals(check.optString("sha256"))) throw new IOException("Checksum index integrity mismatch");
        }
        long checked=0;
        try (BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(new File(root,"checksums.ndjson")),StandardCharsets.UTF_8))) {
            String line;
            while ((line=HoyoungStore.boundedLine(reader,8192))!=null) {
                JSONObject expected;
                try { expected=new JSONObject(line); } catch (JSONException invalid) { throw new IOException("Invalid checksum row",invalid); }
                String path=expected.optString("path"); if (!(path.startsWith("store/")||path.startsWith("projects/")||path.startsWith("originals/")||path.startsWith("device-journal/"))) throw new IOException("Invalid checksum path");
                try (Cursor actual=database.rawQuery("SELECT bytes,sha256,checked FROM backup_entries WHERE path=?",new String[]{path})) {
                    if (!actual.moveToFirst()||actual.getInt(2)!=0||actual.getLong(0)!=expected.optLong("bytes",-1)
                            || !actual.getString(1).equals(expected.optString("sha256"))) throw new IOException("Backup entry checksum mismatch");
                }
                database.execSQL("UPDATE backup_entries SET checked=1 WHERE path=?",new Object[]{path}); checked++;
            }
        }
        if (checked!=manifest.optLong("entryCount",-1)) throw new IOException("Backup entry count mismatch");
        try (Cursor row=database.rawQuery("SELECT path FROM backup_entries WHERE checked=0 AND path NOT IN ('manifest.json','checksums.ndjson') LIMIT 1",null)) {
            if (row.moveToFirst()) throw new IOException("Unverified backup entry");
        }
    }

    private static boolean allowed(String path) {
        if (path.equals("manifest.json")||path.equals("checksums.ndjson")||path.equals("hoyoung/store.json")) return true;
        if (path.startsWith("projects/")||path.startsWith("originals/")||path.startsWith("device-journal/")) return true;
        for (String table:HoyoungStore.tables()) if (path.equals("store/"+table+".ndjson")) return true;
        return false;
    }

    private static File target(File root,String relative) throws IOException {
        if (relative==null||relative.isEmpty()||relative.length()>2048||relative.startsWith("/")||relative.indexOf('\\')>=0||relative.indexOf(':')>=0) throw new IOException("Unsafe backup path");
        String[] parts=relative.split("/",-1); if (parts.length>34) throw new IOException("Backup path is too deep");
        for (String part:parts) {
            if (part.isEmpty()||part.equals(".")||part.equals("..")||part.startsWith(".partial-")||part.getBytes(StandardCharsets.UTF_8).length>255) throw new IOException("Unsafe backup path");
            for (int i=0;i<part.length();i++) if (Character.isISOControl(part.charAt(i))) throw new IOException("Unsafe backup path");
        }
        File file=new File(root,relative); if (!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)) throw new IOException("Backup path escapes staging"); return file;
    }
    private static JSONObject parseSmall(File file,long maximum) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try (InputStream input=new FileInputStream(file)) { copy(input,bytes,maximum); }
        String text=new String(bytes.toByteArray(),StandardCharsets.UTF_8); HoyoungStore.checkJsonDepth(text);
        try { return new JSONObject(text); } catch (JSONException invalid) { throw new IOException("Invalid backup JSON",invalid); }
    }
    private static void checksum(OutputStream output,String path,long bytes,String hash) throws IOException {
        JSONObject value=new JSONObject(); put(value,"path",path); put(value,"bytes",bytes); put(value,"sha256",hash);
        output.write((value.toString()+"\n").getBytes(StandardCharsets.UTF_8));
    }
    private static void mkdir(File directory) throws IOException { if (!directory.isDirectory()&&!directory.mkdirs()) throw new IOException("Cannot create backup staging directory"); }
    private static long copy(InputStream source,OutputStream destination,long maximum) throws IOException {
        long total=0; byte[] buffer=new byte[16_384]; int read;
        while ((read=source.read(buffer))!=-1) { if (read==0)continue; if (maximum<read||total>maximum-read) throw new IOException("Backup storage bound exceeded"); destination.write(buffer,0,read);total+=read; }
        return total;
    }
    private static void put(JSONObject object,String key,Object value) { try { object.put(key,value); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
    private static final class EntryOutput extends OutputStream {
        final OutputStream output; final MessageDigest digest=OriginalTextFiles.digest(); final long[] total; long bytes;
        EntryOutput(OutputStream output,long[] total){this.output=output;this.total=total;}
        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
        @Override public void write(byte[] value,int offset,int length)throws IOException{if(total[0]>MAX_BYTES-65536-length)throw new IOException("Expanded backup exceeds4GiB");output.write(value,offset,length);digest.update(value,offset,length);bytes+=length;total[0]+=length;}
        String hash(){return OriginalTextFiles.hex(digest.digest());}
    }
    private static final class NonClosingOutput extends FilterOutputStream {NonClosingOutput(OutputStream out){super(out);}@Override public void close()throws IOException{flush();}@Override public void write(byte[] b,int o,int l)throws IOException{out.write(b,o,l);}}
    private static final class NonClosingInput extends FilterInputStream {NonClosingInput(InputStream in){super(in);}@Override public void close(){}}
    private static final class BoundedOutput extends FilterOutputStream {long remaining;BoundedOutput(OutputStream out,long max){super(out);remaining=max;}@Override public void write(int v)throws IOException{if(remaining--<=0)throw new IOException("Compressed backup bound exceeded");out.write(v);}@Override public void write(byte[]b,int o,int l)throws IOException{if(l>remaining)throw new IOException("Compressed backup bound exceeded");out.write(b,o,l);remaining-=l;}}
    private static final class BoundedInput extends FilterInputStream {long remaining;BoundedInput(InputStream in,long max){super(in);remaining=max;}@Override public int read()throws IOException{int value=in.read();if(value!=-1&&remaining--<=0)throw new IOException("Compressed backup bound exceeded");return value;}@Override public int read(byte[]b,int o,int l)throws IOException{int count=in.read(b,o,(int)Math.min(l,Math.max(1,remaining)));if(count>remaining)throw new IOException("Compressed backup bound exceeded");if(count>0)remaining-=count;return count;}}
}
