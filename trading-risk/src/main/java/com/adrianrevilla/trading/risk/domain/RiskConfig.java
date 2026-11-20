package com.adrianrevilla.trading.risk.domain;

import com.adrianrevilla.trading.core.domain.Isin;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Risk limits; they come from configuration (YAML), never from code. Starting values are in ADR-0016 and can
 * only be changed with a new ADR.
 *
 * @param maxWeightPerAsset max share of the agent equity in one instrument (0.25)
 * @param maxExposure max share of equity invested in total (1.0 means no cash buffer is required)
 * @param maxDailyLoss day loss at which new buys are blocked (0.02)
 * @param agentMaxDrawdown fall from peak at which the agent is shut down (0.15)
 * @param maxParticipation max order size as a share of the instrument average daily traded value
 * @param minOrderEur orders smaller than this after trimming are rejected
 * @param fatFingerPct max deviation of a limit price from the last price
 */
public record RiskConfig(BigDecimal maxWeightPerAsset, BigDecimal maxExposure, BigDecimal maxDailyLoss,
        BigDecimal agentMaxDrawdown, BigDecimal maxParticipation, BigDecimal minOrderEur, BigDecimal fatFingerPct,
        Set<Isin> whitelist) {

    public RiskConfig {
        whitelist = Set.copyOf(whitelist);
    }

    public static RiskConfig defaults(Set<Isin> whitelist) {
        return new RiskConfig(new BigDecimal("0.25"), BigDecimal.ONE, new BigDecimal("0.02"),
                new BigDecimal("0.15"), new BigDecimal("0.05"), BigDecimal.ONE, new BigDecimal("0.05"), whitelist);
    }
}
