package com.hoyoung.assistant.store;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC6238/RFC4226 computation; no account lookup, network, secret logging or server verification. */
public final class Totp {
    private Totp() {}
    public static final class Value {
        public final String code, algorithm;
        public final int digits;
        public final long period, generatedAt, validFrom, expiresAt;
        Value(String code, String algorithm, int digits, long period, long generatedAt, long validFrom, long expiresAt) {
            this.code = code; this.algorithm = algorithm; this.digits = digits; this.period = period;
            this.generatedAt = generatedAt; this.validFrom = validFrom; this.expiresAt = expiresAt;
        }
        public long remainingMillis() { return expiresAt - generatedAt; }
    }
    public static Value generate(String base32, String algorithm, int digits, long periodSeconds, long nowMillis) {
        if (digits != 6 && digits != 8 || periodSeconds <= 0 || nowMillis < 0) throw new IllegalArgumentException("Invalid TOTP digits, period or clock");
        String normalized = algorithm == null ? "SHA1" : algorithm.replace("-", "").toUpperCase(Locale.ROOT);
        if (!normalized.equals("SHA1") && !normalized.equals("SHA256") && !normalized.equals("SHA512")) throw new IllegalArgumentException("Unsupported TOTP algorithm");
        byte[] secret = decodeBase32(base32);
        try {
            long seconds = nowMillis / 1000L, counter = seconds / periodSeconds;
            long from = Math.multiplyExact(Math.multiplyExact(counter, periodSeconds), 1000L);
            long end = Math.addExact(from, Math.multiplyExact(periodSeconds, 1000L));
            Mac mac = Mac.getInstance("Hmac" + normalized); mac.init(new SecretKeySpec(secret, "Hmac" + normalized));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            try {
                int offset = digest[digest.length - 1] & 15;
                long binary = ((long) (digest[offset] & 127) << 24) | ((long) (digest[offset + 1] & 255) << 16)
                        | ((long) (digest[offset + 2] & 255) << 8) | (digest[offset + 3] & 255);
                long modulus = digits == 6 ? 1_000_000L : 100_000_000L;
                String code = String.format(Locale.ROOT, "%0" + digits + "d", binary % modulus);
                return new Value(code, normalized, digits, periodSeconds, nowMillis, from, end);
            } finally { Arrays.fill(digest, (byte) 0); }
        } catch (GeneralSecurityException | ArithmeticException invalid) { throw new IllegalArgumentException("TOTP configuration or clock cannot be evaluated", invalid); }
        finally { Arrays.fill(secret, (byte) 0); }
    }
    static byte[] decodeBase32(String value) {
        if (value == null) throw new IllegalArgumentException("TOTP seed is missing");
        String encoded = value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        int paddedAt = encoded.indexOf('='); String symbols = paddedAt < 0 ? encoded : encoded.substring(0, paddedAt);
        int remainder = symbols.length() % 8;
        if (symbols.isEmpty() || !(remainder == 0 || remainder == 2 || remainder == 4 || remainder == 5 || remainder == 7)) throw new IllegalArgumentException("TOTP seed has invalid Base32 length");
        if (paddedAt >= 0) {
            for (int i = paddedAt; i < encoded.length(); i++) if (encoded.charAt(i) != '=') throw new IllegalArgumentException("TOTP seed has invalid Base32 padding");
            if (encoded.length() % 8 != 0 || encoded.length() - paddedAt != (8 - remainder) % 8) throw new IllegalArgumentException("TOTP seed has invalid Base32 padding");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int bits = 0, accumulator = 0;
        for (int i = 0; i < symbols.length(); i++) {
            char letter = symbols.charAt(i); int number = letter >= 'A' && letter <= 'Z' ? letter - 'A' : letter >= '2' && letter <= '7' ? letter - '2' + 26 : -1;
            if (number < 0) throw new IllegalArgumentException("TOTP seed is not Base32");
            accumulator = (accumulator << 5) | number; bits += 5;
            if (bits >= 8) { bits -= 8; bytes.write((accumulator >> bits) & 255); accumulator &= (1 << bits) - 1; }
        }
        if (bits > 0 && accumulator != 0) throw new IllegalArgumentException("TOTP seed has nonzero Base32 padding bits");
        return bytes.toByteArray();
    }
}
