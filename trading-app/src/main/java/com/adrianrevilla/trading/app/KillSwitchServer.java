package com.adrianrevilla.trading.app;

import com.adrianrevilla.trading.core.Alerter;
import com.adrianrevilla.trading.core.KillSwitch;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protected endpoint to flip the kill switch from outside the process. It listens only on the loopback address
 * and requires {@code Authorization: Bearer <token>} (compared in constant time). The token comes from the
 * environment. Without a token the server cannot be created.
 *
 * <pre>
 * GET  /killswitch             status
 * POST /killswitch/activate    optional query parameter reason
 * POST /killswitch/deactivate
 * </pre>
 */
public final class KillSwitchServer {
    private final HttpServer server;
    private final KillSwitch killSwitch;
    private final byte[] token;
    private final Alerter alerter;

    public KillSwitchServer(KillSwitch killSwitch, String token, int port, Alerter alerter) throws IOException {
        if (token == null || token.length() < 16) {
            throw new IllegalArgumentException("The kill switch token must have at least 16 characters");
        }
        this.killSwitch = killSwitch;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.alerter = alerter;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/killswitch", this::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!authorized(ex.getRequestHeaders().getFirst("Authorization"))) {
                reply(ex, 401, "unauthorized");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (method.equals("GET") && path.equals("/killswitch")) {
                reply(ex, 200, killSwitch.isActive() ? "active" : "inactive");
            } else if (method.equals("POST") && path.equals("/killswitch/activate")) {
                killSwitch.activate("manual via endpoint: " + reason(ex.getRequestURI().getRawQuery()));
                alerter.alert(Alerter.Level.CRITICAL, "Kill switch ACTIVATED by hand");
                reply(ex, 200, "active");
            } else if (method.equals("POST") && path.equals("/killswitch/deactivate")) {
                killSwitch.deactivate();
                alerter.alert(Alerter.Level.WARNING, "Kill switch DEACTIVATED by hand");
                reply(ex, 200, "inactive");
            } else {
                reply(ex, 404, "not found");
            }
        } catch (RuntimeException e) {
            reply(ex, 500, "error");
        } finally {
            ex.close();
        }
    }

    private boolean authorized(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        return MessageDigest.isEqual(token, header.substring(7).getBytes(StandardCharsets.UTF_8));
    }

    private static String reason(String rawQuery) {
        if (rawQuery != null) {
            for (String part : rawQuery.split("&")) {
                if (part.startsWith("reason=")) {
                    String r = URLDecoder.decode(part.substring(7), StandardCharsets.UTF_8);
                    return r.length() > 200 ? r.substring(0, 200) : r;
                }
            }
        }
        return "no reason given";
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
