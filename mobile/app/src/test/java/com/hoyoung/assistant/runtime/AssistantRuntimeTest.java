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

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** No browser login, model call, phone outbound message, call, or purchase is performed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class AssistantRuntimeTest {
    private Context context;
    private HoyoungStore store;
    private InternalFiles files;
    private AssistantRuntime runtime;
    private FakeTransport transport;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        deleteTree(new java.io.File(context.getFilesDir(), "projects"));
        store = new HoyoungStore(context);
        files = new InternalFiles(context);
        store.putSetting("customInstructions", "테스트용 맞춤 지침. 모르는 것은 모른다고 표시한다.");
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context, store), Runnable::run);
        transport = new FakeTransport();
        runtime.attachTransport(transport);
    }

    @After public void tearDown() {
        store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        deleteTree(new java.io.File(context.getFilesDir(), "projects"));
    }

    @Test public void requiresVerifiedSubscriptionWorkTemporaryAndModel() throws Exception {
        JSONObject request = runtime.receive("작업을 맡길게", new JSONArray());
        runtime.onWebEvent(object("type", "state", "code", "authentication_required", "state", readyState().put("authenticated", false)));
        assertEquals(0, transport.ids.size());
        runtime.onWebEvent(object("type", "state", "state", readyState().put("model", "gpt-6.1-luna")));
        assertEquals(0, transport.ids.size());
        runtime.onWebEvent(object("type", "state", "state", readyState().put("model", "gpt-6.10-sol")));
        assertEquals(0, transport.ids.size());
        runtime.onWebEvent(object("type", "state", "state", readyState().put("temporary", false)));
        assertEquals(0, transport.ids.size());
        ready();
        assertEquals(1, transport.ids.size());
        assertEquals(request.optString("external_id"), transport.ids.get(0));
        assertEquals("dispatching", store.getRequest(request.optLong("id")).optString("status"));
        assertTrue(transport.persistedBeforeSend);
        runtime.tick(); runtime.tick();
        assertEquals(1, transport.ids.size());
    }

    @Test public void statePreflightComposerFailureDoesNotDispatch() throws Exception {
        runtime.receive("작업", new JSONArray());
        runtime.onWebEvent(object("type", "state", "code", "composer_unverified", "state", readyState()));
        assertEquals(0, transport.ids.size());
        assertFalse(runtime.state().optBoolean("ready"));
    }

    @Test public void newerInputQueuesAndViewDetachDoesNotCancelCloud() throws Exception {
        ready();
        JSONObject first = runtime.receive("첫 작업", new JSONArray());
        accept(first);
        JSONObject second = runtime.receive("이것도 이어서", new JSONArray());
        assertEquals(1, transport.ids.size());
        assertEquals("queued", store.getRequest(second.optLong("id")).optString("status"));
        runtime.detachTransport();
        assertEquals(0, transport.cancels);
        assertEquals(first.optString("external_id"), runtime.state().optString("activeRequestId"));
        runtime.attachTransport(transport);
        ready();
        assertEquals(1, transport.ids.size());
        complete(first, "첫 작업 완료");
        assertEquals(2, transport.ids.size());
        assertEquals(second.optString("external_id"), transport.ids.get(1));
    }

    @Test public void mismatchedCompletionIsIgnoredAndResultMemoryRetainsActualSource() throws Exception {
        ready();
        JSONObject request = runtime.receive("테스트 코드 작업", new JSONArray());
        runtime.onWebEvent(object("type", "completed", "requestId", "different", "text", "가짜 완료"));
        assertEquals("dispatching", store.getRequest(request.optLong("id")).optString("status"));
        accept(request);
        complete(request, "코드 작업을 완료했어. 실제 배포는 하지 않았어.");
        assertEquals("completed", store.getRequest(request.optLong("id")).optString("status"));
        JSONArray memories = store.searchMemories("task-result:" + request.optString("external_id"), 10);
        assertEquals(1, memories.length());
        JSONObject memory = memories.getJSONObject(0);
        assertEquals("generated_summary", memory.optString("kind"));
        JSONObject original = store.getMessage(memory.getJSONArray("sourceMessageIds").getLong(0));
        assertEquals("assistant", original.optString("role"));
        assertEquals("코드 작업을 완료했어. 실제 배포는 하지 않았어.", original.optString("text"));
    }

    @Test public void ambiguousErrorPausesAndDoesNotReplayOnNewReadyState() throws Exception {
        ready();
        JSONObject request = runtime.receive("중복하면 안 되는 작업", new JSONArray());
        runtime.onWebEvent(object("type", "error", "requestId", request.optString("external_id"), "code", "DISPATCH_ACK_UNCERTAIN"));
        assertEquals("uncertain", store.getRequest(request.optLong("id")).optString("status"));
        runtime.receive("새 작업도 저장", new JSONArray());
        ready(); runtime.tick();
        assertEquals(1, transport.ids.size());
        assertTrue(runtime.state().optBoolean("uncertaintyBlocked"));
    }

    @Test public void provenPreSendRejectionBlocksRequestWithoutInventingAcceptance() throws Exception {
        ready();
        JSONObject request = runtime.receive("작업", new JSONArray());
        runtime.onWebEvent(object("type", "error", "requestId", request.optString("external_id"), "code", "busy", "mayHaveBeenAccepted", false));
        assertEquals("blocked", store.getRequest(request.optLong("id")).optString("status"));
        ready();
        assertEquals(1, transport.ids.size());
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
    }

    @Test public void quotaBlockSurvivesStateInspectionAndProcessRestart() throws Exception {
        ready();
        JSONObject request = runtime.receive("작업", new JSONArray());
        runtime.onWebEvent(object("type", "quota", "requestId", request.optString("external_id"), "code", "quota_exhausted"));
        assertEquals("uncertain", store.getRequest(request.optLong("id")).optString("status"));
        assertTrue(runtime.state().optBoolean("quotaBlocked"));
        ready();
        runtime.receive("한도 끝나면 처리", new JSONArray());
        assertEquals(1, transport.ids.size());
        store.close();
        store = new HoyoungStore(context);
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context, store), Runnable::run);
        runtime.attachTransport(transport); ready();
        assertEquals(1, transport.ids.size());
        assertTrue(runtime.state().optBoolean("quotaBlocked"));
        assertTrue(store.listMessages(20).toString().contains("한도가 소진"));
    }

    @Test public void quotaAfterAcceptedNeverTurnsIntoSafeRetryableQuotaRequest() throws Exception {
        ready();
        JSONObject request = runtime.receive("접수 후 작업", new JSONArray());
        accept(request);
        runtime.onWebEvent(object("type", "quota", "requestId", request.optString("external_id"), "code", "quota_exhausted"));
        runtime.confirmQuotaRecovery();
        ready(); runtime.tick();
        assertEquals("uncertain", store.getRequest(request.optLong("id")).optString("status"));
        assertTrue(runtime.state().optBoolean("uncertaintyBlocked"));
        assertEquals(1, transport.ids.size());
    }

    @Test public void explicitKnownNotSentQuotaCanBeQueuedByOwnerRetry() throws Exception {
        ready();
        JSONObject request = runtime.receive("전송 전 한도", new JSONArray());
        runtime.onWebEvent(object("type", "quota", "requestId", request.optString("external_id"), "code", "quota_exhausted", "knownNotSent", true));
        assertEquals("quota", store.getRequest(request.optLong("id")).optString("status"));
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
        runtime.retryRequest(request.optLong("id")); ready();
        assertEquals(2, transport.ids.size());
        assertTrue(store.events(20).toString().contains("\"quotaRecoveryVerified\":false"));
    }

    @Test public void knownNotSentBridgeErrorDoesNotInventAnUncertainRemoteSideEffect() throws Exception {
        ready();
        JSONObject request = runtime.receive("브리지 전송 전 실패", new JSONArray());
        runtime.onWebEvent(object("type", "error", "requestId", request.optString("external_id"), "code", "ATTACHMENT_NOT_READY", "knownNotSent", true));
        assertEquals("blocked", store.getRequest(request.optLong("id")).optString("status"));
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
        assertEquals("ATTACHMENT_NOT_READY", runtime.state().optString("lastCode"));
    }

    @Test public void largeRequestKeepsOriginalAndBudgetsOptionalContextBeforeDispatch() throws Exception {
        ready();
        String huge = "x".repeat(95_000);
        JSONObject request = runtime.receive(huge, new JSONArray());
        assertEquals(1, transport.ids.size());
        assertEquals("dispatching", store.getRequest(request.optLong("id")).optString("status"));
        assertEquals(huge, store.getRequest(request.optLong("id")).optString("text"));
        assertTrue(transport.prompts.get(0).contains(huge));
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
    }

    @Test public void restoreRefusesActiveWorkAndImportedDispatchCannotAutoReplay() throws Exception {
        ready();
        JSONObject request = runtime.receive("진행 중인 원문", new JSONArray());
        java.io.ByteArrayOutputStream backup = new java.io.ByteArrayOutputStream();
        files.exportBackup(backup, store);
        try {
            runtime.restoreBackup(new java.io.ByteArrayInputStream(backup.toByteArray()));
            fail("An active work request must block restore");
        } catch (IllegalStateException expected) { }
        assertEquals(request.optString("external_id"), runtime.state().optString("activeRequestId"));
        complete(request, "완료");
        runtime.restoreBackup(new java.io.ByteArrayInputStream(backup.toByteArray()));
        assertEquals("uncertain", store.getRequest(request.optLong("id")).optString("status"));
        assertTrue(runtime.state().optBoolean("uncertaintyBlocked"));
        ready(); runtime.tick();
        assertEquals(1, transport.ids.size());
    }

    @Test public void stateDoesNotCopyHistoricalMessagesOrRequests() throws Exception {
        runtime.receive("개인 원문", new JSONArray());
        JSONObject state = runtime.state();
        assertFalse(state.has("messages"));
        assertFalse(state.has("requests"));
        assertFalse(state.toString().contains("개인 원문"));
    }

    @Test public void interruptedLocalActionReceiptPausesAndOwnerResolutionNeverExecutesItAgain() throws Exception {
        JSONObject request = store.enqueueRequest("로컬 파일 단계", "user", "root-test");
        store.markRequest(request.optLong("id"), "accepted", "클라우드 응답은 완료, 로컬 도구 처리 중");
        store.putSetting("requestRoot:root-test", "root-test");
        store.putSetting("actionReceipt:root-test:write-once", "{\"status\":\"dispatching\",\"name\":\"files.write\"}");
        store.close(); store = new HoyoungStore(context);
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context, store), Runnable::run);
        runtime.attachTransport(transport); ready();
        assertTrue(runtime.state().optBoolean("uncertaintyBlocked"));
        assertEquals(0, transport.ids.size());
        runtime.resolveUncertain(request.optLong("id"));
        JSONObject receipt = new JSONObject(store.getSetting("actionReceipt:root-test:write-once", "{}"));
        assertEquals("completed", receipt.optString("status"));
        assertEquals("owner_resolved_outcome", receipt.getJSONObject("result").optString("code"));
        assertFalse(receipt.getJSONObject("result").optBoolean("providerOutcomeVerified"));
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
        assertEquals(0, files.listFiles().length());
        assertEquals(0, transport.ids.size());
    }

    @Test public void processDeathMakesUnacknowledgedRequestUncertainNeverReplay() throws Exception {
        ready();
        JSONObject request = runtime.receive("접수 확인 전", new JSONArray());
        store.close(); store = new HoyoungStore(context);
        assertEquals("uncertain", store.getRequest(request.optLong("id")).optString("status"));
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context, store), Runnable::run);
        runtime.attachTransport(transport); ready(); runtime.tick();
        assertEquals(1, transport.ids.size());
        assertTrue(runtime.state().optBoolean("uncertaintyBlocked"));
    }

    @Test public void ownerRetryAndResolvedOutcomeHaveExplicitProvenance() throws Exception {
        ready();
        JSONObject request = runtime.receive("직접 확인한 뒤 다시 시도", new JSONArray());
        runtime.onWebEvent(object("type", "error", "requestId", request.optString("external_id"), "code", "preflight_rejected", "knownNotSent", true));
        JSONObject retried = runtime.retryRequest(request.optLong("id"));
        assertNotEquals(request.optString("external_id"), retried.optString("external_id"));
        assertEquals("abandoned", store.getRequest(request.optLong("id")).optString("status"));
        assertEquals(request.optString("external_id"), store.getSetting("requestRoot:" + retried.optString("external_id"), ""));
        assertEquals(1, transport.ids.size()); // A fresh inspection is still required.
        ready();
        assertEquals(2, transport.ids.size());
        JSONArray events = store.events(20);
        assertTrue(events.toString().contains("owner_selected_retry"));
        assertTrue(events.toString().contains("\"quotaRecoveryVerified\":false"));
        runtime.onWebEvent(object("type", "error", "requestId", retried.optString("external_id"), "code", "lost_connection"));
        runtime.resolveUncertain(retried.optLong("id"));
        assertEquals("completed", store.getRequest(retried.optLong("id")).optString("status"));
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
        assertEquals(2, transport.ids.size());
        assertTrue(store.events(20).toString().contains("owner_resolved_uncertain"));
    }

    @Test public void acceptedEvidenceCannotBeOverriddenByLaterKnownNotSentFlags() throws Exception {
        ready();
        JSONObject request = runtime.receive("접수 기록 우선", new JSONArray());
        accept(request);
        runtime.onWebEvent(object("type", "error", "requestId", request.optString("external_id"), "code", "not_sent", "knownNotSent", true, "mayHaveBeenAccepted", false));
        assertEquals("uncertain", store.getRequest(request.optLong("id")).optString("status"));
        try { runtime.retryRequest(request.optLong("id")); fail("An unknown remote effect cannot be safely retried"); }
        catch (IllegalStateException expected) { }
        runtime.resolveUncertain(request.optLong("id"));
        JSONObject next = runtime.receive("다음 접수", new JSONArray());
        accept(next);
        runtime.onWebEvent(object("type", "quota", "requestId", next.optString("external_id"), "knownNotSent", true));
        assertEquals("uncertain", store.getRequest(next.optLong("id")).optString("status"));
    }

    @Test public void queueAndMetadataCommitTogetherAndStorageFailureRollsBackOriginals() throws Exception {
        JSONObject file = files.writeFile("photo.jpg", "local fixture only");
        JSONObject request = runtime.receive("사진 첨부 원문", new JSONArray().put(file.optString("id")));
        String id = request.optString("external_id");
        assertEquals(id, store.getSetting("requestRoot:" + id, ""));
        assertEquals("0", store.getSetting("toolRound:" + id, "missing"));
        assertTrue(Long.parseLong(store.getSetting("requestSource:" + id, "0")) > 0);
        String immutableId = new JSONArray(store.getSetting("attachments:" + id, "[]")).getString(0);
        assertTrue(immutableId.startsWith(".attachment-originals/"));
        assertEquals("local fixture only", files.readText(immutableId, 100));
        assertFalse(immutableId.equals(file.optString("id")));
        int beforeMessages = store.listMessages(100).length();
        int beforeRequests = store.listRequests(100).length();
        store.getWritableDatabase().execSQL("CREATE TRIGGER reject_attachment_metadata BEFORE INSERT ON settings WHEN NEW.setting_key LIKE 'attachmentMetadata:%' BEGIN SELECT RAISE(ABORT, 'injected_metadata_failure'); END");
        try { runtime.receive("이 원문은 원자적으로 롤백", new JSONArray()); fail("Injected metadata failure must fail receive"); }
        catch (RuntimeException expected) { }
        assertEquals(beforeMessages, store.listMessages(100).length());
        assertEquals(beforeRequests, store.listRequests(100).length());
        assertEquals(0, transport.ids.size());
    }

    @Test public void continuationMetadataFailureCannotLeaveReplayableOrphanAfterPhoneEffect() throws Exception {
        ready();
        JSONObject request = runtime.receive("원자적 후속 작업", new JSONArray());
        store.getWritableDatabase().execSQL("CREATE TRIGGER reject_first_round BEFORE INSERT ON settings WHEN NEW.setting_key LIKE 'toolRound:%' AND NEW.value = '1' BEGIN SELECT RAISE(ABORT, 'injected_round_failure'); END");
        complete(request, envelope("파일 작성 요청", new JSONArray().put(action("durable-once", "files.write", object("relativePath", "committed.txt", "content", "already applied"))), null));
        assertEquals("already applied", files.readText("committed.txt", 100));
        assertEquals("uncertain", store.getRequest(request.optLong("id")).optString("status"));
        assertEquals(1, store.listRequests(100).length());
        assertEquals(1, transport.ids.size());
        assertEquals("completed", new JSONObject(store.getSetting("actionReceipt:" + request.optString("external_id") + ":durable-once", "{}")).optString("status"));
        assertNull(store.nextQueuedRequest());
    }

    @Test public void uncertaintyRequiresOwnerResolutionOrAbandonmentBeforeNewTemporaryChat() throws Exception {
        ready();
        JSONObject request = runtime.receive("결과를 잃은 작업", new JSONArray());
        runtime.onWebEvent(object("type", "error", "requestId", request.optString("external_id"), "code", "connection_lost"));
        String previousResult = store.getRequest(request.optLong("id")).optString("result");
        try { runtime.openTemporary(); fail("Unresolved task prevents abandoning its page silently"); }
        catch (IllegalStateException expected) { }
        runtime.abandonUncertain(request.optLong("id"));
        assertEquals("abandoned", store.getRequest(request.optLong("id")).optString("status"));
        assertEquals(previousResult, store.getRequest(request.optLong("id")).optString("result"));
        assertFalse(runtime.state().optBoolean("uncertaintyBlocked"));
        runtime.openTemporary();
        assertEquals(1, transport.opens);
        assertEquals(1, transport.ids.size());
        assertFalse(store.events(10).toString().contains("\"cloudStopped\":true"));
        assertTrue(store.events(10).toString().contains("owner_abandoned_uncertain"));
    }

    @Test public void abandonedActionReceiptIsRetainedAsUnknownNotCompletedAndNeverReplayed() throws Exception {
        JSONObject request = store.enqueueRequest("폰 도구 결과 불명확", "user", "root-abandon");
        store.markRequest(request.optLong("id"), "accepted", "original uncertain result");
        store.putSetting("actionReceipt:root-abandon:write-once", "{\"status\":\"dispatching\",\"name\":\"files.write\",\"fingerprint\":\"original\"}");
        store.close(); store = new HoyoungStore(context);
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context, store), Runnable::run);
        runtime.attachTransport(transport);
        runtime.abandonUncertain(request.optLong("id"));
        JSONObject receipt = new JSONObject(store.getSetting("actionReceipt:root-abandon:write-once", "{}"));
        assertEquals("abandoned", receipt.optString("status"));
        assertTrue(receipt.optBoolean("ownerAbandoned"));
        assertFalse(receipt.optBoolean("providerOutcomeVerified"));
        assertEquals("original", receipt.optString("fingerprint"));
        assertEquals("original uncertain result", store.getRequest(request.optLong("id")).optString("result"));
        assertEquals(0, transport.ids.size());
        assertEquals(0, files.listFiles().length());
    }

    @Test public void disabledMemorySaveAlsoBlocksEnvelopeAndGeneratedMemoryWrites() throws Exception {
        store.putSetting("toolPermissions", "{\"memory.save\":false}");
        ready();
        JSONObject request = runtime.receive("짧은 답변 선호", new JSONArray());
        long source = Long.parseLong(store.getSetting("requestSource:" + request.optString("external_id"), "0"));
        complete(request, envelope("기억 제안", null, new JSONArray().put(object("key", "preference", "content", "짧게 답변", "kind", "preference", "sourceMessageIds", new JSONArray().put(source)))));
        assertEquals(0, store.searchMemories("", 100).length());
        assertEquals(2, store.listMessages(100).length());
    }

    @Test public void assistantOnlyMemoryCannotPromoteGeneratedAssertionToFact() throws Exception {
        long generated = store.addMessage("assistant", "확인되지 않은 생성 문장", null).optLong("id");
        ready();
        JSONObject request = runtime.receive("이전 답변의 기록", new JSONArray());
        complete(request, envelope("출처 붙인 기억", null, new JSONArray().put(object("key", "generated-claim", "content", "생성된 주장", "kind", "fact", "sourceMessageIds", new JSONArray().put(generated)))));
        JSONObject memory = store.searchMemories("generated-claim", 10).getJSONObject(0);
        assertEquals("generated_summary", memory.optString("kind"));
        assertEquals(generated, memory.getJSONArray("sourceMessageIds").getLong(0));
    }

    @Test public void backupCannotEraseLiveUncertaintyAndStaleQueuedImportRequiresOwnerReview() throws Exception {
        JSONObject old = runtime.receive("백업 당시 대기 작업", new JSONArray());
        java.io.ByteArrayOutputStream backup = new java.io.ByteArrayOutputStream();
        files.exportBackup(backup, store);
        ready();
        runtime.onWebEvent(object("type", "error", "requestId", old.optString("external_id"), "code", "lost_response"));
        try { runtime.restoreBackup(new java.io.ByteArrayInputStream(backup.toByteArray())); fail("Restore must not erase live uncertainty"); }
        catch (IllegalStateException expected) { }
        assertEquals("uncertain", store.getRequest(old.optLong("id")).optString("status"));
        runtime.abandonUncertain(old.optLong("id"));
        store.putSetting("autonomy.enabled", "true");
        runtime.restoreBackup(new java.io.ByteArrayInputStream(backup.toByteArray()));
        assertFalse(runtime.autonomyEnabled());
        assertEquals("uncertain", store.getRequest(old.optLong("id")).optString("status"));
        ready(); runtime.tick();
        assertEquals(1, transport.ids.size());
    }

    @Test public void deniedToolProducesReceiptAndNeverWritesFile() throws Exception {
        store.putSetting("toolPermissions", "{\"files.write\":false}");
        ready();
        JSONObject request = runtime.receive("파일 요청", new JSONArray());
        complete(request, envelope("도구 요청", new JSONArray().put(action("deny", "files.write", object("relativePath", "denied.txt", "content", "no"))), null));
        assertEquals(0, files.listFiles().length());
        assertTrue(store.getSetting("actionReceipt:" + request.optString("external_id") + ":deny", "").contains("permission_disabled"));
        assertEquals(2, transport.ids.size());
        assertTrue(transport.prompts.get(1).contains("permission_disabled"));
    }

    @Test public void duplicateActionReceiptReusesFirstResultAcrossContinuation() throws Exception {
        ready();
        JSONObject first = runtime.receive("파일 작성", new JSONArray());
        JSONArray actions = new JSONArray().put(action("once", "files.write", object("relativePath", "once.txt", "content", "first")));
        complete(first, envelope("쓰기 요청", actions, null));
        JSONObject continuation = store.getRequest(runtime.state().optLong("activeStoreId"));
        String firstReceipt = store.getSetting("actionReceipt:" + first.optString("external_id") + ":once", "");
        complete(continuation, envelope("같은 action 재확인", actions, null));
        assertEquals(firstReceipt, store.getSetting("actionReceipt:" + first.optString("external_id") + ":once", ""));
        assertTrue(transport.prompts.get(2).contains("\"cached\":true"));
        assertEquals("first", files.readText("once.txt", 100));
        JSONObject current = store.getRequest(runtime.state().optLong("activeStoreId"));
        complete(current, envelope("잘못 재사용한 id", new JSONArray().put(action("once", "files.write", object("relativePath", "once.txt", "content", "second"))), null));
        assertEquals("first", files.readText("once.txt", 100));
        assertTrue(transport.prompts.get(3).contains("action_id_conflict"));
    }

    @Test public void corruptPermissionConfigurationDoesNotSilentlyGrantToolAccess() throws Exception {
        store.putSetting("toolPermissions", "not-json");
        ready();
        JSONObject request = runtime.receive("파일 도구", new JSONArray());
        complete(request, envelope("쓰기 요청", new JSONArray().put(action("config", "files.write", object("relativePath", "no.txt", "content", "no"))), null));
        assertEquals(0, files.listFiles().length());
        assertTrue(transport.prompts.get(1).contains("configuration_invalid"));
    }

    @Test public void invalidSourcesRejectedButActualOwnerPreferenceRetainsSource() throws Exception {
        ready();
        JSONObject request = runtime.receive("나는 짧은 답변이 좋아", new JSONArray());
        long ownerSource = Long.parseLong(store.getSetting("requestSource:" + request.optString("external_id"), "0"));
        JSONArray memories = new JSONArray().put(object("key", "fake", "content", "꾸며낸 사실", "kind", "fact", "sourceMessageIds", new JSONArray().put(999999)))
                .put(object("key", "answer-length", "content", "사용자는 짧은 답변을 선호한다", "kind", "preference", "sourceMessageIds", new JSONArray().put(ownerSource), "sourceRefs", new JSONArray().put(object("type", "message", "id", ownerSource, "quote", "나는 짧은 답변이 좋아", "offsetChars", 0))));
        complete(request, envelope("기억해 둘게", null, memories));
        assertEquals(0, store.searchMemories("fake", 10).length());
        JSONObject preference = store.searchMemories("answer-length", 10).getJSONObject(0);
        assertEquals(ownerSource, preference.getJSONArray("sourceMessageIds").getLong(0));
        assertEquals("user", store.getMessage(ownerSource).optString("role"));
    }

    @Test public void settingsValidateEntireContractBeforeWritingAndUseUiKeys() throws Exception {
        ready();
        JSONObject request = runtime.receive("설정 조정", new JSONArray());
        complete(request, envelope("설정 요청", new JSONArray().put(action("bad-settings", "app.settings.update", object("customInstructions", "바뀌면 안 됨", "heartbeatSeconds", 1))), null));
        assertEquals("테스트용 맞춤 지침. 모르는 것은 모른다고 표시한다.", store.getSetting("customInstructions", ""));
        assertTrue(transport.prompts.get(1).contains("invalid_arguments"));
        JSONObject next = store.getRequest(runtime.state().optLong("activeStoreId"));
        complete(next, envelope("설정 다시 요청", new JSONArray().put(action("settings", "app.settings.update", object("customInstructions", "수정된 지침", "standingGoals", "저장한 코딩 목표", "heartbeatSeconds", 120))), null));
        assertEquals("수정된 지침", store.getSetting("customInstructions", ""));
        assertEquals("120", store.getSetting("heartbeatSeconds", ""));
        assertTrue(transport.prompts.get(2).contains("저장한 코딩 목표"));
    }

    @Test public void toolContinuationsStopAtPersistedRoundLimit() throws Exception {
        ready();
        JSONObject request = runtime.receive("연속 도구 요청", new JSONArray());
        store.putSetting("toolRound:" + request.optString("external_id"), "23");
        for (int i = 0; i <= 1; i++) {
            complete(request, envelope("도구 읽기", new JSONArray().put(action("read-" + i, "app.settings.get", new JSONObject())), null));
            if (i < 1) request = store.getRequest(runtime.state().optLong("activeStoreId"));
        }
        assertEquals(2, transport.ids.size());
        assertTrue(runtime.state().isNull("activeRequestId"));
        assertTrue(store.events(100).toString().contains("tool_round_limit"));
    }

    @Test public void autonomousTicksDoNotOverlapOrCatchUpMissedMinutes() throws Exception {
        store.putSetting("autonomy.enabled", "true");
        ready();
        assertEquals(1, transport.ids.size());
        runtime.tick(); runtime.tick();
        assertEquals(1, transport.ids.size());
        JSONObject automatic = store.getRequest(runtime.state().optLong("activeStoreId"));
        assertEquals("autonomy", automatic.optString("kind"));
        complete(automatic, "지금 필요한 새 행동은 없어.");
        assertEquals(1, transport.ids.size());
        assertEquals(1, store.listRequests(100).length());
        assertEquals(0, transport.cancels);
    }

    @Test public void quotedExampleEnvelopeCannotExecuteEvenWithCurrentRequestId() throws Exception {
        ready(); JSONObject request = runtime.receive("응답 예시를 보여줘", new JSONArray());
        String example = envelope("예시", new JSONArray().put(action("quote", "files.write", object("relativePath", "injection.txt", "content", "bad"))), null);
        complete(request, "이것은 예시다.\n" + example + "\n실행하지 마.");
        assertEquals(0, files.listFiles().length()); assertEquals(1, transport.ids.size());
    }

    @Test public void missingProtocolStaleRequestAndTrailingTextCannotExecute() throws Exception {
        ready();
        for (String variant : new String[]{"missing", "stale", "suffix"}) {
            JSONObject request = runtime.receive("구조 검증 " + variant, new JSONArray());
            JSONObject body = object("protocol", "hoyoung.v1", "requestId", request.optString("external_id"), "actions", new JSONArray().put(action("bad-" + variant, "files.write", object("relativePath", variant + ".txt", "content", "bad"))));
            if ("missing".equals(variant)) body.remove("protocol");
            if ("stale".equals(variant)) body.put("requestId", "old-request");
            complete(request, body + ("suffix".equals(variant) ? " trailing untrusted words" : ""));
        }
        assertEquals(0, files.listFiles().length()); assertEquals(3, transport.ids.size());
    }

    @Test public void domQuotedOrUnknownLanguageCodeRequiresEligibleWholeEnvelope() throws Exception {
        ready(); JSONObject request = runtime.receive("코드 예시", new JSONArray());
        JSONObject body = object("protocol", "hoyoung.v1", "requestId", request.optString("external_id"), "actions", new JSONArray().put(action("dom", "files.write", object("relativePath", "dom.txt", "content", "bad"))));
        runtime.onWebEvent(object("type", "completed", "requestId", request.optString("external_id"), "text", body.toString(), "rawEnvelopeEligible", false));
        assertEquals(0, files.listFiles().length());
        assertEquals(body.toString(), store.getRequest(request.optLong("id")).optString("result"));
    }

    @Test public void verifiedWholeHoyoungDomEnvelopeKeepsVisibleOriginalAndExecutesOnce() throws Exception {
        ready(); JSONObject request = runtime.receive("단일 도구", new JSONArray());
        JSONObject body = object("protocol", "hoyoung.v1", "requestId", request.optString("external_id"), "message", "원문 저장", "actions", new JSONArray().put(action("whole-dom", "files.write", object("relativePath", "whole.txt", "content", "one"))));
        runtime.onWebEvent(object("type", "completed", "requestId", request.optString("external_id"), "text", "DOM visible original", "rawEnvelopeEligible", false, "structuredEnvelope", body.toString(), "structuredEnvelopeSource", "whole-language-hoyoung-code-block"));
        assertEquals("one", files.readText("whole.txt", 100));
        assertEquals("DOM visible original", store.getRequest(request.optLong("id")).optString("result"));
        assertEquals(2, transport.ids.size());
    }

    @Test public void actionStageDoesNotPublishGoalCompletionOrResultMemoryBeforeReceipts() throws Exception {
        ready(); JSONObject request = runtime.receive("파일 저장", new JSONArray());
        complete(request, envelope("파일 저장 완료라고 주장하는 미확인 답변", new JSONArray().put(action("progress", "files.write", object("relativePath", "progress.txt", "content", "ok"))), null));
        assertEquals("ok", files.readText("progress.txt", 100));
        assertFalse(store.listMessages(100).toString().contains("파일 저장 완료라고 주장하는 미확인 답변"));
        assertFalse(store.getSetting("runtime.progress", "").equals("작업 완료"));
        assertEquals(0, store.searchMemories("task-result:" + request.optString("external_id"), 10).length());
        assertTrue(transport.prompts.get(1).contains("원 요청의 원문"));
        assertTrue(transport.prompts.get(1).contains("파일 저장"));
    }

    @Test public void idleAutonomyRecordsJudgmentWithoutInventingChatBubble() throws Exception {
        store.putSetting("autonomy.enabled", "true"); ready();
        JSONObject request = store.getRequest(runtime.state().optLong("activeStoreId"));
        complete(request, object("protocol", "hoyoung.v1", "requestId", request.optString("external_id"), "status", "idle", "message", "").toString());
        assertEquals(0, store.listMessages(100).length());
        assertEquals("idle", runtime.state().optString("lastCode"));
        assertTrue(store.getRequest(request.optLong("id")).optString("result").contains("hoyoung.v1"));
    }

    @Test public void memoryReviewReadContinuationPreservesScopeAndExactBatch() throws Exception {
        long owner = store.addMessage("user", "나는 알레르기가 있어", null).optLong("id");
        runtime.requestMemoryReview(); ready();
        JSONObject request = store.getRequest(runtime.state().optLong("activeStoreId"));
        assertEquals("memory-review", request.optString("kind"));
        String batch = store.getSetting("memoryReview:" + request.optString("external_id"), "");
        complete(request, envelope("확인 중", new JSONArray().put(action("read", "memory.source.read", object("messageId", owner))).put(action("forbidden", "files.write", object("relativePath", "review.txt", "content", "no"))), null));
        assertEquals(0, files.listFiles().length());
        assertEquals(2, transport.ids.size());
        String nextId = transport.ids.get(1);
        assertEquals("memory-review", store.getSetting("requestPurpose:" + nextId, ""));
        assertEquals(batch, store.getSetting("memoryReview:" + nextId, ""));
        assertTrue(transport.prompts.get(1).contains("memory_review_read_scope_only"));
    }

    @Test public void delayedPreparationDiscardsContextAfterSettingsOrGrantPolicyChange() throws Exception {
        List<Runnable> pending = new ArrayList<>();
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context, store), pending::add);
        pending.remove(0).run();
        runtime.attachTransport(transport); ready();
        JSONObject request = runtime.receive("권한 변경 중 준비", new JSONArray());
        assertEquals(1, pending.size());
        // Change during prepare by a retrieval hook isn't needed: run preparation, then delay main completion.
        Thread worker = new Thread(pending.remove(0)); worker.start(); worker.join();
        store.putSetting("phoneContextEnabled", "false");
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(0, transport.ids.size()); assertEquals("queued", store.getRequest(request.optLong("id")).optString("status"));
        assertEquals(1, pending.size()); pending.remove(0).run();
        assertEquals(1, transport.ids.size());
        assertTrue(store.events(30).toString().contains("settings_or_os_permission_changed"));
    }

    @Test public void fullCustomGoalsAndOriginalWhitespaceSurviveOptionalContextBudgeting() throws Exception {
        String custom = "c".repeat(49_990) + "CUSTOM_END", goals = "g".repeat(49_991) + "GOALS_END";
        store.putSetting("customInstructions", custom); store.putSetting("standingGoals", goals); ready();
        String original = "  \n" + "x".repeat(90_000) + "\n  ";
        JSONObject request = runtime.receive(original, new JSONArray());
        assertEquals(1, transport.ids.size()); String prompt = transport.prompts.get(0);
        assertTrue(prompt.contains(custom)); assertTrue(prompt.contains(goals)); assertTrue(prompt.contains(original));
        assertTrue(prompt.length() <= 250_000); assertEquals(original, store.getRequest(request.optLong("id")).optString("text"));
    }

    @Test public void ownerFactWithMismatchedQuoteCannotEnterLongTermMemory() throws Exception {
        ready(); JSONObject request = runtime.receive("고양이 이름은 나비야", new JSONArray());
        long source = Long.parseLong(store.getSetting("requestSource:" + request.optString("external_id"), "0"));
        JSONObject memory = object("key", "wrong-cat", "content", "고양이 이름은 호랑이", "kind", "fact", "sourceRefs", new JSONArray().put(object("type", "message", "id", source, "quote", "고양이 이름은 호랑이", "offsetChars", 0)));
        complete(request, envelope("제안", null, new JSONArray().put(memory)));
        assertNull(store.getMemory("wrong-cat")); assertTrue(store.events(50).toString().contains("memory_rejected"));
    }

    @Test public void emptyExistingReceiptNeverBecomesAReplayableNewAction() throws Exception {
        ready(); JSONObject request = runtime.receive("기존 영수증 확인", new JSONArray());
        String receiptKey = "actionReceipt:" + request.optString("external_id") + ":existing";
        store.putSetting(receiptKey, "");
        complete(request, envelope("쓰기 요청", new JSONArray().put(action("existing", "files.write", object("relativePath", "never-replay.txt", "content", "bad"))), null));
        assertEquals(0, files.listFiles().length());
        assertEquals("", store.getSetting(receiptKey, "missing"));
        assertEquals("uncertain", store.getRequest(request.optLong("id")).optString("status"));
        assertTrue(runtime.state().optBoolean("uncertaintyBlocked"));
        assertEquals(1, transport.ids.size());
    }

    @Test public void rejectedMemoryCannotDisappearAcrossReadContinuationAndFinalAcknowledgement() throws Exception {
        long source = store.addMessage("user", "내 고양이 이름은 나비야", null).optLong("id");
        runtime.requestMemoryReview(); ready(); JSONObject request = store.getRequest(runtime.state().optLong("activeStoreId"));
        JSONObject bad = ownerMemory("pet.name", source, "내 고양이 이름은 호랑이야");
        complete(request, envelope("원문 다시 읽기", new JSONArray().put(action("read-repair", "memory.source.read", object("messageId", source))), new JSONArray().put(bad)));
        JSONObject next = store.getRequest(runtime.state().optLong("activeStoreId"));
        assertEquals("true", store.getSetting("memoryReviewHadRejected:" + next.optString("external_id"), "false"));
        assertTrue(transport.prompts.get(1).contains("거절된 기억 제안"));
        JSONObject expected = new JSONObject(store.getSetting("memoryReview:" + next.optString("external_id"), "{}"));
        complete(next, object("protocol", "hoyoung.v1", "requestId", next.optString("external_id"), "memories", new JSONArray().put(ownerMemory("pet.name", source, "내 고양이 이름은 나비야")), "review", reviewAck(expected)).toString());
        assertNotNull(store.getMemory("pet.name"));
        assertEquals(0, store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        assertTrue(runtime.getMemoryReview().hasPending());
        runtime.requestMemoryReview();
        JSONObject fresh = store.getRequest(runtime.state().optLong("activeStoreId"));
        JSONObject freshBatch = new JSONObject(store.getSetting("memoryReview:" + fresh.optString("external_id"), "{}"));
        assertEquals("false", store.getSetting("memoryReviewHadRejected:" + fresh.optString("external_id"), "false"));
        complete(fresh, object("protocol", "hoyoung.v1", "requestId", fresh.optString("external_id"), "memories", new JSONArray().put(ownerMemory("pet.name", source, "내 고양이 이름은 나비야")), "review", reviewAck(freshBatch)).toString());
        assertEquals(source, store.getWatermark(MemoryReviewCoordinator.WATERMARK));
    }

    @Test public void reviewedMemorySavedByToolIsCountedWhenFinalTurnHasNoDuplicateProposal() throws Exception {
        long source = store.addMessage("user", "난 답변을 짧게 받는 게 좋아", null).optLong("id");
        runtime.requestMemoryReview(); ready(); JSONObject request = store.getRequest(runtime.state().optLong("activeStoreId"));
        complete(request, envelope("도구로 기억 저장", new JSONArray().put(action("save-review", "memory.save", ownerMemory("reply.length", source, "난 답변을 짧게 받는 게 좋아"))), null));
        JSONObject next = store.getRequest(runtime.state().optLong("activeStoreId"));
        assertEquals("1", store.getSetting("memoryReviewSavedCount:" + next.optString("external_id"), "0"));
        JSONObject batch = new JSONObject(store.getSetting("memoryReview:" + next.optString("external_id"), "{}"));
        complete(next, object("protocol", "hoyoung.v1", "requestId", next.optString("external_id"), "memories", new JSONArray(), "review", reviewAck(batch)).toString());
        assertEquals(source, store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        assertFalse(runtime.getMemoryReview().hasPending());
    }

    @Test public void failedMemorySaveToolIsNotHiddenByAnotherSuccessfulSaveInTheSameChain() throws Exception {
        long source = store.addMessage("user", "내 고양이 이름은 나비야", null).optLong("id");
        runtime.requestMemoryReview(); ready(); JSONObject request = store.getRequest(runtime.state().optLong("activeStoreId"));
        JSONArray actions = new JSONArray().put(action("valid-memory", "memory.save", ownerMemory("pet.correct", source, "내 고양이 이름은 나비야")))
                .put(action("invalid-memory", "memory.save", ownerMemory("pet.invalid", source, "내 고양이 이름은 호랑이야")));
        complete(request, envelope("기억 도구 요청", actions, null));
        JSONObject next = store.getRequest(runtime.state().optLong("activeStoreId"));
        assertEquals("1", store.getSetting("memoryReviewSavedCount:" + next.optString("external_id"), "0"));
        assertEquals("true", store.getSetting("memoryReviewHadRejected:" + next.optString("external_id"), "false"));
        JSONObject batch = new JSONObject(store.getSetting("memoryReview:" + next.optString("external_id"), "{}"));
        complete(next, object("protocol", "hoyoung.v1", "requestId", next.optString("external_id"), "review", reviewAck(batch), "memories", new JSONArray()).toString());
        assertEquals(0, store.getWatermark(MemoryReviewCoordinator.WATERMARK));
        assertNotNull(store.getMemory("pet.correct")); assertNull(store.getMemory("pet.invalid"));
        assertTrue(runtime.getMemoryReview().hasPending());
    }

    private static JSONObject ownerMemory(String key, long source, String quote) throws Exception {
        return object("key", key, "content", quote, "kind", "fact", "sourceRefs", new JSONArray().put(object("type", "message", "id", source, "quote", quote, "offsetChars", 0)));
    }

    @Test public void newOwnerInputRevokesPreparedWireAuthorizationBeforeSubmission() throws Exception {
        ready(); JSONObject first = runtime.receive("첫 요청", new JSONArray());
        assertTrue(runtime.preparedDispatchStillAllowed(first.optString("external_id")));
        JSONObject newer = runtime.receive("아까 요청 정정: 실행하지 마", new JSONArray());
        assertFalse(runtime.preparedDispatchStillAllowed(first.optString("external_id")));
        assertEquals("queued", store.getRequest(newer.optLong("id")).optString("status"));
        assertEquals("아까 요청 정정: 실행하지 마", store.getMessage(Long.parseLong(store.getSetting("ownerInputRevision", "0"))).optString("text"));
    }

    @Test public void advertisedSourceReaderCanReadAssistantAndSystemOriginals() throws Exception {
        long assistantSource = store.addMessage("assistant", "생성 관찰 원문", null).optLong("id");
        long systemSource = store.addMessage("system", "앱 진행 상태 원문", null).optLong("id");
        ready(); JSONObject request = runtime.receive("이전 근거 문맥 확인", new JSONArray());
        complete(request, envelope("원문 읽기", new JSONArray().put(action("read-generated", "memory.source.read", object("messageId", assistantSource))).put(action("read-system", "memory.source.read", object("messageId", systemSource))), null));
        for (String id : new String[]{"read-generated", "read-system"}) {
            JSONObject receipt = new JSONObject(store.getSetting("actionReceipt:" + request.optString("external_id") + ":" + id, "{}"));
            assertTrue(receipt.getJSONObject("result").getBoolean("ok"));
            assertFalse(receipt.getJSONObject("result").getJSONObject("original").has("assetCoverage"));
        }
        assertTrue(transport.prompts.get(1).contains("생성 관찰 원문"));
        assertTrue(transport.prompts.get(1).contains("앱 진행 상태 원문"));
    }

    @Test public void ownerSteeringHoldsOldActionsAndFreshContinuationReceivesExactLatestInput() throws Exception {
        ready(); JSONObject first = runtime.receive("파일을 써 줘", new JSONArray()); accept(first);
        String steering = "  방금 그 파일 작성은 취소야.\n새로 쓰지 마.  ";
        JSONObject newer = runtime.receive(steering, new JSONArray());
        complete(first, envelope("예전 계획", new JSONArray().put(action("old-write", "files.write", object("relativePath", "cancelled.txt", "content", "should not run"))), null));
        assertEquals(0, files.listFiles().length());
        assertEquals("missing", store.getSetting("actionReceipt:" + first.optString("external_id") + ":old-write", "missing"));
        assertEquals(newer.optString("external_id"), transport.ids.get(1));
        complete(newer, "취소 요청을 확인했어.");
        JSONObject replanned = store.getRequest(runtime.state().optLong("activeStoreId"));
        assertEquals(first.optString("external_id"), store.getSetting("requestRoot:" + replanned.optString("external_id"), ""));
        assertTrue(transport.prompts.get(2).contains("owner_steering_pending"));
        assertTrue(transport.prompts.get(2).contains("started:false"));
        assertTrue(transport.prompts.get(2).contains(steering));
        complete(replanned, object("protocol", "hoyoung.v1", "requestId", replanned.optString("external_id"), "status", "blocked", "message", "최신 취소 지시에 따라 파일 작성은 수행하지 않았어.").toString());
        assertEquals(0, files.listFiles().length());
        assertEquals("blocked", runtime.getGoalLedger().get(first.optString("external_id")).optString("stage"));
        assertFalse(store.events(100).toString().contains("\"cloudStopped\":true"));
    }

    @Test public void explicitPauseAfterAcceptanceHoldsEffectsWithoutInventingCloudStopOrReplay() throws Exception {
        ready(); JSONObject request = runtime.receive("작업 도중 중지", new JSONArray()); accept(request);
        JSONObject paused = runtime.pauseCurrentWork("주인이 앱 버튼을 직접 누름");
        assertTrue(paused.getBoolean("newLocalEffectsHeld"));
        assertTrue(paused.getBoolean("cloudCancellationRequested"));
        assertFalse(paused.getBoolean("cloudStoppedConfirmed"));
        assertEquals(1, transport.cancels);
        assertEquals("accepted", store.getRequest(request.optLong("id")).optString("status"));
        complete(request, envelope("중지 뒤 옛 제안", new JSONArray().put(action("paused-write", "files.write", object("relativePath", "pause.txt", "content", "no"))), null));
        assertEquals(0, files.listFiles().length()); assertEquals(1, transport.ids.size());
        assertEquals("blocked", runtime.getGoalLedger().get(request.optString("external_id")).optString("stage"));
        assertTrue(store.getRequest(request.optLong("id")).optString("result").contains("paused-write"));
        assertFalse(store.events(100).toString().contains("\"cloudStoppedConfirmed\":true"));
    }

    @Test public void explicitPauseDuringPreparationIsProvenUnsentAndOwnerRetryResumesFreshAttempt() throws Exception {
        List<Runnable> pending = new ArrayList<>();
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context, store), pending::add);
        pending.remove(0).run(); runtime.attachTransport(transport); ready();
        JSONObject request = runtime.receive("준비 단계 중지", new JSONArray());
        JSONObject paused = runtime.pauseCurrentWork("앱의 현재 작업 중지");
        assertTrue(paused.getBoolean("knownNotSent"));
        assertEquals(0, transport.cancels);
        pending.remove(0).run(); assertEquals(0, transport.ids.size());
        assertEquals("blocked", store.getRequest(request.optLong("id")).optString("status"));
        JSONObject retry = runtime.retryRequest(request.optLong("id")); ready(); pending.remove(0).run();
        assertEquals(retry.optString("external_id"), transport.ids.get(0));
        assertTrue(runtime.preparedDispatchStillAllowed(retry.optString("external_id")));
    }

    private static JSONObject reviewAck(JSONObject batch) throws Exception {
        JSONArray ranges = new JSONArray(); JSONArray sources = batch.getJSONArray("sourceRanges");
        for (int i = 0; i < sources.length(); i++) { JSONObject source = sources.getJSONObject(i); ranges.put(object("messageId", source.getLong("messageId"), "offsetChars", source.getLong("offsetChars"), "nextOffsetChars", source.getLong("nextOffsetChars"), "contentHash", source.getString("contentHash"))); }
        return object("batchId", batch.getString("batchId"), "sourceRanges", ranges, "noMemoryNeeded", false, "reason", "받은 원문을 검토하고 출처가 있는 기억을 저장함");
    }

    private void ready() throws Exception { runtime.onWebEvent(object("type", "state", "code", "ready", "state", readyState())); }
    private static JSONObject readyState() throws Exception { return object("authenticated", true, "workSelected", true, "temporary", true, "model", "gpt-6.1-sol", "effort", "medium", "speed", "fast", "busy", false, "quota", false); }
    private void accept(JSONObject request) throws Exception { runtime.onWebEvent(object("type", "accepted", "requestId", request.optString("external_id"))); }
    private void complete(JSONObject request, String text) throws Exception { text = text.replace("__CURRENT_REQUEST__", request.optString("external_id")); runtime.onWebEvent(object("type", "completed", "requestId", request.optString("external_id"), "text", text, "rawEnvelopeEligible", true)); }
    private static JSONObject action(String id, String name, JSONObject args) throws Exception { return object("id", id, "name", name, "arguments", args); }
    private static String envelope(String message, JSONArray actions, JSONArray memories) throws Exception {
        JSONObject value = object("protocol", "hoyoung.v1", "requestId", "__CURRENT_REQUEST__", "message", message);
        if (actions != null) value.put("actions", actions);
        if (memories != null) value.put("memories", memories);
        return "```hoyoung\n" + value + "\n```";
    }
    private static JSONObject object(Object... pairs) throws Exception { JSONObject value = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]); return value; }
    private static void deleteTree(java.io.File file) {
        if (file == null || !file.exists()) return;
        java.io.File[] children = file.listFiles();
        if (children != null) for (java.io.File child : children) deleteTree(child);
        if (!file.delete()) throw new IllegalStateException("Could not remove test file");
    }

    private final class FakeTransport implements AssistantRuntime.Transport {
        final List<String> ids = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();
        int inspections;
        int cancels;
        int opens;
        boolean persistedBeforeSend = true;
        @Override public void inspect() { inspections++; }
        @Override public void send(String id, String prompt) {
            JSONArray requests = store.listRequests(100);
            boolean found = false;
            for (int i = 0; i < requests.length(); i++) {
                JSONObject row = requests.optJSONObject(i);
                if (id.equals(row.optString("external_id"))) found = "dispatching".equals(row.optString("status"));
            }
            persistedBeforeSend &= found;
            ids.add(id); prompts.add(prompt);
        }
        @Override public void openTemporary() { opens++; }
        @Override public void cancel() { cancels++; }
    }
}
