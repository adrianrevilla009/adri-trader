package com.adrianrevilla.trading.adapter.in.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.domain.InMemoryKillSwitch;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelegramCommandListenerTest {
    private final AtomicReference<String> updates = new AtomicReference<>("{\"ok\":true,\"result\":[]}");
    private final List<String> queries = new ArrayList<>();
    private final List<String> alerts = new ArrayList<>();
    private final InMemoryKillSwitch kill = new InMemoryKillSwitch();
    private HttpServer server;
    private TelegramCommandListener listener;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            queries.add(ex.getRequestURI().getRawQuery());
            byte[] body = updates.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        listener = new TelegramCommandListener("http://127.0.0.1:" + server.getAddress().getPort(), "T", "42", kill,
                (l, m) -> alerts.add(l + ":" + m));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String update(int id, long chat, String text) {
        return "{\"update_id\":" + id + ",\"message\":{\"chat\":{\"id\":" + chat + "},\"text\":\"" + text + "\"}}";
    }

    @Test
    void killFromTheConfiguredChatActivatesTheSwitchAndAcknowledgesTheOffset() {
        updates.set("{\"ok\":true,\"result\":[" + update(7, 42, "/kill something odd") + "]}");
        assertThat(listener.pollOnce()).isEqualTo(1);
        assertThat(kill.isActive()).isTrue();
        assertThat(kill.reason()).contains("something odd");
        updates.set("{\"ok\":true,\"result\":[]}");
        listener.pollOnce();
        assertThat(queries.get(1)).endsWith("offset=8");
    }

    @Test
    void messagesFromOtherChatsAndUnknownCommandsAreIgnored() {
        updates.set("{\"ok\":true,\"result\":[" + update(1, 999, "/kill") + "," + update(2, 42, "/resume") + ","
                + update(3, 42, "hello") + "]}");
        assertThat(listener.pollOnce()).isZero();
        assertThat(kill.isActive()).isFalse();
    }

    @Test
    void statusReportsAndCannotDeactivate() {
        kill.activate("x");
        updates.set("{\"ok\":true,\"result\":[" + update(1, 42, "/status") + "," + update(2, 42, "/deactivate") + "]}");
        assertThat(listener.pollOnce()).isEqualTo(1);
        assertThat(alerts).containsExactly("INFO:Kill switch: ACTIVE");
        assertThat(kill.isActive()).isTrue();
    }

    @Test
    void anUnreachableTelegramNeverThrows() {
        server.stop(0);
        assertThat(listener.pollOnce()).isZero();
    }
}
