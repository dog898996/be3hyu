package com.hoyoung.assistant;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.Configurator;
import com.hoyoung.assistant.phone.HoyoungAccessibilityService;
import com.hoyoung.assistant.phone.PhoneEventJournal;
import com.hoyoung.assistant.phone.PhoneScreenCapture;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.Assert.*;

/** Real Android screenshot Binder and actual native fixture pixels on an offline
 * wiped emulator. No UiAutomation screenshot substitute and no private screen. */
@RunWith(AndroidJUnit4.class)
public final class NativeScreenCaptureDeviceTest {
    private static final int FIXTURE_COLOR = Color.rgb(213, 41, 179);

    @Test public void actualAccessibilityScreenshotPreservesPngPixelsSourceAndAndroidSecureSurfaceResult() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext(); DevicePermissionFixture.assertReady(context);
        Configurator configuration = Configurator.getInstance(); int priorFlags = configuration.getUiAutomationFlags();
        int flags = priorFlags | UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES;
        configuration.setUiAutomationFlags(flags); instrumentation.getUiAutomation(flags);
        HoyoungApplication application = (HoyoungApplication) context.getApplicationContext();
        HoyoungStore store = application.getStore(); assertNotNull(store);
        String priorPolicy = store.getSetting("toolPermissions", "{}");
        PhoneEventJournal journal = PhoneEventJournal.forContext(context);
        JSONObject allowed = new JSONObject(priorPolicy).put("phone_screen_capture", true)
                .put("phone_screen_capture_read", true).put("phone_screen_capture_cancel", true);
        store.putSetting("toolPermissions", allowed.toString()); journal.refreshPolicyBlocking();
        ActivityScenario<MainActivity> scenario = null; View[] fixture = {null}; int[] center = new int[2];
        try {
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (HoyoungAccessibilityService.getInstance() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertNotNull("Actual Android accessibility service must be connected", HoyoungAccessibilityService.getInstance());
            scenario = ActivityScenario.launch(MainActivity.class);
            scenario.onActivity(activity -> {
                fixture[0] = new View(activity); fixture[0].setBackgroundColor(FIXTURE_COLOR);
                fixture[0].setContentDescription("Synthetic offline screen screenshot fixture");
                ((ViewGroup) activity.findViewById(android.R.id.content)).addView(fixture[0], new ViewGroup.LayoutParams(-1, -1));
            });
            instrumentation.waitForIdleSync(); SystemClock.sleep(500);
            scenario.onActivity(activity -> {
                int[] location = new int[2]; fixture[0].getLocationOnScreen(location);
                assertTrue(fixture[0].getWidth() > 0); assertTrue(fixture[0].getHeight() > 0);
                center[0] = location[0] + fixture[0].getWidth() / 2; center[1] = location[1] + fixture[0].getHeight() / 2;
            });
            PhoneScreenCapture captures = PhoneScreenCapture.forContext(context, journal);
            JSONObject started = captures.execute("phone_screen_capture", new JSONObject()); assertTrue(started.toString(), started.getBoolean("ok"));
            assertFalse(started.getBoolean("completionConfirmed"));
            JSONObject result = awaitReceipt(captures, started.getString("requestId")); JSONObject receipt = result.getJSONObject("request");
            assertEquals("Real accessibility screenshot must succeed for our own ordinary fixture: " + receipt, "observed", receipt.getString("state"));
            assertTrue(result.getBoolean("uploadRequested")); assertTrue(result.getBoolean("completionConfirmed"));
            assertFalse(result.getBoolean("imageReadingConfirmed")); assertFalse(result.getBoolean("binaryUploaded"));
            JSONObject asset = result.getJSONObject("asset"); assertEquals("device_observation", asset.getString("sourceKind")); assertFalse(asset.getBoolean("ownerUtterance"));
            assertEquals("android.accessibility_screenshot", asset.getString("source")); assertEquals("tool_result", asset.getString("kind"));
            File image = new File(context.getFilesDir(), "projects/" + asset.getString("id")); assertTrue(image.isFile());
            assertEquals(asset.getLong("size"), image.length()); assertEquals(asset.getString("sha256"), sha256(Files.readAllBytes(image.toPath())));
            Bitmap decoded = BitmapFactory.decodeFile(image.getPath()); assertNotNull(decoded);
            try {
                assertEquals(receipt.getInt("width"), decoded.getWidth()); assertEquals(receipt.getInt("height"), decoded.getHeight());
                assertTrue(center[0] >= 0 && center[0] < decoded.getWidth()); assertTrue(center[1] >= 0 && center[1] < decoded.getHeight());
                assertColorNear(FIXTURE_COLOR, decoded.getPixel(center[0], center[1]));
            } finally { decoded.recycle(); }
            assertTrue(receipt.getBoolean("protectedPixelsMayBeOmittedByAndroid")); assertFalse(receipt.getBoolean("captureCompletenessGuaranteed"));

            // A user permission toggle does not bypass Android FLAG_SECURE.
            // Display APIs can report a secure-window error or omit/redact its
            // pixels. Both are truthful platform outcomes and neither is a fake
            // successful image reading or an app-invented permission rule.
            scenario.onActivity(activity -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE));
            instrumentation.waitForIdleSync(); SystemClock.sleep(800);
            JSONObject secureStarted = captures.execute("phone_screen_capture", new JSONObject()); assertTrue(secureStarted.toString(), secureStarted.getBoolean("ok"));
            JSONObject secureResult = awaitReceipt(captures, secureStarted.getString("requestId")); JSONObject secure = secureResult.getJSONObject("request");
            String secureOutcome;
            if ("observed".equals(secure.optString("state"))) {
                assertTrue(secure.getBoolean("protectedPixelsMayBeOmittedByAndroid")); assertFalse(secure.getBoolean("captureCompletenessGuaranteed"));
                File redactedFile = new File(context.getFilesDir(), "projects/" + secureResult.getJSONObject("asset").getString("id"));
                Bitmap redacted = BitmapFactory.decodeFile(redactedFile.getPath()); assertNotNull(redacted);
                try { assertFalse("Actual Android secure surface pixels must not be passed through", colorNear(FIXTURE_COLOR, redacted.getPixel(center[0], center[1]))); }
                finally { redacted.recycle(); }
                secureOutcome = "OS-redacted-secure-surface";
            } else {
                assertEquals("android_secure_window", secure.getString("code")); assertTrue(secure.has("androidError"));
                assertFalse(secureResult.has("asset")); assertFalse(secure.getBoolean("completionConfirmed"));
                secureOutcome = "OS-secure-window-error";
            }
            String marker = "HOYOUNG_DEVICE_SCREEN_CAPTURE actual-accessibility-Binder-pixels; actual-native-color-fixture; PNG-sha256-verified; immutable-original; device-observation-not-owner-message; actual-" + secureOutcome + "; no-real-account-or-private-screen; AI-reading-unconfirmed";
            android.util.Log.i("HoyoungDeviceProof", marker); Bundle proof = new Bundle(); proof.putString("stream", marker + "\n"); instrumentation.sendStatus(0, proof);
        } finally {
            if (scenario != null) {
                scenario.onActivity(activity -> activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE)); scenario.close();
            }
            store.putSetting("toolPermissions", priorPolicy); journal.refreshPolicyBlocking();
            configuration.setUiAutomationFlags(priorFlags); instrumentation.getUiAutomation(priorFlags);
        }
    }

    private static JSONObject awaitReceipt(PhoneScreenCapture capture, String id) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 12000; JSONObject result;
        do {
            result = capture.execute("phone_screen_capture_read", new JSONObject().put("requestId", id));
            assertTrue(result.toString(), result.getBoolean("ok"));
            if (!"pending".equals(result.getJSONObject("request").optString("state")) && !result.optBoolean("sourceIndexPending")) return result;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Actual screenshot callback did not produce a terminal durable receipt");
    }
    private static boolean colorNear(int expected, int actual) { return Math.abs(Color.red(expected) - Color.red(actual)) < 15 && Math.abs(Color.green(expected) - Color.green(actual)) < 15 && Math.abs(Color.blue(expected) - Color.blue(actual)) < 15; }
    private static void assertColorNear(int expected, int actual) { assertTrue("PNG must contain our actual controlled native pixels", colorNear(expected, actual)); }
    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder text = new StringBuilder();
        for (byte value : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return text.toString();
    }
}
