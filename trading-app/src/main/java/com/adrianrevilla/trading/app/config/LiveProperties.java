package com.adrianrevilla.trading.app.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The daily live cycle against the eToro demo account (prefix {@code trading.live}, ADR-0028). Off by default.
 *
 * @param isin optional: the instrument of the first agent, created only while no agent exists (ADR-0030); it must also
 *     have an eToro id in {@code trading.etoro.instruments}
 * @param capital capital of that first agent; the demo account holds far more, each agent sees only its own budget
 */
@ConfigurationProperties("trading.live")
public record LiveProperties(
        boolean enabled,
        @DefaultValue("live-demo") String agentId,
        @DefaultValue("buy-and-hold") String strategy,
        @DefaultValue("") String isin,
        @DefaultValue("40") BigDecimal capital,
        @DefaultValue("") String runOnStartupDay) {

    public LiveProperties {
        if (!runOnStartupDay.isBlank()) {
            LocalDate.parse(runOnStartupDay); // fails the start with a clear message if it is not yyyy-MM-dd
        }
        if (capital.signum() <= 0) {
            throw new IllegalArgumentException("trading.live.capital must be positive");
        }
        if (agentId.isBlank()) {
            throw new IllegalArgumentException("trading.live.agent-id must not be blank");
        }
        if (!strategy.equals("buy-and-hold")) {
            throw new IllegalArgumentException("trading.live.strategy must be buy-and-hold, was " + strategy);
        }
    }

    /** A day to run once at start (yyyy-MM-dd), for an end-to-end check outside the schedule; null when not set. */
    public LocalDate startupDay() {
        return runOnStartupDay.isBlank() ? null : LocalDate.parse(runOnStartupDay);
    }
}
