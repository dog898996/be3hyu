package com.hoyoung.assistant.phone;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public final class PermissionOnboardingTest {
    private Context context;
    private FakeProbe probe;
    private PermissionOnboarding gate;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("hoyoung_permission_requests_v1", Context.MODE_PRIVATE).edit().clear().commit();
        probe = new FakeProbe(); gate = new PermissionOnboarding(context, probe);
    }

    @Test public void incompleteSetupHasNoPassAndEveryActualGrantIsRequired() throws Exception {
        probe.runtime("contacts", Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS);
        probe.special("accessibility", false);
        assertFalse(gate.snapshot().ready()); assertEquals(2, gate.snapshot().pending().size());
        probe.grants.add(Manifest.permission.READ_CONTACTS);
        assertFalse(gate.snapshot().ready());
        probe.grants.add(Manifest.permission.WRITE_CONTACTS);
        assertEquals(1, gate.snapshot().grantedCount);
        probe.entries.getJSONObject(1).put("granted", true);
        assertTrue(gate.snapshot().ready());
    }

    @Test public void revocationAndFreshInstanceCannotReusePriorCompletion() throws Exception {
        probe.runtime("contacts", Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS);
        probe.grants.addAll(probe.declared);
        assertTrue(gate.snapshot().ready());
        probe.grants.remove(Manifest.permission.WRITE_CONTACTS);
        assertFalse(gate.snapshot().ready());
        assertFalse(new PermissionOnboarding(context, probe).snapshot().ready());
    }

    @Test public void coarseOnlyLocationDoesNotSatisfyAllPermissionRequest() throws Exception {
        probe.runtime("location", Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
        probe.grants.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        probe.entries.getJSONObject(0).put("granted", true); // Catalog correctly allows coarse for tools.
        assertFalse(gate.snapshot().ready()); // Setup deliberately requires the owner's full grant.
        probe.grants.add(Manifest.permission.ACCESS_FINE_LOCATION);
        assertTrue(gate.snapshot().ready());
    }

    @Test public void selectedMediaCannotPassAndFullMediaDoesNotRequireSelectedAlternative() throws Exception {
        probe.runtime("media_images", Manifest.permission.READ_MEDIA_IMAGES);
        probe.runtime("media_video", Manifest.permission.READ_MEDIA_VIDEO);
        probe.runtime("media_selected", Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        probe.grants.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        assertFalse(gate.snapshot().ready()); assertEquals(2, gate.snapshot().requiredCount);
        probe.grants.clear(); probe.grants.add(Manifest.permission.READ_MEDIA_IMAGES); probe.grants.add(Manifest.permission.READ_MEDIA_VIDEO);
        assertTrue(gate.snapshot().ready()); assertFalse(gate.snapshot().find("media_selected").required);
    }

    @Test public void absentHardwareAndUninstallableRolesCannotCreateUnfinishableSetup() throws Exception {
        probe.runtime("camera", Manifest.permission.CAMERA); probe.unsupported.add("camera");
        probe.runtime("sms", Manifest.permission.READ_SMS); probe.declared.remove(Manifest.permission.READ_SMS);
        probe.special("device_owner", false); probe.special("usage_access", true);
        PermissionOnboarding.Snapshot snapshot = gate.snapshot();
        assertTrue(snapshot.ready()); assertEquals(1, snapshot.requiredCount);
        assertFalse(snapshot.find("camera").required); assertFalse(snapshot.find("sms").required);
        assertFalse(snapshot.find("device_owner").required);
    }

    @Test public void fullRuntimeGrantsDoNotOverrideDisabledNotifications() throws Exception {
        probe.runtime("notifications", Manifest.permission.POST_NOTIFICATIONS);
        probe.grants.add(Manifest.permission.POST_NOTIFICATIONS); probe.notifications = false;
        assertFalse(gate.snapshot().ready());
        probe.notifications = true; assertTrue(gate.snapshot().ready());
    }

    @Test public void unavailableGrantQueryFailsClosed() throws Exception {
        probe.fail = true;
        assertFalse(gate.snapshot().ready());
        assertEquals("verification_failed", gate.snapshot().pending().get(0).id);
    }

    @Test public void firstBackgroundLocationRequestOnlyAsksForeground() throws Exception {
        probe.runtime("location", Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
        probe.runtime("background_location", Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertTrue(gate.request(activity, "background_location").launched);
        String[] requested = Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions;
        assertArrayEquals(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, requested);
        assertFalse(Arrays.asList(requested).contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION));
        assertFalse(gate.snapshot().ready());
    }

    @Test public void androidElevenPlusOpensSettingsForBackgroundAfterForeground() throws Exception {
        probe.runtime("location", Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
        probe.runtime("background_location", Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        probe.grants.add(Manifest.permission.ACCESS_COARSE_LOCATION); probe.grants.add(Manifest.permission.ACCESS_FINE_LOCATION);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertTrue(gate.request(activity, "background_location").launched);
        Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, launched.getAction());
        assertNull(Shadows.shadowOf(activity).getLastRequestedPermission());
        assertFalse(gate.snapshot().ready());
    }

    @Test @Config(sdk = 29) public void androidTenRequestsBackgroundInItsOwnDialog() throws Exception {
        probe.runtime("location", Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
        probe.runtime("background_location", Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        probe.grants.add(Manifest.permission.ACCESS_COARSE_LOCATION); probe.grants.add(Manifest.permission.ACCESS_FINE_LOCATION);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertTrue(gate.request(activity, "background_location").launched);
        assertArrayEquals(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions);
        assertFalse(gate.snapshot().ready());
    }

    @Test public void preciseUpgradeIncludesAlreadyGrantedCoarsePermissionForAndroidTwelve() throws Exception {
        probe.runtime("location", Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION);
        probe.grants.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertTrue(gate.request(activity, "location").launched);
        assertArrayEquals(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION},
                Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions);
    }

    @Test public void mediaPromptRequestsImagesVideosAndSelectedScopeTogether() throws Exception {
        probe.runtime("media_images", Manifest.permission.READ_MEDIA_IMAGES);
        probe.runtime("media_video", Manifest.permission.READ_MEDIA_VIDEO);
        probe.runtime("media_selected", Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertTrue(gate.request(activity, "media_images").launched);
        Set<String> requested = new HashSet<>(Arrays.asList(Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions));
        assertEquals(probe.declared, requested);
    }

    @Test public void noPromptAfterPermanentDenialOpensAppSettingsAndStillBlocks() throws Exception {
        probe.runtime("camera", Manifest.permission.CAMERA);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        gate.request(activity, "camera"); // Subsequent denial + no rationale means Android won't show again.
        PermissionOnboarding fresh = new PermissionOnboarding(context, probe);
        assertTrue(fresh.request(activity, "camera").launched);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Shadows.shadowOf(activity).getNextStartedActivity().getAction());
        assertFalse(fresh.snapshot().ready());
    }

    @Test public void unavailableSettingsNeverPretendPermissionGranted() throws Exception {
        probe.special("accessibility", false);
        Activity activity = Robolectric.buildActivity(RejectingSettingsActivity.class).setup().get();
        PermissionOnboarding.RequestResult result = gate.request(activity, "accessibility");
        assertFalse(result.launched); assertTrue(result.message.contains("미완료"));
        assertFalse(gate.snapshot().ready());
    }

    @Test public void notificationListenerAndAccessibilityAreDifferentMandatoryGrants() throws Exception {
        probe.special("notification_access", true); probe.special("accessibility", false);
        assertFalse(gate.snapshot().ready()); assertEquals("accessibility", gate.snapshot().pending().get(0).id);
        probe.entries.getJSONObject(1).put("granted", true);
        assertTrue(gate.snapshot().ready());
    }

    @Test public void realAndroidGrantFixturePassesAndRealRevocationReopensGate() throws Exception {
        PermissionTestGrants.allowAll(context);
        PermissionOnboarding actual = new PermissionOnboarding(context);
        assertTrue(pendingDescription(actual), actual.snapshot().ready());
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.READ_CONTACTS);
        assertFalse(actual.snapshot().ready());
        assertEquals("contacts", actual.snapshot().pending().get(0).id);
    }

    @Test public void unknownInstallerEligibilityRequiresAnActualRequestBeforeAnyConditionalExclusion() throws Exception {
        probe.conditional("sms_read", Manifest.permission.READ_SMS, "unknown");
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertFalse(gate.snapshot().ready()); assertTrue(gate.request(activity, "sms_read").launched);
        assertArrayEquals(new String[]{Manifest.permission.READ_SMS}, Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions);
        assertFalse("Launching a prompt is not a returned denial", gate.snapshot().ready());
        gate.recordRequestResult(activity, new String[]{Manifest.permission.READ_SMS}, new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
        PermissionOnboarding.Item conditional = gate.snapshot().find("sms_read");
        assertFalse(conditional.required); assertFalse(conditional.granted); assertTrue(conditional.detail.contains("확인할 수 없"));
        assertTrue(gate.request(activity, "sms_read").launched);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Shadows.shadowOf(activity).getNextStartedActivity().getAction());
    }

    @Test public void knownAllowlistedPermissionStillBlocksAfterNoPromptDenial() throws Exception {
        probe.conditional("sms_send", Manifest.permission.SEND_SMS, "eligible");
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        gate.recordRequestResult(activity, new String[]{Manifest.permission.SEND_SMS}, new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
        assertTrue(gate.snapshot().find("sms_send").required); assertFalse(gate.snapshot().ready());
    }

    @Test public void previouslyObservedConditionalGrantRemainsRequiredAfterActualRevocation() throws Exception {
        probe.conditional("call_log_read", Manifest.permission.READ_CALL_LOG, "unknown");
        probe.grants.add(Manifest.permission.READ_CALL_LOG); assertTrue(gate.snapshot().ready());
        probe.grants.remove(Manifest.permission.READ_CALL_LOG);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        gate.recordRequestResult(activity, new String[]{Manifest.permission.READ_CALL_LOG}, new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
        PermissionOnboarding restarted = new PermissionOnboarding(context, probe);
        assertFalse(restarted.snapshot().ready()); assertTrue(restarted.snapshot().find("call_log_read").required);
    }

    @Test public void installerBlockedPermissionDoesNotHideAnotherEligibleGrantOrPretendItGranted() throws Exception {
        probe.conditional("sms_read", Manifest.permission.READ_SMS, "installer_blocked");
        probe.conditional("sms_send", Manifest.permission.SEND_SMS, "eligible");
        assertFalse(gate.snapshot().find("sms_read").required); assertFalse(gate.snapshot().find("sms_read").granted);
        assertEquals("sms_send", gate.snapshot().pending().get(0).id); assertFalse(gate.snapshot().ready());
        probe.grants.add(Manifest.permission.SEND_SMS); assertTrue(gate.snapshot().ready());
    }

    @Test public void actualGrantWinsOverStaleInstallerMetadataAndIsStillCounted() throws Exception {
        probe.conditional("sms_receive", Manifest.permission.RECEIVE_SMS, "installer_blocked"); probe.grants.add(Manifest.permission.RECEIVE_SMS);
        assertTrue(gate.snapshot().ready()); assertTrue(gate.snapshot().find("sms_receive").required);
        assertEquals(1, gate.snapshot().grantedCount);
    }

    @Test public void unknownEligibilityWithAndroidRationaleRemainsRequestableAndRequired() throws Exception {
        probe.conditional("call_log_write", Manifest.permission.WRITE_CALL_LOG, "unknown");
        Activity activity = Robolectric.buildActivity(RationaleActivity.class).setup().get();
        gate.recordRequestResult(activity, new String[]{Manifest.permission.WRITE_CALL_LOG}, new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
        assertFalse(gate.snapshot().ready()); assertTrue(gate.snapshot().find("call_log_write").required);
    }

    @Test public void conditionalResultHandlingNeverMakesOrdinaryCameraDenialOptional() throws Exception {
        probe.runtime("camera", Manifest.permission.CAMERA);
        gate.recordRequestResult(Robolectric.buildActivity(Activity.class).setup().get(), new String[]{Manifest.permission.CAMERA}, new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
        assertFalse(gate.snapshot().ready()); assertTrue(gate.snapshot().find("camera").required);
    }

    public static class RationaleActivity extends Activity {
        @Override public boolean shouldShowRequestPermissionRationale(String permission) { return true; }
    }

    public static class RejectingSettingsActivity extends Activity {
        @Override public void startActivity(Intent intent) { throw new android.content.ActivityNotFoundException("Synthetic settings unavailable"); }
    }

    private static String pendingDescription(PermissionOnboarding gate) {
        StringBuilder out = new StringBuilder();
        for (PermissionOnboarding.Item item : gate.snapshot().pending()) out.append(item.id).append(' ');
        return out.toString();
    }

    private static final class FakeProbe implements PermissionOnboarding.Probe {
        final JSONArray entries = new JSONArray();
        final Set<String> grants = new HashSet<>(), declared = new HashSet<>(), unsupported = new HashSet<>();
        boolean fail, notifications = true;
        void runtime(String id, String... permissions) throws Exception {
            declared.addAll(Arrays.asList(permissions));
            entries.put(new JSONObject().put("id", id).put("kind", "runtime").put("label", id)
                    .put("runtimePermissions", new JSONArray(Arrays.asList(permissions))).put("granted", false));
        }
        void conditional(String id, String permission, String eligibility) throws Exception {
            runtime(id, permission); entries.getJSONObject(entries.length() - 1).put("installerRestricted", true).put("grantEligibility", eligibility);
        }
        void special(String id, boolean granted) throws Exception {
            entries.put(new JSONObject().put("id", id).put("kind", "special").put("label", id).put("granted", granted));
        }
        @Override public JSONArray entries() { if (fail) throw new SecurityException("Synthetic query denial"); return entries; }
        @Override public boolean has(String permission) { return grants.contains(permission); }
        @Override public boolean declared(String permission) { return declared.contains(permission); }
        @Override public String unavailableReason(String id) { return unsupported.contains(id) ? "Synthetic unavailable hardware" : ""; }
        @Override public boolean notificationsEnabled() { return notifications; }
    }
}
