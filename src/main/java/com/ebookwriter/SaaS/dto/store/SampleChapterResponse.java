package com.ebookwriter.SaaS.dto.store;

/** A chapter buyers can read for free on the product page. {@code html} is sanitised (no scripts, no images). */
public record SampleChapterResponse(int number, String title, String html) {
}
