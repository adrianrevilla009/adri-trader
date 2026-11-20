package com.adrianrevilla.trading.data.application.port.out;

import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.data.domain.QualityIssue;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Output port: where market data, raw and clean, and its quality issues are stored. The domain and the application
 * only know this interface; the SQL lives in the persistence adapter.
 */
public interface BarRepository {
    long ensureInstrument(Isin isin, String exchange, String currency, String distribution);

    Optional<Long> findInstrumentId(Isin isin, String exchange);

    /** Stores the raw bars of one source. Re-storing the same day replaces it. */
    void upsertRaw(long instrumentId, String source, List<Bar> bars);

    List<Bar> loadRaw(long instrumentId, String source);

    /** Replaces the whole clean series of an instrument atomically. */
    void replaceClean(long instrumentId, List<Bar> bars);

    List<Bar> loadClean(long instrumentId);

    Set<LocalDate> corporateActionDates(long instrumentId);

    void saveIssues(long instrumentId, List<QualityIssue> issues);

    int openCriticalIssues(long instrumentId);

    List<Long> allInstrumentIds();
}
