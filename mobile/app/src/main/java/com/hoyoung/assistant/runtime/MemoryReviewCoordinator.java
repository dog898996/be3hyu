package com.hoyoung.assistant.runtime;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Source-range acknowledgements measure review coverage, never perfect semantic recall. */
public final class MemoryReviewCoordinator {
    public static final String WATERMARK = "memory.review.owner";
    private static final String PREFIX = "memory.review.";
    private static final String ASSET_PENDING = "pendingAssetReview:", ASSET_PENDING_END = "pendingAssetReview;";
    private static final int SOURCE_CHARS = 8_000, BATCH_CHARS = 24_000, OVERLAP_CHARS = 512;
    private final HoyoungStore store;
    public MemoryReviewCoordinator(HoyoungStore store) { this.store = store; }

    public JSONObject prepareBatch() { return prepareBatch(60_000); }

    /** The cap applies to serialized JSON, including escaping and bounded context. */
    public JSONObject prepareBatch(int maxSerializedChars) {
        if (maxSerializedChars < 4_096 || maxSerializedChars > 250_000)
            throw new IllegalArgumentException("Review JSON budget must be 4,096..250,000 characters");
        return store.withTransaction(db -> {
            discoverAssetSources(db, 256); refreshPendingSources(db, 32);
            boolean revisit = revisitPending();
            String partialPrefix = revisit ? PREFIX + "revisit." : PREFIX;
            long cursor = revisit ? number(store.getSetting(PREFIX + "revisit.after", "0")) : store.getWatermark(WATERMARK);
            long through = revisit ? number(store.getSetting(PREFIX + "revisit.through", "0")) : Long.MAX_VALUE;
            long partialId = number(store.getSetting(partialPrefix + "partialId", "0"));
            long partialOffset = number(store.getSetting(partialPrefix + "partialOffset", "0"));
            JSONArray originals = store.listUserMessagesAfter(cursor, 16), ranges = new JSONArray(), blocked = new JSONArray();
            int budget = BATCH_CHARS, contextBudget = 4_000;
            long nextCursor = cursor, nextPartialId = 0, nextPartialOffset = 0;
            boolean risk = false;
            for (int i = 0; i < originals.length() && budget > 0; i++) {
                JSONObject original = originals.optJSONObject(i);
                if (original == null || original.optLong("id") > through) break;
                long id = original.optLong("id"), offset = id == partialId ? partialOffset : 0;
                JSONObject chunk;
                try { chunk = store.readOriginalText(id, offset, Math.min(SOURCE_CHARS, budget)); }
                catch (IOException | IllegalArgumentException unreadable) {
                    blocked.put(object("messageId", id, "code", "source_unavailable"));
                    store.putSetting(PREFIX + "lastCode", "source_unavailable");
                    break;
                }
                String text = chunk.optString("text");
                if (text.isEmpty() && !chunk.optBoolean("complete")) {
                    blocked.put(object("messageId", id, "code", "source_range_empty")); break;
                }
                JSONObject range = object("messageId", id, "offsetChars", chunk.optLong("offsetChars"),
                        "nextOffsetChars", chunk.optLong("nextOffsetChars"), "totalChars", chunk.optLong("totalChars"),
                        "contentHash", chunk.optString("contentHash"), "recordHash", chunk.optString("recordHash"),
                        "createdAt", original.optLong("created_at"), "text", text, "reviewScope", "acknowledged_source_range");
                attachAssetContext(range, id);
                JSONObject before = overlap(id, Math.max(0, offset - OVERLAP_CHARS), offset);
                JSONObject after = overlap(id, chunk.optLong("nextOffsetChars"),
                        Math.min(chunk.optLong("totalChars"), chunk.optLong("nextOffsetChars") + OVERLAP_CHARS));
                if (before != null) put(range, "contextBefore", before);
                if (after != null) put(range, "contextAfter", after);
                if (contextBudget > 0 && (text.length() <= 160 || ranges.length() == 0)) {
                    JSONArray neighbors = neighboringContext(db, id, contextBudget);
                    put(range, "contextMessages", neighbors);
                    for (int n = 0; n < neighbors.length(); n++) contextBudget -= neighbors.optJSONObject(n).optString("text").length();
                }
                boolean rangeRisk = sensitiveOrDurableCue((before == null ? "" : before.optString("text"))
                        + text + (after == null ? "" : after.optString("text")));
                put(range, "noMemoryDecisionNeedsSecondPass", rangeRisk); risk |= rangeRisk;
                ranges.put(range); budget -= text.length();
                if (chunk.optBoolean("complete")) nextCursor = id;
                else { nextPartialId = id; nextPartialOffset = chunk.optLong("nextOffsetChars"); break; }
            }
            if (ranges.length() == 0 && !revisit && assetPendingCount(db) > 0)
                return prepareAssetBatch(db, cursor, partialId, partialOffset, maxSerializedChars);
            JSONObject batch = object("reviewMode", revisit ? "revisit" : "incremental", "cursorStart", cursor,
                    "partialIdStart", partialId, "partialOffsetStart", partialOffset, "cursorEnd", nextCursor,
                    "partialIdEnd", nextPartialId, "partialOffsetEnd", nextPartialOffset,
                    "sourceRanges", ranges, "blockedSources", blocked, "assetsPending", assetPendingCount(db),
                    "noMemoryDecisionNeedsSecondPass", risk, "coverageMeaning", "source_ranges_acknowledged_by_model_not_verified_semantic_recall");
            if (revisit) {
                put(batch, "revisitEpoch", store.getSetting(PREFIX + "revisit.epoch", ""));
                put(batch, "revisitThroughMessageId", through);
                put(batch, "revisitReason", store.getSetting(PREFIX + "revisit.reason", ""));
            }
            fitToSerializedBudget(batch, maxSerializedChars - 256);
            put(batch, "batchId", coverageDigest(batch));
            JSONObject previous = parse(store.getSetting(PREFIX + "noMemoryCandidate", "{}"));
            if (batch.optString("batchId").equals(previous.optString("batchId"))) {
                put(batch, "secondPassRequired", true);
                String priorReason = previous.optString("reason");
                put(batch, "previousNoMemoryReason", priorReason);
                while (batch.toString().length() > maxSerializedChars && !priorReason.isEmpty()) {
                    priorReason = safePrefix(priorReason, priorReason.length() / 2); put(batch, "previousNoMemoryReason", priorReason);
                    put(batch, "previousNoMemoryReasonTruncated", true);
                }
            }
            return batch;
        });
    }

