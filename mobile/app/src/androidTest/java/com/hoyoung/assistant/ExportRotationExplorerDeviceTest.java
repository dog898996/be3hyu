package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real file-export picker and rotation; all bytes belong to the test-only SAF provider. */
@RunWith(AndroidJUnit4.class)
public final class ExportRotationExplorerDeviceTest {
    private static final String FIXTURE_ROOT = "Hoyoung synthetic owner controls";

    @Test public void rotatingActualCreateDocumentPickerStillExportsTheSelectedOriginal() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DevicePermissionFixture.assertReady(context);
        HoyoungApplication app = (HoyoungApplication) context.getApplicationContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String name = "rotation-export-" + UUID.randomUUID() + ".txt";
        String source = "Exact original from before the Android document picker\n회전해도 파일 원문 유지 🐈\n";
        JSONObject saved = app.getFiles().writeFile("explorer-exports/" + name, source);
        Uri destination = DocumentsContract.buildDocumentUri(OwnerPolicyFixtureDocumentsProvider.AUTHORITY, "root/" + name);
        MainActivity[] before = {null}, after = {null};
        device.setOrientationNatural();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            DeviceFixtureHealth.openPage(device, "파일");
            String row = name + " · 0 KB";
            DeviceFixtureHealth.revealText(device, row);
            UiObject2 item = device.wait(Until.findObject(By.text(row).pkg(context.getPackageName())), 20_000);
            assertNotNull("The actual file list must show the synthetic source", item); item.click();
            UiObject2 export = device.wait(Until.findObject(By.text("외부에 저장").pkg(context.getPackageName())), 20_000);
            assertNotNull("The native file action dialog must expose export", export);
            scenario.onActivity(activity -> before[0] = activity);
            export.click();
            String pickerPackage = awaitPicker(device);
            chooseFixtureRoot(device, pickerPackage);

            device.setOrientationLeft();
            long rotationDeadline = SystemClock.elapsedRealtime() + 30_000;
            while (device.getDisplayWidth() <= device.getDisplayHeight() && SystemClock.elapsedRealtime() < rotationDeadline) SystemClock.sleep(100);
            assertTrue("The actual display must rotate while DocumentsUI owns the foreground", device.getDisplayWidth() > device.getDisplayHeight());
            pickerPackage = awaitPicker(device);
            UiObject2 save = device.wait(Until.findObject(By.text(Pattern.compile("(?i)^save$|^저장$")).pkg(pickerPackage)), 20_000);
            assertNotNull("The actual create-document picker must expose Save", save);
            assertTrue("Save must be enabled for the test-only writable destination", save.isEnabled()); save.click();

            long resultDeadline = SystemClock.elapsedRealtime() + 30_000;
            while (!context.getPackageName().equals(device.getCurrentPackageName()) && SystemClock.elapsedRealtime() < resultDeadline) SystemClock.sleep(100);
            DeviceFixtureHealth.awaitForeground(device);
            scenario.onActivity(activity -> after[0] = activity);
            assertNotSame("The real picker rotation must recreate MainActivity before its result is handled", before[0], after[0]);
            UiObject2 notice = device.wait(Until.findObject(By.text(Pattern.compile("^선택한 위치에 저장했어\\.$|^저장·복원을 완료하지 못했어.*")).pkg(context.getPackageName())), 30_000);
            assertNotNull("The actual file-export callback must report an outcome", notice);
            String outcome = notice.getText();
            String exported;
            try (InputStream input = context.getContentResolver().openInputStream(destination)) {
                assertNotNull("DocumentsUI must create the actual exported document", input);
                exported = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            String evidence = "HOYOUNG_EXPORT_ROTATION hardware=" + Build.HARDWARE + "; sourceId=" + saved.getString("id")
                    + "; actual-SAF-picker; actual-landscape-rotation; activity-recreated=true; sourceBytes="
                    + source.getBytes(StandardCharsets.UTF_8).length + "; destinationBytes=" + exported.getBytes(StandardCharsets.UTF_8).length
                    + "; outcome=" + outcome + "; test-provider-only";
            Bundle status = new Bundle(); status.putString("stream", evidence + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, status); android.util.Log.i("HoyoungDeviceProof", evidence);
            assertEquals("A result returning after Activity recreation must export the selected original exactly", source, exported);
            assertEquals("The actual successful export needs a successful native acknowledgement", "선택한 위치에 저장했어.", outcome);
        } finally {
            device.setOrientationNatural(); device.unfreezeRotation();
            try { DocumentsContract.deleteDocument(context.getContentResolver(), destination); }
            catch (Exception notCreatedOrGranted) { /* Only this unique test-owned destination is eligible for cleanup. */ }
        }
    }

    private static String awaitPicker(UiDevice device) {
        long deadline = SystemClock.elapsedRealtime() + 30_000;
        do {
            String current = device.getCurrentPackageName();
            if (current != null && current.endsWith("documentsui")) return current;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("The actual Android document picker did not become foreground: " + device.getCurrentPackageName());
    }

    private static void chooseFixtureRoot(UiDevice device, String pickerPackage) {
        UiObject2 root = device.findObject(By.text(FIXTURE_ROOT).pkg(pickerPackage));
        if (root == null) {
            UiObject2 navigation = device.findObject(By.desc("Show roots").pkg(pickerPackage));
            if (navigation == null) navigation = device.findObject(By.desc("Open navigation drawer").pkg(pickerPackage));
            if (navigation == null) navigation = device.findObject(By.clazz("android.widget.ImageButton").pkg(pickerPackage));
            assertNotNull("The real picker must expose its location drawer", navigation); navigation.click();
            root = device.wait(Until.findObject(By.text(FIXTURE_ROOT).pkg(pickerPackage)), 20_000);
        }
        assertNotNull("Export must use the existing test-only SAF provider", root); root.click();
        device.waitForIdle(1_000);
    }
}
