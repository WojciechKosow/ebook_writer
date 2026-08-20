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
 * {@link CreditTransaction} row, so the balance is always explainable. Spends
 * use a conditional atomic UPDATE so two concurrent generations can never spend
 * the same credits or push the balance negative.
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
     * Spend credits atomically. Throws {@link InsufficientCreditsException} if
     * the balance can't cover the amount (checked in the same UPDATE, so it is
     * race-free).
     */
    @Transactional
    public void spend(UUID userId, int amount, CreditTransactionType type, UUID ebookId, String description) {
        requirePositive(amount);
        ensureWallet(userId);

        int updated = balanceRepository.deductIfEnough(userId, amount);
        if (updated == 0) {
            throw new InsufficientCreditsException(amount, currentBalance(userId));
        }

        int balanceAfter = currentBalance(userId);
        writeLedger(userId, type, -amount, balanceAfter, ebookId, null, null, description);
        log.info("Spent {} credits for user {} ({}), balance now {}", amount, userId, type, balanceAfter);
    }

    /** Grant credits (subscription, purchase, refund, signup bonus). */
    @Transactional
    public void grant(UUID userId, int amount, CreditTransactionType type,
                      UUID ebookId, UUID paymentOrderId, String description) {
        requirePositive(amount);
        ensureWallet(userId);

        balanceRepository.increment(userId, amount);
        int balanceAfter = currentBalance(userId);
        writeLedger(userId, type, amount, balanceAfter, ebookId, paymentOrderId, null, description);
        log.info("Granted {} credits to user {} ({}), balance now {}", amount, userId, type, balanceAfter);
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
        ensureWallet(userId);
        balanceRepository.decrement(userId, amount);
        int balanceAfter = currentBalance(userId);
        writeLedger(userId, CreditTransactionType.REFUND_CLAWBACK, -amount, balanceAfter,
                null, null, stripeReference, description);
        log.info("Reclaimed {} credits from user {} (ref {}), balance now {}",
                amount, userId, stripeReference, balanceAfter);
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

    private void ensureWallet(UUID userId) {
        if (balanceRepository.existsById(userId)) {
            return;
        }
        int bonus = Math.max(0, creditProperties.getSignupBonus());
        try {
            balanceRepository.save(CreditBalance.builder().userId(userId).balance(bonus).build());
            if (bonus > 0) {
                writeLedger(userId, CreditTransactionType.SIGNUP_BONUS, bonus, bonus, null, null, null, "Signup bonus");
            }
        } catch (DataIntegrityViolationException e) {
            // Created concurrently by another request — that's fine.
            log.debug("Wallet for {} already created concurrently", userId);
        }
    }

    private int currentBalance(UUID userId) {
        return balanceRepository.findById(userId).map(CreditBalance::getBalance).orElse(0);
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
