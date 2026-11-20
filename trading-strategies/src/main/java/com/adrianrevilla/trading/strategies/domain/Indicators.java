package com.adrianrevilla.trading.strategies.domain;

import com.adrianrevilla.trading.core.domain.Bar;
import java.util.List;
import java.util.OptionalDouble;

/** Simple indicators over closes, computed in {@code double} (statistics only; money stays BigDecimal). */
public final class Indicators {
    private Indicators() {}

    public static double close(Bar b) {
        return b.close().doubleValue();
    }

    public static OptionalDouble sma(List<Bar> bars, int window) {
        if (bars.size() < window) {
            return OptionalDouble.empty();
        }
        double sum = 0;
        for (int i = bars.size() - window; i < bars.size(); i++) {
            sum += close(bars.get(i));
        }
        return OptionalDouble.of(sum / window);
    }

    /** Total return over the last {@code days} bars. */
    public static OptionalDouble returnOver(List<Bar> bars, int days) {
        if (bars.size() <= days) {
            return OptionalDouble.empty();
        }
        double last = close(bars.get(bars.size() - 1));
        double first = close(bars.get(bars.size() - 1 - days));
        return OptionalDouble.of(last / first - 1.0);
    }

    /** Standard deviation of daily returns over the last {@code window} returns. */
    public static OptionalDouble volatility(List<Bar> bars, int window) {
        if (bars.size() <= window) {
            return OptionalDouble.empty();
        }
        double[] r = new double[window];
        for (int i = 0; i < window; i++) {
            int idx = bars.size() - window + i;
            r[i] = close(bars.get(idx)) / close(bars.get(idx - 1)) - 1.0;
        }
        double mean = 0;
        for (double x : r) {
            mean += x;
        }
        mean /= window;
        double ss = 0;
        for (double x : r) {
            ss += (x - mean) * (x - mean);
        }
        return OptionalDouble.of(Math.sqrt(ss / (window - 1)));
    }

    /** RSI with simple averages of gains and losses over the last {@code period} changes. */
    public static OptionalDouble rsi(List<Bar> bars, int period) {
        if (bars.size() <= period) {
            return OptionalDouble.empty();
        }
        double gain = 0;
        double loss = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            double d = close(bars.get(i)) - close(bars.get(i - 1));
            if (d > 0) {
                gain += d;
            } else {
                loss -= d;
            }
        }
        if (loss == 0) {
            return OptionalDouble.of(gain == 0 ? 50.0 : 100.0);
        }
        double rs = (gain / period) / (loss / period);
        return OptionalDouble.of(100.0 - 100.0 / (1.0 + rs));
    }
}
