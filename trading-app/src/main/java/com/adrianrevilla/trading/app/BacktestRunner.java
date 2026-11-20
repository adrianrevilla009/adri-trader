package com.adrianrevilla.trading.app;

import com.adrianrevilla.trading.backtest.BacktestConfig;
import com.adrianrevilla.trading.backtest.BacktestEngine;
import com.adrianrevilla.trading.backtest.BacktestResult;
import com.adrianrevilla.trading.backtest.CostModel;
import com.adrianrevilla.trading.backtest.Metrics;
import com.adrianrevilla.trading.backtest.ReportWriter;
import com.adrianrevilla.trading.backtest.TargetAdjuster;
import com.adrianrevilla.trading.backtest.Validation;
import com.adrianrevilla.trading.backtest.WalkForward;
import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.Strategy;
import com.adrianrevilla.trading.data.BarCleaner;
import com.adrianrevilla.trading.data.CsvDirectoryBarSource;
import com.adrianrevilla.trading.data.DataQualityValidator;
import com.adrianrevilla.trading.data.DatasetHash;
import com.adrianrevilla.trading.data.QualityIssue;
import com.adrianrevilla.trading.data.XetraCalendar;
import com.adrianrevilla.trading.strategies.BuyAndHold;
import com.adrianrevilla.trading.strategies.MomentumRotation;
import com.adrianrevilla.trading.strategies.SwingRsi;
import com.adrianrevilla.trading.strategies.TrendStrategy;
import com.adrianrevilla.trading.strategies.VolatilityTarget;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
public final class BacktestRunner {
    private static final double WIN_RATE = 0.70;
    private static final int MIN_SWING_TRADES = 30;

    public record Verdict(String strategy, double winRate, int windows, boolean sensitivityStable, int trades,
            boolean passes, List<String> reasons) {}

    public record Outcome(Map<Isin, List<Bar>> data, List<QualityIssue> criticalIssues, LocalDate designEnd,
            List<Verdict> verdicts, Path summary) {}

    private final BacktestEngine engine = new BacktestEngine();
    private final BacktestConfig cfg = BacktestConfig.defaults().withCosts(CostModel.of("10", "5"))
            .withMinOrder(BigDecimal.ONE).withAdjuster(TargetAdjuster.maxWeightPerAsset(new BigDecimal("0.25")));
    private final BacktestConfig benchCfg = cfg.withAdjuster(TargetAdjuster.NONE);

    public Outcome run(Path csvDir, List<Isin> universe, Isin benchmark, Path outDir, String gitCommit, int holdoutYears)
            throws IOException {
        var source = new CsvDirectoryBarSource("csv", csvDir);
        var validator = new DataQualityValidator(new XetraCalendar());
        Map<Isin, List<Bar>> data = new LinkedHashMap<>();
        List<QualityIssue> critical = new ArrayList<>();
        List<Isin> all = new ArrayList<>(universe);
        if (!all.contains(benchmark)) {
            all.add(benchmark);
        }
        for (Isin isin : all) {
            List<Bar> raw = source.fetch(isin);
            validator.validate(raw, Set.of()).stream()
                    .filter(i -> i.severity() == QualityIssue.Severity.CRITICAL).forEach(critical::add);
            data.put(isin, new BarCleaner().clean(List.of(raw)));
        }
        LocalDate dataStart = data.values().stream().mapToLong(b -> b.get(0).date().toEpochDay()).max()
                .stream().mapToObj(LocalDate::ofEpochDay).findFirst().orElseThrow();
        LocalDate dataEnd = data.values().stream().mapToLong(b -> b.get(b.size() - 1).date().toEpochDay()).min()
                .stream().mapToObj(LocalDate::ofEpochDay).findFirst().orElseThrow();
        var holdout = new Validation.Holdout(dataEnd, holdoutYears);
        LocalDate designEnd = holdout.designEnd();
        String hash = DatasetHash.of(data);

        Strategy bench = new BuyAndHold("benchmark", benchmark);
        var trials = new Validation.TrialLog();
        var windows = WalkForward.windows(dataStart, designEnd, 5, 6, 6);

        List<Verdict> verdicts = new ArrayList<>();
        Files.createDirectories(outDir);
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

            var meta = new ReportWriter.RunMeta(s.family() + "-" + hash.substring(0, 8), s.family(),
                    "param=" + s.param() + ", costs 10bps spread + 5bps slippage, cap 25%, design period "
                            + dataStart + ".." + designEnd, hash, gitCommit);
            new ReportWriter().write(outDir, meta, res, m, benchMetrics);
        }

        Path summary = outDir.resolve("summary.md");
        Files.writeString(summary, summaryText(dataStart, designEnd, holdout, hash, critical, verdicts, trials, benchMetrics),
                StandardCharsets.UTF_8);
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
