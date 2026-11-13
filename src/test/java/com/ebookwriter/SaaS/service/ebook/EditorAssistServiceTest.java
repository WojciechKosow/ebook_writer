package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.dto.AssistResponse;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.request.AssistRequest;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.ebookwriter.SaaS.service.ai.ModelChoice;
import com.ebookwriter.SaaS.service.credit.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The editor's writing assistant: one credit per suggestion, returned when the model call fails. */
class EditorAssistServiceTest {

    private final EbookRepository ebooks = mock(EbookRepository.class);
    private final CreditService credits = mock(CreditService.class);
    private final AnthropicService ai = mock(AnthropicService.class);
    private final EditorAssistService service = new EditorAssistService(ebooks, credits, ai, new AnthropicProperties());
    private final UUID ebookId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(ebooks.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(Ebook.builder().topic("x").language("English").build()));
        when(credits.spend(eq(userId), eq(1), eq(CreditTransactionType.EDITOR_ASSIST), eq(ebookId), anyString()))
                .thenReturn(new CreditService.Debit(1, 0));
    }

    private AssistRequest req(String action, String target, String text) {
        return new AssistRequest(action, target, text, "The exposure triangle", "Chapter text.", List.of("Aperture"), "Two macaws.");
    }

    @Test
    void rewritesAParagraphForOneCredit() {
        when(ai.complete(anyString(), anyString(), anyLong(), any(ModelChoice.class)))
                .thenReturn("Here is the improved paragraph:\n\"Every photograph is a negotiation with light.\"");

        AssistResponse res = service.assist(ebookId, userId, req("improve", "block", "Every photo negotiates light."));

        assertEquals("Every photograph is a negotiation with light.", res.text());
        assertNull(res.items());
        assertEquals(1, res.creditsCharged());
    }

    @Test
    void listActionsReturnCleanItems() {
        when(ai.complete(anyString(), anyString(), anyLong(), any(ModelChoice.class)))
                .thenReturn("- **Metering**\n2. Reading the histogram\n• Exposure compensation\n");

        AssistResponse res = service.assist(ebookId, userId, req("structure", "ch", ""));

        assertEquals(List.of("Metering", "Reading the histogram", "Exposure compensation"), res.items());
    }

    @Test
    void refundsTheCreditWhenTheModelFails() {
        when(ai.complete(anyString(), anyString(), anyLong(), any(ModelChoice.class))).thenThrow(new RuntimeException("overloaded"));

        assertThrows(IllegalStateException.class, () -> service.assist(ebookId, userId, req("caption", "fig", "")));
        verify(credits).refundGeneration(userId, 1, 0, ebookId);
    }

    @Test
    void anEmptyParagraphCostsNothing() {
        assertThrows(IllegalStateException.class, () -> service.assist(ebookId, userId, req("improve", "block", "  ")));
        verify(credits, never()).spend(any(), any(Integer.class), any(), any(), any());
    }
}
