package com.adrianrevilla.trading.adapter.out.persistence;

import com.adrianrevilla.trading.core.domain.KillSwitch;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Kill switch kept in the database so it survives restarts and can be flipped from outside the process. */
public class JdbcKillSwitch implements KillSwitch {
    private final JdbcTemplate jdbc;

    public JdbcKillSwitch(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public boolean isActive() {
        Boolean active = jdbc.queryForObject("SELECT active FROM kill_switch WHERE id = 1", Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    @Override
    public void activate(String reason) {
        jdbc.update("UPDATE kill_switch SET active = TRUE, reason = ?, changed_at = now() WHERE id = 1", reason);
    }

    @Override
    public void deactivate() {
        jdbc.update("UPDATE kill_switch SET active = FALSE, reason = NULL, changed_at = now() WHERE id = 1");
    }

    public String reason() {
        return jdbc.queryForObject("SELECT reason FROM kill_switch WHERE id = 1", String.class);
    }
}
