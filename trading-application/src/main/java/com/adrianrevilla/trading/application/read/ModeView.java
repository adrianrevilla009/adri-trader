package com.adrianrevilla.trading.application.read;

import java.util.List;

/**
 * The trading mode of the system (ADR-0030, ADR-0031).
 *
 * @param available modes that can be reached: DEMO always; PROD only while the feature flag is on
 * @param prodEnabled whether the PROD switch feature is on (it is off by default)
 * @param lockedUntil ISO-8601 instant until which PROD confirmations are refused after repeated failures, or null
 * @param confirmPhrase the text the person must type to confirm the switch
 */
public record ModeView(String mode, List<String> available, boolean prodEnabled, String lockedUntil,
        String confirmPhrase) {
}
