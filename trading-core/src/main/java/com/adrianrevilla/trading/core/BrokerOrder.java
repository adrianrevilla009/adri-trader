package com.adrianrevilla.trading.core;

import java.math.BigDecimal;
import java.util.UUID;

/** State of an order as the broker reports it; filled values are cumulative. */
public record BrokerOrder(UUID internalId, OrderState state, BigDecimal filledAmountEur, BigDecimal filledQuantity,
        BigDecimal averagePrice) {}
