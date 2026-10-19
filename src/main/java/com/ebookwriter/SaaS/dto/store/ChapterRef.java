package com.ebookwriter.SaaS.dto.store;

/** A chapter of a product's ebook, for the page editor's free-preview picker. */
public record ChapterRef(int number, String title, boolean hasText) {
}
