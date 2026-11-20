package com.adrianrevilla.trading.adapter.out.marketdata;

import com.adrianrevilla.trading.core.domain.Bar;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads daily bars from CSV with header {@code Date,Open,High,Low,Close,Volume} (Stooq style, ISO dates).
 * Rows are returned as they come, in file order: duplicates and gaps are for the validator to find.
 */
public final class CsvBarReader {

    public List<Bar> read(Reader reader) throws IOException {
        List<Bar> bars = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(reader)) {
            String header = in.readLine();
            if (header == null || !header.toLowerCase().startsWith("date")) {
                throw new IOException("Missing CSV header");
            }
            String line;
            int row = 1;
            while ((line = in.readLine()) != null) {
                row++;
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",");
                if (f.length < 5) {
                    throw new IOException("Row " + row + ": expected at least 5 fields");
                }
                try {
                    long volume = f.length > 5 && !f[5].isBlank() ? new BigDecimal(f[5].trim()).longValue() : 0L;
                    bars.add(new Bar(
                            LocalDate.parse(f[0].trim()),
                            new BigDecimal(f[1].trim()),
                            new BigDecimal(f[2].trim()),
                            new BigDecimal(f[3].trim()),
                            new BigDecimal(f[4].trim()),
                            volume));
                } catch (RuntimeException e) {
                    throw new IOException("Row " + row + ": " + e.getMessage(), e);
                }
            }
        }
        return bars;
    }
}
