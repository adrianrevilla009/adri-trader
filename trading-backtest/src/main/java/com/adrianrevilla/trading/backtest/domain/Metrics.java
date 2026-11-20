package com.adrianrevilla.trading.backtest.domain;

import com.adrianrevilla.trading.backtest.domain.BacktestResult.EquityPoint;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Performance metrics, always net of costs (the equity curve already includes them). Statistics use
 * {@code double}; 252 trading days per year, risk-free rate 0.
 */
public record Metrics(double cagr, double volatility, double sharpe, double sortino, double maxDrawdown,
        double calmar, double turnover, double investedShare, int trades) {

    static final double DAYS = 252.0;

    public static Metrics of(BacktestResult r) {
        List<EquityPoint> eq = r.equity();
        double[] ret = dailyReturns(eq);
        double years = Math.max(1e-9, ChronoUnit.DAYS.between(eq.get(0).date(), eq.get(eq.size() - 1).date()) / 365.25);
        double start = eq.get(0).equity().doubleValue();
        double end = eq.get(eq.size() - 1).equity().doubleValue();
        double cagr = Math.pow(end / start, 1.0 / years) - 1.0;
        double mean = mean(ret);
        double sd = stdev(ret);
        double downside = 0;
        for (double x : ret) {
            downside += Math.min(x, 0) * Math.min(x, 0);
        }
        double dd = ret.length == 0 ? 0 : Math.sqrt(downside / ret.length);
        double maxDd = maxDrawdown(eq);
        double avgEquity = eq.stream().mapToDouble(p -> p.equity().doubleValue()).average().orElse(start);
        double traded = r.trades().stream().mapToDouble(t -> t.amountEur().doubleValue()).sum();
        long investedDays = eq.stream().filter(p -> p.invested().doubleValue() > 0.01 * p.equity().doubleValue()).count();
        return new Metrics(
                cagr,
                sd * Math.sqrt(DAYS),
                sd == 0 ? Double.NaN : mean / sd * Math.sqrt(DAYS),
                dd == 0 ? Double.NaN : mean / dd * Math.sqrt(DAYS),
                maxDd,
                maxDd == 0 ? Double.NaN : cagr / maxDd,
                traded / 2.0 / avgEquity / years,
                (double) investedDays / eq.size(),
                r.trades().size());
    }

    /** Alpha (annualised), beta and tracking error against a benchmark run over the same dates. */
    public record Relative(double alpha, double beta, double trackingError) {}

    public static Relative relativeTo(BacktestResult r, BacktestResult benchmark) {
        double[] a = dailyReturns(r.equity());
        double[] b = dailyReturns(benchmark.equity());
        int n = Math.min(a.length, b.length);
        double[] x = java.util.Arrays.copyOf(b, n);
        double[] y = java.util.Arrays.copyOf(a, n);
        double mx = mean(x);
        double my = mean(y);
        double cov = 0;
        double var = 0;
        double[] diff = new double[n];
        for (int i = 0; i < n; i++) {
            cov += (x[i] - mx) * (y[i] - my);
            var += (x[i] - mx) * (x[i] - mx);
            diff[i] = y[i] - x[i];
        }
        double beta = var == 0 ? Double.NaN : cov / var;
        double alpha = (my - beta * mx) * DAYS;
        return new Relative(alpha, beta, stdev(diff) * Math.sqrt(DAYS));
    }

    public static double[] dailyReturns(List<EquityPoint> eq) {
        double[] r = new double[Math.max(0, eq.size() - 1)];
        for (int i = 1; i < eq.size(); i++) {
            r[i - 1] = eq.get(i).equity().doubleValue() / eq.get(i - 1).equity().doubleValue() - 1.0;
        }
        return r;
    }

    static double maxDrawdown(List<EquityPoint> eq) {
        double peak = Double.NEGATIVE_INFINITY;
        double worst = 0;
        for (EquityPoint p : eq) {
            double v = p.equity().doubleValue();
            peak = Math.max(peak, v);
            worst = Math.max(worst, (peak - v) / peak);
        }
        return worst;
    }

    static double mean(double[] x) {
        if (x.length == 0) {
            return 0;
        }
        double s = 0;
        for (double v : x) {
            s += v;
        }
        return s / x.length;
    }

    static double stdev(double[] x) {
        if (x.length < 2) {
            return 0;
        }
        double m = mean(x);
        double s = 0;
        for (double v : x) {
            s += (v - m) * (v - m);
        }
        return Math.sqrt(s / (x.length - 1));
    }
}
