package com.adrianrevilla.trading.core;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Daily bar. {@code availableOn} is the first date on which the datum was known; the backtest never sees
 * a bar before that date (no look-ahead bias).
 */
public record Bar(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        LocalDate availableOn) {

    public Bar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
        this(date, open, high, low, close, volume, date);
    }
}
