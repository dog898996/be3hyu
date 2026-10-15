package com.hoyoung.assistant.ui;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Large, unsaved editor state stays private and outside portable backups and Binder. */
public final class PrivateFormState {
    private static final String DIRECTORY = "private-form-state";
    private static final long MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private PrivateFormState() {}

    public static synchronized String write(Context context, JSONObject forms) throws IOException {
        String token = UUID.randomUUID().toString().replace("-", "");
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private form directory");
        AtomicFile file = file(context, token);
        FileOutputStream output = null;
        try {
            JSONObject envelope = new JSONObject().put("version", 1).put("forms", forms);
            output = file.startWrite();
            output.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
            return token;
        } catch (Exception failure) {
            if (output != null) file.failWrite(output);
            file.delete();
            throw new IOException("Cannot preserve private form state", failure);
        }
    }

    /** Do not consume on read: Android can recreate the same saved state more than once. */
    public static synchronized JSONObject read(Context context, String token) throws IOException {
        try {
            JSONObject envelope = new JSONObject(new String(file(context, token).readFully(), StandardCharsets.UTF_8));
            if (envelope.optInt("version") != 1) throw new IOException("Unsupported private form state");
            return envelope.getJSONObject("forms");
        } catch (Exception failure) {
            throw new IOException("Cannot restore private form state", failure);
        }
    }

    public static synchronized void delete(Context context, String token) {
        if (validToken(token)) file(context, token).delete();
    }

    /** Keep the currently referenced snapshot even when an old task is reopened. */
    public static synchronized void prune(Context context, String referencedToken) {
        File[] files = directory(context).listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS;
        for (File entry : files) {
            String name = entry.getName();
            if (!name.matches("[0-9a-f]{32}\\.json(?:\\.(?:bak|new))?")) continue;
            if (validToken(referencedToken) && name.startsWith(referencedToken + ".json")) continue;
            if (entry.isFile() && entry.lastModified() > 0 && entry.lastModified() < cutoff) entry.delete();
        }
    }

    /** A committed backup replaces the original records on which every open form was based. */
    public static synchronized void deleteAll(Context context) throws IOException {
        for(String token:tokens(context))deleteChecked(context,token);
    }

    /** Explicit account removal also removes its temporary editor copies, without touching other forms. */
    public static synchronized void deleteAccountSnapshots(Context context,String accountId) throws IOException {
        for(String token:tokens(context)){
            boolean remove;
            try{
                JSONObject account=read(context,token).optJSONObject("account");
                JSONObject record=account==null?null:account.optJSONObject("record");
                remove=record!=null&&accountId.equals(record.optString("accountId"));
            }catch(IOException unreadable){remove=true;}
            if(remove)deleteChecked(context,token);
        }
    }

    private static java.util.Set<String> tokens(Context context) throws IOException {
        File directory=directory(context);java.util.Set<String> tokens=new java.util.HashSet<>();
        if(!directory.exists())return tokens;
        File[] entries=directory.listFiles();if(entries==null)throw new IOException("Cannot inspect private form snapshots");
        for(File entry:entries)if(entry.getName().matches("[0-9a-f]{32}\\.json(?:\\.(?:bak|new))?"))tokens.add(entry.getName().substring(0,32));
        return tokens;
    }

    private static void deleteChecked(Context context,String token) throws IOException {
        AtomicFile atomic=file(context,token);atomic.delete();File base=atomic.getBaseFile();
        if(base.exists()||new File(base+".bak").exists()||new File(base+".new").exists())throw new IOException("Cannot remove private form snapshot");
    }

    private static File directory(Context context) { return new File(context.getNoBackupFilesDir(), DIRECTORY); }
    private static AtomicFile file(Context context, String token) {
        if (!validToken(token)) throw new IllegalArgumentException("Invalid private form token");
        return new AtomicFile(new File(directory(context), token + ".json"));
    }
    private static boolean validToken(String token) { return token != null && token.matches("[0-9a-f]{32}"); }
}
