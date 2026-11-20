package com.adrianrevilla.trading.adapter.out.persistence;

import com.adrianrevilla.trading.application.port.out.ArenaJournal;
import com.adrianrevilla.trading.arena.domain.DayReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists each arena day. Recording the same day twice overwrites it, so re-running a day is safe. */
public class JdbcArenaJournal implements ArenaJournal {
    private final JdbcTemplate jdbc;

    public JdbcArenaJournal(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void record(DayReport report) {
        for (var a : report.agents()) {
            jdbc.update("""
                    INSERT INTO arena_agent_day (day, agent_id, status, equity, orders_sent, error)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (day, agent_id) DO UPDATE SET status = EXCLUDED.status, equity = EXCLUDED.equity,
                        orders_sent = EXCLUDED.orders_sent, error = EXCLUDED.error, recorded_at = now()
                    """, java.sql.Date.valueOf(report.day()), a.agentId(), a.status().name(), a.equity(),
                    a.ordersSent(), a.error());
        }
    }

    /** Equity curve of one agent, ordered by day. */
    public Map<LocalDate, BigDecimal> equity(String agentId) {
        Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
        jdbc.query("SELECT day, equity FROM arena_agent_day WHERE agent_id = ? ORDER BY day", rs -> {
            out.put(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2));
        }, agentId);
        return out;
    }

    public String lastStatus(String agentId) {
        var l = jdbc.queryForList("SELECT status FROM arena_agent_day WHERE agent_id = ? ORDER BY day DESC LIMIT 1",
                String.class, agentId);
        return l.isEmpty() ? null : l.get(0);
    }
}
