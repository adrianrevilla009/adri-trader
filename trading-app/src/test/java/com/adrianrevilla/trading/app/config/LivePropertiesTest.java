package com.adrianrevilla.trading.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.adapter.out.brokeretoro.EToroConfig;
import com.adrianrevilla.trading.core.domain.Isin;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The demo cycle must refuse a wrong or dangerous configuration at start, not on the first night. */
class LivePropertiesTest {
    private static <T> T bind(String prefix, Class<T> type, Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props)).bind(prefix, type).get();
    }

    @Test
    void etoroDefaultsAreDemoWithATightCap() {
        var p = bind("trading.etoro", EtoroProperties.class, Map.of("trading.etoro.api-key", "k"));
        assertThat(p.mode()).isEqualTo(EToroConfig.Mode.DEMO);
        assertThat(p.maxOrderAmount()).isEqualByComparingTo("10");
        assertThat(p.instrumentIds()).isEmpty();
        assertThat(p.hasKeys()).isFalse();   // no user key
    }

    @Test
    void realModeIsRefusedAtStart() {
        assertThatThrownBy(() -> bind("trading.etoro", EtoroProperties.class, Map.of("trading.etoro.mode", "REAL")))
                .hasRootCauseMessage(
                        "trading.etoro.mode must be DEMO: real orders are not enabled (ADR-0008 and the real gate)");
    }

    @Test
    void instrumentsParseIsinEqualsIdAndSkipBlanks() {
        var p = bind("trading.etoro", EtoroProperties.class, Map.of("trading.etoro.instruments",
                "IE00B4L5Y983=15348, IE00B5BMR087=3275,"));
        assertThat(p.instrumentIds()).containsEntry(new Isin("IE00B4L5Y983"), 15348L)
                .containsEntry(new Isin("IE00B5BMR087"), 3275L).hasSize(2);
    }

    @Test
    void malformedInstrumentsStopTheStart() {
        for (String bad : new String[] {"IE00B4L5Y983", "IE00B4L5Y983=abc", "NOTANISIN=1"}) {
            assertThatThrownBy(() -> bind("trading.etoro", EtoroProperties.class,
                    Map.of("trading.etoro.instruments", bad))).as(bad).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void etoroRejectsNonPositiveLimitsAndCap() {
        var ok = EToroConfig.Mode.DEMO;
        assertThatThrownBy(() -> new EtoroProperties("u", "k", "u", ok, 0, 20, BigDecimal.TEN, BigDecimal.TEN, List.of()))
                .hasMessageContaining("rate limits");
        assertThatThrownBy(() -> new EtoroProperties("u", "k", "u", ok, 60, 20, BigDecimal.ZERO, BigDecimal.TEN,
                List.of())).hasMessageContaining("max-order-amount");
    }

    @Test
    void minimumOrderDefaultsToEtorosMinimumAndMustFitUnderTheCap() {
        var p = bind("trading.etoro", EtoroProperties.class, Map.of("trading.etoro.api-key", "k"));
        assertThat(p.minOrderAmount()).isEqualByComparingTo("10");
        var ok = EToroConfig.Mode.DEMO;
        assertThatThrownBy(() -> new EtoroProperties("u", "k", "u", ok, 60, 20, BigDecimal.TEN, BigDecimal.ZERO,
                List.of())).hasMessageContaining("min-order-amount");
        assertThatThrownBy(() -> new EtoroProperties("u", "k", "u", ok, 60, 20, BigDecimal.TEN, new BigDecimal("11"),
                List.of())).hasMessageContaining("min-order-amount");
    }

    @Test
    void liveIsOffByDefaultAndNeedsAnIsinWhenOn() {
        var p = bind("trading.live", LiveProperties.class, Map.of("trading.live.enabled", "false"));
        assertThat(p.enabled()).isFalse();
        assertThat(p.capital()).isEqualByComparingTo("40");
        assertThat(p.strategy()).isEqualTo("buy-and-hold");
        assertThatThrownBy(() -> new LiveProperties(true, "a", "buy-and-hold", "", BigDecimal.TEN, ""))
                .hasMessageContaining("isin is required");
    }

    @Test
    void liveRejectsAnUnknownStrategyAndBadValues() {
        assertThatThrownBy(() -> new LiveProperties(false, "a", "magic", "", BigDecimal.TEN, ""))
                .hasMessageContaining("strategy");
        assertThatThrownBy(() -> new LiveProperties(false, "a", "buy-and-hold", "", BigDecimal.ZERO, ""))
                .hasMessageContaining("capital");
        assertThatThrownBy(() -> new LiveProperties(false, " ", "buy-and-hold", "", BigDecimal.TEN, ""))
                .hasMessageContaining("agent-id");
    }

    @Test
    void startupDayIsOptionalAndMustBeADate() {
        assertThat(new LiveProperties(false, "a", "buy-and-hold", "", BigDecimal.TEN, "").startupDay()).isNull();
        assertThat(new LiveProperties(false, "a", "buy-and-hold", "", BigDecimal.TEN, "2026-11-26").startupDay())
                .hasToString("2026-11-26");
        assertThatThrownBy(() -> new LiveProperties(false, "a", "buy-and-hold", "", BigDecimal.TEN, "26/11/2026"))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }
}
