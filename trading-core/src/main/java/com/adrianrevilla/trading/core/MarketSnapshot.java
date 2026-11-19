package com.adrianrevilla.trading.core;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Market data available up to the close of {@link #asOf()}; never contains later information. */
public interface MarketSnapshot {
    LocalDate asOf();

    Set<Isin> instruments();

    /** Bars with date up to and including {@code asOf} and already available, oldest first. */
    List<Bar> bars(Isin isin);

    default Optional<Bar> latest(Isin isin) {
        List<Bar> bars = bars(isin);
        return bars.isEmpty() ? Optional.empty() : Optional.of(bars.get(bars.size() - 1));
    }
}
