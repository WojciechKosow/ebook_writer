package com.ebookwriter.SaaS.service.ebook;

/**
 * What a render includes besides the chapters: the cover page and the table of
 * contents. Every automatic render prints both; the editor's Export lets the
 * author leave either out.
 */
public record RenderOptions(boolean includeCover, boolean includeToc) {

    /** Cover, contents and chapters — the default for every render. */
    public static final RenderOptions FULL = new RenderOptions(true, true);
}
