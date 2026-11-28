package com.adrianrevilla.trading.application.port.out;

/**
 * Append-only record of everything that touches the trading mode: requests, refusals, lockouts and switches (ADR-0031).
 * If it cannot be written, the switch to PROD does not happen.
 */
public interface ModeAuditLog {
    /**
     * @param outcome CHALLENGE_ISSUED, REFUSED, REJECTED, LOCKED_OUT, SWITCHED
     * @param detail the reason; never a code, a secret or a token
     */
    void record(String actor, String fromMode, String toMode, String outcome, String detail);
}
