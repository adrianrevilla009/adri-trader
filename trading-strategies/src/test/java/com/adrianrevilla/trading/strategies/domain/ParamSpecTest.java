package com.adrianrevilla.trading.strategies.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ParamSpecTest {
    private static final ParamSpec WINDOW = ParamSpec.integer("window", "Moving average days", 200, 20, 400);
    private static final ParamSpec BAND = ParamSpec.decimal("band", "Neutral band", "0.01", "0", "0.1");

    @ParameterizedTest
    @CsvSource({"20,true", "200,true", "400,true", "19,false", "401,false", "200.5,false"})
    void integerParamsAcceptOnlyWholeNumbersInRange(String value, boolean ok) {
        assertThat(WINDOW.accepts(new BigDecimal(value))).isEqualTo(ok);
    }

    @ParameterizedTest
    @CsvSource({"0,true", "0.05,true", "0.1,true", "-0.01,false", "0.11,false"})
    void decimalParamsAcceptAnyNumberInRange(String value, boolean ok) {
        assertThat(BAND.accepts(new BigDecimal(value))).isEqualTo(ok);
    }

    @Test
    void checkExplainsWhatIsWrong() {
        assertThatThrownBy(() -> WINDOW.check(new BigDecimal("500")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window").hasMessageContaining("20").hasMessageContaining("400");
        assertThatThrownBy(() -> WINDOW.check(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefaultMustBeInsideTheRangeAndTheRangeMustBeOrdered() {
        assertThatThrownBy(() -> ParamSpec.integer("w", "d", 5, 20, 400)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParamSpec.integer("w", "d", 30, 400, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParamSpec.integer(" ", "d", 30, 20, 400)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParamSpec.decimal("w", "d", "0.5", "0.6", "0.9"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
