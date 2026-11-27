package com.adrianrevilla.trading.application.port.in;

import com.adrianrevilla.trading.application.read.AgentView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Input port (commands): create, change, stop and resume agents (ADR-0030). Every call names the actor for the audit
 * trail. Invalid input throws IllegalArgumentException with a clear message; nothing is stored in that case.
 */
public interface ManageAgents {
    /** @param params only the ones to set; the rest take the default of the strategy. */
    record CreateAgent(String id, String strategyId, Map<String, BigDecimal> params, List<String> universe,
                       BigDecimal capital, List<StepSpec> steps) {
    }

    record StepSpec(int afterDays, BigDecimal capital) {
    }

    /** A null field keeps the current value; an empty list of steps removes them. The strategy cannot change. */
    record UpdateAgent(Map<String, BigDecimal> params, List<String> universe, BigDecimal capital,
                       List<StepSpec> steps) {
    }

    /** @throws AgentAlreadyExistsException when the id is taken */
    AgentView create(CreateAgent command, String actor);

    /** @throws AgentNotFoundException when there is no such agent */
    AgentView update(String id, UpdateAgent command, String actor);

    /** Stops sending new orders; positions are kept. Stopping a stopped agent changes nothing. */
    AgentView stop(String id, String actor);

    /** Sends orders again from the next daily cycle. Resuming an active agent changes nothing. */
    AgentView resume(String id, String actor);

    class AgentNotFoundException extends RuntimeException {
        public AgentNotFoundException(String id) {
            super("agent not found: " + id);
        }
    }

    class AgentAlreadyExistsException extends RuntimeException {
        public AgentAlreadyExistsException(String id) {
            super("agent already exists: " + id);
        }
    }
}
