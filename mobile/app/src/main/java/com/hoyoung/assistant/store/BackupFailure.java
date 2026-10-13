package com.hoyoung.assistant.store;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;

/** An incomplete backup is never reported as success; paths are relative private IDs. */
public final class BackupFailure extends IOException {
    private static final long serialVersionUID = 1L;
    public final String code, phase, relativePath;
    public final long processedBytes, availableBytes, requiredBytes;
    private volatile Boolean restoreCommitted;

    BackupFailure(String code, String phase, String relativePath, long processedBytes,
                  long availableBytes, long requiredBytes, String message, Throwable cause) {
        super(message, cause);
        this.code=code; this.phase=phase; this.relativePath=relativePath;
        this.processedBytes=processedBytes; this.availableBytes=availableBytes; this.requiredBytes=requiredBytes;
    }

    public JSONObject diagnostics() {
        JSONObject result=new JSONObject();
        try {
            result.put("code",code); result.put("phase",phase); result.put("relativePath",relativePath);
            result.put("processedBytes",processedBytes); result.put("availableBytes",availableBytes);
            result.put("requiredBytes",requiredBytes); result.put("complete",false);
            result.put("providerCapacityKnown",false);
            if(restoreCommitted!=null)result.put("restoreCommitted",restoreCommitted);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return result;
    }
    void markRestoreCommitted(boolean committed){restoreCommitted=committed;}
}
