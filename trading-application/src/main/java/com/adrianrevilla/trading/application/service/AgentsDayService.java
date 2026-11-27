package com.adrianrevilla.trading.application.service;

import com.adrianrevilla.trading.application.port.in.RunAgentsDay;
import com.adrianrevilla.trading.application.port.in.RunTradingDay;
import com.adrianrevilla.trading.application.port.out.AgentStore;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.domain.AgentConfig;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs the daily loop of every active agent, one after another and each isolated from the rest: a failure while
 * building or running one agent is reported and the next one still runs (ADR-0030). The agents are read from the store
 * on each call, so a change made from the API applies from the next daily cycle.
 */
public class AgentsDayService implements RunAgentsDay {
    /** Builds the daily loop of one agent (strategy, budget, risk limits). Throws if the agent cannot be built. */
    @FunctionalInterface
    public interface DayFactory {
        RunTradingDay create(AgentConfig agent);
    }

    private final AgentStore store;
    private final DayFactory factory;
    private final Alerter alerter;

    public AgentsDayService(AgentStore store, DayFactory factory, Alerter alerter) {
        this.store = Objects.requireNonNull(store, "store");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.alerter = Objects.requireNonNull(alerter, "alerter");
    }

    @Override
    public List<AgentResult> run(LocalDate day) {
        List<AgentResult> results = new ArrayList<>();
        for (AgentConfig agent : store.findActive()) {
            results.add(new AgentResult(agent.id(), runOne(agent, day)));
        }
        return results;
    }

    private RunTradingDay.Result runOne(AgentConfig agent, LocalDate day) {
        try {
            return factory.create(agent).run(day);
        } catch (RuntimeException e) {
            alerter.alert(Alerter.Level.CRITICAL, "Agent " + agent.id() + " could not run on " + day + ": "
                    + e.getMessage());
            return RunTradingDay.Result.of(RunTradingDay.Outcome.FAILED, e.getMessage());
        }
    }
}
