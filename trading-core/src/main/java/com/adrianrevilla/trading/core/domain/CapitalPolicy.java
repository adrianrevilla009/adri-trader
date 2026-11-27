package com.adrianrevilla.trading.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Rule that links the minimum order of the broker with the maximum weight of one asset (ADR-0016): an agent needs
 * enough capital for its biggest position to reach the minimum order. Java: a record with validation in the constructor.
 */
public record CapitalPolicy(BigDecimal minOrderAmount, BigDecimal maxWeightPerAsset) {
    public CapitalPolicy {
        Objects.requireNonNull(minOrderAmount, "minOrderAmount");
        Objects.requireNonNull(maxWeightPerAsset, "maxWeightPerAsset");
        if (minOrderAmount.signum() <= 0) {
            throw new IllegalArgumentException("minOrderAmount must be positive: " + minOrderAmount);
        }
        if (maxWeightPerAsset.signum() <= 0 || maxWeightPerAsset.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("maxWeightPerAsset must be in (0, 1]: " + maxWeightPerAsset);
        }
    }

    /** Smallest capital whose biggest allowed position reaches the minimum order (rounded up to cents). */
    public BigDecimal minimumCapital() {
        return minOrderAmount.divide(maxWeightPerAsset, 2, RoundingMode.CEILING);
    }

    /** True when the capital is at least {@link #minimumCapital()}. */
    public boolean accepts(BigDecimal capital) {
        Objects.requireNonNull(capital, "capital");
        return capital.compareTo(minimumCapital()) >= 0;
    }
}
