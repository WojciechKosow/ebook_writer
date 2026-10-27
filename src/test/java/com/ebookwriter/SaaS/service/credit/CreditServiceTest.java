package com.ebookwriter.SaaS.service.credit;

import com.ebookwriter.SaaS.entity.CreditTransaction;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.exceptions.InsufficientCreditsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the credit ledger against the in-memory H2 database: grants,
 * spends, holds, the two kinds of credits (monthly PRO and purchased), the
 * insufficient-funds guard, refunds, renewals and clawbacks — plus that every
 * change is recorded in the ledger. Overdraft is the default 10 pages.
 */
@SpringBootTest
class CreditServiceTest {

    /** The page rate the holds below are taken at. */
    private static final int RATE = 11;

    @Autowired
    CreditService creditService;

    @Test
    void grantSpendRefundKeepsBalanceAndLedgerConsistent() {
        UUID user = UUID.randomUUID();

        assertEquals(0, creditService.getBalance(user));

        creditService.grant(user, 100, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");
        assertEquals(100, creditService.getBalance(user));

        UUID ebook = UUID.randomUUID();
        CreditService.Debit debit = creditService.spend(user, 30, CreditTransactionType.GENERATION, ebook, "gen");
        assertEquals(70, creditService.getBalance(user));
        assertEquals(0, debit.fromSubscription(), "no monthly credits to take from");

        creditService.refundGeneration(user, 30, debit.fromSubscription(), ebook);
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
    void refundUnusedHoldIsNoOpWhenTheWholeHoldWasUsed() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 50, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");
        creditService.spend(user, 50, CreditTransactionType.GENERATION, UUID.randomUUID(), "hold");

        creditService.refundUnusedHold(user, 0, 0, UUID.randomUUID());

        assertEquals(0, creditService.getBalance(user));
        assertEquals(2, creditService.recentTransactions(user).size(), "no adjustment row");
    }

    // ---- generation holds ---------------------------------------------------

    @Test
    void holdIsPagesAtTheRatePlusOverdraftAndImageReserve() {
        UUID user = UUID.randomUUID();
        UUID ebook = UUID.randomUUID();
        creditService.grant(user, 2000, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");

        // Need 100 pages + 120 image credits = 1220. Affordable pages = (2000-120)/11 = 170,
        // capped at 150 by the safety maximum, + 10 overdraft pages = 160 pages.
        CreditService.GenerationHold hold = creditService.reserveGenerationHold(user, ebook, 150, 100, RATE, 120);

        assertEquals(160, hold.pages());
        assertEquals(160 * RATE + 120, hold.credits());
        assertEquals(2000 - hold.credits(), creditService.getBalance(user));
        CreditTransaction tx = creditService.recentTransactions(user).get(0);
        assertEquals(CreditTransactionType.GENERATION, tx.getType());
        assertEquals(-hold.credits(), tx.getAmount());
    }

    @Test
    void holdNeverDrivesTheBalanceBelowTheBoundedOverdraft() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 400, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");

        // Exactly enough for 20 pages + 120 for images: (400-120)/11 = 25 pages + 10 overdraft.
        CreditService.GenerationHold hold = creditService.reserveGenerationHold(user, UUID.randomUUID(), 400, 20, RATE, 120);

        assertEquals(35, hold.pages());
        int balance = creditService.getBalance(user);
        assertTrue(balance >= -10 * RATE, "never below -maxOverdraft pages at the rate, was " + balance);
        assertTrue(balance < 0, "the overdraft is used only as a tolerance");
    }

    @Test
    void holdRequiresTheEstimateHighEndAndARejectedHoldChangesNothing() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 500, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");

        // 50 pages at 11 + 120 = 670 needed.
        InsufficientCreditsException ex = assertThrows(InsufficientCreditsException.class,
                () -> creditService.reserveGenerationHold(user, UUID.randomUUID(), 400, 50, RATE, 120));
        assertEquals(670, ex.getRequired());
        assertEquals(500, ex.getAvailable());
        assertEquals(500, creditService.getBalance(user), "a rejected reservation changes nothing");
        assertEquals(1, creditService.recentTransactions(user).size());
    }

