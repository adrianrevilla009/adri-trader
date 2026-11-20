package com.adrianrevilla.trading.application.service;

import com.adrianrevilla.trading.application.port.in.RunBacktest;
import com.adrianrevilla.trading.backtest.application.BacktestEngine;
import com.adrianrevilla.trading.backtest.application.WalkForward;
import com.adrianrevilla.trading.backtest.application.port.out.BacktestReportPublisher;
import com.adrianrevilla.trading.backtest.domain.BacktestConfig;
import com.adrianrevilla.trading.backtest.domain.BacktestResult;
import com.adrianrevilla.trading.backtest.domain.CostModel;
import com.adrianrevilla.trading.backtest.domain.Metrics;
import com.adrianrevilla.trading.backtest.domain.TargetAdjuster;
import com.adrianrevilla.trading.backtest.domain.Validation;
import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.Strategy;
import com.adrianrevilla.trading.data.application.port.out.BarSource;
import com.adrianrevilla.trading.data.domain.BarCleaner;
import com.adrianrevilla.trading.data.domain.DataQualityValidator;
import com.adrianrevilla.trading.data.domain.DatasetHash;
import com.adrianrevilla.trading.data.domain.QualityIssue;
import com.adrianrevilla.trading.data.domain.XetraCalendar;
import com.adrianrevilla.trading.strategies.domain.BuyAndHold;
import com.adrianrevilla.trading.strategies.domain.MomentumRotation;
import com.adrianrevilla.trading.strategies.domain.SwingRsi;
import com.adrianrevilla.trading.strategies.domain.TrendStrategy;
import com.adrianrevilla.trading.strategies.domain.VolatilityTarget;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleFunction;

/**
 * Runs the four v1 strategies on historical CSV files and applies the phase 2 criteria of ADR-0014: the last
 * 3 years are held out (this runner never opens them), walk-forward 5y/6m/6m with a 70 % win rate against the
 * benchmark, parameter sensitivity of 20 %, and at least 30 trades for swing. Costs are net. It writes one HTML
 * and CSV report per strategy plus a text summary with the verdict.
 */
public final class BacktestRunner implements RunBacktest {
    private static final double WIN_RATE = 0.70;
    private static final int MIN_SWING_TRADES = 30;

    private final BacktestEngine engine = new BacktestEngine();
    private final BacktestReportPublisher reports;

    public BacktestRunner(BacktestReportPublisher reports) {
        this.reports = reports;
    }
    private final BacktestConfig cfg = BacktestConfig.defaults().withCosts(CostModel.of("10", "5"))
            .withMinOrder(BigDecimal.ONE).withAdjuster(TargetAdjuster.maxWeightPerAsset(new BigDecimal("0.25")));
    private final BacktestConfig benchCfg = cfg.withAdjuster(TargetAdjuster.NONE);

