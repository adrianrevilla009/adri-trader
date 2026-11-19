package com.adrianrevilla.trading.backtest;

import com.adrianrevilla.trading.core.PortfolioState;
import com.adrianrevilla.trading.core.TargetPortfolio;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Applied between strategy and execution; stands in for the risk module until it is wired in. */
@FunctionalInterface
public interface TargetAdjuster {
    TargetPortfolio adjust(TargetPortfolio target, PortfolioState state);

    TargetAdjuster NONE = (t, s) -> t;

    static TargetAdjuster maxWeightPerAsset(BigDecimal cap) {
        return (t, s) -> {
            Map<com.adrianrevilla.trading.core.Isin, BigDecimal> w = new HashMap<>();
            t.weights().forEach((k, v) -> w.put(k, v.min(cap)));
            return new TargetPortfolio(w);
        };
    }
}
