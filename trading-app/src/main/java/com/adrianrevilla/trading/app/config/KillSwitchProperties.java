package com.adrianrevilla.trading.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The kill-switch endpoint (prefix {@code trading.killswitch}): off unless a token of 16+ characters is set. */
@ConfigurationProperties("trading.killswitch")
public record KillSwitchProperties(@DefaultValue("") String token, @DefaultValue("8099") int port) {

    public KillSwitchProperties {
        if (!token.isEmpty() && token.length() < 16) {
            throw new IllegalArgumentException("trading.killswitch.token must have at least 16 characters");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("trading.killswitch.port out of range: " + port);
        }
    }

    public boolean enabled() {
        return !token.isEmpty();
    }
}
