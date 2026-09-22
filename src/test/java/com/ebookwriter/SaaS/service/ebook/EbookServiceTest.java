package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.dto.GenerationBudgetResponse;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.exceptions.InsufficientCreditsException;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookPdfRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.request.EbookRequest;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The generation-budget rules on the application entry point:
 *
 * <ul>
 *   <li>the user no longer chooses a page count — a draft is seeded with the
 *       system standard target;</li>
 *   <li>a standard generation needs at least {@code minGenerationBudget} credits
 *       (30): below that {@link EbookService#start} is refused before any state is
 *       mutated or any credits held;</li>
 *   <li>at or above the minimum, the reservation is sized against the absolute
 *       safety cap ({@code maxGenerationBudget}) so the ceiling becomes the user's
 *       own budget — a user with more credits can get a longer book, and nothing
 *       is capped at an arbitrary page count;</li>
 *   <li>the budget-info endpoint reports the minimum, the orientational range, the
 *       balance and whether the user can generate.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class EbookServiceTest {

    @Mock EbookRepository ebookRepository;
    @Mock EbookChapterRepository chapterRepository;
    @Mock EbookPdfRepository pdfRepository;
    @Mock EbookGenerationService generationService;
    @Mock PdfGenerationService pdfGenerationService;
    @Mock AssetUsageService assetUsageService;
    @Mock CreditService creditService;

    CreditProperties creditProperties;
    EbookService ebookService;

    private final UUID userId = UUID.randomUUID();
    private final UUID ebookId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Real properties with production defaults: min budget 30, target ~20–30.
        creditProperties = new CreditProperties();
        ebookService = new EbookService(ebookRepository, chapterRepository, pdfRepository,
                generationService, pdfGenerationService, assetUsageService, creditService,
                creditProperties);
    }

    private User user() {
        User u = new User();
        u.setId(userId);
        return u;
    }

    private Ebook draft() {
        return Ebook.builder().id(ebookId).topic("Topic").status(EbookStatus.DRAFT).build();
    }

    @Test
    void createDraftSeedsTheSystemStandardTargetNotAUserChoice() {
        when(ebookRepository.save(any(Ebook.class))).thenAnswer(i -> i.getArgument(0));

        Ebook draft = ebookService.createDraft(user(), new EbookRequest());

        assertEquals(EbookStatus.DRAFT, draft.getStatus());
        assertEquals(creditProperties.getStandardTargetPages(), draft.getApproxPageCount(),
                "the draft is seeded with the standard target, not a user-supplied page count");
    }

    @Test
    void startIsRefusedBelowTheMinimumBudgetWithoutMutatingAnything() {
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(draft()));
        when(creditService.getBalance(userId)).thenReturn(20); // below the 30-credit minimum

        InsufficientCreditsException ex = assertThrows(InsufficientCreditsException.class,
                () -> ebookService.start(ebookId, userId));

        assertEquals(creditProperties.getMinGenerationBudget(), ex.getRequired());
        assertEquals(20, ex.getAvailable());
        // No claim, no hold, no async hand-off when the budget is too small.
        verify(ebookRepository, never()).claimForStart(any(), any(), any());
        verify(creditService, never()).reserveGenerationHold(any(), any(), anyInt(), anyInt());
        verify(generationService, never()).generate(any());
    }

    @Test
    void startAtExactlyTheMinimumBudgetReservesAgainstTheBudgetCapAndBegins() {
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(draft()));
        when(creditService.getBalance(userId)).thenReturn(30); // exactly the minimum
        when(ebookRepository.claimForStart(ebookId, EbookStatus.DRAFT, EbookStatus.PENDING)).thenReturn(1);
        when(ebookRepository.save(any(Ebook.class))).thenAnswer(i -> i.getArgument(0));
        // With balance 30 the CreditService clamps min(balance, cap)+overdraft = 40.
        when(creditService.reserveGenerationHold(eq(userId), eq(ebookId),
                eq(creditProperties.getMaxGenerationBudget()),
                eq(creditProperties.getMinGenerationBudget()))).thenReturn(40);

        Ebook started = ebookService.start(ebookId, userId);

        assertEquals(EbookStatus.PENDING, started.getStatus());
        assertEquals(40, started.getPageBudget());
        // The reservation is sized against the safety cap, gated on the minimum budget.
        verify(creditService).reserveGenerationHold(userId, ebookId,
                creditProperties.getMaxGenerationBudget(), creditProperties.getMinGenerationBudget());
        verify(generationService).generate(ebookId);
    }

    @Test
    void startWithAmpleBalanceGetsABudgetSizedCeilingNotAFixedCap() {
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(draft()));
        when(creditService.getBalance(userId)).thenReturn(100); // plenty
        when(ebookRepository.claimForStart(ebookId, EbookStatus.DRAFT, EbookStatus.PENDING)).thenReturn(1);
        when(ebookRepository.save(any(Ebook.class))).thenAnswer(i -> i.getArgument(0));
        // CreditService would clamp to min(100, cap)+overdraft = 110 for this balance.
        when(creditService.reserveGenerationHold(eq(userId), eq(ebookId), anyInt(), anyInt()))
                .thenReturn(110);

        Ebook started = ebookService.start(ebookId, userId);

        // The ceiling scales with the user's credits: the reservation target is the
        // safety cap, so the whole balance is available for a longer book.
        assertEquals(110, started.getPageBudget());
        verify(creditService).reserveGenerationHold(userId, ebookId,
                creditProperties.getMaxGenerationBudget(), creditProperties.getMinGenerationBudget());
    }

    @Test
    void generationBudgetReportsMinimumRangeBalanceAndEligibility() {
        when(creditService.getBalance(userId)).thenReturn(47);

        GenerationBudgetResponse info = ebookService.getGenerationBudget(userId);

        assertEquals(creditProperties.getMinGenerationBudget(), info.minCredits());
        assertEquals(creditProperties.getStandardTargetMinPages(), info.estimatedPagesLow());
        assertEquals(creditProperties.getStandardTargetPages(), info.estimatedPagesHigh());
        assertEquals(47, info.balance());
        assertTrue(info.canGenerate(), "47 credits is above the 30-credit minimum");
    }

    @Test
    void generationBudgetSaysCannotGenerateBelowTheMinimum() {
        when(creditService.getBalance(userId)).thenReturn(10);

        GenerationBudgetResponse info = ebookService.getGenerationBudget(userId);

        assertFalse(info.canGenerate(), "10 credits is below the 30-credit minimum");
    }
}
