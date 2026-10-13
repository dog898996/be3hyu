package com.hoyoung.assistant.memory.local;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.hoyoung.assistant.memory.SemanticEncoder;
import org.json.JSONArray;
import org.json.JSONObject;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Actual on-device neural sentence embeddings. No phone text is sent to a service. */
public final class LocalSemanticEncoder implements SemanticEncoder, AutoCloseable {
    private final Context context;
    private final File directory;
    private final JSONObject manifest;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private volatile String phase = "not_prepared", failure = "";
    private volatile long downloaded, total;
    private volatile boolean preparing, ready;
    private volatile Runnable listener;
    private OrtEnvironment environment;
    private OrtSession session;
    private BertWordPieceTokenizer tokenizer;
    private float[][] projection;
    private float[] bias;

    public LocalSemanticEncoder(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(this.context.getFilesDir(), "models/sentence-memory-v1");
        try (InputStream input = this.context.getAssets().open("semantic-model.json")) {
            manifest = new JSONObject(read(input, 64 * 1024));
            JSONArray files = manifest.getJSONArray("files");
            for (int i = 0; i < files.length(); i++) total += files.getJSONObject(i).getLong("size");
        } catch (Exception error) { throw new IllegalStateException("Semantic model manifest is unavailable", error); }
    }
    @Override public String modelId() { return manifest.optString("modelId") + "|bert-wordpiece-cased-v1|mean-dense-tanh512|query-overlap110-v1"; }
    @Override public int dimensions() { return 512; }
    @Override public boolean isReady() { return ready; }
    @Override public boolean isNeural() { return true; }
    public void setListener(Runnable listener) { this.listener = listener; }
    public JSONObject state() { return object("ready", ready, "phase", phase, "code", failure,
            "downloadedBytes", downloaded, "totalBytes", total, "modelId", modelId(),
            "dimensions", dimensions(), "textLeavesDeviceForEmbedding", false); }

