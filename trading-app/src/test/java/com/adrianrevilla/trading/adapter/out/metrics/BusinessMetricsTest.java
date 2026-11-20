package com.adrianrevilla.trading.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.adapter.in.web.StatusServer;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcBarRepository;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcBusinessReadModel;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcMonitoringQueries;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.data.application.port.out.BarRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class BusinessMetricsTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource ds;
    private JdbcTemplate jdbc;
    private PrometheusMeterRegistry registry;
    private BusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway flyway = Flyway.configure().dataSource(ds).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(ds);
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new BusinessMetrics(new JdbcBusinessReadModel(ds), new JdbcMonitoringQueries(ds, Clock.systemUTC()), registry, Clock.systemUTC());
    }

    @Test
    void beforeTheFirstReadTheValuesAreNaNNotAFalseZero() {
        // A fresh process must not report "0 open orders, kill switch off" before it has looked at the database.
        assertThat(registry.scrape()).contains("trading_kill_switch_active NaN");
    }

    @Test
    void anEmptyDatabaseStillPublishesTheOperationalGauges() {
        metrics.refresh();
        String out = registry.scrape();
        assertThat(out).contains("trading_kill_switch_active 0.0").contains("trading_open_orders 0.0")
                .contains("trading_data_age_days -1.0").contains("trading_metrics_refresh_failures_total 0.0");
        assertThat(out).doesNotContain("trading_agent_equity_eur{");
    }

    @Test
    void moneyFlowIsPublishedPerAgent() {
        long inst = new JdbcBarRepository(ds).ensureInstrument(new Isin("IE00B4L5Y983"), "XETRA", "EUR", "ACC");
        jdbc.update("INSERT INTO arena_agent_day(day, agent_id, status, equity, orders_sent) VALUES "
                + "('2025-06-10', 'momentum', 'ARENA', 100, 0), ('2025-06-11', 'momentum', 'ARENA', 120, 1), "
                + "('2025-06-12', 'momentum', 'ARENA', 90, 1)");
        jdbc.update("INSERT INTO cash_ledger(agent_id, kind, amount_eur, occurred_at) VALUES "
                + "('momentum', 'DEPOSIT', 100, now()), ('momentum', 'TRADE', -40, now())");
        jdbc.update("INSERT INTO trade_order(id, agent_id, instrument_id, side, amount_eur, state) VALUES "
                + "('00000000-0000-0000-0000-000000000001', 'momentum', ?, 'BUY', 40, 'FILLED'), "
                + "('00000000-0000-0000-0000-000000000002', 'momentum', ?, 'BUY', 10, 'REJECTED')", inst, inst);
        jdbc.update("INSERT INTO fill(id, order_id, quantity, price, fee_eur, filled_at, amount_eur) VALUES "
                + "(gen_random_uuid(), '00000000-0000-0000-0000-000000000001', 4, 10, 0.5, now(), 40)");

        metrics.refresh();
        String out = registry.scrape();

        assertThat(out).contains("trading_agent_equity_eur{agent=\"momentum\"") // latest day, not the peak
                .contains(" 90.0");
        // peak 120, now 90: a 25 % drawdown
        assertThat(out).containsPattern("trading_agent_drawdown_ratio\\{[^}]*agent=\"momentum\"[^}]*\\} 0\\.25");
        assertThat(out).containsPattern("trading_agent_status\\{[^}]*agent=\"momentum\"[^}]*status=\"ARENA\"[^}]*\\} 1\\.0");
        assertThat(out).containsPattern("trading_cash_eur\\{[^}]*agent=\"momentum\"[^}]*\\} 60\\.0");
        assertThat(out).containsPattern("trading_invested_cost_eur\\{[^}]*agent=\"momentum\"[^}]*\\} 40\\.0");
        assertThat(out).containsPattern("trading_fees_eur\\{[^}]*agent=\"momentum\"[^}]*\\} 0\\.5");
        assertThat(out).containsPattern("trading_fills\\{[^}]*agent=\"momentum\"[^}]*\\} 1\\.0");
        assertThat(out).containsPattern("trading_orders\\{[^}]*agent=\"momentum\"[^}]*state=\"REJECTED\"[^}]*\\} 1\\.0");
    }

    @Test
    void aRetiredAgentDoesNotLeaveItsOldStatusBehind() {
        jdbc.update("INSERT INTO arena_agent_day(day, agent_id, status, equity, orders_sent) VALUES "
                + "('2025-06-10', 'a', 'ARENA', 100, 0)");
        metrics.refresh();
        jdbc.update("INSERT INTO arena_agent_day(day, agent_id, status, equity, orders_sent) VALUES "
                + "('2025-06-11', 'a', 'RETIRED', 70, 0)");
        metrics.refresh();
        String out = registry.scrape();
        assertThat(out).contains("status=\"RETIRED\"").doesNotContain("status=\"ARENA\"");
    }

    @Test
    void whenTheDatabaseFailsTheLastValuesStayAndTheFailureIsCounted() {
        metrics.refresh();
        jdbc.update("UPDATE kill_switch SET active = TRUE WHERE id = 1");
        metrics.refresh();
        assertThat(registry.scrape()).contains("trading_kill_switch_active 1.0");

        jdbc.execute("ALTER TABLE arena_agent_day RENAME TO arena_agent_day_gone");
        try {
            metrics.refresh();
            String out = registry.scrape();
            assertThat(out).contains("trading_metrics_refresh_failures_total 1.0")
                    .contains("trading_kill_switch_active 1.0");
        } finally {
            jdbc.execute("ALTER TABLE arena_agent_day_gone RENAME TO arena_agent_day");
        }
    }

    @Test
    void backupAgeComesFromTheMarkerFileAndSurvivesGarbageCollection() throws Exception {
        java.nio.file.Path marker = java.nio.file.Files.createTempFile("last_success", "");
        java.nio.file.Files.setLastModifiedTime(marker, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minusSeconds(7200)));
        var withBackup = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new BusinessMetrics(new JdbcBusinessReadModel(ds), new JdbcMonitoringQueries(ds, Clock.systemUTC()), withBackup, Clock.systemUTC(), marker);
        marker = null;
        System.gc();
        assertThat(withBackup.scrape()).containsPattern("trading_backup_age_seconds(\\{[^}]*\\})? 7[0-9]{3}\\.0");
        // no marker file yet (first start): NaN, not a fake fresh backup
        var none = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new BusinessMetrics(new JdbcBusinessReadModel(ds), new JdbcMonitoringQueries(ds, Clock.systemUTC()), none, Clock.systemUTC(),
                java.nio.file.Path.of("/nonexistent/last_success"));
        assertThat(none.scrape()).containsPattern("trading_backup_age_seconds(\\{[^}]*\\})? NaN");
    }

    @Test
    void theStatusServerServesTheRegistryOnMetricsAndStaysReadOnly() throws Exception {
        metrics.refresh();
        var server = new StatusServer(() -> new JdbcMonitoringQueries(ds, Clock.systemUTC()).snapshot(), 0,
                "127.0.0.1", registry::scrape);
        server.start();
        try {
            var http = HttpClient.newHttpClient();
            var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/metrics"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).contains("trading_kill_switch_active").contains("trading_metrics_refresh_age_seconds");
            var post = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/metrics"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(post.statusCode()).isEqualTo(404);
        } finally {
            server.stop();
        }
    }
}
