package com.adrianrevilla.trading.strategies;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.InMemoryMarketSnapshot;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.PortfolioState;
import com.adrianrevilla.trading.core.Strategy;
import com.adrianrevilla.trading.core.TargetPortfolio;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntToDoubleFunction;
import org.junit.jupiter.api.Test;

/** Every strategy must survive awkward data: no history, flat prices, spikes. Output is always a valid, repeatable portfolio. */
class StrategyRobustnessTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B5BMR087");
    private static final Isin C = new Isin("IE00B4K48X80");
    private static final List<Isin> UNIVERSE = List.of(A, B, C);

    private static List<Strategy> all() {
        return List.of(new BuyAndHold("bh", A), new TrendStrategy("trend", UNIVERSE, null, 50, 0.01),
                new MomentumRotation("mom", UNIVERSE, 60, 2), new VolatilityTarget("vt", UNIVERSE, 30, 0.25),
                new SwingRsi("swing", UNIVERSE, 50, 10, 65, 10, 3),
                new RandomMatchedStrategy("rnd", UNIVERSE, 7, 0.6, 0.1, 2));
    }

    private static List<Bar> series(int days, IntToDoubleFunction price) {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2020-01-01");
        for (int i = 0; i < days; ) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                BigDecimal p = BigDecimal.valueOf(price.applyAsDouble(i));
                bars.add(new Bar(d, p, p, p, p, 1000));
                i++;
            }
            d = d.plusDays(1);
        }
        return bars;
    }

    private static InMemoryMarketSnapshot snapshot(Map<Isin, List<Bar>> data) {
        LocalDate last = data.values().stream().filter(l -> !l.isEmpty()).map(l -> l.get(l.size() - 1).date())
                .max(LocalDate::compareTo).orElse(LocalDate.parse("2020-01-01"));
        return new InMemoryMarketSnapshot(last, data);
    }

    private static PortfolioState flat() {
        return new PortfolioState(Map.of(), new BigDecimal("100"));
    }

    private static void assertValid(TargetPortfolio t) {
        BigDecimal sum = BigDecimal.ZERO;
        for (var w : t.weights().entrySet()) {
            assertThat(w.getValue().signum()).as("no short positions").isGreaterThanOrEqualTo(0);
            assertThat(UNIVERSE).as("only instruments of the universe").contains(w.getKey());
            sum = sum.add(w.getValue());
        }
        assertThat(sum).as("long only, no leverage").isLessThanOrEqualTo(BigDecimal.ONE.add(new BigDecimal("1e-9")));
    }

    @Test
    void noHistoryAtAllYieldsAValidPortfolioWithoutErrors() {
        Map<Isin, List<Bar>> empty = new HashMap<>();
        for (Isin i : UNIVERSE) {
            empty.put(i, List.of());
        }
        for (Strategy s : all()) {
            assertValid(s.decide(snapshot(empty), flat()));
        }
    }

    @Test
    void historyShorterThanAnyWindowYieldsAValidPortfolio() {
        Map<Isin, List<Bar>> data = new HashMap<>();
        for (Isin i : UNIVERSE) {
            data.put(i, series(3, k -> 100 + k));
        }
        for (Strategy s : all()) {
            assertValid(s.decide(snapshot(data), flat()));
        }
    }

    @Test
    void perfectlyFlatPricesNeverCauseADivisionByZero() {
        Map<Isin, List<Bar>> data = new HashMap<>();
        for (Isin i : UNIVERSE) {
            data.put(i, series(300, k -> 100));
        }
        for (Strategy s : all()) {
            assertValid(s.decide(snapshot(data), flat()));
        }
    }

    @Test
    void anExtremeSpikeKeepsTheOutputValid() {
        Map<Isin, List<Bar>> data = new HashMap<>();
        for (Isin i : UNIVERSE) {
            data.put(i, series(300, k -> k == 150 ? 10_000 : 100 + (k % 7)));
        }
        for (Strategy s : all()) {
            assertValid(s.decide(snapshot(data), flat()));
        }
    }

    @Test
    void anInstrumentMissingFromTheSnapshotDoesNotBreakTheOthers() {
        Map<Isin, List<Bar>> data = new HashMap<>();
        data.put(A, series(300, k -> 100 + k * 0.1));
        data.put(B, series(300, k -> 100 - k * 0.05));
        // C has no entry at all
        for (Strategy s : all()) {
            assertValid(s.decide(snapshot(data), flat()));
        }
    }

    @Test
    void randomWalksAlwaysGiveValidAndRepeatableDecisions() {
        for (long seed = 1; seed <= 25; seed++) {
            Random rnd = new Random(seed);
            Map<Isin, List<Bar>> data = new HashMap<>();
            for (Isin i : UNIVERSE) {
                double[] p = {100};
                data.put(i, series(260, k -> p[0] *= 1 + 0.0004 + 0.012 * rnd.nextGaussian()));
            }
            var snap = snapshot(data);
            for (Strategy s : all()) {
                TargetPortfolio first = s.decide(snap, flat());
                assertValid(first);
                assertThat(s.decide(snap, flat()).weights()).as(s.id() + " seed " + seed + " is repeatable")
                        .isEqualTo(first.weights());
            }
        }
    }
}
