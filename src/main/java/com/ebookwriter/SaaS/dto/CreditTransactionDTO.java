package com.ebookwriter.SaaS.dto;

import com.ebookwriter.SaaS.entity.CreditTransaction;
import com.ebookwriter.SaaS.entity.CreditTransactionType;

import java.time.LocalDateTime;
import java.util.UUID;

public record CreditTransactionDTO(
        CreditTransactionType type,
        int amount,
        int balanceAfter,
        String description,
        UUID ebookId,
        LocalDateTime createdAt
) {
    public static CreditTransactionDTO from(CreditTransaction t) {
        return new CreditTransactionDTO(
                t.getType(), t.getAmount(), t.getBalanceAfter(),
                t.getDescription(), t.getEbookId(), t.getCreatedAt());
    }
}
