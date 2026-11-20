package com.adrianrevilla.trading.backtest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.adrianrevilla.trading.backtest.adapter.out.report.ReportWriter;
import com.adrianrevilla.trading.backtest.application.TestData;
import com.adrianrevilla.trading.backtest.application.WalkForward;
import com.adrianrevilla.trading.backtest.domain.BacktestResult.EquityPoint;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.strategies.domain.BuyAndHold;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsAndValidationTest {

    private static BacktestResult curve(double... values) {
        List<EquityPoint> eq = new ArrayList<>();
        LocalDate d = LocalDate.parse("2020-01-01");
        for (double v : values) {
            eq.add(new EquityPoint(d, BigDecimal.valueOf(v), BigDecimal.valueOf(v)));
            d = d.plusDays(1);
        }
        return new BacktestResult("t", eq, List.of());
    }

    @Test
    void maxDrawdownAndReturnsOnKnownCurve() {
        var m = Metrics.of(curve(100, 50, 100));
        assertThat(m.maxDrawdown()).isCloseTo(0.5, within(1e-12));
        assertThat(Metrics.dailyReturns(curve(100, 110, 99).equity())[0]).isCloseTo(0.10, within(1e-12));
    }

    @Test
    void cagrOfDoublingInOneYearIsAboutOneHundredPercent() {
        List<EquityPoint> eq = List.of(
                new EquityPoint(LocalDate.parse("2020-01-01"), BigDecimal.valueOf(100), BigDecimal.valueOf(100)),
                new EquityPoint(LocalDate.parse("2021-01-01"), BigDecimal.valueOf(200), BigDecimal.valueOf(200)));
        assertThat(Metrics.of(new BacktestResult("t", eq, List.of())).cagr()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void betaOfAStrategyAgainstItselfIsOneAndTrackingErrorZero() {
        var r = curve(100, 101, 99, 103, 102);
        var rel = Metrics.relativeTo(r, r);
        assertThat(rel.beta()).isCloseTo(1.0, within(1e-12));
        assertThat(rel.trackingError()).isCloseTo(0.0, within(1e-12));
    }

    @Test
    void walkForwardProducesAboutEightWindowsForNineYears() {
        var w = WalkForward.windows(LocalDate.parse("2010-01-01"), LocalDate.parse("2018-12-31"), 5, 6, 6);
        assertThat(w).hasSize(8);
        assertThat(w.get(0).testStart()).isEqualTo(LocalDate.parse("2015-01-01"));
        assertThat(w.get(0).trainEnd()).isEqualTo(LocalDate.parse("2014-12-31"));
        assertThat(w.get(1).trainStart()).isEqualTo(LocalDate.parse("2010-07-01"));
    }

    @Test
    void walkForwardCountsWindowsWhereTheCandidateBeatsTheBenchmark() {
        Isin a = new Isin("IE00B4L5Y983");
        var bars = TestData.randomWalk(LocalDate.parse("2010-01-04"), 2600, 7, 0.0004, 0.01);
        var series = Map.of(a, bars);
        var windows = WalkForward.windows(bars.get(0).date(), bars.get(bars.size() - 1).date(), 4, 6, 6);
        assertThat(windows).isNotEmpty();
        // candidate identical to the benchmark never strictly beats it
        var same = new BuyAndHold("bh", a);
        var out = new WalkForward().run(List.of(new WalkForward.Candidate("bh", same)), same, series, windows,
                BacktestConfig.defaults());
        assertThat(out.winRate()).isZero();
        assertThat(out.passes(0.7)).isFalse();
    }

    @Test
    void holdoutOpensOnlyOnce() {
        var h = new Validation.Holdout(LocalDate.parse("2025-12-31"), 3);
        assertThat(h.designEnd()).isEqualTo(LocalDate.parse("2022-12-31"));
        assertThat(h.open()).isEqualTo(LocalDate.parse("2023-01-01"));
        assertThatThrownBy(h::open).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sensitivityDetectsFragileAndRobustMetrics() {
        assertThat(Validation.sensitivity(200, 0.2, p -> 1.0).stable()).isTrue();
        assertThat(Validation.sensitivity(200, 0.2, p -> p > 210 ? -1.0 : 1.0).stable()).isFalse();
    }

    @Test
    void bootstrapIsSeededAndOrdered() {
        double[] r = new double[500];
        for (int i = 0; i < r.length; i++) {
            r[i] = (i % 3 == 0 ? -0.01 : 0.008);
        }
        var x = Validation.bootstrap(r, 20, 200, 42);
        var y = Validation.bootstrap(r, 20, 200, 42);
        assertThat(x).isEqualTo(y);
        assertThat(x.cagr().p5()).isLessThanOrEqualTo(x.cagr().p50());
        assertThat(x.cagr().p50()).isLessThanOrEqualTo(x.cagr().p95());
    }

    @Test
    void trialLogCountsVariantsPerFamily() {
        var log = new Validation.TrialLog();
        log.record("trend", "w=200");
        log.record("trend", "w=150");
        log.record("momentum", "lb=126");
        assertThat(log.count("trend")).isEqualTo(2);
        assertThat(log.count("none")).isZero();
    }

    @Test
    void reportContainsRunIdAndIsReproducibleInfo(@TempDir Path dir) throws Exception {
        var r = curve(100, 101, 102);
        var m = Metrics.of(r);
        var meta = new ReportWriter.RunMeta("run-1", "trend", "window=200", "abc123", "deadbee");
        Path html = new ReportWriter().write(dir, meta, r, m, m);
        assertThat(Files.readString(html)).contains("run-1", "abc123", "deadbee");
        assertThat(Files.readString(dir.resolve("run-1.csv"))).startsWith("date,equity,invested");
    }
}
