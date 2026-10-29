package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.service.credit.CreditPricing;
import com.ebookwriter.SaaS.service.credit.CreditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end billing rules, exercised against the real credit ledger (H2):
 *
 * <ul>
 *   <li>a book is billed for its <b>final</b> pages at the page rate (11 credits)
 *       — the user pays for the real rendered length, which may be under or (via
 *       overdraft) over the target;</li>
 *   <li>the balance may go negative down to {@code -maxOverdraft} pages (10 × 11
 *       = 110 credits), never further — a runaway is trimmed to the ceiling;</li>
 *   <li>billing is idempotent — a retried start or a retried settlement can never
 *       charge the same ebook twice.</li>
 * </ul>
 *
 * The scenarios reproduce the full lifecycle the pipeline runs:
 * {@code reserveGenerationHold} (start) → render (produces the real page count,
 * clamped to the ceiling) → {@code generationCharge} / {@code reconciledCharge} +
 * {@code refundUnusedHold}
 * (settlement).
 */
@SpringBootTest
class EbookBillingScenarioTest {

    /** Credits per page (the default policy). */
    private static final int RATE = 11;

    @Autowired
    CreditService creditService;
    @Autowired
    CreditPricing creditPricing;
    @Autowired
    EbookRepository ebookRepository;
    @Autowired
    UserRepository userRepository;

    /** Run one full generation lifecycle and return the resulting balance + charge. */
    private Result generate(int startingBalance, int target, int pagesModelWouldRender) {
        UUID user = UUID.randomUUID();
        UUID ebook = UUID.randomUUID();
        creditService.grant(user, startingBalance, CreditTransactionType.CREDIT_PURCHASE,
                null, UUID.randomUUID(), "buy");

        // Start: reserve the overdraft-aware ceiling.
        CreditService.GenerationHold hold = creditService.reserveGenerationHold(user, ebook, target, 1, RATE, 0);

        // Render: the PDF pipeline trims a runaway down to the ceiling (paragraph
        // boundary), so the real page count never exceeds it.
        int finalPages = Math.min(pagesModelWouldRender, hold.pages());

        // Settle: charge the real pages, refund the unused reservation.
        int pages = EbookGenerationService.reconciledPages(finalPages, hold.pages());
        int charge = EbookGenerationService.reconciledCharge(
                creditPricing.generationCharge(pages, RATE, 0, false), hold.credits());
        creditService.refundUnusedHold(user, hold.credits() - charge, 0, ebook);

        return new Result(charge, creditService.getBalance(user), finalPages);
    }

    @Test
    void case1_underTarget_chargesFewerPages() {
        Result r = generate(100 * RATE, 15, 13);
        assertEquals(13 * RATE, r.charged());
        assertEquals(87 * RATE, r.balance());
    }

    @Test
    void case2_exactlyTarget_chargesTarget() {
        Result r = generate(100 * RATE, 15, 15);
        assertEquals(15 * RATE, r.charged());
        assertEquals(85 * RATE, r.balance());
    }

    @Test
    void case3_overTarget_goesIntoOverdraft() {
        Result r = generate(15 * RATE, 15, 20);
        assertEquals(20 * RATE, r.charged());
        assertEquals(-5 * RATE, r.balance());
    }

    @Test
    void case4_overTarget_hitsTheOverdraftFloorExactly() {
        Result r = generate(15 * RATE, 15, 25);
        assertEquals(25 * RATE, r.charged());
        assertEquals(-10 * RATE, r.balance());
    }

    @Test
    void case5_runawayIsTrimmedAndNeverBreachesTheFloor() {
        // The model would have produced 200 pages; the ceiling caps the render at
        // 25 and the balance stops at the -10 floor.
        Result r = generate(15 * RATE, 15, 200);
        assertEquals(25, r.finalPages(), "render is trimmed to the reserved ceiling");
        assertEquals(25 * RATE, r.charged());
        assertEquals(-10 * RATE, r.balance());
        assertTrue(r.balance() >= -10 * RATE, "balance must never fall below -maxOverdraft pages");
    }

    @Test
    void case6_failedGenerationBeforeAPdfExistsChargesNothing() {
        UUID user = UUID.randomUUID();
        UUID ebook = UUID.randomUUID();
        creditService.grant(user, 15 * RATE, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");

        CreditService.GenerationHold hold = creditService.reserveGenerationHold(user, ebook, 15, 1, RATE, 0);
        assertEquals(-10 * RATE, creditService.getBalance(user));

        // Generation blows up before rendering: the pipeline refunds the whole
        // hold (this is exactly what EbookGenerationService.fail does).
        creditService.refundGeneration(user, hold.credits(), hold.fromSubscription(), ebook);

        assertEquals(15 * RATE, creditService.getBalance(user), "no PDF, no real pages, no charge");
    }

    @Test
    void case7_retriedStartCannotReserveTwice() {
        User user = persistUser();
        Ebook draft = persistDraft(user, 15);

        // First start wins the DRAFT -> PENDING claim; a retried/duplicate start
        // loses it, so it can never reserve a second hold.
        assertEquals(1, ebookRepository.claimForStart(draft.getId(), EbookStatus.DRAFT, EbookStatus.PENDING));
        assertEquals(0, ebookRepository.claimForStart(draft.getId(), EbookStatus.DRAFT, EbookStatus.PENDING));
    }

    @Test
    void case8_retriedSettlementCannotChargeTwice() {
        User user = persistUser();
        Ebook draft = persistDraft(user, 15);

        // The true-up is claimed exactly once, so a retried worker / refreshed
        // result cannot run the charge+refund a second time.
        assertEquals(1, ebookRepository.markReconciled(draft.getId()));
        assertEquals(0, ebookRepository.markReconciled(draft.getId()));
    }

    private User persistUser() {
        return userRepository.save(User.builder()
                .displayName("Test")
                .email("bill-" + UUID.randomUUID() + "@example.com")
                .password("x")
                .enabled(true)
                .build());
    }

    private Ebook persistDraft(User user, int target) {
        return ebookRepository.save(Ebook.builder()
                .user(user)
                .topic("Topic")
                .depth(com.ebookwriter.SaaS.entity.BookDepth.STANDARD)
                .status(EbookStatus.DRAFT)
                .build());
    }

    @Test
    void case9_imagesAreBilledPerImageOnTopOfPages() {
        // 120 pages, 6 illustrations and a cover, at the default policy.
        int charge = creditPricing.generationCharge(120, RATE, 6, true);
        assertEquals(120 * RATE + 6 * 15 + 30, charge);
        // Illustrations beyond the per-book cap are never billed.
        assertEquals(charge, creditPricing.generationCharge(120, RATE, 9, true));
        // A legacy book (1 credit per page) keeps its old page rate.
        assertEquals(120, creditPricing.generationCharge(120, 1, 0, false));
    }

    @Test
    void case10_referenceWorkloadUsesAboutThreeThousandCredits() {
        // ~265 text pages + 6 illustrations + a cover ≈ the 3,000 PRO allowance.
        int charge = creditPricing.generationCharge(265, RATE, 6, true);
        assertTrue(charge >= 2900 && charge <= 3100, "reference book costs " + charge);
    }

    private record Result(int charged, int balance, int finalPages) {
    }
}