    @Test
    void anEmptyOrOverdrawnWalletCannotStart() {
        UUID user = UUID.randomUUID();
        assertThrows(InsufficientCreditsException.class,
                () -> creditService.reserveGenerationHold(user, UUID.randomUUID(), 400, 1, RATE, 0));

        creditService.grant(user, 11, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");
        creditService.reserveGenerationHold(user, UUID.randomUUID(), 400, 1, RATE, 0); // 1 + 10 pages
        assertEquals(11 - 11 * RATE, creditService.getBalance(user));
        assertThrows(InsufficientCreditsException.class,
                () -> creditService.reserveGenerationHold(user, UUID.randomUUID(), 400, 1, RATE, 0));
    }

    @Test
    void concurrentHoldsNeverOverspend() throws Exception {
        UUID user = UUID.randomUUID();
        // Enough for exactly one 100-page book (1100 + 0 extras) — not two.
        creditService.grant(user, 1500, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Boolean> start = () -> {
                go.await();
                try {
                    creditService.reserveGenerationHold(user, UUID.randomUUID(), 100, 100, RATE, 0);
                    return true;
                } catch (InsufficientCreditsException e) {
                    return false;
                }
            };
            results.add(pool.submit(start));
        }
        go.countDown();
        int started = 0;
        for (Future<Boolean> f : results) {
            if (f.get(30, TimeUnit.SECONDS)) {
                started++;
            }
        }
        pool.shutdown();

        assertEquals(1, started, "only one generation can be paid for");
        assertTrue(creditService.getBalance(user) >= -10 * RATE);
    }

    @Test
    void concurrentSpendsNeverGoNegative() throws Exception {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 100, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    creditService.spend(user, 30, CreditTransactionType.COVER_GENERATION, UUID.randomUUID(), "cover");
                    return true;
                } catch (InsufficientCreditsException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) {
            if (f.get(30, TimeUnit.SECONDS)) {
                ok++;
            }
        }
        pool.shutdown();

        assertEquals(3, ok);
        assertEquals(10, creditService.getBalance(user));
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

    // ---- monthly vs purchased credits ---------------------------------------

    @Test
    void monthlyCreditsAreSpentBeforePurchasedCredits() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 500, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "pack");
        assertTrue(creditService.startSubscriptionPeriod(user, 3000, "in_" + UUID.randomUUID(), "PRO"));

        CreditService.Debit debit = creditService.spend(user, 3200, CreditTransactionType.GENERATION,
                UUID.randomUUID(), "book");

