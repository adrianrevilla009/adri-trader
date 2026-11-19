package com.adrianrevilla.trading.core;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Only contract strategies/engine know about a broker; simulated or real is decided by configuration. */
public interface Broker {
    void submit(OrderRequest request);

    void cancel(UUID internalId);

    Optional<OrderState> status(UUID internalId);

    Map<Isin, BigDecimal> positions();

    BigDecimal cash();
}
