package com.hoyoung.assistant.store;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Working files belong in private filesDir/projects, not external storage or cacheDir. */
public final class InternalFiles {
    public static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    public static final long MAX_ATTACHMENT_BYTES = AttachmentOriginals.MAX_BYTES;
    public static final long MAX_BACKUP_BYTES = StreamingBackup.MAX_BYTES;
    private static final long MAX_SNAPSHOT_BYTES = 16L * 1024 * 1024;
    private static final int MAX_BACKUP_ENTRIES = StreamingBackup.MAX_ENTRIES;
    private static final String RESTORE_JOURNAL = ".restore-transaction.json";
    private final Context context;
    private final File privateRoot;
    private final File projects;
    private final AttachmentOriginals attachmentOriginals;
    private final Object backupLock = new Object();
    private volatile BackupParticipant backupParticipant;
    private IOException startupFailure;
    private IOException startupWarning;
    public interface BackupParticipant {
        void capture(File destination) throws IOException;
        default void capture(File destination,BackupPolicy.SpaceMonitor monitor) throws IOException { capture(destination); }
        /** Validate the staged namespace before any live file or database replacement. */
        default void validate(File stagedJournal) throws IOException { }
    }
    public void setBackupParticipant(BackupParticipant participant) { backupParticipant = participant; }
    public boolean restoreRecoveryPending(){return restoreJournalPresent(privateRoot);}
    /** Recovery failures retain the journal/generations and allow a read-only boot diagnostics UI. */
    public boolean startupBlocked(){return startupFailure!=null||restoreRecoveryPending();}
    public JSONObject startupDiagnostics(){
        JSONObject result=new JSONObject();boolean pending=restoreRecoveryPending(),blocked=startupFailure!=null||pending;
        put(result,"ready",!blocked);put(result,"blocked",blocked);put(result,"recoveryPending",pending);
        put(result,"status",blocked?"restore_recovery_blocked":startupWarning!=null?"ready_cleanup_deferred":"ready");
        put(result,"failure",startupFailure==null?"":startupFailure.getMessage());put(result,"warning",startupWarning==null?"":startupWarning.getMessage());
        put(result,"failureClass",startupFailure==null?"":startupFailure.getClass().getSimpleName());put(result,"warningClass",startupWarning==null?"":startupWarning.getClass().getSimpleName());
        put(result,"retryable",blocked||startupWarning!=null);put(result,"automaticReplay",false);return result;
    }
    /** Only constructor failures make this instance diagnostic-only. A healthy instance
     * may legitimately create a journal while carrying out its own in-session restore. */
    private void assertStartupWritable()throws IOException{
        if(startupFailure!=null)throw new IOException("Private storage is limited to startup recovery diagnostics",startupFailure);
    }
    static boolean restoreJournalPresent(File privateRoot){File journal=new File(privateRoot,RESTORE_JOURNAL);return journal.exists()||Files.isSymbolicLink(journal.toPath());}
    static void requireNoPendingRestore(File privateRoot){if(restoreJournalPresent(privateRoot))throw new RestoreRecoveryPendingException();}
    public static final class RestoreRecoveryPendingException extends IllegalStateException {
        private static final long serialVersionUID=1L;
        public final String code="restore_recovery_pending";
        RestoreRecoveryPendingException(){super("Interrupted restore recovery must finish before creating storage or opening its database");}
    }

    /** Run on an IO worker: filesystem sizes are a conservative estimate, not remote-provider capacity. */
    public JSONObject backupPreflight(HoyoungStore store)throws IOException {
        return backupPreflight(store,BackupPolicy.fromStore(store));
    }
    public JSONObject backupPreflight(HoyoungStore store,BackupPolicy policy)throws IOException {
        if(store==null||policy==null)throw new IllegalArgumentException("A store and backup policy are required");
        BackupPolicy.Session space=policy.session(privateRoot);space.phase("backup_preflight","");
        long sourceFiles=saturatedAdd(treeBytes(projects,0),treeBytes(new File(privateRoot,"originals"),0));
        sourceFiles=saturatedAdd(sourceFiles,treeBytes(new File(privateRoot,"device-journal"),0));
        File database=context.getDatabasePath(HoyoungStore.DATABASE_NAME);
        long databaseBytes=saturatedAdd(database.length(),new File(database.getPath()+"-wal").length());
        long estimate=saturatedAdd(sourceFiles,saturatedMultiply(databaseBytes,6));
        JSONObject result=new JSONObject();
        put(result,"archiveLimitBytes",policy.maxArchiveBytes);put(result,"expandedLimitBytes",policy.maxArchiveBytes);
        put(result,"reserveBytes",policy.reserveBytes);put(result,"privateAvailableBytes",space.availableBytes());
        put(result,"workingFileLimitBytes",MAX_FILE_BYTES);put(result,"attachmentLimitBytes",MAX_ATTACHMENT_BYTES);
        put(result,"textOriginalLimitBytes",OriginalTextFiles.MAX_BYTES);put(result,"maxEntries",MAX_BACKUP_ENTRIES);
        put(result,"sourceFileBytes",sourceFiles);put(result,"databaseAndWalBytes",databaseBytes);
        put(result,"expandedBytesEstimate",estimate);put(result,"expandedEstimateExact",false);
        put(result,"estimateMayExceedConfiguredLimit",estimate>policy.maxArchiveBytes);
        put(result,"estimateMethod","Current file lengths plus six times database and WAL lengths; concurrent changes and metadata differ");
        put(result,"providerCapacityKnown",false);put(result,"requiresRestoreStagingSpace",true);
        return result;
    }

    public InternalFiles(Context context) {
        this.context = context.getApplicationContext();
        this.privateRoot = this.context.getFilesDir();
        this.projects = new File(privateRoot, "projects");
        this.attachmentOriginals = new AttachmentOriginals(projects);
        try { recoverRestore(); }
        catch (IOException failure) { startupFailure=failure;return; }
        catch (RuntimeException unavailable) { startupFailure=new IOException("Interrupted restore recovery is unavailable",unavailable);return; }
        // A missing committed namespace is evidence of an incomplete generation. Never
        // manufacture an empty directory before recovery has checked that evidence.
        try { ensureDirectory(projects);ensureDirectory(new File(privateRoot, "originals")); }
        catch (RuntimeException unavailable) { startupFailure=new IOException("Private storage initialization is unavailable",unavailable);return; }
        try { cleanupAbandonedStaging(); }
        catch (IOException deferred) { startupWarning=deferred; }
        catch (RuntimeException deferred) { startupWarning=new IOException("Abandoned staging cleanup is deferred",deferred); }
    }

