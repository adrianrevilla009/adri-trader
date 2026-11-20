package com.adrianrevilla.trading.app.monitoring;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the operational {@link Snapshot} with a handful of cheap queries. */
public class MonitoringRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MonitoringRepository(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.clock = clock;
    }

    public Snapshot snapshot() {
        var ks = jdbc.queryForMap("SELECT active, reason, changed_at FROM kill_switch WHERE id = 1");
        return new Snapshot(clock.instant(), (Boolean) ks.get("active"), (String) ks.get("reason"),
                ((java.sql.Timestamp) ks.get("changed_at")).toInstant(),
                date("SELECT max(bar_date) FROM bar_daily"),
                date("SELECT max(day) FROM trading_day"),
                date("SELECT max(day) FROM arena_agent_day"),
                count("SELECT count(*) FROM trade_order WHERE state IN ('SUBMITTED','PARTIALLY_FILLED')"),
                count("SELECT count(*) FROM trade_order WHERE state = 'SUBMITTED' AND note LIKE 'UNKNOWN%' "
                        + "AND created_at < now() - interval '1 day'"),
                count("SELECT count(*) FROM trading_day WHERE status = 'STARTED' AND started_at < now() - interval '1 hour'"),
                count("SELECT count(*) FROM trading_day WHERE status = 'FAILED' AND day >= current_date - 3"),
                count("SELECT count(*) FROM data_quality_issue WHERE severity = 'CRITICAL' "
                        + "AND issue_date >= current_date - 30"));
    }

    private LocalDate date(String sql) {
        Date d = jdbc.queryForObject(sql, Date.class);
        return d == null ? null : d.toLocalDate();
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
