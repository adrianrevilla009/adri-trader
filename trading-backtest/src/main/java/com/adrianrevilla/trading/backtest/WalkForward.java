package com.adrianrevilla.trading.backtest;

import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.Strategy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Walk-forward: parameters are chosen on a training window and measured only on the next test window.
 * Default plan (ADR-0014): 5 years of training, 6 months of test, step of 6 months.
 */
public final class WalkForward {

    public record Window(LocalDate trainStart, LocalDate trainEnd, LocalDate testStart, LocalDate testEnd) {}

    public record Candidate(String name, Strategy strategy) {}

    public record WindowResult(Window window, String chosen, Metrics chosenTest, Metrics benchmarkTest, boolean beatsBenchmark) {}

    public record Outcome(List<WindowResult> windows, double winRate) {
        public boolean passes(double threshold) {
            return !windows.isEmpty() && winRate >= threshold;
        }
    }

    public static List<Window> windows(LocalDate dataStart, LocalDate dataEnd, int trainYears, int testMonths,
            int stepMonths) {
        List<Window> out = new ArrayList<>();
        LocalDate trainStart = dataStart;
        while (true) {
            LocalDate testStart = trainStart.plusYears(trainYears);
            LocalDate testEnd = testStart.plusMonths(testMonths).minusDays(1);
            if (testEnd.isAfter(dataEnd)) {
                break;
            }
            out.add(new Window(trainStart, testStart.minusDays(1), testStart, testEnd));
            trainStart = trainStart.plusMonths(stepMonths);
        }
        return out;
    }

    /**
     * Beats the benchmark in a window when its net Sharpe or Calmar is higher (criterion of the plan).
     * The candidate with the best training Sharpe is the one measured in the test window.
     */
    public Outcome run(List<Candidate> candidates, Strategy benchmark, Map<Isin, List<Bar>> series,
            List<Window> windows, BacktestConfig cfg) {
        BacktestEngine engine = new BacktestEngine();
        List<WindowResult> results = new ArrayList<>();
        int wins = 0;
        for (Window w : windows) {
            Candidate best = null;
            double bestSharpe = Double.NEGATIVE_INFINITY;
            for (Candidate c : candidates) {
                double s = Metrics.of(engine.run(c.strategy(), series, w.trainStart(), w.trainEnd(), cfg)).sharpe();
                double score = Double.isNaN(s) ? Double.NEGATIVE_INFINITY : s;
                if (best == null || score > bestSharpe) {
                    best = c;
                    bestSharpe = score;
                }
            }
            Metrics chosen = Metrics.of(engine.run(best.strategy(), series, w.testStart(), w.testEnd(), cfg));
            Metrics bench = Metrics.of(engine.run(benchmark, series, w.testStart(), w.testEnd(), cfg));
            boolean win = greater(chosen.sharpe(), bench.sharpe()) || greater(chosen.calmar(), bench.calmar());
            if (win) {
                wins++;
            }
            results.add(new WindowResult(w, best.name(), chosen, bench, win));
        }
        return new Outcome(results, results.isEmpty() ? 0 : (double) wins / results.size());
    }

    private static boolean greater(double a, double b) {
        return !Double.isNaN(a) && (Double.isNaN(b) || a > b);
    }
}
