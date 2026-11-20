package com.adrianrevilla.trading.core.domain;

import java.math.BigDecimal;
import java.util.Map;

/** Target weights per instrument; they must be in [0,1] and sum to at most 1. The rest is cash. */
public record TargetPortfolio(Map<Isin, BigDecimal> weights) {
    public TargetPortfolio {
        weights = Map.copyOf(weights);
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal w : weights.values()) {
            if (w.signum() < 0 || w.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("Weight out of [0,1]: " + w);
            }
            sum = sum.add(w);
        }
        if (sum.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Weights sum above 100%: " + sum);
        }
    }

    public static TargetPortfolio allCash() {
        return new TargetPortfolio(Map.of());
    }
}
