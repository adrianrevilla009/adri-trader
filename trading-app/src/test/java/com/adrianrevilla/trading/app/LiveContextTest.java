package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.app.config.LiveConfig;
import com.adrianrevilla.trading.adapter.out.brokeretoro.EToroBroker;
import com.adrianrevilla.trading.application.port.in.RunAgentsDay;
import com.adrianrevilla.trading.application.port.out.AgentStore;
import com.adrianrevilla.trading.strategies.domain.StrategyCatalog;
import com.adrianrevilla.trading.core.domain.Broker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** With the live cycle switched on (fake keys, no network call at start) the demo cycle is wired end to end. */
@Testcontainers
@SpringBootTest(properties = {
        "trading.live.enabled=true", "trading.live.isin=IE00B4L5Y983", "trading.live.capital=40",
        "trading.etoro.api-key=fake", "trading.etoro.user-key=fake",
        "trading.etoro.instruments=IE00B4L5Y983=15348",
        "trading.killswitch.port=0", "trading.monitoring.port=0"})
class LiveContextTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    ApplicationContext context;

    @Test
    void theDemoCycleIsWiredWithTheSharedAccountAgentsFromTheStoreAndAScheduledDay() {
        // the broker is the whole account; each agent gets its own budget view of it when its loop is built
        assertThat(context.getBean(Broker.class)).isInstanceOf(EToroBroker.class);
        assertThat(context.getBean(RunAgentsDay.class)).isNotNull();
        assertThat(context.getBean(StrategyCatalog.class).list()).hasSize(5);
        // the first agent is created from trading.live.isin while the store is empty, and only then
        var agents = context.getBean(AgentStore.class).findAll();
        assertThat(agents).extracting(a -> a.id()).containsExactly("live-demo");
        assertThat(agents.get(0).universe()).extracting(i -> i.value()).containsExactly("IE00B4L5Y983");
        assertThat(agents.get(0).capital()).isEqualByComparingTo("40");
        assertThat(context.getBean(LiveConfig.ScheduledLiveDay.class)).isNotNull();
    }
}
