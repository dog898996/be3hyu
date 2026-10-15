package com.hoyoung.assistant.phone;

import static org.junit.Assert.*;

import android.app.Notification;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.runtime.VoiceCaptureService;
import com.hoyoung.assistant.store.AssistantIdentity;
import com.hoyoung.assistant.ui.HoyoungAvatarView;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowWindowManagerImpl;
import org.robolectric.util.ReflectionHelpers;

/** JVM/native view and SQLite regression only; no actual overlay visibility or microphone claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = HoyoungApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AssistantIdentitySurfaceExplorerTest {
    private static final String CURRENT_NAME = "모모";
    private HoyoungApplication app;
    private PhoneOverlayTools overlay;
    private ShadowWindowManagerImpl windows;
    private ServiceController<VoiceCaptureService> voice;

    @Before public void renamedIdentity() throws Exception {
        app = (HoyoungApplication) RuntimeEnvironment.getApplication();
        AssistantIdentity identity = new AssistantIdentity(app, app.getStore(), app.getFiles());
        assertTrue(identity.update(CURRENT_NAME, null, false, identity.snapshot().getString("revision")).getBoolean("ok"));
        assertEquals(CURRENT_NAME, AssistantIdentity.name(app.getStore()));
        PhoneEventJournal journal = PhoneEventJournal.forContext(app);
        journal.refreshPolicyBlocking();
        overlay = ReflectionHelpers.getField(app.getPhoneTools(), "overlayTools");
        windows = Shadow.extract(app.getSystemService(WindowManager.class));
        ShadowSettings.setCanDrawOverlays(true);
    }

    @After public void cleanup() {
        if (overlay != null) overlay.stopForRuntime();
        if (voice != null) voice.destroy();
    }

    @Test public void defaultFloatingCardUsesCurrentSavedAssistantName() throws Exception {
        JSONObject result = overlay.execute("phone_overlay_show", new JSONObject().put("text", "저장된 새 이름 확인"));
        assertTrue(result.toString(), result.getBoolean("ok"));
        assertEquals(1, windows.getViews().size());
        View panel = windows.getViews().get(0);
        assertNotNull("A new default floating card must display the current saved name, not the bundled name", findText(panel, CURRENT_NAME));
    }

    @Test public void requestedFloatingCardTitleRemainsAnExplicitOverride() throws Exception {
        JSONObject result = overlay.execute("phone_overlay_show", new JSONObject().put("title", "오늘의 일정").put("text", "명시한 카드 제목"));
        assertTrue(result.toString(), result.getBoolean("ok"));
        View panel = windows.getViews().get(0);
        assertNotNull(findText(panel, "오늘의 일정"));
        renameThroughRuntime("나비");
        assertNotNull("Saving identity must preserve a custom card title", findText(panel, "오늘의 일정"));
        assertEquals("나비 프로필 사진", findAvatar(panel).getContentDescription());
        assertTrue(overlay.execute("phone_overlay_update", new JSONObject().put("text", "본문만 갱신")).getBoolean("ok"));
        assertSame(panel, windows.getViews().get(0));
        assertNotNull("An explicit card title must survive a later identity change", findText(panel, "오늘의 일정"));
    }

    @Test public void existingDefaultCardRefreshesSavedNameOnBodyOnlyUpdateAndShow() throws Exception {
        assertTrue(overlay.execute("phone_overlay_show", new JSONObject().put("text", "처음 본문")).getBoolean("ok"));
        View panel = windows.getViews().get(0);
        assertNotNull(findText(panel, CURRENT_NAME));
        rename("나비");
        assertTrue(overlay.execute("phone_overlay_update", new JSONObject().put("text", "본문만 갱신")).getBoolean("ok"));
        assertSame(panel, windows.getViews().get(0));
        assertNotNull("An existing default title must follow the newly saved assistant identity", findText(panel, "나비"));
        rename("루루");
        assertTrue(overlay.execute("phone_overlay_show", new JSONObject().put("text", "같은 창 다시 표시")).getBoolean("ok"));
        assertSame(panel, windows.getViews().get(0));
        assertNotNull(findText(panel, "루루"));
    }

    @Test public void savingIdentityRefreshesOpenDefaultCardAndAvatarWithoutAnotherOverlayCommand() throws Exception {
        assertTrue(overlay.execute("phone_overlay_show", new JSONObject().put("text", "유지할 본문")).getBoolean("ok"));
        View panel = windows.getViews().get(0);
        assertNotNull(findText(panel, CURRENT_NAME));
        assertEquals(CURRENT_NAME + " 프로필 사진", findAvatar(panel).getContentDescription());
        renameThroughRuntime("나비");
        assertEquals(1, windows.getViews().size());
        assertSame(panel, windows.getViews().get(0));
        assertNotNull("Saving identity must refresh an already visible default card", findText(panel, "나비"));
        assertNotNull(findText(panel, "유지할 본문"));
        assertEquals("나비 프로필 사진", findAvatar(panel).getContentDescription());
    }

    @Test public void recordingNotificationUsesCurrentSavedAssistantName() {
        voice = Robolectric.buildService(VoiceCaptureService.class).create();
        Notification notification = ReflectionHelpers.callInstanceMethod(voice.get(), "notification",
                ReflectionHelpers.ClassParameter.from(String.class, "fixture-no-recording"));
        String title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString();
        assertTrue("Recording title ignored the saved assistant identity: " + title, title.startsWith(CURRENT_NAME));
    }

    private void rename(String name) throws Exception {
        AssistantIdentity identity = new AssistantIdentity(app, app.getStore(), app.getFiles());
        assertTrue(identity.update(name, null, false, identity.snapshot().getString("revision")).getBoolean("ok"));
    }

    private void renameThroughRuntime(String name) throws Exception {
        assertTrue(app.getRuntime().updateAssistantIdentity(name, null, false,
                app.getRuntime().assistantIdentity().getString("revision")).getBoolean("ok"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static HoyoungAvatarView findAvatar(View view) {
        if (view instanceof HoyoungAvatarView) return (HoyoungAvatarView) view;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            HoyoungAvatarView child = findAvatar(((ViewGroup) view).getChildAt(index));
            if (child != null) return child;
        }
        return null;
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            TextView child = findText(((ViewGroup) view).getChildAt(index), text);
            if (child != null) return child;
        }
        return null;
    }
}
