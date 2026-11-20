package com.adrianrevilla.trading.execution;

import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.OrderRequest;
import com.adrianrevilla.trading.core.OrderState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryOrderRepository implements OrderRepository {
    private record Fill(UUID orderId, BigDecimal quantity, BigDecimal amountEur) {}

    private final Map<UUID, OrderRecord> orders = new LinkedHashMap<>();
    private final List<Fill> fills = new ArrayList<>();

    @Override
    public synchronized void create(OrderRequest request) {
        if (orders.containsKey(request.internalId())) {
            throw new DuplicateOrderException(request.internalId());
        }
        orders.put(request.internalId(), new OrderRecord(request, OrderState.CREATED, ""));
    }

    @Override
    public synchronized void updateState(UUID id, OrderState state, String note) {
        OrderRecord r = orders.get(id);
        if (r == null) {
            throw new IllegalArgumentException("Unknown order " + id);
        }
        OrderStateMachine.require(r.state(), state);
        orders.put(id, new OrderRecord(r.request(), state, note));
    }

    @Override
    public synchronized Optional<OrderRecord> find(UUID id) {
        return Optional.ofNullable(orders.get(id));
    }

    @Override
    public synchronized List<OrderRecord> findOpen() {
        return orders.values().stream().filter(o -> OrderStateMachine.isOpen(o.state())).toList();
    }

    @Override
    public synchronized BigDecimal recordedFillAmount(UUID id) {
        return fills.stream().filter(f -> f.orderId().equals(id)).map(Fill::amountEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public synchronized void recordFill(UUID orderId, BigDecimal quantity, BigDecimal price, BigDecimal amountEur) {
        fills.add(new Fill(orderId, quantity, amountEur));
    }

    @Override
    public synchronized boolean recordFillUpTo(UUID orderId, BigDecimal brokerFilledTotalEur, BigDecimal averagePrice) {
        BigDecimal delta = brokerFilledTotalEur.subtract(recordedFillAmount(orderId));
        if (delta.signum() <= 0) {
            return false;
        }
        BigDecimal qty = averagePrice != null && averagePrice.signum() > 0
                ? delta.divide(averagePrice, java.math.MathContext.DECIMAL128) : BigDecimal.ZERO;
        recordFill(orderId, qty, averagePrice, delta);
        return true;
    }

    @Override
    public synchronized Map<Isin, BigDecimal> netUnits(String agentId) {
        Map<Isin, BigDecimal> net = new HashMap<>();
        for (Fill f : fills) {
            OrderRequest req = orders.get(f.orderId()).request();
            if (!req.agentId().equals(agentId)) {
                continue;
            }
            BigDecimal signed = req.side() == OrderRequest.Side.BUY ? f.quantity() : f.quantity().negate();
            net.merge(req.isin(), signed, BigDecimal::add);
        }
        return net;
    }

    public synchronized int size() {
        return orders.size();
    }
}
