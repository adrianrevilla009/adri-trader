package com.adrianrevilla.trading.application.service;

import com.adrianrevilla.trading.application.port.in.ManageMode;
import com.adrianrevilla.trading.application.port.out.ModeAuditLog;
import com.adrianrevilla.trading.application.read.ModeView;
import com.adrianrevilla.trading.application.read.ProdChallengeView;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.domain.Totp;
import com.adrianrevilla.trading.core.domain.TradingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The trading mode of the system (ADR-0030, ADR-0031). The mode lives in memory and starts as DEMO on every boot, so a
 * restart always returns to the safe state. Going to PROD passes every guard, in this order:
 * <ol>
 *   <li>the feature flag is on (off by default) and an MFA secret is configured;</li>
 *   <li>not locked out by earlier failures;</li>
 *   <li>step one issues one challenge (one pending at a time, short life, bound to the actor);</li>
 *   <li>step two needs that challenge, the exact confirmation phrase and a one-time MFA code never used before;</li>
 *   <li>the audit record is written before the mode changes, so an unauditable switch does not happen.</li>
 * </ol>
 * Any failure consumes the challenge, counts towards the lockout and is reported, but the caller only learns "rejected".
 * Methods are synchronized: the state is tiny and a switch must never race another one.
 */
public class ModeService implements ManageMode {
    /** What the person types to confirm. */
    public static final String CONFIRM_PHRASE = "SWITCH TO PROD";
    private static final int CLOCK_WINDOW = 1; // one 30-second step either side

    private record Pending(String id, String actor, Instant expiresAt) {
    }

    private final Alerter alerter;
    private final ModeAuditLog audit;
    private final ProdSwitchPolicy policy;
    private final Clock clock;
    private final Supplier<String> ids;

    private TradingMode mode = TradingMode.DEMO;
    private Pending pending;
    private int failures;
    private Instant lockedUntil;
    private long lastAcceptedStep = -1;

    public ModeService(Alerter alerter, ModeAuditLog audit, ProdSwitchPolicy policy, Clock clock) {
        this(alerter, audit, policy, clock, ModeService::randomId);
    }

