package com.adrianrevilla.trading.app.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.data.BarRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MonitoringRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Isin ISIN = new Isin("IE00B4L5Y983");

    private PGSimpleDataSource ds;
    private JdbcTemplate jdbc;
    private MonitoringRepository repo;

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
        repo = new MonitoringRepository(ds, Clock.systemUTC());
    }

    @Test
    void anEmptyDatabaseGivesAZeroSnapshotWithoutNullPointers() {
        var s = repo.snapshot();
        assertThat(s.killSwitchActive()).isFalse();
        assertThat(s.lastBarDate()).isNull();
        assertThat(s.lastTradingDay()).isNull();
        assertThat(s.openOrders()).isZero();
        assertThat(WatchdogRules.evaluate(s, LocalDate.now())).extracting(WatchdogRules.Finding::key)
                .containsExactly("no-data");
    }

    @Test
    void theSnapshotSeesDataOrdersTradingDaysAndTheKillSwitch() {
        long id = new BarRepository(ds).ensureInstrument(ISIN, "XETRA", "EUR", "ACC");
        BigDecimal p = BigDecimal.TEN;
        new BarRepository(ds).replaceClean(id, List.of(new Bar(LocalDate.parse("2025-06-12"), p, p, p, p, 1)));
        jdbc.update("INSERT INTO trade_order(id, agent_id, instrument_id, side, amount_eur, state, note) "
                + "VALUES (gen_random_uuid(), 'a', ?, 'BUY', 10, 'SUBMITTED', 'UNKNOWN: outcome of submit not known')", id);
        jdbc.update("UPDATE trade_order SET created_at = now() - interval '2 days'");
        jdbc.update("INSERT INTO trading_day(day, agent_id, status, started_at) VALUES "
                + "('2025-06-12', 'a', 'STARTED', now() - interval '3 hours'), "
                + "(current_date, 'b', 'FAILED', now())");
        jdbc.update("UPDATE kill_switch SET active = TRUE, reason = 'x' WHERE id = 1");

        var s = repo.snapshot();
        assertThat(s.lastBarDate()).isEqualTo(LocalDate.parse("2025-06-12"));
        assertThat(s.openOrders()).isEqualTo(1);
        assertThat(s.staleUnknownOrders()).isEqualTo(1);
        assertThat(s.stuckTradingDays()).isEqualTo(1);
        assertThat(s.failedTradingDays()).isEqualTo(1);
        assertThat(s.killSwitchActive()).isTrue();
        assertThat(s.lastTradingDay()).isEqualTo(LocalDate.now());
    }
}
