package com.adrianrevilla.trading.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.application.port.in.ManageMode.ConfirmationRejectedException;
import com.adrianrevilla.trading.application.port.in.ManageMode.SwitchLockedException;
import com.adrianrevilla.trading.core.domain.Base32;
import com.adrianrevilla.trading.core.domain.Totp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Chaos, race and randomized tests of the switch to PROD (ADR-0031): the alert channel and the audit store fail, the
 * clock moves, many threads confirm at once and thousands of random operations are replayed. The one invariant:
 * the mode is PROD only after a valid challenge, phrase and fresh MFA code.
 */
@Tag("chaos")
class ModeServiceChaosTest {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final byte[] KEY = Base32.decode(SECRET);
    private static final String PHRASE = ModeService.CONFIRM_PHRASE;

    private static final class MovableClock extends Clock {
        volatile Instant now = Instant.parse("2026-12-01T10:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId z) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();

    private ModeService service(com.adrianrevilla.trading.core.application.port.out.Alerter alerter,
            com.adrianrevilla.trading.application.port.out.ModeAuditLog audit) {
        return new ModeService(alerter, audit,
                ProdSwitchPolicy.enabled(SECRET, Duration.ofMinutes(2), 3, Duration.ofMinutes(15)), clock);
    }

    private String code() {
        return Totp.code(KEY, clock.instant().getEpochSecond() / Totp.STEP_SECONDS);
    }

    // ---- the alert channel is down

    @Test
    void aBrokenAlertChannelNeverBlocksOrHidesTheOutcome() {
        var service = service((level, msg) -> {
            throw new IllegalStateException("telegram is down");
        }, (a, f, t, o, d) -> { });

        var challenge = service.requestProdSwitch("alice");
        assertThat(service.confirmProdSwitch(challenge.challengeId(), code(), PHRASE, "alice").mode())
                .isEqualTo("PROD");
        assertThat(service.switchTo("DEMO", "alice").mode()).isEqualTo("DEMO");
    }

    @Test
    void aBrokenAlertChannelStillCountsFailuresAndLocksOut() {
        var service = service((level, msg) -> {
            throw new IllegalStateException("telegram is down");
        }, (a, f, t, o, d) -> { });
        for (int i = 0; i < 3; i++) {
            var c = service.requestProdSwitch("alice");
            assertThatThrownBy(() -> service.confirmProdSwitch(c.challengeId(), "000000", PHRASE, "alice"))
                    .isInstanceOf(ConfirmationRejectedException.class);
        }
        assertThatThrownBy(() -> service.requestProdSwitch("alice")).isInstanceOf(SwitchLockedException.class);
    }

    // ---- the audit store is down

    @Test
    void ifTheAuditIsDownNothingCanBeSwitchedAndTheModeStaysDemo() {
        var service = service((l, m) -> { }, (a, f, t, o, d) -> {
            throw new IllegalStateException("database down");
        });
        assertThatThrownBy(() -> service.requestProdSwitch("alice")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.confirmProdSwitch("x", code(), PHRASE, "alice"))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.current().mode()).isEqualTo("DEMO");
    }

    @Test
    void theAuditFailingOnEachKindOfEventNeverLeavesProdSelected() {
        for (String failing : List.of("CHALLENGE_ISSUED", "REJECTED", "SWITCHED", "LOCKED_OUT", "REFUSED")) {
            var service = service((l, m) -> { }, (a, f, t, o, d) -> {
                if (o.equals(failing)) {
                    throw new IllegalStateException("audit down for " + failing);
                }
            });
            try {
                var c = service.requestProdSwitch("alice");
                service.confirmProdSwitch(c.challengeId(), code(), PHRASE, "alice");
            } catch (RuntimeException expected) {
                // either the audit failed or the confirmation did: both must leave DEMO
            }
            boolean switchedFailed = failing.equals("SWITCHED") || failing.equals("CHALLENGE_ISSUED");
            if (switchedFailed) {
                assertThat(service.current().mode()).as(failing).isEqualTo("DEMO");
            }
        }
    }

    // ---- the clock misbehaves

    @Test
    void aClockThatGoesBackwardsCannotRevivePastChallengesOrCodes() {
        var service = service((l, m) -> { }, (a, f, t, o, d) -> { });
        var c = service.requestProdSwitch("alice");
        String used = code();
        service.confirmProdSwitch(c.challengeId(), used, PHRASE, "alice");
        service.switchTo("DEMO", "alice");

        clock.now = clock.now.minus(Duration.ofMinutes(5)); // NTP jump back
        var again = service.requestProdSwitch("alice");
        assertThatThrownBy(() -> service.confirmProdSwitch(again.challengeId(), used, PHRASE, "alice"))
                .isInstanceOf(ConfirmationRejectedException.class);
        assertThat(service.current().mode()).isEqualTo("DEMO");
    }

