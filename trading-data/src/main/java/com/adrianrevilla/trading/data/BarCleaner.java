package com.adrianrevilla.trading.data;

import com.adrianrevilla.trading.core.Bar;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.time.LocalDate;

/**
 * Builds the clean series from raw bars of one or more sources. Raw data is never modified; the clean series
 * can be regenerated at any time. Sources are given in priority order: the first source with a valid bar wins.
 */
public final class BarCleaner {

    public List<Bar> clean(List<List<Bar>> rawBySourcePriority) {
        Map<LocalDate, Bar> chosen = new TreeMap<>();
        for (List<Bar> source : rawBySourcePriority) {
            for (Bar b : source) {
                if (isValid(b)) {
                    chosen.putIfAbsent(b.date(), b);
                }
            }
        }
        return new ArrayList<>(chosen.values());
    }

    static boolean isValid(Bar b) {
        return b.open().signum() > 0 && b.high().signum() > 0 && b.low().signum() > 0 && b.close().signum() > 0
                && b.high().compareTo(b.low()) >= 0
                && b.high().compareTo(b.open().max(b.close())) >= 0
                && b.low().compareTo(b.open().min(b.close())) <= 0;
    }
}
