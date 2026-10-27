package com.ebookwriter.SaaS.service.credit;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.entity.CreditBalance;
import com.ebookwriter.SaaS.entity.CreditTransaction;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.repository.CreditBalanceRepository;
import com.ebookwriter.SaaS.repository.CreditTransactionRepository;
import com.ebookwriter.SaaS.repository.EbookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Converts wallets from the old economy — where <b>one credit bought one
 * finished page</b> — into current credits, once, at startup.
 *
 * <p><b>The rule</b> (deterministic, documented in docs/PRICING.md): a legacy
 * credit is worth {@code credits.migration.legacy-credit-value} current credits,
 * by default {@code credits.pricing.credits-per-page} (11) — exactly what one
 * finished page costs now — so every balance keeps what it buys. All converted
 * credits become <em>purchased</em> (non-expiring) credits: legacy credits never
 * expired, and the next PRO renewal then grants the new monthly allowance on top.
 * Books still holding legacy credits (started but not settled) are converted
 * with the wallet — their hold is multiplied and stamped with the same page rate —
 * so their eventual true-up or refund is in the new unit.
 *
 * <p><b>Safe to run repeatedly:</b> each wallet is converted in its own
 * transaction under its row lock and stamped {@code economyVersion = 2}; converted
 * wallets are never touched again. Until a wallet is converted, {@link CreditService}
 * refuses to spend from or grant to it, so old page credits are never read as new
 * credits. Every conversion writes an {@code ECONOMY_MIGRATION} ledger row (old
 * balance, factor, new balance), which is what a rollback works from.
 *
 * <p>Runs after {@code SchemaConstraintPatch} (which drops the stale enum check
 * on {@code credit_transactions.type}).
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class LegacyCreditMigration implements ApplicationRunner {

    private final CreditProperties creditProperties;
    private final CreditBalanceRepository balanceRepository;
    private final CreditTransactionRepository transactionRepository;
    private final EbookRepository ebookRepository;
    private final PlatformTransactionManager transactionManager;

    @Override
    public void run(ApplicationArguments args) {
        if (!creditProperties.getMigration().isEnabled()) {
            log.warn("[credits] Legacy credit migration is disabled — legacy wallets stay locked until it runs");
            return;
        }
        migrateAll();
    }

    /** Convert every legacy wallet. Returns how many were converted. */
    public int migrateAll() {
        List<UUID> legacy = balanceRepository.findUserIdsWithEconomyBelow(CreditBalance.ECONOMY_CURRENT);
        if (legacy.isEmpty()) {
            return 0;
        }
        int factor = creditProperties.resolveLegacyCreditValue();
        log.info("[credits] Converting {} legacy wallet(s) at {} credits per legacy credit", legacy.size(), factor);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int converted = 0;
        for (UUID userId : legacy) {
            try {
                Boolean done = tx.execute(status -> convert(userId, factor));
                if (Boolean.TRUE.equals(done)) {
                    converted++;
                }
            } catch (RuntimeException e) {
                // One bad row must not block the others; it stays locked and is
                // retried on the next start.
                log.error("[credits] Could not convert the wallet of user {}", userId, e);
            }
        }
        log.info("[credits] Legacy credit migration done: {} of {} wallet(s) converted", converted, legacy.size());
        return converted;
    }

    private boolean convert(UUID userId, int factor) {
        CreditBalance wallet = balanceRepository.findForUpdate(userId).orElse(null);
        if (wallet == null || wallet.getEconomyVersion() >= CreditBalance.ECONOMY_CURRENT) {
            return false; // converted concurrently (another instance) — nothing to do
        }
        int oldBalance = wallet.getBalance();
        int newBalance = Math.multiplyExact(oldBalance, factor);

        List<Ebook> holds = ebookRepository.findUnsettledHolds(userId, 1);
        for (Ebook ebook : holds) {
            int oldHold = ebook.getCreditsCharged();
            ebook.setCreditsCharged(Math.multiplyExact(oldHold, factor));
            ebook.setCreditsPerPage(factor);
            ebook.setSubscriptionCreditsHeld(0);
            ebookRepository.save(ebook);
            log.info("[credits] Converted the hold of ebook {} ({} -> {} credits)", ebook.getId(),
                    oldHold, ebook.getCreditsCharged());
        }

        wallet.setBalance(newBalance);
        wallet.setSubscriptionCredits(0);
        wallet.setEconomyVersion(CreditBalance.ECONOMY_CURRENT);
        balanceRepository.save(wallet);

        transactionRepository.save(CreditTransaction.builder()
                .userId(userId)
                .type(CreditTransactionType.ECONOMY_MIGRATION)
                .amount(newBalance - oldBalance)
                .balanceAfter(newBalance)
                .description(("Credit system update: %d page credit(s) converted to %d credits (%d per page "
                        + "credit — one finished page)%s").formatted(oldBalance, newBalance, factor,
                        holds.isEmpty() ? "" : "; " + holds.size() + " book(s) in progress converted too"))
                .build());
        log.info("[credits] Converted wallet of user {}: {} -> {} credits", userId, oldBalance, newBalance);
        return true;
    }
}
