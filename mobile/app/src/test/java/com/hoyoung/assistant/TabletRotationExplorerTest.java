package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Looper;
import android.widget.EditText;
import com.hoyoung.assistant.phone.PermissionTestGrants;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import java.lang.reflect.Field;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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

/** Fast lifecycle reproduction; the matching Android test is still required for device evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = HoyoungApplication.class, qualifiers = "ko-rKR-w800dp-h600dp-mdpi")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TabletRotationExplorerTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before public void setup() throws Exception {
        HoyoungApplication app = (HoyoungApplication) RuntimeEnvironment.getApplication();
        app.getStore().putSetting("autonomy.enabled", "false");
        app.getStore().putSetting("memoryReviewEnabled", "false");
        PermissionTestGrants.allowAll(app);
        controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        activity = controller.get(); drain();
    }

    @After public void cleanup() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (controller != null) controller.pause().stop().destroy();
    }

    @Test public void unfinishedAccountFormSurvivesConfigurationRecreation() throws Exception {
        MainActivityUiTestSupport.navigate(activity, "계정"); drain();
        assertTrue(MainActivityUiTestSupport.text(activity.findViewById(android.R.id.content), "계정 추가").performClick());
        AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(editor); assertTrue(editor.isShowing());
        ((EditText) MainActivityUiTestSupport.description(editor.getWindow().getDecorView(), "서비스"))
                .setText("offline tablet rotation fixture");
        ((EditText) MainActivityUiTestSupport.description(editor.getWindow().getDecorView(), "표시 이름"))
                .setText("작성 중인 합성 계정");
        Configuration rotated = new Configuration(activity.getResources().getConfiguration());
        rotated.orientation = rotated.orientation == Configuration.ORIENTATION_LANDSCAPE
                ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
        controller.configurationChange(rotated); activity = controller.get(); drain();
        assertNotNull("Configuration recreation must keep the account destination",
                MainActivityUiTestSupport.text(activity.findViewById(android.R.id.content), "계정 보관함"));
        AlertDialog restored = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(restored); assertTrue(restored.isShowing()); assertNotSame(editor, restored);
        assertEquals("offline tablet rotation fixture", ((EditText) MainActivityUiTestSupport.description(
                restored.getWindow().getDecorView(), "서비스")).getText().toString());
        assertEquals("작성 중인 합성 계정", ((EditText) MainActivityUiTestSupport.description(
                restored.getWindow().getDecorView(), "표시 이름")).getText().toString());
    }

    @Test public void fileExportKeepsSelectedOriginalAcrossPickerRecreation() throws Exception {
        HoyoungApplication app = (HoyoungApplication) RuntimeEnvironment.getApplication();
        String exact = "EXACT_OFFLINE_EXPORT\n한글 원본";
        app.getFiles().writeFile("rotation-export.txt", exact);
        MainActivityUiTestSupport.navigate(activity, "파일"); drain();
        assertNotNull(MainActivityUiTestSupport.text(activity.findViewById(android.R.id.content), "rotation-export.txt · 0 KB"));
        assertTrue(MainActivityUiTestSupport.text(activity.findViewById(android.R.id.content), "rotation-export.txt · 0 KB").performClick());
        AlertDialog choices = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(choices);
        assertTrue(choices.getListView().performItemClick(null, 1, 1)); choices.dismiss();
        assertEquals(205, Shadows.shadowOf(activity).getNextStartedActivityForResult().requestCode);

        Configuration rotated = new Configuration(activity.getResources().getConfiguration());
        rotated.orientation = rotated.orientation == Configuration.ORIENTATION_LANDSCAPE
                ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
        controller.configurationChange(rotated); activity = controller.get(); drain();
        File destination = File.createTempFile("hoyoung-rotation-export-", ".txt");
        try {
            activity.onActivityResult(205, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(destination)));
            drain();
            assertEquals("The chosen original must still be exported after picker recreation", exact,
                    new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
        } finally { assertTrue(destination.delete()); }
    }

    private void drain() throws Exception {
        for (String name : new String[]{"io", "display", "fileListing"}) {
            Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true);
            ((ExecutorService) field.get(activity)).submit(() -> {}).get(10, TimeUnit.SECONDS);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
