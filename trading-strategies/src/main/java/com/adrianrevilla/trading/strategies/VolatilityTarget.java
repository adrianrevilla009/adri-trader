package com.adrianrevilla.trading.strategies;

import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.MarketSnapshot;
import com.adrianrevilla.trading.core.PortfolioState;
import com.adrianrevilla.trading.core.Strategy;
import com.adrianrevilla.trading.core.TargetPortfolio;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Monthly: weights inversely proportional to recent volatility, capped per asset. What the cap removes stays
 * in cash. Between rebalances it keeps current weights.
 */
public final class VolatilityTarget implements Strategy {
    private final String id;
    private final List<Isin> universe;
    private final int volWindow;
    private final double capPerAsset;

    public VolatilityTarget(String id, List<Isin> universe, int volWindow, double capPerAsset) {
        this.id = id;
        this.universe = List.copyOf(universe);
        this.volWindow = volWindow;
        this.capPerAsset = capPerAsset;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public TargetPortfolio decide(MarketSnapshot snapshot, PortfolioState state) {
        if (!StrategySupport.isFirstBarOfMonth(snapshot.bars(universe.get(0)))) {
            return StrategySupport.currentWeights(snapshot, state);
        }
        Map<Isin, Double> inverse = new HashMap<>();
        double sum = 0;
        for (Isin isin : universe) {
            List<Bar> bars = snapshot.bars(isin);
            OptionalDouble vol = Indicators.volatility(bars, volWindow);
            if (vol.isPresent() && vol.getAsDouble() > 0) {
                double inv = 1.0 / vol.getAsDouble();
                inverse.put(isin, inv);
                sum += inv;
            }
        }
        Map<Isin, BigDecimal> target = new HashMap<>();
        for (var e : inverse.entrySet()) {
            target.put(e.getKey(), StrategySupport.weight(Math.min(capPerAsset, e.getValue() / sum)));
        }
        return new TargetPortfolio(target);
    }
}
