package com.adrianrevilla.trading.core;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Order by amount in EUR (eToro accepts amount, units or contracts, only one of them). limitPrice may be null.
 * {@code closePosition} marks a sell that closes the whole position whatever the price moved to.
 */
public record OrderRequest(UUID internalId, String agentId, Isin isin, Side side, BigDecimal amountEur,
        BigDecimal limitPrice, boolean closePosition) {
    public enum Side { BUY, SELL }

    public OrderRequest(UUID internalId, String agentId, Isin isin, Side side, BigDecimal amountEur,
            BigDecimal limitPrice) {
        this(internalId, agentId, isin, side, amountEur, limitPrice, false);
    }

    public OrderRequest withAmount(BigDecimal newAmount) {
        return new OrderRequest(internalId, agentId, isin, side, newAmount, limitPrice, closePosition);
    }
}
