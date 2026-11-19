package com.adrianrevilla.trading.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a target portfolio into orders by amount, sized at the last close. Shared by the backtest, the arena and
 * the live path so all three build orders the same way. Sells come first, then buys.
 */
public final class OrderPlanner {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal NEGLIGIBLE = new BigDecimal("0.01");

    private OrderPlanner() {}

    /**
     * @param minOrderEur orders below this amount are not planned (closing a whole position is always planned)
     */
    public static List<OrderRequest> plan(String agentId, TargetPortfolio target, PortfolioState state,
            Map<Isin, BigDecimal> prices, BigDecimal minOrderEur) {
        BigDecimal equity = state.cash();
        for (var e : state.positions().entrySet()) {
            BigDecimal px = prices.get(e.getKey());
            if (px != null) {
                equity = equity.add(e.getValue().multiply(px, MC));
            }
        }
        Set<Isin> all = new HashSet<>(state.positions().keySet());
        all.addAll(target.weights().keySet());
        List<OrderRequest> sells = new ArrayList<>();
        List<OrderRequest> buys = new ArrayList<>();
        for (Isin isin : all) {
            BigDecimal px = prices.get(isin);
            if (px == null || px.signum() <= 0) {
                continue;
            }
            BigDecimal weight = target.weights().getOrDefault(isin, BigDecimal.ZERO);
            BigDecimal held = state.units(isin);
            BigDecimal current = held.multiply(px, MC);
            BigDecimal delta = weight.multiply(equity, MC).subtract(current);
            if (weight.signum() == 0 && held.signum() > 0) {
                sells.add(new OrderRequest(UUID.randomUUID(), agentId, isin, OrderRequest.Side.SELL, current, null, true));
                continue;
            }
            BigDecimal min = minOrderEur.max(NEGLIGIBLE);
            if (delta.abs().compareTo(min) < 0) {
                continue;
            }
            if (delta.signum() < 0) {
                sells.add(new OrderRequest(UUID.randomUUID(), agentId, isin, OrderRequest.Side.SELL, delta.negate(), null));
            } else {
                buys.add(new OrderRequest(UUID.randomUUID(), agentId, isin, OrderRequest.Side.BUY, delta, null));
            }
        }
        sells.addAll(buys);
        return sells;
    }
}
