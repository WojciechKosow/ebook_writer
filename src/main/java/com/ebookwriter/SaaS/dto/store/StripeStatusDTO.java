package com.ebookwriter.SaaS.dto.store;

import java.time.LocalDateTime;
import java.util.List;

public record StripeStatusDTO(
        StripeConnectState state,
        boolean chargesEnabled,
        boolean detailsSubmitted,
        List<String> currentlyDue,
        LocalDateTime syncedAt
) {
}
