package com.hoyoung.assistant.memory;

/** Local-only encoder. Implementations must never send input text to a remote service. */
public interface SemanticEncoder {
    /** Includes immutable weights/tokenizer/pooling version; changed pipelines need a new id. */
    String modelId();
    int dimensions();
    boolean isReady();
    /** Distinguishes a real trained sentence encoder from a lexical/concept fallback. */
    boolean isNeural();
    /** Largest UTF-16 prefix fitting this model's input budget, without cutting a surrogate pair.
     * Implementations with a tokenizer should override this conservative fallback. */
    default int fittingPrefixLength(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = Math.min(120, text.length());
        if (count < text.length() && Character.isHighSurrogate(text.charAt(count - 1))) count--;
        return count;
    }
    /** Finite, nonzero, unit-normalized vector of dimensions() values. */
    float[] encode(String text) throws Exception;
}
