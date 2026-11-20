package com.adrianrevilla.trading.app.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The nightly arena (prefix {@code trading.arena}).
 *
 * @param startDate first day the agents trade; empty means "from the start of the data"
 */
@ConfigurationProperties("trading.arena")
public record ArenaProperties(
        boolean enabled,
        @DefaultValue("") String benchmark,
        @DefaultValue("100") BigDecimal capital,
        @DefaultValue("reports/arena") String reportDir,
        @DefaultValue("") String startDate) {

    public ArenaProperties {
        if (capital.signum() <= 0) {
            throw new IllegalArgumentException("trading.arena.capital must be positive");
        }
        if (enabled && benchmark.isBlank()) {
            throw new IllegalArgumentException("trading.arena.benchmark is required when the arena is enabled");
        }
        if (!startDate.isBlank()) {
            LocalDate.parse(startDate); // fails the start with a clear message if it is not yyyy-MM-dd
        }
    }

    public LocalDate start() {
        return startDate.isBlank() ? null : LocalDate.parse(startDate);
    }
}
