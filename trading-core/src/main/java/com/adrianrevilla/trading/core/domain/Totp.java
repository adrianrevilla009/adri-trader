package com.adrianrevilla.trading.core.domain;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238, 6 digits, 30 seconds), the second factor to switch to PROD (ADR-0031).
 * HMAC-SHA1 is what every authenticator app uses; it is safe for HMAC. Java: {@code javax.crypto.Mac} is the JDK class.
 */
public final class Totp {
    public static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int MODULO = 1_000_000;

    private Totp() {
    }

    /** The code of one 30-second step (step = epoch seconds / 30). */
    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1"); // nosemgrep: java.lang.security.audit.crypto.weak-hash -- RFC 6238 needs HMAC-SHA1
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%0" + DIGITS + "d", binary % MODULO);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 is not available", e);
        }
    }

    /**
     * Checks a code against the current step and {@code window} steps either side (clock drift).
     *
     * @return the step that matched, or -1 if none. The caller must refuse a step it already accepted (replay).
     */
    public static long verify(byte[] secret, String given, long nowEpochSeconds, int window) {
        if (given == null || !given.matches("[0-9]{" + DIGITS + "}")) {
            return -1;
        }
        long now = nowEpochSeconds / STEP_SECONDS;
        long matched = -1;
        byte[] g = given.getBytes(StandardCharsets.UTF_8);
        for (long s = now - window; s <= now + window; s++) { // every step is checked: no early exit
            if (MessageDigest.isEqual(code(secret, s).getBytes(StandardCharsets.UTF_8), g)) {
                matched = s;
            }
        }
        return matched;
    }
}
