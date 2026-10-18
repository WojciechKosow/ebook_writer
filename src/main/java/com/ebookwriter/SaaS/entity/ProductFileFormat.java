package com.ebookwriter.SaaS.entity;

/**
 * The downloadable file formats a store product may carry. The content type and
 * extension served to buyers come from here — never from the uploading client.
 */
public enum ProductFileFormat {
    PDF("pdf", "application/pdf"),
    EPUB("epub", "application/epub+zip"),
    ZIP("zip", "application/zip");

    private final String extension;
    private final String contentType;

    ProductFileFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }
}
