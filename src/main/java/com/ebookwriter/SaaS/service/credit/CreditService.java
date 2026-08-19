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
        writeLedger(userId, type, -amount, balanceAfter, ebookId, null, description);
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
        writeLedger(userId, type, amount, balanceAfter, ebookId, paymentOrderId, description);
        log.info("Granted {} credits to user {} ({}), balance now {}", amount, userId, type, balanceAfter);
    }

    /** Convenience for returning credits after a failed generation. */
    @Transactional
    public void refundGeneration(UUID userId, int amount, UUID ebookId) {
        grant(userId, amount, CreditTransactionType.GENERATION_REFUND, ebookId, null,
                "Refund for failed generation");
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
                writeLedger(userId, CreditTransactionType.SIGNUP_BONUS, bonus, bonus, null, null, "Signup bonus");
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
                             UUID ebookId, UUID paymentOrderId, String description) {
        transactionRepository.save(CreditTransaction.builder()
                .userId(userId)
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .ebookId(ebookId)
                .paymentOrderId(paymentOrderId)
                .description(description)
                .build());
    }

    private void requirePositive(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
    }
}
