package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StoreUpdateRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 2000) String description
) {
}
