package com.adrianrevilla.trading.arena;

import com.adrianrevilla.trading.backtest.BacktestConfig;
import com.adrianrevilla.trading.backtest.BacktestEngine;
import com.adrianrevilla.trading.backtest.BacktestResult;
import com.adrianrevilla.trading.backtest.BacktestResult.Decision;
import com.adrianrevilla.trading.backtest.BacktestResult.EquityPoint;
import com.adrianrevilla.trading.brokersim.SimulatedBroker;
import com.adrianrevilla.trading.core.Alerter;
import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.ExecutionCosts;
import com.adrianrevilla.trading.core.InMemoryKillSwitch;
import com.adrianrevilla.trading.core.InMemoryMarketSnapshot;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.OrderRequest;
import com.adrianrevilla.trading.core.PortfolioState;
import com.adrianrevilla.trading.core.RiskContext;
import com.adrianrevilla.trading.core.Strategy;
import com.adrianrevilla.trading.core.TargetPortfolio;
import com.adrianrevilla.trading.risk.RiskConfig;
import com.adrianrevilla.trading.risk.RiskManager;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs all agents on the same data, one day at a time, each isolated from the others. Order of a day (TRD 9):
 * 1) data (given), 2) simulated execution of yesterday orders at today open, 3) valuation at the close,
 * 4) decision of each agent with data up to today, 5) risk manager and pending orders, 6) report and alerts.
 */
public final class Arena {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int LIQUIDITY_DAYS = 20;

    public record AgentDay(String agentId, AgentStatus status, BigDecimal equity, int ordersSent, String error) {}

    public record DayReport(LocalDate day, List<AgentDay> agents) {}

    private final Map<Isin, List<Bar>> series;
    private final Map<Isin, TreeMap<LocalDate, Bar>> byDate = new LinkedHashMap<>();
    private final ExecutionCosts costs;
    private final Alerter alerter;
    private final Map<String, Agent> agents = new LinkedHashMap<>();

    public Arena(Map<Isin, List<Bar>> series, ExecutionCosts costs, Alerter alerter) {
        this.series = series;
        this.costs = costs;
        this.alerter = alerter;
        series.forEach((isin, bars) -> {
            TreeMap<LocalDate, Bar> m = new TreeMap<>();
            bars.forEach(b -> m.put(b.date(), b));
            byDate.put(isin, m);
        });
    }

    public Agent addAgent(String id, Agent.Kind kind, Strategy strategy, BigDecimal capital, RiskConfig riskConfig) {
        if (agents.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate agent " + id);
        }
        var broker = new SimulatedBroker(costs, capital);
        var risk = RiskManager.standard(riskConfig, new InMemoryKillSwitch());
        var agent = new Agent(id, kind, strategy, capital, broker, risk, AgentStatus.ARENA);
        agents.put(id, agent);
        return agent;
    }

    public Agent agent(String id) {
        return agents.get(id);
    }

    public List<Agent> agents() {
        return List.copyOf(agents.values());
    }

    /** Runs one day for every agent; a failure in one agent never stops the others. */
    public DayReport runDay(LocalDate day) {
        List<AgentDay> out = new ArrayList<>();
        for (Agent a : agents.values()) {
            int sent = 0;
            String error = null;
            try {
                sent = runAgentDay(a, day);
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                a.lastError = error;
                alerter.alert(Alerter.Level.CRITICAL, "Agent " + a.id() + " failed on " + day + ": " + error);
            }
            BigDecimal eq = a.equity.isEmpty() ? a.initialCapital() : a.equity.get(a.equity.size() - 1).equity();
            out.add(new AgentDay(a.id(), a.status(), eq, sent, error));
        }
        return new DayReport(day, out);
    }

