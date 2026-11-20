package com.adrianrevilla.trading.app;

import com.adrianrevilla.trading.arena.AgentStatus;
import com.adrianrevilla.trading.arena.Arena;
import com.adrianrevilla.trading.core.Alerter;
import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Broker;
import com.adrianrevilla.trading.core.InMemoryMarketSnapshot;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.KillSwitch;
import com.adrianrevilla.trading.core.PortfolioState;
import com.adrianrevilla.trading.core.RiskContext;
import com.adrianrevilla.trading.core.Strategy;
import com.adrianrevilla.trading.core.TargetPortfolio;
import com.adrianrevilla.trading.data.BarRepository;
import com.adrianrevilla.trading.execution.ExecutionService;
import com.adrianrevilla.trading.execution.OrderRepository;
import com.adrianrevilla.trading.execution.RealTradingGate;
import com.adrianrevilla.trading.risk.RiskManager;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The daily live loop of one agent: reconcile, read data, decide, let the risk manager judge, send, record.
 * The decision is made on the close of {@code day}; orders execute at the next open. Safe to call twice for the
 * same day: the day is claimed atomically and a second call does nothing. Works against any {@link Broker}
 * (simulated, dry-run or eToro), so the whole path is tested without eToro.
 */
public class TradingDayJob {
    public enum Outcome { TRADED, KILL_SWITCH_ACTIVE, NOT_ALLOWED, ALREADY_RAN, NO_DATA, HALTED, FAILED }

    public record Result(Outcome outcome, int sent, int rejected, int unknown, String detail) {
        static Result of(Outcome o, String detail) {
            return new Result(o, 0, 0, 0, detail);
        }
    }

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int LIQUIDITY_DAYS = 20;

    private final String agentId;
    private final Strategy strategy;
    private final List<Isin> universe;
    private final String exchange;
    private final BarRepository bars;
    private final ExecutionService execution;
    private final OrderRepository orders;
    private final Broker broker;
    private final KillSwitch killSwitch;
    private final RiskManager risk;
    private final JdbcTradingDayLog dayLog;
    private final JdbcArenaJournal journal;
    private final Alerter alerter;
    private final boolean real;
    private final RealTradingGate gate;

    public TradingDayJob(String agentId, Strategy strategy, List<Isin> universe, String exchange, BarRepository bars,
            ExecutionService execution, OrderRepository orders, Broker broker, KillSwitch killSwitch, RiskManager risk,
            JdbcTradingDayLog dayLog, JdbcArenaJournal journal, Alerter alerter, boolean real, RealTradingGate gate) {
        this.agentId = agentId;
        this.strategy = strategy;
        this.universe = List.copyOf(universe);
        this.exchange = exchange;
        this.bars = bars;
        this.execution = execution;
        this.orders = orders;
        this.broker = broker;
        this.killSwitch = killSwitch;
        this.risk = risk;
        this.dayLog = dayLog;
        this.journal = journal;
        this.alerter = alerter;
        this.real = real;
        this.gate = gate;
    }

    public Result run(LocalDate day) {
        if (killSwitch.isActive()) {
            return Result.of(Outcome.KILL_SWITCH_ACTIVE, "kill switch active");
        }
        if (real && !gate.allows(agentId)) {
            alerter.alert(Alerter.Level.WARNING, "Agent " + agentId + " is not enabled for real trading; skipped");
            return Result.of(Outcome.NOT_ALLOWED, "real trading not enabled for this agent");
        }

        // 1) bring the store up to date with what the broker did since the last run, and compare
        execution.syncOpenOrders();
        if (!execution.reconcile(agentId).ok() || killSwitch.isActive()) {
            return Result.of(Outcome.HALTED, "reconciliation difference or halt; trading stopped");
        }

        // 2) data: every instrument must have its bar of the day
        Map<Isin, List<Bar>> data = new LinkedHashMap<>();
        for (Isin isin : universe) {
            List<Bar> list = bars.findInstrumentId(isin, exchange).map(bars::loadClean).orElse(List.of()).stream()
                    .filter(b -> !b.date().isAfter(day)).toList();
            if (list.isEmpty() || !list.get(list.size() - 1).date().equals(day)) {
                alerter.alert(Alerter.Level.WARNING, "No data for " + isin.value() + " on " + day + "; agent " + agentId
                        + " does not trade today");
                return Result.of(Outcome.NO_DATA, "missing bar for " + isin.value());
            }
            data.put(isin, list);
        }

        // 3) claim the day; from here on a crash is never retried automatically
        if (!dayLog.claim(day, agentId)) {
            if (dayLog.status(day, agentId).filter("STARTED"::equals).isPresent()) {
                alerter.alert(Alerter.Level.CRITICAL, "Agent " + agentId + " day " + day
                        + " was started and never finished; orders may have gone out. Check by hand, not retried.");
            }
            return Result.of(Outcome.ALREADY_RAN, "day already claimed");
        }
        try {
            return trade(day, data);
        } catch (Exception e) {
            dayLog.finish(day, agentId, false, e.getClass().getSimpleName() + ": " + e.getMessage());
            alerter.alert(Alerter.Level.CRITICAL, "Agent " + agentId + " failed on " + day + ": " + e.getMessage());
            return Result.of(Outcome.FAILED, e.getMessage());
        }
    }

