package com.ebookwriter.SaaS.service.credit;

import com.ebookwriter.SaaS.entity.BookDepth;
import com.ebookwriter.SaaS.entity.CreditBalance;
import com.ebookwriter.SaaS.entity.CreditTransaction;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookStatus;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.CreditBalanceRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one-time conversion of legacy wallets (1 credit = 1 finished page) into
 * current credits: value-preserving at the page rate, all converted credits
 * become non-expiring purchased credits, in-flight books are converted with the
 * wallet, it is idempotent, and nothing touches a legacy wallet before it runs.
 */
@SpringBootTest
class LegacyCreditMigrationTest {

    @Autowired LegacyCreditMigration migration;
    @Autowired CreditService creditService;
    @Autowired CreditBalanceRepository balanceRepository;
    @Autowired EbookRepository ebookRepository;
    @Autowired UserRepository userRepository;

    @Test
    void aLegacyBalanceKeepsWhatItBuysAndBecomesNonExpiring() {
        User user = persistUser();
        legacyWallet(user.getId(), 150);

        migration.migrateAll();

        CreditService.Wallet wallet = creditService.getWallet(user.getId());
        assertEquals(150 * 11, wallet.balance(), "150 page credits buy 150 pages = 1,650 credits");
        assertEquals(0, wallet.subscriptionCredits());
        assertEquals(1650, wallet.purchasedCredits(), "converted credits never expire");
        CreditTransaction row = creditService.recentTransactions(user.getId()).get(0);
        assertEquals(CreditTransactionType.ECONOMY_MIGRATION, row.getType());
        assertEquals(1500, row.getAmount());
        assertEquals(1650, row.getBalanceAfter());
    }

    @Test
    void theMigrationIsIdempotent() {
        User user = persistUser();
        legacyWallet(user.getId(), 25);

        migration.migrateAll();
        migration.migrateAll();

        assertEquals(275, creditService.getBalance(user.getId()), "never converted twice");
        assertEquals(1, creditService.recentTransactions(user.getId()).stream()
                .filter(t -> t.getType() == CreditTransactionType.ECONOMY_MIGRATION).count());
    }

    @Test
    void aBookInProgressIsConvertedWithItsWalletSoItsRefundIsInTheNewUnit() {
        User user = persistUser();
        legacyWallet(user.getId(), 20);
        Ebook running = ebookRepository.save(Ebook.builder().user(user).topic("T").depth(BookDepth.STANDARD)
                .status(EbookStatus.WRITING).pageBudget(40).creditsCharged(40).build());
        Ebook finished = ebookRepository.save(Ebook.builder().user(user).topic("Done").depth(BookDepth.STANDARD)
                .status(EbookStatus.COMPLETED).pageBudget(40).creditsCharged(33).creditsReconciled(true).build());

        migration.migrateAll();

        Ebook converted = ebookRepository.findById(running.getId()).orElseThrow();
        assertEquals(440, converted.getCreditsCharged());
        assertEquals(11, converted.getCreditsPerPage());
        assertEquals(40, converted.getPageBudget(), "the page ceiling is unchanged");
        Ebook history = ebookRepository.findById(finished.getId()).orElseThrow();
        assertEquals(33, history.getCreditsCharged(), "settled history is never rewritten");
        assertEquals(1, history.getCreditsPerPage());

        // If the running book now fails, its whole hold comes back in current credits.
        creditService.refundGeneration(user.getId(), converted.getCreditsCharged(), 0, converted.getId());
        assertEquals((20 + 40) * 11, creditService.getBalance(user.getId()));
    }

    @Test
    void aLegacyWalletCannotBeSpentOrToppedUpBeforeItIsConverted() {
        User user = persistUser();
        legacyWallet(user.getId(), 10);

        assertThrows(IllegalStateException.class, () -> creditService.spend(user.getId(), 1,
                CreditTransactionType.COVER_GENERATION, null, "cover"));
        assertThrows(IllegalStateException.class, () -> creditService.grant(user.getId(), 500,
                CreditTransactionType.CREDIT_PURCHASE, null, null, "pack"));
        // Reads already show what the balance will be worth.
        assertEquals(110, creditService.getBalance(user.getId()));
        assertEquals(10, balanceRepository.findById(user.getId()).orElseThrow().getBalance(), "nothing changed");
    }

    private void legacyWallet(UUID userId, int balance) {
        balanceRepository.saveAndFlush(CreditBalance.builder().userId(userId).balance(balance)
                .economyVersion(CreditBalance.ECONOMY_LEGACY).build());
    }

    private User persistUser() {
        return userRepository.save(User.builder().displayName("Legacy")
                .email("legacy-" + UUID.randomUUID() + "@example.com").password("x").enabled(true).build());
    }
}
