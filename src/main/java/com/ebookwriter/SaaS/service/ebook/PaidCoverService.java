package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.dto.EbookImageDTO;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.service.credit.CreditPricing;
import com.ebookwriter.SaaS.service.credit.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Regenerating a book's AI cover on request — a paid operation (a planning call
 * plus a high-quality image). The credits are taken <em>before</em> the call,
 * in their own transaction under the wallet lock (so the balance can never go
 * negative and the lock is not held through the slow image request), and
 * returned to the kind they came from if the generation fails.
 *
 * <p>Deliberately not {@code @Transactional}: the spend, the generation and the
 * refund each commit on their own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaidCoverService {

    private final CoverGenerationService coverGenerationService;
    private final CreditService creditService;
    private final CreditPricing creditPricing;
    private final EbookRepository ebookRepository;

    public EbookImageDTO regenerate(UUID ebookId, UUID userId) {
        // Check everything that would fail before charging anything.
        ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
        if (!coverGenerationService.canGenerate()) {
            throw new IllegalStateException("Cover generation is not available (image generation is not configured).");
        }

        int cost = creditPricing.creditsPerCover();
        CreditService.Debit debit = cost > 0
                ? creditService.spend(userId, cost, CreditTransactionType.COVER_GENERATION, ebookId,
                "Cover regeneration (" + cost + " credits)")
                : null;
        try {
            return coverGenerationService.regenerate(ebookId, userId);
        } catch (RuntimeException e) {
            if (debit != null) {
                log.warn("Cover regeneration for ebook {} failed — returning {} credits: {}",
                        ebookId, debit.credits(), e.getMessage());
                creditService.refundGeneration(userId, debit.credits(), debit.fromSubscription(), ebookId);
            }
            throw e;
        }
    }
}
