package com.adrianrevilla.trading.core;

import java.util.regex.Pattern;

/** ISIN of an instrument; instruments are identified by ISIN, never by ticker (ADR-0001). */
public record Isin(String value) {
    private static final Pattern FORMAT = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    public Isin {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid ISIN: " + value);
        }
    }
}