        assertEquals(3000, debit.fromSubscription());
        CreditService.Wallet wallet = creditService.getWallet(user);
        assertEquals(300, wallet.balance());
        assertEquals(0, wallet.subscriptionCredits());
        assertEquals(300, wallet.purchasedCredits(), "pack credits are used last");
    }

    @Test
    void renewalExpiresUnusedMonthlyCreditsButKeepsPurchasedOnes() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 1200, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "pack");
        creditService.startSubscriptionPeriod(user, 3000, "in_1_" + user, "PRO");
        creditService.spend(user, 1000, CreditTransactionType.GENERATION, UUID.randomUUID(), "book");

        // 2000 monthly left over: they don't roll over.
        creditService.startSubscriptionPeriod(user, 3000, "in_2_" + user, "PRO renewal");

        CreditService.Wallet wallet = creditService.getWallet(user);
        assertEquals(3000, wallet.subscriptionCredits(), "a fresh allowance, not 5000");
        assertEquals(1200, wallet.purchasedCredits());
        assertEquals(4200, wallet.balance());
        assertTrue(creditService.recentTransactions(user).stream()
                .anyMatch(t -> t.getType() == CreditTransactionType.SUBSCRIPTION_EXPIRY && t.getAmount() == -2000));
    }

    @Test
    void theSameInvoiceNeverGrantsTwice() {
        UUID user = UUID.randomUUID();
        String invoice = "in_" + UUID.randomUUID();

        assertTrue(creditService.startSubscriptionPeriod(user, 3000, invoice, "PRO"));
        creditService.spend(user, 500, CreditTransactionType.GENERATION, UUID.randomUUID(), "book");
        assertFalse(creditService.startSubscriptionPeriod(user, 3000, invoice, "PRO (redelivered)"));

        assertEquals(2500, creditService.getBalance(user), "no second allowance and no early expiry");
    }

    @Test
    void endingProExpiresOnlyMonthlyCredits() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 500, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "pack");
        creditService.startSubscriptionPeriod(user, 3000, "in_" + UUID.randomUUID(), "PRO");

        assertEquals(3000, creditService.expireSubscriptionCredits(user, "PRO ended"));
        assertEquals(500, creditService.getBalance(user));
        assertEquals(0, creditService.expireSubscriptionCredits(user, "PRO ended (again)"));
    }

    @Test
    void anOverdraftIsSettledFromTheNextMonthlyCredits() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 11, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "pack");
        creditService.reserveGenerationHold(user, UUID.randomUUID(), 400, 1, RATE, 0); // 11 pages held -> -110
        assertEquals(-110, creditService.getBalance(user));

        creditService.startSubscriptionPeriod(user, 3000, "in_" + UUID.randomUUID(), "PRO");

        CreditService.Wallet wallet = creditService.getWallet(user);
        assertEquals(2890, wallet.balance());
        assertEquals(2890, wallet.subscriptionCredits());
        assertEquals(0, wallet.purchasedCredits());
    }

    @Test
    void refundsReturnCreditsToTheKindTheyCameFromPurchasedFirst() {
        UUID user = UUID.randomUUID();
        UUID ebook = UUID.randomUUID();
        creditService.grant(user, 1000, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "pack");
        creditService.startSubscriptionPeriod(user, 3000, "in_" + UUID.randomUUID(), "PRO");

        // Hold 3500: 3000 monthly + 500 purchased.
        CreditService.Debit hold = creditService.spend(user, 3500, CreditTransactionType.GENERATION, ebook, "hold");
        assertEquals(3000, hold.fromSubscription());

        // The book cost 3200, so 300 come back — all purchased (the charge is monthly first).
        int share = CreditService.subscriptionShareOfRefund(300, 3500, 3000);
        assertEquals(0, share);
        creditService.refundUnusedHold(user, 300, share, ebook);

        CreditService.Wallet wallet = creditService.getWallet(user);
        assertEquals(0, wallet.subscriptionCredits());
        assertEquals(800, wallet.purchasedCredits());

        // A larger refund spills into monthly credits once the purchased part is covered.
        assertEquals(100, CreditService.subscriptionShareOfRefund(600, 3500, 3000));
        assertEquals(3000, CreditService.subscriptionShareOfRefund(3500, 3500, 3000));
    }

    // ---- clawbacks ----------------------------------------------------------

    @Test
    void packClawbackTakesOnlyUnspentPurchasedCreditsAndNeverGoesNegative() {
        UUID user = UUID.randomUUID();
        String pi = "pi_" + UUID.randomUUID();

        creditService.grant(user, 500, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "pack");
        creditService.startSubscriptionPeriod(user, 3000, "in_" + UUID.randomUUID(), "PRO");
        // Spends 3300: all 3000 monthly + 300 of the pack.
        creditService.spend(user, 3300, CreditTransactionType.GENERATION, UUID.randomUUID(), "book");
        assertEquals(200, creditService.getBalance(user));

        int reclaimed = creditService.clawbackPurchased(user, 500, pi, "Refund");

        assertEquals(200, reclaimed, "only what is still unspent");
        assertEquals(0, creditService.getBalance(user), "never negative");
        assertEquals(500, creditService.clawedForReference(pi), "reclaimed + recorded shortfall");
        CreditTransaction row = creditService.recentTransactions(user).get(0);
        assertEquals(CreditTransactionType.REFUND_CLAWBACK, row.getType());
        assertEquals(300, row.getShortfall());
    }

    @Test
    void packClawbackLeavesMonthlyCreditsAlone() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 500, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "pack");
        creditService.startSubscriptionPeriod(user, 3000, "in_" + UUID.randomUUID(), "PRO");

        creditService.clawbackPurchased(user, 500, "pi_" + UUID.randomUUID(), "Refund");

        CreditService.Wallet wallet = creditService.getWallet(user);
        assertEquals(3000, wallet.subscriptionCredits());
        assertEquals(0, wallet.purchasedCredits());
    }

    @Test
    void subscriptionClawbackTakesOnlyUnspentMonthlyCredits() {
        UUID user = UUID.randomUUID();
        creditService.grant(user, 500, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "pack");
        creditService.startSubscriptionPeriod(user, 3000, "in_" + UUID.randomUUID(), "PRO");
        creditService.spend(user, 1000, CreditTransactionType.GENERATION, UUID.randomUUID(), "book");

        int reclaimed = creditService.clawbackSubscription(user, 3000, "pi_" + UUID.randomUUID(), "Refund");

        assertEquals(2000, reclaimed);
        assertEquals(500, creditService.getBalance(user), "pack credits untouched");
    }

    @Test
    void clawedForReferenceTracksReclaimsPerCharge() {
        UUID user = UUID.randomUUID();
        String pi = "pi_" + UUID.randomUUID();

        creditService.grant(user, 60, CreditTransactionType.CREDIT_PURCHASE, null, UUID.randomUUID(), "buy");
        assertEquals(0, creditService.clawedForReference(pi));

        creditService.clawbackPurchased(user, 30, pi, "Refund");
        assertEquals(30, creditService.clawedForReference(pi));

        creditService.clawbackPurchased(user, 30, pi, "Refund");
        assertEquals(60, creditService.clawedForReference(pi));
        assertEquals(0, creditService.getBalance(user));

        assertEquals(0, creditService.clawedForReference("pi_other"));
    }
}
