package com.adrianrevilla.trading.execution.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Remembers which eToro order id belongs to each internal order. It must survive restarts (a database in
 * production), because eToro has no idempotency key and the order can only be found again through this link.
 */
public interface BrokerRefStore {
    void put(UUID internalId, String brokerOrderId);

    Optional<String> get(UUID internalId);
}
