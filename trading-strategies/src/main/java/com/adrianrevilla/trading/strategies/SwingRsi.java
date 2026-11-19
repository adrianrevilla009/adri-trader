package com.adrianrevilla.trading.strategies;

import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.MarketSnapshot;
import com.adrianrevilla.trading.core.PortfolioState;
import com.adrianrevilla.trading.core.Strategy;
import com.adrianrevilla.trading.core.TargetPortfolio;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Swing on ETFs: buys short pullbacks inside an uptrend (RSI(2) below {@code entryRsi} and close above the
 * trend average) and exits on the target (RSI(2) above {@code exitRsi}), after {@code maxHoldDays} calendar
 * days, or when the trend breaks. Each position gets 1/maxPositions of the capital.
 */
public final class SwingRsi implements Strategy {
    private final String id;
    private final List<Isin> universe;
    private final int trendWindow;
    private final double entryRsi;
    private final double exitRsi;
    private final int maxHoldDays;
    private final int maxPositions;

    public SwingRsi(String id, List<Isin> universe, int trendWindow, double entryRsi, double exitRsi,
            int maxHoldDays, int maxPositions) {
        this.id = id;
        this.universe = List.copyOf(universe);
        this.trendWindow = trendWindow;
        this.entryRsi = entryRsi;
        this.exitRsi = exitRsi;
        this.maxHoldDays = maxHoldDays;
        this.maxPositions = maxPositions;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public TargetPortfolio decide(MarketSnapshot snapshot, PortfolioState state) {
        BigDecimal slot = StrategySupport.weight(1.0 / maxPositions);
        Map<Isin, BigDecimal> target = new HashMap<>();
        // keep or close open positions first
        for (Isin isin : universe) {
            if (!state.holds(isin)) {
                continue;
            }
            List<Bar> bars = snapshot.bars(isin);
            OptionalDouble rsi = Indicators.rsi(bars, 2);
            OptionalDouble sma = Indicators.sma(bars, trendWindow);
            if (rsi.isEmpty() || sma.isEmpty()) {
                continue;
            }
            long held = state.entryDates().containsKey(isin)
                    ? ChronoUnit.DAYS.between(state.entryDates().get(isin), snapshot.asOf()) : 0;
            boolean exit = rsi.getAsDouble() > exitRsi
                    || held >= maxHoldDays
                    || Indicators.close(bars.get(bars.size() - 1)) < sma.getAsDouble();
            if (!exit) {
                target.put(isin, slot);
            }
        }
        // new entries with the remaining slots
        for (Isin isin : universe) {
            if (target.size() >= maxPositions || target.containsKey(isin) || state.holds(isin)) {
                continue;
            }
            List<Bar> bars = snapshot.bars(isin);
            OptionalDouble rsi = Indicators.rsi(bars, 2);
            OptionalDouble sma = Indicators.sma(bars, trendWindow);
            if (rsi.isPresent() && sma.isPresent() && rsi.getAsDouble() < entryRsi
                    && Indicators.close(bars.get(bars.size() - 1)) > sma.getAsDouble()) {
                target.put(isin, slot);
            }
        }
        return new TargetPortfolio(target);
    }
}
