package com.adrianrevilla.trading.data.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;

/**
 * Xetra calendar: weekdays except New Year, Good Friday, Easter Monday, 1 May, 24, 25, 26 and 31 December.
 * Based on the published Xetra holiday pattern; not verified against the official calendar of every year
 * (unverified) and must be cross-checked in phase 1 against the loaded data.
 */
public final class XetraCalendar implements TradingCalendar {

    @Override
    public boolean isTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        int day = date.getDayOfMonth();
        Month month = date.getMonth();
        if (month == Month.JANUARY && day == 1) {
            return false;
        }
        if (month == Month.MAY && day == 1) {
            return false;
        }
        if (month == Month.DECEMBER && (day == 24 || day == 25 || day == 26 || day == 31)) {
            return false;
        }
        LocalDate easter = easterSunday(date.getYear());
        return !date.equals(easter.minusDays(2)) && !date.equals(easter.plusDays(1));
    }

    /** Anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
