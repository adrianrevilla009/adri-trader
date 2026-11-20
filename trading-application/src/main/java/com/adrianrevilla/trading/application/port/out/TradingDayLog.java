package com.adrianrevilla.trading.application.port.out;

import java.time.LocalDate;
import java.util.Optional;

/** Output port: atomic claim of an agent day. Only the first caller gets the day; it is never retried by itself. */
public interface TradingDayLog {
    /** @return true if this caller now owns the day; false if the day was already claimed by any earlier run */
    boolean claim(LocalDate day, String agentId);

    void finish(LocalDate day, String agentId, boolean ok, String detail);

    Optional<String> status(LocalDate day, String agentId);
}
