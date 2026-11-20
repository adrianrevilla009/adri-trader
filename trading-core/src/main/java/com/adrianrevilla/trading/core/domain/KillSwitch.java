package com.adrianrevilla.trading.core.domain;

/** Manual switch: while active nothing new is sent. It never liquidates positions; a person decides that. */
public interface KillSwitch {
    boolean isActive();

    void activate(String reason);

    void deactivate();
}
