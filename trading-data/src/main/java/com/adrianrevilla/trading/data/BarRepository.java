package com.adrianrevilla.trading.data;

import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Isin;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Plain SQL access to instruments, raw and clean bars and quality issues. */
public class BarRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public BarRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public long ensureInstrument(Isin isin, String exchange, String currency, String distribution) {
        jdbc.update("""
                INSERT INTO instrument(isin, exchange, currency, distribution) VALUES (?,?,?,?)
                ON CONFLICT (isin, exchange) DO NOTHING""", isin.value(), exchange, currency, distribution);
        return findInstrumentId(isin, exchange).orElseThrow();
    }

    public Optional<Long> findInstrumentId(Isin isin, String exchange) {
        return jdbc.query("SELECT id FROM instrument WHERE isin = ? AND exchange = ?",
                (rs, i) -> rs.getLong(1), isin.value(), exchange).stream().findFirst();
    }

    /** Idempotent: re-ingesting the same bars does not duplicate rows. */
    public void upsertRaw(long instrumentId, String source, List<Bar> bars) {
        jdbc.batchUpdate("""
                INSERT INTO bar_daily_raw(instrument_id, bar_date, source, open, high, low, close, volume, available_on)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT (instrument_id, bar_date, source) DO UPDATE SET
                  open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low, close = EXCLUDED.close,
                  volume = EXCLUDED.volume, available_on = EXCLUDED.available_on""",
                bars, 500, (ps, b) -> {
                    ps.setLong(1, instrumentId);
                    ps.setDate(2, Date.valueOf(b.date()));
                    ps.setString(3, source);
                    ps.setBigDecimal(4, b.open());
                    ps.setBigDecimal(5, b.high());
                    ps.setBigDecimal(6, b.low());
                    ps.setBigDecimal(7, b.close());
                    ps.setLong(8, b.volume());
                    ps.setDate(9, Date.valueOf(b.availableOn()));
                });
    }

    public List<Bar> loadRaw(long instrumentId, String source) {
        return jdbc.query("""
                SELECT bar_date, open, high, low, close, volume, available_on FROM bar_daily_raw
                WHERE instrument_id = ? AND source = ? ORDER BY bar_date""", (rs, i) -> bar(rs), instrumentId, source);
    }

    /** Replaces the clean series of the instrument atomically. */
    public void replaceClean(long instrumentId, List<Bar> bars) {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM bar_daily WHERE instrument_id = ?", instrumentId);
            jdbc.batchUpdate("""
                    INSERT INTO bar_daily(instrument_id, bar_date, open, high, low, close, volume, available_on)
                    VALUES (?,?,?,?,?,?,?,?)""", bars, 500, (ps, b) -> {
                ps.setLong(1, instrumentId);
                ps.setDate(2, Date.valueOf(b.date()));
                ps.setBigDecimal(3, b.open());
                ps.setBigDecimal(4, b.high());
                ps.setBigDecimal(5, b.low());
                ps.setBigDecimal(6, b.close());
                ps.setLong(7, b.volume());
                ps.setDate(8, Date.valueOf(b.availableOn()));
            });
        });
    }

    public List<Bar> loadClean(long instrumentId) {
        return jdbc.query("""
                SELECT bar_date, open, high, low, close, volume, available_on FROM bar_daily
                WHERE instrument_id = ? ORDER BY bar_date""", (rs, i) -> bar(rs), instrumentId);
    }

    public Set<LocalDate> corporateActionDates(long instrumentId) {
        return new HashSet<>(jdbc.query("SELECT action_date FROM corporate_action WHERE instrument_id = ?",
                (rs, i) -> rs.getDate(1).toLocalDate(), instrumentId));
    }

    public void saveIssues(long instrumentId, List<QualityIssue> issues) {
        jdbc.batchUpdate("""
                INSERT INTO data_quality_issue(instrument_id, issue_date, type, severity, detail)
                VALUES (?,?,?,?,?) ON CONFLICT (instrument_id, issue_date, type) DO NOTHING""",
                issues, 500, (ps, q) -> {
                    ps.setLong(1, instrumentId);
                    ps.setDate(2, Date.valueOf(q.date()));
                    ps.setString(3, q.type().name());
                    ps.setString(4, q.severity().name());
                    ps.setString(5, q.detail());
                });
    }

    public int openCriticalIssues(long instrumentId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM data_quality_issue
                WHERE instrument_id = ? AND severity = 'CRITICAL' AND status = 'OPEN'""", Integer.class, instrumentId);
        return n == null ? 0 : n;
    }

    public List<Long> allInstrumentIds() {
        return new ArrayList<>(jdbc.query("SELECT id FROM instrument ORDER BY id", (rs, i) -> rs.getLong(1)));
    }

    private static Bar bar(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Bar(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                rs.getBigDecimal(5), rs.getLong(6), rs.getDate(7).toLocalDate());
    }
}
