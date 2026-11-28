package com.adrianrevilla.trading.adapter.in.web;

import com.adrianrevilla.trading.application.port.in.ManageAgents;
import com.adrianrevilla.trading.application.port.in.ManageMode;
import com.adrianrevilla.trading.application.port.in.QueryAgents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * JSON API to manage agents from the screen (ADR-0030). It listens on the loopback address (unless told otherwise) and every call needs
 * {@code Authorization: Bearer <token>} (compared in constant time; the token comes from the environment and has at
 * least 16 characters). Browsers may call it only from the one allowed origin. It holds no business rule: it turns
 * HTTP into calls to the use cases and their errors into status codes.
 *
 * <pre>
 * GET  /api/system                 mode and available modes
 * PUT  /api/system/mode            {"mode":"DEMO"}; PROD is refused (409): it has its own two steps
 * POST /api/system/prod/challenge   step one to PROD (409 while the feature flag is off, 423 when locked)
 * POST /api/system/prod/confirm     step two: {"challengeId","code","phrase"} (403 for any wrong factor)
 * GET  /api/strategies             catalog with parameter descriptions
 * GET  /api/instruments            ISINs an agent universe may use
 * GET  /api/agents                 list
 * POST /api/agents                 create (201)
 * GET  /api/agents/{id}            one agent
 * PUT  /api/agents/{id}            change params, universe, capital or steps
 * POST /api/agents/{id}/stop       stop sending new orders (positions are kept)
 * POST /api/agents/{id}/resume     send orders again from the next cycle
 * GET  /api/agents/{id}/audit      who changed what
 * </pre>
 *
 * Errors are {@code {"error": "message"}}: 400 invalid input, 401 no token, 404 unknown agent or route, 405 wrong
 * method, 403 wrong MFA factor, 409 duplicate id or unavailable mode, 413 body above 64 KiB. 423 PROD confirmations locked after repeated failures.
 */
