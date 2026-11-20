package com.adrianrevilla.trading.arena.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.arena.domain.Agent;
import com.adrianrevilla.trading.arena.domain.AgentStatus;
import com.adrianrevilla.trading.arena.domain.ArenaReport;
import com.adrianrevilla.trading.arena.domain.Controls;
import com.adrianrevilla.trading.arena.domain.DayReport;
import com.adrianrevilla.trading.arena.domain.FinalistCriteria;
import com.adrianrevilla.trading.arena.domain.LiveVsBacktest;
import com.adrianrevilla.trading.backtest.application.BacktestEngine;
import com.adrianrevilla.trading.backtest.domain.BacktestConfig;
import com.adrianrevilla.trading.backtest.domain.CostModel;
import com.adrianrevilla.trading.backtest.domain.Metrics;
import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.MarketSnapshot;
import com.adrianrevilla.trading.core.domain.PortfolioState;
import com.adrianrevilla.trading.core.domain.Strategy;
import com.adrianrevilla.trading.core.domain.TargetPortfolio;
import com.adrianrevilla.trading.risk.domain.RiskConfig;
import com.adrianrevilla.trading.strategies.domain.BuyAndHold;
import com.adrianrevilla.trading.strategies.domain.TrendStrategy;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntToDoubleFunction;
import org.junit.jupiter.api.Test;

class ArenaTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B3XXRP09");
    private static final Isin C = new Isin("IE00BK5BQT80");
    private static final BigDecimal CAPITAL = new BigDecimal("100");
    private static final CostModel COSTS = CostModel.of("10", "5");

    private static List<Bar> series(int days, IntToDoubleFunction dailyReturn) {
        List<Bar> bars = new ArrayList<>();
        double p = 100;
        LocalDate d = LocalDate.parse("2021-01-04");
        for (int i = 0; i < days; ) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                if (i > 0) {
                    p *= 1.0 + dailyReturn.applyAsDouble(i);
                }
                BigDecimal v = BigDecimal.valueOf(p).setScale(6, java.math.RoundingMode.HALF_EVEN);
                bars.add(new Bar(d, v, v, v, v, 1_000_000));
                i++;
            }
            d = d.plusDays(1);
        }
        return bars;
    }

    private static List<Bar> walk(int days, long seed) {
        Random rnd = new Random(seed);
        return series(days, i -> 0.0003 + 0.01 * rnd.nextGaussian());
    }

    private static RiskConfig permissive() {
        return new RiskConfig(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("1000"),
                new BigDecimal("0.01"), new BigDecimal("0.05"), Set.of(A, B, C));
    }

    private static Map<Isin, List<Bar>> universe(int days) {
        return Map.of(A, walk(days, 1), B, walk(days, 2), C, walk(days, 3));
    }

    private Arena arena(Map<Isin, List<Bar>> data, List<String> alerts) {
        return new Arena(data, COSTS, (level, msg) -> alerts.add(level + ":" + msg));
    }

    private static List<LocalDate> days(Map<Isin, List<Bar>> data) {
        return data.get(A).stream().map(Bar::date).toList();
    }

    @Test
    void liveArenaMatchesTheBacktestOfTheSameStrategy() {
        var data = universe(500);
        var alerts = new ArrayList<String>();
        var arena = arena(data, alerts);
        var trend = new TrendStrategy("trend", List.of(A, B, C), null, 30, 0.01);
        arena.addAgent("trend", Agent.Kind.STRATEGY, trend, CAPITAL, permissive());
        days(data).forEach(arena::runDay);

        var cfg = BacktestConfig.defaults().withCosts(COSTS).withMinOrder(new BigDecimal("0.01"));
        var cmp = arena.compareWithBacktest("trend", cfg);

        assertThat(cmp.commonDays()).isGreaterThan(400);
        assertThat(cmp.decisionAgreement()).isGreaterThanOrEqualTo(0.95);
        assertThat(cmp.maxEquityDiff()).isLessThanOrEqualTo(0.005);
        assertThat(alerts).isEmpty();
    }

    @Test
    void oneFailingAgentNeverStopsTheOthers() {
        var data = universe(60);
        var alerts = new ArrayList<String>();
        var arena = arena(data, alerts);
        Strategy broken = new Strategy() {
            public String id() { return "broken"; }
            public TargetPortfolio decide(MarketSnapshot s, PortfolioState st) {
                if (s.bars(A).size() == 10) {
                    throw new IllegalStateException("boom");
                }
                return TargetPortfolio.allCash();
            }
        };
        arena.addAgent("broken", Agent.Kind.STRATEGY, broken, CAPITAL, permissive());
        arena.addAgent("bench", Agent.Kind.BENCHMARK, new BuyAndHold("bench", A), CAPITAL, permissive());

        List<DayReport> reports = days(data).stream().map(arena::runDay).toList();

        assertThat(reports.stream().flatMap(r -> r.agents().stream())
                .filter(a -> a.agentId().equals("broken") && a.error() != null)).hasSize(1);
        assertThat(arena.agent("bench").equity()).hasSize(60);
        assertThat(arena.agent("broken").equity()).hasSize(60); // it kept running the next days
        assertThat(alerts).anyMatch(a -> a.startsWith("CRITICAL:Agent broken failed"));
    }

    @Test
    void anAgentFallingTooFarFromItsPeakIsShutDownAndStopsTrading() {
        // up 30 days, then -2 % a day
        var crash = Map.of(A, series(120, i -> i < 30 ? 0.003 : -0.02));
        var alerts = new ArrayList<String>();
        var arena = arena(crash, alerts);
        var cfg = new RiskConfig(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.15"),
                new BigDecimal("1000"), new BigDecimal("0.01"), new BigDecimal("0.05"), Set.of(A));
        var agent = arena.addAgent("hold", Agent.Kind.STRATEGY, new BuyAndHold("hold", A), CAPITAL, cfg);
        days(crash).forEach(arena::runDay);

        assertThat(agent.status()).isEqualTo(AgentStatus.RETIRED);
        assertThat(alerts).anyMatch(a -> a.contains("shut down") && a.contains("drawdown"));
        assertThat(agent.equity().size()).isLessThan(120); // retired agents are not valued any more
    }

    @Test
    void lifecycleOnlyAllowsTheDefinedMoves() {
        assertThat(AgentStatus.CANDIDATE.canMoveTo(AgentStatus.ARENA)).isTrue();
        assertThat(AgentStatus.ARENA.canMoveTo(AgentStatus.FINALIST)).isTrue();
        assertThat(AgentStatus.ARENA.canMoveTo(AgentStatus.REAL_SMALL)).isFalse();
        assertThat(AgentStatus.RETIRED.canMoveTo(AgentStatus.ARENA)).isFalse();
        var arena = arena(universe(10), new ArrayList<>());
        var a = arena.addAgent("x", Agent.Kind.STRATEGY, new BuyAndHold("x", A), CAPITAL, permissive());
        assertThatThrownBy(() -> a.moveTo(AgentStatus.REAL_SMALL)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void randomControlsAreDeterministicAndMatchTheReferenceExposure() {
        var data = universe(400);
        var engine = new BacktestEngine();
        var cfg = BacktestConfig.defaults().withCosts(COSTS).withMinOrder(new BigDecimal("0.01"));
        var trend = new TrendStrategy("trend", List.of(A, B, C), null, 30, 0.01);
        var ref = engine.run(trend, data, data.get(A).get(0).date(), data.get(A).get(399).date(), cfg);

        var r1 = Controls.matchedTo("rnd", ref, List.of(A, B, C), 2, 42);
        var r2 = Controls.matchedTo("rnd", ref, List.of(A, B, C), 2, 42);
        var run1 = engine.run(r1, data, data.get(A).get(0).date(), data.get(A).get(399).date(), cfg);
        var run2 = engine.run(r2, data, data.get(A).get(0).date(), data.get(A).get(399).date(), cfg);
        assertThat(run1.equity()).isEqualTo(run2.equity()); // same seed, same result

        var other = engine.run(Controls.matchedTo("rnd", ref, List.of(A, B, C), 2, 43), data,
                data.get(A).get(0).date(), data.get(A).get(399).date(), cfg);
        assertThat(other.equity()).isNotEqualTo(run1.equity());
        assertThat(run1.trades()).isNotEmpty();
    }

    @Test
    void finalistCriteriaPassOnlyWhenAllFiveHold() {
        var good = new Metrics(0.1, 0.1, 1.2, 1.5, 0.10, 1.0, 1, 0.9, 40);
        var bench = new Metrics(0.08, 0.15, 0.8, 1.0, 0.20, 0.4, 0, 1, 1);
        var ok = new LiveVsBacktest.Result(0.97, 0.002, 100);
        var randoms = new double[] {0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.07, 0.08, 0.09, 0.095};
        var c = FinalistCriteria.standard();

        var pass = c.evaluate(new FinalistCriteria.Input(7, ok, good, bench, 0.2, randoms));
        assertThat(pass.allPass()).isTrue();

        assertThat(c.evaluate(new FinalistCriteria.Input(4, ok, good, bench, 0.2, randoms)).enoughTime()).isFalse();
        assertThat(c.evaluate(new FinalistCriteria.Input(7, new LiveVsBacktest.Result(0.90, 0.002, 100), good, bench,
                0.2, randoms)).matchesBacktest()).isFalse();
        assertThat(c.evaluate(new FinalistCriteria.Input(7, new LiveVsBacktest.Result(0.97, 0.02, 100), good, bench,
                0.2, randoms)).matchesBacktest()).isFalse();
        var weak = new Metrics(0.01, 0.1, 0.5, 0.5, 0.10, 0.1, 1, 0.9, 40);
        assertThat(c.evaluate(new FinalistCriteria.Input(7, ok, weak, bench, 0.2, randoms)).beatsBenchmark()).isFalse();
        var deep = new Metrics(0.1, 0.1, 1.2, 1.5, 0.30, 1.0, 1, 0.9, 40);
        assertThat(c.evaluate(new FinalistCriteria.Input(7, ok, deep, bench, 0.2, randoms)).withinDrawdown()).isFalse();
        var lucky = c.evaluate(new FinalistCriteria.Input(7, ok, good, bench, 0.05, randoms));
        assertThat(lucky.beatsRandoms()).isFalse();
        assertThat(lucky.failures()).hasSize(1);
    }

    @Test
    void dailySummaryListsEveryAgentAndItsErrors() {
        var arena = arena(universe(20), new ArrayList<>());
        arena.addAgent("hold", Agent.Kind.BENCHMARK, new BuyAndHold("hold", A), CAPITAL, permissive());
        DayReport last = null;
        for (LocalDate d : days(universe(20))) {
            last = arena.runDay(d);
        }
        String text = ArenaReport.summary(last, CAPITAL);
        assertThat(text).contains("hold").contains("ARENA").contains("EUR");
    }

    @Test
    void percentileRankCountsRandomsBelowTheAgent() {
        assertThat(FinalistCriteria.percentileRank(0.5, new double[] {0.1, 0.2, 0.9, 1.0})).isEqualTo(0.5);
        assertThat(FinalistCriteria.percentileRank(0.5, new double[] {})).isZero();
    }
}
