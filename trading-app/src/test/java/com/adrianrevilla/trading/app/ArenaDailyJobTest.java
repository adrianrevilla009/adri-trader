package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.Alerter;
import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.data.BarRepository;
import com.adrianrevilla.trading.data.DataQualityValidator;
import com.adrianrevilla.trading.data.IngestionService;
import com.adrianrevilla.trading.data.XetraCalendar;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ArenaDailyJobTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B3XXRP09");

    private PGSimpleDataSource ds;
    private BarRepository repo;
    private final List<String> alerts = new ArrayList<>();
    private final Alerter alerter = (level, msg) -> alerts.add(level + ":" + msg);

    @BeforeEach
    void setUp() {
        ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway flyway = Flyway.configure().dataSource(ds).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        repo = new BarRepository(ds);
    }

    private void ingest(Isin isin, double drift) {
        var service = new IngestionService(repo, new DataQualityValidator(new XetraCalendar()));
        List<Bar> bars = new ArrayList<>();
        double p = 100;
        for (LocalDate d : new XetraCalendar().tradingDays(LocalDate.parse("2025-01-02"), LocalDate.parse("2025-12-30"))) {
            p *= 1 + drift + 0.004 * Math.sin(d.getDayOfYear());
            BigDecimal c = BigDecimal.valueOf(p).setScale(4, java.math.RoundingMode.HALF_UP);
            bars.add(new Bar(d, c, c.add(new BigDecimal("0.05")), c.subtract(new BigDecimal("0.05")), c, 1_000_000));
        }
        service.ingest(isin, "XETRA", "csv", bars, List.of("csv"));
    }

    @Test
    void replaysTheDataJournalsTheLastDayAndWritesTheReport(@TempDir Path tmp) throws Exception {
        ingest(A, 0.0004);
        ingest(B, 0.0002);
        var journal = new JdbcArenaJournal(ds);
        var job = new ArenaDailyJob(repo, List.of(A, B), A, "XETRA", new BigDecimal("100"), journal, alerter, tmp);

        var day = job.run();
        var again = job.run(); // re-running the same night is harmless

        assertThat(day).contains(LocalDate.parse("2025-12-30"));
        assertThat(again).isEqualTo(day);
        assertThat(journal.equity("benchmark")).hasSize(1);
        assertThat(journal.lastStatus("trend")).isEqualTo("ARENA");
        String html = Files.readString(tmp.resolve("arena-2025-12-30.html"));
        assertThat(html).contains("benchmark").contains("vol-target").contains("<svg");
        assertThat(alerts).anyMatch(a -> a.startsWith("INFO:Arena 2025-12-30"));
    }

    @Test
    void missingDataSkipsTheDayAndAlerts(@TempDir Path tmp) {
        ingest(A, 0.0004); // B has no data
        var job = new ArenaDailyJob(repo, List.of(A, B), A, "XETRA", new BigDecimal("100"), new JdbcArenaJournal(ds),
                alerter, tmp);
        assertThat(job.run()).isEmpty();
        assertThat(alerts).anyMatch(a -> a.startsWith("CRITICAL:Arena skipped"));
    }
}
