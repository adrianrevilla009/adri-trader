package com.adrianrevilla.trading.strategies.domain;

import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.MarketSnapshot;
import com.adrianrevilla.trading.core.domain.PortfolioState;
import com.adrianrevilla.trading.core.domain.Strategy;
import com.adrianrevilla.trading.core.domain.TargetPortfolio;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Monthly rotation: ranks instruments by return over {@code lookbackDays}, buys the best {@code topN} with
 * positive momentum (1/topN each). Goes to cash if none is rising. Between rebalances it keeps current weights.
 */
public final class MomentumRotation implements Strategy {
    private final String id;
    private final List<Isin> universe;
    private final int lookbackDays;
    private final int topN;

    public MomentumRotation(String id, List<Isin> universe, int lookbackDays, int topN) {
        this.id = id;
        this.universe = List.copyOf(universe);
        this.lookbackDays = lookbackDays;
        this.topN = topN;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public TargetPortfolio decide(MarketSnapshot snapshot, PortfolioState state) {
        List<Bar> reference = snapshot.bars(universe.get(0));
        if (!StrategySupport.isFirstBarOfMonth(reference)) {
            return StrategySupport.currentWeights(snapshot, state);
        }
        List<Map.Entry<Isin, Double>> scored = new ArrayList<>();
        for (Isin isin : universe) {
            OptionalDouble r = Indicators.returnOver(snapshot.bars(isin), lookbackDays);
            if (r.isPresent() && r.getAsDouble() > 0) {
                scored.add(Map.entry(isin, r.getAsDouble()));
            }
        }
        scored.sort(Comparator.comparingDouble((Map.Entry<Isin, Double> e) -> e.getValue()).reversed());
        BigDecimal slot = StrategySupport.weight(1.0 / topN);
        Map<Isin, BigDecimal> target = new HashMap<>();
        for (int i = 0; i < Math.min(topN, scored.size()); i++) {
            target.put(scored.get(i).getKey(), slot);
        }
        return new TargetPortfolio(target);
    }
}
