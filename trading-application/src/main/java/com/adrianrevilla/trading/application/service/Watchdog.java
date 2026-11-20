package com.adrianrevilla.trading.application.service;

import com.adrianrevilla.trading.application.port.in.CheckSystemHealth;
import com.adrianrevilla.trading.application.read.Snapshot;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks the rules on a schedule. Alerts when a problem appears, stays quiet while it lasts and says so when it
 * clears, so a problem that lasts a week is one alert and one all-clear, not hundreds.
 */
public class Watchdog implements CheckSystemHealth {
    private static final Logger LOG = LoggerFactory.getLogger(Watchdog.class);

    private final Supplier<Snapshot> snapshots;
    private final Alerter alerter;
    private final Clock clock;
    private final Set<String> active = new HashSet<>();

    public Watchdog(Supplier<Snapshot> snapshots, Alerter alerter, Clock clock) {
        this.snapshots = snapshots;
        this.alerter = alerter;
        this.clock = clock;
    }

    /** One check; never throws. A monitoring failure is itself alerted (once). */
    @Override
    public synchronized void check() {
        List<WatchdogRules.Finding> findings;
        try {
            findings = WatchdogRules.evaluate(snapshots.get(), LocalDate.now(clock));
        } catch (RuntimeException e) {
            LOG.error("Watchdog could not read the state", e);
            if (active.add("watchdog-failed")) {
                alerter.alert(Alerter.Level.CRITICAL, "Watchdog cannot read the database: " + e.getMessage());
            }
            return;
        }
        active.remove("watchdog-failed");
        Set<String> now = new HashSet<>();
        for (WatchdogRules.Finding f : findings) {
            now.add(f.key());
            if (active.add(f.key())) {
                alerter.alert(f.level(), f.message());
            }
        }
        Set<String> cleared = new HashSet<>(active);
        cleared.removeAll(now);
        for (String key : cleared) {
            active.remove(key);
            alerter.alert(Alerter.Level.INFO, "Resolved: " + key);
        }
    }
}
