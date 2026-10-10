package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.EbookChapter;

import java.util.List;

/**
 * The hard line between what the pipeline keeps for itself and what the reader
 * sees. A chapter carries internal fields — the planner's brief
 * ({@code description}), the summary and topic registry written for later
 * chapters, generation errors, the blueprint link — next to its reader-facing
 * ones (title, reader subtitle, body). Everything that composes the printed
 * book ({@link EbookHtmlBuilder}, {@link DocumentComposer}) works on the copies
 * made here, which contain <b>only</b> the reader-facing fields, so no brief,
 * instruction or note can be printed however the layout code evolves.
 *
 * <p>The page-size estimate ({@code approxPages}) is kept: it steers layout
 * decisions (opener pages) and is never printed.
 */
final class ReaderView {

    private ReaderView() {
    }

    /** Detached, reader-only copies of {@code chapters}, in the same order. */
    static List<EbookChapter> of(List<EbookChapter> chapters) {
        return chapters.stream().map(ReaderView::of).toList();
    }

    static EbookChapter of(EbookChapter c) {
        return EbookChapter.builder()
                .id(c.getId())
                .chapterNumber(c.getChapterNumber())
                .title(c.getTitle())
                .readerSubtitle(c.getReaderSubtitle())
                .content(c.getContent())
                .approxPages(c.getApproxPages())
                .status(c.getStatus())
                .contentSource(c.getContentSource())
                .build();
    }
}
