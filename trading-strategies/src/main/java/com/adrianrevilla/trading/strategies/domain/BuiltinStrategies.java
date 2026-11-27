package com.adrianrevilla.trading.strategies.domain;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.Strategy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** The strategies the project ships with (PRD RF-10) plus the control that buys and holds. */
public final class BuiltinStrategies {
    private BuiltinStrategies() {
    }

    public static List<StrategyDefinition> all() {
        return List.of(new BuyAndHoldDefinition(), new TrendDefinition(), new MomentumDefinition(),
                new VolatilityTargetDefinition(), new SwingDefinition());
    }

    private static int i(Map<String, BigDecimal> p, String name) {
        return p.get(name).intValueExact();
    }

    private static double d(Map<String, BigDecimal> p, String name) {
        return p.get(name).doubleValue();
    }

    private abstract static class Base implements StrategyDefinition {
        private final String id;
        private final String name;
        private final String description;
        private final List<ParamSpec> params;

        Base(String id, String name, String description, List<ParamSpec> params) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.params = params;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public List<ParamSpec> params() {
            return params;
        }
    }

    private static final class BuyAndHoldDefinition extends Base {
        BuyAndHoldDefinition() {
            super("buy-and-hold", "Buy and hold",
                    "Buys the first instrument of the universe and keeps it. The control to beat.", List.of());
        }

        @Override
        public Strategy create(String agentId, List<Isin> universe, Map<String, BigDecimal> p) {
            return new BuyAndHold(agentId, universe.get(0));
        }
    }

    private static final class TrendDefinition extends Base {
        TrendDefinition() {
            super("trend", "Trend following",
                    "Holds an instrument while its price is above its moving average (plus a neutral band).",
                    List.of(ParamSpec.integer("window", "Moving average length in days", 200, 20, 400),
                            ParamSpec.decimal("band", "Neutral band around the average (fraction)", "0.01", "0", "0.1")));
        }

        @Override
        public Strategy create(String agentId, List<Isin> universe, Map<String, BigDecimal> p) {
            return new TrendStrategy(agentId, universe, null, i(p, "window"), d(p, "band"));
        }
    }

    private static final class MomentumDefinition extends Base {
        MomentumDefinition() {
            super("momentum", "Momentum rotation",
                    "Holds the instruments with the best return over the lookback period.",
                    List.of(ParamSpec.integer("lookbackDays", "Return lookback in days", 126, 20, 400),
                            ParamSpec.integer("topN", "How many instruments to hold", 3, 1, 10)));
        }

        @Override
        public Strategy create(String agentId, List<Isin> universe, Map<String, BigDecimal> p) {
            return new MomentumRotation(agentId, universe, i(p, "lookbackDays"), Math.min(i(p, "topN"), universe.size()));
        }
    }

    private static final class VolatilityTargetDefinition extends Base {
        VolatilityTargetDefinition() {
            super("vol-target", "Volatility target",
                    "Sizes each position by the inverse of its recent volatility, capped per instrument.",
                    List.of(ParamSpec.integer("volWindow", "Volatility window in days", 60, 10, 250),
                            ParamSpec.decimal("capPerAsset", "Maximum weight of one instrument", "0.25", "0.05", "1")));
        }

        @Override
        public Strategy create(String agentId, List<Isin> universe, Map<String, BigDecimal> p) {
            return new VolatilityTarget(agentId, universe, i(p, "volWindow"), d(p, "capPerAsset"));
        }
    }

    private static final class SwingDefinition extends Base {
        SwingDefinition() {
            super("swing", "Swing (RSI)",
                    "Buys short pullbacks inside an uptrend and sells on recovery or after a maximum holding time.",
                    List.of(ParamSpec.integer("trendWindow", "Trend filter length in days", 200, 50, 400),
                            ParamSpec.integer("entryRsi", "Buy when the RSI falls below", 10, 1, 50),
                            ParamSpec.integer("exitRsi", "Sell when the RSI rises above", 65, 50, 99),
                            ParamSpec.integer("maxHoldDays", "Maximum days in a position", 10, 1, 60),
                            ParamSpec.integer("maxPositions", "Maximum simultaneous positions", 4, 1, 10)));
        }

        @Override
        public Strategy create(String agentId, List<Isin> universe, Map<String, BigDecimal> p) {
            return new SwingRsi(agentId, universe, i(p, "trendWindow"), d(p, "entryRsi"), d(p, "exitRsi"),
                    i(p, "maxHoldDays"), Math.min(i(p, "maxPositions"), universe.size()));
        }
    }
}
