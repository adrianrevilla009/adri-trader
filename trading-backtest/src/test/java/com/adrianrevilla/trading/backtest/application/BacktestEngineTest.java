package com.adrianrevilla.trading.backtest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.adrianrevilla.trading.backtest.domain.BacktestConfig;
import com.adrianrevilla.trading.backtest.domain.CostModel;
import com.adrianrevilla.trading.backtest.domain.Metrics;
import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.strategies.domain.BuyAndHold;
import com.adrianrevilla.trading.strategies.domain.TrendStrategy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BacktestEngineTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B3XXRP09");
    private static final LocalDate START = LocalDate.parse("2020-01-01");
    private final BacktestEngine engine = new BacktestEngine();

    private static BacktestConfig noCosts() {
        return BacktestConfig.defaults().withCosts(CostModel.none()).withMinOrder(BigDecimal.ZERO);
    }

    @Test
    void buyAndHoldWithoutCostsMatchesPriceGrowthFromTheNextOpen() {
        List<Bar> bars = TestData.constantGrowth(START, 300, 0.001);
        var result = engine.run(new BuyAndHold("bh", A), Map.of(A, bars), START, LocalDate.parse("2030-01-01"), noCosts());
        // decision on day 1 close, execution at the open of day 2
        double expected = 100.0 * bars.get(299).close().doubleValue() / bars.get(1).open().doubleValue();
        double last = result.equity().get(result.equity().size() - 1).equity().doubleValue();
        assertThat(last).isCloseTo(expected, within(1e-6));
        assertThat(result.equity().get(0).equity()).isEqualByComparingTo("100"); // nothing bought on the decision day
        assertThat(result.trades()).hasSize(1);
    }

    @Test
    void costsReduceResultAndAreReportedOnTrades() {
        List<Bar> bars = TestData.constantGrowth(START, 300, 0.001);
        var free = engine.run(new BuyAndHold("bh", A), Map.of(A, bars), START, LocalDate.parse("2030-01-01"), noCosts());
        var costly = engine.run(new BuyAndHold("bh", A), Map.of(A, bars), START, LocalDate.parse("2030-01-01"),
                noCosts().withCosts(CostModel.of("100", "50"))); // 0.5 % + 0.5 % adverse
        double f = free.equity().get(free.equity().size() - 1).equity().doubleValue();
        double c = costly.equity().get(costly.equity().size() - 1).equity().doubleValue();
        assertThat(c).isLessThan(f);
        assertThat(c / f).isCloseTo(1.0 / 1.01, within(1e-6));
        assertThat(costly.trades().get(0).costEur().signum()).isPositive();
    }

    @Test
    void neverSpendsMoreThanTheCashItHas() {
        List<Bar> bars = TestData.constantGrowth(START, 50, 0.001);
        var all = new com.adrianrevilla.trading.core.domain.Strategy() {
            public String id() { return "greedy"; }
            public com.adrianrevilla.trading.core.domain.TargetPortfolio decide(
                    com.adrianrevilla.trading.core.domain.MarketSnapshot s, com.adrianrevilla.trading.core.domain.PortfolioState st) {
                return new com.adrianrevilla.trading.core.domain.TargetPortfolio(Map.of(A, new BigDecimal("0.5"), B, new BigDecimal("0.5")));
            }
        };
        var result = engine.run(all, Map.of(A, bars, B, bars), START, LocalDate.parse("2030-01-01"),
                BacktestConfig.defaults().withMinOrder(BigDecimal.ZERO));
        assertThat(result.equity()).allSatisfy(p -> assertThat(p.equity().signum()).isPositive());
    }

    @Test
    void noLookAheadChangingFutureDataDoesNotChangePastDecisions() {
        List<Bar> a = TestData.randomWalk(START, 600, 1, 0.0003, 0.01);
        List<Bar> b = TestData.randomWalk(START, 600, 2, 0.0003, 0.01);
        LocalDate cutoff = a.get(400).date();
        var strategy = new TrendStrategy("trend", List.of(A, B), null, 50, 0.01);

        var base = engine.run(strategy, Map.of(A, a, B, b), START, a.get(599).date(), BacktestConfig.defaults());

        List<Bar> a2 = new ArrayList<>(a.subList(0, 401));
        List<Bar> b2 = new ArrayList<>(b.subList(0, 401));
        for (int i = 401; i < 600; i++) { // rewrite the future completely
            BigDecimal px = BigDecimal.valueOf(50 + i % 7);
            a2.add(new Bar(a.get(i).date(), px, px, px, px, 1));
            b2.add(new Bar(b.get(i).date(), px, px, px, px, 1));
        }
        var changed = engine.run(strategy, Map.of(A, a2, B, b2), START, a.get(599).date(), BacktestConfig.defaults());

        var basePast = base.equity().stream().filter(p -> !p.date().isAfter(cutoff)).toList();
        var changedPast = changed.equity().stream().filter(p -> !p.date().isAfter(cutoff)).toList();
        assertThat(changedPast).isEqualTo(basePast);
        var baseTrades = base.trades().stream().filter(t -> !t.date().isAfter(cutoff)).toList();
        var changedTrades = changed.trades().stream().filter(t -> !t.date().isAfter(cutoff)).toList();
        assertThat(changedTrades).isEqualTo(baseTrades);
        assertThat(baseTrades).isNotEmpty();
    }

    @Test
    void trendStrategyAvoidsASustainedCrash() {
        // 250 days up, then 250 days of -1 % per day
        List<Bar> bars = TestData.series(START, 500, 100, i -> i < 250 ? 0.002 : -0.01);
        var trend = engine.run(new TrendStrategy("trend", List.of(A), null, 50, 0.0), Map.of(A, bars), START,
                bars.get(499).date(), noCosts());
        var hold = engine.run(new BuyAndHold("bh", A), Map.of(A, bars), START, bars.get(499).date(), noCosts());
        assertThat(Metrics.of(trend).maxDrawdown()).isLessThan(Metrics.of(hold).maxDrawdown());
        assertThat(trend.equity().get(499).equity().doubleValue()).isGreaterThan(hold.equity().get(499).equity().doubleValue());
    }
}
