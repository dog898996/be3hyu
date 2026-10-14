package com.hoyoung.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Runs as the provider's test-package UID, to grant actual persistable tree authority. */
public final class OwnerPolicyFixtureGrantActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        grantUriPermission("com.hoyoung.assistant",DocumentsContract.buildTreeDocumentUri(OwnerPolicyFixtureDocumentsProvider.AUTHORITY,"root"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        finish();
    }
}
