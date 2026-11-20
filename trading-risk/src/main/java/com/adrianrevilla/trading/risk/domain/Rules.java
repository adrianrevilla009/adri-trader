package com.adrianrevilla.trading.risk.domain;

import static com.adrianrevilla.trading.core.domain.OrderRequest.Side.BUY;

import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.RiskContext;
import com.adrianrevilla.trading.core.domain.RiskRule;
import com.adrianrevilla.trading.core.domain.RiskRule.Decision;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** The rules of the TRD section 8.1. Each one is independent and stateless. */
public final class Rules {
    private static final MathContext MC = MathContext.DECIMAL128;

    private Rules() {}

    private static final Decision APPROVE = new Decision.Approve();

    /** Instrument must be in the approved universe. */
    public static RiskRule whitelist(RiskConfig cfg) {
        return rule("whitelist", (o, c) -> cfg.whitelist().contains(o.isin())
                ? APPROVE : new Decision.Reject("ISIN " + o.isin().value() + " not in approved universe"));
    }

    /** No instrument above the cap, judged after the order. Trims buys. */
    public static RiskRule maxWeightPerAsset(RiskConfig cfg) {
        return rule("max-weight-per-asset", (o, c) -> {
            if (o.side() != BUY) {
                return APPROVE;
            }
            BigDecimal room = cfg.maxWeightPerAsset().multiply(c.equity(), MC).subtract(c.positionValue(o.isin()));
            return limit(o, room, "weight cap " + cfg.maxWeightPerAsset());
        });
    }

    /** Total invested capital against agent equity. Trims buys. */
    public static RiskRule maxExposure(RiskConfig cfg) {
        return rule("max-exposure", (o, c) -> {
            if (o.side() != BUY) {
                return APPROVE;
            }
            BigDecimal room = cfg.maxExposure().multiply(c.equity(), MC).subtract(c.investedValue());
            return limit(o, room, "exposure cap " + cfg.maxExposure());
        });
    }

    /** After the daily loss limit, no new buys until the next day; sells stay allowed. */
    public static RiskRule maxDailyLoss(RiskConfig cfg) {
        return rule("max-daily-loss", (o, c) -> {
            if (o.side() != BUY || c.dayStartEquity().signum() <= 0) {
                return APPROVE;
            }
            BigDecimal loss = c.dayStartEquity().subtract(c.equity()).divide(c.dayStartEquity(), MC);
            return loss.compareTo(cfg.maxDailyLoss()) >= 0
                    ? new Decision.Reject("daily loss " + pct(loss) + " reached limit " + pct(cfg.maxDailyLoss()))
                    : APPROVE;
        });
    }

    /** Fall from the agent peak: shuts the agent down. Checked per agent, not only per order. */
    public static RiskRule agentDrawdown(RiskConfig cfg) {
        return new RiskRule() {
            @Override
            public String name() {
                return "agent-drawdown";
            }

            @Override
            public Decision evaluate(OrderRequest order, RiskContext c) {
                return evaluateAgent(c);
            }

            @Override
            public Decision evaluateAgent(RiskContext c) {
                if (c.peakEquity().signum() <= 0) {
                    return APPROVE;
                }
                BigDecimal dd = c.peakEquity().subtract(c.equity()).divide(c.peakEquity(), MC);
                return dd.compareTo(cfg.agentMaxDrawdown()) >= 0
                        ? new Decision.Shutdown("drawdown " + pct(dd) + " reached limit " + pct(cfg.agentMaxDrawdown()))
                        : APPROVE;
            }
        };
    }

    /** Order size against average daily traded value. Trims, or rejects if what is left is below the minimum. */
    public static RiskRule liquidity(RiskConfig cfg) {
        return rule("liquidity", (o, c) -> {
            BigDecimal adv = c.avgDailyValueEur().get(o.isin());
            if (adv == null) {
                return new Decision.Reject("no liquidity data for " + o.isin().value());
            }
            return limit(o, cfg.maxParticipation().multiply(adv, MC), "liquidity " + cfg.maxParticipation() + " of ADV");
        });
    }

    /** Fat finger: a limit price too far from the last price is rejected. */
    public static RiskRule fatFinger(RiskConfig cfg) {
        return rule("fat-finger", (o, c) -> {
            if (o.limitPrice() == null) {
                return APPROVE;
            }
            BigDecimal last = c.lastPrices().get(o.isin());
            if (last == null || last.signum() <= 0) {
                return new Decision.Reject("no reference price for " + o.isin().value());
            }
            BigDecimal dev = o.limitPrice().subtract(last).abs().divide(last, MC);
            return dev.compareTo(cfg.fatFingerPct()) > 0
                    ? new Decision.Reject("limit price deviates " + pct(dev) + " from last price")
                    : APPROVE;
        });
    }

    /** Same intention (instrument and side) already sent today. */
    public static RiskRule duplicateOrder() {
        return rule("duplicate-order", (o, c) -> c.ordersSentToday().contains(RiskContext.key(o.isin(), o.side()))
                ? new Decision.Reject("same intention already sent today") : APPROVE);
    }

    /** Market must be open and not in auction. */
    public static RiskRule marketHours() {
        return rule("market-hours", (o, c) -> c.marketOpen() ? APPROVE : new Decision.Defer("market closed or in auction"));
    }

    private static Decision limit(OrderRequest o, BigDecimal room, String why) {
        if (room.compareTo(o.amountEur()) >= 0) {
            return APPROVE;
        }
        if (room.signum() <= 0) {
            return new Decision.Reject("no room: " + why);
        }
        return new Decision.Trim(room.setScale(6, RoundingMode.DOWN), why);
    }

    private static String pct(BigDecimal v) {
        return v.movePointRight(2).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    interface Check {
        Decision apply(OrderRequest order, RiskContext context);
    }

    private static RiskRule rule(String name, Check check) {
        return new RiskRule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Decision evaluate(OrderRequest order, RiskContext context) {
                return check.apply(order, context);
            }
        };
    }
}
