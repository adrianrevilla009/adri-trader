package com.adrianrevilla.trading.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TargetPortfolioTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B3XXRP09");

    @Test
    void acceptsWeightsSummingToAtMostOne() {
        var p = new TargetPortfolio(Map.of(A, new BigDecimal("0.6"), B, new BigDecimal("0.4")));
        assertThat(p.weights()).hasSize(2);
    }

    @Test
    void rejectsWeightsAboveOne() {
        assertThatThrownBy(() -> new TargetPortfolio(Map.of(A, new BigDecimal("0.6"), B, new BigDecimal("0.5"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeWeight() {
        assertThatThrownBy(() -> new TargetPortfolio(Map.of(A, new BigDecimal("-0.1"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isinMustHaveValidFormat() {
        assertThatThrownBy(() -> new Isin("spy")).isInstanceOf(IllegalArgumentException.class);
    }
}
