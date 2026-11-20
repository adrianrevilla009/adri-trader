package com.adrianrevilla.trading.application.port.out;

import com.adrianrevilla.trading.application.read.Snapshot;

/** Output port (read side): the operational state of the system as one {@link Snapshot}. */
public interface MonitoringQueries {
    Snapshot snapshot();
}
