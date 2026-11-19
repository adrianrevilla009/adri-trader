package com.adrianrevilla.trading.brokersim;

import com.adrianrevilla.trading.core.Broker;
import com.adrianrevilla.trading.core.BrokerOrder;
import com.adrianrevilla.trading.core.BrokerRejectedException;
import com.adrianrevilla.trading.core.ExecutionCosts;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.OrderRequest;
import com.adrianrevilla.trading.core.OrderState;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Own simulator for the arena (ADR-0022). Orders wait until {@link #executePending} is called with the open
 * prices of the next day; they fill at that price moved against the trader by the cost model. Same interface as
 * the real broker.
 */
public final class SimulatedBroker implements Broker {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal DUST = new BigDecimal("1e-9");

    private final ExecutionCosts costs;
    private BigDecimal cash;
    private BigDecimal maxFillFraction = BigDecimal.ONE;
    private final Map<Isin, BigDecimal> units = new HashMap<>();
    private final Map<UUID, OrderRequest> requests = new LinkedHashMap<>();
    private final Map<UUID, BrokerOrder> orders = new LinkedHashMap<>();

    public SimulatedBroker(ExecutionCosts costs, BigDecimal initialCash) {
        this.costs = costs;
        this.cash = initialCash;
    }

    /** Fraction of each order that fills per execution pass (1 = full fills; less simulates partial fills). */
    public synchronized void setMaxFillFraction(BigDecimal fraction) {
        this.maxFillFraction = fraction;
    }

    @Override
    public synchronized void submit(OrderRequest request) throws BrokerRejectedException {
        if (request.amountEur().signum() <= 0) {
            throw new BrokerRejectedException("amount must be positive");
        }
        if (requests.containsKey(request.internalId())) {
            throw new BrokerRejectedException("duplicate order id " + request.internalId());
        }
        requests.put(request.internalId(), request);
        orders.put(request.internalId(), new BrokerOrder(request.internalId(), OrderState.SUBMITTED,
                BigDecimal.ZERO, BigDecimal.ZERO, null));
    }

    @Override
    public synchronized void cancel(UUID id) {
        BrokerOrder o = orders.get(id);
        if (o != null && (o.state() == OrderState.SUBMITTED || o.state() == OrderState.PARTIALLY_FILLED)) {
            orders.put(id, new BrokerOrder(id, OrderState.CANCELLED, o.filledAmountEur(), o.filledQuantity(),
                    o.averagePrice()));
        }
    }

    @Override
    public synchronized Optional<BrokerOrder> lookup(UUID id) {
        return Optional.ofNullable(orders.get(id));
    }

    @Override
    public synchronized Map<Isin, BigDecimal> positions() {
        return Map.copyOf(units);
    }

    @Override
    public synchronized BigDecimal cash() {
        return cash;
    }

    public synchronized int orderCount() {
        return orders.size();
    }

    /** Executes pending orders at the given open prices: sells first, then buys. */
    public synchronized void executePending(Map<Isin, BigDecimal> opens) {
        List<UUID> sells = new ArrayList<>();
        List<UUID> buys = new ArrayList<>();
        for (var e : orders.entrySet()) {
            OrderState s = e.getValue().state();
            if (s == OrderState.SUBMITTED || s == OrderState.PARTIALLY_FILLED) {
                (requests.get(e.getKey()).side() == OrderRequest.Side.SELL ? sells : buys).add(e.getKey());
            }
        }
        sells.forEach(id -> fill(id, opens));
        buys.forEach(id -> fill(id, opens));
    }

    private void fill(UUID id, Map<Isin, BigDecimal> opens) {
        OrderRequest r = requests.get(id);
        BrokerOrder o = orders.get(id);
        BigDecimal open = opens.get(r.isin());
        if (open == null) {
            return; // no price today: stays pending
        }
        BigDecimal adverse = costs.adverseFraction(r.isin());
        BigDecimal remaining = r.amountEur().subtract(o.filledAmountEur());
        BigDecimal target = remaining.min(r.amountEur().multiply(maxFillFraction, MC));
        BigDecimal amount;
        BigDecimal price;
        BigDecimal qty;
        if (r.side() == OrderRequest.Side.BUY) {
            price = open.multiply(BigDecimal.ONE.add(adverse), MC);
            amount = target.min(cash);
            if (amount.signum() <= 0) {
                orders.put(id, new BrokerOrder(id, o.filledAmountEur().signum() > 0 ? OrderState.CANCELLED
                        : OrderState.REJECTED, o.filledAmountEur(), o.filledQuantity(), o.averagePrice()));
                return;
            }
            qty = amount.divide(price, MC);
            cash = cash.subtract(amount);
            units.merge(r.isin(), qty, BigDecimal::add);
        } else {
            price = open.multiply(BigDecimal.ONE.subtract(adverse), MC);
            BigDecimal held = units.getOrDefault(r.isin(), BigDecimal.ZERO);
            qty = target.divide(open, MC).min(held);
            if (qty.signum() <= 0) {
                orders.put(id, new BrokerOrder(id, OrderState.REJECTED, o.filledAmountEur(), o.filledQuantity(),
                        o.averagePrice()));
                return;
            }
            amount = qty.multiply(price, MC);
            cash = cash.add(amount);
            BigDecimal left = held.subtract(qty);
            if (left.compareTo(DUST) < 0) {
                units.remove(r.isin());
            } else {
                units.put(r.isin(), left);
            }
        }
        BigDecimal totalAmount = o.filledAmountEur().add(amount);
        BigDecimal totalQty = o.filledQuantity().add(qty);
        BigDecimal avg = totalAmount.divide(totalQty, MC);
        boolean done = totalAmount.compareTo(r.amountEur().subtract(new BigDecimal("0.000001"))) >= 0;
        orders.put(id, new BrokerOrder(id, done ? OrderState.FILLED : OrderState.PARTIALLY_FILLED, totalAmount,
                totalQty, avg));
    }
}
