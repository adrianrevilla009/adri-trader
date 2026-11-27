package com.adrianrevilla.trading.application.port.in;

import com.adrianrevilla.trading.application.read.ModeView;

/**
 * Input port: the trading mode of the system, DEMO or PROD (ADR-0030). The switch exists so the model and the API are
 * ready, but only DEMO can be selected today; PROD needs MFA and its own ADR (phase 2).
 */
public interface ManageMode {
    ModeView current();

    /**
     * @param mode DEMO or PROD
     * @throws ModeNotAvailableException for PROD today
     * @throws IllegalArgumentException for anything that is not a mode
     */
    ModeView switchTo(String mode, String actor);

    class ModeNotAvailableException extends RuntimeException {
        public ModeNotAvailableException(String message) {
            super(message);
        }
    }
}
