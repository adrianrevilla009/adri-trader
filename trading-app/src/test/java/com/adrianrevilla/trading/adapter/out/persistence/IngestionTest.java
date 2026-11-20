package com.adrianrevilla.trading.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.application.service.DailyIngestionService;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.data.application.IngestionService;
import com.adrianrevilla.trading.data.application.port.out.BarRepository;
import com.adrianrevilla.trading.data.application.port.out.BarSource;
import com.adrianrevilla.trading.data.domain.DataQualityValidator;
import com.adrianrevilla.trading.data.domain.XetraCalendar;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class IngestionTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Isin ISIN = new Isin("IE00B4L5Y983");

    private PGSimpleDataSource ds;
    private BarRepository repo;
    private IngestionService service;

    @BeforeEach
    void setUp() {
        ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway flyway = Flyway.configure().dataSource(ds).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        repo = new JdbcBarRepository(ds);
        service = new IngestionService(repo, new DataQualityValidator(new XetraCalendar()));
    }

    private static List<Bar> tradingBars(String from, String to) {
        List<Bar> bars = new ArrayList<>();
        for (LocalDate d : new XetraCalendar().tradingDays(LocalDate.parse(from), LocalDate.parse(to))) {
            BigDecimal p = BigDecimal.valueOf(100 + d.getDayOfMonth() % 3);
            bars.add(new Bar(d, p, p, p, p, 1000));
        }
        return bars;
    }

    @Test
    void ingestionIsIdempotentAndKeepsRawSeparateFromClean() {
        var bars = tradingBars("2025-06-02", "2025-06-30");
        var first = service.ingest(ISIN, "XETRA", "csv", bars, List.of("csv"));
        var second = service.ingest(ISIN, "XETRA", "csv", bars, List.of("csv"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertThat(first.hasCritical()).isFalse();
        assertThat(second.cleanBars()).isEqualTo(bars.size());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bar_daily_raw", Integer.class)).isEqualTo(bars.size());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bar_daily", Integer.class)).isEqualTo(bars.size());
    }

    @Test
    void invalidRawBarIsKeptRawButExcludedFromCleanAndRecorded() {
        var bars = tradingBars("2025-06-02", "2025-06-13");
        Bar bad = new Bar(bars.get(3).date(), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, 1);
        bars.set(3, bad);
        var report = service.ingest(ISIN, "XETRA", "csv", bars, List.of("csv"));
        long id = repo.findInstrumentId(ISIN, "XETRA").orElseThrow();
        assertThat(report.hasCritical()).isTrue();
        assertThat(repo.loadRaw(id, "csv")).hasSize(bars.size());
        assertThat(repo.loadClean(id)).hasSize(bars.size() - 1);
        assertThat(repo.openCriticalIssues(id)).isGreaterThanOrEqualTo(2); // non-positive price + gap
    }

    @Test
    void dailyJobRetriesTransientFailuresAndAlertsOnPersistentOnes() {
        AtomicInteger calls = new AtomicInteger();
        List<String> alerts = new ArrayList<>();
        Alerter alerter = (level, msg) -> alerts.add(level + ":" + msg);
        var good = tradingBars("2025-06-02", "2025-06-13");
        BarSource flaky = new BarSource() {
            public String name() { return "csv"; }
            public List<Bar> fetch(Isin isin) throws IOException {
                if (calls.incrementAndGet() < 3) {
                    throw new IOException("temporary");
                }
                return good;
            }
        };
        var job = new DailyIngestionService(service, flaky, List.of(ISIN), "XETRA", alerter, 1);
        assertThat(job.run()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(3);

        BarSource broken = new BarSource() {
            public String name() { return "csv"; }
            public List<Bar> fetch(Isin isin) throws IOException { throw new IOException("down"); }
        };
        alerts.clear();
        assertThat(new DailyIngestionService(service, broken, List.of(ISIN), "XETRA", alerter, 1).run()).isZero();
        assertThat(alerts).anyMatch(a -> a.startsWith("CRITICAL:Ingestion failed"));
    }
}
