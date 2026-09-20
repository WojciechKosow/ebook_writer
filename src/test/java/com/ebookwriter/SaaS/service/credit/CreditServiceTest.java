package com.ebookwriter.SaaS.service.credit;

import com.ebookwriter.SaaS.entity.CreditTransaction;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.exceptions.InsufficientCreditsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the credit ledger against the in-memory H2 database: grants,
 * spends, the insufficient-funds guard, and refunds — plus that every change
 * is recorded in the ledger.
 */
@SpringBootTest
class CreditServiceTest {

    @Autowired
    CreditService creditService;

    @Test
    void grantSpendRefundKeepsBalanceAndLedgerConsistent() {
        UUID user = UUID.randomUUID();

        assertEquals(0, creditService.getBalance(user));

        creditService.grant(user, 100, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");
        assertEquals(100, creditService.getBalance(user));

        UUID ebook = UUID.randomUUID();
        creditService.spend(user, 30, CreditTransactionType.GENERATION, ebook, "gen");
        assertEquals(70, creditService.getBalance(user));

        creditService.refundGeneration(user, 30, ebook);
        assertEquals(100, creditService.getBalance(user));

        List<CreditTransaction> tx = creditService.recentTransactions(user);
        assertEquals(3, tx.size());
        // Newest first: refund, spend, grant.
        assertEquals(CreditTransactionType.GENERATION_REFUND, tx.get(0).getType());
        assertEquals(30, tx.get(0).getAmount());
        assertEquals(-30, tx.get(1).getAmount());
        assertEquals(100, tx.get(2).getAmount());
    }

    @Test
    void reservationHoldIsTrueDownToActualPages() {
        UUID user = UUID.randomUUID();
        UUID ebook = UUID.randomUUID();
        creditService.grant(user, 100, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");

        // Reserve a 60-page hold up front, then refund the unused part after the
        // real render came in at 42 pages.
        creditService.spend(user, 60, CreditTransactionType.GENERATION, ebook, "hold");
        assertEquals(40, creditService.getBalance(user));

        creditService.refundUnusedHold(user, 60 - 42, ebook);
        assertEquals(58, creditService.getBalance(user), "user pays for 42 pages, keeps the rest");

        CreditTransaction adjustment = creditService.recentTransactions(user).get(0);
        assertEquals(CreditTransactionType.GENERATION_ADJUSTMENT, adjustment.getType());
        assertEquals(18, adjustment.getAmount());
    }

    @Test
    void refundUnusedHoldIsNoOpWhenTheWholeBudgetWasUsed() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 50, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");
        creditService.spend(user, 50, CreditTransactionType.GENERATION, UUID.randomUUID(), "hold");

        creditService.refundUnusedHold(user, 0, UUID.randomUUID());

        assertEquals(0, creditService.getBalance(user));
        // Only the grant and the spend were recorded — no adjustment row.
        assertEquals(2, creditService.recentTransactions(user).size());
    }

    @Test
    void reserveGenerationHoldReservesTargetPlusOverdraftAndMayGoNegative() {
        UUID user = UUID.randomUUID();
        UUID ebook = UUID.randomUUID();
        creditService.grant(user, 15, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");

        // Target 15 with a balance of 15: ceiling = min(15,15) + 10 overdraft = 25.
        int hold = creditService.reserveGenerationHold(user, ebook, 15);

        assertEquals(25, hold);
        assertEquals(-10, creditService.getBalance(user), "hold drives the balance down to exactly -maxOverdraft");

        CreditTransaction tx = creditService.recentTransactions(user).get(0);
        assertEquals(CreditTransactionType.GENERATION, tx.getType());
        assertEquals(-25, tx.getAmount());
        assertEquals(-10, tx.getBalanceAfter());
    }

    @Test
    void reserveGenerationHoldIsTargetBoundedSoALargeBalanceIsNotDrained() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 100, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");

        // A 15-page target never reserves more than target + overdraft, so a rich
        // wallet keeps plenty for other generations.
        int hold = creditService.reserveGenerationHold(user, UUID.randomUUID(), 15);

        assertEquals(25, hold);
        assertEquals(75, creditService.getBalance(user));
    }

    @Test
    void reserveGenerationHoldNeverDrivesBelowMaxOverdraft() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 5, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");

        // Balance 5, target 15: ceiling = min(15,5) + 10 = 15, so the floor (-10)
        // is respected even though the target is far above the balance.
        int hold = creditService.reserveGenerationHold(user, UUID.randomUUID(), 15);

        assertEquals(15, hold);
        assertEquals(-10, creditService.getBalance(user));
    }

    @Test
    void reserveGenerationHoldRequiresAtLeastOneCreditToStart() {
        UUID user = UUID.randomUUID();

        // A brand-new wallet (balance 0) cannot start a generation — overdraft is a
        // tolerance for overshoot, not a way to generate for free.
        InsufficientCreditsException ex = assertThrows(
                InsufficientCreditsException.class,
                () -> creditService.reserveGenerationHold(user, UUID.randomUUID(), 15));
        assertEquals(1, ex.getRequired());
        assertEquals(0, creditService.getBalance(user), "a rejected reservation changes nothing");

        // A second generation while already overdrawn is likewise refused.
        creditService.grant(user, 15, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");
        creditService.reserveGenerationHold(user, UUID.randomUUID(), 15); // -> -10
        assertThrows(InsufficientCreditsException.class,
                () -> creditService.reserveGenerationHold(user, UUID.randomUUID(), 15));
    }

    @Test
    void spendingMoreThanBalanceIsRejectedAndChangesNothing() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 20, CreditTransactionType.CREDIT_PURCHASE, null, null, "buy");

        InsufficientCreditsException ex = assertThrows(
                InsufficientCreditsException.class,
                () -> creditService.spend(user, 50, CreditTransactionType.GENERATION, UUID.randomUUID(), "gen"));

        assertEquals(50, ex.getRequired());
        assertEquals(20, ex.getAvailable());
        assertEquals(20, creditService.getBalance(user), "balance must be untouched after a rejected spend");
    }

    @Test
    void clawbackCanGoNegativeAndBlocksFurtherSpending() {
        UUID user = UUID.randomUUID();
        String pi = "pi_" + UUID.randomUUID();

        creditService.grant(user, 100, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");
        creditService.spend(user, 90, CreditTransactionType.GENERATION, UUID.randomUUID(), "gen");
        assertEquals(10, creditService.getBalance(user));

        // Full refund reclaims all 100 granted — the spent-down balance goes negative.
        int reclaimed = creditService.clawback(user, 100, pi, "Refund");
        assertEquals(100, reclaimed);
        assertEquals(-90, creditService.getBalance(user));

        // A negative balance blocks any further spend.
        assertThrows(InsufficientCreditsException.class,
                () -> creditService.spend(user, 1, CreditTransactionType.GENERATION, UUID.randomUUID(), "gen"));
    }

    @Test
    void clawedForReferenceTracksReclaimsPerCharge() {
        UUID user = UUID.randomUUID();
        String pi = "pi_" + UUID.randomUUID();

        creditService.grant(user, 60, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");
        assertEquals(0, creditService.clawedForReference(pi));

        // A partial (half) refund, then the rest — the tracker is the sum for that charge.
        creditService.clawback(user, 30, pi, "Refund");
        assertEquals(30, creditService.clawedForReference(pi));

        creditService.clawback(user, 30, pi, "Refund");
        assertEquals(60, creditService.clawedForReference(pi));
        assertEquals(0, creditService.getBalance(user));

        // A different charge is tracked independently.
        assertEquals(0, creditService.clawedForReference("pi_other"));
    }
}