    private Result trade(LocalDate day, Map<Isin, List<Bar>> data) throws Exception {
        Map<Isin, BigDecimal> prices = new HashMap<>();
        Map<Isin, BigDecimal> avgValue = new HashMap<>();
        data.forEach((isin, list) -> {
            prices.put(isin, list.get(list.size() - 1).close());
            BigDecimal sum = BigDecimal.ZERO;
            List<Bar> tail = list.subList(Math.max(0, list.size() - LIQUIDITY_DAYS), list.size());
            for (Bar b : tail) {
                sum = sum.add(b.close().multiply(BigDecimal.valueOf(b.volume()), MC));
            }
            avgValue.put(isin, sum.divide(BigDecimal.valueOf(tail.size()), MC));
        });

        PortfolioState state = new PortfolioState(orders.netUnits(agentId), broker.cash());
        BigDecimal equity = state.cash();
        for (var e : state.positions().entrySet()) {
            equity = equity.add(e.getValue().multiply(prices.getOrDefault(e.getKey(), BigDecimal.ZERO), MC));
        }
        Map<LocalDate, BigDecimal> history = journal.equity(agentId);
        BigDecimal peak = history.values().stream().reduce(equity, BigDecimal::max);
        BigDecimal dayStart = history.entrySet().stream().filter(e -> e.getKey().isBefore(day))
                .reduce((a, b) -> b).map(Map.Entry::getValue).orElse(equity);

        TargetPortfolio target = strategy.decide(new InMemoryMarketSnapshot(day, data), state);
        var context = new RiskContext(state, prices, avgValue, dayStart, peak, Set.of(), true);
        RiskManager.Review review = risk.review(agentId, target, context);
        if (review.shutdownAgent()) {
            alerter.alert(Alerter.Level.CRITICAL, "Agent " + agentId + " shut down by risk: " + review.shutdownReason());
        }

        int sent = 0;
        int rejected = 0;
        int unknown = 0;
        List<String> notes = new ArrayList<>();
        for (var order : review.approved()) {
            var outcome = execution.send(order);
            switch (outcome) {
                case SUBMITTED -> sent++;
                case REJECTED -> {
                    rejected++;
                    notes.add("rejected " + order.isin().value());
                }
                case UNKNOWN -> {
                    unknown++;
                    notes.add("unknown " + order.isin().value());
                }
                case BLOCKED -> notes.add("blocked " + order.isin().value());
            }
            if (outcome == ExecutionService.SendOutcome.BLOCKED) {
                break; // the kill switch came on while sending: stop, send nothing else
            }
        }
        execution.syncOpenOrders();

        AgentStatus status = review.shutdownAgent() ? AgentStatus.RETIRED : real ? AgentStatus.REAL_SMALL : AgentStatus.ARENA;
        journal.record(new Arena.DayReport(day, List.of(new Arena.AgentDay(agentId, status, equity, sent, null))));
        String detail = "sent " + sent + ", rejected " + rejected + ", unknown " + unknown
                + (notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")");
        dayLog.finish(day, agentId, true, detail);
        return new Result(Outcome.TRADED, sent, rejected, unknown, detail);
    }
}
