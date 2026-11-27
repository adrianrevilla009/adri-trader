package com.adrianrevilla.trading.strategies.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Describes one numeric parameter of a strategy: name, meaning, default and allowed range. The catalog publishes it so a
 * form can be built from it, and the server checks every value against it (ADR-0030). Java: a record with factories.
 */
public record ParamSpec(String name, String description, BigDecimal defaultValue, BigDecimal min, BigDecimal max,
                        boolean integer) {
    public ParamSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("param name is required");
        }
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + ": min " + min + " is above max " + max);
        }
        if (!accepts(defaultValue, min, max, integer)) {
            throw new IllegalArgumentException(name + ": default " + defaultValue + " is outside " + min + ".." + max);
        }
    }

    public static ParamSpec integer(String name, String description, int defaultValue, int min, int max) {
        return new ParamSpec(name, description, BigDecimal.valueOf(defaultValue), BigDecimal.valueOf(min),
                BigDecimal.valueOf(max), true);
    }

    public static ParamSpec decimal(String name, String description, String defaultValue, String min, String max) {
        return new ParamSpec(name, description, new BigDecimal(defaultValue), new BigDecimal(min), new BigDecimal(max),
                false);
    }

    public boolean accepts(BigDecimal value) {
        return value != null && accepts(value, min, max, integer);
    }

    private static boolean accepts(BigDecimal v, BigDecimal min, BigDecimal max, boolean integer) {
        boolean whole = v.stripTrailingZeros().scale() <= 0;
        return (!integer || whole) && v.compareTo(min) >= 0 && v.compareTo(max) <= 0;
    }

    /** Throws IllegalArgumentException saying what is wrong and which values are allowed. */
    public void check(BigDecimal value) {
        if (!accepts(value)) {
            throw new IllegalArgumentException("param " + name + " = " + value + " is not valid: "
                    + (integer ? "a whole number" : "a number") + " between " + min + " and " + max);
        }
    }
}
