package com.adrianrevilla.trading.tax;

import java.math.BigDecimal;

/**
 * Modelo 720: securities held at a foreign broker must be declared when a category exceeds 50,000 EUR. The
 * bot warns earlier, at 40,000 EUR (ADR-0017), because the broker does not report it for the taxpayer.
 */
public final class Model720Monitor {
    public static final BigDecimal WARNING_THRESHOLD = new BigDecimal("40000");
    public static final BigDecimal LEGAL_THRESHOLD = new BigDecimal("50000");

    public enum Level { NONE, WARNING, OBLIGATION }

    private Model720Monitor() {}

    /** @param valueAtYearEnd value of the securities category at 31 December, in EUR */
    public static Level check(BigDecimal valueAtYearEnd) {
        if (valueAtYearEnd.compareTo(LEGAL_THRESHOLD) > 0) {
            return Level.OBLIGATION;
        }
        return valueAtYearEnd.compareTo(WARNING_THRESHOLD) >= 0 ? Level.WARNING : Level.NONE;
    }
}
