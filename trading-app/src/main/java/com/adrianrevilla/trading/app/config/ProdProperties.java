package com.adrianrevilla.trading.app.config;

import com.adrianrevilla.trading.application.service.ProdSwitchPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The switch to PROD (prefix {@code trading.prod}, ADR-0031). Off by default: with the flag off the feature does not
 * exist. With it on, an MFA secret (Base32, from an authenticator app) is required, or the application does not start.
 *
 * @param enabled the feature flag
 * @param mfaSecret Base32 shared secret of the authenticator app; keep it in the environment, never in the repository
 * @param challengeSeconds how long the one-use challenge of step one lives
 * @param maxFailures wrong confirmations in a row before the switch is locked
 * @param lockoutMinutes how long the lock lasts
 */
@ConfigurationProperties("trading.prod")
public record ProdProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String mfaSecret,
        @DefaultValue("120") int challengeSeconds,
        @DefaultValue("3") int maxFailures,
        @DefaultValue("15") int lockoutMinutes) {

    public ProdProperties {
        if (enabled && mfaSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "trading.prod.enabled needs trading.prod.mfa-secret (TRADING_PROD_MFA_SECRET): run scripts/new-mfa-secret.sh");
        }
    }

    /** The domain policy; throws if the settings are not acceptable (short secret, silly limits). */
    public ProdSwitchPolicy policy() {
        if (!enabled) {
            return ProdSwitchPolicy.disabled();
        }
        return ProdSwitchPolicy.enabled(mfaSecret, Duration.ofSeconds(challengeSeconds), maxFailures,
                Duration.ofMinutes(lockoutMinutes));
    }
}
