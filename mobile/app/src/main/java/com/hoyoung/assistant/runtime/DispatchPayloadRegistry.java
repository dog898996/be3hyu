package com.hoyoung.assistant.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Native-only, one-use admission bound to the final text, model and attachment bytes. */
public final class DispatchPayloadRegistry {
    private static final int MAX_PENDING = 32;
    private static final int QUOTE_CHUNK_CHARS = 8192;
    private final Map<String, Entry> pending = new LinkedHashMap<>();
    /** Only native preparation can construct a digest; callers keep its source payload unchanged. */
    static final class PreparedDigest {
        final String requestId, digest, replyTextDigest;
        private PreparedDigest(String requestId, String digest, String replyTextDigest) {
            this.requestId=requestId; this.digest=digest; this.replyTextDigest=replyTextDigest;
        }
    }
    private static final class Entry {
        final String digest, memoryFingerprint;
        final long registeredAt;
        boolean consumed;
        Entry(String digest, String memoryFingerprint, long now) {
            this.digest=digest; this.memoryFingerprint=memoryFingerprint; registeredAt=now;
        }
    }
    /** Preparation does not hold the registry lock, including for large attachment bodies. */
    public String register(String requestId, JSONObject finalPayload, String memoryFingerprint, long now) {
        return registerPrepared(prepare(requestId,finalPayload),memoryFingerprint,now);
    }
    static PreparedDigest prepare(String requestId, JSONObject finalPayload) {
        if (requestId==null || requestId.isEmpty() || finalPayload==null ||
                !(finalPayload.opt("requestId") instanceof String) || !requestId.equals(finalPayload.opt("requestId")))
            throw new IllegalArgumentException("Unbound dispatch payload");
        if (finalPayload.has("nativePayloadDigest")) throw new IllegalArgumentException("Digest must be computed before adding its envelope field");
        return new PreparedDigest(requestId,digest(finalPayload),replyTextDigest(finalPayload.optString("text")));
    }
    /** Call after validating live native control/memory state; this method only registers a small entry. */
    synchronized String registerPrepared(PreparedDigest prepared, String memoryFingerprint, long now) {
        if (prepared==null || memoryFingerprint==null || memoryFingerprint.isEmpty() || now<0)
            throw new IllegalArgumentException("Unbound dispatch payload");
        String requestId=prepared.requestId, digest=prepared.digest;
        // Re-registration cannot turn a consumed permit into another click authorization.
        Entry previous=pending.get(requestId);
        if (previous!=null && previous.consumed) throw new IllegalStateException("Dispatch permit already consumed");
        if (previous!=null && (!previous.digest.equals(digest) || !previous.memoryFingerprint.equals(memoryFingerprint)))
            throw new IllegalStateException("Prepared request identity changed");
        pending.put(requestId,new Entry(digest,memoryFingerprint,now));
        while(pending.size()>MAX_PENDING)pending.remove(pending.keySet().iterator().next());
        return digest;
    }
    public synchronized boolean consume(String requestId,String payloadDigest,String memoryFingerprint,long now) {
        Entry entry=pending.get(requestId);
        if(entry==null || entry.consumed || now<entry.registeredAt)return false;
        if(now-entry.registeredAt>600_000L){entry.consumed=true;return false;}
        if(!entry.digest.equals(payloadDigest) || !entry.memoryFingerprint.equals(memoryFingerprint))return false;
        entry.consumed=true;
        return true;
    }
    public synchronized void forget(String requestId) { pending.remove(requestId); }
    public synchronized String registeredDigest(String requestId) { Entry e=pending.get(requestId);return e==null?"":e.digest; }
    /** Sorted JSON keys, array order preserved, UTF-8. Only JSON scalar types are admitted. */
    public static String digest(JSONObject payload) {
        DigestSink sink=new DigestSink(); writeCanonical(payload,sink); return sink.finish();
    }
    private static void writeCanonical(Object value, DigestSink sink) {
        if(value==null || value==JSONObject.NULL){sink.write("null");return;}
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;ArrayList<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);Collections.sort(keys);
            sink.write("{");boolean first=true;
            for(String key:keys){if(!first)sink.write(",");first=false;writeQuoted(key,sink);sink.write(":");writeCanonical(object.opt(key),sink);}
            sink.write("}");return;
        }
        if(value instanceof JSONArray) {
            JSONArray array=(JSONArray)value;sink.write("[");
            for(int i=0;i<array.length();i++){if(i>0)sink.write(",");writeCanonical(array.opt(i),sink);}
            sink.write("]");return;
        }
        if(value instanceof String){writeQuoted((String)value,sink);return;}
        if(value instanceof Boolean){sink.write(value.toString());return;}
        if(value instanceof Number){sink.write(numberText((Number)value));return;}
        throw new IllegalArgumentException("Unsupported JSON value");
    }
    private static String numberText(Number value) {
        if(!Double.isFinite(value.doubleValue()))throw new IllegalArgumentException("Non-finite JSON number");
        return value.toString();
    }
    private static void writeQuoted(String text, DigestSink sink) {
        sink.write("\"");
        for(int start=0;start<text.length();) {
            int end=Math.min(text.length(),start+QUOTE_CHUNK_CHARS);
            // A valid pair must remain intact even at the bounded quoting boundary.
            if(end<text.length() && Character.isHighSurrogate(text.charAt(end-1)) && Character.isLowSurrogate(text.charAt(end)))end--;
            String piece=text.substring(start,end);
            int skip=1;
            if(start>0) {
                // JSONObject.quote is platform specific. Keeping the preceding original
                // character preserves its context-sensitive </ escaping on the JVM too.
                String predecessor=String.valueOf(text.charAt(start-1));
                piece=predecessor+piece;
                skip+=JSONObject.quote(predecessor).length()-2;
            }
            String quoted=JSONObject.quote(piece);
            sink.writeExactQuotedInterior(quoted,skip,quoted.length()-1);
            start=end;
        }
        sink.write("\"");
    }
    public static String canonical(Object value) {
        if(value==null || value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;ArrayList<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);Collections.sort(keys);
            StringBuilder out=new StringBuilder("{");boolean first=true;
            for(String key:keys){if(!first)out.append(',');first=false;out.append(quoteExact(key)).append(':').append(canonical(object.opt(key)));}
            return out.append('}').toString();
        }
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.opt(i)));}return out.append(']').toString();}
        if(value instanceof String)return quoteExact((String)value);
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Number)return numberText((Number)value);
        throw new IllegalArgumentException("Unsupported JSON value");
    }
    private static String quoteExact(String text) {
        // UTF-8 replaces unpaired UTF-16 surrogates. Escape those units first so
        // distinct retained owner originals never borrow the same payload digest.
        String quoted=JSONObject.quote(text);StringBuilder out=new StringBuilder(quoted.length());
        for(int i=0;i<quoted.length();i++) {
            char c=quoted.charAt(i);
            if(Character.isHighSurrogate(c)&&i+1<quoted.length()&&Character.isLowSurrogate(quoted.charAt(i+1))){out.append(c).append(quoted.charAt(++i));continue;}
            if(Character.isSurrogate(c))out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));
            else out.append(c);
        }
        return out.toString();
    }
    /** Same normalization as authoredUserText in the browser; stores no extra prompt copy. */
    public static String replyTextDigest(String value) {
        // Work rejects larger composer text. Do not duplicate an attachment-sized
        // string when this generic registry is used with other payloads.
        if(value.length()>250_000)return "";
        String normalized=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\u200b-\\u200f\\u202a-\\u202e\\u2060-\\u206f\\ufeff]", "")
                .replaceAll("[\\u0009-\\u000d\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+", " ").trim();
        return hash(normalized);
    }
    public static String hash(String value) {
        try {return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String hex(byte[] bytes) {
        char[] chars=new char[bytes.length*2];String alphabet="0123456789abcdef";
        for(int i=0;i<bytes.length;i++){int b=bytes[i]&255;chars[i*2]=alphabet.charAt(b>>>4);chars[i*2+1]=alphabet.charAt(b&15);}
        return new String(chars);
    }
    /** Fixed UTF-8 workspace; no canonical JSON or attachment-sized byte array is retained. */
    private static final class DigestSink {
        private final MessageDigest digest;
        private final byte[] buffer=new byte[8192];
        private int used;
        DigestSink() {
            try{digest=MessageDigest.getInstance("SHA-256");}
            catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
        void write(String text) { writeUtf8(text,0,text.length(),false); }
        void writeExactQuotedInterior(String text,int start,int end) { writeUtf8(text,start,end,true); }
        private void writeUtf8(String text,int start,int end,boolean escapeUnpaired) {
            for(int i=start;i<end;i++) {
                char c=text.charAt(i);
                if(c<0x80){put(c);continue;}
                if(c<0x800){put(0xc0|(c>>>6));put(0x80|(c&63));continue;}
                if(Character.isHighSurrogate(c)&&i+1<end&&Character.isLowSurrogate(text.charAt(i+1))) {
                    int cp=Character.toCodePoint(c,text.charAt(++i));
                    put(0xf0|(cp>>>18));put(0x80|((cp>>>12)&63));put(0x80|((cp>>>6)&63));put(0x80|(cp&63));continue;
                }
                if(Character.isSurrogate(c)) {
                    if(escapeUnpaired) {
                        put('\\');put('u');
                        String alphabet="0123456789abcdef";
                        put(alphabet.charAt((c>>>12)&15));put(alphabet.charAt((c>>>8)&15));put(alphabet.charAt((c>>>4)&15));put(alphabet.charAt(c&15));
                    } else put('?'); // StandardCharsets.UTF_8's replacement for a malformed unit.
                    continue;
                }
                put(0xe0|(c>>>12));put(0x80|((c>>>6)&63));put(0x80|(c&63));
            }
        }
        private void put(int value) { if(used==buffer.length)flush();buffer[used++]=(byte)value; }
        private void flush() { digest.update(buffer,0,used);used=0; }
        String finish() { flush();return hex(digest.digest()); }
    }
}
