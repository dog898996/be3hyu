package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.view.View;
import android.widget.EditText;
import com.hoyoung.assistant.phone.PermissionTestGrants;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.AccountStore;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * Exercises a supported large account through the visible editor and saved activity state.
 * Robolectric does not execute Binder transactions: this is a state-size regression,
 * not a claim that TransactionTooLargeException was observed on an Android device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = HoyoungApplication.class, qualifiers = "ko-rKR-w412dp-h915dp-xhdpi")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class MediaSavedStateCapacityTest {
    // Binder's 1 MiB transaction buffer is shared; leave half for lifecycle/framework data.
    private static final int STATE_BUDGET_BYTES = 512 * 1024;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private HoyoungApplication app;

    @Before public void setup() throws Exception {
        app = (HoyoungApplication) RuntimeEnvironment.getApplication();
        app.getStore().putSetting("autonomy.enabled", "false");
        app.getStore().putSetting("memoryReviewEnabled", "false");
        PermissionTestGrants.allowAll(app);
        controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        activity = controller.get(); drain();
    }

    @After public void cleanup() {
        if (controller != null) controller.pause().stop().destroy();
    }

    @Test public void supportedLargeAccountEditsSurviveRecreationWithinBinderStateBudget() throws Exception {
        char[] characters = new char[300_000]; Arrays.fill(characters, 'n');
        String originalNotes = new String(characters);
        AccountStore accounts = new AccountStore(app, app.getStore());
        JSONObject saved = accounts.saveFromUi(new JSONObject()
                .put("service", "Large-state fixture")
                .put("username", "offline@example.invalid")
                .put("password", "synthetic-password")
                .put("notes", originalNotes));
        String accountId = saved.getString("accountId");
        assertEquals("The fixture is accepted by the real account store", originalNotes,
                accounts.get(accountId).getString("notes"));

        MainActivityUiTestSupport.navigate(activity, "계정"); drain();
        View card = MainActivityUiTestSupport.description(activity.findViewById(android.R.id.content),
                "계정 열기 · Large-state fixture · offline@example.invalid");
        assertNotNull("The stored account is offered by the visible account list", card);
        assertTrue(card.performClick()); drain();
        AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(editor); assertTrue(editor.isShowing());
        EditText notes = notes(editor);
        assertEquals(originalNotes, notes.getText().toString());
        String unsavedNotes = originalNotes + "\nUNSAVED exact suffix 🙂";
        notes.setText(unsavedNotes);

        Bundle state = new Bundle();
        controller.pause().saveInstanceState(state).stop().destroy(); controller = null;
        Parcel parcel = Parcel.obtain();
        int parcelBytes, marshalledBytes;
        try {
            parcel.writeBundle(state);
            parcelBytes = parcel.dataSize();
            marshalledBytes = parcel.marshall().length;
        } finally { parcel.recycle(); }
        // This independent lower bound is useful when ShadowParcel's marshalling encoding
        // differs from Android's native Parcel. Android writes String values as UTF-16.
        long topLevelStringUtf16Bytes = 0;
        for (String key : state.keySet()) {
            Object value = state.get(key);
            if (value instanceof String) topLevelStringUtf16Bytes += 2L * ((String) value).length();
        }
        String measured = "savedState parcel.dataSize=" + parcelBytes
                + ", Robolectric marshall bytes=" + marshalledBytes
                + ", top-level String UTF-16 payload lower bound=" + topLevelStringUtf16Bytes;
        System.out.println(measured);
        assertEquals("Recreation must not implicitly save edits", originalNotes,
                accounts.get(accountId).getString("notes"));
        assertTrue(measured + "; keep lifecycle state below " + STATE_BUDGET_BYTES + " bytes",
                parcelBytes < STATE_BUDGET_BYTES
                        && marshalledBytes < STATE_BUDGET_BYTES
                        && topLevelStringUtf16Bytes < STATE_BUDGET_BYTES);

        controller = Robolectric.buildActivity(MainActivity.class)
                .create(state).start().restoreInstanceState(state).resume().visible();
        activity = controller.get(); drain();
        AlertDialog restored = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(restored); assertTrue(restored.isShowing()); assertNotSame(editor, restored);
        assertEquals("The small saved state still restores every unsaved character",
                unsavedNotes, notes(restored).getText().toString());
        assertEquals(originalNotes, accounts.get(accountId).getString("notes"));
    }

    @Test public void accountDeletionRemovesEditorSnapshotAndOldBundleCannotRestoreCredentials() throws Exception {
        AccountStore accounts = new AccountStore(app, app.getStore());
        String originalPassword = "deleted-account-original-fixture-password";
        String editedPassword = "deleted-account-unsaved-fixture-password";
        JSONObject saved = accounts.saveFromUi(new JSONObject()
                .put("service", "Delete-state fixture")
                .put("username", "deleted@example.invalid")
                .put("password", originalPassword).put("notes", "private draft fixture"));
        String accountId = saved.getString("accountId");
        String cardDescription = "계정 열기 · Delete-state fixture · deleted@example.invalid";
        MainActivityUiTestSupport.navigate(activity, "계정"); drain();
        View card = MainActivityUiTestSupport.description(activity.findViewById(android.R.id.content), cardDescription);
        assertNotNull(card); assertTrue(card.performClick()); drain();
        AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(editor); assertTrue(editor.isShowing());
        EditText password = (EditText) MainActivityUiTestSupport.description(editor.getWindow().getDecorView(), "비밀번호");
        assertNotNull(password); assertEquals(originalPassword, password.getText().toString());
        password.setText(editedPassword);

        Bundle oldState = new Bundle();
        controller.pause().saveInstanceState(oldState).stop().destroy(); controller = null;
        String token = oldState.getString("privateFormStateToken");
        assertNotNull("The actual editor produced a private snapshot reference", token);
        File snapshot = new File(new File(app.getNoBackupFilesDir(), "private-form-state"), token + ".json");
        assertTrue("Snapshot exists before account removal", snapshot.isFile());
        String snapshotText = new String(Files.readAllBytes(snapshot.toPath()), StandardCharsets.UTF_8);
        assertTrue("Opening record is preserved before deletion", snapshotText.contains(originalPassword));
        assertTrue("Unsaved edits are preserved before deletion", snapshotText.contains(editedPassword));

        accounts.delete(accountId, "owner");
        assertNull(accounts.get(accountId));
        assertEquals(0, accounts.history(accountId, 10).length());
        assertFalse("Real AccountStore.delete removes the temporary credential copy", snapshot.exists());
        assertFalse(new File(snapshot + ".bak").exists());
        assertFalse(new File(snapshot + ".new").exists());
        assertEquals("Use the unchanged, stale saved Bundle", token, oldState.getString("privateFormStateToken"));

        controller = Robolectric.buildActivity(MainActivity.class)
                .create(oldState).start().restoreInstanceState(oldState).resume().visible();
        activity = controller.get(); drain();
        View root = activity.findViewById(android.R.id.content);
        assertNotNull(MainActivityUiTestSupport.text(root, "계정 보관함"));
        assertNull("The deleted account is absent from the restored list",
                MainActivityUiTestSupport.description(root, cardDescription));
        assertNull(MainActivityUiTestSupport.text(root, originalPassword));
        assertNull(MainActivityUiTestSupport.text(root, editedPassword));
        AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
        if (latest != null && latest.isShowing()) {
            View dialog = latest.getWindow().getDecorView();
            assertNull("An old Bundle cannot reopen the deleted account editor",
                    MainActivityUiTestSupport.description(dialog, "비밀번호"));
            assertNull(MainActivityUiTestSupport.text(dialog, originalPassword));
            assertNull(MainActivityUiTestSupport.text(dialog, editedPassword));
        }
        assertNull(accounts.get(accountId));
        assertFalse("Recreation cannot recreate the deleted credential snapshot", snapshot.exists());
    }

    private EditText notes(AlertDialog dialog) {
        View found = MainActivityUiTestSupport.description(dialog.getWindow().getDecorView(), "메모");
        assertTrue(found instanceof EditText); return (EditText) found;
    }

    private void drain() throws Exception {
        for (int pass = 0; pass < 4; pass++) {
            for (String name : new String[]{"io", "display"}) {
                Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true);
                ((ExecutorService) field.get(activity)).submit(() -> {}).get(10, TimeUnit.SECONDS);
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
}
