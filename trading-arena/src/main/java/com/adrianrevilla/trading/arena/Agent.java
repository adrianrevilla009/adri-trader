package com.adrianrevilla.trading.arena;

import com.adrianrevilla.trading.backtest.BacktestResult;
import com.adrianrevilla.trading.backtest.BacktestResult.Decision;
import com.adrianrevilla.trading.backtest.BacktestResult.EquityPoint;
import com.adrianrevilla.trading.backtest.BacktestResult.Trade;
import com.adrianrevilla.trading.brokersim.SimulatedBroker;
import com.adrianrevilla.trading.core.OrderRequest;
import com.adrianrevilla.trading.core.Strategy;
import com.adrianrevilla.trading.risk.RiskManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A strategy with its own virtual portfolio, capital and risk limits. State is kept here and can always be
 * rebuilt by replaying the data, because every component is deterministic.
 */
public final class Agent {
    public enum Kind { STRATEGY, BENCHMARK, RANDOM }

    private final String id;
    private final Kind kind;
    private final Strategy strategy;
    private final BigDecimal initialCapital;
    private final SimulatedBroker broker;
    private final RiskManager risk;
    private AgentStatus status;

    final List<EquityPoint> equity = new ArrayList<>();
    final List<Decision> decisions = new ArrayList<>();
    BigDecimal peak;
    String lastError;

    Agent(String id, Kind kind, Strategy strategy, BigDecimal initialCapital, SimulatedBroker broker, RiskManager risk,
            AgentStatus status) {
        this.id = id;
        this.kind = kind;
        this.strategy = strategy;
        this.initialCapital = initialCapital;
        this.broker = broker;
        this.risk = risk;
        this.status = status;
        this.peak = initialCapital;
    }

    public String id() { return id; }
    public Kind kind() { return kind; }
    public Strategy strategy() { return strategy; }
    public BigDecimal initialCapital() { return initialCapital; }
    public AgentStatus status() { return status; }
    public String lastError() { return lastError; }
    SimulatedBroker broker() { return broker; }
    RiskManager risk() { return risk; }

    public List<EquityPoint> equity() { return List.copyOf(equity); }
    public List<Decision> decisions() { return List.copyOf(decisions); }

    void moveTo(AgentStatus next) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("Agent " + id + ": illegal move " + status + " -> " + next);
        }
        status = next;
    }

    /** The agent history as a backtest-style result so the same metrics apply. */
    public BacktestResult result() {
        List<Trade> trades = new ArrayList<>();
        for (var f : broker.fills()) {
            trades.add(new Trade(f.date(), f.isin(),
                    f.side() == OrderRequest.Side.BUY ? BacktestResult.Side.BUY : BacktestResult.Side.SELL,
                    f.quantity(), f.price(), f.amountEur(), f.costEur()));
        }
        return new BacktestResult(id, List.copyOf(equity), trades, List.copyOf(decisions));
    }
}
