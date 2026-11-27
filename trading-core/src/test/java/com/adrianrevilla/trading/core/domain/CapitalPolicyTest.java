package com.adrianrevilla.trading.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CapitalPolicyTest {
    private final CapitalPolicy policy = new CapitalPolicy(new BigDecimal("10"), new BigDecimal("0.25"));

    @Test
    void minimumCapitalMakesTheMaxWeightReachTheMinimumOrder() {
        assertThat(policy.minimumCapital()).isEqualByComparingTo("40");
    }

    @ParameterizedTest
    @CsvSource({"40,true", "40.01,true", "39.99,false", "10,false", "100,true"})
    void acceptsCapitalOnlyFromTheMinimum(String capital, boolean ok) {
        assertThat(policy.accepts(new BigDecimal(capital))).isEqualTo(ok);
    }

    @Test
    void rejectsNullCapital() {
        assertThatThrownBy(() -> policy.accepts(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @CsvSource({"0,0.25", "-1,0.25", "10,0", "10,-0.1", "10,1.01"})
    void rejectsNonsensePolicies(String minOrder, String maxWeight) {
        assertThatThrownBy(() -> new CapitalPolicy(new BigDecimal(minOrder), new BigDecimal(maxWeight)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundsTheMinimumUpSoTheWeightNeverFallsShort() {
        var p = new CapitalPolicy(new BigDecimal("10"), new BigDecimal("0.3"));
        assertThat(p.minimumCapital()).isEqualByComparingTo("33.34");
        assertThat(p.accepts(new BigDecimal("33.33"))).isFalse();
    }
}
