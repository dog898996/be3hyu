package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Real SQLite/private-file failures; the transport only records messages and never contacts a model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class RuntimeReadFailureTest {
    private Context context;
    private HoyoungStore store;
    private InternalFiles files;
    private AssistantRuntime runtime;
    private FakeTransport transport;

    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        delete(context.getFilesDir());
        store = new HoyoungStore(context);
        files = new InternalFiles(context);
        store.putSetting("customInstructions", "Failure fixture: distinguish missing information from unknown external effects.");
        createRuntime();
    }

    @After public void after() throws Exception {
        runtime.detachTransport();
        store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        delete(context.getFilesDir());
    }

    @Test public void missingReadProducesTypedCompletedReceiptAndContinuesOtherReads() throws Exception {
        files.writeFile("known.txt", "available original");
        assertThrows(IOException.class, () -> files.readTextRange("missing.txt", 0, 100));
        ready();
        JSONObject request = runtime.receive("두 자료를 확인하고 없으면 알려줘", new JSONArray());
        complete(request, actions(
                action("missing", "files.read", object("fileId", "missing.txt")),
                action("known", "files.read", object("fileId", "known.txt"))));

        JSONObject failed = receipt(request, "missing");
        assertEquals("completed", failed.getString("status"));
        assertEquals("files.read", failed.getString("name"));
        assertFalse(failed.getJSONObject("result").getBoolean("ok"));
        assertEquals("tool_read_failed", failed.getJSONObject("result").getString("code"));
        assertFalse(failed.getJSONObject("result").getBoolean("completionConfirmed"));
        JSONObject available = receipt(request, "known");
        assertEquals("completed", available.getString("status"));
        assertEquals("available original", available.getJSONObject("result").getString("text"));
        assertEquals("completed", store.getRequest(request.getLong("id")).getString("status"));
        assertFalse(runtime.state().getBoolean("uncertaintyBlocked"));
        assertEquals(2, transport.ids.size());
        JSONObject continuation = requestById(transport.ids.get(1));
        assertEquals("tool-continuation", continuation.getString("kind"));
        assertTrue(continuation.getString("text").contains("tool_read_failed"));
        assertFalse(runtime.getGoalLedger().get(request.getString("external_id")).getBoolean("completionVerified"));
    }

    @Test public void failedReadDoesNotPreventNewOwnerInputAfterContinuationFinishes() throws Exception {
        ready();
        JSONObject request = runtime.receive("없는 파일 조회", new JSONArray());
        complete(request, actions(action("missing", "files.read", object("fileId", "absent.txt"))));
        JSONObject continuation = requestById(transport.ids.get(1));
        JSONObject next = runtime.receive("다음 독립 요청", new JSONArray());
        assertEquals("queued", store.getRequest(next.getLong("id")).getString("status"));
        assertFalse(runtime.state().getBoolean("uncertaintyBlocked"));
        complete(continuation, finalReport("파일이 존재하지 않아 내용을 확인하지 못했어."));
        assertEquals(3, transport.ids.size());
        assertEquals(next.getString("external_id"), transport.ids.get(2));
        assertEquals("dispatching", store.getRequest(next.getLong("id")).getString("status"));
        assertEquals("completed", receipt(request, "missing").getString("status"));
        assertFalse(new File(context.getFilesDir(), "projects/absent.txt").exists());
    }

    @Test public void protectedWriteIOExceptionRemainsUncertainAndCannotReplayOrRunNextAction() throws Exception {
        String protectedPath = ".attachment-originals/" + "b".repeat(64) + ".png";
        assertThrows(IOException.class, () -> files.writeFile(protectedPath, "must not create original"));
        files.writeFile("preserved.txt", "unchanged");
        ready();
        JSONObject request = runtime.receive("보호 원본에 저장", new JSONArray());
        complete(request, actions(
                action("write", "files.write", object("relativePath", protectedPath, "content", "must not create original")),
                action("after", "files.read", object("fileId", "preserved.txt"))));

        assertEquals("uncertain", receipt(request, "write").getString("status"));
        assertNull(store.getSetting(receiptKey(request, "after"), null));
        assertEquals("uncertain", store.getRequest(request.getLong("id")).getString("status"));
        assertTrue(runtime.state().getBoolean("uncertaintyBlocked"));
        JSONObject goal = runtime.getGoalLedger().get(request.getString("external_id"));
        assertEquals("uncertain", goal.getString("stage"));
        assertTrue(goal.getBoolean("requiresOwnerReconciliation"));
        assertFalse(goal.getBoolean("mayAutoRepeat"));
        assertFalse(goal.getBoolean("completionVerified"));
        assertFalse(new File(context.getFilesDir(), "projects/" + protectedPath).exists());
        assertEquals("unchanged", files.readText("preserved.txt", 100));
        JSONObject next = runtime.receive("새 입력은 보관", new JSONArray());
        ready(); runtime.tick();
        assertEquals("queued", store.getRequest(next.getLong("id")).getString("status"));
        assertEquals(1, transport.ids.size());
    }

    @Test public void knownReadFailureCanArchiveReportedGoalWithoutClaimingIndependentVerification() throws Exception {
        ready();
        JSONObject request = runtime.receive("자료 조회 결과를 알려줘", new JSONArray());
        complete(request, actions(action("missing", "files.read", object("fileId", "absent.txt"))));
        JSONObject continuation = requestById(transport.ids.get(1));
        complete(continuation, finalReport("자료가 없다는 조회 결과를 알려줬어."));
        assertNull(runtime.getGoalLedger().get(request.getString("external_id")));
        JSONObject archived = new JSONObject(store.getSetting("goalReportedArchive:" + request.getString("external_id"), "{}"));
        assertEquals("reported_completed", archived.getString("stage"));
        assertTrue(archived.getBoolean("operationallyArchived"));
        assertFalse(archived.getBoolean("completionVerified"));
        assertFalse(archived.getBoolean("overallOutcomeVerified"));
        assertEquals(1, archived.getLong("nativeReceiptCount"));
        assertFalse(runtime.state().getBoolean("uncertaintyBlocked"));
    }

    @Test public void unknownLegacyToolFailureCannotBeReclassifiedAsSafeReadToArchiveGoal() throws Exception {
        ready();
        JSONObject request = runtime.receive("기존 작업의 결과 확인", new JSONArray());
        String root = request.getString("external_id");
        long source = Long.parseLong(store.getSetting("requestSource:" + root, "0"));
        runtime.getGoalLedger().update(root, "working", source, root, "알 수 없는 이전 도구 결과", 0);
        new ActionReceiptJournal(store).put(receiptKey(request, "unknown-legacy"), object(
                "status", "completed", "name", "legacy.future_tool", "fingerprint", "fixture-unknown",
                "result", object("ok", false, "code", "operation_failed")));
        complete(request, finalReport("완료했다고 주장하는 제공자 응답"));
        JSONObject goal = runtime.getGoalLedger().get(root);
        assertNotNull(goal);
        assertEquals("awaiting_result", goal.getString("stage"));
        assertFalse(goal.getBoolean("completionVerified"));
        assertFalse(goal.getBoolean("mayAutoRepeat"));
        assertNull(store.getSetting("goalReportedArchive:" + root, null));
        assertEquals("legacy.future_tool", receipt(request, "unknown-legacy").getString("name"));
        assertEquals(1, transport.ids.size());
    }

    private void createRuntime() {
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context, store), Runnable::run);
        transport = new FakeTransport(); runtime.attachTransport(transport);
    }
    private void ready() throws Exception { runtime.onWebEvent(object("type", "state", "code", "ready", "state", object("authenticated", true, "workSelected", true, "temporary", true, "model", "gpt-6.1-sol", "effort", "medium", "speed", "fast", "busy", false, "quota", false))); }
    private void complete(JSONObject request, String envelope) throws Exception {
        String id = request.getString("external_id");
        runtime.onWebEvent(object("type", "accepted", "requestId", id));
        runtime.onWebEvent(object("type", "completed", "requestId", id, "text", envelope.replace("__CURRENT_REQUEST__", id), "rawEnvelopeEligible", true));
    }
    private static String actions(JSONObject... actions) throws Exception { JSONArray rows = new JSONArray(); for (JSONObject action : actions) rows.put(action); return "```hoyoung\n" + object("protocol", "hoyoung.v1", "requestId", "__CURRENT_REQUEST__", "message", "도구로 확인", "actions", rows) + "\n```"; }
    private static String finalReport(String message) throws Exception { return "```hoyoung\n" + object("protocol", "hoyoung.v1", "requestId", "__CURRENT_REQUEST__", "message", message, "status", "completed") + "\n```"; }
    private static JSONObject action(String id, String name, JSONObject args) throws Exception { return object("id", id, "name", name, "arguments", args); }
    private static JSONObject object(Object... pairs) throws Exception { JSONObject result = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
    private static String receiptKey(JSONObject request, String action) { return "actionReceipt:" + request.optString("external_id") + ":" + action; }
    private JSONObject receipt(JSONObject request, String action) throws Exception { return new JSONObject(store.getSetting(receiptKey(request, action), "{}")); }
    private JSONObject requestById(String id) { JSONArray requests = store.listRequests(100); for (int i = 0; i < requests.length(); i++) if (id.equals(requests.optJSONObject(i).optString("external_id"))) return requests.optJSONObject(i); throw new AssertionError("No persisted request " + id); }
    private static void delete(File file) throws IOException { if (Files.isSymbolicLink(file.toPath())) { Files.deleteIfExists(file.toPath()); return; } if (!file.exists()) return; if (file.isDirectory()) { File[] children = file.listFiles(); if (children == null) throw new IOException("Fixture directory unavailable"); for (File child : children) delete(child); } Files.delete(file.toPath()); }
    private static final class FakeTransport implements AssistantRuntime.Transport {
        final List<String> ids = new ArrayList<>();
        public void inspect() { }
        public void openTemporary() { }
        public void cancel() { }
        public void send(String id, String prompt) { ids.add(id); }
    }
}
