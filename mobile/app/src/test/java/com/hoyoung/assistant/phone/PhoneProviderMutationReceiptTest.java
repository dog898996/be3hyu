package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.ContactsContract;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class PhoneProviderMutationReceiptTest {
    private PhoneProviderTools tools;
    private ContactProvider provider;
    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication(); tools = new PhoneProviderTools(context);
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS);
        provider = new ContactProvider(); ProviderInfo info = new ProviderInfo(); info.authority = ContactsContract.AUTHORITY;
        provider.attachInfo(context, info); ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider);
    }
    @Test public void disappearedContactDoesNotConfirmAZeroRowUpdate() throws Exception {
        provider.updateRows = 0;
        JSONObject result = tools.updateContact(new JSONObject().put("rawContactId", 7).put("name", "synthetic replacement"));
        assertFalse(result.getBoolean("ok")); assertEquals("target_not_found", result.getString("code"));
        assertEquals(1, provider.updates); assertEquals(0, result.getInt("operationsApplied")); assertEquals(0, result.getInt("rowsChanged"));
        assertFalse(result.getBoolean("providerConfirmed")); assertFalse(result.getBoolean("completionConfirmed"));
    }
    @Test public void successfulProviderCountsRemainUsableCompletionEvidence() throws Exception {
        provider.updateRows = 2;
        JSONObject result = tools.updateContact(new JSONObject().put("rawContactId", 7).put("phone", "01012345678"));
        assertTrue(result.getBoolean("ok")); assertEquals(1, result.getInt("operationsApplied")); assertEquals(2, result.getInt("rowsChanged"));
        assertTrue(result.getBoolean("providerConfirmed")); assertTrue(result.getBoolean("completionConfirmed")); assertFalse(result.getBoolean("cloudSyncConfirmed"));
    }
    public static final class ContactProvider extends ContentProvider {
        int updateRows, updates;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            MatrixCursor cursor = new MatrixCursor(projection); cursor.addRow(new Object[]{7L}); return cursor;
        }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { updates++; return updateRows; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new AssertionError("The existing-field fixture must update"); }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public String getType(Uri uri) { return null; }
    }
}
