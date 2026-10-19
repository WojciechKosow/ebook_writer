package com.ebookwriter.SaaS.dto.store;

import java.util.UUID;

/**
 * One change in the store's draft that buyers don't see yet. {@code kind} is
 * profile | theme | order | product | file; {@code field} names what changed
 * (e.g. "priceCents"); {@code before}/{@code after} are the live and the draft
 * value as text (null = empty). The dashboard words them for the author.
 */
public record PendingChange(
        String kind,
        UUID productId,
        UUID fileId,
        String field,
        String before,
        String after
) {
}
