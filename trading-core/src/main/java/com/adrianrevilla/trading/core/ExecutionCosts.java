package com.adrianrevilla.trading.core;

import java.math.BigDecimal;

/** Cost of crossing the market, shared by the backtest and the simulated broker so both price alike. */
public interface ExecutionCosts {
    /** Fraction by which the execution price moves against the trader, per side. */
    BigDecimal adverseFraction(Isin isin);
}
