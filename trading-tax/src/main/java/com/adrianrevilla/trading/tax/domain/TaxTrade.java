package com.adrianrevilla.trading.tax.domain;

import com.adrianrevilla.trading.core.domain.Isin;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A trade as the tax ledger needs it, always in EUR (the v1 universe has no currency conversion).
 * {@code amountEur} is the gross amount, {@code feeEur} the commission paid.
 */
public record TaxTrade(LocalDate date, Isin isin, Side side, BigDecimal quantity, BigDecimal amountEur,
        BigDecimal feeEur) {
    public enum Side { BUY, SELL }
}
