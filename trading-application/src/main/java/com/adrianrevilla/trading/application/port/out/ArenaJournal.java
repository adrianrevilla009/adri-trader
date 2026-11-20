package com.adrianrevilla.trading.application.port.out;

import com.adrianrevilla.trading.arena.domain.DayReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** Output port: persists each arena day. Recording the same day twice overwrites it, so re-running a day is safe. */
public interface ArenaJournal {
    void record(DayReport report);

    /** Equity curve of one agent, ordered by day. */
    Map<LocalDate, BigDecimal> equity(String agentId);

    /** Status of the agent on its last recorded day, or null if it has none. */
    String lastStatus(String agentId);
}
