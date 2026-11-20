package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.core.InMemoryKillSwitch;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KillSwitchServerTest {
    private static final String TOKEN = "0123456789abcdef-secret";
    private final InMemoryKillSwitch kill = new InMemoryKillSwitch();
    private final List<String> alerts = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private KillSwitchServer server;

    @BeforeEach
    void start() throws Exception {
        server = new KillSwitchServer(kill, TOKEN, 0, (l, m) -> alerts.add(l + ":" + m));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> call(String method, String path, String auth) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (auth != null) {
            b.header("Authorization", auth);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void requestsWithoutTheRightTokenAreRefusedAndChangeNothing() throws Exception {
        assertThat(call("POST", "/killswitch/activate", null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/killswitch/activate", "Bearer wrong").statusCode()).isEqualTo(401);
        assertThat(call("GET", "/killswitch", "Basic " + TOKEN).statusCode()).isEqualTo(401);
        assertThat(kill.isActive()).isFalse();
        assertThat(alerts).isEmpty();
    }

    @Test
    void activateDeactivateAndStatusWithTheToken() throws Exception {
        String auth = "Bearer " + TOKEN;
        assertThat(call("GET", "/killswitch", auth).body()).isEqualTo("inactive");
        assertThat(call("POST", "/killswitch/activate?reason=testing%20now", auth).body()).isEqualTo("active");
        assertThat(kill.isActive()).isTrue();
        assertThat(kill.reason()).contains("testing now");
        assertThat(call("GET", "/killswitch", auth).body()).isEqualTo("active");
        assertThat(call("POST", "/killswitch/deactivate", auth).body()).isEqualTo("inactive");
        assertThat(kill.isActive()).isFalse();
        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(0)).startsWith("CRITICAL");
    }

    @Test
    void wrongMethodOrPathIsNotFound() throws Exception {
        assertThat(call("GET", "/killswitch/activate", "Bearer " + TOKEN).statusCode()).isEqualTo(404);
        assertThat(call("POST", "/killswitch", "Bearer " + TOKEN).statusCode()).isEqualTo(404);
    }

    @Test
    void listensOnlyOnTheLoopbackAddress() {
        assertThat(server.port()).isPositive();
        // created on InetAddress.getLoopbackAddress(): never reachable from other machines
        assertThat(java.net.InetAddress.getLoopbackAddress().isLoopbackAddress()).isTrue();
    }

    @Test
    void aWeakOrMissingTokenCannotCreateTheServer() {
        assertThatThrownBy(() -> new KillSwitchServer(kill, "short", 0, (l, m) -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KillSwitchServer(kill, null, 0, (l, m) -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
