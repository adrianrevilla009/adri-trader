package com.adrianrevilla.trading.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The schema as a whole: migrations apply cleanly, are numbered without gaps, and the safety rules live in the database. */
@Testcontainers
class MigrationsTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void fresh() {
        ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
    }

    @Test
    void theMigrationFilesAreNumberedOneToNWithoutGapsOrDuplicates() throws IOException {
        Path dir = Path.of("src", "main", "resources", "db", "migration");
        try (Stream<Path> files = Files.list(dir)) {
            List<Integer> numbers = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("V[0-9]+__[a-z0-9_]+\\.sql")).map(n -> Integer.parseInt(n.substring(1, n.indexOf("__"))))
                    .sorted().toList();
            assertThat(numbers).isNotEmpty();
            for (int i = 0; i < numbers.size(); i++) {
                assertThat(numbers.get(i)).as("migration #%d", i + 1).isEqualTo(i + 1);
            }
        }
    }

    @Test
    void everyMigrationAppliesFromAnEmptyDatabaseAndValidates() {
        var flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        assertThat(flyway.info().pending()).isEmpty();
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero(); // running it again changes nothing
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class))
                .isZero();
    }

    @Test
    void theAuditTablesRefuseUpdatesAndDeletes() {
        jdbc.update("INSERT INTO agent_config_audit (agent_id, changed_by, field) VALUES ('a', 'x', 'created')");
        jdbc.update("INSERT INTO mode_audit (actor, from_mode, to_mode, outcome, detail) "
                + "VALUES ('x', 'DEMO', 'PROD', 'REFUSED', 'flag off')");
        for (String sql : List.of("UPDATE agent_config_audit SET id = id + 100", "DELETE FROM agent_config_audit",
                "TRUNCATE agent_config_audit", "UPDATE mode_audit SET id = id + 100", "DELETE FROM mode_audit",
                "TRUNCATE mode_audit")) {
            assertThatThrownBy(() -> jdbc.update(sql)).as(sql).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mode_audit", Integer.class)).isEqualTo(1);
    }

    @Test
    void theMoneyLedgerCannotBeEmptiedEitherNotEvenWithTruncate() {
        for (String sql : List.of("TRUNCATE fill", "TRUNCATE cash_ledger", "TRUNCATE trade_order",
                "TRUNCATE fill, cash_ledger, trade_order CASCADE")) {
            // trade_order is refused by its foreign keys before the trigger runs: either way it cannot be emptied
            assertThatThrownBy(() -> jdbc.update(sql)).as(sql).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void theDatabaseRefusesWhatTheDomainRefuses() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO agent (id, strategy_id, capital, mode, state) VALUES ('a', 't', 40, 'REAL', 'ACTIVE')"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO agent (id, strategy_id, capital, mode, state) VALUES ('a', 't', 0, 'DEMO', 'ACTIVE')"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO agent (id, strategy_id, capital, mode, state) VALUES ('a', 't', 40, 'DEMO', 'PAUSED')"))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("INSERT INTO agent (id, strategy_id, capital, mode, state) VALUES ('a', 't', 40, 'DEMO', 'ACTIVE')");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO agent (id, strategy_id, capital, mode, state) VALUES ('a', 't', 40, 'DEMO', 'ACTIVE')"))
                .isInstanceOf(DataAccessException.class); // primary key
        assertThatThrownBy(() -> jdbc.update("INSERT INTO agent_step (agent_id, position, after_days, capital) "
                + "VALUES ('a', 1, 0, 50)")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO agent_param (agent_id, name, value) VALUES ('ghost', 'p', 1)"))
                .isInstanceOf(DataAccessException.class); // foreign key
    }

    @Test
    void deletingAnAgentRemovesItsChildrenButNeverItsAuditTrail() {
        jdbc.update("INSERT INTO agent (id, strategy_id, capital, mode, state) VALUES ('a', 't', 40, 'DEMO', 'ACTIVE')");
        jdbc.update("INSERT INTO agent_param (agent_id, name, value) VALUES ('a', 'p', 1)");
        jdbc.update("INSERT INTO agent_config_audit (agent_id, changed_by, field) VALUES ('a', 'x', 'created')");
        jdbc.update("DELETE FROM agent WHERE id = 'a'");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_param", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_config_audit", Integer.class)).isEqualTo(1);
    }
}
