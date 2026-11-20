package com.adrianrevilla.trading.application.port.in;

/** Input port (use case): looks at what generates no events (stale data, stuck days...) and alerts once per problem. */
public interface CheckSystemHealth {
    void check();
}
