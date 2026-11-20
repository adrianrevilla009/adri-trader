package com.adrianrevilla.trading.application.read;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Operational state read from the database. No amounts, positions or secrets: safe to show on a status page.
 *
 * @param lastBarDate newest clean bar over all instruments, null when there is none
 * @param lastTradingDay newest day a trading agent ran, null if never
 * @param stuckTradingDays days left in STARTED for more than an hour (a run that did not finish)
 * @param failedTradingDays days that ended FAILED in the last 3 days
 * @param staleUnknownOrders open orders still of unknown outcome after a day
 * @param criticalIssues30d critical data-quality issues dated in the last 30 days
 */
public record Snapshot(Instant takenAt, boolean killSwitchActive, String killSwitchReason, Instant killSwitchChangedAt,
        LocalDate lastBarDate, LocalDate lastTradingDay, LocalDate lastArenaDay, int openOrders, int staleUnknownOrders,
        int stuckTradingDays, int failedTradingDays, int criticalIssues30d) {}
