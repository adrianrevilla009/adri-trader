package com.adrianrevilla.trading.arena;

import com.adrianrevilla.trading.backtest.BacktestResult;
import com.adrianrevilla.trading.backtest.BacktestResult.Decision;
import com.adrianrevilla.trading.backtest.BacktestResult.EquityPoint;
import com.adrianrevilla.trading.core.Isin;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Measures how closely the live arena matches the backtest of the same strategy over the same days. */
public final class LiveVsBacktest {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.000001");

    /**
     * @param decisionAgreement share of common days with the same target weights (1.0 = identical)
     * @param maxEquityDiff largest gap between equity curves as a fraction of the initial capital
     */
    public record Result(double decisionAgreement, double maxEquityDiff, int commonDays) {}

    private LiveVsBacktest() {}

    public static Result compare(BacktestResult live, BacktestResult backtest, BigDecimal initialCapital) {
        Map<LocalDate, Decision> bt = new HashMap<>();
        backtest.decisions().forEach(d -> bt.put(d.date(), d));
        int common = 0;
        int same = 0;
        for (Decision d : live.decisions()) {
            Decision other = bt.get(d.date());
            if (other == null) {
                continue;
            }
            common++;
            if (sameWeights(d, other)) {
                same++;
            }
        }
        Map<LocalDate, EquityPoint> btEq = new HashMap<>();
        backtest.equity().forEach(p -> btEq.put(p.date(), p));
        double maxDiff = 0;
        for (EquityPoint p : live.equity()) {
            EquityPoint q = btEq.get(p.date());
            if (q != null) {
                double diff = p.equity().subtract(q.equity()).abs().divide(initialCapital, java.math.MathContext.DECIMAL64)
                        .doubleValue();
                maxDiff = Math.max(maxDiff, diff);
            }
        }
        return new Result(common == 0 ? 0 : (double) same / common, maxDiff, common);
    }

    private static boolean sameWeights(Decision a, Decision b) {
        Set<Isin> keys = new HashSet<>(a.target().weights().keySet());
        keys.addAll(b.target().weights().keySet());
        for (Isin k : keys) {
            BigDecimal x = a.target().weights().getOrDefault(k, BigDecimal.ZERO);
            BigDecimal y = b.target().weights().getOrDefault(k, BigDecimal.ZERO);
            if (x.subtract(y).abs().compareTo(TOLERANCE) > 0) {
                return false;
            }
        }
        return true;
    }

    /** Convenience for callers that only have lists. */
    static int size(List<?> l) {
        return l.size();
    }
}
