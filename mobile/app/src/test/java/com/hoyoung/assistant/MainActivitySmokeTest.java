package com.hoyoung.assistant;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import org.junit.Test;
import org.junit.Before;
import org.robolectric.RuntimeEnvironment;
import com.hoyoung.assistant.phone.PermissionTestGrants;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = HoyoungApplication.class)
public class MainActivitySmokeTest {
    @Before public void grantDevicePermissions() { PermissionTestGrants.allowAll(RuntimeEnvironment.getApplication()); }
    @Test public void nativeAppStartsWithoutAccountAndKeepsTypedDraft() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        assertNotNull(findText(activity.findViewById(android.R.id.content), "호영"));
        EditText composer = findEditor(activity.findViewById(android.R.id.content));
        assertNotNull(composer);
        composer.setText("연결 전 입력도 폰에 저장해야 해");
        HoyoungApplication app = (HoyoungApplication) activity.getApplication();
        assertEquals("연결 전 입력도 폰에 저장해야 해", app.getStore().getSetting("ui.draft", ""));
        assertFalse(app.getRuntime().state().optBoolean("ready"));
        controller.pause().stop().destroy();
        ActivityController<MainActivity> recreated = Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals("연결 전 입력도 폰에 저장해야 해", findEditor(recreated.get().findViewById(android.R.id.content)).getText().toString());
        recreated.pause().stop().destroy();
    }
    @Test public void copiedDiagnosticsExcludePrivateStreamedConversation() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        HoyoungApplication app = (HoyoungApplication) activity.getApplication();
        app.getStore().putSetting("runtime.progress", "PRIVATE-CHAT-AND-NOTIFICATION-BODY");
        MainActivityUiTestSupport.navigate(activity, "설정");
        findText(activity.findViewById(android.R.id.content), "연결 진단 복사").performClick();
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        String copied = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
        assertFalse(copied.contains("PRIVATE-CHAT-AND-NOTIFICATION-BODY"));
        assertFalse(copied.contains("\"messages\""));
        assertFalse(copied.contains("\"requests\""));
        controller.pause().stop().destroy();
    }
    private EditText findEditor(View view) {
        if (view instanceof EditText editor) return editor;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { EditText found = findEditor(group.getChildAt(i)); if (found != null) return found; }
        return null;
    }
    private TextView findText(View view, String text) {
        if (view instanceof TextView label && text.contentEquals(label.getText())) return label;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { TextView found = findText(group.getChildAt(i), text); if (found != null) return found; }
        return null;
    }
}
