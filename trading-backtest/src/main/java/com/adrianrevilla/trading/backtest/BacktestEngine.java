package com.adrianrevilla.trading.backtest;

import com.adrianrevilla.trading.backtest.BacktestResult.Decision;
import com.adrianrevilla.trading.backtest.BacktestResult.EquityPoint;
import com.adrianrevilla.trading.backtest.BacktestResult.Side;
import com.adrianrevilla.trading.backtest.BacktestResult.Trade;
import com.adrianrevilla.trading.brokersim.SimulatedBroker;
import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.BrokerRejectedException;
import com.adrianrevilla.trading.core.InMemoryMarketSnapshot;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.OrderPlanner;
import com.adrianrevilla.trading.core.OrderRequest;
import com.adrianrevilla.trading.core.PortfolioState;
import com.adrianrevilla.trading.core.Strategy;
import com.adrianrevilla.trading.core.TargetPortfolio;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Portfolio-level daily engine. Day t: valuation at the close, the strategy decides with data up to the close of
 * t and orders are sized at that close. Day t+1: the orders execute at the open of t+1 with spread and slippage
 * against the trader. The execution is the same {@link SimulatedBroker} the arena uses, and the order planner is
 * shared too, so backtest and arena cannot drift apart by implementation. The strategy only sees a snapshot
 * bounded to t, which is what prevents look-ahead.
 */
public final class BacktestEngine {
    private static final MathContext MC = MathContext.DECIMAL128;

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

        SimulatedBroker broker = new SimulatedBroker(cfg.costs(), cfg.initialCash());
        Map<Isin, LocalDate> entry = new HashMap<>();
        Map<Isin, BigDecimal> lastClose = new HashMap<>();
        List<EquityPoint> equity = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        int fillsSeen = 0;
        List<Trade> trades = new ArrayList<>();

        for (int i = 0; i < dates.size(); i++) {
            LocalDate d = dates.get(i);

            Map<Isin, BigDecimal> opens = new HashMap<>();
            byDate.forEach((isin, m) -> {
                Bar b = m.get(d);
                if (b != null) {
                    opens.put(isin, b.open());
                    lastClose.put(isin, b.close());
                }
            });
            broker.executePending(d, opens);
            List<SimulatedBroker.Fill> fills = broker.fills();
            for (; fillsSeen < fills.size(); fillsSeen++) {
                var f = fills.get(fillsSeen);
                trades.add(new Trade(f.date(), f.isin(), f.side() == OrderRequest.Side.BUY ? Side.BUY : Side.SELL,
                        f.quantity(), f.price(), f.amountEur(), f.costEur()));
            }

            Map<Isin, BigDecimal> units = broker.positions();
            entry.keySet().removeIf(isin -> !units.containsKey(isin));
            units.keySet().forEach(isin -> entry.putIfAbsent(isin, d));

            BigDecimal invested = BigDecimal.ZERO;
            for (var e : units.entrySet()) {
                invested = invested.add(e.getValue().multiply(lastClose.get(e.getKey()), MC));
            }
            BigDecimal cash = broker.cash();
            equity.add(new EquityPoint(d, cash.add(invested), invested));

            if (i < dates.size() - 1) {
                var snapshot = new InMemoryMarketSnapshot(d, series);
                var state = new PortfolioState(units, cash, entry);
                TargetPortfolio raw = strategy.decide(snapshot, state);
                decisions.add(new Decision(d, raw));
                TargetPortfolio target = cfg.adjuster().adjust(raw, state);
                for (OrderRequest o : OrderPlanner.plan(strategy.id(), target, state, lastClose, cfg.minOrderEur())) {
                    try {
                        broker.submit(o);
                    } catch (BrokerRejectedException e) {
                        throw new IllegalStateException("Simulated broker rejected a planned order", e);
                    }
                }
            }
        }
        return new BacktestResult(strategy.id(), equity, trades, decisions);
    }
}
