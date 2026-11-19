package com.adrianrevilla.trading.core;

import java.math.BigDecimal;

/** Risk rule; the risk module has the last word over any order and is independent of strategies. */
public interface RiskRule {
    String name();

    Decision evaluate(OrderRequest order, PortfolioState state);

    sealed interface Decision {
        record Approve() implements Decision {}

        record Trim(BigDecimal newAmountEur, String reason) implements Decision {}

        record Reject(String reason) implements Decision {}

        record Defer(String reason) implements Decision {}
    }
}
