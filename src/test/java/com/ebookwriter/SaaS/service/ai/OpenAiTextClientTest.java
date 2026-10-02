package com.ebookwriter.SaaS.service.ai;

import com.ebookwriter.SaaS.config.OpenAiConfig;
import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.support.FakeOpenAiServer;
import com.ebookwriter.SaaS.support.FakeOpenAiServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The OpenAI text client over real HTTP (local fake endpoint): request shape, usage, retries, errors. */
class OpenAiTextClientTest {

    private FakeOpenAiServer server;
    private OpenAiProperties props;
    private OpenAiTextClient client;

    @BeforeEach
    void setUp() {
        server = new FakeOpenAiServer();
        props = new OpenAiProperties();
        props.setApiKey("sk-test");
        props.setBaseUrl(server.baseUrl());
        props.setMaxRetries(2);
        client = new OpenAiTextClient(new OpenAiConfig().openAiTextWebClient(props), props);
        client.setBackoffBaseMs(1);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void sendsTheConfiguredModelAsAJsonModeRequestAndReportsUsage() throws Exception {
        server.respond(r -> Reply.json("{\"topics\":[{\"name\":\"JWT\"}]}"));

        OpenAiTextClient.JsonCompletion c = client.completeJson("system prompt", "user prompt");

        assertEquals("JWT", c.json().path("topics").path(0).path("name").asText());
        assertTrue(c.inputTokens() > 0);
        assertTrue(c.outputTokens() > 0);
        assertEquals("gpt-5-mini-2026-test", c.model());

        JsonNode sent = new ObjectMapper().readTree(server.requests().get(0).body());
        assertEquals("gpt-5-mini", sent.path("model").asText(), "model comes from openai.knowledge-model");
        assertEquals("json_object", sent.path("response_format").path("type").asText());
        assertEquals("low", sent.path("reasoning_effort").asText());
        assertEquals(16_000, sent.path("max_completion_tokens").asInt());
        assertEquals("system", sent.path("messages").path(0).path("role").asText());
        assertEquals("user prompt", sent.path("messages").path(1).path("content").asText());
    }

    @Test
    void modelAndReasoningEffortAreConfigurable() throws Exception {
        props.setKnowledgeModel("gpt-4.1-mini");
        props.setKnowledgeReasoningEffort("");
        server.respond(r -> Reply.json("{}"));
        client.completeJson("s", "u");
        JsonNode sent = new ObjectMapper().readTree(server.requests().get(0).body());
        assertEquals("gpt-4.1-mini", sent.path("model").asText());
        assertFalse(sent.has("reasoning_effort"), "blank effort is omitted for non-reasoning models");
    }

    @Test
    void transientErrorsAreRetried() {
        AtomicInteger calls = new AtomicInteger();
        server.respond(r -> calls.incrementAndGet() < 3 ? Reply.error(503) : Reply.json("{\"ok\":true}"));
        OpenAiTextClient.JsonCompletion c = client.completeJson("s", "u");
        assertTrue(c.json().path("ok").asBoolean());
        assertEquals(3, c.attempts());
    }

    @Test
    void clientErrorsAreNotRetried() {
        server.respond(r -> Reply.error(401));
        OpenAiTextException e = assertThrows(OpenAiTextException.class, () -> client.completeJson("s", "u"));
        assertFalse(e.isRetryable());
        assertTrue(e.getMessage().contains("HTTP 401"));
        assertEquals(1, server.requests().size());
    }

    @Test
    void truncatedOutputAndInvalidJsonAreErrors() {
        String truncated = "{\"model\":\"m\",\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"a\\\":\"}}]}";
        assertThrows(OpenAiTextException.class, () -> OpenAiTextClient.parseResponse(truncated));

        server.respond(r -> Reply.json("not json at all"));
        assertThrows(OpenAiTextException.class, () -> client.completeJson("s", "u"));
        assertEquals(3, server.requests().size(), "invalid JSON is retried (1 + 2 retries)");
    }

    @Test
    void fencedJsonIsAccepted() {
        String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"```json\\n{\\\"a\\\":1}\\n```\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
        OpenAiTextClient.JsonCompletion c = OpenAiTextClient.parseResponse(body);
        assertEquals(1, c.json().path("a").asInt());
        assertEquals(10, c.inputTokens());
    }

    @Test
    void refusesToCallWithoutAKey() {
        props.setApiKey("");
        assertThrows(OpenAiTextException.class, () -> client.completeJson("s", "u"));
        assertTrue(server.requests().isEmpty());
    }
}
