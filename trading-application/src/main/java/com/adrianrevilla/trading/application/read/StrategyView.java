package com.adrianrevilla.trading.application.read;

import java.math.BigDecimal;
import java.util.List;

/** One strategy of the catalog with the description of its parameters, so a form can be built from it. */
public record StrategyView(String id, String name, String description, List<Param> params) {
    public record Param(String name, String description, BigDecimal defaultValue, BigDecimal min, BigDecimal max,
                        boolean integer) {
    }
}
