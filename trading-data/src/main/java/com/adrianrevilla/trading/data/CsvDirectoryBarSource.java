package com.adrianrevilla.trading.data;

import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Isin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reads {@code <dir>/<ISIN>.csv}; used for the one-off historical download (ADR-0005). */
public final class CsvDirectoryBarSource implements BarSource {
    private final String name;
    private final Path directory;

    public CsvDirectoryBarSource(String name, Path directory) {
        this.name = name;
        this.directory = directory;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Bar> fetch(Isin isin) throws IOException {
        Path file = directory.resolve(isin.value() + ".csv");
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new CsvBarReader().read(reader);
        }
    }
}
