package com.adrianrevilla.trading.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The limits of the PROD switch settings, on both sides of every edge. */
class ProdSwitchPolicyTest {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"; // 20 bytes

    private static ProdSwitchPolicy with(String secret, Duration ttl, int failures, Duration lockout) {
        return ProdSwitchPolicy.enabled(secret, ttl, failures, lockout);
    }

    @Test
    void theDisabledPolicyIsOffAndHasNoSecret() {
        var p = ProdSwitchPolicy.disabled();
        assertThat(p.enabled()).isFalse();
        assertThat(p.secret()).isEmpty();
    }

    @Test
    void theSecretMustBeAtLeastTenBytes() {
        assertThat(with("MFRGGZDFMZTWQ2LK", Duration.ofMinutes(2), 3, Duration.ofMinutes(15)).secret()).hasSize(10);
        assertThatThrownBy(() -> with("MFRGGZDFMZTWQ2I", Duration.ofMinutes(2), 3, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too short");
    }

    @Test
    void theChallengeLifetimeIsBetweenThirtySecondsAndTenMinutesInclusive() {
        assertThat(with(SECRET, Duration.ofSeconds(30), 3, Duration.ofMinutes(15)).challengeTtl())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(with(SECRET, Duration.ofMinutes(10), 3, Duration.ofMinutes(15)).challengeTtl())
                .isEqualTo(Duration.ofMinutes(10));
        assertThatThrownBy(() -> with(SECRET, Duration.ofSeconds(29), 3, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(SECRET, Duration.ofMinutes(10).plusSeconds(1), 3, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(SECRET, null, 3, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFailureLimitIsBetweenOneAndTenInclusive() {
        assertThat(with(SECRET, Duration.ofMinutes(2), 1, Duration.ofMinutes(15)).maxFailures()).isEqualTo(1);
        assertThat(with(SECRET, Duration.ofMinutes(2), 10, Duration.ofMinutes(15)).maxFailures()).isEqualTo(10);
        assertThatThrownBy(() -> with(SECRET, Duration.ofMinutes(2), 0, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(SECRET, Duration.ofMinutes(2), 11, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLockoutIsAtLeastOneMinute() {
        assertThat(with(SECRET, Duration.ofMinutes(2), 3, Duration.ofMinutes(1)).lockout())
                .isEqualTo(Duration.ofMinutes(1));
        assertThatThrownBy(() -> with(SECRET, Duration.ofMinutes(2), 3, Duration.ofSeconds(59)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(SECRET, Duration.ofMinutes(2), 3, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSecretIsCopiedSoCallersCannotChangeIt() {
        var p = with(SECRET, Duration.ofMinutes(2), 3, Duration.ofMinutes(15));
        p.secret()[0] = 0;
        assertThat(p.secret()[0]).isNotZero();
    }
}
