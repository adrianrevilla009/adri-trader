package com.adrianrevilla.trading.brokersim.domain;

import com.adrianrevilla.trading.core.domain.Broker;
import com.adrianrevilla.trading.core.domain.BrokerOrder;
import com.adrianrevilla.trading.core.domain.BrokerRejectedException;
import com.adrianrevilla.trading.core.domain.ExecutionCosts;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.OrderState;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Own simulator, shared by the backtest and the arena so both execute identically (ADR-0022). Orders wait until
 * {@link #executePending} is called with the open prices of the next day; they fill at that price moved against
 * the trader by the cost model. Same interface as the real broker.
 */
public final class SimulatedBroker implements Broker {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal DUST = new BigDecimal("1e-9");

    /** One execution; {@code costEur} is what spread and slippage cost against the open price. */
    public record Fill(LocalDate date, UUID orderId, Isin isin, OrderRequest.Side side, BigDecimal quantity,
            BigDecimal price, BigDecimal amountEur, BigDecimal costEur) {}

    private final ExecutionCosts costs;
    private BigDecimal cash;
    private BigDecimal maxFillFraction = BigDecimal.ONE;
    private final Map<Isin, BigDecimal> units = new HashMap<>();
    private final Map<UUID, OrderRequest> requests = new LinkedHashMap<>();
    private final Map<UUID, BrokerOrder> orders = new LinkedHashMap<>();
    private final List<Fill> fills = new ArrayList<>();

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

    public synchronized List<Fill> fills() {
        return List.copyOf(fills);
    }

    /** Same as {@link #executePending(LocalDate, Map)} without a date (used where dates do not matter). */
    public void executePending(Map<Isin, BigDecimal> opens) {
        executePending(LocalDate.EPOCH, opens);
    }

    /** Executes pending orders at the given open prices: sells first, then buys scaled to the cash available. */
    public synchronized void executePending(LocalDate date, Map<Isin, BigDecimal> opens) {
        List<UUID> sells = new ArrayList<>();
        List<UUID> buys = new ArrayList<>();
        for (var e : orders.entrySet()) {
            OrderState s = e.getValue().state();
            if (s == OrderState.SUBMITTED || s == OrderState.PARTIALLY_FILLED) {
                (requests.get(e.getKey()).side() == OrderRequest.Side.SELL ? sells : buys).add(e.getKey());
            }
        }
        sells.forEach(id -> fillSell(date, id, opens));

        BigDecimal wanted = BigDecimal.ZERO;
        for (UUID id : buys) {
            if (opens.containsKey(requests.get(id).isin())) {
                wanted = wanted.add(buyTarget(id));
            }
        }
        BigDecimal scale = wanted.compareTo(cash) > 0 && wanted.signum() > 0 ? cash.divide(wanted, MC) : BigDecimal.ONE;
        buys.forEach(id -> fillBuy(date, id, opens, scale));
    }

    private BigDecimal buyTarget(UUID id) {
        OrderRequest r = requests.get(id);
        BrokerOrder o = orders.get(id);
        return r.amountEur().subtract(o.filledAmountEur()).min(r.amountEur().multiply(maxFillFraction, MC));
    }

    private void fillBuy(LocalDate date, UUID id, Map<Isin, BigDecimal> opens, BigDecimal scale) {
        OrderRequest r = requests.get(id);
        BrokerOrder o = orders.get(id);
        BigDecimal open = opens.get(r.isin());
        if (open == null) {
            return; // no price today: stays pending
        }
        BigDecimal amount = buyTarget(id).multiply(scale, MC).min(cash);
        if (amount.signum() <= 0) {
            finish(id, o, o.filledAmountEur().signum() > 0 ? OrderState.CANCELLED : OrderState.REJECTED);
            return;
        }
        BigDecimal price = open.multiply(BigDecimal.ONE.add(costs.adverseFraction(r.isin())), MC);
        BigDecimal qty = amount.divide(price, MC);
        cash = cash.subtract(amount);
        units.merge(r.isin(), qty, BigDecimal::add);
        record(date, id, r, o, qty, price, amount, qty.multiply(price.subtract(open), MC));
    }

    private void fillSell(LocalDate date, UUID id, Map<Isin, BigDecimal> opens) {
        OrderRequest r = requests.get(id);
        BrokerOrder o = orders.get(id);
        BigDecimal open = opens.get(r.isin());
        if (open == null) {
            return;
        }
        BigDecimal held = units.getOrDefault(r.isin(), BigDecimal.ZERO);
        BigDecimal qty = r.closePosition() ? held : buyTarget(id).divide(open, MC).min(held);
        if (qty.signum() <= 0) {
            finish(id, o, OrderState.REJECTED);
            return;
        }
        BigDecimal price = open.multiply(BigDecimal.ONE.subtract(costs.adverseFraction(r.isin())), MC);
        BigDecimal amount = qty.multiply(price, MC);
        cash = cash.add(amount);
        BigDecimal left = held.subtract(qty);
        if (left.compareTo(DUST) < 0) {
            units.remove(r.isin());
        } else {
            units.put(r.isin(), left);
        }
        record(date, id, r, o, qty, price, amount, qty.multiply(open.subtract(price), MC));
    }

    private void record(LocalDate date, UUID id, OrderRequest r, BrokerOrder o, BigDecimal qty, BigDecimal price,
            BigDecimal amount, BigDecimal cost) {
        fills.add(new Fill(date, id, r.isin(), r.side(), qty, price, amount, cost));
        BigDecimal totalAmount = o.filledAmountEur().add(amount);
        BigDecimal totalQty = o.filledQuantity().add(qty);
        boolean done = r.closePosition()
                || totalAmount.compareTo(r.amountEur().subtract(new BigDecimal("0.000001"))) >= 0;
        orders.put(id, new BrokerOrder(id, done ? OrderState.FILLED : OrderState.PARTIALLY_FILLED, totalAmount,
                totalQty, totalAmount.divide(totalQty, MC)));
    }

    private void finish(UUID id, BrokerOrder o, OrderState state) {
        orders.put(id, new BrokerOrder(id, state, o.filledAmountEur(), o.filledQuantity(), o.averagePrice()));
    }
}
