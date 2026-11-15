package com.ebookwriter.SaaS.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a failed model call surfaces: a refusal and a rejected request are not sent
 * again (the same request gets the same answer), and when retries do run out the
 * real cause is in the message — the log and the failed chapter show it, not only
 * "failed after 2 attempts".
 */
class AnthropicServiceFailureTest {

    private MessageService messages;
    private AnthropicService service;

    @BeforeEach
    void setUp() {
        AnthropicClient client = mock(AnthropicClient.class);
        messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        service = new AnthropicService(client, new AnthropicProperties());
    }

    private static Message response(StopReason stop, String text) {
        Message m = mock(Message.class);
        Usage usage = mock(Usage.class);
        when(m.usage()).thenReturn(usage);
        when(m.stopReason()).thenReturn(Optional.of(stop));
        if (text == null) {
            when(m.content()).thenReturn(List.of());
        } else {
            ContentBlock block = mock(ContentBlock.class);
            com.anthropic.models.messages.TextBlock tb = mock(com.anthropic.models.messages.TextBlock.class);
            when(tb.text()).thenReturn(text);
            when(block.text()).thenReturn(Optional.of(tb));
            when(m.content()).thenReturn(List.of(block));
        }
        return m;
    }

    @Test
    void aRefusalIsReportedAsSuchAndNotSentAgain() {
        Message refused = response(StopReason.REFUSAL, null);
        when(messages.create(any(MessageCreateParams.class))).thenReturn(refused);

        ModelRefusalException e = assertThrows(ModelRefusalException.class,
                () -> service.complete("system", "user", 1000));

        assertEquals(new AnthropicProperties().getModel(), e.model());
        verify(messages, times(1)).create(any(MessageCreateParams.class));
    }

    @Test
    void aRejectedRequestIsNotRetriedAndCarriesTheApiExplanation() {
        BadRequestException bad = BadRequestException.builder()
                .headers(Headers.builder().build())
                .body(JsonValue.from(Map.of("type", "error", "error",
                        Map.of("type", "invalid_request_error", "message", "max_tokens: too large"))))
                .build();
        when(messages.create(any(MessageCreateParams.class))).thenThrow(bad);

        ModelRequestRejectedException e = assertThrows(ModelRequestRejectedException.class,
                () -> service.complete("system", "user", 1000));

        assertEquals(400, e.status());
        assertTrue(e.getMessage().contains("HTTP 400"), e.getMessage());
        assertTrue(e.getMessage().contains("max_tokens: too large"), e.getMessage());
        verify(messages, times(1)).create(any(MessageCreateParams.class));
    }

    @Test
    void aRejectedApiKeyIsReportedAsTheServiceBeingUnavailable() {
        com.anthropic.errors.UnauthorizedException unauthorized = com.anthropic.errors.UnauthorizedException.builder()
                .headers(Headers.builder().build())
                .body(JsonValue.from(Map.of("type", "error", "error",
                        Map.of("type", "authentication_error", "message", "invalid x-api-key"))))
                .build();
        when(messages.create(any(MessageCreateParams.class))).thenThrow(unauthorized);

        AiServiceUnavailableException e = assertThrows(AiServiceUnavailableException.class,
                () -> service.complete("system", "user", 1000));

        assertEquals(401, e.status());
        assertEquals(AiServiceUnavailableException.USER_MESSAGE, e.getMessage(), "no API JSON for the author");
        assertTrue(e.detail().contains("invalid x-api-key"), "the operator still gets the API's explanation");
        verify(messages, times(1)).create(any(MessageCreateParams.class));
    }

    @Test
    void whenRetriesRunOutTheCauseIsInTheMessage() {
        InternalServerException down = InternalServerException.builder()
                .statusCode(529)
                .headers(Headers.builder().build())
                .body(JsonValue.from(Map.of("type", "error", "error",
                        Map.of("type", "overloaded_error", "message", "Overloaded"))))
                .build();
        when(messages.create(any(MessageCreateParams.class))).thenThrow(down);

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.complete("system", "user", 1000));

        assertTrue(e.getMessage().startsWith("Anthropic completion failed after 2 attempts: "), e.getMessage());
        assertTrue(e.getMessage().contains("Overloaded"), e.getMessage());
        verify(messages, times(2)).create(any(MessageCreateParams.class));
    }

    @Test
    void anAnswerThatIsAllThinkingSaysSo() {
        Message allThinking = response(StopReason.MAX_TOKENS, null);
        when(messages.create(any(MessageCreateParams.class))).thenReturn(allThinking);

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.complete("system", "user", 1000));

        assertTrue(e.getMessage().contains("spent its whole output budget (1000 tokens) thinking"), e.getMessage());
    }

    @Test
    void aNormalAnswerStillComesThrough() {
        Message hello = response(StopReason.END_TURN, " Hello ");
        when(messages.create(any(MessageCreateParams.class))).thenReturn(hello);

        assertEquals("Hello", service.complete("system", "user", 1000));
    }
}
