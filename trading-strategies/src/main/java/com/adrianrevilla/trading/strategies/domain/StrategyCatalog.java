package com.adrianrevilla.trading.strategies.domain;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.Strategy;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Registry of the strategies an agent can use: lists them, validates parameters and builds them (ADR-0030). */
public final class StrategyCatalog {
    private final Map<String, StrategyDefinition> byId = new TreeMap<>();

    public StrategyCatalog(List<StrategyDefinition> definitions) {
        for (StrategyDefinition d : definitions) {
            if (byId.put(d.id(), d) != null) {
                throw new IllegalArgumentException("duplicate strategy id: " + d.id());
            }
        }
    }

    /** All strategies ordered by id. */
    public List<StrategyDefinition> list() {
        return List.copyOf(byId.values());
    }

    public Optional<StrategyDefinition> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * The complete parameters of an agent: the given ones checked against the spec, the missing ones filled with
     * defaults. Unknown strategies, unknown parameter names and invalid values throw IllegalArgumentException.
     */
    public Map<String, BigDecimal> resolveParams(String strategyId, Map<String, BigDecimal> given) {
        StrategyDefinition def = find(strategyId)
                .orElseThrow(() -> new IllegalArgumentException("unknown strategy: " + strategyId));
        Map<String, ParamSpec> specs = new LinkedHashMap<>();
        def.params().forEach(p -> specs.put(p.name(), p));
        for (String name : given.keySet()) {
            if (!specs.containsKey(name)) {
                throw new IllegalArgumentException("strategy " + strategyId + " has no param " + name
                        + "; known: " + specs.keySet());
            }
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        specs.forEach((name, spec) -> {
            BigDecimal v = given.containsKey(name) ? given.get(name) : spec.defaultValue();
            spec.check(v);
            out.put(name, v);
        });
        return out;
    }

    /** Builds the strategy of an agent after resolving its params. */
    public Strategy create(String agentId, String strategyId, List<Isin> universe, Map<String, BigDecimal> params) {
        if (universe == null || universe.isEmpty()) {
            throw new IllegalArgumentException("the universe needs at least one instrument");
        }
        return find(strategyId).orElseThrow(() -> new IllegalArgumentException("unknown strategy: " + strategyId))
                .create(agentId, List.copyOf(universe), resolveParams(strategyId, params));
    }
}
