package com.adrianrevilla.trading.execution;

import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.OrderRequest;
import com.adrianrevilla.trading.core.OrderState;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence of orders and fills. An order is stored here before it is sent to the broker. */
public interface OrderRepository {

    record OrderRecord(OrderRequest request, OrderState state, String note) {}

    /** @throws DuplicateOrderException if an order with the same internal id already exists */
    void create(OrderRequest request);

    void updateState(UUID id, OrderState state, String note);

    Optional<OrderRecord> find(UUID id);

    List<OrderRecord> findOpen();

    /** Cumulative EUR amount already recorded as filled for the order. */
    BigDecimal recordedFillAmount(UUID id);

    void recordFill(UUID orderId, BigDecimal quantity, BigDecimal price, BigDecimal amountEur);

    /** Net units per instrument for an agent, derived from the fills (buys positive, sells negative). */
    Map<Isin, BigDecimal> netUnits(String agentId);

    class DuplicateOrderException extends RuntimeException {
        public DuplicateOrderException(UUID id) {
            super("Order already exists: " + id);
        }
    }
}
