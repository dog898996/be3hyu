package com.hoyoung.assistant.memory.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cased BERT BasicTokenizer + greedy WordPiece, including Unicode/CJK boundaries. */
public final class BertWordPieceTokenizer {
    private final Map<String, Integer> vocabulary = new HashMap<>();
    private final int unknown, cls, sep;
    public BertWordPieceTokenizer(Reader input) throws IOException {
        try (BufferedReader reader = new BufferedReader(input)) {
            String line; int index = 0;
            while ((line = reader.readLine()) != null) vocabulary.put(line, index++);
        }
        unknown = require("[UNK]"); cls = require("[CLS]"); sep = require("[SEP]"); require("[PAD]");
    }
    private int require(String token) { Integer value = vocabulary.get(token); if (value == null) throw new IllegalArgumentException("Incomplete BERT vocabulary"); return value; }

    public long[] tokens(String text) {
        List<Long> result = new ArrayList<>();
        for (String word : basic(text)) {
            if (word.codePointCount(0, word.length()) > 100) { result.add((long) unknown); continue; }
            Integer special = vocabulary.get(word);
            if (word.startsWith("[") && word.endsWith("]") && special != null) { result.add(special.longValue()); continue; }
            List<Long> pieces = new ArrayList<>(); int start = 0; boolean failed = false;
            while (start < word.length()) {
                int end = word.length(); Integer found = null;
                while (end > start) {
                    String piece = (start == 0 ? "" : "##") + word.substring(start, end);
                    found = vocabulary.get(piece); if (found != null) break;
                    end = word.offsetByCodePoints(end, -1);
                }
                if (found == null) { failed = true; break; }
                pieces.add(found.longValue()); start = end;
            }
            if (failed) result.add((long) unknown); else result.addAll(pieces);
        }
        long[] ids = new long[result.size()]; for (int i = 0; i < ids.length; i++) ids[i] = result.get(i); return ids;
    }

    public long[] withSpecialTokens(long[] tokens, int from, int to) {
        long[] result = new long[to - from + 2]; result[0] = cls;
        System.arraycopy(tokens, from, result, 1, to - from); result[result.length - 1] = sep; return result;
    }

    public int fittingPrefixLength(String text, int contentTokens) {
        if (text.isEmpty()) return 0;
        if (tokens(text).length <= contentTokens) return text.length();
        int low = 0, high = text.codePointCount(0, text.length());
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            int chars = text.offsetByCodePoints(0, mid);
            if (tokens(text.substring(0, chars)).length <= contentTokens) low = mid; else high = mid - 1;
        }
        int result = text.offsetByCodePoints(0, low);
        // WordPiece's UNK rule makes lengths non-monotonic for unusually long single words.
        // Recheck the actual selected prefix; never claim tokens beyond the model budget.
        while (result > 0 && tokens(text.substring(0, result)).length > contentTokens)
            result = text.offsetByCodePoints(result, -1);
        return result;
    }

    static List<String> basic(String input) {
        List<String> words = new ArrayList<>(); StringBuilder word = new StringBuilder();
        for (int offset = 0; offset < input.length();) {
            boolean special = false;
            for (String token : new String[]{"[CLS]", "[SEP]", "[MASK]", "[UNK]", "[PAD]"}) {
                if (input.startsWith(token, offset)) { flush(word, words); words.add(token); offset += token.length(); special = true; break; }
            }
            if (special) continue;
            int code = input.codePointAt(offset); offset += Character.charCount(code);
            if (code == 0 || code == 0xfffd || control(code)) continue;
            if (Character.isWhitespace(code) || Character.getType(code) == Character.SPACE_SEPARATOR) { flush(word, words); continue; }
            if (cjk(code) || punctuation(code)) { flush(word, words); words.add(new String(Character.toChars(code))); }
            else word.appendCodePoint(code);
        }
        flush(word, words); return words;
    }
    private static void flush(StringBuilder word, List<String> output) { if (word.length() > 0) { output.add(word.toString()); word.setLength(0); } }
    private static boolean control(int code) { int type = Character.getType(code); return code != '\n' && code != '\r' && code != '\t' && (type == Character.CONTROL || type == Character.FORMAT); }
    private static boolean punctuation(int code) {
        if ((code >= 33 && code <= 47) || (code >= 58 && code <= 64) || (code >= 91 && code <= 96) || (code >= 123 && code <= 126)) return true;
        int type = Character.getType(code);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION;
    }
    private static boolean cjk(int code) { return (code >= 0x4e00 && code <= 0x9fff) || (code >= 0x3400 && code <= 0x4dbf)
            || (code >= 0x20000 && code <= 0x2a6df) || (code >= 0x2a700 && code <= 0x2b73f) || (code >= 0x2b740 && code <= 0x2b81f)
            || (code >= 0x2b820 && code <= 0x2ceaf) || (code >= 0xf900 && code <= 0xfaff) || (code >= 0x2f800 && code <= 0x2fa1f); }
}