    private int runAgentDay(Agent a, LocalDate day) {
        if (a.status() == AgentStatus.RETIRED) {
            return 0;
        }
        SimulatedBroker broker = a.broker();

        // 2) yesterday orders execute at today open
        Map<Isin, BigDecimal> opens = new LinkedHashMap<>();
        Map<Isin, BigDecimal> closes = latestCloses(day);
        byDate.forEach((isin, m) -> {
            Bar b = m.get(day);
            if (b != null) {
                opens.put(isin, b.open());
            }
        });
        broker.executePending(day, opens);

        // 3) valuation at the close
        Map<Isin, BigDecimal> units = broker.positions();
        BigDecimal invested = BigDecimal.ZERO;
        for (var e : units.entrySet()) {
            invested = invested.add(e.getValue().multiply(closes.get(e.getKey()), MC));
        }
        BigDecimal equity = broker.cash().add(invested);
        BigDecimal dayStart = a.equity.isEmpty() ? a.initialCapital() : a.equity.get(a.equity.size() - 1).equity();
        a.equity.add(new EquityPoint(day, equity, invested));
        a.peak = a.peak.max(equity);

        if (!a.status().decides()) {
            return 0;
        }

        // 4) decision with data up to today
        var snapshot = new InMemoryMarketSnapshot(day, series);
        var state = new PortfolioState(units, broker.cash());
        TargetPortfolio target = a.strategy().decide(snapshot, state);
        a.decisions.add(new Decision(day, target));

        // 5) risk manager has the last word; approved orders wait for tomorrow open
        var ctx = new RiskContext(state, closes, averageDailyValue(day), dayStart, a.peak, Set.of(), true);
        RiskManager.Review review = a.risk().review(a.id(), target, ctx);
        int sent = 0;
        for (OrderRequest o : review.approved()) {
            try {
                broker.submit(o);
                sent++;
            } catch (com.adrianrevilla.trading.core.BrokerRejectedException e) {
                throw new IllegalStateException("Simulated broker rejected an approved order", e);
            }
        }
        if (review.shutdownAgent()) {
            a.moveTo(AgentStatus.RETIRED);
            alerter.alert(Alerter.Level.CRITICAL, "Agent " + a.id() + " shut down: " + review.shutdownReason());
        }
        return sent;
    }

    private Map<Isin, BigDecimal> latestCloses(LocalDate day) {
        Map<Isin, BigDecimal> m = new LinkedHashMap<>();
        byDate.forEach((isin, bars) -> {
            var e = bars.floorEntry(day);
            if (e != null) {
                m.put(isin, e.getValue().close());
            }
        });
        return m;
    }

    private Map<Isin, BigDecimal> averageDailyValue(LocalDate day) {
        Map<Isin, BigDecimal> m = new LinkedHashMap<>();
        byDate.forEach((isin, bars) -> {
            BigDecimal sum = BigDecimal.ZERO;
            int n = 0;
            for (var e : bars.headMap(day, true).descendingMap().values()) {
                sum = sum.add(e.close().multiply(BigDecimal.valueOf(e.volume()), MC));
                if (++n >= LIQUIDITY_DAYS) {
                    break;
                }
            }
            if (n > 0) {
                m.put(isin, sum.divide(BigDecimal.valueOf(n), MC));
            }
        });
        return m;
    }

    /**
     * Weekly check: reruns the backtest of the agent over the period it has lived and compares it with the arena.
     * A big divergence means an engine bug, look-ahead, or badly modelled costs; fix it before trusting results.
     */
    public LiveVsBacktest.Result compareWithBacktest(String agentId, BacktestConfig cfg) {
        Agent a = agents.get(agentId);
        if (a.equity.size() < 2) {
            throw new IllegalStateException("Not enough history to compare");
        }
        LocalDate from = a.equity.get(0).date();
        LocalDate to = a.equity.get(a.equity.size() - 1).date();
        BacktestResult bt = new BacktestEngine().run(a.strategy(), series, from, to, cfg);
        return LiveVsBacktest.compare(a.result(), bt, a.initialCapital());
    }
}
