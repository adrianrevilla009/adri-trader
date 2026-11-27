package com.adrianrevilla.trading.core.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** After {@code afterDays} days since the agent started, its capital may go up to {@code capital} (a person confirms). */
public record EscalationStep(int afterDays, BigDecimal capital) {
    public EscalationStep {
        if (afterDays <= 0) {
            throw new IllegalArgumentException("afterDays must be positive: " + afterDays);
        }
        Objects.requireNonNull(capital, "capital");
        if (capital.signum() <= 0) {
            throw new IllegalArgumentException("capital must be positive: " + capital);
        }
    }
}
