package com.adrianrevilla.trading.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class RealTradingGateTest {
    @Test
    void closedByDefault() {
        assertThat(RealTradingGate.closed().allows("trend")).isFalse();
        assertThatThrownBy(() -> RealTradingGate.closed().require("trend")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAgentNeedsBothTheGlobalSwitchAndAListing() {
        assertThat(new RealTradingGate(true, Set.of("trend")).allows("trend")).isTrue();
        assertThat(new RealTradingGate(true, Set.of("trend")).allows("swing")).isFalse();
        assertThat(new RealTradingGate(false, Set.of("trend")).allows("trend")).isFalse();
    }
}
