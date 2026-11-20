package com.adrianrevilla.trading.application.service;

import com.adrianrevilla.trading.application.read.Snapshot;
import com.adrianrevilla.trading.core.application.port.out.Alerter.Level;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Pure rules over a {@link Snapshot}: what is wrong right now. Each finding has a stable key for de-duplication. */
public final class WatchdogRules {
    /** A weekend plus a holiday plus a missed day: beyond this, data is considered stale. */
    static final int MAX_DATA_AGE_DAYS = 5;
    static final Duration KILL_SWITCH_REMINDER = Duration.ofHours(24);

    public record Finding(String key, Level level, String message) {}

    private WatchdogRules() {}

    public static List<Finding> evaluate(Snapshot s, LocalDate today) {
        List<Finding> out = new ArrayList<>();
        if (s.lastBarDate() == null) {
            out.add(new Finding("no-data", Level.WARNING, "There is no market data in the database yet"));
        } else if (s.lastBarDate().isBefore(today.minusDays(MAX_DATA_AGE_DAYS))) {
            out.add(new Finding("data-stale", Level.CRITICAL, "Market data is stale: newest bar is " + s.lastBarDate()
                    + " (more than " + MAX_DATA_AGE_DAYS + " days old). Ingestion may be failing."));
        }
        if (s.stuckTradingDays() > 0) {
            out.add(new Finding("trading-day-stuck", Level.CRITICAL, s.stuckTradingDays()
                    + " trading day(s) started and never finished. Orders may have gone out: check by hand."));
        }
        if (s.failedTradingDays() > 0) {
            out.add(new Finding("trading-day-failed", Level.CRITICAL,
                    s.failedTradingDays() + " trading day(s) failed in the last 3 days."));
        }
        if (s.staleUnknownOrders() > 0) {
            out.add(new Finding("unknown-orders", Level.CRITICAL, s.staleUnknownOrders()
                    + " order(s) with unknown outcome for more than a day. Reconcile with the broker."));
        }
        if (s.killSwitchActive() && s.killSwitchChangedAt() != null
                && Duration.between(s.killSwitchChangedAt(), s.takenAt()).compareTo(KILL_SWITCH_REMINDER) > 0) {
            out.add(new Finding("kill-switch-long", Level.WARNING, "Kill switch has been active since "
                    + s.killSwitchChangedAt().atZone(ZoneOffset.UTC).toLocalDate() + ": " + s.killSwitchReason()));
        }
        if (s.criticalIssues30d() > 0) {
            out.add(new Finding("data-issues", Level.WARNING,
                    s.criticalIssues30d() + " critical data issue(s) in the last 30 days."));
        }
        return out;
    }
}
