package com.ebookwriter.SaaS.dto;

import java.util.List;

public record CreditBalanceResponse(
        int balance,
        List<CreditTransactionDTO> transactions
) {
}
