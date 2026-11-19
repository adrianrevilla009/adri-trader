package com.adrianrevilla.trading.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryMarketSnapshotTest {
    private static final Isin I = new Isin("IE00B4L5Y983");

    private static Bar bar(String date, String availableOn) {
        BigDecimal p = BigDecimal.TEN;
        return new Bar(LocalDate.parse(date), p, p, p, p, 1, LocalDate.parse(availableOn));
    }

    @Test
    void neverExposesBarsAfterAsOf() {
        var series = Map.of(I, List.of(bar("2025-06-02", "2025-06-02"), bar("2025-06-03", "2025-06-03"),
                bar("2025-06-04", "2025-06-04")));
        var snap = new InMemoryMarketSnapshot(LocalDate.parse("2025-06-03"), series);
        assertThat(snap.bars(I)).hasSize(2);
        assertThat(snap.latest(I).orElseThrow().date()).isEqualTo(LocalDate.parse("2025-06-03"));
    }

    @Test
    void hidesBarsNotYetAvailable() {
        var series = Map.of(I, List.of(bar("2025-06-02", "2025-06-02"), bar("2025-06-03", "2025-06-05")));
        var snap = new InMemoryMarketSnapshot(LocalDate.parse("2025-06-03"), series);
        assertThat(snap.bars(I)).hasSize(1);
    }
}
