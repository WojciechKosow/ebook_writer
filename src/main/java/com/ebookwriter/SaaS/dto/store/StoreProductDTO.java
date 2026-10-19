package com.ebookwriter.SaaS.dto.store;

import java.util.List;
import java.util.UUID;

public record StoreProductDTO(
        UUID id,
        /** The Scrivetta ebook whose PDF is delivered, or null for a standalone product. */
        UUID ebookId,
        String title,
        String description,
        String highlights,
        long priceCents,
        String currency,
        boolean active,
        int sortOrder,
        /** Pages of the finished PDF (0 if unknown). */
        int pageCount,
        /** Relative API path of the cover image (own cover, else first page of the PDF), or null. */
        String coverUrl,
        /** Whether the author uploaded their own cover. */
        boolean customCover,
        /** The uploaded downloadable files (current versions). */
        List<ProductFileDTO> files,
        /** Whether buyers would get anything (the ebook's PDF or at least one file). */
        boolean deliverable,
        /** The product page design (JSON), null for defaults. */
        String pageSettings,
        /** The ebook's chapters (empty for a standalone product). */
        List<ChapterRef> chapters,
        String subtitle,
        String language,
        /** Shown to buyers right now (the live store; the fields above are the draft). */
        boolean liveActive,
        /** The price buyers pay right now. */
        long livePriceCents
) {
}
