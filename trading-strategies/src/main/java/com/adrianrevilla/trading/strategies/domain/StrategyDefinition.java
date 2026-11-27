package com.adrianrevilla.trading.strategies.domain;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.Strategy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One strategy that can be chosen for an agent. A new strategy is a new implementation of this interface registered in
 * the catalog; the API and the screen need no change (ADR-0030). Java: the same kind of interface a Spring bean would implement.
 */
public interface StrategyDefinition {
    /** Stable identifier, for example {@code trend}. */
    String id();

    String name();

    String description();

    /** The parameters it understands, in display order. */
    List<ParamSpec> params();

    /**
     * Builds the strategy. {@code params} is already complete and valid (defaults applied, ranges checked) and
     * {@code universe} is never empty.
     */
    Strategy create(String agentId, List<Isin> universe, Map<String, BigDecimal> params);
}