    @FunctionalInterface public interface StorageMaintenance<T> { T run() throws IOException; }

    /** Run on an IO worker without already holding a Store monitor. This uses the
     * same backup -> files order as import and also excludes ordinary file mutation.
     * The callback must not delegate a nested storage operation to another thread. */
    public <T> T withExclusiveStorageMaintenance(StorageMaintenance<T> action)throws IOException {
        if(action==null)throw new IllegalArgumentException("Storage maintenance callback is required");
        synchronized(backupLock){synchronized(this){assertStartupWritable();return action.run();}}
    }

    public synchronized JSONObject saveFromUri(Uri uri, String displayName) throws IOException {
        assertStartupWritable();
        if (uri == null) throw new IllegalArgumentException("A source URI is required");
        if (displayName == null || displayName.trim().isEmpty()) {
            try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) displayName = cursor.getString(0);
            } catch (RuntimeException ignored) { /* A provider may omit display-name metadata. */ }
        }
        if (displayName == null || displayName.trim().isEmpty()) displayName = "attachment";
        JSONObject supplied = new JSONObject(); put(supplied, "displayName", displayName);
        String mimeType = null;
        try { mimeType = context.getContentResolver().getType(uri); }
        catch (RuntimeException unavailable) { /* A provider may omit MIME metadata. */ }
        if (mimeType != null) { put(supplied, "mimeType", mimeType); put(supplied, "mimeSource", "content_resolver"); }
        try (InputStream source = context.getContentResolver().openInputStream(uri)) {
            if (source == null) throw new IOException("The selected document cannot be opened");
            return attachmentOriginals.save(source, supplied);
        }
    }

    /** Freeze before linking an attachment to an owner message. Call from a background worker. */
    public synchronized JSONArray freezeAttachments(JSONArray attachments) throws IOException {
        assertStartupWritable();
        JSONArray frozen = new JSONArray();
        if (attachments == null) return frozen;
        if (attachments.length() > 8) throw new IOException("A message supports at most eight attachments");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < attachments.length(); i++) {
            Object value = attachments.opt(i);
            JSONObject supplied = value instanceof JSONObject ? (JSONObject) value : new JSONObject();
            String id = value instanceof String ? (String) value : supplied.optString("originalAssetId", supplied.optString("id", supplied.optString("fileId", "")));
            if (supplied.has("originalAssetId") && supplied.has("id") && !supplied.optString("originalAssetId").equals(supplied.optString("id"))) throw new IOException("Attachment metadata has contradictory ids");
            if (id.isEmpty() || !seen.add(id)) throw new IOException("Missing or duplicate attachment id");
            JSONObject original;
            if (AttachmentOriginals.reserved(id)) {
                original = attachmentOriginals.describe(id, true);
                if (supplied.has("sha256") && !original.optString("sha256").equals(supplied.optString("sha256"))) throw new IOException("Attachment source hash changed");
                if (supplied.has("size") && supplied.optLong("size", -1) != original.optLong("size")) throw new IOException("Attachment source size changed");
                // Names are per-message provenance; retain the immutable record's binary identity.
                // Original identity and trusted import MIME/name come from the private sidecar.
                // Caller labels remain separate message annotations, not replacements for source metadata.
            } else {
                File source = resolve(projects, id);
                if (!source.isFile() || Files.isSymbolicLink(source.toPath())) throw new IOException("No such attachment source");
                JSONObject metadata;
                try { metadata = value instanceof JSONObject ? new JSONObject(supplied.toString()) : metadata(source); }
                catch (JSONException malformed) { throw new IOException("Invalid attachment metadata", malformed); }
                if (!metadata.has("id")) put(metadata, "id", id);
                try (InputStream input = new FileInputStream(source)) { original = attachmentOriginals.save(input, metadata); }
            }
            original.remove("path"); frozen.put(original);
        }
        return frozen;
    }

    /** Exact large action results use the same immutable payload archive as owner attachments. */
    public synchronized JSONObject archiveToolResult(String rootRequestId, String actionId, String text) throws IOException {
        assertStartupWritable();
        if (text == null || text.length() > OriginalTextFiles.MAX_ORIGINAL_CHARS) throw new IOException("Tool result exceeds 10,000,000 UTF-16 characters");
        if (rootRequestId == null || rootRequestId.isEmpty() || rootRequestId.length() > 512
                || actionId == null || actionId.isEmpty() || actionId.length() > 512) throw new IOException("Tool result needs bounded request and action ids");
        JSONObject supplied = new JSONObject(); put(supplied, "sourceName", safeBasename(actionId) + ".json");
        put(supplied, "mime", "application/json"); put(supplied, "mimeSource", "tool_contract"); put(supplied, "kind", "tool_result");
        put(supplied, "rootRequestId", rootRequestId); put(supplied, "actionId", actionId); put(supplied, "totalChars", text.length());
        try (InputStream source = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
            JSONObject saved = attachmentOriginals.save(source, supplied); saved.remove("path"); return saved;
        }
    }

    /** Verified immutable image handle. Run on an IO worker; no raw host path is exposed. */
    public VerifiedImageSource openVerifiedImage(JSONObject metadata)throws IOException {
        if(metadata==null)throw new ImageSourceFailure("invalid_identity","Image metadata is missing",null);
        String id=metadata.optString("originalAssetId",metadata.optString("id",""));
        if(!id.matches("\\.attachment-originals/[a-f0-9]{64}\\.[a-z0-9]{1,12}")
                ||metadata.has("id")&&!id.equals(metadata.optString("id")))
            throw new ImageSourceFailure("original_missing","An immutable image ID is required",null);
        String expected=metadata.optString("sha256","");
        if(!expected.matches("[a-f0-9]{64}"))throw new ImageSourceFailure("invalid_identity","Image SHA-256 is missing",null);
        if(!id.substring(AttachmentOriginals.PREFIX.length()+1,AttachmentOriginals.PREFIX.length()+65).equals(expected))
            throw new ImageSourceFailure("integrity_failed","Image ID and SHA-256 disagree",null);
        Object suppliedSize=metadata.opt("size");
        if(!(suppliedSize instanceof Number))throw new ImageSourceFailure("invalid_identity","Image byte size is missing",null);
        long size=((Number)suppliedSize).longValue();double number=((Number)suppliedSize).doubleValue();
        if(!Double.isFinite(number)||number!=(double)size||size<0)throw new ImageSourceFailure("invalid_identity","Image byte size is invalid",null);
        if(size>MAX_ATTACHMENT_BYTES)throw new ImageSourceFailure("encoded_too_large","Image exceeds its 64 MiB original limit",null);
        String mime=metadata.optString("mime",metadata.optString("mimeType","")).toLowerCase(java.util.Locale.ROOT);
        if(!mime.startsWith("image/"))throw new ImageSourceFailure("not_image","Image MIME metadata is required",null);
        FileInputStream opened;
        synchronized(this){
            JSONObject actual;
            try{actual=attachmentOriginals.describe(id,false);}
            catch(IOException missing){throw new ImageSourceFailure("source_unavailable","Image original metadata is unavailable",missing);}
            if(!expected.equals(actual.optString("sha256"))||size!=actual.optLong("size",-1))
                throw new ImageSourceFailure("integrity_failed","Image byte identity differs from its stored metadata",null);
            if(!actual.optString("mime",actual.optString("mimeType","")).toLowerCase(java.util.Locale.ROOT).startsWith("image/"))
                throw new ImageSourceFailure("not_image","The stored original is not marked as an image",null);
            opened=new FileInputStream(resolve(projects,id));
        }
        VerifiedImageSource source=new VerifiedImageSource(opened,id,expected,size);
        try{source.verifyUnchanged();return source;}
        catch(IOException|RuntimeException|Error failure){try{source.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }

    public static final class ImageSourceFailure extends IOException {
        private static final long serialVersionUID=1L;
        public final String code;
        ImageSourceFailure(String code,String message,Throwable cause){super(message,cause);this.code=code;}
    }

    /** SHA validation, all decoder passes and rewinds use one pinned FileInputStream/inode. */
    public static final class VerifiedImageSource implements java.io.Closeable {
        public final String originalAssetId,sha256;public final long size;
        private final FileInputStream input;private boolean closed;
        private VerifiedImageSource(FileInputStream input,String id,String hash,long size){this.input=input;originalAssetId=id;sha256=hash;this.size=size;}
        private void ensureOpen()throws IOException{if(closed)throw new IOException("Verified image source is closed");}
        public synchronized InputStream stream()throws IOException{ensureOpen();return input;}
        public synchronized java.io.FileDescriptor descriptor()throws IOException{ensureOpen();return input.getFD();}
        public synchronized void rewind()throws IOException{ensureOpen();input.getChannel().position(0);}
        /** Recheck after decoding too: mutations to the same open inode must not be rendered as verified. */
        public synchronized void verifyUnchanged()throws IOException{
            ensureOpen();rewind();
            long actualSize=input.getChannel().size();
            if(actualSize>MAX_ATTACHMENT_BYTES)throw new ImageSourceFailure("encoded_too_large","Image exceeds its 64 MiB original limit",null);
            if(actualSize!=size)throw new ImageSourceFailure("integrity_failed","Image original size changed",null);
            java.security.MessageDigest digest=OriginalTextFiles.digest();byte[]buffer=new byte[16_384];long readBytes=0;int count;
            while((count=input.read(buffer))!=-1){readBytes+=count;if(readBytes>size)throw new ImageSourceFailure("integrity_failed","Image grew during verification",null);digest.update(buffer,0,count);}
            if(readBytes!=size||!sha256.equals(OriginalTextFiles.hex(digest.digest())))throw new ImageSourceFailure("integrity_failed","Image original SHA-256 changed",null);
            rewind();
        }
        @Override public synchronized void close()throws IOException{if(!closed){closed=true;input.close();}}
    }

    /** A bounded UTF-8 text view. Binary identity is the SHA-256 of the exact saved bytes. */
    public synchronized JSONObject readTextRange(String fileId, long offsetChars, int maxChars) throws IOException {
        if (offsetChars < 0) throw new IOException("Text offset cannot be negative");
        int bound = Math.max(0, Math.min(maxChars, 1_000_000));
        File file = resolve(projects, fileId);
        if (!file.isFile() || file.length() > MAX_FILE_BYTES) throw new IOException("Working file is missing or oversized");
        String expectedHash = AttachmentOriginals.reserved(fileId) ? attachmentOriginals.describe(fileId, false).optString("sha256") : "";
        java.security.MessageDigest digest = OriginalTextFiles.digest();
        long total = 0; StringBuilder text = new StringBuilder(Math.min(bound, 8192)); char previous = 0;
        try (InputStreamReader reader = new InputStreamReader(new java.security.DigestInputStream(new FileInputStream(file), digest), StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))) {
            char[] chars = new char[8192]; int count;
            while ((count = reader.read(chars)) != -1) {
                for (int i = 0; i < count; i++, total++) {
                    char current = chars[i];
                    if (total == offsetChars && offsetChars > 0 && Character.isHighSurrogate(previous) && Character.isLowSurrogate(current)) throw new IOException("Text offset splits a UTF-16 pair");
                    if (total >= offsetChars && text.length() < bound) text.append(current);
                    previous = current;
                }
            }
        }
        String hash = OriginalTextFiles.hex(digest.digest());
        if (!expectedHash.isEmpty() && !hash.equals(expectedHash)) throw new IOException("Attachment original SHA-256 mismatch");
        long start = Math.min(offsetChars, total);
        if (text.length() > 0 && start + text.length() < total && Character.isHighSurrogate(text.charAt(text.length() - 1))) text.setLength(text.length() - 1);
        JSONObject range = new JSONObject(); put(range, "fileId", fileId); put(range, "text", text.toString());
        put(range, "offsetChars", start); put(range, "nextOffsetChars", start + text.length()); put(range, "totalChars", total);
        put(range, "complete", start + text.length() >= total); put(range, "contentHash", hash); put(range, "decodedTextView", true);
        return range;
    }

    public synchronized JSONArray listFiles() throws IOException {
        List<File> items = new ArrayList<>();
        collect(projects, items, 0);
        items.sort(Comparator.comparingLong(File::lastModified).reversed());
        JSONArray result = new JSONArray();
        for (File file : items) {
            String id = relativeId(file);
            if (AttachmentOriginals.reserved(id) && AttachmentOriginals.sidecar(file)) continue;
            result.put(metadata(file));
        }
        return result;
    }

    /** Direct identity lookup: attaching one photo must not enumerate every old project file. */
    public synchronized JSONObject describeStoredFile(String id) throws IOException {
        File file = resolve(projects, id);
        if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || AttachmentOriginals.sidecar(file)) throw new IOException("No such saved file");
        return metadata(file);
    }

    /** Bounded listing for interactive use; directory walking never holds the attachment/restore monitor. */
    public JSONObject listFilesPage(int limit, String query) throws IOException {
        if (limit < 1 || limit > 1000 || query == null || query.length() > 1000) throw new IllegalArgumentException("Invalid listing query");
        Comparator<JSONObject> order = Comparator.comparingLong((JSONObject item) -> item.optLong("updatedAt")).thenComparing(item -> item.optString("id"));
        java.util.PriorityQueue<JSONObject> recent = new java.util.PriorityQueue<>(limit, order);
        long[] counts = new long[3];
        collectListing(projects, 0, limit, query.toLowerCase(java.util.Locale.ROOT), recent, order, counts);
        List<JSONObject> selected = new ArrayList<>(recent); selected.sort(order.reversed()); JSONArray result = new JSONArray();
        for (JSONObject item : selected) result.put(item);
        JSONObject page = new JSONObject(); put(page, "files", result); put(page, "ok", true); put(page, "matchedCount", counts[1]);
        put(page, "scannedEntries", counts[0]); put(page, "skippedUnsupportedOrUnavailableEntries", counts[2]);
        put(page, "truncated", counts[1] > result.length()); put(page, "limit", limit); put(page, "query", query);
        put(page, "selectionOrder", "recent_modified"); put(page, "fileContentsRead", false); return page;
    }
    private void collectListing(File folder, int depth, int limit, String query, java.util.PriorityQueue<JSONObject> recent, Comparator<JSONObject> order, long[] counts) throws IOException {
        if (depth > 64 || Files.isSymbolicLink(folder.toPath())) { counts[2]++; return; }
        try (java.nio.file.DirectoryStream<java.nio.file.Path> children = Files.newDirectoryStream(folder.toPath())) {
            for (java.nio.file.Path path : children) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Listing interrupted");
                counts[0]++; File file = path.toFile();
                if (Files.isSymbolicLink(path)) { counts[2]++; continue; }
                if (file.isDirectory()) { collectListing(file, depth + 1, limit, query, recent, order, counts); continue; }
                if (!file.isFile() || AttachmentOriginals.sidecar(file) || file.getName().endsWith(".part")) continue;
                JSONObject item;
                try { item = metadata(file); item.remove("path"); put(item, "updatedAt", file.lastModified()); put(item, "contentBytesVerifiedByListing", false); }
                catch (IOException unavailable) { counts[2]++; continue; }
                String searchable = item.optString("id") + "\n" + item.optString("displayName") + "\n" + item.optString("sourceName", item.optString("name"));
                if (!query.isEmpty() && !searchable.toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
                counts[1]++;
                if (recent.size() < limit) recent.add(item);
                else if (order.compare(item, recent.peek()) > 0) { recent.poll(); recent.add(item); }
            }
        }
    }

    public synchronized JSONArray catalog() {
        JSONArray result = new JSONArray();
        JSONObject listing = new JSONObject(); put(listing, "limit", property("integer", "1–1000 recent files; default100")); put(listing, "query", property("string", "Optional filename/path substring; incomplete lists report truncated"));
        result.put(tool("files.list", "Search a bounded listing of the owner's saved working files without reading their contents.", listing, new JSONArray()));
        JSONObject read = new JSONObject();
        put(read, "fileId", property("string", "Relative id from files.list; not a host path."));
        put(read, "offsetChars", property("integer", "UTF-16 offset returned by the previous read; default zero."));
        put(read, "maxChars", property("integer", "Maximum text characters: 0–24,000; default 20,000."));
        result.put(tool("files.read", "Read an exact UTF-8 range with hash, nextOffsetChars, totalChars and complete. Continue at nextOffsetChars when incomplete.", read, new JSONArray().put("fileId")));
        JSONObject write = new JSONObject();
        put(write, "relativePath", property("string", "Relative path within the private projects folder."));
        put(write, "content", property("string", "UTF-8 file content; writes are bounded and atomic."));
        result.put(tool("files.write", "Create or replace a private working file.", write, new JSONArray().put("relativePath").put("content")));
        return result;
    }

    public JSONObject execute(String name, JSONObject arguments) throws IOException {
        if (arguments == null) arguments = new JSONObject();
        JSONObject result = new JSONObject();
        switch (name == null ? "" : name) {
            case "files.list":
                if (arguments.has("query") && !(arguments.opt("query") instanceof String)) throw new IllegalArgumentException("Invalid file query");
                result = listFilesPage((int)strictInteger(arguments, "limit", 100, 1, 1000), arguments.optString("query", "")); break;
            case "files.read":
                if (!(arguments.opt("fileId") instanceof String)) throw new IOException("fileId must be a string");
                String fileId = arguments.optString("fileId");
                long offset = strictInteger(arguments, "offsetChars", 0, 0, MAX_FILE_BYTES);
                int count = (int) strictInteger(arguments, "maxChars", 20_000, 0, 24_000);
                result = readTextRange(fileId, offset, count);
                break;
            case "files.write":
                // Reject malformed schema values before any filesystem operation;
                // optString would silently turn numbers or objects into file data.
                if (!(arguments.opt("relativePath") instanceof String)
                        || arguments.optString("relativePath").isEmpty())
                    throw new IllegalArgumentException("A nonempty string relativePath is required");
                if (!(arguments.opt("content") instanceof String))
                    throw new IllegalArgumentException("String file content is required");
                put(result, "file", writeFile(arguments.optString("relativePath"), arguments.optString("content")));
                break;
            default: throw new IOException("Unknown private-files tool");
        }
        put(result, "ok", true);
        if ("files.write".equals(name)) put(result, "completionConfirmed", true);
        return result;
    }

    public synchronized String readText(String fileId, int maxChars) throws IOException {
        int limit = Math.max(0, Math.min(maxChars, 1_000_000));
        File file = resolve(projects, fileId);
        if (AttachmentOriginals.reserved(fileId)) attachmentOriginals.describe(fileId, true);
        if (!file.isFile()) throw new IOException("No such working file");
        if (file.length() > MAX_FILE_BYTES) throw new IOException("Working file exceeds the 512 MiB limit");
        StringBuilder text = new StringBuilder(Math.min(limit, 8192));
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            while (text.length() < limit) {
                int read = reader.read(buffer, 0, Math.min(buffer.length, limit - text.length()));
                if (read < 0) break;
                text.append(buffer, 0, read);
            }
        }
        return text.toString();
    }

    public synchronized JSONObject writeFile(String relativePath, String content) throws IOException {
        assertStartupWritable();
        if (content == null) throw new IllegalArgumentException("File content is required");
        if (content.length() > MAX_FILE_BYTES) throw new IOException("Working file exceeds the 512 MiB limit");
        return writeFile(relativePath, new Utf8StringInput(content));
    }

    /** The caller owns the input stream. A failed/oversized write leaves the old file intact. */
    public synchronized JSONObject writeFile(String relativePath, InputStream source) throws IOException {
        assertStartupWritable();
        return writeFile(relativePath,source,MAX_FILE_BYTES);
    }

    /** An explicit smaller test/operation limit never raises the production binary limit. */
    public synchronized JSONObject writeFile(String relativePath,InputStream source,long maxBytes)throws IOException {
        assertStartupWritable();
        if (source == null) throw new IllegalArgumentException("File content is required");
        if(maxBytes<=0||maxBytes>MAX_FILE_BYTES)throw new IllegalArgumentException("Invalid working-file byte limit");
        if (AttachmentOriginals.reserved(relativePath)) throw new IOException("Attachment originals are immutable; use a separate working path");
        File target = resolve(projects, relativePath);
        if (AttachmentOriginals.reserved(relativeId(target))) throw new IOException("Attachment originals cannot be replaced through an alias");
        ensureDirectory(target.getParentFile());
        // Resolve again after making parents, in case the tree changed outside this helper.
        target = resolve(projects, relativePath);
        File temporary = new File(target.getParentFile(), ".partial-" + UUID.randomUUID());
        try {
            try (FileOutputStream raw = new FileOutputStream(temporary); OutputStream output = new BufferedOutputStream(raw)) {
                copy(source, output, maxBytes);
                output.flush();
                raw.getFD().sync();
            }
            atomicMove(temporary, target);
            return metadata(target);
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    /** Export to the user-selected Storage Access Framework destination. */
    public synchronized void exportFile(String fileId, OutputStream destination) throws IOException {
        if (destination == null) throw new IllegalArgumentException("A destination is required");
        File source = resolve(projects, fileId);
        if (AttachmentOriginals.reserved(fileId)) attachmentOriginals.describe(fileId, true);
        if (!source.isFile()) throw new IOException("No such working file");
        try (InputStream input = new BufferedInputStream(new FileInputStream(source))) {
            copy(input, destination, MAX_FILE_BYTES);
        }
        destination.flush();
    }

    @FunctionalInterface public interface ExportDestination { OutputStream open() throws IOException; }

    /** Verify and open the source before a provider can truncate the destination. Owns both streams. */
    public synchronized void exportFileToDestination(String fileId, ExportDestination destination) throws IOException {
        if (destination == null) throw new IllegalArgumentException("A destination is required");
        File source = resolve(projects, fileId);
        if (!source.isFile() || Files.isSymbolicLink(source.toPath()) || AttachmentOriginals.sidecar(source))
            throw new IOException("No such saved file");
        if (source.length() > MAX_FILE_BYTES) throw new IOException("Working file exceeds the 512 MiB limit");
        if (AttachmentOriginals.reserved(fileId)) attachmentOriginals.describe(fileId, true);
        try (InputStream input = new BufferedInputStream(new FileInputStream(source))) {
            try (OutputStream output = destination.open()) {
                if (output == null) throw new IOException("The export destination is unavailable");
                copy(input, output, MAX_FILE_BYTES);
                output.flush();
            }
        }
    }

    /**
     * Snapshot originals, all memory revisions, requests/settings/events, and working files.
     * OutputStream ownership stays with the caller. Export this outside the app before uninstall.
     */
    public void exportBackup(OutputStream destination, HoyoungStore store) throws IOException {
        assertStartupWritable();
        if(destination==null||store==null)throw new IllegalArgumentException("A destination and store are required");
        exportBackup(destination,store,BackupPolicy.fromStore(store));
    }

    public void exportBackup(OutputStream destination, HoyoungStore store,BackupPolicy policy) throws IOException {
        assertStartupWritable();
        if (destination == null || store == null) throw new IllegalArgumentException("A destination and store are required");
        if(policy==null)throw new IllegalArgumentException("A backup policy is required");
        BackupPolicy.Session space=policy.session(privateRoot);
        synchronized (backupLock) {
            File journalSnapshot = new File(privateRoot, ".backup-snapshot-" + UUID.randomUUID());
            IOException primaryFailure=null;
            try {
                space.phase("export_snapshot","");
                BackupParticipant participant = backupParticipant;
                File deviceJournal = null;
                if (participant != null) {
                    deviceJournal = new File(journalSnapshot, "device-journal");
                    ensureDirectory(deviceJournal);
                    participant.capture(deviceJournal,space);
                    space.checkpoint("export_snapshot","device-journal",0);
                }
                // The journal snapshot precedes the SQLite cut: duplication can be deduped,
                // but an observation committed/deleted between the cuts must not disappear.
                StreamingBackup.exportArchive(destination, store, privateRoot, deviceJournal,policy,space);
                try{destination.flush();}catch(IOException failure){throw space.failure("backup_destination_io","Backup destination flush failed; the selected document may be incomplete",failure);}
            } catch(BackupFailure failure){primaryFailure=failure;throw failure;}
            catch(IOException failure){primaryFailure=space.failure("backup_export_io","Backup export did not finish; the external document may be incomplete",failure);throw primaryFailure;}
            finally {
                try{deleteTree(journalSnapshot);}
                catch(IOException cleanup){if(primaryFailure!=null)primaryFailure.addSuppressed(cleanup);else throw space.failure("backup_cleanup_io","Backup temporary files could not be removed",cleanup);}
            }
        }
    }

    public void importBackup(InputStream source, HoyoungStore store) throws IOException {
        assertStartupWritable();
        if(source==null||store==null)throw new IllegalArgumentException("A backup and store are required");
        importBackup(source,store,BackupPolicy.fromStore(store));
    }

    public void importBackup(InputStream source, HoyoungStore store,BackupPolicy policy) throws IOException {
        assertStartupWritable();
        if (source == null || store == null) throw new IllegalArgumentException("A backup and store are required");
        if(policy==null)throw new IllegalArgumentException("A backup policy is required");
        BackupPolicy.Session space=policy.session(privateRoot);
        synchronized (backupLock) {
            synchronized (this) {
                recoverRestore(); cleanupAbandonedStaging();
                space.phase("restore_preflight","");
                File staging = new File(privateRoot, ".restore-" + UUID.randomUUID());
                ensureDirectory(staging);
                String token = UUID.randomUUID().toString();
                File oldProjects = new File(privateRoot, ".previous-projects-" + UUID.randomUUID());
                File oldOriginals = new File(privateRoot, ".previous-originals-" + UUID.randomUUID());
                File oldDeviceJournal = new File(privateRoot, ".previous-device-journal-" + UUID.randomUUID());
                boolean journalWritten = false;
                IOException primaryFailure=null;
                try {
                    StreamingBackup.PreparedBackup prepared = StreamingBackup.prepare(source, store, staging,policy,space);
                    space.phase("restore_validate","");
                    store.validateOriginalFiles(prepared.databaseFile, staging);
                    AttachmentOriginals.validateForRestore(prepared.projectsDirectory, prepared.databaseFile);
                    File deviceJournal = new File(privateRoot, "device-journal");
                    ensureDirectory(deviceJournal);
                    File stagedDeviceJournal = new File(staging, "device-journal");
                    ensureDirectory(stagedDeviceJournal);
                    BackupParticipant participant = backupParticipant;
                    if (participant != null) participant.validate(stagedDeviceJournal);
                    // This is a conservative commit/WAL estimate, not a promise that SQLite's
                    // derived indexes have a fixed size. Rechecks continue inside the transaction.
                    long originalsBytes=treeBytes(prepared.originalsDirectory,0);
                    long commitEstimate=saturatedAdd(saturatedMultiply(prepared.databaseFile.length(),2),saturatedMultiply(originalsBytes,2));
                    File liveDatabase=context.getDatabasePath(HoyoungStore.DATABASE_NAME);
                    long liveBytes=saturatedAdd(liveDatabase.length(),new File(liveDatabase.getPath()+"-wal").length());
                    // Removing a large old generation can write its pages into WAL even when
                    // the incoming snapshot is small; include that existing footprint too.
                    commitEstimate=saturatedAdd(commitEstimate,saturatedMultiply(liveBytes,2));
                    space.checkpoint("restore_precommit","",saturatedAdd(commitEstimate,64L*1024));
                    // Publish a conservative union before swapping any authoritative originals.
                    // A crash/rollback may leave stricter controls, never a privacy rollback.
                    if(prepared.ownerControls!=null)OwnerPolicyBackup.merge(context,store,prepared.ownerControls);
                    com.hoyoung.assistant.ui.PrivateFormState.deleteAll(context);
                    writeRestoreJournal(token, staging, oldProjects, oldOriginals, oldDeviceJournal);
                    journalWritten = true;
                    atomicMove(projects, oldProjects);
                    atomicMove(new File(privateRoot, "originals"), oldOriginals);
                    atomicMove(deviceJournal, oldDeviceJournal);
                    atomicMove(prepared.projectsDirectory, projects);
                    atomicMove(prepared.originalsDirectory, new File(privateRoot, "originals"));
                    atomicMove(stagedDeviceJournal, deviceJournal);
                    store.importStagedDatabase(prepared.databaseFile, token,space);
                    com.hoyoung.assistant.ui.PrivateFormState.deleteAll(context);
                    // Recovery uses the transaction token to finish cleanup after either a
                    // successful commit or a process death in the directory-swap window.
                    recoverRestore();
                } catch(BackupFailure failure){primaryFailure=failure;throw failure;}
                catch (RuntimeException invalid) {
                    primaryFailure=space.failure("backup_restore_database","Backup restore did not finish; the transaction journal identifies the recoverable generation",invalid);throw primaryFailure;
                } catch(IOException failure){primaryFailure=space.failure(space.phase().equals("restore_validate")?"backup_invalid_archive":"backup_restore_io","Backup restore did not finish; the transaction journal identifies the recoverable generation",failure);throw primaryFailure;
                } finally {
                    boolean recovered=true;IOException cleanupFailure=null;
                    Boolean committed=journalWritten?null:Boolean.FALSE;
                    if(journalWritten)try{committed=restoreTokenCommitted(token);}catch(IOException inspection){if(primaryFailure!=null)primaryFailure.addSuppressed(inspection);}
                    try{if(journalWritten)recoverRestore();}
                    catch(IOException recovery){recovered=false;cleanupFailure=space.failure("backup_restore_recovery","Restore recovery remains pending; recoverable files and its journal are retained",recovery);}
                    if(recovered)try{deleteTree(staging);}catch(IOException cleanup){cleanupFailure=space.failure("backup_cleanup_io","Restore staging files could not be removed",cleanup);}
                    if(committed!=null){if(primaryFailure instanceof BackupFailure)((BackupFailure)primaryFailure).markRestoreCommitted(committed);if(cleanupFailure instanceof BackupFailure)((BackupFailure)cleanupFailure).markRestoreCommitted(committed);}
                    if(cleanupFailure!=null){if(primaryFailure!=null)primaryFailure.addSuppressed(cleanupFailure);else throw cleanupFailure;}
                }
            }
        }
    }

    /** The DB commit token decides whether every namespace swap commits or rolls back. */
    private void recoverRestore() throws IOException {
        File journal = new File(privateRoot, RESTORE_JOURNAL);
        if (!restoreJournalPresent(privateRoot)) return;
        if (Files.isSymbolicLink(journal.toPath())||!journal.isFile()||journal.length() > 8192) throw new IOException("Invalid restore journal");
        JSONObject record;
        try (InputStream input = new FileInputStream(journal); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            copy(input, bytes, 8192);
            try { record = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)); }
            catch (JSONException e) { throw new IOException("Invalid restore journal", e); }
        }
        String token = record.optString("token", "");
        try { UUID.fromString(token); } catch (IllegalArgumentException e) { throw new IOException("Invalid restore token", e); }
        File staging = journalDirectory(record.optString("staging", ""), ".restore-");
        JSONArray moves = record.optJSONArray("moves");
        if (moves == null) {
            moves = new JSONArray();
            JSONObject legacy = new JSONObject(); put(legacy, "current", "projects"); put(legacy, "previous", record.optString("previous")); moves.put(legacy);
        }
        boolean committed = restoreTokenCommitted(token);
        if(moves.length()==0||moves.length()>3)throw new IOException("Invalid restore namespace count");
        List<File> currents=new ArrayList<>(),previousDirectories=new ArrayList<>();Set<String> namespaces=new HashSet<>();
        // Check the complete generation before deleting even the first previous tree.
        // Otherwise a later missing namespace could destroy the only recoverable original.
        for (int i = 0; i < moves.length(); i++) {
            JSONObject move = moves.optJSONObject(i); if (move == null) throw new IOException("Invalid namespace move");
            String namespace = move.optString("current");
            if (!(namespace.equals("projects") || namespace.equals("originals") || namespace.equals("device-journal"))) throw new IOException("Invalid restore namespace");
            if(!namespaces.add(namespace))throw new IOException("Duplicate restore namespace");
            File current = new File(privateRoot, namespace);
            File previous = journalDirectory(move.optString("previous"), ".previous-" + namespace + "-");
            if(Files.isSymbolicLink(current.toPath())||Files.isSymbolicLink(previous.toPath())||previous.exists()&&!previous.isDirectory())throw new IOException("Unsafe restore namespace");
            if (committed) {
                if (!current.isDirectory()) throw new IOException("Committed restore is missing a namespace");
            } else if (!previous.isDirectory()&&!current.isDirectory()) throw new IOException("Original namespace is missing during rollback");
            currents.add(current);previousDirectories.add(previous);
        }
        for(int i=0;i<currents.size();i++){
            File current=currents.get(i),previous=previousDirectories.get(i);
            if(committed)deleteTree(previous);
            else if(previous.isDirectory()){deleteTree(current);atomicMove(previous,current);}
        }
        deleteTree(staging); removeRestoreJournal();
    }

    private boolean restoreTokenCommitted(String token)throws IOException {
        File databaseFile=context.getDatabasePath(HoyoungStore.DATABASE_NAME);if(!databaseFile.isFile())return false;
        try(SQLiteDatabase database=SQLiteDatabase.openDatabase(databaseFile.getPath(),null,SQLiteDatabase.OPEN_READONLY);
            Cursor cursor=database.rawQuery("SELECT value FROM settings WHERE setting_key = ?",new String[]{HoyoungStore.FILE_RESTORE_TOKEN_KEY})){
            return cursor.moveToFirst()&&token.equals(cursor.getString(0));
        }catch(RuntimeException unavailable){throw new IOException("Cannot inspect interrupted restore commit",unavailable);}
    }

    private File journalDirectory(String name, String prefix) throws IOException {
        if (!name.startsWith(prefix)) throw new IOException("Invalid restore directory");
        try { UUID.fromString(name.substring(prefix.length())); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid restore directory", e); }
        File directory = new File(privateRoot, name);
        if(Files.isSymbolicLink(directory.toPath()))throw new IOException("Unsafe restore directory");
        if (!directory.getCanonicalPath().startsWith(privateRoot.getCanonicalPath() + File.separator)) throw new IOException("Unsafe restore directory");
        return directory;
    }

    private void writeRestoreJournal(String token, File staging, File... previous) throws IOException {
        JSONObject record = new JSONObject(); put(record, "token", token); put(record, "staging", staging.getName());
        JSONArray moves = new JSONArray();
        String[] namespaces = {"projects", "originals", "device-journal"};
        for (int i = 0; i < previous.length; i++) {
            JSONObject move = new JSONObject(); put(move, "current", namespaces[i]); put(move, "previous", previous[i].getName()); moves.put(move);
        }
        put(record, "moves", moves);
        File temporary = new File(privateRoot, RESTORE_JOURNAL + ".partial");
        try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(record.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
        atomicMove(temporary, new File(privateRoot, RESTORE_JOURNAL));
    }

    private void removeRestoreJournal() throws IOException {
        File journal = new File(privateRoot, RESTORE_JOURNAL);
        if (journal.exists() && !journal.delete()) throw new IOException("Completed restore journal cannot be removed");
    }

    private void cleanupAbandonedStaging() throws IOException {
        if (new File(privateRoot, RESTORE_JOURNAL).exists()) return;
        File[] children = privateRoot.listFiles(); if (children == null) throw new IOException("Cannot inspect staging recovery");
        for (File file : children) {
            String name = file.getName();
            for (String prefix : new String[]{".restore-", ".backup-snapshot-", ".backup-index-"}) {
                if (!name.startsWith(prefix)) continue;
                try { UUID.fromString(name.substring(prefix.length())); deleteTree(file); }
                catch (IllegalArgumentException ignored) { /* Unknown owner files are never removed. */ }
            }
        }
    }

    private static long treeBytes(File directory,int depth)throws IOException {
        if(depth>32||Files.isSymbolicLink(directory.toPath()))throw new IOException("Unsafe restore sizing path");
        if(!directory.exists())return 0;
        File[] entries=directory.listFiles();if(entries==null)throw new IOException("Restore sizing directory cannot be listed");
        long total=0;
        for(File entry:entries){
            if(Files.isSymbolicLink(entry.toPath()))throw new IOException("Restore sizing does not follow symbolic links");
            total=saturatedAdd(total,entry.isDirectory()?treeBytes(entry,depth+1):entry.length());
        }
        return total;
    }
    private static long saturatedAdd(long left,long right){return left>Long.MAX_VALUE-right?Long.MAX_VALUE:left+right;}
    private static long saturatedMultiply(long value,int factor){return value>Long.MAX_VALUE/factor?Long.MAX_VALUE:value*factor;}

    /** Encode the caller's existing String in small windows rather than allocating its entire UTF-8 copy. */
    private static final class Utf8StringInput extends InputStream {
        final String source;int position,offset;byte[]window=new byte[0];
        Utf8StringInput(String source){this.source=source;}
        private boolean refill(){
            if(offset<window.length)return true;if(position>=source.length())return false;
            int end=Math.min(source.length(),position+8192);
            if(end<source.length()&&Character.isHighSurrogate(source.charAt(end-1))&&Character.isLowSurrogate(source.charAt(end)))end--;
            window=source.substring(position,end).getBytes(StandardCharsets.UTF_8);position=end;offset=0;return true;
        }
        @Override public int read(){return refill()?window[offset++]&255:-1;}
        @Override public int read(byte[]bytes,int target,int length){
            if(bytes==null)throw new NullPointerException();if(target<0||length<0||target>bytes.length-length)throw new IndexOutOfBoundsException();
            if(length==0)return 0;if(!refill())return-1;int count=Math.min(length,window.length-offset);System.arraycopy(window,offset,bytes,target,count);offset+=count;return count;
        }
    }

    private JSONObject metadata(File file) throws IOException {
        String id = relativeId(file);
        if (AttachmentOriginals.reserved(id)) return attachmentOriginals.describe(id, false);
        JSONObject result = new JSONObject();
        put(result, "id", relativeId(file));
        put(result, "name", file.getName());
        put(result, "displayName", file.getName());
        put(result, "path", file.getCanonicalPath());
        put(result, "size", file.length());
        put(result, "updatedAt", file.lastModified());
        put(result, "storage", "private-internal-files");
        return result;
    }

    private String relativeId(File file) throws IOException {
        String base = projects.getCanonicalPath() + File.separator;
        String path = file.getCanonicalPath();
        if (!path.startsWith(base)) throw new IOException("File is outside private projects");
        return path.substring(base.length()).replace(File.separatorChar, '/');
    }

    private static File resolve(File root, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.length() > 2048
                || relative.startsWith("/") || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0) {
            throw new IOException("Only a relative private-project path is allowed");
        }
        String[] parts = relative.split("/", -1);
        if (parts.length > 33) throw new IOException("Project path is too deep");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".partial-") || part.getBytes(StandardCharsets.UTF_8).length > 255) throw new IOException("Unsafe project path");
            for (int i = 0; i < part.length(); i++) if (Character.isISOControl(part.charAt(i))) throw new IOException("Unsafe project path");
        }
        File target = new File(root, relative);
        String base = root.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(base)) throw new IOException("Path escapes the private project folder");
        return target;
    }

    private static String safeBasename(String displayName) {
        String name = displayName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}:]", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.isEmpty()) name = "attachment";
        // Reserve bytes for the UUID prefix; filesystem limits count UTF-8 bytes, not characters.
        int end = Math.min(name.length(), 160);
        while (end > 0 && name.substring(0, end).getBytes(StandardCharsets.UTF_8).length > 180) end--;
        if (end > 0 && end < name.length() && Character.isHighSurrogate(name.charAt(end - 1))
                && Character.isLowSurrogate(name.charAt(end))) end--;
        return end == 0 ? "attachment" : name.substring(0, end);
    }

    private static void collect(File folder, List<File> result, int depth) throws IOException {
        if (depth > 32) throw new IOException("Project directory nesting is too deep");
        File[] children = folder.listFiles();
        if (children == null) throw new IOException("Private working files cannot be listed");
        for (File child : children) {
            if (child.getName().startsWith(".partial-")) continue;
            // Do not follow symlinks, even ones that currently point inside the tree.
            if (Files.isSymbolicLink(child.toPath())) throw new IOException("Symbolic links are not supported in private projects");
            if (child.isDirectory()) collect(child, result, depth + 1);
            else if (child.isFile()) result.add(child);
            else throw new IOException("Unsupported private working file");
            if (result.size() > MAX_BACKUP_ENTRIES) throw new IOException("Too many private working files");
        }
    }

    private static void ensureDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Private storage directory cannot be created");
    }

    private static void atomicMove(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static long copy(InputStream input, OutputStream output, long maximum) throws IOException {
        if (maximum < 0) throw new IOException("Storage limit exceeded");
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            if (total > maximum - read) throw new IOException("Storage limit exceeded");
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        if (Files.isSymbolicLink(file.toPath())) {
            if (!file.delete()) throw new IOException("Restore temporary link cannot be removed");
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Restore directory cannot be listed");
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("Restore temporary file cannot be removed");
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private static long strictInteger(JSONObject input, String key, long defaultValue, long minimum, long maximum) throws IOException {
        if (!input.has(key)) return defaultValue;
        Object value = input.opt(key);
        if (!(value instanceof Number)) throw new IOException(key + " must be an integer");
        double number = ((Number) value).doubleValue(); long integer = ((Number) value).longValue();
        if (!Double.isFinite(number) || number != (double) integer || integer < minimum || integer > maximum) throw new IOException(key + " is outside its integer bounds");
        return integer;
    }

    private static JSONObject property(String type, String description) {
        JSONObject result = new JSONObject();
        put(result, "type", type); put(result, "description", description);
        return result;
    }

    private static JSONObject tool(String name, String description, JSONObject properties, JSONArray required) {
        JSONObject schema = new JSONObject();
        put(schema, "type", "object"); put(schema, "properties", properties); put(schema, "required", required);
        put(schema, "additionalProperties", false);
        JSONObject result = new JSONObject();
        put(result, "name", name); put(result, "description", description); put(result, "inputSchema", schema);
        return result;
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream stream) { super(stream); }
        @Override public void close() throws IOException { flush(); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); }
    }
    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream stream) { super(stream); }
        @Override public void close() { /* Caller owns the selected-document stream. */ }
    }
    private static final class LimitedOutputStream extends FilterOutputStream {
        private long remaining;
        LimitedOutputStream(OutputStream stream, long maximum) { super(stream); remaining = maximum; }
        @Override public void write(int value) throws IOException {
            if (remaining < 1) throw new IOException("Compressed backup exceeds 64 MiB");
            out.write(value); remaining--;
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (remaining < length) throw new IOException("Compressed backup exceeds 64 MiB");
            out.write(bytes, offset, length); remaining -= length;
        }
    }
    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream stream, long maximum) { super(stream); remaining = maximum; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value != -1 && remaining-- <= 0) throw new IOException("Compressed backup exceeds 64 MiB");
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, (int) Math.min(length, Math.max(1, remaining)));
            if (read != -1 && read > remaining) throw new IOException("Compressed backup exceeds 64 MiB");
            if (read > 0) remaining -= read;
            return read;
        }
    }
}
