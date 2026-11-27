package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.app.config.LiveConfig;
import com.adrianrevilla.trading.application.port.in.RunTradingDay;
import com.adrianrevilla.trading.core.domain.Broker;
import com.adrianrevilla.trading.core.domain.CashCappedBroker;
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
    void theDemoCycleIsWiredWithACappedBrokerAndAScheduledDay() {
        assertThat(context.getBean(Broker.class)).isInstanceOf(CashCappedBroker.class);
        assertThat(context.getBean(RunTradingDay.class)).isNotNull();
        assertThat(context.getBean(LiveConfig.ScheduledLiveDay.class)).isNotNull();
    }
}
