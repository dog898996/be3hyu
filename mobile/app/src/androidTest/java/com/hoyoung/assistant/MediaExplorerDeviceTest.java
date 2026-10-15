package com.hoyoung.assistant;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.runtime.OwnerDraftStore;
import com.hoyoung.assistant.store.EmojiReactionReply;
import com.hoyoung.assistant.store.HoyoungStore;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Independent exploration on a real offline Android emulator. No mocked UI or Work account. */
@RunWith(AndroidJUnit4.class)
public final class MediaExplorerDeviceTest {
    private static final String APP = "com.hoyoung.assistant";
    private Context context;
    private HoyoungApplication app;
    private HoyoungStore store;
    private OwnerDraftStore drafts;
    private OwnerDraftStore.Snapshot originalDraft;
    private UiDevice device;

    @Before public void realOfflineDeviceOnly() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DevicePermissionFixture.assertReady(context);
        app = (HoyoungApplication) context.getApplicationContext();
        store = app.getStore(); drafts = new OwnerDraftStore(store);
        originalDraft = drafts.read(); drafts.edit("", new JSONArray());
        store.restoreChatView();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.setOrientationNatural();
        proof("device", "fingerprint=" + Build.FINGERPRINT + "; sdk=" + Build.VERSION.SDK_INT
                + "; display=" + device.getDisplayWidth() + "x" + device.getDisplayHeight()
                + "; synthetic-SAF; actual-native-window; offline");
    }

    @After public void restoreOnlyFixtureDraftAndOrientation() throws Exception {
        if (drafts != null && originalDraft != null) drafts.edit(originalDraft.text, originalDraft.attachments(), originalDraft.inputActor);
        if (store != null) store.restoreChatView();
        if (device != null) { device.setOrientationNatural(); device.unfreezeRotation(); }
    }

    @Test public void draftSurvivesChoiceDismissalPickerBackAndRealRotation() throws Exception {
        String text = "MEDIA_DRAFT " + UUID.randomUUID() + "\n한글 👩🏽‍💻 é\nline three";
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            editor().setText(text); hideKeyboard(scenario);
            OwnerDraftStore.Snapshot written = drafts.read(); assertEquals(text, written.text);
            clickDescription("사진 또는 파일 첨부"); clickText("닫기");
            assertEquals(written.clientInputId, drafts.read().clientInputId);
            openAllFiles(); assertPickerVisible(); device.pressBack();
            DeviceFixtureHealth.awaitForeground(device);
            assertEquals("Picker cancellation must preserve all Unicode text", text, editor().getText());
            assertEquals("Cancellation must not create a new submission identity", written.clientInputId, drafts.read().clientInputId);
            rotate(scenario, true);
            assertEquals(text, editor().getText());
            assertEquals(written.clientInputId, drafts.read().clientInputId);
            clickDescription("사진 또는 파일 첨부");
            rotate(scenario, false);
            if (device.hasObject(By.text("닫기").pkg(APP))) clickText("닫기");
            assertEquals("Rotation during the attachment dialog must preserve the exact draft", text, editor().getText());
            proof("draft-cancel-rotation", "exact-Unicode; stable-client-input-id; choice-dismiss; system-picker-back; two-real-rotations");
        }
    }

    @Test public void selectingSameDocumentTwiceRemainsSendableWithoutDuplicateOriginals() throws Exception {
        String text = "MEDIA_DUPLICATE " + UUID.randomUUID();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            editor().setText(text); hideKeyboard(scenario);
            pick("01-photo.png"); await(() -> drafts.read().attachments().length() == 1, "first SAF import");
            pick("01-photo.png");
            drainImportQueue(scenario);
            JSONArray selected = drafts.read().attachments();
            proof("duplicate-observation", "draft-attachments=" + selected.length() + "; ids=" + selected);
            assertEquals("Selecting an already attached original must not create an unsendable duplicate", 1, selected.length());
            long before = store.lastUserMessageId();
            clickDescription("메시지 보내기");
            JSONObject sent = awaitMessage(before, text);
            JSONArray metadata = new JSONArray(store.getSetting("attachmentMetadata:" + sent.getString("request_id"), "[]"));
            assertEquals(1, metadata.length());
            assertEquals("", drafts.read().text); assertEquals(0, drafts.read().attachments().length());
            proof("duplicate-send", "same-document-twice; one-immutable-original; real-send-persisted");
        }
    }

    @Test public void allFileTypesAndEmptyImageSurviveSendViewAndRotation() throws Exception {
        String text = "MEDIA_ALL_TYPES " + UUID.randomUUID();
        String[] files = {"01-photo.png", "02-audio.wav", "03-clip.mp4", "04-document.pdf", "05-unknown.dat", "06-empty.jpg"};
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            editor().setText(text); hideKeyboard(scenario);
            for (int i = 0; i < files.length; i++) {
                pick(files[i]); final int count = i + 1;
                await(() -> drafts.read().attachments().length() == count, "import " + files[i]);
            }
            rotate(scenario, true);
            assertEquals(text, editor().getText()); assertEquals(files.length, drafts.read().attachments().length());
            long before = store.lastUserMessageId(); clickDescription("메시지 보내기");
            JSONObject sent = awaitMessage(before, text);
            JSONArray assets = new JSONArray(store.getSetting("attachmentMetadata:" + sent.getString("request_id"), "[]"));
            assertEquals(files.length, assets.length());
            Set<String> names = new HashSet<>();
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i); names.add(asset.getString("sourceName"));
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); app.getFiles().exportFile(asset.getString("id"), bytes);
                assertEquals(asset.getLong("size"), bytes.size());
                if (asset.getString("sourceName").equals("06-empty.jpg")) assertEquals(0, bytes.size());
            }
            for (String file : files) assertTrue(file, names.contains(file));
            rotate(scenario, false);
            DeviceFixtureHealth.revealDescription(device, "01-photo.png");
            awaitImage(scenario, "01-photo.png"); clickDescription("01-photo.png");
            assertNotNull("Native image viewer must open", device.wait(Until.findObject(By.text("닫기").pkg(APP)), 15_000));
            rotate(scenario, true);
            DeviceFixtureHealth.awaitForeground(device);
            if (device.hasObject(By.text("닫기").pkg(APP))) clickText("닫기");
            DeviceFixtureHealth.revealText(device, "06-empty.jpg");
            UiObject2 broken = device.wait(Until.findObject(By.descContains("06-empty.jpg").clazz(ImageView.class)), 15_000);
            assertNotNull("An empty image must remain represented in chat", broken); broken.click();
            assertNotNull("Unreadable image bytes need a visible failure without losing the original",
                    device.wait(Until.findObject(By.text("사진을 표시하지 못했어. 보관된 원본을 파일 페이지에서 확인해 줘.")), 15_000));
            clickText("확인");
            proof("all-file-types", "PNG-native-pixels; WAV; MP4; PDF; binary; zero-byte-JPEG-explicit-failure; archived-bytes-preserved; rotation-during-viewer");
        }
    }

    @Test public void unreadableAndSlowDocumentsPreserveDraftAndCompleteAcrossRotation() throws Exception {
        String text = "MEDIA_IO_RACE " + UUID.randomUUID();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            editor().setText(text); hideKeyboard(scenario);
            OwnerDraftStore.Snapshot before = drafts.read();
            pick("07-unreadable.txt");
            assertNotNull(device.wait(Until.findObject(By.text("파일을 저장하지 못했어. 파일 크기와 접근 권한을 확인해.")), 20_000));
            clickText("확인");
            assertEquals(before.clientInputId, drafts.read().clientInputId); assertEquals(text, editor().getText());
            assertEquals(0, drafts.read().attachments().length());
            pick("08-slow.txt"); rotate(scenario, true);
            await(() -> drafts.read().attachments().length() == 1, "slow pipe completion after originating activity destruction");
            assertEquals(text, editor().getText());
            assertNotNull(device.wait(Until.findObject(By.text("첨부 1개 · 폰 내부에 저장됨")), 15_000));
            clickText("첨부 1개 · 폰 내부에 저장됨"); clickText("유지");
            assertEquals(1, drafts.read().attachments().length());
            clickText("첨부 1개 · 폰 내부에 저장됨"); clickText("첨부 선택 지우기");
            await(() -> drafts.read().attachments().length() == 0, "clear attachment selection");
            assertEquals(text, editor().getText());
            proof("provider-errors-race", "revoked-open-visible-error; original-draft-preserved; slow-SAF-pipe-after-rotation; selection-clear-keeps-text");
        }
    }

    @Test public void rapidSendAndReactionClearViewPreserveImmutableHistory() throws Exception {
        String text = "MEDIA_RAPID_SEND " + UUID.randomUUID();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            editor().setText(text); hideKeyboard(scenario);
            long before = store.lastUserMessageId();
            UiObject2 send = device.wait(Until.findObject(By.desc("메시지 보내기")), 15_000); assertNotNull(send);
            Rect bounds = send.getVisibleBounds();
            for (int i = 0; i < 4; i++) device.click(bounds.centerX(), bounds.centerY());
            JSONObject owner = awaitMessage(before, text);
            SystemClock.sleep(500);
            int copies = 0; JSONArray newer = store.listUserMessagesAfter(before, 100);
            for (int i = 0; i < newer.length(); i++) if (text.equals(newer.getJSONObject(i).optString("text"))) copies++;
            assertEquals("A fast repeated Send tap must create one durable owner message", 1, copies);
            JSONObject reaction = new JSONObject().put("emoji", "👍").put("targetMessageId", owner.getLong("id"))
                    .put("targetRecordHash", owner.getString("content_hash")).put("targetText", text);
            JSONObject reply = store.addMessage("assistant", EmojiReactionReply.originalText(reaction), owner.getString("request_id"));
            store.putSetting("answerSources:" + reply.getLong("id"), new JSONObject().put("version", 1).put("reaction", reaction)
                    .put("sources", new JSONArray().put(new JSONObject().put("type", "message").put("id", owner.getLong("id"))
                            .put("hash", owner.getString("content_hash")))).toString());
            assertNotNull(device.wait(Until.findObject(By.desc("호영이 이 메시지에 👍 이모지로 응답함")), 20_000));
            clickDescription("호영이 이 메시지에 👍 이모지로 응답함"); clickText("확인");
            long count = store.messageCount();
            clickDescription("대화 옵션"); clickText("대화방 비우기");
            await(() -> store.chatViewHiddenThroughId() >= reply.optLong("id"), "hide chat view");
            assertEquals(count, store.messageCount()); assertEquals(text, store.getMessage(owner.getLong("id")).getString("text"));
            rotate(scenario, true); assertEquals(count, store.messageCount());
            clickDescription("대화 옵션"); clickText("비운 대화 다시 보기");
            await(() -> store.chatViewHiddenThroughId() == 0, "restore chat view");
            assertNotNull(device.wait(Until.findObject(By.desc("호영이 이 메시지에 👍 이모지로 응답함")), 20_000));
            proof("send-reaction-history", "four-actual-send-taps-one-owner-record; synthetic-saved-reaction-native-badge; clear-and-restore-preserve-history-across-rotation");
        }
    }

    @Test public void rotationPreservesEachSidebarDestination() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            boolean landscape = false;
            for (String page : new String[]{"계정", "파일", "기억", "권한", "설정", "Work", "대화"}) {
                DeviceFixtureHealth.openPage(device, page);
                landscape = !landscape; rotate(scenario, landscape);
                final boolean[] present = {false};
                scenario.onActivity(activity -> {
                    View menu = find(activity.getWindow().getDecorView(), view -> "메뉴 열기".contentEquals(description(view)));
                    View parent = menu != null && menu.getParent() instanceof View ? (View) menu.getParent() : null;
                    String title = "대화".equals(page) ? "호영" : page;
                    present[0] = parent != null && find(parent, view -> view instanceof TextView && title.contentEquals(((TextView) view).getText())) != null;
                });
                proof("rotate-page", "page=" + page + "; header-preserved=" + present[0]);
                assertTrue("Real rotation must preserve the selected sidebar destination: " + page, present[0]);
            }
        }
    }

    private UiObject2 editor() {
        DeviceFixtureHealth.awaitForeground(device);
        UiObject2 editor = device.wait(Until.findObject(By.desc("메시지 입력").clazz(EditText.class)), 20_000);
        assertNotNull("Native chat editor must be present", editor); return editor;
    }
    private void hideKeyboard(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> {
            View focus = activity.getCurrentFocus();
            if (focus != null) { activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(), 0); focus.clearFocus(); }
        }); device.waitForIdle(1_000);
    }
    /** A completion barrier only: every import itself still begins with a real picker selection. */
    private void drainImportQueue(ActivityScenario<MainActivity> scenario) throws Exception {
        java.util.concurrent.ExecutorService[] executor = {null};
        scenario.onActivity(activity -> {
            try {
                java.lang.reflect.Field field = MainActivity.class.getDeclaredField("io"); field.setAccessible(true);
                executor[0] = (java.util.concurrent.ExecutorService) field.get(activity);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        assertNotNull(executor[0]); executor[0].submit(() -> { }).get(30, java.util.concurrent.TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void clickDescription(String description) {
        DeviceFixtureHealth.revealDescription(device, description);
        UiObject2 item = device.wait(Until.findObject(By.desc(description).pkg(APP)), 15_000);
        assertNotNull(description, item); item.click(); device.waitForIdle(1_000);
    }
    private void clickText(String text) {
        UiObject2 item = device.wait(Until.findObject(By.text(text).pkg(APP)), 15_000);
        assertNotNull(text, item); item.click(); device.waitForIdle(1_000);
    }
    private void openAllFiles() { clickDescription("사진 또는 파일 첨부"); clickText("모든 파일"); }
    private void assertPickerVisible() {
        await(() -> { String packageName = device.getCurrentPackageName(); return packageName != null && packageName.endsWith("documentsui"); }, "real Android DocumentsUI");
    }
    private void pick(String name) {
        openAllFiles(); assertPickerVisible();
        if (!device.hasObject(By.text(name))) {
            UiObject2 root = device.findObject(By.text(MediaExplorerDocumentsProvider.ROOT_TITLE));
            if (root == null) {
                UiObject2 navigation = device.findObject(By.desc("Show roots"));
                if (navigation == null) navigation = device.findObject(By.desc("Open navigation drawer"));
                if (navigation == null) navigation = device.findObject(By.clazz("android.widget.ImageButton"));
                assertNotNull("System picker navigation button", navigation); navigation.click();
                root = device.wait(Until.findObject(By.text(MediaExplorerDocumentsProvider.ROOT_TITLE)), 15_000);
            }
            assertNotNull("Test provider must be surfaced through the real Android picker", root); root.click();
        }
        UiObject2 file = device.wait(Until.findObject(By.text(name)), 15_000);
        for (int i = 0; file == null && i < 4; i++) {
            device.swipe(device.getDisplayWidth() / 2, device.getDisplayHeight() * 3 / 4,
                    device.getDisplayWidth() / 2, device.getDisplayHeight() / 3, 25);
            file = device.findObject(By.text(name));
        }
        assertNotNull("Real SAF fixture file " + name, file); file.click();
        DeviceFixtureHealth.awaitForeground(device);
    }
    private void rotate(ActivityScenario<MainActivity> scenario, boolean landscape) throws Exception {
        if (landscape) device.setOrientationLeft(); else device.setOrientationNatural();
        int expected = landscape ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        boolean[] matched = {false};
        await(() -> {
            scenario.onActivity(activity -> matched[0] = activity.getResources().getConfiguration().orientation == expected);
            return matched[0];
        }, "actual display rotation");
        DeviceFixtureHealth.awaitForeground(device);
    }
    private JSONObject awaitMessage(long before, String text) {
        JSONObject[] found = {null};
        await(() -> {
            JSONArray messages = store.listUserMessagesAfter(before, 100);
            for (int i = 0; i < messages.length(); i++) if (text.equals(messages.optJSONObject(i).optString("text"))) found[0] = messages.optJSONObject(i);
            return found[0] != null;
        }, "real Send persistence"); return found[0];
    }
    private void awaitImage(ActivityScenario<MainActivity> scenario, String name) {
        boolean[] ready = {false};
        await(() -> {
            scenario.onActivity(activity -> {
                View view = find(activity.getWindow().getDecorView(), candidate -> candidate instanceof ImageView && name.contentEquals(description(candidate)));
                if (view instanceof ImageView && ((ImageView) view).getDrawable() instanceof BitmapDrawable) {
                    Bitmap bitmap = ((BitmapDrawable) ((ImageView) view).getDrawable()).getBitmap();
                    assertEquals(Color.MAGENTA, bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2)); ready[0] = true;
                }
            }); return ready[0];
        }, "actual native bitmap decode");
    }
    private static void await(BooleanSupplier condition, String reason) {
        long deadline = SystemClock.elapsedRealtime() + 25_000;
        do { if (condition.getAsBoolean()) return; SystemClock.sleep(100); } while (SystemClock.elapsedRealtime() < deadline);
        fail("Timed out waiting for " + reason);
    }
    private static String description(View view) { return view.getContentDescription() == null ? "" : view.getContentDescription().toString(); }
    private static View find(View view, Predicate<View> predicate) {
        if (predicate.test(view)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View child = find(((ViewGroup) view).getChildAt(i), predicate); if (child != null) return child;
        }
        return null;
    }
    private static void proof(String test, String details) {
        String line = "HOYOUNG_MEDIA_EXPLORER " + test + "; " + details;
        android.util.Log.i("HoyoungDeviceProof", line);
        Bundle status = new Bundle(); status.putString("stream", line + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
