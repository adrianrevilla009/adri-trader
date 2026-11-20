package com.adrianrevilla.trading.application.service;

import com.adrianrevilla.trading.application.port.in.IngestMarketData;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.data.application.IngestionService;
import com.adrianrevilla.trading.data.application.port.out.BarSource;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daily ingestion after the close of Xetra. Idempotent (upsert), retries with growing wait and alerts when
 * something does not add up. Runs on a single thread (ADR-0012).
 */
public class DailyIngestionService implements IngestMarketData {
    private static final Logger LOG = LoggerFactory.getLogger(DailyIngestionService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final IngestionService service;
    private final BarSource source;
    private final List<Isin> universe;
    private final String exchange;
    private final Alerter alerter;
    private final long baseBackoffMillis;
    private final java.time.Clock clock;
    private final Integer recentDays;

    public DailyIngestionService(IngestionService service, BarSource source, List<Isin> universe, String exchange,
            Alerter alerter, long baseBackoffMillis) {
        this(service, source, universe, exchange, alerter, baseBackoffMillis, java.time.Clock.systemUTC(), null);
    }

    /**
     * @param recentDays when set, only critical issues dated within that many days alert as CRITICAL; older ones stay in
     *     the database and in the INFO summary, so a known historic hole does not alert every night
     */
    public DailyIngestionService(IngestionService service, BarSource source, List<Isin> universe, String exchange,
            Alerter alerter, long baseBackoffMillis, java.time.Clock clock, Integer recentDays) {
        this.clock = clock;
        this.recentDays = recentDays;
        this.service = service;
        this.source = source;
        this.universe = universe;
        this.exchange = exchange;
        this.alerter = alerter;
        this.baseBackoffMillis = baseBackoffMillis;
    }

    /** @return number of instruments ingested without critical issues */
    @Override
    public int run() {
        int ok = 0;
        for (Isin isin : universe) {
            try {
                var report = withRetry(isin);
                var critical = report.issues().stream()
                        .filter(i -> i.severity() == com.adrianrevilla.trading.data.domain.QualityIssue.Severity.CRITICAL).toList();
                long recent = critical.stream().filter(this::isRecent).count();
                if (recent > 0) {
                    alerter.alert(Alerter.Level.CRITICAL, "Critical data issues for " + isin.value() + ": " + recent);
                } else {
                    ok++;
                    if (!critical.isEmpty()) {
                        LOG.info("{}: {} historic critical issues stored, none recent", isin.value(), critical.size());
                    }
                }
            } catch (Exception e) {
                LOG.error("Ingestion failed for {}", isin.value(), e);
                alerter.alert(Alerter.Level.CRITICAL, "Ingestion failed for " + isin.value() + ": " + e.getMessage());
            }
        }
        alerter.alert(Alerter.Level.INFO, "Ingestion finished: " + ok + "/" + universe.size() + " clean");
        return ok;
    }

    private boolean isRecent(com.adrianrevilla.trading.data.domain.QualityIssue i) {
        return recentDays == null || !i.date().isBefore(java.time.LocalDate.now(clock).minusDays(recentDays));
    }

    private IngestionService.Report withRetry(Isin isin) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return service.ingest(isin, exchange, source.name(), source.fetch(isin), List.of(source.name()));
            } catch (Exception e) {
                last = e;
                LOG.warn("Attempt {}/{} failed for {}: {}", attempt, MAX_ATTEMPTS, isin.value(), e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(baseBackoffMillis * (1L << (attempt - 1)));
                }
            }
        }
        throw last;
    }
}
