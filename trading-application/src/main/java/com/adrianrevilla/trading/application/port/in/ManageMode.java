package com.adrianrevilla.trading.application.port.in;

import com.adrianrevilla.trading.application.read.ModeView;
import com.adrianrevilla.trading.application.read.ProdChallengeView;

/**
 * Input port: the trading mode of the system, DEMO or PROD (ADR-0030, ADR-0031). Going to DEMO is always allowed (it is
 * the safe direction). Going to PROD is a two-step operation behind a feature flag: {@link #requestProdSwitch} issues a
 * challenge and {@link #confirmProdSwitch} needs that challenge, a one-time MFA code and the confirmation phrase.
 */
public interface ManageMode {
    ModeView current();

    /**
     * Selects DEMO. PROD can never be selected here.
     *
     * @throws ModeNotAvailableException when asked for PROD
     * @throws IllegalArgumentException for anything that is not a mode
     */
    ModeView switchTo(String mode, String actor);

    /**
     * Step one: asks to switch to PROD.
     *
     * @throws ModeNotAvailableException when the feature flag is off
     * @throws SwitchLockedException after too many failed confirmations
     */
    ProdChallengeView requestProdSwitch(String actor);

    /**
     * Step two: switches to PROD if the challenge, the MFA code and the phrase are all right. Any failure consumes the
     * challenge and counts towards the lockout; the reason is never told to the caller (it goes to the audit trail).
     *
     * @throws ConfirmationRejectedException for any wrong or expired factor
     * @throws SwitchLockedException when locked out
     * @throws ModeNotAvailableException when the feature flag is off
     */
    ModeView confirmProdSwitch(String challengeId, String code, String phrase, String actor);

    class ModeNotAvailableException extends RuntimeException {
        public ModeNotAvailableException(String message) {
            super(message);
        }
    }

    /** The challenge, the code or the phrase was wrong. The message is deliberately the same for every cause. */
    class ConfirmationRejectedException extends RuntimeException {
        public ConfirmationRejectedException() {
            super("confirmation rejected: ask for a new challenge and try again");
        }
    }

    class SwitchLockedException extends RuntimeException {
        public SwitchLockedException(String until) {
            super("too many failed confirmations: locked until " + until);
        }
    }
}
