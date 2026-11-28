package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.adapter.in.web.AgentsApiServer;
import com.adrianrevilla.trading.application.port.out.AgentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Chaos test: the database freezes in the middle of use (the container is paused, so connections stay open but nobody
 * answers) and then comes back. The bot must answer with a clean error instead of hanging, keep serving what needs no
 * database, never half-create anything, never switch the mode when the audit cannot be written, and recover alone.
 */
@Tag("chaos")
@Testcontainers
@SpringBootTest(properties = {
        "trading.api.token=0123456789abcdef-secret", "trading.api.port=0",
        "trading.prod.enabled=true", "trading.prod.mfa-secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
        "trading.etoro.instruments=IE00B4L5Y983=15348",
        "spring.datasource.hikari.data-source-properties.socketTimeout=2",
        "spring.datasource.hikari.connection-timeout=2000",
        "trading.killswitch.port=0", "trading.monitoring.port=0"})
class DatabaseChaosTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    AgentsApiServer server;
    @Autowired
    AgentStore store;
    @Autowired
    JdbcTemplate jdbc;

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer 0123456789abcdef-secret")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void freeze() {
        PG.getDockerClient().pauseContainerCmd(PG.getContainerId()).exec();
    }

    private static void thaw() {
        PG.getDockerClient().unpauseContainerCmd(PG.getContainerId()).exec();
    }

    private static final String AGENT = "{\"id\":\"%s\",\"strategyId\":\"trend\",\"capital\":40,"
            + "\"universe\":[\"IE00B4L5Y983\"]}";

    @Test
    void aFrozenDatabaseGivesCleanErrorsKeepsTheRestAliveAndTheBotRecoversAlone() throws Exception {
        assertThat(call("POST", "/api/agents", AGENT.formatted("before")).statusCode()).isEqualTo(201);

        freeze();
        try {
            // a request that needs the database fails with a clean 500 after the timeout, it does not hang
            long t0 = System.nanoTime();
            CompletableFuture<HttpResponse<String>> slow = CompletableFuture.supplyAsync(() -> {
                try {
                    return call("GET", "/api/agents", null);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            // meanwhile a request that needs no database is answered at once (the server is not blocked)
            long t1 = System.nanoTime();
            var system = call("GET", "/api/system", null);
            assertThat(system.statusCode()).isEqualTo(200);
            assertThat(Duration.ofNanos(System.nanoTime() - t1)).isLessThan(Duration.ofSeconds(2));

            var answer = slow.get();
            assertThat(answer.statusCode()).isEqualTo(500);
            assertThat(answer.body()).isEqualTo("{\"error\":\"internal error\"}");
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(25));

            // a write cannot be half done, and the PROD switch cannot start when its audit cannot be written
            assertThat(call("POST", "/api/agents", AGENT.formatted("during")).statusCode()).isEqualTo(500);
            assertThat(call("POST", "/api/system/prod/challenge", null).statusCode()).isEqualTo(500);
            assertThat(JSON.readTree(call("GET", "/api/system", null).body()).get("mode").asText()).isEqualTo("DEMO");
        } finally {
            thaw();
        }

        // the pool recovers without a restart (broken connections are replaced)
        HttpResponse<String> back = null;
        for (int i = 0; i < 20; i++) {
            back = call("GET", "/api/agents", null);
            if (back.statusCode() == 200) {
                break;
            }
            Thread.sleep(1000);
        }
        assertThat(back).isNotNull();
        assertThat(back.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(back.body())).hasSize(1); // only "before": "during" never half-existed
        assertThat(store.find("during")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mode_audit", Integer.class)).isZero();

        // and the PROD flow works again after the outage
        assertThat(call("POST", "/api/system/prod/challenge", null).statusCode()).isEqualTo(200);
    }
}
