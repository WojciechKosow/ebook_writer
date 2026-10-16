package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The author's store profile. Links must be https:// (they are rendered as
 * links on the public store, so no other scheme is accepted).
 */
public record StoreUpdateRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 2000) String description,
        @Size(max = 160) String headline,
        @Size(max = 80) String authorName,
        @Email(message = "Enter a valid email address") @Size(max = 254) String contactEmail,
        @Size(max = 500) @Pattern(regexp = HTTPS, message = "Use a full https:// link") String instagramUrl,
        @Size(max = 500) @Pattern(regexp = HTTPS, message = "Use a full https:// link") String tiktokUrl,
        @Size(max = 500) @Pattern(regexp = HTTPS, message = "Use a full https:// link") String websiteUrl
) {
    /** Empty, or an https URL without spaces. */
    static final String HTTPS = "^$|^https://[^\\s]+$";
}
