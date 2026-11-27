package com.adrianrevilla.trading.application.port.out;

import com.adrianrevilla.trading.core.domain.AgentConfig;
import java.util.List;
import java.util.Optional;

/** Output port: stores agent configuration and keeps an append-only audit trail of every change (ADR-0030). */
public interface AgentStore {
    /** Inserts or updates the agent; each changed field adds one audit row with the old and new value. */
    void save(AgentConfig agent, String changedBy);

    Optional<AgentConfig> find(String id);

    /** All agents ordered by id. */
    List<AgentConfig> findAll();

    /** Only agents that send orders (state ACTIVE), ordered by id. */
    List<AgentConfig> findActive();

    /** Audit rows of one agent, oldest first. */
    List<AgentAuditEntry> audit(String agentId);

    /** One audited change. {@code oldValue} is null when the agent was created. */
    record AgentAuditEntry(String agentId, String changedBy, String field, String oldValue, String newValue) {
    }
}
