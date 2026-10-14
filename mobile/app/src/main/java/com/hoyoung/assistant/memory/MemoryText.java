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
            ,{"reference", "registration", "receipt", "identifier", "tracking", "number", "번호", "접수번호", "등록번호", "참조번호", "관리번호", "식별번호", "신청번호", "등록코드", "접수코드", "식별자", "접수", "등록", "참조"}
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
            String grammatical = token;
            if (token.matches("[가-힣]{4,}")) for (String suffix : new String[]{"에서는", "에게는", "에서", "에게", "으로", "부터", "까지", "을", "를", "은", "는", "이", "가", "의"}) {
                if (token.endsWith(suffix) && token.length() - suffix.length() >= 2) { grammatical = token.substring(0, token.length() - suffix.length()); terms.add(grammatical); break; }
            }
            // Owners also append a group descriptor directly to a short nickname. Keep the
            // complete name and add its stem only as a search hint, never as alias equivalence.
            // A relation still needs a positive, source-backed owner cue in adaptive recall.
            for (String descriptor : new String[]{"모임", "팀", "동아리", "그룹"}) {
                if (grammatical.endsWith(descriptor) && grammatical.length() - descriptor.length() >= 2) {
                    terms.add(grammatical.substring(0, grammatical.length() - descriptor.length())); break;
                }
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

    /** Search hints only: explicit owner relationships are not new instructions or verified facts. */
    static List<String> relatedQuotedTerms(String original,Set<String> known) {
        List<String> result=new ArrayList<>();if(original==null||known.isEmpty())return result;
        Pattern relation=Pattern.compile("별명|별칭|약칭|코드명|부르|부를|불러|이름을|이름은|새 이름|담당|파트너|동료|팀원|출장|프로젝트|renamed|formerly|also known|\\balias\\b|\\baka\\b|\\bcalled\\b|\\bpartner\\b|\\bassigned\\b",Pattern.CASE_INSENSITIVE);
        Pattern negation=Pattern.compile("아니|아닌|않|잘못|오해|무관|없는|없다|없어|부르지|말고|not\\b|never\\b|incorrect|mistaken|unrelated",Pattern.CASE_INSENSITIVE);
        Pattern quotes=Pattern.compile("[\\\"“‘']([^\\\"”’'\\r\\n]{2,120})[\\\"”’']");
        Pattern plainAlias=Pattern.compile("(?:^|[.])\\s*([\\p{L}\\p{N}_. -]{2,100}?)(?:을|를)\\s+([\\p{L}\\p{N}_-]{2,40})(?:이라고도|라고도|이라고|라고)\\s*(?:부를|부르|불러)");
        for(String sentence:original.split("[\\r\\n。!?;]+|(?<=[.])\\s+")) {
            if(sentence.length()>2000||!relation.matcher(sentence).find()||negation.matcher(sentence).find())continue;
            List<String> quoted=new ArrayList<>();Matcher match=quotes.matcher(sentence);
            while(match.find()&&quoted.size()<12){String value=match.group(1).trim();if(!value.isEmpty()&&terms(value).size()<=6)quoted.add(value);}
            Matcher plain=plainAlias.matcher(sentence);while(plain.find()&&quoted.size()<12){quoted.add(plain.group(1).trim());quoted.add(plain.group(2).trim());}
            boolean linked=false;for(String value:quoted){String normal=normalize(value);for(String seed:known)if(normal.equals(normalize(seed))){linked=true;break;}if(linked)break;}
            if(!linked||quoted.size()<2)continue;
            for(String value:quoted)if(!known.contains(normalize(value))&&!result.contains(value))result.add(value);
        }
        return result;
    }

    public static String window(String text, String query, int maximum) {
        if (text == null) return "";
        if (text.length() <= maximum) return text;
        String normalized = normalize(text);List<String> terms=terms(query);int position=-1;double best=-1;int probes=0;
        // A repeated generic prefix must not select a weaker window ahead of a rarer identifier/concept.
        for(String term:terms){int from=0;for(int hit=0;hit<3&&probes<48;hit++){int found=normalized.indexOf(term,from);if(found<0)break;from=found+Math.max(1,term.length());probes++;int start=Math.max(0,Math.min(normalized.length()-Math.min(maximum,normalized.length()),found-maximum/3));String slice=normalized.substring(start,Math.min(normalized.length(),start+maximum));double score=0;for(String word:terms)if(slice.contains(word))score+=1+Math.min(12,word.length())/12.0;if(score>best){best=score;position=found;}}}
        // Normalization can move offsets. The result is explicitly an excerpt, never an exact quote range.
        int start = position < 0 ? 0 : Math.max(0, Math.min(text.length() - maximum, position - maximum / 3));
        return (start > 0 ? "[…] " : "") + text.substring(start, Math.min(text.length(), start + maximum)) + (start + maximum < text.length() ? " […]" : "");
    }
}
