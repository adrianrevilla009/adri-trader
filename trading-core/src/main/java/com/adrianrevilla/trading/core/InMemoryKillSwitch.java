package com.adrianrevilla.trading.core;

import java.util.concurrent.atomic.AtomicReference;

public final class InMemoryKillSwitch implements KillSwitch {
    private final AtomicReference<String> reason = new AtomicReference<>();

    @Override
    public boolean isActive() {
        return reason.get() != null;
    }

    @Override
    public void activate(String why) {
        reason.set(why == null ? "unspecified" : why);
    }

    @Override
    public void deactivate() {
        reason.set(null);
    }

    public String reason() {
        return reason.get();
    }
}
