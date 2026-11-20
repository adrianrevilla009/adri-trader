package com.adrianrevilla.trading.app.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Market data ingestion (prefix {@code trading.ingestion}). Invalid values fail the start, not the first night's run.
 *
 * @param source {@code csv} or {@code yahoo}
 * @param yahooTickers entries like {@code IE00B4L5Y983=EUNL.DE}
 */
@ConfigurationProperties("trading.ingestion")
public record IngestionProperties(
        boolean enabled,
        @DefaultValue("csv") String source,
        @DefaultValue("") String csvDir,
        @DefaultValue("https://query1.finance.yahoo.com") String yahooBaseUrl,
        @DefaultValue List<String> yahooTickers,
        @DefaultValue("2010") int fromYear,
        boolean runOnStartup,
        @DefaultValue List<String> isins) {

    public IngestionProperties {
        if (!source.equals("csv") && !source.equals("yahoo")) {
            throw new IllegalArgumentException("trading.ingestion.source must be csv or yahoo, was " + source);
        }
        if (fromYear < 2000 || fromYear > 2100) {
            throw new IllegalArgumentException("trading.ingestion.from-year out of range: " + fromYear);
        }
        if (enabled && isins.stream().noneMatch(i -> !i.isBlank())) {
            throw new IllegalArgumentException("trading.ingestion.isins is required when the ingestion is enabled");
        }
        if (enabled && source.equals("csv") && csvDir.isBlank()) {
            throw new IllegalArgumentException("trading.ingestion.csv-dir is required for the csv source");
        }
        isins = isins.stream().map(String::trim).filter(i -> !i.isEmpty()).toList();
    }
}
