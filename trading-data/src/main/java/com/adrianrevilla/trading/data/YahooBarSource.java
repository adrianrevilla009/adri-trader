package com.adrianrevilla.trading.data;

import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Isin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Daily bars from Yahoo Finance (unofficial endpoint, free, ADR-0005). {@code range=max} degrades to monthly data,
 * so history is requested in 4-year chunks. Read-only: only GET requests. Rows with a missing close are dropped.
 * An ISIN needs a ticker mapping because Yahoo does not search by ISIN here.
 */
public class YahooBarSource implements BarSource {
    private static final int CHUNK_YEARS = 4;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final Map<Isin, String> tickers;
    private final int fromYear;
    private final LocalDate today;

    public YahooBarSource(String baseUrl, Map<Isin, String> tickers, int fromYear, LocalDate today) {
        this.baseUrl = baseUrl;
        this.tickers = Map.copyOf(tickers);
        this.fromYear = fromYear;
        this.today = today;
    }

    @Override
    public String name() {
        return "yahoo";
    }

    @Override
    public List<Bar> fetch(Isin isin) throws IOException {
        String ticker = tickers.get(isin);
        if (ticker == null) {
            throw new IOException("No Yahoo ticker configured for " + isin.value());
        }
        TreeMap<LocalDate, Bar> byDate = new TreeMap<>();
        for (int year = fromYear; year <= today.getYear(); year += CHUNK_YEARS) {
            long p1 = LocalDate.of(year, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
            long p2 = LocalDate.of(year + CHUNK_YEARS, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
            parse(get(baseUrl + "/v8/finance/chart/" + ticker + "?period1=" + p1 + "&period2=" + p2
                    + "&interval=1d&events=div,split"), byDate);
        }
        if (byDate.isEmpty()) {
            throw new IOException("Yahoo returned no data for " + ticker);
        }
        return List.copyOf(byDate.values());
    }

    private String get(String url) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Yahoo answered " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private void parse(String body, TreeMap<LocalDate, Bar> out) throws IOException {
        JsonNode result = json.readTree(body).path("chart").path("result");
        if (!result.isArray() || result.isEmpty()) {
            return; // a chunk before the listing exists comes back empty
        }
        JsonNode r = result.get(0);
        JsonNode quote = r.path("indicators").path("quote").path(0);
        JsonNode ts = r.path("timestamp");
        for (int i = 0; i < ts.size(); i++) {
            if (quote.path("close").path(i).isNull() || quote.path("open").path(i).isNull()
                    || quote.path("high").path(i).isNull() || quote.path("low").path(i).isNull()) {
                continue;
            }
            LocalDate date = Instant.ofEpochSecond(ts.get(i).asLong()).atZone(ZoneOffset.UTC).toLocalDate();
            out.put(date, new Bar(date, dec(quote, "open", i), dec(quote, "high", i), dec(quote, "low", i),
                    dec(quote, "close", i), quote.path("volume").path(i).asLong(0)));
        }
    }

    private static BigDecimal dec(JsonNode quote, String field, int i) {
        return BigDecimal.valueOf(quote.path(field).path(i).asDouble()).setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
