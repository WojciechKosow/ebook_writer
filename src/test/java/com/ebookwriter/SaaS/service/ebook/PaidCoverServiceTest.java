package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import com.ebookwriter.SaaS.dto.EbookImageDTO;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.exceptions.InsufficientCreditsException;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.credit.CreditPricing;
import com.ebookwriter.SaaS.service.credit.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regenerating a cover is a paid operation: charged before the image call,
 * returned in full if it fails, and never started without the credits.
 */
@ExtendWith(MockitoExtension.class)
class PaidCoverServiceTest {

    @Mock CoverGenerationService coverGenerationService;
    @Mock CreditService creditService;
    @Mock EbookRepository ebookRepository;

    PaidCoverService service;
    final UUID user = UUID.randomUUID();
    final UUID ebook = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PaidCoverService(coverGenerationService, creditService,
                new CreditPricing(new CreditProperties(), new OpenAiProperties()), ebookRepository);
        when(ebookRepository.findByIdAndUserId(ebook, user)).thenReturn(Optional.of(new Ebook()));
        when(coverGenerationService.canGenerate()).thenReturn(true);
    }

    @Test
    void chargesTheCoverCostAndRegenerates() {
        when(creditService.spend(eq(user), eq(30), eq(CreditTransactionType.COVER_GENERATION), eq(ebook), anyString()))
                .thenReturn(new CreditService.Debit(30, 30));
        EbookImageDTO image = mock(EbookImageDTO.class);
        when(coverGenerationService.regenerate(ebook, user)).thenReturn(image);

        assertSame(image, service.regenerate(ebook, user));
        verify(creditService, never()).refundGeneration(any(), anyInt(), anyInt(), any());
    }

    @Test
    void aFailedGenerationReturnsTheCreditsToWhereTheyCameFrom() {
        when(creditService.spend(eq(user), eq(30), eq(CreditTransactionType.COVER_GENERATION), eq(ebook), anyString()))
                .thenReturn(new CreditService.Debit(30, 12));
        when(coverGenerationService.regenerate(ebook, user)).thenThrow(new IllegalStateException("image API down"));

        assertThrows(IllegalStateException.class, () -> service.regenerate(ebook, user));
        verify(creditService).refundGeneration(user, 30, 12, ebook);
    }

    @Test
    void withoutEnoughCreditsNothingIsGenerated() {
        when(creditService.spend(eq(user), eq(30), any(), eq(ebook), anyString()))
                .thenThrow(new InsufficientCreditsException(30, 10));

        assertThrows(InsufficientCreditsException.class, () -> service.regenerate(ebook, user));
        verify(coverGenerationService, never()).regenerate(any(), any());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
