package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MigrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate migrated() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway flyway = Flyway.configure().dataSource(ds).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        return new JdbcTemplate(ds);
    }

    @Test
    void instrumentIsUniqueByIsinAndExchange() {
        JdbcTemplate jdbc = migrated();
        String sql = "INSERT INTO instrument(isin, exchange, currency, distribution) VALUES ('IE00B4L5Y983','XETRA','EUR','ACC')";
        jdbc.update(sql);
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void cashLedgerIsAppendOnly() {
        JdbcTemplate jdbc = migrated();
        jdbc.update("INSERT INTO cash_ledger(agent_id, kind, amount_eur, occurred_at) VALUES ('a','DEPOSIT',100,now())");
        assertThatThrownBy(() -> jdbc.update("UPDATE cash_ledger SET amount_eur = 1")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM cash_ledger")).hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cash_ledger", Integer.class)).isEqualTo(1);
    }
}
