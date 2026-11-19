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
 * Trend: each instrument gets an equal slot (1/N) and is held while its close is above its moving average.
 * Hysteresis band avoids repeated entries and exits: enter above SMA*(1+band), leave below SMA*(1-band).
 * Out-of-trend slots stay in cash, or in {@code safeAsset} when configured.
 */
public final class TrendStrategy implements Strategy {
    private final String id;
    private final List<Isin> universe;
    private final Isin safeAsset;
    private final int window;
    private final double band;

    public TrendStrategy(String id, List<Isin> universe, Isin safeAsset, int window, double band) {
        this.id = id;
        this.universe = List.copyOf(universe);
        this.safeAsset = safeAsset;
        this.window = window;
        this.band = band;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public TargetPortfolio decide(MarketSnapshot snapshot, PortfolioState state) {
        BigDecimal slot = StrategySupport.weight(1.0 / universe.size());
        Map<Isin, BigDecimal> target = new HashMap<>();
        int idle = 0;
        for (Isin isin : universe) {
            List<Bar> bars = snapshot.bars(isin);
            OptionalDouble sma = Indicators.sma(bars, window);
            if (sma.isEmpty()) {
                idle++;
                continue;
            }
            double close = Indicators.close(bars.get(bars.size() - 1));
            boolean in = state.holds(isin)
                    ? close >= sma.getAsDouble() * (1 - band)
                    : close > sma.getAsDouble() * (1 + band);
            if (in) {
                target.put(isin, slot);
            } else {
                idle++;
            }
        }
        if (safeAsset != null && idle > 0 && snapshot.latest(safeAsset).isPresent()) {
            target.merge(safeAsset, slot.multiply(BigDecimal.valueOf(idle)), BigDecimal::add);
        }
        return new TargetPortfolio(target);
    }
}
