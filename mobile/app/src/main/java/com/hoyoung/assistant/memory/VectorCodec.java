package com.hoyoung.assistant.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Exact float32 cache encoding; scores measure relevance, never factual confidence. */
public final class VectorCodec {
    public static final String ENCODING = "unit-f32-le-v1";
    private VectorCodec() { }

    public static float[] normalize(float[] input, int dimensions) {
        if (input == null || input.length != dimensions || dimensions < 1 || dimensions > 8192) throw new IllegalArgumentException("Invalid vector dimensions");
        double sum = 0;
        for (float value : input) { if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite vector"); sum += (double) value * value; }
        if (!(sum > 1e-20)) throw new IllegalArgumentException("Empty vector");
        float[] result = new float[dimensions]; double length = Math.sqrt(sum);
        for (int i = 0; i < dimensions; i++) result[i] = (float) (input[i] / length);
        return result;
    }

    public static byte[] encode(float[] input, int dimensions) {
        float[] normalized = normalize(input, dimensions);
        ByteBuffer output = ByteBuffer.allocate(dimensions * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : normalized) output.putFloat(value);
        return output.array();
    }

    public static float[] decode(byte[] bytes, int dimensions) {
        if (bytes == null || bytes.length != dimensions * 4) throw new IllegalArgumentException("Corrupt vector length");
        float[] result = new float[dimensions]; ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < dimensions; i++) { result[i] = input.getFloat(); if (!Float.isFinite(result[i])) throw new IllegalArgumentException("Non-finite cached vector"); }
        return result;
    }

    public static double cosine(float[] left, float[] right) {
        if (left.length != right.length || left.length == 0) throw new IllegalArgumentException("Incompatible vectors");
        double dot = 0, leftLength = 0, rightLength = 0;
        for (int i = 0; i < left.length; i++) {
            if (!Float.isFinite(left[i]) || !Float.isFinite(right[i])) throw new IllegalArgumentException("Non-finite vector");
            dot += (double) left[i] * right[i]; leftLength += (double) left[i] * left[i]; rightLength += (double) right[i] * right[i];
        }
        if (!(leftLength > 1e-20) || !(rightLength > 1e-20)) return 0;
        return Math.max(-1, Math.min(1, dot / Math.sqrt(leftLength * rightLength)));
    }
}
