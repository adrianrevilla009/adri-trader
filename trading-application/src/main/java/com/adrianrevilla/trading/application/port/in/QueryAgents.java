package com.adrianrevilla.trading.application.port.in;

import com.adrianrevilla.trading.application.read.AgentView;
import com.adrianrevilla.trading.application.read.AuditView;
import com.adrianrevilla.trading.application.read.StrategyView;
import java.util.List;
import java.util.Optional;

/** Input port (queries, CQRS level 1): reads that never change anything (ADR-0030). */
public interface QueryAgents {
    /** All agents ordered by id. */
    List<AgentView> list();

    Optional<AgentView> find(String id);

    /** Changes of one agent, oldest first. @throws ManageAgents.AgentNotFoundException when there is no such agent */
    List<AuditView> audit(String id);

    /** The strategy catalog with the description of every parameter. */
    List<StrategyView> strategies();

    /** ISINs the broker can trade: the only ones an agent universe may use. */
    List<String> instruments();
}
