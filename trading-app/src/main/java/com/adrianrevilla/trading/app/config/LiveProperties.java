package com.adrianrevilla.trading.app.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The daily live cycle against the eToro demo account (prefix {@code trading.live}, ADR-0028). Off by default.
 *
 * @param isin the instrument the agent holds (it must also have an eToro id in {@code trading.etoro.instruments})
 * @param capital what the agent may use; the demo account holds far more, the cash it sees is capped to this
 */
@ConfigurationProperties("trading.live")
public record LiveProperties(
        boolean enabled,
        @DefaultValue("live-demo") String agentId,
        @DefaultValue("buy-and-hold") String strategy,
        @DefaultValue("") String isin,
        @DefaultValue("40") BigDecimal capital) {

    public LiveProperties {
        if (capital.signum() <= 0) {
            throw new IllegalArgumentException("trading.live.capital must be positive");
        }
        if (agentId.isBlank()) {
            throw new IllegalArgumentException("trading.live.agent-id must not be blank");
        }
        if (!strategy.equals("buy-and-hold")) {
            throw new IllegalArgumentException("trading.live.strategy must be buy-and-hold, was " + strategy);
        }
        if (enabled && isin.isBlank()) {
            throw new IllegalArgumentException("trading.live.isin is required when the live cycle is enabled");
        }
    }
}
