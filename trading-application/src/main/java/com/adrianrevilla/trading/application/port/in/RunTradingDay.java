package com.adrianrevilla.trading.application.port.in;

import java.time.LocalDate;

/**
 * Input port (use case): the daily live loop of one agent. The decision is made on the close of {@code day}; orders
 * execute at the next open. Safe to call twice for the same day: the day is claimed atomically.
 */
public interface RunTradingDay {
    enum Outcome { TRADED, KILL_SWITCH_ACTIVE, NOT_ALLOWED, ALREADY_RAN, NO_DATA, HALTED, FAILED }

    record Result(Outcome outcome, int sent, int rejected, int unknown, String detail) {
        public static Result of(Outcome o, String detail) {
            return new Result(o, 0, 0, 0, detail);
        }
    }

    Result run(LocalDate day);
}
