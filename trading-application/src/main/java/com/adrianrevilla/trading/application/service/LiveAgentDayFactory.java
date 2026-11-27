package com.adrianrevilla.trading.application.service;

import com.adrianrevilla.trading.application.port.in.RunTradingDay;
import com.adrianrevilla.trading.application.port.out.ArenaJournal;
import com.adrianrevilla.trading.application.port.out.TradingDayLog;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.domain.AgentBudgetBroker;
import com.adrianrevilla.trading.core.domain.AgentConfig;
import com.adrianrevilla.trading.core.domain.Broker;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.KillSwitch;
import com.adrianrevilla.trading.core.domain.Strategy;
import com.adrianrevilla.trading.data.application.port.out.BarRepository;
import com.adrianrevilla.trading.execution.application.ExecutionService;
import com.adrianrevilla.trading.execution.application.port.out.OrderRepository;
import com.adrianrevilla.trading.execution.domain.RealTradingGate;
import com.adrianrevilla.trading.risk.domain.RiskConfig;
import com.adrianrevilla.trading.risk.domain.RiskManager;
import com.adrianrevilla.trading.strategies.domain.StrategyCatalog;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the daily loop of one agent from its stored configuration (ADR-0030): its strategy from the catalog, its own
 * budget on the shared account and the risk limits for its universe. DEMO only: the real-money gate stays closed.
 * Every agent shares one {@link ExecutionService}, so they all reconcile against the same account.
 */
public class LiveAgentDayFactory implements AgentsDayService.DayFactory {
    private final StrategyCatalog catalog;
    private final BarRepository bars;
    private final ExecutionService execution;
    private final OrderRepository orders;
    private final Broker account;
    private final KillSwitch killSwitch;
    private final TradingDayLog dayLog;
    private final ArenaJournal journal;
    private final Alerter alerter;
    private final Function<Set<Isin>, RiskConfig> riskFor;
    private final Set<Isin> tradable;
    private final String exchange;

    /**
     * @param account the shared broker (the whole account); each agent gets its own budget view of it
     * @param riskFor risk limits for a given universe (the minimum order of the broker is applied there)
     * @param tradable instruments the broker can really trade; an agent using another one cannot be built
     */
    public LiveAgentDayFactory(StrategyCatalog catalog, BarRepository bars, ExecutionService execution,
            OrderRepository orders, Broker account, KillSwitch killSwitch, TradingDayLog dayLog, ArenaJournal journal,
            Alerter alerter, Function<Set<Isin>, RiskConfig> riskFor, Set<Isin> tradable, String exchange) {
        this.catalog = catalog;
        this.bars = bars;
        this.execution = execution;
        this.orders = orders;
        this.account = account;
        this.killSwitch = killSwitch;
        this.dayLog = dayLog;
        this.journal = journal;
        this.alerter = alerter;
        this.riskFor = riskFor;
        this.tradable = Set.copyOf(tradable);
        this.exchange = exchange;
    }

    @Override
    public RunTradingDay create(AgentConfig agent) {
        for (Isin isin : agent.universe()) {
            if (!tradable.contains(isin)) {
                throw new IllegalArgumentException("agent " + agent.id() + " uses " + isin.value()
                        + " which the broker cannot trade (no id in trading.etoro.instruments)");
            }
        }
        Strategy strategy = catalog.create(agent.id(), agent.strategyId(), agent.universe(), agent.params());
        Broker budget = new AgentBudgetBroker(account, agent.capital(), () -> orders.committedAmount(agent.id()));
        RiskManager risk = RiskManager.standard(riskFor.apply(Set.copyOf(agent.universe())), killSwitch);
        List<Isin> universe = agent.universe();
        return new TradingDayService(agent.id(), strategy, universe, exchange, bars, execution, orders, budget,
                killSwitch, risk, dayLog, journal, alerter, false, RealTradingGate.closed());
    }
}
