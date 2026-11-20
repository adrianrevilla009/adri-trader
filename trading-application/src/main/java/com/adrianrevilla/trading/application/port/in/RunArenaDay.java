package com.adrianrevilla.trading.application.port.in;

import java.time.LocalDate;
import java.util.Optional;

/** Input port (use case): replays the arena up to the latest data and records, reports and alerts the last day. */
public interface RunArenaDay {
    /** @return the day that was recorded, empty if there was no data */
    Optional<LocalDate> run();
}
