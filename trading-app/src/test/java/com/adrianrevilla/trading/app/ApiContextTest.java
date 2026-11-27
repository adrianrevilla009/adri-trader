package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.adapter.in.web.AgentsApiServer;
import com.adrianrevilla.trading.application.port.out.AgentStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The API is wired to the real use cases and a real database: create, list, stop, audit, and PROD is refused. */
@Testcontainers
@SpringBootTest(properties = {
        "trading.api.token=0123456789abcdef-secret", "trading.api.port=0",
        "trading.etoro.instruments=IE00B4L5Y983=15348,IE00B5BMR087=3275",
        "trading.killswitch.port=0", "trading.monitoring.port=0"})
class ApiContextTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

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

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Authorization", "Bearer 0123456789abcdef-secret").header("X-Actor", "tester")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anAgentIsCreatedStoppedAndAuditedThroughTheApiAndLandsInTheDatabase() throws Exception {
        var catalog = JSON.readTree(call("GET", "/api/strategies", null).body());
        assertThat(catalog).hasSize(5);
        assertThat(JSON.readTree(call("GET", "/api/instruments", null).body())).hasSize(2);

        var created = call("POST", "/api/agents", """
                {"id":"trend-a","strategyId":"trend","params":{"window":100},
                 "universe":["IE00B4L5Y983","IE00B5BMR087"],"capital":40,
                 "steps":[{"afterDays":30,"capital":60}]}""");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode agent = JSON.readTree(created.body());
        assertThat(agent.get("params").get("band").decimalValue()).isEqualByComparingTo("0.01"); // default filled

        var stored = store.find("trend-a").orElseThrow();
        assertThat(stored.universe()).hasSize(2);
        assertThat(stored.steps()).hasSize(1);

        assertThat(call("POST", "/api/agents/trend-a/stop", null).statusCode()).isEqualTo(200);
        assertThat(store.findActive()).extracting(a -> a.id()).doesNotContain("trend-a");

        var audit = JSON.readTree(call("GET", "/api/agents/trend-a/audit", null).body());
        assertThat(audit).hasSize(2);
        assertThat(audit.get(1).get("actor").asText()).isEqualTo("api:tester");
        assertThat(audit.get(1).get("field").asText()).isEqualTo("state");
    }

    @Test
    void anInstrumentTheBrokerDoesNotKnowAndPROD() throws Exception {
        var bad = call("POST", "/api/agents", """
                {"id":"bad","strategyId":"trend","universe":["IE00BKM4GZ66"],"capital":40}""");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(store.find("bad")).isEmpty();

        assertThat(call("PUT", "/api/system/mode", "{\"mode\":\"PROD\"}").statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(call("GET", "/api/system", null).body()).get("mode").asText()).isEqualTo("DEMO");
    }
}
