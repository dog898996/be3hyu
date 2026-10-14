package com.hoyoung.assistant.phone;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.SQLiteMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowWindowManagerImpl;
import java.util.List;
import static org.junit.Assert.*;

/** Native window/view fixture only; it does not prove visibility over real Galaxy app windows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class PhoneOverlayToolsTest {
    private Context context; private HoyoungStore store; private PhoneEventJournal journal; private PhoneOverlayTools tools; private ShadowWindowManagerImpl windows;
    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext(); store = new HoyoungStore(context); journal = PhoneEventJournal.bind(context, store); journal.refreshPolicyBlocking();
        tools = new PhoneOverlayTools(context, journal); windows = Shadow.extract((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)); ShadowSettings.setCanDrawOverlays(false);
    }
    @After public void cleanup() { tools.stopForRuntime(); journal.closeWorker(); journal.awaitClosedForTests(); store.close(); }
    @Test public void catalogContainsThreeUsableStrictOwnerControlledTools() throws Exception {
        JSONArray catalog = new JSONArray(); tools.appendCatalog(catalog); assertEquals(3, catalog.length());
        for (int i = 0; i < catalog.length(); i++) { JSONObject tool = catalog.getJSONObject(i); assertTrue(tools.supports(tool.getString("name"))); assertFalse(tool.getJSONObject("inputSchema").getBoolean("additionalProperties")); }
    }
    @Test public void actualOverlayGrantIsRequiredBeforeAnyWindowIsAdded() throws Exception {
        JSONObject result = tools.execute("phone_overlay_show", new JSONObject().put("text", "Synthetic only")); assertEquals("permission_missing", result.getString("code")); assertEquals(0, windows.getViews().size());
        ShadowSettings.setCanDrawOverlays(true); JSONObject shown = tools.execute("phone_overlay_show", new JSONObject().put("text", "Synthetic only")); assertTrue(shown.toString(), shown.getBoolean("ok")); assertEquals(1, windows.getViews().size());
        assertFalse(shown.getBoolean("touchInjectionGranted")); assertFalse(shown.getBoolean("screenCaptureGranted")); assertFalse(shown.getBoolean("ownerSawCardVerified"));
    }
    @Test public void showAndUpdateUseTheSameRealWindowAndPreserveTheAvatarCloseControls() throws Exception {
        ShadowSettings.setCanDrawOverlays(true); JSONObject shown = tools.execute("phone_overlay_show", new JSONObject().put("title", "호영").put("text", "운동 다녀와 🌙").put("xDp", 20).put("yDp", 70));
        assertTrue(shown.toString(), shown.getBoolean("ok")); View panel = windows.getViews().get(0);
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, ((WindowManager.LayoutParams) panel.getLayoutParams()).type);
        assertNotNull(findText(panel, "운동 다녀와 🌙")); assertNotNull(findDescription(panel, "호영 플로팅 카드 닫기"));
        JSONObject updated = tools.execute("phone_overlay_update", new JSONObject().put("text", "Synthetic update").put("xDp", 40)); assertTrue(updated.toString(), updated.getBoolean("ok")); assertEquals(1, windows.getViews().size()); assertSame(panel, windows.getViews().get(0)); assertNotNull(findText(panel, "Synthetic update"));
        assertEquals(Math.round(40 * context.getResources().getDisplayMetrics().density), updated.getInt("xPx"));
    }
    @Test public void ownerCloseButtonRemovesTheWindowAndUpdateCannotInventAnExistingOverlay() throws Exception {
        ShadowSettings.setCanDrawOverlays(true); assertEquals("overlay_not_visible", tools.execute("phone_overlay_update", new JSONObject().put("text", "No window")).getString("code"));
        assertTrue(tools.execute("phone_overlay_show", new JSONObject().put("text", "Synthetic")).getBoolean("ok")); View panel = windows.getViews().get(0);
        assertTrue(findDescription(panel, "호영 플로팅 카드 닫기").performClick()); assertEquals(0, windows.getViews().size());
        assertEquals("overlay_already_hidden", tools.execute("phone_overlay_hide", new JSONObject()).getString("code"));
    }
    @Test public void ownerToolSwitchBlocksShowWithoutAnAdditionalAppApprovalRule() throws Exception {
        ShadowSettings.setCanDrawOverlays(true); store.putSetting("toolPermissions", "{\"phone_overlay_show\":false}"); journal.applyPermissionPolicyFromOwner("{\"phone_overlay_show\":false}", true);
        assertEquals("permission_disabled", tools.execute("phone_overlay_show", new JSONObject().put("text", "Synthetic")).getString("code")); assertEquals(0, windows.getViews().size());
    }
    @Test public void permissionRevocationImmediatelyBlocksUpdatesAndArchivedExposure() throws Exception {
        ShadowSettings.setCanDrawOverlays(true); assertTrue(tools.execute("phone_overlay_show", new JSONObject().put("text", "Synthetic")).getBoolean("ok"));
        assertTrue(tools.canReadArchivedResult("phone_overlay_show", new JSONObject())); ShadowSettings.setCanDrawOverlays(false);
        assertEquals("permission_missing", tools.execute("phone_overlay_update", new JSONObject().put("text", "Not accepted")).getString("code")); assertFalse(tools.canReadArchivedResult("phone_overlay_show", new JSONObject()));
    }
    @Test public void hideAndBackupPauseRemoveTheWindowAndBlockNewDispatchUntilRestoreCompletes() throws Exception {
        ShadowSettings.setCanDrawOverlays(true); assertTrue(tools.execute("phone_overlay_show", new JSONObject().put("text", "Synthetic")).getBoolean("ok"));
        JSONObject hidden = tools.execute("phone_overlay_hide", new JSONObject()); assertTrue(hidden.getBoolean("windowRemoved")); assertEquals(0, windows.getViews().size());
        tools.execute("phone_overlay_show", new JSONObject().put("text", "Synthetic")); tools.pauseForBackupRestore(); assertEquals(0, windows.getViews().size());
        assertEquals("restore_in_progress", tools.execute("phone_overlay_show", new JSONObject().put("text", "Synthetic")).getString("code"));
        tools.afterBackupRestore(); assertTrue(tools.execute("phone_overlay_show", new JSONObject().put("text", "Synthetic")).getBoolean("ok"));
    }
    @Test public void invalidOverlayArgumentsNeverAddOrAlterAnyWindow() throws Exception {
        ShadowSettings.setCanDrawOverlays(true);
        for (JSONObject args : new JSONObject[]{new JSONObject(), new JSONObject().put("text", 17), new JSONObject().put("text", "Synthetic").put("widthDp", 1), new JSONObject().put("text", "Synthetic").put("tapOtherApps", true)}) {
            assertEquals("invalid_arguments", tools.execute("phone_overlay_show", args).getString("code"));
        }
        assertEquals(0, windows.getViews().size());
    }
    private static View findDescription(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View result = findDescription(((ViewGroup) view).getChildAt(i), description); if (result != null) return result; } return null;
    }
    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { TextView result = findText(((ViewGroup) view).getChildAt(i), text); if (result != null) return result; } return null;
    }
}
