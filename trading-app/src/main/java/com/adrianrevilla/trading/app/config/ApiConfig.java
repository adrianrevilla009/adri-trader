package com.adrianrevilla.trading.app.config;

import com.adrianrevilla.trading.adapter.in.web.AgentsApiServer;
import com.adrianrevilla.trading.application.port.in.ManageAgents;
import com.adrianrevilla.trading.application.port.in.ManageMode;
import com.adrianrevilla.trading.application.port.in.QueryAgents;
import com.adrianrevilla.trading.application.port.out.AgentStore;
import com.adrianrevilla.trading.application.service.AgentService;
import com.adrianrevilla.trading.application.service.ModeService;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.domain.CapitalPolicy;
import com.adrianrevilla.trading.strategies.domain.StrategyCatalog;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring only (ADR-0025, ADR-0030): the use cases that manage agents and the JSON API that exposes them. */
@Configuration
public class ApiConfig {
    /** Only instruments the broker has an id for can be in an agent universe. */
    @Bean
    AgentService agentService(AgentStore store, StrategyCatalog catalog, CapitalPolicy policy, EtoroProperties etoro) {
        return new AgentService(store, catalog, policy, etoro.instrumentIds().keySet());
    }

    @Bean
    ModeService modeService(Alerter alerter) {
        return new ModeService(alerter);
    }

    /** Only created when TRADING_API_TOKEN is set (16+ characters). */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnExpression("!'${trading.api.token:}'.isEmpty()")
    AgentsApiServer agentsApiServer(AgentService agents, ModeService mode, ApiProperties api) throws IOException {
        ManageAgents commands = agents;
        QueryAgents queries = agents;
        ManageMode modes = mode;
        return new AgentsApiServer(commands, queries, modes, api.token(), api.port(), api.allowedOrigin(),
                api.bindAddress());
    }
}
