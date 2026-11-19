package com.adrianrevilla.trading.core;

import java.time.LocalDate;

/** Market data available up to the close of {@code asOf}; never contains later information. */
public interface MarketSnapshot {
    LocalDate asOf();
}
