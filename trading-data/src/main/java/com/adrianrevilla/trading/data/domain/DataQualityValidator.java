package com.adrianrevilla.trading.data.domain;

import static com.adrianrevilla.trading.data.domain.QualityIssue.Severity.CRITICAL;
import static com.adrianrevilla.trading.data.domain.QualityIssue.Severity.WARNING;

import com.adrianrevilla.trading.core.domain.Bar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Automatic checks run on every ingestion (phase 1 quality rules). */
public final class DataQualityValidator {
    private static final BigDecimal JUMP_LIMIT = new BigDecimal("0.10");
    private static final BigDecimal SOURCE_TOLERANCE = new BigDecimal("0.005");

    private final TradingCalendar calendar;

    public DataQualityValidator(TradingCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * @param bars series as received (any order)
     * @param corporateActionDates dates with a known corporate event, which justify a large jump
     */
    public List<QualityIssue> validate(List<Bar> bars, Set<LocalDate> corporateActionDates) {
        List<QualityIssue> issues = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();
        List<Bar> unique = new ArrayList<>();
        for (Bar b : bars) {
            if (!seen.add(b.date())) {
                issues.add(new QualityIssue(QualityIssue.Type.DUPLICATE, CRITICAL, b.date(), "Duplicate date"));
            } else {
                unique.add(b);
            }
        }
        unique.sort((a, b) -> a.date().compareTo(b.date()));

        for (Bar b : unique) {
            if (b.open().signum() <= 0 || b.high().signum() <= 0 || b.low().signum() <= 0 || b.close().signum() <= 0) {
                issues.add(new QualityIssue(QualityIssue.Type.NON_POSITIVE_PRICE, CRITICAL, b.date(), "Price <= 0"));
            } else if (b.high().compareTo(b.low()) < 0
                    || b.high().compareTo(b.open().max(b.close())) < 0
                    || b.low().compareTo(b.open().min(b.close())) > 0) {
                issues.add(new QualityIssue(QualityIssue.Type.INCONSISTENT_OHLC, CRITICAL, b.date(), "OHLC inconsistent"));
            }
            if (b.volume() == 0) {
                issues.add(new QualityIssue(QualityIssue.Type.ZERO_VOLUME, WARNING, b.date(), "Zero volume"));
            }
        }

        for (int i = 1; i < unique.size(); i++) {
            Bar prev = unique.get(i - 1);
            Bar cur = unique.get(i);
            if (prev.close().signum() > 0 && cur.close().signum() > 0) {
                BigDecimal change = cur.close().subtract(prev.close()).abs().divide(prev.close(), 6, RoundingMode.HALF_UP);
                if (change.compareTo(JUMP_LIMIT) > 0 && !corporateActionDates.contains(cur.date())) {
                    issues.add(new QualityIssue(QualityIssue.Type.PRICE_JUMP, CRITICAL, cur.date(),
                            "Move of " + change.movePointRight(2).setScale(1, RoundingMode.HALF_UP) + "% without corporate event"));
                }
            }
        }

        if (!unique.isEmpty()) {
            Set<LocalDate> have = new HashSet<>();
            unique.forEach(b -> have.add(b.date()));
            for (LocalDate d : calendar.tradingDays(unique.get(0).date(), unique.get(unique.size() - 1).date())) {
                if (!have.contains(d)) {
                    issues.add(new QualityIssue(QualityIssue.Type.GAP, CRITICAL, d, "Missing trading day"));
                }
            }
        }
        return issues;
    }

    /** Compares closes of two sources for the same instrument; differences above 0.5 % are reported. */
    public List<QualityIssue> compareSources(List<Bar> a, List<Bar> b) {
        Map<LocalDate, Bar> byDate = new HashMap<>();
        b.forEach(x -> byDate.put(x.date(), x));
        List<QualityIssue> issues = new ArrayList<>();
        for (Bar x : a) {
            Bar y = byDate.get(x.date());
            if (y == null || x.close().signum() <= 0) {
                continue;
            }
            BigDecimal diff = x.close().subtract(y.close()).abs().divide(x.close(), 6, RoundingMode.HALF_UP);
            if (diff.compareTo(SOURCE_TOLERANCE) > 0) {
                issues.add(new QualityIssue(QualityIssue.Type.SOURCE_MISMATCH, WARNING, x.date(),
                        "Sources differ by " + diff.movePointRight(2).setScale(2, RoundingMode.HALF_UP) + "%"));
            }
        }
        return issues;
    }

    public static boolean hasCritical(List<QualityIssue> issues) {
        return issues.stream().anyMatch(i -> i.severity() == CRITICAL);
    }
}
