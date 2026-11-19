package com.adrianrevilla.trading.backtest;

import com.adrianrevilla.trading.backtest.BacktestResult.EquityPoint;
import com.adrianrevilla.trading.backtest.BacktestResult.Side;
import com.adrianrevilla.trading.backtest.BacktestResult.Trade;
import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.InMemoryMarketSnapshot;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.PortfolioState;
import com.adrianrevilla.trading.core.Strategy;
import com.adrianrevilla.trading.core.TargetPortfolio;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Portfolio-level daily engine. Day t: valuation at the close, strategy decides with data up to the close of t.
 * Day t+1: the decided target is executed at the open of t+1 with spread and slippage against the trader.
 * The strategy only sees a snapshot bounded to t, which is what prevents look-ahead.
 */
public final class BacktestEngine {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal DUST = new BigDecimal("1e-9");

    /**
     * @param series full history per instrument, sorted by date; bars before {@code start} are warm-up data
     */
    public BacktestResult run(Strategy strategy, Map<Isin, List<Bar>> series, LocalDate start, LocalDate end,
            BacktestConfig cfg) {
        Map<Isin, TreeMap<LocalDate, Bar>> byDate = new HashMap<>();
        TreeSet<LocalDate> dateSet = new TreeSet<>();
        series.forEach((isin, bars) -> {
            TreeMap<LocalDate, Bar> m = new TreeMap<>();
            for (Bar b : bars) {
                m.put(b.date(), b);
                if (!b.date().isBefore(start) && !b.date().isAfter(end)) {
                    dateSet.add(b.date());
                }
            }
            byDate.put(isin, m);
        });
        List<LocalDate> dates = new ArrayList<>(dateSet);

        BigDecimal cash = cfg.initialCash();
        Map<Isin, BigDecimal> units = new HashMap<>();
        Map<Isin, LocalDate> entry = new HashMap<>();
        Map<Isin, BigDecimal> lastClose = new HashMap<>();
        List<EquityPoint> equity = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        TargetPortfolio pending = null;

        for (int i = 0; i < dates.size(); i++) {
            LocalDate d = dates.get(i);

            if (pending != null) {
                cash = execute(pending, d, byDate, lastClose, units, entry, cash, cfg, trades);
                pending = null;
            }

            BigDecimal invested = BigDecimal.ZERO;
            for (var e : units.entrySet()) {
                Bar bar = byDate.get(e.getKey()).get(d);
                if (bar != null) {
                    lastClose.put(e.getKey(), bar.close());
                }
                invested = invested.add(e.getValue().multiply(lastClose.get(e.getKey()), MC));
            }
            equity.add(new EquityPoint(d, cash.add(invested), invested));

            if (i < dates.size() - 1) {
                var snapshot = new InMemoryMarketSnapshot(d, series);
                var state = new PortfolioState(units, cash, entry);
                TargetPortfolio target = strategy.decide(snapshot, state);
                pending = cfg.adjuster().adjust(target, state);
            }
        }
        return new BacktestResult(strategy.id(), equity, trades);
    }

    private BigDecimal execute(TargetPortfolio target, LocalDate d, Map<Isin, TreeMap<LocalDate, Bar>> byDate,
            Map<Isin, BigDecimal> lastClose, Map<Isin, BigDecimal> units, Map<Isin, LocalDate> entry,
            BigDecimal cash, BacktestConfig cfg, List<Trade> trades) {
        Set<Isin> all = new HashSet<>(units.keySet());
        all.addAll(target.weights().keySet());

        Map<Isin, BigDecimal> open = new HashMap<>();
        for (Isin isin : all) {
            TreeMap<LocalDate, Bar> m = byDate.get(isin);
            Bar bar = m == null ? null : m.get(d);
            if (bar != null) {
                open.put(isin, bar.open());
            }
        }
        BigDecimal equityOpen = cash;
        for (var e : units.entrySet()) {
            BigDecimal px = open.getOrDefault(e.getKey(), lastClose.get(e.getKey()));
            equityOpen = equityOpen.add(e.getValue().multiply(px, MC));
        }

        // sells first, then buys (also the order used live)
        Map<Isin, BigDecimal> buys = new HashMap<>();
        for (Isin isin : all) {
            BigDecimal px = open.get(isin);
            if (px == null) {
                continue; // no bar today: cannot trade this instrument
            }
            BigDecimal weight = target.weights().getOrDefault(isin, BigDecimal.ZERO);
            BigDecimal desired = weight.multiply(equityOpen, MC);
            BigDecimal held = units.getOrDefault(isin, BigDecimal.ZERO);
            BigDecimal current = held.multiply(px, MC);
            BigDecimal delta = desired.subtract(current);
            BigDecimal adverse = cfg.costs().adverseFraction(isin);

            if (delta.signum() < 0) {
                boolean closeAll = weight.signum() == 0;
                BigDecimal sellValue = delta.negate();
                if (!closeAll && sellValue.compareTo(cfg.minOrderEur()) < 0) {
                    continue;
                }
                BigDecimal qty = closeAll ? held : sellValue.divide(px, MC).min(held);
                BigDecimal execPrice = px.multiply(BigDecimal.ONE.subtract(adverse), MC);
                BigDecimal proceeds = qty.multiply(execPrice, MC);
                cash = cash.add(proceeds);
                BigDecimal left = held.subtract(qty);
                if (left.compareTo(DUST) < 0) {
                    units.remove(isin);
                    entry.remove(isin);
                } else {
                    units.put(isin, left);
                }
                trades.add(new Trade(d, isin, Side.SELL, qty, execPrice, proceeds,
                        qty.multiply(px.subtract(execPrice), MC)));
            } else if (delta.compareTo(cfg.minOrderEur()) >= 0) {
                buys.put(isin, delta);
            }
        }

        BigDecimal totalBuy = buys.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal scale = totalBuy.compareTo(cash) > 0 && totalBuy.signum() > 0
                ? cash.divide(totalBuy, MC) : BigDecimal.ONE;
        for (var e : buys.entrySet()) {
            Isin isin = e.getKey();
            BigDecimal px = open.get(isin);
            BigDecimal spend = e.getValue().multiply(scale, MC);
            if (spend.compareTo(cfg.minOrderEur()) < 0 || spend.signum() <= 0) {
                continue;
            }
            BigDecimal execPrice = px.multiply(BigDecimal.ONE.add(cfg.costs().adverseFraction(isin)), MC);
            BigDecimal qty = spend.divide(execPrice, MC);
            cash = cash.subtract(spend);
            if (!units.containsKey(isin)) {
                entry.put(isin, d);
            }
            units.merge(isin, qty, BigDecimal::add);
            trades.add(new Trade(d, isin, Side.BUY, qty, execPrice, spend, qty.multiply(execPrice.subtract(px), MC)));
        }
        return cash.setScale(12, RoundingMode.HALF_EVEN);
    }
}
