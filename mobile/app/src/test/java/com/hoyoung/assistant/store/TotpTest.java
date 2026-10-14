package com.hoyoung.assistant.store;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class TotpTest {
    // RFC6238 AppendixB, independently published HMAC/SHA1, SHA256 and SHA512 vectors.
    @Test public void allEighteenPublishedRfc6238VectorsMatchExactly() {
        String[] algorithms = {"SHA1", "SHA256", "SHA512"};
        String[] seeds = {"12345678901234567890", "12345678901234567890123456789012", "1234567890123456789012345678901234567890123456789012345678901234"};
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[][] expected = {{"94287082", "46119246", "90693936"}, {"07081804", "68084774", "25091201"},
                {"14050471", "67062674", "99943326"}, {"89005924", "91819424", "93441116"},
                {"69279037", "90698825", "38618901"}, {"65353130", "77737706", "47863826"}};
        for (int t = 0; t < times.length; t++) for (int a = 0; a < algorithms.length; a++)
            assertEquals(algorithms[a] + " at " + times[t], expected[t][a], Totp.generate(encode(seeds[a]), algorithms[a], 8, 30, times[t] * 1000L).code);
    }
    @Test public void publishedSixDigitHotpCountersAlsoMatchTheEquivalentTotpPeriods() {
        String[] expected = {"755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489"};
        for (int counter = 0; counter < expected.length; counter++) assertEquals(expected[counter], Totp.generate(encode("12345678901234567890"), "SHA1", 6, 30, counter * 30_000L).code);
    }
    @Test public void expiryAndCustomPeriodComeFromTheActualClockWindow() {
        Totp.Value first = Totp.generate(encode("12345678901234567890"), "SHA1", 6, 45, 89_999);
        assertEquals(45_000, first.validFrom); assertEquals(90_000, first.expiresAt); assertEquals(1, first.remainingMillis());
        Totp.Value next = Totp.generate(encode("12345678901234567890"), "SHA1", 6, 45, 90_000);
        assertEquals(90_000, next.validFrom); assertEquals(135_000, next.expiresAt); assertNotEquals(first.code, next.code);
    }
    @Test public void equivalentBase32CaseWhitespaceAndPaddingDecodeWithoutChangingTheStoredSeed() {
        String seed = "  my====== \n"; byte[] decoded = Totp.decodeBase32(seed); assertArrayEquals(new byte[]{'f'}, decoded); assertEquals("  my====== \n", seed);
        assertEquals(Totp.generate("MY", "SHA1", 6, 30, 60_000).code, Totp.generate(seed, "sha-1", 6, 30, 60_000).code);
    }
    @Test public void malformedSeedsClockAndConfigurationsDoNotProduceAValue() {
        for (String seed : new String[]{"", "A", "AAA", "AAAAAA", "MZ", "M0", "MY===", "MY=AAAA=", "MY========"}) assertThrows(seed, IllegalArgumentException.class, () -> Totp.generate(seed, "SHA1", 6, 30, 60_000));
        assertThrows(IllegalArgumentException.class, () -> Totp.generate("MY", "MD5", 6, 30, 60_000));
        assertThrows(IllegalArgumentException.class, () -> Totp.generate("MY", "SHA1", 7, 30, 60_000));
        assertThrows(IllegalArgumentException.class, () -> Totp.generate("MY", "SHA1", 6, 0, 60_000));
        assertThrows(IllegalArgumentException.class, () -> Totp.generate("MY", "SHA1", 6, 30, -1));
        assertThrows(IllegalArgumentException.class, () -> Totp.generate("MY", "SHA1", 6, Long.MAX_VALUE, 60_000));
    }
    private static String encode(String plain) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; StringBuilder encoded = new StringBuilder(); int accumulator = 0, bits = 0;
        for (byte value : plain.getBytes(StandardCharsets.US_ASCII)) { accumulator = (accumulator << 8) | (value & 255); bits += 8; while (bits >= 5) { bits -= 5; encoded.append(alphabet.charAt((accumulator >> bits) & 31)); } accumulator &= (1 << bits) - 1; }
        if (bits > 0) encoded.append(alphabet.charAt((accumulator << (5 - bits)) & 31)); return encoded.toString();
    }
}
