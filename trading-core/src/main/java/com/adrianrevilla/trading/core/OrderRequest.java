package com.adrianrevilla.trading.core;

import java.math.BigDecimal;
import java.util.UUID;

/** Order by amount in EUR (eToro accepts amount, units or contracts, only one of them). limitPrice may be null. */
public record OrderRequest(UUID internalId, String agentId, Isin isin, Side side, BigDecimal amountEur,
        BigDecimal limitPrice) {
    public enum Side { BUY, SELL }

    public OrderRequest withAmount(BigDecimal newAmount) {
        return new OrderRequest(internalId, agentId, isin, side, newAmount, limitPrice);
    }
}
