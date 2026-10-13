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
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context));
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
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context));
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

    @Test public void oversizedPromptFailsBeforeAnyDispatchWithoutTruncatingOriginal() throws Exception {
        ready();
        String huge = "x".repeat(95_000);
        JSONObject request = runtime.receive(huge, new JSONArray());
        assertEquals(0, transport.ids.size());
        assertEquals("blocked", store.getRequest(request.optLong("id")).optString("status"));
        assertEquals(huge, store.getRequest(request.optLong("id")).optString("text"));
        assertEquals("prompt_too_large", runtime.state().optString("lastCode"));
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
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context));
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
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context));
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
        assertEquals(file.optString("id"), new JSONArray(store.getSetting("attachments:" + id, "[]")).getString(0));
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
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context));
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
                .put(object("key", "answer-length", "content", "사용자는 짧은 답변을 선호한다", "kind", "preference", "sourceMessageIds", new JSONArray().put(ownerSource)));
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

    @Test public void toolContinuationsStopAfterFourRounds() throws Exception {
        ready();
        JSONObject request = runtime.receive("연속 도구 요청", new JSONArray());
        for (int i = 0; i <= 4; i++) {
            complete(request, envelope("도구 읽기", new JSONArray().put(action("read-" + i, "app.settings.get", new JSONObject())), null));
            if (i < 4) request = store.getRequest(runtime.state().optLong("activeStoreId"));
        }
        assertEquals(5, transport.ids.size());
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

    private void ready() throws Exception { runtime.onWebEvent(object("type", "state", "code", "ready", "state", readyState())); }
    private static JSONObject readyState() throws Exception { return object("authenticated", true, "workSelected", true, "temporary", true, "model", "gpt-6.1-sol", "effort", "medium", "speed", "fast", "busy", false, "quota", false); }
    private void accept(JSONObject request) throws Exception { runtime.onWebEvent(object("type", "accepted", "requestId", request.optString("external_id"))); }
    private void complete(JSONObject request, String text) throws Exception { runtime.onWebEvent(object("type", "completed", "requestId", request.optString("external_id"), "text", text)); }
    private static JSONObject action(String id, String name, JSONObject args) throws Exception { return object("id", id, "name", name, "arguments", args); }
    private static String envelope(String message, JSONArray actions, JSONArray memories) throws Exception {
        JSONObject value = object("message", message);
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
