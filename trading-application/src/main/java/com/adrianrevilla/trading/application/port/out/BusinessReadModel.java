package com.adrianrevilla.trading.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Output port (read side of CQRS, level 1: same database, queries shaped for the question): how the money moves per
 * agent. Nothing here changes state. The values are derived and can always be recomputed from the ledger.
 */
public interface BusinessReadModel {
    /** Latest equity, highest equity and lifecycle status of every arena agent. */
    List<AgentEquity> agentEquity();

    Map<String, BigDecimal> cashByAgent();

    /** Bought minus sold, at cost (not market value). */
    Map<String, BigDecimal> investedCostByAgent();

    Map<String, Long> fillsByAgent();

    Map<String, BigDecimal> tradedAmountByAgent();

    Map<String, BigDecimal> feesByAgent();

    List<OrderCount> ordersByAgentAndState();

    record AgentEquity(String agentId, BigDecimal equity, BigDecimal peak, String status) {
        /** 1 - equity / peak, never negative; 0 when there is no peak yet. */
        public double drawdown() {
            if (peak == null || peak.signum() <= 0) {
                return 0;
            }
            return Math.max(0, 1 - equity.doubleValue() / peak.doubleValue());
        }
    }

    record OrderCount(String agentId, String state, long count) {}
}
