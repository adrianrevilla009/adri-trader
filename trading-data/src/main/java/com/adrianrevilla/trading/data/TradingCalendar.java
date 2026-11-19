package com.adrianrevilla.trading.data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Trading days of one exchange. */
public interface TradingCalendar {
    boolean isTradingDay(LocalDate date);

    default List<LocalDate> tradingDays(LocalDate fromInclusive, LocalDate toInclusive) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = fromInclusive; !d.isAfter(toInclusive); d = d.plusDays(1)) {
            if (isTradingDay(d)) {
                days.add(d);
            }
        }
        return days;
    }

    /** Next trading day strictly after {@code date}. */
    default LocalDate nextTradingDay(LocalDate date) {
        LocalDate d = date.plusDays(1);
        while (!isTradingDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }
}
