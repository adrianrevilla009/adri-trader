package com.adrianrevilla.trading.backtest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

/** Tools that distrust good results (phase 2 validation). */
public final class Validation {
    private Validation() {}

    /** Last N years are held out and may be opened exactly once (ADR-0014). */
    public static final class Holdout {
        private final LocalDate holdoutStart;
        private boolean opened;

        public Holdout(LocalDate dataEnd, int years) {
            this.holdoutStart = dataEnd.minusYears(years).plusDays(1);
        }

        /** Last date that may be used for design and walk-forward. */
        public LocalDate designEnd() {
            return holdoutStart.minusDays(1);
        }

        public LocalDate holdoutStart() {
            return holdoutStart;
        }

        public synchronized LocalDate open() {
            if (opened) {
                throw new IllegalStateException("The holdout was already looked at; it can be opened only once");
            }
            opened = true;
            return holdoutStart;
        }
    }

    /** Counts how many variants were tried per strategy family: the more, the likelier the best is luck. */
    public static final class TrialLog {
        private final Map<String, List<String>> trials = new HashMap<>();

        public void record(String family, String variant) {
            trials.computeIfAbsent(family, k -> new ArrayList<>()).add(variant);
        }

        public int count(String family) {
            return trials.getOrDefault(family, List.of()).size();
        }
    }

    public record Sensitivity(double base, double low, double high, boolean stable) {}

    /**
     * Moves a parameter by {@code +/- pct} and checks the metric (for example excess Sharpe versus the
     * benchmark) keeps its sign. If it flips, the strategy is fragile and is discarded.
     */
    public static Sensitivity sensitivity(double param, double pct, DoubleUnaryOperator metricOfParam) {
        double base = metricOfParam.applyAsDouble(param);
        double low = metricOfParam.applyAsDouble(param * (1 - pct));
        double high = metricOfParam.applyAsDouble(param * (1 + pct));
        boolean stable = Math.signum(low) == Math.signum(base) && Math.signum(high) == Math.signum(base) && base != 0;
        return new Sensitivity(base, low, high, stable);
    }

    public record Distribution(double p5, double p50, double p95) {}

    public record BootstrapResult(Distribution cagr, Distribution maxDrawdown) {}

    /** Block bootstrap of daily returns (seeded): plausible range of annual return and maximum drawdown. */
    public static BootstrapResult bootstrap(double[] dailyReturns, int blockLength, int iterations, long seed) {
        Random rnd = new Random(seed);
        int n = dailyReturns.length;
        double[] cagrs = new double[iterations];
        double[] dds = new double[iterations];
        for (int it = 0; it < iterations; it++) {
            double eq = 1.0;
            double peak = 1.0;
            double dd = 0;
            int produced = 0;
            while (produced < n) {
                int start = rnd.nextInt(n);
                for (int k = 0; k < blockLength && produced < n; k++, produced++) {
                    eq *= 1.0 + dailyReturns[(start + k) % n];
                    peak = Math.max(peak, eq);
                    dd = Math.max(dd, (peak - eq) / peak);
                }
            }
            cagrs[it] = Math.pow(eq, Metrics.DAYS / n) - 1.0;
            dds[it] = dd;
        }
        return new BootstrapResult(dist(cagrs), dist(dds));
    }

    private static Distribution dist(double[] x) {
        double[] s = x.clone();
        Arrays.sort(s);
        return new Distribution(s[(int) (0.05 * (s.length - 1))], s[(int) (0.5 * (s.length - 1))],
                s[(int) (0.95 * (s.length - 1))]);
    }
}
