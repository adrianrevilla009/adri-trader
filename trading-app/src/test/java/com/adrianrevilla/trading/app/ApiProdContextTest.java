package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.adapter.in.web.AgentsApiServer;
import com.adrianrevilla.trading.core.domain.Base32;
import com.adrianrevilla.trading.core.domain.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The switch to PROD with the feature flag on, end to end: real API, real MFA codes, real database audit (ADR-0031). */
@Testcontainers
@SpringBootTest(properties = {
        "trading.api.token=0123456789abcdef-secret", "trading.api.port=0",
        "trading.prod.enabled=true", "trading.prod.mfa-secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
        "trading.etoro.instruments=IE00B4L5Y983=15348",
        "trading.killswitch.port=0", "trading.monitoring.port=0"})
class ApiProdContextTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] KEY = Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
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
    JdbcTemplate jdbc;

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Authorization", "Bearer 0123456789abcdef-secret").header("X-Actor", "tester")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String code() {
        return Totp.code(KEY, Instant.now().getEpochSecond() / Totp.STEP_SECONDS);
    }

    @Test
    void theWholeFlowSwitchesToProdAndBackAndLeavesAnAuditTrail() throws Exception {
        JsonNode before = JSON.readTree(call("GET", "/api/system", null).body());
        assertThat(before.get("mode").asText()).isEqualTo("DEMO");
        assertThat(before.get("prodEnabled").asBoolean()).isTrue();

        assertThat(call("PUT", "/api/system/mode", "{\"mode\":\"PROD\"}").statusCode()).isEqualTo(409); // no shortcut

        JsonNode challenge = JSON.readTree(call("POST", "/api/system/prod/challenge", null).body());
        String id = challenge.get("challengeId").asText();

        var wrong = call("POST", "/api/system/prod/confirm", "{\"challengeId\":\"" + id
                + "\",\"code\":\"000000\",\"phrase\":\"SWITCH TO PROD\"}");
        assertThat(wrong.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(call("GET", "/api/system", null).body()).get("mode").asText()).isEqualTo("DEMO");

        String id2 = JSON.readTree(call("POST", "/api/system/prod/challenge", null).body()).get("challengeId").asText();
        var ok = call("POST", "/api/system/prod/confirm", "{\"challengeId\":\"" + id2 + "\",\"code\":\"" + code()
                + "\",\"phrase\":\"SWITCH TO PROD\"}");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(ok.body()).get("mode").asText()).isEqualTo("PROD");

        assertThat(call("PUT", "/api/system/mode", "{\"mode\":\"DEMO\"}").statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(call("GET", "/api/system", null).body()).get("mode").asText()).isEqualTo("DEMO");

        List<String> outcomes = jdbc.queryForList("SELECT outcome FROM mode_audit ORDER BY id", String.class);
        assertThat(outcomes).containsExactly("REFUSED", "CHALLENGE_ISSUED", "REJECTED", "CHALLENGE_ISSUED", "SWITCHED",
                "SWITCHED");
        assertThat(jdbc.queryForObject("SELECT string_agg(detail, ' ') FROM mode_audit", String.class))
                .doesNotContain("000000").doesNotContain("0123456789abcdef-secret");
    }
}