    /** A new pass uses its own cursor; the original durable watermark never moves backwards. */
    public boolean requestRevisit(long fromMessageIdInclusive, long throughMessageId, String reason) {
        if (fromMessageIdInclusive < 0 || throughMessageId < 0 || reason == null || reason.trim().isEmpty())
            throw new IllegalArgumentException("A nonnegative source range and revisit reason are required");
        return store.withTransaction(db -> {
            if (revisitPending()) return false;
            long after = Math.max(0, fromMessageIdInclusive - 1), through = 0;
            try (Cursor lastSource = db.rawQuery("SELECT MAX(id) FROM messages WHERE role='user' AND id > ? AND id <= ?",
                    new String[]{String.valueOf(after), String.valueOf(throughMessageId)})) {
                if (lastSource.moveToFirst() && !lastSource.isNull(0)) through = lastSource.getLong(0);
            }
            if (through <= after) return false;
            store.putSetting(PREFIX + "revisit.after", String.valueOf(after));
            store.putSetting(PREFIX + "revisit.through", String.valueOf(through));
            store.putSetting(PREFIX + "revisit.epoch", java.util.UUID.randomUUID().toString());
            store.putSetting(PREFIX + "revisit.reason", reason.trim().substring(0, Math.min(512, reason.trim().length())));
            store.putSetting(PREFIX + "revisit.partialId", "0"); store.putSetting(PREFIX + "revisit.partialOffset", "0");
            store.putSetting(PREFIX + "noMemoryCandidate", "{}");
            store.recordEvent("memory_review_revisit_requested", object("fromMessageIdInclusive", fromMessageIdInclusive,
                    "throughMessageId", through, "reason", store.getSetting(PREFIX + "revisit.reason", "")));
            return true;
        });
    }

    public boolean hasPending() {
        return store.withTransaction(db -> {
            discoverAssetSources(db, 32); auditAssetSources(db, 32); refreshPendingSources(db, 32);
            return revisitPending() || store.lastUserMessageId() > store.getWatermark(WATERMARK) || assetPendingCount(db) > 0;
        });
    }

    public JSONObject status() {
        return object("reviewedThroughMessageId", store.getWatermark(WATERMARK),
                "lastOwnerMessageId", store.lastUserMessageId(), "hasPending", hasPending(),
                "textReviewedThroughMessageId", store.getWatermark(WATERMARK),
                "assetsPending", store.withTransaction(MemoryReviewCoordinator::assetPendingCount),
                "assetSourcesPending", store.withTransaction(MemoryReviewCoordinator::assetPendingSourceCount),
                "assetReviewCursor", assetReviewCursor(),
                "assetDiscoveryCursor", number(store.getSetting(PREFIX + "assetDiscoveryCursor", "0")),
                "assetIntegrityAuditCursor", number(store.getSetting(PREFIX + "assetIntegrityAuditCursor", "0")),
                "assetAnalysisIndependentlyVerified", false,
                "partialMessageId", number(store.getSetting(PREFIX + "partialId", "0")),
                "partialOffsetChars", number(store.getSetting(PREFIX + "partialOffset", "0")),
                "revisitPending", revisitPending(), "revisitAfterMessageId", number(store.getSetting(PREFIX + "revisit.after", "0")),
                "revisitThroughMessageId", number(store.getSetting(PREFIX + "revisit.through", "0")),
                "lastReviewCode", store.getSetting(PREFIX + "lastCode", "not_started"),
                "coverageMeaning", "source_ranges_acknowledged_by_model_not_verified_semantic_recall");
    }

    public boolean acknowledge(JSONObject expected, JSONObject review, boolean memoriesAccepted, int proposedMemories) {
        if (expected != null && "asset".equals(expected.optString("reviewMode")))
            return acknowledgeObservedAssets(expected);
        if (expected == null || expected.optJSONArray("sourceRanges") == null
                || expected.optJSONArray("sourceRanges").length() == 0) return false;
        if (!memoriesAccepted || review == null || !expected.optString("batchId").equals(review.optString("batchId")))
            return reject("missing_or_invalid_acknowledgement");
        if (!(review.opt("noMemoryNeeded") instanceof Boolean)) return reject("missing_review_decision");
        boolean none = review.optBoolean("noMemoryNeeded");
        if ((none && proposedMemories != 0) || (!none && proposedMemories == 0)
                || (none && review.optString("reason").trim().isEmpty())) return reject("inconsistent_review_decision");
        JSONArray actual = review.optJSONArray("sourceRanges"), wanted = expected.optJSONArray("sourceRanges");
        if (actual == null || actual.length() != wanted.length()) return reject("incomplete_source_coverage");
        for (int i = 0; i < wanted.length(); i++) {
            JSONObject a = actual.optJSONObject(i), w = wanted.optJSONObject(i);
            if (a == null || w == null || !exactLong(a, "messageId", w.optLong("messageId"))
                    || !exactLong(a, "offsetChars", w.optLong("offsetChars"))
                    || !exactLong(a, "nextOffsetChars", w.optLong("nextOffsetChars"))
                    || !w.optString("contentHash").equals(a.optString("contentHash"))) return reject("source_range_mismatch");
        }
        return store.withTransaction(db -> {
            boolean revisit = "revisit".equals(expected.optString("reviewMode"));
            String partialPrefix = revisit ? PREFIX + "revisit." : PREFIX;
            long old = expected.optLong("cursorStart");
            if ((revisit ? number(store.getSetting(PREFIX + "revisit.after", "0")) : store.getWatermark(WATERMARK)) != old
                    || (revisit && !expected.optString("revisitEpoch").equals(store.getSetting(PREFIX + "revisit.epoch", "")))
                    || number(store.getSetting(partialPrefix + "partialId", "0")) != expected.optLong("partialIdStart")
                    || number(store.getSetting(partialPrefix + "partialOffset", "0")) != expected.optLong("partialOffsetStart"))
                return reject("stale_batch");
            for (int i = 0; i < wanted.length(); i++) {
                JSONObject w = wanted.optJSONObject(i), source;
                try { source = store.readOriginalText(w.optLong("messageId"), w.optLong("offsetChars"),
                        (int) (w.optLong("nextOffsetChars") - w.optLong("offsetChars"))); }
                catch (IOException | IllegalArgumentException unavailable) { return reject("source_unavailable"); }
                if (!w.optString("contentHash").equals(source.optString("contentHash"))
                        || !w.optString("recordHash").equals(source.optString("recordHash"))
                        || !w.optString("text").equals(source.optString("text"))
                        || w.optLong("nextOffsetChars") != source.optLong("nextOffsetChars")) return reject("source_changed");
            }
            if (none && expected.optBoolean("noMemoryDecisionNeedsSecondPass")) {
                JSONObject prior = parse(store.getSetting(PREFIX + "noMemoryCandidate", "{}"));
                JSONObject second = review.optJSONObject("secondPass");
                if (!expected.optString("batchId").equals(prior.optString("batchId"))) {
                    store.putSetting(PREFIX + "noMemoryCandidate", object("batchId", expected.optString("batchId"),
                            "reason", safePrefix(review.optString("reason"), 2_000)).toString());
                    return reject("second_pass_required");
                }
                if (second == null || !Boolean.TRUE.equals(second.opt("reviewed"))
                        || second.optString("reason").trim().isEmpty()) return reject("second_pass_required");
            }
            if (revisit) store.putSetting(PREFIX + "revisit.after", String.valueOf(expected.optLong("cursorEnd")));
            else if (!store.advanceWatermark(WATERMARK, old, expected.optLong("cursorEnd"))) return reject("cursor_conflict");
            store.putSetting(partialPrefix + "partialId", String.valueOf(expected.optLong("partialIdEnd")));
            store.putSetting(partialPrefix + "partialOffset", String.valueOf(expected.optLong("partialOffsetEnd")));
            store.putSetting(PREFIX + "lastCode", "reviewed"); store.putSetting(PREFIX + "noMemoryCandidate", "{}");
            store.recordEvent("memory_reviewed", object("batchId", expected.optString("batchId"),
                    "sourceCount", wanted.length(), "cursorEnd", expected.optLong("cursorEnd"), "noMemoryNeeded", none,
                    "reviewMode", revisit ? "revisit" : "incremental", "semanticExtractionVerified", false));
            return true;
        });
    }

