package com.adrianrevilla.trading.backtest;

import java.math.BigDecimal;

/**
 * @param minOrderEur orders below this amount are not sent (eToro minimum ~10 USD is unverified, ADR-0007)
 */
public record BacktestConfig(BigDecimal initialCash, CostModel costs, BigDecimal minOrderEur, TargetAdjuster adjuster) {

    public static BacktestConfig defaults() {
        return new BacktestConfig(new BigDecimal("100"), CostModel.of("10", "5"), BigDecimal.ONE, TargetAdjuster.NONE);
    }

    public BacktestConfig withCosts(CostModel c) {
        return new BacktestConfig(initialCash, c, minOrderEur, adjuster);
    }

    public BacktestConfig withAdjuster(TargetAdjuster a) {
        return new BacktestConfig(initialCash, costs, minOrderEur, a);
    }

    public BacktestConfig withMinOrder(BigDecimal m) {
        return new BacktestConfig(initialCash, costs, m, adjuster);
    }
}
