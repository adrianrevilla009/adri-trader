package com.adrianrevilla.trading.core;

import java.math.BigDecimal;
import java.util.Map;

/** Current holdings (units per instrument) and cash of one agent. */
public record PortfolioState(Map<Isin, BigDecimal> positions, BigDecimal cash) {
    public PortfolioState {
        positions = Map.copyOf(positions);
    }
}
