package com.adrianrevilla.trading.core;

import java.math.BigDecimal;
import java.util.UUID;

/** Order by amount in EUR (eToro accepts amount, units or contracts, only one of them). */
public record OrderRequest(UUID internalId, Isin isin, Side side, BigDecimal amountEur, BigDecimal limitPrice) {
    public enum Side { BUY, SELL }
}
