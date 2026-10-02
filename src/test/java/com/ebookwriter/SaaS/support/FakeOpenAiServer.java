package com.ebookwriter.SaaS.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A local stand-in for the OpenAI chat-completions endpoint. Only the network
 * leg is replaced: callers use the real WebClient and request/response code,
 * and the {@link #responder} decides what the "model" answers per request (so a
 * test can answer realistically from what was actually sent).
 */
public final class FakeOpenAiServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A captured request: the raw body plus its system and user messages. */
    public record Request(String body, String model, String system, String user) {
    }

    /** What to answer: an HTTP status plus either a model JSON object (content) or a raw body. */
    public record Reply(int status, String content, String rawBody) {
        public static Reply json(String content) {
            return new Reply(200, content, null);
        }

        public static Reply error(int status) {
            return new Reply(status, null, "{\"error\":{\"message\":\"simulated " + status + "\"}}");
        }

        public static Reply raw(String body) {
            return new Reply(200, null, body);
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, Reply> responder = r -> Reply.json("{}");

    public FakeOpenAiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode node = MAPPER.readTree(body);
            Request req = new Request(body, node.path("model").asText(),
                    node.path("messages").path(0).path("content").asText(),
                    node.path("messages").path(1).path("content").asText());
            requests.add(req);
            Reply reply;
            try {
                reply = responder.apply(req);
            } catch (RuntimeException e) {
                reply = Reply.error(500);
            }
            String out = reply.rawBody() != null ? reply.rawBody() : completion(req, reply.content());
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    private static String completion(Request req, String content) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-test");
        root.put("model", req.model() + "-2026-test");
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        choice.put("finish_reason", "stop");
        choice.putObject("message").put("role", "assistant").put("content", content);
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", (req.system().length() + req.user().length()) / 4);
        usage.put("completion_tokens", content.length() / 4 + 200);
        return root.toString();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public void respond(Function<Request, Reply> responder) {
        this.responder = responder;
    }

    public List<Request> requests() {
        return requests;
    }

    public void reset() {
        requests.clear();
        responder = r -> Reply.json("{}");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
