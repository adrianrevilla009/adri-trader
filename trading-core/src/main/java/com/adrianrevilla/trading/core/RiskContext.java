package com.adrianrevilla.trading.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Everything the risk rules need to judge an order. {@code ordersSentToday} holds keys "ISIN|SIDE".
 * {@link #apply(OrderRequest)} returns the context as it would be after the order, so that several orders in the
 * same review are judged cumulatively.
 */
public record RiskContext(PortfolioState state, Map<Isin, BigDecimal> lastPrices,
        Map<Isin, BigDecimal> avgDailyValueEur, BigDecimal dayStartEquity, BigDecimal peakEquity,
        Set<String> ordersSentToday, boolean marketOpen) {

    private static final MathContext MC = MathContext.DECIMAL128;

    public RiskContext {
        lastPrices = Map.copyOf(lastPrices);
        avgDailyValueEur = Map.copyOf(avgDailyValueEur);
        ordersSentToday = Set.copyOf(ordersSentToday);
    }

    public BigDecimal positionValue(Isin isin) {
        return state.units(isin).multiply(lastPrices.getOrDefault(isin, BigDecimal.ZERO), MC);
    }

    public BigDecimal investedValue() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Isin isin : state.positions().keySet()) {
            sum = sum.add(positionValue(isin));
        }
        return sum;
    }

    public BigDecimal equity() {
        return state.cash().add(investedValue());
    }

    public static String key(Isin isin, OrderRequest.Side side) {
        return isin.value() + "|" + side;
    }

    public RiskContext apply(OrderRequest order) {
        BigDecimal price = lastPrices.get(order.isin());
        Map<Isin, BigDecimal> units = new HashMap<>(state.positions());
        BigDecimal cash = state.cash();
        if (price != null && price.signum() > 0) {
            BigDecimal qty = order.amountEur().divide(price, MC);
            if (order.side() == OrderRequest.Side.BUY) {
                units.merge(order.isin(), qty, BigDecimal::add);
                cash = cash.subtract(order.amountEur());
            } else {
                units.merge(order.isin(), qty.negate(), BigDecimal::add);
                cash = cash.add(order.amountEur());
            }
        }
        Set<String> sent = new HashSet<>(ordersSentToday);
        sent.add(key(order.isin(), order.side()));
        return new RiskContext(new PortfolioState(units, cash, state.entryDates()), lastPrices, avgDailyValueEur,
                dayStartEquity, peakEquity, sent, marketOpen);
    }
}
