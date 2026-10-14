package com.hoyoung.assistant.web;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.runtime.AssistantRuntime;
import com.hoyoung.assistant.runtime.UsageBudgetLedger;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Local capture-worker lifecycle only; no WebView provider, login, model, or outbound action. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public final class WorkResponseCaptureLifecycleTest {
    private Context context;
    private HoyoungStore store;
    private AssistantRuntime runtime;
    private WorkWebController controller;
    private ExecutorService worker;

    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
        store = new HoyoungStore(context);
        InternalFiles files = new InternalFiles(context);
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context, store), Runnable::run);
        controller = new WorkWebController(context, runtime, files, c -> { throw new AssertionError("No provider is needed"); });
        worker = (ExecutorService) field(controller, "responseCaptures");
    }

    @After public void after() throws Exception {
        invoke("discardView", new Class<?>[]{android.webkit.WebView.class}, (Object) null);
        drain();
        for (String name : new String[]{"responseCaptures", "downloads", "attachmentPreparation"})
            ((ExecutorService) field(controller, name)).shutdownNow();
        ((UsageBudgetLedger) field(runtime, "usageBudget")).close();
        store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        context.deleteDatabase(UsageBudgetLedger.DATABASE_NAME);
    }

    @Test public void repeatedDocumentLossClosesQueuedPartAndPreservesOriginalWithoutExhaustingSlots() throws Exception {
        for (int i=0; i<3; i++) {
            int epoch = (Integer) field(controller, "documentEpoch");
            String request = "interrupted-" + i, capture = request + ":reply";
            String text = "  saved before document loss " + i + " 😀\n";
            AtomicReference<WorkWebController.ResponseCaptureJournal> received = new AtomicReference<>();
            worker.submit(() -> {
                deliver(part(request, capture, 0, text), epoch);
                received.set(captures().get(capture));
            });
            // Teardown queues behind the received part instead of closing from the UI thread.
            invoke("discardView", new Class<?>[]{android.webkit.WebView.class}, (Object) null);
            drain();
            WorkWebController.ResponseCaptureJournal journal = received.get();
            assertNotNull(journal);
            File partial = (File) field(journal, "file");
            assertEquals(text, new String(Files.readAllBytes(partial.toPath()), StandardCharsets.UTF_8));
            JSONObject manifest = new JSONObject(new String(Files.readAllBytes(new File(partial.getPath()+".json").toPath()), StandardCharsets.UTF_8));
            assertFalse(manifest.getBoolean("captureComplete"));
            assertEquals(1, manifest.getInt("receivedParts"));
            assertFalse(((FileOutputStream) field(journal, "output")).getFD().valid());
            assertThrows(IOException.class, () -> journal.append(1, "late"));
            worker.submit(() -> deliver(part(request, capture, 0, "stale replay"), epoch)).get(5, TimeUnit.SECONDS);
            assertTrue(captures().isEmpty());
            assertEquals(text, new String(Files.readAllBytes(partial.toPath()), StandardCharsets.UTF_8));
        }

        int epoch = (Integer) field(controller, "documentEpoch");
        worker.submit(() -> {
            deliver(part("fresh", "fresh:reply", 0, "first "), epoch);
            deliver(part("fresh", "fresh:reply", 1, "second"), epoch);
        }).get(5, TimeUnit.SECONDS);
        WorkWebController.ResponseCaptureJournal fresh = captures().get("fresh:reply");
        assertNotNull(fresh);
        File completed = worker.submit(() -> fresh.finish("first second".length())).get(5, TimeUnit.SECONDS);
        assertEquals("first second", new String(Files.readAllBytes(completed.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void failedRequestCannotReopenAndDelayedRetirementCannotCloseItsSuccessor() throws Exception {
        worker.submit(() -> {
            deliver(part("failed", "failed:reply", 0, "kept"), 0);
            deliver(part("failed", "failed:reply", 0, "duplicate"), 0);
            deliver(part("failed", "failed:replacement", 0, "must not reopen"), 0);
        }).get(5, TimeUnit.SECONDS);
        drain();
        assertTrue(captures().isEmpty());
        assertTrue((Boolean) field(controller, "captureRequestClosed"));
        worker.submit(() -> deliver(part("successor", "successor:reply", 0, "next"), 0)).get(5, TimeUnit.SECONDS);
        invoke("retireCaptureRequest", new Class<?>[]{String.class}, "failed");
        drain();
        assertNotNull(captures().get("successor:reply"));
        assertFalse((Boolean) field(controller, "captureRequestClosed"));
    }

    private static JSONObject part(String request, String capture, int index, String text) {
        try { return new JSONObject().put("type", "progress").put("code", "response_capture_part")
                .put("requestId", request).put("captureId", capture).put("totalParts", 2).put("index", index).put("text", text); }
        catch (Exception invalid) { throw new AssertionError(invalid); }
    }
    private void deliver(JSONObject event, int epoch) {
        try { invoke("receiveCaptureEvent", new Class<?>[]{JSONObject.class, int.class}, event, epoch); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    @SuppressWarnings("unchecked") private Map<String, WorkWebController.ResponseCaptureJournal> captures() {
        try { return (Map<String, WorkWebController.ResponseCaptureJournal>) field(controller, "captures"); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private void drain() throws Exception { worker.submit(() -> {}).get(5, TimeUnit.SECONDS); }
    private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = WorkWebController.class.getDeclaredMethod(name, types);
        method.setAccessible(true); method.invoke(controller, args);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
}