    /** Root calls this after saving immutable attachment metadata in the same owner-message transaction. */
    public boolean registerAssetSource(long messageId) {
        return store.withTransaction(db -> registerAssetSource(db, messageId));
    }

    public JSONObject discoverPendingAssets(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("Asset discovery limit must be 1..1,000");
        return store.withTransaction(db -> {
            int scanned = discoverAssetSources(db, limit);
            return object("scannedOwnerMessages", scanned, "assetDiscoveryCursor", number(store.getSetting(PREFIX + "assetDiscoveryCursor", "0")),
                    "assetsPending", assetPendingCount(db), "analysisIndependentlyVerified", false);
        });
    }

    public JSONObject assetCoverage(long messageId) {
        return store.withTransaction(db -> {
            JSONObject source = store.getMessage(messageId);
            if (source == null || !"user".equals(source.optString("role"))) throw new IllegalArgumentException("Unknown owner asset source");
            JSONObject coverage = loadAssetCoverage(source, messageId);
            updateAssetPendingIndex(db, messageId, coverage);
            put(coverage, "messageId", messageId); put(coverage, "analysisVerified", false); return coverage;
        });
    }

    /** Bare model review acknowledgements do not call or satisfy this receipt-gated path. */
    public JSONObject acceptAssetAnalysis(long messageId, String assetId, String sha256, String providerAnalysis) {
        JSONObject receipt = parse(readAssetReceipt(messageId, assetId, sha256));
        return acceptAssetAnalysis(messageId, assetId, sha256, providerAnalysis, receipt);
    }

    /** The receipt comes only from runtime-observed attachment upload and a request-bound provider response. */
    public JSONObject acceptAssetAnalysis(long messageId, String assetId, String sha256, String providerAnalysis, JSONObject trustedReceipt) {
        if (messageId <= 0 || assetId == null || assetId.isEmpty() || assetId.length() > 512
                || sha256 == null || !sha256.matches("[a-f0-9]{64}") || providerAnalysis == null
                || providerAnalysis.trim().isEmpty() || providerAnalysis.length() > 10_000_000)
            throw new IllegalArgumentException("An exact asset source and nonempty provider analysis are required");
        JSONObject receipt = trustedReceipt == null ? new JSONObject() : parse(trustedReceipt.toString());
        return store.withTransaction(db -> {
            JSONObject coverage = assetCoverage(messageId); JSONArray assets = coverage.optJSONArray("assets");
            JSONObject asset = findAsset(assets, assetId);
            if (asset == null || !sha256.equals(asset.optString("sha256"))) return assetReject("asset_source_mismatch");
            String requestId = receipt.optString("providerRequestId");
            if (!Boolean.TRUE.equals(receipt.opt("uploadObserved")) || !Boolean.TRUE.equals(receipt.opt("responseObserved"))
                    || receipt.optLong("sourceMessageId") != messageId || !assetId.equals(receipt.optString("assetId"))
                    || !sha256.equals(receipt.optString("sha256")) || !digest(providerAnalysis).equals(receipt.optString("analysisHash")))
                return assetReject("asset_analysis_receipt_missing");
            JSONObject providerRequest = null;
            try (Cursor request = db.rawQuery("SELECT id FROM requests WHERE external_id = ?", new String[]{requestId})) {
                if (request.moveToFirst()) providerRequest = store.getRequest(request.getLong(0));
            }
            if (providerRequest == null || !("accepted".equals(providerRequest.optString("status")) || "completed".equals(providerRequest.optString("status")))
                    || receipt.optString("providerResponseHash").isEmpty()
                    || !receipt.optString("providerResponseHash").equals(providerRequest.optString("raw_result_hash")))
                return assetReject("asset_provider_response_not_observed");
            JSONArray uploaded = parseArray(store.getSetting("attachmentMetadata:" + requestId, "[]"));
            JSONObject uploadedAsset = findAsset(uploaded, assetId);
            if (uploadedAsset == null || !sha256.equals(uploadedAsset.optString("sha256"))) return assetReject("asset_not_bound_to_uploaded_request");
            String analysisHash = digest(providerAnalysis);
            if (analysisHash.equals(asset.optString("analysisHash")) && ("provider_analyzed".equals(asset.optString("coverageStage"))
                    || "user_confirmed".equals(asset.optString("coverageStage"))))
                return object("ok", true, "cached", true, "stage", asset.optString("coverageStage"), "analysisVerified", false,
                        "analysisSourceMessageId", asset.optLong("analysisSourceMessageId"));
            JSONObject originalAnalysis = store.addMessage("assistant", providerAnalysis, requestId);
            long analysisSource = originalAnalysis.optLong("id");
            String memoryKey = assetMemoryKey(messageId, sha256, assetId);
            store.upsertMemoryWithSources(memoryKey, safePrefix(providerAnalysis, 24_000), "observation",
                    new JSONArray().put(object("type", "message", "id", messageId)).put(object("type", "message", "id", analysisSource)));
            store.putMemoryMetadata(memoryKey, object("provenance", "provider_asset_analysis", "evidenceState", "generated_observation",
                    "analysisVerified", false, "meaningIndependentlyVerified", false, "userConfirmed", false,
                    "assetRefs", new JSONArray().put(object("messageId", messageId, "assetId", assetId, "sha256", sha256)),
                    "analysisSourceMessageId", analysisSource, "analysisHash", analysisHash, "providerRequestId", requestId));
            put(asset, "coverageStage", "provider_analyzed"); put(asset, "analysisSourceMessageId", analysisSource);
            put(asset, "analysisHash", analysisHash); put(asset, "providerRequestId", requestId);
            put(asset, "analysisVerified", false); put(asset, "userConfirmed", false); put(asset, "analyzedAt", System.currentTimeMillis());
            put(asset, "analysisReceiptKey", assetReceiptKey(messageId, sha256, assetId));
            put(coverage, "stage", anyPendingAsset(coverage) ? "partially_analyzed" : "provider_analyzed");
            put(coverage, "analysisVerified", false); store.putSetting("assetCoverage:" + messageId, coverage.toString());
            store.putSetting(assetReceiptKey(messageId, sha256, assetId), receipt.toString()); registerAssetSource(db, messageId);
            store.recordEvent("asset_provider_analysis_observed", object("messageId", messageId, "assetId", assetId, "sha256", sha256,
                    "providerRequestId", requestId, "analysisSourceMessageId", analysisSource, "analysisHash", analysisHash,
                    "analysisIndependentlyVerified", false, "originalImageFullyUnderstood", false));
            return object("ok", true, "stage", "provider_analyzed", "analysisVerified", false,
                    "analysisSourceMessageId", analysisSource, "memoryKey", memoryKey, "assetsPending", assetPendingCount(db));
        });
    }

