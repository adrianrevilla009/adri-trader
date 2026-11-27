package com.adrianrevilla.trading.core.domain;

/** Where orders go. Only DEMO can be used today; PROD exists so the model is ready (ADR-0030, needs its own ADR and MFA). */
public enum TradingMode {
    DEMO,
    PROD
}
