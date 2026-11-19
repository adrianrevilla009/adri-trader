package com.adrianrevilla.trading.arena;

import com.adrianrevilla.trading.backtest.Metrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The five criteria to become a finalist (ADR-0015), fixed before the arena starts:
 * 1) at least {@code minMonths} in the arena (3 only detect technical faults, 6 or more to judge results);
 * 2) same decisions as the backtest on at least 95 % of days and equity gap at most 0.5 % of the capital;
 * 3) net Sharpe or Calmar at least the benchmark one;
 * 4) maximum drawdown within the risk limit;
 * 5) result above the 90th percentile of the random control agents.
 */
public record FinalistCriteria(int minMonths, double minDecisionAgreement, double maxEquityDiff, double maxDrawdown,
        double minPercentileRank) {

    public static FinalistCriteria standard() {
        return new FinalistCriteria(6, 0.95, 0.005, 0.15, 0.90);
    }

    public record Input(double monthsInArena, LiveVsBacktest.Result liveVsBacktest, Metrics agent, Metrics benchmark,
            double agentReturn, double[] randomReturns) {}

    public record Evaluation(boolean enoughTime, boolean matchesBacktest, boolean beatsBenchmark,
            boolean withinDrawdown, boolean beatsRandoms, double percentileRank, List<String> failures) {
        public boolean allPass() {
            return failures.isEmpty();
        }
    }

    public Evaluation evaluate(Input in) {
        List<String> failures = new ArrayList<>();
        boolean time = in.monthsInArena() >= minMonths;
        if (!time) {
            failures.add("only " + in.monthsInArena() + " months in the arena, " + minMonths + " needed");
        }
        boolean match = in.liveVsBacktest().decisionAgreement() >= minDecisionAgreement
                && in.liveVsBacktest().maxEquityDiff() <= maxEquityDiff;
        if (!match) {
            failures.add("live differs from backtest: agreement " + in.liveVsBacktest().decisionAgreement()
                    + ", equity gap " + in.liveVsBacktest().maxEquityDiff());
        }
        boolean beats = atLeast(in.agent().sharpe(), in.benchmark().sharpe())
                || atLeast(in.agent().calmar(), in.benchmark().calmar());
        if (!beats) {
            failures.add("Sharpe and Calmar below the benchmark");
        }
        boolean dd = in.agent().maxDrawdown() <= maxDrawdown;
        if (!dd) {
            failures.add("drawdown " + in.agent().maxDrawdown() + " above limit " + maxDrawdown);
        }
        double rank = percentileRank(in.agentReturn(), in.randomReturns());
        boolean randoms = rank >= minPercentileRank;
        if (!randoms) {
            failures.add("above only " + rank + " of the random agents, " + minPercentileRank + " needed");
        }
        return new Evaluation(time, match, beats, dd, randoms, rank, failures);
    }

    /** Share of random results that are strictly below the agent result. */
    public static double percentileRank(double value, double[] randoms) {
        if (randoms.length == 0) {
            return 0;
        }
        long below = Arrays.stream(randoms).filter(r -> r < value).count();
        return (double) below / randoms.length;
    }

    private static boolean atLeast(double a, double b) {
        return !Double.isNaN(a) && (Double.isNaN(b) || a >= b);
    }
}
