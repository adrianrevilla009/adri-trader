package com.adrianrevilla.trading.execution.application;

import com.adrianrevilla.trading.core.domain.Isin;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares the broker positions with the internal ledger. It reports; it never fixes anything by itself. */
public final class Reconciler {
    public record Difference(String what, BigDecimal expected, BigDecimal actual) {}

    public record Report(List<Difference> differences) {
        public boolean ok() {
            return differences.isEmpty();
        }
    }

    private final BigDecimal unitTolerance;

    public Reconciler(BigDecimal unitTolerance) {
        this.unitTolerance = unitTolerance;
    }

    public Report compare(Map<Isin, BigDecimal> ledger, Map<Isin, BigDecimal> broker) {
        Set<Isin> all = new HashSet<>(ledger.keySet());
        all.addAll(broker.keySet());
        List<Difference> diffs = new ArrayList<>();
        for (Isin isin : all) {
            BigDecimal expected = ledger.getOrDefault(isin, BigDecimal.ZERO);
            BigDecimal actual = broker.getOrDefault(isin, BigDecimal.ZERO);
            if (expected.subtract(actual).abs().compareTo(unitTolerance) > 0) {
                diffs.add(new Difference(isin.value(), expected, actual));
            }
        }
        return new Report(diffs);
    }
}
