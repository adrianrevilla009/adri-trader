package com.adrianrevilla.trading.application.service;

import com.adrianrevilla.trading.application.port.out.AgentStore;
import com.adrianrevilla.trading.core.domain.AgentConfig;
import com.adrianrevilla.trading.core.domain.AgentRunState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** Test double of the agent store: keeps agents in a map and writes one audit row per save, like the real one. */
final class InMemoryAgentStore implements AgentStore {
    private final TreeMap<String, AgentConfig> agents = new TreeMap<>();
    private final List<AgentAuditEntry> audit = new ArrayList<>();

    @Override
    public synchronized void save(AgentConfig agent, String changedBy) {
        AgentConfig old = agents.put(agent.id(), agent);
        if (old == null) {
            audit.add(new AgentAuditEntry(agent.id(), changedBy, "created", null, agent.strategyId()));
        } else if (!old.equals(agent)) {
            audit.add(new AgentAuditEntry(agent.id(), changedBy, "changed", old.toString(), agent.toString()));
        }
    }

    @Override
    public synchronized Optional<AgentConfig> find(String id) {
        return Optional.ofNullable(agents.get(id));
    }

    @Override
    public synchronized List<AgentConfig> findAll() {
        return List.copyOf(agents.values());
    }

    @Override
    public synchronized List<AgentConfig> findActive() {
        return agents.values().stream().filter(a -> a.state() == AgentRunState.ACTIVE).toList();
    }

    @Override
    public synchronized List<AgentAuditEntry> audit(String agentId) {
        return audit.stream().filter(e -> e.agentId().equals(agentId)).toList();
    }
}