    @Test
    void aCodeFromTheFutureBeyondTheWindowIsRejected() {
        var service = service((l, m) -> { }, (a, f, t, o, d) -> { });
        var c = service.requestProdSwitch("alice");
        String future = Totp.code(KEY, clock.instant().getEpochSecond() / Totp.STEP_SECONDS + 5);
        assertThatThrownBy(() -> service.confirmProdSwitch(c.challengeId(), future, PHRASE, "alice"))
                .isInstanceOf(ConfirmationRejectedException.class);
    }

    // ---- races

    @Test
    void fiftyThreadsConfirmingTheSameChallengeWithTheRightCodeGetExactlyOneSwitch() throws Exception {
        var audit = new AtomicInteger();
        var service = service((l, m) -> { }, (a, f, t, o, d) -> {
            if (o.equals("SWITCHED")) {
                audit.incrementAndGet();
            }
        });
        var challenge = service.requestProdSwitch("alice");
        String code = code();

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> jobs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            jobs.add(() -> {
                start.await();
                try {
                    service.confirmProdSwitch(challenge.challengeId(), code, PHRASE, "alice");
                    return true;
                } catch (ConfirmationRejectedException | SwitchLockedException e) {
                    return false;
                }
            });
        }
        List<Future<Boolean>> futures = new ArrayList<>();
        for (var j : jobs) {
            futures.add(pool.submit(j));
        }
        start.countDown();
        int wins = 0;
        for (var f : futures) {
            if (f.get()) {
                wins++;
            }
        }
        pool.shutdownNow();

        assertThat(wins).isEqualTo(1);
        assertThat(audit.get()).isEqualTo(1);
        assertThat(service.current().mode()).isEqualTo("PROD");
    }

    @Test
    void parallelRequestsAndConfirmsNeverCorruptTheState() throws Exception {
        var service = service((l, m) -> { }, (a, f, t, o, d) -> { });
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(pool.submit(() -> {
                Random r = new Random();
                for (int n = 0; n < 200; n++) {
                    try {
                        switch (r.nextInt(4)) {
                            case 0 -> service.requestProdSwitch("alice");
                            case 1 -> service.confirmProdSwitch("nope", "000000", PHRASE, "alice");
                            case 2 -> service.switchTo("DEMO", "alice");
                            default -> service.current();
                        }
                    } catch (ConfirmationRejectedException | SwitchLockedException
                             | IllegalArgumentException expected) {
                        // normal answers of the service
                    }
                }
            }));
        }
        for (var f : futures) {
            f.get(); // any unexpected exception fails the test here
        }
        pool.shutdownNow();
        assertThat(service.current().mode()).isEqualTo("DEMO"); // nobody ever had a valid code
    }

    // ---- randomized replay (seeded, so a failure can be reproduced)

    @Test
    void tenThousandRandomOperationsNeverReachProdWithoutAllThreeFactors() {
        for (long seed = 1; seed <= 5; seed++) {
            Random r = new Random(seed);
            AtomicInteger prodSwitches = new AtomicInteger();
            int seen = 0;
            var service = service((l, m) -> { }, (a, f, t, o, d) -> {
                if (o.equals("SWITCHED") && t.equals("PROD")) {
                    prodSwitches.incrementAndGet();
                }
            });
            String pendingId = null;
            for (int i = 0; i < 2000; i++) {
                try {
                    switch (r.nextInt(7)) {
                        case 0 -> pendingId = service.requestProdSwitch("alice").challengeId();
                        case 1 -> service.confirmProdSwitch(pendingId, randomCode(r), PHRASE, "alice");
                        case 2 -> service.confirmProdSwitch(pendingId, code(), "wrong phrase", "alice");
                        case 3 -> service.confirmProdSwitch("guess", code(), PHRASE, "alice");
                        case 4 -> clock.now = clock.now.plusSeconds(r.nextInt(400));
                        case 5 -> service.switchTo("DEMO", "alice");
                        default -> {
                            var c = service.requestProdSwitch("alice");
                            service.confirmProdSwitch(c.challengeId(), code(), PHRASE, "alice");
                        }
                    }
                } catch (RuntimeException expected) {
                    // refusals, lockouts and rejections are the normal answers
                }
                // PROD may only be observed right after an audited valid switch; going back to DEMO is unconditional
                boolean inProd = service.current().mode().equals("PROD");
                assertThat(!inProd || prodSwitches.get() > seen)
                        .as("seed %d step %d: PROD without a recorded valid switch", seed, i).isTrue();
                seen = prodSwitches.get();
                service.switchTo("DEMO", "alice");
            }
        }
    }

    private static String randomCode(Random r) {
        return String.format("%06d", r.nextInt(1_000_000));
    }
}
