package com.adrianrevilla.trading.app;

import com.adrianrevilla.trading.core.Alerter;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Alerts through the Telegram bot API. A failure to alert is logged and never breaks the trading flow. */
public class TelegramAlerter implements Alerter {
    private static final Logger LOG = LoggerFactory.getLogger(TelegramAlerter.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String token;
    private final String chatId;

    public TelegramAlerter(String baseUrl, String token, String chatId) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.chatId = chatId;
    }

    @Override
    public void alert(Level level, String message) {
        String text = "[" + level + "] " + message;
        String body = "chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/sendMessage"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                LOG.warn("Telegram answered {} for alert: {}", response.statusCode(), text);
            }
        } catch (IOException e) {
            LOG.warn("Telegram alert failed ({}): {}", e.getMessage(), text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
