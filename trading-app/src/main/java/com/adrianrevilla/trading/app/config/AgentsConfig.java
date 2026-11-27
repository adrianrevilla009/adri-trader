package com.adrianrevilla.trading.app.config;

import com.adrianrevilla.trading.adapter.out.persistence.JdbcAgentStore;
import com.adrianrevilla.trading.application.port.out.AgentStore;
import com.adrianrevilla.trading.core.domain.CapitalPolicy;
import com.adrianrevilla.trading.risk.domain.RiskConfig;
import com.adrianrevilla.trading.strategies.domain.BuiltinStrategies;
import com.adrianrevilla.trading.strategies.domain.StrategyCatalog;
import com.adrianrevilla.trading.strategies.domain.StrategyDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring only (ADR-0025, ADR-0030): the strategy catalog and the agent store. Always on, because the API that creates
 * agents must work even while the daily cycle is switched off. A strategy defined as a Spring bean of type
 * {@link StrategyDefinition} joins the catalog without any other change.
 */
@Configuration
public class AgentsConfig {
    @Bean
    StrategyCatalog strategyCatalog(ObjectProvider<StrategyDefinition> extra) {
        List<StrategyDefinition> all = new ArrayList<>(BuiltinStrategies.all());
        extra.orderedStream().forEach(all::add);
        return new StrategyCatalog(all);
    }

    /** The biggest position of an agent must reach the broker minimum order: capital >= minimum / max weight. */
    @Bean
    CapitalPolicy capitalPolicy(EtoroProperties etoro) {
        return new CapitalPolicy(etoro.minOrderAmount(), RiskConfig.defaults(Set.of()).maxWeightPerAsset());
    }

    @Bean
    AgentStore agentStore(DataSource dataSource, CapitalPolicy capitalPolicy) {
        return new JdbcAgentStore(dataSource, capitalPolicy);
    }
}
