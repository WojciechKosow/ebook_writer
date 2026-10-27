package com.ebookwriter.SaaS.dto;

import java.util.List;

/**
 * The user's credits: the spendable total and its two parts — this billing
 * period's monthly PRO credits (used first, replaced at renewal) and purchased
 * credits (never expire; negative only while a small generation overdraft is
 * outstanding) — plus the recent ledger.
 */
public record CreditBalanceResponse(
        int balance,
        int subscriptionCredits,
        int purchasedCredits,
        List<CreditTransactionDTO> transactions
) {
}
