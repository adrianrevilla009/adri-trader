package com.adrianrevilla.trading.strategies.domain;

import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.MarketSnapshot;
import com.adrianrevilla.trading.core.domain.PortfolioState;
import com.adrianrevilla.trading.core.domain.TargetPortfolio;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class StrategySupport {
    private StrategySupport() {}

    /** Current weights derived from the state, valued at the latest close. Used when no rebalance is due. */
    static TargetPortfolio currentWeights(MarketSnapshot snapshot, PortfolioState state) {
        BigDecimal equity = state.cash();
        Map<Isin, BigDecimal> values = new HashMap<>();
        for (var e : state.positions().entrySet()) {
            Optional<Bar> bar = snapshot.latest(e.getKey());
            if (bar.isPresent()) {
                BigDecimal v = e.getValue().multiply(bar.orElseThrow().close());
                values.put(e.getKey(), v);
                equity = equity.add(v);
            }
        }
        if (equity.signum() <= 0) {
            return TargetPortfolio.allCash();
        }
        Map<Isin, BigDecimal> w = new HashMap<>();
        for (var e : values.entrySet()) {
            // rounded down so that rounding can never push the sum above 100 %
            w.put(e.getKey(), e.getValue().divide(equity, MathContext.DECIMAL64).setScale(10, java.math.RoundingMode.DOWN));
        }
        return new TargetPortfolio(w);
    }

    /** True when the last bar of the reference series opens a new calendar month. */
    static boolean isFirstBarOfMonth(List<Bar> bars) {
        if (bars.size() < 2) {
            return bars.size() == 1;
        }
        var last = bars.get(bars.size() - 1).date();
        var prev = bars.get(bars.size() - 2).date();
        return last.getMonth() != prev.getMonth() || last.getYear() != prev.getYear();
    }

    static BigDecimal weight(double w) {
        return BigDecimal.valueOf(Math.floor(w * 1e9) / 1e9);
    }
}
