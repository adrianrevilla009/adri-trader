package com.adrianrevilla.trading.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.arena.domain.AgentDay;
import com.adrianrevilla.trading.arena.domain.AgentStatus;
import com.adrianrevilla.trading.arena.domain.DayReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcArenaJournalTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void recordsDaysOverwritesRepeatedOnesAndReadsTheCurve() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        var journal = new JdbcArenaJournal(ds);
        LocalDate d1 = LocalDate.parse("2025-01-02");
        LocalDate d2 = LocalDate.parse("2025-01-03");

        journal.record(new DayReport(d1, List.of(new AgentDay("trend", AgentStatus.ARENA, new BigDecimal("100.00"), 1, null))));
        journal.record(new DayReport(d2, List.of(new AgentDay("trend", AgentStatus.ARENA, new BigDecimal("101.50"), 0, null))));
        journal.record(new DayReport(d2, List.of(new AgentDay("trend", AgentStatus.RETIRED, new BigDecimal("99.00"), 0, "drawdown"))));

        var curve = journal.equity("trend");
        assertThat(curve).hasSize(2);
        assertThat(curve.get(d2)).isEqualByComparingTo("99.00");
        assertThat(journal.lastStatus("trend")).isEqualTo("RETIRED");
        assertThat(journal.lastStatus("nobody")).isNull();
    }
}
