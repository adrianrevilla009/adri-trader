package com.adrianrevilla.trading.app;

import com.adrianrevilla.trading.core.Alerter;
import com.adrianrevilla.trading.core.KillSwitch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads commands from the Telegram bot and only obeys the configured chat. Commands:
 * {@code /kill [reason]} activates the kill switch, {@code /status} reports it. Deactivating is deliberately not
 * possible from here: re-enabling trading takes the authenticated endpoint on the machine (fail safe).
 */
public class TelegramCommandListener {
    private static final Logger LOG = LoggerFactory.getLogger(TelegramCommandListener.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String token;
    private final String chatId;
    private final KillSwitch killSwitch;
    private final Alerter alerter;
    private long offset = 0;

    public TelegramCommandListener(String baseUrl, String token, String chatId, KillSwitch killSwitch,
            Alerter alerter) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.chatId = chatId;
        this.killSwitch = killSwitch;
        this.alerter = alerter;
    }

    /** One short poll; never throws. @return number of commands obeyed */
    public int pollOnce() {
        int obeyed = 0;
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/getUpdates?timeout=0&offset="
                    + offset)).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                LOG.warn("Telegram getUpdates answered {}", response.statusCode());
                return 0;
            }
            for (JsonNode update : json.readTree(response.body()).path("result")) {
                offset = Math.max(offset, update.path("update_id").asLong() + 1);
                JsonNode message = update.path("message");
                String from = message.path("chat").path("id").asText("");
                if (!from.equals(chatId)) {
                    LOG.warn("Ignored a Telegram message from an unknown chat");
                    continue;
                }
                if (handle(message.path("text").asText(""))) {
                    obeyed++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warn("Telegram command polling failed: {}", e.getMessage());
        }
        return obeyed;
    }

    private boolean handle(String text) {
        String[] parts = text.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase(java.util.Locale.ROOT).replaceAll("@.*$", "");
        switch (command) {
            case "/kill" -> {
                String reason = parts.length > 1 ? parts[1] : "no reason given";
                killSwitch.activate("manual via Telegram: " + (reason.length() > 200 ? reason.substring(0, 200) : reason));
                alerter.alert(Alerter.Level.CRITICAL, "Kill switch ACTIVATED from Telegram");
                return true;
            }
            case "/status" -> {
                alerter.alert(Alerter.Level.INFO, "Kill switch: " + (killSwitch.isActive() ? "ACTIVE" : "inactive"));
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
