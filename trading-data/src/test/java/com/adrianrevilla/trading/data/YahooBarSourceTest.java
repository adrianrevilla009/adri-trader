package com.adrianrevilla.trading.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.core.Isin;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class YahooBarSourceTest {
    private static final Isin ISIN = new Isin("IE00B4L5Y983");
    private final List<String> requests = new ArrayList<>();
    private final List<String> methods = new ArrayList<>();
    private HttpServer server;
    private YahooBarSource source;

    // 2020-01-02 and 2020-01-03 at 08:00 UTC; the second row has a null close and must be dropped; the third repeats day 1
    private static final String CHUNK = "{\"chart\":{\"result\":[{\"timestamp\":[1577952000,1578038400,1577952000],"
            + "\"indicators\":{\"quote\":[{\"open\":[10.0,11.0,10.5],\"high\":[10.5,11.5,10.6],\"low\":[9.5,10.5,10.4],"
            + "\"close\":[10.2,null,10.55],\"volume\":[1000,2000,3000]}]}}],\"error\":null}}";
    private static final String EMPTY = "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(ex.getRequestURI().toString());
            methods.add(ex.getRequestMethod());
            boolean firstChunk = ex.getRequestURI().getRawQuery().contains("period1=1577836800"); // 2020-01-01
            byte[] body = (firstChunk ? CHUNK : EMPTY).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        source = new YahooBarSource("http://127.0.0.1:" + server.getAddress().getPort(), Map.of(ISIN, "EUNL.DE"),
                2020, LocalDate.parse("2026-11-20"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void requestsFourYearChunksWithGetOnlyAndParsesTheBars() throws Exception {
        var bars = source.fetch(ISIN);
        assertThat(requests).hasSize(2).allSatisfy(r -> assertThat(r).contains("/v8/finance/chart/EUNL.DE"));
        assertThat(methods).containsOnly("GET");
        assertThat(bars).hasSize(1); // null close dropped, duplicate date collapsed
        assertThat(bars.get(0).date()).isEqualTo(LocalDate.parse("2020-01-02"));
        assertThat(bars.get(0).close()).isEqualByComparingTo("10.55"); // the later row wins
    }

    @Test
    void anUnmappedIsinOrAnEmptyAnswerFails() {
        assertThatThrownBy(() -> source.fetch(new Isin("IE00B5BMR087"))).isInstanceOf(IOException.class)
                .hasMessageContaining("No Yahoo ticker");
        var nothing = new YahooBarSource("http://127.0.0.1:" + server.getAddress().getPort(), Map.of(ISIN, "X"), 2000,
                LocalDate.parse("2005-01-01"));
        assertThatThrownBy(() -> nothing.fetch(ISIN)).isInstanceOf(IOException.class).hasMessageContaining("no data");
    }
}
