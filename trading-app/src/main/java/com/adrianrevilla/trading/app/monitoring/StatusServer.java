package com.adrianrevilla.trading.app.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * Read-only status page on the loopback address. GET /status (JSON) and GET /metrics (Prometheus text format, ready
 * for Grafana or Azure Monitor). Shows no amounts, positions or secrets. Nothing here can change state.
 */
public final class StatusServer {
    private final HttpServer server;
    private final Supplier<Snapshot> snapshots;
    private final Supplier<String> scrape;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public StatusServer(Supplier<Snapshot> snapshots, int port) throws IOException {
        this(snapshots, port, "127.0.0.1", null);
    }

    /**
     * @param bindAddress loopback by default; inside a container it is 0.0.0.0 so Prometheus on the private compose
     *     network can scrape, and the port is never published to the host
     * @param scrape when set, /metrics returns this (the Micrometer registry) instead of the snapshot-only text
     */
    public StatusServer(Supplier<Snapshot> snapshots, int port, String bindAddress, Supplier<String> scrape)
            throws IOException {
        this.snapshots = snapshots;
        this.scrape = scrape;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bindAddress), port), 0);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (!ex.getRequestMethod().equals("GET") || !(path.equals("/status") || path.equals("/metrics"))) {
                reply(ex, 404, "text/plain", "not found");
                return;
            }
            if (path.equals("/metrics") && scrape != null) {
                reply(ex, 200, "text/plain; version=0.0.4; charset=utf-8", scrape.get());
                return;
            }
            Snapshot s = snapshots.get();
            if (path.equals("/status")) {
                reply(ex, 200, "application/json", json.writeValueAsString(s));
            } else {
                reply(ex, 200, "text/plain; version=0.0.4", metrics(s));
            }
        } catch (RuntimeException e) {
            reply(ex, 503, "text/plain", "state unavailable");
        } finally {
            ex.close();
        }
    }

    static String metrics(Snapshot s) {
        StringBuilder sb = new StringBuilder();
        gauge(sb, "trading_kill_switch_active", "1 when the kill switch is on", s.killSwitchActive() ? 1 : 0);
        gauge(sb, "trading_open_orders", "orders submitted or partially filled", s.openOrders());
        gauge(sb, "trading_unknown_orders_stale", "orders of unknown outcome older than a day", s.staleUnknownOrders());
        gauge(sb, "trading_days_stuck", "trading days started and never finished", s.stuckTradingDays());
        gauge(sb, "trading_days_failed_3d", "trading days that failed in the last 3 days", s.failedTradingDays());
        gauge(sb, "trading_data_issues_critical_30d", "critical data issues in the last 30 days", s.criticalIssues30d());
        gauge(sb, "trading_data_age_days", "age in days of the newest clean bar (-1 when no data)",
                s.lastBarDate() == null ? -1 : java.time.temporal.ChronoUnit.DAYS.between(s.lastBarDate(),
                        LocalDate.ofInstant(s.takenAt(), java.time.ZoneOffset.UTC)));
        return sb.toString();
    }

    private static void gauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(" gauge\n").append(name).append(' ').append(value).append('\n');
    }

    private static void reply(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
