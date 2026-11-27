package com.adrianrevilla.trading.application.service;

import com.adrianrevilla.trading.application.port.in.ManageAgents;
import com.adrianrevilla.trading.application.port.in.QueryAgents;
import com.adrianrevilla.trading.application.port.out.AgentStore;
import com.adrianrevilla.trading.application.read.AgentView;
import com.adrianrevilla.trading.application.read.AuditView;
import com.adrianrevilla.trading.application.read.StrategyView;
import com.adrianrevilla.trading.core.domain.AgentConfig;
import com.adrianrevilla.trading.core.domain.CapitalPolicy;
import com.adrianrevilla.trading.core.domain.EscalationStep;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.TradingMode;
import com.adrianrevilla.trading.strategies.domain.StrategyCatalog;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Use cases to create, change, stop and resume agents and to read them (ADR-0030). All the rules live in the domain
 * (AgentConfig, CapitalPolicy, StrategyCatalog); this class only turns the plain input of the API into domain values,
 * stores the result with the actor for the audit trail and turns the result back into a read model. An update builds
 * the complete new agent first, so it is all or nothing.
 */
public class AgentService implements ManageAgents, QueryAgents {
    private final AgentStore store;
    private final StrategyCatalog catalog;
    private final CapitalPolicy policy;
    private final Set<Isin> tradable;

    /** @param tradable instruments the broker can trade: the only ones an agent universe may use */
    public AgentService(AgentStore store, StrategyCatalog catalog, CapitalPolicy policy, Set<Isin> tradable) {
        this.store = Objects.requireNonNull(store, "store");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.tradable = Set.copyOf(tradable);
    }

    // ---- commands ----

    @Override
    public AgentView create(CreateAgent command, String actor) {
        requireActor(actor);
        Objects.requireNonNull(command, "command");
        if (command.id() != null && store.find(command.id()).isPresent()) {
            throw new AgentAlreadyExistsException(command.id());
        }
        Map<String, BigDecimal> params = catalog.resolveParams(command.strategyId(),
                command.params() == null ? Map.of() : command.params());
        AgentConfig agent = AgentConfig.create(command.id(), command.strategyId(), params,
                universe(command.universe()), command.capital(), steps(command.steps()), TradingMode.DEMO, policy);
        store.save(agent, actor);
        return view(agent);
    }

    @Override
    public AgentView update(String id, UpdateAgent command, String actor) {
        requireActor(actor);
        Objects.requireNonNull(command, "command");
        AgentConfig old = require(id);
        Map<String, BigDecimal> params = command.params() == null ? old.params()
                : catalog.resolveParams(old.strategyId(), command.params());
        List<Isin> universe = command.universe() == null ? old.universe() : universe(command.universe());
        BigDecimal capital = command.capital() == null ? old.capital() : command.capital();
        List<EscalationStep> steps = command.steps() == null ? old.steps() : steps(command.steps());
        AgentConfig updated = AgentConfig.restore(old.id(), old.strategyId(), params, universe, capital, steps,
                old.mode(), old.state(), policy);
        store.save(updated, actor);
        return view(updated);
    }

    @Override
    public AgentView stop(String id, String actor) {
        requireActor(actor);
        AgentConfig stopped = require(id).stopped();
        store.save(stopped, actor);
        return view(stopped);
    }

    @Override
    public AgentView resume(String id, String actor) {
        requireActor(actor);
        AgentConfig resumed = require(id).resumed();
        store.save(resumed, actor);
        return view(resumed);
    }

    // ---- queries ----

    @Override
    public List<AgentView> list() {
        return store.findAll().stream().map(AgentService::view).toList();
    }

    @Override
    public Optional<AgentView> find(String id) {
        return id == null ? Optional.empty() : store.find(id).map(AgentService::view);
    }

    @Override
    public List<AuditView> audit(String id) {
        require(id);
        return store.audit(id).stream()
                .map(e -> new AuditView(e.agentId(), e.changedBy(), e.field(), e.oldValue(), e.newValue())).toList();
    }

    @Override
    public List<StrategyView> strategies() {
        return catalog.list().stream().map(d -> new StrategyView(d.id(), d.name(), d.description(),
                d.params().stream().map(p -> new StrategyView.Param(p.name(), p.description(), p.defaultValue(),
                        p.min(), p.max(), p.integer())).toList())).toList();
    }

    @Override
    public List<String> instruments() {
        return tradable.stream().map(Isin::value).sorted(Comparator.naturalOrder()).toList();
    }

    // ---- helpers ----

    private AgentConfig require(String id) {
        return (id == null ? Optional.<AgentConfig>empty() : store.find(id))
                .orElseThrow(() -> new AgentNotFoundException(id));
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("the actor is required for the audit trail");
        }
    }

    private List<Isin> universe(List<String> isins) {
        if (isins == null || isins.isEmpty()) {
            throw new IllegalArgumentException("the universe needs at least one instrument");
        }
        return isins.stream().map(this::tradableIsin).toList();
    }

    private Isin tradableIsin(String value) {
        Isin isin = new Isin(value);
        if (!tradable.contains(isin)) {
            throw new IllegalArgumentException("instrument " + value + " cannot be traded: the broker has no id for it");
        }
        return isin;
    }

    private static List<EscalationStep> steps(List<StepSpec> specs) {
        return specs == null ? List.of()
                : specs.stream().map(s -> new EscalationStep(s.afterDays(), s.capital())).toList();
    }

    private static AgentView view(AgentConfig a) {
        return new AgentView(a.id(), a.strategyId(), a.params(), a.universe().stream().map(Isin::value).toList(),
                a.capital(), a.steps().stream().map(s -> new AgentView.Step(s.afterDays(), s.capital())).toList(),
                a.mode().name(), a.state().name());
    }
}
