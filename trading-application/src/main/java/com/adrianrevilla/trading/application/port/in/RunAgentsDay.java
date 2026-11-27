package com.adrianrevilla.trading.application.port.in;

import java.time.LocalDate;
import java.util.List;

/**
 * Input port (use case): the daily live loop of every ACTIVE agent (ADR-0030). Each agent runs on its own, so one that
 * fails does not stop the others. Safe to call twice for the same day: each agent day is claimed atomically.
 */
public interface RunAgentsDay {
    /** What one agent did, or why it did not. */
    record AgentResult(String agentId, RunTradingDay.Result result) {
    }

    /** @return one result per active agent, ordered by agent id; empty when there is none */
    List<AgentResult> run(LocalDate day);
}
