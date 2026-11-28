package com.adrianrevilla.trading.application.service;

import com.adrianrevilla.trading.core.domain.Base32;
import java.time.Duration;

/**
 * Settings of the switch to PROD (ADR-0031). Off unless the feature flag is on, and then it needs an MFA secret.
 * Java: a final class instead of a record because it holds a byte array (a record would compare it by reference).
 */
public final class ProdSwitchPolicy {
    public static final int MIN_SECRET_BYTES = 10; // 80 bits; a generated secret has 20 bytes

    private final boolean enabled;
    private final byte[] secret;
    private final Duration challengeTtl;
    private final int maxFailures;
    private final Duration lockout;

    private ProdSwitchPolicy(boolean enabled, byte[] secret, Duration challengeTtl, int maxFailures, Duration lockout) {
        this.enabled = enabled;
        this.secret = secret;
        this.challengeTtl = challengeTtl;
        this.maxFailures = maxFailures;
        this.lockout = lockout;
    }

    /** The default: the feature does not exist. */
    public static ProdSwitchPolicy disabled() {
        return new ProdSwitchPolicy(false, new byte[0], Duration.ofMinutes(2), 3, Duration.ofMinutes(15));
    }

    /** @param base32Secret the shared secret of the authenticator app; at least 80 bits */
    public static ProdSwitchPolicy enabled(String base32Secret, Duration challengeTtl, int maxFailures,
            Duration lockout) {
        byte[] secret = Base32.decode(base32Secret);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("the MFA secret is too short: at least " + MIN_SECRET_BYTES * 8
                    + " bits (16 Base32 characters)");
        }
        if (challengeTtl == null || challengeTtl.compareTo(Duration.ofSeconds(30)) < 0
                || challengeTtl.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("the challenge lifetime must be between 30 seconds and 10 minutes");
        }
        if (maxFailures < 1 || maxFailures > 10) {
            throw new IllegalArgumentException("max failures must be between 1 and 10");
        }
        if (lockout == null || lockout.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("the lockout must be at least one minute");
        }
        return new ProdSwitchPolicy(true, secret, challengeTtl, maxFailures, lockout);
    }

    public boolean enabled() {
        return enabled;
    }

    byte[] secret() {
        return secret.clone();
    }

    Duration challengeTtl() {
        return challengeTtl;
    }

    int maxFailures() {
        return maxFailures;
    }

    Duration lockout() {
        return lockout;
    }
}