public final class AgentsApiServer {
    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_DRAIN = 10 * 1024 * 1024;
    private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9._@-]{1,64}");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private record ModeRequest(String mode) {
    }

    private record ConfirmRequest(String challengeId, String code, String phrase) {
    }

    /** An answer decided inside the HTTP layer (bad request, no token, unknown route...). */
    private static final class ApiError extends RuntimeException {
        private final int status;

        ApiError(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private final HttpServer server;
    private final ManageAgents commands;
    private final QueryAgents queries;
    private final ManageMode mode;
    private final byte[] token;
    private final String allowedOrigin;

    /** Listens on the loopback address. */
    public AgentsApiServer(ManageAgents commands, QueryAgents queries, ManageMode mode, String token, int port,
            String allowedOrigin) throws IOException {
        this(commands, queries, mode, token, port, allowedOrigin, InetAddress.getLoopbackAddress().getHostAddress());
    }

    /**
     * @param bindAddress where to listen; loopback by default. Only a container that publishes the port on the host
     *     loopback only (compose does) should use 0.0.0.0.
     */
    public AgentsApiServer(ManageAgents commands, QueryAgents queries, ManageMode mode, String token, int port,
            String allowedOrigin, String bindAddress) throws IOException {
        if (token == null || token.length() < 16) {
            throw new IllegalArgumentException("The API token must have at least 16 characters");
        }
        this.commands = commands;
        this.queries = queries;
        this.mode = mode;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.allowedOrigin = allowedOrigin == null ? "" : allowedOrigin;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bindAddress), port), 0);
        server.createContext("/api", this::handle);
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
            cors(ex);
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            if (ex.getRequestMethod().equals("OPTIONS")) {
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
                ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Actor");
                ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!authorized(ex.getRequestHeaders().getFirst("Authorization"))) {
                throw new ApiError(401, "unauthorized");
            }
            route(ex);
        } catch (ApiError e) {
            reply(ex, e.status(), Map.of("error", e.getMessage()));
        } catch (ManageAgents.AgentNotFoundException e) {
            reply(ex, 404, Map.of("error", e.getMessage()));
        } catch (ManageMode.ConfirmationRejectedException e) {
            reply(ex, 403, Map.of("error", e.getMessage()));
        } catch (ManageMode.SwitchLockedException e) {
            reply(ex, 423, Map.of("error", e.getMessage()));
        } catch (ManageAgents.AgentAlreadyExistsException | ManageMode.ModeNotAvailableException e) {
            reply(ex, 409, Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            reply(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            reply(ex, 500, Map.of("error", "internal error"));
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String[] p = ex.getRequestURI().getPath().replaceAll("/+$", "").split("/");
        // p = ["", "api", ...]
        if (p.length == 3 && p[2].equals("system")) {
            allow(method, "GET");
            reply(ex, 200, mode.current());
        } else if (p.length == 4 && p[2].equals("system") && p[3].equals("mode")) {
            allow(method, "PUT");
            ModeRequest req = body(ex, ModeRequest.class);
            if (req.mode() == null) {
                throw new ApiError(400, "mode is required: one of DEMO, PROD");
            }
            reply(ex, 200, mode.switchTo(req.mode(), actor(ex)));
        } else if (p.length == 5 && p[2].equals("system") && p[3].equals("prod") && p[4].equals("challenge")) {
            allow(method, "POST");
            reply(ex, 200, mode.requestProdSwitch(actor(ex)));
        } else if (p.length == 5 && p[2].equals("system") && p[3].equals("prod") && p[4].equals("confirm")) {
            allow(method, "POST");
            ConfirmRequest req = body(ex, ConfirmRequest.class);
            reply(ex, 200, mode.confirmProdSwitch(req.challengeId(), req.code(), req.phrase(), actor(ex)));
        } else if (p.length == 3 && p[2].equals("strategies")) {
            allow(method, "GET");
            reply(ex, 200, queries.strategies());
        } else if (p.length == 3 && p[2].equals("limits")) {
            allow(method, "GET");
            reply(ex, 200, queries.limits());
        } else if (p.length == 3 && p[2].equals("instruments")) {
            allow(method, "GET");
            reply(ex, 200, queries.instruments());
        } else if (p.length == 3 && p[2].equals("agents")) {
            if (method.equals("GET")) {
                reply(ex, 200, queries.list());
            } else {
                allow(method, "POST");
                reply(ex, 201, commands.create(body(ex, ManageAgents.CreateAgent.class), actor(ex)));
            }
        } else if (p.length == 4 && p[2].equals("agents")) {
            if (method.equals("PUT")) {
                reply(ex, 200, commands.update(p[3], body(ex, ManageAgents.UpdateAgent.class), actor(ex)));
            } else {
                allow(method, "GET");
                reply(ex, 200, queries.find(p[3]).orElseThrow(() -> new ManageAgents.AgentNotFoundException(p[3])));
            }
        } else if (p.length == 5 && p[2].equals("agents") && p[4].equals("stop")) {
            allow(method, "POST");
            reply(ex, 200, commands.stop(p[3], actor(ex)));
        } else if (p.length == 5 && p[2].equals("agents") && p[4].equals("resume")) {
            allow(method, "POST");
            reply(ex, 200, commands.resume(p[3], actor(ex)));
        } else if (p.length == 5 && p[2].equals("agents") && p[4].equals("audit")) {
            allow(method, "GET");
            reply(ex, 200, queries.audit(p[3]));
        } else {
            throw new ApiError(404, "not found");
        }
    }

    private static void allow(String method, String expected) {
        if (!method.equals(expected)) {
            throw new ApiError(405, "method not allowed: use " + expected);
        }
    }

    private boolean authorized(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        return MessageDigest.isEqual(token, header.substring(7).getBytes(StandardCharsets.UTF_8));
    }

    /** A browser may call the API only from the allowed origin; any other origin gets no CORS header at all. */
    private void cors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !allowedOrigin.isEmpty() && origin.equals(allowedOrigin)) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            ex.getResponseHeaders().set("Vary", "Origin");
        }
    }

    private static String actor(HttpExchange ex) {
        String given = ex.getRequestHeaders().getFirst("X-Actor");
        return given != null && ACTOR.matcher(given).matches() ? "api:" + given : "api";
    }

    private static <T> T body(HttpExchange ex, Class<T> type) throws IOException {
        byte[] raw = readLimited(ex.getRequestBody());
        if (raw.length == 0) {
            throw new ApiError(400, "a JSON body is required");
        }
        try {
            T value = JSON.readValue(raw, type);
            if (value == null) {
                throw new ApiError(400, "a JSON object is required");
            }
            return value;
        } catch (JsonProcessingException e) {
            throw new ApiError(400, "invalid JSON: " + e.getOriginalMessage());
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BODY + 1);
        if (data.length > MAX_BODY) {
            // read and drop the rest, so the client receives the answer instead of a reset connection
            long drained = 0;
            byte[] sink = new byte[8192];
            int n;
            while (drained < MAX_DRAIN && (n = in.read(sink)) > 0) {
                drained += n;
            }
            throw new ApiError(413, "body too large: the limit is " + MAX_BODY + " bytes");
        }
        return data;
    }

    private static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
