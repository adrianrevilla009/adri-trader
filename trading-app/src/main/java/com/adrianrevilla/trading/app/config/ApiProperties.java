package com.adrianrevilla.trading.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The JSON API that manages agents (prefix {@code trading.api}, ADR-0030): off unless a token of 16+ characters is set.
 *
 * @param allowedOrigin the one browser origin that may call it (the screen in development)
 * @param bindAddress loopback by default; a container sets 0.0.0.0 and publishes the port on the host loopback only
 */
@ConfigurationProperties("trading.api")
public record ApiProperties(
        @DefaultValue("") String token,
        @DefaultValue("8100") int port,
        @DefaultValue("http://localhost:5173") String allowedOrigin,
        @DefaultValue("127.0.0.1") String bindAddress) {

    public ApiProperties {
        if (!token.isEmpty() && token.length() < 16) {
            throw new IllegalArgumentException("trading.api.token must have at least 16 characters");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("trading.api.port out of range: " + port);
        }
        if (bindAddress.isBlank()) {
            throw new IllegalArgumentException("trading.api.bind-address must not be blank");
        }
    }

    public boolean enabled() {
        return !token.isEmpty();
    }
}
