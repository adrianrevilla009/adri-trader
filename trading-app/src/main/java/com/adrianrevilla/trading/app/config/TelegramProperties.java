package com.adrianrevilla.trading.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Telegram alerts and commands (prefix {@code trading.telegram}). The token only ever comes from the environment. */
@ConfigurationProperties("trading.telegram")
public record TelegramProperties(
        @DefaultValue("") String botToken,
        @DefaultValue("") String chatId,
        @DefaultValue("https://api.telegram.org") String baseUrl,
        @DefaultValue("5000") long pollMs) {

    public TelegramProperties {
        if (botToken.isBlank() != chatId.isBlank()) {
            throw new IllegalArgumentException("trading.telegram: set both bot-token and chat-id, or neither");
        }
    }

    public boolean configured() {
        return !botToken.isBlank();
    }
}
