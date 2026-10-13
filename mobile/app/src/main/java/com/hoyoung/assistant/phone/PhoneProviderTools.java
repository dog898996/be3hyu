package com.hoyoung.assistant.phone;

import android.Manifest;
import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

final class PhoneProviderTools {
    private final Context context;
    PhoneProviderTools(Context context) { this.context = context.getApplicationContext(); }
    JSONObject contact(JSONObject args) throws Exception {
        if (!new PermissionCatalog(context).has(Manifest.permission.READ_CONTACTS)) return missing("contacts");
        long id = args.getLong("contactId"); if (id <= 0) throw new IllegalArgumentException();
        JSONArray rows = new JSONArray();
        String[] projection = {ContactsContract.Data._ID, ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1};
        try (Cursor cursor = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI, projection, ContactsContract.Data.CONTACT_ID + "=?", new String[]{String.valueOf(id)}, null)) {
            while (cursor != null && cursor.moveToNext() && rows.length() < 100) {
                String mime = cursor.getString(2);
                if (!mime.equals(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) && !mime.equals(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    && !mime.equals(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) && !mime.equals(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE) && !mime.equals(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)) continue;
                rows.put(PhoneEventJournal.object("dataId", cursor.getLong(0), "rawContactId", cursor.getLong(1), "mime", mime, "value", cursor.getString(3)));
            }
        }
        return PhoneEventJournal.object("ok", true, "code", "ok", "contactId", id, "fields", rows, "source", "android.contacts_provider", "observedAt", System.currentTimeMillis());
    }
    JSONObject updateContact(JSONObject args) throws Exception {
        PermissionCatalog permissions = new PermissionCatalog(context);
        if (!permissions.has(Manifest.permission.READ_CONTACTS) || !permissions.has(Manifest.permission.WRITE_CONTACTS)) return missing("contacts");
        long rawId = args.getLong("rawContactId"); if (rawId <= 0) throw new IllegalArgumentException();
        if (!args.has("name") && !args.has("phone") && !args.has("email")) throw new IllegalArgumentException();
        try (Cursor cursor = context.getContentResolver().query(ContactsContract.RawContacts.CONTENT_URI, new String[]{ContactsContract.RawContacts._ID}, ContactsContract.RawContacts._ID + "=? AND " + ContactsContract.RawContacts.DELETED + "=0", new String[]{String.valueOf(rawId)}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return PhoneEventJournal.object("ok", false, "code", "target_not_found");
        }
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        for (String key : new String[]{"name", "phone", "email"}) {
            if (!args.has(key)) continue;
            String value = args.getString(key); if (value.length() > 300 || value.trim().isEmpty()) throw new IllegalArgumentException();
            String mime = key.equals("name") ? ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE : key.equals("phone") ? ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE : ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE;
            String selection = ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?";
            String[] selectionArgs = {String.valueOf(rawId), mime};
            boolean exists;
            try (Cursor cursor = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI, new String[]{ContactsContract.Data._ID}, selection, selectionArgs, null)) { exists = cursor != null && cursor.moveToFirst(); }
            ContentProviderOperation.Builder operation = exists ? ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI).withSelection(selection, selectionArgs)
                : ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, mime);
            operations.add(operation.withValue(ContactsContract.Data.DATA1, value).build());
        }
        android.content.ContentProviderResult[] results = context.getContentResolver().applyBatch(ContactsContract.AUTHORITY, operations);
        return PhoneEventJournal.object("ok", true, "code", "ok", "rawContactId", rawId, "operationsApplied", results.length, "scope", "All existing fields of each requested MIME in this selected raw contact are replaced.");
    }
    JSONObject updateEvent(JSONObject args) throws Exception {
        if (!new PermissionCatalog(context).has(Manifest.permission.WRITE_CALENDAR)) return missing("calendar");
        long id = args.getLong("eventId"); if (id <= 0) throw new IllegalArgumentException();
        ContentValues values = new ContentValues();
        for (String key : new String[]{"title", "description", "location"}) {
            if (!args.has(key)) continue; String value = args.getString(key); if (value.length() > (key.equals("description") ? 4000 : 500)) throw new IllegalArgumentException();
            values.put(key.equals("title") ? CalendarContract.Events.TITLE : key.equals("description") ? CalendarContract.Events.DESCRIPTION : CalendarContract.Events.EVENT_LOCATION, value);
        }
        if (args.has("startMs") || args.has("endMs")) {
            if (!args.has("startMs") || !args.has("endMs")) throw new IllegalArgumentException(); long start = args.getLong("startMs"), end = args.getLong("endMs");
            if (start < 0 || end <= start || end - start > 366L * 86400000) throw new IllegalArgumentException();
            values.put(CalendarContract.Events.DTSTART, start); values.put(CalendarContract.Events.DTEND, end);
        }
        if (values.size() == 0) throw new IllegalArgumentException();
        int count = context.getContentResolver().update(CalendarContract.Events.CONTENT_URI, values, CalendarContract.Events._ID + "=?", new String[]{String.valueOf(id)});
        return PhoneEventJournal.object("ok", count == 1, "code", count == 1 ? "ok" : "target_not_found", "eventId", id, "rowsChanged", count);
    }
    private static JSONObject missing(String id) { return PhoneEventJournal.object("ok", false, "code", "permission_missing", "permissionId", id); }
}