    /** Record a real owner confirmation as another source, without inventing the semantic extent of that confirmation. */
    public JSONObject confirmAssetAnalysis(long messageId, String assetId, String sha256, long confirmationMessageId) {
        return store.withTransaction(db -> {
            JSONObject confirmation = store.getMessage(confirmationMessageId);
            if (confirmation == null || !"user".equals(confirmation.optString("role"))) throw new IllegalArgumentException("Actual owner confirmation source required");
            JSONObject coverage = assetCoverage(messageId), asset = findAsset(coverage.optJSONArray("assets"), assetId);
            if (asset == null || !sha256.equals(asset.optString("sha256")) || asset.optLong("analysisSourceMessageId") <= 0
                    || confirmationMessageId <= asset.optLong("analysisSourceMessageId"))
                return assetReject("asset_analysis_not_available_for_confirmation");
            put(asset, "coverageStage", "user_confirmed"); put(asset, "userConfirmed", true);
            put(asset, "confirmationMessageId", confirmationMessageId); put(asset, "analysisVerified", false);
            put(coverage, "stage", anyPendingAsset(coverage) ? "partially_analyzed" : allUserConfirmed(coverage) ? "user_confirmed" : "provider_analyzed");
            put(coverage, "analysisVerified", false); store.putSetting("assetCoverage:" + messageId, coverage.toString()); registerAssetSource(db, messageId);
            store.recordEvent("asset_owner_confirmation_recorded", object("messageId", messageId, "assetId", assetId,
                    "confirmationMessageId", confirmationMessageId, "confirmationMeaningIndependentlyVerified", false));
            return object("ok", true, "stage", "user_confirmed", "analysisVerified", false, "confirmationMessageId", confirmationMessageId);
        });
    }

    public boolean markAssetNeedsReanalysis(long messageId, String assetId, String reason) {
        if (reason == null || reason.trim().isEmpty()) throw new IllegalArgumentException("A reanalysis reason is required");
        return store.withTransaction(db -> {
            JSONObject coverage = assetCoverage(messageId), asset = findAsset(coverage.optJSONArray("assets"), assetId);
            if (asset == null) return false;
            put(asset, "coverageStage", "needs_reanalysis"); put(asset, "reanalysisReason", safePrefix(reason, 512));
            if (asset.optLong("confirmationMessageId") > 0) put(asset, "priorConfirmationMessageId", asset.optLong("confirmationMessageId"));
            put(asset, "userConfirmed", false);
            put(coverage, "stage", "needs_reanalysis"); store.putSetting("assetCoverage:" + messageId, coverage.toString());
            registerAssetSource(db, messageId); store.recordEvent("asset_reanalysis_requested", object("messageId", messageId, "assetId", assetId, "reason", safePrefix(reason, 512)));
            return true;
        });
    }

    private int discoverAssetSources(SQLiteDatabase db, int limit) {
        long cursor = number(store.getSetting(PREFIX + "assetDiscoveryCursor", "0"));
        JSONArray sources = store.listUserMessagesAfter(cursor, limit); long last = cursor;
        for (int i = 0; i < sources.length(); i++) { last = sources.optJSONObject(i).optLong("id"); registerAssetSource(db, last); }
        if (last != cursor) store.putSetting(PREFIX + "assetDiscoveryCursor", String.valueOf(last));
        return sources.length();
    }

    private boolean registerAssetSource(SQLiteDatabase db, long messageId) {
        JSONObject source = store.getMessage(messageId);
        if (source == null || !"user".equals(source.optString("role"))) throw new IllegalArgumentException("Unknown owner asset source");
        JSONObject coverage = loadAssetCoverage(source, messageId);
        return updateAssetPendingIndex(db, messageId, coverage);
    }

    private boolean updateAssetPendingIndex(SQLiteDatabase db, long messageId, JSONObject coverage) {
        int pendingCount = pendingAssetCount(coverage);
        String existing = store.getSetting(assetPendingKey(messageId), "");
        int previousCount = existing.isEmpty() ? 0 : parse(existing).optInt("pendingAssetCount", 1);
        long currentTotal = assetPendingCount(db);
        if (pendingCount > 0) store.putSetting(assetPendingKey(messageId), object("messageId", messageId, "pendingAssetCount", pendingCount).toString());
        else db.delete("settings", "setting_key = ?", new String[]{assetPendingKey(messageId)});
        store.putSetting(PREFIX + "assetsPendingCount", String.valueOf(Math.max(0, currentTotal - previousCount + pendingCount)));
        return pendingCount > 0;
    }

    /** Bounded local integrity audit also revisits terminal coverage; it performs no provider calls. */
    private void auditAssetSources(SQLiteDatabase db, int limit) {
        long cursor = number(store.getSetting(PREFIX + "assetIntegrityAuditCursor", "0"));
        if (cursor >= store.lastUserMessageId()) cursor = 0;
        JSONArray sources = store.listUserMessagesAfter(cursor, limit); long last = cursor;
        for (int i = 0; i < sources.length(); i++) { last = sources.optJSONObject(i).optLong("id"); registerAssetSource(db, last); }
        if (sources.length() > 0) store.putSetting(PREFIX + "assetIntegrityAuditCursor", String.valueOf(last));
    }
    private boolean acknowledgeObservedAssets(JSONObject expected) {
        return store.withTransaction(db -> {
            long id = expected.optLong("assetSourceMessageId"); JSONArray ranges = expected.optJSONArray("sourceRanges");
            if (id <= 0 || ranges == null || ranges.length() != 1) return reject("asset_analysis_receipt_required");
            JSONObject range = ranges.optJSONObject(0); JSONArray refs = range == null ? null : range.optJSONArray("assetRefs");
            if (range == null || range.optLong("messageId") != id || refs == null || refs.length() == 0) return reject("asset_analysis_receipt_required");
            JSONObject coverage;
            try { coverage = assetCoverage(id); }
            catch (IllegalArgumentException unreadable) { return reject("asset_review_source_unavailable"); }
            JSONArray assets = coverage.optJSONArray("assets");
            if (pendingAssetCount(coverage) > 0 || assets == null || assets.length() != refs.length()) return reject("asset_analysis_receipt_required");
            for (int i = 0; i < refs.length(); i++) {
                JSONObject ref = refs.optJSONObject(i); JSONObject asset = ref == null ? null : findAsset(assets, ref.optString("assetId"));
                if (asset == null || !asset.optString("sha256").equals(ref.optString("sha256")) || !analysisProofExists(id, asset))
                    return reject("asset_analysis_receipt_required");
            }
            try {
                JSONObject original = store.readOriginalText(id, range.optLong("offsetChars"),
                        (int) (range.optLong("nextOffsetChars") - range.optLong("offsetChars")));
                if (!range.optString("contentHash").equals(original.optString("contentHash"))
                        || !range.optString("recordHash").equals(original.optString("recordHash"))
                        || !range.optString("text").equals(original.optString("text"))) return reject("asset_review_source_changed");
            } catch (IOException | IllegalArgumentException unavailable) { return reject("asset_review_source_unavailable"); }
            store.putSetting(PREFIX + "lastCode", "asset_receipts_verified");
            store.recordEvent("asset_review_receipts_verified", object("messageId", id, "assetCount", refs.length(),
                    "batchId", expected.optString("batchId"), "textCursorChanged", false,
                    "assetStagesChangedByAcknowledgement", false, "analysisIndependentlyVerified", false));
            return true;
        });
    }

