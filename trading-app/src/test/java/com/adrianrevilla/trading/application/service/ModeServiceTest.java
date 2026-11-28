package com.adrianrevilla.trading.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.application.port.in.ManageMode.ConfirmationRejectedException;
import com.adrianrevilla.trading.application.port.in.ManageMode.ModeNotAvailableException;
import com.adrianrevilla.trading.application.port.in.ManageMode.SwitchLockedException;
import com.adrianrevilla.trading.application.port.out.ModeAuditLog;
import com.adrianrevilla.trading.core.domain.Base32;
import com.adrianrevilla.trading.core.domain.Totp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ModeServiceTest {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final byte[] KEY = Base32.decode(SECRET);
    private static final String PHRASE = ModeService.CONFIRM_PHRASE;

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-12-01T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final TestClock clock = new TestClock();
    private final List<String> alerts = new ArrayList<>();
    private final List<String> audit = new ArrayList<>();
    private final AtomicInteger idCounter = new AtomicInteger();
    private boolean auditFails;

    private final ModeAuditLog log = (actor, from, to, outcome, detail) -> {
        if (auditFails && outcome.equals("SWITCHED")) {
            throw new IllegalStateException("audit store is down");
        }
        audit.add(outcome + ":" + actor + ":" + from + ">" + to + ":" + detail);
    };

    private ModeService service(ProdSwitchPolicy policy) {
        return new ModeService((level, msg) -> alerts.add(level + ":" + msg), log, policy, clock,
                () -> "id-" + idCounter.incrementAndGet());
    }

    private ModeService enabled() {
        return service(ProdSwitchPolicy.enabled(SECRET, Duration.ofMinutes(2), 3, Duration.ofMinutes(15)));
    }

    private String codeNow() {
        return Totp.code(KEY, clock.instant().getEpochSecond() / Totp.STEP_SECONDS);
    }

    private List<String> outcomes() {
        return audit.stream().map(a -> a.substring(0, a.indexOf(':'))).toList();
    }

    // ---- guard 1: the feature flag

    @Test
    void startsInDemoWithTheFeatureOffAndOnlyDemoAvailable() {
        var view = service(ProdSwitchPolicy.disabled()).current();
        assertThat(view.mode()).isEqualTo("DEMO");
        assertThat(view.available()).containsExactly("DEMO");
        assertThat(view.prodEnabled()).isFalse();
        assertThat(view.lockedUntil()).isNull();
        assertThat(view.confirmPhrase()).isEqualTo(PHRASE);
    }

    @Test
    void withTheFlagOffNothingToDoWithProdWorksAndEveryAttemptIsAuditedAndReported() {
        var service = service(ProdSwitchPolicy.disabled());
        assertThatThrownBy(() -> service.requestProdSwitch("mallory")).isInstanceOf(ModeNotAvailableException.class)
                .hasMessageContaining("disabled");
        assertThatThrownBy(() -> service.confirmProdSwitch("id-1", codeNow(), PHRASE, "mallory"))
                .isInstanceOf(ModeNotAvailableException.class);
        assertThat(service.current().mode()).isEqualTo("DEMO");
        assertThat(outcomes()).containsExactly("REFUSED", "REFUSED");
        assertThat(alerts).hasSize(2).allMatch(a -> a.startsWith("WARNING") && a.contains("mallory"));
    }

    @Test
    void theFlagOnWithoutAUsableSecretCannotBeBuilt() {
        assertThatThrownBy(() -> ProdSwitchPolicy.enabled("", Duration.ofMinutes(2), 3, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProdSwitchPolicy.enabled("ABCDEFGH", Duration.ofMinutes(2), 3, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too short");
        assertThatThrownBy(() -> ProdSwitchPolicy.enabled("not base32!", Duration.ofMinutes(2), 3,
                Duration.ofMinutes(15))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyLimitsAreChecked() {
        assertThatThrownBy(() -> ProdSwitchPolicy.enabled(SECRET, Duration.ofSeconds(5), 3, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProdSwitchPolicy.enabled(SECRET, Duration.ofHours(1), 3, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProdSwitchPolicy.enabled(SECRET, Duration.ofMinutes(2), 0, Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProdSwitchPolicy.enabled(SECRET, Duration.ofMinutes(2), 3, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withTheFlagOnProdIsAvailableButStillNotSelectedAndNeverDirectly() {
        var service = enabled();
        assertThat(service.current().available()).containsExactly("DEMO", "PROD");
        assertThat(service.current().mode()).isEqualTo("DEMO");
        assertThatThrownBy(() -> service.switchTo("PROD", "alice")).isInstanceOf(ModeNotAvailableException.class)
                .hasMessageContaining("challenge");
        assertThat(service.current().mode()).isEqualTo("DEMO");
        assertThat(outcomes()).containsExactly("REFUSED");
    }

    // ---- the two steps

    @Test
    void allGuardsPassedSwitchesToProdAndEverythingIsAuditedAndReported() {
        var service = enabled();
        var challenge = service.requestProdSwitch("alice");
        assertThat(challenge.challengeId()).isEqualTo("id-1");
        assertThat(challenge.confirmPhrase()).isEqualTo(PHRASE);
        assertThat(Instant.parse(challenge.expiresAt())).isEqualTo(clock.instant().plus(Duration.ofMinutes(2)));

        var view = service.confirmProdSwitch("id-1", codeNow(), PHRASE, "alice");

        assertThat(view.mode()).isEqualTo("PROD");
        assertThat(service.current().mode()).isEqualTo("PROD");
        assertThat(outcomes()).containsExactly("CHALLENGE_ISSUED", "SWITCHED");
        assertThat(audit.toString()).doesNotContain(codeNow()); // the code is never written down
        assertThat(alerts.get(alerts.size() - 1)).startsWith("CRITICAL").contains("PROD");
    }

    @Test
    void theChallengeIsOneUseEvenWhenTheAttemptWasRight() {
        var service = enabled();
        service.requestProdSwitch("alice");
        service.confirmProdSwitch("id-1", codeNow(), PHRASE, "alice");
        service.switchTo("DEMO", "alice");
        assertThatThrownBy(() -> service.confirmProdSwitch("id-1", codeNow(), PHRASE, "alice"))
                .isInstanceOf(ConfirmationRejectedException.class);
        assertThat(service.current().mode()).isEqualTo("DEMO");
    }

    @Test
    void aCodeCannotBeReusedEvenWithAFreshChallenge() {
        var service = enabled();
        service.requestProdSwitch("alice");
        String code = codeNow();
        service.confirmProdSwitch("id-1", code, PHRASE, "alice");
        service.switchTo("DEMO", "alice");

        service.requestProdSwitch("alice");
        assertThatThrownBy(() -> service.confirmProdSwitch("id-2", code, PHRASE, "alice"))
                .isInstanceOf(ConfirmationRejectedException.class);
        assertThat(audit.toString()).contains("MFA code already used");

        clock.advance(Duration.ofSeconds(31)); // the next code is a different one
        service.requestProdSwitch("alice");
        assertThat(service.confirmProdSwitch("id-3", codeNow(), PHRASE, "alice").mode()).isEqualTo("PROD");
    }

    @Test
    void everyKindOfWrongFactorGetsTheSameAnswerAndLeavesDemo() {
        var service = enabled();
        var messages = new java.util.HashSet<String>();
        String[][] wrong = {{"id-1", "000000", PHRASE}, {"id-2", null, "switch to prod"},
                {"other", null, PHRASE}};
        for (String[] w : wrong) {
            service.requestProdSwitch("alice");
            String code = w[1] != null ? w[1] : codeNow();
            try {
                service.confirmProdSwitch(w[0], code, w[2], "alice");
            } catch (ConfirmationRejectedException e) {
                messages.add(e.getMessage());
            }
            clock.advance(Duration.ofMinutes(16)); // let any lockout pass
        }
        assertThat(messages).hasSize(1); // the caller cannot tell which factor was wrong
        assertThat(service.current().mode()).isEqualTo("DEMO");
    }

    @Test
    void aChallengeForAnotherActorIsRejected() {
        var service = enabled();
        service.requestProdSwitch("alice");
        assertThatThrownBy(() -> service.confirmProdSwitch("id-1", codeNow(), PHRASE, "bob"))
                .isInstanceOf(ConfirmationRejectedException.class);
        assertThat(service.current().mode()).isEqualTo("DEMO");
        assertThat(audit.toString()).contains("belongs to alice");
    }

    @Test
    void anExpiredChallengeIsRejected() {
        var service = enabled();
        service.requestProdSwitch("alice");
        clock.advance(Duration.ofMinutes(2));
        assertThatThrownBy(() -> service.confirmProdSwitch("id-1", codeNow(), PHRASE, "alice"))
                .isInstanceOf(ConfirmationRejectedException.class);
        assertThat(audit.toString()).contains("challenge expired");
    }

    @Test
    void confirmingWithoutAChallengeIsRejected() {
        assertThatThrownBy(() -> enabled().confirmProdSwitch("id-1", codeNow(), PHRASE, "alice"))
                .isInstanceOf(ConfirmationRejectedException.class);
        assertThat(audit.toString()).contains("no pending challenge");
    }

    @Test
    void aWrongAttemptConsumesTheChallengeSoTheRightCodeAfterwardsFails() {
        var service = enabled();
        service.requestProdSwitch("alice");
        assertThatThrownBy(() -> service.confirmProdSwitch("id-1", "123456", PHRASE, "alice"))
                .isInstanceOf(ConfirmationRejectedException.class);
        assertThatThrownBy(() -> service.confirmProdSwitch("id-1", codeNow(), PHRASE, "alice"))
                .isInstanceOf(ConfirmationRejectedException.class);
        assertThat(service.current().mode()).isEqualTo("DEMO");
    }

    @Test
    void aNewRequestReplacesThePreviousChallenge() {
        var service = enabled();
        service.requestProdSwitch("alice");
        service.requestProdSwitch("alice");
        assertThatThrownBy(() -> service.confirmProdSwitch("id-1", codeNow(), PHRASE, "alice"))
                .isInstanceOf(ConfirmationRejectedException.class);
    }

    @Test
    void whenAlreadyInProdAnotherRequestIsAnError() {
        var service = enabled();
        service.requestProdSwitch("alice");
        service.confirmProdSwitch("id-1", codeNow(), PHRASE, "alice");
        assertThatThrownBy(() -> service.requestProdSwitch("alice")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ifTheAuditCannotBeWrittenTheSwitchDoesNotHappen() {
        var service = enabled();
        service.requestProdSwitch("alice");
        auditFails = true;
        assertThatThrownBy(() -> service.confirmProdSwitch("id-1", codeNow(), PHRASE, "alice"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.current().mode()).isEqualTo("DEMO");
    }

    // ---- lockout

    @Test
    void threeFailuresLockTheSwitchForTheLockoutTimeAndAlertCritically() {
        var service = enabled();
        for (int i = 1; i <= 3; i++) {
            service.requestProdSwitch("alice");
            String id = "id-" + i;
            assertThatThrownBy(() -> service.confirmProdSwitch(id, "000000", PHRASE, "alice"))
                    .isInstanceOf(ConfirmationRejectedException.class);
        }
        assertThat(outcomes()).contains("LOCKED_OUT");
        assertThat(service.current().lockedUntil()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)).toString());
        assertThat(alerts.get(alerts.size() - 1)).startsWith("CRITICAL").contains("locked");

        assertThatThrownBy(() -> service.requestProdSwitch("alice")).isInstanceOf(SwitchLockedException.class);
        assertThatThrownBy(() -> service.confirmProdSwitch("id-3", codeNow(), PHRASE, "alice"))
                .isInstanceOf(SwitchLockedException.class);

        clock.advance(Duration.ofMinutes(15));
        assertThat(service.current().lockedUntil()).isNull();
        service.requestProdSwitch("alice");
        assertThat(service.confirmProdSwitch("id-4", codeNow(), PHRASE, "alice").mode()).isEqualTo("PROD");
    }

    @Test
    void aSuccessResetsTheFailureCounter() {
        var service = enabled();
        for (int i = 1; i <= 2; i++) {
            service.requestProdSwitch("alice");
            String id = "id-" + i;
            assertThatThrownBy(() -> service.confirmProdSwitch(id, "000000", PHRASE, "alice"))
                    .isInstanceOf(ConfirmationRejectedException.class);
        }
        service.requestProdSwitch("alice");
        service.confirmProdSwitch("id-3", codeNow(), PHRASE, "alice");
        service.switchTo("DEMO", "alice");
        clock.advance(Duration.ofSeconds(31));
        for (int i = 4; i <= 5; i++) {
            service.requestProdSwitch("alice");
            String id = "id-" + i;
            assertThatThrownBy(() -> service.confirmProdSwitch(id, "000000", PHRASE, "alice"))
                    .isInstanceOf(ConfirmationRejectedException.class);
        }
        assertThat(outcomes()).doesNotContain("LOCKED_OUT");
    }

    // ---- back to DEMO and inputs

    @Test
    void goingBackToDemoNeedsNoMfaButIsAuditedAndReported() {
        var service = enabled();
        service.requestProdSwitch("alice");
        service.confirmProdSwitch("id-1", codeNow(), PHRASE, "alice");
        assertThat(service.switchTo("DEMO", "bob").mode()).isEqualTo("DEMO");
        assertThat(audit.get(audit.size() - 1)).startsWith("SWITCHED:bob:PROD>DEMO");
        assertThat(alerts.get(alerts.size() - 1)).startsWith("WARNING").contains("bob");
    }

    @Test
    void switchingToDemoWhileInDemoIsANoOp() {
        var service = enabled();
        assertThat(service.switchTo("DEMO", "alice").mode()).isEqualTo("DEMO");
        assertThat(audit).isEmpty();
        assertThat(alerts).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "real", "demo", "STAGING"})
    void anythingThatIsNotAModeIsAnError(String mode) {
        assertThatThrownBy(() -> enabled().switchTo(mode, "alice")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anActorIsRequiredForEveryOperation() {
        var service = enabled();
        assertThatThrownBy(() -> service.switchTo("DEMO", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.requestProdSwitch(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.confirmProdSwitch("x", "1", PHRASE, "")).isInstanceOf(
                IllegalArgumentException.class);
    }

    @Test
    void nullFactorsAreRejectedNotCrashed() {
        var service = enabled();
        service.requestProdSwitch("alice");
        assertThatThrownBy(() -> service.confirmProdSwitch(null, null, null, "alice"))
                .isInstanceOf(ConfirmationRejectedException.class);
    }
}
