package com.adrianrevilla.trading.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** Current holdings (units per instrument), cash and entry date of each open position for one agent. */
public record PortfolioState(Map<Isin, BigDecimal> positions, BigDecimal cash, Map<Isin, LocalDate> entryDates) {
    public PortfolioState {
        positions = Map.copyOf(positions);
        entryDates = Map.copyOf(entryDates);
    }

    public PortfolioState(Map<Isin, BigDecimal> positions, BigDecimal cash) {
        this(positions, cash, Map.of());
    }

    public BigDecimal units(Isin isin) {
        return positions.getOrDefault(isin, BigDecimal.ZERO);
    }

    public boolean holds(Isin isin) {
        return units(isin).signum() > 0;
    }
}
