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
 * and can never both spend the same credits; every balance check happens under
 * that lock.
 *
 * <p>Deliberately <b>not</b> using a bulk {@code @Modifying(clearAutomatically =
 * true)} update: that clears the whole persistence context mid-transaction,
 * which used to detach the {@code PaymentOrder} the Stripe webhook was
 * fulfilling and blow up its follow-up save with an optimistic-lock failure —
 * rolling back the whole webhook so credits were never delivered and the order
 * stayed stuck on "processing".
 *
 * <p><b>Two kinds of credits</b> (see {@link CreditBalance}): the monthly PRO
 * allowance, which is replaced at each renewal, and purchased credits, which
 * never expire. Spending always takes monthly credits first; a refund returns
 * credits to the kind they were taken from, purchased first (the charge that
 * stands is attributed to monthly credits first, so the unused part of a hold is
 * purchased credits first).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditService {

    private final CreditBalanceRepository balanceRepository;
    private final CreditTransactionRepository transactionRepository;
    private final CreditProperties creditProperties;

    /** A wallet as the UI shows it. */
    public record Wallet(int balance, int subscriptionCredits, int purchasedCredits) {
        static Wallet of(CreditBalance b) {
            return new Wallet(b.getBalance(), b.getSubscriptionCredits(), b.purchasedCredits());
        }
    }

    /**
     * What a debit took: {@code credits} in total, {@code fromSubscription} of
     * them monthly credits (the rest purchased). Needed to refund it correctly.
     */
    public record Debit(int credits, int fromSubscription) {
    }

    /**
     * A reserved generation hold: the page ceiling the book may render
     * ({@code pages}) and the credits held for it, {@code fromSubscription} of
     * them monthly credits.
     */
    public record GenerationHold(int pages, int credits, int fromSubscription) {
    }

    @Transactional(readOnly = true)
    public int getBalance(UUID userId) {
        return getWallet(userId).balance();
    }

    @Transactional(readOnly = true)
    public Wallet getWallet(UUID userId) {
        return balanceRepository.findById(userId)
                .map(b -> b.getEconomyVersion() < CreditBalance.ECONOMY_CURRENT ? legacyPreview(b) : Wallet.of(b))
                .orElse(new Wallet(0, 0, 0));
    }

    @Transactional(readOnly = true)
    public List<CreditTransaction> recentTransactions(UUID userId) {
        return transactionRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Spend credits on a paid operation. The wallet is locked for the transaction
     * and the balance is checked against the amount under that lock, so the check
     * is race-free and the balance can never go negative. Monthly credits are
     * used first.
     *
     * @throws InsufficientCreditsException if the balance can't cover it
     */
    @Transactional
    public Debit spend(UUID userId, int amount, CreditTransactionType type, UUID ebookId, String description) {
        requirePositive(amount);
        CreditBalance wallet = lockOrCreateWallet(userId);

        if (wallet.getBalance() < amount) {
            throw new InsufficientCreditsException(amount, wallet.getBalance());
        }

        int fromSubscription = debit(wallet, amount);
        balanceRepository.save(wallet);
        writeLedger(userId, type, -amount, -fromSubscription, wallet.getBalance(), ebookId, null, null, 0, description);
        log.info("Spent {} credits for user {} ({}), balance now {}", amount, userId, type, wallet.getBalance());
        return new Debit(amount, fromSubscription);
    }

    /**
     * The smallest number of pages a user must be able to pay for to
     * <em>start</em> a generation. Overdraft is a tolerance for a book that
     * finishes slightly longer than expected — not a way to generate with an
     * empty (or negative) wallet.
     */
    static final int MIN_PAGES_TO_START = 1;

    /**
     * Reserve the up-front credit hold for a generation, with the bounded overdraft.
     *
     * <p>A book's length is a result of generation, so its real cost is only known
     * once the PDF is rendered. We therefore reserve a <b>ceiling</b>:
     * <ul>
     *   <li>{@code pages} — the most pages the book may render:
     *       {@code min(ceilingPages, affordablePages) + maxOverdraft}, where
     *       {@code affordablePages = (balance - extraCredits) / creditsPerPage};</li>
     *   <li>{@code credits} — {@code pages × creditsPerPage + extraCredits}, where
     *       {@code extraCredits} reserves the book's AI images.</li>
     * </ul>
     * Since the held credits never exceed what the balance covers plus
     * {@code maxOverdraft} pages, the balance can never fall below
     * {@code -maxOverdraft × creditsPerPage}, however long the model runs.
     *
     * <p>Race-safe: the minimum is checked under the same wallet lock as the
     * reservation, so two concurrent starts serialise and the second sees what
     * the first drew down. The unused part is returned by {@link #refundUnusedHold}
     * once the real cost is known, and the whole hold by {@link #refundGeneration}
     * if the generation fails.
     *
     * @param ceilingPages    the per-book safety maximum, in pages
     * @param minPagesToStart the pages the user must be able to pay for to start
     *                        (the estimate's high end)
     * @param creditsPerPage  the page rate the book is billed at
     * @param extraCredits    credits reserved besides pages (the image reserve)
     * @throws InsufficientCreditsException if the balance is below
     *         {@code minPagesToStart × creditsPerPage + extraCredits}
     */
    @Transactional
    public GenerationHold reserveGenerationHold(UUID userId, UUID ebookId, int ceilingPages, int minPagesToStart,
                                                int creditsPerPage, int extraCredits) {
        int cap = Math.max(1, ceilingPages);
        int rate = Math.max(1, creditsPerPage);
        int extra = Math.max(0, extraCredits);
        int minPages = Math.max(MIN_PAGES_TO_START, minPagesToStart);
        int maxOverdraft = Math.max(0, creditProperties.getMaxOverdraft());
        int minCredits = minPages * rate + extra;

        CreditBalance wallet = lockOrCreateWallet(userId);
        int balance = wallet.getBalance();
        if (balance < minCredits) {
            throw new InsufficientCreditsException(minCredits, balance);
        }

        int affordablePages = (balance - extra) / rate;
        int pages = Math.min(cap, affordablePages) + maxOverdraft;
        int credits = pages * rate + extra;

        int fromSubscription = debit(wallet, credits);
        balanceRepository.save(wallet);
        writeLedger(userId, CreditTransactionType.GENERATION, -credits, -fromSubscription, wallet.getBalance(),
                ebookId, null, null, 0,
                "Ebook generation hold (up to " + pages + " pages at " + rate + " credits"
                        + (extra > 0 ? ", " + extra + " for images" : "") + "; unused credits are returned)");
        log.info("Reserved generation hold of {} credits ({} pages × {} + {}) for user {} (balance {} -> {})",
                credits, pages, rate, extra, userId, balance, wallet.getBalance());
        return new GenerationHold(pages, credits, fromSubscription);
    }

    /**
     * Add {@code extraCredits} to a running generation's hold — when the user
     * approves a longer book than first reserved for. Same wallet lock as the
     * original reservation; the balance must cover the extension (no overdraft
     * beyond the one already in the hold). Trued up with the rest of the hold.
     *
     * @throws InsufficientCreditsException if the balance is below {@code extraCredits}
     */
    @Transactional
    public Debit extendGenerationHold(UUID userId, UUID ebookId, int extraCredits) {
        requirePositive(extraCredits);
        CreditBalance wallet = lockOrCreateWallet(userId);
        int balance = wallet.getBalance();
        if (balance < extraCredits) {
            throw new InsufficientCreditsException(("Continuing at this length needs %d more credits reserved "
                    + "(you only pay for what is actually produced) — you have %d. Add credits, or keep the "
                    + "book within the agreed length.").formatted(extraCredits, Math.max(0, balance)),
                    extraCredits, balance);
        }
        int fromSubscription = debit(wallet, extraCredits);
        balanceRepository.save(wallet);
        writeLedger(userId, CreditTransactionType.GENERATION, -extraCredits, -fromSubscription,
                wallet.getBalance(), ebookId, null, null, 0,
                "Ebook generation hold extension (" + extraCredits + " credits, approved longer book)");
        log.info("Extended generation hold of ebook {} by {} credits (balance {} -> {})",
                ebookId, extraCredits, balance, wallet.getBalance());
        return new Debit(extraCredits, fromSubscription);
    }

    /**
     * Grant purchased (non-expiring) credits: a credit pack or the signup bonus.
     * Monthly PRO credits are granted by {@link #startSubscriptionPeriod} instead.
     */
    @Transactional
    public void grant(UUID userId, int amount, CreditTransactionType type,
                      UUID ebookId, UUID paymentOrderId, String description) {
        requirePositive(amount);
        CreditBalance wallet = lockOrCreateWallet(userId);

        wallet.setBalance(wallet.getBalance() + amount);
        normalize(wallet);
        balanceRepository.save(wallet);
        writeLedger(userId, type, amount, 0, wallet.getBalance(), ebookId, paymentOrderId, null, 0, description);
        log.info("Granted {} credits to user {} ({}), balance now {}", amount, userId, type, wallet.getBalance());
    }

    /**
     * Return credits after a failed or cancelled generation (or a failed paid
     * operation): {@code fromSubscription} of them go back to the monthly
     * credits, the rest to purchased credits.
     */
    @Transactional
    public void refundGeneration(UUID userId, int amount, int fromSubscription, UUID ebookId) {
        refund(userId, amount, fromSubscription, CreditTransactionType.GENERATION_REFUND, ebookId,
                "Refund for work that was not completed");
    }

    /**
     * Return the unused part of a generation hold once the real cost is known.
     * No-op for {@code amount <= 0} (the book used its whole hold).
     */
    @Transactional
    public void refundUnusedHold(UUID userId, int amount, int fromSubscription, UUID ebookId) {
        if (amount <= 0) {
            return;
        }
        refund(userId, amount, fromSubscription, CreditTransactionType.GENERATION_ADJUSTMENT, ebookId,
                "Unused part of the generation hold (" + amount + " credits)");
    }

    /**
     * How much of a refund goes back to monthly credits. Purchased credits are
     * returned first: the charge that stands is attributed to monthly credits
     * first (the order credits are spent in), so what is left over is purchased
     * credits first.
     *
     * @param refund            credits being returned
     * @param held              credits currently held for the operation
     * @param heldFromSubscription the part of {@code held} that was monthly credits
     */
    public static int subscriptionShareOfRefund(int refund, int held, int heldFromSubscription) {
        int fromSubscription = Math.max(0, Math.min(heldFromSubscription, held));
        int purchasedHeld = Math.max(0, held - fromSubscription);
        int toPurchased = Math.min(Math.max(0, refund), purchasedHeld);
        return Math.min(fromSubscription, Math.max(0, refund) - toPurchased);
    }

    /**
     * Start a paid PRO billing period: the unused rest of the previous period's
     * monthly credits expires and {@code allowance} new monthly credits are
     * granted. Purchased credits are untouched. A deficit from a generation
     * overdraft is settled from the new credits.
     *
     * <p>Idempotent per invoice: the grant is recorded with the invoice id and
     * checked under the wallet lock, so a duplicated or re-sent {@code invoice.paid}
     * (or two concurrent deliveries) can never grant a period twice.
     *
     * @return false when this invoice's allowance was already granted
     */
    @Transactional
    public boolean startSubscriptionPeriod(UUID userId, int allowance, String invoiceId, String description) {
        requirePositive(allowance);
        if (invoiceId == null || invoiceId.isBlank()) {
            throw new IllegalArgumentException("invoiceId is required for a subscription grant");
        }
        CreditBalance wallet = lockOrCreateWallet(userId);
        if (transactionRepository.existsByStripeReferenceAndType(invoiceId, CreditTransactionType.SUBSCRIPTION_GRANT)) {
            log.info("Subscription credits for invoice {} already granted to user {} — skipping", invoiceId, userId);
            return false;
        }

        expireMonthly(wallet, userId, "Unused monthly credits expired at renewal");

        wallet.setBalance(wallet.getBalance() + allowance);
        wallet.setSubscriptionCredits(Math.max(0, Math.min(allowance, wallet.getBalance())));
        normalize(wallet);
        balanceRepository.save(wallet);
        writeLedger(userId, CreditTransactionType.SUBSCRIPTION_GRANT, allowance, wallet.getSubscriptionCredits(),
                wallet.getBalance(), null, null, invoiceId, 0, description);
        log.info("Granted {} monthly credits to user {} for invoice {}, balance now {}",
                allowance, userId, invoiceId, wallet.getBalance());
        return true;
    }

    /**
     * Expire whatever monthly credits are left — when the subscription ends.
     * Purchased credits are untouched.
     *
     * @return the credits expired
     */
    @Transactional
    public int expireSubscriptionCredits(UUID userId, String description) {
        CreditBalance wallet = lockOrCreateWallet(userId);
        int expired = expireMonthly(wallet, userId, description);
        balanceRepository.save(wallet);
        return expired;
    }

    /**
     * Reclaim credits a refunded or charged-back credit pack granted. Only
     * purchased credits that are still unspent are taken — never monthly credits
     * and never below zero. The part already spent is recorded as the row's
     * {@code shortfall} (and logged for review) rather than becoming a debt.
     *
     * @return the credits actually reclaimed
     */
    @Transactional
    public int clawbackPurchased(UUID userId, int amount, String stripeReference, String description) {
        if (amount <= 0) {
            return 0;
        }
        CreditBalance wallet = lockOrCreateWallet(userId);
        int reclaim = Math.min(amount, Math.max(0, wallet.purchasedCredits()));
        wallet.setBalance(wallet.getBalance() - reclaim);
        normalize(wallet);
        balanceRepository.save(wallet);
        return recordClawback(wallet, userId, amount, reclaim, 0, stripeReference, description);
    }

    /**
     * Reclaim credits a refunded or charged-back subscription payment granted —
     * only from the monthly credits still unspent, never purchased credits and
     * never below zero. The rest is recorded as the row's {@code shortfall}.
     *
     * @return the credits actually reclaimed
     */
    @Transactional
    public int clawbackSubscription(UUID userId, int amount, String stripeReference, String description) {
        if (amount <= 0) {
            return 0;
        }
        CreditBalance wallet = lockOrCreateWallet(userId);
        int reclaim = Math.min(amount, wallet.getSubscriptionCredits());
        wallet.setBalance(wallet.getBalance() - reclaim);
        wallet.setSubscriptionCredits(wallet.getSubscriptionCredits() - reclaim);
        normalize(wallet);
        balanceRepository.save(wallet);
        return recordClawback(wallet, userId, amount, reclaim, -reclaim, stripeReference, description);
    }

    /**
     * How much of a Stripe charge's credits has already been settled by
     * clawbacks — reclaimed plus recorded as unrecoverable — so repeated refund
     * and dispute events for the same charge never reclaim twice.
     */
    @Transactional(readOnly = true)
    public int clawedForReference(String stripeReference) {
        if (stripeReference == null || stripeReference.isBlank()) {
            return 0;
        }
        return transactionRepository.findByStripeReference(stripeReference).stream()
                .filter(t -> t.getType() == CreditTransactionType.REFUND_CLAWBACK)
                .mapToInt(t -> -t.getAmount() + t.getShortfall()) // stored negative → positive
                .sum();
    }

    /** The SUBSCRIPTION_GRANT row for a Stripe invoice, if its allowance was granted. */
    @Transactional(readOnly = true)
    public java.util.Optional<CreditTransaction> subscriptionGrantFor(String invoiceId) {
        if (invoiceId == null || invoiceId.isBlank()) {
            return java.util.Optional.empty();
        }
        return transactionRepository.findByStripeReference(invoiceId).stream()
                .filter(t -> t.getType() == CreditTransactionType.SUBSCRIPTION_GRANT)
                .findFirst();
    }

    /** Whether {@code invoiceId} is the user's most recent subscription grant (the current period). */
    @Transactional(readOnly = true)
    public boolean isCurrentSubscriptionGrant(UUID userId, String invoiceId) {
        return transactionRepository
                .findFirstByUserIdAndTypeOrderByCreatedAtDesc(userId, CreditTransactionType.SUBSCRIPTION_GRANT)
                .map(t -> invoiceId != null && invoiceId.equals(t.getStripeReference()))
                .orElse(false);
    }

    // ------------------------------------------------------------------------

    private void refund(UUID userId, int amount, int fromSubscription, CreditTransactionType type,
                        UUID ebookId, String description) {
        requirePositive(amount);
        int toSubscription = Math.max(0, Math.min(fromSubscription, amount));
        CreditBalance wallet = lockOrCreateWallet(userId);
        wallet.setBalance(wallet.getBalance() + amount);
        wallet.setSubscriptionCredits(wallet.getSubscriptionCredits() + toSubscription);
        normalize(wallet);
        balanceRepository.save(wallet);
        writeLedger(userId, type, amount, toSubscription, wallet.getBalance(), ebookId, null, null, 0, description);
        log.info("Returned {} credits ({} monthly) to user {} ({}), balance now {}",
                amount, toSubscription, userId, type, wallet.getBalance());
    }

    /** Take {@code amount} from the wallet, monthly credits first. Returns the monthly part. */
    private static int debit(CreditBalance wallet, int amount) {
        int fromSubscription = Math.min(Math.max(0, wallet.getSubscriptionCredits()), amount);
        wallet.setSubscriptionCredits(wallet.getSubscriptionCredits() - fromSubscription);
        wallet.setBalance(wallet.getBalance() - amount);
        normalize(wallet);
        return fromSubscription;
    }

    private int expireMonthly(CreditBalance wallet, UUID userId, String description) {
        int leftover = Math.max(0, wallet.getSubscriptionCredits());
        if (leftover == 0) {
            return 0;
        }
        wallet.setBalance(wallet.getBalance() - leftover);
        wallet.setSubscriptionCredits(0);
        normalize(wallet);
        writeLedger(userId, CreditTransactionType.SUBSCRIPTION_EXPIRY, -leftover, -leftover, wallet.getBalance(),
                null, null, null, 0, description);
        log.info("Expired {} monthly credits for user {}, balance now {}", leftover, userId, wallet.getBalance());
        return leftover;
    }

    private int recordClawback(CreditBalance wallet, UUID userId, int amount, int reclaimed, int subscriptionAmount,
                               String stripeReference, String description) {
        int shortfall = amount - reclaimed;
        writeLedger(userId, CreditTransactionType.REFUND_CLAWBACK, -reclaimed, subscriptionAmount,
                wallet.getBalance(), null, null, stripeReference, shortfall,
                shortfall > 0 ? description + " (" + shortfall + " already used, not reclaimed)" : description);
        if (shortfall > 0) {
            log.warn("Clawback for {} (user {}): {} of {} credits were already spent and were not reclaimed "
                    + "— review manually if this looks like abuse", stripeReference, userId, shortfall, amount);
        }
        log.info("Reclaimed {} credits from user {} (ref {}), balance now {}",
                reclaimed, userId, stripeReference, wallet.getBalance());
        return reclaimed;
    }

    /** Keep {@code 0 <= subscriptionCredits <= max(0, balance)}. */
    static void normalize(CreditBalance wallet) {
        int cap = Math.max(0, wallet.getBalance());
        wallet.setSubscriptionCredits(Math.max(0, Math.min(wallet.getSubscriptionCredits(), cap)));
    }

    /**
     * Return the wallet row locked FOR UPDATE, creating it (with the optional
     * signup bonus) on first use. A wallet still in the legacy economy is never
     * spent from or granted to before {@code LegacyCreditMigration} converts it —
     * otherwise its page credits would silently be read as current credits.
     */
    private CreditBalance lockOrCreateWallet(UUID userId) {
        CreditBalance wallet = balanceRepository.findForUpdate(userId)
                .orElseGet(() -> createWallet(userId));
        if (wallet.getEconomyVersion() < CreditBalance.ECONOMY_CURRENT) {
            throw new IllegalStateException("Your credit balance is being converted to the new credit system. "
                    + "Please try again in a few minutes.");
        }
        return wallet;
    }

    /** What a not-yet-converted legacy wallet will hold once converted (display only). */
    private Wallet legacyPreview(CreditBalance b) {
        int balance = b.getBalance() * creditProperties.resolveLegacyCreditValue();
        return new Wallet(balance, 0, balance);
    }

    private CreditBalance createWallet(UUID userId) {
        int bonus = Math.max(0, creditProperties.getSignupBonus());
        try {
            CreditBalance wallet = balanceRepository.saveAndFlush(
                    CreditBalance.builder().userId(userId).balance(bonus)
                            .economyVersion(CreditBalance.ECONOMY_CURRENT).build());
            if (bonus > 0) {
                writeLedger(userId, CreditTransactionType.SIGNUP_BONUS, bonus, 0, bonus, null, null, null, 0,
                        "Signup bonus");
            }
            return wallet;
        } catch (DataIntegrityViolationException e) {
            // Created concurrently by another request — re-read under the lock.
            log.debug("Wallet for {} already created concurrently", userId);
            return balanceRepository.findForUpdate(userId)
                    .orElseThrow(() -> new IllegalStateException("Wallet vanished after concurrent create for " + userId));
        }
    }

    private void writeLedger(UUID userId, CreditTransactionType type, int amount, int subscriptionAmount,
                             int balanceAfter, UUID ebookId, UUID paymentOrderId, String stripeReference,
                             int shortfall, String description) {
        transactionRepository.save(CreditTransaction.builder()
                .userId(userId)
                .type(type)
                .amount(amount)
                .subscriptionAmount(subscriptionAmount)
                .balanceAfter(balanceAfter)
                .ebookId(ebookId)
                .paymentOrderId(paymentOrderId)
                .stripeReference(stripeReference)
                .shortfall(shortfall)
                .description(description)
                .build());
    }

    private void requirePositive(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
    }
}
