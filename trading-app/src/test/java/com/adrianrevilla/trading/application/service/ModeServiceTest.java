package com.adrianrevilla.trading.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.application.port.in.ManageMode.ModeNotAvailableException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ModeServiceTest {
    private final List<String> alerts = new ArrayList<>();
    private final ModeService service = new ModeService((level, msg) -> alerts.add(level + ":" + msg));

    @Test
    void startsInDemoAndOnlyDemoIsAvailable() {
        var view = service.current();
        assertThat(view.mode()).isEqualTo("DEMO");
        assertThat(view.available()).containsExactly("DEMO");
    }

    @Test
    void switchingToDemoIsANoOp() {
        assertThat(service.switchTo("DEMO", "alice").mode()).isEqualTo("DEMO");
        assertThat(alerts).isEmpty();
    }

    @Test
    void prodIsRefusedWithAClearReasonAndTheAttemptIsReported() {
        assertThatThrownBy(() -> service.switchTo("PROD", "mallory"))
                .isInstanceOf(ModeNotAvailableException.class)
                .hasMessageContaining("PROD").hasMessageContaining("MFA");
        assertThat(service.current().mode()).isEqualTo("DEMO");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0)).startsWith("WARNING").contains("mallory");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "real", "demo", "STAGING"})
    void anythingThatIsNotAModeIsAnError(String mode) {
        assertThatThrownBy(() -> service.switchTo(mode, "alice")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anActorIsRequired() {
        assertThatThrownBy(() -> service.switchTo("DEMO", " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
