package com.adrianrevilla.trading.data.domain;

import java.time.LocalDate;

public record QualityIssue(Type type, Severity severity, LocalDate date, String detail) {

    public enum Type {
        GAP, NON_POSITIVE_PRICE, INCONSISTENT_OHLC, PRICE_JUMP, ZERO_VOLUME, DUPLICATE, SOURCE_MISMATCH
    }

    public enum Severity { WARNING, CRITICAL }
}
