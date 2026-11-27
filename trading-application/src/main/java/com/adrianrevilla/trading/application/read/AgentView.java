package com.adrianrevilla.trading.application.read;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Read model of an agent for the API and the screen: plain strings and numbers, no domain classes (CQRS, ADR-0030). */
public record AgentView(String id, String strategyId, Map<String, BigDecimal> params, List<String> universe,
                        BigDecimal capital, List<Step> steps, String mode, String state) {
    /** After {@code afterDays} days the capital may go up to {@code capital}. */
    public record Step(int afterDays, BigDecimal capital) {
    }
}
