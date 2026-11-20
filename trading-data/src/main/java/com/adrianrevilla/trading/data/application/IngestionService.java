package com.adrianrevilla.trading.data.application;

import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.data.application.port.out.BarRepository;
import com.adrianrevilla.trading.data.domain.BarCleaner;
import com.adrianrevilla.trading.data.domain.DataQualityValidator;
import com.adrianrevilla.trading.data.domain.QualityIssue;
import java.util.ArrayList;
import java.util.List;

/**
 * Ingests bars for one instrument: stores raw (upsert), regenerates the clean series from raw, validates and
 * records issues. Idempotent: running it twice with the same data leaves the same state.
 */
public class IngestionService {
    private final BarRepository repository;
    private final DataQualityValidator validator;
    private final BarCleaner cleaner = new BarCleaner();

    public IngestionService(BarRepository repository, DataQualityValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    public record Report(Isin isin, int rawBars, int cleanBars, List<QualityIssue> issues) {
        public boolean hasCritical() {
            return DataQualityValidator.hasCritical(issues);
        }
    }

    /**
     * @param sourcePriority names of the sources in priority order; {@code sourceName} must be one of them
     */
    public Report ingest(Isin isin, String exchange, String sourceName, List<Bar> fetched, List<String> sourcePriority) {
        long id = repository.ensureInstrument(isin, exchange, "EUR", "ACC");
        List<QualityIssue> issues = new ArrayList<>(
                validator.validate(fetched, repository.corporateActionDates(id)));
        repository.upsertRaw(id, sourceName, fetched);

        List<List<Bar>> raws = new ArrayList<>();
        for (String s : sourcePriority) {
            raws.add(repository.loadRaw(id, s));
        }
        List<Bar> clean = cleaner.clean(raws);
        repository.replaceClean(id, clean);

        // gaps are only meaningful on the clean series (invalid bars were dropped)
        for (QualityIssue q : validator.validate(clean, repository.corporateActionDates(id))) {
            if (issues.stream().noneMatch(x -> x.type() == q.type() && x.date().equals(q.date()))) {
                issues.add(q);
            }
        }
        if (sourcePriority.size() > 1) {
            List<Bar> primary = raws.get(0);
            for (int i = 1; i < raws.size(); i++) {
                issues.addAll(validator.compareSources(primary, raws.get(i)));
            }
        }
        repository.saveIssues(id, issues);
        return new Report(isin, fetched.size(), clean.size(), issues);
    }
}
