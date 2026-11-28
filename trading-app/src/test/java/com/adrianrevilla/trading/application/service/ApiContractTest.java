package com.adrianrevilla.trading.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.adapter.in.web.AgentsApiServer;
import com.adrianrevilla.trading.core.domain.CapitalPolicy;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.strategies.domain.BuiltinStrategies;
import com.adrianrevilla.trading.strategies.domain.StrategyCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Contract test, server side: every operation of {@code contract/api-contract.json} is executed against the real API and
 * use cases, in order, and the real answer must have the contract's status and the same shape (field names and JSON
 * types). The screen's client is tested against the same file (frontend/src/contract.test.ts), so a change on either
 * side that breaks the other fails a test.
 */
@Tag("contract")
class ApiContractTest {
    private static final String TOKEN = "0123456789abcdef-secret";
    private static final String A = "IE00B4L5Y983";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private AgentsApiServer server;

    @BeforeEach
    void start() throws Exception {
        var agents = new AgentService(new InMemoryAgentStore(), new StrategyCatalog(BuiltinStrategies.all()),
                new CapitalPolicy(new BigDecimal("10"), new BigDecimal("0.25")), Set.of(new Isin(A)));
        var mode = new ModeService((l, m) -> { }, (a, f, t, o, d) -> { },
                ProdSwitchPolicy.enabled("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Duration.ofMinutes(2), 3,
                        Duration.ofMinutes(15)), Clock.systemUTC());
        server = new AgentsApiServer(agents, agents, mode, TOKEN, 0, "http://localhost:5173");
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private static JsonNode contract() throws Exception {
        Path file = Path.of("..", "contract", "api-contract.json");
        return JSON.readTree(Files.readString(file));
    }

    /** null matches anything; objects must have the same keys; arrays compare their first elements. */
    static List<String> differences(String at, JsonNode expected, JsonNode actual) {
        List<String> out = new ArrayList<>();
        if (expected.isNull() || actual.isNull()) {
            return out;
        }
        if (expected.isObject() && actual.isObject()) {
            Iterator<String> names = expected.fieldNames();
            while (names.hasNext()) {
                String n = names.next();
                if (!actual.has(n)) {
                    out.add(at + "." + n + " is missing in the answer");
                } else {
                    out.addAll(differences(at + "." + n, expected.get(n), actual.get(n)));
                }
            }
            Iterator<String> real = actual.fieldNames();
            while (real.hasNext()) {
                String n = real.next();
                if (!expected.has(n)) {
                    out.add(at + "." + n + " is in the answer but not in the contract");
                }
            }
        } else if (expected.isArray() && actual.isArray()) {
            if (expected.size() > 0 && actual.size() > 0) {
                out.addAll(differences(at + "[0]", expected.get(0), actual.get(0)));
            }
        } else if (kind(expected) != kind(actual)) {
            out.add(at + " is " + kind(actual) + " but the contract says " + kind(expected));
        }
        return out;
    }

    private static String kind(JsonNode n) {
        return n.isObject() ? "object" : n.isArray() ? "array" : n.isTextual() ? "string" : n.isNumber() ? "number"
                : n.isBoolean() ? "boolean" : "null";
    }

    @Test
    void everyOperationOfTheContractIsAnsweredWithItsStatusAndShape() throws Exception {
        JsonNode operations = contract().get("operations");
        assertThat(operations.size()).isGreaterThan(15);
        List<String> problems = new ArrayList<>();
        for (JsonNode op : operations) {
            var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + op.get("path").asText()))
                    .header("X-Actor", "contract")
                    .method(op.get("method").asText(), op.has("request")
                            ? HttpRequest.BodyPublishers.ofString(op.get("request").toString())
                            : HttpRequest.BodyPublishers.noBody());
            if (!op.has("auth") || op.get("auth").asBoolean()) {
                b.header("Authorization", "Bearer " + TOKEN);
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String name = op.get("name").asText();
            if (r.statusCode() != op.get("status").asInt()) {
                problems.add(name + ": status " + r.statusCode() + " but the contract says " + op.get("status").asInt()
                        + " (" + r.body() + ")");
                continue;
            }
            for (String d : differences(name, op.get("response"), JSON.readTree(r.body()))) {
                problems.add(d);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theComparisonItselfDetectsMissingExtraAndRetypedFields() throws Exception {
        JsonNode expected = JSON.readTree("{\"a\":1,\"b\":\"x\",\"c\":[{\"d\":true}],\"n\":null}");
        assertThat(differences("t", expected, JSON.readTree("{\"a\":2,\"b\":\"y\",\"c\":[{\"d\":false}],\"n\":5}")))
                .isEmpty();
        assertThat(differences("t", expected, JSON.readTree("{\"a\":1,\"b\":\"x\",\"c\":[{\"d\":true}]}")))
                .containsExactly("t.n is missing in the answer");
        assertThat(differences("t", expected, JSON.readTree("{\"a\":1,\"b\":\"x\",\"c\":[{\"d\":true}],\"n\":null,\"z\":1}")))
                .containsExactly("t.z is in the answer but not in the contract");
        assertThat(differences("t", expected, JSON.readTree("{\"a\":\"1\",\"b\":\"x\",\"c\":[{\"d\":true}],\"n\":null}")))
                .containsExactly("t.a is string but the contract says number");
        assertThat(differences("t", expected, JSON.readTree("{\"a\":1,\"b\":\"x\",\"c\":[{\"d\":1}],\"n\":null}")))
                .containsExactly("t.c[0].d is number but the contract says boolean");
    }
}
