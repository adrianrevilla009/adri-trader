package com.adrianrevilla.trading.core.domain;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Configuration of one agent (ADR-0030): which strategy, with which parameters, how much capital and whether it runs.
 * Immutable: each verb returns a new value, so the caller can compare old and new for the audit trail.
 * Java: like a final class with private fields and "withers"; equals/hashCode are by value.
 */
public final class AgentConfig {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final String id;
    private final String strategyId;
    private final Map<String, BigDecimal> params;
    private final List<Isin> universe;
    private final BigDecimal capital;
    private final List<EscalationStep> steps;
    private final TradingMode mode;
    private final AgentRunState state;

    private AgentConfig(String id, String strategyId, Map<String, BigDecimal> params, List<Isin> universe,
                        BigDecimal capital, List<EscalationStep> steps, TradingMode mode, AgentRunState state) {
        this.id = id;
        this.strategyId = strategyId;
        this.params = params;
        this.universe = universe;
        this.capital = capital;
        this.steps = steps;
        this.mode = mode;
        this.state = state;
    }

    /** Creates an ACTIVE agent after validating every field; throws IllegalArgumentException with a clear message. */
    public static AgentConfig create(String id, String strategyId, Map<String, BigDecimal> params,
                                     List<Isin> universe, BigDecimal capital, List<EscalationStep> steps,
                                     TradingMode mode, CapitalPolicy policy) {
        return build(id, strategyId, params, universe, capital, steps, mode, AgentRunState.ACTIVE, policy);
    }

    /** Rebuilds a stored agent (any state), with the same validation. */
    public static AgentConfig restore(String id, String strategyId, Map<String, BigDecimal> params,
                                      List<Isin> universe, BigDecimal capital, List<EscalationStep> steps,
                                      TradingMode mode, AgentRunState state, CapitalPolicy policy) {
        return build(id, strategyId, params, universe, capital, steps, mode, state, policy);
    }

    private static AgentConfig build(String id, String strategyId, Map<String, BigDecimal> params,
                                     List<Isin> universe, BigDecimal capital, List<EscalationStep> steps,
                                     TradingMode mode, AgentRunState state, CapitalPolicy policy) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "agent id must be 1-64 chars of a-z, 0-9, '-' or '_', starting with a letter or digit: " + id);
        }
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId is required");
        }
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(policy, "policy");
        if (mode != TradingMode.DEMO) {
            throw new IllegalArgumentException("mode " + mode + " is not available yet: only DEMO (ADR-0030)");
        }
        checkCapital(capital, policy);
        Map<String, BigDecimal> copy = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (k == null || k.isBlank() || v == null) {
                throw new IllegalArgumentException("param needs a name and a value: " + k);
            }
            copy.put(k, canonical(v));
        });
        checkSteps(capital, steps, policy);
        List<EscalationStep> canonicalSteps = steps.stream()
                .map(s -> new EscalationStep(s.afterDays(), canonical(s.capital()))).toList();
        return new AgentConfig(id, strategyId, Map.copyOf(copy), checkUniverse(universe), canonical(capital),
                canonicalSteps, mode, state);
    }

    private static List<Isin> checkUniverse(List<Isin> universe) {
        Objects.requireNonNull(universe, "universe");
        if (universe.isEmpty()) {
            throw new IllegalArgumentException("the universe needs at least one instrument");
        }
        java.util.Set<Isin> seen = new java.util.HashSet<>();
        for (Isin i : universe) {
            if (i == null) {
                throw new IllegalArgumentException("the universe cannot contain null");
            }
            if (!seen.add(i)) {
                throw new IllegalArgumentException("instrument " + i.value() + " is in the universe twice");
            }
        }
        return List.copyOf(universe);
    }

    /** Same number without trailing zeros ("40.00" and "40" become one value), so equals does not depend on scale. */
    /** Above this size (10^30) a number is nonsense for money or a parameter, and rewriting it would eat memory. */
    private static final int MAX_MAGNITUDE_DIGITS = 30;

    private static BigDecimal canonical(BigDecimal v) {
        // long arithmetic: the scale of 1e+2147483647 is near Integer.MIN_VALUE and would overflow an int
        if ((long) v.precision() - v.scale() > MAX_MAGNITUDE_DIGITS) {
            throw new IllegalArgumentException("number out of range (more than " + MAX_MAGNITUDE_DIGITS + " digits)");
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static void checkCapital(BigDecimal capital, CapitalPolicy policy) {
        if (capital == null || capital.signum() <= 0) {
            throw new IllegalArgumentException("capital must be positive: " + capital);
        }
        canonical(capital); // rejects an absurd magnitude before any other arithmetic
        if (!policy.accepts(capital)) {
            throw new IllegalArgumentException("capital " + capital + " is below the minimum "
                    + policy.minimumCapital() + " (the biggest position must reach the minimum order)");
        }
    }

    private static void checkSteps(BigDecimal capital, List<EscalationStep> steps, CapitalPolicy policy) {
        int lastDays = 0;
        BigDecimal lastCapital = capital;
        for (EscalationStep s : steps) {
            Objects.requireNonNull(s, "step");
            if (s.afterDays() <= lastDays) {
                throw new IllegalArgumentException("escalation steps must be in increasing order of days");
            }
            if (s.capital().compareTo(lastCapital) <= 0) {
                throw new IllegalArgumentException("each escalation step must raise the capital above " + lastCapital);
            }
            checkCapital(s.capital(), policy);
            lastDays = s.afterDays();
            lastCapital = s.capital();
        }
    }

    /** Same agent, not sending new orders. Positions are kept. Stopping a stopped agent changes nothing. */
    public AgentConfig stopped() {
        return state == AgentRunState.STOPPED ? this
                : new AgentConfig(id, strategyId, params, universe, capital, steps, mode, AgentRunState.STOPPED);
    }

    /** Same agent, sending orders again from the next daily cycle. Resuming an active agent changes nothing. */
    public AgentConfig resumed() {
        return state == AgentRunState.ACTIVE ? this
                : new AgentConfig(id, strategyId, params, universe, capital, steps, mode, AgentRunState.ACTIVE);
    }

    /** Same agent with another capital; it must satisfy the policy and still be below its next escalation step. */
    public AgentConfig withCapital(BigDecimal newCapital, CapitalPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        checkCapital(newCapital, policy);
        checkSteps(newCapital, steps, policy);
        return new AgentConfig(id, strategyId, params, universe, canonical(newCapital), steps, mode, state);
    }

    public String id() {
        return id;
    }

    public String strategyId() {
        return strategyId;
    }

    /** Read-only view. */
    public Map<String, BigDecimal> params() {
        return params;
    }

    /** Instruments the agent may trade, in order (buy-and-hold uses the first). Read-only. */
    public List<Isin> universe() {
        return universe;
    }

    public BigDecimal capital() {
        return capital;
    }

    public List<EscalationStep> steps() {
        return steps;
    }

    public TradingMode mode() {
        return mode;
    }

    public AgentRunState state() {
        return state;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AgentConfig a && id.equals(a.id) && strategyId.equals(a.strategyId)
                && params.equals(a.params) && universe.equals(a.universe) && capital.equals(a.capital) && steps.equals(a.steps)
                && mode == a.mode && state == a.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, strategyId, params, universe, capital, steps, mode, state);
    }

    @Override
    public String toString() {
        return "AgentConfig[" + id + ", " + strategyId + ", capital=" + capital + ", " + state + ", " + mode + "]";
    }
}
