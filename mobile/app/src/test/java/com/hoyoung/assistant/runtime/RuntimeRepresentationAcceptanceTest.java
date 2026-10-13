package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.store.InternalFiles;
import com.hoyoung.assistant.web.WorkAttachmentRepresentations;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.zip.CRC32;
import static org.junit.Assert.*;

/** Real private files, native image decoding and SQLite; only the Work transport is a fixture.
 * These tests establish delivery/provenance boundaries, not that ChatGPT accepts every file type.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class RuntimeRepresentationAcceptanceTest {
    private static final long MIB = 1024L * 1024;
    private static final String ANALYSIS = "전달된 이미지는 파란색이다. 원본의 모든 세부사항을 확인한 것은 아니다.";
    private Context context;
    private HoyoungStore store;
    private InternalFiles files;
    private AssistantRuntime runtime;
    private FakeTransport transport;
    private WorkAttachmentRepresentations delivery;
    private ExecutorService mediaWorker;

    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir());
        clear(new File(context.getNoBackupFilesDir(), "work-attachment-representations"));
        clear(new File(context.getNoBackupFilesDir(), "work-attachment-representation-auth"));
        store = new HoyoungStore(context);
        files = new InternalFiles(context);
        store.putSetting("customInstructions", "Media integration fixture: report exactly what was observed.");
        store.putSetting("phoneContextEnabled", "false");
        store.putSetting("semanticSearchEnabled", "false");
        runtime = new AssistantRuntime(context, store, files, new PhoneTools(context, store), Runnable::run);
        transport = new FakeTransport();
        runtime.attachTransport(transport);
        delivery = new WorkAttachmentRepresentations(context, files);
        mediaWorker = Executors.newSingleThreadExecutor();
        ready();
    }

    @After public void after() throws Exception {
        runtime.detachTransport();
        mediaWorker.shutdownNow();
        assertTrue(mediaWorker.awaitTermination(10, TimeUnit.SECONDS));
        store.close();
        context.deleteDatabase(HoyoungStore.DATABASE_NAME);
        clear(context.getFilesDir());
        clear(new File(context.getNoBackupFilesDir(), "work-attachment-representations"));
        clear(new File(context.getNoBackupFilesDir(), "work-attachment-representation-auth"));
    }

    @Test public void largePhotoSurvivesOwnerQueueDerivedDeliveryAndDurableObservation() throws Exception {
        Fixture fixture = photo();
        assertTrue(fixture.source.getLong("size") > 10 * MIB);
        assertEquals("image/png", fixture.source.getString("mime"));
        JSONObject plan = prepare(fixture, 256 * 1024);
        assertEquals("delivered_representation", plan.getString("analysisScope"));
        assertNotEquals(fixture.source.getString("sha256"), plan.getString("providerRepresentationSha256"));
        persistPlan(fixture, plan);
        accept(fixture, plan.getString("wireName"));
        JSONObject observed = observed(fixture).getJSONObject(0);
        assertEquals(fixture.messageId, observed.getLong("sourceMessageId"));
        assertEquals(fixture.source.getString("id"), observed.getString("id"));
        assertEquals(fixture.source.getString("sha256"), observed.getString("sha256"));
        assertEquals(plan.getString("providerRepresentationSha256"), observed.getString("providerRepresentationSha256"));
        complete(fixture, plan.getString("providerRepresentationSha256"));

        JSONObject covered = covered(fixture);
        assertEquals("provider_analyzed", covered.getString("coverageStage"));
        assertEquals("delivered_representation", covered.getString("analysisScope"));
        assertEquals(plan.getString("providerRepresentationSha256"), covered.getString("providerRepresentationSha256"));
        assertEquals(plan.getString("representationAssetId"), covered.getString("representationAssetId"));
        assertFalse(covered.getBoolean("analysisVerified"));
        assertFalse(covered.getBoolean("userConfirmed"));
        assertFalse(covered.getBoolean("originalBytesIncludedInWire"));
        assertNoFullInspectionClaims(covered);
        assertEquals(0, runtime.getMemoryReview().status().getLong("assetsPending"));

        JSONObject receipt = new JSONObject(store.getSetting(covered.getString("analysisReceiptKey"), "{}"));
        assertEquals(fixture.request.getString("external_id"), receipt.getString("providerRequestId"));
        assertEquals(fixture.messageId, receipt.getLong("sourceMessageId"));
        assertEquals(store.getRequest(fixture.request.getLong("id")).getString("raw_result_hash"), receipt.getString("providerResponseHash"));
        assertNoFullInspectionClaims(receipt);
        assertEquals(ANALYSIS, store.readOriginalText(covered.getLong("analysisSourceMessageId"), 0, 1000).getString("text"));
        JSONObject memory = store.getMemory(memoryKey(fixture));
        assertEquals("observation", memory.getString("kind"));
        JSONObject metadata = memory.getJSONObject("metadata");
        assertFalse(metadata.getBoolean("meaningIndependentlyVerified"));
        assertNoFullInspectionClaims(metadata);
        JSONObject reference = metadata.getJSONArray("assetRefs").getJSONObject(0);
        assertEquals(fixture.messageId, reference.getLong("messageId"));
        assertEquals(fixture.source.getString("sha256"), reference.getString("sha256"));
        assertEquals(plan.getString("providerRepresentationSha256"), reference.getString("providerRepresentationSha256"));
        assertOriginalPreserved(fixture);

        // A fresh coordinator must validate the persisted provider response, native plan and wire proof.
        MemoryReviewCoordinator restarted = new MemoryReviewCoordinator(store);
        assertEquals("provider_analyzed", restarted.assetCoverage(fixture.messageId).getJSONArray("assets").getJSONObject(0).getString("coverageStage"));
        assertEquals(0, restarted.status().getLong("assetsPending"));
        assertOriginalPreserved(fixture);
    }

    @Test public void originalHashCannotStandInForTheActualDerivedWireHash() throws Exception {
        Fixture fixture = photo();
        JSONObject plan = prepare(fixture, 256 * 1024);
        persistPlan(fixture, plan);
        accept(fixture, plan.getString("wireName"));
        complete(fixture, fixture.source.getString("sha256"));
        assertPendingWithoutAnalysis(fixture);
    }

    @Test public void originalFilenameCannotSubstituteForObservedDerivedFilename() throws Exception {
        Fixture fixture = photo();
        JSONObject plan = prepare(fixture, 256 * 1024);
        assertNotEquals(fixture.source.getString("name"), plan.getString("wireName"));
        persistPlan(fixture, plan);
        accept(fixture, fixture.source.getString("name"));
        assertEquals(0, observed(fixture).length());
        complete(fixture, plan.getString("providerRepresentationSha256"));
        assertPendingWithoutAnalysis(fixture);
    }

    @Test public void derivedFilenameWithoutNativePlanCannotEstablishAnUpload() throws Exception {
        Fixture fixture = photo();
        JSONObject plan = prepare(fixture, 256 * 1024);
        accept(fixture, plan.getString("wireName"));
        assertEquals(0, observed(fixture).length());
        complete(fixture, plan.getString("providerRepresentationSha256"));
        assertPendingWithoutAnalysis(fixture);
    }

    @Test public void losingNativePlanAfterDomObservationCannotFallBackToOriginalAnalysis() throws Exception {
        Fixture fixture = photo();
        JSONObject plan = prepare(fixture, 256 * 1024);
        persistPlan(fixture, plan);
        accept(fixture, plan.getString("wireName"));
        assertEquals(1, observed(fixture).length());
        store.putSetting("workAttachmentRepresentations:" + fixture.id(), "");
        complete(fixture, plan.getString("providerRepresentationSha256"));
        assertPendingWithoutAnalysis(fixture);
    }

    @Test public void nativePlanBoundToAnotherOwnerMessageCannotBeAccepted() throws Exception {
        Fixture fixture = photo();
        JSONObject plan = prepare(fixture, 256 * 1024);
        persistPlan(fixture, new JSONObject(plan.toString()).put("sourceMessageId", fixture.messageId + 999));
        accept(fixture, plan.getString("wireName"));
        assertEquals(0, observed(fixture).length());
        complete(fixture, plan.getString("providerRepresentationSha256"));
        assertPendingWithoutAnalysis(fixture);
    }

    @Test public void missingPlanSourceIdIsBoundByNativeRuntimeToTheActualOwnerMessage() throws Exception {
        Fixture fixture = photo();
        JSONObject plan = prepare(fixture, 256 * 1024);
        plan.remove("sourceMessageId");
        persistPlan(fixture, plan);
        accept(fixture, plan.getString("wireName"));
        assertEquals(fixture.messageId, observed(fixture).getJSONObject(0).getLong("sourceMessageId"));
        complete(fixture, plan.getString("providerRepresentationSha256"));
        assertEquals("provider_analyzed", covered(fixture).getString("coverageStage"));
        assertNoFullInspectionClaims(covered(fixture));
        assertOriginalPreserved(fixture);
    }

    @Test public void videoAudioPdfAndArchiveUseExactBytesWithoutInventingProviderSupport() throws Exception {
        String[] names = {"clip.mp4", "voice.m4a", "document.pdf", "archive.zip"};
        String[] mimes = {"video/mp4", "audio/mp4", "application/pdf", "application/zip"};
        JSONArray attachments = new JSONArray();
        for (int i = 0; i < names.length; i++) {
            byte[] bytes = ("Synthetic byte-transfer fixture, not a valid " + names[i]).getBytes(StandardCharsets.UTF_8);
            JSONObject working = files.writeFile(names[i], new ByteArrayInputStream(bytes));
            working.put("mime", mimes[i]).put("mimeSource", "caller_metadata");
            attachments.put(working);
        }
        JSONObject request = runtime.receive("보낼 수 있는지 확인하고 지원하지 않으면 알려줘", attachments);
        String id = request.getString("external_id");
        JSONArray sources = new JSONArray(store.getSetting("attachmentMetadata:" + id, "[]"));
        assertEquals(4, sources.length());
        JSONArray plans = new JSONArray(), namesObserved = new JSONArray();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.getJSONObject(i);
            WorkAttachmentRepresentations.Representation result = worker(source, 64 * 1024);
            assertEquals(mimes[i], result.mime);
            assertEquals(source.getString("sha256"), result.wireSha256);
            assertArrayEquals(Files.readAllBytes(original(source).toPath()), Base64.decode(result.payloadBase64(), Base64.DEFAULT));
            assertTrue(result.metadata().getBoolean("originalBytesIncludedInWire"));
            assertNoFullInspectionClaims(result.metadata());
            plans.put(result.metadata()); namesObserved.put(result.name);
        }
        store.putSetting("workAttachmentRepresentations:" + id, plans.toString());
        runtime.onWebEvent(object("type", "accepted", "requestId", id, "uploadObserved", true, "attachmentNames", namesObserved));
        assertEquals(4, new JSONArray(store.getSetting("uploadedAssetsObserved:" + id, "[]")).length());
        // A provider refusal is not an asset analysis and cannot silently advance any file's coverage.
        runtime.onWebEvent(object("type", "completed", "requestId", id, "rawEnvelopeEligible", true,
                "text", "```hoyoung\n" + object("protocol", "hoyoung.v1", "requestId", id, "status", "blocked", "message", "이 서비스가 파일 형식을 지원하는지는 실제로 확인되지 않았어.") + "\n```"));
        long sourceId = Long.parseLong(store.getSetting("requestSource:" + id, "0"));
        assertEquals(4, runtime.getMemoryReview().status().getLong("assetsPending"));
        JSONArray covered = runtime.getMemoryReview().assetCoverage(sourceId).getJSONArray("assets");
        for (int i = 0; i < covered.length(); i++) {
            assertEquals("raw_saved", covered.getJSONObject(i).optString("coverageStage", "raw_saved"));
            assertFalse(covered.getJSONObject(i).optBoolean("analysisVerified", false));
            assertEquals(sources.getJSONObject(i).getString("sha256"), sha256(original(sources.getJSONObject(i))));
        }
        assertEquals(0, store.memoryCount());
    }

    @Test public void oversizedGenericFileIsPreservedAndNeverSilentlyTruncatedOrQueued() throws Exception {
        byte[] large = new byte[(int) (21 * MIB)];
        Arrays.fill(large, (byte) 73);
        JSONObject working = files.writeFile("large-archive.zip", new ByteArrayInputStream(large));
        JSONObject original = files.freezeAttachments(new JSONArray().put(working)).getJSONObject(0);
        String expected = original.getString("sha256");
        assertThrows(IllegalArgumentException.class, () -> runtime.receive("큰 파일 전송", new JSONArray().put(working)));
        assertEquals(0, transport.ids.size());
        assertEquals(0, store.listRequests(100).length());
        assertEquals(21 * MIB, original(original).length());
        assertEquals(expected, sha256(original(original)));
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> mediaWorker.submit(() -> delivery.prepare(original, 10 * MIB)).get(90, TimeUnit.SECONDS));
        assertTrue(refused.getCause() instanceof WorkAttachmentRepresentations.RepresentationFailure);
        assertEquals("non_image_too_large", ((WorkAttachmentRepresentations.RepresentationFailure) refused.getCause()).code);
        assertEquals(expected, sha256(original(original)));
    }

    private Fixture photo() throws Exception {
        File fixture = new File(context.getFilesDir(), "large-blue.png");
        writeLargeBluePng(fixture);
        JSONObject working;
        try (InputStream input = new FileInputStream(fixture)) { working = files.writeFile("large-blue.png", input); }
        JSONObject request = runtime.receive("이 사진에서 확인할 수 있는 내용을 기억해줘", new JSONArray().put(working));
        String id = request.getString("external_id");
        assertEquals(1, transport.ids.size());
        assertEquals(id, transport.ids.get(0));
        JSONObject source = new JSONArray(store.getSetting("attachmentMetadata:" + id, "[]")).getJSONObject(0);
        long messageId = Long.parseLong(store.getSetting("requestSource:" + id, "0"));
        assertEquals(messageId, source.getLong("sourceMessageId"));
        return new Fixture(request, source, messageId);
    }

    private JSONObject prepare(Fixture fixture, long budget) throws Exception { return worker(fixture.source, budget).metadata(); }
    private WorkAttachmentRepresentations.Representation worker(JSONObject source, long budget) throws Exception {
        return mediaWorker.submit(() -> delivery.prepare(source, budget)).get(90, TimeUnit.SECONDS);
    }
    private void persistPlan(Fixture fixture, JSONObject plan) { store.putSetting("workAttachmentRepresentations:" + fixture.id(), new JSONArray().put(plan).toString()); }
    private JSONArray observed(Fixture fixture) throws Exception { return new JSONArray(store.getSetting("uploadedAssetsObserved:" + fixture.id(), "[]")); }
    private void accept(Fixture fixture, String wireName) throws Exception {
        runtime.onWebEvent(object("type", "accepted", "requestId", fixture.id(), "uploadObserved", true,
                "attachmentNames", new JSONArray().put(wireName), "uploadObservationScope", "filename_dom_only"));
    }
    private void complete(Fixture fixture, String wireSha) throws Exception {
        JSONObject analysis = object("messageId", fixture.messageId, "assetId", fixture.source.getString("id"),
                "sha256", fixture.source.getString("sha256"), "providerRepresentationSha256", wireSha,
                "status", "analyzed", "content", ANALYSIS,
                "originalBytesFullyUploaded", true, "originalContentFullyInspected", true, "serverBytesIndependentlyVerified", true);
        String text = "```hoyoung\n" + object("protocol", "hoyoung.v1", "requestId", fixture.id(), "message", "전달 이미지의 관찰을 저장했어.", "assetAnalyses", new JSONArray().put(analysis)) + "\n```";
        runtime.onWebEvent(object("type", "completed", "requestId", fixture.id(), "text", text, "rawEnvelopeEligible", true));
        assertEquals("completed", store.getRequest(fixture.request.getLong("id")).getString("status"));
    }
    private JSONObject covered(Fixture fixture) throws Exception { return runtime.getMemoryReview().assetCoverage(fixture.messageId).getJSONArray("assets").getJSONObject(0); }
    private void assertPendingWithoutAnalysis(Fixture fixture) throws Exception {
        JSONObject covered = covered(fixture);
        assertEquals("raw_saved", covered.optString("coverageStage", "raw_saved"));
        assertFalse(covered.optBoolean("analysisVerified", false));
        assertEquals(0, covered.optLong("analysisSourceMessageId"));
        assertEquals(1, runtime.getMemoryReview().status().getLong("assetsPending"));
        assertNull(store.getMemory(memoryKey(fixture)));
        assertOriginalPreserved(fixture);
    }
    private void assertOriginalPreserved(Fixture fixture) throws Exception {
        assertEquals(fixture.source.getLong("size"), original(fixture.source).length());
        assertEquals(fixture.source.getString("sha256"), sha256(original(fixture.source)));
    }
    private static void assertNoFullInspectionClaims(JSONObject value) throws Exception {
        for (String flag : new String[]{"originalBytesFullyUploaded", "originalContentFullyInspected", "serverBytesIndependentlyVerified"}) {
            assertTrue(flag + " must remain a typed native boolean", value.get(flag) instanceof Boolean);
            assertFalse(flag, value.getBoolean(flag));
        }
    }
    private void ready() throws Exception { runtime.onWebEvent(object("type", "state", "code", "ready", "state", object("authenticated", true, "workSelected", true, "temporary", true, "model", "gpt-6.1-sol", "effort", "medium", "speed", "fast", "busy", false, "quota", false))); }
    private File original(JSONObject source) throws Exception { return new File(context.getFilesDir(), "projects/" + source.getString("id")); }
    private static String memoryKey(Fixture fixture) throws Exception { return "asset-analysis:" + fixture.messageId + ":" + fixture.source.getString("sha256") + ":" + sha256(fixture.source.getString("id").getBytes(StandardCharsets.UTF_8)); }
    private static JSONObject object(Object... pairs) throws Exception { JSONObject result = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }

    /** Actual native PNG plus a valid private ancillary chunk; never an invalid trailing-byte fake. */
    private static void writeLargeBluePng(File destination) throws Exception {
        Bitmap image = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.BLUE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, bytes)); } finally { image.recycle(); }
        byte[] png = bytes.toByteArray();
        assertTrue(png.length > 12);
        assertArrayEquals("IEND".getBytes(StandardCharsets.US_ASCII), Arrays.copyOfRange(png, png.length - 8, png.length - 4));
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(destination)))) {
            output.write(png, 0, png.length - 12);
            byte[] type = "hoYg".getBytes(StandardCharsets.US_ASCII), zeros = new byte[16384];
            long remaining = 11 * MIB;
            output.writeInt((int) remaining); output.write(type);
            CRC32 crc = new CRC32(); crc.update(type);
            while (remaining > 0) { int count = (int) Math.min(remaining, zeros.length); output.write(zeros, 0, count); crc.update(zeros, 0, count); remaining -= count; }
            output.writeInt((int) crc.getValue());
            output.write(png, png.length - 12, 12);
        }
    }
    private static String sha256(File file) throws Exception { try (InputStream input = new FileInputStream(file)) { MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[16384]; int read; while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read); return hex(digest.digest()); } }
    private static String sha256(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) { StringBuilder result = new StringBuilder(); for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255)); return result.toString(); }
    private static void clear(File file) throws IOException { if (Files.isSymbolicLink(file.toPath())) { Files.deleteIfExists(file.toPath()); return; } if (!file.exists()) return; if (file.isDirectory()) { File[] children = file.listFiles(); if (children == null) throw new IOException("Fixture directory unavailable"); for (File child : children) clear(child); } Files.delete(file.toPath()); }
    private static final class Fixture {
        final JSONObject request, source; final long messageId;
        Fixture(JSONObject request, JSONObject source, long messageId) { this.request = request; this.source = source; this.messageId = messageId; }
        String id() { return request.optString("external_id"); }
    }
    private static final class FakeTransport implements AssistantRuntime.Transport {
        final List<String> ids = new ArrayList<>();
        public void inspect() { }
        public void openTemporary() { }
        public void cancel() { }
        public void send(String id, String prompt) { ids.add(id); }
    }
}
