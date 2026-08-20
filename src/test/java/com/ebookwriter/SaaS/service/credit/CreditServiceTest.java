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
