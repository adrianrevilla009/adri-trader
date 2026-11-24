package com.adrianrevilla.trading.adapter.out.brokeretoro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.core.application.RateLimiter;
import com.adrianrevilla.trading.core.application.port.out.InstrumentCatalog;
import com.adrianrevilla.trading.core.domain.BrokerAccessDeniedException;
import com.adrianrevilla.trading.core.domain.BrokerRejectedException;
import com.adrianrevilla.trading.core.domain.BrokerUnavailableException;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.OrderState;
import com.adrianrevilla.trading.execution.application.port.out.BrokerRefStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The client against a local scripted server. JSON shapes are the assumed (unverified) ones of the client. */
class EToroBrokerTest {
    private static final Isin ISIN = new Isin("IE00B4L5Y983");

    record Reply(int status, String body, Map<String, String> headers, long delayMillis) {
        static Reply ok(String body) {
            return new Reply(200, body, Map.of(), 0);
        }

        static Reply status(int s) {
            return new Reply(s, "{}", Map.of(), 0);
        }
    }

    record Seen(String method, String path, Map<String, String> headers, String body) {}

    private HttpServer server;
    private final Map<String, Deque<Reply>> script = new HashMap<>();
    private final List<Seen> seen = new ArrayList<>();
    private final List<Long> sleeps = new ArrayList<>();
    private final Map<UUID, String> refStore = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> h = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
            String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            synchronized (seen) {
                seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), h, body));
            }
            Deque<Reply> q = script.get(key);
            Reply r = q == null || q.isEmpty() ? Reply.status(404) : q.size() > 1 ? q.pollFirst() : q.peekFirst();
            try {
                Thread.sleep(r.delayMillis());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void on(String methodAndPath, Reply... replies) {
        script.put(methodAndPath, new ArrayDeque<>(List.of(replies)));
    }

    private EToroBroker broker(EToroConfig.Mode mode, Duration timeout, RateLimiter reads, RateLimiter writes) {
        var cfg = new EToroConfig("http://127.0.0.1:" + server.getAddress().getPort(), "API-KEY", "USER-KEY", mode,
                timeout, EToroConfig.Endpoints.unverifiedDefaults());
        InstrumentCatalog catalog = new InstrumentCatalog() {
            public Optional<Long> brokerId(Isin isin) { return isin.equals(ISIN) ? Optional.of(1001L) : Optional.empty(); }
            public Optional<Isin> isin(long id) { return id == 1001L ? Optional.of(ISIN) : Optional.empty(); }
        };
        BrokerRefStore refs = new BrokerRefStore() {
            public void put(UUID id, String ref) { refStore.put(id, ref); }
            public Optional<String> get(UUID id) { return Optional.ofNullable(refStore.get(id)); }
        };
        return new EToroBroker(cfg, catalog, refs, reads, writes, sleeps::add);
    }

    private EToroBroker broker(EToroConfig.Mode mode) {
        return broker(mode, Duration.ofSeconds(5), RateLimiter.perMinute(1000), RateLimiter.perMinute(1000));
    }

    private static OrderRequest buy() {
        return new OrderRequest(UUID.randomUUID(), "agent", ISIN, OrderRequest.Side.BUY, new BigDecimal("25.50"), null);
    }

    @Test
    void demoOrderGoesToTheDemoEndpointWithHeadersAndSafeBody() throws Exception {
        on("POST /api/v2/trading/execution/demo/orders", Reply.ok("{\"orderId\":\"E-1\"}"));
        var order = buy();
        broker(EToroConfig.Mode.DEMO).submit(order);

        Seen s = seen.get(0);
        assertThat(s.headers()).containsEntry("x-api-key", "API-KEY").containsEntry("x-user-key", "USER-KEY")
                .containsEntry("x-request-id", order.internalId().toString());
        assertThat(s.body()).contains("\"instrumentId\":1001", "\"isBuy\":true", "\"amount\":25.50",
                "\"leverage\":1", "\"settlementType\":\"real\"");
        assertThat(s.body()).doesNotContain("units", "contracts");
        assertThat(refStore.get(order.internalId())).isEqualTo("E-1");
    }

    // Eligibility and costs shapes below were verified on 2026-11-24 against the real API (read-only queries).
    private static final String ELIGIBILITY = "POST /api/v2/trading/info/eligibility";
    private static final String COSTS = "POST /api/v2/trading/info/costs";
    private static final String REAL_ORDERS = "POST /api/v2/trading/execution/orders";
    private static final String COSTS_OK = "{\"instrumentId\":1001,\"costs\":[{\"costType\":\"marketSpread\","
            + "\"value\":0.1,\"currency\":\"USD\"}]}";

    private static String eligibility(String settlement, boolean allowOpen, boolean allowClose, String min) {
        return "{\"eligibilities\":[{\"instrumentId\":1001,\"minPositionExposure\":" + min
                + ",\"allowOpenPosition\":" + allowOpen + ",\"allowClosePosition\":" + allowClose
                + ",\"leverageConfigs\":[{\"settlementType\":\"" + settlement + "\",\"direction\":\"long\"}]}],"
                + "\"notFoundInstrumentIds\":[]}";
    }

    private static OrderRequest sell() {
        return new OrderRequest(UUID.randomUUID(), "agent", ISIN, OrderRequest.Side.SELL, new BigDecimal("25.50"), null);
    }

    private void assertOrderNeverSent() {
        assertThat(seen).noneMatch(s -> s.path().endsWith("/execution/orders"));
    }

    @Test
    void realOrderAbortsIfTheInstrumentOnlyTradesAsCfd() {
        on(ELIGIBILITY, Reply.ok(eligibility("cfd", true, true, "10")));
        on(REAL_ORDERS, Reply.ok("{\"orderId\":\"E-1\"}"));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.REAL).submit(buy()))
                .isInstanceOf(BrokerRejectedException.class).hasMessageContaining("no real-asset");
        assertOrderNeverSent();
    }

    @Test
    void realOrderIsSentAfterEligibilityAndCostsPass() throws Exception {
        on(ELIGIBILITY, Reply.ok(eligibility("real", true, true, "10")));
        on(COSTS, Reply.ok(COSTS_OK));
        on(REAL_ORDERS, Reply.ok("{\"orderId\":\"E-9\"}"));
        broker(EToroConfig.Mode.REAL).submit(buy());
        assertThat(seen).extracting(Seen::path).containsExactly("/api/v2/trading/info/eligibility",
                "/api/v2/trading/info/costs", "/api/v2/trading/execution/orders");
        assertThat(seen.get(0).body()).contains("\"instrumentIds\":[1001]", "\"currency\":\"USD\"");
        assertThat(seen.get(1).body()).contains("\"action\":\"open\"", "\"transaction\":\"buy\"",
                "\"instrumentId\":1001", "\"settlementType\":\"real\"", "\"orderType\":\"mkt\"", "\"leverage\":1",
                "\"amount\":25.50", "\"orderCurrency\":\"usd\"");
    }

    @Test
    void realOrderAbortsIfEtoroDoesNotKnowTheInstrument() {
        on(ELIGIBILITY, Reply.ok("{\"eligibilities\":[],\"notFoundInstrumentIds\":[1001]}"));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.REAL).submit(buy()))
                .isInstanceOf(BrokerRejectedException.class).hasMessageContaining("not eligible");
        assertOrderNeverSent();
    }

    @Test
    void realOrderAbortsIfOpeningPositionsIsNotAllowed() {
        on(ELIGIBILITY, Reply.ok(eligibility("real", false, true, "10")));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.REAL).submit(buy()))
                .isInstanceOf(BrokerRejectedException.class).hasMessageContaining("opening");
        assertOrderNeverSent();
    }

    @Test
    void realOrderAbortsBelowTheMinimumPositionExposure() {
        on(ELIGIBILITY, Reply.ok(eligibility("real", true, true, "30")));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.REAL).submit(buy()))
                .isInstanceOf(BrokerRejectedException.class).hasMessageContaining("minimum");
        assertOrderNeverSent();
    }

    @Test
    void realOrderAbortsIfTheCostEstimateHasNoCosts() {
        on(ELIGIBILITY, Reply.ok(eligibility("real", true, true, "10")));
        on(COSTS, Reply.ok("{\"instrumentId\":1001}"));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.REAL).submit(buy()))
                .isInstanceOf(BrokerRejectedException.class).hasMessageContaining("costs");
        assertOrderNeverSent();
    }

    @Test
    void realOrderAbortsIfThePreflightCallIsRejected() {
        on(ELIGIBILITY, new Reply(400, "bad request", Map.of(), 0));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.REAL).submit(buy()))
                .isInstanceOf(BrokerRejectedException.class).hasMessageContaining("preflight failed");
        assertOrderNeverSent();
    }

    @Test
    void realOrderAbortsIfTheEligibilityResponseIsMalformed() {
        on(ELIGIBILITY, Reply.ok("not json"));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.REAL).submit(buy()))
                .isInstanceOf(BrokerRejectedException.class).hasMessageContaining("unreadable");
        assertOrderNeverSent();
    }

    @Test
    void deniedKeyInThePreflightIsReportedAsAccessDenied() {
        on(ELIGIBILITY, Reply.status(401));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.REAL).submit(buy()))
                .isInstanceOf(BrokerAccessDeniedException.class);
    }

    @Test
    void realSellChecksEligibilityButSkipsTheCostEstimate() throws Exception {
        // the costs endpoint rejects 'sell' (only opening is supported), so selling must not depend on it
        on(ELIGIBILITY, Reply.ok(eligibility("real", false, true, "10")));
        on(REAL_ORDERS, Reply.ok("{\"orderId\":\"E-5\"}"));
        broker(EToroConfig.Mode.REAL).submit(sell());
        assertThat(seen).extracting(Seen::path).containsExactly("/api/v2/trading/info/eligibility",
                "/api/v2/trading/execution/orders");
    }

    @Test
    void realSellAbortsIfClosingPositionsIsNotAllowed() {
        on(ELIGIBILITY, Reply.ok(eligibility("real", true, false, "10")));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.REAL).submit(sell()))
                .isInstanceOf(BrokerRejectedException.class).hasMessageContaining("closing");
        assertOrderNeverSent();
    }

    @Test
    void clientErrorIsADefinitiveRejection() {
        on("POST /api/v2/trading/execution/demo/orders", new Reply(400, "bad amount", Map.of(), 0));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.DEMO).submit(buy())).isInstanceOf(BrokerRejectedException.class);
    }

    @Test
    void deniedKeyIsReportedAsAccessDenied() {
        on("POST /api/v2/trading/execution/demo/orders", Reply.status(401));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.DEMO).submit(buy())).isInstanceOf(BrokerAccessDeniedException.class);
    }

    @Test
    void serverErrorOnAWriteIsUnknownOutcomeAndIsNeverRetried() {
        on("POST /api/v2/trading/execution/demo/orders", Reply.status(500));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.DEMO).submit(buy())).isInstanceOf(BrokerUnavailableException.class);
        assertThat(seen).hasSize(1);
    }

    @Test
    void timeoutOnAWriteIsUnknownOutcomeAndIsNeverRetried() {
        on("POST /api/v2/trading/execution/demo/orders", new Reply(200, "{\"orderId\":\"E-1\"}", Map.of(), 1500));
        var b = broker(EToroConfig.Mode.DEMO, Duration.ofMillis(300), RateLimiter.perMinute(100), RateLimiter.perMinute(100));
        assertThatThrownBy(() -> b.submit(buy())).isInstanceOf(BrokerUnavailableException.class);
        assertThat(seen).hasSize(1);
    }

    @Test
    void throttledWriteWaitsRetryAfterAndThenSucceeds() throws Exception {
        on("POST /api/v2/trading/execution/demo/orders",
                new Reply(429, "{}", Map.of("Retry-After", "7"), 0), Reply.ok("{\"orderId\":\"E-2\"}"));
        broker(EToroConfig.Mode.DEMO).submit(buy());
        assertThat(sleeps).containsExactly(7000L);
        assertThat(seen).hasSize(2);
    }

    @Test
    void readsAreRetriedWithGrowingWaitUntilTheyWork() throws Exception {
        on("GET /api/v1/trading/info/demo/pnl", Reply.status(500), Reply.status(503),
                Reply.ok("{\"clientPortfolio\":{\"credit\":75.5,\"positions\":[{\"instrumentId\":1001,\"units\":2.5}]}}"));
        var b = broker(EToroConfig.Mode.DEMO);
        assertThat(b.cash()).isEqualByComparingTo("75.5");
        assertThat(sleeps).containsExactly(1000L, 2000L);
        assertThat(b.positions().get(ISIN)).isEqualByComparingTo("2.5");
    }

    @Test
    void readFailsAfterTheMaximumNumberOfAttempts() {
        on("GET /api/v1/trading/info/demo/pnl", Reply.status(500));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.DEMO).cash()).isInstanceOf(BrokerUnavailableException.class);
        assertThat(seen).hasSize(3);
    }

    @Test
    void realModeReadsThePortfolioFromTheRealEndpoint() throws Exception {
        on("GET /api/v1/trading/info/real/pnl", Reply.ok("{\"clientPortfolio\":{\"credit\":10,\"positions\":[]}}"));
        assertThat(broker(EToroConfig.Mode.REAL).cash()).isEqualByComparingTo("10");
    }

    @Test
    void portfolioWithoutClientPortfolioOrCreditIsAnError() {
        on("GET /api/v1/trading/info/demo/pnl", Reply.ok("{\"cash\":75.5}"));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.DEMO).cash())
                .isInstanceOf(BrokerUnavailableException.class).hasMessageContaining("clientPortfolio");
        on("GET /api/v1/trading/info/demo/pnl", Reply.ok("{\"clientPortfolio\":{\"positions\":[]}}"));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.DEMO).cash())
                .isInstanceOf(BrokerUnavailableException.class).hasMessageContaining("credit");
    }

    @Test
    void unknownInstrumentAtTheBrokerIsNotSilentlyIgnored() {
        on("GET /api/v1/trading/info/demo/pnl",
                Reply.ok("{\"clientPortfolio\":{\"credit\":1,\"positions\":[{\"instrumentId\":9,\"units\":1}]}}"));
        assertThatThrownBy(() -> broker(EToroConfig.Mode.DEMO).positions())
                .isInstanceOf(BrokerUnavailableException.class).hasMessageContaining("unknown instrument");
    }

    @Test
    void ownRateLimiterDelaysWritesBeyondTheLimit() throws Exception {
        long[] now = {0};
        Clock clock = new Clock() {
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now[0]); }
        };
        List<Long> waits = new ArrayList<>();
        RateLimiter writes = new RateLimiter(2, 60_000, clock, ms -> { waits.add(ms); now[0] += ms; });
        on("POST /api/v2/trading/execution/demo/orders", Reply.ok("{\"orderId\":\"E\"}"));
        var b = broker(EToroConfig.Mode.DEMO, Duration.ofSeconds(5), RateLimiter.perMinute(100), writes);
        b.submit(buy());
        b.submit(buy());
        assertThat(waits).isEmpty();
        b.submit(buy()); // third in the same minute must wait for the window
        assertThat(waits).containsExactly(60_000L);
    }

    @Test
    void lookupWithoutBrokerReferenceReturnsEmptySoTheCallerHaltsInsteadOfResending() throws Exception {
        assertThat(broker(EToroConfig.Mode.DEMO).lookup(UUID.randomUUID())).isEmpty();
    }

    @Test
    void lookupMapsTheBrokerStatus() throws Exception {
        on("POST /api/v2/trading/execution/demo/orders", Reply.ok("{\"orderId\":\"E-5\"}"));
        on("GET /api/v2/trading/execution/orders/E-5",
                Reply.ok("{\"status\":\"PartiallyExecuted\",\"executedAmount\":10,\"executedUnits\":1,\"averagePrice\":10}"));
        var b = broker(EToroConfig.Mode.DEMO);
        var order = buy();
        b.submit(order);
        var found = b.lookup(order.internalId()).orElseThrow();
        assertThat(found.state()).isEqualTo(OrderState.PARTIALLY_FILLED);
        assertThat(found.filledAmountEur()).isEqualByComparingTo("10");
        assertThat(found.averagePrice()).isEqualByComparingTo("10");
    }
}
