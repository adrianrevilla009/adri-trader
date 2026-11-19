package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.adrianrevilla.trading.core.Alerter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TelegramAlerterTest {

    @Test
    void postsTheMessageToTheBotApi() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            var alerter = new TelegramAlerter("http://127.0.0.1:" + server.getAddress().getPort(), "TOKEN", "42");
            alerter.alert(Alerter.Level.CRITICAL, "trading halted");
            assertThat(path.get()).isEqualTo("/botTOKEN/sendMessage");
            assertThat(body.get()).contains("chat_id=42").contains("CRITICAL").contains("trading+halted");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aFailingTelegramNeverBreaksTheCaller() {
        var alerter = new TelegramAlerter("http://127.0.0.1:1", "TOKEN", "42"); // nothing listens there
        assertThatCode(() -> alerter.alert(Alerter.Level.INFO, "hello")).doesNotThrowAnyException();
    }
}
