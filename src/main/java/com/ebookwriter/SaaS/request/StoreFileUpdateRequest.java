package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.Size;

/** An extra's details for buyers. Null fields are left unchanged; blank clears. */
public record StoreFileUpdateRequest(
        @Size(max = 120) String label,
        @Size(max = 300) String note,
        Boolean accountOnly
) {
}
