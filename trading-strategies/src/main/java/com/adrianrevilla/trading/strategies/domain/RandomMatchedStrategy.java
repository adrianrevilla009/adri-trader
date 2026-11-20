package com.adrianrevilla.trading.strategies.domain;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.MarketSnapshot;
import com.adrianrevilla.trading.core.domain.PortfolioState;
import com.adrianrevilla.trading.core.domain.Strategy;
import com.adrianrevilla.trading.core.domain.TargetPortfolio;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Control agent: random portfolios with the same exposure and rotation as a real strategy and a fixed seed. What
 * it earns shows how much can be won by luck alone; a strategy that does not clearly beat these controls has
 * proved nothing. Randomness is a pure function of (seed, date), so the same seed always gives the same decisions.
 */
public final class RandomMatchedStrategy implements Strategy {
    private final String id;
    private final List<Isin> universe;
    private final long seed;
    private final double exposure;
    // nosemgrep: java-money-floating-point, java-money-as-double -- a probability in [0,1], not an amount of money
    private final double rebalanceProbability;
    private final int positions;

    /**
     * @param exposure share of capital invested when invested, in (0,1]
     * @param rebalanceProbability chance per day of drawing a new random portfolio
     * @param positions number of instruments held at once
     */
    public RandomMatchedStrategy(String id, List<Isin> universe, long seed, double exposure,
            // nosemgrep: java-money-as-double -- a probability in [0,1], not an amount of money
            double rebalanceProbability, int positions) {
        this.id = id;
        this.universe = List.copyOf(universe);
        this.seed = seed;
        this.exposure = exposure;
        this.rebalanceProbability = rebalanceProbability;
        this.positions = Math.min(positions, universe.size());
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public TargetPortfolio decide(MarketSnapshot snapshot, PortfolioState state) {
        // nosemgrep: java-weak-crypto-algorithm -- seeded PRNG for a reproducible control strategy, not a security use
        Random rnd = new Random(seed * 1_000_003L + snapshot.asOf().toEpochDay());
        boolean first = state.positions().isEmpty() && rnd.nextDouble() < 0.5; // lets the control enter early
        if (rnd.nextDouble() >= rebalanceProbability && !first) {
            return StrategySupport.currentWeights(snapshot, state);
        }
        List<Isin> pool = new ArrayList<>(universe);
        pool.removeIf(i -> snapshot.latest(i).isEmpty());
        Collections.shuffle(pool, rnd);
        int k = Math.min(positions, pool.size());
        Map<Isin, BigDecimal> w = new HashMap<>();
        for (int i = 0; i < k; i++) {
            w.put(pool.get(i), StrategySupport.weight(exposure / k));
        }
        return new TargetPortfolio(w);
    }
}
