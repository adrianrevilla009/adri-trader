package com.adrianrevilla.trading.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcModeAuditLogTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private JdbcModeAuditLog log;

    @BeforeEach
    void setUp() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        log = new JdbcModeAuditLog(ds);
    }

    @Test
    void recordsEveryEventInOrder() {
        log.record("api:ui", "DEMO", "PROD", "CHALLENGE_ISSUED", "expires soon");
        log.record("api:ui", "DEMO", "PROD", "SWITCHED", "MFA verified");

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM mode_audit ORDER BY id");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("outcome", "CHALLENGE_ISSUED").containsEntry("actor", "api:ui");
        assertThat(rows.get(1)).containsEntry("outcome", "SWITCHED").containsEntry("to_mode", "PROD");
        assertThat(rows.get(1).get("at")).isNotNull();
    }

    @Test
    void theTableIsAppendOnly() {
        log.record("a", "DEMO", "PROD", "REFUSED", "flag off");
        assertThatThrownBy(() -> jdbc.update("UPDATE mode_audit SET actor = 'x'")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM mode_audit")).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mode_audit", Integer.class)).isEqualTo(1);
    }

    @Test
    void anUnknownOutcomeOrModeIsRefusedByTheDatabase() {
        assertThatThrownBy(() -> log.record("a", "DEMO", "PROD", "WHATEVER", "x")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> log.record("a", "DEMO", "REAL", "REFUSED", "x")).isInstanceOf(DataAccessException.class);
    }
}
