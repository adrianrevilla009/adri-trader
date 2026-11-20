package com.adrianrevilla.trading.tax.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Estimate of the tax on savings income from sales: 19 % up to 6,000 EUR, 21 % up to 50,000 and 23 % up to
 * 200,000 (rates quoted in the project brief; confirm them with an adviser). Higher rates are not implemented:
 * above 200,000 EUR the method refuses instead of guessing.
 */
public final class SavingsTax {
    private static final BigDecimal[] LIMITS = {new BigDecimal("6000"), new BigDecimal("50000"), new BigDecimal("200000")};
    private static final BigDecimal[] RATES = {new BigDecimal("0.19"), new BigDecimal("0.21"), new BigDecimal("0.23")};

    private SavingsTax() {}

    /** A net loss gives 0 here; carrying it forward to later years is not implemented. */
    public static BigDecimal estimate(BigDecimal netGain) {
        if (netGain.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (netGain.compareTo(LIMITS[2]) > 0) {
            throw new IllegalArgumentException("Gains above 200,000 EUR need the higher rates, not implemented");
        }
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal from = BigDecimal.ZERO;
        for (int i = 0; i < LIMITS.length; i++) {
            BigDecimal slice = netGain.min(LIMITS[i]).subtract(from);
            if (slice.signum() > 0) {
                tax = tax.add(slice.multiply(RATES[i]));
            }
            from = LIMITS[i];
        }
        return tax.setScale(2, RoundingMode.HALF_EVEN);
    }
}
