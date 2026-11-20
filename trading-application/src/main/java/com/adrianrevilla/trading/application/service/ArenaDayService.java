package com.adrianrevilla.trading.application.service;

import com.adrianrevilla.trading.application.port.in.RunArenaDay;
import com.adrianrevilla.trading.application.port.out.ArenaJournal;
import com.adrianrevilla.trading.arena.application.Arena;
import com.adrianrevilla.trading.arena.application.port.out.ArenaReportPublisher;
import com.adrianrevilla.trading.arena.domain.Agent;
import com.adrianrevilla.trading.arena.domain.ArenaReport;
import com.adrianrevilla.trading.arena.domain.DayReport;
import com.adrianrevilla.trading.backtest.domain.CostModel;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.data.application.port.out.BarRepository;
import com.adrianrevilla.trading.risk.domain.RiskConfig;
import com.adrianrevilla.trading.strategies.domain.BuyAndHold;
import com.adrianrevilla.trading.strategies.domain.MomentumRotation;
import com.adrianrevilla.trading.strategies.domain.SwingRsi;
import com.adrianrevilla.trading.strategies.domain.TrendStrategy;
import com.adrianrevilla.trading.strategies.domain.VolatilityTarget;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Nightly arena run. Every component is deterministic, so the arena is rebuilt by replaying the stored clean bars
 * and only the latest day is journaled, reported and alerted. Re-running the same day is harmless.
 */
public class ArenaDayService implements RunArenaDay {
    private final BarRepository bars;
    private final List<Isin> universe;
    private final Isin benchmark;
    private final String exchange;
    private final BigDecimal capital;
    private final ArenaJournal journal;
    private final Alerter alerter;
    private final ArenaReportPublisher reports;
    private final LocalDate startDate;

    public ArenaDayService(BarRepository bars, List<Isin> universe, Isin benchmark, String exchange, BigDecimal capital,
            ArenaJournal journal, Alerter alerter, ArenaReportPublisher reports) {
        this(bars, universe, benchmark, exchange, capital, journal, alerter, reports, null);
    }

    /** @param startDate first day the agents trade; earlier data is only history for indicators. Null = all days. */
    public ArenaDayService(BarRepository bars, List<Isin> universe, Isin benchmark, String exchange, BigDecimal capital,
            ArenaJournal journal, Alerter alerter, ArenaReportPublisher reports, LocalDate startDate) {
        this.startDate = startDate;
        this.bars = bars;
        this.universe = universe;
        this.benchmark = benchmark;
        this.exchange = exchange;
        this.capital = capital;
        this.journal = journal;
        this.alerter = alerter;
        this.reports = reports;
    }

    /** @return the day processed, empty when there is no data yet */
    @Override
    public Optional<LocalDate> run() {
        Map<Isin, List<Bar>> data = new LinkedHashMap<>();
        for (Isin isin : universe) {
            bars.findInstrumentId(isin, exchange).map(bars::loadClean).filter(l -> !l.isEmpty())
                    .ifPresent(l -> data.put(isin, l));
        }
        if (data.size() < universe.size()) {
            alerter.alert(Alerter.Level.CRITICAL, "Arena skipped: data missing for "
                    + (universe.size() - data.size()) + " instrument(s)");
            return Optional.empty();
        }
        // only days where every instrument has a bar, so valuation never lacks a price
        TreeSet<LocalDate> days = null;
        for (List<Bar> l : data.values()) {
            Set<LocalDate> own = new HashSet<>();
            l.forEach(b -> own.add(b.date()));
            if (days == null) {
                days = new TreeSet<>(own);
            } else {
                days.retainAll(own);
            }
        }
        if (days != null && startDate != null) {
            days.removeIf(d -> d.isBefore(startDate));
        }
        if (days == null || days.isEmpty()) {
            return Optional.empty();
        }
        Arena arena = build(data);
        DayReport last = null;
        for (LocalDate d : days) {
            last = arena.runDay(d);
        }
        journal.record(last);
        alerter.alert(Alerter.Level.INFO, ArenaReport.summary(last, capital));
        try {
            reports.publish(arena, last);
        } catch (IOException e) {
            alerter.alert(Alerter.Level.WARNING, "Arena report not written: " + e.getMessage());
        }
        return Optional.of(last.day());
    }

    private Arena build(Map<Isin, List<Bar>> data) {
        var arena = new Arena(data, CostModel.of("5", "2"), alerter);
        RiskConfig risk = RiskConfig.defaults(Set.copyOf(universe));
        int topN = Math.max(1, Math.min(3, universe.size()));
        arena.addAgent("benchmark", Agent.Kind.BENCHMARK, new BuyAndHold("benchmark", benchmark), capital, risk);
        arena.addAgent("trend", Agent.Kind.STRATEGY, new TrendStrategy("trend", universe, null, 200, 0.01), capital, risk);
        arena.addAgent("momentum", Agent.Kind.STRATEGY, new MomentumRotation("momentum", universe, 126, topN), capital,
                risk);
        arena.addAgent("vol-target", Agent.Kind.STRATEGY, new VolatilityTarget("vol-target", universe, 60, 0.25),
                capital, risk);
        arena.addAgent("swing", Agent.Kind.STRATEGY,
                new SwingRsi("swing", universe, 200, 10, 65, 10, Math.max(1, Math.min(4, universe.size()))), capital,
                risk);
        return arena;
    }
}
