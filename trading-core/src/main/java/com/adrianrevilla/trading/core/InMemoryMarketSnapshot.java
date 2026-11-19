package com.adrianrevilla.trading.core;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot over full series held in memory that exposes only data up to {@code asOf}.
 * Series must be sorted by date ascending.
 */
public final class InMemoryMarketSnapshot implements MarketSnapshot {
    private final LocalDate asOf;
    private final Map<Isin, List<Bar>> series;

    public InMemoryMarketSnapshot(LocalDate asOf, Map<Isin, List<Bar>> series) {
        this.asOf = asOf;
        this.series = series;
    }

    @Override
    public LocalDate asOf() {
        return asOf;
    }

    @Override
    public Set<Isin> instruments() {
        return Collections.unmodifiableSet(series.keySet());
    }

    @Override
    public List<Bar> bars(Isin isin) {
        List<Bar> all = series.getOrDefault(isin, List.of());
        int lo = 0;
        int hi = all.size();
        while (lo < hi) { // first index with date > asOf
            int mid = (lo + hi) >>> 1;
            if (all.get(mid).date().isAfter(asOf)) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        List<Bar> visible = all.subList(0, lo);
        // availableOn can only delay a bar; trim trailing bars not yet available
        int end = visible.size();
        while (end > 0 && visible.get(end - 1).availableOn().isAfter(asOf)) {
            end--;
        }
        return Collections.unmodifiableList(visible.subList(0, end));
    }
}