    private void refreshPendingSources(SQLiteDatabase db, int limit) {
        java.util.List<Long> pending = new java.util.ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT value FROM settings WHERE setting_key >= ? AND setting_key < ? ORDER BY setting_key ASC LIMIT ?", new String[]{ASSET_PENDING, ASSET_PENDING_END, String.valueOf(limit)})) {
            while (rows.moveToNext()) pending.add(parse(rows.getString(0)).optLong("messageId"));
        }
        for (long id : pending) {
            JSONObject source = store.getMessage(id);
            if (source != null && "user".equals(source.optString("role"))) registerAssetSource(db, id);
            // An orphaned pending source is still an integrity failure, not evidence that it was analyzed.
        }
    }

    private JSONObject loadAssetCoverage(JSONObject source, long messageId) {
        String serialized = store.getSetting("assetCoverage:" + messageId, "");
        String originalMetadata = store.getSetting("attachmentMetadata:" + source.optString("request_id"), "");
        JSONArray originals = strictAssetArray(originalMetadata);
        JSONObject coverage = strictCoverage(serialized);
        String failure = "";
        if (coverage == null && !serialized.isEmpty()) failure = "coverage_json_unreadable";
        else if (coverage != null) {
            JSONArray assets = coverage.optJSONArray("assets");
            if (assets == null || (assets.length() == 0 && coverage.optInt("pendingUnknownAssets") <= 0)) failure = "coverage_assets_missing";
            else if (assets != null) for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null || asset.optString("originalAssetId", asset.optString("id")).isEmpty()) { failure = "coverage_asset_identity_invalid"; break; }
                String id = asset.optString("originalAssetId", asset.optString("id")); JSONObject original = findAsset(originals, id);
                if (originals != null && originals.length() > 0 && (original == null || !original.optString("sha256").equals(asset.optString("sha256")))) { failure = "coverage_source_sha_mismatch"; break; }
                if (("provider_analyzed".equals(asset.optString("coverageStage")) || "user_confirmed".equals(asset.optString("coverageStage")))
                        && !analysisProofExists(messageId, asset)) { failure = "provider_analysis_proof_missing"; break; }
            }
            if (failure.isEmpty() && originals != null && originals.length() > 0 && assets != null && assets.length() != originals.length()) failure = "coverage_asset_count_mismatch";
        }
        String pendingSerialized = store.getSetting(assetPendingKey(messageId), "");
        if (coverage != null && failure.isEmpty()) return coverage;
        boolean hasOriginals = originals != null && originals.length() > 0;
        if (serialized.isEmpty() && !hasOriginals && pendingSerialized.isEmpty() && originalMetadata.isEmpty())
            return object("stage", "not_applicable", "assets", new JSONArray(), "analysisVerified", false);
        if (failure.isEmpty()) failure = serialized.isEmpty() ? "coverage_missing" : "coverage_assets_missing";
        JSONArray recovered = hasOriginals ? originals : coverage == null ? new JSONArray() : coverage.optJSONArray("assets");
        if (recovered == null) recovered = new JSONArray();
        JSONArray safe = new JSONArray(); boolean immutableIdentity = recovered.length() > 0;
        for (int i = 0; i < recovered.length(); i++) {
            JSONObject asset = recovered.optJSONObject(i); if (asset == null) { immutableIdentity = false; continue; }
            JSONObject raw = parse(asset.toString()); put(raw, "coverageStage", "raw_saved"); put(raw, "analysisVerified", false); put(raw, "userConfirmed", false);
            raw.remove("analysisSourceMessageId"); raw.remove("analysisHash"); raw.remove("providerRequestId"); raw.remove("confirmationMessageId"); raw.remove("analysisReceiptKey");
            String identity = raw.optString("originalAssetId", raw.optString("id")), sha = raw.optString("sha256");
            immutableIdentity &= Boolean.TRUE.equals(raw.opt("immutable")) && sha.matches("[a-f0-9]{64}")
                    && identity.startsWith(".attachment-originals/" + sha + "."); safe.put(raw);
        }
        JSONObject repaired = object("stage", serialized.isEmpty() ? "needs_reanalysis" : "raw_saved", "assets", safe,
                "analysisVerified", false, "sourceIntegrity", failure, "originalIdentityMetadataVerified", immutableIdentity,
                "pendingUnknownAssets", safe.length() == 0 ? Math.max(1, parse(pendingSerialized).optInt("pendingAssetCount", 1)) : 0,
                "legacySourceIdentityUnverified", !immutableIdentity, "recoveredAt", System.currentTimeMillis());
        if (!serialized.isEmpty()) {
            JSONObject preserved = store.addMessage("system", object("assetMessageId", messageId, "rawCoverage", serialized).toString(), null);
            put(repaired, "previousCoverageSourceMessageId", preserved.optLong("id")); put(repaired, "previousCoverageHash", digest(serialized));
        }
        store.putSetting("assetCoverage:" + messageId, repaired.toString());
        store.recordEvent("asset_coverage_integrity_recovered", object("messageId", messageId, "code", failure,
                "sourceIdentitiesRecovered", safe.length(), "rawOriginalsRetained", true, "semanticAnalysisRestored", false));
        return repaired;
    }

    private boolean analysisProofExists(long messageId, JSONObject asset) {
        JSONObject analysis = store.getMessage(asset.optLong("analysisSourceMessageId"));
        String sha = asset.optString("sha256"), hash = asset.optString("analysisHash"), assetId = asset.optString("originalAssetId", asset.optString("id"));
        if (analysis == null || !"assistant".equals(analysis.optString("role")) || hash.isEmpty()
                || !hash.equals(analysis.optString("raw_content_hash"))) return false;
        JSONObject receipt = strictCoverage(readAssetReceipt(messageId, assetId, sha));
        return receipt != null && Boolean.TRUE.equals(receipt.opt("uploadObserved")) && Boolean.TRUE.equals(receipt.opt("responseObserved"))
                && receipt.optLong("sourceMessageId") == messageId && assetId.equals(receipt.optString("assetId"))
                && sha.equals(receipt.optString("sha256")) && hash.equals(receipt.optString("analysisHash"))
                && asset.optString("providerRequestId").equals(receipt.optString("providerRequestId"))
                && asset.optString("providerRequestId").equals(analysis.optString("request_id"))
                && receipt.optString("providerResponseHash").matches("[a-f0-9]{64}");
    }
    private static JSONObject strictCoverage(String serialized) {
        if (serialized == null || serialized.isEmpty() || !boundedJsonDepth(serialized)) return null;
        try {
            JSONTokener tokener = new JSONTokener(serialized); Object value = tokener.nextValue();
            return value instanceof JSONObject && tokener.nextClean() == 0 ? (JSONObject) value : null;
        } catch (Exception invalid) { return null; }
    }
    private static JSONArray strictAssetArray(String serialized) {
        if (serialized == null || serialized.isEmpty()) return new JSONArray();
        if (!boundedJsonDepth(serialized)) return null;
        try {
            JSONTokener tokener = new JSONTokener(serialized); Object value = tokener.nextValue();
            return value instanceof JSONArray && tokener.nextClean() == 0 ? (JSONArray) value : null;
        } catch (Exception invalid) { return null; }
    }

    private static boolean boundedJsonDepth(String serialized) {
        boolean quoted = false, escaped = false; int depth = 0;
        for (int i = 0; i < serialized.length(); i++) {
            char value = serialized.charAt(i);
            if (quoted) {
                if (escaped) escaped = false; else if (value == '\\') escaped = true; else if (value == '"') quoted = false;
            } else if (value == '"') quoted = true;
            else if (value == '[' || value == '{') { if (++depth > 64) return false; }
            else if (value == ']' || value == '}') { if (--depth < 0) return false; }
        }
        return true;
    }
    private JSONObject prepareAssetBatch(SQLiteDatabase db, long cursor, long partialId, long partialOffset, int maxSerializedChars) {
        long id = 0;
        try (Cursor first = db.rawQuery("SELECT value FROM settings WHERE setting_key >= ? AND setting_key < ? ORDER BY setting_key ASC LIMIT 1", new String[]{ASSET_PENDING, ASSET_PENDING_END})) {
            if (first.moveToFirst()) id = parse(first.getString(0)).optLong("messageId");
        }
        JSONArray ranges = new JSONArray(), blocked = new JSONArray();
        try {
            JSONObject source = store.getMessage(id), caption = store.readOriginalText(id, 0, 1_000);
            JSONObject range = object("messageId", id, "offsetChars", caption.optLong("offsetChars"), "nextOffsetChars", caption.optLong("nextOffsetChars"),
                    "totalChars", caption.optLong("totalChars"), "contentHash", caption.optString("contentHash"), "recordHash", caption.optString("recordHash"),
                    "createdAt", source.optLong("created_at"), "text", caption.optString("text"), "reviewScope", "asset_caption_context_only_text_cursor_unchanged");
            attachAssetContext(range, id); ranges.put(range);
        } catch (IOException | IllegalArgumentException unreadable) { blocked.put(object("messageId", id, "code", "asset_source_unavailable")); }
        JSONObject batch = object("reviewMode", "asset", "cursorStart", cursor, "cursorEnd", cursor,
                "partialIdStart", partialId, "partialIdEnd", partialId, "partialOffsetStart", partialOffset, "partialOffsetEnd", partialOffset,
                "sourceRanges", ranges, "blockedSources", blocked, "assetSourceMessageId", id, "assetsPending", assetPendingCount(db),
                "assetAnalysisRequiresObservedUploadReceipt", true, "analysisIndependentlyVerified", false,
                "coverageMeaning", "caption_context_is_not_image_analysis_use_memory.asset.analyze");
        fitToSerializedBudget(batch, maxSerializedChars - 128); put(batch, "batchId", coverageDigest(batch)); return batch;
    }

    private void attachAssetContext(JSONObject range, long messageId) {
        JSONObject coverage = assetCoverage(messageId); JSONArray assets = coverage.optJSONArray("assets");
        put(range, "assetSourceIntegrity", coverage.optString("sourceIntegrity", "stored_metadata"));
        if (coverage.optInt("pendingUnknownAssets") > 0) {
            put(range, "assetCoverageStage", "needs_reanalysis"); put(range, "assetsNeedAnalysis", true);
            put(range, "pendingUnknownAssets", coverage.optInt("pendingUnknownAssets"));
        }
        if (assets == null || assets.length() == 0) return;
        JSONArray refs = new JSONArray();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i); if (asset == null) continue;
            JSONObject ref = object("assetId", asset.optString("originalAssetId", asset.optString("id")), "sha256", asset.optString("sha256"),
                    "coverageStage", asset.optString("coverageStage", "raw_saved"), "analysisVerified", false);
            if (asset.has("analysisSourceMessageId")) put(ref, "analysisSourceMessageId", asset.optLong("analysisSourceMessageId"));
            put(ref, "name", safePrefix(asset.optString("displayName", asset.optString("name")), 120)); refs.put(ref);
        }
        put(range, "assetRefs", refs); put(range, "assetCoverageStage", coverage.optString("stage", "raw_saved"));
        put(range, "assetsNeedAnalysis", anyPendingAsset(coverage)); put(range, "assetFactsIndependentlyVerified", false);
    }

    private long assetReviewCursor() {
        synchronized (store) {
            long cursor = number(store.getSetting(PREFIX + "assetDiscoveryCursor", "0"));
            try (Cursor oldest = store.getReadableDatabase().rawQuery("SELECT value FROM settings WHERE setting_key >= ? AND setting_key < ? ORDER BY setting_key ASC LIMIT 1", new String[]{ASSET_PENDING, ASSET_PENDING_END})) {
                if (oldest.moveToFirst()) cursor = Math.min(cursor, Math.max(0, parse(oldest.getString(0)).optLong("messageId") - 1));
            }
            return cursor;
        }
    }
    private static long assetPendingCount(SQLiteDatabase db) {
        try (Cursor aggregate = db.rawQuery("SELECT value FROM settings WHERE setting_key = ?", new String[]{PREFIX + "assetsPendingCount"})) {
            if (aggregate.moveToFirst()) {
                try { return Math.max(0, Long.parseLong(aggregate.getString(0))); }
                catch (NumberFormatException invalid) { /* Rebuild this derived aggregate from its bounded pending-only index. */ }
            }
        }
        long total = 0;
        try (Cursor pending = db.rawQuery("SELECT value FROM settings WHERE setting_key >= ? AND setting_key < ?", new String[]{ASSET_PENDING, ASSET_PENDING_END})) {
            while (pending.moveToNext()) total += Math.max(0, parse(pending.getString(0)).optInt("pendingAssetCount", 1));
        }
        return total;
    }
    private static long assetPendingSourceCount(SQLiteDatabase db) {
        try (Cursor count = db.rawQuery("SELECT COUNT(*) FROM settings WHERE setting_key >= ? AND setting_key < ?", new String[]{ASSET_PENDING, ASSET_PENDING_END})) { return count.moveToFirst() ? count.getLong(0) : 0; }
    }
    private static int pendingAssetCount(JSONObject coverage) {
        JSONArray assets = coverage.optJSONArray("assets"); int count = Math.max(0, coverage.optInt("pendingUnknownAssets"));
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !("provider_analyzed".equals(asset.optString("coverageStage")) || "user_confirmed".equals(asset.optString("coverageStage")))) count++;
        }
        return count;
    }
    private static boolean anyPendingAsset(JSONObject coverage) { return pendingAssetCount(coverage) > 0; }
    private static boolean allUserConfirmed(JSONObject coverage) {
        JSONArray assets = coverage.optJSONArray("assets"); if (assets == null || assets.length() == 0) return false;
        for (int i = 0; i < assets.length(); i++) if (!"user_confirmed".equals(assets.optJSONObject(i).optString("coverageStage"))) return false; return true;
    }
    private static JSONObject findAsset(JSONArray assets, String id) {
        for (int i = 0; assets != null && i < assets.length(); i++) { JSONObject asset = assets.optJSONObject(i); if (asset != null && id.equals(asset.optString("originalAssetId", asset.optString("id")))) return asset; } return null;
    }
    private JSONObject assetReject(String code) { store.recordEvent("asset_analysis_rejected", object("code", code)); return object("ok", false, "code", code, "analysisVerified", false); }
    private static String assetPendingKey(long id) { return ASSET_PENDING + String.format(Locale.ROOT, "%019d", id); }
    private static String assetMemoryKey(long id, String sha, String assetId) { return "asset-analysis:" + id + ":" + sha + ":" + digest(assetId); }
    private static String assetReceiptKey(long id, String sha, String assetId) { return "assetAnalysisReceipt:" + id + ":" + sha + ":" + digest(assetId); }
    private String readAssetReceipt(long id, String assetId, String sha) {
        String current = store.getSetting(assetReceiptKey(id, sha, assetId), "");
        return current.isEmpty() ? store.getSetting("assetAnalysisReceipt:" + id + ":" + sha, "") : current;
    }
    private static JSONArray parseArray(String value) { try { return new JSONArray(value); } catch (Exception invalid) { return new JSONArray(); } }

    private void fitToSerializedBudget(JSONObject batch, int cap) {
        JSONArray ranges = batch.optJSONArray("sourceRanges");
        if (batch.toString().length() <= cap) return;
        // Keep source pointers for omitted context so the model can deliberately read it.
        for (int i = 0; i < ranges.length(); i++) {
            JSONObject range = ranges.optJSONObject(i); JSONArray neighbors = range.optJSONArray("contextMessages");
            JSONArray assets = range.optJSONArray("assetRefs");
            if (assets != null) for (int a = 0; a < assets.length(); a++) assets.optJSONObject(a).remove("name");
            if (neighbors != null) for (int n = 0; n < neighbors.length(); n++) {
                JSONObject neighbor = neighbors.optJSONObject(n); String text = neighbor.optString("text");
                if (text.length() > 120) {
                    boolean tail = "tail".equals(neighbor.optString("excerptPosition"));
                    String kept = tail ? safeSuffix(text, 120) : safePrefix(text, 120);
                    long offset = neighbor.optLong("offsetChars") + (tail ? text.length() - kept.length() : 0);
                    put(neighbor, "text", kept); put(neighbor, "offsetChars", offset); put(neighbor, "nextOffsetChars", offset + kept.length());
                    put(neighbor, "truncatedForBudget", true);
                }
            }
            boundOverlap(range, "contextBefore", true, 128); boundOverlap(range, "contextAfter", false, 128);
            put(range, "contextLimitedByBudget", true);
        }
        while (batch.toString().length() > cap && ranges.length() > 1) ranges.remove(ranges.length() - 1);
        if (batch.toString().length() > cap && ranges.length() == 1) {
            JSONObject range = ranges.optJSONObject(0);
            JSONArray neighbors = range.optJSONArray("contextMessages");
            if (neighbors != null) for (int i = 0; i < neighbors.length(); i++) {
                JSONObject neighbor = neighbors.optJSONObject(i); neighbor.remove("text"); put(neighbor, "textOmittedForBudget", true);
            }
            boundOverlap(range, "contextBefore", true, 32); boundOverlap(range, "contextAfter", false, 32);
            while (batch.toString().length() > cap && !range.optString("text").isEmpty()) {
                try {
                    JSONObject smaller = store.readOriginalText(range.optLong("messageId"), range.optLong("offsetChars"),
                            Math.max(1, range.optString("text").length() / 2));
                    if ((smaller.optString("text").isEmpty() && !smaller.optBoolean("complete"))
                            || smaller.optString("text").length() >= range.optString("text").length()) break;
                    put(range, "text", smaller.optString("text")); put(range, "nextOffsetChars", smaller.optLong("nextOffsetChars"));
                    JSONObject after = overlap(range.optLong("messageId"), smaller.optLong("nextOffsetChars"),
                            Math.min(smaller.optLong("totalChars"), smaller.optLong("nextOffsetChars") + 32));
                    if (after == null) range.remove("contextAfter"); else put(range, "contextAfter", after);
                } catch (IOException | IllegalArgumentException unreadable) {
                    batch.optJSONArray("blockedSources").put(object("messageId", range.optLong("messageId"), "code", "source_unavailable"));
                    ranges.remove(0); store.putSetting(PREFIX + "lastCode", "source_unavailable"); break;
                }
            }
        }
        if (batch.toString().length() > cap) {
            while (ranges.length() > 0) ranges.remove(ranges.length() - 1);
            put(batch, "budgetTooSmallForReview", true);
        }
        recalculateProgress(batch);
    }

    private static void boundOverlap(JSONObject range, String key, boolean tail, int maximum) {
        JSONObject overlap = range.optJSONObject(key); if (overlap == null) return;
        String original = overlap.optString("text"), kept = tail ? safeSuffix(original, maximum) : safePrefix(original, maximum);
        if (kept.length() == original.length()) return;
        long offset = overlap.optLong("offsetChars") + (tail ? original.length() - kept.length() : 0);
        put(overlap, "text", kept); put(overlap, "offsetChars", offset); put(overlap, "nextOffsetChars", offset + kept.length());
        put(overlap, "truncatedForBudget", true);
    }

    private static void recalculateProgress(JSONObject batch) {
        if ("asset".equals(batch.optString("reviewMode"))) return;
        long cursor = batch.optLong("cursorStart"), partialId = 0, partialOffset = 0; boolean risk = false;
        JSONArray ranges = batch.optJSONArray("sourceRanges");
        for (int i = 0; i < ranges.length(); i++) {
            JSONObject range = ranges.optJSONObject(i);
            JSONObject before = range.optJSONObject("contextBefore"), after = range.optJSONObject("contextAfter");
            boolean rangeRisk = range.optBoolean("noMemoryDecisionNeedsSecondPass")
                    || sensitiveOrDurableCue((before == null ? "" : before.optString("text"))
                    + range.optString("text") + (after == null ? "" : after.optString("text")));
            put(range, "noMemoryDecisionNeedsSecondPass", rangeRisk); risk |= rangeRisk;
            if (range.optLong("nextOffsetChars") >= range.optLong("totalChars")) cursor = range.optLong("messageId");
            else { partialId = range.optLong("messageId"); partialOffset = range.optLong("nextOffsetChars"); break; }
        }
        put(batch, "cursorEnd", cursor); put(batch, "partialIdEnd", partialId); put(batch, "partialOffsetEnd", partialOffset);
        put(batch, "noMemoryDecisionNeedsSecondPass", risk);
    }

    private static String safePrefix(String text, int count) {
        int end = Math.max(0, Math.min(text.length(), count));
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end);
    }
    private static String safeSuffix(String text, int count) {
        int start = Math.max(0, text.length() - Math.max(0, count));
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start)) && Character.isHighSurrogate(text.charAt(start - 1))) start++;
        return text.substring(start);
    }

    private boolean revisitPending() {
        return number(store.getSetting(PREFIX + "revisit.after", "0")) < number(store.getSetting(PREFIX + "revisit.through", "0"));
    }

    private JSONObject overlap(long id, long start, long end) {
        if (end <= start) return null;
        try {
            JSONObject original;
            try { original = store.readOriginalText(id, start, (int) (end - start)); }
            catch (IllegalArgumentException splitPair) { original = store.readOriginalText(id, Math.max(0, start - 1), (int) (end - Math.max(0, start - 1))); }
            return object("messageId", id, "offsetChars", original.optLong("offsetChars"),
                    "nextOffsetChars", original.optLong("nextOffsetChars"), "contentHash", original.optString("contentHash"),
                    "text", original.optString("text"), "reviewScope", "context_only_not_acknowledged");
        } catch (IOException | IllegalArgumentException unreadable) { return object("messageId", id, "reviewScope", "context_only_not_acknowledged", "code", "context_unavailable"); }
    }

    private JSONArray neighboringContext(SQLiteDatabase db, long id, int budget) {
        JSONArray result = new JSONArray();
        long[] ids = new long[3]; int count = 0;
        try (Cursor previous = db.rawQuery("SELECT id FROM messages WHERE id < ? ORDER BY id DESC LIMIT 2", new String[]{String.valueOf(id)})) {
            while (previous.moveToNext()) ids[count++] = previous.getLong(0);
        }
        for (int i = count - 1; i >= 0 && budget > 0; i--) budget -= addNeighbor(result, ids[i], budget, true);
        try (Cursor next = db.rawQuery("SELECT id FROM messages WHERE id > ? ORDER BY id ASC LIMIT 1", new String[]{String.valueOf(id)})) {
            if (budget > 0 && next.moveToFirst()) addNeighbor(result, next.getLong(0), budget, false);
        }
        return result;
    }

    private int addNeighbor(JSONArray result, long id, int budget, boolean previous) {
        JSONObject message = store.getMessage(id);
        if (message == null) return 0;
        try {
            int count = Math.min(700, budget);
            long total = message.optLong("raw_char_count", message.optString("text").length());
            long offset = previous ? Math.max(0, total - count) : 0;
            JSONObject excerpt;
            try { excerpt = store.readOriginalText(id, offset, count); }
            catch (IllegalArgumentException splitPair) { excerpt = store.readOriginalText(id, Math.max(0, offset - 1), count + 1); }
            result.put(object("messageId", id, "role", message.optString("role"), "text", excerpt.optString("text"),
                    "offsetChars", excerpt.optLong("offsetChars"), "nextOffsetChars", excerpt.optLong("nextOffsetChars"),
                    "totalChars", excerpt.optLong("totalChars"), "contentHash", excerpt.optString("contentHash"),
                    "createdAt", message.optLong("created_at"), "excerptPosition", previous ? "tail" : "prefix",
                    "reviewScope", "context_only_not_acknowledged"));
            return excerpt.optString("text").length();
        } catch (IOException | IllegalArgumentException unreadable) { return 0; }
    }

    private static boolean sensitiveOrDurableCue(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return value.matches("(?s).*(알레르기|알러지|복용|질환|약물|임신|건강|장애|기억해|기억하|잊지|정정|수정해|앞으로|항상|절대|이사|생일|생년|좋아해|싫어해|선호|취향|아니라|allerg|diagnos|medicat|remember|never forget|from now|correction).*" );
    }
    private boolean reject(String code) {
        store.putSetting(PREFIX + "lastCode", code); store.recordEvent("memory_review_incomplete", object("code", code)); return false;
    }
    private static boolean exactLong(JSONObject object, String key, long expected) {
        Object value = object.opt(key);
        return value instanceof Number && ((Number) value).longValue() == expected && ((Number) value).doubleValue() == expected;
    }
    private static long number(String value) { try { return Math.max(0, Long.parseLong(value)); } catch (Exception invalid) { return 0; } }
    private static JSONObject parse(String value) { try { return new JSONObject(value); } catch (Exception invalid) { return new JSONObject(); } }
    /** Context may grow between passes; only the exact acknowledged source coverage identifies a batch. */
    private static String coverageDigest(JSONObject batch) {
        JSONObject signature = object("reviewMode", batch.optString("reviewMode"), "cursorStart", batch.optLong("cursorStart"),
                "partialIdStart", batch.optLong("partialIdStart"), "partialOffsetStart", batch.optLong("partialOffsetStart"),
                "cursorEnd", batch.optLong("cursorEnd"), "partialIdEnd", batch.optLong("partialIdEnd"),
                "partialOffsetEnd", batch.optLong("partialOffsetEnd"), "revisitEpoch", batch.optString("revisitEpoch"));
        JSONArray ranges = new JSONArray(), sources = batch.optJSONArray("sourceRanges");
        for (int i = 0; sources != null && i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            ranges.put(object("messageId", source.optLong("messageId"), "offsetChars", source.optLong("offsetChars"),
                    "nextOffsetChars", source.optLong("nextOffsetChars"), "contentHash", source.optString("contentHash"),
                    "recordHash", source.optString("recordHash")));
        }
        put(signature, "sourceRanges", ranges); return digest(signature.toString());
    }
    private static String digest(String value) {
        try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b & 255)); return hex.toString();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static JSONObject object(Object... pairs) { JSONObject result = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) put(result, String.valueOf(pairs[i]), pairs[i + 1]); return result; }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception failure) { throw new IllegalStateException(failure); } }
}
