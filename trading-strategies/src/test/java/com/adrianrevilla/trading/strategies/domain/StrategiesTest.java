package com.adrianrevilla.trading.strategies.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.InMemoryMarketSnapshot;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.PortfolioState;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
import org.junit.jupiter.api.Test;

class StrategiesTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B3XXRP09");
    private static final Isin C = new Isin("IE00BK5BQT80");

    private static List<Bar> series(String start, int days, IntToDoubleFunction price) {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse(start);
        for (int i = 0; i < days; ) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                BigDecimal p = BigDecimal.valueOf(price.applyAsDouble(i));
                bars.add(new Bar(d, p, p, p, p, 1));
                i++;
            }
            d = d.plusDays(1);
        }
        return bars;
    }

    private static InMemoryMarketSnapshot snap(Map<Isin, List<Bar>> s) {
        List<Bar> any = s.values().iterator().next();
        return new InMemoryMarketSnapshot(any.get(any.size() - 1).date(), s);
    }

    private static PortfolioState flat() {
        return new PortfolioState(Map.of(), new BigDecimal("100"));
    }

    @Test
    void indicatorsMatchHandComputedValues() {
        var bars = series("2020-01-01", 5, i -> new double[] {10, 11, 12, 11, 13}[i]);
        assertThat(Indicators.sma(bars, 5).getAsDouble()).isEqualTo(11.4, org.assertj.core.api.Assertions.within(1e-12));
        assertThat(Indicators.returnOver(bars, 4).getAsDouble()).isEqualTo(0.3, org.assertj.core.api.Assertions.within(1e-12));
        // last two changes: -1 and +2 -> avg gain 1, avg loss 0.5 -> RS 2 -> RSI 66.67
        assertThat(Indicators.rsi(bars, 2).getAsDouble()).isBetween(66.6, 66.7);
        assertThat(Indicators.sma(bars, 6)).isEmpty();
    }

    @Test
    void trendEntersAboveBandAndHysteresisKeepsPosition() {
        var up = series("2020-01-01", 60, i -> 100 + i);
        var strategy = new TrendStrategy("t", List.of(A), null, 20, 0.02);
        assertThat(strategy.decide(snap(Map.of(A, up)), flat()).weights()).containsKey(A);

        // price slightly below SMA but inside the band: a holder stays, a non-holder does not enter
        var flatBars = series("2020-01-01", 30, i -> i < 29 ? 100 : 99);
        var holder = new PortfolioState(Map.of(A, BigDecimal.ONE), BigDecimal.ZERO);
        assertThat(strategy.decide(snap(Map.of(A, flatBars)), holder).weights()).containsKey(A);
        assertThat(strategy.decide(snap(Map.of(A, flatBars)), flat()).weights()).isEmpty();
    }

    @Test
    void trendSendsIdleSlotsToTheSafeAsset() {
        var down = series("2020-01-01", 60, i -> 200 - i);
        var safe = series("2020-01-01", 60, i -> 100);
        var strategy = new TrendStrategy("t", List.of(A), B, 20, 0.0);
        var w = strategy.decide(snap(Map.of(A, down, B, safe)), flat()).weights();
        assertThat(w).containsOnlyKeys(B);
    }

    @Test
    void momentumPicksTopNWithPositiveReturnAndGoesToCashOtherwise() {
        // 2020-01-01 .. enough bars that the last bar opens a new month
        var fast = series("2020-01-01", 70, i -> 100 + 3 * i);
        var slow = series("2020-01-01", 70, i -> 100 + i);
        var falling = series("2020-01-01", 70, i -> 200 - i);
        var s = new MomentumRotation("m", List.of(A, B, C), 20, 2);
        // find a length where the last bar is the first of a month
        int n = 0;
        for (int len = 30; len < 70; len++) {
            var sub = Map.of(A, fast.subList(0, len), B, slow.subList(0, len), C, falling.subList(0, len));
            var bars = fast.subList(0, len);
            if (bars.get(len - 1).date().getMonth() != bars.get(len - 2).date().getMonth()) {
                var w = s.decide(snap(sub), flat()).weights();
                assertThat(w).containsOnlyKeys(A, B);
                n++;
            }
        }
        assertThat(n).isPositive();
        var allDown = Map.of(A, falling, B, falling, C, falling);
        for (int len = 30; len < 70; len++) {
            var bars = falling.subList(0, len);
            if (bars.get(len - 1).date().getMonth() != bars.get(len - 2).date().getMonth()) {
                var sub = Map.of(A, falling.subList(0, len), B, falling.subList(0, len), C, falling.subList(0, len));
                assertThat(s.decide(snap(sub), flat()).weights()).isEmpty();
            }
        }
        assertThat(allDown).hasSize(3);
    }

    @Test
    void volatilityTargetGivesMoreWeightToTheCalmAssetAndRespectsTheCap() {
        var calm = series("2020-01-01", 70, i -> 100 + (i % 2 == 0 ? 0.1 : 0));
        var wild = series("2020-01-01", 70, i -> 100 + (i % 2 == 0 ? 5 : 0));
        var s = new VolatilityTarget("v", List.of(A, B), 20, 0.6);
        for (int len = 30; len < 70; len++) {
            var bars = calm.subList(0, len);
            if (bars.get(len - 1).date().getMonth() != bars.get(len - 2).date().getMonth()) {
                var w = s.decide(snap(Map.of(A, calm.subList(0, len), B, wild.subList(0, len))), flat()).weights();
                assertThat(w.get(A)).isGreaterThan(w.get(B));
                assertThat(w.get(A)).isLessThanOrEqualTo(new BigDecimal("0.6"));
                return;
            }
        }
        throw new AssertionError("no rebalance day found");
    }

    @Test
    void swingBuysPullbackInUptrendAndExitsOnTimeLimit() {
        // long uptrend then a 2-day dip: RSI(2) low while still above the 20-day average
        var bars = series("2020-01-01", 60, i -> i < 58 ? 100 + i : 100 + 57 - 3 * (i - 57));
        var s = new SwingRsi("s", List.of(A), 20, 10, 70, 5, 1);
        var w = s.decide(snap(Map.of(A, bars)), flat()).weights();
        assertThat(w).containsKey(A);

        // an open position held for more than maxHoldDays is closed
        var holder = new PortfolioState(Map.of(A, BigDecimal.ONE), BigDecimal.ZERO,
                Map.of(A, LocalDate.parse("2020-01-01")));
        var up = series("2020-01-01", 60, i -> 100 + i);
        assertThat(s.decide(snap(Map.of(A, up)), holder).weights()).isEmpty();
    }
}
