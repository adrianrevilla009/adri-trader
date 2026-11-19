package com.adrianrevilla.trading.backtest;

import com.adrianrevilla.trading.core.Isin;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BacktestResult(String strategyId, List<EquityPoint> equity, List<Trade> trades) {

    /** Value at the close of {@code date}; {@code invested} is the part held in instruments. */
    public record EquityPoint(LocalDate date, BigDecimal equity, BigDecimal invested) {}

    public enum Side { BUY, SELL }

    /** {@code costEur} is the spread plus slippage paid versus the open price. */
    public record Trade(LocalDate date, Isin isin, Side side, BigDecimal quantity, BigDecimal price,
            BigDecimal amountEur, BigDecimal costEur) {}
}
