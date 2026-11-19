package com.adrianrevilla.trading.core;

/** Sends operational alerts (Telegram in production, log in dev). */
public interface Alerter {
    void alert(Level level, String message);

    enum Level { INFO, WARNING, CRITICAL }
}