    public void prepareAsync() {
        if (ready || preparing) return;
        synchronized (this) { if (ready || preparing) return; preparing = true; }
        loader.execute(() -> {
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("model_directory_unavailable");
                phase = "verifying"; downloaded = 0; changed();
                JSONArray files = manifest.getJSONArray("files");
                for (int i = 0; i < files.length(); i++) {
                    JSONObject specification = files.getJSONObject(i);
                    String name = specification.getString("name");
                    if (!name.matches("[A-Za-z0-9_.-]+")) throw new IllegalStateException("invalid_manifest_name");
                    File destination = new File(directory, name);
                    if (!verified(destination, specification)) {
                        if (!unmetered()) { phase = "waiting_for_unmetered_network"; changed(); return; }
                        if (directory.getUsableSpace() < specification.getLong("size") + 100L * 1024 * 1024)
                            throw new IllegalStateException("model_storage_full");
                        download(destination, specification);
                    }
                    downloaded += specification.getLong("size"); changed();
                }
                phase = "loading"; changed();
                synchronized (this) { loadVerifiedModel(); ready = true; }
                phase = "ready"; failure = ""; changed();
            } catch (Exception | LinkageError error) {
                ready = false; phase = "failed";
                failure = error instanceof IllegalStateException ? safeCode(error.getMessage()) : error.getClass().getSimpleName();
                changed();
            } finally { preparing = false; }
        });
    }

    private boolean unmetered() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private void download(File destination, JSONObject specification) throws Exception {
        URL url = new URL(specification.getString("url"));
        if (!"https".equals(url.getProtocol()) || !"huggingface.co".equals(url.getHost())) throw new IllegalStateException("invalid_model_origin");
        phase = "downloading"; changed();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(20_000); connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        File temporary = new File(directory, destination.getName() + ".partial");
        try {
            if (connection.getResponseCode() != 200 || !"https".equals(connection.getURL().getProtocol())) throw new IllegalStateException("model_download_unavailable");
            long count = 0, expected = specification.getLong("size"), base = downloaded;
            try (InputStream input = new BufferedInputStream(connection.getInputStream()); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024]; int read;
                while ((read = input.read(buffer)) != -1) {
                    if (!unmetered()) throw new IllegalStateException("network_became_metered");
                    count += read; if (count > expected) throw new IllegalStateException("model_size_mismatch");
                    output.write(buffer, 0, read); downloaded = base + count;
                }
                output.getFD().sync();
            }
            if (!verified(temporary, specification)) throw new IllegalStateException("model_checksum_mismatch");
            try { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            downloaded = base;
        } finally { connection.disconnect(); if (temporary.exists()) temporary.delete(); }
    }

    private static boolean verified(File file, JSONObject specification) throws Exception {
        if (!file.isFile() || file.length() != specification.getLong("size")) return false;
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) { byte[] buffer = new byte[64 * 1024]; int read; while ((read = input.read(buffer)) != -1) hash.update(buffer, 0, read); }
        return specification.getString("sha256").equals(hex(hash.digest()));
    }

    private void loadVerifiedModel() throws Exception {
        tokenizer = new BertWordPieceTokenizer(new InputStreamReader(new FileInputStream(new File(directory, "vocab.txt")), StandardCharsets.UTF_8));
        try (FileInputStream file = new FileInputStream(new File(directory, "dense.safetensors")); FileChannel channel = file.getChannel()) {
            ByteBuffer data = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN);
            long headerSize = data.getLong();
            if (headerSize < 2 || headerSize > 64 * 1024) throw new IllegalStateException("invalid_projection_header");
            byte[] header = new byte[(int) headerSize]; data.get(header);
            JSONObject tensors = new JSONObject(new String(header, StandardCharsets.UTF_8));
            projection = new float[512][768]; bias = new float[512];
            readTensor(data, tensors.getJSONObject("linear.weight"), 8 + (int) headerSize, projection, null);
            readTensor(data, tensors.getJSONObject("linear.bias"), 8 + (int) headerSize, null, bias);
        }
        environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = environment.createSession(new File(directory, "transformer.onnx").getAbsolutePath(), options);
        }
    }

    private static void readTensor(ByteBuffer data, JSONObject specification, int base, float[][] matrix, float[] vector) throws Exception {
        if (!"F32".equals(specification.getString("dtype"))) throw new IllegalStateException("unsupported_projection_dtype");
        JSONArray shape = specification.getJSONArray("shape"), offsets = specification.getJSONArray("data_offsets");
        int floats = matrix != null ? 512 * 768 : 512;
        if (offsets.getLong(1) - offsets.getLong(0) != floats * 4L || offsets.getLong(0) < 0
                || base + offsets.getLong(1) > data.capacity()) throw new IllegalStateException("invalid_projection_range");
        if (matrix != null && (shape.length() != 2 || shape.getInt(0) != 512 || shape.getInt(1) != 768)) throw new IllegalStateException("invalid_projection_shape");
        if (vector != null && (shape.length() != 1 || shape.getInt(0) != 512)) throw new IllegalStateException("invalid_projection_shape");
        ByteBuffer values = data.duplicate().order(ByteOrder.LITTLE_ENDIAN); values.position(base + offsets.getInt(0));
        if (matrix != null) for (float[] row : matrix) for (int i = 0; i < row.length; i++) row[i] = values.getFloat();
        else for (int i = 0; i < vector.length; i++) vector[i] = values.getFloat();
    }

    @Override public synchronized int fittingPrefixLength(String text) {
        if (!ready) return SemanticEncoder.super.fittingPrefixLength(text);
        return tokenizer.fittingPrefixLength(text, 126);
    }

    @Override public synchronized float[] encode(String text) throws Exception {
        if (!ready || session == null) throw new IllegalStateException("semantic_model_not_ready");
        if (text == null || text.trim().isEmpty() || text.length() > 100_000) throw new IllegalArgumentException("Invalid semantic input");
        long[] tokens = tokenizer.tokens(text);
        if (tokens.length == 0) throw new IllegalArgumentException("Empty semantic token sequence");
        float[] result = new float[512]; int windows = 0;
        // Queries longer than one model window are encoded in full rather than silently truncated.
        for (int start = 0; start < tokens.length; start += 110) {
            int end = Math.min(tokens.length, start + 126);
            long[] ids = tokenizer.withSpecialTokens(tokens, start, end), mask = new long[ids.length];
            java.util.Arrays.fill(mask, 1L);
            try (OnnxTensor input = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), new long[]{1, ids.length});
                 OnnxTensor attention = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), new long[]{1, ids.length})) {
                Map<String, OnnxTensor> inputs = new HashMap<>(); inputs.put("input_ids", input); inputs.put("attention_mask", attention);
                try (OrtSession.Result output = session.run(inputs)) {
                    Object value = output.get("last_hidden_state").orElseThrow(() -> new IllegalStateException("embedding_output_missing")).getValue();
                    if (!(value instanceof float[][][])) throw new IllegalStateException("invalid_embedding_output");
                    float[][] hidden = ((float[][][]) value)[0]; float[] pooled = new float[768];
                    if (hidden.length != ids.length) throw new IllegalStateException("embedding_length_mismatch");
                    for (float[] token : hidden) { if (token.length != 768) throw new IllegalStateException("embedding_dimension_mismatch"); for (int d = 0; d < 768; d++) pooled[d] += token[d] / hidden.length; }
                    for (int d = 0; d < 512; d++) { double sum = bias[d]; for (int k = 0; k < 768; k++) sum += projection[d][k] * (double) pooled[k]; result[d] += (float) Math.tanh(sum); }
                }
            }
            windows++; if (end == tokens.length) break;
        }
        double norm = 0; for (float value : result) { if (!Float.isFinite(value)) throw new IllegalStateException("nonfinite_embedding"); norm += value * (double) value; }
        if (windows == 0 || norm <= 1e-16) throw new IllegalStateException("invalid_embedding_norm");
        float scale = (float) (1 / Math.sqrt(norm)); for (int i = 0; i < result.length; i++) result[i] *= scale;
        return result;
    }
    @Override public synchronized void close() throws Exception { ready = false; loader.shutdownNow(); if (session != null) { session.close(); session = null; } }
    private void changed() { Runnable callback = listener; if (callback != null) callback.run(); }
    private static String safeCode(String code) { return code != null && code.matches("[A-Za-z0-9_]{1,80}") ? code : "model_load_failed"; }
    private static String read(InputStream input, int limit) throws Exception { java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) != -1) { if (output.size() + count > limit) throw new IllegalStateException("manifest_too_large"); output.write(buffer, 0, count); } return output.toString(StandardCharsets.UTF_8.name()); }
    private static String hex(byte[] data) { char[] alphabet = "0123456789abcdef".toCharArray(), output = new char[data.length * 2]; for (int i = 0; i < data.length; i++) { output[i * 2] = alphabet[(data[i] & 255) >>> 4]; output[i * 2 + 1] = alphabet[data[i] & 15]; } return new String(output); }
    private static JSONObject object(Object... pairs) { JSONObject result = new JSONObject(); try { for (int i = 0; i < pairs.length; i += 2) result.put(String.valueOf(pairs[i]), pairs[i + 1]); return result; } catch (Exception failure) { throw new IllegalStateException(failure); } }
}
