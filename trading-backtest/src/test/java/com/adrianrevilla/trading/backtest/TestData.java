package com.adrianrevilla.trading.backtest;

import com.adrianrevilla.trading.core.Bar;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntToDoubleFunction;

/** Synthetic series with a known answer (open = close = price of the day). */
final class TestData {
    private TestData() {}

    static List<Bar> series(LocalDate start, int days, double startPrice, IntToDoubleFunction dailyReturn) {
        List<Bar> bars = new ArrayList<>();
        double p = startPrice;
        LocalDate d = start;
        for (int i = 0; i < days; ) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                if (i > 0) {
                    p *= 1.0 + dailyReturn.applyAsDouble(i);
                }
                BigDecimal v = BigDecimal.valueOf(p).setScale(6, java.math.RoundingMode.HALF_EVEN);
                bars.add(new Bar(d, v, v, v, v, 1000));
                i++;
            }
            d = d.plusDays(1);
        }
        return bars;
    }

    static List<Bar> constantGrowth(LocalDate start, int days, double r) {
        return series(start, days, 100, i -> r);
    }

    static List<Bar> randomWalk(LocalDate start, int days, long seed, double drift, double vol) {
        Random rnd = new Random(seed);
        return series(start, days, 100, i -> drift + vol * rnd.nextGaussian());
    }
}
