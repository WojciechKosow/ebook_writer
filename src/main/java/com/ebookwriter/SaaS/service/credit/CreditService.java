package com.ebookwriter.SaaS.service.credit;

import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.entity.CreditBalance;
import com.ebookwriter.SaaS.entity.CreditTransaction;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.exceptions.InsufficientCreditsException;
import com.ebookwriter.SaaS.repository.CreditBalanceRepository;
import com.ebookwriter.SaaS.repository.CreditTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The credit ledger. Every balance change goes through here and writes a
 * {@link CreditTransaction} row, so the balance is always explainable.
 *
 * <p>Concurrency and correctness follow the proven bossAi wallet pattern: the
 * balance row is taken under a pessimistic {@code SELECT ... FOR UPDATE} lock
 * ({@link CreditBalanceRepository#findForUpdate}), mutated as a <em>managed</em>
 * entity, and saved. Two concurrent generations therefore serialise on the row
 * and can never both spend the same credits or push the balance negative.
 *
 * <p>Deliberately <b>not</b> using a bulk {@code @Modifying(clearAutomatically =
 * true)} update: that clears the whole persistence context mid-transaction,
 * which used to detach the {@code PaymentOrder} the Stripe webhook was
 * fulfilling and blow up its follow-up save with an optimistic-lock failure —
 * rolling back the whole webhook so credits were never delivered and the order
 * stayed stuck on "processing".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditService {

    private final CreditBalanceRepository balanceRepository;
    private final CreditTransactionRepository transactionRepository;
    private final CreditProperties creditProperties;

    @Transactional(readOnly = true)
    public int getBalance(UUID userId) {
        return balanceRepository.findById(userId).map(CreditBalance::getBalance).orElse(0);
    }

    @Transactional(readOnly = true)
    public List<CreditTransaction> recentTransactions(UUID userId) {
        return transactionRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Spend credits. The wallet is locked for the transaction and the balance is
     * checked against the amount under that lock, so the check is race-free.
     * Throws {@link InsufficientCreditsException} if the balance can't cover it.
     */
    @Transactional
    public void spend(UUID userId, int amount, CreditTransactionType type, UUID ebookId, String description) {
        requirePositive(amount);
        CreditBalance wallet = lockOrCreateWallet(userId);

        if (wallet.getBalance() < amount) {
            throw new InsufficientCreditsException(amount, wallet.getBalance());
        }

        wallet.setBalance(wallet.getBalance() - amount);
        balanceRepository.save(wallet);
        writeLedger(userId, type, -amount, wallet.getBalance(), ebookId, null, null, description);
        log.info("Spent {} credits for user {} ({}), balance now {}", amount, userId, type, wallet.getBalance());
    }

    /** Grant credits (subscription, purchase, refund, signup bonus). */
    @Transactional
    public void grant(UUID userId, int amount, CreditTransactionType type,
                      UUID ebookId, UUID paymentOrderId, String description) {
        requirePositive(amount);
        CreditBalance wallet = lockOrCreateWallet(userId);

        wallet.setBalance(wallet.getBalance() + amount);
        balanceRepository.save(wallet);
        writeLedger(userId, type, amount, wallet.getBalance(), ebookId, paymentOrderId, null, description);
        log.info("Granted {} credits to user {} ({}), balance now {}", amount, userId, type, wallet.getBalance());
    }

    /** Convenience for returning credits after a failed generation. */
    @Transactional
    public void refundGeneration(UUID userId, int amount, UUID ebookId) {
        grant(userId, amount, CreditTransactionType.GENERATION_REFUND, ebookId, null,
                "Refund for failed generation");
    }

    /**
     * Reclaim credits after a Stripe refund or chargeback. Unlike {@link #spend}
     * this is unconditional and MAY drive the balance negative — that is
     * deliberate: a user who spent granted credits and then refunded or charged
     * back is left with a deficit that blocks further generation until they pay
     * again. {@code stripeReference} (the payment_intent) makes repeated events
     * for the same charge idempotent when combined with {@link #clawedForReference}.
     *
     * @return the number of credits actually reclaimed (0 if amount ≤ 0).
     */
    @Transactional
    public int clawback(UUID userId, int amount, String stripeReference, String description) {
        if (amount <= 0) {
            return 0;
        }
        CreditBalance wallet = lockOrCreateWallet(userId);
        wallet.setBalance(wallet.getBalance() - amount); // allowed to go negative
        balanceRepository.save(wallet);
        writeLedger(userId, CreditTransactionType.REFUND_CLAWBACK, -amount, wallet.getBalance(),
                null, null, stripeReference, description);
        log.info("Reclaimed {} credits from user {} (ref {}), balance now {}",
                amount, userId, stripeReference, wallet.getBalance());
        return amount;
    }

    /** How many credits have already been reclaimed for a given Stripe charge. */
    @Transactional(readOnly = true)
    public int clawedForReference(String stripeReference) {
        if (stripeReference == null || stripeReference.isBlank()) {
            return 0;
        }
        return transactionRepository.findByStripeReference(stripeReference).stream()
                .filter(t -> t.getType() == CreditTransactionType.REFUND_CLAWBACK)
                .mapToInt(t -> -t.getAmount()) // stored negative → positive reclaimed
                .sum();
    }

    // ------------------------------------------------------------------------

    /**
     * Return the wallet row locked FOR UPDATE, creating it (with the optional
     * signup bonus) on first use. Mirrors bossAi's lock-or-create wallet flow.
     */
    private CreditBalance lockOrCreateWallet(UUID userId) {
        return balanceRepository.findForUpdate(userId)
                .orElseGet(() -> createWallet(userId));
    }

    private CreditBalance createWallet(UUID userId) {
        int bonus = Math.max(0, creditProperties.getSignupBonus());
        try {
            CreditBalance wallet = balanceRepository.saveAndFlush(
                    CreditBalance.builder().userId(userId).balance(bonus).build());
            if (bonus > 0) {
                writeLedger(userId, CreditTransactionType.SIGNUP_BONUS, bonus, bonus, null, null, null, "Signup bonus");
            }
            return wallet;
        } catch (DataIntegrityViolationException e) {
            // Created concurrently by another request — re-read under the lock.
            log.debug("Wallet for {} already created concurrently", userId);
            return balanceRepository.findForUpdate(userId)
                    .orElseThrow(() -> new IllegalStateException("Wallet vanished after concurrent create for " + userId));
        }
    }

    private void writeLedger(UUID userId, CreditTransactionType type, int amount, int balanceAfter,
                             UUID ebookId, UUID paymentOrderId, String stripeReference, String description) {
        transactionRepository.save(CreditTransaction.builder()
                .userId(userId)
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .ebookId(ebookId)
                .paymentOrderId(paymentOrderId)
                .stripeReference(stripeReference)
                .description(description)
                .build());
    }

    private void requirePositive(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
    }
}
