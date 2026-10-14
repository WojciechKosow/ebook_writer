package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StoreCreateRequest(
        @NotBlank
        @Pattern(regexp = "^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$",
                message = "Use 3–40 lowercase letters, digits or hyphens (not at the start or end)")
        String slug,
        @NotBlank @Size(max = 80) String name,
        @Size(max = 2000) String description
) {
}
