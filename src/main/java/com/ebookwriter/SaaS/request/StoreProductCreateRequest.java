package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Put one of the author's finished ebooks on sale. Title defaults to the ebook's title. */
public record StoreProductCreateRequest(
        @NotNull UUID ebookId,
        @NotNull Long priceCents,
        @Size(max = 200) String title,
        @Size(max = 2000) String description
) {
}
