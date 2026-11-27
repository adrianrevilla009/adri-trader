package com.adrianrevilla.trading.application.read;

import java.math.BigDecimal;

/** Limits the screen shows so a person does not have to guess them: they are enforced by the server anyway. */
public record LimitsView(BigDecimal minimumCapital, BigDecimal minOrderAmount, BigDecimal maxWeightPerAsset) {
}
