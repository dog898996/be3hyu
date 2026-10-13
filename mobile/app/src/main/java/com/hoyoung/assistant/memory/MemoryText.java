package com.hoyoung.assistant.memory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Unicode normalization and small explicit query expansions; this is not a neural model. */
public final class MemoryText {
    private static final Set<String> STOP = new LinkedHashSet<>(Arrays.asList("the", "a", "an", "is", "was", "are", "what", "which", "my", "me", "i", "do", "did", "of", "to", "in", "and", "about", "알려줘", "뭐야", "무엇", "어떤", "내가", "나는", "나의", "내", "좀", "그", "이", "것", "있어", "해줘"));
    private static final String[][] CONCEPTS = {
            {"brief", "concise", "short", "짧게", "짧은", "간결", "간단"},
            {"budget", "cost", "price", "cheap", "예산", "가격", "비용", "저렴", "가성비"},
            {"home", "residence", "address", "live", "거주", "주소", "사는", "살고", "집"},
            {"deadline", "due", "마감", "기한", "제출"},
            {"meeting", "appointment", "calendar", "schedule", "일정", "약속", "회의", "스케줄"},
            {"allergy", "allergic", "알레르기", "알러지"},
            {"vegetarian", "vegan", "채식", "비건"},
            {"drink", "beverage", "음료", "마시는", "마실"},
            {"preferred", "preference", "favorite", "선호", "좋아", "취향"},
            {"code", "coding", "programming", "코드", "코딩", "프로그래밍"}
    };
    private MemoryText() { }

    public static String normalize(String text) { return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim(); }
    public static List<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        String input = normalize(text);
        Matcher quoted = Pattern.compile("[\"“](.{2,100}?)[\"”]").matcher(input);
        while (quoted.find() && terms.size() < 24) terms.add(quoted.group(1));
        for (String token : input.split("[^\\p{L}\\p{N}_.@/-]+")) {
            if (token.length() < 2 || STOP.contains(token)) continue;
            terms.add(token);
            if (token.matches("[가-힣]{4,}")) for (String suffix : new String[]{"에서는", "에게는", "에서", "에게", "으로", "부터", "까지", "을", "를", "은", "는", "이", "가", "의"}) {
                if (token.endsWith(suffix) && token.length() - suffix.length() >= 2) { terms.add(token.substring(0, token.length() - suffix.length())); break; }
            }
            if (terms.size() >= 24) break;
        }
        return new ArrayList<>(terms);
    }

    public static String expandedQuery(String query) {
        Set<String> result = new LinkedHashSet<>(terms(query)); String normal = normalize(query);
        for (String[] group : CONCEPTS) {
            boolean found = false; for (String value : group) if (value.matches("[a-z]+")
                    ? Pattern.compile("(?<![a-z0-9])"+Pattern.quote(value)+"(?![a-z0-9])").matcher(normal).find()
                    : normal.contains(value)) { found = true; break; }
            if (found) for (String value : group) result.add(value);
        }
        return String.join(" ", result);
    }

    public static double coverage(String query, String document) {
        List<String> terms = terms(query); if (terms.isEmpty()) return 0;
        String normalized = normalize(document); int matches = 0;
        for (String term : terms) if (normalized.contains(term)) matches++;
        return (double) matches / terms.size();
    }

    public static String window(String text, String query, int maximum) {
        if (text == null) return "";
        if (text.length() <= maximum) return text;
        String normalized = normalize(text); int position = -1;
        for (String term : terms(query)) { int found = normalized.indexOf(term); if (found >= 0 && (position < 0 || found < position)) position = found; }
        // Normalization can move offsets. The result is explicitly an excerpt, never an exact quote range.
        int start = position < 0 ? 0 : Math.max(0, Math.min(text.length() - maximum, position - maximum / 3));
        return (start > 0 ? "[…] " : "") + text.substring(start, Math.min(text.length(), start + maximum)) + (start + maximum < text.length() ? " […]" : "");
    }
}