    ModeService(Alerter alerter, ModeAuditLog audit, ProdSwitchPolicy policy, Clock clock, Supplier<String> ids) {
        this.alerter = Objects.requireNonNull(alerter, "alerter");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    private static String randomId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public synchronized ModeView current() {
        Instant now = clock.instant();
        boolean locked = lockedUntil != null && now.isBefore(lockedUntil);
        List<String> available = policy.enabled()
                ? List.of(TradingMode.DEMO.name(), TradingMode.PROD.name()) : List.of(TradingMode.DEMO.name());
        return new ModeView(mode.name(), available, policy.enabled(), locked ? lockedUntil.toString() : null,
                CONFIRM_PHRASE);
    }

    @Override
    public synchronized ModeView switchTo(String requested, String actor) {
        requireActor(actor);
        TradingMode target = parse(requested);
        if (target == TradingMode.PROD) {
            refuse(actor, "direct switch to PROD attempted");
            throw new ModeNotAvailableException(
                    "PROD cannot be selected directly: request a challenge and confirm it with the MFA code");
        }
        if (mode == TradingMode.PROD) {
            audit.record(actor, TradingMode.PROD.name(), TradingMode.DEMO.name(), "SWITCHED", "back to DEMO");
            mode = TradingMode.DEMO;
            pending = null;
            alerter.alert(Alerter.Level.WARNING, "Mode switched back to DEMO by " + actor);
        }
        return current();
    }

    @Override
    public synchronized ProdChallengeView requestProdSwitch(String actor) {
        requireActor(actor);
        requireFeature(actor);
        requireNotLocked();
        if (mode == TradingMode.PROD) {
            throw new IllegalArgumentException("the system is already in PROD");
        }
        Instant expires = clock.instant().plus(policy.challengeTtl());
        pending = new Pending(ids.get(), actor, expires);
        audit.record(actor, mode.name(), TradingMode.PROD.name(), "CHALLENGE_ISSUED", "expires " + expires);
        alerter.alert(Alerter.Level.WARNING, "A switch to PROD was requested by " + actor + "; waiting for MFA");
        return new ProdChallengeView(pending.id(), expires.toString(), CONFIRM_PHRASE);
    }

    @Override
    public synchronized ModeView confirmProdSwitch(String challengeId, String code, String phrase, String actor) {
        requireActor(actor);
        requireFeature(actor);
        requireNotLocked();
        Pending challenge = pending;
        pending = null; // one attempt per challenge, right or wrong
        Instant now = clock.instant();
        if (challenge == null) {
            throw reject(actor, "no pending challenge");
        }
        if (!same(challenge.id(), challengeId)) {
            throw reject(actor, "wrong challenge id");
        }
        if (!challenge.actor().equals(actor)) {
            throw reject(actor, "challenge belongs to " + challenge.actor());
        }
        if (!now.isBefore(challenge.expiresAt())) {
            throw reject(actor, "challenge expired");
        }
        if (!same(CONFIRM_PHRASE, phrase)) {
            throw reject(actor, "wrong confirmation phrase");
        }
        long step = Totp.verify(policy.secret(), code, now.getEpochSecond(), CLOCK_WINDOW);
        if (step < 0) {
            throw reject(actor, "wrong MFA code");
        }
        if (step <= lastAcceptedStep) {
            throw reject(actor, "MFA code already used");
        }
        audit.record(actor, mode.name(), TradingMode.PROD.name(), "SWITCHED", "MFA verified"); // if this fails, no switch
        lastAcceptedStep = step;
        failures = 0;
        mode = TradingMode.PROD;
        alerter.alert(Alerter.Level.CRITICAL, "Mode switched to PROD by " + actor
                + ". The daily cycle will not trade until a real broker exists (ADR-0031).");
        return current();
    }

    private void requireFeature(String actor) {
        if (!policy.enabled()) {
            refuse(actor, "feature flag off");
            throw new ModeNotAvailableException(
                    "the switch to PROD is disabled (trading.prod.enabled is off): only DEMO can be used");
        }
    }

    private void requireNotLocked() {
        if (lockedUntil != null) {
            if (clock.instant().isBefore(lockedUntil)) {
                throw new SwitchLockedException(lockedUntil.toString());
            }
            lockedUntil = null;
        }
    }

    private void refuse(String actor, String reason) {
        audit.record(actor, mode.name(), TradingMode.PROD.name(), "REFUSED", reason);
        alerter.alert(Alerter.Level.WARNING, "Switch to PROD refused (" + reason + "): requested by " + actor);
    }

    /** Counts the failure, locks out when there are too many and returns the one error every cause shares. */
    private ConfirmationRejectedException reject(String actor, String reason) {
        failures++;
        audit.record(actor, mode.name(), TradingMode.PROD.name(), "REJECTED", reason);
        alerter.alert(Alerter.Level.WARNING, "Switch to PROD rejected (" + reason + "): " + actor + ", failure "
                + failures + " of " + policy.maxFailures());
        if (failures >= policy.maxFailures()) {
            lockedUntil = clock.instant().plus(policy.lockout());
            failures = 0;
            audit.record(actor, mode.name(), TradingMode.PROD.name(), "LOCKED_OUT", "until " + lockedUntil);
            alerter.alert(Alerter.Level.CRITICAL, "Switch to PROD locked until " + lockedUntil
                    + " after repeated failures (last by " + actor + ")");
        }
        return new ConfirmationRejectedException();
    }

    private static boolean same(String expected, String given) {
        return given != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("the actor is required for the audit trail");
        }
    }

    private static TradingMode parse(String requested) {
        if (requested == null) {
            throw new IllegalArgumentException("mode is required: one of DEMO, PROD");
        }
        try {
            return TradingMode.valueOf(requested);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown mode '" + requested + "': one of DEMO, PROD");
        }
    }
}
