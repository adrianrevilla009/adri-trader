package com.adrianrevilla.trading.execution;

import java.util.Set;

/**
 * Per-agent switch for real money. An agent may send real orders only if it is explicitly listed in the
 * configuration and real trading is enabled as a whole. Default: nobody. Listing an agent takes a deliberate
 * configuration change (and an ADR for the first one, ADR-0008), never a code default.
 */
public final class RealTradingGate {
    private final boolean realEnabled;
    private final Set<String> agents;

    public RealTradingGate(boolean realEnabled, Set<String> agents) {
        this.realEnabled = realEnabled;
        this.agents = Set.copyOf(agents);
    }

    public static RealTradingGate closed() {
        return new RealTradingGate(false, Set.of());
    }

    public boolean allows(String agentId) {
        return realEnabled && agents.contains(agentId);
    }

    /** @throws IllegalStateException when the agent may not trade real money */
    public void require(String agentId) {
        if (!allows(agentId)) {
            throw new IllegalStateException("Agent " + agentId + " is not enabled for real trading");
        }
    }
}
