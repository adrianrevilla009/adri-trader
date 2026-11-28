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
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Load and race tests: the real use cases and the real HTTP server, many clients at once, an in-memory store. They
 * check that nothing is lost, duplicated or answered with a 5xx under concurrency, and that the answers stay fast.
 */
@Tag("load")
class ApiLoadTest {
    private static final String TOKEN = "0123456789abcdef-secret";
    private static final String A = "IE00B4L5Y983";
    private static final String B = "IE00B5BMR087";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private AgentsApiServer server;
    private ExecutorService pool;

    @BeforeEach
    void start() throws Exception {
        var store = new InMemoryAgentStore();
        var agents = new AgentService(store, new StrategyCatalog(BuiltinStrategies.all()),
                new CapitalPolicy(new BigDecimal("10"), new BigDecimal("0.25")), Set.of(new Isin(A), new Isin(B)));
        var mode = new ModeService((l, m) -> { }, (a, f, t, o, d) -> { }, ProdSwitchPolicy.disabled(),
                Clock.systemUTC());
        server = new AgentsApiServer(agents, agents, mode, TOKEN, 0, "http://localhost:5173");
        server.start();
        pool = Executors.newFixedThreadPool(24);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
        server.stop();
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Authorization", "Bearer " + TOKEN).header("X-Actor", "load")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String agent(String id) {
        return "{\"id\":\"" + id + "\",\"strategyId\":\"trend\",\"universe\":[\"" + A + "\",\"" + B
                + "\"],\"capital\":40}";
    }

    private <T> List<T> runAll(List<Callable<T>> jobs) throws Exception {
        List<Future<T>> futures = pool.invokeAll(jobs, 120, TimeUnit.SECONDS);
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }
        return out;
    }

    @Test
    void manyClientsCreateChangeStopAndResumeTheirOwnAgentsWithoutLosingAnything() throws Exception {
        int clients = 16;
        int perClient = 5;
        List<Callable<List<Integer>>> jobs = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            int client = c;
            jobs.add(() -> {
                List<Integer> statuses = new ArrayList<>();
                for (int i = 0; i < perClient; i++) {
                    String id = "load-" + client + "-" + i;
                    statuses.add(call("POST", "/api/agents", agent(id)).statusCode());
                    statuses.add(call("PUT", "/api/agents/" + id, "{\"capital\":80}").statusCode());
                    statuses.add(call("POST", "/api/agents/" + id + "/stop", null).statusCode());
                    statuses.add(call("POST", "/api/agents/" + id + "/resume", null).statusCode());
                    statuses.add(call("GET", "/api/agents", null).statusCode());
                }
                return statuses;
            });
        }
        List<Integer> all = runAll(jobs).stream().flatMap(List::stream).toList();

        assertThat(all).hasSize(clients * perClient * 5);
        assertThat(all.stream().filter(s -> s >= 500).toList()).as("server errors").isEmpty();
        assertThat(all.stream().filter(s -> s == 201).toList()).hasSize(clients * perClient);
        assertThat(all.stream().filter(s -> s == 200).toList()).hasSize(clients * perClient * 4);

        var list = JSON.readTree(call("GET", "/api/agents", null).body());
        assertThat(list).hasSize(clients * perClient);
        for (var a : list) {
            assertThat(a.get("capital").decimalValue()).isEqualByComparingTo("80");
            assertThat(a.get("state").asText()).isEqualTo("ACTIVE");
        }
    }

    @Test
    void twentyClientsCreatingTheSameIdAtOnceGetExactlyOneCreatedAndTheRestConflict() throws Exception {
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            jobs.add(() -> call("POST", "/api/agents", agent("same")).statusCode());
        }
        List<Integer> statuses = runAll(jobs);

        assertThat(statuses.stream().filter(s -> s == 201).toList()).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409).toList()).hasSize(19);
        assertThat(JSON.readTree(call("GET", "/api/agents", null).body())).hasSize(1);
        assertThat(JSON.readTree(call("GET", "/api/agents/same/audit", null).body())).hasSize(1);
    }

    @Test
    void concurrentUpdatesOfOneAgentLeaveOneOfTheSubmittedValuesAndEveryChangeIsAudited() throws Exception {
        assertThat(call("POST", "/api/agents", agent("shared")).statusCode()).isEqualTo(201);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int capital = 100 + i;
            jobs.add(() -> call("PUT", "/api/agents/shared", "{\"capital\":" + capital + "}").statusCode());
        }
        assertThat(runAll(jobs)).containsOnly(200);

        BigDecimal finalCapital = JSON.readTree(call("GET", "/api/agents/shared", null).body()).get("capital")
                .decimalValue();
        assertThat(finalCapital).isBetween(new BigDecimal("100"), new BigDecimal("119"));
        // one "created" row plus one row per distinct value written: no change is lost from the trail
        assertThat(JSON.readTree(call("GET", "/api/agents/shared/audit", null).body())).hasSize(21);
    }

    @Test
    void listingStaysFastWithTwoHundredAgentsUnderParallelReaders() throws Exception {
        for (int i = 0; i < 200; i++) {
            assertThat(call("POST", "/api/agents", agent("bulk-" + i)).statusCode()).isEqualTo(201);
        }
        List<Callable<Long>> jobs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            jobs.add(() -> {
                long t = System.nanoTime();
                assertThat(call("GET", "/api/agents", null).statusCode()).isEqualTo(200);
                return (System.nanoTime() - t) / 1_000_000;
            });
        }
        List<Long> ms = new ArrayList<>(runAll(jobs));
        Collections.sort(ms);
        long p99 = ms.get((int) (ms.size() * 0.99) - 1);
        assertThat(p99).as("p99 latency in ms").isLessThan(2000);
    }

    @Test
    void aStormOfBadTokensAndBadBodiesNeverProducesAServerErrorAndChangesNothing() throws Exception {
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int n = i;
            jobs.add(() -> {
                var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/agents"))
                        .header("Authorization", n % 2 == 0 ? "Bearer nope" + n : "Bearer " + TOKEN)
                        .POST(HttpRequest.BodyPublishers.ofString(n % 3 == 0 ? "{" : "{\"id\":" + n + "}"));
                return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
            });
        }
        assertThat(runAll(jobs)).allMatch(s -> s == 401 || s == 400);
        assertThat(JSON.readTree(call("GET", "/api/agents", null).body())).isEmpty();
    }
}
