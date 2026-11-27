package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.adapter.in.web.KillSwitchServer;
import com.adrianrevilla.trading.adapter.out.notification.LoggingAlerter;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcKillSwitch;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcOrderRepository;
import com.adrianrevilla.trading.app.config.DataConfig;
import com.adrianrevilla.trading.app.config.LiveConfig;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.domain.KillSwitch;
import com.adrianrevilla.trading.execution.application.port.out.OrderRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The whole application starts against a real PostgreSQL: Flyway, configuration, beans and the endpoint. */
@Testcontainers
@SpringBootTest(properties = {"trading.killswitch.token=0123456789abcdef-secret", "trading.killswitch.port=0", "trading.monitoring.port=0"})
class ApplicationContextTest {
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
    void contextStartsWithTheExpectedBeans() {
        assertThat(context.getBean(KillSwitch.class)).isInstanceOf(JdbcKillSwitch.class);
        assertThat(context.getBean(OrderRepository.class)).isInstanceOf(JdbcOrderRepository.class);
        assertThat(context.getBean(Alerter.class)).isInstanceOf(LoggingAlerter.class); // no Telegram token set
        assertThat(context.getBean(KillSwitchServer.class)).isNotNull();
        assertThat(context.getBeansOfType(DataConfig.ScheduledIngestion.class)).isEmpty(); // disabled by default
        assertThat(context.getBeansOfType(LiveConfig.ScheduledLiveDay.class)).isEmpty();   // live cycle off by default
    }

    @Test
    void killSwitchEndpointWorksThroughTheDatabase() throws Exception {
        var server = context.getBean(KillSwitchServer.class);
        var http = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/killswitch/activate?reason=ctx"))
                .header("Authorization", "Bearer 0123456789abcdef-secret")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(context.getBean(KillSwitch.class).isActive()).isTrue();
        context.getBean(KillSwitch.class).deactivate();
    }
}
