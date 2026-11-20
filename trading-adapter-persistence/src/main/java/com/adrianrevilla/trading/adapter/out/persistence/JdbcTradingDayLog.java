package com.adrianrevilla.trading.adapter.out.persistence;

import com.adrianrevilla.trading.application.port.out.TradingDayLog;
import java.time.LocalDate;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Atomic claim of an agent day: only the first caller gets it. */
public class JdbcTradingDayLog implements TradingDayLog {
    private final JdbcTemplate jdbc;

    public JdbcTradingDayLog(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** @return true if this caller now owns the day; false if the day was already claimed by any earlier run */
    public boolean claim(LocalDate day, String agentId) {
        return jdbc.update("INSERT INTO trading_day(day, agent_id, status) VALUES (?, ?, 'STARTED') "
                + "ON CONFLICT (day, agent_id) DO NOTHING", java.sql.Date.valueOf(day), agentId) == 1;
    }

    public void finish(LocalDate day, String agentId, boolean ok, String detail) {
        jdbc.update("UPDATE trading_day SET status = ?, detail = ?, finished_at = now() WHERE day = ? AND agent_id = ?",
                ok ? "DONE" : "FAILED", detail, java.sql.Date.valueOf(day), agentId);
    }

    public Optional<String> status(LocalDate day, String agentId) {
        return jdbc.queryForList("SELECT status FROM trading_day WHERE day = ? AND agent_id = ?", String.class,
                java.sql.Date.valueOf(day), agentId).stream().findFirst();
    }
}
