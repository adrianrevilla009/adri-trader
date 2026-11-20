package com.adrianrevilla.trading.arena.domain;

import com.adrianrevilla.trading.backtest.domain.BacktestResult;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.strategies.domain.BuyAndHold;
import com.adrianrevilla.trading.strategies.domain.RandomMatchedStrategy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builders of the control agents. */
public final class Controls {
    private Controls() {}

    /** Buys the benchmark and holds it. */
    public static BuyAndHold benchmark(Isin benchmark) {
        return new BuyAndHold("benchmark-" + benchmark.value(), benchmark);
    }

    /**
     * Random agent with the same exposure and rotation as a reference run: exposure is the average share of equity
     * invested, rotation the share of days that had trades.
     */
    public static RandomMatchedStrategy matchedTo(String id, BacktestResult reference, List<Isin> universe,
            int positions, long seed) {
        double exposure = reference.equity().stream()
                .mapToDouble(p -> p.invested().doubleValue() / p.equity().doubleValue()).average().orElse(1.0);
        Set<java.time.LocalDate> tradeDays = new HashSet<>();
        reference.trades().forEach(t -> tradeDays.add(t.date()));
        double rotation = reference.equity().isEmpty() ? 0 : (double) tradeDays.size() / reference.equity().size();
        // a fully idle reference would give a control that never trades; keep at least some activity
        return new RandomMatchedStrategy(id, universe, seed, Math.max(0.05, Math.min(1.0, exposure)),
                Math.max(0.005, Math.min(1.0, rotation)), positions);
    }
}
