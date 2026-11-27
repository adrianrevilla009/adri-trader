package com.adrianrevilla.trading.adapter.out.brokeretoro;

import com.adrianrevilla.trading.core.application.RateLimiter;
import com.adrianrevilla.trading.core.application.port.out.InstrumentCatalog;
import com.adrianrevilla.trading.core.domain.Broker;
import com.adrianrevilla.trading.core.domain.BrokerAccessDeniedException;
import com.adrianrevilla.trading.core.domain.BrokerException;
import com.adrianrevilla.trading.core.domain.BrokerOrder;
import com.adrianrevilla.trading.core.domain.BrokerRejectedException;
import com.adrianrevilla.trading.core.domain.BrokerUnavailableException;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.OrderState;
import com.adrianrevilla.trading.execution.application.port.out.BrokerRefStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * eToro public API (beta) behind the {@link Broker} interface, using the standard Java HTTP client.
 *
 * <p>Guarantees implemented here and tested against a local server: headers x-api-key, x-user-key and
 * x-request-id (the internal order id) on every call; own rate limits (60 reads, 20 writes per minute and key);
 * 429 handled by waiting Retry-After; reads retried with growing wait; a write is never retried after an unknown
 * outcome; orders always by amount, leverage 1 and settlementType real; before a REAL order the eligibility and
 * cost-estimate queries must pass (see {@link #preflight}), otherwise nothing is sent.
 *
 * <p>Verified in demo on 2026-11-26 (one 10 USD buy and its close): order create, order lookup, close by positionId,
 * close status and portfolio shape. Verified read-only on 2026-11-24: eligibility and cost estimate. Every amount is
 * capped by {@link EToroConfig#maxOrderAmount()} before anything is sent. NOT VERIFIED (docs only):
 * the real-account paths, cancel, limit orders and the order status ids other than 3 Filled;
 * see {@link EToroConfig.Endpoints}. They are isolated in this class.
 */
public final class EToroBroker implements Broker {
    private static final String CLOSE_PREFIX = "close:";
    private static final int CLOSE_RECEIVED = 1;   // seen right after sending a close (2026-11-26)
    private static final int CLOSE_FILLED = 3;     // seen a moment later
    private static final int MAX_READ_ATTEMPTS = 3;
    private static final int MAX_WRITE_THROTTLE_RETRIES = 2;
    private static final long MAX_WAIT_SECONDS = 60;

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final EToroConfig config;
    private final InstrumentCatalog catalog;
    private final BrokerRefStore refs;
    private final RateLimiter readLimiter;
    private final RateLimiter writeLimiter;
    private final Sleeper sleeper;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public EToroBroker(EToroConfig config, InstrumentCatalog catalog, BrokerRefStore refs, RateLimiter readLimiter,
            RateLimiter writeLimiter, Sleeper sleeper) {
        this.config = config;
        this.catalog = catalog;
        this.refs = refs;
        this.readLimiter = readLimiter;
        this.writeLimiter = writeLimiter;
        this.sleeper = sleeper;
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
    }

    // ---------------------------------------------------------------- orders

    @Override
    public void submit(OrderRequest order) throws BrokerException {
        long instrumentId = catalog.brokerId(order.isin())
                .orElseThrow(() -> new BrokerRejectedException("no eToro id for " + order.isin().value()));
        if (order.amountEur().compareTo(config.maxOrderAmount()) > 0) {
            throw new BrokerRejectedException("amount " + order.amountEur() + " is above the cap of "
                    + config.maxOrderAmount() + " per order, order not sent");
        }
        if (order.limitPrice() != null) {
            throw new BrokerRejectedException("limit orders are not supported (their body is not verified), order not sent");
        }
        if (config.mode() == EToroConfig.Mode.REAL) {
            preflight(order, instrumentId);
        }
        if (order.side() == OrderRequest.Side.SELL) {
            sell(order, instrumentId);
            return;
        }
        String body = orderBody(order, instrumentId);
        String path = config.mode() == EToroConfig.Mode.REAL
                ? config.endpoints().ordersReal() : config.endpoints().ordersDemo();
        Response r = write("POST", path, body, order.internalId());
        try {
            JsonNode node = json.readTree(r.body());
            String id = node.path("orderId").asText("");
            if (id.isBlank()) {
                throw new BrokerUnavailableException("order accepted but no order id in the response");
            }
            refs.put(order.internalId(), id);
        } catch (IOException e) {
            throw new BrokerUnavailableException("order accepted but response unreadable", e);
        }
    }

    private String orderBody(OrderRequest o, long instrumentId) {
        ObjectNode n = new ObjectMapper().setNodeFactory(JsonNodeFactory.withExactBigDecimals(true)).createObjectNode();
        n.put("action", "open");             // verified 2026-11-26 (demo); a sell is a close by positionId, see sell()
        n.put("transaction", "buy");
        n.put("instrumentId", instrumentId);
        n.put("settlementType", "real");     // never a CFD
        n.put("orderType", "mkt");
        n.put("leverage", 1);                // never leverage
        n.put("amount", o.amountEur());      // by amount: never units or contracts at the same time
        n.put("orderCurrency", "usd");       // eToro states amounts in USD (see cash())
        return n.toString();
    }

    /**
     * A sell closes open positions of the instrument by positionId (verified 2026-11-26 in demo): the whole position
     * when {@code closePosition} is set, otherwise units worth about the requested amount, oldest position first. The
     * references of all close orders are stored together as {@code close:id1,id2}. If a later close fails after an
     * earlier one went through, the references so far are kept and the outcome is reported as unknown.
     */
    private void sell(OrderRequest order, long instrumentId) throws BrokerException {
        List<JsonNode> open = new ArrayList<>();
        for (JsonNode p : portfolio().path("positions")) {
            if (instrumentOf(p) == instrumentId && p.path("units").decimalValue().signum() > 0) {
                open.add(p);
            }
        }
        if (open.isEmpty()) {
            throw new BrokerRejectedException("no open position in " + order.isin().value() + " to sell, order not sent");
        }
        boolean demo = config.mode() != EToroConfig.Mode.REAL;
        String template = demo ? config.endpoints().closeDemo() : config.endpoints().closeReal();
        BigDecimal left = order.amountEur();
        List<String> closeIds = new ArrayList<>();
        for (JsonNode p : open) {
            if (!order.closePosition() && left.signum() <= 0) {
                break;
            }
            BigDecimal units = p.path("units").decimalValue();
            BigDecimal exposure = p.path("unrealizedPnL").path("exposureInAccountCurrency").decimalValue();
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("InstrumentId", instrumentId);
            if (!order.closePosition() && exposure.compareTo(left) > 0 && exposure.signum() > 0) {
                BigDecimal part = left.multiply(units).divide(exposure, 6, java.math.RoundingMode.DOWN);
                if (part.signum() <= 0) {
                    break;
                }
                body.put("UnitsToDeduct", part);
                left = BigDecimal.ZERO;
            } else {
                left = left.subtract(exposure);
            }
            String path = template.replace("{positionId}", String.valueOf(p.path("positionID").asLong()));
            try {
                Response r = write("POST", path, body.toString(), UUID.randomUUID());
                String id = json.readTree(r.body()).path("orderForClose").path("orderID").asText("");
                if (id.isBlank()) {
                    throw new BrokerUnavailableException("close accepted but no order id in the response");
                }
                closeIds.add(id);
            } catch (IOException | BrokerException e) {
                if (!closeIds.isEmpty()) {
                    refs.put(order.internalId(), CLOSE_PREFIX + String.join(",", closeIds));
                    throw new BrokerUnavailableException("a close failed after " + closeIds.size()
                            + " went through, outcome unknown: " + e.getMessage(), e);
                }
                if (e instanceof BrokerException be) {
                    throw be;
                }
                throw new BrokerUnavailableException("close accepted but response unreadable", e);
            }
        }
        if (closeIds.isEmpty()) {
            throw new BrokerRejectedException("nothing to close for " + order.isin().value() + ", order not sent");
        }
        refs.put(order.internalId(), CLOSE_PREFIX + String.join(",", closeIds));
    }

    private static long instrumentOf(JsonNode position) {
        JsonNode id = position.has("instrumentID") ? position.path("instrumentID") : position.path("instrumentId");
        return id.asLong(-1);
    }

    /**
     * Pre-trade check, nothing is sent unless it passes. Eligibility (verified 2026-11-24) must show a real-asset
     * long configuration, an open (BUY) or close (SELL) permission and, for a BUY, an amount of at least the minimum
     * exposure. A BUY also needs a cost estimate with a costs list. The costs endpoint only supports opening, so a
     * SELL skips it. Both calls only query: neither creates an order. LIMITATION: eToro states amounts and minimums
     * in USD; the EUR amount is compared as a plain number (see {@link #cash()}).
     */
    private void preflight(OrderRequest order, long instrumentId) throws BrokerException {
        boolean buy = order.side() == OrderRequest.Side.BUY;
        JsonNode item = eligibilityOf(instrumentId, order);
        boolean realLong = false;
        for (JsonNode c : item.path("leverageConfigs")) {
            realLong |= "real".equalsIgnoreCase(c.path("settlementType").asText())
                    && "long".equalsIgnoreCase(c.path("direction").asText());
        }
        if (!realLong) {
            throw new BrokerRejectedException(order.isin().value() + " has no real-asset (long) configuration at eToro,"
                    + " order not sent");
        }
        if (!item.path(buy ? "allowOpenPosition" : "allowClosePosition").asBoolean(false)) {
            throw new BrokerRejectedException("eToro does not allow " + (buy ? "opening" : "closing") + " positions in "
                    + order.isin().value() + ", order not sent");
        }
        if (!buy) {
            return;
        }
        JsonNode min = item.path("minPositionExposure");
        if (min.isNumber() && order.amountEur().compareTo(min.decimalValue()) < 0) {
            throw new BrokerRejectedException("amount " + order.amountEur() + " is below the minimum position exposure "
                    + min.decimalValue() + " of " + order.isin().value() + ", order not sent");
        }
        requireCosts(order, instrumentId);
    }

    private JsonNode eligibilityOf(long instrumentId, OrderRequest order) throws BrokerException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.putArray("instrumentIds").add(instrumentId);
        body.put("currency", "USD");   // the only currency eToro accepts
        JsonNode root = queryForPreflight(config.endpoints().eligibility(), body.toString());
        for (JsonNode e : root.path("eligibilities")) {
            if (e.path("instrumentId").asLong(-1) == instrumentId) {
                return e;
            }
        }
        throw new BrokerRejectedException(order.isin().value() + " is not eligible for trading at eToro, order not sent");
    }

    private void requireCosts(OrderRequest order, long instrumentId) throws BrokerException {
        ObjectNode n = new ObjectMapper().setNodeFactory(JsonNodeFactory.withExactBigDecimals(true)).createObjectNode();
        n.put("action", "open");
        n.put("transaction", "buy");
        n.put("instrumentId", instrumentId);
        n.put("settlementType", "real");
        n.put("orderType", "mkt");
        n.put("leverage", 1);
        n.put("amount", order.amountEur());
        n.put("orderCurrency", "usd");
        if (!queryForPreflight(config.endpoints().estimate(), n.toString()).path("costs").isArray()) {
            throw new BrokerRejectedException("cost estimate for " + order.isin().value()
                    + " has no costs list, order not sent");
        }
    }

    /** POST that only queries. An access error is passed on; any other failure means the order is not sent. */
    private JsonNode queryForPreflight(String path, String body) throws BrokerException {
        Response r;
        try {
            r = write("POST", path, body, UUID.randomUUID());
        } catch (BrokerAccessDeniedException e) {
            throw e;
        } catch (BrokerException e) {
            throw new BrokerRejectedException("preflight failed, order not sent: " + e.getMessage());
        }
        try {
            return json.readTree(r.body());
        } catch (IOException e) {
            throw new BrokerRejectedException("preflight response unreadable, order not sent");
        }
    }

    @Override
    public void cancel(UUID internalId) throws BrokerException {
        String ref = refs.get(internalId).orElseThrow(
                () -> new BrokerRejectedException("order " + internalId + " has no broker reference"));
        if (ref.startsWith(CLOSE_PREFIX)) {
            throw new BrokerRejectedException("a close order cannot be cancelled: it is executed at once");
        }
        String path = (config.mode() == EToroConfig.Mode.REAL ? config.endpoints().ordersReal()
                : config.endpoints().ordersDemo()) + "/" + ref;
        write("DELETE", path, null, UUID.randomUUID());
    }

    /**
     * Without a stored broker id the order cannot be found (no idempotency key): the answer is empty, which makes
     * the execution service halt for a manual check instead of resending.
     */
    @Override
    public Optional<BrokerOrder> lookup(UUID internalId) throws BrokerException {
        Optional<String> ref = refs.get(internalId);
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        String stored = ref.orElseThrow();
        if (stored.startsWith(CLOSE_PREFIX)) {
            return lookupClose(internalId, stored.substring(CLOSE_PREFIX.length()));
        }
        boolean demo = config.mode() != EToroConfig.Mode.REAL;
        String base = demo ? config.endpoints().lookupDemo() : config.endpoints().lookupReal();
        Response r = read(base + "?orderId=" + URLEncoder.encode(stored, StandardCharsets.UTF_8));
        if (r.status() == 404) {
            // seen live on 2026-11-26: a new order is not listed for a moment. We hold eToro's id, so it is pending.
            return Optional.of(new BrokerOrder(internalId, OrderState.SUBMITTED, BigDecimal.ZERO, BigDecimal.ZERO, null));
        }
        try {
            JsonNode n = json.readTree(r.body());
            BigDecimal amount = BigDecimal.ZERO;
            BigDecimal units = BigDecimal.ZERO;
            for (JsonNode e : n.path("positionExecutions")) {
                amount = amount.add(decimal(e, "initialExposureAccountCurrency"));
                units = units.add(decimal(e.path("openingData"), "units"));
            }
            // Price in the ACCOUNT currency (amount / units), not eToro's avgPrice (asset currency, EUR): the ledger
            // derives units as amount / price and must get eToro's real units back.
            BigDecimal price = units.signum() == 0 ? null : amount.divide(units, 10, java.math.RoundingMode.HALF_UP);
            return Optional.of(new BrokerOrder(internalId, state(n.path("status").path("id").asInt(-1)), amount, units,
                    price));
        } catch (IOException e) {
            throw new BrokerUnavailableException("order status unreadable", e);
        }
    }

    /** Status of the close orders of a sell: filled only when every one is filled (verified statusID 3, 2026-11-26). */
    private Optional<BrokerOrder> lookupClose(UUID internalId, String ids) throws BrokerException {
        boolean demo = config.mode() != EToroConfig.Mode.REAL;
        String template = demo ? config.endpoints().closeStatusDemo() : config.endpoints().closeStatusReal();
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal units = BigDecimal.ZERO;
        boolean allFilled = true;
        for (String id : ids.split(",")) {
            Response r = read(template.replace("{id}", id));
            if (r.status() == 404) {
                // seen live on 2026-11-26: right after sending, the close is accepted but not listed yet. We hold its
                // id, so it is "sent, pending", not "no trace" (which would halt the caller).
                allFilled = false;
                continue;
            }
            try {
                JsonNode n = json.readTree(r.body());
                int status = n.path("statusID").asInt(-1);
                if (status != CLOSE_FILLED && status != CLOSE_RECEIVED) {
                    throw new BrokerUnavailableException("unknown close status " + status + " for close order " + id);
                }
                allFilled &= status == CLOSE_FILLED;
                for (JsonNode p : n.path("positions")) {
                    amount = amount.add(decimal(p, "amount"));
                    units = units.add(decimal(p, "units"));
                }
            } catch (IOException e) {
                throw new BrokerUnavailableException("close status unreadable", e);
            }
        }
        BigDecimal price = units.signum() == 0 ? null : amount.divide(units, 6, java.math.RoundingMode.HALF_UP);
        return Optional.of(new BrokerOrder(internalId, allFilled ? OrderState.FILLED : OrderState.SUBMITTED, amount,
                units, price));
    }

    /** Status ids of eToro order lookup (documentation; only 3 Filled was seen live on 2026-11-26). */
    private static OrderState state(int id) throws BrokerUnavailableException {
        return switch (id) {
            case 1, 2, 6, 11, 12 -> OrderState.SUBMITTED;
            case 3 -> OrderState.FILLED;
            case 5, 9 -> OrderState.PARTIALLY_FILLED;
            case 7, 8 -> OrderState.CANCELLED;
            case 4, 10 -> OrderState.REJECTED;
            default -> throw new BrokerUnavailableException("unknown order status id " + id);
        };
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? BigDecimal.ZERO : v.decimalValue();
    }

    // ----------------------------------------------------------- portfolio

    @Override
    public Map<Isin, BigDecimal> positions() throws BrokerException {
        JsonNode root = portfolio();
        Map<Isin, BigDecimal> out = new HashMap<>();
        for (JsonNode p : root.path("positions")) {
            long id = instrumentOf(p);   // the real response says instrumentID (verified 2026-11-26)
            Isin isin = catalog.isin(id).orElseThrow(
                    () -> new BrokerUnavailableException("broker holds unknown instrument id " + id));
            out.merge(isin, p.path("units").decimalValue(), BigDecimal::add);
        }
        return out;
    }

    /**
     * Available trading balance: {@code clientPortfolio.credit}. LIMITATION: eToro reports it in USD
     * ({@code accountCurrencyId} 1), while this project reasons in EUR. No conversion is done here, so the caller
     * must not treat this number as euros until the currency decision is made.
     */
    @Override
    public BigDecimal cash() throws BrokerException {
        JsonNode credit = portfolio().path("credit");
        if (!credit.isNumber()) {
            throw new BrokerUnavailableException("portfolio response has no numeric clientPortfolio.credit");
        }
        return credit.decimalValue();
    }

    /** The {@code clientPortfolio} object of the pnl endpoint (shape verified; position item fields are not). */
    private JsonNode portfolio() throws BrokerException {
        String env = config.mode() == EToroConfig.Mode.REAL ? "real" : "demo";
        String path = config.endpoints().portfolio().replace("{env}", env);
        try {
            JsonNode portfolio = json.readTree(read(path).body()).path("clientPortfolio");
            if (!portfolio.isObject()) {
                throw new BrokerUnavailableException("portfolio response has no clientPortfolio object");
            }
            return portfolio;
        } catch (IOException e) {
            throw new BrokerUnavailableException("portfolio unreadable", e);
        }
    }

    // ----------------------------------------------------------------- http

    private record Response(int status, String body) {}

    /** Reads are retried with growing wait on 429, 5xx and network errors. */
    private Response read(String path) throws BrokerException {
        BrokerException last = null;
        for (int attempt = 1; attempt <= MAX_READ_ATTEMPTS; attempt++) {
            try {
                acquire(readLimiter);
                HttpResponse<String> res = send("GET", path, null, UUID.randomUUID());
                int s = res.statusCode();
                if (s == 404) {
                    return new Response(404, res.body());
                }
                if (s / 100 == 2) {
                    return new Response(s, res.body());
                }
                if (s == 401 || s == 403) {
                    throw new BrokerAccessDeniedException("HTTP " + s + " on read: API key rejected or revoked");
                }
                if (s == 429) {
                    last = new BrokerUnavailableException("HTTP 429 on read");
                    sleep(waitMillis(res, attempt));
                    continue;
                }
                if (s / 100 == 5) {
                    last = new BrokerUnavailableException("HTTP " + s + " on read");
                    sleep(1000L * (1L << (attempt - 1)));
                    continue;
                }
                throw new BrokerRejectedException("HTTP " + s + " on read: " + res.body());
            } catch (IOException e) {
                last = new BrokerUnavailableException("network error on read: " + e.getMessage(), e);
                sleep(1000L * (1L << (attempt - 1)));
            }
        }
        throw last;
    }

    /** A write is retried only on 429 (the order was certainly not processed); never on an unknown outcome. */
    private Response write(String method, String path, String body, UUID requestId) throws BrokerException {
        for (int attempt = 0; ; attempt++) {
            try {
                acquire(writeLimiter);
                HttpResponse<String> res = send(method, path, body, requestId);
                int s = res.statusCode();
                if (s / 100 == 2) {
                    return new Response(s, res.body());
                }
                if (s == 401 || s == 403) {
                    throw new BrokerAccessDeniedException("HTTP " + s + ": API key rejected or revoked");
                }
                if (s == 429) {
                    if (attempt >= MAX_WRITE_THROTTLE_RETRIES) {
                        throw new BrokerRejectedException("rate limited by eToro (429), order not processed");
                    }
                    sleep(waitMillis(res, attempt + 1));
                    continue;
                }
                if (s / 100 == 5) {
                    throw new BrokerUnavailableException("HTTP " + s + ": outcome unknown");
                }
                throw new BrokerRejectedException("HTTP " + s + ": " + res.body());
            } catch (IOException e) {
                throw new BrokerUnavailableException("network error, outcome unknown: " + e.getMessage(), e);
            }
        }
    }

    private HttpResponse<String> send(String method, String path, String body, UUID requestId)
            throws IOException, BrokerException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .timeout(config.timeout())
                .header("x-api-key", config.apiKey())
                .header("x-user-key", config.userKey())
                .header("x-request-id", requestId.toString())
                .header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("interrupted", e);
        }
    }

    private static void acquire(RateLimiter limiter) throws BrokerException {
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("interrupted while waiting for the rate limit", e);
        }
    }

    private void sleep(long millis) throws BrokerException {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("interrupted", e);
        }
    }

    private static long waitMillis(HttpResponse<String> res, int attempt) {
        long seconds = res.headers().firstValue("Retry-After").map(v -> {
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return 1L;
            }
        }).orElse((long) attempt);
        return Math.min(seconds, MAX_WAIT_SECONDS) * 1000;
    }

    /** Duration helper for callers building the config. */
    public static Duration defaultTimeout() {
        return Duration.ofSeconds(10);
    }
}
