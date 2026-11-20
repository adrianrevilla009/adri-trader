package com.adrianrevilla.trading.core.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Only contract the engine knows about a broker; simulated or real is decided by configuration. */
public interface Broker {
    /**
     * @throws BrokerRejectedException the broker definitively refused the order
     * @throws BrokerUnavailableException the outcome is unknown: the order may or may not have arrived
     */
    void submit(OrderRequest request) throws BrokerException;

    void cancel(UUID internalId) throws BrokerException;

    /** Empty when the broker has no trace of the order. */
    Optional<BrokerOrder> lookup(UUID internalId) throws BrokerException;

    Map<Isin, BigDecimal> positions() throws BrokerException;

    BigDecimal cash() throws BrokerException;
}
