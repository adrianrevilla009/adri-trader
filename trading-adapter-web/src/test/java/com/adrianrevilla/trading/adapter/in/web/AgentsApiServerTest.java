package com.adrianrevilla.trading.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.application.port.in.ManageAgents;
import com.adrianrevilla.trading.application.port.in.ManageMode;
import com.adrianrevilla.trading.application.port.in.QueryAgents;
import com.adrianrevilla.trading.application.read.AgentView;
import com.adrianrevilla.trading.application.read.AuditView;
import com.adrianrevilla.trading.application.read.ModeView;
import com.adrianrevilla.trading.application.read.StrategyView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentsApiServerTest {
    private static final String TOKEN = "0123456789abcdef-secret";
    private static final String ORIGIN = "http://localhost:5173";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, AgentView> agents = new TreeMap<>();
    private final List<String> actors = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private AgentsApiServer server;
    private String prodBehaviour = "ok";
    private final List<String> confirmed = new ArrayList<>();

    /** Fake use cases: enough behaviour to see how the HTTP layer maps requests, answers and errors. */
    private final class Fake implements ManageAgents, QueryAgents, ManageMode {
        @Override
        public AgentView create(CreateAgent c, String actor) {
            actors.add(actor);
            if (agents.containsKey(c.id())) {
                throw new AgentAlreadyExistsException(c.id());
            }
            if (c.capital() == null) {
                throw new IllegalArgumentException("capital must be positive: null");
            }
            var v = new AgentView(c.id(), c.strategyId(), c.params() == null ? Map.of() : c.params(), c.universe(),
                    c.capital(), List.of(), "DEMO", "ACTIVE");
            agents.put(c.id(), v);
            return v;
        }

        @Override
        public AgentView update(String id, UpdateAgent c, String actor) {
            actors.add(actor);
            var old = require(id);
            var v = new AgentView(id, old.strategyId(), old.params(), old.universe(),
                    c.capital() == null ? old.capital() : c.capital(), old.steps(), "DEMO", old.state());
            agents.put(id, v);
            return v;
        }

        @Override
        public AgentView stop(String id, String actor) {
            actors.add(actor);
            var o = require(id);
            var v = new AgentView(id, o.strategyId(), o.params(), o.universe(), o.capital(), o.steps(), "DEMO", "STOPPED");
            agents.put(id, v);
            return v;
        }

        @Override
        public AgentView resume(String id, String actor) {
            actors.add(actor);
            var o = require(id);
            var v = new AgentView(id, o.strategyId(), o.params(), o.universe(), o.capital(), o.steps(), "DEMO", "ACTIVE");
            agents.put(id, v);
            return v;
        }

        private AgentView require(String id) {
            var v = agents.get(id);
            if (v == null) {
                throw new AgentNotFoundException(id);
            }
            return v;
        }

        @Override
        public List<AgentView> list() {
            return List.copyOf(agents.values());
        }

        @Override
        public Optional<AgentView> find(String id) {
            return Optional.ofNullable(agents.get(id));
        }

        @Override
        public List<AuditView> audit(String id) {
            require(id);
            return List.of(new AuditView(id, "api", "created", null, "trend"));
        }

        @Override
        public List<StrategyView> strategies() {
            return List.of(new StrategyView("trend", "Trend", "d", List.of(
                    new StrategyView.Param("window", "days", new BigDecimal("200"), new BigDecimal("20"),
                            new BigDecimal("400"), true))));
        }

        @Override
        public com.adrianrevilla.trading.application.read.LimitsView limits() {
            return new com.adrianrevilla.trading.application.read.LimitsView(new BigDecimal("40"), BigDecimal.TEN,
                    new BigDecimal("0.25"));
        }

        @Override
        public List<String> instruments() {
            return List.of("IE00B4L5Y983");
        }

        @Override
        public ModeView current() {
            return new ModeView("DEMO", List.of("DEMO"), false, null, "SWITCH TO PROD");
        }

        @Override
        public com.adrianrevilla.trading.application.read.ProdChallengeView requestProdSwitch(String actor) {
            actors.add(actor);
            switch (prodBehaviour) {
                case "locked" -> throw new SwitchLockedException("2026-12-01T10:15:00Z");
                case "off" -> throw new ModeNotAvailableException("the switch to PROD is disabled");
                default -> { }
            }
            return new com.adrianrevilla.trading.application.read.ProdChallengeView("c1", "2026-12-01T10:02:00Z",
                    "SWITCH TO PROD");
        }

        @Override
        public ModeView confirmProdSwitch(String id, String code, String phrase, String actor) {
            actors.add(actor);
            confirmed.add(id + "|" + code + "|" + phrase);
            if (!code.equals("123456")) {
                throw new ConfirmationRejectedException();
            }
            return new ModeView("PROD", List.of("DEMO", "PROD"), true, null, "SWITCH TO PROD");
        }

        @Override
        public ModeView switchTo(String mode, String actor) {
            actors.add(actor);
            if (mode.equals("PROD")) {
                throw new ModeNotAvailableException("PROD is not available yet");
            }
            return current();
        }
    }

    @BeforeEach
    void start() throws Exception {
        var fake = new Fake();
        server = new AgentsApiServer(fake, fake, fake, TOKEN, 0, ORIGIN);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private record Reply(int status, String body, HttpResponse<String> raw) {
        JsonNode json() throws Exception {
            return JSON.readTree(body);
        }
    }

    private Reply call(String method, String path, String body, String auth, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body(), r);
    }

    private Reply authed(String method, String path, String body, String... headers) throws Exception {
        return call(method, path, body, "Bearer " + TOKEN, headers);
    }

    private static final String NEW_AGENT = """
            {"id":"t1","strategyId":"trend","params":{"window":100},"universe":["IE00B4L5Y983"],"capital":40,"steps":[]}""";

    // ---- security ----

    @Test
    void theServerCannotBeCreatedWithAWeakToken() {
        var f = new Fake();
        assertThatThrownBy(() -> new AgentsApiServer(f, f, f, "short", 0, ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentsApiServer(f, f, f, null, 0, ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyRouteNeedsTheTokenAndANoTokenCallChangesNothing() throws Exception {
        for (String[] r : new String[][] {{"GET", "/api/agents"}, {"POST", "/api/agents"}, {"GET", "/api/system"},
                {"PUT", "/api/system/mode"}, {"POST", "/api/system/prod/challenge"},
                {"POST", "/api/system/prod/confirm"}, {"GET", "/api/strategies"}, {"POST", "/api/agents/x/stop"}}) {
            assertThat(call(r[0], r[1], r[0].equals("GET") ? null : NEW_AGENT, null).status()).as(r[1]).isEqualTo(401);
            assertThat(call(r[0], r[1], null, "Bearer wrong").status()).as(r[1]).isEqualTo(401);
        }
        assertThat(agents).isEmpty();
    }

    @Test
    void corsPreflightIsAnsweredWithoutTokenOnlyForTheAllowedOrigin() throws Exception {
        var ok = call("OPTIONS", "/api/agents", null, null, "Origin", ORIGIN,
                "Access-Control-Request-Method", "POST");
        assertThat(ok.status()).isEqualTo(204);
        assertThat(ok.raw().headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        assertThat(ok.raw().headers().firstValue("Access-Control-Allow-Headers").orElse(""))
                .contains("Authorization").contains("Content-Type");

        var other = call("OPTIONS", "/api/agents", null, null, "Origin", "http://evil.example");
        assertThat(other.raw().headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void responsesCarryTheAllowedOriginOnlyForThatOrigin() throws Exception {
        var ok = authed("GET", "/api/agents", null, "Origin", ORIGIN);
        assertThat(ok.raw().headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        var other = authed("GET", "/api/agents", null, "Origin", "http://evil.example");
        assertThat(other.raw().headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    // ---- reads ----

    @Test
    void readsTheSystemTheStrategiesAndTheInstruments() throws Exception {
        assertThat(authed("GET", "/api/system", null).json().get("mode").asText()).isEqualTo("DEMO");
        var strategies = authed("GET", "/api/strategies", null).json();
        assertThat(strategies.get(0).get("id").asText()).isEqualTo("trend");
        assertThat(strategies.get(0).get("params").get(0).get("integer").asBoolean()).isTrue();
        assertThat(authed("GET", "/api/instruments", null).json().get(0).asText()).isEqualTo("IE00B4L5Y983");
        assertThat(authed("GET", "/api/limits", null).json().get("minimumCapital").decimalValue()).isEqualByComparingTo("40");
    }

    // ---- agents ----

    @Test
    void createsReadsListsAndAuditsAnAgent() throws Exception {
        var created = authed("POST", "/api/agents", NEW_AGENT);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("id").asText()).isEqualTo("t1");
        assertThat(created.json().get("capital").decimalValue()).isEqualByComparingTo("40");

        assertThat(authed("GET", "/api/agents", null).json()).hasSize(1);
        assertThat(authed("GET", "/api/agents/t1", null).json().get("state").asText()).isEqualTo("ACTIVE");
        assertThat(authed("GET", "/api/agents/t1/audit", null).json().get(0).get("field").asText())
                .isEqualTo("created");
    }

    @Test
    void stopResumeAndUpdateAnAgent() throws Exception {
        authed("POST", "/api/agents", NEW_AGENT);

        assertThat(authed("POST", "/api/agents/t1/stop", null).json().get("state").asText()).isEqualTo("STOPPED");
        assertThat(authed("POST", "/api/agents/t1/resume", null).json().get("state").asText()).isEqualTo("ACTIVE");
        var updated = authed("PUT", "/api/agents/t1", "{\"capital\":80}");
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("capital").decimalValue()).isEqualByComparingTo("80");
    }

    @Test
    void errorsMapToTheRightStatusWithAMessage() throws Exception {
        authed("POST", "/api/agents", NEW_AGENT);

        var dup = authed("POST", "/api/agents", NEW_AGENT);
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.json().get("error").asText()).contains("already exists");

        assertThat(authed("GET", "/api/agents/nope", null).status()).isEqualTo(404);
        assertThat(authed("POST", "/api/agents/nope/stop", null).status()).isEqualTo(404);
        assertThat(authed("GET", "/api/agents/nope/audit", null).status()).isEqualTo(404);

        var invalid = authed("POST", "/api/agents", "{\"id\":\"t2\",\"strategyId\":\"trend\"}");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.json().get("error").asText()).contains("capital");

        assertThat(authed("GET", "/api/nothing", null).status()).isEqualTo(404);
        assertThat(authed("DELETE", "/api/agents/t1", null).status()).isEqualTo(405);
    }

    @Test
    void malformedOrUnexpectedJsonIsABadRequestAndNeverAServerError() throws Exception {
        assertThat(authed("POST", "/api/agents", "{not json").status()).isEqualTo(400);
        assertThat(authed("POST", "/api/agents", "").status()).isEqualTo(400);
        assertThat(authed("POST", "/api/agents", "[1,2]").status()).isEqualTo(400);
        assertThat(authed("POST", "/api/agents", "{\"id\":\"t1\",\"extra\":1}").status()).isEqualTo(400);
        assertThat(authed("POST", "/api/agents", "{\"id\":5}").status()).isEqualTo(400);
        assertThat(agents).isEmpty();
    }

    @Test
    void aBodyThatIsTooBigIsRefused() throws Exception {
        String big = "{\"id\":\"" + "a".repeat(100_000) + "\"}";
        assertThat(authed("POST", "/api/agents", big).status()).isEqualTo(413);
        assertThat(agents).isEmpty();
    }

    @Test
    void theActorComesFromTheHeaderSanitizedOrDefaultsToApi() throws Exception {
        authed("POST", "/api/agents", NEW_AGENT, "X-Actor", "alice@home");
        authed("POST", "/api/agents/t1/stop", null);
        authed("POST", "/api/agents/t1/resume", null, "X-Actor", "bad/actor");
        assertThat(actors).containsExactly("api:alice@home", "api", "api");
    }

    // ---- mode ----

    @Test
    void demoCanBeSelectedAndProdIsRefusedWithAConflict() throws Exception {
        assertThat(authed("PUT", "/api/system/mode", "{\"mode\":\"DEMO\"}").status()).isEqualTo(200);
        var prod = authed("PUT", "/api/system/mode", "{\"mode\":\"PROD\"}");
        assertThat(prod.status()).isEqualTo(409);
        assertThat(prod.json().get("error").asText()).contains("PROD");
        assertThat(authed("PUT", "/api/system/mode", "{}").status()).isEqualTo(400);
    }

    @Test
    void theTwoStepsToProdAnswerWithTheRightStatusCodes() throws Exception {
        var challenge = authed("POST", "/api/system/prod/challenge", null);
        assertThat(challenge.status()).isEqualTo(200);
        assertThat(challenge.json().get("challengeId").asText()).isEqualTo("c1");
        assertThat(challenge.json().get("confirmPhrase").asText()).isEqualTo("SWITCH TO PROD");

        var ok = authed("POST", "/api/system/prod/confirm",
                "{\"challengeId\":\"c1\",\"code\":\"123456\",\"phrase\":\"SWITCH TO PROD\"}");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.json().get("mode").asText()).isEqualTo("PROD");
        assertThat(confirmed).containsExactly("c1|123456|SWITCH TO PROD");

        var bad = authed("POST", "/api/system/prod/confirm",
                "{\"challengeId\":\"c1\",\"code\":\"000000\",\"phrase\":\"SWITCH TO PROD\"}");
        assertThat(bad.status()).isEqualTo(403);
        assertThat(bad.json().get("error").asText()).contains("rejected");
    }

    @Test
    void theFlagOffIs409AndALockoutIs423() throws Exception {
        prodBehaviour = "off";
        assertThat(authed("POST", "/api/system/prod/challenge", null).status()).isEqualTo(409);
        prodBehaviour = "locked";
        var locked = authed("POST", "/api/system/prod/challenge", null);
        assertThat(locked.status()).isEqualTo(423);
        assertThat(locked.json().get("error").asText()).contains("locked");
    }

    @Test
    void theProdStepsAcceptOnlyPostAndAStrictBody() throws Exception {
        assertThat(authed("GET", "/api/system/prod/challenge", null).status()).isEqualTo(405);
        assertThat(authed("PUT", "/api/system/prod/confirm", "{}").status()).isEqualTo(405);
        assertThat(authed("POST", "/api/system/prod/confirm", "").status()).isEqualTo(400);
        assertThat(authed("POST", "/api/system/prod/confirm", "{\"code\":\"1\",\"extra\":1}").status()).isEqualTo(400);
        assertThat(confirmed).isEmpty();
    }
}
