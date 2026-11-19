package com.adrianrevilla.trading.core;

import java.math.BigDecimal;

/** Risk rule; the risk module has the last word over any order and is independent of strategies. */
public interface RiskRule {
    String name();

    Decision evaluate(OrderRequest order, RiskContext context);

    sealed interface Decision {
        record Approve() implements Decision {}

        record Trim(BigDecimal newAmountEur, String reason) implements Decision {}

        record Reject(String reason) implements Decision {}

        /** Not now (for example market closed); the caller may try again later. */
        record Defer(String reason) implements Decision {}

        /** Turns the agent off and raises an alert; nothing else is sent. */
        record Shutdown(String reason) implements Decision {}
    }
}
