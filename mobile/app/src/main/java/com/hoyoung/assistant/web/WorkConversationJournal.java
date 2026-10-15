package com.hoyoung.assistant.web;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Device-local ownership receipts, deliberately outside portable personal-data backups. */
final class WorkConversationJournal {
    private final AtomicFile file;
    private JSONObject value;
    WorkConversationJournal(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "work-conversation.json"));
        try {
            value = file.getBaseFile().exists() || new File(file.getBaseFile()+".bak").exists()
                    ? new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8)) : new JSONObject();
            if ((!id().isEmpty() && !validId(id())) || (cleaning() && id().isEmpty())) throw new IOException("Invalid ownership receipt");
        }
        catch (Exception invalid) { value = new JSONObject(); put(value, "unreadable", true); }
    }
    synchronized String id() { return value.optString("conversationId"); }
    synchronized boolean blocked() { return value.optBoolean("unreadable"); }
    synchronized boolean cleaning() { return value.optBoolean("cleanupPending"); }
    synchronized boolean deletionAttempted() { return value.optBoolean("deletionAttempted"); }
    synchronized String pendingRequest() { return value.optString("pendingRequestId"); }
    static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{8,128}"); }
    synchronized void begin(String requestId) throws IOException {
        if (blocked() || cleaning()) throw new IOException("Session cleanup required");
        if (!id().isEmpty()) return;
        if (!pendingRequest().isEmpty() && !pendingRequest().equals(requestId)) throw new IOException("Unresolved conversation creation");
        JSONObject next = copy(); put(next, "pendingRequestId", requestId); save(next);
    }
    synchronized boolean accept(String requestId, String conversationId) throws IOException {
        if (blocked() || cleaning() || !validId(conversationId)) return false;
        if (!id().isEmpty()) return id().equals(conversationId);
        if (!requestId.equals(pendingRequest()) || requestId.isEmpty()) return false;
        JSONObject next = copy(); put(next, "conversationId", conversationId); put(next, "creationRequestId", requestId);
        next.remove("pendingRequestId"); save(next); return true;
    }
    synchronized void notSent(String requestId) throws IOException {
        if (!requestId.equals(pendingRequest())) return;
        JSONObject next = copy(); next.remove("pendingRequestId"); save(next);
    }
    /** Retire a reservation after native request review, without claiming its unknown web chat was deleted. */
    synchronized void retireReviewedCreation(String requestId, String disposition) throws IOException {
        if (blocked() || cleaning() || !id().isEmpty() || requestId.isEmpty() || !requestId.equals(pendingRequest())
                || !("completed".equals(disposition) || "abandoned".equals(disposition) || "known_not_sent".equals(disposition)))
            throw new IOException("Creation review does not match reservation");
        JSONObject next=copy();next.remove("pendingRequestId");
        JSONObject retired=new JSONObject();put(retired,"requestId",requestId);put(retired,"disposition",disposition);
        put(retired,"providerDeletionVerified",false);put(next,"lastRetiredCreation",retired);save(next);
    }
    synchronized JSONObject snapshot() throws IOException { return copy(); }
    synchronized void requestCleanup() throws IOException {
        if (blocked() || !pendingRequest().isEmpty()) throw new IOException("Conversation ownership unresolved");
        if (id().isEmpty()) return;
        JSONObject next = copy(); put(next, "cleanupPending", true); save(next);
    }
    synchronized void beginDeletion() throws IOException {
        if (!cleaning() || deletionAttempted()) throw new IOException("Deletion needs owner review");
        JSONObject next=copy();put(next,"deletionAttempted",true);save(next);
    }
    synchronized void deleted(String id, boolean ownerConfirmed) throws IOException {
        if (!cleaning() || !id().equals(id) || !validId(id)) throw new IOException("Deletion receipt does not match owned conversation");
        JSONObject next = new JSONObject(); put(next, "lastDeletedId", id); put(next, "deletedAt", System.currentTimeMillis());
        put(next, "ownerConfirmed", ownerConfirmed); save(next);
    }
    synchronized void unknownCreationDeletedByOwner(String requestId) throws IOException {
        if (blocked() || !id().isEmpty() || requestId.isEmpty() || !requestId.equals(pendingRequest()))
            throw new IOException("Unresolved creation changed");
        JSONObject next=new JSONObject();put(next,"lastUnknownCreationReviewed",requestId);put(next,"ownerConfirmed",true);save(next);
    }
    private JSONObject copy() throws IOException {
        try { return new JSONObject(value.toString()); } catch (Exception invalid) { throw new IOException(invalid); }
    }
    private void save(JSONObject next) throws IOException {
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(next.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); value = next; }
        catch (Exception failure) { if (out != null) file.failWrite(out); throw new IOException("Conversation journal write failed", failure); }
    }
    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
    }
}
