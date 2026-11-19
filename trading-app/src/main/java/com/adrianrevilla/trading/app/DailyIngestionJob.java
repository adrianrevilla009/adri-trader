package com.adrianrevilla.trading.app;

import com.adrianrevilla.trading.core.Alerter;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.data.BarSource;
import com.adrianrevilla.trading.data.IngestionService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Daily ingestion after the close of Xetra. Idempotent (upsert), retries with growing wait and alerts when
 * something does not add up. Runs on a single thread (ADR-0012).
 */
public class DailyIngestionJob {
    private static final Logger LOG = LoggerFactory.getLogger(DailyIngestionJob.class);
    private static final int MAX_ATTEMPTS = 3;

    private final IngestionService service;
    private final BarSource source;
    private final List<Isin> universe;
    private final String exchange;
    private final Alerter alerter;
    private final long baseBackoffMillis;

    public DailyIngestionJob(IngestionService service, BarSource source, List<Isin> universe, String exchange,
            Alerter alerter, long baseBackoffMillis) {
        this.service = service;
        this.source = source;
        this.universe = universe;
        this.exchange = exchange;
        this.alerter = alerter;
        this.baseBackoffMillis = baseBackoffMillis;
    }

    /** @return number of instruments ingested without critical issues */
    public int run() {
        int ok = 0;
        for (Isin isin : universe) {
            try {
                var report = withRetry(isin);
                if (report.hasCritical()) {
                    alerter.alert(Alerter.Level.CRITICAL, "Critical data issues for " + isin.value() + ": "
                            + report.issues().stream().filter(i -> i.severity()
                                    == com.adrianrevilla.trading.data.QualityIssue.Severity.CRITICAL).count());
                } else {
                    ok++;
                }
            } catch (Exception e) {
                LOG.error("Ingestion failed for {}", isin.value(), e);
                alerter.alert(Alerter.Level.CRITICAL, "Ingestion failed for " + isin.value() + ": " + e.getMessage());
            }
        }
        alerter.alert(Alerter.Level.INFO, "Ingestion finished: " + ok + "/" + universe.size() + " clean");
        return ok;
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
