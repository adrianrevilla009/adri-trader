package com.adrianrevilla.trading.risk.domain;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.PortfolioState;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Limits at account level, not only per agent: several agents buying the same ETF can blow up the real
 * exposure of the account (hidden concentration).
 */
public final class AccountLimits {
    private static final MathContext MC = MathContext.DECIMAL128;

    public record Violation(Isin isin, BigDecimal weight, BigDecimal limit) {}

    private final BigDecimal maxWeightPerAsset;

    public AccountLimits(BigDecimal maxWeightPerAsset) {
        this.maxWeightPerAsset = maxWeightPerAsset;
    }

    public List<Violation> check(Map<String, PortfolioState> agents, Map<Isin, BigDecimal> prices) {
        BigDecimal equity = BigDecimal.ZERO;
        Map<Isin, BigDecimal> value = new HashMap<>();
        for (PortfolioState s : agents.values()) {
            equity = equity.add(s.cash());
            for (var e : s.positions().entrySet()) {
                BigDecimal v = e.getValue().multiply(prices.getOrDefault(e.getKey(), BigDecimal.ZERO), MC);
                value.merge(e.getKey(), v, BigDecimal::add);
                equity = equity.add(v);
            }
        }
        List<Violation> out = new ArrayList<>();
        if (equity.signum() <= 0) {
            return out;
        }
        for (var e : value.entrySet()) {
            BigDecimal w = e.getValue().divide(equity, MC);
            if (w.compareTo(maxWeightPerAsset) > 0) {
                out.add(new Violation(e.getKey(), w, maxWeightPerAsset));
            }
        }
        return out;
    }
}
