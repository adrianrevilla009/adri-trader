package com.adrianrevilla.trading.core.domain;

/** Single strategy contract for backtest, arena and live (ADR-0021). It does not know which mode it runs in. */
public interface Strategy {
    String id();

    /** Receives data up to today's close and the portfolio state; returns target weights. */
    TargetPortfolio decide(MarketSnapshot snapshot, PortfolioState state);
}
