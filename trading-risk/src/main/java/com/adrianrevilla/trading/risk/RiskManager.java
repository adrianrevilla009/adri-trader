package com.adrianrevilla.trading.risk;

import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.KillSwitch;
import com.adrianrevilla.trading.core.OrderRequest;
import com.adrianrevilla.trading.core.OrderRequest.Side;
import com.adrianrevilla.trading.core.RiskContext;
import com.adrianrevilla.trading.core.RiskRule;
import com.adrianrevilla.trading.core.RiskRule.Decision;
import com.adrianrevilla.trading.core.TargetPortfolio;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Receives the target portfolio of a strategy, compares it with the real state and returns approved, trimmed,
 * rejected or deferred orders. Independent of any strategy; it has the last word.
 */
public final class RiskManager {
    private static final MathContext MC = MathContext.DECIMAL128;

    public record Reviewed(OrderRequest proposed, Decision decision, String rule, OrderRequest finalOrder) {
        public boolean goesOut() {
            return finalOrder != null;
        }
    }

    public record Review(List<Reviewed> orders, boolean shutdownAgent, String shutdownReason) {
        public List<OrderRequest> approved() {
            return orders.stream().filter(Reviewed::goesOut).map(Reviewed::finalOrder).toList();
        }
    }

    private final List<RiskRule> rules;
    private final KillSwitch killSwitch;
    private final BigDecimal minOrderEur;

    public RiskManager(List<RiskRule> rules, KillSwitch killSwitch, BigDecimal minOrderEur) {
        this.rules = List.copyOf(rules);
        this.killSwitch = killSwitch;
        this.minOrderEur = minOrderEur;
    }

    /** Standard rule set in the order of the TRD. */
    public static RiskManager standard(RiskConfig cfg, KillSwitch killSwitch) {
        return new RiskManager(List.of(
                Rules.agentDrawdown(cfg), Rules.whitelist(cfg), Rules.marketHours(), Rules.duplicateOrder(),
                Rules.fatFinger(cfg), Rules.maxDailyLoss(cfg), Rules.maxWeightPerAsset(cfg), Rules.maxExposure(cfg),
                Rules.liquidity(cfg)), killSwitch, cfg.minOrderEur());
    }

    public Review review(String agentId, TargetPortfolio target, RiskContext start) {
        List<OrderRequest> proposals = toOrders(agentId, target, start);
        List<Reviewed> out = new ArrayList<>();
        RiskContext ctx = start;
        boolean shutdown = false;
        String shutdownReason = null;

        for (OrderRequest proposed : proposals) {
            if (shutdown) {
                out.add(new Reviewed(proposed, new Decision.Reject("agent shut down"), "agent-drawdown", null));
                continue;
            }
            if (killSwitch.isActive()) {
                out.add(new Reviewed(proposed, new Decision.Reject("kill switch active"), "kill-switch", null));
                continue;
            }
            OrderRequest current = proposed;
            Decision last = new Decision.Approve();
            String lastRule = "";
            boolean stopped = false;
            for (RiskRule rule : rules) {
                Decision d = rule.evaluate(current, ctx);
                if (d instanceof Decision.Approve) {
                    continue;
                }
                lastRule = rule.name();
                if (d instanceof Decision.Trim trim) {
                    current = current.withAmount(trim.newAmountEur());
                    last = d;
                    if (current.amountEur().compareTo(minOrderEur) < 0) {
                        last = new Decision.Reject("amount below minimum after trim by " + rule.name());
                        stopped = true;
                        break;
                    }
                    continue;
                }
                if (d instanceof Decision.Shutdown) {
                    shutdown = true;
                    shutdownReason = ((Decision.Shutdown) d).reason();
                }
                last = d;
                stopped = true;
                break;
            }
            if (stopped) {
                out.add(new Reviewed(proposed, last, lastRule, null));
            } else {
                out.add(new Reviewed(proposed, last, lastRule, current));
                ctx = ctx.apply(current);
            }
        }
        return new Review(out, shutdown, shutdownReason);
    }

    /** Orders to reach the target at last prices. Sells first, then buys (also how they are sent). */
    static List<OrderRequest> toOrders(String agentId, TargetPortfolio target, RiskContext ctx) {
        BigDecimal equity = ctx.equity();
        Set<Isin> all = new HashSet<>(ctx.state().positions().keySet());
        all.addAll(target.weights().keySet());
        List<OrderRequest> sells = new ArrayList<>();
        List<OrderRequest> buys = new ArrayList<>();
        for (Isin isin : all) {
            BigDecimal price = ctx.lastPrices().get(isin);
            if (price == null || price.signum() <= 0) {
                continue;
            }
            BigDecimal desired = target.weights().getOrDefault(isin, BigDecimal.ZERO).multiply(equity, MC);
            BigDecimal delta = desired.subtract(ctx.positionValue(isin));
            if (delta.abs().compareTo(new BigDecimal("0.01")) < 0) {
                continue;
            }
            Side side = delta.signum() > 0 ? Side.BUY : Side.SELL;
            OrderRequest o = new OrderRequest(UUID.randomUUID(), agentId, isin, side, delta.abs(), null);
            (side == Side.SELL ? sells : buys).add(o);
        }
        sells.addAll(buys);
        return sells;
    }
}
