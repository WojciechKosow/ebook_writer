package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.dto.BookScopeResponse;
import com.ebookwriter.SaaS.dto.GenerationBudgetResponse;
import com.ebookwriter.SaaS.dto.ScopeEstimateDTO;
import com.ebookwriter.SaaS.entity.BookDepth;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.exceptions.InsufficientCreditsException;
import com.ebookwriter.SaaS.repository.BookKnowledgeRepository;
import com.ebookwriter.SaaS.repository.EbookChapterRepository;
import com.ebookwriter.SaaS.repository.EbookPdfRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.repository.KnowledgeSourceRepository;
import com.ebookwriter.SaaS.request.EbookRequest;
import com.ebookwriter.SaaS.service.blueprint.BookBlueprintService;
import com.ebookwriter.SaaS.service.credit.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The scope-first rules on the application entry point:
 *
 * <ul>
 *   <li>the user chooses a depth, never a page count — a draft stores the depth
 *       and no length;</li>
 *   <li>starting requires credits for the high end of Scrivetta's estimate for
 *       that depth; below it, {@link EbookService#start} is refused with an
 *       explanation, before any state is mutated or any credits held — the book is
 *       never planned smaller to fit the balance;</li>
 *   <li>the hold is a credit ceiling against the per-book safety cap, not a length;</li>
 *   <li>the scope/budget endpoints report estimates per depth.</li>
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
    @Mock BookBlueprintService blueprintService;
    @Mock KnowledgeSourceRepository sourceRepository;
    @Mock BookKnowledgeRepository knowledgeRepository;

    CreditProperties creditProperties;
    ScopeEstimationService scopeEstimationService;
    EbookService ebookService;

    private final UUID userId = UUID.randomUUID();
    private final UUID ebookId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        creditProperties = new CreditProperties();
        scopeEstimationService = new ScopeEstimationService(sourceRepository, knowledgeRepository,
                blueprintService, creditProperties);
        ebookService = new EbookService(ebookRepository, chapterRepository, pdfRepository,
                generationService, pdfGenerationService, assetUsageService, creditService,
                scopeEstimationService, blueprintService);
        // No materials / blueprint → the legacy brief-only flow (what these tests cover).
        lenient().when(blueprintService.readiness(any()))
                .thenReturn(new BookBlueprintService.GenerationReadiness(
                        com.ebookwriter.SaaS.entity.GenerationMode.LEGACY, null));
        lenient().when(blueprintService.getBlueprint(any())).thenReturn(Optional.empty());
        lenient().when(sourceRepository.findByEbookIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
    }

    private User user() {
        User u = new User();
        u.setId(userId);
        return u;
    }

    private Ebook draft(BookDepth depth) {
        return Ebook.builder().id(ebookId).topic("Topic").depth(depth).status(EbookStatus.DRAFT).build();
    }

    private int required(BookDepth depth) {
        return scopeEstimationService.estimate(draft(depth)).requiredCredits();
    }

    @Test
    void createDraftStoresTheDepthAndNoPageCount() {
        when(ebookRepository.save(any(Ebook.class))).thenAnswer(i -> i.getArgument(0));
        EbookRequest request = new EbookRequest();
        request.setTopic("Focus");
        request.setDepth(BookDepth.COMPREHENSIVE);

        Ebook draft = ebookService.createDraft(user(), request);

        assertEquals(EbookStatus.DRAFT, draft.getStatus());
        assertEquals(BookDepth.COMPREHENSIVE, draft.getDepth());
        assertEquals(0, draft.getApproxPageCount(), "no page count is stored as an input");
        assertNull(draft.getEstimatedPagesHigh(), "the estimate is taken at start, not stored on the draft");
    }

    @Test
    void createDraftDefaultsToStandardDepth() {
        when(ebookRepository.save(any(Ebook.class))).thenAnswer(i -> i.getArgument(0));

        assertEquals(BookDepth.STANDARD, ebookService.createDraft(user(), new EbookRequest()).getDepth());
    }

    @Test
    void startIsRefusedWhenCreditsDoNotCoverTheEstimateWithoutMutatingAnything() {
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(draft(BookDepth.STANDARD)));
        int required = required(BookDepth.STANDARD);
        when(creditService.getBalance(userId)).thenReturn(required - 1);

        InsufficientCreditsException ex = assertThrows(InsufficientCreditsException.class,
                () -> ebookService.start(ebookId, userId));

        assertEquals(required, ex.getRequired());
        assertEquals(required - 1, ex.getAvailable());
        assertTrue(ex.getMessage().contains("Standard depth"), ex.getMessage());
        assertTrue(ex.getMessage().contains("needs up to " + required + " credits"), ex.getMessage());
        verify(ebookRepository, never()).claimForStart(any(), any(), any());
        verify(creditService, never()).reserveGenerationHold(any(), any(), anyInt(), anyInt());
        verify(generationService, never()).generate(any());
    }

    @Test
    void startWithEnoughCreditsReservesACreditCeilingAndRecordsTheEstimate() {
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(draft(BookDepth.STANDARD)));
        int required = required(BookDepth.STANDARD);
        when(creditService.getBalance(userId)).thenReturn(required);
        when(ebookRepository.claimForStart(ebookId, EbookStatus.DRAFT, EbookStatus.PENDING)).thenReturn(1);
        when(ebookRepository.save(any(Ebook.class))).thenAnswer(i -> i.getArgument(0));
        when(creditService.reserveGenerationHold(eq(userId), eq(ebookId),
                eq(creditProperties.getMaxGenerationBudget()), eq(required))).thenReturn(required + 10);

        Ebook started = ebookService.start(ebookId, userId);

        assertEquals(EbookStatus.PENDING, started.getStatus());
        assertEquals(required + 10, started.getPageBudget());
        ScopeEstimate estimate = scopeEstimationService.estimate(draft(BookDepth.STANDARD));
        assertEquals(estimate.pagesLow(), started.getEstimatedPagesLow());
        assertEquals(estimate.pagesHigh(), started.getEstimatedPagesHigh());
        verify(generationService).generate(ebookId);
    }

    @Test
    void deeperBooksNeedMoreCreditsToStart() {
        assertTrue(required(BookDepth.QUICK) < required(BookDepth.STANDARD));
        assertTrue(required(BookDepth.STANDARD) < required(BookDepth.COMPREHENSIVE));
    }

    @Test
    void aPlanThatAlreadyBouncedRaisesWhatIsNeeded() {
        Ebook bounced = draft(BookDepth.STANDARD);
        bounced.setPlannedPages(90);
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(bounced));
        when(creditService.getBalance(userId)).thenReturn(60);

        InsufficientCreditsException ex = assertThrows(InsufficientCreditsException.class,
                () -> ebookService.start(ebookId, userId));

        assertEquals(90, ex.getRequired());
        assertTrue(ex.getMessage().contains("planned this book at about 90 pages"), ex.getMessage());
    }

    @Test
    void changingTheDepthClearsAStalePlanAndReturnsTheNewScope() {
        Ebook d = draft(BookDepth.COMPREHENSIVE);
        d.setPlannedPages(140);
        d.setErrorMessage("needs more credits");
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(d));
        when(ebookRepository.save(any(Ebook.class))).thenAnswer(i -> i.getArgument(0));
        when(creditService.getBalance(userId)).thenReturn(500);

        BookScopeResponse scope = ebookService.updateDepth(ebookId, userId, BookDepth.QUICK);

        assertEquals(BookDepth.QUICK, d.getDepth());
        assertNull(d.getPlannedPages());
        assertNull(d.getErrorMessage());
        assertEquals(BookDepth.QUICK, scope.depth());
        assertEquals(3, scope.options().size());
        assertTrue(scope.canGenerate());
    }

    @Test
    void theDepthCannotChangeAfterGenerationStarted() {
        Ebook running = draft(BookDepth.STANDARD);
        running.setStatus(EbookStatus.WRITING);
        when(ebookRepository.findByIdAndUserId(ebookId, userId)).thenReturn(Optional.of(running));

        assertThrows(IllegalStateException.class, () -> ebookService.updateDepth(ebookId, userId, BookDepth.QUICK));
    }

    @Test
    void generationBudgetOffersEveryDepthWithAnEstimate() {
        when(creditService.getBalance(userId)).thenReturn(47);

        GenerationBudgetResponse info = ebookService.getGenerationBudget(userId, 120, 0);

        assertEquals(47, info.balance());
        assertEquals(BookDepth.STANDARD, info.defaultDepth());
        assertEquals(List.of(BookDepth.QUICK, BookDepth.STANDARD, BookDepth.COMPREHENSIVE),
                info.options().stream().map(ScopeEstimateDTO::depth).toList());
        for (ScopeEstimateDTO o : info.options()) {
            assertTrue(o.pagesLow() <= o.pagesHigh());
            assertEquals(o.pagesHigh(), o.requiredCredits());
        }
    }
}
