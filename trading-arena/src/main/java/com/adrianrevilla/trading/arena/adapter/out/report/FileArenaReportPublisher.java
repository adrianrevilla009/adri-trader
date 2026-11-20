package com.adrianrevilla.trading.arena.adapter.out.report;

import com.adrianrevilla.trading.arena.application.Arena;
import com.adrianrevilla.trading.arena.application.port.out.ArenaReportPublisher;
import com.adrianrevilla.trading.arena.domain.DayReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the report of each arena day as {@code arena-yyyy-MM-dd.html} in a directory. */
public final class FileArenaReportPublisher implements ArenaReportPublisher {
    private final Path dir;

    public FileArenaReportPublisher(Path dir) {
        this.dir = dir;
    }

    @Override
    public void publish(Arena arena, DayReport day) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("arena-" + day.day() + ".html"), ArenaHtmlReport.render(arena, day));
    }
}
