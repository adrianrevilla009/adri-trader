package com.adrianrevilla.trading.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Wrong configuration must stop the start, not break the first night's run (factory standard 04-spring-platform). */
class ConfigurationPropertiesTest {

    private static <T> T bind(String prefix, Class<T> type, java.util.Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props)).bind(prefix, type).get();
    }

    @Test
    void ingestionBindsDefaultsAndAListFromACommaSeparatedValue() {
        var p = bind("trading.ingestion", IngestionProperties.class, java.util.Map.of(
                "trading.ingestion.enabled", "true", "trading.ingestion.source", "yahoo",
                "trading.ingestion.isins", "IE00B4L5Y983, IE00B5BMR087"));
        assertThat(p.isins()).containsExactly("IE00B4L5Y983", "IE00B5BMR087");
        assertThat(p.fromYear()).isEqualTo(2010);
        assertThat(p.yahooBaseUrl()).isEqualTo("https://query1.finance.yahoo.com");
        assertThat(p.runOnStartup()).isFalse();
    }

    @Test
    void ingestionRejectsWhatCannotWork() {
        assertThatThrownBy(() -> bind("trading.ingestion", IngestionProperties.class,
                java.util.Map.of("trading.ingestion.source", "ftp")))
                .hasRootCauseMessage("trading.ingestion.source must be csv or yahoo, was ftp");
        assertThatThrownBy(() -> new IngestionProperties(true, "yahoo", "", "u", List.of(), 2010, false, List.of()))
                .hasMessageContaining("isins is required");
        assertThatThrownBy(() -> new IngestionProperties(true, "csv", "", "u", List.of(), 2010, false, List.of("X")))
                .hasMessageContaining("csv-dir is required");
        assertThatThrownBy(() -> new IngestionProperties(false, "csv", "", "u", List.of(), 1990, false, List.of()))
                .hasMessageContaining("from-year");
    }

    @Test
    void arenaNeedsABenchmarkOnlyWhenEnabledAndAValidDate() {
        assertThat(new ArenaProperties(false, "", new BigDecimal("100"), "r", "").start()).isNull();
        assertThat(new ArenaProperties(true, "IE00B4L5Y983", new BigDecimal("100"), "r", "2026-01-05").start())
                .hasToString("2026-01-05");
        assertThatThrownBy(() -> new ArenaProperties(true, "", new BigDecimal("100"), "r", ""))
                .hasMessageContaining("benchmark is required");
        assertThatThrownBy(() -> new ArenaProperties(false, "", new BigDecimal("100"), "r", "05/01/2026"))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
        assertThatThrownBy(() -> new ArenaProperties(false, "", BigDecimal.ZERO, "r", ""))
                .hasMessageContaining("capital must be positive");
    }

    @Test
    void telegramNeedsTokenAndChatTogether() {
        assertThat(new TelegramProperties("", "", "u", 5000).configured()).isFalse();
        assertThat(new TelegramProperties("123:abc", "42", "u", 5000).configured()).isTrue();
        assertThatThrownBy(() -> new TelegramProperties("123:abc", "", "u", 5000))
                .hasMessageContaining("both bot-token and chat-id");
    }

    @Test
    void theKillSwitchEndpointNeedsALongTokenAndIsOffWithoutOne() {
        assertThat(new KillSwitchProperties("", 8099).enabled()).isFalse();
        assertThat(new KillSwitchProperties("0123456789abcdef", 8099).enabled()).isTrue();
        assertThatThrownBy(() -> new KillSwitchProperties("short", 8099)).hasMessageContaining("at least 16");
        assertThatThrownBy(() -> new KillSwitchProperties("", 70000)).hasMessageContaining("out of range");
    }

    @Test
    void monitoringDefaultsAreLoopbackAndAStatusPage() {
        var p = bind("trading.monitoring", MonitoringProperties.class, java.util.Map.of("trading.monitoring.port", "8098"));
        assertThat(p.bindAddress()).isEqualTo("127.0.0.1");
        assertThat(p.statusPageEnabled()).isTrue();
        assertThat(new MonitoringProperties(-1, "127.0.0.1", "", true).statusPageEnabled()).isFalse();
    }
}
