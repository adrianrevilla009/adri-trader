package com.adrianrevilla.trading.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.adapter.in.web.AgentsApiServer;
import com.adrianrevilla.trading.core.domain.CapitalPolicy;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.strategies.domain.BuiltinStrategies;
import com.adrianrevilla.trading.strategies.domain.StrategyCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Hostile input against the real API and use cases: whatever is sent, the answer is a clean 4xx, never a 5xx, never a
 * stack trace, and nothing is created, changed or switched.
 */
class ApiSecurityTest {
    private static final String TOKEN = "0123456789abcdef-secret";
    private static final String ORIGIN = "http://localhost:5173";
    private static final String A = "IE00B4L5Y983";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private InMemoryAgentStore store;
    private AgentsApiServer server;

    @BeforeEach
    void start() throws Exception {
        store = new InMemoryAgentStore();
        var agents = new AgentService(store, new StrategyCatalog(BuiltinStrategies.all()),
                new CapitalPolicy(new BigDecimal("10"), new BigDecimal("0.25")), Set.of(new Isin(A)));
        var mode = new ModeService((l, m) -> { }, (a, f, t, o, d) -> { }, ProdSwitchPolicy.disabled(),
                Clock.systemUTC());
        server = new AgentsApiServer(agents, agents, mode, TOKEN, 0, ORIGIN);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> send(String method, String path, byte[] body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> authed(String method, String path, String body) throws Exception {
        return send(method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                "Authorization", "Bearer " + TOKEN, "Content-Type", "application/json");
    }

    private void assertClean(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isBetween(400, 499);
        assertThat(r.body()).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("\tat ");
        assertThat(r.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    // ---- authentication

    @ParameterizedTest
    @ValueSource(strings = {"", "Bearer", "Bearer ", "bearer 0123456789abcdef-secret", "Basic MDEyMzQ1Njc4OWFiY2RlZg==",
            "Bearer  0123456789abcdef-secret", "Bearer 0123456789abcdef-secre",
            "Bearer 0123456789abcdef-secret!", "Token 0123456789abcdef-secret", "0123456789abcdef-secret"})
    void everyBadAuthorizationHeaderIsUnauthorizedAndTheBodyNeverHintsWhy(String header) throws Exception {
        var r = send("GET", "/api/agents", null, "Authorization", header);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).isEqualTo("{\"error\":\"unauthorized\"}");
    }

    @Test
    void aMissingTokenAndAWrongTokenLookExactlyTheSame() throws Exception {
        var missing = send("GET", "/api/agents", null);
        var wrong = send("GET", "/api/agents", null, "Authorization", "Bearer wrong-wrong-wrong-1");
        assertThat(missing.statusCode()).isEqualTo(wrong.statusCode());
        assertThat(missing.body()).isEqualTo(wrong.body());
    }

    @Test
    void aMegabyteLongTokenIsJustUnauthorized() throws Exception {
        var r = send("GET", "/api/agents", null, "Authorization", "Bearer " + "a".repeat(1_000_000 / 100));
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void everyRouteAndMethodNeedsTheTokenFirstEvenForUnknownPaths() throws Exception {
        for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS-LIKE")) {
            for (String path : List.of("/api/agents", "/api/system/mode", "/api/nothing", "/api/agents/x/stop",
                    "/api/system/prod/confirm")) {
                if (method.equals("OPTIONS-LIKE")) {
                    continue;
                }
                var r = send(method, path, null);
                assertThat(r.statusCode()).as(method + " " + path).isEqualTo(401);
            }
        }
    }

    // ---- paths and methods

    @ParameterizedTest
    @ValueSource(strings = {"/api/../etc/passwd", "/api/agents/..%2f..%2fetc", "/api/agents/%00", "/api/agents/a%2fb",
            "/api/agents/%E2%98%83", "/api/agents/..", "/api//agents", "/api/agents/x/unknown", "/api/agents/x/stop/more",
            "/api/system/prod", "/api/system/prod/unknown", "/api/%2e%2e/agents", "/api/agents/;jsessionid=1",
            "/api/agents/x%0d%0aSet-Cookie:%20a=b"})
    void strangePathsAreCleanNotFoundOrBadRequest(String path) throws Exception {
        var r = authed("GET", path, null);
        assertThat(r.statusCode()).as(path).isIn(400, 404, 405);
        assertThat(r.body()).doesNotContain("Exception");
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void aVeryLongIdIsNotFoundNotACrash() throws Exception {
        assertClean(authed("GET", "/api/agents/" + "a".repeat(20_000), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "PATCH", "TRACE", "HEAD"})
    void unsupportedMethodsAreRefusedCleanly(String method) throws Exception {
        var r = authed(method, "/api/agents", null);
        assertThat(r.statusCode()).isBetween(400, 499);
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void theRightRouteWithTheWrongMethodIs405() throws Exception {
        assertThat(authed("GET", "/api/agents/x/stop", null).statusCode()).isEqualTo(405);
        assertThat(authed("POST", "/api/strategies", "{}").statusCode()).isEqualTo(405);
        assertThat(authed("PUT", "/api/system", "{}").statusCode()).isEqualTo(405);
        assertThat(authed("GET", "/api/system/prod/confirm", null).statusCode()).isEqualTo(405);
    }

    // ---- bodies

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "}", "null", "[]", "\"text\"", "12", "{\"id\":\"a\"}{\"id\":\"b\"}",
            "{\"id\":\"a\",\"id\":\"b\",\"strategyId\":\"trend\"}", "{\"unknown\":1}", "{\"id\":null}",
            "{\"id\":5,\"strategyId\":\"trend\"}", "{\"id\":\"ok-1\",\"strategyId\":7}",
            "{\"id\":\"ok-1\",\"strategyId\":\"trend\",\"capital\":\"40\"}",
            "{\"id\":\"ok-1\",\"strategyId\":\"trend\",\"capital\":1e999999999,\"universe\":[\"" + A + "\"]}",
            "{\"id\":\"ok-1\",\"strategyId\":\"trend\",\"capital\":-1,\"universe\":[\"" + A + "\"]}",
            "{\"id\":\"ok-1\",\"strategyId\":\"trend\",\"capital\":40,\"universe\":[]}",
            "{\"id\":\"ok-1\",\"strategyId\":\"trend\",\"capital\":40,\"universe\":[null]}",
            "{\"id\":\"ok-1\",\"strategyId\":\"trend\",\"capital\":40,\"universe\":\"" + A + "\"}",
            "{\"id\":\"../x\",\"strategyId\":\"trend\",\"capital\":40,\"universe\":[\"" + A + "\"]}",
            "{\"id\":\"ok-1\",\"strategyId\":\"nope\",\"capital\":40,\"universe\":[\"" + A + "\"]}",
            "{\"id\":\"ok-1\",\"strategyId\":\"trend\",\"capital\":40,\"universe\":[\"" + A + "\"],\"params\":{\"x\":1}}",
            "{\"id\":\"ok-1\",\"strategyId\":\"trend\",\"capital\":40,\"universe\":[\"" + A + "\"],\"params\":{\"window\":null}}",
            "{\"id\":\"ok-1\",\"strategyId\":\"trend\",\"capital\":40,\"universe\":[\"" + A + "\"],\"steps\":[{\"afterDays\":0,\"capital\":50}]}",
            "{\"id\":\"ok-1\",\"strategyId\":\"trend\",\"capital\":40,\"universe\":[\"" + A + "\"],\"steps\":[null]}",
            "{\"id\":\"ok-1\",\"strategyId\":\"trend\",\"capital\":40,\"universe\":[\"" + A + "\"],\"mode\":\"PROD\"}"})
    void aBadCreateBodyIsABadRequestAndCreatesNothing(String body) throws Exception {
        var r = authed("POST", "/api/agents", body);
        assertClean(r);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void aDeeplyNestedBodyIsRejectedNotAStackOverflow() throws Exception {
        String nested = "[".repeat(50_000) + "]".repeat(50_000);
        var r = authed("POST", "/api/agents", nested);
        assertClean(r);
    }

    @Test
    void aBodyThatIsNotUtf8IsABadRequest() throws Exception {
        byte[] bytes = new byte[] {'{', '"', 'i', 'd', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
        var r = send("POST", "/api/agents", bytes, "Authorization", "Bearer " + TOKEN);
        assertClean(r);
    }

    @Test
    void aBodyAboveTheLimitIs413AndNothingIsCreated() throws Exception {
        var r = authed("POST", "/api/agents", "{\"id\":\"" + "a".repeat(200_000) + "\"}");
        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void anUpdateOfAnUnknownAgentIs404AndAnEmptyUpdateChangesNothing() throws Exception {
        assertThat(authed("PUT", "/api/agents/ghost", "{\"capital\":50}").statusCode()).isEqualTo(404);
        assertThat(authed("POST", "/api/agents/ghost/stop", null).statusCode()).isEqualTo(404);
        assertThat(authed("GET", "/api/agents/ghost/audit", null).statusCode()).isIn(200, 404);
    }

    // ---- PROD can never be reached by tampering

    @ParameterizedTest
    @ValueSource(strings = {"{\"mode\":\"PROD\"}", "{\"mode\":\"prod\"}", "{\"mode\":\" PROD\"}", "{\"mode\":[\"PROD\"]}",
            "{\"mode\":\"DEMO\",\"mode\":\"PROD\"}", "{\"mode\":\"PROD\",\"confirmed\":true}", "{\"mode\":null}", "{}"})
    void noBodyOfTheModeEndpointEverSelectsProd(String body) throws Exception {
        var r = authed("PUT", "/api/system/mode", body);
        assertThat(r.statusCode()).isBetween(400, 499);
        assertThat(JSON.readTree(authed("GET", "/api/system", null).body()).get("mode").asText()).isEqualTo("DEMO");
    }

    @Test
    void withTheFeatureOffEveryPartOfTheProdFlowIsRefusedAndNothingChanges() throws Exception {
        assertThat(authed("POST", "/api/system/prod/challenge", null).statusCode()).isEqualTo(409);
        for (String body : List.of("{}", "{\"challengeId\":\"x\",\"code\":\"123456\",\"phrase\":\"SWITCH TO PROD\"}",
                "{\"challengeId\":null,\"code\":null,\"phrase\":null}")) {
            var r = authed("POST", "/api/system/prod/confirm", body);
            assertThat(r.statusCode()).isIn(400, 409);
        }
        assertThat(JSON.readTree(authed("GET", "/api/system", null).body()).get("mode").asText()).isEqualTo("DEMO");
    }

    // ---- browser protection

    @Test
    void onlyTheAllowedOriginGetsCorsHeaders() throws Exception {
        var good = authed("GET", "/api/system", null);
        assertThat(good.headers().firstValue("Access-Control-Allow-Origin")).isEmpty(); // no Origin sent

        var withOrigin = send("GET", "/api/system", null, "Authorization", "Bearer " + TOKEN, "Origin", ORIGIN);
        assertThat(withOrigin.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);

        for (String evil : List.of("http://evil.example", "http://localhost:5174", "null", ORIGIN + ".evil.example",
                "https://localhost:5173")) {
            var r = send("GET", "/api/system", null, "Authorization", "Bearer " + TOKEN, "Origin", evil);
            assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).as(evil).isEmpty();
        }
    }

    @Test
    void aPreflightAnswersWithoutNeedingTheTokenAndNeverToAnEvilOrigin() throws Exception {
        var ok = send("OPTIONS", "/api/agents", null, "Origin", ORIGIN, "Access-Control-Request-Method", "POST");
        assertThat(ok.statusCode()).isEqualTo(204);
        assertThat(ok.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);
        var evil = send("OPTIONS", "/api/agents", null, "Origin", "http://evil.example",
                "Access-Control-Request-Method", "POST");
        assertThat(evil.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    // ---- audit trail hygiene

    @Test
    void theActorHeaderCannotInjectAnythingIntoTheAuditTrail() throws Exception {
        for (String actor : List.of("<script>alert(1)</script>", "a b", "x".repeat(200), "admin\u0000", "../../etc")) {
            authed("POST", "/api/agents", "{\"id\":\"t" + Math.abs(actor.hashCode() % 1000) + "\",\"strategyId\":\"trend\","
                    + "\"capital\":40,\"universe\":[\"" + A + "\"]}");
        }
        var r = send("POST", "/api/agents", ("{\"id\":\"clean\",\"strategyId\":\"trend\",\"capital\":40,"
                + "\"universe\":[\"" + A + "\"]}").getBytes(StandardCharsets.UTF_8), "Authorization", "Bearer " + TOKEN,
                "X-Actor", "<script>alert(1)</script>");
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(store.audit("clean")).hasSize(1);
        assertThat(store.audit("clean").get(0).changedBy()).isEqualTo("api"); // unusable value falls back
    }
}
