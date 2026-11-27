package com.adrianrevilla.trading.application.service;

import com.adrianrevilla.trading.application.port.in.ManageMode;
import com.adrianrevilla.trading.application.read.ModeView;
import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.domain.TradingMode;
import java.util.List;
import java.util.Objects;

/**
 * The trading mode of the system (ADR-0030). Only DEMO can be selected: PROD needs MFA and its own ADR (phase 2), so
 * every attempt is refused with a clear reason and reported. The mode is not stored: the broker connection is DEMO by
 * construction (trading.etoro.mode accepts only DEMO), and this keeps the two from ever disagreeing.
 */
public class ModeService implements ManageMode {
    private static final List<String> AVAILABLE = List.of(TradingMode.DEMO.name());

    private final Alerter alerter;

    public ModeService(Alerter alerter) {
        this.alerter = Objects.requireNonNull(alerter, "alerter");
    }

    @Override
    public ModeView current() {
        return new ModeView(TradingMode.DEMO.name(), AVAILABLE);
    }

    @Override
    public ModeView switchTo(String mode, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("the actor is required for the audit trail");
        }
        TradingMode target = parse(mode);
        if (target == TradingMode.PROD) {
            alerter.alert(Alerter.Level.WARNING, "Switch to PROD refused (not available): requested by " + actor);
            throw new ModeNotAvailableException(
                    "PROD is not available yet: it needs MFA and its own ADR (phase 2). Only DEMO can be selected.");
        }
        return current();
    }

    private static TradingMode parse(String mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode is required: one of DEMO, PROD");
        }
        try {
            return TradingMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown mode '" + mode + "': one of DEMO, PROD");
        }
    }
}