    /**
     * Critical issues that matter for this run: only dates inside the common window, and a day missing in every
     * instrument at once is a market closure (the calendar is unverified), not a data gap.
     */
    static List<QualityIssue> relevantIssues(Map<Isin, List<QualityIssue>> byIsin, LocalDate windowStart) {
        Set<LocalDate> closedForAll = null;
        for (List<QualityIssue> l : byIsin.values()) {
            Set<LocalDate> gaps = new java.util.HashSet<>();
            l.stream().filter(i -> i.type() == QualityIssue.Type.GAP).forEach(i -> gaps.add(i.date()));
            if (closedForAll == null) {
                closedForAll = gaps;
            } else {
                closedForAll.retainAll(gaps);
            }
        }
        Set<LocalDate> closed = closedForAll == null ? Set.of() : closedForAll;
        List<QualityIssue> out = new ArrayList<>();
        for (List<QualityIssue> l : byIsin.values()) {
            for (QualityIssue i : l) {
                boolean closure = i.type() == QualityIssue.Type.GAP && closed.contains(i.date());
                if (!i.date().isBefore(windowStart) && !closure) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    @Override
    public Outcome run(BarSource source, List<Isin> universe, Isin benchmark, Path outDir, String gitCommit, int holdoutYears)
            throws IOException {
        var validator = new DataQualityValidator(new XetraCalendar());
        Map<Isin, List<Bar>> data = new LinkedHashMap<>();
        Map<Isin, List<QualityIssue>> issuesByIsin = new LinkedHashMap<>();
        List<Isin> all = new ArrayList<>(universe);
        if (!all.contains(benchmark)) {
            all.add(benchmark);
        }
        for (Isin isin : all) {
            List<Bar> raw = source.fetch(isin);
            issuesByIsin.put(isin, validator.validate(raw, Set.of()).stream()
                    .filter(i -> i.severity() == QualityIssue.Severity.CRITICAL).toList());
            data.put(isin, new BarCleaner().clean(List.of(raw)));
        }
        LocalDate dataStart = data.values().stream().mapToLong(b -> b.get(0).date().toEpochDay()).max()
                .stream().mapToObj(LocalDate::ofEpochDay).findFirst().orElseThrow();
        LocalDate dataEnd = data.values().stream().mapToLong(b -> b.get(b.size() - 1).date().toEpochDay()).min()
                .stream().mapToObj(LocalDate::ofEpochDay).findFirst().orElseThrow();
        List<QualityIssue> critical = relevantIssues(issuesByIsin, dataStart);
        var holdout = new Validation.Holdout(dataEnd, holdoutYears);
        LocalDate designEnd = holdout.designEnd();
        String hash = DatasetHash.of(data);

        Strategy bench = new BuyAndHold("benchmark", benchmark);
        var trials = new Validation.TrialLog();
        var windows = WalkForward.windows(dataStart, designEnd, 5, 6, 6);

        List<Verdict> verdicts = new ArrayList<>();
        BacktestResult benchResult = engine.run(bench, data, dataStart, designEnd, benchCfg);
        Metrics benchMetrics = Metrics.of(benchResult);

        record Spec(String family, int tradesMin, java.util.function.DoubleFunction<Strategy> factory, double param) {}
        int topN = Math.max(1, Math.min(3, universe.size()));
        List<Spec> specs = List.of(
                new Spec("trend", 0, p -> new TrendStrategy("trend", universe, null, (int) Math.round(p), 0.01), 200),
                new Spec("momentum", 0, p -> new MomentumRotation("momentum", universe, (int) Math.round(p), topN), 126),
                new Spec("vol-target", 0, p -> new VolatilityTarget("vol-target", universe, (int) Math.round(p), 0.25), 60),
                new Spec("swing", MIN_SWING_TRADES, p -> new SwingRsi("swing", universe, (int) Math.round(p), 10, 65, 10,
                        Math.max(1, Math.min(4, universe.size()))), 200));

        for (Spec s : specs) {
            trials.record(s.family(), "base " + s.param());
            Strategy strategy = s.factory().apply(s.param());
            BacktestResult res = engine.run(strategy, data, dataStart, designEnd, cfg);
            Metrics m = Metrics.of(res);
            var wf = new WalkForward().run(List.of(new WalkForward.Candidate(s.family(), strategy)), bench, data,
                    windows, cfg);

            DoubleFunction<Double> excess = p -> {
                trials.record(s.family(), "sens " + p);
                Metrics x = Metrics.of(engine.run(s.factory().apply(p), data, dataStart, designEnd, cfg));
                return nz(x.sharpe()) - nz(benchMetrics.sharpe());
            };
            var sens = Validation.sensitivity(s.param(), 0.20, p -> excess.apply(p));

            List<String> reasons = new ArrayList<>();
            if (windows.isEmpty()) {
                reasons.add("not enough history for walk-forward (needs about 5.5 years before the holdout)");
            } else if (!wf.passes(WIN_RATE)) {
                reasons.add(String.format(Locale.ROOT, "beats the benchmark in %.0f%% of %d windows, 70%% needed",
                        wf.winRate() * 100, windows.size()));
            }
            if (!sens.stable()) {
                reasons.add("fragile: a 20% change of the parameter flips the sign of the excess Sharpe");
            }
            if (s.tradesMin() > 0 && res.trades().size() < s.tradesMin()) {
                reasons.add("only " + res.trades().size() + " trades, " + s.tradesMin() + " needed");
            }
            verdicts.add(new Verdict(s.family(), wf.winRate(), windows.size(), sens.stable(), res.trades().size(),
                    reasons.isEmpty(), reasons));

            var meta = new BacktestReportPublisher.RunMeta(s.family() + "-" + hash.substring(0, 8), s.family(),
                    "param=" + s.param() + ", costs 10bps spread + 5bps slippage, cap 25%, design period "
                            + dataStart + ".." + designEnd, hash, gitCommit);
            reports.write(outDir, meta, res, m, benchMetrics);
        }

        Path summary = reports.writeSummary(outDir, summaryText(dataStart, designEnd, holdout, hash, critical, verdicts, trials, benchMetrics));
        return new Outcome(data, critical, designEnd, verdicts, summary);
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private static String summaryText(LocalDate start, LocalDate designEnd, Validation.Holdout holdout, String hash,
            List<QualityIssue> critical, List<Verdict> verdicts, Validation.TrialLog trials, Metrics bench) {
        StringBuilder sb = new StringBuilder("# Resultado del backtest\n\n");
        sb.append("- Periodo de diseño: ").append(start).append(" a ").append(designEnd).append('\n');
        sb.append("- Holdout (sin abrir): desde ").append(holdout.holdoutStart()).append('\n');
        sb.append("- Hash del dataset: ").append(hash).append('\n');
        sb.append(String.format(Locale.ROOT, "- Benchmark: CAGR %.2f%%, Sharpe %.2f, caída máxima %.2f%%%n",
                bench.cagr() * 100, nz(bench.sharpe()), bench.maxDrawdown() * 100));
        if (!critical.isEmpty()) {
            sb.append("- **Datos con ").append(critical.size())
                    .append(" incidencias críticas: no fiarse de estos resultados hasta limpiarlas.**\n");
            var byType = new java.util.TreeMap<QualityIssue.Type, List<QualityIssue>>();
            critical.forEach(i -> byType.computeIfAbsent(i.type(), t -> new ArrayList<>()).add(i));
            byType.forEach((type, list) -> sb.append("  - ").append(type).append(": ").append(list.size())
                    .append(" (ej. ").append(list.stream().limit(5).map(i -> i.date().toString())
                            .collect(java.util.stream.Collectors.joining(", "))).append(")\n"));
        }
        sb.append("\n| Estrategia | Ventanas ganadas | Ventanas | Estable ±20% | Operaciones | Cumple |\n| --- | --- | --- | --- | --- | --- |\n");
        for (Verdict v : verdicts) {
            sb.append(String.format(Locale.ROOT, "| %s | %.0f%% | %d | %s | %d | %s |%n", v.strategy(), v.winRate() * 100,
                    v.windows(), v.sensitivityStable() ? "sí" : "no", v.trades(), v.passes() ? "SÍ" : "no"));
        }
        sb.append("\n## Motivos\n\n");
        for (Verdict v : verdicts) {
            sb.append("- **").append(v.strategy()).append("**: ")
                    .append(v.passes() ? "cumple los umbrales de la fase 2" : String.join("; ", v.reasons())).append('\n');
        }
        sb.append("\n## Variantes probadas (registro de intentos)\n\n");
        for (Verdict v : verdicts) {
            sb.append("- ").append(v.strategy()).append(": ").append(trials.count(v.strategy())).append('\n');
        }
        sb.append("\nSi ninguna estrategia cumple, el resultado válido es abandonar esa familia o mantener un ETF global (ADR-0014).\n");
        return sb.toString();
    }
}
