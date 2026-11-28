package com.adrianrevilla.trading.adapter.out.persistence;

import com.adrianrevilla.trading.application.port.out.ModeAuditLog;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC adapter for {@link ModeAuditLog} (ADR-0031): one append-only row per event; the table refuses updates and deletes. */
public class JdbcModeAuditLog implements ModeAuditLog {
    private final JdbcTemplate jdbc;

    public JdbcModeAuditLog(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void record(String actor, String fromMode, String toMode, String outcome, String detail) {
        jdbc.update("""
                INSERT INTO mode_audit (actor, from_mode, to_mode, outcome, detail) VALUES (?, ?, ?, ?, ?)
                """, actor, fromMode, toMode, outcome, detail);
    }
}
