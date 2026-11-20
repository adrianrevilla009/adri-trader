package com.adrianrevilla.trading.arena.application.port.out;

import com.adrianrevilla.trading.arena.application.Arena;
import com.adrianrevilla.trading.arena.domain.DayReport;
import java.io.IOException;

/** Output port: publishes the report of an arena day (today an HTML file; could be anything else). */
public interface ArenaReportPublisher {
    void publish(Arena arena, DayReport day) throws IOException;
}
