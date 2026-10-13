package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
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
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

/** Actual SQLite/working files and source references. No model, network, account, or physical-device effects. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class RuntimeNativeOutcomeMemoryTest {
    private Context context;
    private HoyoungStore store;
    private InternalFiles files;
    private AssistantRuntime runtime;
    private FakeTransport transport;
    private static final String PRIVATE_VALUE = "fixture-secret-owner-value-72";

    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME); delete(context.getFilesDir());
        store = new HoyoungStore(context); files = new InternalFiles(context);
        store.putSetting("customInstructions", "Native outcome fixture: learn execution lessons without inventing approval or overall success.");
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context, store), Runnable::run);
        transport = new FakeTransport(); runtime.attachTransport(transport);
    }
    @After public void after() throws Exception { runtime.detachTransport(); store.close(); context.deleteDatabase(HoyoungStore.DATABASE_NAME); delete(context.getFilesDir()); }

    @Test public void realWriteAndReadArchiveSmallSystemSourcesWithRecordHashesWithoutPrivateValues() throws Exception {
        String path = "private-" + PRIVATE_VALUE + ".txt";
        ready(); JSONObject request = runtime.receive("자료 처리", new JSONArray());
        complete(request, actions(action("write", "files.write", object("relativePath", path, "content", PRIVATE_VALUE)),
                action("read", "files.read", object("fileId", path))));
        JSONObject write = receipt(request, "write"), read = receipt(request, "read");
        JSONObject writeBody = verifiedBody(write), readBody = verifiedBody(read);
        assertEquals("files.write", writeBody.getString("tool")); assertTrue(writeBody.getBoolean("ok"));
        assertTrue(writeBody.getBoolean("completionConfirmed")); assertFalse(writeBody.getBoolean("overallTaskSuccessVerified"));
        assertEquals("files.read", readBody.getString("tool")); assertTrue(readBody.getBoolean("ok"));
        assertEquals(PRIVATE_VALUE, read.getJSONObject("result").getString("text"));
        assertEquals(2, outcomeCount());
        JSONObject continuation = requestById(transport.ids.get(1));
        assertTrue(continuation.getString("text").contains("nativeOutcomeSource"));
        assertTrue(continuation.getString("text").contains("native_return_flags_without_private_result_values"));
        assertEquals(PRIVATE_VALUE, files.readText(path, 100));
    }

    @Test public void replayingCompletedReceiptReturnsExistingEvidenceSourceWithoutCreatingDuplicate() throws Exception {
        files.writeFile("stable.txt", PRIVATE_VALUE); ready();
        JSONObject root = runtime.receive("자료 확인", new JSONArray());
        JSONObject readAction = action("same-read", "files.read", object("fileId", "stable.txt"));
        complete(root, actions(readAction)); JSONObject original = receipt(root, "same-read");
        long source = original.getLong("nativeOutcomeSourceMessageId"); String hash = original.getString("nativeOutcomeSourceHash");
        complete(requestById(transport.ids.get(1)), actions(readAction));
        JSONObject cached = receipt(root, "same-read");
        assertEquals(source, cached.getLong("nativeOutcomeSourceMessageId")); assertEquals(hash, cached.getString("nativeOutcomeSourceHash"));
        assertEquals(1, outcomeCount()); verifiedBody(cached);
        String next = requestById(transport.ids.get(2)).getString("text");
        assertTrue(next.contains("\"cached\":true")); assertTrue(next.contains("\"messageId\":" + source));
    }

    @Test public void missingReadEvidenceIsCitableThroughRealOriginalReaderAndMemorySave() throws Exception {
        ready(); JSONObject root = runtime.receive("없는 파일의 조회 실패를 교훈으로 기억", new JSONArray());
        complete(root, actions(action("missing", "files.read", object("fileId", "absent-" + PRIVATE_VALUE + ".txt"))));
        JSONObject failed = receipt(root, "missing"), failureBody = verifiedBody(failed);
        assertEquals("tool_read_failed", failureBody.getString("code")); assertFalse(failureBody.getBoolean("ok"));
        assertFalse(failureBody.getBoolean("completionConfirmed")); assertFalse(runtime.state().getBoolean("uncertaintyBlocked"));
        long source = failed.getLong("nativeOutcomeSourceMessageId");
        complete(requestById(transport.ids.get(1)), actions(
                action("source", "memory.source.read", object("messageId", source, "maxChars", 1000)),
                action("lesson", "memory.save", object("key", "lesson:missing-file", "content", "없는 파일 조회는 재시도 전에 파일 존재 여부를 확인한다.", "kind", "inference", "sourceMessageIds", new JSONArray().put(source)))));
        JSONObject range = receipt(root, "source").getJSONObject("result").getJSONObject("original");
        assertEquals(store.getMessage(source).getString("text"), range.getString("text"));
        assertEquals(failed.getString("nativeOutcomeSourceHash"), range.getString("recordHash"));
        assertTrue(range.getBoolean("complete"));
        JSONObject lesson = store.getMemory("lesson:missing-file"); assertNotNull(lesson);
        assertEquals("generated_summary", lesson.getString("kind"));
        assertEquals(source, lesson.getJSONArray("sourceMessageIds").getLong(0));
        assertEquals("generated_assertion", lesson.getJSONObject("metadata").getString("provenance"));
        assertFalse(lesson.getJSONObject("metadata").getBoolean("sourceQuoteVerified"));
    }

    @Test public void nativeSourceCannotBecomeOwnerFactOrPreferenceThroughModelMemorySave() throws Exception {
        files.writeFile("safe.txt", "safe"); ready(); JSONObject root = runtime.receive("도구 경험 저장", new JSONArray());
        complete(root, actions(action("observe", "files.read", object("fileId", "safe.txt"))));
        long source = receipt(root, "observe").getLong("nativeOutcomeSourceMessageId");
        complete(requestById(transport.ids.get(1)), actions(
                action("fact", "memory.save", object("key", "lesson:forged-fact", "content", "사용자가 이 도구를 선호한다고 주장", "kind", "fact", "sourceMessageIds", new JSONArray().put(source))),
                action("preference", "memory.save", object("key", "lesson:forged-pref", "content", "사용자의 승인이라고 주장", "kind", "preference", "sourceMessageIds", new JSONArray().put(source))),
                action("summary", "memory.save", object("key", "lesson:summary", "content", "파일 읽기가 반환됨", "kind", "summary", "sourceMessageIds", new JSONArray().put(source)))));
        for (String key : new String[]{"lesson:forged-fact", "lesson:forged-pref", "lesson:summary"}) {
            JSONObject memory = store.getMemory(key); assertNotNull(memory);
            assertNotEquals("fact", memory.getString("kind")); assertNotEquals("preference", memory.getString("kind"));
            assertEquals("generated_assertion", memory.getJSONObject("metadata").getString("provenance"));
            assertFalse(memory.getJSONObject("metadata").getBoolean("sourceQuoteVerified"));
            assertFalse(memory.getJSONObject("metadata").getBoolean("meaningIndependentlyVerified"));
        }
        assertEquals("system", store.getMessage(source).getString("role"));
    }

    @Test public void browserLaunchRequestRecordsRequestedWithoutLoadedAuthenticatedOrTaskCompletionProof() throws Exception {
        String url = "https://example.org/" + PRIVATE_VALUE;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo resolver = new ResolveInfo(); resolver.activityInfo = new ActivityInfo();
        resolver.activityInfo.packageName = "synthetic.browser"; resolver.activityInfo.name = "synthetic.browser.MainActivity";
        resolver.activityInfo.applicationInfo = new ApplicationInfo(); resolver.activityInfo.applicationInfo.packageName = "synthetic.browser";
        Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(intent, resolver);
        ready(); JSONObject root = runtime.receive("웹 화면 열기", new JSONArray());
        complete(root, actions(action("open", "phone_url_open", object("url", url))));
        JSONObject body = verifiedBody(receipt(root, "open"));
        assertEquals("url_launch_requested", body.getString("code")); assertTrue(body.getBoolean("requested"));
        assertFalse(body.getBoolean("completionConfirmed")); assertFalse(body.getBoolean("browserLoaded"));
        assertFalse(body.getBoolean("overallTaskSuccessVerified")); assertFalse(body.has("authenticationVerified"));
        Intent started = Shadows.shadowOf((Application) context).getNextStartedActivity(); assertNotNull(started); assertEquals(url, started.getDataString());
        JSONObject continuation = requestById(transport.ids.get(1));
        complete(continuation, finalReport("사이트 작업이 완료됐다고 주장"));
        JSONObject goal = runtime.getGoalLedger().get(root.getString("external_id")); assertNotNull(goal);
        assertEquals("awaiting_result", goal.getString("stage")); assertFalse(goal.getBoolean("completionVerified"));
        assertNull(store.getSetting("goalReportedArchive:" + root.getString("external_id"), null));
    }

    @Test public void acceptedAndInitiatedEvidenceIncludesOnlyBooleanWhitelistAndNeverOverallTaskSuccess() throws Exception {
        // This narrow serializer seam supplies mixed provider flags; it performs no phone gesture.
        JSONObject receipt = object("status", "completed", "result", object("ok", true, "code", "gesture_accepted",
                "accepted", true, "initiated", true, "requested", true, "completionConfirmed", false,
                "started", "yes", "browserLoaded", 1, "authenticationVerified", true, "taskCompleted", true,
                "url", PRIVATE_VALUE, "file", object("path", PRIVATE_VALUE), "text", PRIVATE_VALUE, "message", PRIVATE_VALUE,
                "arguments", object("content", PRIVATE_VALUE)));
        Method archive = AssistantRuntime.class.getDeclaredMethod("archiveNativeOutcome", String.class, String.class, String.class, String.class, JSONObject.class);
        archive.setAccessible(true); archive.invoke(runtime, "serializer-root", "serializer-request", "accepted", "phone_ui_tap", receipt);
        JSONObject body = verifiedBody(receipt);
        assertTrue(body.getBoolean("accepted")); assertTrue(body.getBoolean("initiated")); assertTrue(body.getBoolean("requested"));
        assertFalse(body.getBoolean("completionConfirmed")); assertFalse(body.getBoolean("overallTaskSuccessVerified"));
        for (String excluded : new String[]{"started", "browserLoaded", "authenticationVerified", "taskCompleted", "url", "file", "text", "message", "arguments"}) assertFalse(excluded, body.has(excluded));
    }

    @Test public void mutatingIOExceptionProducesCitableUncertainSourceWithoutExceptionMessageOrPrivatePath() throws Exception {
        String path = ".attachment-originals/" + "d".repeat(64) + ".png";
        assertThrows(IOException.class, () -> files.writeFile(path, PRIVATE_VALUE));
        ready(); JSONObject root = runtime.receive("보호 경로 수정", new JSONArray());
        complete(root, actions(action("write", "files.write", object("relativePath", path, "content", PRIVATE_VALUE))));
        JSONObject receipt = receipt(root, "write"), body = verifiedBody(receipt);
        assertEquals("uncertain", body.getString("receiptStage")); assertEquals("IOException", body.getString("exceptionType"));
        assertFalse(body.has("message")); assertFalse(body.has("completionConfirmed")); assertFalse(body.has("ok"));
        assertFalse(body.getBoolean("overallTaskSuccessVerified"));
        assertTrue(runtime.state().getBoolean("uncertaintyBlocked"));
        assertEquals("uncertain", store.getRequest(root.getLong("id")).getString("status"));
        assertEquals("uncertain", runtime.getGoalLedger().get(root.getString("external_id")).getString("stage"));
        assertEquals(1, transport.ids.size()); assertFalse(new File(context.getFilesDir(), "projects/" + path).exists());
        assertFalse(store.getMessage(receipt.getLong("nativeOutcomeSourceMessageId")).getString("text").contains("Attachment originals are immutable"));
    }

    @Test public void largeActualSearchResultArchivesOriginalWhileOutcomeKeepsOnlyTypedStatus() throws Exception {
        long source = store.addMessage("user", "Synthetic source for large result fixture", null).getLong("id");
        String content = "giant outcome fixture " + (PRIVATE_VALUE + " ").repeat(700);
        for (int i = 0; i < 30; i++) store.upsertMemory("large-result:" + i, content, "generated_summary", new JSONArray().put(source));
        ready(); JSONObject root = runtime.receive("자료 검색", new JSONArray());
        complete(root, actions(action("large", "memory.search", object("query", "giant outcome fixture", "limit", 40))));
        JSONObject receipt = receipt(root, "large"), pointer = receipt.getJSONObject("result");
        assertTrue(pointer.toString(), pointer.getBoolean("fullResultArchived"));
        assertTrue(pointer.getLong("originalChars") > 200_000);
        JSONObject original = files.readTextRange(pointer.getJSONObject("artifact").getString("id"), 0, 1_000_000);
        assertTrue(original.getBoolean("complete"));
        assertTrue(original.getString("text").contains(PRIVATE_VALUE));
        JSONArray candidates = new JSONObject(original.getString("text")).getJSONArray("memories");
        assertEquals(30, candidates.length()); assertTrue(candidates.getJSONObject(0).getBoolean("candidateExcerpt"));
        JSONObject body = verifiedBody(receipt); assertTrue(body.getBoolean("ok"));
        assertFalse(body.has("artifact")); assertFalse(body.has("resultPreview")); assertFalse(body.has("memories"));
        assertFalse(body.getBoolean("overallTaskSuccessVerified"));
        assertFalse(runtime.state().getBoolean("uncertaintyBlocked"));
    }

    private JSONObject verifiedBody(JSONObject receipt) throws Exception {
        long sourceId = receipt.getLong("nativeOutcomeSourceMessageId"); String hash = receipt.getString("nativeOutcomeSourceHash");
        assertTrue(hash.matches("[0-9a-f]{64}")); JSONObject source = store.getMessage(sourceId);
        assertNotNull(source); assertEquals("system", source.getString("role")); assertEquals(hash, source.getString("content_hash"));
        JSONObject range = store.readOriginalText(sourceId, 0, 2000); assertEquals(hash, range.getString("recordHash")); assertTrue(range.getBoolean("complete"));
        String raw = range.getString("text"); assertTrue(raw.length() < 1000); assertFalse(raw.contains(PRIVATE_VALUE));
        assertFalse(raw.contains(context.getFilesDir().getAbsolutePath())); assertFalse(raw.contains(".attachment-originals/"));
        JSONObject body = new JSONObject(raw); assertEquals("native_tool_outcome", body.getString("type"));
        assertFalse(body.getBoolean("isOwnerInstruction")); assertFalse(body.getBoolean("argsIncluded"));
        assertFalse(body.getBoolean("privateResultValuesIncluded")); assertFalse(body.getBoolean("overallTaskSuccessVerified"));
        Set<String> allowed = new HashSet<>(Arrays.asList("type", "rootRequestId", "actionId", "tool", "receiptStage", "observedAt", "isOwnerInstruction", "argsIncluded", "privateResultValuesIncluded", "overallTaskSuccessVerified", "exceptionType", "code", "ok", "completionConfirmed", "initiated", "requested", "accepted", "started", "browserLoaded"));
        for (Iterator<String> keys = body.keys(); keys.hasNext();) assertTrue("Unexpected summary field", allowed.contains(keys.next()));
        return body;
    }
    private int outcomeCount() throws Exception { int count = 0; JSONArray messages = store.listMessages(100); for (int i = 0; i < messages.length(); i++) { JSONObject message = messages.getJSONObject(i); if ("system".equals(message.optString("role")) && message.optString("text").startsWith("{\"type\":\"native_tool_outcome\"")) count++; } return count; }
    private void ready() throws Exception { runtime.onWebEvent(object("type", "state", "code", "ready", "state", object("authenticated", true, "workSelected", true, "temporary", true, "model", "gpt-6.1-sol", "effort", "medium", "speed", "fast", "busy", false, "quota", false))); }
    private void complete(JSONObject request, String envelope) throws Exception { String id = request.getString("external_id"); runtime.onWebEvent(object("type", "accepted", "requestId", id)); runtime.onWebEvent(object("type", "completed", "requestId", id, "text", envelope.replace("__CURRENT_REQUEST__", id), "rawEnvelopeEligible", true)); }
    private static String actions(JSONObject... actions) throws Exception { JSONArray rows = new JSONArray(); for (JSONObject action : actions) rows.put(action); return "```hoyoung\n" + object("protocol", "hoyoung.v1", "requestId", "__CURRENT_REQUEST__", "message", "도구로 확인", "actions", rows) + "\n```"; }
    private static String finalReport(String message) throws Exception { return "```hoyoung\n" + object("protocol", "hoyoung.v1", "requestId", "__CURRENT_REQUEST__", "message", message, "status", "completed") + "\n```"; }
    private static JSONObject action(String id, String name, JSONObject args) throws Exception { return object("id", id, "name", name, "arguments", args); }
    private static JSONObject object(Object... pairs) throws Exception { JSONObject result = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
    private JSONObject receipt(JSONObject root, String action) throws Exception { return new JSONObject(store.getSetting("actionReceipt:" + root.getString("external_id") + ":" + action, "{}")); }
    private JSONObject requestById(String id) { JSONArray requests = store.listRequests(100); for (int i = 0; i < requests.length(); i++) if (id.equals(requests.optJSONObject(i).optString("external_id"))) return requests.optJSONObject(i); throw new AssertionError("No persisted request " + id); }
    private static void delete(File file) throws IOException { if (Files.isSymbolicLink(file.toPath())) { Files.deleteIfExists(file.toPath()); return; } if (!file.exists()) return; if (file.isDirectory()) { File[] children = file.listFiles(); if (children == null) throw new IOException("Fixture directory unavailable"); for (File child : children) delete(child); } Files.delete(file.toPath()); }
    private static final class FakeTransport implements AssistantRuntime.Transport { final List<String> ids = new ArrayList<>(); public void inspect() { } public void openTemporary() { } public void cancel() { } public void send(String id, String prompt) { ids.add(id); } }
}
